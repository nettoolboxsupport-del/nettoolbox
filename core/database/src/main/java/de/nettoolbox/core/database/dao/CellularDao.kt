package de.nettoolbox.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import de.nettoolbox.core.database.entity.CellSampleEntity
import de.nettoolbox.core.database.entity.MeasurementSessionEntity
import de.nettoolbox.core.database.entity.NeighborSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementSessionDao {

    @Insert
    suspend fun insert(session: MeasurementSessionEntity): Long

    @Update
    suspend fun update(session: MeasurementSessionEntity)

    @Query("SELECT * FROM measurement_session ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<MeasurementSessionEntity>>

    @Query("SELECT * FROM measurement_session WHERE id = :id")
    suspend fun findById(id: Long): MeasurementSessionEntity?

    @Query("DELETE FROM measurement_session WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM cell_sample WHERE sessionId = :sessionId")
    suspend fun cellSampleCount(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM wifi_scan_sample WHERE sessionId = :sessionId")
    suspend fun wifiSampleCount(sessionId: Long): Int
}

@Dao
interface CellSampleDao {

    /**
     * Batched insert. The spec forbids one insert per sample - at 1 Hz over a
     * two-hour drive that is 7200 transactions, and Room's write lock would show
     * up as UI jank.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(samples: List<CellSampleEntity>): List<Long>

    @Insert
    suspend fun insertNeighbors(neighbors: List<NeighborSampleEntity>)

    /**
     * Writes a sample together with its neighbours in one transaction, so a
     * cancelled logging run can never leave neighbours without their parent.
     */
    @Transaction
    suspend fun insertSampleWithNeighbors(
        sample: CellSampleEntity,
        neighbors: List<NeighborSampleEntity>,
    ) {
        val id = insertAll(listOf(sample)).first()
        if (neighbors.isNotEmpty()) {
            insertNeighbors(neighbors.map { it.copy(cellSampleId = id) })
        }
    }

    @Query("SELECT * FROM cell_sample WHERE sessionId = :sessionId ORDER BY ts ASC")
    fun observeBySession(sessionId: Long): Flow<List<CellSampleEntity>>

    /** One-shot read for export and statistics, where a Flow would be in the way. */
    @Query("SELECT * FROM cell_sample WHERE sessionId = :sessionId ORDER BY ts ASC")
    suspend fun bySession(sessionId: Long): List<CellSampleEntity>

    @Query(
        "SELECT * FROM cell_sample WHERE sessionId = :sessionId " +
            "ORDER BY ts DESC LIMIT :limit",
    )
    suspend fun latest(sessionId: Long, limit: Int): List<CellSampleEntity>

    @Query("SELECT * FROM neighbor_sample WHERE cellSampleId = :cellSampleId")
    suspend fun neighborsOf(cellSampleId: Long): List<NeighborSampleEntity>

    /** Median is not available in SQLite; the UI computes it from this. */
    @Query(
        "SELECT rsrp FROM cell_sample WHERE sessionId = :sessionId AND rsrp IS NOT NULL " +
            "ORDER BY rsrp ASC",
    )
    suspend fun sortedRsrp(sessionId: Long): List<Int>
}
