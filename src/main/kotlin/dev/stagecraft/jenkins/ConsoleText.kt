package dev.stagecraft.jenkins

/**
 * Console text handling: line endings, console notes, and the offset cursor.
 *
 * Three measured facts drive all of this (Day-0 re-check R1, fixtures `16.*`):
 *
 *  1. `progressiveText` wraps pipeline output in *console notes* - `ESC[8mha:` … `ESC[0m` with a
 *     base64 payload between them. `/consoleText` has them already removed. Stripping them from
 *     `progressiveText` and normalising CRLF reproduces `/consoleText` **byte for byte**
 *     (2856 bytes, 74 lines, zero differences on the reference build). That equality is asserted
 *     by a test, so the stripper cannot silently rot.
 *  2. `progressiveText` writes CRLF; `/consoleText` writes LF.
 *  3. `X-Text-Size` is the *next* offset, and it is opaque. It is not the body length: the finished
 *     build's first chunk is 12336 bytes with `X-Text-Size: 12263`, because the cursor counts the
 *     CRLF-normalised stream while the body carries the CR bytes and the note payloads. Never
 *     derive the next offset from the body length - echo the header back (§9.5).
 */
object ConsoleText {

    const val NOTE_PREAMBLE = "\u001B[8mha:"
    const val NOTE_EPILOGUE = "\u001B[0m"

    /** CRLF and lone CR both become LF. */
    fun normaliseLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * Remove console notes.
     *
     * An unterminated note at the end of the text is dropped rather than emitted: it is the first
     * half of a note whose second half has not arrived yet, and it is metadata, not log output. A
     * chunk that ends in the middle of the `ESC[8mha:` marker itself is trimmed for the same reason,
     * so that live output never shows a stray escape sequence.
     */
    fun stripNotes(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(NOTE_PREAMBLE, index)
            if (start < 0) {
                out.append(text, index, text.length)
                break
            }
            out.append(text, index, start)
            val end = text.indexOf(NOTE_EPILOGUE, start + NOTE_PREAMBLE.length)
            if (end < 0) break
            index = end + NOTE_EPILOGUE.length
        }
        return trimTrailingPartialPreamble(out.toString())
    }

    /** Everything a log view should display: notes gone, line endings uniform. */
    fun toDisplayText(raw: String): String = stripNotes(normaliseLineEndings(raw))

    private fun trimTrailingPartialPreamble(text: String): String {
        val max = minOf(text.length, NOTE_PREAMBLE.length - 1)
        for (length in max downTo 1) {
            val tail = text.substring(text.length - length)
            if (NOTE_PREAMBLE.startsWith(tail)) return text.substring(0, text.length - length)
        }
        return text
    }
}

/**
 * One `progressiveText` response.
 *
 * [nextOffset] is `X-Text-Size` exactly as Jenkins sent it, which is the value to pass as `start`
 * on the next poll. [resetDetected] flags the one case where the cursor cannot be trusted: asking
 * for an offset past the end of a finished log makes Jenkins answer 200 with the **whole log
 * again** (Day-0 re-check R1), so a cursor that went backwards means the reader must resynchronise
 * from zero rather than append a duplicate.
 */
data class ConsoleChunk(
    val text: String,
    val rawByteCount: Int,
    val nextOffset: Long,
    val moreData: Boolean,
    val resetDetected: Boolean,
) {
    val isEmpty: Boolean get() = text.isEmpty()
}
