package dev.stagecraft.jenkins

import java.io.ByteArrayOutputStream

/**
 * URL arithmetic for Jenkins, and the only place that knows the two ways Jenkins encodes things.
 *
 * The trap, measured at Day-0 and recorded in `docs/fixtures/rechecks` notes: a folder's child is
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
     * Jenkins builds every `url` and `absoluteUrl` it emits from *its* idea of the root - the
     * configured root URL, or whatever a proxy's forwarded headers told it - and that need not be the
     * address the IDE reaches it on. So the scheme, host and port are always ours. The path needs
     * more care, because both sides may carry a context path, and they need not be the same one:
     *
     *  1. the path already sits under our base's context path (`/jenkins/job/a/1/` against
     *     `https://ci/jenkins/`) - it is used as it stands. This is the common case of a Jenkins
     *     started with `--prefix=/jenkins`, and it is what makes rebasing idempotent: a URL that has
     *     been rebased once comes back unchanged;
     *  2. otherwise the path is cut at its first `/job/` segment, which is where the root ends in
     *     every job and build URL, and re-rooted on the base. This covers a proxy that maps `/ci/`
     *     onto a Jenkins at `/` or at `/jenkins/`;
     *  3. anything else (a root-level URL such as `/me/`) is taken as relative to the root.
     *
     * Prefer building URLs locally ([jobUrl]) wherever the raw path is known; this is for the URLs
     * we can only get from the server.
     */
    fun rebase(base: String, serverUrl: String): String {
        val value = serverUrl.trim()
        if (value.isEmpty()) return base
        val path: String
        val schemeEnd = value.indexOf("://")
        if (schemeEnd >= 0) {
            val pathStart = value.indexOf('/', schemeEnd + 3)
            if (pathStart < 0) return base
            path = value.substring(pathStart)
        } else if (value.startsWith("/")) {
            path = value
        } else {
            return base + value
        }

        val baseOrigin = origin(base)
        val basePath = base.substring(baseOrigin.length)
        if (basePath != "/" && path.startsWith(basePath)) return baseOrigin + path

        val job = path.indexOf("/job/")
        if (job >= 0) return base + path.substring(job + 1)
        return base + path.removePrefix("/")
    }

    /** `scheme://authority` of an absolute URL, without the path. */
    private fun origin(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    /**
     * The `name` Jenkins gives the branch job for [branch] - what `branch-api`'s `NameEncoder` does.
     *
     * Only the characters Jenkins refuses in an item name are percent-encoded (`/` becomes `%2F`, `%`
     * becomes `%25`, and so on); everything else, a space included, is kept as it is. That is the
     * spelling the job tree reports and the spelling [jobPath] expects, so a branch path built here
     * matches the one Jenkins would have listed. Encoding with [encodeSegment] instead would turn
     * `my branch` into `my%20branch`, which [jobPath] encodes again into a 404.
     */
    fun encodeItemName(branch: String): String {
        if (branch == ".") return "%2E"
        if (branch == "..") return "%2E%2E"
        val out = StringBuilder(branch.length + 8)
        for (char in branch) {
            if (char in UNSAFE_ITEM_CHARS) {
                out.append('%').append(HEX[char.code shr 4]).append(HEX[char.code and 0x0F])
            } else {
                out.append(char)
            }
        }
        return out.toString()
    }

    /** `Jenkins.checkGoodName`'s refused characters, which `NameEncoder` escapes. */
    private const val UNSAFE_ITEM_CHARS = "?*/\\%!@#$^&|<>[]:;"

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
