package dev.stagecraft.service

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseVerifierTest {

    @Test
    fun `the jetbrains roots parse as certificates`() {
        val factory = CertificateFactory.getInstance("X.509")
        val roots = LicenseVerifier.rootCertificates.map {
            factory.generateCertificate(ByteArrayInputStream(it.toByteArray())) as X509Certificate
        }

        assertEquals(2, roots.size)
        assertTrue(roots.all { it.subjectX500Principal.name.contains("JetProfile", ignoreCase = true) || it.subjectX500Principal.name.contains("License", ignoreCase = true) }, roots.map { it.subjectX500Principal.name }.toString())
    }

    @Test
    fun `no stamp, an unknown stamp and a forged key are all unlicensed`() {
        assertFalse(LicenseVerifier.isValid(null))
        assertFalse(LicenseVerifier.isValid("eval:whatever"))
        assertFalse(LicenseVerifier.isValid("key:ABC-bGljZW5zZQ==-c2lnbmF0dXJl-Y2VydA=="))
        assertFalse(LicenseVerifier.isValid("key:not-four-parts"))
        assertFalse(LicenseVerifier.isValid("stamp:m:1:m:SHA1withRSA:c2ln:Y2VydA=="))
    }
}
