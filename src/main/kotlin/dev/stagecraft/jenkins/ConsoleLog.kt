package dev.stagecraft.jenkins

import java.io.Reader
import java.io.StringReader

/**
 * A console log retained under a memory cap, with the first error located while it streamed.
 *
 * Jenkins logs of 100-500 MB exist and neither console endpoint takes a byte range, so the log
 * cannot be fetched "around the error": the reader streams the whole thing once and keeps the
 * **head** and a **rolling tail** (README §9.7). When [truncated], [text] is head + one marker line
 * + tail, and the dropped middle is exactly [droppedLines] whole lines: both cuts are snapped to line
 * boundaries, so no line is shown half-cut and every line number maps exactly ([displayLine]).
 *
 * [firstErrorLine] is a line number in the **full** log, found while streaming, so it survives
 * truncation; [firstErrorOffset] is where that line sits in [text], or null when it was in the
 * dropped middle. [totalChars] and [totalLines] describe the whole log.
 *
 * [text] is assembled on demand rather than stored, so the log is held once, not twice.
 */
data class ConsoleLog(
    val head: String,
    val tail: String,
    val totalChars: Long,
    val totalLines: Int,
    val firstErrorLine: Int?,
    val firstErrorText: String?,
    val truncated: Boolean,
    val droppedLines: Int = 0,
    val droppedChars: Long = 0,
) {

    /** What the editor shows: the whole log, or head + marker + tail. */
    val text: String get() = if (truncated) head + markerLine + tail else head + tail

    val headChars: Long get() = head.length.toLong()
    val tailChars: Long get() = tail.length.toLong()

    /** Lines of [head] (whole lines only when truncated). */
    val headLines: Int get() = if (truncated) countNewlines(head) else 0

    /** The line standing in for the dropped middle; visible, so truncation is never silent. */
    val markerLine: String get() = markerLine(droppedLines, droppedChars)

    /** 0-based line in [text] of 1-based full-log line [originalLine], or null when it was dropped. */
    fun displayLine(originalLine: Int): Int? {
        if (originalLine < 1 || originalLine > totalLines) return null
        if (!truncated) return originalLine - 1
        val headLines = headLines
        return when {
            originalLine <= headLines -> originalLine - 1
            originalLine > headLines + droppedLines -> originalLine - droppedLines // +1 marker, -1 base
            else -> null
        }
    }

    /** Offset in [text] of the first error line, or null when there is none or it was dropped. */
    val firstErrorOffset: Int?
        get() {
            val line = firstErrorLine?.let(::displayLine) ?: return null
            return lineStartOffset(text, line)
        }

    /**
     * §9.7: truncation must be visible, never silent. `showing the first 20 MB and the last 2 MB of
     * 180 MB` is the shape; sizes are reported in characters (≈ bytes for a log).
     */
    val truncationBanner: String? get() = if (!truncated) {
        null
    } else {
        "showing the first ${megabytes(headChars)} and the last ${megabytes(tailChars)} of " +
            "${megabytes(totalChars)} ($totalLines lines) - open in Jenkins for the rest"
    }

    companion object {
        /**
         * How every marker line starts. ASCII on purpose: one non-Latin-1 character (the `…` this
         * used to contain) makes the JVM store the whole 22 M-character log at two bytes per char.
         */
        const val TRUNCATION_MARKER = "[... Stagecraft dropped "

        fun markerLine(droppedLines: Int, droppedChars: Long): String =
            "$TRUNCATION_MARKER$droppedLines lines (${megabytes(droppedChars)}) from the middle of " +
                "this log; open it in Jenkins for the rest ...]\n"

        fun megabytes(chars: Long): String = "%.1f MB".format(chars / 1_048_576.0)

        internal fun countNewlines(text: CharSequence): Int {
            var count = 0
            for (i in text.indices) if (text[i] == '\n') count++
            return count
        }

        internal fun lineStartOffset(text: CharSequence, line: Int): Int? {
            if (line == 0) return 0
            var seen = 0
            for (i in text.indices) {
                if (text[i] == '\n' && ++seen == line) return if (i + 1 <= text.length) i + 1 else null
            }
            return null
        }
    }
}

