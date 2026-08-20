package de.nettoolbox.feature.tools.domain.http

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.Outcome
import de.nettoolbox.core.common.result.toNetToolboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Walks a URL's redirect chain and reports what happened at every hop.
 *
 * Redirects are followed by hand rather than by OkHttp: the chain is the point of
 * the tool. A client that resolves it silently would hide the very thing the user
 * opened the inspector for - and each hop can have its own TLS parameters and its
 * own certificate.
 */
class HttpInspector @Inject constructor(
    private val client: OkHttpClient,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun inspect(
        url: String,
        method: String = "GET",
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    ): Outcome<HttpInspection> = withContext(dispatcher) {
        val normalized = HttpUrlNormalizer.normalize(url)
            ?: return@withContext Outcome.Failure(
                NetToolboxError(ErrorReason.INVALID_INPUT, detail = url),
            )

        val steps = mutableListOf<HttpStep>()
        val visited = mutableSetOf<String>()
        var current = normalized
        var loopDetected = false
        var limitReached = false

        try {
            while (true) {
                coroutineContext.ensureActive()

                if (!visited.add(current)) {
                    loopDetected = true
                    break
                }
                if (steps.size >= maxRedirects) {
                    limitReached = true
                    break
                }

                val step = requestOnce(current, method)
                steps += step

                val location = step.location?.let { resolve(current, it) }
                if (!step.isRedirect || location == null) break
                current = location
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // A chain that fails halfway is still worth showing, so partial steps
            // are only discarded when nothing at all succeeded.
            if (steps.isEmpty()) {
                return@withContext Outcome.Failure(throwable.toNetToolboxError())
            }
        }

        Outcome.Success(
            HttpInspection(
                requestedUrl = normalized,
                steps = steps,
                finalUrl = steps.lastOrNull()?.url ?: normalized,
                redirectLimitReached = limitReached,
                loopDetected = loopDetected,
            ),
        )
    }

    private fun requestOnce(url: String, method: String): HttpStep {
        val request = Request.Builder()
            .url(url)
            .method(method, null)
            // A neutral, honest user agent. Pretending to be a browser would
            // change what some servers answer and make the result a fiction.
            .header("User-Agent", USER_AGENT)
            .build()

        val startedAt = System.nanoTime()
        return client.newCall(request).execute().use { result ->
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            HttpStep(
                url = url,
                statusCode = result.code,
                statusMessage = result.message,
                protocol = result.protocol.toString().uppercase(),
                elapsedMillis = elapsed,
                headers = result.headers.map { (name, value) -> HttpHeader(name, value) },
                location = result.header("Location"),
                tls = result.handshake?.toTlsInfo(),
            )
        }
    }

    /** Resolves a relative Location header against the URL it came from. */
    private fun resolve(base: String, location: String): String? =
        base.toHttpUrlOrNull()?.resolve(location)?.toString()

    private fun Handshake.toTlsInfo(): TlsInfo = TlsInfo(
        version = tlsVersion.javaName,
        cipherSuite = cipherSuite.javaName,
        certificates = peerCertificates
            .filterIsInstance<X509Certificate>()
            .map { it.toCertificateInfo() },
    )

    private fun X509Certificate.toCertificateInfo(): CertificateInfo = CertificateInfo(
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        notBeforeMillis = notBefore.time,
        notAfterMillis = notAfter.time,
        subjectAlternativeNames = readSubjectAlternativeNames(),
        signatureAlgorithm = sigAlgName,
        serialNumber = serialNumber.toString(16),
    )

    /**
     * SANs come back as a collection of two-element lists: a type code and the
     * value. Types 2 (DNS) and 7 (IP) are the ones worth showing.
     */
    private fun X509Certificate.readSubjectAlternativeNames(): List<String> = try {
        subjectAlternativeNames.orEmpty().mapNotNull { entry ->
            val parts = entry.toList()
            val type = parts.getOrNull(0) as? Int
            val value = parts.getOrNull(1) as? String
            when (type) {
                SAN_TYPE_DNS -> value
                SAN_TYPE_IP -> value
                else -> null
            }
        }
    } catch (parsing: java.security.cert.CertificateParsingException) {
        emptyList()
    }

    private companion object {
        const val DEFAULT_MAX_REDIRECTS = 10
        const val SAN_TYPE_DNS = 2
        const val SAN_TYPE_IP = 7
        const val USER_AGENT = "NetToolbox/0.1 (+network diagnostics)"
    }
}
