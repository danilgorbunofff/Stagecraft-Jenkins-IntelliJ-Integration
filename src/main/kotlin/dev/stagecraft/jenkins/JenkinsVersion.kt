package dev.stagecraft.jenkins

/**
 * The Jenkins version, read from the `X-Jenkins` response header - which Jenkins sends on every
 * response, including error responses, so one request is enough to learn it (§9.8).
 *
 * The supported floor is 2.204.1 LTS. Below it we warn and carry on: refusing to talk to a server
 * is worse than trying, because the API surface Stagecraft uses has been stable far longer than
 * that.
 */
data class JenkinsVersion(val raw: String, val major: Int, val minor: Int, val patch: Int) :
    Comparable<JenkinsVersion> {

    override fun compareTo(other: JenkinsVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    val isBelowFloor: Boolean get() = this < FLOOR

    companion object {
        val FLOOR: JenkinsVersion = JenkinsVersion("2.204.1", 2, 204, 1)

        /**
         * Parse `X-Jenkins`, which looks like `2.541.3`, `2.479.3`, or on an ancient server simply
         * `1.395`. Anything unparseable yields `null` rather than a guess, and the caller treats
         * `null` as "unknown", not as "too old".
         */
        fun parse(headerValue: String?): JenkinsVersion? {
            val token = headerValue?.trim()?.substringBefore(' ')?.removePrefix("v")?.trim()
            if (token.isNullOrEmpty()) return null
            val parts = token.split('.')
            if (parts.size < 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            return JenkinsVersion(token, major, minor, patch)
        }
    }
}
