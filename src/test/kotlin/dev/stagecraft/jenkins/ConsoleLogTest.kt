package dev.stagecraft.jenkins

import dev.stagecraft.Fixtures
import java.io.Reader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleLogTest {

    private fun read(text: String, head: Int = 1000, tail: Int = 100): ConsoleLog =
        ConsoleLogReader(headCapChars = head, tailCapChars = tail).read(text)

    @Test
    fun `a short log is kept whole and its first error is located`() {
        val text = "[Pipeline] stage\nhello\nERROR: something broke\nmore\n"

        val log = read(text)

        assertTrue(!log.truncated)
        assertEquals(text, log.text)
        assertEquals(4, log.totalLines)
        assertEquals(3, log.firstErrorLine)
        assertEquals("ERROR: something broke", log.firstErrorText)
        assertEquals(text.indexOf("ERROR"), log.firstErrorOffset)
        assertNull(log.truncationBanner)
    }

    @Test
    fun `progressiveText notes are stripped and CRLF normalised to match consoleText`() {
        // The Day-0 equality claim: progressiveText?start=0, once notes are stripped and CRLF is
        // normalised, is byte-for-byte the same as /consoleText. The streaming reader must reproduce
        // it, and find the same first error the parser does (line 72).
        val raw = Fixtures.text("16.progressive-main-finished.raw.txt")

        val log = ConsoleLogReader().read(raw)

        assertEquals(Fixtures.text("08.console-main.txt"), log.text)
        assertEquals(72, log.firstErrorLine)
    }

    @Test
    fun `a log with no error has no first error`() {
        val log = read("all good\nstill good\n")

        assertNull(log.firstErrorLine)
        assertNull(log.firstErrorOffset)
    }

    @Test
    fun `CRLF and lone CR become LF`() {
        val log = read("one\r\ntwo\rthree\n")

        assertEquals("one\ntwo\nthree\n", log.text)
        assertEquals(3, log.totalLines)
    }

    @Test
    fun `a log that does not end in a newline still counts its last line`() {
        val log = read("one\ntwo")

        assertEquals("one\ntwo", log.text)
        assertEquals(2, log.totalLines)
    }

    @Test
    fun `a log longer than the caps keeps the head and the tail and marks the drop`() {
        val prefix = "0123456789\n"
        val middle = "middle-noise\n".repeat(100)
        val tail = "tail-line\n".repeat(100)
        val whole = prefix + middle + tail
        val log = read(whole, head = 30, tail = 40)

        assertTrue(log.truncated)
        assertTrue(log.text.startsWith(whole.take(30)))
        assertTrue(log.text.endsWith(whole.takeLast(40)))
        assertTrue(log.text.contains(ConsoleLog.TRUNCATION_MARKER))
        assertTrue(log.truncationBanner!!.contains("showing the first"), log.truncationBanner!!)
        assertEquals(whole.length.toLong(), log.totalChars)
    }

    @Test
    fun `the first error is found even when it sits in the dropped middle`() {
        val head = "ok\n".repeat(50)          // 150 chars, dropped after 20
        val error = "ERROR: boom\n"
        val tail = "tail\n".repeat(50)        // 250 chars, only the last 20 kept
        val log = read(head + error + tail, head = 20, tail = 20)

        assertTrue(log.truncated)
        assertEquals(51, log.firstErrorLine)
        assertEquals("ERROR: boom", log.firstErrorText)
        // The error line itself was dropped, so there is nothing in the retained text to scroll to.
        assertNull(log.firstErrorOffset)
    }

    @Test
    fun `an error still visible in the retained text has an offset to scroll to`() {
        val head = "ok\n".repeat(4)           // fits entirely in the head
        val error = "ERROR: visible\n"
        val tail = "tail\n".repeat(4)
        val log = read(head + error + tail, head = 100, tail = 100)

        assertTrue(!log.truncated)
        assertNotNull(log.firstErrorOffset)
        assertTrue(log.text.substring(log.firstErrorOffset!!).startsWith("ERROR: visible"))
    }

    @Test
    fun `a 50 MB stream retains only the head and tail, not the whole log`() {
        // The exit criterion is a 50 MB log without exhausting the heap. The source is generated in
        // place so the test itself never materialises 50 MB, and the retained text must stay at the
        // caps plus the marker.
        val chunk = "a line of ordinary build output\n" // 33 chars
        val times = (50 * 1024 * 1024) / chunk.length + 1
        val head = ConsoleLogReader.DEFAULT_HEAD_CHARS
        val tail = ConsoleLogReader.DEFAULT_TAIL_CHARS
        val reader = ConsoleLogReader(headCapChars = head, tailCapChars = tail)

        val log = reader.read(RepeatingReader(chunk, times))

        assertTrue(log.totalChars > 50L * 1024 * 1024, "streamed ${log.totalChars} chars")
        assertTrue(log.truncated)
        assertTrue(
            log.text.length <= head + ConsoleLog.TRUNCATION_MARKER.length + tail,
            "retained ${log.text.length} chars, expected <= ${head + tail}",
        )
    }

    /** A [Reader] that repeats [chunk] [times] without ever building the whole string. */
    private class RepeatingReader(private val chunk: String, private var times: Int) : Reader() {
        private var position = 0

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (times <= 0) return -1
            var written = 0
            while (written < len) {
                if (position >= chunk.length) {
                    times--
                    if (times <= 0) break
                    position = 0
                }
                cbuf[off + written++] = chunk[position++]
            }
            return if (written == 0) -1 else written
        }

        override fun close() = Unit
    }
}
