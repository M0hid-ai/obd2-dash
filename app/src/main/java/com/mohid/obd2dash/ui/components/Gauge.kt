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
import com.mohid.obd2dash.data.GaugeSkin
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.TextPrimary
import com.mohid.obd2dash.ui.theme.ZoneGood
import com.mohid.obd2dash.ui.theme.ZoneWarn
import com.mohid.obd2dash.ui.theme.ZoneDanger
import kotlin.math.cos
import kotlin.math.sin

/** A coloured band on the dial, in metric units. */
data class GaugeZone(
    val from: Float,
    val to: Float,
    val color: Color,
    /**
     * Whether this is the "everything is fine" band, as opposed to a warning
     * or danger band. Tracked explicitly rather than inferred from the colour,
     * since the healthy colour itself is now a user choice: some faces treat
     * the healthy band as context rather than information and want to know
     * which one it is regardless of what colour it happens to be.
     */
    val healthy: Boolean = false,
)

/**
 * Everything a dial face needs in order to draw itself.
 *
 * The reading, the animation and the zone lookup are all resolved once in
 * [MetricGauge], so a face only has to decide how to paint them. That is what
 * makes swapping faces a one line change rather than five copies of the same
 * arithmetic.
 */
internal class GaugeState(
    val label: String,
    val valueText: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val span: Float,
    val value: Float?,
    val fraction: Float,
    val originFraction: Float?,
    val zones: List<GaugeZone>,
    val accent: Color,
) {
    val hasValue: Boolean get() = value != null

    /** True once the live reading has left the healthy band. */
    val alarm: Boolean get() = accent == ZoneWarn || accent == ZoneDanger

    /** The band colour at a position along the dial, for faces that ramp. */
    fun colorAt(position: Float): Color {
        val v = min + position * span
        return zones.firstOrNull { v >= it.from && v < it.to }?.color
            ?: zones.lastOrNull()?.color
            ?: ZoneGood
    }

    fun tickLabel(position: Float): String = formatTick(min + span * position, span)
}

/**
 * A large, glanceable dial.
 *
 * The face is chosen by [skin]. Each one is a different physical instrument
 * rather than a recolour of the same drawing: they disagree about sweep angle,
 * needle length, whether there is a needle at all, and where the number sits.
 * See [GaugeSkin] for what each is after.
 *
 * The pointer is tweened linearly over roughly one poll interval so it slides
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
     * Where the lit portion is measured from. Defaults to the bottom of the
     * scale, which is right for a tachometer. Boost sets it to zero so vacuum
     * fills one way and positive boost the other, the way the needle actually
     * behaves on a turbo car.
     */
    origin: Float? = null,
    skin: GaugeSkin = GaugeSkin.CLASSIC,
) {
    val span = (max - min).takeIf { it > 0f } ?: 1f
    val target = ((value ?: min) - min) / span
    val fraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(
            // Cap the tween so a slow adapter does not make the needle feel drunk.
            // Floor it so a 150ms poll still interpolates instead of snapping.
            durationMillis = animationMillis.coerceIn(90, 220),
            easing = LinearEasing,
        ),
        label = "gauge-$label",
    )

    // With no reading there is no zone to be in, so the dial stays neutral
    // rather than borrowing the colour of whichever band happens to be last.
    val accent = when {
        value == null -> TextMuted
        else -> zones.firstOrNull { value >= it.from && value < it.to }?.color
            ?: zones.lastOrNull()?.color
            ?: MaterialTheme.colorScheme.primary
    }

    val state = GaugeState(
        label = label,
        valueText = valueText ?: value?.let { formatDefault(it) } ?: "--",
        unit = unit,
        min = min,
        max = max,
        span = span,
        value = value,
        fraction = fraction,
        originFraction = origin?.let { ((it - min) / span).coerceIn(0f, 1f) },
        zones = zones,
        accent = accent,
    )

    when (skin) {
        GaugeSkin.HEXA -> HexaGauge(state, modifier)
        GaugeSkin.HERITAGE -> HeritageGauge(state, modifier, HeritageFinish.STEEL)
        GaugeSkin.HERITAGE_GUNMETAL -> HeritageGauge(state, modifier, HeritageFinish.GUNMETAL)
        GaugeSkin.HERITAGE_TITANIUM -> HeritageGauge(state, modifier, HeritageFinish.TITANIUM)
        GaugeSkin.HERITAGE_CARBON -> HeritageGauge(state, modifier, HeritageFinish.CARBON)
        GaugeSkin.COCKPIT -> CockpitGauge(state, modifier)
        GaugeSkin.CIRCUIT -> CircuitGauge(state, modifier)
        // SHOWCASE is resolved to a real face before it ever reaches here.
        // Falling back rather than throwing keeps a stale setting harmless.
        GaugeSkin.CLASSIC, GaugeSkin.SHOWCASE -> ClassicGauge(state, modifier)
    }
}

