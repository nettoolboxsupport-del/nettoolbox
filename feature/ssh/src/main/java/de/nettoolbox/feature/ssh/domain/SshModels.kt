package de.nettoolbox.feature.ssh.domain

import de.nettoolbox.core.common.result.NetToolboxError

/** Where to connect, and as whom. */
data class SshTarget(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val username: String,
) {
    /**
     * The key under which this host's key is stored.
     *
     * Includes the port in OpenSSH's bracket form whenever it is not 22, so
     * that a host reached on two ports does not share one trust decision -
     * they can be entirely different machines behind a port forward.
     */
    val knownHostsId: String
        get() = if (port == DEFAULT_PORT) host else "[$host]:$port"

    companion object {
        const val DEFAULT_PORT: Int = 22
    }
}

/**
 * How to authenticate.
 *
 * Secrets are held as [CharArray] rather than [String] so they can be
 * overwritten after use. This is a modest measure - the JVM copies strings
 * around freely and jsch itself takes a String - but it keeps the window
 * during which a password sits in an immutable, GC-pinned object short, and
 * it costs nothing.
 */
sealed interface SshAuthMethod {

    data class Password(val password: CharArray) : SshAuthMethod {
        // Generated equals/hashCode would compare CharArray by identity, which
        // is both wrong and a needless invitation to compare secrets at all.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * A private key in OpenSSH or PEM form.
     *
     * @param passphrase null when the key is not encrypted
     */
    data class PrivateKey(
        val privateKeyPem: CharArray,
        val passphrase: CharArray?,
    ) : SshAuthMethod {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

/** Terminal geometry, needed when the shell channel is opened and on resize. */
data class TerminalSize(
    val rows: Int,
    val cols: Int,
) {
    init {
        require(rows > 0 && cols > 0) { "Terminal size must be positive, was ${rows}x$cols" }
    }
}

/**
 * A host key as presented by the server, in the form the user has to judge.
 *
 * @param fingerprintSha256 the OpenSSH-style `SHA256:...` fingerprint - the
 *   only representation a person can realistically compare against what
 *   `ssh-keygen -lf` prints on the server
 */
data class PresentedHostKey(
    val hostId: String,
    val keyType: String,
    val fingerprintSha256: String,
    val base64Key: String,
)

/**
 * What the known-hosts store says about a presented key.
 *
 * Deliberately a closed set rather than a boolean: "unknown" and "changed"
 * are not two shades of the same thing, and the type system should not let a
 * caller treat them alike.
 */
sealed interface HostKeyVerdict {

    /** Stored, and identical. Connecting may proceed. */
    data object Trusted : HostKeyVerdict

    /** No key stored for this host yet - first contact. */
    data class Unknown(val presented: PresentedHostKey) : HostKeyVerdict

    /**
     * A key is stored, and it is a different one.
     *
     * @param stored what was trusted previously, so the two can be shown side
     *   by side rather than asking the user to trust an assertion
     */
    data class Changed(
        val presented: PresentedHostKey,
        val stored: PresentedHostKey,
    ) : HostKeyVerdict
}

/** Progress of a connection attempt, as observed by the UI. */
sealed interface SshConnectionState {
    data object Idle : SshConnectionState
    data object Connecting : SshConnectionState

    /** Waiting for the user to accept or reject a host key. */
    data class AwaitingHostKeyDecision(val verdict: HostKeyVerdict) : SshConnectionState

    data object Authenticating : SshConnectionState
    data class Connected(val target: SshTarget) : SshConnectionState
    data class Failed(val error: NetToolboxError) : SshConnectionState
    data object Disconnected : SshConnectionState
}
