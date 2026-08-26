package com.mohid.obd2dash.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.TextPrimary
import kotlin.math.cos
import kotlin.math.sin

/** A coloured band on the dial, in metric units. */
data class GaugeZone(
    val from: Float,
    val to: Float,
    val color: Color,
)

private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f

/** Major graduations around the dial, with four minor ticks between each. */
private const val MAJOR_TICKS = 4
private const val MINOR_PER_MAJOR = 4

/**
 * A large, glanceable dial.
 *
 * Built up in layers rather than drawn as one arc, because that is what stops it
 * looking like a progress bar bent into a circle:
 *
 *  - a slim coloured strip sits *outside* the track, the way a redline is
 *    printed on a real tachometer, instead of tinting the track itself
 *  - the lit portion carries a bloom, drawn as a few concentric strokes at
 *    falling alpha, which is far cheaper than a real blur and reads the same
 *  - the needle is a tapered blade rather than a line, and only occupies the
 *    outer third so it never crosses the digital readout
 *
 * The needle is tweened linearly over roughly one poll interval so it slides
 * continuously between samples. An eased animation reads as a stutter at this
 * sample rate.
 */
@Composable
fun MetricGauge(
    label: String,
    value: Float?,
    unit: String,
    min: Float,
    max: Float,
    zones: List<GaugeZone>,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    animationMillis: Int = 400,
    /**
     * Where the lit arc is measured from. Defaults to the bottom of the scale,
     * which is right for a tachometer. Boost sets it to zero so vacuum fills
     * anticlockwise and positive boost fills clockwise, the way the needle
     * actually behaves on a turbo car.
     */
    origin: Float? = null,
) {
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val target = ((value ?: min) - min) / span
    val fraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = animationMillis, easing = LinearEasing),
        label = "gauge-$label",
    )

    // With no reading there is no zone to be in, so the dial stays neutral
    // rather than borrowing the colour of whichever band happens to be last.
    val activeColor = when {
        value == null -> TextMuted
        else -> zones.firstOrNull { value >= it.from && value < it.to }?.color
            ?: zones.lastOrNull()?.color
            ?: MaterialTheme.colorScheme.primary
    }
    val textMeasurer = rememberTextMeasurer()

    // The 240 degree sweep leaves the bottom of the circle empty, so the dial's
    // real bounding box is wider than it is tall. Matching that ratio, and
    // pushing the centre down accordingly, stops every gauge carrying a band of
    // dead space underneath it.
    BoxWithConstraints(modifier = modifier.aspectRatio(1.30f)) {
        val boxHeight = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.width * 0.062f
            val radius = (size.width - stroke) / 2f * 0.90f
            val center = Offset(size.width / 2f, radius + stroke * 1.5f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            drawDialFace(center, radius, stroke)
            drawZoneStrip(zones, min, span, center, radius, stroke)

            // Unlit track, slightly inset from the zone strip.
            drawArc(
                color = Color.White.copy(alpha = 0.055f),
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (value != null) {
                val originFraction = origin?.let { ((it - min) / span).coerceIn(0f, 1f) } ?: 0f
                val from = minOf(originFraction, fraction)
                val to = maxOf(originFraction, fraction)
                if (to - from > 0.001f) {
                    drawLitArc(center, topLeft, arcSize, stroke, from, to, activeColor)
                }
            }

            // A dial that fills from somewhere other than the bottom needs to
            // show where that somewhere is, or the lit arc reads as arbitrary.
            origin?.let {
                drawOriginMark(center, radius, stroke, ((it - min) / span).coerceIn(0f, 1f))
            }

            drawTicks(center, radius, stroke, min, max, textMeasurer, activeColor, value != null)

            if (value != null) {
                drawNeedle(center, radius, stroke, fraction, activeColor)
            }
        }

        // The readout sits in the open middle of the dial face, above the
        // needle's arc. Type scales with the gauge so two-up on a phone and a
        // single large dial both stay legible.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = boxHeight * 0.07f),
        ) {
            Text(
                text = valueText ?: value?.let { formatDefault(it) } ?: "--",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = (boxHeight.value * 0.175f).sp,
                    lineHeight = (boxHeight.value * 0.19f).sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = if (value == null) TextMuted else TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = TextMuted,
                    maxLines = 1,
                )
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = activeColor.copy(alpha = 0.95f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A soft sheen across the dial face, so the middle is not a flat void. */
private fun DrawScope.drawDialFace(center: Offset, radius: Float, stroke: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.030f),
                Color.White.copy(alpha = 0.008f),
                Color.Transparent,
            ),
            center = center.copy(y = center.y - radius * 0.25f),
            radius = radius * 1.05f,
        ),
        radius = radius - stroke * 0.5f,
        center = center,
    )
}

