package dev.stagecraft.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * These are the rules that can destroy a configuration without saying anything, so they are tested
 * as rules rather than through the dialog: a blank token field must never wipe a stored token, and a
 * credential must never follow the user to an address or a user name it was not issued for.
 */
class CredentialPlanTest {

    /**
     * `url` follows `previousUrl` by default, which reads as "the address did not move". A test that
     * means "the project was never configured and this address is new" has to say so.
     */
    private fun plan(
        previousUrl: String = "https://ci.example.com/",
        previousUser: String = "admin",
        url: String = previousUrl,
        user: String = previousUser,
        enteredToken: String = "",
    ) = credentialPlan(
        previousUrl = previousUrl,
        previousUser = previousUser,
        url = url,
        user = user,
        enteredToken = enteredToken,
    )

    @Test
    fun `a blank field keeps the stored token`() {
        assertEquals(CredentialPlan.Changes(forget = null, store = null), plan())
    }

    @Test
    fun `a typed token replaces the stored one in place`() {
        assertEquals(
            CredentialPlan.Changes(
                forget = null,
                store = StoredCredential("https://ci.example.com/", "admin", "new"),
            ),
            plan(enteredToken = "new"),
        )
    }

    @Test
    fun `an unconfigured project needs a token`() {
        assertEquals(
            CredentialPlan.RequiresToken,
            plan(
                previousUrl = "",
                previousUser = "",
                url = "https://ci.example.com/",
                user = "admin",
            ),
        )
    }

    @Test
    fun `a moved address needs a token of its own`() {
        assertEquals(
            CredentialPlan.RequiresToken,
            plan(url = "https://ci.internal/"),
        )
    }

    @Test
    fun `a changed user needs a token of its own`() {
        assertEquals(
            CredentialPlan.RequiresToken,
            plan(user = "jenkins-bot"),
        )
    }

    @Test
    fun `a moved address forgets the token it can no longer use`() {
        assertEquals(
            CredentialPlan.Changes(
                forget = "https://ci.example.com/",
                store = StoredCredential("https://ci.internal/", "admin", "new"),
            ),
            plan(url = "https://ci.internal/", enteredToken = "new"),
        )
    }

    @Test
    fun `clearing the address forgets the token`() {
        assertEquals(
            CredentialPlan.Changes(forget = "https://ci.example.com/", store = null),
            plan(url = ""),
        )
    }

    @Test
    fun `clearing the address forgets the token even when one was typed`() {
        assertEquals(
            CredentialPlan.Changes(forget = "https://ci.example.com/", store = null),
            plan(url = "", enteredToken = "new"),
        )
    }

    @Test
    fun `an empty configuration stays empty`() {
        assertEquals(
            CredentialPlan.Changes(forget = null, store = null),
            plan(previousUrl = "", previousUser = "", url = "", user = ""),
        )
    }

    @Test
    fun `a token typed into a fresh configuration is stored`() {
        assertEquals(
            CredentialPlan.Changes(
                forget = null,
                store = StoredCredential("https://ci.example.com/", "admin", "new"),
            ),
            plan(
                previousUrl = "",
                previousUser = "",
                url = "https://ci.example.com/",
                user = "admin",
                enteredToken = "new",
            ),
        )
    }
}
