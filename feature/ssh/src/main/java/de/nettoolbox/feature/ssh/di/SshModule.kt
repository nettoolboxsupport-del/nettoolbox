package de.nettoolbox.feature.ssh.di

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.nettoolbox.feature.ssh.data.KnownHosts
import de.nettoolbox.feature.ssh.data.SshProfiles
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Singleton

/**
 * Serializer for the known-hosts file.
 *
 * A corrupt file throws rather than silently resetting to an empty set.
 * Quietly returning "no hosts known" would turn a damaged trust store into a
 * fresh accept-on-first-use prompt for every server - which is precisely the
 * moment an attacker would want the user to see one.
 */
object KnownHostsSerializer : Serializer<KnownHosts> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: KnownHosts = KnownHosts()

    override suspend fun readFrom(input: InputStream): KnownHosts =
        try {
            json.decodeFromString(
                KnownHosts.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: SerializationException) {
            throw CorruptionException("Known-hosts file could not be read", e)
        }

    override suspend fun writeTo(t: KnownHosts, output: OutputStream) {
        output.write(json.encodeToString(KnownHosts.serializer(), t).encodeToByteArray())
    }
}

object SshProfilesSerializer : Serializer<SshProfiles> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: SshProfiles = SshProfiles()

    override suspend fun readFrom(input: InputStream): SshProfiles =
        try {
            json.decodeFromString(
                SshProfiles.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (e: SerializationException) {
            throw CorruptionException("SSH profiles file could not be read", e)
        }

    override suspend fun writeTo(t: SshProfiles, output: OutputStream) {
        output.write(json.encodeToString(SshProfiles.serializer(), t).encodeToByteArray())
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SshModule {

    @Provides
    @Singleton
    fun provideKnownHostsStore(
        @ApplicationContext context: Context,
    ): DataStore<KnownHosts> = DataStoreFactory.create(
        serializer = KnownHostsSerializer,
        produceFile = { context.dataStoreFile(KNOWN_HOSTS_FILE) },
    )

    @Provides
    @Singleton
    fun provideSshProfilesStore(
        @ApplicationContext context: Context,
    ): DataStore<SshProfiles> = DataStoreFactory.create(
        serializer = SshProfilesSerializer,
        produceFile = { context.dataStoreFile(SSH_PROFILES_FILE) },
    )

    private const val KNOWN_HOSTS_FILE = "ssh_known_hosts.json"
    private const val SSH_PROFILES_FILE = "ssh_profiles.json"
}

