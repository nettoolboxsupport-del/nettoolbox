package de.nettoolbox.feature.ssh.data

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Makes the algorithms jsch needs available on Android.
 *
 * jsch delegates all cryptography to the JCE. Its own documentation states
 * that **ed25519 needs Java 15+** and **x25519 needs Java 11+**. Android
 * provides neither, at any API level this app supports, and the consequences
 * are not theoretical:
 *
 * - `curve25519-sha256` is the default key exchange in current OpenSSH. Without
 *   x25519 it is unavailable, and a server configured to offer only modern key
 *   exchange cannot be reached at all.
 * - `ssh-ed25519` is the default host key type in current OpenSSH. Without it,
 *   a host that publishes only an ed25519 key cannot be verified.
 *
 * So Bouncy Castle is not a convenience here; without it the client would be
 * unable to talk to a large share of the servers it exists to talk to.
 *
 * **Provider ordering is deliberate.** Android ships a cut-down provider that
 * is also called "BC", so the full one cannot simply be added alongside it -
 * the name would collide and the call would be ignored. The stripped one is
 * therefore removed and the full one appended at the *end* of the list rather
 * than inserted at the front. That way every algorithm the platform already
 * implements keeps resolving to the platform provider, including any that is
 * hardware-backed, and Bouncy Castle only fills the gaps. Putting it first
 * would silently move a great deal of unrelated cryptography onto a software
 * implementation.
 */
object SshCrypto {

    @Volatile
    private var registered = false

    /** Idempotent; safe to call before every connection. */
    @Synchronized
    fun ensureProvidersRegistered() {
        if (registered) return

        // Android's own abbreviated provider uses this exact name. It has to
        // go before the complete one can be installed.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())

        registered = true
    }
}
