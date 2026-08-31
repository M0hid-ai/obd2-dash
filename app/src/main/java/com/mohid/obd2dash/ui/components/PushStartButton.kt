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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneGood
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lamborghini-inspired Engine Start / Stop Ignition Switch.
 *
 * Modeled after the iconic fighter-jet missile launch switch found in modern
 * Lamborghini supercars (Aventador, Huracán, Revuelto, Urus):
 *  - High-tensile titanium hexagonal outer chassis with Allen hex bolts
 *  - Recessed carbon fiber weave backing dish
 *  - Anodized Rosso Mars (racing red) flip-guard safety cage with top hinge
 *    and tactile grip ridges
 *  - 36-tooth precision knurled outer ring on the inner ignition switch
 *  - Laser-etched backlit "ENGINE START / STOP" typography with halo glow
 *  - Lamborghini "Heartbeat" (Battito Cardiaco) breathing pulse animation
 *    when connected and armed for ignition
 *  - 3D tactile mechanical stroke response on press
 */
@Composable
fun PushStartButton(
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 92.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()

    // Tactile press depression: scale down and dip physically into bezel
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "lambo-press-scale",
    )

    // Lamborghini "Heartbeat" pulse (Battito Cardiaco) when armed & ready to start
    val infiniteTransition = rememberInfiniteTransition(label = "lambo-ignition-pulse")
    val heartbeatPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lambo-heartbeat",
    )

    val (glowColor, glowAlpha) = when {
        !enabled -> TextMuted.copy(alpha = 0.25f) to 0.18f
        running -> ZoneGood to 0.96f
        else -> Color(0xFFFF3344) to heartbeatPulse
    }

    // Outer container provides touch target and subtle ambient elevation
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter + 24.dp)
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
            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)
            val radius = size.minDimension / 2f

            // 1. OUTER HEXAGONAL CHASSIS (Lamborghini Cockpit Mount)
            drawHexChassis(center, radius)

            // 2. RECESSED CARBON FIBER WEAVE DISH
            drawCarbonFiberDish(center, radius * 0.84f)

            // 3. ANODIZED ROSSO MARS SAFETY CAGE / FLIP-GUARD
            drawLamborghiniSafetyCage(center, radius, glowColor, glowAlpha, enabled, running)

            // 4. INNER IGNITION BUTTON SWITCH CORE
            drawIgnitionSwitchCore(
                center = center,
                coreRadius = radius * 0.48f,
                glowColor = glowColor,
                glowAlpha = glowAlpha,
                running = running,
                enabled = enabled,
                textMeasurer = textMeasurer,
            )
        }
    }
}

/**
 * Draws the titanium/gunmetal hexagonal outer cockpit chassis with chamfered
 * specular bevels and Allen-head hex bolts at the vertices.
 */
private fun DrawScope.drawHexChassis(center: Offset, radius: Float) {
    val hexPath = createHexagonPath(center, radius * 0.98f)

    // Brushed titanium / gunmetal surface gradient
    val chassisBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF38404A),
            Color(0xFF22272E),
            Color(0xFF13171C),
            Color(0xFF282F38),
            Color(0xFF0F1216),
        ),
        start = Offset(center.x - radius, center.y - radius),
        end = Offset(center.x + radius, center.y + radius),
    )
    drawPath(path = hexPath, brush = chassisBrush)

    // Outer 3D bevel stroke
    val bevelBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF8E9BAA),
            Color(0xFF485360),
            Color(0xFF1E242C),
            Color(0xFF0A0C0E),
        ),
        start = Offset(center.x - radius, center.y - radius),
        end = Offset(center.x + radius, center.y + radius),
    )
    drawPath(
        path = hexPath,
        brush = bevelBrush,
        style = Stroke(width = radius * 0.045f, cap = StrokeCap.Round),
    )

    // 6 Precision Allen-Head Hex Screws at the Hexagon Vertices
    val boltDist = radius * 0.88f
    for (i in 0 until 6) {
        val angleDeg = i * 60f + 30f
        val rad = Math.toRadians(angleDeg.toDouble())
        val bx = center.x + boltDist * cos(rad).toFloat()
        val by = center.y + boltDist * sin(rad).toFloat()
        drawHexBolt(Offset(bx, by), radius * 0.055f)
    }
}

/**
 * Draws a single machined Allen/Torx machine screw with a countersunk bevel
 * and dark hexagonal keyway.
 */
private fun DrawScope.drawHexBolt(boltCenter: Offset, boltRadius: Float) {
    // Outer metallic bolt head with specular catch
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF9EAAB8), Color(0xFF454E5A), Color(0xFF1C2128)),
            center = boltCenter.copy(y = boltCenter.y - boltRadius * 0.3f),
            radius = boltRadius * 1.2f,
        ),
        radius = boltRadius,
        center = boltCenter,
    )
    // Dark hexagonal socket hole
    val socketPath = createHexagonPath(boltCenter, boltRadius * 0.55f)
    drawPath(path = socketPath, color = Color(0xFF07090C), style = Fill)
}

