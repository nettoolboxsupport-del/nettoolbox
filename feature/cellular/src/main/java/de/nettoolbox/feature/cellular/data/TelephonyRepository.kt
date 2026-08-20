package de.nettoolbox.feature.cellular.data

import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.feature.cellular.domain.CellularSnapshot
import de.nettoolbox.feature.cellular.domain.NeighborCell
import de.nettoolbox.feature.cellular.domain.ServingCell
import de.nettoolbox.feature.cellular.domain.SubscriptionInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cellular data pipeline.
 *
 * Two implementations of the same thing, because [TelephonyCallback] only exists
 * from Android 12 while this app supports Android 9. The deprecated
 * [PhoneStateListener] is the only option below that, so it is kept rather than
 * pretending the feature starts at API 31.
 *
 * What this cannot do, by platform design: see cells of any operator other than
 * the inserted SIM's. Scanning foreign networks needs a signature permission the
 * app will never hold, so the UI says so instead of implying otherwise.
 */
@Singleton
class TelephonyRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val executor: Executor get() = ContextCompat.getMainExecutor(context)

    /** Every active SIM, so a dual-SIM device can be measured on both. */
    fun subscriptions(): List<SubscriptionInfo> {
        val manager = context.getSystemService<SubscriptionManager>() ?: return emptyList()

        val active = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return active.map { info ->
            SubscriptionInfo(
                subscriptionId = info.subscriptionId,
                displayName = info.displayName?.toString().orEmpty()
                    .ifBlank { "SIM ${info.simSlotIndex + 1}" },
                carrierName = info.carrierName?.toString(),
                slotIndex = info.simSlotIndex,
            )
        }
    }

    fun defaultSubscriptionId(): Int = SubscriptionManager.getDefaultDataSubscriptionId()

    /**
     * Live snapshots for one subscription.
     *
     * The three inputs arrive on separate callbacks, so the latest of each is
     * held and a combined snapshot is emitted whenever any of them changes -
     * emitting only on cell info would leave the 5G NSA indicator stale.
     */
    fun observe(subscriptionId: Int): Flow<CellularSnapshot> = callbackFlow {
        val manager = telephonyFor(subscriptionId)
        if (manager == null) {
            send(CellularSnapshot(subscription = null, serving = null, hasService = false))
            awaitClose { }
            return@callbackFlow
        }

        val subscription = subscriptions().firstOrNull { it.subscriptionId == subscriptionId }

        var latestCells: List<CellInfo> = emptyList()
        var displayOverride: String? = null
        var isNsa = false
        var hasService = true
        var roaming = false

        fun emitSnapshot() {
            val registered = latestCells.filter { it.isRegistered }
            val serving = registered.firstNotNullOfOrNull {
                CellInfoMapper.toServingCell(it, subscriptionId)
            }
            val neighbors = latestCells
                .filterNot { it.isRegistered }
                .mapNotNull { CellInfoMapper.toNeighbor(it) }
                .sortedByDescending { it.metrics.rsrp ?: Int.MIN_VALUE }

            trySend(
                CellularSnapshot(
                    subscription = subscription,
                    serving = serving?.copy(
                        // NSA is only visible through the display info: the cell
                        // info list still shows the LTE anchor cell.
                        rat = if (isNsa && serving.rat == RadioAccessTechnology.LTE) {
                            RadioAccessTechnology.NR_NSA
                        } else {
                            serving.rat
                        },
                        displayNetworkType = displayOverride,
                        isRoaming = roaming,
                    ),
                    neighbors = neighbors,
                    networkOperatorName = runCatching { manager.networkOperatorName }.getOrNull(),
                    isRoaming = roaming,
                    hasService = hasService,
                ),
            )
        }

        val unregister: () -> Unit
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object :
                TelephonyCallback(),
                TelephonyCallback.CellInfoListener,
                TelephonyCallback.ServiceStateListener,
                TelephonyCallback.DisplayInfoListener {

                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                    latestCells = cellInfo.toList()
                    emitSnapshot()
                }

                override fun onServiceStateChanged(serviceState: ServiceState) {
                    hasService = serviceState.state == ServiceState.STATE_IN_SERVICE
                    roaming = serviceState.roaming
                    emitSnapshot()
                }

                override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                    displayOverride = displayInfo.describe()
                    isNsa = displayInfo.isNsa()
                    emitSnapshot()
                }
            }

            runCatching { manager.registerTelephonyCallback(executor, callback) }
            unregister = { runCatching { manager.unregisterTelephonyCallback(callback) } }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                    latestCells = cellInfo?.toList().orEmpty()
                    emitSnapshot()
                }

                override fun onServiceStateChanged(serviceState: ServiceState?) {
                    hasService = serviceState?.state == ServiceState.STATE_IN_SERVICE
                    roaming = serviceState?.roaming == true
                    emitSnapshot()
                }

                override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                    displayOverride = displayInfo.describe()
                    isNsa = displayInfo.isNsa()
                    emitSnapshot()
                }
            }

            @Suppress("DEPRECATION")
            val events = PhoneStateListener.LISTEN_CELL_INFO or
                PhoneStateListener.LISTEN_SERVICE_STATE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
                } else {
                    0
                }

            @Suppress("DEPRECATION")
            runCatching { manager.listen(listener, events) }

            @Suppress("DEPRECATION")
            unregister = { runCatching { manager.listen(listener, PhoneStateListener.LISTEN_NONE) } }
        }

        // The callbacks only fire on change, so the first snapshot comes from the
        // cached list - otherwise the screen stays empty until something moves.
        latestCells = runCatching { manager.allCellInfo }.getOrNull().orEmpty()
        emitSnapshot()

        awaitClose { unregister() }
    }

    /**
     * Asks the modem for a fresh measurement.
     *
     * From Android 10 the cached list is refreshed at the platform's own pace, so
     * this is the only way to get an on-demand update. Roughly one per second is
     * the realistic ceiling.
     */
    fun requestUpdate(subscriptionId: Int, onResult: (List<CellInfo>) -> Unit) {
        val manager = telephonyFor(subscriptionId) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                manager.requestCellInfoUpdate(
                    executor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            onResult(cellInfo.toList())
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            onResult(emptyList())
                        }
                    },
                )
            }
        } else {
            onResult(runCatching { manager.allCellInfo }.getOrNull().orEmpty())
        }
    }

    private fun telephonyFor(subscriptionId: Int): TelephonyManager? {
        val base = context.getSystemService<TelephonyManager>() ?: return null
        return if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { base.createForSubscriptionId(subscriptionId) }.getOrDefault(base)
        } else {
            base
        }
    }
}

private fun TelephonyDisplayInfo.isNsa(): Boolean =
    overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
        overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE

private fun TelephonyDisplayInfo.describe(): String = when (overrideNetworkType) {
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE -> "5G NSA (mmWave)"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE-A (CA)"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE-A Pro"
    else -> "—"
}
