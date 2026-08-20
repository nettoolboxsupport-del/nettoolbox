package de.nettoolbox.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One BSSID as seen in one scan.
 *
 * `capabilities` is kept as the raw platform string rather than a parsed security
 * enum: the format grows with every Wi-Fi generation, and keeping the original
 * means an old recording can still be re-interpreted by a newer parser.
 */
@Entity(
    tableName = "wifi_scan_sample",
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
        Index("bssid"),
    ],
)
data class WifiScanSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val ts: Long,

    val bssid: String,
    val ssid: String?,
    val rssi: Int,
    val frequencyMhz: Int,
    /** ScanResult.channelWidth constant. */
    val channelWidth: Int? = null,
    val centerFreq0: Int? = null,
    val centerFreq1: Int? = null,
    val capabilities: String? = null,
    /** ScanResult.getWifiStandard() constant. */
    val standard: Int? = null,

    val lat: Double? = null,
    val lon: Double? = null,
)
