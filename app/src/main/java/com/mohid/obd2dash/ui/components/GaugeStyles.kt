package com.mohid.obd2dash.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.ui.theme.Ink
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.TextPrimary
import com.mohid.obd2dash.ui.theme.ZoneGood
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/*
 * Four alternative dial faces, each modelled on a real instrument cluster.
 *
 * They deliberately disagree about more than colour. Sweep angle, needle
 * length, graduation style and where the number lives all change, because that
 * is what actually makes two dials look like different instruments rather than
 * one instrument in two paint schemes.
 */

// ---------------------------------------------------------------------------
// Hexa: after the Lamborghini Aventador cluster
// ---------------------------------------------------------------------------

private const val HEXA_START = 145f
private const val HEXA_SWEEP = 250f
private const val HEXA_MAJORS = 4
private const val HEXA_MINORS_PER_MAJOR = 5

/**
 * Angular and aggressive: a hexagonal bezel, wedge graduations that taper
 * inward, and a hard edged sweep with no rounded caps anywhere. The needle is
 * a thin dagger riding the outer half, with a floating hub ring in the middle
 * so the centre still reads as a pivot without the pointer crossing the number.
 */
@Composable
internal fun HexaGauge(state: GaugeState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.aspectRatio(1.14f)) {
        val boxHeight = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val center = Offset(w / 2f, size.height / 2f)
            val radius = w * 0.385f
            val hexRadius = radius * 1.22f
            val track = w * 0.030f

            // The bezel. Two hexagons, the inner one picking up the live colour,
            // which is what carries the state when the arc itself is this thin.
            val outerHex = hexPath(center, hexRadius)
            drawPath(
                outerHex,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF161D25), Color(0xFF090C10)),
                    startY = center.y - hexRadius,
                    endY = center.y + hexRadius,
                ),
            )
            drawPath(outerHex, color = Color.White.copy(alpha = 0.10f), style = Stroke(width = 2.4f))
            drawPath(
                hexPath(center, hexRadius * 0.935f),
                color = state.accent.copy(alpha = if (state.hasValue) 0.26f else 0.07f),
                style = Stroke(width = 1.3f),
            )

            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            // Unlit track, then the zone bands painted straight onto it. A face
            // this spare has nowhere else to put a redline.
            drawArc(
                color = Color.White.copy(alpha = 0.06f),
                startAngle = HEXA_START,
                sweepAngle = HEXA_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = track, cap = StrokeCap.Butt),
            )
            for (zone in state.zones) {
                if (zone.healthy) continue
                val from = ((zone.from - state.min) / state.span).coerceIn(0f, 1f)
                val to = ((zone.to - state.min) / state.span).coerceIn(0f, 1f)
                if (to - from < 0.004f) continue
                drawArc(
                    color = zone.color.copy(alpha = 0.30f),
                    startAngle = HEXA_START + from * HEXA_SWEEP,
                    sweepAngle = (to - from) * HEXA_SWEEP,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = track, cap = StrokeCap.Butt),
                )
            }

            if (state.hasValue) {
                val origin = state.originFraction ?: 0f
                val lo = minOf(origin, state.fraction)
                val hi = maxOf(origin, state.fraction)
                if (hi - lo > 0.001f) {
                    val begin = HEXA_START + lo * HEXA_SWEEP
                    val sweep = (hi - lo) * HEXA_SWEEP
                    bloomArc(
                        topLeft, arcSize, begin, sweep, track, state.accent,
                        cap = StrokeCap.Butt,
                        passes = listOf(3.4f to 0.05f, 2.2f to 0.09f, 1.5f to 0.14f),
                    )
                    drawArc(
                        color = state.accent,
                        startAngle = begin,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = track, cap = StrokeCap.Butt),
                    )
                }
            }

            state.originFraction?.let { origin ->
                val angle = HEXA_START + origin * HEXA_SWEEP
                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = polar(center, radius - track, angle),
                    end = polar(center, radius + track, angle),
                    strokeWidth = 2f,
                )
            }

            // Wedge graduations, pointing in from just inside the track.
            val tickOuter = radius - track * 1.05f
            val total = HEXA_MAJORS * HEXA_MINORS_PER_MAJOR
            for (i in 0..total) {
                val angle = HEXA_START + (i / total.toFloat()) * HEXA_SWEEP
                val major = i % HEXA_MINORS_PER_MAJOR == 0
                drawWedge(
                    center = center,
                    degrees = angle,
                    outerRadius = tickOuter,
                    length = if (major) w * 0.050f else w * 0.024f,
                    halfWidthDegrees = if (major) 1.5f else 0.6f,
                    color = Color.White.copy(alpha = if (major) 0.42f else 0.16f),
                )
            }

            val labelStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (radius * 0.107f).toSp(),
                color = TextMuted.copy(alpha = 0.85f),
            )
            for (i in 0..HEXA_MAJORS) {
                val f = i / HEXA_MAJORS.toFloat()
                val layout = measurer.measure(state.tickLabel(f), labelStyle)
                val at = polar(center, tickOuter - w * 0.105f, HEXA_START + f * HEXA_SWEEP)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
                )
            }

            // A floating hub ring instead of a pivot, since the needle starts
            // well outside the centre to keep clear of the readout.
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = radius * 0.34f,
                center = center,
                style = Stroke(width = 1.2f),
            )

            if (state.hasValue) {
                drawDagger(
                    center = center,
                    degrees = HEXA_START + state.fraction * HEXA_SWEEP,
                    from = radius * 0.78f,
                    to = radius - track * 0.9f,
                    halfWidth = w * 0.013f,
                    color = state.accent,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = state.label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 2.4.sp,
                ),
                color = state.accent.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = state.valueText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = (boxHeight.value * 0.150f).sp,
                    lineHeight = (boxHeight.value * 0.165f).sp,
                    letterSpacing = (-1).sp,
                ),
                color = if (state.hasValue) TextPrimary else TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (state.unit.isNotEmpty()) {
                Text(
                    text = state.unit.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        letterSpacing = 1.6.sp,
                    ),
                    color = TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A hexagon with its points at left and right, which is the way the car wears it. */
private fun hexPath(center: Offset, radius: Float): Path = Path().apply {
    for (i in 0 until 6) {
        val p = polar(center, radius, i * 60f)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

/** A graduation drawn as a triangle rather than a line, so it tapers inward. */
private fun DrawScope.drawWedge(
    center: Offset,
    degrees: Float,
    outerRadius: Float,
    length: Float,
    halfWidthDegrees: Float,
    color: Color,
) {
    val a = polar(center, outerRadius, degrees - halfWidthDegrees)
    val b = polar(center, outerRadius, degrees + halfWidthDegrees)
    val tip = polar(center, outerRadius - length, degrees)
    drawPath(
        Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(tip.x, tip.y)
            close()
        },
        color = color,
    )
}

/** A thin blade that is widest at its base and comes to a point at the tip. */
private fun DrawScope.drawDagger(
    center: Offset,
    degrees: Float,
    from: Float,
    to: Float,
    halfWidth: Float,
    color: Color,
) {
    val radians = Math.toRadians(degrees.toDouble())
    val perpX = -sin(radians).toFloat()
    val perpY = cos(radians).toFloat()
    val base = polar(center, from, degrees)
    val tip = polar(center, to, degrees)

    drawPath(
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(base.x + perpX * halfWidth, base.y + perpY * halfWidth)
            lineTo(base.x - perpX * halfWidth, base.y - perpY * halfWidth)
            close()
        },
        color = Color.White.copy(alpha = 0.95f),
    )
    drawCircle(color = color, radius = halfWidth * 0.9f, center = tip)
}

// ---------------------------------------------------------------------------
// Heritage: after the Porsche 911 five dial cluster
// ---------------------------------------------------------------------------

private const val HERITAGE_START = 145f
private const val HERITAGE_SWEEP = 250f
private const val HERITAGE_MAJORS = 8
private const val HERITAGE_MINORS_PER_MAJOR = 5

/** The off white the needles and numerals are printed in on the real thing. */
private val Cream = Color(0xFFF2E9D8)

/**
 * The material the Heritage bezel is machined from. The layout, numerals and
 * needle stay identical across all four; only what the metal looks like
 * changes, the same way a watch line sells one movement in four cases.
 */
internal enum class HeritageFinish(val label: String, val blurb: String) {
    STEEL(
        "Heritage — Steel",
        "Brushed stainless, warm under the cream dial. The original.",
    ),
    GUNMETAL(
        "Heritage — Gunmetal",
        "Dark graphite bezel with almost no shine. Reads as understated, tactical.",
    ),
    TITANIUM(
        "Heritage — Titanium",
        "Cooler and lighter than steel, with a faint blue cast under the sweep.",
    ),
    CARBON(
        "Heritage — Carbon",
        "A woven carbon fibre bezel instead of polished metal, under a glossy clear coat.",
    ),
    ;

    /** The sweep gradient stops the bezel ring is painted with. */
    val bezelColors: List<Color>
        get() = when (this) {
            STEEL -> listOf(
                Color(0xFF7C858F), Color(0xFF2B3138), Color(0xFF98A1AB),
                Color(0xFF31383F), Color(0xFF6B747E), Color(0xFF20262C), Color(0xFF7C858F),
            )
            GUNMETAL -> listOf(
                Color(0xFF4B4E52), Color(0xFF16181A), Color(0xFF5C6064),
                Color(0xFF1C1E20), Color(0xFF3D4043), Color(0xFF121314), Color(0xFF4B4E52),
            )
            TITANIUM -> listOf(
                Color(0xFFAEB7C2), Color(0xFF4A535F), Color(0xFFC7CFD8),
                Color(0xFF515A66), Color(0xFF95A0AC), Color(0xFF3A424C), Color(0xFFAEB7C2),
            )
            // Carbon draws as a flat near-black base; the weave is layered on
            // top of it separately rather than faked with a gradient.
            CARBON -> listOf(Color(0xFF17181A), Color(0xFF0B0C0D))
        }

    /** The hub picks up a duller version of the same material. */
    val hubColors: List<Color>
        get() = when (this) {
            STEEL -> listOf(Color(0xFF8A939D), Color(0xFF2A3037), Color(0xFF8A939D))
            GUNMETAL -> listOf(Color(0xFF585C60), Color(0xFF16181A), Color(0xFF585C60))
            TITANIUM -> listOf(Color(0xFFB7C0CA), Color(0xFF3A424C), Color(0xFFB7C0CA))
            CARBON -> listOf(Color(0xFF2A2C2E), Color(0xFF0B0C0D), Color(0xFF2A2C2E))
        }

    val woven: Boolean get() = this == CARBON
}

/**
 * The traditional analogue instrument: a metal bezel, numerals printed on a
 * black face, dense graduations, and a full length needle with a counterweight
 * swinging from a hub in the middle.
 *
 * The digital readout lives in a small window at the bottom of the face, in the
 * dead space the 250 degree sweep leaves behind, so the needle never crosses
 * it. The needle stays cream while everything is healthy and picks up the alert
 * colour when it is not, which keeps the glanceable colour coding without
 * spoiling the look the rest of the time.
 */
@Composable
internal fun HeritageGauge(state: GaugeState, modifier: Modifier, finish: HeritageFinish = HeritageFinish.STEEL) {
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.aspectRatio(1f)) {
        val boxHeight = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val w = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = w * 0.47f
            val bezel = w * 0.032f

            // Brushed metal, faked with a sweep gradient rather than a bitmap.
            // Carbon skips the sweep for a woven texture instead, laid down below.
            drawCircle(
                brush = Brush.sweepGradient(colors = finish.bezelColors, center = center),
                radius = radius,
                center = center,
                style = Stroke(width = bezel),
            )
            if (finish.woven) {
                drawCarbonWeave(center = center, radius = radius, ringWidth = bezel)
            }
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = radius - bezel * 0.85f,
                center = center,
                style = Stroke(width = bezel * 0.55f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1C232B), Color(0xFF0B0F14), Color(0xFF05070A)),
                    center = center.copy(y = center.y - radius * 0.35f),
                    radius = radius * 1.25f,
                ),
                radius = radius - bezel * 1.1f,
                center = center,
            )

            val gradOuter = radius - bezel * 2.0f

            // The redline, printed on the face just inside the graduations.
            for (zone in state.zones) {
                if (zone.healthy) continue
                val from = ((zone.from - state.min) / state.span).coerceIn(0f, 1f)
                val to = ((zone.to - state.min) / state.span).coerceIn(0f, 1f)
                if (to - from < 0.004f) continue
                val stripR = gradOuter + bezel * 0.55f
                drawArc(
                    color = zone.color.copy(alpha = 0.9f),
                    startAngle = HERITAGE_START + from * HERITAGE_SWEEP,
                    sweepAngle = (to - from) * HERITAGE_SWEEP,
                    useCenter = false,
                    topLeft = Offset(center.x - stripR, center.y - stripR),
                    size = Size(stripR * 2, stripR * 2),
                    style = Stroke(width = w * 0.018f, cap = StrokeCap.Butt),
                )
            }

            val total = HERITAGE_MAJORS * HERITAGE_MINORS_PER_MAJOR
            for (i in 0..total) {
                val angle = HERITAGE_START + (i / total.toFloat()) * HERITAGE_SWEEP
                val major = i % HERITAGE_MINORS_PER_MAJOR == 0
                drawLine(
                    color = if (major) Cream.copy(alpha = 0.85f) else Cream.copy(alpha = 0.30f),
                    start = polar(center, gradOuter - if (major) w * 0.052f else w * 0.026f, angle),
                    end = polar(center, gradOuter, angle),
                    strokeWidth = if (major) w * 0.011f else w * 0.005f,
                )
            }

            val numeralStyle = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = (boxHeight.value * 0.072f).sp,
                color = Cream.copy(alpha = 0.92f),
            )
            for (i in 0..HERITAGE_MAJORS) {
                val f = i / HERITAGE_MAJORS.toFloat()
                // Eight majors is the right graduation density for the look, but
                // eight numerals is a thicket, so only every other one is printed.
                if (i % 2 != 0) continue
                val layout = measurer.measure(state.tickLabel(f), numeralStyle)
                val at = polar(center, gradOuter - w * 0.115f, HERITAGE_START + f * HERITAGE_SWEEP)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
                )
            }

            // The digital window, sunk into the lower face.
            val windowSize = Size(radius * 0.78f, radius * 0.28f)
            val windowTopLeft = Offset(
                center.x - windowSize.width / 2f,
                center.y + radius * 0.38f,
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = windowTopLeft,
                size = windowSize,
                cornerRadius = CornerRadius(w * 0.018f, w * 0.018f),
            )
            drawRoundRect(
                color = Cream.copy(alpha = 0.16f),
                topLeft = windowTopLeft,
                size = windowSize,
                cornerRadius = CornerRadius(w * 0.018f, w * 0.018f),
                style = Stroke(width = 1.2f),
            )

            if (state.hasValue) {
                val needleColor = if (state.alarm) state.accent else Cream
                drawSweepNeedle(
                    center = center,
                    degrees = HERITAGE_START + state.fraction * HERITAGE_SWEEP,
                    tipRadius = gradOuter - w * 0.012f,
                    tailRadius = radius * 0.17f,
                    halfWidth = w * 0.016f,
                    color = needleColor,
                )
            }

            // Hub, over the needle so the pivot reads as one piece of hardware.
            drawCircle(
                brush = Brush.sweepGradient(colors = finish.hubColors, center = center),
                radius = w * 0.042f,
                center = center,
            )
            drawCircle(color = Color(0xFF11161C), radius = w * 0.026f, center = center)
            drawCircle(
                color = if (state.hasValue) state.accent.copy(alpha = 0.8f) else Cream.copy(alpha = 0.25f),
                radius = w * 0.010f,
                center = center,
            )
        }

        // Metric name, printed high on the face the way a real dial is captioned.
        Text(
            text = state.label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                letterSpacing = 1.8.sp,
            ),
            color = Cream.copy(alpha = 0.55f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -boxHeight * 0.165f),
        )

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = boxHeight * 0.245f),
        ) {
            Text(
                text = state.valueText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = (boxHeight.value * 0.098f).sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = if (state.hasValue) state.accent else TextMuted,
                maxLines = 1,
            )
            if (state.unit.isNotEmpty()) {
                Text(
                    text = state.unit,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp),
                    color = TextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 3.dp, bottom = 1.dp),
                )
            }
        }
    }
}