// ---------------------------------------------------------------------------
// Shared geometry and formatting
// ---------------------------------------------------------------------------

/** A point on a circle, in the canvas convention where zero degrees is 3 o'clock. */
internal fun polar(center: Offset, radius: Float, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(
        center.x + cos(radians).toFloat() * radius,
        center.y + sin(radians).toFloat() * radius,
    )
}

internal fun formatDefault(value: Float): String =
    if (kotlin.math.abs(value) >= 100f) value.toInt().toString() else "%.1f".format(value)

/**
 * Tick labels are read peripherally, so they round hard. Only a dial whose
 * whole span is under ten, boost in bar essentially, earns a decimal place.
 */
internal fun formatTick(value: Float, span: Float): String = when {
    kotlin.math.abs(value) >= 1000f -> "%.0fk".format(value / 1000f)
    span >= 12f -> Math.round(value).toString()
    else -> "%.1f".format(value)
}

/** The bloom under a lit arc, as concentric strokes at falling alpha. */
internal fun DrawScope.bloomArc(
    topLeft: Offset,
    arcSize: Size,
    startAngle: Float,
    sweepAngle: Float,
    stroke: Float,
    color: Color,
    cap: StrokeCap = StrokeCap.Round,
    passes: List<Pair<Float, Float>> = listOf(2.45f to 0.05f, 1.85f to 0.08f, 1.30f to 0.13f),
) {
    for ((widthScale, alpha) in passes) {
        drawArc(
            color = color.copy(alpha = alpha),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke * widthScale, cap = cap),
        )
    }
}

// ---------------------------------------------------------------------------
// Classic: the face this app shipped with
// ---------------------------------------------------------------------------

private const val START_ANGLE = 150f
private const val SWEEP_ANGLE = 240f

/** Major graduations around the dial, with four minor ticks between each. */
private const val MAJOR_TICKS = 4
private const val MINOR_PER_MAJOR = 4

/**
 * Built up in layers rather than drawn as one arc, because that is what stops
 * it looking like a progress bar bent into a circle:
 *
 *  - a slim coloured strip sits *outside* the track, the way a redline is
 *    printed on a real tachometer, instead of tinting the track itself
 *  - the lit portion carries a bloom, drawn as a few concentric strokes at
 *    falling alpha, which is far cheaper than a real blur and reads the same
 *  - the needle is a tapered blade rather than a line, and only occupies the
 *    outer third so it never crosses the digital readout
 */
