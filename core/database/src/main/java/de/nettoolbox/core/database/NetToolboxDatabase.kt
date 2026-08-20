package de.nettoolbox.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import de.nettoolbox.core.database.dao.CellSampleDao
import de.nettoolbox.core.database.dao.DiscoveredHostDao
import de.nettoolbox.core.database.dao.KnownCellDao
import de.nettoolbox.core.database.dao.MeasurementSessionDao
import de.nettoolbox.core.database.dao.ToolRunDao
import de.nettoolbox.core.database.dao.WifiScanSampleDao
import de.nettoolbox.core.database.entity.CellSampleEntity
import de.nettoolbox.core.database.entity.DiscoveredHostEntity
import de.nettoolbox.core.database.entity.KnownCellEntity
import de.nettoolbox.core.database.entity.MeasurementSessionEntity
import de.nettoolbox.core.database.entity.NeighborSampleEntity
import de.nettoolbox.core.database.entity.ToolRunEntity
import de.nettoolbox.core.database.entity.WifiScanSampleEntity

/**
 * `exportSchema = true` from version 1 on: without the exported JSON there is
 * nothing for an AutoMigration to diff against and no way to test a migration.
 * Schemas land in `core/database/schemas` and belong in version control.
 */
@Database(
    entities = [
        MeasurementSessionEntity::class,
        CellSampleEntity::class,
        NeighborSampleEntity::class,
        KnownCellEntity::class,
        WifiScanSampleEntity::class,
        ToolRunEntity::class,
        DiscoveredHostEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(NetToolboxConverters::class)
abstract class NetToolboxDatabase : RoomDatabase() {

    abstract fun measurementSessionDao(): MeasurementSessionDao

    abstract fun cellSampleDao(): CellSampleDao

    abstract fun wifiScanSampleDao(): WifiScanSampleDao

    abstract fun knownCellDao(): KnownCellDao

    abstract fun toolRunDao(): ToolRunDao

    abstract fun discoveredHostDao(): DiscoveredHostDao

    companion object {
        const val DATABASE_NAME = "nettoolbox.db"
    }
}
