package de.nettoolbox.feature.cellular.data

import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import de.nettoolbox.core.common.radio.RadioAccessTechnology
import de.nettoolbox.feature.cellular.domain.ArfcnBands
import de.nettoolbox.feature.cellular.domain.NeighborCell
import de.nettoolbox.feature.cellular.domain.ServingCell
import de.nettoolbox.feature.cellular.domain.SignalMetrics

/**
 * Turns the platform's [CellInfo] zoo into the app's model.
 *
 * The important rule here is that [UNAVAILABLE] becomes null. Android reports
 * "not measured" as Integer.MAX_VALUE, and a log that stores 2147483647 as an
 * RSRP is not merely odd - it destroys every average computed from it later.
 */
object CellInfoMapper {

    /** Android's "no value" marker; `CellInfo.UNAVAILABLE` only exists from API 29. */
    private const val UNAVAILABLE = Int.MAX_VALUE

    fun toServingCell(cellInfo: CellInfo, subscriptionId: Int): ServingCell? = when (cellInfo) {
        is CellInfoLte -> cellInfo.toServing(subscriptionId)
        is CellInfoWcdma -> cellInfo.toServing(subscriptionId)
        is CellInfoGsm -> cellInfo.toServing(subscriptionId)
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr) {
            cellInfo.toServing(subscriptionId)
        } else {
            null
        }
    }

    fun toNeighbor(cellInfo: CellInfo): NeighborCell? = when (cellInfo) {
        is CellInfoLte -> NeighborCell(
            rat = RadioAccessTechnology.LTE,
            pci = cellInfo.cellIdentity.pci.orNull(),
            arfcn = cellInfo.cellIdentity.earfcn.orNull(),
            band = ArfcnBands.lteBandOf(cellInfo.cellIdentity.earfcn.orNull()),
            metrics = cellInfo.cellSignalStrength.toMetrics(),
        )

        is CellInfoWcdma -> NeighborCell(
            rat = RadioAccessTechnology.UMTS,
            pci = cellInfo.cellIdentity.psc.orNull(),
            arfcn = cellInfo.cellIdentity.uarfcn.orNull(),
            band = ArfcnBands.umtsBandOf(cellInfo.cellIdentity.uarfcn.orNull()),
            metrics = cellInfo.cellSignalStrength.toMetrics(),
        )

        is CellInfoGsm -> NeighborCell(
            rat = RadioAccessTechnology.GSM,
            arfcn = cellInfo.cellIdentity.arfcn.orNull(),
            band = ArfcnBands.gsmBandOf(cellInfo.cellIdentity.arfcn.orNull()),
            metrics = cellInfo.cellSignalStrength.toMetrics(),
        )

        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr) {
            val identity = cellInfo.cellIdentity as? CellIdentityNr
            NeighborCell(
                rat = RadioAccessTechnology.NR_SA,
                pci = identity?.pci?.orNull(),
                arfcn = identity?.nrarfcn?.orNull(),
                band = ArfcnBands.nrBandOf(identity?.nrarfcn?.orNull()),
                metrics = (cellInfo.cellSignalStrength as? CellSignalStrengthNr)?.toMetrics()
                    ?: SignalMetrics(),
            )
        } else {
            null
        }
    }

    // ---- LTE ----------------------------------------------------------------

    private fun CellInfoLte.toServing(subscriptionId: Int): ServingCell {
        val identity: CellIdentityLte = cellIdentity
        val earfcn = identity.earfcn.orNull()

        return ServingCell(
            subscriptionId = subscriptionId,
            rat = RadioAccessTechnology.LTE,
            operatorName = identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() },
            mcc = identity.mccString?.toIntOrNull(),
            mnc = identity.mncString?.toIntOrNull(),
            cellId = identity.ci.orNull()?.toLong(),
            pci = identity.pci.orNull(),
            tac = identity.tac.orNull(),
            arfcn = earfcn,
            band = ArfcnBands.lteBandOf(earfcn),
            bandwidthKhz = identity.bandwidth.orNull(),
            timingAdvance = cellSignalStrength.timingAdvance.orNull(),
            metrics = cellSignalStrength.toMetrics(),
        )
    }

    private fun CellSignalStrengthLte.toMetrics(): SignalMetrics = SignalMetrics(
        rsrp = rsrp.orNull(),
        rsrq = rsrq.orNull(),
        sinr = rssnr.orNull(),
        rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rssi.orNull() else null,
        cqi = cqi.orNull(),
        platformLevel = level,
    )

    // ---- NR -----------------------------------------------------------------

    private fun CellInfoNr.toServing(subscriptionId: Int): ServingCell {
        val identity = cellIdentity as? CellIdentityNr
        val nrarfcn = identity?.nrarfcn?.orNull()

        return ServingCell(
            subscriptionId = subscriptionId,
            // Standalone until the display info says otherwise; the repository
            // corrects this to NR_NSA when the platform reports an LTE anchor.
            rat = RadioAccessTechnology.NR_SA,
            operatorName = identity?.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() },
            mcc = identity?.mccString?.toIntOrNull(),
            mnc = identity?.mncString?.toIntOrNull(),
            cellId = identity?.nci?.takeIf { it != Long.MAX_VALUE },
            pci = identity?.pci?.orNull(),
            tac = identity?.tac?.orNull(),
            arfcn = nrarfcn,
            band = ArfcnBands.nrBandOf(nrarfcn),
            metrics = (cellSignalStrength as? CellSignalStrengthNr)?.toMetrics() ?: SignalMetrics(),
        )
    }

    private fun CellSignalStrengthNr.toMetrics(): SignalMetrics = SignalMetrics(
        // SS-* are the synchronisation signal measurements, which is what a
        // technician means by "NR RSRP"; the CSI-* values describe the data
        // channel and are reported far less consistently.
        rsrp = ssRsrp.orNull(),
        rsrq = ssRsrq.orNull(),
        sinr = ssSinr.orNull(),
        platformLevel = level,
    )

    // ---- UMTS ---------------------------------------------------------------

    private fun CellInfoWcdma.toServing(subscriptionId: Int): ServingCell {
        val identity: CellIdentityWcdma = cellIdentity
        val uarfcn = identity.uarfcn.orNull()

        return ServingCell(
            subscriptionId = subscriptionId,
            rat = RadioAccessTechnology.UMTS,
            operatorName = identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() },
            mcc = identity.mccString?.toIntOrNull(),
            mnc = identity.mncString?.toIntOrNull(),
            cellId = identity.cid.orNull()?.toLong(),
            pci = identity.psc.orNull(),
            tac = identity.lac.orNull(),
            arfcn = uarfcn,
            band = ArfcnBands.umtsBandOf(uarfcn),
            metrics = cellSignalStrength.toMetrics(),
        )
    }

    private fun CellSignalStrengthWcdma.toMetrics(): SignalMetrics = SignalMetrics(
        // For WCDMA the reported dBm is the RSCP.
        rscp = dbm.orNull(),
        ecno = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ecNo.orNull() else null,
        platformLevel = level,
    )

    // ---- GSM ----------------------------------------------------------------

    private fun CellInfoGsm.toServing(subscriptionId: Int): ServingCell {
        val identity: CellIdentityGsm = cellIdentity
        val arfcn = identity.arfcn.orNull()

        return ServingCell(
            subscriptionId = subscriptionId,
            rat = RadioAccessTechnology.GSM,
            operatorName = identity.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() },
            mcc = identity.mccString?.toIntOrNull(),
            mnc = identity.mncString?.toIntOrNull(),
            cellId = identity.cid.orNull()?.toLong(),
            tac = identity.lac.orNull(),
            arfcn = arfcn,
            band = ArfcnBands.gsmBandOf(arfcn),
            timingAdvance = cellSignalStrength.timingAdvance.orNull(),
            metrics = cellSignalStrength.toMetrics(),
        )
    }

    private fun CellSignalStrengthGsm.toMetrics(): SignalMetrics = SignalMetrics(
        rssi = dbm.orNull(),
        platformLevel = level,
    )

    private fun Int.orNull(): Int? = takeIf { it != UNAVAILABLE && it != Int.MIN_VALUE }
}
