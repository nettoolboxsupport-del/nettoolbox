package de.nettoolbox.feature.wifi.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WifiChannelsTest {

    @ParameterizedTest
    @CsvSource(
        "2412, 1",
        "2437, 6",
        "2462, 11",
        "2472, 13",
        // Channel 14 breaks the 5 MHz spacing and needs its own case.
        "2484, 14",
        "5180, 36",
        "5320, 64",
        "5745, 149",
        "5825, 165",
    )
    fun `maps 2_4 and 5 GHz frequencies to channels`(frequency: Int, expected: Int) {
        assertEquals(expected, WifiChannels.channelOf(frequency))
    }

    @ParameterizedTest
    @CsvSource(
        "5955, 1",
        "6175, 45",
        "7115, 233",
        // The one 6 GHz channel that sits below the base frequency.
        "5935, 2",
    )
    fun `maps 6 GHz frequencies to channels`(frequency: Int, expected: Int) {
        assertEquals(expected, WifiChannels.channelOf(frequency))
    }

    @Test
    fun `assigns the band a technician would act on`() {
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiChannels.bandOf(2437))
        assertEquals(WifiBand.BAND_5_GHZ, WifiChannels.bandOf(5180))
        // 6 GHz must not be reported as 5 GHz - that would send someone to the
        // wrong radio.
        assertEquals(WifiBand.BAND_6_GHZ, WifiChannels.bandOf(6175))
        assertEquals(WifiBand.UNKNOWN, WifiChannels.bandOf(900))
    }

    @Test
    fun `rejects frequencies that are not on the channel grid`() {
        assertNull(WifiChannels.channelOf(2413))
        assertNull(WifiChannels.channelOf(900))
    }

    @Test
    fun `knows which 2_4 GHz channels overlap`() {
        // 22 MHz of occupied spectrum against 5 MHz numbering: anything less than
        // five channels apart shares spectrum.
        assertTrue(WifiChannels.overlaps2_4(1, 5))
        assertTrue(WifiChannels.overlaps2_4(6, 8))
        assertFalse(WifiChannels.overlaps2_4(1, 6))
        assertFalse(WifiChannels.overlaps2_4(1, 11))
        assertFalse(WifiChannels.overlaps2_4(6, 11))
    }

    @Test
    fun `recommends the channel with the least interference`() {
        val neighbours = listOf(1 to -40, 2 to -45, 6 to -50)

        assertEquals(11, WifiChannels.recommend2_4Channel(neighbours))
    }

    @Test
    fun `weights a strong neighbour above several weak ones`() {
        // One very strong AP on channel 11 must outweigh three faint ones on 1.
        val neighbours = listOf(11 to -35, 1 to -88, 1 to -90, 1 to -92)

        assertEquals(6, WifiChannels.recommend2_4Channel(neighbours))
    }

    @Test
    fun `falls back to the lowest channel when nothing is in the way`() {
        assertEquals(1, WifiChannels.recommend2_4Channel(emptyList()))
    }
}
