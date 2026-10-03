package dev.stagecraft.jenkins

import java.io.Reader
import java.io.StringReader

/**
 * A console log retained under a memory cap, with the first error located while it streamed.
 *
 * Jenkins logs of 100-500 MB exist and neither console endpoint takes a byte range, so the log
 * cannot be fetched "around the error": the reader streams the whole thing once and keeps the
 * **head** and a **rolling tail** (README §9.7). [text] is therefore not the whole log when
 * [truncated]; it is what the editor shows, with [TRUNCATION_MARKER] standing in for the dropped
 * middle.
 *
 * [firstErrorLine] is a line number in the **full** log, found while streaming, so it survives
 * truncation; [firstErrorOffset] is where that line (or, if it was dropped, the first error still
 * visible) sits in [text], which is what the editor scrolls to. [totalChars] and [totalLines]
 * describe the whole log.
 */
data class ConsoleLog(
    val text: String,
    val totalChars: Long,
    val totalLines: Int,
    val firstErrorLine: Int?,
    val firstErrorText: String?,
    val truncated: Boolean,
    val headChars: Long,
    val tailChars: Long,
) {

    /** Offset in [text] of the first error line still present, or null when none is. */
    val firstErrorOffset: Int? by lazy {
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val line = text.substring(start, end)
            if (ConsoleStages.isErrorLine(line)) return@lazy start
            start = if (end >= text.length) text.length else end + 1
        }
        null
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
        /** The line that stands in for the dropped middle; visible, so truncation is never silent. */
        const val TRUNCATION_MARKER = "\n\u2026 Stagecraft dropped the middle of this log \u2026\n"

        /** One character of marker line plus the marker text; used to keep offsets honest. */
        fun megabytes(chars: Long): String = "%.1f MB".format(chars / 1_048_576.0)
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

        fun consume(c: Char) {
            totalChars++
            if (head.length < headCapChars) head.append(c) else tail.add(c)
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

        val stripper = if (stripNotes) NoteStripper() else null
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
                if (stripper != null) {
                    val display = stripper.accept(emitted)
                    var j = 0
                    while (j < display.length) consume(display[j++])
                } else {
                    consume(emitted)
                }
            }
        }

        // A log that does not end in a newline still has a last line worth scanning.
        var totalLines = lineNumber - 1
        if (currentLine.isNotEmpty()) {
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
        val text = if (truncated) {
            head.toString() + ConsoleLog.TRUNCATION_MARKER + tail.toString()
        } else {
            head.toString() + tail.toString()
        }
        return ConsoleLog(
            text = text,
            totalChars = totalChars,
            totalLines = totalLines,
            firstErrorLine = firstErrorLine,
            firstErrorText = firstErrorText,
            truncated = truncated,
            headChars = minOf(totalChars, headCapChars.toLong()),
            tailChars = if (truncated) tail.length.toLong() else maxOf(0L, totalChars - headCapChars),
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
 * mid-escape. An unterminated note or a trailing partial preamble at the end of the stream is
 * dropped, exactly as the string version does.
 */
internal class NoteStripper {
    private val pending = StringBuilder()
    private var inNote = false
    private var epilogueMatch = 0

    /** Feed one character; returns the display characters it produced (possibly empty). */
    fun accept(c: Char): String {
        if (inNote) {
            if (c == EPILOGUE[epilogueMatch]) {
                epilogueMatch++
                if (epilogueMatch == EPILOGUE.length) {
                    inNote = false
                    epilogueMatch = 0
                }
            } else {
                epilogueMatch = if (c == EPILOGUE[0]) 1 else 0
            }
            return ""
        }

        pending.append(c)
        val out = StringBuilder()
        while (pending.isNotEmpty() && !PREAMBLE.startsWith(pending.toString())) {
            out.append(pending[0])
            pending.deleteCharAt(0)
        }
        if (pending.length == PREAMBLE.length) {
            pending.setLength(0)
            inNote = true
            epilogueMatch = 0
        }
        return out.toString()
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
