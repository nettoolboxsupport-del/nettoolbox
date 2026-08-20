package de.nettoolbox.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.nettoolbox.core.common.radio.RadioAccessTechnology

/**
 * Where a cell site is believed to be. Imported from OpenCelliD or derived from
 * the app's own measurements - never from a proprietary backend.
 *
 * [source] is mandatory because the licence follows the data: OpenCelliD is
 * CC-BY-SA and has to be attributed wherever it is shown.
 */
@Entity(
    tableName = "known_cell",
    indices = [
        Index(value = ["mcc", "mnc", "cid"], unique = true),
        Index(value = ["lat", "lon"]),
    ],
)
data class KnownCellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mcc: Int,
    val mnc: Int,
    val rat: RadioAccessTechnology,
    val cid: Long,
    val lat: Double,
    val lon: Double,
    /** Sector bearing in degrees, when the source provides one. */
    val azimuth: Int? = null,
    /** Estimated coverage radius in metres, as reported by the source. */
    val rangeMeters: Int? = null,
    val source: KnownCellSource,
    val updatedAt: Long,
)

enum class KnownCellSource {
    OPENCELLID,
    LOCAL_IMPORT,
    OWN_MEASUREMENT,
}
