package de.nettoolbox.feature.fileserver.ftp

import de.nettoolbox.feature.fileserver.domain.FileServerConfig
import de.nettoolbox.feature.fileserver.domain.Protocol
import de.nettoolbox.feature.fileserver.domain.ServerAccount
import de.nettoolbox.core.common.storage.ShareStorage
import de.nettoolbox.feature.fileserver.domain.TransferDirection
import de.nettoolbox.feature.fileserver.domain.TransferLog
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest

/**
 * FTP and explicit FTPS, on top of Apache FtpServer.
 *
 * The library does the protocol; everything interesting here is the wiring
 * around it - which accounts exist, where each one is rooted, and what gets
 * written to the live log.
 *
 * ### The port
 *
 * FTP's assigned port is 21 and, like TFTP's 69, it is out of reach: Android
 * runs apps unprivileged and the kernel refuses to bind anything below 1024.
 * Every FTP client worth the name accepts a port, so this is a smaller problem
 * than it is for TFTP - but it still has to be stated rather than discovered.
 *
 * ### Passive ports
 *
 * A passive transfer opens a second connection on a port the server names. Left
 * to the library that would be an arbitrary ephemeral port, which cannot be
 * forwarded through a firewall and fails in exactly the environments where
 * someone would want to forward it. A small fixed range is configured instead,
 * so the range can be opened once and stays true.
 */
internal class FtpServerHost(
    private val storage: ShareStorage,
    private val log: TransferLog,
) {

    private var server: FtpServer? = null

    /** Throws [FtpException] when the port is taken - the caller turns that into a message. */
    fun start(config: FileServerConfig, bindAddress: InetAddress?) {
        val ftp = config.ftp
        val factory = FtpServerFactory()

        val listener = ListenerFactory().apply {
            port = ftp.port
            bindAddress?.hostAddress?.let { serverAddress = it }
            idleTimeout = ftp.idleTimeoutSeconds

            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                passivePorts = "${ftp.passivePortFrom}-${ftp.passivePortTo}"
                // Without this the library hands out whatever address it thinks
                // it has, which on a multi-homed phone is regularly the wrong
                // one and produces a client that connects and then hangs.
                bindAddress?.hostAddress?.let { passiveAddress = it }
                isActiveEnabled = true
                // The check compares the data connection's peer with the
                // control connection's. Turning it on blocks the FXP bounce
                // attack and costs nothing a normal client would notice.
                isActiveIpCheck = true
                isPassiveIpCheck = true
                sslConfiguration = if (ftp.tlsEnabled) DeviceSslConfiguration() else null
            }.createDataConnectionConfiguration()

            if (ftp.tlsEnabled) {
                sslConfiguration = DeviceSslConfiguration()
                // Explicit FTPS (AUTH TLS on the ordinary port), not implicit.
                // Implicit FTPS wants a port of its own and was deprecated
                // before most of the clients in the field were written.
                isImplicitSsl = false
            }
        }.createListener()

        factory.addListener("default", listener)
        factory.userManager = ShareUserManager(config, storage.root)
        factory.fileSystem = NativeFileSystemFactory().apply {
            // The home directory is created by this app, not by a login. A
            // client that authenticates must never be the thing that brings a
            // directory into existence outside the share.
            isCreateHome = false
        }
        factory.connectionConfig = ConnectionConfigFactory().apply {
            isAnonymousLoginEnabled = ftp.allowAnonymous
            maxLogins = ftp.maxConcurrentLogins
            maxAnonymousLogins = if (ftp.allowAnonymous) ftp.maxConcurrentLogins else 0
            // A short delay after a failed login turns an online password guess
            // from thousands of attempts a second into a handful.
            loginFailureDelay = LOGIN_FAILURE_DELAY_MILLIS
            maxLoginFailures = MAX_LOGIN_FAILURES
        }.createConnectionConfig()
        factory.setFtplets(linkedMapOf<String, org.apache.ftpserver.ftplet.Ftplet>("log" to LoggingFtplet(log)))

        server = factory.createServer().also { it.start() }
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
    }

    val running: Boolean get() = server?.isStopped == false

    private companion object {
        const val LOGIN_FAILURE_DELAY_MILLIS = 2000
        const val MAX_LOGIN_FAILURES = 5
    }
}