/**
 * A woven carbon fibre look for the Heritage bezel, laid over its flat near
 * black base coat.
 *
 * Real carbon weave is a plain 2x2 twill: alternating light and dark squares
 * whose diagonal splits flip direction every tile, which is what actually
 * reads as "woven" rather than just "diagonally hatched." Drawn as small
 * alternating quads across the bezel's bounding box, clipped to the ring so
 * nothing spills onto the face, plus a soft diagonal sheen for the clear coat
 * over the top.
 */
private fun DrawScope.drawCarbonWeave(center: Offset, radius: Float, ringWidth: Float) {
    val outer = radius + ringWidth / 2f
    val inner = radius - ringWidth / 2f
    val ring = Path().apply {
        addOval(Rect(center = center, radius = outer))
        addOval(Rect(center = center, radius = inner))
        fillType = PathFillType.EvenOdd
    }

    clipPath(ring) {
        val tile = ringWidth * 0.30f
        val dark = Color(0xFF0C0D0E)
        val light = Color(0xFF212325)
        var row = 0
        var y = center.y - outer
        while (y < center.y + outer) {
            var col = 0
            var x = center.x - outer
            while (x < center.x + outer) {
                // The twill flip: every other tile in a checkerboard pattern
                // swaps which diagonal half is light, which is what makes the
                // weave look woven instead of just tiled.
                val flipped = (row + col) % 2 == 0
                val a = Offset(x, y)
                val b = Offset(x + tile, y)
                val c = Offset(x + tile, y + tile)
                val d = Offset(x, y + tile)
                drawPath(
                    Path().apply {
                        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
                    },
                    color = if (flipped) light else dark,
                )
                drawPath(
                    Path().apply {
                        moveTo(a.x, a.y); lineTo(d.x, d.y); lineTo(c.x, c.y); close()
                    },
                    color = if (flipped) dark else light,
                )
                x += tile
                col++
            }
            y += tile
            row++
        }

        // The glossy clear coat every real carbon panel is finished with.
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.16f),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.05f),
                ),
                start = Offset(center.x - outer, center.y - outer),
                end = Offset(center.x + outer, center.y + outer),
            ),
            topLeft = Offset(center.x - outer, center.y - outer),
            size = Size(outer * 2, outer * 2),
        )
    }
}

