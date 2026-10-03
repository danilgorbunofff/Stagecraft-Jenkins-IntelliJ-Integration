package dev.stagecraft.service

import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathValidator
import java.security.cert.CertStore
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Verifies a JetBrains Marketplace confirmation stamp for a paid plugin (§8.3, §12).
 *
 * A paid listing that never checks its licence keeps working after the trial. The IDE hands the
 * plugin a *confirmation stamp* for its product code (`LicensingFacade.getConfirmationStamp`); this
 * class decides whether that stamp was really issued by JetBrains. It is a direct port of JetBrains'
 * reference implementation (`marketplace-makemecoffee-plugin`, `CheckLicense.java`), including the
 * two public root certificates, and it is plain Kotlin with no IDE imports so the rejection paths
 * are unit-tested.
 *
 * Two stamp shapes exist:
 *  * `key:<licenseId>-<licensePart>-<signature>-<certificate>` - a licence from a JetBrains Account,
 *    an activation code, or the trial. The certificate must chain to a JetBrains root; expiry is not
 *    checked, so a perpetual fallback licence stays valid.
 *  * `stamp:<machineId>:<timestamp>:<machineId>:<algorithm>:<signature>:<cert>[:<intermediate>...]`
 *    - a ticket from a Floating License Server. Here expiry *is* checked and the ticket must be fresh.
 */
object LicenseVerifier {

    private const val KEY_PREFIX = "key:"
    private const val STAMP_PREFIX = "stamp:"

    /** How fresh a licence-server ticket must be, as in the reference implementation. */
    private const val TIMESTAMP_VALIDITY_PERIOD_MS = 60L * 60 * 1000

    /** The public JetBrains root certificates that sign every Marketplace licence. */
    private val ROOT_CERTIFICATES = listOf(
            """
            -----BEGIN CERTIFICATE-----
            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
            -----END CERTIFICATE-----
            """.trimIndent(),
            """
            -----BEGIN CERTIFICATE-----
            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
            7tlDFnvaRnQukqSG+VqNWg==
            -----END CERTIFICATE-----
            """.trimIndent(),
    )

    /** True when [stamp] is a genuine JetBrains licence for this machine; null stamps are false. */
    fun isValid(stamp: String?, nowMillis: Long = System.currentTimeMillis()): Boolean = when {
        stamp == null -> false
        stamp.startsWith(KEY_PREFIX) -> isKeyValid(stamp.substring(KEY_PREFIX.length))
        stamp.startsWith(STAMP_PREFIX) -> isLicenseServerStampValid(stamp.substring(STAMP_PREFIX.length), nowMillis)
        else -> false
    }

    private fun isKeyValid(key: String): Boolean {
        val parts = key.split('-')
        if (parts.size != 4) return false
        val (licenseId, licensePart, signaturePart, certPart) = parts
        return try {
            val decoder = Base64.getMimeDecoder()
            val signature = Signature.getInstance("SHA1withRSA")
            signature.initVerify(createCertificate(decoder.decode(certPart), emptyList(), checkValidityNow = false))
            val licenseBytes = decoder.decode(licensePart)
            signature.update(licenseBytes)
            if (!signature.verify(decoder.decode(signaturePart))) return false
            // The licence id named in the stamp must be the one inside the signed data.
            String(licenseBytes, Charsets.UTF_8).contains("\"licenseId\":\"$licenseId\"")
        } catch (_: Exception) {
            false
        }
    }

    private fun isLicenseServerStampValid(serverStamp: String, nowMillis: Long): Boolean = try {
        val parts = serverStamp.split(':')
        val decoder = Base64.getMimeDecoder()
        val expectedMachineId = parts[0]
        val timeStamp = parts[1].toLong()
        val machineId = parts[2]
        val signatureType = parts[3]
        val signatureBytes = decoder.decode(parts[4])
        val certBytes = decoder.decode(parts[5])
        val intermediate = parts.drop(6).map { decoder.decode(it) }

        val signature = Signature.getInstance(signatureType)
        // Expired certificates from a licence server cannot be trusted, so expiry is checked here.
        signature.initVerify(createCertificate(certBytes, intermediate, checkValidityNow = true))
        signature.update("$timeStamp:$machineId".toByteArray(Charsets.UTF_8))
        signature.verify(signatureBytes) &&
            expectedMachineId == machineId &&
            kotlin.math.abs(nowMillis - timeStamp) < TIMESTAMP_VALIDITY_PERIOD_MS
    } catch (_: Exception) {
        false
    }

    private fun createCertificate(
        certBytes: ByteArray,
        intermediateCertsBytes: List<ByteArray>,
        checkValidityNow: Boolean,
    ): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        val cert = factory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        val allCerts = HashSet<Certificate>()
        allCerts += cert
        for (bytes in intermediateCertsBytes) allCerts += factory.generateCertificate(ByteArrayInputStream(bytes))

        val selector = X509CertSelector().apply { certificate = cert }
        val anchors = ROOT_CERTIFICATES.map { root ->
            TrustAnchor(factory.generateCertificate(ByteArrayInputStream(root.toByteArray(Charsets.UTF_8))) as X509Certificate, null)
        }.toSet()
        val params = PKIXBuilderParameters(anchors, selector).apply {
            isRevocationEnabled = false
            // Without an expiry check, validate on the certificate's own start date so the answer
            // does not depend on when the check runs.
            if (!checkValidityNow) date = cert.notBefore
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(allCerts)))
        }
        val path = CertPathBuilder.getInstance("PKIX").build(params).certPath
        CertPathValidator.getInstance("PKIX").validate(path, params)
        return cert
    }

    /** The roots, for the test that pins them as parseable X.509 certificates. */
    internal val rootCertificates: List<String> get() = ROOT_CERTIFICATES
}
