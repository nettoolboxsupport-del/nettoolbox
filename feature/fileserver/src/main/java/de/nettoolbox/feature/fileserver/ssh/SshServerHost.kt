package de.nettoolbox.feature.fileserver.ssh

import de.nettoolbox.feature.fileserver.domain.FileServerConfig
import de.nettoolbox.feature.fileserver.domain.Protocol
import de.nettoolbox.feature.fileserver.domain.ServerAccount
import de.nettoolbox.core.common.storage.ShareStorage
import de.nettoolbox.feature.fileserver.domain.TransferDirection
import de.nettoolbox.feature.fileserver.domain.TransferLog
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.scp.common.ScpTransferEventListener
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.ServerAuthenticationManager
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.Handle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * SFTP and legacy SCP, on top of Apache MINA SSHD.
 *
 * ### Why both subsystems
 *
 * They are two different wire protocols that happen to share a command name.
 * OpenSSH 9 and later implement "scp" by speaking SFTP, so for any modern
 * client SFTP alone would be enough. Cisco IOS, and comparable network gear,
 * still speak the original SCP protocol - and that gear is the reason this
 * feature exists. Offering only one of the two would quietly exclude exactly
 * the devices it is meant for.
 *
 * ### No shell, ever
 *
 * The command factory handles SCP and nothing else, and no shell factory is
 * installed. A client that authenticates gets files and only files. This is not
 * a hardening nicety: a shell here would be a remote shell on the user's phone,
 * reachable with a password they typed on a small keyboard in a hurry.
 *
 * ### The ServiceLoader problem
 *
 * SSHD picks its I/O backend through java.util.ServiceLoader. Under R8 that
 * lookup is unreliable - the implementation has no compile-time reference to
 * keep it alive - and the failure appears at start as an unhelpful "no
 * IoServiceFactoryFactory found". Setting Nio2ServiceFactoryFactory explicitly
 * removes the lookup entirely, and NIO2 is the right backend anyway: it is the
 * one built on java.nio.channels, which Android has had since API 26.
 */
internal class SshServerHost(
    private val storage: ShareStorage,
    private val hostKeys: HostKeyStore,
    private val log: TransferLog,
) {

    private var server: SshServer? = null

    /** Throws when the port is taken - the caller turns that into a message. */
    fun start(config: FileServerConfig, bindAddress: InetAddress?) {
        val ssh = config.ssh
        val accountsByName = config.activeAccounts.associateBy { it.username }

        val sshd = SshServer.setUpDefaultServer().apply {
            port = ssh.port
            bindAddress?.hostAddress?.let { host = it }
            ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
            keyPairProvider = KeyPairProvider.wrap(hostKeys.keyPair())

            // Each account is chrooted to its own directory below the share.
            // The default home covers the anonymous case, which SSH does not
            // have - it is there so a misconfigured account cannot end up
            // rooted at the filesystem.
            fileSystemFactory = VirtualFileSystemFactory(storage.root.toPath()).also { factory ->
                for (account in config.activeAccounts) {
                    val home = if (account.homeSubdirectory.isEmpty()) {
                        storage.root
                    } else {
                        storage.resolve(account.homeSubdirectory) ?: storage.root
                    }
                    factory.setUserHomeDir(account.username, home.toPath())
                }
            }

            userAuthFactories = buildList<UserAuthFactory> {
                if (ssh.allowPasswordAuth) {
                    add(ServerAuthenticationManager.DEFAULT_USER_AUTH_PASSWORD_FACTORY)
                }
                // Always offered. It is the one credential here that cannot be
                // read off the screen by someone standing behind the user.
                add(ServerAuthenticationManager.DEFAULT_USER_AUTH_PUBLIC_KEY_FACTORY)
            }

            passwordAuthenticator = if (ssh.allowPasswordAuth) {
                PasswordCheck(accountsByName, log)
            } else {
                null
            }
            publickeyAuthenticator = KeyCheck(ssh.authorizedKeys, accountsByName, log)

            // No shell. Not "a restricted shell" - none at all.
            shellFactory = null

            commandFactory = if (ssh.enableScp) {
                ScpCommandFactory().apply {
                    addEventListener(ScpLogger(accountsByName, log))
                }
            } else {
                null
            }

            subsystemFactories = if (ssh.enableSftp) {
                listOf(
                    SftpSubsystemFactory().apply {
                        addSftpEventListener(SftpGuard(accountsByName, storage, log))
                    },
                )
            } else {
                emptyList()
            }
        }

        sshd.start()
        server = sshd
    }

    fun stop() {
        runCatching { server?.stop(true) }
        server = null
    }

    val running: Boolean get() = server?.isOpen == true
}

