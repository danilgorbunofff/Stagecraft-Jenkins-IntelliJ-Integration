package dev.stagecraft.jenkins

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The §9.2 obligations, each asserted against a recorded Day-0 response.
 *
 * The request *counts* are as important as the responses: "fetch the crumb once", "refetch once and
 * retry once, never loop" are only true if a test can count.
 */
class JenkinsHttpTest {

    private val base = "http://localhost:18080/"
    private val crumbUrl = base + JenkinsHttp.CRUMB_ISSUER_PATH
    private val postUrl = base + "job/a/build"

    private val crumbValue =
        "e8950be54638dd280e06e9a308e92126e42a25b35a79dc24c0ca715ca9779f9c"

    private fun tokenHttp(fake: FakeTransport, user: String = "admin", secret: String = "token") =
        JenkinsHttp(base, JenkinsAuth(JenkinsCredential.ApiToken(user, secret)), fake)

    private fun passwordHttp(fake: FakeTransport, user: String = "admin", secret: String = "pw") =
        JenkinsHttp(base, JenkinsAuth(JenkinsCredential.Password(user, secret)), fake)

    @Test
    fun `basic credentials are attached to every request`() {
        val fake = FakeTransport()
        fake.onGet(base + "me/api/json", Fixtures.json("01.me.json"))

        tokenHttp(fake).get("me/api/json")

        val expected = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("admin:token".toByteArray(Charsets.UTF_8))
        assertEquals(expected, fake.requests.single().header("Authorization"))
    }

    @Test
    fun `an api token never asks for a crumb`() {
        // Day-0: with an API token a wrong crumb is ignored rather than rejected, so the crumb is
        // not required. Not asking is also what keeps a broken /crumbIssuer from blocking the
        // supported auth path.
        val fake = FakeTransport()
        fake.onPost(postUrl, Fixtures.empty(200))

        val response = tokenHttp(fake).post("job/a/build", ByteArray(0))

        assertEquals(200, response.status)
        assertEquals(1, fake.count)
        assertNull(fake.requests.single().header("Jenkins-Crumb"))
        assertTrue(fake.requestsMatching("crumbIssuer").isEmpty())
    }

    @Test
    fun `a password fetches the crumb once and reuses it`() {
        val fake = FakeTransport()
        fake.onGet(crumbUrl, Fixtures.json("02.crumb.json"))
        fake.onPost(postUrl, Fixtures.empty(200))

        val http = passwordHttp(fake)
        http.post("job/a/build", ByteArray(0))
        http.post("job/a/build", ByteArray(0))

        assertEquals(3, fake.count, "one crumb fetch plus two posts")
        assertEquals(1, fake.requestsMatching("crumbIssuer").size)
        val posts = fake.requestsMatching("job/a/build")
        assertEquals(2, posts.size)
        assertEquals(crumbValue, posts[0].header("Jenkins-Crumb"))
        assertEquals(crumbValue, posts[1].header("Jenkins-Crumb"))
    }

    @Test
    fun `the crumb request field from the server is honoured`() {
        val fake = FakeTransport()
        fake.onGet(
            crumbUrl,
            Fixtures.of("""{"crumb":"abc123","crumbRequestField":"X-Custom-Crumb"}"""),
        )
        fake.onPost(postUrl, Fixtures.empty(200))

        passwordHttp(fake).post("job/a/build", ByteArray(0))

        assertEquals("abc123", fake.requestsMatching("job/a/build").single().header("X-Custom-Crumb"))
        assertNull(fake.requestsMatching("job/a/build").single().header("Jenkins-Crumb"))
    }

    @Test
    fun `a 404 on the crumb issuer means csrf is off and is never asked again`() {
        val fake = FakeTransport()
        fake.onGet(crumbUrl, Fixtures.empty(404))
        fake.onPost(postUrl, Fixtures.empty(200))

        val http = passwordHttp(fake)
        http.post("job/a/build", ByteArray(0))
        http.post("job/a/build", ByteArray(0))

        assertTrue(http.auth.crumbCache.isDisabled)
        assertNull(http.auth.lastCrumbError)
        assertEquals(1, fake.requestsMatching("crumbIssuer").size)
        assertNull(fake.requestsMatching("job/a/build").first().header("Jenkins-Crumb"))
    }

    @Test
    fun `a rejected crumb is refetched exactly once and the request retried exactly once`() {
        val fake = FakeTransport()
        fake.onGet(crumbUrl, Fixtures.json("02.crumb.json"))
        fake.onPost(postUrl, Fixtures.of(FORBIDDEN_CRUMB_BODY, 403))

        val response = passwordHttp(fake).post("job/a/build", ByteArray(0))

        assertEquals(403, response.status)
        assertEquals(2, fake.requestsMatching("crumbIssuer").size, "the crumb was refetched more or less than once")
        assertEquals(2, fake.requestsMatching("job/a/build").size, "the request was retried more or less than once")
        assertEquals(4, fake.count)
    }

