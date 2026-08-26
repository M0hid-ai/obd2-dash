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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.data.SeriesPoint
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.TextPrimary
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneGood
import com.mohid.obd2dash.ui.theme.ZoneWarn
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LEFT_GUTTER = 46f
private const val BOTTOM_GUTTER = 20f

/**
 * A trip's time series.
 *
 * Drawn directly rather than pulled in from a charting library. The whole
 * requirement is one line, a filled area and a scrubber, and hand drawing keeps
 * the visual language identical to the gauges. A generic charting library would
 * bring its own business-dashboard look that no amount of theming really
 * removes.
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
    val lo = minValue - span * 0.14f
    val hi = maxValue + span * 0.14f
    val duration = points.last().elapsedMs.takeIf { it > 0 } ?: 1L
    val peakIndex = points.indices.maxByOrNull { points[it].value } ?: 0

    Box(
        // Horizontal only, deliberately: a general drag detector would swallow
        // vertical swipes and make the report impossible to scroll past.
        modifier = modifier
            .pointerInput(points) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrubIndex = indexAt(o.x, size.width, points.size) },
                    onDragEnd = { scrubIndex = null },
                    onDragCancel = { scrubIndex = null },
                ) { change, _ ->
                    scrubIndex = indexAt(change.position.x, size.width, points.size)
                }
            }
            .pointerInput(points) {
                detectTapGestures { o -> scrubIndex = indexAt(o.x, size.width, points.size) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val plotWidth = size.width - LEFT_GUTTER
            val plotHeight = size.height - BOTTOM_GUTTER

            fun xFor(index: Int) =
                LEFT_GUTTER + (points[index].elapsedMs.toFloat() / duration) * plotWidth

            fun yFor(v: Float) = plotHeight - ((v - lo) / (hi - lo)) * plotHeight

            val gridStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 8.5.sp,
                color = TextMuted.copy(alpha = 0.7f),
            )
            val dashed = PathEffect.dashPathEffect(floatArrayOf(3f, 7f), 0f)

            for (i in 0..3) {
                val f = i / 3f
                val y = plotHeight * f
                drawLine(
                    color = Color.White.copy(alpha = 0.055f),
                    start = Offset(LEFT_GUTTER, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = dashed,
                )
                val layout = textMeasurer.measure(formatValue(hi - (hi - lo) * f, decimals), gridStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        LEFT_GUTTER - layout.size.width - 7f,
                        y - layout.size.height / 2f,
                    ),
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
                    0.0f to color.copy(alpha = 0.32f),
                    0.55f to color.copy(alpha = 0.10f),
                    1.0f to Color.Transparent,
                    endY = plotHeight,
                ),
            )

            // Same bloom trick as the gauges: concentric strokes at falling
            // alpha, which costs a fraction of a real blur.
            for ((w, a) in listOf(5.5f to 0.06f, 3.4f to 0.10f)) {
                drawPath(
                    path = line,
                    color = color.copy(alpha = a),
                    style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            drawPath(
                path = line,
                color = color,
                style = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // The peak is the number people actually go looking for, so it is
            // marked rather than left to be hunted with the scrubber.
            if (scrubIndex == null && points.size > 4) {
                val px = xFor(peakIndex)
                val py = yFor(points[peakIndex].value)
                drawCircle(color = color.copy(alpha = 0.25f), radius = 6.5f, center = Offset(px, py))
                drawCircle(color = color, radius = 3f, center = Offset(px, py))
                val label = "${formatValue(points[peakIndex].value, decimals)} $unit"
                chip(
                    textMeasurer = textMeasurer,
                    text = label,
                    anchorX = px,
                    topY = (py - 22f).coerceAtLeast(2f),
                    maxX = size.width,
                    accent = color,
                )
            }

            for (i in 0..2) {
                val f = i / 2f
                val layout = textMeasurer.measure(formatElapsed((duration * f).toLong()), gridStyle)
                val x = (LEFT_GUTTER + f * plotWidth - layout.size.width / 2f)
                    .coerceIn(LEFT_GUTTER, size.width - layout.size.width)
                drawText(textLayoutResult = layout, topLeft = Offset(x, plotHeight + 6f))
            }

            scrubIndex?.let { raw ->
                val index = raw.coerceIn(points.indices)
                val point = points[index]
                val x = xFor(index)
                val y = yFor(point.value)
                drawLine(
                    color = Color.White.copy(alpha = 0.28f),
                    start = Offset(x, 0f),
                    end = Offset(x, plotHeight),
                    strokeWidth = 1.2f,
                )
                drawCircle(color = color.copy(alpha = 0.3f), radius = 8f, center = Offset(x, y))
                drawCircle(color = color, radius = 3.5f, center = Offset(x, y))
                chip(
                    textMeasurer = textMeasurer,
                    text = "${formatValue(point.value, decimals)} $unit  ${formatElapsed(point.elapsedMs)}",
                    anchorX = x,
                    topY = 2f,
                    maxX = size.width,
                    accent = color,
                )
            }
        }
    }
}

/** A small rounded readout pinned near a point on the plot. */
private fun DrawScope.chip(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    anchorX: Float,
    topY: Float,
    maxX: Float,
    accent: Color,
) {
    val layout = textMeasurer.measure(
        text,
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        ),
    )
    val padH = 6f
    val boxW = layout.size.width + padH * 2
    val x = (anchorX - boxW / 2f).coerceIn(0f, (maxX - boxW).coerceAtLeast(0f))

    drawRoundRect(
        color = Color(0xFF0B1016).copy(alpha = 0.92f),
        topLeft = Offset(x, topY),
        size = Size(boxW, layout.size.height + 5f),
        cornerRadius = CornerRadius(5f, 5f),
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.45f),
        topLeft = Offset(x, topY),
        size = Size(boxW, layout.size.height + 5f),
        cornerRadius = CornerRadius(5f, 5f),
        style = Stroke(width = 1f),
    )
    drawText(textLayoutResult = layout, topLeft = Offset(x + padH, topY + 2.5f))
}

