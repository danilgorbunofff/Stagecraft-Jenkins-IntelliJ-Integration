package dev.stagecraft.jenkins

import dev.stagecraft.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleTailerTest {

    private val base = "http://localhost:18080/"
    private val buildUrl = base + "job/svc/job/main/1/"

    private fun client(fake: FakeTransport) =
        JenkinsClient(base, JenkinsAuth(JenkinsCredential.ApiToken("admin", "token")), fake)

    private fun response(cursor: Long, more: Boolean?, body: String = ""): HttpResponse {
        val headers = ArrayList<Pair<String, String>>()
        headers += "X-Text-Size" to cursor.toString()
        if (more != null) headers += "X-More-Data" to more.toString()
        return HttpResponse(200, headers, body.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `a running poll advances the cursor and asks for more`() {
        val fake = FakeTransport()
        fake.onGet(buildUrl + "logText/progressiveText?start=0", response(cursor = 100, more = true, body = "hello\n"))

        val tailer = ConsoleTailer(client(fake), buildUrl)
        val delta = tailer.poll()

        assertEquals("hello\n", delta.text)
        assertEquals(100L, tailer.offset)
        assertTrue(delta.moreData)
        assertFalse(tailer.finished)
        assertFalse(delta.resetDetected)
    }

    @Test
    fun `an empty delta does not move the cursor`() {
        val fake = FakeTransport()
        fake.onGet(buildUrl + "logText/progressiveText?start=0", response(cursor = 100, more = true, body = "hello\n"))
        fake.onGet(buildUrl + "logText/progressiveText?start=100", response(cursor = 100, more = true, body = ""))

        val tailer = ConsoleTailer(client(fake), buildUrl)
        tailer.poll()
        val delta = tailer.poll()

        assertEquals("", delta.text)
        assertEquals(100L, tailer.offset)
        assertTrue(delta.moreData)
        assertFalse(delta.resetDetected)
    }

    @Test
    fun `a finished build stops the tail`() {
        val fake = FakeTransport()
        fake.onGet(buildUrl + "logText/progressiveText?start=0", response(cursor = 50, more = null, body = "done\n"))

        val tailer = ConsoleTailer(client(fake), buildUrl)
        val delta = tailer.poll()

        assertFalse(delta.moreData)
        assertTrue(tailer.finished)
        assertEquals(1, fake.count)

        val again = tailer.poll()
        assertTrue(again.text.isEmpty())
        assertEquals(1, fake.count, "a finished tail makes no more requests")
    }

    @Test
    fun `a cursor that goes backwards is surfaced as a reset`() {
        // Re-check R1: a cursor past the end makes Jenkins reset to zero and re-send the whole log.
        // Appending it would duplicate the console, so the tailer flags it instead.
        val fake = FakeTransport()
        fake.onGet(buildUrl + "logText/progressiveText?start=0", response(cursor = 100, more = true, body = "hello\n"))
        fake.onGet(buildUrl + "logText/progressiveText?start=100", response(cursor = 40, more = true, body = "whole log"))

        val tailer = ConsoleTailer(client(fake), buildUrl)
        tailer.poll()
        val delta = tailer.poll()

        assertTrue(delta.resetDetected)
        assertEquals(40L, tailer.offset)
    }
}
