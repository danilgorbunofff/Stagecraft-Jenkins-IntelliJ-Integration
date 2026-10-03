package dev.stagecraft.service

/**
 * Where the API token lives. The interface is plain Kotlin so the model is testable headless;
 * [PasswordSafeCredentialStore] is the IntelliJ adapter (§7.3 rule 5: the token lives in
 * `PasswordSafe`, never in a settings file).
 */
interface CredentialStore {

    /**
     * The stored token for [user] on [serverUrl], or `null` when none has been saved yet.
     *
     * Keyed on both: two projects may talk to one server as two different users, and a key on the
     * server alone lets the second project's token silently replace the first one's.
     */
    fun token(serverUrl: String, user: String): String?

    fun save(serverUrl: String, user: String, token: String)

    fun clear(serverUrl: String, user: String)
}

/** In-memory store for tests and for the "do not persist anything yet" mode. */
class InMemoryCredentialStore : CredentialStore {

    private val tokens = HashMap<String, String>()

    override fun token(serverUrl: String, user: String): String? = tokens[key(serverUrl, user)]

    override fun save(serverUrl: String, user: String, token: String) {
        tokens[key(serverUrl, user)] = token
    }

    override fun clear(serverUrl: String, user: String) {
        tokens.remove(key(serverUrl, user))
    }

    private fun key(serverUrl: String, user: String) = "$user@${serverUrl.trimEnd('/')}"
}