/**
 * The warning and danger bands, printed as a thin strip outside the track the
 * way a redline is marked on a real dial. Kept off the track itself so the lit
 * portion stays a single clean colour.
 */
private fun DrawScope.drawZoneStrip(
    zones: List<GaugeZone>,
    min: Float,
    span: Float,
    center: Offset,
    radius: Float,
    stroke: Float,
) {
    val stripRadius = radius + stroke * 0.80f
    val stripTopLeft = Offset(center.x - stripRadius, center.y - stripRadius)
    val stripSize = Size(stripRadius * 2, stripRadius * 2)

    for (zone in zones) {
        val from = ((zone.from - min) / span).coerceIn(0f, 1f)
        val to = ((zone.to - min) / span).coerceIn(0f, 1f)
        if (to - from < 0.004f) continue

        // The healthy band is context, not information, so it stays a whisper.
        val alpha = if (zone.color == com.mohid.obd2dash.ui.theme.ZoneGood) 0.20f else 0.85f
        drawArc(
            color = zone.color.copy(alpha = alpha),
            startAngle = START_ANGLE + from * SWEEP_ANGLE,
            sweepAngle = (to - from) * SWEEP_ANGLE,
            useCenter = false,
            topLeft = stripTopLeft,
            size = stripSize,
            style = Stroke(width = stroke * 0.16f, cap = StrokeCap.Butt),
        )
    }
}

/**
 * The lit portion, with a bloom underneath.
 *
 * The glow is three concentric strokes at falling alpha rather than a real
 * blur. BlurMaskFilter needs a software layer and would cost far more than this
 * is worth on something that redraws several times a second.
 */
