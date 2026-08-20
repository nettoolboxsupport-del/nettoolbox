package de.nettoolbox.feature.tools.domain.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HttpUrlNormalizerTest {

    @Test
    fun `a bare host defaults to HTTPS`() {
        // Defaulting to http would make the tool report a plaintext connection
        // for a host that is perfectly fine over TLS.
        assertEquals("https://example.com", HttpUrlNormalizer.normalize("example.com"))
        assertEquals("https://example.com:8443/x", HttpUrlNormalizer.normalize("example.com:8443/x"))
    }

    @Test
    fun `an explicit scheme is kept`() {
        assertEquals("http://example.com", HttpUrlNormalizer.normalize("http://example.com"))
        assertEquals("https://example.com", HttpUrlNormalizer.normalize("https://example.com"))
        assertEquals("HTTP://example.com", HttpUrlNormalizer.normalize("HTTP://example.com"))
    }

    @Test
    fun `whitespace and empty input are rejected`() {
        assertNull(HttpUrlNormalizer.normalize(""))
        assertNull(HttpUrlNormalizer.normalize("   "))
        assertNull(HttpUrlNormalizer.normalize("example .com"))
    }

    @Test
    fun `a scheme the inspector cannot speak is an error, not something to prefix`() {
        assertNull(HttpUrlNormalizer.normalize("ftp://example.com"))
        assertNull(HttpUrlNormalizer.normalize("ssh://example.com"))
    }

    @Test
    fun `a missing host is rejected`() {
        assertNull(HttpUrlNormalizer.normalize("https://:8080"))
    }
}

class CertificateInfoTest {

    private fun certificate(notAfterMillis: Long, notBeforeMillis: Long = 0L) = CertificateInfo(
        subject = "CN=example.com",
        issuer = "CN=Test CA",
        notBeforeMillis = notBeforeMillis,
        notAfterMillis = notAfterMillis,
        subjectAlternativeNames = listOf("example.com"),
        signatureAlgorithm = "SHA256withRSA",
        serialNumber = "01",
    )

    @Test
    fun `counts the days left before expiry`() {
        val now = 1_000_000_000_000L
        val inTenDays = now + TimeUnit.DAYS.toMillis(10)

        assertEquals(10L, certificate(inTenDays).daysUntilExpiry(now))
    }

    @Test
    fun `an expired certificate reports negative days and is flagged`() {
        val now = 1_000_000_000_000L
        val threeDaysAgo = now - TimeUnit.DAYS.toMillis(3)

        val expired = certificate(threeDaysAgo)
        assertEquals(-3L, expired.daysUntilExpiry(now))
        assert(expired.isExpired(now))
    }

    @Test
    fun `a certificate that is not valid yet is flagged separately`() {
        val now = 1_000_000_000_000L
        val certificate = certificate(
            notBeforeMillis = now + TimeUnit.DAYS.toMillis(1),
            notAfterMillis = now + TimeUnit.DAYS.toMillis(90),
        )

        assert(certificate.isNotYetValid(now))
        assert(!certificate.isExpired(now))
    }
}
