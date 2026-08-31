package com.mohid.obd2dash.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneGood
import kotlin.math.cos
import kotlin.math.sin

/**
 * Premium Automotive Push/Start/Stop Ignition Switch.
 *
 * Inspired by luxury and performance sports car ignition controls (Porsche, Audi RS,
 * AMG, BMW M):
 *  - Bold, commanding circular footprint (default diameter: 84.dp)
 *  - 48-tooth precision machined knurled grip ring with paired highlight/shadow micro-teeth
 *  - Multi-stage brushed titanium/aluminum beveled bezel with 360° sweep reflections
 *  - Deep, lustrous concave obsidian face with radial depth
 *  - Multi-layer backlit laser-etched power glyph with ambient backlight bloom
 *  - Smooth breathing standby illumination when connected & ready
 *  - Vibrant emerald glow during active trip recording
 *  - Tactile 3D spring depression and haptic feedback on press
 */
@Composable
fun PushStartButton(
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 84.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current

    // Tactile 3D mechanical stroke: crisp depression on tap
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "push-start-scale",
    )

    // Smooth luxury standby pulse when connected and ready to start
    val infiniteTransition = rememberInfiniteTransition(label = "ignition-pulse")
    val standbyPulse by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0.98f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "standby-heartbeat",
    )

    val (glowColor, glowAlpha) = when {
        !enabled -> TextMuted.copy(alpha = 0.22f) to 0.16f
        running -> ZoneGood to 0.98f
        else -> Color.White.copy(alpha = 0.85f) to standbyPulse
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter + 20.dp)
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

            // 1. OUTER CHASSIS & MOUNTING RING
            drawOuterMountRing(center, radius)

            // 2. PRECISION 48-TOOTH MACHINED KNURLED GRIP RING
            drawPrecisionKnurling(center, radius)

            // 3. MULTI-STAGE BRUSHED TITANIUM BEZEL
            val bezelRadius = radius * 0.84f
            drawBrushedBezel(center, bezelRadius)

            // 4. DEEP LUSTROUS CONCAVE OBSIDIAN BUTTON FACE
            val faceRadius = bezelRadius * 0.82f
            drawObsidianFace(center, faceRadius)

            // 5. MULTI-LAYER BACKLIT GLOW & POWER GLYPH
            drawPowerGlyph(center, faceRadius, glowColor, glowAlpha, enabled, running)
        }
    }
}

/**
 * Draws the outer mounting rim with a dark chamfer and subtle drop shadow.
 */
private fun DrawScope.drawOuterMountRing(center: Offset, radius: Float) {
    // Outer drop shadow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x66000000), Color(0xFF000000)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )

    // Outer dark chassis ring
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF323842), Color(0xFF161A20), Color(0xFF0E1116)),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
        radius = radius * 0.98f,
        center = center,
    )

    // Specular outer lip highlight
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF7A8898).copy(alpha = 0.5f), Color(0xFF1B2028).copy(alpha = 0.8f)),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
        radius = radius * 0.98f,
        center = center,
        style = Stroke(width = radius * 0.025f),
    )
}

/**
 * Draws the 48-tooth precision machined knurling: paired light and shadow
 * micro-teeth creating realistic 3D tactile grip texture around the perimeter.
 */
private fun DrawScope.drawPrecisionKnurling(center: Offset, radius: Float) {
    val notches = 48
    val innerRadius = radius * 0.86f
    val toothWidth = radius * 0.042f

    for (i in 0 until notches) {
        val angle = i * 360f / notches
        val rad = Math.toRadians(angle.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        // Highlight stroke (catching light from top-left)
        val highlightAlpha = (0.20f + 0.30f * ((-cosA - sinA) / 2f + 0.5f)).coerceIn(0.12f, 0.50f)
        drawLine(
            color = Color.White.copy(alpha = highlightAlpha),
            start = Offset(center.x + cosA * innerRadius, center.y + sinA * innerRadius),
            end = Offset(center.x + cosA * (radius * 0.96f), center.y + sinA * (radius * 0.96f)),
            strokeWidth = toothWidth * 0.6f,
            cap = StrokeCap.Round,
        )

        // Shadow groove stroke on adjacent side
        val radOffset = Math.toRadians(angle.toDouble() + 360.0 / (notches * 2.5))
        val cosB = cos(radOffset).toFloat()
        val sinB = sin(radOffset).toFloat()
        drawLine(
            color = Color(0xFF080A0D),
            start = Offset(center.x + cosB * innerRadius, center.y + sinB * innerRadius),
            end = Offset(center.x + cosB * (radius * 0.96f), center.y + sinB * (radius * 0.96f)),
            strokeWidth = toothWidth * 0.45f,
            cap = StrokeCap.Round,
        )
    }

    // Groove dividing knurling from inner bezel
    drawCircle(
        color = Color(0xFF080A0D),
        radius = innerRadius,
        center = center,
        style = Stroke(width = radius * 0.035f),
    )
}

/**
 * Draws the multi-stage brushed titanium/aluminum bezel with metallic sweep
 * gradients and specular edge highlights.
 */
private fun DrawScope.drawBrushedBezel(center: Offset, bezelRadius: Float) {
    // Brushed titanium sweep gradient
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(
                Color(0xFF9EABB8), Color(0xFF38404B), Color(0xFFA8B6C4),
                Color(0xFF282F38), Color(0xFF8A97A5), Color(0xFF20262E),
                Color(0xFF9EABB8),
            ),
            center = center,
        ),
        radius = bezelRadius,
        center = center,
    )

    // Inner bright chamfer ring
    val chamferRadius = bezelRadius * 0.94f
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFCED8E4), Color(0xFF424C58), Color(0xFF1E242C)),
            start = Offset(center.x - chamferRadius, center.y - chamferRadius),
            end = Offset(center.x + chamferRadius, center.y + chamferRadius),
        ),
        radius = chamferRadius,
        center = center,
        style = Stroke(width = bezelRadius * 0.035f),
    )
}