/**
 * Draws the recessed carbon fiber weave baseplate with fine crosshatch cloth lines
 * and deep perimeter shadow.
 */
private fun DrawScope.drawCarbonFiberDish(center: Offset, dishRadius: Float) {
    val dishPath = Path().apply {
        addOval(Rect(center.x - dishRadius, center.y - dishRadius, center.x + dishRadius, center.y + dishRadius))
    }

    clipPath(dishPath) {
        // Deep carbon background
        drawRect(color = Color(0xFF0C0F14), size = size)

        // Carbon weave crosshatch micro-stripes (+45° and -45°)
        val step = dishRadius * 0.12f
        val extent = dishRadius * 2f
        var x = -extent
        while (x <= extent * 2f) {
            drawLine(
                color = Color(0xFF181E27).copy(alpha = 0.7f),
                start = Offset(center.x + x - extent, center.y - extent),
                end = Offset(center.x + x + extent, center.y + extent),
                strokeWidth = step * 0.45f,
            )
            drawLine(
                color = Color(0xFF141920).copy(alpha = 0.6f),
                start = Offset(center.x + x + extent, center.y - extent),
                end = Offset(center.x + x - extent, center.y + extent),
                strokeWidth = step * 0.45f,
            )
            x += step
        }

        // Inner drop shadow vignette around dish perimeter
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color(0x99000000), Color(0xFF000000)),
                center = center,
                radius = dishRadius,
            ),
            radius = dishRadius,
            center = center,
        )
    }

    // Dish recessed border ring
    drawCircle(
        color = Color(0xFF222933),
        radius = dishRadius,
        center = center,
        style = Stroke(width = dishRadius * 0.035f),
    )
}

/**
 * Draws the signature Lamborghini Rosso Mars (anodized racing red) flip-guard
 * cover with its heavy-duty top hinge, tactile flip-lip, and center window aperture.
 */
