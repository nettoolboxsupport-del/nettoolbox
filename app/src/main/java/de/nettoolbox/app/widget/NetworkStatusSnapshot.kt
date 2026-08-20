package de.nettoolbox.app.widget

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.nettoolbox.app.R
import de.nettoolbox.feature.cellular.data.TelephonyRepository
import de.nettoolbox.feature.wifi.data.WifiConnectionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of the widget.
 *
 * @param value what to show, or null when there is nothing to show
 * @param blockedByPermission true when [value] is null *because* a permission
 *   is missing rather than because the radio has nothing to report. The two
 *   render identically as an empty field and mean opposite things: one is a
 *   device state the user cannot change, the other is two taps away.
 */
@Serializable
data class WidgetLine(
    val label: String,
    val value: String?,
    val blockedByPermission: Boolean = false,
)

/**
 * Everything the widget shows.
 *
 * Both radios are reported, always. An earlier version showed one *or* the
 * other and picked between them by asking whether a Wi-Fi SSID was readable -
 * which is not a test of connectivity at all: Android hides the SSID from apps
 * without location permission, so a device sitting on Wi-Fi looked like a
 * device on cellular, and the widget then mixed a mobile operator name with a
 * Wi-Fi IP address. For someone diagnosing a network, that is worse than
 * showing nothing.
 *
 * @param readAtMillis when this was taken. Android refreshes widgets at most
 *   every half hour, so a reading without its timestamp invites trust it has
 *   not earned.
 */
@Serializable
data class NetworkStatusSnapshot(
    val activeTransport: String,
    val wifi: List<WidgetLine>,
    val cellular: List<WidgetLine>,
    val readAtMillis: Long,
)

object NetworkStatusReader {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDependencies {
        fun wifiConnectionRepository(): WifiConnectionRepository
        fun telephonyRepository(): TelephonyRepository
    }

    suspend fun read(context: Context): NetworkStatusSnapshot {
        val deps = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetDependencies::class.java,
        )
        val now = System.currentTimeMillis()

        // The transport comes from NetworkCapabilities, which is the platform's
        // own answer to "what am I connected through". It needs no permission
        // and cannot be confused by redacted fields.
        val capabilities = context.getSystemService<ConnectivityManager>()
            ?.let { cm -> cm.activeNetwork?.let(cm::getNetworkCapabilities) }
        val onWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val onCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val hasLocation = hasPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val hasPhoneState = hasPermission(context, android.Manifest.permission.READ_PHONE_STATE)

        val wifi = runCatching { deps.wifiConnectionRepository().current() }.getOrNull()


        val wifiLines = listOf(
            WidgetLine(
                label = context.getString(R.string.widget_label_ssid),
                // Present but unreadable is its own state. Android redacts the
                // SSID without location permission and returns a placeholder,
                // which the repository correctly maps to null - so null here
                // means either "not on Wi-Fi" or "not allowed to know", and
                // only the permission flag can tell them apart.
                value = wifi?.ssid,
                blockedByPermission = onWifi && wifi?.ssid == null && !hasLocation,
            ),
            WidgetLine(
                label = context.getString(R.string.widget_label_signal),
                // RSSI survives redaction, so this fills in even when the SSID
                // does not.
                value = wifi?.rssiDbm?.takeIf { onWifi }?.let { "$it dBm" },
            ),
            WidgetLine(
                label = context.getString(R.string.widget_label_channel),
                value = wifi?.channel?.takeIf { onWifi }?.let { "$it · ${wifi.band.label}" },
            ),
            // The address belongs to whichever transport currently carries
            // traffic: it comes from the LinkProperties of the *active*
            // network. Listing it at the bottom made a Wi-Fi address look like
            // a mobile one.
            WidgetLine(
                label = context.getString(R.string.widget_label_ip),
                value = wifi?.ipv4Address?.takeIf { onWifi },
            ),
        )

        val cellular = if (!hasPhoneState) {
            null
        } else {
            // The repository is a callback flow, so the first emission is the
            // snapshot. Bounded, because a widget update runs on a short budget
            // and a modem that never answers must not consume it.
            runCatching {
                withTimeoutOrNull(CELLULAR_TIMEOUT_MILLIS) {
                    val repo = deps.telephonyRepository()
                    repo.observe(repo.defaultSubscriptionId()).first()
                }
            }.getOrNull()
        }
        val serving = cellular?.serving

        val cellularLines = listOf(
            WidgetLine(
                label = context.getString(R.string.widget_label_operator),
                value = cellular?.networkOperatorName ?: serving?.operatorName,
                blockedByPermission = !hasPhoneState,
            ),
            WidgetLine(
                label = context.getString(R.string.widget_label_technology),
                value = serving?.displayNetworkType ?: serving?.rat?.name,
            ),
            WidgetLine(
                label = context.getString(R.string.widget_label_signal),
                // SignalMetrics keeps one field per measurement, because which
                // one is meaningful depends on the radio: RSRP on LTE and NR,
                // RSCP on UMTS, RSSI on GSM.
                value = serving?.metrics?.let { m ->
                    (m.rsrp ?: m.rscp ?: m.rssi)?.let { "$it dBm" }
                },
            ),
            WidgetLine(
                label = context.getString(R.string.widget_label_ip),
                value = wifi?.ipv4Address?.takeIf { onCellular && !onWifi },
            ),
        )

        return NetworkStatusSnapshot(
            activeTransport = when {
                onWifi -> context.getString(R.string.widget_transport_wifi)
                onCellular -> context.getString(R.string.widget_transport_cellular)
                else -> context.getString(R.string.widget_transport_offline)
            },
            wifi = wifiLines,
            cellular = cellularLines,
            readAtMillis = now,
        )
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private const val CELLULAR_TIMEOUT_MILLIS = 3_000L
}

/**
 * Moves a snapshot in and out of the widget's Glance state.
 *
 * Glance keeps a live composition for a visible widget: calling `update()`
 * recomposes it, but does **not** run `provideGlance` again. Data captured
 * outside the composition is therefore frozen at the moment the widget was
 * placed - which is exactly why an earlier version never refreshed, no matter
 * how often it was tapped.
 *
 * The snapshot consequently has to live in state the composition observes.
 * Stored as one JSON string rather than a dozen individual preference keys:
 * the snapshot is already nothing but strings, and one key cannot fall out of
 * step with itself.
 */
object SnapshotCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(snapshot: NetworkStatusSnapshot): String =
        json.encodeToString(NetworkStatusSnapshot.serializer(), snapshot)

    /**
     * Returns null when there is no stored state yet.
     *
     * A decoding failure is logged rather than quietly turned into null:
     * unreadable state and absent state look identical to the caller, and this
     * project has lost hours three times to a runCatching that swallowed the
     * one fact worth knowing.
     */
    fun decode(raw: String?): NetworkStatusSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(NetworkStatusSnapshot.serializer(), raw)
        }.onFailure {
            Log.e("NetToolboxWidget", "stored snapshot could not be decoded", it)
        }.getOrNull()
    }
}
