package de.nettoolbox.feature.tools.domain.ping

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.icmp.ICMP_STATUS_REPLY
import de.nettoolbox.icmp.IcmpNativeBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * Ping over a real ICMP echo request/reply, via the unprivileged ping socket
 * in `:native:icmp` (spec section 3.3, option 1 - "der saubere Weg").
 *
 * Whether this works at all depends on the device's `ping_group_range`
 * sysctl, which this app cannot query or change. [PingService] probes it once
 * and falls back to the system binary or TCP timing when it does not.
 *
 * Unverified on real hardware from this environment: the native layer was
 * written against documented Linux ping-socket semantics, not exercised
 * against an actual kernel here. If probing reports the transport available
 * but replies never arrive, that gap is the first place to look.
 */
class IcmpDatagramPingRunner @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    fun run(request: PingRequest): Flow<PingEvent> = flow {
        if (isIpv6Literal(request.target)) {
            // icmp_ping.c only builds ICMPv4 packets; ICMPv6 is a different
            // protocol number and header layout, out of scope for this module.
            emit(
                PingEvent.Failed(
                    NetToolboxError(
                        ErrorReason.UNSUPPORTED_ON_DEVICE,
                        detail = "IPv6 needs the system ping binary",
                    ),
                ),
            )
            return@flow
        }

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

        val destinationIp = address.hostAddress ?: request.target

        val fd = IcmpNativeBridge.open()
        if (fd == null) {
            emit(
                PingEvent.Failed(
                    NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "Kernel refused a ping socket"),
                ),
            )
            return@flow
        }

        val rtts = mutableListOf<Double>()
        var sequence = 0

        try {
            request.ttl?.let { IcmpNativeBridge.setTtl(fd, it) }

            while (request.count == null || sequence < request.count) {
                currentCoroutineContext().ensureActive()

                val startedAtNanos = System.nanoTime()
                val sent = IcmpNativeBridge.sendEcho(
                    fd = fd,
                    destinationIp = destinationIp,
                    sequence = sequence,
                    payloadSize = request.payloadSizeBytes,
                )

                if (!sent) {
                    emit(PingEvent.Loss(sequence, LossReason.UNKNOWN))
                } else {
                    val (status, from) = IcmpNativeBridge.receiveEcho(
                        fd = fd,
                        sequence = sequence,
                        timeoutMillis = request.timeoutSeconds * 1000,
                    )

                    if (status == ICMP_STATUS_REPLY) {
                        val rttMillis = (System.nanoTime() - startedAtNanos) / 1_000_000.0
                        rtts += rttMillis
                        emit(
                            PingEvent.Reply(
                                sequence = sequence,
                                from = from.ifEmpty { destinationIp },
                                // A ping socket's recvfrom() yields the ICMP
                                // payload only, not the reply's IP header, so
                                // its TTL is not available here.
                                ttl = null,
                                rttMillis = rttMillis,
                            ),
                        )
                    } else {
                        emit(PingEvent.Loss(sequence, LossReason.TIMEOUT))
                    }
                }

                sequence++
                if (request.count == null || sequence < request.count) {
                    delay(request.intervalMillis)
                }
            }
        } finally {
            IcmpNativeBridge.close(fd)
        }

        emit(PingEvent.Completed(PingStatistics.of(sequence, rtts)))
    }.flowOn(dispatcher)

    private fun isIpv6Literal(target: String): Boolean = target.contains(':')
}