private fun indexAt(x: Float, width: Int, count: Int): Int {
    if (count <= 1 || width <= 0) return 0
    val f = ((x - LEFT_GUTTER) / (width - LEFT_GUTTER)).coerceIn(0f, 1f)
    return (f * (count - 1)).roundToInt()
}

/** One logged position, with the speed recorded at that moment. */
data class RouteSample(
    val latitude: Double,
    val longitude: Double,
    val speedKph: Float?,
)

/**
 * The GPS track, with the line coloured by how fast you were going.
 *
 * No Maps SDK: a basemap needs an API key committed to the repo and a network
 * round trip to draw a route the phone already recorded. Colouring by speed
 * turns the shape of the drive into something you can actually read, which a
 * plain grey line on a map never is.
 */
@Composable
fun RouteTrace(
    rawSamples: List<RouteSample>,
    modifier: Modifier = Modifier,
) {
    // Trips logged before stale fixes were rejected still hold the odd
    // teleport, and one of those stretches the bounding box so far that the
    // real drive collapses into a single dot.
    val samples = remember(rawSamples) { longestCoherentRun(rawSamples) }
    val lats = samples.map { it.latitude }
    val lons = samples.map { it.longitude }
    // Roughly three metres of latitude. Below that the "route" is a parked car
    // with GPS jitter, and stretching it to fill the box would be a lie.
    val stationary = samples.size < 2 ||
        ((lats.max() - lats.min()) < 3e-5 && (lons.max() - lons.min()) < 3e-5)

    if (stationary) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (samples.isEmpty()) "No GPS track for this trip" else "No movement recorded",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val topSpeed = samples.mapNotNull { it.speedKph }.maxOrNull() ?: 0f

    Canvas(modifier) {
        val midLat = (lats.min() + lats.max()) / 2.0
        // A degree of longitude shrinks with latitude; without this correction
        // the track comes out stretched east to west.
        val lonScale = kotlin.math.cos(Math.toRadians(midLat))
        val xs = lons.map { it * lonScale }

        val minX = xs.min()
        val maxY = lats.max()
        val spanX = (xs.max() - minX).takeIf { it > 1e-9 } ?: 1e-9
        val spanY = (maxY - lats.min()).takeIf { it > 1e-9 } ?: 1e-9

        val inset = 18f
        val legendRoom = if (topSpeed > 0f) 16f else 0f
        val usableW = size.width - inset * 2
        val usableH = size.height - inset * 2 - legendRoom
        // One scale for both axes keeps the route's true proportions.
        val scale = minOf(usableW / spanX, usableH / spanY).toFloat()
        val offsetX = inset + (usableW - (spanX * scale).toFloat()) / 2f
        val offsetY = inset + (usableH - (spanY * scale).toFloat()) / 2f

        fun project(i: Int) = Offset(
            offsetX + ((xs[i] - minX) * scale).toFloat(),
            // Latitude increases north, screen y increases downward.
            offsetY + ((maxY - lats[i]) * scale).toFloat(),
        )

        // Glow pass under the whole track.
        val full = Path().apply {
            moveTo(project(0).x, project(0).y)
            for (i in 1 until samples.size) {
                val p = project(i)
                lineTo(p.x, p.y)
            }
        }
        for ((w, a) in listOf(9f to 0.05f, 5.5f to 0.09f)) {
            drawPath(
                full,
                color = Color(0xFF35D0E0).copy(alpha = a),
                style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // Then each segment in its own speed colour.
        for (i in 1 until samples.size) {
            val a = project(i - 1)
            val b = project(i)
            drawLine(
                color = speedColor(samples[i].speedKph, topSpeed),
                start = a,
                end = b,
                strokeWidth = 3.2f,
                cap = StrokeCap.Round,
            )
        }

        val start = project(0)
        val end = project(samples.lastIndex)
        drawCircle(color = ZoneGood.copy(alpha = 0.28f), radius = 8f, center = start)
        drawCircle(color = ZoneGood, radius = 3.5f, center = start)
        drawCircle(color = ZoneDanger.copy(alpha = 0.28f), radius = 8f, center = end)
        drawCircle(color = ZoneDanger, radius = 3.5f, center = end)

        if (topSpeed > 0f) {
            drawSpeedLegend(textMeasurer, topSpeed, size.width, size.height)
        }
    }
}

/** Consecutive fixes are about a second apart, so this far apart cannot have happened. */
private const val MAX_ROUTE_STEP_M = 2_000.0

/**
 * Keeps the longest unbroken stretch of the track.
 *
 * Filtering forward from the first sample would be wrong: when it is the very
 * first fix that is bogus, every real point afterwards looks like the outlier.
 * Taking the longest run instead lets the genuine drive outvote the stray.
 */
private fun longestCoherentRun(samples: List<RouteSample>): List<RouteSample> {
    if (samples.size < 3) return samples
    var bestStart = 0
    var bestLength = 1
    var runStart = 0
    for (i in 1 until samples.size) {
        if (roughMetresBetween(samples[i - 1], samples[i]) > MAX_ROUTE_STEP_M) {
            if (i - runStart > bestLength) {
                bestLength = i - runStart
                bestStart = runStart
            }
            runStart = i
        }
    }
    if (samples.size - runStart > bestLength) {
        bestLength = samples.size - runStart
        bestStart = runStart
    }
    return samples.subList(bestStart, bestStart + bestLength)
}

private fun roughMetresBetween(a: RouteSample, b: RouteSample): Double {
    val midLat = Math.toRadians((a.latitude + b.latitude) / 2)
    val dLat = (a.latitude - b.latitude) * 111_320.0
    val dLon = (a.longitude - b.longitude) * 111_320.0 * kotlin.math.cos(midLat)
    return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
}

/**
 * Cool to hot by speed. Stops short of pure red so it never reads as an alert;
 * on a route a warm colour means quick, not wrong.
 */
private fun speedColor(speedKph: Float?, topSpeed: Float): Color {
    if (speedKph == null || topSpeed <= 0f) return Color(0xFF35D0E0)
    val f = (speedKph / topSpeed).coerceIn(0f, 1f)
    return when {
        f < 0.5f -> lerp(Color(0xFF2A8BF2), Color(0xFF35D0E0), f / 0.5f)
        else -> lerp(Color(0xFF35D0E0), ZoneWarn, (f - 0.5f) / 0.5f)
    }
}

private fun DrawScope.drawSpeedLegend(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    topSpeed: Float,
    width: Float,
    height: Float,
) {
    val barW = (width * 0.34f).coerceAtMost(140f)
    val barH = 3.5f
    val x = width - barW - 14f
    val y = height - 12f

    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color(0xFF2A8BF2), Color(0xFF35D0E0), ZoneWarn),
            startX = x,
            endX = x + barW,
        ),
        topLeft = Offset(x, y),
        size = Size(barW, barH),
        cornerRadius = CornerRadius(2f, 2f),
    )

    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        color = TextMuted.copy(alpha = 0.85f),
    )
    val zero = textMeasurer.measure("0", style)
    drawText(textLayoutResult = zero, topLeft = Offset(x - zero.size.width - 5f, y - 5f))
    val top = textMeasurer.measure("${topSpeed.roundToInt()} km/h", style)
    drawText(textLayoutResult = top, topLeft = Offset(x + barW + 5f, y - 5f))
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
