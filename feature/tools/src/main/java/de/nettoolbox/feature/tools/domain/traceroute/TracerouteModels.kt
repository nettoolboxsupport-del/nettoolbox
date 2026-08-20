package de.nettoolbox.feature.tools.domain.traceroute

import de.nettoolbox.core.common.result.NetToolboxError
import kotlinx.serialization.Serializable

@Serializable
data class TracerouteRequest(
    val target: String,
    val maxHops: Int = 30,
    val timeoutSeconds: Int = 2,
    val payloadSizeBytes: Int = 56,
)

/**
 * One hop's outcome.
 *
 * [address] and [rttMillis] are both null when nothing answered within the
 * timeout - the ordinary, expected result for a hop behind a firewall that
 * drops expired-TTL packets instead of reporting them, not a sign that
 * anything went wrong.
 */
data class TracerouteHop(
    val ttl: Int,
    val address: String?,
    val rttMillis: Double?,
    val isDestination: Boolean,
    val isUnreachable: Boolean,
)

sealed interface TracerouteEvent {
    data class Hop(val hop: TracerouteHop) : TracerouteEvent
    data class Failed(val error: NetToolboxError) : TracerouteEvent
    data object Completed : TracerouteEvent
}
