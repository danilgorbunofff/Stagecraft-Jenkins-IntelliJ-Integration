package dev.stagecraft.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager

/**
 * The IntelliJ adapter for [CredentialStore]: the API token goes to `PasswordSafe`, which is the OS
 * keychain where the platform has one, and never to the project's settings file (§7.3 rule 5).
 *
 * The credential is keyed on the server *and* the user: a second server is a second credential, the
 * same server typed with or without a trailing slash is one credential, and two projects that use one
 * server as two different users keep two tokens instead of overwriting each other's.
 *
 * Releases before the user became part of the key stored one credential per server. Such an entry
 * is still read - only when the user it was stored with is the user asked for - and moved to the new
 * key on that first read, so an upgrade never loses a configured token.
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

    override fun token(serverUrl: String, user: String): String? {
        passwordSafe.get(attributes(serverUrl, user))?.getPasswordAsString()?.let { return it }
        return migrateLegacy(serverUrl, user)
    }

    override fun save(serverUrl: String, user: String, token: String) {
        passwordSafe.set(attributes(serverUrl, user), Credentials(user, token))
    }

    override fun clear(serverUrl: String, user: String) {
        // A null credential is how the platform deletes one.
        passwordSafe.set(attributes(serverUrl, user), null)
    }

    private fun migrateLegacy(serverUrl: String, user: String): String? {
        val legacyKey = legacyAttributes(serverUrl)
        val legacy = passwordSafe.get(legacyKey) ?: return null
        if (legacy.userName != user) return null
        val token = legacy.getPasswordAsString() ?: return null
        save(serverUrl, user, token)
        passwordSafe.set(legacyKey, null)
        return token
    }

    private fun attributes(serverUrl: String, user: String) =
        CredentialAttributes(generateServiceName(SERVICE_NAME, serverUrl.trimEnd('/')), user)

    private fun legacyAttributes(serverUrl: String) =
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
