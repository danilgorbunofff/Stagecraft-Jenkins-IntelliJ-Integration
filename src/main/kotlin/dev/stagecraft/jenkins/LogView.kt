package dev.stagecraft.jenkins

/** How a line of the shown document is striped (§7.2: error and warning lines stand out). */
enum class LineKind { ERROR, WARNING }

/** A document-line stripe: 0-based line in the shown document, and what kind of line it is. */
data class Stripe(val line: Int, val kind: LineKind)

/** What the editor must do after a [LogView] change. */
sealed interface LogChange {
    /** Replace the whole document with [text]. */
    data class Replace(val text: String, val stripes: List<Stripe>) : LogChange

    /** Append [text] at the end of the document; [stripes] are already in document lines. */
    data class Append(val text: String, val stripes: List<Stripe>) : LogChange

    /** Nothing visible changed. */
    data object None : LogChange
}

/**
 * The log panel's model: the retained log, the current filter, and the exact mapping between
 * full-log line numbers and the lines the editor shows (§7.2, §9.5, §9.7).
 *
 * It exists so the panel never has to reason about any of this on the EDT, and so it is tested:
 *
 *  * **Memory stays capped while tailing.** Live output is appended to a body that is trimmed back to
 *    [tailCapChars] of whole lines whenever it outgrows twice that, and the first time the log
 *    outgrows head + tail the middle is dropped exactly as [ConsoleLogReader] drops it. A build that
 *    streams for hours holds the same bounded text as one opened after it finished.
 *  * **The filter applies to live output.** In a filtered mode only complete new lines are filtered
 *    and appended; a half-written last line waits for its newline (or for the build to finish).
 *  * **Line numbers survive truncation and filtering.** A full-log line number is mapped through the
 *    dropped middle and the filter ([documentLine]), so a click lands on that line or, when it is not
 *    shown, on the nearest shown line after it.
 *  * **Work per tick is proportional to the delta**, never to the whole log.
 *
 * The marker line that stands for a dropped middle is kept by every filter: truncation is never
 * silent (§9.7).
 */
