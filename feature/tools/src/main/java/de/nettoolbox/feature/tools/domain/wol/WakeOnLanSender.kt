package de.nettoolbox.feature.tools.domain.wol

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.core.common.result.Outcome
import de.nettoolbox.core.common.result.suspendRunCatching
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject

/**
 * Sends a magic packet over UDP.
 *
 * Wake-on-LAN is fire-and-forget: there is no acknowledgement, and a sent packet
 * says nothing about whether the target woke up. The UI has to word its success
 * message accordingly.
 */
class WakeOnLanSender @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /**
     * @param broadcastAddress use the subnet's directed broadcast (e.g.
     *   192.168.1.255) rather than 255.255.255.255 when the target sits behind a
     *   router - a limited broadcast is not forwarded.
     */
    suspend fun send(
        macAddress: String,
        broadcastAddress: String = LIMITED_BROADCAST,
        port: Int = MagicPacket.DEFAULT_PORT,
    ): Outcome<Unit> = withContext(dispatcher) {
        val mac = MagicPacket.parseMac(macAddress)
            ?: return@withContext Outcome.Failure(
                NetToolboxError(ErrorReason.INVALID_INPUT, detail = macAddress),
            )

        if (port !in 1..65535) {
            return@withContext Outcome.Failure(
                NetToolboxError(ErrorReason.INVALID_INPUT, detail = "Port $port"),
            )
        }

        suspendRunCatching {
            val payload = MagicPacket.build(mac)
            val target = InetAddress.getByName(broadcastAddress)

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(payload, payload.size, target, port))
            }
        }
    }

    private companion object {
        const val LIMITED_BROADCAST = "255.255.255.255"
    }
}
