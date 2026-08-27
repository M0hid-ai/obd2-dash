package com.mohid.obd2dash.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneWarn
import kotlinx.coroutines.delay

private const val SEGMENTS = 24
private const val FLASH_MS = 70L

/**
 * A segmented shift light strip across the top of the dashboard, the kind
 * bolted to an aftermarket wheel or built into an M-car cluster rather than
 * the stock dial.
 *
 * Fills left to right, amber into red, as RPM climbs from [warnAt] toward
 * [criticalAt], then the whole strip flashes solid red once the engine is
 * actually at or past redline. It reacts to the raw live RPM rather than the
 * alert engine's hysteresis on purpose: a shift light that lags the
 * tachometer by half a second defeats the point of having one.
 */
@Composable
fun ShiftLightBar(
    rpm: Float?,
    warnAt: Float?,
    criticalAt: Float?,
    modifier: Modifier = Modifier,
) {
    val active = rpm != null && warnAt != null && rpm >= warnAt
    val atRedline = rpm != null && criticalAt != null && rpm >= criticalAt
    val fraction = if (rpm != null && warnAt != null && criticalAt != null && criticalAt > warnAt) {
        ((rpm - warnAt) / (criticalAt - warnAt)).coerceIn(0f, 1f)
    } else {
        0f
    }
    val flashOn by flashState(atRedline)

    AnimatedVisibility(
        visible = active,
        enter = fadeIn(tween(80)),
        exit = fadeOut(tween(180)),
        modifier = modifier,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(7.dp),
        ) {
            val gap = size.width * 0.006f
            val segmentWidth = (size.width - gap * (SEGMENTS - 1)) / SEGMENTS
            val lit = (fraction * SEGMENTS).toInt().coerceIn(0, SEGMENTS)
            val corner = CornerRadius(size.height * 0.3f, size.height * 0.3f)

            for (i in 0 until SEGMENTS) {
                val shown = if (atRedline) flashOn else i < lit
                if (!shown) continue
                val colorPosition = i / (SEGMENTS - 1).toFloat()
                val color = if (atRedline) ZoneDanger else lerp(ZoneWarn, ZoneDanger, colorPosition)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (segmentWidth + gap), 0f),
                    size = Size(segmentWidth, size.height),
                    cornerRadius = corner,
                )
            }
        }
    }
}

/** Toggles on a fixed interval while [active], off otherwise. */
@Composable
private fun flashState(active: Boolean): State<Boolean> =
    produceState(initialValue = false, active) {
        if (!active) {
            value = false
            return@produceState
        }
        while (true) {
            value = true
            delay(FLASH_MS)
            value = false
            delay(FLASH_MS)
        }
    }
