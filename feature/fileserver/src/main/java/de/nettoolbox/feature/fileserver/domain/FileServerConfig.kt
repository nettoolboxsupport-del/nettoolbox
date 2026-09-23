package de.nettoolbox.feature.fileserver.domain

import kotlinx.serialization.Serializable

/**
 * One login the servers accept.
 *
 * The password is stored in clear text, in the app's private DataStore file.
 * That is a considered decision rather than an oversight, and it is worth being
 * explicit about:
 *
 * A hash would be strictly better if this were a login the user chooses once
 * and types from memory. It is not. It is a credential the user has to read off
 * the screen and type into a switch console or an FTP client minutes later, and
 * a hash cannot be read back. Storing a hash would mean every password is
 * write-once and lost - which in practice pushes people towards "1234".
 *
 * The file lives in the app's private storage, unreadable by other apps without
 * root, and it is excluded from backups along with the rest of the app data.
 * The UI keeps passwords masked until asked.
 *
 * @param homeSubdirectory a path below the share root, or empty for the whole
 *   share. Confining an account to one directory is the cheapest useful
 *   restriction there is: a switch that only ever fetches firmware has no
 *   business seeing the configs folder.
 */
@Serializable
data class ServerAccount(
    val id: String,
    val username: String,
    val password: String,
    val homeSubdirectory: String = "",
    val canWrite: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * TFTP settings.
 *
 * @param port defaults to 6969, not 69. Android refuses to bind ports below
 *   1024 to an unprivileged process, and no amount of configuration changes
 *   that - it is a kernel rule, not an app permission. Devices that cannot be
 *   told a port are therefore out of reach over TFTP, and the UI says so
 *   plainly rather than letting the user discover it during a firmware
 *   upgrade.
 * @param allowUpload TFTP has no authentication whatsoever - none is defined in
 *   the protocol - so an enabled write is an open write for anyone who can
 *   reach the port. Off by default, and the UI warns when it is turned on.
 * @param maxBlockSize RFC 2348. 512 bytes is the protocol default and is
 *   painfully slow; 1468 keeps a block inside one Ethernet frame after IP and
 *   UDP headers, which is the largest size that avoids fragmentation.
 * @param windowSize RFC 7440. A window of 1 means one acknowledgement per block
 *   and a throughput ceiling set by round-trip time. Clients that do not
 *   understand the option simply do not request it.
 */
@Serializable
data class TftpConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val allowUpload: Boolean = false,
    val allowOverwrite: Boolean = false,
    val maxBlockSize: Int = 1468,
    val windowSize: Int = 4,
    val timeoutSeconds: Int = 5,
    val maxRetries: Int = 5,
) {
    companion object {
        const val DEFAULT_PORT = 6969
        const val MIN_BLOCK_SIZE = 8
        const val MAX_BLOCK_SIZE = 65464
    }
}

/**
 * FTP settings.
 *
 * @param passivePortFrom passive mode needs a range of data ports the client
 *   can reach. Left to the library it would pick anything, which is unusable
 *   behind a firewall; a fixed narrow range is what makes the server routable.
 * @param tlsEnabled explicit FTPS (AUTH TLS) on the same port, using a
 *   self-signed certificate generated on the device. Explicit rather than
 *   implicit because implicit FTPS wants its own port and is the deprecated
 *   variant.
 */
@Serializable
data class FtpConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val allowAnonymous: Boolean = false,
    val anonymousCanWrite: Boolean = false,
    val passivePortFrom: Int = 50000,
    val passivePortTo: Int = 50020,
    val tlsEnabled: Boolean = false,
    val maxConcurrentLogins: Int = 10,
    val idleTimeoutSeconds: Int = 300,
) {
    companion object {
        const val DEFAULT_PORT = 2121
    }
}

/**
 * SSH settings, covering both SFTP and legacy SCP.
 *
 * @param enableScp the original SCP protocol, separate from SFTP on purpose.
 *   OpenSSH 9 and later route the "scp" command through SFTP, so for a modern
 *   client SFTP alone is enough - but Cisco IOS, and comparable network gear,
 *   still speak the original protocol. Switching this off would quietly break
 *   the exact devices this feature exists for.
 * @param authorizedKeys public keys in OpenSSH one-line format. Key
 *   authentication is offered alongside passwords because it is the only
 *   credential here that a shoulder-surfer cannot copy off the screen.
 */
@Serializable
data class SshConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val enableSftp: Boolean = true,
    val enableScp: Boolean = true,
    val allowPasswordAuth: Boolean = true,
    val authorizedKeys: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_PORT = 2222
    }
}

/**
 * Which interfaces the listening sockets are bound to.
 *
 * WIFI_ONLY binds the Wi-Fi address specifically, so the servers are not
 * reachable over the mobile interface even if that interface has a routable
 * address. On a metered connection with a public IPv6 address - which is
 * ordinary on modern mobile networks - the difference between this and ALL is
 * the difference between a share on the local network and a share on the
 * internet.
 */
enum class BindScope { WIFI_ONLY, ALL_INTERFACES, LOOPBACK_ONLY }

@Serializable
data class FileServerConfig(
    val tftp: TftpConfig = TftpConfig(),
    val ftp: FtpConfig = FtpConfig(),
    val ssh: SshConfig = SshConfig(),
    val accounts: List<ServerAccount> = emptyList(),
    val bindScope: BindScope = BindScope.WIFI_ONLY,
    /**
     * Stops every server after this many minutes of the service running.
     *
     * Zero disables it. It exists because the realistic failure mode of this
     * feature is not an attack, it is a technician who finished the job, put
     * the phone in a pocket and left an authenticated write share running on a
     * customer network for the rest of the day.
     */
    val autoStopMinutes: Int = 60,
    /** Holds a partial wake lock while serving, so a transfer survives the screen going off. */
    val keepAwakeWhileServing: Boolean = true,
) {
    /** Accounts that are switched on. The servers never see the others. */
    val activeAccounts: List<ServerAccount> get() = accounts.filter { it.enabled }

    val anyProtocolEnabled: Boolean get() = tftp.enabled || ftp.enabled || ssh.enabled

    /**
     * True when a client could write without proving who it is.
     *
     * Drives the warning banner. Deliberately a property of the whole
     * configuration rather than of one protocol: the user needs one answer to
     * "can a stranger put files on my phone right now", not three.
     */
    val hasUnauthenticatedWrite: Boolean
        get() = (tftp.enabled && tftp.allowUpload) ||
            (ftp.enabled && ftp.allowAnonymous && ftp.anonymousCanWrite)
}

/** Ports below this cannot be bound by an unprivileged process on Android. */
const val FIRST_UNPRIVILEGED_PORT = 1024
