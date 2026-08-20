package de.nettoolbox.feature.cellular.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import de.nettoolbox.core.common.di.IoDispatcher
import de.nettoolbox.core.database.entity.CellSampleEntity
import de.nettoolbox.core.datastore.SettingsRepository
import de.nettoolbox.feature.cellular.R
import de.nettoolbox.feature.cellular.data.DriveTestRepository
import de.nettoolbox.feature.cellular.data.LocationProvider
import de.nettoolbox.feature.cellular.data.TelephonyRepository
import de.nettoolbox.feature.cellular.domain.CellularSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import javax.inject.Inject

/**
 * Records a drive test in the background.
 *
 * A foreground service, because that is the only way Android keeps a measurement
 * running with the screen off - and because a recording that silently stops is
 * worse than one that never started.
 */
@AndroidEntryPoint
class CellularLoggingService : Service() {

    @Inject lateinit var telephonyRepository: TelephonyRepository

    @Inject lateinit var locationProvider: LocationProvider

    @Inject lateinit var driveTestRepository: DriveTestRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var controller: DriveTestController

    @Inject @IoDispatcher lateinit var dispatcher: CoroutineDispatcher

    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

    private var recordingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val buffer = mutableListOf<CellSampleEntity>()
    private var lastLocation: Location? = null
    private var lastCellId: Long? = null
    private var sessionId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_SESSION_NAME).orEmpty()
                startRecording(name.ifBlank { defaultSessionName() })
            }

            ACTION_STOP -> stopRecording()
            else -> stopRecording()
        }
        // START_NOT_STICKY: a recording that the system restarts without the user
        // asking would produce a session nobody started and cannot interpret.
        return START_NOT_STICKY
    }

    private fun startRecording(sessionName: String) {
        if (recordingJob != null) return

        startForegroundWithNotification(sessionName, samples = 0)
        acquireWakeLock()

        recordingJob = scope.launch {
            val newSessionId = driveTestRepository.startSession(sessionName)
            sessionId = newSessionId
            controller.update {
                it.copy(
                    isRecording = true,
                    sessionId = newSessionId,
                    sessionName = sessionName,
                    startedAt = System.currentTimeMillis(),
                    sampleCount = 0,
                )
            }

            val settings = settingsRepository.settings.first()
            val subscriptionId = telephonyRepository.defaultSubscriptionId()

            launch {
                locationProvider.observe(
                    minIntervalMillis = settings.cellSampleIntervalSeconds * 1000L,
                    minDistanceMeters = settings.minUpdateDistanceMeters.toFloat(),
                ).collect { location ->
                    lastLocation = location
                    controller.update {
                        it.copy(hasFix = true, lastAccuracyMeters = location.accuracy)
                    }
                }
            }

            launch {
                telephonyRepository.observe(subscriptionId).collect { snapshot ->
                    record(snapshot, newSessionId)
                }
            }

            // The callbacks alone are not enough from Android 10 on: the cached
            // cell list only refreshes when explicitly asked.
            while (true) {
                telephonyRepository.requestUpdate(subscriptionId) { }
                delay(settings.cellSampleIntervalSeconds.coerceIn(1, 10) * 1000L)
            }
        }
    }

    private suspend fun record(snapshot: CellularSnapshot, sessionId: Long) {
        val serving = snapshot.serving ?: return
        val location = lastLocation

        val sample = CellSampleEntity(
            sessionId = sessionId,
            ts = System.currentTimeMillis(),
            lat = location?.latitude,
            lon = location?.longitude,
            accuracy = location?.accuracy,
            speed = location?.speed,
            subId = serving.subscriptionId,
            rat = serving.rat,
            mcc = serving.mcc,
            mnc = serving.mnc,
            operatorName = serving.operatorName ?: snapshot.networkOperatorName,
            cid = serving.cellId,
            pci = serving.pci,
            tac = serving.tac,
            arfcn = serving.arfcn,
            band = serving.band?.number,
            bandwidthKhz = serving.bandwidthKhz,
            rsrp = serving.metrics.rsrp,
            rsrq = serving.metrics.rsrq,
            sinr = serving.metrics.sinr,
            rssi = serving.metrics.rssi,
            cqi = serving.metrics.cqi,
            timingAdvance = serving.timingAdvance,
            isServing = true,
            isRoaming = snapshot.isRoaming,
        )

        val isHandover = lastCellId != null && serving.cellId != null && serving.cellId != lastCellId
        serving.cellId?.let { lastCellId = it }

        synchronized(buffer) { buffer += sample }

        controller.update {
            it.copy(
                sampleCount = it.sampleCount + 1,
                pendingSamples = synchronized(buffer) { buffer.size },
                handoverCount = if (isHandover) it.handoverCount + 1 else it.handoverCount,
            )
        }

        if (synchronized(buffer) { buffer.size } >= FLUSH_THRESHOLD) {
            flush()
        }

        updateNotification()
    }

    private suspend fun flush() {
        val pending = synchronized(buffer) {
            if (buffer.isEmpty()) return
            val copy = buffer.toList()
            buffer.clear()
            copy
        }
        driveTestRepository.appendBatch(pending)
        controller.update { it.copy(pendingSamples = 0) }
    }

    private fun stopRecording() {
        val job = recordingJob
        recordingJob = null

        scope.launch {
            job?.cancel()
            // Flush before ending the session: samples still in the buffer are
            // measurements the user drove for and cannot repeat.
            flush()
            sessionId?.let { driveTestRepository.endSession(it) }
            controller.reset()

            releaseWakeLock()
            ServiceCompat.stopForeground(this@CellularLoggingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification -------------------------------------------------------

    private fun startForegroundWithNotification(sessionName: String, samples: Int) {
        createChannel()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(sessionName, samples), type)
    }

    private fun updateNotification() {
        val state = controller.state.value
        val manager = getSystemService<NotificationManager>() ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(state.sessionName.orEmpty(), state.sampleCount),
        )
    }

    private fun buildNotification(sessionName: String, samples: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.logging_notification_title, sessionName))
            .setContentText(getString(R.string.logging_notification_text, samples))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                0,
                getString(R.string.logging_notification_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, CellularLoggingService::class.java).setAction(ACTION_STOP),
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
                getString(R.string.logging_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.logging_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        val power = getSystemService<PowerManager>() ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_WAKE_LOCK_MILLIS) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    private fun defaultSessionName(): String =
        getString(R.string.logging_default_session_name)

    companion object {
        const val ACTION_START = "de.nettoolbox.cellular.LOG_START"
        const val ACTION_STOP = "de.nettoolbox.cellular.LOG_STOP"
        const val EXTRA_SESSION_NAME = "session_name"

        private const val CHANNEL_ID = "cellular_logging"
        private const val NOTIFICATION_ID = 4711
        private const val WAKE_LOCK_TAG = "nettoolbox:drive-test"

        /** Batch size for the Room write. */
        private const val FLUSH_THRESHOLD = 20

        /** Eight hours; a drive test longer than a working day is a bug. */
        private const val MAX_WAKE_LOCK_MILLIS = 8 * 60 * 60 * 1000L
    }
}
