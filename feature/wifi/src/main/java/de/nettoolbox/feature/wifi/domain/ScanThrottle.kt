package de.nettoolbox.feature.wifi.domain

/**
 * Android's Wi-Fi scan throttling, as arithmetic.
 *
 * From Android 9 an app may trigger four scans per two minutes in the
 * foreground; from Android 10 the limit can only be lifted in the developer
 * options. The app cannot work around this, so it models it exactly and shows the
 * user when the next scan is possible - the alternative is a button that silently
 * does nothing, which is what most Wi-Fi apps do.
 *
 * Pure, so the boundary behaviour is tested rather than observed in the field two
 * minutes at a time.
 */
object ScanThrottle {

    const val MAX_SCANS_PER_WINDOW = 4
    const val WINDOW_MILLIS = 120_000L

    /**
     * @param recentScanTimestamps when scans were requested, any order
     * @return 0 when a scan is allowed now, otherwise the wait in milliseconds
     */
    fun millisUntilNextScan(recentScanTimestamps: List<Long>, now: Long): Long {
        val inWindow = recentScanTimestamps
            .filter { now - it < WINDOW_MILLIS }
            .sorted()

        if (inWindow.size < MAX_SCANS_PER_WINDOW) return 0L

        // The oldest scan still inside the window is the one whose expiry frees
        // the next slot.
        val oldestBlocking = inWindow[inWindow.size - MAX_SCANS_PER_WINDOW]
        return (oldestBlocking + WINDOW_MILLIS - now).coerceAtLeast(0L)
    }

    fun isScanAllowed(recentScanTimestamps: List<Long>, now: Long): Boolean =
        millisUntilNextScan(recentScanTimestamps, now) == 0L

    /** Drops timestamps that have left the window, so the list cannot grow forever. */
    fun prune(recentScanTimestamps: List<Long>, now: Long): List<Long> =
        recentScanTimestamps.filter { now - it < WINDOW_MILLIS }
}
