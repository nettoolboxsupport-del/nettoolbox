package de.nettoolbox.feature.cellular.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class DriveTestState(
    val isRecording: Boolean = false,
    val sessionId: Long? = null,
    val sessionName: String? = null,
    val startedAt: Long? = null,
    val sampleCount: Int = 0,
    val pendingSamples: Int = 0,
    val lastAccuracyMeters: Float? = null,
    val hasFix: Boolean = false,
    val handoverCount: Int = 0,
)

/**
 * Shared state between the logging service and the UI.
 *
 * The recording itself lives in the service, not in a ViewModel: the spec
 * requires a running measurement to survive process death and rotation, and a
 * ViewModel survives neither.
 */
@Singleton
class DriveTestController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val _state = MutableStateFlow(DriveTestState())
    val state: StateFlow<DriveTestState> = _state.asStateFlow()

    fun start(sessionName: String) {
        val intent = Intent(context, CellularLoggingService::class.java).apply {
            action = CellularLoggingService.ACTION_START
            putExtra(CellularLoggingService.EXTRA_SESSION_NAME, sessionName)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop() {
        val intent = Intent(context, CellularLoggingService::class.java).apply {
            action = CellularLoggingService.ACTION_STOP
        }
        // Not startForegroundService: the service is already running, and asking
        // the system to start one it must then promote would be wrong here.
        context.startService(intent)
    }

    internal fun update(transform: (DriveTestState) -> DriveTestState) = _state.update(transform)

    internal fun reset() = _state.update { DriveTestState() }
}