/**
 * Streams a console into a [ConsoleLog] without ever holding the whole thing (§9.7).
 *
 * The log is read once. The first [headCapChars] characters are kept in full; everything after that
 * rolls through a fixed-size ring that always holds the last [tailCapChars] characters. Line
 * numbers, the total count, and the first error are tracked on the way, so the answer survives the
 * middle being thrown away.
 *
 * The reader is plain Kotlin over a [Reader] - no IntelliJ imports - so the memory behaviour is
 * unit-tested with a generated large stream instead of a live server.
 */
class ConsoleLogReader(
    private val headCapChars: Int = DEFAULT_HEAD_CHARS,
    private val tailCapChars: Int = DEFAULT_TAIL_CHARS,
    private val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    private val isError: (String) -> Boolean = ConsoleStages::isErrorLine,
    /**
     * Strip `progressiveText` console notes as they stream. A no-op for `/consoleText`, which has
     * them already removed, so it is on by default and the same reader serves both endpoints.
     */
    private val stripNotes: Boolean = true,
) {

    /** Convenience for tests and already-buffered text. */
    fun read(text: CharSequence): ConsoleLog = read(StringReader(text.toString()))

    fun read(reader: Reader): ConsoleLog {
        val head = StringBuilder()
        val tail = CharRing(tailCapChars)
        var totalChars = 0L
        var lineNumber = 1
        var firstErrorLine: Int? = null
        var firstErrorText: String? = null
        val currentLine = StringBuilder()
        var pendingCr = false
        var lastWasPartial = false

        fun consume(c: Char) {
            totalChars++
            if (head.length < headCapChars) head.append(c) else tail.add(c)
            lastWasPartial = c != '\n'
            if (c == '\n') {
                if (firstErrorLine == null) {
                    val line = currentLine.toString()
                    if (isError(line)) {
                        firstErrorLine = lineNumber
                        firstErrorText = line
                    }
                }
                lineNumber++
                currentLine.setLength(0)
            } else if (currentLine.length < maxLineChars) {
                currentLine.append(c)
            }
        }

        val stripper = if (stripNotes) NoteStripper(::consume) else null
        val buffer = CharArray(COPY_BUFFER_CHARS)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            var i = 0
            while (i < read) {
                val c = buffer[i++]
                if (pendingCr) {
                    pendingCr = false
                    if (c == '\n') continue // the LF of a CRLF we already turned into one LF
                }
                val emitted = if (c == '\r') {
                    pendingCr = true
                    '\n'
                } else {
                    c
                }
                if (stripper != null) stripper.accept(emitted) else consume(emitted)
            }
        }
        stripper?.finish()

        // A log that does not end in a newline still has a last line worth scanning.
        var totalLines = lineNumber - 1
        if (currentLine.isNotEmpty() || lastWasPartial) {
            if (firstErrorLine == null) {
                val line = currentLine.toString()
                if (isError(line)) {
                    firstErrorLine = lineNumber
                    firstErrorText = line
                }
            }
            totalLines = lineNumber
        }

        val truncated = totalChars > headCapChars.toLong() + tailCapChars
        if (!truncated) {
            return ConsoleLog(
                head = head.toString() + tail.toString(),
                tail = "",
                totalChars = totalChars,
                totalLines = totalLines,
                firstErrorLine = firstErrorLine,
                firstErrorText = firstErrorText,
                truncated = false,
            )
        }

        // Snap both cuts to line boundaries: the head keeps whole lines only, the tail starts at the
        // first whole line in the ring. What falls between is the dropped middle, in whole lines.
        val headEnd = head.lastIndexOf("\n").let { if (it < 0) 0 else it + 1 }
        val headText = head.substring(0, headEnd)
        val ring = tail.toString()
        val tailStart = ring.indexOf('\n').let { if (it < 0 || it + 1 >= ring.length) 0 else it + 1 }
        val tailText = ring.substring(tailStart)
        val headLines = ConsoleLog.countNewlines(headText)
        val tailLines = ConsoleLog.countNewlines(tailText) + if (tailText.isNotEmpty() && !tailText.endsWith('\n')) 1 else 0
        return ConsoleLog(
            head = headText,
            tail = tailText,
            totalChars = totalChars,
            totalLines = totalLines,
            firstErrorLine = firstErrorLine,
            firstErrorText = firstErrorText,
            truncated = true,
            droppedLines = (totalLines - headLines - tailLines).coerceAtLeast(0),
            droppedChars = totalChars - headText.length - tailText.length,
        )
    }

    companion object {
        /** §9.7: keep the first 20 MB, the last 2 MB, drop the middle. */
        const val DEFAULT_HEAD_CHARS = 20 * 1024 * 1024
        const val DEFAULT_TAIL_CHARS = 2 * 1024 * 1024

        /** A single pathological line is capped too, so one runaway line cannot exhaust the heap. */
        const val DEFAULT_MAX_LINE_CHARS = 8 * 1024

        private const val COPY_BUFFER_CHARS = 64 * 1024
    }
}

