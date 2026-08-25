package com.mohid.obd2dash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.data.SeriesPoint
import com.mohid.obd2dash.ui.theme.Hairline
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.TextPrimary
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A trip's time series.
 *
 * Drawn directly rather than pulled in from a charting library: the whole
 * requirement is one line, a filled area, and a scrubber, and hand-drawing it
 * keeps the visual language identical to the gauges.
 *
 * Dragging across the plot pins a readout to the nearest sample, which is how
 * you answer "what was the coolant doing when boost spiked" after the drive.
 */
@Composable
fun TripLineChart(
    points: List<SeriesPoint>,
    color: Color,
    unit: String,
    decimals: Int,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("No samples recorded", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        return
    }

    var scrubIndex by remember(points) { mutableStateOf<Int?>(null) }
    val textMeasurer = rememberTextMeasurer()

    val minValue = points.minOf { it.value }
    val maxValue = points.maxOf { it.value }
    // A flat series would otherwise divide by zero and draw a line at the top.
    val span = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
    val padded = span * 0.12f
    val lo = minValue - padded
    val hi = maxValue + padded
    val duration = points.last().elapsedMs.takeIf { it > 0 } ?: 1L

    Box(
        // Horizontal only, deliberately: a general drag detector would swallow
        // vertical swipes and make the report impossible to scroll past the
        // chart.
        modifier = modifier.pointerInput(points) {
            detectHorizontalDragGestures(
                onDragStart = { offset -> scrubIndex = indexAt(offset.x, size.width, points.size) },
                onDragEnd = { scrubIndex = null },
                onDragCancel = { scrubIndex = null },
            ) { change, _ ->
                scrubIndex = indexAt(change.position.x, size.width, points.size)
            }
        }.pointerInput(points) {
            detectTapGestures { offset -> scrubIndex = indexAt(offset.x, size.width, points.size) }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val leftGutter = 44f
            val bottomGutter = 18f
            val plotWidth = size.width - leftGutter
            val plotHeight = size.height - bottomGutter

            fun xFor(index: Int): Float =
                leftGutter + (points[index].elapsedMs.toFloat() / duration) * plotWidth

            fun yFor(value: Float): Float =
                plotHeight - ((value - lo) / (hi - lo)) * plotHeight

            val gridStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextMuted)

            // Horizontal grid with value labels.
            for (i in 0..3) {
                val f = i / 3f
                val y = plotHeight * f
                val value = hi - (hi - lo) * f
                drawLine(
                    color = Hairline.copy(alpha = 0.6f),
                    start = Offset(leftGutter, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                val layout = textMeasurer.measure(formatValue(value, decimals), gridStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(leftGutter - layout.size.width - 6f, y - layout.size.height / 2f),
                )
            }

            val line = Path()
            val area = Path()
            points.forEachIndexed { index, point ->
                val x = xFor(index)
                val y = yFor(point.value)
                if (index == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, plotHeight)
                    area.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(xFor(points.lastIndex), plotHeight)
            area.close()

            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)),
                    endY = plotHeight,
                ),
            )
            drawPath(path = line, color = color, style = Stroke(width = 2.2f, cap = StrokeCap.Round))

            // Elapsed-time labels along the bottom.
            for (i in 0..2) {
                val f = i / 2f
                val label = formatElapsed((duration * f).toLong())
                val layout = textMeasurer.measure(label, gridStyle)
                val x = (leftGutter + f * plotWidth - layout.size.width / 2f)
                    .coerceIn(leftGutter, size.width - layout.size.width)
                drawText(textLayoutResult = layout, topLeft = Offset(x, plotHeight + 4f))
            }

            scrubIndex?.let { index ->
                val point = points[index.coerceIn(points.indices)]
                val x = xFor(index.coerceIn(points.indices))
                val y = yFor(point.value)
                drawLine(
                    color = TextPrimary.copy(alpha = 0.5f),
                    start = Offset(x, 0f),
                    end = Offset(x, plotHeight),
                    strokeWidth = 1.4f,
                )
                drawCircle(color = color, radius = 4.5f, center = Offset(x, y))
                drawCircle(color = Color.Black, radius = 2f, center = Offset(x, y))

                val readout = "${formatValue(point.value, decimals)} $unit · ${formatElapsed(point.elapsedMs)}"
                val layout = textMeasurer.measure(
                    readout,
                    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextPrimary),
                )
                val boxX = (x + 8f).coerceAtMost(size.width - layout.size.width - 6f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.75f),
                    topLeft = Offset(boxX - 4f, 2f),
                    size = androidx.compose.ui.geometry.Size(
                        layout.size.width + 8f,
                        layout.size.height + 4f,
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                )
                drawText(textLayoutResult = layout, topLeft = Offset(boxX, 4f))
            }
        }
    }
}

