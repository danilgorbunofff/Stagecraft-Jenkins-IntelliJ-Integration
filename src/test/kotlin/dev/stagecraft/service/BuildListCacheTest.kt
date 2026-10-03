package dev.stagecraft.service

import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.BuildStatus
import dev.stagecraft.model.JobKind
import dev.stagecraft.model.JobNode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildListCacheTest {

    private val base = "http://localhost:18080"

    private fun tempDir(): File = Files.createTempDirectory("stagecraft-buildcache").toFile()

    private fun job() = JobNode(
        name = "main",
        displayName = "main",
        fullName = "svc/main",
        rawPath = listOf("svc", "main"),
        className = "org.jenkinsci.plugins.workflow.job.WorkflowJob",
        kind = JobKind.WORKFLOW,
        url = "$base/job/svc/job/main/",
        depth = 2,
    )

    private fun entry(key: String) = CachedBuildList(
        serverUrl = base,
        key = key,
        job = job(),
        builds = listOf(
            BuildRef(
                jobFullName = "svc/main",
                jobRawPath = listOf("svc", "main"),
                number = 3,
                url = "$base/job/svc/job/main/3/",
                status = BuildStatus.FAILURE,
                timestampMillis = 1_000L,
                durationMillis = 2_000L,
            ),
        ),
        how = "branch \"main\" - child of \"svc\"",
        versionWarning = "Jenkins 2.164.3 is below the supported floor",
        fetchedAtMillis = 5_000L,
    )

    private fun key(branch: String) = BuildListCache.key(base, listOf("svc", "main"), branch, null)

    @Test
    fun `a cached list round trips through disk unchanged`() {
        val dir = tempDir()
        try {
            val saved = entry(key("main"))
            BuildListCache.save(dir, saved)

            val loaded = BuildListCache.load(dir, base, saved.key)

            assertNotNull(loaded)
            assertEquals(saved, loaded)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a key for another branch is a miss, not a wrong answer`() {
        val dir = tempDir()
        try {
            BuildListCache.save(dir, entry(key("main")))

            assertNull(BuildListCache.load(dir, base, key("feature-x")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a list cached for another server is a miss`() {
        val dir = tempDir()
        try {
            val saved = entry(key("main"))
            BuildListCache.save(dir, saved)

            assertNull(BuildListCache.load(dir, "http://other:8080", saved.key))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a corrupted cache file is a miss, not a crash`() {
        val dir = tempDir()
        try {
            val file = BuildListCache.cacheFile(dir, key("main"))
            file.parentFile.mkdirs()
            file.writeText("{ this is not json")

            assertNull(BuildListCache.load(dir, base, key("main")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `age is clamped at zero so clock skew never reports a negative age`() {
        val entry = entry(key("main"))

        assertEquals(0L, BuildListCache.ageMillis(entry, nowMillis = 1_000L))
        assertEquals(1_500L, BuildListCache.ageMillis(entry, nowMillis = 6_500L))
        assertTrue(BuildListCache.ageMillis(entry, 6_500L) > 0)
    }
}
