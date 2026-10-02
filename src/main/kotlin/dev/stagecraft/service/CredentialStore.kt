package dev.stagecraft.service

/**
 * Where the API token lives. The interface is plain Kotlin so the model is testable headless;
 * [PasswordSafeCredentialStore] is the IntelliJ adapter (§7.3 rule 5: the token lives in
 * `PasswordSafe`, never in a settings file).
 */
interface CredentialStore {

    /** The stored token for [serverUrl], or `null` when none has been saved yet. */
    fun token(serverUrl: String): String?

    fun save(serverUrl: String, user: String, token: String)

    fun clear(serverUrl: String)
}

/** In-memory store for tests and for the "do not persist anything yet" mode. */
class InMemoryCredentialStore : CredentialStore {

    private val tokens = HashMap<String, String>()

    override fun token(serverUrl: String): String? = tokens[serverUrl.trimEnd('/')]

    override fun save(serverUrl: String, user: String, token: String) {
        tokens[serverUrl.trimEnd('/')] = token
    }

    override fun clear(serverUrl: String) {
        tokens.remove(serverUrl.trimEnd('/'))
    }
}
