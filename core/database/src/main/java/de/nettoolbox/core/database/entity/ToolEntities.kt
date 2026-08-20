package de.nettoolbox.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * History entry for any tool run, so every tool gets a "repeat this" tab for free.
 *
 * Parameters and results are JSON: each tool has a different shape, and one table
 * per tool would mean a migration every time a tool gains an option.
 */
@Entity(
    tableName = "tool_run",
    indices = [
        Index("toolType"),
        Index("startedAt"),
    ],
)
data class ToolRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val toolType: ToolType,
    val target: String,
    val paramsJson: String,
    val startedAt: Long,
    val durationMs: Long? = null,
    val resultJson: String? = null,
    val success: Boolean = false,
)

enum class ToolType {
    PING,
    TRACEROUTE,
    DNS,
    IP_SCAN,
    PORT_SCAN,
    IPERF3,
    SUBNET,
    WOL,
    WHOIS,
    HTTP_TLS,
    SPEEDTEST,
}

/**
 * A host found by an IP scan.
 *
 * `mac` stays nullable and is usually null: /proc/net/arp is blocked by SELinux
 * from Android 10 on, so a MAC is only known for Wi-Fi BSSIDs or manual entry.
 */
@Entity(
    tableName = "discovered_host",
    foreignKeys = [
        ForeignKey(
            entity = ToolRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["scanId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("scanId")],
)
data class DiscoveredHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val scanId: Long,
    val ip: String,
    val hostname: String? = null,
    val mac: String? = null,
    val vendor: String? = null,
    val openPortsJson: String? = null,
    val rttMs: Double? = null,
)
