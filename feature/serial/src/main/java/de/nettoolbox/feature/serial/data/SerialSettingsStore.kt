package de.nettoolbox.feature.serial.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.nettoolbox.core.common.di.ApplicationScope
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.feature.serial.domain.SerialSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Same pattern as the other typed DataStores in the project: JSON, forgiving on read. */
object SerialSettingsSerializer : Serializer<SerialSettings> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: SerialSettings = SerialSettings()

    override suspend fun readFrom(input: InputStream): SerialSettings =
        try {
            json.decodeFromString(SerialSettings.serializer(), input.readBytes().decodeToString())
        } catch (serialization: SerializationException) {
            throw CorruptionException("Serial settings could not be read", serialization)
        }

    override suspend fun writeTo(t: SerialSettings, output: OutputStream) {
        output.write(json.encodeToString(SerialSettings.serializer(), t).encodeToByteArray())
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SerialDataStoreModule {

    @Provides
    @Singleton
    fun providesSerialSettingsDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): DataStore<SerialSettings> = DataStoreFactory.create(
        serializer = SerialSettingsSerializer,
        scope = scope + dispatcher,
        produceFile = { File(context.filesDir, "datastore/serial_settings.json") },
    )
}

@Singleton
class SerialSettingsStore @Inject constructor(
    private val dataStore: DataStore<SerialSettings>,
) {
    val settings: Flow<SerialSettings> = dataStore.data

    suspend fun current(): SerialSettings = dataStore.data.first()

    suspend fun update(transform: (SerialSettings) -> SerialSettings) {
        dataStore.updateData(transform)
    }
}
