package dev.stagecraft.jenkins

import dev.stagecraft.FakeTransport
import dev.stagecraft.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LintClientTest {

    private val base = "http://localhost:18080/"
    private val validateUrl = base + "pipeline-model-converter/validate"

    private fun client(fake: FakeTransport) =
        JenkinsClient(base, JenkinsAuth(JenkinsCredential.ApiToken("admin", "token")), fake)

    @Test
    fun `a valid jenkinsfile is reported valid`() {
        val fake = FakeTransport()
        fake.onPost(validateUrl, Fixtures.json("13.lint-valid.txt"))

        val result = LintClient(client(fake)).validate("pipeline { agent any }")

        assertTrue(result.valid)
        assertTrue(result.headline.contains("successfully validated"), result.headline)
        assertTrue(result.details.isEmpty())
    }

    @Test
    fun `a broken jenkinsfile reports its error as a headline plus details`() {
        val fake = FakeTransport()
        fake.onPost(validateUrl, Fixtures.json("13.lint-broken.txt"))

        val result = LintClient(client(fake)).validate("pipeline {")

        assertFalse(result.valid)
        assertTrue(result.headline.contains("Errors encountered"), result.headline)
        assertTrue(result.details.any { it.contains("line 8") }, result.details.toString())
    }

    @Test
    fun `the buffer that is sent is the unsaved edit, not a saved file`() {
        val fake = FakeTransport()
        fake.onPost(validateUrl, Fixtures.json("13.lint-valid.txt"))

        LintClient(client(fake)).validate("pipeline { /* UNSAVED EDIT */ }")

        val body = fake.requests.single().body!!.toString(Charsets.UTF_8)
        assertTrue(body.startsWith("jenkinsfile="), body)
        assertTrue(body.contains("UNSAVED"), body)
    }

    @Test
    fun `the linter posts to the server's validator endpoint`() {
        val fake = FakeTransport()
        fake.onPost(validateUrl, Fixtures.json("13.lint-valid.txt"))

        LintClient(client(fake)).validate("pipeline {}")

        assertEquals("POST", fake.requests.single().method)
        assertEquals(validateUrl, fake.requests.single().url)
    }

    @Test
    fun `a web page instead of a lint answer is reported, not shown as markup`() {
        val fake = FakeTransport()
        fake.onPost(validateUrl, Fixtures.of("<!DOCTYPE html><html><head><title>Sign in [Jenkins]</title></head></html>"))

        val result = LintClient(client(fake)).validate("pipeline {}")

        kotlin.test.assertFalse(result.valid)
        kotlin.test.assertTrue(result.headline.contains("web page"), result.headline)
        kotlin.test.assertTrue(result.message.contains("Sign in [Jenkins]"), result.message)
    }
}
