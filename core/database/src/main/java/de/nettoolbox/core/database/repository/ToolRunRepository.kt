package de.nettoolbox.core.database.repository

import de.nettoolbox.core.database.dao.ToolRunDao
import de.nettoolbox.core.database.entity.ToolRunEntity
import de.nettoolbox.core.database.entity.ToolType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * History for every tool, shared across feature modules.
 *
 * Lives in `:core:database` rather than in `:feature:tools`, where it
 * originated: `:feature:iperf` needs the exact same run-history behaviour,
 * and feature modules are siblings that must not depend on one another - the
 * shared piece belongs at the layer both already depend on.
 *
 * A run is written twice: once when it starts, so a run that is cancelled or
 * crashes still leaves a trace, and once when it finishes with the result.
 * That is what makes "repeat this run" work even for runs that never
 * completed.
 */
@Singleton
class ToolRunRepository @Inject constructor(
    private val dao: ToolRunDao,
) {

    suspend fun startRun(
        toolType: ToolType,
        target: String,
        paramsJson: String,
    ): Long = dao.insert(
        ToolRunEntity(
            toolType = toolType,
            target = target,
            paramsJson = paramsJson,
            startedAt = System.currentTimeMillis(),
        ),
    )

    suspend fun finishRun(
        runId: Long,
        resultJson: String?,
        success: Boolean,
    ) {
        val existing = dao.findById(runId) ?: return
        dao.update(
            existing.copy(
                durationMs = System.currentTimeMillis() - existing.startedAt,
                resultJson = resultJson,
                success = success,
            ),
        )
    }

    fun history(toolType: ToolType, limit: Int = DEFAULT_HISTORY_LIMIT): Flow<List<ToolRunEntity>> =
        dao.observeHistory(toolType, limit)

    /** Used to restore a tool's parameters when a run is repeated. */
    suspend fun find(runId: Long): ToolRunEntity? = dao.findById(runId)

    suspend fun delete(runId: Long) = dao.deleteById(runId)

    suspend fun clear(toolType: ToolType) = dao.clearHistory(toolType)

    private companion object {
        const val DEFAULT_HISTORY_LIMIT = 50
    }
}
