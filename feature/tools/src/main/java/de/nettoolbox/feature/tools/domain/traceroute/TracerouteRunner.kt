package de.nettoolbox.feature.tools.domain.traceroute

import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.icmp.ICMP_STATUS_REPLY
import de.nettoolbox.icmp.ICMP_STATUS_TIME_EXCEEDED
import de.nettoolbox.icmp.ICMP_STATUS_UNREACHABLE
import de.nettoolbox.icmp.IcmpNativeBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * TTL-ramp traceroute over the same native ping socket used for plain pings
 * (spec section 4.3 - "ICMP-Modus"), one probe per hop with the destination's
 * own echo reply as the stopping condition.
 *
 * This is the part of the native module with the least confidence behind it:
 * it depends on `IP_RECVERR`/`MSG_ERRQUEUE` delivering ICMP errors, which is
 * standard, documented Linux behaviour but has not been exercised against a
 * real kernel from where this was written. See the comment above
 * `read_error_queue` in icmp_ping.c. If every hop times out, including ones
 * that a working traceroute would resolve, that is where to look first.
 */
class TracerouteRunner @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    fun run(request: TracerouteRequest): Flow<TracerouteEvent> = flow {
        if (isIpv6Literal(request.target)) {
            // icmp_ping.c only speaks ICMPv4; see IcmpDatagramPingRunner for
            // the same restriction on plain ping.
            emit(
                TracerouteEvent.Failed(
                    NetToolboxError(
                        ErrorReason.UNSUPPORTED_ON_DEVICE,
                        detail = "IPv6 is not supported by the native traceroute module",
                    ),
                ),
            )
            return@flow
        }

        val address = try {
            InetAddress.getByName(request.target)
        } catch (unknown: UnknownHostException) {
            emit(
                TracerouteEvent.Failed(
                    NetToolboxError(ErrorReason.HOST_UNREACHABLE, request.target, cause = unknown),
                ),
            )
            return@flow
        }
        val destinationIp = address.hostAddress ?: request.target

        val fd = IcmpNativeBridge.open()
        if (fd == null) {
            emit(
                TracerouteEvent.Failed(
                    NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "Kernel refused a ping socket"),
                ),
            )
            return@flow
        }

        if (!IcmpNativeBridge.enableReceiveErrors(fd)) {
            IcmpNativeBridge.close(fd)
            emit(
                TracerouteEvent.Failed(
                    NetToolboxError(ErrorReason.NATIVE_UNAVAILABLE, "Could not enable ICMP error reporting"),
                ),
            )
            return@flow
        }

        try {
            var reachedEnd = false
            var ttl = 1

            while (ttl <= request.maxHops && !reachedEnd) {
                currentCoroutineContext().ensureActive()

                IcmpNativeBridge.setTtl(fd, ttl)
                // One sequence per hop, equal to the TTL: unique across the
                // whole run, so a stray late answer from an earlier hop can
                // never be mistaken for the current one.
                val sequence = ttl

                val startedAtNanos = System.nanoTime()
                val sent = IcmpNativeBridge.sendEcho(
                    fd = fd,
                    destinationIp = destinationIp,
                    sequence = sequence,
                    payloadSize = request.payloadSizeBytes,
                )

                val hop = if (!sent) {
                    TracerouteHop(ttl, null, null, isDestination = false, isUnreachable = false)
                } else {
                    val (status, from) = IcmpNativeBridge.receiveHop(
                        fd = fd,
                        sequence = sequence,
                        timeoutMillis = request.timeoutSeconds * 1000,
                    )
                    val rttMillis = (System.nanoTime() - startedAtNanos) / 1_000_000.0

                    when (status) {
                        ICMP_STATUS_REPLY -> {
                            reachedEnd = true
                            TracerouteHop(
                                ttl = ttl,
                                address = from.ifEmpty { destinationIp },
                                rttMillis = rttMillis,
                                isDestination = true,
                                isUnreachable = false,
                            )
                        }

                        ICMP_STATUS_TIME_EXCEEDED -> TracerouteHop(
                            ttl = ttl,
                            address = from.ifEmpty { null },
                            rttMillis = rttMillis,
                            isDestination = false,
                            isUnreachable = false,
                        )

                        ICMP_STATUS_UNREACHABLE -> {
                            // Nothing further out will answer either; stop here
                            // rather than spending the remaining hop budget on
                            // certain timeouts.
                            reachedEnd = true
                            TracerouteHop(
                                ttl = ttl,
                                address = from.ifEmpty { null },
                                rttMillis = rttMillis,
                                isDestination = false,
                                isUnreachable = true,
                            )
                        }

                        else -> TracerouteHop(ttl, null, null, isDestination = false, isUnreachable = false)
                    }
                }

                emit(TracerouteEvent.Hop(hop))
                ttl++
            }
        } finally {
            IcmpNativeBridge.close(fd)
        }

        emit(TracerouteEvent.Completed)
    }.flowOn(dispatcher)

    private fun isIpv6Literal(target: String): Boolean = target.contains(':')
}
