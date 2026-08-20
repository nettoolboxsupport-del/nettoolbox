package de.nettoolbox.feature.map.domain

import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.core.database.entity.KnownCellEntity
import de.nettoolbox.core.database.entity.KnownCellSource

/**
 * Parser for an OpenCelliD CSV dump.
 *
 * Column order per the published export format:
 * `radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal`
 *
 * A dump is millions of rows, so the parser is deliberately allocation-light and
 * skips a malformed row instead of aborting the import - one bad line in a
 * multi-megabyte file must not cost the user the whole import.
 *
 * The data is CC-BY-SA. Attribution is shown on the map and in the about screen.
 */
object OpenCellIdCsv {

    private const val COLUMN_RADIO = 0
    private const val COLUMN_MCC = 1
    private const val COLUMN_NET = 2
    private const val COLUMN_AREA = 3
    private const val COLUMN_CELL = 4
    private const val COLUMN_LON = 6
    private const val COLUMN_LAT = 7
    private const val COLUMN_RANGE = 8
    private const val MINIMUM_COLUMNS = 9

    /** True for the header line of an export, which must not become a cell. */
    fun isHeader(line: String): Boolean =
        line.startsWith("radio,", ignoreCase = true) || line.startsWith("\"radio\"", ignoreCase = true)

    /**
     * @return null when the row is malformed, incomplete or carries no usable
     *   position - never a partially filled entity.
     */
    fun parseLine(line: String, importedAt: Long): KnownCellEntity? {
        if (line.isBlank() || isHeader(line)) return null

        val columns = line.split(',')
        if (columns.size < MINIMUM_COLUMNS) return null

        val mcc = columns[COLUMN_MCC].trim().toIntOrNull() ?: return null
        val mnc = columns[COLUMN_NET].trim().toIntOrNull() ?: return null
        val cell = columns[COLUMN_CELL].trim().toLongOrNull() ?: return null
        val lon = columns[COLUMN_LON].trim().toDoubleOrNull() ?: return null
        val lat = columns[COLUMN_LAT].trim().toDoubleOrNull() ?: return null

        // 0/0 is the classic "no position" placeholder in scraped data sets and
        // would put a cell in the Gulf of Guinea.
        if (lat == 0.0 && lon == 0.0) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        return KnownCellEntity(
            mcc = mcc,
            mnc = mnc,
            rat = radioOf(columns[COLUMN_RADIO]),
            cid = cell,
            lat = lat,
            lon = lon,
            // The dump has no azimuth; only the coverage radius.
            azimuth = null,
            rangeMeters = columns.getOrNull(COLUMN_RANGE)?.trim()?.toIntOrNull(),
            source = KnownCellSource.OPENCELLID,
            updatedAt = importedAt,
        )
    }

    private fun radioOf(raw: String): RadioAccessTechnology =
        when (raw.trim().trim('"').uppercase()) {
            "GSM" -> RadioAccessTechnology.GSM
            "UMTS" -> RadioAccessTechnology.UMTS
            "LTE" -> RadioAccessTechnology.LTE
            "NR" -> RadioAccessTechnology.NR_SA
            else -> RadioAccessTechnology.UNKNOWN
        }
}
