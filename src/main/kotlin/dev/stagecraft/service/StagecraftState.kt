package dev.stagecraft.service

/**
 * The project's Stagecraft configuration, without credentials (those go to
 * [CredentialStore]/[PasswordSafeCredentialStore]).
 *
 * This is the bean [StagecraftProjectSettings] persists, so three things about its shape are
 * deliberate and load-bearing:
 *
 *  * every property is a `var`. The platform's XML serializer writes through property setters and
 *    skips read-only properties, so `val`s here would silently persist nothing - and losing the
 *    server on restart is exactly what §7.3 rule 5 forbids;
 *  * every property defaults to "not configured", so a fresh IDE start reads a usable bean rather
 *    than a null;
 *  * [pinnedJobs] defaults to a mutable map, because the serializer deserialises a map by writing
 *    into the instance it finds.
 */
data class StagecraftState(
    var serverUrl: String = "",
    var user: String = "",
    var trustCertificate: Boolean = false,
    var useProxy: Boolean = true,
    /**
     * The secret is the account password rather than an API token (§9.2). Some locked-down servers
     * issue no tokens, and a password needs a CSRF crumb on every write - see
     * [dev.stagecraft.jenkins.JenkinsCredential.Password].
     */
    var secretIsPassword: Boolean = false,
    /** Remote keys (as [dev.stagecraft.jenkins.RemoteMatcher] normalises them) to `job` full names. */
    var pinnedJobs: Map<String, String> = linkedMapOf(),
) {

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && user.isNotBlank()
}
