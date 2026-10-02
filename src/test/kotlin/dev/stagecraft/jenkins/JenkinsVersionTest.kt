package dev.stagecraft.jenkins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JenkinsVersionTest {

    @Test
    fun `a plain version is read whole`() {
        val version = JenkinsVersion.parse("2.541.3")!!

        assertEquals(2, version.major)
        assertEquals(541, version.minor)
        assertEquals(3, version.patch)
        assertEquals("2.541.3", version.toString())
        assertEquals("2.541.3", version.raw)
    }

    @Test
    fun `the headers of both test servers parse`() {
        // Day-0 read 2.541.3 off the plugin-laden container and 2.479.3 off the bare LTS one.
        assertEquals("2.541.3", JenkinsVersion.parse("2.541.3").toString())
        assertEquals("2.479.3", JenkinsVersion.parse("2.479.3").toString())
    }

    @Test
    fun `a build suffix is ignored rather than rejected`() {
        val version = JenkinsVersion.parse("2.541.3-jenkins-20250901.123456")!!

        assertEquals("2.541.3", version.toString())
        assertEquals("2.541.3-jenkins-20250901.123456", version.raw)
    }

    @Test
    fun `a version with no patch is still comparable`() {
        val version = JenkinsVersion.parse("2.500")!!

        assertEquals(0, version.patch)
        assertFalse(version.isBelowFloor)
    }

    @Test
    fun `an unreadable header is null, not an exception`() {
        // §9.8: a server that does not tell us its version is not a failure. We just cannot warn.
        assertNull(JenkinsVersion.parse(null))
        assertNull(JenkinsVersion.parse(""))
        assertNull(JenkinsVersion.parse("   "))
        assertNull(JenkinsVersion.parse("unknown"))
        assertNull(JenkinsVersion.parse("Jenkins 2.541.3"))
        assertNull(JenkinsVersion.parse("2.x.3"))
        assertNull(JenkinsVersion.parse("2"))
    }

    @Test
    fun `a version with extra parts keeps the first three`() {
        assertEquals("1.2.3", JenkinsVersion.parse("1.2.3.4")!!.toString())
    }

    @Test
    fun `a leading v is tolerated`() {
        assertEquals("2.541.3", JenkinsVersion.parse("v2.541.3")!!.toString())
    }

    @Test
    fun `the floor itself is supported`() {
        val floor = JenkinsVersion.parse("2.204.1")!!

        assertEquals("2.204.1", floor.toString())
        assertEquals("2.204.1", JenkinsVersion.FLOOR.toString())
        assertFalse(floor.isBelowFloor, "the floor is the lowest supported version, not the first unsupported one")
        assertFalse(JenkinsVersion.FLOOR.isBelowFloor)
    }

    @Test
    fun `anything below the floor is below the floor`() {
        assertTrue(JenkinsVersion.parse("2.204.0")!!.isBelowFloor)
        assertTrue(JenkinsVersion.parse("2.203.9")!!.isBelowFloor)
        assertTrue(JenkinsVersion.parse("2.190.1")!!.isBelowFloor)
        assertTrue(JenkinsVersion.parse("1.651.3")!!.isBelowFloor)
    }

    @Test
    fun `comparison is by number, not by text`() {
        // "2.9" sorts above "2.204" as text and below it as a version.
        assertTrue(JenkinsVersion.parse("2.9")!! < JenkinsVersion.parse("2.204.1")!!)
        assertTrue(JenkinsVersion.parse("2.204.10")!! > JenkinsVersion.parse("2.204.9")!!)
        assertTrue(JenkinsVersion.parse("2.541.3")!! > JenkinsVersion.parse("2.479.3")!!)
        assertEquals(0, JenkinsVersion.parse("2.204.1")!!.compareTo(JenkinsVersion.parse("2.204.1")!!))
    }

    @Test
    fun `a major upgrade outranks any minor`() {
        assertTrue(JenkinsVersion.parse("3.0")!! > JenkinsVersion.parse("2.999.99")!!)
    }
}
