package de.nettoolbox.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.nettoolbox.core.database.entity.WifiScanSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WifiScanSampleDao {

    @Insert
    suspend fun insertAll(samples: List<WifiScanSampleEntity>)

    @Query("SELECT * FROM wifi_scan_sample WHERE sessionId = :sessionId ORDER BY ts DESC")
    fun observeBySession(sessionId: Long): Flow<List<WifiScanSampleEntity>>

    /** Signal history of one BSSID - drives the antenna alignment chart. */
    @Query(
        "SELECT * FROM wifi_scan_sample WHERE bssid = :bssid AND ts >= :since " +
            "ORDER BY ts ASC",
    )
    fun observeHistory(bssid: String, since: Long): Flow<List<WifiScanSampleEntity>>

    @Query("DELETE FROM wifi_scan_sample WHERE ts < :olderThan")
    suspend fun prune(olderThan: Long): Int
}
