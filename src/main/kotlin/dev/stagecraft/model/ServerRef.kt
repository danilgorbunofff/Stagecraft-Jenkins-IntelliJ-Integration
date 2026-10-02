package dev.stagecraft.model

/**
 * A Jenkins server as the user configured it. Plain data; §9.1 keeps behaviour out of `model/`.
 *
 * [baseUrl] is normalised by `JenkinsUrls.normalizeBase` before it is stored, so it always ends
 * with `/` and always carries a scheme.
 */
data class ServerRef(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val user: String,
)
