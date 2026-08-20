package de.nettoolbox.feature.wifi.domain

enum class WifiBand(val label: String) {
    BAND_2_4_GHZ("2,4 GHz"),
    BAND_5_GHZ("5 GHz"),
    BAND_6_GHZ("6 GHz"),
    UNKNOWN("?"),
}

/**
 * Frequency, channel and band arithmetic.
 *
 * Pure and unit-tested, because every one of these conversions is silently wrong
 * rather than loudly broken: a channel number off by one still looks plausible in
 * a list, and a 6 GHz network shown as 5 GHz would send a technician to change
 * the wrong radio.
 */
object WifiChannels {

    private const val CHANNEL_WIDTH_MHZ = 5

    // 2.4 GHz: channel 1 is 2412 MHz, spaced 5 MHz apart, with channel 14 as the
    // Japanese exception that does not follow the spacing.
    private const val BAND_2_4_FIRST_FREQUENCY = 2412
    private const val BAND_2_4_CHANNEL_14 = 2484

    private const val BAND_5_BASE = 5000
    private const val BAND_6_BASE = 5950
    /** 6 GHz channel 2 sits below the base and is the one irregular case. */
    private const val BAND_6_CHANNEL_2_FREQUENCY = 5935

    fun bandOf(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
        in 2401..2499 -> WifiBand.BAND_2_4_GHZ
        in 4900..5899 -> WifiBand.BAND_5_GHZ
        in 5925..7125 -> WifiBand.BAND_6_GHZ
        else -> WifiBand.UNKNOWN
    }

    fun channelOf(frequencyMhz: Int): Int? = when (bandOf(frequencyMhz)) {
        WifiBand.BAND_2_4_GHZ -> when {
            frequencyMhz == BAND_2_4_CHANNEL_14 -> 14
            frequencyMhz in BAND_2_4_FIRST_FREQUENCY..2472 &&
                (frequencyMhz - BAND_2_4_FIRST_FREQUENCY) % CHANNEL_WIDTH_MHZ == 0 ->
                (frequencyMhz - BAND_2_4_FIRST_FREQUENCY) / CHANNEL_WIDTH_MHZ + 1

            else -> null
        }

        WifiBand.BAND_5_GHZ ->
            ((frequencyMhz - BAND_5_BASE) / CHANNEL_WIDTH_MHZ).takeIf {
                (frequencyMhz - BAND_5_BASE) % CHANNEL_WIDTH_MHZ == 0 && it > 0
            }

        WifiBand.BAND_6_GHZ -> when {
            frequencyMhz == BAND_6_CHANNEL_2_FREQUENCY -> 2
            (frequencyMhz - BAND_6_BASE) % CHANNEL_WIDTH_MHZ == 0 ->
                ((frequencyMhz - BAND_6_BASE) / CHANNEL_WIDTH_MHZ).takeIf { it > 0 }

            else -> null
        }

        WifiBand.UNKNOWN -> null
    }

    /**
     * The 2.4 GHz channels that do not overlap each other. Everything else in
     * that band shares spectrum with its neighbours, which is the single most
     * common cause of a slow 2.4 GHz network.
     */
    val NON_OVERLAPPING_2_4_CHANNELS = listOf(1, 6, 11)

    /**
     * True when two 2.4 GHz channels share spectrum. Each channel is 22 MHz wide
     * while the numbering steps in 5 MHz, so anything closer than five channels
     * apart interferes.
     */
    fun overlaps2_4(channelA: Int, channelB: Int): Boolean {
        if (channelA !in 1..14 || channelB !in 1..14) return false
        return kotlin.math.abs(channelA - channelB) < 5
    }

    /**
     * Suggests the least congested of the non-overlapping 2.4 GHz channels.
     *
     * Weighted by signal strength rather than by count: one strong neighbour on a
     * channel does more damage than three faint ones two rooms away.
     *
     * @param neighbours channel to RSSI in dBm
     */
    fun recommend2_4Channel(neighbours: List<Pair<Int, Int>>): Int {
        val scores = NON_OVERLAPPING_2_4_CHANNELS.associateWith { candidate ->
            neighbours.sumOf { (channel, rssi) ->
                if (overlaps2_4(candidate, channel)) interferenceWeight(rssi) else 0L
            }
        }
        // Ties resolve to the lowest channel number, which keeps the answer stable
        // between two scans of the same room.
        return scores.entries.minWith(compareBy({ it.value }, { it.key })).key
    }

    /**
     * Turns an RSSI into an interference weight. -30 dBm counts roughly a hundred
     * times as much as -90 dBm, so the ranking follows perceived interference
     * rather than the raw count of networks.
     */
    private fun interferenceWeight(rssiDbm: Int): Long =
        (100 + rssiDbm.coerceIn(-100, 0)).toLong().let { it * it / 10 }
}
