package dev.stagecraft.service

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §7.3 rule 5: configuration must survive a restart.
 *
 * What actually survives is whatever the platform's XML serializer writes into `stagecraft.xml` on
 * shutdown and reads back on startup, so that round trip is what these tests exercise rather than
 * the bean on its own. A `val` property, for instance, serialises to nothing and would be lost on
 * restart without any other test in the suite noticing.
 */
class StagecraftStateTest {

    private fun restart(state: StagecraftState): StagecraftState =
        XmlSerializer.deserialize(XmlSerializer.serialize(state), StagecraftState::class.java)

    @Test
    fun `a state that was never configured is not configured`() {
        val state = StagecraftState()

        assertFalse(state.isConfigured)
        assertTrue(state.pinnedJobs.isEmpty())
    }

    @Test
    fun `the server and the user survive a restart`() {
        val restored = restart(StagecraftState(serverUrl = "https://ci.example.com/", user = "alice"))

        assertEquals("https://ci.example.com/", restored.serverUrl)
        assertEquals("alice", restored.user)
        assertTrue(restored.isConfigured)
    }

    @Test
    fun `the three switches survive a restart`() {
        val restored = restart(
            StagecraftState(trustCertificate = true, useProxy = false, secretIsPassword = true),
        )

        assertTrue(restored.trustCertificate)
        assertFalse(restored.useProxy)
        assertTrue(restored.secretIsPassword)
    }

    @Test
    fun `an unconfigured state keeps its defaults across a restart`() {
        val restored = restart(StagecraftState())

        assertFalse(restored.trustCertificate)
        assertTrue(restored.useProxy)
        assertFalse(restored.secretIsPassword)
    }

    @Test
    fun `a pinned job survives a restart`() {
        val state = StagecraftState(serverUrl = "https://ci.example.com/")
        state.pinnedJobs = mapOf("github.com/org/repo" to "svc/main")

        val restored = restart(state)

        assertEquals(mapOf("github.com/org/repo" to "svc/main"), restored.pinnedJobs)
    }
}
