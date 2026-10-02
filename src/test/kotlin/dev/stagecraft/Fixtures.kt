package dev.stagecraft

import dev.stagecraft.jenkins.HttpResponse
import java.io.File

/**
 * The Day-0 fixtures are the test oracle: everything in this suite runs against what a real
 * Jenkins actually sent, recorded in `docs/fixtures`, with no server running.
 */
object Fixtures {

    val root: File = File(System.getProperty("stagecraft.fixtures") ?: "docs/fixtures")

    fun file(name: String): File = File(root, name)

    fun bytes(name: String): ByteArray {
        val file = file(name)
        check(file.isFile) {
            "missing fixture '${file.absolutePath}' - the tests must run from the project directory " +
                "with -Dstagecraft.fixtures pointing at docs/fixtures"
        }
        return file.readBytes()
    }

    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)

    /** A body-only fixture as an HTTP response. */
    fun json(name: String, status: Int = 200, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(status, headers.toList(), bytes(name))

    fun of(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(status, headers.toList(), body.toByteArray(Charsets.UTF_8))

    fun empty(status: Int = 200, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(status, headers.toList(), ByteArray(0))

    /** A `curl -D` style dump: status line (optional), headers, blank line, body. */
    class Recorded(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(name: String): String? =
            headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

        val text: String get() = String(body, Charsets.UTF_8)

        /** The response as the client would have received it, headers included. */
        fun asResponse(): HttpResponse = HttpResponse(status, headers, body)
    }

    /**
     * Some recorded header dumps keep the status line and some do not (the ones captured through
     * nginx lose it), so the status defaults to 200 and is only read when it is there.
     */
    fun recorded(name: String): Recorded {
        val raw = text(name)
        val lines = ArrayList<String>()
        var index = 0
        var body = ""
        while (true) {
            val end = raw.indexOf('\n', index)
            if (end < 0) break
            var line = raw.substring(index, end)
            if (line.endsWith("\r")) line = line.dropLast(1)
            index = end + 1
            if (line.isEmpty()) {
                body = raw.substring(index)
                break
            }
            lines += line
        }
        check(lines.isNotEmpty()) { "fixture '$name' has no header block" }

        var status = 200
        var first = 0
        if (lines.first().startsWith("HTTP/")) {
            status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 200
            first = 1
        }
        val headers = ArrayList<Pair<String, String>>(lines.size)
        for (position in first until lines.size) {
            val separator = lines[position].indexOf(':')
            if (separator <= 0) continue
            headers += lines[position].substring(0, separator).trim() to
                lines[position].substring(separator + 1).trim()
        }
        return Recorded(status, headers, body.toByteArray(Charsets.UTF_8))
    }

    /** The header-only dumps, keyed by the fixture name the Day-0 record gives them. */
    val finishedFirstChunkHeaders: Recorded by lazy { recorded("10.headers.txt") }
    val runningFirstChunkHeaders: Recorded by lazy { recorded("16.progressive-running-first.headers.txt") }
    val runningDeltaHeaders: Recorded by lazy { recorded("rechecks/r1-running.delta.headers.txt") }
    val atEndHeaders: Recorded by lazy { recorded("rechecks/r1-finished.at-end.headers.txt") }
    val beyondEndHeaders: Recorded by lazy { recorded("rechecks/r1-finished.beyond-end.headers.txt") }
    val midLogHeaders: Recorded by lazy { recorded("rechecks/r1-finished.mid-log.headers.txt") }
    val badTokenHeaders: Recorded by lazy { recorded("rechecks/r6-bad-token.headers.txt") }
    val anonymousForbiddenHeaders: Recorded by lazy { recorded("rechecks/r7-anonymous.403.headers.txt") }
    val hiddenJobHeaders: Recorded by lazy { recorded("rechecks/r7-authenticated-no-item-read.404.headers.txt") }
    val bareServerMe: Recorded by lazy { recorded("rechecks/r10-check1.me.json") }
}
