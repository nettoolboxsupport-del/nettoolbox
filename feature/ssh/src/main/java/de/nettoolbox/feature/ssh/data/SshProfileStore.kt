package de.nettoolbox.feature.ssh.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY,
}

@Serializable
data class SshProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val privateKeyPem: String? = null,
    val lastUsedMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class SshProfiles(
    val profiles: List<SshProfile> = emptyList(),
)

/**
 * Persists connection profiles so field technicians can quickly reconnect
 * to core switches, routers, and servers without re-entering parameters.
 */
@Singleton
class SshProfileStore @Inject constructor(
    private val dataStore: DataStore<SshProfiles>,
) {

    val profiles: Flow<List<SshProfile>> = dataStore.data.map {
        it.profiles.sortedByDescending { p -> p.lastUsedMillis }
    }

    suspend fun saveProfile(profile: SshProfile) {
        dataStore.updateData { current ->
            val filtered = current.profiles.filterNot { it.id == profile.id }
            current.copy(profiles = filtered + profile)
        }
    }

    suspend fun deleteProfile(profileId: String) {
        dataStore.updateData { current ->
            current.copy(profiles = current.profiles.filterNot { it.id == profileId })
        }
    }

    suspend fun updateLastUsed(profileId: String, nowMillis: Long = System.currentTimeMillis()) {
        dataStore.updateData { current ->
            val updated = current.profiles.map {
                if (it.id == profileId) it.copy(lastUsedMillis = nowMillis) else it
            }
            current.copy(profiles = updated)
        }
    }
}
