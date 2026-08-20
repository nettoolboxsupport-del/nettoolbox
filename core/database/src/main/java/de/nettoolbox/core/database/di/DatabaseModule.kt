package de.nettoolbox.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.nettoolbox.core.database.NetToolboxDatabase
import de.nettoolbox.core.database.dao.CellSampleDao
import de.nettoolbox.core.database.dao.DiscoveredHostDao
import de.nettoolbox.core.database.dao.KnownCellDao
import de.nettoolbox.core.database.dao.MeasurementSessionDao
import de.nettoolbox.core.database.dao.ToolRunDao
import de.nettoolbox.core.database.dao.WifiScanSampleDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(
        @ApplicationContext context: Context,
    ): NetToolboxDatabase = Room.databaseBuilder(
        context,
        NetToolboxDatabase::class.java,
        NetToolboxDatabase.DATABASE_NAME,
    )
        // No fallbackToDestructiveMigration: measurement data is the product of
        // field work that cannot be repeated, so a missing migration must fail
        // loudly at development time rather than wipe a drive test on a user's
        // device.
        .build()

    @Provides
    fun providesMeasurementSessionDao(database: NetToolboxDatabase): MeasurementSessionDao =
        database.measurementSessionDao()

    @Provides
    fun providesCellSampleDao(database: NetToolboxDatabase): CellSampleDao =
        database.cellSampleDao()

    @Provides
    fun providesWifiScanSampleDao(database: NetToolboxDatabase): WifiScanSampleDao =
        database.wifiScanSampleDao()

    @Provides
    fun providesKnownCellDao(database: NetToolboxDatabase): KnownCellDao =
        database.knownCellDao()

    @Provides
    fun providesToolRunDao(database: NetToolboxDatabase): ToolRunDao =
        database.toolRunDao()

    @Provides
    fun providesDiscoveredHostDao(database: NetToolboxDatabase): DiscoveredHostDao =
        database.discoveredHostDao()
}
