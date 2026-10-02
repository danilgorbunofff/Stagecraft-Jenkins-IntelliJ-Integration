package dev.stagecraft.service

/**
 * The project's Stagecraft configuration, without credentials (those go to
 * [CredentialStore]/`PasswordSafe`).
 *
 * This is the bean the IntelliJ [StagecraftProjectSettings] component persists, so it must be plain
 * data with defaults that make sense after a fresh restart: empty, not configured.
 */
data class StagecraftState(
    val serverUrl: String = "",
    val user: String = "",
    val trustCertificate: Boolean = false,
    val useProxy: Boolean = true,
    /** Remote keys (as [dev.stagecraft.jenkins.RemoteMatcher] normalises them) to `job` full names. */
    val pinnedJobs: Map<String, String> = emptyMap(),
) {

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && user.isNotBlank()
}
