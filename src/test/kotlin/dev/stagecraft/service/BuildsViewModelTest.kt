package dev.stagecraft.service

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import dev.stagecraft.jenkins.JenkinsAuth
import dev.stagecraft.jenkins.JenkinsClient
import dev.stagecraft.jenkins.JenkinsCredential
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.HttpTransport
import dev.stagecraft.jenkins.JobIndex
import dev.stagecraft.jenkins.FOLDER_CLASS
import dev.stagecraft.jenkins.MULTIBRANCH_CLASS
import dev.stagecraft.jenkins.WORKFLOW_CLASS
import dev.stagecraft.jenkins.testJob
import dev.stagecraft.model.BuildStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class BuildsViewModelTest {

    private val base = "http://localhost:18080/"

    // --------------------------------------------------------------- helpers

    private fun tempDir(): File = Files.createTempDirectory("stagecraft-svc").toFile()

    private fun transport(): FakeTransport = FakeTransport()

    /** The client factory every test uses; [fake] is shared so requests can be counted. */
    private fun factory(fake: FakeTransport): JenkinsClientFactory =
        JenkinsClientFactory { baseUrl, user, token ->
            JenkinsClient(baseUrl, JenkinsAuth(JenkinsCredential.ApiToken(user, token)), fake)
        }

    private fun loader(
        credentials: CredentialStore = InMemoryCredentialStore().apply { save(base, "admin", "token") },
        fake: FakeTransport = transport(),
        cacheDir: File? = null,
    ): Pair<DefaultBuildsLoader, FakeTransport> =
        DefaultBuildsLoader(credentials, factory(fake), cacheDir) to fake

    /** An index with svc (multibranch) + its main branch child, cached for [base]. */
    private fun seedCache(dir: File) {
        JobIndex.save(
            dir,
            JobIndex.of(
                listOf(
                    testJob(listOf("svc"), MULTIBRANCH_CLASS),
                    testJob(listOf("svc", "main"), WORKFLOW_CLASS),
                    testJob(listOf("unrelated"), FOLDER_CLASS),
                ),
                serverUrl = base,
            ),
        )
    }

    private fun configured() = StagecraftState(serverUrl = base, user = "admin")

    private fun ctx(
        remote: String? = "git@github.com:corp/svc.git",
        branch: String? = "main",
        pr: Int? = null,
    ) = BranchContext(remote, branch, pr)

    private fun FakeTransport.routeMe() {
        // The tree parameter is URL-encoded by the client, so route by prefix.
        onGetPrefix(base + "me/api", Fixtures.json("01.me.json"))
    }

    // --------------------------------------------------------------- states

    @Test
    fun `unconfigured loads nothing and says so`() {
        val (model, fake) = loader(credentials = InMemoryCredentialStore(), cacheDir = null)

        val state = model.load(StagecraftState(), ctx())

        assertEquals(ToolWindowState.Unconfigured, state)
        assertEquals(0, fake.count)
    }

    @Test
    fun `a missing token explains where to put one and retrying cannot help`() {
        val (model, fake) = loader(credentials = InMemoryCredentialStore(), cacheDir = null)

        val state = model.load(configured(), ctx())

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("token"), state.reason)
        assertTrue(state.reason.contains("Settings"), state.reason)
        assertTrue(!state.retryable)
        assertEquals(0, fake.count)
    }

    @Test
    fun `a missing git remote explains what Stagecraft needs`() {
        val (model, fake) = loader(cacheDir = null)

        val state = model.load(configured(), ctx(remote = null))

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("git remote"), state.reason)
        assertTrue(state.retryable)
        assertEquals(0, fake.count)
    }

    @Test
    fun `branch builds load newest first without being told a job name`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertEquals(1, state.builds.size)
            val build = state.builds[0]
            assertEquals(1, build.number)
            assertEquals(BuildStatus.FAILURE, build.status)
            assertEquals("http://localhost:18080/job/multibranch-demo/job/main/1/", build.url)
            assertTrue(state.how.contains("main"), state.how)
            // Exactly two calls, in this order: the identity check, then the branch's builds. The
            // warm index is read from disk, so the tree is never refetched (§9.2).
            assertEquals(2, fake.count)
            assertTrue(fake.urls()[0].startsWith(base + "me/api"), fake.urls().toString())
            assertTrue(
                fake.urls()[1].startsWith(base + "job/svc/job/main/api/json?tree=builds"),
                fake.urls().toString(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an empty job says so instead of pretending it is ready`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.of("""{"builds":[]}"""))
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Empty, "was $state")
            assertEquals("main", state.job.displayName)
            assertTrue(state.how.contains("main"), state.how)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a dead server is a retryable failure, not a crash`() {
        val model = DefaultBuildsLoader(
            InMemoryCredentialStore().apply { save(base, "admin", "token") },
            JenkinsClientFactory { baseUrl, user, token ->
                JenkinsClient(
                    baseUrl,
                    JenkinsAuth(JenkinsCredential.ApiToken(user, token)),
                    HttpTransport { throw JenkinsException.Transport("connection refused") },
                )
            },
            cacheDir = null,
        )

        val state = model.load(configured(), ctx())

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.retryable)
        assertTrue(state.reason.isNotBlank())
    }

    @Test
    fun `an anonymous answer is named at connection time`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.onGetPrefix(
                base + "me/api",
                Fixtures.of("""{"id":"anonymous","fullName":"Anonymous"}"""),
            )
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Failed, "was $state")
            assertTrue(state.reason.contains("anonymous"), state.reason)
            // Credentials are verified before anything else; the index must not be fetched.
            assertTrue(fake.urls().none { it.contains("api/json?tree=") && !it.contains("/me/") }, fake.urls().toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a 401 is a readable failure`() {
        val (model, fake) = loader(cacheDir = null)
        fake.onGetPrefix(
            base + "me/api",
            Fixtures.empty(401, mapOf("WWW-Authenticate" to "Basic realm=\"Jenkins\"")),
        )

        val state = model.load(configured(), ctx())

        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("401"), state.reason)
        assertTrue(state.retryable)
    }

    @Test
    fun `a pinned job wins over the matcher`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/othername/job/main/api", Fixtures.json("06.branchbuilds.json"))
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(
                configured().copy(pinnedJobs = mapOf("github.com/corp/svc" to "othername")),
                ctx(),
            )

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertTrue(state.how.contains("pinned"), state.how)
            // The pin names a job the index has never held, so the branch child is built from the
            // pin rather than silently replaced by the closest name match (§9.4 step 5).
            assertTrue(
                fake.urls().any { it.startsWith(base + "job/othername/job/main/api/json?tree=builds") },
                fake.urls().toString(),
            )
            assertEquals("othername/main", state.builds[0].jobFullName)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a version below the floor is carried to the UI as a warning`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.onGetPrefix(
                base + "me/api",
                Fixtures.json("01.me.json", headers = mapOf("X-Jenkins" to "2.164.3")),
            )
            fake.onGetPrefix(
                base + "job/svc/job/main/api",
                Fixtures.json("06.branchbuilds.json", headers = mapOf("X-Jenkins" to "2.164.3")),
            )
            val (model, _) = loader(fake = fake, cacheDir = dir)

            val state = model.load(configured(), ctx())

            assertTrue(state is ToolWindowState.Ready, "was $state")
            assertTrue(!state.versionWarning.isNullOrBlank())
        } finally {
            dir.deleteRecursively()
        }
    }

    // --------------------------------------------------------------- first paint

    @Test
    fun `first paint with a warm cache names the job and costs no network`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val (model, fake) = loader(cacheDir = dir)

            var painted: ToolWindowState? = null
            val elapsed = measureTimeMillis { painted = model.snapshotForFirstPaint(configured(), ctx()) }
            val state = painted ?: fail("no state was painted")

            assertTrue(state is ToolWindowState.Loading, "was $state")
            assertTrue(state.hint?.contains("main") == true, state.hint)
            assertEquals(0, fake.count)
            assertTrue(elapsed < 200, "first paint took $elapsed ms; the exit criterion is 200")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `first paint with no cache shows an honest loading and costs no network`() {
        val (model, fake) = loader(cacheDir = null)

        val state = model.snapshotForFirstPaint(configured(), ctx())

        assertEquals(ToolWindowState.Loading(null), state)
        assertEquals(0, fake.count)
    }

    @Test
    fun `first paint before settings lands on Unconfigured`() {
        val (model, fake) = loader(cacheDir = null)

        val state = model.snapshotForFirstPaint(StagecraftState(), ctx())

        assertEquals(ToolWindowState.Unconfigured, state)
        assertEquals(0, fake.count)
    }

    // --------------------------------------------------------------- the model

    @Test
    fun `the model paints first, then loads, then reports ready`() {
        val dir = tempDir()
        try {
            seedCache(dir)
            val fake = transport()
            fake.routeMe()
            fake.onGetPrefix(base + "job/svc/job/main/api", Fixtures.json("06.branchbuilds.json"))
            val seen = ArrayList<ToolWindowState>()
            val model = BuildsViewModel(
                DefaultBuildsLoader(
                    InMemoryCredentialStore().apply { save(base, "admin", "token") },
                    factory(fake),
                    dir,
                ),
                Executor { it.run() },
            )
            model.onState = { seen += it }

            model.paintFirst(configured(), ctx())
            model.refresh(configured(), ctx())

            assertTrue(seen.first() is ToolWindowState.Loading, seen.toString())
            assertTrue(seen.last() is ToolWindowState.Ready, seen.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `refreshing an unconfigured project never flashes a spinner`() {
        val fake = transport()
        val seen = ArrayList<ToolWindowState>()
        val model = BuildsViewModel(
            DefaultBuildsLoader(InMemoryCredentialStore(), factory(fake), null),
            Executor { it.run() },
        )
        model.onState = { seen += it }

        model.refresh(StagecraftState(), ctx())

        assertTrue(seen.none { it is ToolWindowState.Loading }, seen.toString())
        assertEquals(ToolWindowState.Unconfigured, model.state)
    }

    @Test
    fun `a failed load is carried through the model`() {
        val fake = transport()
        // Nothing routed: the /me call errors out as an unrouted transport failure.
        val model = BuildsViewModel(
            DefaultBuildsLoader(
                InMemoryCredentialStore().apply { save(base, "admin", "token") },
                factory(fake),
                null,
            ),
            Executor { it.run() },
        )

        model.refresh(configured(), ctx(remote = null))

        val state = model.state
        assertTrue(state is ToolWindowState.Failed, "was $state")
        assertTrue(state.reason.contains("git remote"), state.reason)
    }

    // --------------------------------------------------------------- the settings bean

    @Test
    fun `a fresh state bean is empty and not configured`() {
        val state = StagecraftState()

        assertTrue(!state.isConfigured)
        assertTrue(!state.trustCertificate)
        assertTrue(state.useProxy)
        assertEquals(emptyMap<String, String>(), state.pinnedJobs)
    }

    // --------------------------------------------------------------- the poller

    @Test
    fun `the poller repeats until cancelled and then stays silent`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            var fires = 0
            val poller = BuildPoller(scheduler, periodMillis = 10)
            val handle = poller.start { fires++ }

            Thread.sleep(250)
            assertTrue(fires >= 2, "poller fired $fires times in 250 ms")
            handle.cancel()
            val afterCancel = fires

            Thread.sleep(200)
            assertEquals(afterCancel, fires)
        } finally {
            scheduler.shutdownNow()
        }
    }
}
