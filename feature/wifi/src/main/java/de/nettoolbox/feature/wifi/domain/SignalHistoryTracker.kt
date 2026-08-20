package de.nettoolbox.feature.wifi.domain

import javax.inject.Inject
import javax.inject.Singleton

data class SignalSample(val timestampMillis: Long, val rssiDbm: Int)

/**
 * Keeps a bounded signal history per BSSID.
 *
 * In memory rather than in Room: this is what a technician watches while walking
 * an antenna into position, and it is worthless five minutes later. Persisting it
 * would fill the database with data nobody asks for again - the drive-test
 * session of phase 4 is where measurements belong on disk.
 */
@Singleton
class SignalHistoryTracker @Inject constructor() {

    private val history = LinkedHashMap<String, ArrayDeque<SignalSample>>()

    @Synchronized
    fun record(networks: List<WifiNetwork>, now: Long = System.currentTimeMillis()) {
        networks.forEach { network ->
            if (network.bssid.isBlank()) return@forEach

            val samples = history.getOrPut(network.bssid) { ArrayDeque() }
            // Scans can repeat without a new measurement; a duplicate timestamp
            // would flatten the chart with points that carry no information.
            if (samples.lastOrNull()?.timestampMillis == now) return@forEach

            samples.addLast(SignalSample(now, network.rssiDbm))
            while (samples.size > MAX_SAMPLES_PER_BSSID) {
                samples.removeFirst()
            }
        }

        pruneTrackedNetworks()
    }

    @Synchronized
    fun historyOf(bssid: String): List<SignalSample> =
        history[bssid]?.toList().orEmpty()

    @Synchronized
    fun trackedBssids(): Set<String> = history.keys.toSet()

    @Synchronized
    fun clear() = history.clear()

    /**
     * Drops the least recently seen networks once too many are tracked. A site
     * survey in a business park can walk past hundreds of BSSIDs.
     */
    private fun pruneTrackedNetworks() {
        while (history.size > MAX_TRACKED_BSSIDS) {
            val oldest = history.entries.minByOrNull {
                it.value.lastOrNull()?.timestampMillis ?: 0L
            } ?: break
            history.remove(oldest.key)
        }
    }

    companion object {
        const val MAX_SAMPLES_PER_BSSID = 120
        const val MAX_TRACKED_BSSIDS = 256
    }
}
