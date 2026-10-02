package dev.stagecraft

import dev.stagecraft.jenkins.HttpRequest
import dev.stagecraft.jenkins.HttpResponse
import dev.stagecraft.jenkins.HttpTransport

/**
 * A transport that answers from recorded fixtures and remembers every request.
 *
 * Recording the requests is half the point: several rules in §9.2 are about how *many* requests are
 * made - "fetch the crumb once", "refetch once on 403, never loop" - and a test that cannot count
 * requests cannot assert them.
 */
class FakeTransport : HttpTransport {

    private val exact = LinkedHashMap<String, HttpResponse>()
    private val prefixes = LinkedHashMap<String, HttpResponse>()

    val requests = ArrayList<HttpRequest>()

    fun on(method: String, url: String, response: HttpResponse): FakeTransport {
        exact["$method $url"] = response
        return this
    }

    fun onGet(url: String, response: HttpResponse): FakeTransport = on("GET", url, response)

    fun onPost(url: String, response: HttpResponse): FakeTransport = on("POST", url, response)

    /** Match any URL starting with [prefix]; an exact route always wins over a prefix. */
    fun onGetPrefix(prefix: String, response: HttpResponse): FakeTransport {
        prefixes["GET $prefix"] = response
        return this
    }

    val count: Int get() = requests.size

    fun urls(): List<String> = requests.map { it.url }

    fun requestsMatching(fragment: String): List<HttpRequest> = requests.filter { it.url.contains(fragment) }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        exact["${request.method} ${request.url}"]?.let { return it }
        prefixes.entries
            .filter { request.method == it.key.substringBefore(' ') && request.url.startsWith(it.key.substringAfter(' ')) }
            .maxByOrNull { it.key.length }
            ?.let { return it.value }
        error(
            "no recorded response for ${request.method} ${request.url}\n" +
                "known routes:\n" + (exact.keys + prefixes.keys).joinToString("\n") { "  $it" },
        )
    }
}