private fun DrawScope.drawLamborghiniSafetyCage(
    center: Offset,
    radius: Float,
    glowColor: Color,
    glowAlpha: Float,
    enabled: Boolean,
    running: Boolean,
) {
    val cageWidth = radius * 1.54f
    val cageHeight = radius * 1.54f
    val cageLeft = center.x - cageWidth / 2f
    val cageTop = center.y - cageHeight / 2f

    // 1. Top Hinge Knuckle & Chrome Pivot Pin
    val hingeWidth = cageWidth * 0.72f
    val hingeHeight = radius * 0.16f
    val hingeLeft = center.x - hingeWidth / 2f
    val hingeTop = cageTop - hingeHeight * 0.4f

    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF6B7582), Color(0xFF262C34), Color(0xFF7E8997)),
            start = Offset(hingeLeft, hingeTop),
            end = Offset(hingeLeft, hingeTop + hingeHeight),
        ),
        topLeft = Offset(hingeLeft, hingeTop),
        size = Size(hingeWidth, hingeHeight),
        cornerRadius = CornerRadius(hingeHeight / 2f),
    )

    // 2. Anodized Red Flip-Guard Frame Body
    val redFramePath = Path().apply {
        val top = cageTop
        val btm = cageTop + cageHeight
        val left = cageLeft
        val right = cageLeft + cageWidth
        val cut = radius * 0.22f

        // Angular fighter-jet chamfered silhouette
        moveTo(left + cut, top)
        lineTo(right - cut, top)
        lineTo(right, top + cut)
        lineTo(right, btm - cut)
        lineTo(right - cut, btm)
        lineTo(left + cut, btm)
        lineTo(left, btm - cut)
        lineTo(left, top + cut)
        close()
    }

    val redGradient = if (enabled) {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFFFF334B),
                Color(0xFFD61228),
                Color(0xFF8B0014),
                Color(0xFF5E000D),
            ),
            center = Offset(center.x, center.y - cageHeight * 0.35f),
            radius = cageWidth * 0.9f,
        )
    } else {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFF8F323C),
                Color(0xFF5A1C23),
                Color(0xFF330E13),
            ),
            center = center,
            radius = cageWidth * 0.8f,
        )
    }
    drawPath(path = redFramePath, brush = redGradient)

    // Red frame 3D edge highlight stroke
    val cageEdgeBrush = Brush.linearGradient(
        colors = listOf(
            if (enabled) Color(0xFFFF8595).copy(alpha = 0.85f) else Color(0xFFB0646D).copy(alpha = 0.5f),
            if (enabled) Color(0xFFE52035) else Color(0xFF6B222A),
            Color(0xFF380007),
        ),
        start = Offset(cageLeft, cageTop),
        end = Offset(cageLeft + cageWidth, cageTop + cageHeight),
    )
    drawPath(
        path = redFramePath,
        brush = cageEdgeBrush,
        style = Stroke(width = radius * 0.032f),
    )

    // 3. Tactile Grip Ridges on Top Flip-Lip
    val lipWidth = cageWidth * 0.44f
    val lipLeft = center.x - lipWidth / 2f
    val lipY = cageTop + radius * 0.08f
    for (r in 0..2) {
        val yOffset = lipY + r * (radius * 0.042f)
        drawLine(
            color = if (enabled) Color(0xFFFF94A3).copy(alpha = 0.9f) else Color(0xFF8A464E),
            start = Offset(lipLeft + r * 2f, yOffset),
            end = Offset(lipLeft + lipWidth - r * 2f, yOffset),
            strokeWidth = radius * 0.022f,
            cap = StrokeCap.Round,
        )
    }

    // 4. White / Silver Laser Hazard Chevrons on the Flanks
    val chevronY = center.y + radius * 0.42f
    val chevronSize = radius * 0.09f
    // Left chevron
    drawPath(
        path = Path().apply {
            moveTo(cageLeft + radius * 0.16f, chevronY - chevronSize)
            lineTo(cageLeft + radius * 0.24f, chevronY)
            lineTo(cageLeft + radius * 0.16f, chevronY + chevronSize)
        },
        color = Color.White.copy(alpha = if (enabled) 0.35f else 0.12f),
        style = Stroke(width = radius * 0.024f, cap = StrokeCap.Round),
    )
    // Right chevron
    drawPath(
        path = Path().apply {
            moveTo(cageLeft + cageWidth - radius * 0.16f, chevronY - chevronSize)
            lineTo(cageLeft + cageWidth - radius * 0.24f, chevronY)
            lineTo(cageLeft + cageWidth - radius * 0.16f, chevronY + chevronSize)
        },
        color = Color.White.copy(alpha = if (enabled) 0.35f else 0.12f),
        style = Stroke(width = radius * 0.024f, cap = StrokeCap.Round),
    )

    // 5. Central Window Aperture (Opening exposing the switch)
    val apertureRadius = radius * 0.54f
    // Inner wall reflection cast from the internal backlight
    if (enabled && (running || glowAlpha > 0.4f)) {
        drawCircle(
            color = glowColor.copy(alpha = glowAlpha * 0.38f),
            radius = apertureRadius * 1.08f,
            center = center,
            style = Stroke(width = radius * 0.065f),
        )
    }

    // Inner aperture dark bevel
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF260005), Color(0xFF5E000D), Color(0xFF1F0004)),
            start = Offset(center.x, center.y - apertureRadius),
            end = Offset(center.x, center.y + apertureRadius),
        ),
        radius = apertureRadius,
        center = center,
        style = Stroke(width = radius * 0.04f),
    )
}

/**
 * Draws the inner ignition button core: knurled grip ring, brushed metallic bezel,
 * obsidian dish, illuminated power glyph, and crisp laser-etched typography.
 */
