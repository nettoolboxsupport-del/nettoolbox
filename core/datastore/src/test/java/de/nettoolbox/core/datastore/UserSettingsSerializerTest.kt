package de.nettoolbox.core.datastore

import androidx.datastore.core.CorruptionException
import de.nettoolbox.core.datastore.model.PingMethod
import de.nettoolbox.core.datastore.model.ThemePreference
import de.nettoolbox.core.datastore.model.UserSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UserSettingsSerializerTest {

    @Test
    fun `a round trip preserves every field`() = runTest {
        val original = UserSettings(
            theme = ThemePreference.FIELD,
            dynamicColor = false,
            cellSampleIntervalSeconds = 5,
            pingMethod = PingMethod.ICMP_DATAGRAM,
            detectedPingMethod = PingMethod.SYSTEM_BINARY,
            maxParallelProbes = 32,
            openCellIdApiKey = "key-from-user",
            scannerDisclaimerAccepted = true,
        )

        val output = ByteArrayOutputStream()
        UserSettingsSerializer.writeTo(original, output)
        val restored = UserSettingsSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))

        assertEquals(original, restored)
    }

    @Test
    fun `an unknown field from a newer version is ignored instead of failing`() = runTest {
        val fromTheFuture = """{"theme":"DARK","somethingAddedLater":42}"""

        val restored = UserSettingsSerializer.readFrom(ByteArrayInputStream(fromTheFuture.toByteArray()))

        assertEquals(ThemePreference.DARK, restored.theme)
        // Everything the payload does not mention falls back to the default.
        assertEquals(64, restored.maxParallelProbes)
    }

    @Test
    fun `a corrupt file is reported as CorruptionException so DataStore can reset it`() {
        val garbage = ByteArrayInputStream("not json at all".toByteArray())

        assertThrows(CorruptionException::class.java) {
            runBlocking { UserSettingsSerializer.readFrom(garbage) }
        }
    }

    @Test
    fun `defaults are the documented ones`() {
        val defaults = UserSettingsSerializer.defaultValue

        assertEquals(ThemePreference.SYSTEM, defaults.theme)
        assertEquals(PingMethod.AUTO, defaults.pingMethod)
        assertEquals(2, defaults.cellSampleIntervalSeconds)
        assertEquals(64, defaults.maxParallelProbes)
        assertNull(defaults.openCellIdApiKey)
    }
}
