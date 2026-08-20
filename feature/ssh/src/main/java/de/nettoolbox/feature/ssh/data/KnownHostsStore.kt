package de.nettoolbox.feature.ssh.data

import androidx.datastore.core.DataStore
import de.nettoolbox.feature.ssh.domain.PresentedHostKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One trusted host key.
 *
 * @param hostId matches [de.nettoolbox.feature.ssh.domain.SshTarget.knownHostsId]
 * @param keyType the SSH key type name, e.g. `ssh-ed25519`
 * @param base64Key the key blob, base64 encoded - the actual thing compared
 * @param fingerprintSha256 kept alongside purely so a stored key can be shown
 *   to the user without recomputing it; never the basis of a comparison
 * @param addedAtMillis when the user made the trust decision
 */
@Serializable
data class KnownHost(
    val hostId: String,
    val keyType: String,
    val base64Key: String,
    val fingerprintSha256: String,
    val addedAtMillis: Long,
)

@Serializable
data class KnownHosts(
    val hosts: List<KnownHost> = emptyList(),
)

/**
 * Persistent store of host keys the user has chosen to trust.
 *
 * This is deliberately **not** in the shared Room database. Two reasons: the
 * set is small and append-mostly, so a relational store buys nothing; and
 * keeping the security-critical state in one small file with one serializer
 * makes it far easier to audit than a table among a dozen others. It also
 * avoids a schema migration on a database that has no reason to change.
 *
 * A host is identified by [KnownHost.hostId] **and** [KnownHost.keyType]:
 * OpenSSH servers routinely offer several key types for the same host, and
 * trusting an ed25519 key must not imply anything about an RSA key from the
 * same address.
 */
@Singleton
class KnownHostsStore @Inject constructor(
    private val dataStore: DataStore<KnownHosts>,
) {

    val knownHosts: Flow<List<KnownHost>> = dataStore.data.map { it.hosts }

    suspend fun find(hostId: String, keyType: String): KnownHost? =
        dataStore.data.first().hosts.firstOrNull {
            it.hostId == hostId && it.keyType == keyType
        }

    /**
     * Stores a trust decision, replacing any previous key of the same type
     * for the same host.
     *
     * Replacement is the correct behaviour here only because the caller has
     * already put a changed key in front of the user - see
     * [HostKeyVerifier][de.nettoolbox.feature.ssh.data.HostKeyVerifier].
     * Nothing in this class decides that a key may be replaced; it only
     * records that something else did.
     */
    suspend fun trust(key: PresentedHostKey, nowMillis: Long) {
        dataStore.updateData { current ->
            val remaining = current.hosts.filterNot {
                it.hostId == key.hostId && it.keyType == key.keyType
            }
            KnownHosts(
                hosts = remaining + KnownHost(
                    hostId = key.hostId,
                    keyType = key.keyType,
                    base64Key = key.base64Key,
                    fingerprintSha256 = key.fingerprintSha256,
                    addedAtMillis = nowMillis,
                ),
            )
        }
    }

    /** Removes every stored key for a host, whatever its type. */
    suspend fun forget(hostId: String) {
        dataStore.updateData { current ->
            KnownHosts(hosts = current.hosts.filterNot { it.hostId == hostId })
        }
    }

    suspend fun forget(hostId: String, keyType: String) {
        dataStore.updateData { current ->
            KnownHosts(
                hosts = current.hosts.filterNot {
                    it.hostId == hostId && it.keyType == keyType
                },
            )
        }
    }
}
