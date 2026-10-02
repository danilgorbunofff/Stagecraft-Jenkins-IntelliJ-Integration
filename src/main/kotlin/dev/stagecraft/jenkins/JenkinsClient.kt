package dev.stagecraft.jenkins

import dev.stagecraft.model.BuildRef
import dev.stagecraft.model.BuildStatus
import dev.stagecraft.model.JobKind
import dev.stagecraft.model.JobNode
import java.io.OutputStream

/** Who Jenkins says we are. */
data class MeInfo(val id: String, val fullName: String?)

/**
 * The typed Jenkins API, as far as Days 1-2 need it.
 *
 * Nothing here builds a URL by string-joining a URL that Jenkins returned: job paths are composed
 * locally from the configured base (see [JenkinsUrls]) and build URLs are rebased. That is not
 * fastidiousness - Jenkins emits its own configured root, which is regularly not the address the
 * IDE reached it on (Day-0 re-check R4).
 */
class JenkinsClient(
    val baseUrl: String,
    val http: JenkinsHttp,
) {

    constructor(baseUrl: String, auth: JenkinsAuth, transport: HttpTransport? = null) :
        this(baseUrl, JenkinsHttp(baseUrl, auth, transport))

    val serverBaseUrl: String get() = http.baseUrl

    val version: JenkinsVersion? get() = http.lastJenkinsVersion

    /** A sentence to show when the server is older than the supported floor, or `null`. */
    val versionWarning: String?
        get() {
            val version = http.lastJenkinsVersion ?: return null
            if (!version.isBelowFloor) return null
            return "This server runs Jenkins $version, which is older than the supported " +
                "floor of ${JenkinsVersion.FLOOR}. Stagecraft will keep working, but some panels " +
                "may be empty because the API it relies on did not exist yet."
        }

    /**
     * §9.2: `GET {base}/me/api/json`, and the answer must be the configured user. A 200 on its own
     * proves nothing, because a server that allows anonymous read answers as `anonymous`.
     */
    fun me(): MeInfo {
        val url = JenkinsUrls.apiJson(http.baseUrl + "me/", "id,fullName")
        val obj = parseBody(url, http.get(url))
        val id = obj.str("id")
            ?: throw JenkinsException.Malformed(url, "the response carried no 'id'")
        return MeInfo(id, obj.str("fullName"))
    }

    /** Verify the credentials against the configured user, throwing a readable error if not. */
    fun verifyCredentials(): MeInfo {
        val info = me()
        if (!http.auth.matchesConfiguredUser(info.id)) {
            throw JenkinsException.Malformed(
                JenkinsUrls.apiJson(http.baseUrl + "me/", "id"),
                "Jenkins authenticated the request as '${info.id}', not as " +
                    "'${http.auth.credential.user}'. The server is answering anonymous or " +
                    "unauthenticated reads, so this connection is not actually authenticated.",
            )
        }
        return info
    }

    /** Top-level jobs only. */
    fun rootJobs(): List<JobNode> = jobTree(1)

    /**
     * The whole job tree in **one** request, recursing [depth] levels.
     *
     * §9.4: Jenkins has no search-by-SCM endpoint, so discovery is a nested `tree` query. `url` is
     * deliberately not requested - it is the largest field in the payload and it is also the one we
     * cannot use, since Jenkins computes it from its own root URL. It would multiply the response
     * size for a value we have to rebuild anyway.
     */
    fun jobTree(depth: Int = DEFAULT_TREE_DEPTH): List<JobNode> {
        require(depth >= 1) { "depth must be at least 1" }
        val tree = nestedJobTree(depth)
        val url = JenkinsUrls.apiJson(http.baseUrl, tree)
        return parseTree(url, http.get(url), emptyList(), emptyList(), 1, depth)
    }

    /**
     * Direct children of a container job - the folders and, for a multibranch project, the branch
     * jobs. One request, same parser as [jobTree].
     */
    fun childJobs(rawPath: List<String>): List<JobNode> {
        if (rawPath.isEmpty()) return rootJobs()
        val parentUrl = JenkinsUrls.jobUrl(http.baseUrl, rawPath)
        val url = JenkinsUrls.apiJson(parentUrl, "jobs[name,_class,color]")
        return parseTree(url, http.get(url), rawPath, rawPath.map { JenkinsUrls.decodeSegment(it) }, rawPath.size + 1, 1)
    }

    /** Recent builds of one job, newest first, as Jenkins orders them. */
    fun builds(rawPath: List<String>, limit: Int = DEFAULT_BUILD_LIMIT): List<BuildRef> {
        val tree = "builds[_class,number,url,result,building,timestamp,duration]{0,$limit}"
        val url = JenkinsUrls.apiJson(JenkinsUrls.jobUrl(http.baseUrl, rawPath), tree)
        val obj = parseBody(url, http.get(url))
        val fullName = rawPath.joinToString("/") { JenkinsUrls.decodeSegment(it) }
        return obj.objects("builds").mapNotNull { build ->
            val number = build.int("number") ?: return@mapNotNull null
            BuildRef(
                jobFullName = fullName,
                jobRawPath = rawPath,
                number = number,
                url = JenkinsUrls.rebase(
                    http.baseUrl,
                    build.str("url") ?: JenkinsUrls.jobUrl(http.baseUrl, rawPath) + "$number/",
                ),
                status = BuildStatus.fromResult(build.str("result"), build.bool("building") ?: false),
                timestampMillis = build.long("timestamp") ?: 0L,
                durationMillis = build.long("duration") ?: 0L,
            )
        }
    }

    /**
     * The whole console, buffered. Refuses to exceed [maxBytes] rather than filling the heap -
     * §9.7 wants a 50 MB log to be announced, not to take the IDE down. Use [streamConsoleText]
     * for anything that might be large.
     */
    fun consoleText(buildUrl: String, maxBytes: Long = DEFAULT_CONSOLE_BUFFER_LIMIT): String {
        val url = buildScopedUrl(buildUrl, "consoleText")
        http.stream(url).use { response ->
            val buffered = response.toBuffered(maxBytes)
            requireOk(buffered, url)
            return ConsoleText.normaliseLineEndings(buffered.text)
        }
    }

    /** Stream the console into [out] without ever holding it. Returns the byte count written. */
    fun streamConsoleText(
        buildUrl: String,
        out: OutputStream,
        maxBytes: Long = Long.MAX_VALUE,
    ): Long {
        val url = buildScopedUrl(buildUrl, "consoleText")
        http.stream(url).use { response ->
            if (!response.isSuccess) requireOk(response.toBuffered(ERROR_BODY_LIMIT), url)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = response.stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw JenkinsException.Malformed(url, "the console exceeds the $maxBytes byte limit")
                }
                out.write(buffer, 0, read)
            }
            out.flush()
            return total
        }
    }

    /**
     * One poll of a running build's console.
     *
     * [start] must be the `nextOffset` of the previous chunk, or 0 to read from the beginning.
     * `X-Text-Size` is an opaque cursor, not a byte count of the body: on the reference build the
     * first chunk is 12336 bytes and reports 12263, because the cursor counts the CRLF-normalised
     * stream while the body carries CR bytes and console-note payloads. Never compute the next
     * offset from the body length (§9.5).
     */
    fun progressiveText(buildUrl: String, start: Long = 0L): ConsoleChunk {
        val url = buildScopedUrl(buildUrl, "logText/progressiveText?start=$start")
        val response = http.get(url)
        val text = requireOk(response, url)
        val cursor = response.header("X-Text-Size")?.trim()?.toLongOrNull()
            ?: throw JenkinsException.Malformed(
                url,
                "the response carried no usable X-Text-Size header, so the console cursor cannot be advanced",
            )
        return ConsoleChunk(
            text = ConsoleText.toDisplayText(text),
            rawByteCount = response.body.size,
            nextOffset = cursor,
            // Absent means finished. Jenkins omits the header rather than sending `false`, so
            // testing for `false` would loop forever on a completed build (Day-0 re-check R1).
            moreData = response.header("X-More-Data")?.trim().equals("true", ignoreCase = true),
            resetDetected = start > 0 && cursor < start,
        )
    }

    /** `{buildUrl}api/json?tree=…`, tolerating a build URL that does or does not end in a slash. */
    fun buildApiJson(buildUrl: String, tree: String): String =
        JenkinsUrls.apiJson(buildScopedUrl(buildUrl, ""), tree)

    private fun buildScopedUrl(buildUrl: String, suffix: String): String {
        val rebased = JenkinsUrls.rebase(http.baseUrl, buildUrl).trimEnd('/') + "/"
        return if (suffix.isEmpty()) rebased else rebased + suffix
    }

    private fun parseTree(
        url: String,
        response: HttpResponse,
        parentRawPath: List<String>,
        parentDisplayPath: List<String>,
        firstDepth: Int,
        maxDepth: Int,
    ): List<JobNode> {
        val obj = parseBody(url, response)
        return parseJobs(obj.objects("jobs"), parentRawPath, parentDisplayPath, firstDepth, maxDepth)
    }

    private fun parseJobs(
        jobs: List<kotlinx.serialization.json.JsonObject>,
        parentRawPath: List<String>,
        parentDisplayPath: List<String>,
        depth: Int,
        maxDepth: Int,
    ): List<JobNode> {
        val result = ArrayList<JobNode>(jobs.size)
        for (job in jobs) {
            val rawName = job.str("name") ?: continue
            val rawPath = parentRawPath + rawName
            val displayName = JenkinsUrls.decodeSegment(rawName)
            val displayPath = parentDisplayPath + displayName
            val className = job.str("_class")
            val kind = JobKind.fromClass(className)
            result += JobNode(
                name = rawName,
                displayName = displayName,
                fullName = displayPath.joinToString("/"),
                rawPath = rawPath,
                className = className,
                kind = kind,
                url = JenkinsUrls.jobUrl(http.baseUrl, rawPath),
                depth = depth,
                colour = job.str("color"),
            )
            val children = job.objects("jobs")
            if (children.isNotEmpty() && depth < maxDepth) {
                result += parseJobs(children, rawPath, displayPath, depth + 1, maxDepth)
            }
        }
        return result
    }

    /** `tree=jobs[name,_class,jobs[name,_class,…]]`, three levels deep by default (§9.4). */
    private fun nestedJobTree(depth: Int): String {
        val level = "name,_class,color"
        var tree = level
        for (ignored in 2..depth) tree = "$level,jobs[$tree]"
        return "jobs[$tree]"
    }

    /**
     * Turn any status into an exception, or return the body. Every failure mode is distinguished
     * because the fix differs: 401 is credentials, 403 is permissions or a crumb, 404 is missing
     * **or hidden**, and a 3xx is SSO.
     */
    private fun requireOk(response: HttpResponse, url: String): String {
        if (response.isSuccess) return response.text
        when (response.status) {
            401 -> throw JenkinsException.Unauthorized(url, response.header("WWW-Authenticate"))
            403 -> {
                val title = response.title.ifEmpty { "the server did not explain why" }
                throw JenkinsException.Forbidden(
                    url = url,
                    detail = title,
                    crumbProblem = response.text.contains(JenkinsHttp.NO_VALID_CRUMB, ignoreCase = true),
                    crumbError = http.auth.lastCrumbError,
                )
            }
            404 -> throw JenkinsException.NotFound(url)
            in 300..399 -> {
                val location = response.header("Location") ?: "(no Location header)"
                if (looksLikeLogin(location)) throw JenkinsException.SsoRedirect(url, location)
                throw JenkinsException.UnexpectedStatus(response.status, url, "redirect to $location")
            }
            else -> throw JenkinsException.UnexpectedStatus(response.status, url, excerpt(response.text))
        }
    }

    private fun looksLikeLogin(location: String): Boolean {
        val lower = location.lowercase()
        return lower.contains("login") || lower.contains("securityrealm") || lower.contains("saml") ||
            lower.contains("oauth") || lower.contains("adfs") || lower.contains("sso")
    }

    private fun excerpt(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= 200) collapsed else collapsed.take(200) + "…"
    }

    private fun parseBody(url: String, response: HttpResponse): kotlinx.serialization.json.JsonObject {
        val text = requireOk(response, url)
        return try {
            parseJsonObject(text)
        } catch (e: JenkinsException) {
            throw JenkinsException.Malformed(url, e.message ?: "unreadable response")
        }
    }

    companion object {
        /** §9.4 asks for three levels of recursion in one request. */
        const val DEFAULT_TREE_DEPTH = 3

        const val DEFAULT_BUILD_LIMIT = 25

        /** Enough for a normal console, small enough that a runaway job cannot exhaust the heap. */
        const val DEFAULT_CONSOLE_BUFFER_LIMIT = 8L * 1024 * 1024

        private const val ERROR_BODY_LIMIT = 1L * 1024 * 1024
    }
}
