package de.nettoolbox.feature.wifi.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.nettoolbox.feature.wifi.domain.WifiBand
import de.nettoolbox.feature.wifi.domain.WifiNetwork

/**
 * The classic channel occupancy view: every network as an arc over the spectrum
 * it occupies, height by signal strength.
 *
 * This is the one picture that answers "why is this network slow" faster than any
 * list can - overlapping arcs are visible interference.
 */
@Composable
fun ChannelDiagram(
    networks: List<WifiNetwork>,
    band: WifiBand,
    modifier: Modifier = Modifier,
) {
    val range = frequencyRangeOf(band) ?: return
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    val labelPaint = remember(labelColor, density) {
        Paint().apply {
            color = labelColor.toArgb()
            textSize = with(density) { 10.dp.toPx() }
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }

    val inBand = networks.filter { it.band == band }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        val axisHeight = size.height - AXIS_LABEL_SPACE_PX
        if (axisHeight <= 0f) return@Canvas

        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(0f, axisHeight),
            end = androidx.compose.ui.geometry.Offset(size.width, axisHeight),
            strokeWidth = 1f,
        )

        // Channel ticks. Drawing them for the whole band rather than only where
        // networks sit keeps the picture stable between scans.
        tickChannelsOf(band).forEach { (channel, frequency) ->
            val x = xFor(frequency, range)
            if (x < 0f || x > size.width) return@forEach
            drawLine(
                color = axisColor,
                start = androidx.compose.ui.geometry.Offset(x, axisHeight),
                end = androidx.compose.ui.geometry.Offset(x, axisHeight + 4f),
                strokeWidth = 1f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                channel.toString(),
                x,
                size.height - 2f,
                labelPaint,
            )
        }

        inBand
            // Weakest first, so a strong network is not hidden behind a faint one.
            .sortedBy { it.rssiDbm }
            .forEach { network ->
                drawNetworkArc(network, range, axisHeight, labelPaint)
            }
    }
}

private fun DrawScope.drawNetworkArc(
    network: WifiNetwork,
    range: IntRange,
    axisHeight: Float,
    labelPaint: Paint,
) {
    val centerFrequency = network.centerFrequency0 ?: network.frequencyMhz
    val halfWidth = (network.channelWidthMhz ?: DEFAULT_WIDTH_MHZ) / 2f

    val left = xFor(centerFrequency - halfWidth, range)
    val right = xFor(centerFrequency + halfWidth, range)
    val center = xFor(centerFrequency.toFloat(), range)
    if (right < 0f || left > size.width) return

    val strength = ((network.rssiDbm + WEAKEST_DBM).toFloat() / SPAN_DBM).coerceIn(0f, 1f)
    val apexY = axisHeight * (1f - strength)

    val color = colorFor(network.bssid)
    val path = Path().apply {
        moveTo(left, axisHeight)
        // Control point mirrored above the apex: a quadratic Bezier passes
        // through it at the midpoint, which gives the familiar rounded hump.
        quadraticBezierTo(center, 2f * apexY - axisHeight, right, axisHeight)
    }

    drawPath(path = path, color = color.copy(alpha = 0.18f))
    drawPath(path = path, color = color, style = Stroke(width = 2f))

    if (right - left > MIN_LABEL_WIDTH_PX) {
        drawContext.canvas.nativeCanvas.drawText(
            network.displayName.take(MAX_LABEL_CHARS),
            center,
            (apexY - 4f).coerceAtLeast(labelPaint.textSize),
            labelPaint,
        )
    }
}

private fun DrawScope.xFor(frequencyMhz: Number, range: IntRange): Float {
    val span = (range.last - range.first).toFloat()
    if (span <= 0f) return 0f
    return (frequencyMhz.toFloat() - range.first) / span * size.width
}

/**
 * Stable colour per BSSID, so a network keeps its colour between scans and can be
 * followed across the diagram.
 */
private fun colorFor(bssid: String): Color {
    val hue = ((bssid.hashCode() % 360) + 360) % 360
    return Color.hsv(hue.toFloat(), 0.65f, 0.85f)
}

private fun frequencyRangeOf(band: WifiBand): IntRange? = when (band) {
    WifiBand.BAND_2_4_GHZ -> 2400..2500
    WifiBand.BAND_5_GHZ -> 5150..5900
    WifiBand.BAND_6_GHZ -> 5925..7125
    WifiBand.UNKNOWN -> null
}

/** Channel number to centre frequency, for the axis labels. */
private fun tickChannelsOf(band: WifiBand): List<Pair<Int, Int>> = when (band) {
    WifiBand.BAND_2_4_GHZ -> (1..13).map { it to 2412 + (it - 1) * 5 }
    WifiBand.BAND_5_GHZ ->
        listOf(36, 44, 52, 60, 100, 108, 116, 124, 132, 140, 149, 157, 165)
            .map { it to 5000 + it * 5 }

    WifiBand.BAND_6_GHZ ->
        listOf(1, 33, 65, 97, 129, 161, 193, 225).map { it to 5950 + it * 5 }

    WifiBand.UNKNOWN -> emptyList()
}

private const val WEAKEST_DBM = 100
private const val SPAN_DBM = 70f
private const val DEFAULT_WIDTH_MHZ = 20
private const val AXIS_LABEL_SPACE_PX = 28f
private const val MIN_LABEL_WIDTH_PX = 40f
private const val MAX_LABEL_CHARS = 12
