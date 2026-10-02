package dev.stagecraft.jenkins

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import dev.stagecraft.model.JobKind
import dev.stagecraft.model.JobNode
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val MULTIBRANCH_CLASS = "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"
internal const val WORKFLOW_CLASS = "org.jenkinsci.plugins.workflow.job.WorkflowJob"
internal const val FOLDER_CLASS = "com.cloudbees.hudson.plugins.folder.Folder"

/** A [JobNode] whose display identity is derived the way [JenkinsClient] derives it. */
internal fun testJob(rawPath: List<String>, className: String, colour: String? = null): JobNode = JobNode(
    name = rawPath.last(),
    displayName = JenkinsUrls.decodeSegment(rawPath.last()),
    fullName = rawPath.joinToString("/") { JenkinsUrls.decodeSegment(it) },
    rawPath = rawPath,
    className = className,
    kind = JobKind.fromClass(className),
    url = "http://localhost:18080/" + JenkinsUrls.jobPath(rawPath),
    depth = rawPath.size,
    colour = colour,
)

class JobIndexTest {

    private val base = "http://localhost:18080/"
    private val rootPrefix = base + "api/json"

    private fun client(fake: FakeTransport) =
        JenkinsClient(base, JenkinsAuth(JenkinsCredential.ApiToken("admin", "token")), fake)

    private fun tempDir(): File = Files.createTempDirectory("stagecraft-index").toFile()

    /** One multibranch job with two branch children: svc, main, ticking — three nodes. */
    private fun rootTree(): String =
        """{"jobs":[{"name":"svc","_class":"$MULTIBRANCH_CLASS","jobs":[
            {"name":"main","_class":"$WORKFLOW_CLASS"},
            {"name":"ticking","_class":"$WORKFLOW_CLASS"}]}]}"""

    // ---------------------------------------------------------------- fetching

    @Test
    fun `fetch caps the tree and counts what it saw`() {
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.of(rootTree()))

        val index = JobIndex.fetch(client(fake), maxJobs = 2)

