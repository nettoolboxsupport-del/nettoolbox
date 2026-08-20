package de.nettoolbox.feature.tools.domain.scan

import de.nettoolbox.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * SSDP discovery: one M-SEARCH to the multicast group, then collect whatever
 * answers until the window closes.
 *
 * Finds the devices that never open a TCP port worth probing - printers, NAS
 * boxes, media receivers, IP cameras - and usually names them, which a port scan
 * cannot.
 */
class SsdpDiscovery @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun discover(timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): List<DiscoveredHost> =
        withContext(dispatcher) {
            val found = mutableMapOf<String, DiscoveredHost>()

            // MX is the maximum delay a device may wait before answering; it must
            // be smaller than our own window or we stop listening too early.
            val request = buildString {
                append("M-SEARCH * HTTP/1.1\r\n")
                append("HOST: $MULTICAST_ADDRESS:$PORT\r\n")
                append("MAN: \"ssdp:discover\"\r\n")
                append("MX: 2\r\n")
                append("ST: ssdp:all\r\n")
                append("\r\n")
            }.toByteArray()

            DatagramSocket().use { socket ->
                socket.soTimeout = RECEIVE_SLICE_MILLIS
                socket.broadcast = true

                val group = InetAddress.getByName(MULTICAST_ADDRESS)
                socket.send(DatagramPacket(request, request.size, group, PORT))

                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(BUFFER_SIZE)

                while (System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    }

                    val ip = packet.address?.hostAddress ?: continue
                    val payload = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                    val service = payload.headerValue("SERVER")
                        ?: payload.headerValue("ST")
                        ?: "SSDP"

                    val host = DiscoveredHost(
                        ip = ip,
                        services = listOf(service),
                        sources = setOf(DiscoverySource.SSDP),
                    )
                    found[ip] = found[ip]?.mergeWith(host) ?: host
                }
            }

            found.values.toList()
        }

    private fun String.headerValue(name: String): String? = lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MULTICAST_ADDRESS = "239.255.255.250"
        const val PORT = 1900
        const val DEFAULT_TIMEOUT_MILLIS = 4_000
        /** Short slices keep the loop cancellable while waiting. */
        const val RECEIVE_SLICE_MILLIS = 500
        const val BUFFER_SIZE = 2048
    }
}
