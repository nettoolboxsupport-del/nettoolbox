package de.nettoolbox.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.nettoolbox.core.common.radio.RadioAccessTechnology

/**
 * A named recording run. Everything measured belongs to exactly one session, so
 * a drive test can be exported, pruned or deleted as a unit.
 */
@Entity(tableName = "measurement_session")
data class MeasurementSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val note: String? = null,
    val deviceModel: String,
    val osVersion: String,
)

/**
 * One serving-cell sample with the position it was taken at.
 *
 * Nearly every radio field is nullable: which metrics a modem reports depends on
 * the RAT, the vendor and the Android version. Storing a fabricated 0 instead of
 * null would turn "not reported" into "measured as zero" in every later average.
 */
@Entity(
    tableName = "cell_sample",
    foreignKeys = [
        ForeignKey(
            entity = MeasurementSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("ts"),
        Index(value = ["mcc", "mnc", "cid"]),
    ],
)
data class CellSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val ts: Long,

    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Float? = null,
    val speed: Float? = null,

    val subId: Int,
    val rat: RadioAccessTechnology,
    val mcc: Int? = null,
    val mnc: Int? = null,
    val operatorName: String? = null,

    val cid: Long? = null,
    val pci: Int? = null,
    val tac: Int? = null,
    val arfcn: Int? = null,
    val band: Int? = null,
    val bandwidthKhz: Int? = null,

    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val rssi: Int? = null,
    val cqi: Int? = null,
    val timingAdvance: Int? = null,

    val isServing: Boolean = true,
    val isRoaming: Boolean = false,
)

/**
 * A neighbour cell as seen at the moment of the parent [CellSampleEntity].
 */
@Entity(
    tableName = "neighbor_sample",
    foreignKeys = [
        ForeignKey(
            entity = CellSampleEntity::class,
            parentColumns = ["id"],
            childColumns = ["cellSampleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("cellSampleId")],
)
data class NeighborSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val cellSampleId: Long,
    val rat: RadioAccessTechnology,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
)