private fun indexAt(x: Float, width: Int, count: Int): Int {
    if (count <= 1 || width <= 0) return 0
    val leftGutter = 44f
    val f = ((x - leftGutter) / (width - leftGutter)).coerceIn(0f, 1f)
    return (f * (count - 1)).roundToInt()
}

/**
 * The GPS track, drawn as a plain polyline.
 *
 * No Maps SDK: that needs an API key checked into the project and a network
 * round trip to show a route the phone already recorded. The shape of the drive
 * is what is actually useful in a post-trip report.
 */
@Composable
fun RouteTrace(
    points: List<Pair<Double, Double>>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val lats = points.map { it.first }
    val lons = points.map { it.second }
    // Roughly three metres of latitude. Below that the "route" is a parked car
    // with GPS jitter, and stretching it to fill the box would be a lie.
    val stationary = points.size < 2 ||
        ((lats.max() - lats.min()) < 3e-5 && (lons.max() - lons.min()) < 3e-5)

    if (stationary) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (points.isEmpty()) "No GPS track for this trip" else "No movement recorded",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        return
    }

    Canvas(modifier) {
        val midLat = (lats.min() + lats.max()) / 2.0

        // A degree of longitude shrinks with latitude; without this correction
        // the track comes out stretched east-west.
        val lonScale = kotlin.math.cos(Math.toRadians(midLat))
        val xs = lons.map { it * lonScale }

        val minX = xs.min()
        val maxX = xs.max()
        val minY = lats.min()
        val maxY = lats.max()
        val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1e-9
        val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1e-9

        val inset = 12f
        val usableW = size.width - inset * 2
        val usableH = size.height - inset * 2
        // One scale for both axes keeps the route's true proportions.
        val scale = minOf(usableW / spanX, usableH / spanY).toFloat()
        val offsetX = inset + (usableW - (spanX * scale).toFloat()) / 2f
        val offsetY = inset + (usableH - (spanY * scale).toFloat()) / 2f

        fun project(index: Int): Offset = Offset(
            offsetX + ((xs[index] - minX) * scale).toFloat(),
            // Latitude increases north, screen y increases downward.
            offsetY + ((maxY - lats[index]) * scale).toFloat(),
        )

        val path = Path()
        path.moveTo(project(0).x, project(0).y)
        for (i in 1 until points.size) {
            val p = project(i)
            path.lineTo(p.x, p.y)
        }
        drawPath(path, color = color.copy(alpha = 0.9f), style = Stroke(width = 3f, cap = StrokeCap.Round))

        drawCircle(color = com.mohid.obd2dash.ui.theme.ZoneGood, radius = 5f, center = project(0))
        drawCircle(color = com.mohid.obd2dash.ui.theme.ZoneDanger, radius = 5f, center = project(points.lastIndex))
    }
}

private fun formatValue(value: Float, decimals: Int): String =
    if (decimals == 0) value.roundToInt().toString() else "%.${decimals}f".format(value)

fun formatElapsed(millis: Long): String {
    val totalSeconds = abs(millis) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
