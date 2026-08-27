package com.mohid.obd2dash.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.MetricSnapshot
import com.mohid.obd2dash.obd.PidRegistry
import com.mohid.obd2dash.ui.theme.TextMuted

private enum class HudMetric {
    SPEED,
    RPM,
    BOOST,
    ;

    fun next(): HudMetric = entries[(ordinal + 1) % entries.size]
}

/**
 * A windshield head-up display: one huge number, mirrored, on pure black.
 *
 * Prop the phone flat on the dash facing up and the windshield reflects this
 * back at driver height, the same trick dedicated HUD gadgets use. The
 * mirroring has to happen here rather than relying on the glass: a phone
 * screen reads left to right, but its reflection reads right to left, so the
 * text is flipped in software to come out the right way round after the
 * glass flips it back a second time.
 *
 * Tapping the number cycles speed, RPM, and boost. The back arrow is
 * deliberately not mirrored: it is meant to be tapped by hand before the
 * phone goes down, not read through the glass.
 */
@Composable
fun HudScreen(graph: AppGraph, onBack: () -> Unit) {
    val snapshot by graph.controller.snapshot.collectAsStateWithLifecycle()
    val settings by graph.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    var metric by remember { mutableStateOf(HudMetric.SPEED) }

    // A HUD is useless the instant the display sleeps.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val (valueText, unitText) = hudReading(metric, snapshot, settings)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { metric = metric.next() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .scale(scaleX = -1f, scaleY = 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = valueText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 130.sp,
                ),
                color = Color.White,
            )
            Text(
                text = unitText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    letterSpacing = 4.sp,
                ),
                color = TextMuted,
            )
        }

        Surface(
            color = Color.White.copy(alpha = 0.08f),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit HUD", tint = Color.White)
            }
        }
    }
}

/** (value text, unit label) for the active metric. */
private fun hudReading(metric: HudMetric, snapshot: MetricSnapshot, settings: AppSettings): Pair<String, String> =
    when (metric) {
        HudMetric.SPEED -> {
            val v = snapshot[PidRegistry.SPEED.key]
            (v?.toInt()?.toString() ?: "--") to "KM/H"
        }
        HudMetric.RPM -> {
            val v = snapshot[PidRegistry.RPM.key]
            (v?.toInt()?.toString() ?: "--") to "RPM"
        }
        HudMetric.BOOST -> {
            val unit = settings.pressureUnit
            val kpa = snapshot[DerivedMetrics.BOOST.key]
            val v = kpa?.let { unit.from(it) }
            (v?.let { "%.${unit.decimals}f".format(it) } ?: "--") to unit.suffix.uppercase()
        }
    }