// --- authentication ---------------------------------------------------------

private class PasswordCheck(
    private val accounts: Map<String, ServerAccount>,
    private val log: TransferLog,
) : org.apache.sshd.server.auth.password.PasswordAuthenticator {

    override fun authenticate(
        username: String?,
        password: String?,
        session: ServerSession?,
    ): Boolean {
        val account = accounts[username]
        val ok = account != null && password != null &&
            constantTimeEquals(account.password, password)
        if (ok) {
            log.info(Protocol.SFTP, session.describe(), MESSAGE_LOGIN_OK)
        } else {
            log.warn(Protocol.SFTP, session.describe(), MESSAGE_LOGIN_FAILED)
        }
        return ok
    }
}

private class KeyCheck(
    authorizedKeys: List<String>,
    private val accounts: Map<String, ServerAccount>,
    private val log: TransferLog,
) : org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator {

    /**
     * Parsed once at start rather than per attempt.
     *
     * A malformed line is dropped instead of failing the whole server: one bad
     * paste in the key list must not take SFTP down for every other account.
     */
    private val entries: List<AuthorizedKeyEntry> = authorizedKeys.mapNotNull { line ->
        runCatching { AuthorizedKeyEntry.parseAuthorizedKeyEntry(line) }.getOrNull()
    }

    override fun authenticate(
        username: String?,
        key: PublicKey?,
        session: ServerSession?,
    ): Boolean {
        // A key still has to belong to a known account. Without that check any
        // authorized key would grant the rights of every account at once, and
        // the per-account read-only setting would mean nothing.
        if (username == null || key == null || username !in accounts) {
            log.warn(Protocol.SFTP, session.describe(), MESSAGE_LOGIN_FAILED)
            return false
        }
        val matched = entries.any { entry ->
            runCatching {
                entry.resolvePublicKey(session, PublicKeyEntryResolver.IGNORING) == key
            }.getOrDefault(false)
        }
        if (matched) {
            log.info(Protocol.SFTP, session.describe(), MESSAGE_KEY_LOGIN_OK)
        } else {
            log.warn(Protocol.SFTP, session.describe(), MESSAGE_LOGIN_FAILED)
        }
        return matched
    }
}

// --- read-only enforcement and logging --------------------------------------

/**
 * Refuses every write for an account that has no write permission, and turns
 * the rest into log lines.
 *
 * The refusals hang off the *pre* events - opening, writing, creating, moving,
 * removing - because those are the ones that can still stop the operation by
 * throwing. Hooking the past-tense events instead would produce a tidy log of
 * changes that had already happened.
 */
