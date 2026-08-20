package de.nettoolbox.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import de.nettoolbox.core.datastore.model.UserSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Typed DataStore backed by kotlinx.serialization instead of protobuf.
 *
 * Same type safety, no protoc toolchain in the build, and the JSON payload is
 * readable when a user sends in a settings file with a bug report.
 * `ignoreUnknownKeys` makes a downgrade survivable; `encodeDefaults` keeps the
 * stored file explicit rather than relying on the current defaults.
 */
object UserSettingsSerializer : Serializer<UserSettings> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    override val defaultValue: UserSettings = UserSettings()

    override suspend fun readFrom(input: InputStream): UserSettings =
        try {
            json.decodeFromString(
                UserSettings.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (serialization: SerializationException) {
            // DataStore replaces the file with defaultValue when this is thrown,
            // which is the right outcome: settings are reproducible, and refusing
            // to start over a corrupt preferences file would be worse.
            throw CorruptionException("Settings could not be read", serialization)
        }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) {
        output.write(
            json.encodeToString(UserSettings.serializer(), t).encodeToByteArray(),
        )
    }
}
