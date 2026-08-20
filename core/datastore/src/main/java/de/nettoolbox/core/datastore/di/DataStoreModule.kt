package de.nettoolbox.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.nettoolbox.core.common.di.ApplicationScope
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.datastore.UserSettingsSerializer
import de.nettoolbox.core.datastore.model.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import javax.inject.Singleton
import java.io.File

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    private const val SETTINGS_FILE = "user_settings.json"

    @Provides
    @Singleton
    fun providesUserSettingsDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): DataStore<UserSettings> = DataStoreFactory.create(
        serializer = UserSettingsSerializer,
        scope = scope + dispatcher,
        produceFile = { File(context.filesDir, "datastore/$SETTINGS_FILE") },
    )
}
