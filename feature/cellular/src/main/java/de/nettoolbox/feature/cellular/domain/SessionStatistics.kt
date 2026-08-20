package de.nettoolbox.feature.cellular.domain

import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.database.entity.CellSampleEntity

data class SessionStatistics(
    val sampleCount: Int,
    val positionedCount: Int,
    val handoverCount: Int,
    val medianRsrp: Int?,
    val worstRsrp: Int?,
    val bestRsrp: Int?,
    val ratShare: Map<RadioAccessTechnology, Int>,
    val durationMillis: Long,
    val noServiceSamples: Int,
)

/**
 * Statistics over a recorded session.
 *
 * Pure, so the numbers that end up in a customer report are tested rather than
 * eyeballed. Nothing here invents data: a session without positions reports zero
 * positioned samples instead of a coverage figure.
 */
object SessionAnalysis {

    fun analyse(samples: List<CellSampleEntity>): SessionStatistics {
        if (samples.isEmpty()) {
            return SessionStatistics(0, 0, 0, null, null, null, emptyMap(), 0, 0)
        }

        val ordered = samples.sortedBy { it.ts }
        val rsrps = ordered.mapNotNull { it.rsrp }.sorted()

        return SessionStatistics(
            sampleCount = ordered.size,
            positionedCount = ordered.count { it.lat != null && it.lon != null },
            handoverCount = countHandovers(ordered),
            medianRsrp = rsrps.median(),
            worstRsrp = rsrps.firstOrNull(),
            bestRsrp = rsrps.lastOrNull(),
            ratShare = ordered.groupingBy { it.rat }.eachCount(),
            durationMillis = ordered.last().ts - ordered.first().ts,
            noServiceSamples = ordered.count { it.cid == null && it.pci == null },
        )
    }

    /**
     * Counts serving-cell changes.
     *
     * A handover is a change of the cell identity, not of the signal. Samples
     * without a cell id are skipped rather than treated as a change - otherwise a
     * momentary reporting gap would show up as two handovers.
     */
    fun countHandovers(samples: List<CellSampleEntity>): Int {
        var previous: Long? = null
        var count = 0

        samples.sortedBy { it.ts }.forEach { sample ->
            val current = sample.cid ?: return@forEach
            if (previous != null && current != previous) count++
            previous = current
        }
        return count
    }

    /**
     * Median of an already sorted list. The median rather than the mean because
     * a single deep fade would drag an average down and misrepresent a route
     * that was otherwise fine.
     */
    private fun List<Int>.median(): Int? = when {
        isEmpty() -> null
        size % 2 == 1 -> this[size / 2]
        else -> (this[size / 2 - 1] + this[size / 2]) / 2
    }
}
