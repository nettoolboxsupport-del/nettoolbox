package de.nettoolbox.feature.map.domain

import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.database.entity.KnownCellSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenCellIdCsvTest {

    private val importedAt = 1_700_000_000_000L

    private val validLine =
        "LTE,262,1,4711,12345678,,13.404954,52.520008,1000,42,1,1500000000,1600000000,-85"

    @Test
    fun `parses a complete row`() {
        val cell = OpenCellIdCsv.parseLine(validLine, importedAt)

        requireNotNull(cell)
        assertEquals(262, cell.mcc)
        assertEquals(1, cell.mnc)
        assertEquals(12_345_678L, cell.cid)
        assertEquals(RadioAccessTechnology.LTE, cell.rat)
        assertEquals(52.520008, cell.lat)
        assertEquals(13.404954, cell.lon)
        assertEquals(1000, cell.rangeMeters)
        assertEquals(KnownCellSource.OPENCELLID, cell.source)
        assertEquals(importedAt, cell.updatedAt)
    }

    @Test
    fun `reads longitude before latitude, as the dump orders them`() {
        // Swapping these would place every German cell in the Indian Ocean.
        val cell = OpenCellIdCsv.parseLine(validLine, importedAt)

        requireNotNull(cell)
        assertTrue(cell.lat > cell.lon, "Berlin: latitude 52 is greater than longitude 13")
    }

    @Test
    fun `recognises the header line`() {
        val header = "radio,mcc,net,area,cell,unit,lon,lat,range,samples," +
            "changeable,created,updated,averageSignal"

        assertTrue(OpenCellIdCsv.isHeader(header))
        assertNull(OpenCellIdCsv.parseLine(header, importedAt))
    }

    @Test
    fun `skips a malformed row rather than failing the whole import`() {
        assertNull(OpenCellIdCsv.parseLine("", importedAt))
        assertNull(OpenCellIdCsv.parseLine("LTE,262,1", importedAt))
        assertNull(OpenCellIdCsv.parseLine("LTE,abc,1,4711,123,,13.4,52.5,1000", importedAt))
    }

    @Test
    fun `rejects the zero-zero placeholder and impossible coordinates`() {
        // 0/0 is the classic "unknown position" marker in scraped data.
        assertNull(OpenCellIdCsv.parseLine("LTE,262,1,4711,123,,0.0,0.0,1000", importedAt))
        assertNull(OpenCellIdCsv.parseLine("LTE,262,1,4711,123,,13.4,91.0,1000", importedAt))
        assertNull(OpenCellIdCsv.parseLine("LTE,262,1,4711,123,,181.0,52.5,1000", importedAt))
    }

    @Test
    fun `maps the radio column, unknown values included`() {
        fun radioOf(radio: String) =
            OpenCellIdCsv.parseLine("$radio,262,1,4711,123,,13.4,52.5,1000", importedAt)?.rat

        assertEquals(RadioAccessTechnology.GSM, radioOf("GSM"))
        assertEquals(RadioAccessTechnology.UMTS, radioOf("UMTS"))
        assertEquals(RadioAccessTechnology.LTE, radioOf("LTE"))
        assertEquals(RadioAccessTechnology.NR_SA, radioOf("NR"))
        assertEquals(RadioAccessTechnology.UNKNOWN, radioOf("CDMA"))
    }
}
