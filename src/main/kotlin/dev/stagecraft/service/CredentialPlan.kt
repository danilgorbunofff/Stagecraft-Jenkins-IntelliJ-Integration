package dev.stagecraft.service

/**
 * What an edit in the settings dialog means for the token the IDE has stored.
 *
 * The dialog cannot read the password safe while the user types - `PasswordSafe.get` is guarded by
 * `SlowOperations` and `Configurable.isModified` runs on the EDT on every keystroke - so the token
 * field starts empty and is filled from the password safe a moment later. A blank field therefore
 * has to mean "keep the token that is already stored": anything else would let a user who clicks OK
 * before that read lands wipe the token the project is configured with, which is exactly the
 * configuration loss §7.3 rule 5 exists to prevent.
 *
 * That rule, plus what a changed address implies, is the whole of this file. It is plain Kotlin so
 * it can be tested without an IDE: the rules are the part of the settings dialog that can silently
 * destroy a configuration.
 */
sealed interface CredentialPlan {

    /**
     * The address or the user needs a credential of its own and no token came with it: the project
     * has never been configured, the address moved, or the user name changed.
     *
     * A moved address must not inherit the previous server's token - the token was issued by one
     * server, and sending it to another hands a credential to a host the user never entered it for.
     * A changed user name must not either: a Jenkins API token authenticates as the user it was
     * created for, so the old token under a new name is a request that can only fail, while the
     * password safe would go on claiming the credential belongs to the old user. The dialog reports
     * this as a validation error rather than storing a configuration that cannot authenticate.
     */
    data object RequiresToken : CredentialPlan

    /**
     * @param forget the stored credential this edit makes stale, or null when the address did not
     *   move and nothing has to be removed.
     * @param store the credential to store, or null when the edit kept the token already there.
     */
    data class Changes(val forget: CredentialKey?, val store: StoredCredential?) : CredentialPlan
}

/** Which stored credential: tokens are kept per server and per user. */
data class CredentialKey(val serverUrl: String, val user: String)

/** A token and the Jenkins user it belongs to, as the password safe keeps them. */
data class StoredCredential(val serverUrl: String, val user: String, val token: String)

/**
 * @param previousUrl the address the project is configured with, already normalised, empty when the
 *   project has never been configured.
 * @param previousUser the user name the stored token was stored with.
 * @param url the address the dialog now holds, normalised the same way, empty when the user cleared
 *   it.
 * @param user the user name the token belongs to now.
 * @param enteredToken what the user typed, blank meaning "leave the stored token alone".
 */
fun credentialPlan(
    previousUrl: String,
    previousUser: String,
    url: String,
    user: String,
    enteredToken: String,
): CredentialPlan {
    if (url.isEmpty()) {
        // Clearing the address unconfigures the project, so the token it was using goes with it.
        return CredentialPlan.Changes(forget = previousKey(previousUrl, previousUser), store = null)
    }
    val credentialStillApplies = previousUrl == url && previousUser == user
    if (enteredToken.isEmpty()) {
        return if (credentialStillApplies) {
            CredentialPlan.Changes(forget = null, store = null)
        } else {
            CredentialPlan.RequiresToken
        }
    }
    return CredentialPlan.Changes(
        // A moved address leaves the old server's token behind for nothing, so it goes. A changed
        // user does not: entries are keyed per user, and another project may still use that one.
        forget = previousKey(previousUrl, previousUser).takeIf { previousUrl != url },
        store = StoredCredential(url, user, enteredToken),
    )
}

private fun previousKey(previousUrl: String, previousUser: String): CredentialKey? =
    if (previousUrl.isEmpty()) null else CredentialKey(previousUrl, previousUser)
