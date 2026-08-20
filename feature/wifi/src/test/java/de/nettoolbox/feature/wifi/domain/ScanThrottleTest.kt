package de.nettoolbox.feature.wifi.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScanThrottleTest {

    private val now = 1_000_000L

    @Test
    fun `the first four scans in a window are allowed`() {
        val scans = listOf(now - 90_000, now - 60_000, now - 30_000)

        assertTrue(ScanThrottle.isScanAllowed(scans, now))
        assertEquals(0L, ScanThrottle.millisUntilNextScan(scans, now))
    }

    @Test
    fun `the fifth scan waits for the oldest one to leave the window`() {
        val scans = listOf(now - 90_000, now - 60_000, now - 30_000, now - 10_000)

        // The oldest is 90 s old, so its slot frees up in 30 s.
        assertEquals(30_000L, ScanThrottle.millisUntilNextScan(scans, now))
        assertFalse(ScanThrottle.isScanAllowed(scans, now))
    }

    @Test
    fun `scans older than the window do not count`() {
        val scans = listOf(now - 200_000, now - 150_000, now - 130_000, now - 10_000)

        assertTrue(ScanThrottle.isScanAllowed(scans, now))
    }

    @Test
    fun `more than four in the window still resolves to the correct wait`() {
        val scans = listOf(now - 110_000, now - 100_000, now - 50_000, now - 40_000, now - 5_000)

        // Five in the window: the fourth from the end is the blocking one, so the
        // wait follows the 100 s old scan, not the 110 s one.
        assertEquals(20_000L, ScanThrottle.millisUntilNextScan(scans, now))
    }

    @Test
    fun `an empty history allows a scan`() {
        assertTrue(ScanThrottle.isScanAllowed(emptyList(), now))
    }

    @Test
    fun `pruning drops what has left the window`() {
        val scans = listOf(now - 200_000, now - 30_000)

        assertEquals(listOf(now - 30_000), ScanThrottle.prune(scans, now))
    }
}