/**
 * Draws the deep concave obsidian/matte black button face with radial depth.
 */
private fun DrawScope.drawObsidianFace(center: Offset, faceRadius: Float) {
    // Recessed drop shadow groove
    drawCircle(
        color = Color(0xFF050608),
        radius = faceRadius * 1.02f,
        center = center,
        style = Stroke(width = faceRadius * 0.05f),
    )

    // Concave dish face
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF282D35),
                Color(0xFF14171D),
                Color(0xFF07090C),
            ),
            center = center.copy(y = center.y - faceRadius * 0.35f),
            radius = faceRadius * 1.35f,
        ),
        radius = faceRadius,
        center = center,
    )
}

/**
 * Draws the multi-layer backlit laser-etched power glyph (arc + vertical stem)
 * with ambient bloom and crisp foreground illumination.
 */
private fun DrawScope.drawPowerGlyph(
    center: Offset,
    faceRadius: Float,
    glowColor: Color,
    glowAlpha: Float,
    enabled: Boolean,
    running: Boolean,
) {
    // 1. Deep diffuse backlight bloom
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glowColor.copy(alpha = glowAlpha * 0.38f),
                glowColor.copy(alpha = glowAlpha * 0.12f),
                Color.Transparent,
            ),
            center = center,
            radius = faceRadius * 0.92f,
        ),
        radius = faceRadius * 0.92f,
        center = center,
    )

    // Power glyph geometry: bolder, refined proportions
    val glyphRadius = faceRadius * 0.44f
    val strokeWidth = faceRadius * 0.135f
    val glowStrokeWidth = strokeWidth * 1.6f

    // 2. Ambient glow stroke behind the glyph
    drawArc(
        color = glowColor.copy(alpha = glowAlpha * 0.40f),
        startAngle = -230f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(center.x - glyphRadius, center.y - glyphRadius),
        size = Size(glyphRadius * 2f, glyphRadius * 2f),
        style = Stroke(width = glowStrokeWidth, cap = StrokeCap.Round),
    )
    drawLine(
        color = glowColor.copy(alpha = glowAlpha * 0.40f),
        start = Offset(center.x, center.y - glyphRadius * 1.34f),
        end = Offset(center.x, center.y - glyphRadius * 0.12f),
        strokeWidth = glowStrokeWidth,
        cap = StrokeCap.Round,
    )

    // 3. Crisp laser-etched foreground stroke
    val foregroundAlpha = if (enabled) glowAlpha else 0.22f
    drawArc(
        color = glowColor.copy(alpha = foregroundAlpha),
        startAngle = -230f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(center.x - glyphRadius, center.y - glyphRadius),
        size = Size(glyphRadius * 2f, glyphRadius * 2f),
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    drawLine(
        color = glowColor.copy(alpha = foregroundAlpha),
        start = Offset(center.x, center.y - glyphRadius * 1.34f),
        end = Offset(center.x, center.y - glyphRadius * 0.12f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

/**
 * Supercar-styled cockpit caption printed underneath the ignition switch.
 */
@Composable
fun PushStartCaption(
    running: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val (statusDotColor, statusText) = when {
        !enabled -> TextMuted.copy(alpha = 0.4f) to "PUSH TO START"
        running -> ZoneGood to "PUSH TO STOP"
        else -> Color.White.copy(alpha = 0.9f) to "PUSH TO START"
    }

    Row(
        modifier = modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(statusDotColor),
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                letterSpacing = 1.3.sp,
                fontFamily = FontFamily.Monospace,
            ),
            fontWeight = FontWeight.Bold,
            color = if (enabled) {
                if (running) ZoneGood else TextMuted
            } else {
                TextMuted.copy(alpha = 0.4f)
            },
        )
    }
}


