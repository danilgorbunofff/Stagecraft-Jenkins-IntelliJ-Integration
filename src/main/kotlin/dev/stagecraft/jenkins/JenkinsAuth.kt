package dev.stagecraft.jenkins

import java.util.Base64

/**
 * How Stagecraft authenticates. An API token is the supported way (§9.2, §15.4); a password is
 * accepted because some locked-down servers still issue nothing else, and it costs a crumb round
 * trip that the token path does not need.
 */
sealed class JenkinsCredential {

    abstract val user: String
    abstract val secret: String

    data class ApiToken(override val user: String, override val secret: String) : JenkinsCredential()

    data class Password(override val user: String, override val secret: String) : JenkinsCredential()

    /**
     * Measured at Day-0 on Jenkins 2.541.3: with an API token the CSRF crumb is not required at all
     * (a wrong one is ignored, not rejected), while with a password it is required and is bound to
     * the web session. So the crumb is fetched for password auth only - which also means a broken
     * `/crumbIssuer` can never block the token path.
     */
    val requiresCrumb: Boolean get() = this is Password

    val authorizationHeader: String
        get() {
            val encoded = Base64.getEncoder().encodeToString("$user:$secret".toByteArray(Charsets.UTF_8))
            return "Basic $encoded"
        }

    val description: String get() = if (this is ApiToken) "API token" else "password"
}

/**
 * The crumb cache. A crumb is fetched at most once per session, and 404 on `/crumbIssuer/api/json`
 * is a *successful* answer meaning "this server has CSRF protection switched off", which is cached
 * just as firmly as a crumb is (§9.2).
 */
class CrumbCache {

    private var loaded = false
    private var disabled = false
    private var value: String? = null

    // Named to avoid `field`, which inside a custom accessor refers to the accessor's own
    // backing field rather than to this property.
    private var fieldName: String = DEFAULT_FIELD

    /** True once we know the answer, whichever answer it was. */
    val isLoaded: Boolean get() = loaded

    /** True when the server answered 404: CSRF is off, so send no crumb. */
    val isDisabled: Boolean get() = disabled

    val crumb: String? get() = value

    val requestField: String get() = fieldName

    /** The header to add to a POST, or `null` when no crumb should be sent. */
    val header: Pair<String, String>? get() = if (disabled || value == null) null else fieldName to value!!

    /** Store a `/crumbIssuer/api/json` body. Throws [JenkinsException.Malformed] if it is unusable. */
    fun store(crumbJson: String) {
        val obj = try {
            parseJsonObject(crumbJson)
        } catch (e: JenkinsException) {
            throw JenkinsException.Malformed("crumbIssuer/api/json", e.message ?: "unreadable crumb response")
        }
        val crumbValue = obj.str("crumb")
        if (crumbValue.isNullOrEmpty()) {
            throw JenkinsException.Malformed("crumbIssuer/api/json", "the response contained no 'crumb'")
        }
        value = crumbValue
        fieldName = obj.str("crumbRequestField")?.takeIf { it.isNotBlank() } ?: DEFAULT_FIELD
        disabled = false
        loaded = true
    }

    /** The server answered 404: CSRF protection is off. Remember it, and never ask again. */
    fun markDisabled() {
        value = null
        disabled = true
        loaded = true
    }

    /**
     * Forget the crumb so the next request fetches a fresh one. Used by the single refetch-on-403
     * retry; crumbs expire with the session, so a cached one can go stale mid-session.
     */
    fun invalidate() {
        value = null
        disabled = false
        loaded = false
    }

    companion object {
        const val DEFAULT_FIELD = "Jenkins-Crumb"
    }
}

/**
 * Credentials plus the crumb cache for one server. Holds no sockets and no IDE state, so it is
 * directly testable.
 */
class JenkinsAuth(val credential: JenkinsCredential) {

    val crumbCache: CrumbCache = CrumbCache()

    /** Why the last crumb fetch failed, if it did. Reported in the message of a later 403. */
    var lastCrumbError: String? = null
        internal set

    /**
     * §9.2: `/me/api/json` answering 200 is not enough, because a server that allows anonymous read
     * answers it as `anonymous`. The returned `id` has to be the user we configured - compared
     * case-insensitively, because Jenkins' default user id strategy is case-insensitive and reports
     * the id in its stored spelling, not the one the user typed.
     */
    fun matchesConfiguredUser(id: String): Boolean = id.equals(credential.user, ignoreCase = true)

    val isAnonymousUser: Boolean get() = credential.user.equals("anonymous", ignoreCase = true)

    override fun toString(): String = "${credential.user} (${credential.description})"
}
