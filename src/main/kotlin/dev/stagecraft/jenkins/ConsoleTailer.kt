package dev.stagecraft.jenkins

/** One tail poll's result: the delta since the last poll, and whether the build is still running. */
data class TailDelta(
    val text: String,
    val moreData: Boolean,
    /** The cursor went backwards and Jenkins re-sent the whole log; the reader must reset. */
    val resetDetected: Boolean,
)

/**
 * Follows a running build's console with `logText/progressiveText`, appending only the delta
 * (§9.5).
 *
 * [offset] is the opaque `X-Text-Size` cursor from the previous chunk, never a byte count of the
 * body. A poll that returns no body while the build is still running is normal and leaves the
 * cursor where it is; a poll that finds the cursor behind [offset] means Jenkins reset to zero and
 * re-sent the whole log (asking past the end on a finished build), which is surfaced as
 * [TailDelta.resetDetected] rather than appended as a duplicate.
 *
 * Plain Kotlin over [JenkinsClient], so the protocol is unit-tested against the R1 fixtures.
 */
class ConsoleTailer(
    private val client: JenkinsClient,
    private val buildUrl: String,
    startOffset: Long = 0L,
) {

    var offset: Long = startOffset
        private set

    var finished: Boolean = false
        private set

    /** True when this tailer talks through [other]. */
    fun usesClient(other: JenkinsClient): Boolean = client === other

    /**
     * The same cursor on a new client (the settings changed mid-tail). The cursor is Jenkins' own
     * offset into the log, valid whichever session asks, so the log continues instead of restarting
     * from zero and being appended a second time.
     */
    fun continueWith(other: JenkinsClient): ConsoleTailer =
        ConsoleTailer(other, buildUrl, offset).also { it.finished = finished }

    fun poll(): TailDelta {
        if (finished) return TailDelta("", moreData = false, resetDetected = false)
        val chunk = client.progressiveText(buildUrl, offset)
        offset = chunk.nextOffset
        finished = !chunk.moreData
        return TailDelta(chunk.text, chunk.moreData, chunk.resetDetected)
    }
}
