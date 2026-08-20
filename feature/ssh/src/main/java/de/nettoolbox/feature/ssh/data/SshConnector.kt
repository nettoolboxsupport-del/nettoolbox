package de.nettoolbox.feature.ssh.data

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import de.nettoolbox.core.common.di.IoDispatcher
import android.util.Log
import de.nettoolbox.feature.ssh.BuildConfig
import de.nettoolbox.core.common.result.ErrorReason
import de.nettoolbox.core.common.result.NetToolboxError
import de.nettoolbox.feature.ssh.domain.HostKeyVerdict
import de.nettoolbox.feature.ssh.domain.SshAuthMethod
import de.nettoolbox.feature.ssh.domain.SshTarget
import de.nettoolbox.feature.ssh.domain.TerminalSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A live shell session: the streams, plus what is needed to resize and close.
 *
 * Closing is the caller's job and must happen even on failure paths, which is
 * why this is a plain holder rather than something clever - there is nothing
 * here that can silently forget to shut a socket.
 */
class SshShellSession internal constructor(
    private val session: Session,
    private val channel: ChannelShell,
    val input: InputStream,
    private val output: OutputStream,
) {
    /**
     * Guards the channel's output stream.
     *
     * jsch's channel OutputStream is not safe for concurrent use: it keeps a
     * single Buffer and Packet as fields and fills them across a write/flush
     * pair. Two threads writing at once can interleave inside that pair and
     * emit a malformed SSH packet, which a server answers by dropping the
     * connection. Key presses arrive on arbitrary Dispatchers.IO threads, so
     * this is not hypothetical.
     */
    private val writeLock = Any()

    val isConnected: Boolean get() = session.isConnected && channel.isConnected

    /**
     * Sends bytes to the remote shell.
     *
     * Returns false when the channel is already gone, rather than throwing or
     * pretending it worked - the caller has to be able to tell the user that
     * their keystrokes are going nowhere.
     */
    fun write(bytes: ByteArray): Boolean {
        if (!isConnected) return false
        return synchronized(writeLock) {
            runCatching {
                output.write(bytes)
                output.flush()
            }.isSuccess
        }
    }

    /**
     * Why the channel ended, as far as jsch can tell.
     *
     * Assembled for diagnostics only. An exit status of -1 means the server
     * never sent one, which is itself informative: it distinguishes a shell
     * that exited from a connection that was severed.
     */
    fun closureDiagnostics(): String =
        "exitStatus=${channel.exitStatus}, channelClosed=${channel.isClosed}, " +
            "channelEof=${channel.isEOF}, channelConnected=${channel.isConnected}, " +
            "sessionConnected=${session.isConnected}"

    /**
     * Tells the server the terminal changed size.
     *
     * MUST NOT be called from the main thread, and MUST hold the same lock as
     * [write]. Both requirements come from one incident worth recording.
     *
     * This used to be a bare runCatching around channel.setPtySize(), called
     * from Compose's onSizeChanged - which runs on the main thread. Android
     * throws NetworkOnMainThreadException there, and jsch throws it from
     * *inside* its own send path:
     *
     *     synchronized (lock) {
     *         encode(packet);   // encrypts and MACs using the sequence number
     *         io.put(packet);   // <- throws here, mid-write to the socket
     *         ++seqo;           // <- never reached
     *     }
     *
     * The result is half a packet on the wire and a sequence number that never
     * advanced. Every later packet is then misaligned, so the server computes
     * the MAC over the wrong bytes and kills the connection with
     * "message authentication code incorrect" - which is exactly what the
     * server log showed. The exception itself was swallowed and invisible.
     *
     * Two lessons, both already learned once in this project and both worth
     * more than the fix: a silent runCatching around third-party code turns a
     * crash into silence, and every write to one SSH channel has to go through
     * a single serialised path.
     */
    fun resize(size: TerminalSize): Boolean {
        if (!isConnected || size.cols <= 0 || size.rows <= 0) return false
        return synchronized(writeLock) {
            runCatching {
                channel.setPtySize(size.cols, size.rows, size.cols * 8, size.rows * 16)
            }.onFailure {
                Log.e("NetToolboxSSH", "resize failed: ${it.javaClass.simpleName}: ${it.message}", it)
            }.isSuccess
        }
    }

    fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

/**
 * Opens SSH shell sessions.
 *
 * Host key checking is not optional and has no bypass: [NetToolboxHostKeyRepository]
 * is always installed, and a key that is not already trusted aborts the
 * connection before authentication is attempted. That ordering matters - it
 * means a password is never sent to a host whose identity has not been
 * confirmed.
 */
@Singleton
class SshConnector @Inject constructor(
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
    private val verifier: HostKeyVerifier,
) {

    /**
     * Result of an attempt. [HostKeyRejected] is separate from [Failure]
     * because it is not a fault to report - it is a question to ask.
     */
    sealed interface Attempt {
        data class Success(val session: SshShellSession) : Attempt
        data class HostKeyRejected(val verdict: HostKeyVerdict) : Attempt
        data class Failure(val error: NetToolboxError) : Attempt
    }

    suspend fun connect(
        target: SshTarget,
        auth: SshAuthMethod,
        size: TerminalSize,
        connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Attempt = withContext(dispatcher) {
        // Must happen before any jsch object exists: jsch resolves its
        // algorithm list from the installed JCE providers.
        SshCrypto.ensureProvidersRegistered()

        // Without this, a server-side disconnect arrives as nothing more than
        // an input stream at EOF - the reason the server gave is lost.
        SshJschLogger.installIfEnabled(BuildConfig.DEBUG)

        val jsch = JSch()
        val hostKeys = NetToolboxHostKeyRepository(verifier, target.knownHostsId)
        jsch.hostKeyRepository = hostKeys

        var session: Session? = null
        try {
            session = jsch.getSession(target.username, target.host, target.port)

            when (auth) {
                is SshAuthMethod.Password -> session.setPassword(String(auth.password))
                is SshAuthMethod.PrivateKey -> jsch.addIdentity(
                    target.knownHostsId,
                    String(auth.privateKeyPem).toByteArray(),
                    null,
                    auth.passphrase?.let { String(it).toByteArray() },
                )
            }

            // Belt and braces. Our repository already refuses anything not
            // trusted, but leaving jsch's own default in place means a future
            // change to that repository cannot quietly downgrade to
            // accept-on-first-use.
            session.setConfig("StrictHostKeyChecking", "yes")

            session.connect(connectTimeoutMillis)

            val channel = session.openChannel("shell") as ChannelShell
            val wp = if (size.cols > 0) size.cols * 8 else 640
            val hp = if (size.rows > 0) size.rows * 16 else 480
            channel.setPtyType("xterm", size.cols, size.rows, wp, hp)

            // The input stream must be taken BEFORE connect: jsch pipes
            // incoming channel data into it, and anything that arrives before
            // the pipe exists is discarded.
            val input = channel.inputStream

            channel.connect(connectTimeoutMillis)

            // The output stream is taken AFTER connect, deliberately. jsch
            // initialises it lazily on first write, so taking it earlier is
            // documented as safe - this is not a claimed fix, it removes a
            // variable. The remote packet size and the server's channel id are
            // both known only once the channel is open, and there is no reason
            // to hold a stream that depends on them across that boundary.
            val output = channel.outputStream

            Attempt.Success(SshShellSession(session, channel, input, output))
        } catch (e: JSchException) {
            session?.disconnect()

            // A rejected host key surfaces as an ordinary JSchException, so the
            // repository's own verdict is what distinguishes it. Checking that
            // first keeps a man-in-the-middle from being reported as a vague
            // connection error.
            val verdict = hostKeys.lastVerdict
            when {
                verdict is HostKeyVerdict.Unknown || verdict is HostKeyVerdict.Changed ->
                    Attempt.HostKeyRejected(verdict)

                else -> Attempt.Failure(e.toSshError())
            }
        } catch (e: Exception) {
            session?.disconnect()
            Attempt.Failure(
                NetToolboxError(ErrorReason.UNKNOWN, e.message, cause = e),
            )
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000
    }
}

/**
 * Maps jsch's failures onto the app's error domain.
 *
 * jsch reports almost everything as a [JSchException] carrying a message, so
 * the message is inspected. That is fragile by nature, which is why the
 * fallback keeps the original text rather than replacing it with a generic
 * one: a wrong classification should still leave the user something true to
 * read.
 */
private fun JSchException.toSshError(): NetToolboxError {
    val text = message.orEmpty()
    val reason = when {
        text.contains("Auth fail", ignoreCase = true) -> ErrorReason.AUTH_FAILED
        text.contains("Auth cancel", ignoreCase = true) -> ErrorReason.AUTH_FAILED
        text.contains("USERAUTH fail", ignoreCase = true) -> ErrorReason.AUTH_FAILED
        text.contains("timeout", ignoreCase = true) -> ErrorReason.TIMEOUT
        text.contains("UnknownHost", ignoreCase = true) -> ErrorReason.HOST_UNREACHABLE
        text.contains("Connection refused", ignoreCase = true) -> ErrorReason.CONNECTION_REFUSED
        else -> ErrorReason.UNKNOWN
    }
    return NetToolboxError(reason, text.ifBlank { null }, cause = this)
}
