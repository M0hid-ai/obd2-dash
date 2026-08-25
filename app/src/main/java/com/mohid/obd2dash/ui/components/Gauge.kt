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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.ui.theme.Hairline
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

/**
 * A large, glanceable dial.
 *
 * Two details do the real work here. The needle is tweened linearly over
 * roughly one poll interval, so it slides continuously between samples instead
 * of snapping and then sitting still. An eased animation reads as a stutter at
 * this sample rate. And the healthy/warning/danger bands are painted into the
 * dial itself, so "is this bad" is answered by where the needle is, without
 * reading the number.
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

    // The 240° sweep leaves the bottom of the circle empty, so the dial's real
    // bounding box is wider than it is tall. Matching that ratio, and pushing
    // the centre down accordingly, stops every gauge carrying a band of dead
    // space underneath it.
    BoxWithConstraints(modifier = modifier.aspectRatio(1.32f)) {
        val boxHeight = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.width * 0.072f
            val radius = (size.width - stroke) / 2f * 0.94f
            val center = Offset(size.width / 2f, radius + stroke)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            // Unlit track.
            drawArc(
                color = Hairline,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // Coloured bands, dim until the needle reaches them.
            for (zone in zones) {
                val from = ((zone.from - min) / span).coerceIn(0f, 1f)
                val to = ((zone.to - min) / span).coerceIn(0f, 1f)
                if (to <= from) continue
                drawArc(
                    color = zone.color.copy(alpha = 0.26f),
                    startAngle = START_ANGLE + from * SWEEP_ANGLE,
                    sweepAngle = (to - from) * SWEEP_ANGLE,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
            }

            if (value != null) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to activeColor.copy(alpha = 0.55f),
                        1f to activeColor,
                        center = center,
                    ),
                    startAngle = START_ANGLE,
                    sweepAngle = fraction * SWEEP_ANGLE,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            drawTicks(center, radius, stroke, min, max, textMeasurer)

            if (value != null) {
                drawNeedle(center, radius, stroke, fraction, activeColor)
            }
        }

        // The readout sits in the open middle of the dial face, above the
        // needle's pivot. Type scales with the gauge so two-up on a phone and
        // a single large dial both stay legible.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = boxHeight * 0.06f),
        ) {
            Text(
                text = valueText ?: value?.let { formatDefault(it) } ?: "--",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = (boxHeight.value * 0.165f).sp,
                    lineHeight = (boxHeight.value * 0.19f).sp,
                ),
                color = if (value == null) TextMuted else TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                )
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = activeColor.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

private fun DrawScope.drawTicks(
    center: Offset,
    radius: Float,
    stroke: Float,
    min: Float,
    max: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val steps = 4
    val labelStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        color = TextMuted,
    )
    for (i in 0..steps) {
        val f = i / steps.toFloat()
        val angle = Math.toRadians((START_ANGLE + f * SWEEP_ANGLE).toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()

        val outer = radius - stroke * 0.62f
        val inner = outer - stroke * 0.30f
        drawLine(
            color = Hairline,
            start = Offset(center.x + cosA * inner, center.y + sinA * inner),
            end = Offset(center.x + cosA * outer, center.y + sinA * outer),
            strokeWidth = 2f,
        )

        val labelValue = min + (max - min) * f
        val text = formatTick(labelValue, max - min)
        val layout = textMeasurer.measure(text, labelStyle)
        // Hugs the inside of the track, leaving the middle clear for the readout.
        val labelRadius = inner - stroke * 0.50f
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                center.x + cosA * labelRadius - layout.size.width / 2f,
                center.y + sinA * labelRadius - layout.size.height / 2f,
            ),
        )
    }
}

/**
 * A short pointer riding just inside the track rather than a full-length
 * needle: a needle sweeping to the pivot would cut straight through the digital
 * readout, and the filled arc already carries the "how far along" reading.
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
    val tip = radius - stroke * 0.62f
    val tail = radius - stroke * 2.6f

    drawLine(
        color = Color.Black.copy(alpha = 0.85f),
        start = Offset(center.x + cosA * tail, center.y + sinA * tail),
        end = Offset(center.x + cosA * tip, center.y + sinA * tip),
        strokeWidth = stroke * 0.42f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = Offset(center.x + cosA * tail, center.y + sinA * tail),
        end = Offset(center.x + cosA * tip, center.y + sinA * tip),
        strokeWidth = stroke * 0.22f,
        cap = StrokeCap.Round,
    )
}

private fun formatDefault(value: Float): String =
    if (kotlin.math.abs(value) >= 100f) value.toInt().toString() else "%.1f".format(value)

/**
 * Tick labels are read peripherally, so they round hard. Only a dial whose
 * whole span is under ten (boost in bar, essentially) earns a decimal place.
 */
private fun formatTick(value: Float, span: Float): String = when {
    kotlin.math.abs(value) >= 1000f -> "%.0fk".format(value / 1000f)
    span >= 12f -> Math.round(value).toString()
    else -> "%.1f".format(value)
}
