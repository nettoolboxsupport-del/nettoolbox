package de.nettoolbox.feature.cellular.domain

import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.database.entity.CellSampleEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionAnalysisTest {

    private var nextId = 1L

    private fun sample(
        ts: Long,
        cid: Long? = 100L,
        rsrp: Int? = -90,
        lat: Double? = 52.0,
        rat: RadioAccessTechnology = RadioAccessTechnology.LTE,
    ) = CellSampleEntity(
        id = nextId++,
        sessionId = 1,
        ts = ts,
        lat = lat,
        lon = lat?.let { 13.0 },
        subId = 1,
        rat = rat,
        cid = cid,
        rsrp = rsrp,
    )

    @Test
    fun `an empty session yields zeroes, not nulls in the counters`() {
        val stats = SessionAnalysis.analyse(emptyList())

        assertEquals(0, stats.sampleCount)
        assertEquals(0, stats.handoverCount)
        assertNull(stats.medianRsrp)
    }

    @Test
    fun `counts a handover for every change of the serving cell`() {
        val samples = listOf(
            sample(ts = 1, cid = 100),
            sample(ts = 2, cid = 100),
            sample(ts = 3, cid = 200),
            sample(ts = 4, cid = 200),
            sample(ts = 5, cid = 100),
        )

        assertEquals(2, SessionAnalysis.countHandovers(samples))
    }

    @Test
    fun `a reporting gap is not counted as two handovers`() {
        // A sample without a cell id means the modem said nothing, not that the
        // device moved to a different cell and back.
        val samples = listOf(
            sample(ts = 1, cid = 100),
            sample(ts = 2, cid = null),
            sample(ts = 3, cid = 100),
        )

        assertEquals(0, SessionAnalysis.countHandovers(samples))
    }

    @Test
    fun `handovers are counted in time order, not list order`() {
        val samples = listOf(
            sample(ts = 3, cid = 200),
            sample(ts = 1, cid = 100),
            sample(ts = 2, cid = 100),
        )

        assertEquals(1, SessionAnalysis.countHandovers(samples))
    }

    @Test
    fun `median is used so a single deep fade does not distort the result`() {
        val samples = listOf(
            sample(ts = 1, rsrp = -85),
            sample(ts = 2, rsrp = -87),
            sample(ts = 3, rsrp = -89),
            sample(ts = 4, rsrp = -140),
        )

        val stats = SessionAnalysis.analyse(samples)

        // Mean would be about -100; the median reflects the route far better.
        assertEquals(-88, stats.medianRsrp)
        assertEquals(-140, stats.worstRsrp)
        assertEquals(-85, stats.bestRsrp)
    }

    @Test
    fun `counts positioned samples separately from all samples`() {
        val samples = listOf(
            sample(ts = 1, lat = 52.0),
            sample(ts = 2, lat = null),
            sample(ts = 3, lat = 52.1),
        )

        val stats = SessionAnalysis.analyse(samples)

        assertEquals(3, stats.sampleCount)
        assertEquals(2, stats.positionedCount)
    }

    @Test
    fun `reports the share of each radio technology and the duration`() {
        val samples = listOf(
            sample(ts = 1_000, rat = RadioAccessTechnology.LTE),
            sample(ts = 2_000, rat = RadioAccessTechnology.LTE),
            sample(ts = 5_000, rat = RadioAccessTechnology.NR_NSA),
        )

        val stats = SessionAnalysis.analyse(samples)

        assertEquals(2, stats.ratShare[RadioAccessTechnology.LTE])
        assertEquals(1, stats.ratShare[RadioAccessTechnology.NR_NSA])
        assertEquals(4_000L, stats.durationMillis)
    }
}
