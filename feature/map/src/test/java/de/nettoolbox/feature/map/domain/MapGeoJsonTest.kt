package de.nettoolbox.feature.map.domain

import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.database.entity.CellSampleEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapGeoJsonTest {

    private fun sample(
        ts: Long = 1L,
        lat: Double? = 52.5,
        lon: Double? = 13.4,
        rsrp: Int? = -95,
    ) = CellSampleEntity(
        sessionId = 1,
        ts = ts,
        lat = lat,
        lon = lon,
        subId = 1,
        rat = RadioAccessTechnology.LTE,
        rsrp = rsrp,
    )

    @Test
    fun `writes a FeatureCollection with longitude first`() {
        val json = MapGeoJson.samplesToGeoJson(listOf(sample()))

        assertTrue(json.contains("\"FeatureCollection\""))
        assertTrue(json.contains("\"coordinates\":[13.4,52.5]"))
    }

    @Test
    fun `skips samples without a position`() {
        val json = MapGeoJson.samplesToGeoJson(
            listOf(sample(), sample(lat = null, lon = null)),
        )

        assertEquals(1, Regex("\"Feature\"").findAll(json).count())
    }

    @Test
    fun `attaches a quality bucket instead of a colour`() {
        // The style owns the palette; the data owns the meaning.
        assertEquals("excellent", MapGeoJson.qualityOf(-75))
        assertEquals("good", MapGeoJson.qualityOf(-85))
        assertEquals("fair", MapGeoJson.qualityOf(-95))
        assertEquals("poor", MapGeoJson.qualityOf(-105))
        assertEquals("bad", MapGeoJson.qualityOf(-120))
        assertEquals("unknown", MapGeoJson.qualityOf(null))
    }

    @Test
    fun `omits metrics the modem did not report`() {
        val json = MapGeoJson.samplesToGeoJson(listOf(sample(rsrp = null)))

        assertFalse(json.contains("\"rsrp\""))
        assertTrue(json.contains("\"quality\":\"unknown\""))
    }

    // ---- downsampling -------------------------------------------------------

    @Test
    fun `a short list is returned untouched`() {
        val points = listOf(1, 2, 3)
        assertSame(points, MapGeoJson.downsample(points, maxPoints = 10))
    }

    @Test
    fun `downsampling keeps the requested count`() {
        val points = (1..1000).toList()

        assertEquals(10, MapGeoJson.downsample(points, maxPoints = 10).size)
    }

    @Test
    fun `the first and last point always survive`() {
        // Otherwise a drive test would appear to start late and end early.
        val points = (1..1000).toList()

        val reduced = MapGeoJson.downsample(points, maxPoints = 10)

        assertEquals(1, reduced.first())
        assertEquals(1000, reduced.last())
    }

    @Test
    fun `points stay in order so the route keeps its shape`() {
        val points = (1..500).toList()

        val reduced = MapGeoJson.downsample(points, maxPoints = 25)

        assertEquals(reduced.sorted(), reduced)
    }

    @Test
    fun `a budget of one or less is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MapGeoJson.downsample(listOf(1, 2, 3), maxPoints = 1)
        }
    }
}