@Composable
private fun ClassicGauge(state: GaugeState, modifier: Modifier) {
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
            drawZoneStrip(state, center, radius, stroke)

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

            if (state.hasValue) {
                val originFraction = state.originFraction ?: 0f
                val from = minOf(originFraction, state.fraction)
                val to = maxOf(originFraction, state.fraction)
                if (to - from > 0.001f) {
                    drawLitArc(center, topLeft, arcSize, stroke, from, to, state.accent)
                }
            }

            // A dial that fills from somewhere other than the bottom needs to
            // show where that somewhere is, or the lit arc reads as arbitrary.
            state.originFraction?.let { drawOriginMark(center, radius, stroke, it) }

            drawClassicTicks(state, center, radius, stroke, textMeasurer)

            if (state.hasValue) {
                drawClassicNeedle(center, radius, stroke, state.fraction, state.accent)
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
                text = state.valueText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = (boxHeight.value * 0.175f).sp,
                    lineHeight = (boxHeight.value * 0.19f).sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = if (state.hasValue) TextPrimary else TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (state.unit.isNotEmpty()) {
                Text(
                    text = state.unit,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = TextMuted,
                    maxLines = 1,
                )
            }
            Text(
                text = state.label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = state.accent.copy(alpha = 0.95f),
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
    state: GaugeState,
    center: Offset,
    radius: Float,
    stroke: Float,
) {
    val stripRadius = radius + stroke * 0.80f
    val stripTopLeft = Offset(center.x - stripRadius, center.y - stripRadius)
    val stripSize = Size(stripRadius * 2, stripRadius * 2)

    for (zone in state.zones) {
        val from = ((zone.from - state.min) / state.span).coerceIn(0f, 1f)
        val to = ((zone.to - state.min) / state.span).coerceIn(0f, 1f)
        if (to - from < 0.004f) continue

        // The healthy band is context, not information, so it stays a whisper.
        val alpha = if (zone.healthy) 0.20f else 0.85f
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

    bloomArc(topLeft, arcSize, begin, sweep, stroke, color)

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
    val angle = START_ANGLE + fraction * SWEEP_ANGLE
    drawLine(
        color = Color.White.copy(alpha = 0.55f),
        start = polar(center, radius - stroke * 0.58f, angle),
        end = polar(center, radius + stroke * 0.58f, angle),
        strokeWidth = 2.2f,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawClassicTicks(
    state: GaugeState,
    center: Offset,
    radius: Float,
    stroke: Float,
    textMeasurer: TextMeasurer,
) {
    // Sized off the dial rather than fixed, so a small preview copy of this
    // face crowds its numerals no worse than the full size one does.
    val labelStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = (radius * 0.107f).toSp(),
        color = TextMuted.copy(alpha = 0.75f),
    )
    val outer = radius - stroke * 0.60f
    val totalMinor = MAJOR_TICKS * MINOR_PER_MAJOR

    // Minor graduations first, so the major ones sit on top of them.
    for (i in 0..totalMinor) {
        if (i % MINOR_PER_MAJOR == 0) continue
        val angle = START_ANGLE + (i / totalMinor.toFloat()) * SWEEP_ANGLE
        drawLine(
            color = Color.White.copy(alpha = 0.13f),
            start = polar(center, outer - stroke * 0.20f, angle),
            end = polar(center, outer, angle),
            strokeWidth = 1.4f,
        )
    }

    for (i in 0..MAJOR_TICKS) {
        val f = i / MAJOR_TICKS.toFloat()
        val angle = START_ANGLE + f * SWEEP_ANGLE
        val inner = outer - stroke * 0.42f
        drawLine(
            color = Color.White.copy(alpha = 0.30f),
            start = polar(center, inner, angle),
            end = polar(center, outer, angle),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )

        val layout = textMeasurer.measure(state.tickLabel(f), labelStyle)
        val at = polar(center, inner - stroke * 0.62f, angle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
        )
    }

    // A small pip at the pivot gives the needle something to sit against.
    drawCircle(
        color = if (state.hasValue) state.accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.08f),
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
private fun DrawScope.drawClassicNeedle(
    center: Offset,
    radius: Float,
    stroke: Float,
    fraction: Float,
    color: Color,
) {
    val angle = START_ANGLE + fraction * SWEEP_ANGLE
    val tipAt = polar(center, radius - stroke * 0.72f, angle)
    val tailCenter = polar(center, radius - stroke * 2.35f, angle)
    val halfWidth = stroke * 0.17f

    // Perpendicular, for the blade's width at the tail.
    val radians = Math.toRadians(angle.toDouble())
    val perpX = -sin(radians).toFloat()
    val perpY = cos(radians).toFloat()

    val blade = Path().apply {
        moveTo(tipAt.x, tipAt.y)
        lineTo(tailCenter.x + perpX * halfWidth, tailCenter.y + perpY * halfWidth)
        lineTo(tailCenter.x - perpX * halfWidth, tailCenter.y - perpY * halfWidth)
        close()
    }

    // Dark backing so the blade reads against the lit arc it sits on.
    drawLine(
        color = Color.Black.copy(alpha = 0.55f),
        start = polar(center, radius - stroke * 2.45f, angle),
        end = tipAt,
        strokeWidth = stroke * 0.52f,
        cap = StrokeCap.Round,
    )
    drawPath(blade, color = Color.White.copy(alpha = 0.92f))
    drawCircle(color = color, radius = stroke * 0.13f, center = tipAt)
}
