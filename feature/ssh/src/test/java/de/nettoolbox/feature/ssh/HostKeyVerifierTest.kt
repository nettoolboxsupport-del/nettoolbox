package de.nettoolbox.feature.ssh

import de.nettoolbox.feature.ssh.data.HostKeyVerifier
import de.nettoolbox.feature.ssh.data.KnownHosts
import de.nettoolbox.feature.ssh.data.KnownHostsStore
import de.nettoolbox.feature.ssh.domain.HostKeyVerdict
import de.nettoolbox.feature.ssh.domain.PresentedHostKey
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * In-memory stand-in for the DataStore.
 *
 * Hand-written rather than mocked: the contract is two methods, and a fake
 * that actually stores things catches ordering mistakes a mock would happily
 * accept.
 */
private class FakeKnownHostsDataStore : DataStore<KnownHosts> {
    private val state = MutableStateFlow(KnownHosts())
    override val data: Flow<KnownHosts> = state

    override suspend fun updateData(
        transform: suspend (t: KnownHosts) -> KnownHosts,
    ): KnownHosts {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class HostKeyVerifierTest {

    private fun verifier(): Pair<HostKeyVerifier, KnownHostsStore> {
        val store = KnownHostsStore(FakeKnownHostsDataStore())
        return HostKeyVerifier(store) to store
    }

    private fun key(
        host: String = "server.example",
        type: String = "ssh-ed25519",
        blob: String = "AAAAC3NzaC1lZDI1NTE5AAAAI",
    ) = PresentedHostKey(
        hostId = host,
        keyType = type,
        fingerprintSha256 = "SHA256:irrelevant-for-comparison",
        base64Key = blob,
    )

    @Test
    fun `first contact reports the host as unknown`() = runTest {
        val (verifier, _) = verifier()

        val verdict = verifier.verify(key())

        assertTrue(verdict is HostKeyVerdict.Unknown)
    }

    @Test
    fun `a trusted key is accepted on the next connection`() = runTest {
        val (verifier, _) = verifier()
        val presented = key()

        verifier.trust(HostKeyVerdict.Unknown(presented), nowMillis = 1_000)

        assertEquals(HostKeyVerdict.Trusted, verifier.verify(presented))
    }

    @Test
    fun `a different key for a known host is reported as changed, not unknown`() = runTest {
        val (verifier, _) = verifier()
        val original = key(blob = "ORIGINAL-KEY-BLOB")
        verifier.trust(HostKeyVerdict.Unknown(original), nowMillis = 1_000)

        val verdict = verifier.verify(key(blob = "ATTACKER-KEY-BLOB"))

        // The distinction is the whole point: "changed" means someone may be
        // impersonating the host, "unknown" merely means first contact.
        assertTrue(verdict is HostKeyVerdict.Changed)
    }

    @Test
    fun `a changed verdict carries the stored key so both can be shown`() = runTest {
        val (verifier, _) = verifier()
        val original = key(blob = "ORIGINAL-KEY-BLOB")
        verifier.trust(HostKeyVerdict.Unknown(original), nowMillis = 1_000)

        val verdict = verifier.verify(key(blob = "ATTACKER-KEY-BLOB")) as HostKeyVerdict.Changed

        assertEquals("ORIGINAL-KEY-BLOB", verdict.stored.base64Key)
        assertEquals("ATTACKER-KEY-BLOB", verdict.presented.base64Key)
    }

    @Test
    fun `trusting one key type says nothing about another type on the same host`() = runTest {
        val (verifier, _) = verifier()
        verifier.trust(
            HostKeyVerdict.Unknown(key(type = "ssh-ed25519", blob = "ED-KEY")),
            nowMillis = 1_000,
        )

        // A server offering several host key types is ordinary. Trusting its
        // ed25519 key must not imply trust in an RSA key from the same address,
        // or an attacker could switch types to dodge the comparison entirely.
        val verdict = verifier.verify(key(type = "ssh-rsa", blob = "RSA-KEY"))

        assertTrue(verdict is HostKeyVerdict.Unknown)
    }

    @Test
    fun `the same host on a different port is a separate trust decision`() = runTest {
        val (verifier, _) = verifier()
        verifier.trust(
            HostKeyVerdict.Unknown(key(host = "server.example", blob = "PORT-22-KEY")),
            nowMillis = 1_000,
        )

        // Two ports on one address can be two entirely different machines
        // behind a port forward.
        val verdict = verifier.verify(key(host = "[server.example]:2222", blob = "PORT-2222-KEY"))

        assertTrue(verdict is HostKeyVerdict.Unknown)
    }

    @Test
    fun `accepting a changed key replaces the stored one rather than adding a second`() = runTest {
        val (verifier, store) = verifier()
        verifier.trust(HostKeyVerdict.Unknown(key(blob = "OLD")), nowMillis = 1_000)

        val changed = verifier.verify(key(blob = "NEW")) as HostKeyVerdict.Changed
        verifier.trust(changed, nowMillis = 2_000)

        assertEquals(HostKeyVerdict.Trusted, verifier.verify(key(blob = "NEW")))
        assertEquals("NEW", store.find("server.example", "ssh-ed25519")?.base64Key)
    }

    @Test
    fun `forgetting a host returns it to first contact`() = runTest {
        val (verifier, store) = verifier()
        val presented = key()
        verifier.trust(HostKeyVerdict.Unknown(presented), nowMillis = 1_000)

        store.forget(presented.hostId)

        assertTrue(verifier.verify(presented) is HostKeyVerdict.Unknown)
    }

    @Test
    fun `comparison uses the key blob, not the fingerprint`() = runTest {
        val (verifier, _) = verifier()
        verifier.trust(
            HostKeyVerdict.Unknown(
                PresentedHostKey(
                    hostId = "server.example",
                    keyType = "ssh-ed25519",
                    fingerprintSha256 = "SHA256:matching-fingerprint",
                    base64Key = "REAL-KEY",
                ),
            ),
            nowMillis = 1_000,
        )

        // A forged entry whose fingerprint string matches but whose key does
        // not must still be rejected. Comparing on the human-readable digest
        // would make the check only as strong as that digest.
        val verdict = verifier.verify(
            PresentedHostKey(
                hostId = "server.example",
                keyType = "ssh-ed25519",
                fingerprintSha256 = "SHA256:matching-fingerprint",
                base64Key = "FORGED-KEY",
            ),
        )

        assertTrue(verdict is HostKeyVerdict.Changed)
    }

    @Test
    fun `the OpenSSH fingerprint format has no base64 padding`() {
        // ssh-keygen prints SHA256:<base64 without padding>. Leaving the '='
        // in place yields a string that looks right and never matches.
        val fingerprint = HostKeyVerifier.sha256Fingerprint(byteArrayOf(1, 2, 3))

        assertTrue(fingerprint.startsWith("SHA256:"))
        assertTrue(!fingerprint.contains("="))
    }
}
