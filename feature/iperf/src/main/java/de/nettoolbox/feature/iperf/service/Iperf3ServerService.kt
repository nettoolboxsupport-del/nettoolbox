package de.nettoolbox.feature.iperf.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.feature.iperf.R
import de.nettoolbox.feature.iperf.domain.Iperf3ServerEvent
import de.nettoolbox.feature.iperf.domain.Iperf3ServerRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject

/**
 * Keeps an iperf3 server listening while the app is in the background.
 *
 * A foreground service with type `dataSync`, as the spec requires: a
 * listening socket that Android silently kills mid-measurement is worse than
 * one that never started, and the notification is what makes a running
 * server visible and stoppable.
 */
@AndroidEntryPoint
class Iperf3ServerService : Service() {

    @Inject lateinit var runner: Iperf3ServerRunner

    @Inject lateinit var controller: Iperf3ServerController

    @Inject @IoDispatcher lateinit var dispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

    private var serverJob: Job? = null

    private var stopWatchdog: Job? = null

    /** Set when the watchdog released the UI without libiperf having returned. */
    private var stopForced = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
                startServer(port)
            }

            else -> requestStop()
        }
        // A listening server the user did not ask for is not something the
        // system should recreate on its own.
        return START_NOT_STICKY
    }

    private fun startServer(port: Int) {
        if (serverJob != null) return

        stopForced = false
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(port, testCount = 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        serverJob = scope.launch {
            runner.run(port).collect { event ->
                when (event) {
                    is Iperf3ServerEvent.Listening -> controller.update {
                        it.copy(
                            isRunning = true,
                            isStopping = false,
                            port = event.port,
                            lastErrorDetail = null,
                        )
                    }

                    is Iperf3ServerEvent.TestCompleted -> {
                        controller.update { current ->
                            current.copy(
                                completedTests = (
                                    current.completedTests + CompletedServerTest(
                                        finishedAtMillis = System.currentTimeMillis(),
                                        summary = event.summary,
                                        rawJson = event.rawJson,
                                    )
                                    ).takeLast(MAX_REMEMBERED_TESTS),
                            )
                        }
                        updateNotification(port)
                    }

                    is Iperf3ServerEvent.Failed -> controller.update {
                        it.copy(lastErrorDetail = event.error.detail ?: event.error.reason.name)
                    }

                    Iperf3ServerEvent.Stopped -> Unit
                }
            }

            // The flow only completes once iperf_run_server() has actually
            // returned, so this is the first moment the server is genuinely
            // down rather than merely asked to stop.
            if (!stopForced) finishService()
        }
    }

    /**
     * Asks the server to stop, without cancelling the job.
     *
     * Cancelling here would be pointless and misleading: the coroutine is
     * blocked inside a native call that cooperative cancellation cannot
     * preempt, so the measurement would keep running either way - only the UI
     * would claim otherwise. Instead the state goes to "stopping" and the flow
     * is left to end on its own.
     */
    private fun requestStop() {
        if (serverJob == null) {
            finishService()
            return
        }
        controller.update { it.copy(isStopping = true) }
        runner.stop()

        // Escape hatch. If libiperf never returns, the flow never completes,
        // finishService() never runs, and the UI stays in "stopping" with a
        // dead button forever - a state the user can only leave by force-
        // stopping the app. That is never acceptable, whatever the cause.
        stopWatchdog?.cancel()
        stopWatchdog = scope.launch {
            delay(STOP_TIMEOUT_MILLIS)
            if (serverJob == null) return@launch

            // The timeout is generous on purpose: a client picks its own test
            // duration, and a long but perfectly normal test must not be
            // mistaken for a hang.
            stopForced = true
            val detail = getString(R.string.iperf3_server_stop_timeout)
            finishService()
            controller.update { it.copy(lastErrorDetail = detail) }
        }
    }

    private fun finishService() {
        serverJob = null
        controller.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(port: Int) {
        val manager = getSystemService<NotificationManager>() ?: return
        val count = controller.state.value.completedTests.size
        manager.notify(NOTIFICATION_ID, buildNotification(port, count))
    }

    private fun buildNotification(port: Int, testCount: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.iperf3_server_notification_title, port))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.iperf3_server_notification_text,
                    testCount,
                    testCount,
                ),
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                0,
                getString(R.string.iperf3_server_notification_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, Iperf3ServerService::class.java).setAction(ACTION_STOP),
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
                getString(R.string.iperf3_server_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.iperf3_server_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val ACTION_START = "de.nettoolbox.iperf3.SERVER_START"
        const val ACTION_STOP = "de.nettoolbox.iperf3.SERVER_STOP"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 5201

        private const val CHANNEL_ID = "iperf3_server"
        private const val NOTIFICATION_ID = 4712
        private const val MAX_REMEMBERED_TESTS = 20

        /**
         * How long a stop request may take before the UI is released anyway.
         *
         * Long enough that an ordinary client-chosen test duration finishes
         * first, short enough that nobody sits in front of a frozen button.
         */
        private const val STOP_TIMEOUT_MILLIS = 60_000L
    }
}