/**
 * Maps the app's accounts onto the library's user model.
 *
 * Read-only on purpose: [save] and [delete] throw rather than silently doing
 * nothing. Accounts are owned by the configuration screen, and a code path that
 * appears to create one but does not is worse than one that refuses.
 */
private class ShareUserManager(
    private val config: FileServerConfig,
    private val shareRoot: File,
) : UserManager {

    override fun getUserByName(name: String?): User? {
        if (name == UserManager.ANONYMOUS) return anonymousUser()
        val account = config.activeAccounts.firstOrNull { it.username == name } ?: return null
        return account.toFtpUser()
    }

    override fun getAllUserNames(): Array<String> =
        config.activeAccounts.map { it.username }.toTypedArray()

    override fun doesExist(name: String?): Boolean = getUserByName(name) != null

    override fun authenticate(authentication: Authentication?): User = when (authentication) {
        is UsernamePasswordAuthentication -> {
            val account = config.activeAccounts
                .firstOrNull { it.username == authentication.username }
            // The password comparison is constant-time. The window is small -
            // an attacker would need many attempts against a phone on a local
            // network - but there is no reason to leave it open.
            if (account == null || !constantTimeEquals(account.password, authentication.password)) {
                throw AuthenticationFailedException("authentication failed")
            }
            account.toFtpUser()
        }

        is AnonymousAuthentication -> anonymousUser()
            ?: throw AuthenticationFailedException("anonymous login disabled")

        else -> throw AuthenticationFailedException("unsupported authentication")
    }

    override fun getAdminName(): String? = null

    override fun isAdmin(name: String?): Boolean = false

    override fun save(user: User?) {
        throw FtpException("accounts are managed in the app")
    }

    override fun delete(name: String?) {
        throw FtpException("accounts are managed in the app")
    }

    private fun anonymousUser(): User? {
        val ftp = config.ftp
        if (!ftp.allowAnonymous) return null
        return BaseUser().apply {
            name = UserManager.ANONYMOUS
            homeDirectory = shareRoot.absolutePath
            enabled = true
            maxIdleTime = ftp.idleTimeoutSeconds
            authorities = buildList<Authority> {
                add(ConcurrentLoginPermission(ftp.maxConcurrentLogins, ftp.maxConcurrentLogins))
                if (ftp.anonymousCanWrite) add(WritePermission())
            }
        }
    }

    private fun ServerAccount.toFtpUser(): User = BaseUser().apply {
        name = username
        password = this@toFtpUser.password
        // A home below the share root, resolved here rather than trusted from
        // the stored string: the account editor writes a relative path, and
        // this is the point where it becomes an absolute one that the library
        // will chroot the session to.
        homeDirectory = if (homeSubdirectory.isEmpty()) {
            shareRoot.absolutePath
        } else {
            File(shareRoot, homeSubdirectory).absolutePath
        }
        enabled = true
        maxIdleTime = config.ftp.idleTimeoutSeconds
        authorities = buildList<Authority> {
            add(
                ConcurrentLoginPermission(
                    config.ftp.maxConcurrentLogins,
                    config.ftp.maxConcurrentLogins,
                ),
            )
            if (canWrite) add(WritePermission())
        }
    }

    /**
     * Compares two passwords without leaking their length or where they differ.
     *
     * Done by comparing digests rather than by a hand-rolled loop over the
     * bytes: the digests are always the same length, MessageDigest.isEqual is
     * documented as timing-independent, and there is no index arithmetic to get
     * subtly wrong. The window this closes is narrow - an attacker would need
     * many attempts against a phone on the local network - but it costs one
     * line.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val left = digest.digest(a.toByteArray(Charsets.UTF_8))
        digest.reset()
        val right = digest.digest(b.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(left, right)
    }
}

/**
 * Turns FTP commands into log lines.
 *
 * Only the commands that say something a technician would act on. Logging every
 * command - including the client's chatter of FEAT, OPTS, TYPE and PWD - buries
 * the failed login that explains why nothing works.
 */
private class LoggingFtplet(private val log: TransferLog) : DefaultFtplet() {

    override fun onConnect(session: FtpSession): FtpletResult {
        log.info(Protocol.FTP, session.describe(), MESSAGE_CONNECTED)
        return FtpletResult.DEFAULT
    }

    override fun onDisconnect(session: FtpSession): FtpletResult {
        log.info(Protocol.FTP, session.describe(), MESSAGE_DISCONNECTED)
        return FtpletResult.DEFAULT
    }

    override fun afterCommand(
        session: FtpSession,
        request: FtpRequest,
        reply: FtpReply?,
    ): FtpletResult {
        val command = request.command?.uppercase() ?: return FtpletResult.DEFAULT
        val code = reply?.code ?: 0
        val client = session.describe()

        when (command) {
            "PASS" -> if (code == FtpReply.REPLY_530_NOT_LOGGED_IN) {
                log.warn(Protocol.FTP, client, MESSAGE_LOGIN_FAILED)
            } else if (code == FtpReply.REPLY_230_USER_LOGGED_IN) {
                log.info(Protocol.FTP, client, MESSAGE_LOGIN_OK)
            }

            "RETR" -> logTransfer(client, request.argument, TransferDirection.DOWNLOAD, code)
            "STOR", "STOU", "APPE" -> logTransfer(client, request.argument, TransferDirection.UPLOAD, code)

            "DELE" -> if (isSuccess(code)) {
                log.warn(Protocol.FTP, client, MESSAGE_DELETED, request.argument)
            }

            "RMD" -> if (isSuccess(code)) {
                log.warn(Protocol.FTP, client, MESSAGE_DELETED, request.argument)
            }

            "MKD" -> if (isSuccess(code)) {
                log.info(Protocol.FTP, client, MESSAGE_CREATED, request.argument)
            }
        }
        return FtpletResult.DEFAULT
    }

    private fun logTransfer(
        client: String,
        path: String?,
        direction: TransferDirection,
        code: Int,
    ) {
        if (isSuccess(code)) {
            // The byte count is not available here without instrumenting the
            // data connection, and a wrong number is worse than none: the
            // completed-transfer layout would then show a rate that is simply
            // false. The size is filled in by the explorer instead.
            log.info(
                Protocol.FTP,
                client,
                if (direction == TransferDirection.DOWNLOAD) MESSAGE_SENT else MESSAGE_RECEIVED,
                path,
            )
        } else {
            log.error(Protocol.FTP, client, MESSAGE_TRANSFER_FAILED, path)
        }
    }

    private fun isSuccess(code: Int): Boolean = code in 200..299

    private fun FtpSession.describe(): String {
        val address = clientAddress ?: return ""
        return "${address.address?.hostAddress ?: address.hostString}:${address.port}"
    }

    private companion object {
        const val MESSAGE_CONNECTED = "ftp.connected"
        const val MESSAGE_DISCONNECTED = "ftp.disconnected"
        const val MESSAGE_LOGIN_OK = "ftp.login.ok"
        const val MESSAGE_LOGIN_FAILED = "ftp.login.failed"
        const val MESSAGE_SENT = "ftp.sent"
        const val MESSAGE_RECEIVED = "ftp.received"
        const val MESSAGE_TRANSFER_FAILED = "ftp.transfer.failed"
        const val MESSAGE_DELETED = "ftp.deleted"
        const val MESSAGE_CREATED = "ftp.created"
    }
}
