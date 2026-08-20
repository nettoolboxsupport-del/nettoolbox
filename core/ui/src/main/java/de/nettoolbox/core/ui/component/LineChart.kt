package de.nettoolbox.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.nettoolbox.core.ui.theme.NetToolboxTheme

/**
 * One line in a [LineChart]. Oldest sample first.
 */
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<Float>,
)

/**
 * How a bucket collapses when there are more samples than pixels.
 *
 * The right choice depends on the metric: for signal strength the worst value in
 * a window is what a technician acts on ([MIN]), for round-trip times it is the
 * worst spike ([MAX]). Defaulting to [MEAN] would quietly hide both.
 */
enum class BucketStrategy {
    MEAN,
    MIN,
    MAX,
}

/**
 * Rolling line chart on a plain Compose canvas - no chart framework, as required
 * by the spec. Handles the 60/300 s windows of the cellular screen and the
 * per-BSSID history of the Wi-Fi screen.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    maxPointsPerSeries: Int = 240,
    bucketStrategy: BucketStrategy = BucketStrategy.MEAN,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val strokeWidth = with(LocalDensity.current) { 2.dp.toPx() }
    val reduced = series.map { it.copy(points = downsample(it.points, maxPointsPerSeries, bucketStrategy)) }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val span = valueRange.endInclusive - valueRange.start
            if (span <= 0f) return@Canvas

            // Three horizontal guides: bottom, middle, top of the value range.
            repeat(3) { index ->
                val y = size.height * index / 2f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            reduced.forEach { line ->
                if (line.points.size < 2) return@forEach

                val stepX = size.width / (line.points.size - 1).toFloat()
                val path = Path()
                line.points.forEachIndexed { index, value ->
                    val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
                    val x = index * stepX
                    val y = size.height * (1f - (clamped - valueRange.start) / span)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = line.color,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * Legend for a [LineChart]. Separate composable so a screen can place it above,
 * below or beside the chart without the chart having to know about layout.
 */
@Composable
fun ChartLegend(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        series.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(line.color),
                )
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * Collapses [points] to at most [maxPoints] buckets.
 *
 * Pure function on purpose: the reduction is the part that can silently lie about
 * a measurement, so it is unit-tested rather than eyeballed on a canvas.
 */
internal fun downsample(
    points: List<Float>,
    maxPoints: Int,
    strategy: BucketStrategy = BucketStrategy.MEAN,
): List<Float> {
    require(maxPoints > 0) { "maxPoints must be positive, was $maxPoints" }
    if (points.size <= maxPoints) return points

    val result = ArrayList<Float>(maxPoints)
    for (bucket in 0 until maxPoints) {
        val from = (bucket.toLong() * points.size / maxPoints).toInt()
        val to = ((bucket + 1).toLong() * points.size / maxPoints).toInt().coerceAtLeast(from + 1)
        val slice = points.subList(from, to.coerceAtMost(points.size))
        result += when (strategy) {
            BucketStrategy.MEAN -> slice.average().toFloat()
            BucketStrategy.MIN -> slice.min()
            BucketStrategy.MAX -> slice.max()
        }
    }
    return result
}

@Preview(showBackground = true)
@Composable
private fun LineChartPreview() {
    NetToolboxTheme {
        LineChart(
            series = listOf(
                ChartSeries("RSRP", Color(0xFF0B6FA4), List(120) { -95f + (it % 17) * 1.5f }),
            ),
            valueRange = -120f..-60f,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
        )
    }
}
