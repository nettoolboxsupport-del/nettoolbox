package de.nettoolbox.feature.map.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.common.result.Outcome
import de.nettoolbox.core.common.result.suspendRunCatching
import de.nettoolbox.core.database.dao.KnownCellDao
import de.nettoolbox.core.database.entity.KnownCellEntity
import de.nettoolbox.feature.map.domain.OpenCellIdCsv
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(val imported: Int, val skipped: Int)

/**
 * Imports an OpenCelliD CSV dump into Room.
 *
 * Streamed and batched, because a country-sized dump is millions of rows: reading
 * it into a list first would run the app out of memory, and one insert per row
 * would take hours.
 *
 * The file is picked by the user through the Storage Access Framework, so the app
 * needs no storage permission and reads nothing it was not handed.
 */
@Singleton
class KnownCellImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val knownCellDao: KnownCellDao,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun importCsv(
        uri: Uri,
        onProgress: (Int) -> Unit = {},
    ): Outcome<ImportResult> = withContext(dispatcher) {
        suspendRunCatching {
            var imported = 0
            var skipped = 0
            val importedAt = System.currentTimeMillis()
            val batch = ArrayList<KnownCellEntity>(BATCH_SIZE)

            val stream = context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Could not open the selected file")

            stream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    currentCoroutineContext().ensureActive()

                    val cell = OpenCellIdCsv.parseLine(line, importedAt)
                    if (cell == null) {
                        // A header or a broken row is skipped, not fatal: one bad
                        // line must not cost the user a multi-megabyte import.
                        skipped++
                        return@forEach
                    }

                    batch += cell
                    if (batch.size >= BATCH_SIZE) {
                        knownCellDao.upsertAll(batch.toList())
                        imported += batch.size
                        batch.clear()
                        onProgress(imported)
                    }
                }
            }

            if (batch.isNotEmpty()) {
                knownCellDao.upsertAll(batch.toList())
                imported += batch.size
                onProgress(imported)
            }

            ImportResult(imported = imported, skipped = skipped)
        }
    }

    suspend fun count(): Int = withContext(dispatcher) { knownCellDao.count() }

    suspend fun clearImported(): Outcome<Int> = withContext(dispatcher) {
        suspendRunCatching {
            knownCellDao.deleteBySource(
                de.nettoolbox.core.database.entity.KnownCellSource.OPENCELLID.name,
            )
        }
    }

    private companion object {
        /**
         * Large enough that the transaction overhead disappears, small enough
         * that a cancelled import does not lose much work.
         */
        const val BATCH_SIZE = 2_000
    }
}
