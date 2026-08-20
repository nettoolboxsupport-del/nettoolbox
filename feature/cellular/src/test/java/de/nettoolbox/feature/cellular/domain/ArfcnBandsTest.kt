package de.nettoolbox.feature.cellular.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Band edges get their own cases: an off-by-one at a boundary produces a
 * plausible-looking wrong band, which is far worse in a drive-test log than a
 * blank field.
 */
class ArfcnBandsTest {

    @ParameterizedTest
    @CsvSource(
        "0, 1", "599, 1",
        "600, 2", "1199, 2",
        "1200, 3", "1949, 3",
        "1950, 4",
        "2400, 5", "2649, 5",
        "2750, 7", "3449, 7",
        "3450, 8", "3799, 8",
        "6150, 20", "6449, 20",
        "6450, 21",
        "9210, 28", "9659, 28",
        "38650, 40", "39649, 40",
    )
    fun `maps LTE EARFCN to the band, including both edges`(earfcn: Int, band: Int) {
        assertEquals(band, ArfcnBands.lteBandOf(earfcn)?.number)
    }

    @Test
    fun `returns null inside an unallocated EARFCN gap instead of guessing`() {
        // 2650..2749 lies between band 5 and band 7.
        assertNull(ArfcnBands.lteBandOf(2700))
        assertNull(ArfcnBands.lteBandOf(-1))
        assertNull(ArfcnBands.lteBandOf(null))
    }

    @Test
    fun `computes NR frequencies across all three raster ranges`() {
        // Range 1: 5 kHz raster.
        assertEquals(1805.0, ArfcnBands.nrFrequencyMhz(361_000))
        // Range 2: 15 kHz raster from 3000 MHz.
        assertEquals(3489.42, ArfcnBands.nrFrequencyMhz(632_628)!!, 0.001)
        // Range 3: 60 kHz raster from 24250.08 MHz.
        assertEquals(24_250.08, ArfcnBands.nrFrequencyMhz(2_016_667)!!, 0.001)

        assertNull(ArfcnBands.nrFrequencyMhz(3_279_166))
    }

    @Test
    fun `maps the NR channels a European network actually uses`() {
        assertEquals(78, ArfcnBands.nrBandOf(632_628)?.number)
        assertEquals(3, ArfcnBands.nrBandOf(361_000)?.number)
        assertEquals(28, ArfcnBands.nrBandOf(156_000)?.number)
    }

    @Test
    fun `prefers n78 over n77 where the two overlap`() {
        // Both cover 3300-3800; n78 is what is deployed in Europe, so a bare n77
        // label there would be technically true and practically misleading.
        val band = ArfcnBands.nrBandOf(632_628)

        assertEquals(78, band?.number)
    }

    @Test
    fun `maps UMTS and GSM channels`() {
        assertEquals(1, ArfcnBands.umtsBandOf(10_700)?.number)
        assertEquals(8, ArfcnBands.umtsBandOf(3_000)?.number)
        assertNull(ArfcnBands.umtsBandOf(1))

        assertEquals("P-GSM 900", ArfcnBands.gsmBandOf(30)?.label)
        assertEquals("E-GSM 900", ArfcnBands.gsmBandOf(1_000)?.label)
        assertEquals("DCS 1800", ArfcnBands.gsmBandOf(600)?.label)
        assertNull(ArfcnBands.gsmBandOf(300))
    }

    @Test
    fun `turns timing advance into a distance estimate`() {
        assertEquals(0, ArfcnBands.timingAdvanceToMeters(0))
        assertEquals(780, ArfcnBands.timingAdvanceToMeters(10))
        assertNull(ArfcnBands.timingAdvanceToMeters(null))
        assertNull(ArfcnBands.timingAdvanceToMeters(-1))
    }
}
