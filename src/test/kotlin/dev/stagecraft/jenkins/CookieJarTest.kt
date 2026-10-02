package dev.stagecraft.jenkins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieJarTest {

    @Test
    fun `a cookie is stored and sent for a matching path`() {
        val jar = CookieJar()
        jar.store(listOf("JSESSIONID.4fdc0dd1=abc; Path=/; HttpOnly; SameSite=Lax"))

        assertEquals("JSESSIONID.4fdc0dd1=abc", jar.headerValueFor("/"))
        assertEquals("JSESSIONID.4fdc0dd1=abc", jar.headerValueFor("/job/a/"))
        assertEquals(setOf("JSESSIONID.4fdc0dd1"), jar.names())
    }

    @Test
    fun `a cookie is not sent outside its path`() {
        val jar = CookieJar()
        jar.store(listOf("JSESSIONID=abc; Path=/jenkins"))

        assertEquals("JSESSIONID=abc", jar.headerValueFor("/jenkins/job/a/"))
        assertNull(jar.headerValueFor("/other/"))
    }

    @Test
    fun `an expired cookie is a deletion`() {
        // Exactly what Jenkins sent when it rejected a bad token (re-check R6):
        // Set-Cookie: remember-me=…; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT
        val jar = CookieJar()
        jar.store(listOf("remember-me=stale; Path=/"))
        assertEquals("remember-me=stale", jar.headerValueFor("/"))

        jar.store(listOf("remember-me=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; SameSite=Lax"))

        assertNull(jar.headerValueFor("/"))
        assertTrue(jar.names().isEmpty())
    }

    @Test
    fun `an empty value is a deletion even without Expires`() {
        val jar = CookieJar()
        jar.store(listOf("remember-me=stale; Path=/"))
        jar.store(listOf("remember-me=; Path=/"))

        assertNull(jar.headerValueFor("/"))
    }

    @Test
    fun `max age zero is a deletion`() {
        val jar = CookieJar()
        jar.store(listOf("remember-me=stale; Path=/"))
        jar.store(listOf("remember-me=stale; Path=/; Max-Age=0"))

        assertNull(jar.headerValueFor("/"))
    }

    @Test
    fun `an Expires we cannot read is treated as a deletion`() {
        // Keeping a cookie whose lifetime we cannot parse is the failure that leaks a session.
        val jar = CookieJar()
        jar.store(listOf("remember-me=stale; Path=/"))
        jar.store(listOf("remember-me=stale; Path=/; Expires=whenever"))

        assertNull(jar.headerValueFor("/"))
    }

    @Test
    fun `a cookie that expires in the future is kept`() {
        val jar = CookieJar()
        jar.store(listOf("remember-me=fresh; Path=/; Expires=Wed, 09 Jun 2100 10:18:14 GMT"))

        assertEquals("remember-me=fresh", jar.headerValueFor("/"))
    }

    @Test
    fun `expiry is judged against the supplied clock`() {
        // The RFC-1123 formatter is strict about the day name matching the date, so use real ones:
        // 1 Jan 2035 is a Monday, 1 Jan 2025 is a Wednesday.
        val jar = CookieJar()
        val whenMillis = java.time.ZonedDateTime.parse("2030-01-01T00:00:00Z").toInstant().toEpochMilli()
        jar.store(listOf("c=v; Path=/; Expires=Mon, 01 Jan 2035 00:00:00 GMT"), nowMillis = whenMillis)
        assertEquals("c=v", jar.headerValueFor("/"))

        jar.store(listOf("c=v; Path=/; Expires=Wed, 01 Jan 2025 00:00:00 GMT"), nowMillis = whenMillis)
        assertNull(jar.headerValueFor("/"))
    }

    @Test
    fun `several cookies are joined into one header`() {
        val jar = CookieJar()
        jar.store(listOf("a=1; Path=/", "b=2; Path=/"))

        assertEquals("a=1; b=2", jar.headerValueFor("/"))
    }

    @Test
    fun `junk is ignored rather than stored`() {
        val jar = CookieJar()
        jar.store(listOf("", "no-equals-sign", "=novalue", "  "))

        assertTrue(jar.names().isEmpty())
        assertNull(jar.headerValueFor("/"))
    }

    @Test
    fun `the jar can be emptied`() {
        val jar = CookieJar()
        jar.store(listOf("a=1; Path=/"))
        jar.clear()

        assertEquals(0, jar.size)
        assertNull(jar.headerValueFor("/"))
    }
}
