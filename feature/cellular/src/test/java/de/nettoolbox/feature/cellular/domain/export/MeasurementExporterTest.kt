package de.nettoolbox.feature.cellular.domain.export

import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.database.entity.CellSampleEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeasurementExporterTest {

    private fun sample(
        lat: Double? = 52.520008,
        lon: Double? = 13.404954,
        rsrp: Int? = -95,
        operator: String? = "Testnetz",
    ) = CellSampleEntity(
        id = 1,
        sessionId = 1,
        ts = 1_700_000_000_000,
        lat = lat,
        lon = lon,
        accuracy = 5.5f,
        speed = 13.9f,
        subId = 1,
        rat = RadioAccessTechnology.LTE,
        mcc = 262,
        mnc = 1,
        operatorName = operator,
        cid = 12_345_678L,
        pci = 42,
        tac = 4711,
        arfcn = 1300,
        band = 3,
        bandwidthKhz = 20_000,
        rsrp = rsrp,
        rsrq = -10,
        sinr = 12,
        rssi = -65,
        cqi = 11,
        timingAdvance = 3,
    )

    // ---- CSV ----------------------------------------------------------------

    @Test
    fun `CSV writes a header and one row per sample`() {
        val csv = MeasurementExporter.toCsv(listOf(sample(), sample()))
        val lines = csv.trim().lines()

        assertEquals(3, lines.size)
        assertTrue(lines.first().startsWith("timestamp,lat,lon"))
    }

    @Test
    fun `CSV uses a decimal point regardless of locale`() {
        // A German decimal comma would split the column and break every consumer
        // downstream.
        val csv = MeasurementExporter.toCsv(listOf(sample()))

        assertTrue(csv.contains("52.520008"), "latitude must use a decimal point")
        assertFalse(csv.contains("52,520008"))
    }

    @Test
    fun `CSV leaves missing values empty rather than writing null`() {
        val csv = MeasurementExporter.toCsv(listOf(sample(lat = null, lon = null, rsrp = null)))
        val row = csv.trim().lines()[1]

        assertFalse(row.contains("null"))
        assertTrue(row.startsWith("1700000000000,,,"))
    }

    @Test
    fun `CSV quotes an operator name containing a separator`() {
        val csv = MeasurementExporter.toCsv(listOf(sample(operator = "Netz, GmbH")))

        assertTrue(csv.contains("\"Netz, GmbH\""))
    }

    // ---- GeoJSON ------------------------------------------------------------

    @Test
    fun `GeoJSON writes longitude before latitude`() {
        val json = MeasurementExporter.toGeoJson(listOf(sample()))

        val coordinates = json.substringAfter("\"coordinates\"").substringAfter("[")
            .substringBefore("]")
        val values = coordinates.split(",").map { it.trim().toDouble() }

        assertEquals(13.404954, values[0], 0.000001)
        assertEquals(52.520008, values[1], 0.000001)
    }

    @Test
    fun `GeoJSON skips samples without a position`() {
        // Writing 0/0 would drop every unpositioned sample into the Gulf of
        // Guinea and quietly corrupt the map.
        val json = MeasurementExporter.toGeoJson(
            listOf(sample(), sample(lat = null, lon = null)),
        )

        assertEquals(1, Regex("\"Feature\"").findAll(json).count())
    }

    @Test
    fun `GeoJSON omits keys the modem did not report`() {
        val json = MeasurementExporter.toGeoJson(listOf(sample(rsrp = null)))

        assertFalse(json.contains("\"rsrp\""))
    }

    // ---- KML ----------------------------------------------------------------

    @Test
    fun `KML is well-formed and colours by signal strength`() {
        val kml = MeasurementExporter.toKml(listOf(sample(rsrp = -75), sample(rsrp = -120)))

        assertTrue(kml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(kml.contains("#q-excellent"))
        assertTrue(kml.contains("#q-bad"))
        assertTrue(kml.trimEnd().endsWith("</kml>"))
    }

    @Test
    fun `KML escapes characters that would break the document`() {
        val kml = MeasurementExporter.toKml(
            listOf(sample(operator = "A & B <test>")),
            documentName = "Fahrt \"Nord\" & Süd",
        )

        assertTrue(kml.contains("Fahrt &quot;Nord&quot; &amp; S"))
        assertFalse(kml.contains("<test>"))
    }

    @Test
    fun `KML skips samples without a position`() {
        val kml = MeasurementExporter.toKml(listOf(sample(lat = null, lon = null)))

        assertFalse(kml.contains("<Placemark>"))
    }
}
