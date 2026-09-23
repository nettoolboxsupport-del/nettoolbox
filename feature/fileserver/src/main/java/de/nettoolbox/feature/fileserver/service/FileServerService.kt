package de.nettoolbox.feature.fileserver.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.feature.fileserver.R
import de.nettoolbox.feature.fileserver.domain.BindScope
import de.nettoolbox.feature.fileserver.domain.FIRST_UNPRIVILEGED_PORT
import de.nettoolbox.feature.fileserver.domain.FileServerConfig
import de.nettoolbox.feature.fileserver.domain.FileServerConfigRepository
import de.nettoolbox.feature.fileserver.domain.NetworkAddresses
import de.nettoolbox.feature.fileserver.domain.Protocol
import de.nettoolbox.core.common.storage.ShareStorage
import de.nettoolbox.feature.fileserver.domain.TransferLog
import de.nettoolbox.feature.fileserver.ftp.FtpServerHost
import de.nettoolbox.feature.fileserver.ssh.HostKeyStore
import de.nettoolbox.feature.fileserver.ssh.SshServerHost
import de.nettoolbox.feature.fileserver.tftp.TftpServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.net.BindException
import java.net.InetAddress
import javax.inject.Inject

/**
 * Runs all three servers for as long as the user wants them running.
 *
 * One service rather than three. The protocols share a root, a set of accounts,
 * a log and a notification; splitting them would triple the notifications a user
 * sees for what they experience as one switch.
 *
 * ### Why a foreground service is not optional here
 *
 * A listening socket in a backgrounded app is killed by Android without warning
 * and without a message. For a measurement that is annoying; for a switch
 * halfway through pulling a firmware image over TFTP it is a bricked device. The
 * notification is not a formality - it is what keeps the sockets alive, and it
 * is also the only honest way to tell someone their phone is currently serving
 * files.
 */
@AndroidEntryPoint
class FileServerService : Service() {

    @Inject lateinit var configRepository: FileServerConfigRepository

    @Inject lateinit var storage: ShareStorage

    @Inject lateinit var addresses: NetworkAddresses

    @Inject lateinit var hostKeys: HostKeyStore

    @Inject lateinit var log: TransferLog

    @Inject lateinit var controller: FileServerController

    @Inject @IoDispatcher lateinit var dispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

