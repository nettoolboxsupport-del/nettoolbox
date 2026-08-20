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
import kotlin.random.Random

/**
 * Asks every address in a subnet for its NetBIOS name.
 *
 * One socket sends all queries and then listens for whatever comes back, rather
 * than one socket and one timeout per host. UDP needs no handshake, so a sweep of
 * a /24 costs 254 datagrams and a single listening window instead of 254
 * sequential timeouts.
 */
class NetBiosDiscovery @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun discover(
        addresses: List<String>,
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): List<DiscoveredHost> = withContext(dispatcher) {
        if (addresses.isEmpty()) return@withContext emptyList()

        val found = mutableMapOf<String, DiscoveredHost>()
        val query = NetBiosCodec.buildNodeStatusQuery(Random.nextInt(0, 0x10000))

        DatagramSocket().use { socket ->
            socket.soTimeout = RECEIVE_SLICE_MILLIS

            addresses.forEach { address ->
                coroutineContext.ensureActive()
                runCatching {
                    socket.send(
                        DatagramPacket(
                            query,
                            query.size,
                            InetAddress.getByName(address),
                            NetBiosCodec.PORT,
                        ),
                    )
                }
            }

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
                val name = NetBiosCodec.parseNodeStatusResponse(
                    packet.data.copyOf(packet.length),
                ) ?: continue

                found[ip] = DiscoveredHost(
                    ip = ip,
                    hostname = name,
                    sources = setOf(DiscoverySource.NETBIOS),
                )
            }
        }

        found.values.toList()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000
        const val RECEIVE_SLICE_MILLIS = 400
        const val BUFFER_SIZE = 1024
    }
}