private fun DrawScope.drawLitArc(
    center: Offset,
    topLeft: Offset,
    arcSize: Size,
    stroke: Float,
    fromFraction: Float,
    toFraction: Float,
    color: Color,
) {
    val begin = START_ANGLE + fromFraction * SWEEP_ANGLE
    val sweep = (toFraction - fromFraction) * SWEEP_ANGLE

    val bloom = listOf(2.45f to 0.05f, 1.85f to 0.08f, 1.30f to 0.13f)
    for ((widthScale, alpha) in bloom) {
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = begin,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke * widthScale, cap = StrokeCap.Round),
        )
    }

    // Sweep gradients always start at 3 o'clock, so the canvas is rotated to
    // bring the dial's own origin under it rather than fighting the maths.
    rotate(degrees = begin, pivot = center) {
        drawArc(
            brush = Brush.sweepGradient(
                0.0f to color.copy(alpha = 0.45f),
                (SWEEP_ANGLE / 360f) * 0.55f to color.copy(alpha = 0.85f),
                (SWEEP_ANGLE / 360f) to color,
                1.0f to color,
                center = center,
            ),
            startAngle = 0f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** The datum the lit arc grows out of, marked across the full width of the track. */
private fun DrawScope.drawOriginMark(
    center: Offset,
    radius: Float,
    stroke: Float,
    fraction: Float,
) {
    val angle = Math.toRadians((START_ANGLE + fraction * SWEEP_ANGLE).toDouble())
    val cosA = cos(angle).toFloat()
    val sinA = sin(angle).toFloat()
    val inner = radius - stroke * 0.58f
    val outer = radius + stroke * 0.58f
    drawLine(
        color = Color.White.copy(alpha = 0.55f),
        start = Offset(center.x + cosA * inner, center.y + sinA * inner),
        end = Offset(center.x + cosA * outer, center.y + sinA * outer),
        strokeWidth = 2.2f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawTicks(
    center: Offset,
    radius: Float,
    stroke: Float,
    min: Float,
    max: Float,
    textMeasurer: TextMeasurer,
    accent: Color,
    hasValue: Boolean,
) {
    val labelStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 8.5.sp,
        color = TextMuted.copy(alpha = 0.75f),
    )
    val outer = radius - stroke * 0.60f
    val totalMinor = MAJOR_TICKS * MINOR_PER_MAJOR

    // Minor graduations first, so the major ones sit on top of them.
    for (i in 0..totalMinor) {
        if (i % MINOR_PER_MAJOR == 0) continue
        val f = i / totalMinor.toFloat()
        val angle = Math.toRadians((START_ANGLE + f * SWEEP_ANGLE).toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val inner = outer - stroke * 0.20f
        drawLine(
            color = Color.White.copy(alpha = 0.13f),
            start = Offset(center.x + cosA * inner, center.y + sinA * inner),
            end = Offset(center.x + cosA * outer, center.y + sinA * outer),
            strokeWidth = 1.4f,
        )
    }

    for (i in 0..MAJOR_TICKS) {
        val f = i / MAJOR_TICKS.toFloat()
        val angle = Math.toRadians((START_ANGLE + f * SWEEP_ANGLE).toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()

        val inner = outer - stroke * 0.42f
        drawLine(
            color = Color.White.copy(alpha = 0.30f),
            start = Offset(center.x + cosA * inner, center.y + sinA * inner),
            end = Offset(center.x + cosA * outer, center.y + sinA * outer),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )

        val text = formatTick(min + (max - min) * f, max - min)
        val layout = textMeasurer.measure(text, labelStyle)
        val labelRadius = inner - stroke * 0.62f
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                center.x + cosA * labelRadius - layout.size.width / 2f,
                center.y + sinA * labelRadius - layout.size.height / 2f,
            ),
        )
    }

    // A small pip at the pivot gives the needle something to sit against.
    drawCircle(
        color = if (hasValue) accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.08f),
        radius = stroke * 0.16f,
        center = center,
    )
}

/**
 * A tapered blade riding the outer third of the dial.
 *
 * A full length needle would sweep straight through the digital readout, and
 * the lit arc already carries the "how far along" reading, so the pointer only
 * has to mark the exact position.
 */
private fun DrawScope.drawNeedle(
    center: Offset,
    radius: Float,
    stroke: Float,
    fraction: Float,
    color: Color,
) {
    val angle = Math.toRadians((START_ANGLE + fraction * SWEEP_ANGLE).toDouble())
    val cosA = cos(angle).toFloat()
    val sinA = sin(angle).toFloat()

    val tip = radius - stroke * 0.72f
    val tail = radius - stroke * 2.35f
    val halfWidth = stroke * 0.17f

    // Perpendicular, for the blade's width at the tail.
    val perpX = -sinA
    val perpY = cosA

    val blade = Path().apply {
        moveTo(center.x + cosA * tip, center.y + sinA * tip)
        lineTo(center.x + cosA * tail + perpX * halfWidth, center.y + sinA * tail + perpY * halfWidth)
        lineTo(center.x + cosA * tail - perpX * halfWidth, center.y + sinA * tail - perpY * halfWidth)
        close()
    }

    // Dark backing so the blade reads against the lit arc it sits on.
    drawLine(
        color = Color.Black.copy(alpha = 0.55f),
        start = Offset(center.x + cosA * (tail - stroke * 0.1f), center.y + sinA * (tail - stroke * 0.1f)),
        end = Offset(center.x + cosA * tip, center.y + sinA * tip),
        strokeWidth = stroke * 0.52f,
        cap = StrokeCap.Round,
    )
    drawPath(blade, color = Color.White.copy(alpha = 0.92f))
    drawCircle(
        color = color,
        radius = stroke * 0.13f,
        center = Offset(center.x + cosA * tip, center.y + sinA * tip),
    )
}

private fun formatDefault(value: Float): String =
    if (kotlin.math.abs(value) >= 100f) value.toInt().toString() else "%.1f".format(value)

/**
 * Tick labels are read peripherally, so they round hard. Only a dial whose
 * whole span is under ten, boost in bar essentially, earns a decimal place.
 */
private fun formatTick(value: Float, span: Float): String = when {
    kotlin.math.abs(value) >= 1000f -> "%.0fk".format(value / 1000f)
    span >= 12f -> Math.round(value).toString()
    else -> "%.1f".format(value)
}
