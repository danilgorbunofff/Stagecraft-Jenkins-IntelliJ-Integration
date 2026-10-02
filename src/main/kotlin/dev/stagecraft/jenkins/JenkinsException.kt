package dev.stagecraft.jenkins

/**
 * Every failure in this package is one of these, and every one of them carries enough detail to be
 * shown to a human without further digging. §15.5: a network problem must fail visibly and quickly,
 * never degrade silently into an empty panel.
 */
sealed class JenkinsException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The socket itself failed: DNS, refused connection, timeout, TLS handshake. */
    class Transport(message: String, cause: Throwable? = null) : JenkinsException(message, cause)

    /**
     * HTTP 401. Jenkins answers a bad API token with 401 and `WWW-Authenticate: Basic realm="Jenkins"`
     * (Day-0 re-check R6), and clears the session cookie on the way out.
     */
    class Unauthorized(val url: String, val challenge: String?) : JenkinsException(
        buildString {
            append("Jenkins rejected the credentials (HTTP 401) for ").append(url).append('.')
            if (challenge != null) append(" The server asked for: ").append(challenge).append('.')
            append(" Check the user name and, if you used one, that the API token has not been revoked.")
        }
    )

    /**
     * HTTP 403. Distinguished from 401 because the fix is different: 403 is "you are somebody, but
     * not somebody who may do this" - usually a missing permission, or a CSRF crumb problem.
     */
    class Forbidden(
        val url: String,
        val detail: String,
        val crumbProblem: Boolean,
        val crumbError: String?,
    ) : JenkinsException(
        buildString {
            append("Jenkins refused the request (HTTP 403) for ").append(url).append('.')
            if (crumbProblem) {
                append(" This is a CSRF crumb failure: the server said \"")
                append(detail).append("\". The crumb was refetched once and the request retried once.")
                if (crumbError != null) append(" The crumb request itself said: ").append(crumbError).append('.')
                append(" With an API token Jenkins does not need a crumb; with a password it does.")
            } else {
                append(' ').append(detail)
            }
        }
    )

    /**
     * HTTP 404. Jenkins uses 404 for "no such job" and for "a job you are not allowed to see"
     * interchangeably (Day-0 re-check R7), so this message says so rather than claiming the job
     * does not exist.
     */
    class NotFound(val url: String) : JenkinsException(
        "Jenkins returned 404 for $url. Jenkins answers 404 both for something that does not exist " +
            "and for something the configured user is not allowed to read, so this may be a permissions " +
            "problem rather than a missing job."
    )

    /**
     * A redirect we do not follow. §9.2: an SSO-protected server answers an API request with a
     * redirect to its login page; following it would turn an authentication failure into an HTML
     * parse error, so we stop here and say what is actually wrong.
     */
    class SsoRedirect(val url: String, val location: String) : JenkinsException(
        "$url redirected to $location instead of answering. This server uses single sign-on, and " +
            "Stagecraft will not follow the redirect into a login page. API tokens are still required " +
            "for this plugin - ask your Jenkins administrator for one."
    )

    /** The server answered, but not with the shape the API promises. */
    class Malformed(val url: String, val detail: String) : JenkinsException(
        "The response from $url could not be understood: $detail"
    )

    class UnexpectedStatus(val status: Int, val url: String, val bodyExcerpt: String) : JenkinsException(
        "Jenkins returned an unexpected HTTP $status for $url.${if (bodyExcerpt.isBlank()) "" else " It said: $bodyExcerpt"}"
    )
}