    @Test
    fun `a crumb fetch that fails does not fail the request`() {
        // §9.2: never fail a request because the crumb fetch failed. If the fetch breaks, the
        // request still goes out - without a crumb - and the server's answer is what decides.
        val fake = FakeTransport()
        fake.onGet(crumbUrl, Fixtures.empty(500))
        fake.onPost(postUrl, Fixtures.empty(200))

        val http = passwordHttp(fake)
        val response = http.post("job/a/build", ByteArray(0))

        assertEquals(200, response.status)
        assertNotNull(http.auth.lastCrumbError)
        assertNull(fake.requestsMatching("job/a/build").single().header("Jenkins-Crumb"))
    }

    @Test
    fun `a crumb body with no crumb in it does not fail the request either`() {
        val fake = FakeTransport()
        fake.onGet(crumbUrl, Fixtures.of("""{"_class":"hudson.security.csrf.DefaultCrumbIssuer"}"""))
        fake.onPost(postUrl, Fixtures.empty(200))

        val http = passwordHttp(fake)
        assertEquals(200, http.post("job/a/build", ByteArray(0)).status)
        assertNotNull(http.auth.lastCrumbError)
        assertFalse(http.auth.crumbCache.isLoaded)
    }

    @Test
    fun `a 403 that is not about the crumb is not retried`() {
        val fake = FakeTransport()
        fake.onGet(crumbUrl, Fixtures.json("02.crumb.json"))
        fake.onPost(postUrl, Fixtures.of("<html><title>Error 403 Forbidden</title></html>", 403))

        val response = passwordHttp(fake).post("job/a/build", ByteArray(0))

        assertEquals(403, response.status)
        assertEquals(2, fake.count, "an ordinary 403 must not trigger a crumb refetch")
        assertEquals(1, fake.requestsMatching("job/a/build").size)
    }

    @Test
    fun `a read never asks for a crumb`() {
        val fake = FakeTransport()
        fake.onGet(base + "me/api/json", Fixtures.json("01.me.json"))

        passwordHttp(fake).get("me/api/json")

        assertEquals(1, fake.count)
        assertTrue(fake.requestsMatching("crumbIssuer").isEmpty())
    }

    @Test
    fun `the jenkins version is read off the first response`() {
        val fake = FakeTransport()
        fake.onGetPrefix(base, Fixtures.of("{}", 200, mapOf("X-Jenkins" to "2.541.3")))

        val http = tokenHttp(fake)
        assertNull(http.lastJenkinsVersion)
        http.get("")

        assertEquals("2.541.3", http.lastJenkinsVersion.toString())
    }

    @Test
    fun `a response without the header leaves the version unknown`() {
        val fake = FakeTransport()
        fake.onGetPrefix(base, Fixtures.of("{}", 200))

        val http = tokenHttp(fake)
        http.get("")

        assertNull(http.lastJenkinsVersion)
    }

    @Test
    fun `paths are resolved against the base and absolute urls are left alone`() {
        val fake = FakeTransport()
        fake.onGetPrefix(base, Fixtures.of("{}", 200))

        val http = tokenHttp(fake)
        assertEquals(base + "job/a/", http.resolve("job/a/"))
        assertEquals(base + "job/a/", http.resolve("/job/a/"))
        assertEquals("https://other.example.com/job/a/", http.resolve("https://other.example.com/job/a/"))
    }

    @Test
    fun `a redirect is handed back rather than followed`() {
        // §9.2: SSO has to be visible as a redirect. Following it silently lands the IDE on an
        // HTML login page, which then fails as "unreadable JSON" and tells the user nothing.
        val fake = FakeTransport()
        fake.onGetPrefix(
            base,
            Fixtures.of("", 302, mapOf("Location" to "https://sso.example.com/login?from=jenkins")),
        )

        val response = tokenHttp(fake).get("job/a/")

        assertEquals(302, response.status)
        assertEquals("https://sso.example.com/login?from=jenkins", response.header("Location"))
    }

    private companion object {
        const val FORBIDDEN_CRUMB_BODY =
            "<html><head><title>Error 403 No valid crumb was included in the request</title></head></html>"
    }

    // --------------------------------------------------------------- the global authenticator (audit)

    @Test
    fun `a 401 comes back as a 401 without consulting the process-wide authenticator`() {
        // Inside an IDE the default Authenticator is the platform's. The JDK used to hand it every
        // 401 and replay the request with its answer - 20 times, measured.
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Jenkins\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val previous = java.net.Authenticator.getDefault()
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                asked.incrementAndGet()
                return java.net.PasswordAuthentication("someone", "else".toCharArray())
            }
        })
        try {
            val http = JenkinsHttp(
                "http://127.0.0.1:${server.address.port}/",
                JenkinsAuth(JenkinsCredential.ApiToken("admin", "revoked")),
                transport = UrlConnectionTransport(),
            )

            assertEquals(401, http.get("me/api/json").status)
            assertEquals(0, asked.get())
        } finally {
            java.net.Authenticator.setDefault(previous)
            server.stop(0)
        }
    }
}