/** A full length pointer with a counterweight behind the pivot. */
private fun DrawScope.drawSweepNeedle(
    center: Offset,
    degrees: Float,
    tipRadius: Float,
    tailRadius: Float,
    halfWidth: Float,
    color: Color,
) {
    val radians = Math.toRadians(degrees.toDouble())
    val perpX = -sin(radians).toFloat()
    val perpY = cos(radians).toFloat()
    val tip = polar(center, tipRadius, degrees)
    val tail = polar(center, -tailRadius, degrees)

    // Shadow, thrown slightly off axis so the needle looks like it floats.
    drawLine(
        color = Color.Black.copy(alpha = 0.45f),
        start = Offset(tail.x + 2f, tail.y + 3f),
        end = Offset(tip.x + 2f, tip.y + 3f),
        strokeWidth = halfWidth * 1.6f,
        cap = StrokeCap.Round,
    )

    drawPath(
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(center.x + perpX * halfWidth, center.y + perpY * halfWidth)
            lineTo(center.x - perpX * halfWidth, center.y - perpY * halfWidth)
            close()
        },
        color = color,
    )
    drawLine(
        color = color,
        start = center,
        end = tail,
        strokeWidth = halfWidth * 1.9f,
        cap = StrokeCap.Round,
    )
}

// ---------------------------------------------------------------------------
// Cockpit: after the Audi virtual cockpit
// ---------------------------------------------------------------------------

