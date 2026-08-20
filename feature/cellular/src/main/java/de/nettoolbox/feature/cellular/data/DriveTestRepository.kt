package de.nettoolbox.feature.cellular.data

import android.os.Build
import de.nettoolbox.core.database.dao.CellSampleDao
import de.nettoolbox.core.database.dao.MeasurementSessionDao
import de.nettoolbox.core.database.entity.CellSampleEntity
import de.nettoolbox.core.database.entity.MeasurementSessionEntity
import de.nettoolbox.feature.cellular.domain.SessionAnalysis
import de.nettoolbox.feature.cellular.domain.SessionStatistics
import de.nettoolbox.feature.cellular.domain.export.ExportFormat
import de.nettoolbox.feature.cellular.domain.export.MeasurementExporter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveTestRepository @Inject constructor(
    private val sessionDao: MeasurementSessionDao,
    private val cellSampleDao: CellSampleDao,
) {

    fun sessions(): Flow<List<MeasurementSessionEntity>> = sessionDao.observeAll()

    suspend fun startSession(name: String): Long = sessionDao.insert(
        MeasurementSessionEntity(
            name = name,
            startedAt = System.currentTimeMillis(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        ),
    )

    suspend fun endSession(sessionId: Long) {
        val session = sessionDao.findById(sessionId) ?: return
        sessionDao.update(session.copy(endedAt = System.currentTimeMillis()))
    }

    suspend fun rename(sessionId: Long, name: String, note: String?) {
        val session = sessionDao.findById(sessionId) ?: return
        sessionDao.update(session.copy(name = name, note = note))
    }

    suspend fun delete(sessionId: Long) = sessionDao.deleteById(sessionId)

    /**
     * Batched write. One insert per sample would be 7200 transactions on a
     * two-hour drive at 1 Hz, and Room's write lock would show up as UI jank.
     */
    suspend fun appendBatch(samples: List<CellSampleEntity>) {
        if (samples.isEmpty()) return
        cellSampleDao.insertAll(samples)
    }

    suspend fun samplesOf(sessionId: Long): List<CellSampleEntity> =
        cellSampleDao.bySession(sessionId)

    suspend fun statisticsOf(sessionId: Long): SessionStatistics =
        SessionAnalysis.analyse(samplesOf(sessionId))

    suspend fun export(sessionId: Long, format: ExportFormat): String =
        MeasurementExporter.export(samplesOf(sessionId), format)
}
