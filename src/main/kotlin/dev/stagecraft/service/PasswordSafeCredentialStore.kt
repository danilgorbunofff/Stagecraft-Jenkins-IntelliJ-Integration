package dev.stagecraft.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager

/**
 * The IntelliJ adapter for [CredentialStore]: the API token goes to `PasswordSafe`, which is the OS
 * keychain where the platform has one, and never to the project's settings file (§7.3 rule 5).
 *
 * The credential is keyed on the server, so a second server is a second credential rather than an
 * overwrite, and the same server typed with or without a trailing slash is one credential. The
 * Jenkins user name travels inside the stored credential; the settings file carries its own copy
 * because it has to render the settings dialog before the keychain is read.
 *
 * `PasswordSafe` exposes an asynchronous read, but the synchronous `get` and `set` inherited from
 * `com.intellij.credentialStore.CredentialStore` are what the rest of the plugin uses. The platform
 * guards both of them with `SlowOperations`, so neither may be called on the EDT: [JenkinsService]
 * performs them on its I/O thread, and [DefaultBuildsLoader] reads there too. A settings dialog,
 * which lives on the EDT, goes through [JenkinsService.readToken] instead.
 */
class PasswordSafeCredentialStore(
    private val passwordSafe: PasswordSafe = defaultPasswordSafe(),
) : CredentialStore {

    override fun token(serverUrl: String): String? =
        passwordSafe.get(attributes(serverUrl))?.getPasswordAsString()

    override fun save(serverUrl: String, user: String, token: String) {
        passwordSafe.set(attributes(serverUrl), Credentials(user, token))
    }

    override fun clear(serverUrl: String) {
        // A null credential is how the platform deletes one.
        passwordSafe.set(attributes(serverUrl), null)
    }

    private fun attributes(serverUrl: String) =
        CredentialAttributes(SERVICE_NAME, serverUrl.trimEnd('/'))

    private companion object {
        /** What the entry is called in the password-safe UI. */
        const val SERVICE_NAME = "Stagecraft"

        /**
         * `PasswordSafe.getInstance()` is hidden from Kotlin callers in 2025.2, but the platform
         * registers the interface as an application service (`META-INF/credential-store.xml`), so
         * the service container is the supported way to reach the same instance.
         */
        fun defaultPasswordSafe(): PasswordSafe =
            ApplicationManager.getApplication().getService(PasswordSafe::class.java)
                ?: error("PasswordSafe is not registered as an application service")
    }
}
