package dev.stagecraft.jenkins

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProxySelector
import java.net.URI
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/** One HTTP request. A plain class rather than a data class because of the byte array body. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun toString(): String = "$method $url"
}

/** One buffered HTTP response. Header lookup is case-insensitive, as HTTP requires. */
class HttpResponse(
    val status: Int,
    val headerEntries: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? =
        headerEntries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun headers(name: String): List<String> =
        headerEntries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    val text: String get() = String(body, Charsets.UTF_8)

    val isSuccess: Boolean get() = status in 200..299

    /** The `<title>` of an error page, which is the most readable thing Jenkins sends on a failure. */
    val title: String
        get() {
            val html = text
            val start = html.indexOf("<title>", ignoreCase = true)
            if (start < 0) return ""
            val end = html.indexOf("</title>", start, ignoreCase = true)
            if (end < 0) return ""
            return html.substring(start + 7, end).trim()
        }

    fun toStreaming(): StreamingResponse =
        StreamingResponse(status, headerEntries, body.inputStream())

    override fun toString(): String = "HTTP $status, ${body.size} bytes"
}

/**
 * A response whose body is still on the wire. §9.7 forbids holding a whole log in memory, so the
 * console reader uses this rather than [HttpResponse].
 */
class StreamingResponse(
    val status: Int,
    val headerEntries: List<Pair<String, String>>,
    val stream: InputStream,
    private val onClose: () -> Unit = {},
) : AutoCloseable {

    fun header(name: String): String? =
        headerEntries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val isSuccess: Boolean get() = status in 200..299

    /** Read the whole body, refusing to buffer more than [maxBytes]. */
    fun toBuffered(maxBytes: Long = Long.MAX_VALUE): HttpResponse {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw JenkinsException.Malformed(
                    header("X-Request-Url") ?: "<stream>",
                    "the response body exceeds the $maxBytes byte limit",
                )
            }
            out.write(buffer, 0, read)
        }
        return HttpResponse(status, headerEntries, out.toByteArray())
    }

    override fun close() {
        try {
            stream.close()
        } finally {
            onClose()
        }
    }
}

/**
 * The seam that makes this package testable without a server. `JenkinsHttp` never opens a socket
 * itself; it asks a transport, and the tests hand it recorded Day-0 responses instead.
 */
fun interface HttpTransport {

    fun execute(request: HttpRequest): HttpResponse

    /** Default implementation buffers, which is fine for everything except a large console. */
    fun executeStreaming(request: HttpRequest): StreamingResponse = execute(request).toStreaming()

    fun close() {}
}

/**
 * A per-server cookie store.
 *
 * `java.net.CookieHandler.setDefault` is process-global, which is unacceptable inside an IDE, so
 * the jar is explicit and owned by one [JenkinsHttp]. Jenkins hands out `JSESSIONID` and
 * `remember-me`; a password-auth crumb is bound to the session, so these cookies are not optional.
 *
 * A cookie whose `Expires` is in the past, whose `Max-Age` is zero, or whose value is empty is a
 * *deletion*: Jenkins uses exactly that to clear the session when it rejects a token (Day-0
 * re-check R6, `Set-Cookie: remember-me=…; Expires=Thu, 01 Jan 1970`).
 */
class CookieJar {

    private class Entry(val value: String, val path: String)

    private val cookies = LinkedHashMap<String, Entry>()

    val size: Int get() = cookies.size

    fun names(): Set<String> = cookies.keys.toSet()

    fun clear() = cookies.clear()

    fun store(setCookieHeaders: List<String>, nowMillis: Long = System.currentTimeMillis()) {
        for (raw in setCookieHeaders) {
            val parts = raw.split(';')
            val nameValue = parts.firstOrNull()?.trim().orEmpty()
            val separator = nameValue.indexOf('=')
            if (separator <= 0) continue
            val name = nameValue.substring(0, separator).trim()
            val value = nameValue.substring(separator + 1).trim()
            if (name.isEmpty()) continue

            var path = "/"
            var deleted = value.isEmpty()
            for (index in 1 until parts.size) {
                val attribute = parts[index].trim()
                val lower = attribute.lowercase()
                when {
                    lower.startsWith("path=") -> path = attribute.substring(5).trim().ifEmpty { "/" }
                    lower.startsWith("max-age=") -> {
                        val seconds = attribute.substring(8).trim().toLongOrNull()
                        if (seconds != null && seconds <= 0) deleted = true
                    }
                    lower.startsWith("expires=") -> if (isExpired(attribute.substring(8).trim(), nowMillis)) deleted = true
                }
            }
            if (deleted) cookies.remove(name) else cookies[name] = Entry(value, path)
        }
    }