    private var tftp: TftpServer? = null
    private var tftpJob: Job? = null
    private var ftp: FtpServerHost? = null
    private var ssh: SshServerHost? = null
    private var autoStopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { startServers() }
            else -> stopServers()
        }
        // A listening file server the user did not ask for is not something the
        // system should bring back on its own.
        return START_NOT_STICKY
    }

    private suspend fun startServers() {
        if (tftp != null || ftp != null || ssh != null) return
        val config = configRepository.current()

        val bindAddress = addresses.bindAddressFor(config.bindScope)
        if (config.bindScope == BindScope.WIFI_ONLY && bindAddress == null) {
            // Deliberately not falling back to every interface. The user asked
            // for Wi-Fi only, and quietly widening that would expose the share
            // on the mobile interface - which on a modern network carries a
            // publicly routable IPv6 address.
            controller.update {
                it.copy(isStarting = false, fatalFailure = ProtocolFailure.NO_BIND_ADDRESS)
            }
            stopSelf()
            return
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(config, running = 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        val tftpStatus = startTftp(config, bindAddress)
        val ftpStatus = startFtp(config, bindAddress)
        val sshStatus = startSsh(config, bindAddress)

        val running = listOf(tftpStatus, ftpStatus, sshStatus).count { it.running }
        if (running == 0) {
            controller.update {
                it.copy(
                    isStarting = false,
                    tftp = tftpStatus,
                    ftp = ftpStatus,
                    ssh = sshStatus,
                    fatalFailure = tftpStatus.failure ?: ftpStatus.failure ?: sshStatus.failure
                        ?: ProtocolFailure.OTHER,
                )
            }
            stopServers()
            return
        }

        acquireWakeLock(config)
        val startedAt = System.currentTimeMillis()
        val autoStopAt = if (config.autoStopMinutes > 0) {
            startedAt + config.autoStopMinutes * 60_000L
        } else {
            null
        }

        controller.update {
            it.copy(
                isStarting = false,
                tftp = tftpStatus,
                ftp = ftpStatus,
                ssh = sshStatus,
                addresses = addresses.reachable(config.bindScope),
                startedAtMillis = startedAt,
                autoStopAtMillis = autoStopAt,
                fatalFailure = null,
            )
        }

        updateNotification(config, running)
        scheduleAutoStop(config)
    }

    private fun startTftp(config: FileServerConfig, bindAddress: InetAddress?): ProtocolStatus {
        if (!config.tftp.enabled) return ProtocolStatus(enabled = false)
        val port = config.tftp.port
        if (port < FIRST_UNPRIVILEGED_PORT) {
            return ProtocolStatus(true, false, port, ProtocolFailure.PRIVILEGED_PORT)
        }

        val server = TftpServer(storage, log)
        return try {
            // Bound here, on this thread, so a clash is thrown at this call
            // rather than swallowed inside the serving coroutine - where the
            // only visible outcome would be a server the UI reports as running.
            server.bind(config.tftp, bindAddress)
            tftp = server
            tftpJob = scope.launch { server.serve(this, config.tftp) }
            ProtocolStatus(enabled = true, running = true, port = port)
        } catch (bind: BindException) {
            ProtocolStatus(true, false, port, ProtocolFailure.PORT_IN_USE)
        } catch (failure: Throwable) {
            Log.e(TAG, "tftp start failed", failure)
            ProtocolStatus(true, false, port, ProtocolFailure.OTHER)
        }
    }

    private fun startFtp(config: FileServerConfig, bindAddress: InetAddress?): ProtocolStatus {
        if (!config.ftp.enabled) return ProtocolStatus(enabled = false)
        val port = config.ftp.port
        if (port < FIRST_UNPRIVILEGED_PORT) {
            return ProtocolStatus(true, false, port, ProtocolFailure.PRIVILEGED_PORT)
        }
        val host = FtpServerHost(storage, log)
        return try {
            host.start(config, bindAddress)
            ftp = host
            ProtocolStatus(enabled = true, running = true, port = port)
        } catch (failure: Throwable) {
            Log.e(TAG, "ftp start failed", failure)
            host.stop()
            ProtocolStatus(true, false, port, classifyFtpFailure(failure, config))
        }
    }

    private fun startSsh(config: FileServerConfig, bindAddress: InetAddress?): ProtocolStatus {
        if (!config.ssh.enabled) return ProtocolStatus(enabled = false)
        val port = config.ssh.port
        if (port < FIRST_UNPRIVILEGED_PORT) {
            return ProtocolStatus(true, false, port, ProtocolFailure.PRIVILEGED_PORT)
        }
        val host = SshServerHost(storage, hostKeys, log)
        return try {
            host.start(config, bindAddress)
            ssh = host
            ProtocolStatus(enabled = true, running = true, port = port)
        } catch (failure: Throwable) {
            Log.e(TAG, "ssh start failed", failure)
            host.stop()
            ProtocolStatus(
                true,
                false,
                port,
                if (failure.hasCause<BindException>()) ProtocolFailure.PORT_IN_USE else ProtocolFailure.OTHER,
            )
        }
    }

    /**
     * Tells a taken port apart from a TLS problem.
     *
     * Worth the effort because the two need opposite responses: one is "pick
     * another port", the other is "turn FTPS off". The device certificate is
     * generated by the platform keystore, which can genuinely refuse - on a
     * device whose keystore is in a bad state, for instance - and reporting
     * that as "port in use" would send the user chasing the wrong thing.
     */
    private fun classifyFtpFailure(failure: Throwable, config: FileServerConfig): ProtocolFailure = when {
        failure.hasCause<BindException>() -> ProtocolFailure.PORT_IN_USE
        config.ftp.tlsEnabled && failure.hasCause<java.security.GeneralSecurityException>() ->
            ProtocolFailure.TLS_UNAVAILABLE
        else -> ProtocolFailure.OTHER
    }

    private fun stopServers() {
        autoStopJob?.cancel()
        autoStopJob = null

        tftp?.stop()
        tftp = null
        tftpJob?.cancel()
        tftpJob = null

        ftp?.stop()
        ftp = null
        ssh?.stop()
        ssh = null

        releaseWakeLock()
        controller.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleAutoStop(config: FileServerConfig) {
        if (config.autoStopMinutes <= 0) return
        autoStopJob = scope.launch {
            delay(config.autoStopMinutes * 60_000L)
            log.info(Protocol.FTP, "", MESSAGE_AUTO_STOP)
            stopServers()
        }
    }

    /**
     * Holds a partial wake lock so a transfer survives the screen going off.
     *
     * Partial only: the CPU stays awake, the screen does not. Without it a long
     * upload over Wi-Fi is suspended when the phone dozes, and the client sees a
     * stall it cannot explain. Released in every exit path, including the one
     * where a protocol failed to start.
     */
    private fun acquireWakeLock(config: FileServerConfig) {
        if (!config.keepAwakeWhileServing) return
        val manager = getSystemService<PowerManager>() ?: return
        wakeLock = manager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(WAKE_LOCK_LIMIT_MILLIS) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        tftp?.stop()
        ftp?.stop()
        ssh?.stop()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // --- notification -------------------------------------------------------

    private fun updateNotification(config: FileServerConfig, running: Int) {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(config, running))
    }

    private fun buildNotification(config: FileServerConfig, running: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fileserver_notification_title))
            .setContentText(
                if (config.hasUnauthenticatedWrite) {
                    // Stated on the notification itself, not only in the app.
                    // An open write share is the one state whose risk a user
                    // should be reminded of without having to open anything.
                    getString(R.string.fileserver_notification_open_write)
                } else {
                    resources.getQuantityString(
                        R.plurals.fileserver_notification_text,
                        running,
                        running,
                    )
                },
            )
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                0,
                getString(R.string.fileserver_notification_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, FileServerService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fileserver_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.fileserver_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val ACTION_START = "de.nettoolbox.fileserver.START"
        const val ACTION_STOP = "de.nettoolbox.fileserver.STOP"

        private const val TAG = "NetToolboxFileServer"
        private const val CHANNEL_ID = "file_server"
        private const val NOTIFICATION_ID = 4713
        private const val WAKE_LOCK_TAG = "NetToolbox::FileServer"

        /**
         * An upper bound on the wake lock, independent of the auto-stop.
         *
         * A wake lock without a timeout is the classic way an app drains a
         * battery overnight after something went wrong in a stop path. Twelve
         * hours is far longer than any transfer and far shorter than a day.
         */
        private const val WAKE_LOCK_LIMIT_MILLIS = 12 * 60 * 60 * 1000L

        private const val MESSAGE_AUTO_STOP = "server.autostop"
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    // Bounded: a cause chain can be circular, and an unbounded walk over one
    // hangs the start path with no clue as to why.
    repeat(8) {
        if (current is T) return true
        current = current?.cause ?: return false
    }
    return false
}