private fun DrawScope.drawIgnitionSwitchCore(
    center: Offset,
    coreRadius: Float,
    glowColor: Color,
    glowAlpha: Float,
    running: Boolean,
    enabled: Boolean,
    textMeasurer: TextMeasurer,
) {
    // 1. Backlight Halo (radiating from behind the switch)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glowColor.copy(alpha = glowAlpha * 0.50f),
                glowColor.copy(alpha = glowAlpha * 0.15f),
                Color.Transparent,
            ),
            center = center,
            radius = coreRadius * 1.35f,
        ),
        radius = coreRadius * 1.35f,
        center = center,
    )

    // 2. Precision 36-Teeth Knurled Grip Ring
    val notches = 36
    for (i in 0 until notches) {
        val angle = i * 360f / notches
        val rad = Math.toRadians(angle.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val inner = coreRadius * 0.88f
        drawLine(
            color = if (i % 2 == 0) Color.White.copy(alpha = 0.28f) else Color(0xFF424A55),
            start = Offset(center.x + cosA * inner, center.y + sinA * inner),
            end = Offset(center.x + cosA * coreRadius, center.y + sinA * coreRadius),
            strokeWidth = coreRadius * 0.045f,
        )
    }

    // 3. Brushed Titanium Bezel Ring
    val bezelRadius = coreRadius * 0.86f
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(
                Color(0xFF9EABB8), Color(0xFF38404A), Color(0xFFA8B5C3),
                Color(0xFF282F38), Color(0xFF8A97A5), Color(0xFF222830),
                Color(0xFF9EABB8),
            ),
            center = center,
        ),
        radius = bezelRadius,
        center = center,
    )

    // 4. Matte Obsidian / Carbon Ignition Dish
    val faceRadius = bezelRadius * 0.82f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF2B313A), Color(0xFF14171C), Color(0xFF07090C)),
            center = center.copy(y = center.y - faceRadius * 0.35f),
            radius = faceRadius * 1.3f,
        ),
        radius = faceRadius,
        center = center,
    )

    // 5. Backlight Diffuse Glow Core
    drawCircle(
        color = glowColor.copy(alpha = glowAlpha * 0.32f),
        radius = faceRadius * 0.95f,
        center = center,
    )

    // 6. Laser-Etched "ENGINE" Typography (Top)
    val engineText = "ENGINE"
    val engineStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = (faceRadius * 0.24f).sp,
        letterSpacing = 1.6.sp,
        color = glowColor.copy(alpha = if (enabled) glowAlpha else 0.25f),
    )
    val engineLayout = textMeasurer.measure(engineText, engineStyle)
    drawText(
        textLayoutResult = engineLayout,
        topLeft = Offset(
            center.x - engineLayout.size.width / 2f,
            center.y - faceRadius * 0.72f,
        ),
    )

    // 7. Iconic Backlit Power Glyph (Center)
    val glyphRadius = faceRadius * 0.34f
    val glyphY = center.y + faceRadius * 0.04f
    val glyphCenter = Offset(center.x, glyphY)

    // Ambient diffuse stroke behind the glyph
    drawArc(
        color = glowColor.copy(alpha = glowAlpha * 0.45f),
        startAngle = -230f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(glyphCenter.x - glyphRadius, glyphCenter.y - glyphRadius),
        size = Size(glyphRadius * 2f, glyphRadius * 2f),
        style = Stroke(width = faceRadius * 0.16f, cap = StrokeCap.Round),
    )
    drawLine(
        color = glowColor.copy(alpha = glowAlpha * 0.45f),
        start = Offset(glyphCenter.x, glyphCenter.y - glyphRadius * 1.32f),
        end = Offset(glyphCenter.x, glyphCenter.y - glyphRadius * 0.10f),
        strokeWidth = faceRadius * 0.16f,
        cap = StrokeCap.Round,
    )

    // Sharp foreground laser stroke
    drawArc(
        color = glowColor.copy(alpha = if (enabled) glowAlpha else 0.25f),
        startAngle = -230f,
        sweepAngle = 280f,
        useCenter = false,
        topLeft = Offset(glyphCenter.x - glyphRadius, glyphCenter.y - glyphRadius),
        size = Size(glyphRadius * 2f, glyphRadius * 2f),
        style = Stroke(width = faceRadius * 0.10f, cap = StrokeCap.Round),
    )
    drawLine(
        color = glowColor.copy(alpha = if (enabled) glowAlpha else 0.25f),
        start = Offset(glyphCenter.x, glyphCenter.y - glyphRadius * 1.32f),
        end = Offset(glyphCenter.x, glyphCenter.y - glyphRadius * 0.10f),
        strokeWidth = faceRadius * 0.10f,
        cap = StrokeCap.Round,
    )

    // 8. Laser-Etched "START / STOP" Typography (Bottom)
    val actionText = if (running) "STOP" else "START"
    val actionStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = (faceRadius * 0.26f).sp,
        letterSpacing = 1.8.sp,
        color = glowColor.copy(alpha = if (enabled) glowAlpha else 0.25f),
    )
    val actionLayout = textMeasurer.measure(actionText, actionStyle)
    drawText(
        textLayoutResult = actionLayout,
        topLeft = Offset(
            center.x - actionLayout.size.width / 2f,
            glyphCenter.y + glyphRadius * 0.72f,
        ),
    )
}

/**
 * Creates a regular 6-sided polygon Path centered at [center] with radius [radius].
 */
private fun createHexagonPath(center: Offset, radius: Float): Path {
    return Path().apply {
        for (i in 0 until 6) {
            val angleDeg = i * 60f
            val rad = Math.toRadians(angleDeg.toDouble())
            val x = center.x + radius * cos(rad).toFloat()
            val y = center.y + radius * sin(rad).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * Supercar-styled cockpit caption rendered beneath the Lamborghini ignition switch.
 */
@Composable
fun PushStartCaption(
    running: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val (statusDotColor, statusText) = when {
        !enabled -> TextMuted.copy(alpha = 0.4f) to "IGNITION OFF"
        running -> ZoneGood to "PUSH TO STOP"
        else -> Color(0xFFFF3344) to "PUSH TO START"
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
                letterSpacing = 1.4.sp,
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

