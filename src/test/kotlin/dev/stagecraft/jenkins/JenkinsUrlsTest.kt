package dev.stagecraft.jenkins

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JenkinsUrlsTest {

    @Test
    fun `a bare host gets https and a trailing slash`() {
        assertEquals("https://ci.example.com/", JenkinsUrls.normalizeBase("ci.example.com"))
        assertEquals("https://ci.example.com/", JenkinsUrls.normalizeBase("  ci.example.com  "))
        assertEquals("https://ci.example.com/", JenkinsUrls.normalizeBase("https://ci.example.com"))
        assertEquals("http://localhost:18080/", JenkinsUrls.normalizeBase("http://localhost:18080"))
        assertEquals("http://localhost:18080/", JenkinsUrls.normalizeBase("http://localhost:18080/"))
    }

    @Test
    fun `a context path survives normalisation`() {
        assertEquals("https://ci.example.com/jenkins/", JenkinsUrls.normalizeBase("https://ci.example.com/jenkins/"))
        assertEquals("https://ci.example.com/jenkins/", JenkinsUrls.normalizeBase("https://ci.example.com/jenkins"))
    }

    @Test
    fun `the scheme is normalised to lower case and junk is dropped`() {
        assertEquals("http://ci.example.com/", JenkinsUrls.normalizeBase("HTTP://ci.example.com"))
        assertEquals("http://ci.example.com/", JenkinsUrls.normalizeBase("http://ci.example.com/#frag"))
        assertEquals("http://ci.example.com/", JenkinsUrls.normalizeBase("http://ci.example.com/?a=b"))
    }

    @Test
    fun `an unusable address is refused rather than guessed at`() {
        assertFailsWith<JenkinsException.Malformed> { JenkinsUrls.normalizeBase("") }
        assertFailsWith<JenkinsException.Malformed> { JenkinsUrls.normalizeBase("   ") }
        assertFailsWith<JenkinsException.Malformed> { JenkinsUrls.normalizeBase("ftp://ci.example.com") }
        assertFailsWith<JenkinsException.Malformed> { JenkinsUrls.normalizeBase("http:///job/x") }
    }

    @Test
    fun `an already encoded branch name is encoded exactly once more`() {
        // Jenkins reports the branch `feature/ORD-214` as `feature%2FORD-214` and its own URL as
        // `.../job/feature%252FORD-214/`. Encoding the raw name instead would give `feature/ORD-214`,
        // which Jenkins reads as two levels and answers with a 404.
        assertEquals("feature%252FORD-214", JenkinsUrls.encodeSegment("feature%2FORD-214"))
        assertEquals("main", JenkinsUrls.encodeSegment("main"))
        assertEquals("PR-1", JenkinsUrls.encodeSegment("PR-1"))
        assertEquals("a%20b", JenkinsUrls.encodeSegment("a b"))
        assertEquals("a%2Fb", JenkinsUrls.encodeSegment("a/b"))
        assertEquals("%D1%8B", JenkinsUrls.encodeSegment("\u044B"))
    }

    @Test
    fun `decoding is the inverse of encoding`() {
        for (name in listOf("main", "feature%2FORD-214", "PR-1", "a b", "\u044B", "100%")) {
            assertEquals(name, JenkinsUrls.decodeSegment(JenkinsUrls.encodeSegment(name)))
        }
        assertEquals("feature/ORD-214", JenkinsUrls.decodeSegment("feature%2FORD-214"))
        assertEquals("100%", JenkinsUrls.decodeSegment("100%"))
        assertEquals("%zz", JenkinsUrls.decodeSegment("%zz"))
    }

    @Test
    fun `a job path is job-per-level with each name encoded`() {
        assertEquals("job/freestyle-fail/", JenkinsUrls.jobPath(listOf("freestyle-fail")))
        assertEquals(
            "job/multibranch-demo/job/feature%252FORD-214/",
            JenkinsUrls.jobPath(listOf("multibranch-demo", "feature%2FORD-214")),
        )
        assertEquals(
            "job/stagecraft/job/deep/job/nested-freestyle/",
            JenkinsUrls.jobPath(listOf("stagecraft", "deep", "nested-freestyle")),
        )
        assertEquals(
            "http://localhost:18080/job/multibranch-demo/job/main/",
            JenkinsUrls.jobUrl("http://localhost:18080/", listOf("multibranch-demo", "main")),
        )
    }

    @Test
    fun `server URLs are rebased onto the base we actually talk to`() {
        // Re-check R4: Jenkins builds every url it emits from its own configured root, so an HTTPS
        // request through nginx still answers with https://localhost:18443/ even when we asked over
        // http://localhost:18080/.
        assertEquals(
            "http://localhost:18080/job/multibranch-demo/job/main/1/",
            JenkinsUrls.rebase("http://localhost:18080/", "https://localhost:18443/job/multibranch-demo/job/main/1/"),
        )
        assertEquals(
            "https://localhost:18443/job/multibranch-demo/job/main/1/",
            JenkinsUrls.rebase("https://localhost:18443/", "http://localhost:18080/job/multibranch-demo/job/main/1/"),
        )
    }

    @Test
    fun `rebasing keeps a context path`() {
        assertEquals(
            "https://ci.example.com/jenkins/job/a/1/",
            JenkinsUrls.rebase("https://ci.example.com/jenkins/", "http://ci.example.com/job/a/1/"),
        )
    }

    @Test
    fun `rebasing accepts a root-relative URL and leaves an empty one alone`() {
        assertEquals("http://localhost:18080/job/a/1/", JenkinsUrls.rebase("http://localhost:18080/", "/job/a/1/"))
        assertEquals("http://localhost:18080/", JenkinsUrls.rebase("http://localhost:18080/", ""))
    }

    @Test
    fun `a tree query is escaped enough for java net URI to accept it`() {
        // RFC 3986 allows only pchar in a query, and `[` and `]` are not pchar. A raw
        // `tree=jobs[name]` throws URISyntaxException in java.net.URI, so the brackets must be
        // escaped even though Jenkins' own UI sends them raw.
        val url = JenkinsUrls.apiJson("http://localhost:18080/", "jobs[name,_class,color]")
        assertEquals(
            "http://localhost:18080/api/json?tree=jobs%5Bname%2C_class%2Ccolor%5D",
            url,
        )
        URI(url)
        assertEquals("jobs[name,_class,color]", decodeTree(url))
    }

    @Test
    fun `api json tolerates a URL with or without a trailing slash`() {
        assertEquals(
            JenkinsUrls.apiJson("http://localhost:18080/job/a/", "builds[number]"),
            JenkinsUrls.apiJson("http://localhost:18080/job/a", "builds[number]"),
        )
    }

    private fun decodeTree(url: String): String =
        java.net.URLDecoder.decode(url.substringAfter("?tree="), "UTF-8")

    @Test
    fun `encoding leaves no character that a URL cannot carry`() {
        for (tree in listOf("jobs[name]", "jobs[name,jobs[name,jobs[name]]]", "builds[_class,number]{0,25}")) {
            val url = JenkinsUrls.apiJson("http://localhost:18080/", tree)
            URI(url)
            assertEquals(tree, decodeTree(url))
            assertTrue(url.substringAfter("?tree=").none { it == '[' || it == ']' || it == ' ' })
        }
    }
}
