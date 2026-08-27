package com.mohid.obd2dash.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneGood
import kotlin.math.cos
import kotlin.math.sin

/**
 * The ignition button real cars have had since keys stopped being mandatory:
 * a knurled metal ring, a glossy black face, and a backlit power glyph. Trip
 * recording is the closest thing this app has to "the engine is running," so
 * it borrows the metaphor rather than using an ordinary Material button.
 *
 * The glyph and its backlight read grey when there is nothing to start yet,
 * glow steady green while a trip is recording, and the whole button dips
 * slightly on press, the same tactile confirmation the real switch gives you.
 */
@Composable
fun PushStartButton(
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 64.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "push-start-scale",
    )

    val glow = when {
        !enabled -> TextMuted.copy(alpha = 0.18f)
        running -> ZoneGood
        else -> Color.White.copy(alpha = 0.55f)
    }
    val glowAlpha = if (enabled) (if (running) 0.95f else 0.7f) else 0.18f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter + 22.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            ),
    ) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .scale(pressScale),
        ) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // The knurled outer ring: short radial notches, the grip texture
            // on the real button rather than a smooth bezel.
            val notches = 36
            for (i in 0 until notches) {
                val angle = i * 360f / notches
                val rad = Math.toRadians(angle.toDouble())
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()
                val inner = radius - radius * 0.11f
                drawLine(
                    color = Color.White.copy(alpha = 0.16f),
                    start = Offset(center.x + cosA * inner, center.y + sinA * inner),
                    end = Offset(center.x + cosA * radius, center.y + sinA * radius),
                    strokeWidth = radius * 0.045f,
                )
            }

            // Brushed metal bezel, just inside the knurling.
            val bezelRadius = radius * 0.86f
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF8A939D), Color(0xFF32383F), Color(0xFF9AA3AC),
                        Color(0xFF262C33), Color(0xFF7C858F), Color(0xFF20262C),
                        Color(0xFF8A939D),
                    ),
                    center = center,
                ),
                radius = bezelRadius,
                center = center,
            )

            // Glossy black face.
            val faceRadius = bezelRadius * 0.82f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF2A2E33), Color(0xFF0A0C0E)),
                    center = center.copy(y = center.y - faceRadius * 0.4f),
                    radius = faceRadius * 1.4f,
                ),
                radius = faceRadius,
                center = center,
            )

            // Backlight glow, seated behind the glyph.
            drawCircle(color = glow.copy(alpha = glowAlpha * 0.30f), radius = faceRadius * 0.95f, center = center)

            // The power glyph: a broken ring with a stem through the gap,
            // exactly what is printed on the real button.
            val glyphRadius = faceRadius * 0.44f
            drawArc(
                color = glow.copy(alpha = glowAlpha),
                startAngle = -230f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(center.x - glyphRadius, center.y - glyphRadius),
                size = Size(glyphRadius * 2, glyphRadius * 2),
                style = Stroke(width = faceRadius * 0.12f, cap = StrokeCap.Round),
            )
            drawLine(
                color = glow.copy(alpha = glowAlpha),
                start = Offset(center.x, center.y - glyphRadius * 1.35f),
                end = Offset(center.x, center.y - glyphRadius * 0.15f),
                strokeWidth = faceRadius * 0.12f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The small caption a real ignition button is labelled with, printed underneath. */
@Composable
fun PushStartCaption(running: Boolean, enabled: Boolean) {
    Text(
        text = if (running) "PUSH TO STOP" else "PUSH TO START",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 1.2.sp),
        fontWeight = FontWeight.Bold,
        color = if (enabled) TextMuted else TextMuted.copy(alpha = 0.4f),
    )
}
