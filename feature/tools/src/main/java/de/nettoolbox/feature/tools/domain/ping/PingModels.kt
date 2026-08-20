package de.nettoolbox.feature.tools.domain.ping

import de.nettoolbox.core.common.result.NetToolboxError
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * How a "ping" is actually produced.
 *
 * The distinction is visible in the UI on purpose: a TCP connect time is not an
 * ICMP round trip, and presenting it as one would misreport the network.
 */
@Serializable
enum class PingTransport {
    /** Unprivileged ICMP datagram socket via the native module (phase 5). */
    ICMP_DATAGRAM,

    /** /system/bin/ping, output parsed from stdout. */
    SYSTEM_BINARY,

    /** TCP connect timing - labelled as "TCP ping" everywhere it is shown. */
    TCP_CONNECT,
}

@Serializable
data class PingRequest(
    val target: String,
    /** null runs until cancelled. */
    val count: Int? = 4,
    val intervalMillis: Long = 1_000,
    val payloadSizeBytes: Int = 56,
    val timeoutSeconds: Int = 4,
    val ttl: Int? = null,
    val dontFragment: Boolean = false,
    /** Only used by [PingTransport.TCP_CONNECT]. */
    val tcpPort: Int = 443,
) {
    val isEndless: Boolean get() = count == null
}

enum class LossReason {
    TIMEOUT,
    HOST_UNREACHABLE,
    TTL_EXCEEDED,
    UNKNOWN,
}

sealed interface PingEvent {

    data class Reply(
        val sequence: Int,
        val from: String,
        val ttl: Int?,
        val rttMillis: Double,
    ) : PingEvent

    data class Loss(
        val sequence: Int,
        val reason: LossReason,
        val from: String? = null,
    ) : PingEvent

    /** The run could not start or died; terminal. */
    data class Failed(val error: NetToolboxError) : PingEvent

    data class Completed(val statistics: PingStatistics) : PingEvent
}

@Serializable
data class PingStatistics(
    val sent: Int,
    val received: Int,
    val minMillis: Double?,
    val avgMillis: Double?,
    val maxMillis: Double?,
    val mdevMillis: Double?,
) {
    val lostPackets: Int get() = (sent - received).coerceAtLeast(0)

    val lossPercent: Double get() = if (sent == 0) 0.0 else lostPackets * 100.0 / sent

    companion object {

        /**
         * Statistics are computed from the replies the app saw, not parsed from
         * the summary block of `ping`.
         *
         * Two reasons: the summary only arrives when the process ends normally,
         * so a cancelled run would have none, and its format differs between
         * toybox, busybox and iputils.
         *
         * `mdev` follows iputils: the root of the mean squared deviation, not the
         * sample standard deviation.
         */
        fun of(sent: Int, rtts: List<Double>): PingStatistics {
            if (rtts.isEmpty()) {
                return PingStatistics(sent, 0, null, null, null, null)
            }

            val mean = rtts.average()
            val meanOfSquares = rtts.sumOf { it * it } / rtts.size
            val variance = (meanOfSquares - mean * mean).coerceAtLeast(0.0)

            return PingStatistics(
                sent = sent,
                received = rtts.size,
                minMillis = rtts.min(),
                avgMillis = mean,
                maxMillis = rtts.max(),
                mdevMillis = sqrt(variance),
            )
        }
    }
}
