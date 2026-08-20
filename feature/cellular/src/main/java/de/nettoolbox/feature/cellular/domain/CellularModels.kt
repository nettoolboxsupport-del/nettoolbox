package de.nettoolbox.feature.cellular.domain

import de.nettoolbox.core.common.radio.RadioAccessTechnology

/**
 * Signal metrics of one cell.
 *
 * Every field is nullable and stays null when the modem does not report it.
 * Substituting 0 would turn "not reported" into "measured as zero" in every
 * later average - the single most common way a drive-test log lies.
 */
data class SignalMetrics(
    /** LTE/NR reference signal received power, dBm. */
    val rsrp: Int? = null,
    /** LTE/NR reference signal received quality, dB. */
    val rsrq: Int? = null,
    /** Signal to interference plus noise ratio, dB. */
    val sinr: Int? = null,
    /** Received signal strength, dBm. */
    val rssi: Int? = null,
    /** Channel quality indicator, LTE only. */
    val cqi: Int? = null,
    /** UMTS received signal code power, dBm. */
    val rscp: Int? = null,
    /** UMTS energy per chip over noise, dB. */
    val ecno: Int? = null,
    /** The platform's own 0..4 bucket, for cross-checking our thresholds. */
    val platformLevel: Int? = null,
) {
    val hasAnyValue: Boolean
        get() = listOfNotNull(rsrp, rsrq, sinr, rssi, cqi, rscp, ecno).isNotEmpty()
}

data class ServingCell(
    val subscriptionId: Int,
    val rat: RadioAccessTechnology,
    val operatorName: String? = null,
    val mcc: Int? = null,
    val mnc: Int? = null,
    /** eCI on LTE, NCI on NR, CID on UMTS/GSM. */
    val cellId: Long? = null,
    val pci: Int? = null,
    val tac: Int? = null,
    val arfcn: Int? = null,
    val band: ArfcnBands.BandInfo? = null,
    val bandwidthKhz: Int? = null,
    val timingAdvance: Int? = null,
    val metrics: SignalMetrics = SignalMetrics(),
    val isRoaming: Boolean = false,
    /**
     * What the platform displays as the network type, which is the only way to
     * tell 5G NSA from plain LTE - the cell info list still shows the LTE anchor.
     */
    val displayNetworkType: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    val plmn: String? get() = if (mcc != null && mnc != null) "$mcc-$mnc" else null

    val estimatedDistanceMeters: Int?
        get() = ArfcnBands.timingAdvanceToMeters(timingAdvance)
}

data class NeighborCell(
    val rat: RadioAccessTechnology,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val band: ArfcnBands.BandInfo? = null,
    val metrics: SignalMetrics = SignalMetrics(),
) {
    /**
     * Difference to the serving cell's RSRP. Positive means the neighbour is
     * stronger - the situation right before a handover.
     */
    fun rsrpDeltaTo(serving: ServingCell): Int? {
        val own = metrics.rsrp ?: return null
        val reference = serving.metrics.rsrp ?: return null
        return own - reference
    }
}

data class SubscriptionInfo(
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String?,
    val slotIndex: Int,
)

data class CellularSnapshot(
    val subscription: SubscriptionInfo?,
    val serving: ServingCell?,
    val neighbors: List<NeighborCell> = emptyList(),
    val networkOperatorName: String? = null,
    val isRoaming: Boolean = false,
    /** False when the modem reports no service or is in flight mode. */
    val hasService: Boolean = true,
)
