package de.nettoolbox.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.nettoolbox.core.database.entity.DiscoveredHostEntity
import de.nettoolbox.core.database.entity.ToolRunEntity
import de.nettoolbox.core.database.entity.ToolType
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolRunDao {

    @Insert
    suspend fun insert(run: ToolRunEntity): Long

    @Update
    suspend fun update(run: ToolRunEntity)

    @Query("SELECT * FROM tool_run WHERE toolType = :toolType ORDER BY startedAt DESC LIMIT :limit")
    fun observeHistory(toolType: ToolType, limit: Int): Flow<List<ToolRunEntity>>

    @Query("SELECT * FROM tool_run WHERE id = :id")
    suspend fun findById(id: Long): ToolRunEntity?

    @Query("DELETE FROM tool_run WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tool_run WHERE toolType = :toolType")
    suspend fun clearHistory(toolType: ToolType)
}

@Dao
interface DiscoveredHostDao {

    @Insert
    suspend fun insertAll(hosts: List<DiscoveredHostEntity>)

    @Query("SELECT * FROM discovered_host WHERE scanId = :scanId ORDER BY ip ASC")
    fun observeByScan(scanId: Long): Flow<List<DiscoveredHostEntity>>
}