class LogView(
    log: ConsoleLog,
    private val headCapChars: Int = ConsoleLogReader.DEFAULT_HEAD_CHARS,
    private val tailCapChars: Int = ConsoleLogReader.DEFAULT_TAIL_CHARS,
    private val maxStripes: Int = DEFAULT_MAX_STRIPES,
) {

    private var head = ""
    private val body = StringBuilder()
    private var marker = ""
    private var truncated = false
    private var headLines = 0
    private var droppedLines = 0
    private var droppedChars = 0L

    /** Lines of the full log seen so far, a trailing unterminated line included. */
    var totalLines: Int = 0
        private set

    var firstErrorLine: Int? = null
        private set

    var mode: LogFilterMode = LogFilterMode.ALL
        private set
    var collapseBlankLines: Boolean = false
        private set
    private var ownPackageTokens: List<String> = emptyList()

    /** The build still streams: a half-written last line is not final yet. */
    var live: Boolean = false
        private set

    // ---- what the document currently shows
    /** For each document line, the display line it came from (ascending; identity in raw mode). */
    private var sourceLines = IntArray(1024)
    private var documentLines = 0
    private var wroteAny = false
    private var lastBlank = false
    private var stripeCount = 0

    /** Display offset of the first line the document has not taken in completely yet. */
    private var pendingOffset = 0

    /** Display line number of the line at [pendingOffset]. */
    private var pendingLine = 0

    init {
        load(log)
    }

    private val raw: Boolean get() = mode == LogFilterMode.ALL && !collapseBlankLines

    /** The unfiltered display text - head, marker when truncated, body - as a zero-copy view. */
    val display: CharSequence = object : CharSequence {
        override val length: Int get() = head.length + marker.length + body.length

        override fun get(index: Int): Char = when {
            index < head.length -> head[index]
            index < head.length + marker.length -> marker[index - head.length]
            else -> body[index - head.length - marker.length]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            val out = StringBuilder(endIndex - startIndex)
            for (i in startIndex until endIndex) out.append(get(i))
            return out
        }

        override fun toString(): String = head + marker + body
    }

    val isTruncated: Boolean get() = truncated

    /** Replace the whole log (Jenkins resent it from zero) and re-render. */
    fun reset(log: ConsoleLog): LogChange.Replace {
        load(log)
        return render()
    }

    /** Change the filter and re-render the document. */
    fun setFilter(
        mode: LogFilterMode,
        collapseBlankLines: Boolean,
        ownPackageTokens: List<String> = emptyList(),
    ): LogChange.Replace {
        this.mode = mode
        this.collapseBlankLines = collapseBlankLines
        this.ownPackageTokens = ownPackageTokens
        return render()
    }

    /**
     * Whether the build is still streaming. When it stops, a last line with no newline is final, so
     * it is taken into a filtered view (a raw view already shows it).
     */
    fun setLive(live: Boolean): LogChange {
        val wasLive = this.live
        this.live = live
        return if (wasLive && !live) takeLines() else LogChange.None
    }

    /**
     * Live output arrived. Usually an append; a full replace when the body had to be trimmed to stay
     * under the memory cap.
     */
    fun append(delta: String): LogChange {
        if (delta.isEmpty()) return LogChange.None
        val endedMidLine = body.isNotEmpty() && body[body.length - 1] != '\n'
        val completeBefore = totalLines - if (endedMidLine) 1 else 0
        val deltaStart = display.length
        body.append(delta)
        totalLines = completeBefore + ConsoleLog.countNewlines(delta) + if (delta.last() != '\n') 1 else 0
        if (firstErrorLine == null) findFirstError(lineStart(deltaStart), completeBefore + 1)
        if (trimIfNeeded()) return render()
        if (!raw) return takeLines()
        val change = takeLines()
        return LogChange.Append(delta, (change as? LogChange.Append)?.stripes.orEmpty())
    }

    /** The full document for the current mode. */
    fun render(): LogChange.Replace {
        documentLines = 0
        wroteAny = false
        lastBlank = false
        stripeCount = 0
        pendingOffset = 0
        pendingLine = 0
        val change = takeLines()
        return if (raw) {
            LogChange.Replace(display.toString(), (change as? LogChange.Append)?.stripes.orEmpty())
        } else {
            LogChange.Replace((change as? LogChange.Append)?.text.orEmpty(), (change as? LogChange.Append)?.stripes.orEmpty())
        }
    }

    /**
     * 0-based document line for the 1-based full-log [originalLine]: exact when that line is shown,
     * otherwise the nearest shown line after it (a filtered-out line, or the marker for a line in the
     * dropped middle). Null when nothing at or after it is shown.
     */
    fun documentLine(originalLine: Int): Int? {
        val display = displayLineOf(originalLine) ?: return null
        return documentLineOfDisplay(display)
    }

    /** Where the first error is shown - exactly, or null when there is none or it is not shown. */
    fun firstErrorDocumentLine(): Int? {
        val line = firstErrorLine ?: return null
        val display = displayLineOf(line) ?: return null
        if (truncated && display == headLines && line <= headLines + droppedLines) return null // dropped
        val document = documentLineOfDisplay(display) ?: return null
        return document.takeIf { sourceLines[it] == display }
    }

    /**
     * The document line where stage [name] opens (`[Pipeline] { (name)` at the start of a line).
     * Used when stage view supplied the stage but no line range (§9.3 wfapi path).
     */
    fun stageDocumentLine(name: String): Int? {
        val needle = "[Pipeline] { ($name)"
        var index = display.indexOf(needle)
        while (index > 0 && display[index - 1] != '\n') index = display.indexOf(needle, index + 1)
        if (index < 0) return null
        return documentLineOfDisplay(ConsoleLog.countNewlines(display.subSequence(0, index)))
    }

    // ---------------------------------------------------------------- internals

    private fun load(log: ConsoleLog) {
        truncated = log.truncated
        head = if (log.truncated) log.head else ""
        body.setLength(0)
        body.append(if (log.truncated) log.tail else log.head + log.tail)
        headLines = if (log.truncated) ConsoleLog.countNewlines(head) else 0
        droppedLines = log.droppedLines
        droppedChars = log.droppedChars
        marker = if (truncated) ConsoleLog.markerLine(droppedLines, droppedChars) else ""
        totalLines = log.totalLines
        firstErrorLine = log.firstErrorLine
    }

    private val markerLines: Int get() = if (truncated) 1 else 0

    /** First full-log line held in [body]. */
    private val bodyFirstLine: Int get() = headLines + droppedLines + 1

    private fun displayLineOf(originalLine: Int): Int? {
        if (originalLine < 1 || originalLine > totalLines) return null
        return when {
            originalLine <= headLines -> originalLine - 1
            originalLine >= bodyFirstLine -> headLines + markerLines + (originalLine - bodyFirstLine)
            else -> headLines // dropped: the marker line stands for it
        }
    }

    private fun documentLineOfDisplay(display: Int): Int? {
        // sourceLines[0 until documentLines] ascends; binary-search the first entry >= display.
        var low = 0
        var high = documentLines - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sourceLines[mid] >= display) {
                found = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return found.takeIf { it >= 0 }
    }

    /** Start offset of the display line containing [offset]. */
    private fun lineStart(offset: Int): Int {
        var i = offset - 1
        while (i >= 0 && display[i] != '\n') i--
        return i + 1
    }

    /** Scan complete lines from display [offset] (full-log line [lineNumber]) for the first error. */
    private fun findFirstError(offset: Int, lineNumber: Int) {
        var start = offset
        var line = lineNumber
        val end = display.length
        for (i in offset..end) {
            val atEnd = i == end
            if (!atEnd && display[i] != '\n') continue
            if (atEnd && (start >= end || live)) return
            if (ConsoleStages.isErrorLine(display.subSequence(start, i).toString())) {
                firstErrorLine = line
                return
            }
            line++
            start = i + 1
        }
    }

    /** Keep memory capped; true when the document has to be re-rendered. */
    private fun trimIfNeeded(): Boolean {
        if (!truncated) {
            if (body.length.toLong() <= headCapChars.toLong() + tailCapChars) return false
            val headEnd = body.lastIndexOf("\n", headCapChars).let { if (it < 0) 0 else it + 1 }
            val tailStart = nextLineStart(body.length - tailCapChars).coerceAtLeast(headEnd)
            head = body.substring(0, headEnd)
            headLines = ConsoleLog.countNewlines(head)
            droppedLines = ConsoleLog.countNewlines(body.subSequence(headEnd, tailStart))
            droppedChars = (tailStart - headEnd).toLong()
            body.delete(0, tailStart)
            truncated = true
        } else {
            if (body.length <= tailCapChars * 2) return false
            val tailStart = nextLineStart(body.length - tailCapChars)
            droppedLines += ConsoleLog.countNewlines(body.subSequence(0, tailStart))
            droppedChars += tailStart
            body.delete(0, tailStart)
        }
        marker = ConsoleLog.markerLine(droppedLines, droppedChars)
        return true
    }

    private fun nextLineStart(from: Int): Int {
        val newline = body.indexOf("\n", from.coerceAtLeast(0))
        return if (newline < 0 || newline + 1 >= body.length) from.coerceAtLeast(0) else newline + 1
    }

    /**
     * Take every display line from [pendingOffset] on into the document: complete lines always, a
     * trailing unterminated line only once the build is no longer live. In raw mode every line maps
     * to itself and only stripes are computed; in a filtered mode the kept lines are the text.
     */
    private fun takeLines(): LogChange {
        val out = StringBuilder()
        val newStripes = ArrayList<Stripe>()
        val end = display.length
        val markerLine = if (truncated) headLines else -1
        var start = pendingOffset
        for (i in pendingOffset..end) {
            val atEnd = i == end
            if (!atEnd && display[i] != '\n') continue
            if (atEnd && (start >= end || live)) break
            val content = display.subSequence(start, i).toString()
            if (raw) {
                ensureSourceCapacity(pendingLine + 1)
                sourceLines[pendingLine] = pendingLine
                documentLines = pendingLine + 1
                stripe(pendingLine, content, newStripes)
            } else if (pendingLine == markerLine || LogFilter.keeps(content, mode, ownPackageTokens)) {
                val normalised = if (collapseBlankLines) content.trimEnd() else content
                val blank = normalised.isEmpty()
                if (!(collapseBlankLines && blank && lastBlank)) {
                    if (wroteAny) out.append('\n')
                    out.append(normalised)
                    wroteAny = true
                    lastBlank = blank
                    ensureSourceCapacity(documentLines + 1)
                    sourceLines[documentLines] = pendingLine
                    stripe(documentLines, normalised, newStripes)
                    documentLines++
                }
            }
            pendingLine++
            pendingOffset = if (atEnd) end else i + 1
            start = i + 1
            if (atEnd) break
        }
        if (raw) {
            // A raw document also shows the half-written last line; keep it addressable.
            val tailLine = display.length > pendingOffset
            if (tailLine) {
                ensureSourceCapacity(pendingLine + 1)
                sourceLines[pendingLine] = pendingLine
                documentLines = pendingLine + 1
            }
            return if (newStripes.isEmpty()) LogChange.None else LogChange.Append("", newStripes)
        }
        return if (out.isEmpty() && newStripes.isEmpty()) LogChange.None else LogChange.Append(out.toString(), newStripes)
    }

    private fun stripe(documentLine: Int, content: String, into: MutableList<Stripe>) {
        if (stripeCount >= maxStripes) return
        val kind = when {
            ConsoleStages.isErrorLine(content) -> LineKind.ERROR
            LogFilter.isWarning(content) -> LineKind.WARNING
            else -> return
        }
        into += Stripe(documentLine, kind)
        stripeCount++
    }

    private fun ensureSourceCapacity(size: Int) {
        if (size <= sourceLines.size) return
        var capacity = sourceLines.size
        while (capacity < size) capacity *= 2
        sourceLines = sourceLines.copyOf(capacity)
    }

    companion object {
        /** An editor with a highlighter on every line is unusable; stripes stop here. */
        const val DEFAULT_MAX_STRIPES = 5_000
    }
}
