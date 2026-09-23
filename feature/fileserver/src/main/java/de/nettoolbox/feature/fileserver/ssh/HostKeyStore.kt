package de.nettoolbox.feature.fileserver.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server's own SSH identity.
 *
 * ### Why the key is generated once and then kept
 *
 * An SSH client remembers the host key it saw the first time and refuses to
 * connect if it changes - that is the entire basis of SSH's protection against
 * being redirected to another machine. A server that generated a fresh key on
 * every start would greet its users with the "REMOTE HOST IDENTIFICATION HAS
 * CHANGED" warning every single time, and the only way to work would be to
 * teach them to ignore it. Teaching people to ignore that warning is worse than
 * having no warning at all, so the key is generated once and persisted.
 *
 * ### Why it is not stored in the Android keystore
 *
 * Unlike the FTPS certificate, this key has to be handed to Apache SSHD as a
 * [KeyPair] with a usable private key, and an AndroidKeyStore key deliberately
 * never leaves the keystore. It is therefore stored as an ordinary file in the
 * app's private directory - readable only by this app, and by root.
 *
 * ### Why ECDSA P-256
 *
 * Every SSH client in circulation supports it, it is fast enough that a phone
 * completes a handshake without a visible pause, and unlike ed25519 it needs no
 * provider beyond what the platform already has. The fingerprint shown in the
 * app is the SHA-256 form that OpenSSH prints, so the two can be compared
 * character for character.
 */
@Singleton
class HostKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    private val privateFile: File get() = File(directory, PRIVATE_FILE_NAME)
    private val publicFile: File get() = File(directory, PUBLIC_FILE_NAME)

    /** The key pair, generating and storing one on first use. */
    @Synchronized
    fun keyPair(): KeyPair = load() ?: generate()

    /**
     * The OpenSSH SHA-256 fingerprint, in the exact form ssh(1) prints.
     *
     * Shown in the app so a user can compare it against what their client
     * displays on first connection. Without that comparison, "trust on first
     * use" is just "trust", and the whole host key mechanism is decoration.
     */
    fun fingerprint(): String {
        val blob = opensshPublicKeyBlob(keyPair().public) ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        // OpenSSH prints base64 without the padding.
        val encoded = Base64.getEncoder().encodeToString(digest).trimEnd('=')
        return "SHA256:$encoded"
    }

    /** The one-line public key, for pasting into a known_hosts file. */
    fun publicKeyLine(): String {
        val blob = opensshPublicKeyBlob(keyPair().public) ?: return ""
        return "$KEY_TYPE ${Base64.getEncoder().encodeToString(blob)} nettoolbox"
    }

    /**
     * Throws the identity away and makes a new one.
     *
     * Offered because there is one legitimate reason to want it - the key may
     * have been copied off a rooted or lost device - and it comes with the
     * warning it deserves: every client that has connected before will refuse
     * the next connection until its stored entry is removed.
     */
    @Synchronized
    fun regenerate(): KeyPair {
        privateFile.delete()
        publicFile.delete()
        return generate()
    }

    private fun load(): KeyPair? {
        if (!privateFile.isFile || !publicFile.isFile) return null
        return runCatching {
            val factory = KeyFactory.getInstance(KEY_ALGORITHM)
            KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())),
            )
        }.getOrNull()
    }

    private fun generate(): KeyPair {
        val pair = KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
            initialize(KEY_SIZE_BITS)
        }.generateKeyPair()

        privateFile.writeBytes(pair.private.encoded)
        publicFile.writeBytes(pair.public.encoded)
        // Best effort: the app's private directory is already inaccessible to
        // other apps, so this only narrows it further on devices where the
        // filesystem honours it.
        runCatching {
            privateFile.setReadable(false, false)
            privateFile.setReadable(true, true)
        }
        return pair
    }

    /**
     * The SSH wire encoding of an ECDSA P-256 public key, RFC 5656 section 3.1.
     *
     * Assembled by hand because the fingerprint has to match what a client
     * computes, and a client computes it over exactly these bytes: the key type
     * string, the curve identifier, and the uncompressed point. Anything else -
     * the X.509 encoding the JCE hands out, say - produces a plausible-looking
     * fingerprint that matches nothing.
     */
    private fun opensshPublicKeyBlob(key: PublicKey): ByteArray? {
        val ec = key as? ECPublicKey ?: return null
        val point = uncompressedPoint(ec.w) ?: return null
        return buildList {
            add(KEY_TYPE.toByteArray(Charsets.US_ASCII))
            add(CURVE_NAME.toByteArray(Charsets.US_ASCII))
            add(point)
        }.let { fields ->
            val size = fields.sumOf { it.size + 4 }
            val out = ByteArray(size)
            var offset = 0
            for (field in fields) {
                writeInt(out, offset, field.size)
                offset += 4
                field.copyInto(out, offset)
                offset += field.size
            }
            out
        }
    }

    /**
     * The point as 0x04 followed by X and Y, each left-padded to the field size.
     *
     * The padding is the part that is easy to get wrong: BigInteger drops
     * leading zero bytes, so roughly one key in 256 produces a coordinate one
     * byte short, and the resulting fingerprint is wrong for that key only -
     * a bug that passes every test run until it does not.
     */
    private fun uncompressedPoint(point: ECPoint): ByteArray? {
        val x = fixedLength(point.affineX.toByteArray()) ?: return null
        val y = fixedLength(point.affineY.toByteArray()) ?: return null
        return ByteArray(1 + x.size + y.size).also {
            it[0] = 0x04
            x.copyInto(it, 1)
            y.copyInto(it, 1 + x.size)
        }
    }

    private fun fixedLength(raw: ByteArray): ByteArray? {
        // BigInteger.toByteArray is signed, so a coordinate whose high bit is
        // set gains a leading zero. Both that and a short value are normalised
        // to exactly the field size.
        val trimmed = if (raw.size > COORDINATE_BYTES && raw[0] == 0.toByte()) {
            raw.copyOfRange(1, raw.size)
        } else {
            raw
        }
        if (trimmed.size > COORDINATE_BYTES) return null
        if (trimmed.size == COORDINATE_BYTES) return trimmed
        return ByteArray(COORDINATE_BYTES).also {
            trimmed.copyInto(it, COORDINATE_BYTES - trimmed.size)
        }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private companion object {
        const val DIRECTORY_NAME = "ssh_host_key"
        const val PRIVATE_FILE_NAME = "host_ecdsa.pk8"
        const val PUBLIC_FILE_NAME = "host_ecdsa.x509"
        const val KEY_ALGORITHM = "EC"
        const val KEY_SIZE_BITS = 256
        const val COORDINATE_BYTES = 32
        const val KEY_TYPE = "ecdsa-sha2-nistp256"
        const val CURVE_NAME = "nistp256"
    }
}
