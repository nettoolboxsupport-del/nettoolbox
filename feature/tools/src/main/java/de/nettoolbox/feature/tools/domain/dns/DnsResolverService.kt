package de.nettoolbox.feature.tools.domain.dns

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.toNetToolboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Runs a lookup against one or several resolvers at the same time.
 *
 * Querying several resolvers in parallel is the point of the tool: "the name
 * resolves for me but not for the customer" is almost always a difference
 * between resolvers, and seeing them side by side answers that in one shot.
 */
@Singleton
class DnsResolverService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun lookup(
        name: String,
        type: DnsRecordType,
        targets: List<DnsResolverTarget>,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): List<DnsLookupResult> = coroutineScope {
        targets
            .map { target -> async { lookupOne(name, type, target, timeoutMillis) } }
            .awaitAll()
    }

    private suspend fun lookupOne(
        name: String,
        type: DnsRecordType,
        target: DnsResolverTarget,
        timeoutMillis: Int,
    ): DnsLookupResult {
        val question = DnsQuestion(name.trim().removeSuffix("."), type)
        if (question.name.isEmpty()) {
            return DnsLookupResult(
                target = target,
                response = null,
                elapsedMillis = 0,
                error = NetToolboxError(ErrorReason.INVALID_INPUT, detail = name),
            )
        }

        var response: DnsResponse? = null
        var error: NetToolboxError? = null

        // Measured around the failure too: how long a resolver took to not answer
        // is the diagnostically interesting number, and reporting 0 ms for a
        // five-second timeout would be a lie about the network.
        val elapsed = measureTimeMillis {
            try {
                response = exchange(question, target, timeoutMillis)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                error = throwable.toNetToolboxError()
            }
        }

        return DnsLookupResult(target, response, elapsed, error)
    }

    private suspend fun exchange(
        question: DnsQuestion,
        target: DnsResolverTarget,
        timeoutMillis: Int,
    ): DnsResponse {
        val id = Random.nextInt(0, 0x10000)
        val query = DnsMessageCodec.encodeQuery(id, question)

        val answer = transportFor(target, timeoutMillis).query(query)
        val decoded = DnsMessageCodec.decode(answer)

        // A mismatched transaction ID means the answer does not belong to this
        // query - over UDP that is the classic spoofing signal, not a hiccup.
        if (decoded.id != id) {
            throw IllegalStateException("Transaction ID mismatch: expected $id, got ${decoded.id}")
        }

        // A truncated UDP answer is not an error, it is the protocol telling us
        // to ask again over TCP.
        if (decoded.truncated && target.kind == DnsTransportKind.UDP) {
            val overTcp = TcpDnsTransport(target.address, target.port, timeoutMillis, dispatcher)
                .query(query)
            return DnsMessageCodec.decode(overTcp)
        }

        return decoded
    }

    private fun transportFor(target: DnsResolverTarget, timeoutMillis: Int): DnsTransport =
        when (target.kind) {
            DnsTransportKind.UDP ->
                UdpDnsTransport(target.address, target.port, timeoutMillis, dispatcher)

            DnsTransportKind.TCP ->
                TcpDnsTransport(target.address, target.port, timeoutMillis, dispatcher)

            DnsTransportKind.DOT ->
                DotDnsTransport(target.address, target.port, timeoutMillis, dispatcher)

            DnsTransportKind.DOH ->
                DohDnsTransport(target.address, okHttpClient, dispatcher)
        }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000
    }
}
