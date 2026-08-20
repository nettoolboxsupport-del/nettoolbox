package de.nettoolbox.feature.tools.domain.scan

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.toNetToolboxError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.system.measureTimeMillis

@Serializable
data class PortScanRequest(
    val host: String,
    val ports: List<Int>,
    val timeoutMillis: Int = 1_000,
    val parallelism: Int = 64,
    val grabBanner: Boolean = false,
)

data class OpenPort(
    val port: Int,
    val service: String?,
    val connectMillis: Long,
    val banner: String? = null,
)

sealed interface PortScanEvent {
    data class Progress(val completed: Int, val total: Int) : PortScanEvent
    data class Found(val openPort: OpenPort) : PortScanEvent
    data class Failed(val error: NetToolboxError) : PortScanEvent
    data class Completed(val openPorts: List<OpenPort>, val scanned: Int) : PortScanEvent
}

/**
 * TCP connect scan.
 *
 * Connect rather than SYN: a half-open scan needs raw sockets, which an
 * unprivileged Android app does not get (spec section 3.3). The consequence is
 * visible to the target - every probe is a completed handshake in its logs - and
 * that is exactly why the app asks the user to confirm they are authorised.
 */
class PortScanner @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    fun scan(request: PortScanRequest): Flow<PortScanEvent> = channelFlow {
        val address = try {
            withContext(dispatcher) { InetAddress.getByName(request.host) }
        } catch (unknown: UnknownHostException) {
            send(
                PortScanEvent.Failed(
                    NetToolboxError(ErrorReason.HOST_UNREACHABLE, request.host, cause = unknown),
                ),
            )
            return@channelFlow
        } catch (throwable: Throwable) {
            send(PortScanEvent.Failed(throwable.toNetToolboxError()))
            return@channelFlow
        }

        // Bounded parallelism: the spec caps this at 64 by default, and a phone
        // that opens 1000 sockets at once mostly measures its own file
        // descriptor limit rather than the network.
        val permits = Semaphore(request.parallelism.coerceIn(1, MAX_PARALLELISM))
        val completed = AtomicInteger(0)
        val found = ConcurrentLinkedQueue<OpenPort>()

        // The inner scope is what makes the summary correct: it suspends until
        // every probe has finished, so Completed cannot race the last results.
        coroutineScope {
            request.ports.forEach { port ->
                launch {
                    permits.withPermit {
                        probe(address, port, request)?.let { open ->
                            found += open
                            send(PortScanEvent.Found(open))
                        }
                        send(
                            PortScanEvent.Progress(
                                completed = completed.incrementAndGet(),
                                total = request.ports.size,
                            ),
                        )
                    }
                }
            }
        }

        send(
            PortScanEvent.Completed(
                openPorts = found.sortedBy { it.port },
                scanned = completed.get(),
            ),
        )
    }.flowOn(dispatcher)

    private fun probe(
        address: InetAddress,
        port: Int,
        request: PortScanRequest,
    ): OpenPort? {
        var connected = false
        var banner: String? = null

        val elapsed = measureTimeMillis {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), request.timeoutMillis)
                    connected = true
                    if (request.grabBanner) {
                        banner = readBanner(socket, request.timeoutMillis)
                    }
                }
            } catch (io: IOException) {
                connected = false
            }
        }

        return if (connected) {
            OpenPort(port, PortSpec.serviceName(port), elapsed, banner)
        } else {
            null
        }
    }

    /**
     * Reads whatever a service volunteers on connect. Nothing is written first:
     * many services stay silent until spoken to, and sending a probe payload
     * would turn a passive scan into active interaction.
     */
    private fun readBanner(socket: Socket, timeoutMillis: Int): String? = try {
        socket.soTimeout = timeoutMillis
        val buffer = ByteArray(BANNER_LIMIT)
        val read = socket.getInputStream().read(buffer)
        if (read > 0) {
            String(buffer, 0, read, Charsets.ISO_8859_1)
                .filter { it == '\n' || it == '\t' || !it.isISOControl() }
                .trim()
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
    } catch (io: IOException) {
        null
    }

    private companion object {
        const val MAX_PARALLELISM = 256
        const val BANNER_LIMIT = 256
    }
}
