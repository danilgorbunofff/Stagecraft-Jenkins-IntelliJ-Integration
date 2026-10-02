package dev.stagecraft.jenkins

import dev.stagecraft.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The centrepiece here is [stripping the notes reproduces consoleText byte for byte]. Everything
 * else in the class is a unit test of the pieces; that one is the evidence.
 */
class ConsoleTextTest {

    @Test
    fun `line endings are uniform afterwards`() {
        assertEquals("a\nb\nc", ConsoleText.normaliseLineEndings("a\r\nb\rc"))
        assertEquals("a\nb", ConsoleText.normaliseLineEndings("a\nb"))
        assertEquals("", ConsoleText.normaliseLineEndings(""))
    }

    @Test
    fun `a console note disappears entirely`() {
        assertEquals("hello world", ConsoleText.stripNotes("hello ${note()}world"))
        assertEquals("", ConsoleText.stripNotes(note()))
        assertEquals("before after", ConsoleText.stripNotes("before ${note()}after"))
    }

    @Test
    fun `a note inside a line does not split the line`() {
        // Real example from the reference build: the annotation sits between `user` and the name.
        assertEquals("Started by user admin", ConsoleText.stripNotes("Started by user ${note()}admin"))
    }

    @Test
    fun `several notes on one line all go`() {
        assertEquals("abcdef", ConsoleText.stripNotes("a${note()}b${note()}c${note()}d${note()}e${note()}f"))
    }

    @Test
    fun `an unterminated note is dropped rather than shown`() {
        // A live chunk can end in the middle of a note; half an annotation is metadata, not output.
        // The space before the note is real log content, so it stays.
        assertEquals("kept ", ConsoleText.stripNotes("kept ${ConsoleText.NOTE_PREAMBLE}ha:AAAA"))
        assertEquals("kept ", ConsoleText.stripNotes("kept ${ConsoleText.NOTE_PREAMBLE}"))
        assertEquals("kept ", ConsoleText.stripNotes("kept \u001B[8"))
        assertEquals("kept ", ConsoleText.stripNotes("kept \u001B"))
    }

    @Test
    fun `text that merely looks like an escape is left alone`() {
        assertEquals("plain \u001B[0m reset", ConsoleText.stripNotes("plain \u001B[0m reset"))
        assertEquals("plain ha: text", ConsoleText.stripNotes("plain ha: text"))
    }

    @Test
    fun `toDisplayText does both jobs`() {
        assertEquals("a\nb", ConsoleText.toDisplayText("a\r\n${note()}b"))
    }

    @Test
    fun `stripping the notes reproduces consoleText byte for byte`() {
        // progressiveText (16.*) and consoleText (08.*) for the same finished build,
        // multibranch-demo/main/1. Jenkins writes the notes into the first and not the second.
        val raw = Fixtures.text("16.progressive-main-finished.raw.txt")
        val expected = Fixtures.text("08.console-main.txt")

        assertEquals(12336, raw.toByteArray(Charsets.UTF_8).size, "the raw chunk changed size")
        assertTrue(raw.contains(ConsoleText.NOTE_PREAMBLE), "the raw chunk no longer contains console notes")

        val stripped = ConsoleText.toDisplayText(raw)

        assertEquals(expected, stripped)
        assertEquals(
            expected.toByteArray(Charsets.UTF_8).size,
            stripped.toByteArray(Charsets.UTF_8).size,
            "the stripped text is not byte-identical to consoleText",
        )
        assertEquals(2856, stripped.toByteArray(Charsets.UTF_8).size)
        assertEquals(74, stripped.lines().size)
        assertTrue(!stripped.contains('\r'), "CR survived normalisation")
        assertTrue(!stripped.contains('\u001B'), "an escape sequence survived note stripping")
    }

    @Test
    fun `the cursor counts the normalised stream, not the bytes on the wire`() {
        // 12336 bytes on the wire, X-Text-Size 12263: the difference is exactly the 73 CR bytes
        // that CRLF normalisation removes. The note payloads are already excluded from both.
        val raw = Fixtures.text("16.progressive-main-finished.raw.txt").toByteArray(Charsets.UTF_8)
        val carriageReturns = raw.count { it == '\r'.code.toByte() }
        val textSize = Fixtures.finishedFirstChunkHeaders.header("X-Text-Size")!!.toLong()

        assertEquals(73, carriageReturns)
        assertEquals(raw.size - carriageReturns, textSize.toInt())
    }

    @Test
    fun `a live chunk is readable too`() {
        val raw = Fixtures.text("16.progressive-running-first.raw.txt")
        val display = ConsoleText.toDisplayText(raw)
        assertTrue(raw.contains(ConsoleText.NOTE_PREAMBLE))
        assertTrue(!display.contains('\u001B'))
        assertTrue(!display.contains('\r'))
        assertTrue(display.contains("[Pipeline]"), "the pipeline lines did not survive stripping")
    }

    private fun note(): String = "${ConsoleText.NOTE_PREAMBLE}ha:////4Kv1AAAA${ConsoleText.NOTE_EPILOGUE}"
}
