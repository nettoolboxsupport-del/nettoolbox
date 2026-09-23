package de.nettoolbox.feature.fileserver.domain

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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the server configuration.
 *
 * Its own DataStore file rather than a corner of the shared user settings: this
 * one holds credentials, and keeping it separate means it can be wiped on its
 * own and never travels with a settings export.
 */
object FileServerConfigSerializer : Serializer<FileServerConfig> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override val defaultValue: FileServerConfig = FileServerConfig()

    override suspend fun readFrom(input: InputStream): FileServerConfig =
        try {
            json.decodeFromString(
                FileServerConfig.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (serialization: SerializationException) {
            throw CorruptionException("Server configuration could not be read", serialization)
        }

    override suspend fun writeTo(t: FileServerConfig, output: OutputStream) {
        output.write(json.encodeToString(FileServerConfig.serializer(), t).encodeToByteArray())
    }
}

@Module
@InstallIn(SingletonComponent::class)
object FileServerDataStoreModule {

    private const val CONFIG_FILE = "file_server.json"

    @Provides
    @Singleton
    fun providesFileServerDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): DataStore<FileServerConfig> = DataStoreFactory.create(
        serializer = FileServerConfigSerializer,
        scope = scope + dispatcher,
        produceFile = { File(context.filesDir, "datastore/$CONFIG_FILE") },
    )
}

@Singleton
class FileServerConfigRepository @Inject constructor(
    private val dataStore: DataStore<FileServerConfig>,
) {

    val config: Flow<FileServerConfig> = dataStore.data

    suspend fun current(): FileServerConfig = dataStore.data.first()

    suspend fun update(transform: (FileServerConfig) -> FileServerConfig) {
        dataStore.updateData(transform)
    }

    suspend fun setTftp(transform: (TftpConfig) -> TftpConfig) =
        update { it.copy(tftp = transform(it.tftp)) }

    suspend fun setFtp(transform: (FtpConfig) -> FtpConfig) =
        update { it.copy(ftp = transform(it.ftp)) }

    suspend fun setSsh(transform: (SshConfig) -> SshConfig) =
        update { it.copy(ssh = transform(it.ssh)) }

    suspend fun addAccount(
        username: String,
        password: String,
        homeSubdirectory: String,
        canWrite: Boolean,
    ) = update { config ->
        config.copy(
            accounts = config.accounts + ServerAccount(
                id = UUID.randomUUID().toString(),
                username = username.trim(),
                password = password,
                homeSubdirectory = homeSubdirectory.trim('/'),
                canWrite = canWrite,
            ),
        )
    }

    suspend fun replaceAccount(account: ServerAccount) = update { config ->
        config.copy(accounts = config.accounts.map { if (it.id == account.id) account else it })
    }

    suspend fun removeAccount(id: String) = update { config ->
        config.copy(accounts = config.accounts.filterNot { it.id == id })
    }

    suspend fun addAuthorizedKey(openSshLine: String) = update { config ->
        val cleaned = openSshLine.trim()
        // Duplicates are silently ignored rather than rejected with an error:
        // pasting the same key twice is a slip, not a decision the user needs
        // to be told about.
        if (cleaned.isEmpty() || cleaned in config.ssh.authorizedKeys) {
            config
        } else {
            config.copy(ssh = config.ssh.copy(authorizedKeys = config.ssh.authorizedKeys + cleaned))
        }
    }

    suspend fun removeAuthorizedKey(openSshLine: String) = update { config ->
        config.copy(
            ssh = config.ssh.copy(
                authorizedKeys = config.ssh.authorizedKeys.filterNot { it == openSshLine },
            ),
        )
    }
}
