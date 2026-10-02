package dev.stagecraft.jenkins

import java.io.ByteArrayOutputStream

/**
 * URL arithmetic for Jenkins, and the only place that knows the two ways Jenkins encodes things.
 *
 * The trap, measured at Day-0 and recorded in `docs/fixtures/recheck` notes: a folder's child is
 * reported as `"name": "feature%2FORD-214"` - already percent-encoded once - while its URL is
 * `.../job/feature%252FORD-214/`, which is that name encoded a *second* time. So the rule is:
 * take `name` verbatim as the identity, and percent-encode it exactly once more when it goes into
 * a URL path. Encoding an already-decoded branch name instead would produce `feature/ORD-214`,
 * which Jenkins reads as two path levels and answers with a 404.
 */
object JenkinsUrls {

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    private const val HEX = "0123456789ABCDEF"

    /**
     * Turn whatever the user typed into a usable base: `ci.example.com` and `https://ci.example.com`
     * and `https://ci.example.com/jenkins/` are all accepted. A missing scheme becomes `https`, and
     * the result always ends with `/`.
     */
    fun normalizeBase(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) throw JenkinsException.Malformed(raw, "the server address is empty")
        if (!value.contains("://")) value = "https://$value"
        val scheme = value.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") {
            throw JenkinsException.Malformed(raw, "unsupported URL scheme '$scheme'; use http or https")
        }
        value = value.substringBefore('#').substringBefore('?')
        val authorityStart = value.indexOf("://") + 3
        val authorityEnd = value.indexOf('/', authorityStart).let { if (it < 0) value.length else it }
        if (authorityEnd == authorityStart) throw JenkinsException.Malformed(raw, "the server address has no host")
        val path = value.substring(authorityEnd)
        return scheme + value.substring(value.indexOf("://"), authorityEnd) + path.trimEnd('/') + "/"
    }

    /**
     * Percent-encode one path segment. Everything outside RFC 3986's unreserved set is escaped,
     * including `%` itself - which is what turns an already-encoded `feature%2FORD-214` into
     * `feature%252FORD-214`.
     *
     * Spaces become `%20` and not `+`: `+` means a space only in a query string, and Jenkins'
     * own URLs use `%20`.
     */
    fun encodeSegment(segment: String): String {
        val bytes = segment.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (value < 0x80 && UNRESERVED.indexOf(char) >= 0) {
                out.append(char)
            } else {
                out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    /** Reverse of [encodeSegment], used for display names only - never for building a URL. */
    fun decodeSegment(segment: String): String {
        if (segment.indexOf('%') < 0) return segment
        val out = ByteArrayOutputStream(segment.length)
        var index = 0
        while (index < segment.length) {
            val char = segment[index]
            if (char == '%') {
                val high = hexValue(segment.getOrNull(index + 1))
                val low = hexValue(segment.getOrNull(index + 2))
                if (high >= 0 && low >= 0) {
                    out.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }
            out.write(char.toString().toByteArray(Charsets.UTF_8))
            index++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * The path part of a job URL: `job/a/job/b/`. [rawNames] are the `name` values as Jenkins
     * reported them, root first.
     */
    fun jobPath(rawNames: List<String>): String {
        val out = StringBuilder()
        for (name in rawNames) {
            out.append("job/").append(encodeSegment(name)).append('/')
        }
        return out.toString()
    }

    /** Absolute URL of a job, built locally against [base]. */
    fun jobUrl(base: String, rawNames: List<String>): String = base + jobPath(rawNames)

    /**
     * Move a URL that Jenkins produced onto the base we actually talk to.
     *
     * Jenkins builds every `url` and `absoluteUrl` it emits from its own configured root URL, so a
     * server reached at `https://localhost:18443/` through nginx answers with `https://localhost:18443/`
     * even when the request arrived at `http://localhost:18080/` - and vice versa (Day-0 re-check R4).
     * Only the path and query survive; the scheme, host and port are ours.
     */
    fun rebase(base: String, serverUrl: String): String {
        val value = serverUrl.trim()
        if (value.isEmpty()) return base
        val schemeEnd = value.indexOf("://")
        if (schemeEnd >= 0) {
            val authorityStart = schemeEnd + 3
            val pathStart = value.indexOf('/', authorityStart)
            return if (pathStart < 0) base else base + value.substring(pathStart + 1)
        }
        return if (value.startsWith("/")) base + value.substring(1) else base + value
    }

    /** `{url}api/json?tree={tree}` with the tree query escaped. */
    fun apiJson(url: String, tree: String): String =
        url.trimEnd('/') + "/api/json?tree=" + encodeQueryValue(tree)

    /**
     * Encode a query-string value.
     *
     * Deliberately identical to [encodeSegment], and that is the whole point: RFC 3986 allows only
     * `pchar` in a query, and `[` and `]` are not `pchar`. A raw `tree=jobs[name]` is therefore
     * rejected outright by `java.net.URI`, so the brackets have to be percent-encoded even though
     * Jenkins' own UI sends them raw. Jenkins decodes the parameter before reading it, so `%5B`
     * arrives as `[` on the server.
     */
    fun encodeQueryValue(value: String): String = encodeSegment(value)

    private fun hexValue(char: Char?): Int = when (char) {
        null -> -1
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
