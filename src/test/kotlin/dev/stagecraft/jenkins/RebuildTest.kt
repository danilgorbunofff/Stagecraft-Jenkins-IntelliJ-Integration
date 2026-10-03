package dev.stagecraft.jenkins

import kotlin.test.assertEquals
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

    // ---------------------------------------------------------------- audit regressions

    @Test
    fun `a parameterized build is re-run with the parameters it used`() {
        val fake = FakeTransport()
        fake.onGetPrefix(
            base + "job/svc/job/main/7/api/json",
            Fixtures.of("""{"actions":[{"_class":"hudson.model.ParametersAction","parameters":[{"name":"ENV","value":"staging"},{"name":"DRY","value":true}]},{}]}"""),
        )
        fake.onPost(base + "job/svc/job/main/buildWithParameters", Fixtures.empty(201))
        val client = client(fake)

        val parameters = client.buildParameters(base + "job/svc/job/main/7/")
        val result = client.triggerBuild(listOf("svc", "main"), parameters)

        assertEquals(mapOf("ENV" to "staging", "DRY" to "true"), parameters)
        assertTrue(result.accepted, result.message)
        assertEquals("ENV=staging&DRY=true", String(fake.requests.last().body!!, Charsets.UTF_8))
    }

    @Test
    fun `every refusal says what it is`() {
        fun answer(status: Int, body: String = "", headers: Map<String, String> = emptyMap()): String {
            val fake = FakeTransport()
            fake.onPost(base + "job/a/build", Fixtures.of(body, status, headers))
            return client(fake).triggerBuild(listOf("a")).message
        }

        assertTrue(answer(401).contains("credentials"))
        assertTrue(answer(403, "No valid crumb was included in the request").contains("crumb"))
        assertTrue(answer(403).contains("not allowed"))
        assertTrue(answer(409).contains("disabled"))
        assertTrue(answer(302, headers = mapOf("Location" to "https://sso.example.com/login")).contains("single sign-on"))
    }

    @Test
    fun `a disabled job is not buildable`() {
        val fake = FakeTransport()
        fake.onGetPrefix(base + "job/a/api/json", Fixtures.of("""{"buildable":false}"""))

        assertFalse(client(fake).isBuildable(listOf("a")))
    }
}
