package dev.stagecraft.jenkins

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RebuildTest {

    private val base = "http://localhost:18080/"

    private fun client(fake: FakeTransport) =
        JenkinsClient(base, JenkinsAuth(JenkinsCredential.ApiToken("admin", "token")), fake)

    @Test
    fun `a permitted rebuild is queued`() {
        val fake = FakeTransport()
        fake.onPost(base + "job/svc/job/main/build", Fixtures.empty(201))

        val result = client(fake).triggerBuild(listOf("svc", "main"))

        assertTrue(result.accepted)
        assertTrue(result.message.contains("queued"))
    }

    @Test
    fun `a forbidden rebuild is reported, not thrown`() {
        // A server that forbids the trigger is a normal configuration, not a load failure.
        val fake = FakeTransport()
        fake.onPost(base + "job/svc/job/main/build", Fixtures.empty(403))

        val result = client(fake).triggerBuild(listOf("svc", "main"))

        assertFalse(result.accepted)
        assertTrue(result.message.contains("not allowed"), result.message)
    }

    @Test
    fun `a hidden job is reported as not found`() {
        val fake = FakeTransport()
        fake.onPost(base + "job/svc/job/main/build", Fixtures.empty(404))

        val result = client(fake).triggerBuild(listOf("svc", "main"))

        assertFalse(result.accepted)
        assertTrue(result.message.contains("not visible"), result.message)
    }

    @Test
    fun `parameters switch to buildWithParameters`() {
        val fake = FakeTransport()
        fake.onPost(base + "job/svc/job/main/buildWithParameters", Fixtures.empty(201))

        client(fake).triggerBuild(listOf("svc", "main"), mapOf("ENV" to "prod"))

        val request = fake.requests.single()
        assertTrue(request.url.endsWith("buildWithParameters"), request.url)
        val body = request.body!!.toString(Charsets.UTF_8)
        assertTrue(body.contains("ENV=prod"), body)
    }
}
