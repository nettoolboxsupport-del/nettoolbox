package de.nettoolbox.feature.ssh.data

import de.nettoolbox.feature.ssh.domain.HostKeyVerdict
import de.nettoolbox.feature.ssh.domain.PresentedHostKey
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject

/**
 * Decides whether a presented host key may be trusted.
 *
 * Split out from the connector and kept free of jsch types so the decision
 * can be unit-tested exhaustively. This is the one piece of the SSH feature
 * where a quiet mistake is a security hole rather than a bug, so it is
 * deliberately small, pure, and covered by tests.
 *
 * There is no "trust everything" mode and no flag that disables the check.
 * The spec's ban on claiming capabilities the platform cannot deliver has a
 * sibling here: an SSH client that silently accepts any key offers the
 * appearance of security without it.
 */
class HostKeyVerifier @Inject constructor(
    private val store: KnownHostsStore,
) {

    suspend fun verify(presented: PresentedHostKey): HostKeyVerdict {
        val stored = store.find(presented.hostId, presented.keyType)
            ?: return HostKeyVerdict.Unknown(presented)

        // Compared on the key blob, never on the fingerprint. A fingerprint is
        // a hash shown to humans; treating it as the identity would make the
        // check only as strong as that hash, and would break silently if the
        // fingerprint format ever changed.
        return if (constantTimeEquals(stored.base64Key, presented.base64Key)) {
            HostKeyVerdict.Trusted
        } else {
            HostKeyVerdict.Changed(
                presented = presented,
                stored = PresentedHostKey(
                    hostId = stored.hostId,
                    keyType = stored.keyType,
                    fingerprintSha256 = stored.fingerprintSha256,
                    base64Key = stored.base64Key,
                ),
            )
        }
    }

    /**
     * Records a trust decision the user has actually made.
     *
     * Takes the verdict rather than a bare key so that it cannot be called
     * with something that was never presented to the user: only [Unknown] and
     * [Changed] are decisions to be made, and [Trusted] needs no recording.
     */
    suspend fun trust(verdict: HostKeyVerdict, nowMillis: Long) {
        when (verdict) {
            is HostKeyVerdict.Unknown -> store.trust(verdict.presented, nowMillis)
            is HostKeyVerdict.Changed -> store.trust(verdict.presented, nowMillis)
            HostKeyVerdict.Trusted -> Unit
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        // Host keys are public, so a timing side channel here leaks nothing of
        // value. Used anyway because it costs nothing and keeps the habit
        // intact for comparisons where it does matter.
        val aBytes = a.toByteArray()
        val bBytes = b.toByteArray()
        if (aBytes.size != bBytes.size) return false
        var diff = 0
        for (i in aBytes.indices) {
            diff = diff or (aBytes[i].toInt() xor bBytes[i].toInt())
        }
        return diff == 0
    }

    companion object {

        /**
         * Formats a key blob the way OpenSSH does, so the user can compare it
         * with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the
         * server.
         *
         * OpenSSH prints `SHA256:` followed by the base64 of the digest with
         * padding removed. The padding removal is not cosmetic - leaving the
         * `=` in place produces a string that looks right but never matches
         * what the server prints.
         */
        fun sha256Fingerprint(keyBlob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
            // java.util.Base64, not android.util.Base64: it exists from API 26
            // and this module needs 28, and it keeps this class free of
            // Android types so it runs in a plain JVM unit test rather than
            // needing an instrumented one.
            val base64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
            return "SHA256:$base64"
        }
    }
}
