package de.nettoolbox.feature.ssh.data

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import de.nettoolbox.feature.ssh.domain.HostKeyVerdict
import de.nettoolbox.feature.ssh.domain.PresentedHostKey
import kotlinx.coroutines.runBlocking
import java.util.Base64

/**
 * Bridges jsch's host key check onto [HostKeyVerifier].
 *
 * jsch calls [check] from inside its own connect logic and expects a verdict
 * synchronously. That shapes this class in two ways worth stating plainly:
 *
 * **The verdict is captured, not acted upon.** [check] never prompts and never
 * stores anything. It records what it saw in [lastVerdict] and returns
 * `NOT_INCLUDED` or `CHANGED`, which makes jsch abort the connection. The
 * decision is then put to the user, and only a fresh connection attempt -
 * made after the key was explicitly trusted - can succeed. A connection is
 * therefore never established against an unverified key, not even briefly.
 *
 * **[add] is a no-op.** jsch would call it to persist a key after a `UserInfo`
 * said yes. Writing to the trust store from inside a callback that jsch drives
 * is exactly the kind of implicit trust this class exists to prevent; storing
 * happens only through [HostKeyVerifier.trust], from code the user's decision
 * reached directly.
 */
class NetToolboxHostKeyRepository(
    private val verifier: HostKeyVerifier,
    private val hostId: String,
) : HostKeyRepository {

    /**
     * What the most recent [check] concluded, or null if it was never called.
     *
     * Read after a failed connect to find out why.
     */
    @Volatile
    var lastVerdict: HostKeyVerdict? = null
        private set

    /**
     * @param host the host as jsch sees it; ignored in favour of [hostId],
     *   which carries this app's port-qualified form
     * @param key the raw key blob
     */
    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) {
            lastVerdict = null
            return HostKeyRepository.NOT_INCLUDED
        }

        val presented = PresentedHostKey(
            hostId = hostId,
            keyType = keyTypeOf(key),
            fingerprintSha256 = HostKeyVerifier.sha256Fingerprint(key),
            base64Key = Base64.getEncoder().encodeToString(key),
        )

        // runBlocking is normally banned in this project, and it is used here
        // for one specific reason: jsch's interface is synchronous and is
        // already being called on a background thread that this app owns and
        // dedicates to the connection. The alternative - making the check
        // asynchronous - is not available, and returning "OK" while a
        // suspending check runs elsewhere would defeat the entire purpose.
        val verdict = runBlocking { verifier.verify(presented) }
        lastVerdict = verdict

        return when (verdict) {
            HostKeyVerdict.Trusted -> HostKeyRepository.OK
            is HostKeyVerdict.Unknown -> HostKeyRepository.NOT_INCLUDED
            is HostKeyVerdict.Changed -> HostKeyRepository.CHANGED
        }
    }

    /** Intentionally does nothing - see the class comment. */
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "nettoolbox-known-hosts"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    private companion object {
        fun keyTypeOf(key: ByteArray): String = sshKeyTypeOf(key)
    }
}

/**
 * Reads the key type out of an SSH public key blob.
 *
 * Every SSH public key blob starts with an SSH string: a four-byte big-endian
 * length followed by that many bytes of the algorithm name (RFC 4253,
 * section 6.6). Taking the type from the blob rather than from anything the
 * transport claims means the stored type always describes the key actually
 * being stored.
 *
 * Internal rather than private because this parses bytes that arrived over
 * the network, and that deserves its own tests.
 */
internal fun sshKeyTypeOf(key: ByteArray): String {
    if (key.size < 4) return UNKNOWN_KEY_TYPE
    val length = ((key[0].toInt() and 0xFF) shl 24) or
        ((key[1].toInt() and 0xFF) shl 16) or
        ((key[2].toInt() and 0xFF) shl 8) or
        (key[3].toInt() and 0xFF)

    // Bound the claimed length against the blob before trusting it. A hostile
    // or truncated blob must yield "unknown", never an exception and never a
    // read past the end.
    if (length <= 0 || length > key.size - 4 || length > MAX_TYPE_NAME) return UNKNOWN_KEY_TYPE
    return String(key, 4, length, Charsets.US_ASCII)
}

internal const val UNKNOWN_KEY_TYPE = "unknown"

/** No real SSH algorithm name comes close; anything longer is malformed. */
internal const val MAX_TYPE_NAME = 64
