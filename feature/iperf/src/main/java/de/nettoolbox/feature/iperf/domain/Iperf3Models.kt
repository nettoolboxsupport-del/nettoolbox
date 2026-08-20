package de.nettoolbox.feature.iperf.domain

import de.nettoolbox.core.common.result.NetToolboxError
import kotlinx.serialization.Serializable

@Serializable
data class Iperf3ClientRequest(
    val host: String,
    val port: Int = 5201,
    val useUdp: Boolean = false,
    val durationSeconds: Int = 10,
    val reverse: Boolean = false,
    val parallelStreams: Int = 1,
    /** 0 = unlimited. */
    val rateLimitBitsPerSecond: Long = 0,
)

/**
 * The headline numbers a technician looks at first, parsed out of the raw
 * JSON on a best-effort basis - see [Iperf3RunResult.rawJson] for everything
 * else. Both are null rather than 0 when a field is missing, so a genuinely
 * measured zero throughput can never be confused with "not parsed".
 */
data class Iperf3Summary(
    val sentBitsPerSecond: Double?,
    val receivedBitsPerSecond: Double?,
    val retransmits: Long?,
)

sealed interface Iperf3RunResult {
    data class Success(val summary: Iperf3Summary, val rawJson: String) : Iperf3RunResult
    data class Failed(val error: NetToolboxError) : Iperf3RunResult
}
