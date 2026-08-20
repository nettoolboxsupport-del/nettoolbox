package de.nettoolbox.feature.tools.domain.ping

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.core.datastore.model.PingMethod
import de.nettoolbox.icmp.IcmpNativeBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides how a ping is produced and runs it.
 *
 * Which mechanism works cannot be known in advance - it depends on the kernel's
 * `ping_group_range` and on which `ping` binary the vendor shipped. The app
 * probes once, caches the answer in the settings and lets the user override it,
 * exactly as section 3.3 of the spec requires.
 */
@Singleton
class PingService @Inject constructor(
    private val icmpRunner: IcmpDatagramPingRunner,
    private val systemRunner: SystemPingRunner,
    private val tcpRunner: TcpPingRunner,
    private val settingsRepository: SettingsRepository,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /**
     * The transport to use, running the one-off probe if it has not run yet.
     *
     * The ICMP check is live on every call rather than read from the cache:
     * it costs only a `socket()` and a `bind()`, no network and no process
     * spawn, so caching it would save nothing. It matters more than that,
     * though - phase 5 added ICMP_DATAGRAM as an option after phase 2 could
     * have already cached [de.nettoolbox.core.datastore.model.PingMethod
     * .TCP_CONNECT] as the detected method on this exact device. Without a
     * live check, that stale cache would keep a device capable of real ICMP
     * on TCP timing forever.
     */
    suspend fun resolveTransport(): PingTransport {
        val settings = settingsRepository.settings.first()

        settings.pingMethod.toTransport()?.let { return it }

        if (withContext(dispatcher) { IcmpNativeBridge.isAvailable() }) {
            return PingTransport.ICMP_DATAGRAM
        }

        settings.detectedPingMethod
            ?.toTransport()
            ?.takeUnless { it == PingTransport.ICMP_DATAGRAM } // just found unavailable above
            ?.let { return it }

        val detected = probeFallback()
        settingsRepository.update { it.copy(detectedPingMethod = detected.toMethod()) }
        return detected
    }

    fun run(request: PingRequest, transport: PingTransport): Flow<PingEvent> = when (transport) {
        PingTransport.ICMP_DATAGRAM -> icmpRunner.run(request)
        PingTransport.SYSTEM_BINARY -> systemRunner.run(request)
        PingTransport.TCP_CONNECT -> tcpRunner.run(request)
    }

    /**
     * Falls back to the system binary, tested against loopback so the probe
     * needs no network, no permission and no name resolution - a failure
     * there means the binary itself is unusable. Only reached once ICMP has
     * already been ruled out for this call.
     */
    private suspend fun probeFallback(): PingTransport = withContext(dispatcher) {
        val systemBinaryWorks = try {
            val process = ProcessBuilder(
                listOf("/system/bin/ping", "-c", "1", "-W", "1", "127.0.0.1"),
            ).redirectErrorStream(true).start()

            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (io: java.io.IOException) {
            false
        }

        if (systemBinaryWorks) PingTransport.SYSTEM_BINARY else PingTransport.TCP_CONNECT
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 5L
    }
}

/** null for [PingMethod.AUTO]: that is not a transport, it is the absence of a choice. */
internal fun PingMethod.toTransport(): PingTransport? = when (this) {
    PingMethod.AUTO -> null
    PingMethod.ICMP_DATAGRAM -> PingTransport.ICMP_DATAGRAM
    PingMethod.SYSTEM_BINARY -> PingTransport.SYSTEM_BINARY
    PingMethod.TCP_CONNECT -> PingTransport.TCP_CONNECT
}

internal fun PingTransport.toMethod(): PingMethod = when (this) {
    PingTransport.ICMP_DATAGRAM -> PingMethod.ICMP_DATAGRAM
    PingTransport.SYSTEM_BINARY -> PingMethod.SYSTEM_BINARY
    PingTransport.TCP_CONNECT -> PingMethod.TCP_CONNECT
}
