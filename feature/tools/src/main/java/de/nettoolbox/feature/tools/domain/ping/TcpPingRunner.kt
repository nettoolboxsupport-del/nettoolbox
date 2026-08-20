package de.nettoolbox.feature.tools.domain.ping

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.inject.Inject
import kotlin.system.measureNanoTime

/**
 * Last-resort "ping" that measures how long a TCP handshake takes.
 *
 * This is not ICMP and the UI must never present it as such: it measures the
 * path to one port on one host, a closed port answers faster than an open one,
 * and a firewall changes the result entirely. It exists because on some devices
 * neither ICMP datagram sockets nor the ping binary are usable.
 */
class TcpPingRunner @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    fun run(request: PingRequest): Flow<PingEvent> = flow {
        val address = try {
            InetAddress.getByName(request.target)
        } catch (unknown: UnknownHostException) {
            emit(
                PingEvent.Failed(
                    NetToolboxError(ErrorReason.HOST_UNREACHABLE, request.target, cause = unknown),
                ),
            )
            return@flow
        }

        val timeoutMillis = (request.timeoutSeconds * 1000).coerceAtLeast(1)
        val rtts = mutableListOf<Double>()
        var sequence = 0

        while (request.count == null || sequence < request.count) {
            currentCoroutineContext().ensureActive()

            val endpoint = InetSocketAddress(address, request.tcpPort)
            var connected = false
            val nanos = measureNanoTime {
                try {
                    Socket().use { socket ->
                        socket.connect(endpoint, timeoutMillis)
                        connected = true
                    }
                } catch (io: IOException) {
                    connected = false
                }
            }

            if (connected) {
                val millis = nanos / 1_000_000.0
                rtts += millis
                emit(
                    PingEvent.Reply(
                        sequence = sequence,
                        from = address.hostAddress ?: request.target,
                        // TCP carries no TTL back to the application layer.
                        ttl = null,
                        rttMillis = millis,
                    ),
                )
            } else {
                emit(PingEvent.Loss(sequence, LossReason.TIMEOUT))
            }

            sequence++
            if (request.count == null || sequence < request.count) {
                delay(request.intervalMillis)
            }
        }

        emit(PingEvent.Completed(PingStatistics.of(sequence, rtts)))
    }.flowOn(dispatcher)
}
