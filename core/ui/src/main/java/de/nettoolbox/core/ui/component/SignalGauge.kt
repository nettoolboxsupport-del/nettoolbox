package de.nettoolbox.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.nettoolbox.core.common.signal.SignalMetric
import de.nettoolbox.core.common.signal.SignalThresholds
import de.nettoolbox.core.ui.R
import de.nettoolbox.core.ui.theme.NetToolboxTheme
import de.nettoolbox.core.ui.theme.color
import de.nettoolbox.core.ui.theme.labelRes

private const val GAUGE_START_ANGLE = 150f
private const val GAUGE_SWEEP_ANGLE = 240f

/**
 * Arc gauge for a single signal metric.
 *
 * Shows the numeric value, the unit and the quality level as text next to the
 * colour, so the reading survives both a colour-blind user and direct sunlight.
 */
@Composable
fun SignalGauge(
    metric: SignalMetric,
    value: Float?,
    modifier: Modifier = Modifier,
    thresholds: SignalThresholds = metric.defaultThresholds,
    label: String = metric.name,
) {
    val quality = metric.classify(value, thresholds)
    val qualityColor = quality.color()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val qualityLabel = stringResource(quality.labelRes())

    val targetFraction = metric.fraction(value)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        label = "signal-gauge-sweep",
    )

    val valueText = value?.let { formatSignalValue(it) } ?: stringResource(R.string.value_unavailable)
    val strokeWidth = with(LocalDensity.current) { 12.dp.toPx() }

    Column(
        modifier = modifier.semantics {
            contentDescription = "$label: $valueText ${metric.unit}, $qualityLabel"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val inset = strokeWidth / 2f
                val diameter = minOf(size.width, size.height * 1.35f) - strokeWidth
                val topLeft = Offset(
                    x = (size.width - diameter) / 2f,
                    y = inset,
                )
                val arcSize = Size(diameter, diameter)

                drawArc(
                    color = trackColor,
                    startAngle = GAUGE_START_ANGLE,
                    sweepAngle = GAUGE_SWEEP_ANGLE,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )

                if (animatedFraction > 0f) {
                    drawArc(
                        color = qualityColor,
                        startAngle = GAUGE_START_ANGLE,
                        sweepAngle = GAUGE_SWEEP_ANGLE * animatedFraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = metric.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = qualityLabel,
            style = MaterialTheme.typography.labelMedium,
            color = qualityColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * One decimal place at most: cell metrics are reported in whole dBm, and a
 * fabricated third digit would suggest precision the radio does not deliver.
 */
internal fun formatSignalValue(value: Float): String {
    val rounded = Math.round(value * 10f) / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

@Preview(showBackground = true)
@Composable
private fun SignalGaugePreview() {
    NetToolboxTheme {
        SignalGauge(
            metric = SignalMetric.RSRP,
            value = -95f,
            label = "RSRP",
            modifier = Modifier.size(180.dp),
        )
    }
}