        assertEquals(2, index.jobs.size)
        assertEquals(3, index.totalSeen)
        assertTrue(index.truncated)
    }

    @Test
    fun `fetch under the cap is not truncated`() {
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.of(rootTree()))

        val index = JobIndex.fetch(client(fake))

        assertEquals(3, index.jobs.size)
        assertEquals(3, index.totalSeen)
        assertTrue(!index.truncated)
    }

    @Test
    fun `a refresh carries the pins over`() {
        val fake = FakeTransport()
        fake.onGetPrefix(rootPrefix, Fixtures.of(rootTree()))
        val previous = JobIndex.of(emptyList(), serverUrl = base)
            .withPin("github.com/corp/svc", testJob(listOf("svc"), MULTIBRANCH_CLASS))

        val refreshed = JobIndex.fetch(client(fake), previous = previous)

        assertEquals("svc", refreshed.pinFor("github.com/corp/svc")?.displayName)
        assertNull(JobIndex.fetch(client(fake)).pinFor("github.com/corp/svc"))
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun `rank grades the evidence`() {
        val index = JobIndex.of(
            listOf(
                testJob(listOf("ui", "stagecraft"), MULTIBRANCH_CLASS),
                testJob(listOf("stagecraft"), FOLDER_CLASS),
                testJob(listOf("stagecraft-ui"), WORKFLOW_CLASS),
                testJob(listOf("corp-app"), WORKFLOW_CLASS),
            ),
        )
        val remote = RemoteInfo.parse("git@github.com:corp/stagecraft.git")!!

        val ranked = index.rank(remote)

        assertEquals(listOf(100, 80, 60, 50), ranked.map { it.tier })
        assertEquals(JobKind.MULTIBRANCH, ranked.first().job.kind)
        assertTrue(ranked.first().reason.contains("multibranch"))
        assertTrue(ranked.last().reason.contains("corp"))
    }

    @Test
    fun `ties break by depth then name`() {
        val index = JobIndex.of(
            listOf(
                testJob(listOf("deep", "stagecraft-b"), WORKFLOW_CLASS),
                testJob(listOf("stagecraft-b"), WORKFLOW_CLASS),
                testJob(listOf("stagecraft-a"), WORKFLOW_CLASS),
            ),
        )
        val remote = RemoteInfo.parse("git@github.com:corp/stagecraft.git")!!

        val ranked = index.rank(remote)

        assertEquals(
            listOf("stagecraft-a", "stagecraft-b", "stagecraft-b"),
            ranked.map { it.job.displayName },
        )
        assertEquals(1, ranked[0].job.depth)
        assertEquals(2, ranked[2].job.depth)
    }

    @Test
    fun `a child is found by its decoded branch name`() {
        val parent = testJob(listOf("svc"), MULTIBRANCH_CLASS)
        val child = testJob(listOf("svc", "feature%2FORD-214"), WORKFLOW_CLASS)
        val index = JobIndex.of(listOf(parent, child))

        assertEquals(child, index.childNamed(parent, "feature/ORD-214"))
        assertNull(index.childNamed(parent, "feature/nope"))
    }

    // ---------------------------------------------------------------- the cache

    @Test
    fun `the cache round-trips everything`() {
        val dir = tempDir()
        try {
            val index = JobIndex.of(
                listOf(testJob(listOf("svc", "main"), WORKFLOW_CLASS), testJob(listOf("svc"), MULTIBRANCH_CLASS)),
                truncated = true,
                totalSeen = 5000,
                serverUrl = base,
                fetchedAtMillis = 1700000000000,
            ).withPin("github.com/corp/svc", testJob(listOf("svc"), MULTIBRANCH_CLASS))

            JobIndex.save(dir, index)
            val loaded = JobIndex.load(dir, base)!!

            assertEquals(index.jobs, loaded.jobs)
            assertEquals(5000, loaded.totalSeen)
            assertTrue(loaded.truncated)
            assertEquals(1700000000000, loaded.fetchedAtMillis)
            assertEquals(base.trimEnd('/'), loaded.serverUrl)
            // Keys are normalised: stored lower-case, asked for upper-case.
            assertEquals("svc", loaded.pinFor("GITHUB.COM/CORP/SVC")?.displayName)
            assertEquals(setOf("github.com/corp/svc"), loaded.pinnedKeys)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a corrupt cache is a miss, not a crash`() {
        val dir = tempDir()
        try {
            JobIndex.cacheFile(dir, base).apply { parentFile.mkdirs() }.writeText("{not json")

            assertNull(JobIndex.load(dir, base))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a cache from another server is not this server's cache`() {
        val dir = tempDir()
        try {
            JobIndex.save(dir, JobIndex.of(emptyList(), serverUrl = base))

            assertNull(JobIndex.load(dir, "http://elsewhere:8080/"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `loadOrFetch prefers the cache and fills it when empty`() {
        val dir = tempDir()
        try {
            val fake = FakeTransport()
            fake.onGetPrefix(rootPrefix, Fixtures.of(rootTree()))

            val first = JobIndex.loadOrFetch(client(fake), dir, nowMillis = 42)
            assertEquals(42, first.fetchedAtMillis)
            assertEquals(1, fake.count)

            val second = JobIndex.loadOrFetch(client(fake), dir, nowMillis = 99)
            assertEquals(42, second.fetchedAtMillis)
            assertEquals(1, fake.count)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- the cap under load

    @Test
    fun `a thousand-job index matches in under half a second`() {
        val index = JobIndex.of(
            (0 until 1000).map { i -> testJob(listOf("team-${i % 40}", "svc-$i"), MULTIBRANCH_CLASS) },
        )
        val remoteUrl = "https://github.com/corp/svc-777.git"

        val elapsed = measureTimeMillis {
            val match = RemoteMatcher(index).match(remoteUrl)
            assertTrue(match is RemoteMatcher.MatchResult.Matched)
            assertEquals("svc-777", match.job.displayName)
        }

        assertTrue(elapsed < 500, "matching took $elapsed ms; the exit criterion is 500")
    }
}