    /** The `Cookie` header for a request to [path], or `null` when the jar is empty. */
    fun headerValueFor(path: String): String? {
        val applicable = cookies.entries.filter { path.startsWith(it.value.path) }
        if (applicable.isEmpty()) return null
        return applicable.joinToString("; ") { "${it.key}=${it.value.value}" }
    }

    private fun isExpired(value: String, nowMillis: Long): Boolean {
        if (value.isEmpty()) return false
        if (value == "0") return true
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() <= nowMillis
        } catch (_: Exception) {
            // An Expires we cannot read is treated as a deletion: keeping a cookie we do not
            // understand is the failure that leaks a session into the wrong place.
            true
        }
    }
}

/**
 * The only place in Stagecraft that opens a socket.
 *
 * Two rules from §9.1 live here:
 *
 *  * No IntelliJ imports. Proxy and TLS arrive as a [ProxySelector] and an [SSLContext], so this
 *    class has no opinion about where they came from.
 *  * Redirects are **not** followed. §9.2 wants an SSO redirect to be visible as a redirect, not
 *    silently followed into an HTML login page; a 3xx is returned to the caller as a 3xx.
 */
class UrlConnectionTransport(
    private val proxySelector: ProxySelector? = null,
    private val sslContext: SSLContext? = null,
    private val connectTimeoutMillis: Int = JenkinsHttp.DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = JenkinsHttp.DEFAULT_READ_TIMEOUT_MS,
    private val cookieJar: CookieJar = CookieJar(),
) : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val connection = open(request)
        try {
            write(connection, request)
            val status = connection.responseCode
            val entries = headerEntries(connection)
            cookieJar.store(connection.headerFields["Set-Cookie"].orEmpty())
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpResponse(status, entries, stream?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: IOException) {
            throw JenkinsException.Transport("${request.method} ${request.url} failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    override fun executeStreaming(request: HttpRequest): StreamingResponse {
        val connection = open(request)
        try {
            write(connection, request)
            val status = connection.responseCode
            val entries = headerEntries(connection)
            cookieJar.store(connection.headerFields["Set-Cookie"].orEmpty())
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return StreamingResponse(status, entries, stream ?: ByteArray(0).inputStream()) {
                connection.disconnect()
            }
        } catch (e: IOException) {
            connection.disconnect()
            throw JenkinsException.Transport("${request.method} ${request.url} failed: ${e.message}", e)
        }
    }

    private fun open(request: HttpRequest): HttpURLConnection {
        val uri = try {
            URI(request.url)
        } catch (e: Exception) {
            throw JenkinsException.Transport("'${request.url}' is not a usable URL: ${e.message}", e)
        }
        val url: URL = try {
            uri.toURL()
        } catch (e: Exception) {
            throw JenkinsException.Transport("'${request.url}' is not a usable URL: ${e.message}", e)
        }
        val proxy = try {
            proxySelector?.select(uri)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        val connection = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = request.method
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", ACCEPT)
        cookieJar.headerValueFor(uri.rawPath ?: "/")?.let { connection.setRequestProperty("Cookie", it) }
        // Caller headers last, so an explicit Cookie or Authorization always wins over ours.
        for ((name, value) in request.headers) connection.setRequestProperty(name, value)
        if (sslContext != null && connection is HttpsURLConnection) {
            connection.sslSocketFactory = sslContext.socketFactory
        }
        return connection
    }

    private fun write(connection: HttpURLConnection, request: HttpRequest) {
        val body = request.body
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
        }
        connection.connect()
        if (body != null) connection.outputStream.use { it.write(body) }
    }

    private fun headerEntries(connection: HttpURLConnection): List<Pair<String, String>> =
        connection.headerFields.entries
            .filter { it.key != null }
            .flatMap { entry -> entry.value.orEmpty().map { entry.key to it } }

    companion object {
        const val USER_AGENT = "Stagecraft-IntelliJ"
        const val ACCEPT = "application/json, text/plain, */*"
    }
}

/**
 * The authenticated HTTP layer for one Jenkins server.
 *
 * Responsibilities, all of them §9.2 obligations:
 *
 *  * attach Basic credentials to every request;
 *  * keep one cookie store for the server and reuse it, rather than creating a client per request;
 *  * fetch the CSRF crumb **for password auth only**, cache the answer - including the 404 that
 *    means "CSRF is off" - and never fail a request because the crumb fetch failed;
 *  * on a `403` that says `No valid crumb`, refetch the crumb **once** and retry **once**, then
 *    give up with a readable message;
 *  * read `X-Jenkins` off whatever response arrives, so the version is known after the first call;
 *  * never follow a redirect, so SSO is reported as SSO.
 */
class JenkinsHttp(
    baseUrl: String,
    val auth: JenkinsAuth,
    transport: HttpTransport? = null,
    proxySelector: ProxySelector? = null,
    sslContext: SSLContext? = null,
    connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
    val cookieJar: CookieJar = CookieJar(),
) : AutoCloseable {

    val baseUrl: String = JenkinsUrls.normalizeBase(baseUrl)

    private val transport: HttpTransport = transport
        ?: UrlConnectionTransport(proxySelector, sslContext, connectTimeoutMillis, readTimeoutMillis, cookieJar)

    /** Filled in from the `X-Jenkins` header of the first response we see. */
    var lastJenkinsVersion: JenkinsVersion? = null
        private set

    /** Counted so tests can assert that a rule like "refetch once" really is once. */
    var requestCount: Int = 0
        private set

    fun get(path: String, extraHeaders: Map<String, String> = emptyMap()): HttpResponse =
        send("GET", resolve(path), null, extraHeaders)

    fun post(path: String, body: ByteArray, contentType: String = "application/x-www-form-urlencoded"): HttpResponse =
        send("POST", resolve(path), body, mapOf("Content-Type" to contentType))

    /** A GET whose body is read from the wire rather than buffered. Used for consoles (§9.7). */
    fun stream(path: String): StreamingResponse {
        val request = buildRequest("GET", resolve(path), null, emptyMap())
        requestCount++
        val response = transport.executeStreaming(request)
        remember(response.headerEntries)
        return response
    }

    /** Absolute form of [path] against the configured base. Accepts an already-absolute URL. */
    fun resolve(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + path.trimStart('/')

    override fun close() = transport.close()

    private fun send(
        method: String,
        url: String,
        body: ByteArray?,
        extraHeaders: Map<String, String>,
    ): HttpResponse {
        var response = execute(buildRequest(method, url, body, extraHeaders))
        if (isCrumbFailure(response) && auth.credential.requiresCrumb) {
            auth.crumbCache.invalidate()
            response = execute(buildRequest(method, url, body, extraHeaders))
        }
        return response
    }

    private fun execute(request: HttpRequest): HttpResponse {
        requestCount++
        val response = transport.execute(request)
        remember(response.headerEntries)
        return response
    }

    private fun buildRequest(
        method: String,
        url: String,
        body: ByteArray?,
        extraHeaders: Map<String, String>,
    ): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = auth.credential.authorizationHeader
        if (isMutating(method) && auth.credential.requiresCrumb) {
            ensureCrumb()
            auth.crumbCache.header?.let { (name, value) -> headers[name] = value }
        }
        headers.putAll(extraHeaders)
        return HttpRequest(method, url, headers, body)
    }

    /**
     * Make sure the crumb cache holds an answer, fetching it if not. A 404 is an answer: CSRF is
     * switched off on this server and we must never ask again.
     */
    private fun ensureCrumb() {
        val cache = auth.crumbCache
        if (cache.isLoaded) return
        val url = baseUrl + CRUMB_ISSUER_PATH
        val request = HttpRequest(
            "GET",
            url,
            mapOf(
                "Authorization" to auth.credential.authorizationHeader,
                "Accept" to UrlConnectionTransport.ACCEPT,
            ),
            null,
        )
        requestCount++
        val response = transport.execute(request)
        remember(response.headerEntries)
        when {
            response.status == 404 -> {
                cache.markDisabled()
                auth.lastCrumbError = null
            }
            response.isSuccess -> try {
                cache.store(response.text)
                auth.lastCrumbError = null
            } catch (e: JenkinsException) {
                auth.lastCrumbError = e.message
            }
            else -> auth.lastCrumbError = "HTTP ${response.status} from $url"
        }
    }

    private fun remember(headerEntries: List<Pair<String, String>>) {
        headerEntries.firstOrNull { it.first.equals("X-Jenkins", ignoreCase = true) }
            ?.let { JenkinsVersion.parse(it.second) }
            ?.let { lastJenkinsVersion = it }
    }

    private fun isMutating(method: String): Boolean =
        method != "GET" && method != "HEAD" && method != "OPTIONS"

    private fun isCrumbFailure(response: HttpResponse): Boolean =
        response.status == 403 && response.text.contains(NO_VALID_CRUMB, ignoreCase = true)

    companion object {
        /** §9.2: both short, both configurable. The incumbent's freeze was an infinite timeout. */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000

        const val CRUMB_ISSUER_PATH = "crumbIssuer/api/json"
        const val NO_VALID_CRUMB = "No valid crumb"
    }
}