/**
 * Removes `progressiveText` console notes (`ESC[8mha:` … `ESC[0m`) from a character stream.
 *
 * [ConsoleText.stripNotes] does this for a whole string; this does it incrementally, holding back a
 * partial preamble so a note split across two read buffers is not leaked and a line is never cut
 * mid-escape. Characters go straight to [sink] - no per-character allocation, which on a 50 MB log
 * was most of the read time.
 *
 * A note is base64 and never spans a line, so a newline inside one means the preamble was literal
 * text, not a note: the note is abandoned and the newline kept, rather than swallowing the rest of
 * the log. At the end of the stream a held-back partial preamble is emitted as the text it was.
 */
internal class NoteStripper(private val sink: (Char) -> Unit) {
    private val pending = CharArray(PREAMBLE.length)
    private var pendingLength = 0
    private var inNote = false
    private var epilogueMatch = 0

    fun accept(c: Char) {
        if (inNote) {
            if (c == '\n') {
                inNote = false
                epilogueMatch = 0
                sink(c)
                return
            }
            if (c == EPILOGUE[epilogueMatch]) {
                epilogueMatch++
                if (epilogueMatch == EPILOGUE.length) {
                    inNote = false
                    epilogueMatch = 0
                }
            } else {
                epilogueMatch = if (c == EPILOGUE[0]) 1 else 0
            }
            return
        }

        if (pendingLength == 0 && c != PREAMBLE[0]) {
            sink(c) // the common case: plain text, nothing held back
            return
        }
        pending[pendingLength++] = c
        while (pendingLength > 0 && !pendingIsPreamblePrefix()) {
            sink(pending[0])
            System.arraycopy(pending, 1, pending, 0, pendingLength - 1)
            pendingLength--
        }
        if (pendingLength == PREAMBLE.length) {
            pendingLength = 0
            inNote = true
            epilogueMatch = 0
        }
    }

    /** End of stream: a partial preamble was literal text after all. An open note is dropped. */
    fun finish() {
        for (i in 0 until pendingLength) sink(pending[i])
        pendingLength = 0
    }

    private fun pendingIsPreamblePrefix(): Boolean {
        for (i in 0 until pendingLength) if (pending[i] != PREAMBLE[i]) return false
        return true
    }

    private companion object {
        const val PREAMBLE = ConsoleText.NOTE_PREAMBLE
        const val EPILOGUE = ConsoleText.NOTE_EPILOGUE
    }
}

/**
 * A fixed-size ring of characters that always holds the last [capacity] written. Used for the log
 * tail: appending is O(1) and the buffer never grows past the cap, where trimming a `StringBuilder`
 * from the front would be O(n) per character.
 */
internal class CharRing(capacity: Int) {
    private val buffer = CharArray(capacity.coerceAtLeast(1))
    private var size = 0
    private var start = 0

    val length: Int get() = size

    fun add(c: Char) {
        val end = (start + size) % buffer.size
        buffer[end] = c
        if (size < buffer.size) {
            size++
        } else {
            start = (start + 1) % buffer.size
        }
    }

    override fun toString(): String {
        if (size == 0) return ""
        val out = CharArray(size)
        val first = minOf(size, buffer.size - start)
        System.arraycopy(buffer, start, out, 0, first)
        if (size > first) System.arraycopy(buffer, 0, out, first, size - first)
        return String(out)
    }
}