private class SftpGuard(
    private val accounts: Map<String, ServerAccount>,
    private val storage: ShareStorage,
    private val log: TransferLog,
) : SftpEventListener {

    private data class OpenFile(val startedAt: Long, var bytes: Long, var wrote: Boolean)

    private val open = ConcurrentHashMap<String, OpenFile>()

    override fun opening(session: ServerSession, remoteHandle: String, handle: Handle) {
        val file = handle as? FileHandle ?: return
        val wantsWrite = file.openOptions.any {
            it == StandardOpenOption.WRITE ||
                it == StandardOpenOption.APPEND ||
                it == StandardOpenOption.CREATE ||
                it == StandardOpenOption.CREATE_NEW ||
                it == StandardOpenOption.TRUNCATE_EXISTING
        }
        if (wantsWrite) requireWrite(session, handle.file)
        open[remoteHandle] = OpenFile(System.currentTimeMillis(), 0, wantsWrite)
    }

    override fun writing(
        session: ServerSession,
        remoteHandle: String,
        handle: FileHandle,
        offset: Long,
        data: ByteArray,
        dataOffset: Int,
        dataLen: Int,
    ) {
        requireWrite(session, handle.file)
        open[remoteHandle]?.let { it.bytes += dataLen; it.wrote = true }
    }

    override fun read(
        session: ServerSession,
        remoteHandle: String,
        handle: FileHandle,
        offset: Long,
        data: ByteArray,
        dataOffset: Int,
        dataLen: Int,
        readLen: Int,
        thrown: Throwable?,
    ) {
        if (readLen > 0) open[remoteHandle]?.let { it.bytes += readLen }
    }

    override fun closed(
        session: ServerSession,
        remoteHandle: String,
        handle: Handle,
        thrown: Throwable?,
    ) {
        val record = open.remove(remoteHandle) ?: return
        if (handle !is FileHandle || record.bytes == 0L) return
        log.transferred(
            protocol = Protocol.SFTP,
            client = session.describe(),
            path = storage.relativeOf(handle.file.toFile()),
            direction = if (record.wrote) TransferDirection.UPLOAD else TransferDirection.DOWNLOAD,
            bytes = record.bytes,
            durationMillis = System.currentTimeMillis() - record.startedAt,
        )
    }

    override fun creating(session: ServerSession, path: Path, attrs: Map<String, *>) =
        requireWrite(session, path)

    override fun moving(
        session: ServerSession,
        source: Path,
        destination: Path,
        options: Collection<java.nio.file.CopyOption>,
    ) = requireWrite(session, destination)

    override fun removing(session: ServerSession, path: Path, isDirectory: Boolean) =
        requireWrite(session, path)

    override fun modifyingAttributes(session: ServerSession, path: Path, attrs: Map<String, *>) =
        requireWrite(session, path)

    /**
     * Symbolic links are refused outright, for everyone.
     *
     * A link is the one operation that can point out of the share while every
     * path check still passes: the target is resolved later, by whatever opens
     * it. The containment guarantee is only worth something if nothing inside
     * can create a door.
     */
    override fun linking(session: ServerSession, source: Path, target: Path, symLink: Boolean) {
        log.warn(Protocol.SFTP, session.describe(), MESSAGE_LINK_REFUSED, source.toString())
        throw IOException("symbolic links are not permitted")
    }

    private fun requireWrite(session: ServerSession, path: Path) {
        val account = accounts[session.username]
        if (account?.canWrite == true) return
        log.warn(
            Protocol.SFTP,
            session.describe(),
            MESSAGE_READ_ONLY,
            storage.relativeOf(path.toFile()),
        )
        throw IOException("write access denied")
    }
}

private class ScpLogger(
    private val accounts: Map<String, ServerAccount>,
    private val log: TransferLog,
) : ScpTransferEventListener {

    private val started = ConcurrentHashMap<String, Long>()

    override fun startFileEvent(
        session: Session,
        operation: ScpTransferEventListener.FileOperation,
        file: Path,
        length: Long,
        permissions: Set<java.nio.file.attribute.PosixFilePermission>,
    ) {
        if (operation == ScpTransferEventListener.FileOperation.RECEIVE) {
            val account = accounts[session.username]
            if (account?.canWrite != true) {
                log.warn(Protocol.SCP, session.describe(), MESSAGE_READ_ONLY, file.fileName?.toString())
                throw IOException("write access denied")
            }
        }
        started[file.toString()] = System.currentTimeMillis()
    }

    override fun endFileEvent(
        session: Session,
        operation: ScpTransferEventListener.FileOperation,
        file: Path,
        length: Long,
        permissions: Set<java.nio.file.attribute.PosixFilePermission>,
        thrown: Throwable?,
    ) {
        val startedAt = started.remove(file.toString())
        if (thrown != null) {
            log.error(Protocol.SCP, session.describe(), MESSAGE_TRANSFER_FAILED, file.fileName?.toString())
            return
        }
        log.transferred(
            protocol = Protocol.SCP,
            client = session.describe(),
            path = file.fileName?.toString() ?: file.toString(),
            direction = if (operation == ScpTransferEventListener.FileOperation.RECEIVE) {
                TransferDirection.UPLOAD
            } else {
                TransferDirection.DOWNLOAD
            },
            bytes = length,
            durationMillis = System.currentTimeMillis() - (startedAt ?: System.currentTimeMillis()),
        )
    }
}

// --- shared helpers ---------------------------------------------------------

private fun Session?.describe(): String {
    val address = this?.ioSession?.remoteAddress as? java.net.InetSocketAddress ?: return ""
    return "${address.address?.hostAddress ?: address.hostString}:${address.port}"
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val left = digest.digest(a.toByteArray(Charsets.UTF_8))
    digest.reset()
    val right = digest.digest(b.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(left, right)
}

private const val MESSAGE_LOGIN_OK = "ssh.login.ok"
private const val MESSAGE_KEY_LOGIN_OK = "ssh.login.key"
private const val MESSAGE_LOGIN_FAILED = "ssh.login.failed"
private const val MESSAGE_READ_ONLY = "ssh.readonly"
private const val MESSAGE_LINK_REFUSED = "ssh.link"
private const val MESSAGE_TRANSFER_FAILED = "ssh.transfer.failed"
