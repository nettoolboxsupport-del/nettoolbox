package de.nettoolbox.feature.tools.domain.http

import java.util.concurrent.TimeUnit

data class HttpHeader(val name: String, val value: String)

/** One hop of the redirect chain. */
data class HttpStep(
    val url: String,
    val statusCode: Int,
    val statusMessage: String,
    val protocol: String,
    val elapsedMillis: Long,
    val headers: List<HttpHeader>,
    val location: String?,
    val tls: TlsInfo?,
) {
    val isRedirect: Boolean get() = statusCode in 300..399 && location != null
}

data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    val subjectAlternativeNames: List<String>,
    val signatureAlgorithm: String,
    val serialNumber: String,
) {
    /**
     * Negative once the certificate has expired. This is the number a technician
     * is usually here for, so it is computed rather than left to the reader.
     */
    fun daysUntilExpiry(nowMillis: Long = System.currentTimeMillis()): Long =
        TimeUnit.MILLISECONDS.toDays(notAfterMillis - nowMillis)

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis > notAfterMillis

    fun isNotYetValid(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis < notBeforeMillis
}

data class TlsInfo(
    val version: String,
    val cipherSuite: String,
    /** Leaf first, as the server sent it. */
    val certificates: List<CertificateInfo>,
) {
    val leaf: CertificateInfo? get() = certificates.firstOrNull()
}

data class HttpInspection(
    val requestedUrl: String,
    val steps: List<HttpStep>,
    val finalUrl: String,
    /** True when the chain was cut off at the redirect limit. */
    val redirectLimitReached: Boolean,
    /** True when a URL appeared twice - a redirect loop. */
    val loopDetected: Boolean,
) {
    val finalStep: HttpStep? get() = steps.lastOrNull()

    val totalElapsedMillis: Long get() = steps.sumOf { it.elapsedMillis }
}