private const val COCKPIT_START = 120f
private const val COCKPIT_SWEEP = 300f

/**
 * The opposite of Heritage. No needle, no bezel, no numerals: one hairline ring
 * with a puck riding it, and a number large enough to read without focusing.
 * Everything that is not information has been deleted, which is the whole point
 * of the instrument it is copying.
 */
@Composable
internal fun CockpitGauge(state: GaugeState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.aspectRatio(1.22f)) {
        val boxHeight = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val ring = w * 0.016f
            val radius = w * 0.42f
            val center = Offset(w / 2f, size.height * 0.537f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            drawArc(
                color = Color.White.copy(alpha = 0.09f),
                startAngle = COCKPIT_START,
                sweepAngle = COCKPIT_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = ring, cap = StrokeCap.Round),
            )

            // Zones are a hairline set outside the ring, not a tint on it. The
            // ring has to stay one flat colour or the whole look collapses.
            val stripR = radius + ring * 1.9f
            for (zone in state.zones) {
                if (zone.healthy) continue
                val from = ((zone.from - state.min) / state.span).coerceIn(0f, 1f)
                val to = ((zone.to - state.min) / state.span).coerceIn(0f, 1f)
                if (to - from < 0.004f) continue
                drawArc(
                    color = zone.color.copy(alpha = 0.75f),
                    startAngle = COCKPIT_START + from * COCKPIT_SWEEP,
                    sweepAngle = (to - from) * COCKPIT_SWEEP,
                    useCenter = false,
                    topLeft = Offset(center.x - stripR, center.y - stripR),
                    size = Size(stripR * 2, stripR * 2),
                    style = Stroke(width = ring * 0.42f, cap = StrokeCap.Butt),
                )
            }

            for (i in 1..3) {
                val angle = COCKPIT_START + (i / 4f) * COCKPIT_SWEEP
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = polar(center, radius + ring * 0.9f, angle),
                    end = polar(center, radius + ring * 2.4f, angle),
                    strokeWidth = 1.4f,
                )
            }

            if (state.hasValue) {
                val origin = state.originFraction ?: 0f
                val lo = minOf(origin, state.fraction)
                val hi = maxOf(origin, state.fraction)
                val begin = COCKPIT_START + lo * COCKPIT_SWEEP
                val sweep = (hi - lo) * COCKPIT_SWEEP
                if (sweep > 0.2f) {
                    bloomArc(
                        topLeft, arcSize, begin, sweep, ring, state.accent,
                        passes = listOf(3.0f to 0.06f, 1.9f to 0.10f),
                    )
                    drawArc(
                        color = state.accent,
                        startAngle = begin,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = ring, cap = StrokeCap.Round),
                    )
                }

                // The puck. This is the pointer, and the only heavy element on
                // the whole face, so it wants a halo to sell it.
                val at = polar(center, radius, COCKPIT_START + state.fraction * COCKPIT_SWEEP)
                drawCircle(color = state.accent.copy(alpha = 0.16f), radius = ring * 2.9f, center = at)
                drawCircle(color = state.accent, radius = ring * 1.45f, center = at)
                drawCircle(color = Ink, radius = ring * 0.55f, center = at)
            }

            state.originFraction?.let { origin ->
                val angle = COCKPIT_START + origin * COCKPIT_SWEEP
                drawLine(
                    color = Color.White.copy(alpha = 0.45f),
                    start = polar(center, radius - ring * 1.6f, angle),
                    end = polar(center, radius + ring * 1.6f, angle),
                    strokeWidth = 1.6f,
                )
            }

            // Only the two ends are labelled. Anything more is clutter here.
            val endStyle = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = (radius * 0.092f).toSp(),
                color = TextMuted.copy(alpha = 0.6f),
            )
            listOf(0f, 1f).forEach { f ->
                val layout = measurer.measure(state.tickLabel(f), endStyle)
                val at = polar(center, radius - ring * 3.4f, COCKPIT_START + f * COCKPIT_SWEEP)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = boxHeight * 0.04f),
        ) {
            Text(
                text = state.label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    letterSpacing = 2.8.sp,
                ),
                color = TextMuted,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = state.valueText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    fontSize = (boxHeight.value * 0.235f).sp,
                    lineHeight = (boxHeight.value * 0.25f).sp,
                    letterSpacing = (-1.5).sp,
                ),
                color = if (state.hasValue) TextPrimary else TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (state.unit.isNotEmpty()) {
                Text(
                    text = state.unit,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                    ),
                    color = if (state.hasValue) state.accent.copy(alpha = 0.85f) else TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Circuit: after GT-R and race car shift displays
// ---------------------------------------------------------------------------

private const val CIRCUIT_START = 140f
private const val CIRCUIT_SWEEP = 260f
private const val CIRCUIT_SEGMENTS = 40

/** How far the peak marker falls per tick once its hold has expired. */
private const val PEAK_DECAY_PER_TICK = 0.010f
private const val PEAK_HOLD_MS = 1_200L
private const val PEAK_TICK_MS = 100L

/**
 * A shift light bar bent into an arc: discrete blocks that light in sequence,
 * each one coloured by the band it sits in, so the green to red ramp is part of
 * the scale rather than something the pointer does when it arrives.
 *
 * It also carries a peak marker, which holds the highest reading for just over
 * a second and then falls back. That is the one thing a segmented display can
 * do that a needle cannot, and on boost in particular it is genuinely useful:
 * the spike is over before you can look down at it.
 */
@Composable
internal fun CircuitGauge(state: GaugeState, modifier: Modifier) {
    val measurer = rememberTextMeasurer()

    // Read through a holder so the decay loop can sample the live fraction
    // without restarting every time it changes, and without the composition
    // itself doing the mutating.
    val live by rememberUpdatedState(state.fraction)
    val hasValue by rememberUpdatedState(state.hasValue)
    var peak by remember { mutableFloatStateOf(0f) }
    var holdUntil by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(PEAK_TICK_MS)
            if (!hasValue) {
                peak = 0f
                continue
            }
            val now = System.currentTimeMillis()
            if (live >= peak) {
                peak = live
                holdUntil = now + PEAK_HOLD_MS
            } else if (now > holdUntil) {
                peak = (peak - PEAK_DECAY_PER_TICK).coerceAtLeast(live)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.aspectRatio(1.16f)) {
        val boxHeight = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val radius = w * 0.40f
            val center = Offset(w / 2f, size.height * 0.522f)
            val block = w * 0.058f
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            val step = CIRCUIT_SWEEP / CIRCUIT_SEGMENTS
            val gap = step * 0.34f

            val origin = state.originFraction ?: 0f
            val lo = minOf(origin, state.fraction)
            val hi = maxOf(origin, state.fraction)

            for (i in 0 until CIRCUIT_SEGMENTS) {
                val mid = (i + 0.5f) / CIRCUIT_SEGMENTS
                val lit = state.hasValue && mid in lo..hi
                val bandColor = state.colorAt(mid)
                drawArc(
                    color = if (lit) bandColor else bandColor.copy(alpha = 0.085f),
                    startAngle = CIRCUIT_START + i * step + gap / 2f,
                    sweepAngle = step - gap,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = block, cap = StrokeCap.Butt),
                )
            }

            // One bloom pass across the whole lit run rather than per block,
            // which costs three draws instead of forty.
            if (state.hasValue && hi - lo > 0.001f) {
                bloomArc(
                    topLeft, arcSize,
                    CIRCUIT_START + lo * CIRCUIT_SWEEP,
                    (hi - lo) * CIRCUIT_SWEEP,
                    block, state.accent,
                    cap = StrokeCap.Butt,
                    passes = listOf(2.2f to 0.07f, 1.5f to 0.10f),
                )
            }

            if (state.hasValue && peak > lo + 0.012f) {
                val index = (peak * CIRCUIT_SEGMENTS).toInt().coerceIn(0, CIRCUIT_SEGMENTS - 1)
                drawArc(
                    color = Color.White.copy(alpha = 0.9f),
                    startAngle = CIRCUIT_START + index * step + gap / 2f,
                    sweepAngle = step - gap,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = block * 1.22f, cap = StrokeCap.Butt),
                )
            }

            state.originFraction?.let { o ->
                val angle = CIRCUIT_START + o * CIRCUIT_SWEEP
                drawLine(
                    color = Color.White.copy(alpha = 0.5f),
                    start = polar(center, radius - block * 0.85f, angle),
                    end = polar(center, radius + block * 0.85f, angle),
                    strokeWidth = 2f,
                )
            }

            // Structural hairline inside the blocks, and the two end labels.
            val innerR = radius - block * 0.92f
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = CIRCUIT_START,
                sweepAngle = CIRCUIT_SWEEP,
                useCenter = false,
                topLeft = Offset(center.x - innerR, center.y - innerR),
                size = Size(innerR * 2, innerR * 2),
                style = Stroke(width = 1.2f),
            )

            val endStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = (radius * 0.096f).toSp(),
                color = TextMuted.copy(alpha = 0.7f),
            )
            listOf(0f, 1f).forEach { f ->
                val layout = measurer.measure(state.tickLabel(f), endStyle)
                val at = polar(center, innerR - w * 0.055f, CIRCUIT_START + f * CIRCUIT_SWEEP)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f),
                )
            }

            drawCornerBrackets(size, w * 0.062f, state.accent.copy(alpha = if (state.hasValue) 0.35f else 0.12f))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = boxHeight * 0.025f),
        ) {
            Text(
                text = state.label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 1.6.sp,
                ),
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = state.valueText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = (boxHeight.value * 0.195f).sp,
                    lineHeight = (boxHeight.value * 0.21f).sp,
                    letterSpacing = (-1).sp,
                ),
                color = if (state.hasValue) TextPrimary else TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (state.unit.isNotEmpty()) {
                Text(
                    text = state.unit.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.sp,
                        letterSpacing = 1.4.sp,
                    ),
                    color = TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Four corner brackets, the way a racing display frames its readout. */
private fun DrawScope.drawCornerBrackets(canvas: Size, length: Float, color: Color) {
    val inset = length * 0.28f
    val width = 1.8f
    val corners = listOf(
        Triple(Offset(inset, inset), 1f, 1f),
        Triple(Offset(canvas.width - inset, inset), -1f, 1f),
        Triple(Offset(inset, canvas.height - inset), 1f, -1f),
        Triple(Offset(canvas.width - inset, canvas.height - inset), -1f, -1f),
    )
    for ((corner, dx, dy) in corners) {
        drawLine(
            color = color,
            start = corner,
            end = Offset(corner.x + length * dx, corner.y),
            strokeWidth = width,
        )
        drawLine(
            color = color,
            start = corner,
            end = Offset(corner.x, corner.y + length * dy),
            strokeWidth = width,
        )
    }
}
