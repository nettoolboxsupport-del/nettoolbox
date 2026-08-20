package de.nettoolbox.core.datastore

import androidx.datastore.core.DataStore
import de.nettoolbox.core.datastore.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read and write access to [UserSettings].
 *
 * The read flow swallows [IOException] and falls back to defaults: a settings
 * file that cannot be read must not take down a measurement that is running.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
) {

    val settings: Flow<UserSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(UserSettings()) else throw throwable
        }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        dataStore.updateData(transform)
    }
}
