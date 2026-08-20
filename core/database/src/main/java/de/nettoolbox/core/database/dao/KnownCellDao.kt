package de.nettoolbox.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.nettoolbox.core.database.entity.KnownCellEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownCellDao {

    /**
     * REPLACE on the (mcc, mnc, cid) unique index: re-importing a newer
     * OpenCelliD dump should update a site, not duplicate it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cells: List<KnownCellEntity>)

    @Query("SELECT * FROM known_cell WHERE mcc = :mcc AND mnc = :mnc AND cid = :cid")
    suspend fun find(mcc: Int, mnc: Int, cid: Long): KnownCellEntity?

    /**
     * Bounding-box query for the map viewport. Cheap because of the (lat, lon)
     * index, and enough for a screen-sized area - no spatial extension needed.
     */
    @Query(
        "SELECT * FROM known_cell WHERE lat BETWEEN :minLat AND :maxLat " +
            "AND lon BETWEEN :minLon AND :maxLon LIMIT :limit",
    )
    fun observeInBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int,
    ): Flow<List<KnownCellEntity>>

    /** One-shot variant for building a map layer, where a Flow would be in the way. */
    @Query(
        "SELECT * FROM known_cell WHERE lat BETWEEN :minLat AND :maxLat " +
            "AND lon BETWEEN :minLon AND :maxLon LIMIT :limit",
    )
    suspend fun inBounds(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        limit: Int,
    ): List<KnownCellEntity>

    @Query("SELECT COUNT(*) FROM known_cell")
    suspend fun count(): Int

    @Query("DELETE FROM known_cell WHERE source = :source")
    suspend fun deleteBySource(source: String): Int
}
