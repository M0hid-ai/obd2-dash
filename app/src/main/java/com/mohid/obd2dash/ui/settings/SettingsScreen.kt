package com.mohid.obd2dash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.alerts.ThresholdRule
import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.data.GaugeAccent
import com.mohid.obd2dash.data.GaugeSkin
import com.mohid.obd2dash.data.PressureUnit
import com.mohid.obd2dash.obd.metricByKey
import com.mohid.obd2dash.ui.components.GaugeZone
import com.mohid.obd2dash.ui.components.MetricGauge
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.PanelRaised
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneGood
import com.mohid.obd2dash.ui.theme.ZoneWarn
import kotlinx.coroutines.launch

/**
 * A lazy list rather than a plain scrolling [Column].
 *
 * The gauge face picker alone puts nine live, animating dial previews on this
 * screen. A plain `Column().verticalScroll()` composes and draws every child
 * up front regardless of what is actually on screen, so all nine, and every
 * threshold card below them, were live and redrawing every frame the instant
 * Settings opened, whether scrolled into view or not. [LazyColumn] only
 * composes what is near the viewport, which is what actually made the gauge
 * face and thresholds lists need to be section by section here rather than
 * one big block: each row has to be its own list item for the laziness to
 * apply to it individually.
 */
@Composable
fun SettingsScreen(graph: AppGraph) {
    val settings by graph.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val store = graph.settingsStore

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionLabel("Connection")
        }
        item {
            SettingsCard {
                SwitchRow(
                    title = "Demo mode",
                    subtitle = "Run against a simulated turbo engine instead of the adapter.",
                    checked = settings.demoMode,
                    onCheckedChange = { scope.launch { store.setDemoMode(it) } },
                )
                RowDivider()
                SwitchRow(
                    title = "Automatic trips",
                    subtitle = "Begin a trip when the engine starts and end it when it stops. " +
                        "Off means the Start and Stop buttons are the only things that " +
                        "open or close a trip.",
                    checked = settings.autoStartTripOnConnect,
                    onCheckedChange = { scope.launch { store.setAutoStartTrip(it) } },
                )
                RowDivider()
                SwitchRow(
                    title = "Fast responses",
                    subtitle = "Tells the ELM327 to stop waiting after the first reply frame. " +
                        "Roughly doubles the sample rate; turn off if a clone adapter misbehaves.",
                    checked = settings.useFrameCountHint,
                    onCheckedChange = { scope.launch { store.setFrameCountHint(it) } },
                )
                RowDivider()
                SliderRow(
                    title = "Poll interval",
                    value = settings.pollIntervalMs.toFloat(),
                    valueLabel = "${settings.pollIntervalMs} ms",
                    range = 150f..800f,
                    subtitle = "Target time per cycle. Lower is smoother but leaves the adapter " +
                        "less headroom; the loop never runs faster than the adapter answers.",
                    onValueChange = { scope.launch { store.setPollIntervalMs(it.toInt()) } },
                )
            }
        }

        item {
            SectionLabel("Gauge face")
        }
        item {
            Text(
                "Each face is modelled on a real instrument cluster. Compare all puts a different " +
                    "one on each of the four dials so they can be judged on live data.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        items(GaugeSkin.entries, key = { it.name }) { skin ->
            SkinRow(
                skin = skin,
                selected = settings.gaugeSkin == skin,
                onSelect = { scope.launch { store.setGaugeSkin(skin) } },
            )
        }

        item {
            SectionLabel("Accent colour")
        }
        item {
            Text(
                "Recolours the healthy band on whichever gauge face you're using. Warning stays " +
                    "amber and danger stays red no matter what you pick here.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        item {
            SettingsCard {
                AccentSwatchRow(
                    selected = settings.gaugeAccent,
                    onSelect = { scope.launch { store.setGaugeAccent(it) } },
                )
            }
        }

        item {
            SectionLabel("Display")
        }
        item {
            SettingsCard {
                Column(Modifier.padding(14.dp)) {
                    Text("Boost unit", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Boost is MAP minus ambient pressure, so it reads negative under vacuum.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.padding(top = 10.dp)) {
                        PressureUnit.entries.forEachIndexed { index, unit ->
                            SegmentedButton(
                                selected = settings.pressureUnit == unit,
                                onClick = { scope.launch { store.setPressureUnit(unit) } },
                                shape = SegmentedButtonDefaults.itemShape(index, PressureUnit.entries.size),
                            ) {
                                Text(unit.label)
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionLabel("Alerts")
        }
        item {
            SettingsCard {
                SwitchRow(
                    title = "Alert chime",
                    subtitle = "Two-tone dash chime plus a persistent on-screen banner. " +
                        "Turning this off leaves the banner but silences the sound.",
                    checked = settings.alertSoundEnabled,
                    onCheckedChange = { scope.launch { store.setAlertSound(it) } },
                )
            }
        }

        item {
            SectionLabel("Thresholds")
        }
        item {
            Text(
                "Defaults are set for a 2023 Move turbo. Leave a field empty to stop checking that bound.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        items(settings.thresholds, key = { it.metricKey }) { rule ->
            ThresholdCard(
                rule = rule,
                onSave = { scope.launch { store.saveThreshold(it) } },
            )
        }
        item {
            TextButton(onClick = { scope.launch { store.resetThresholds() } }) {
                Text("Reset all thresholds to defaults", color = ZoneWarn)
            }
        }

        item {
            SectionLabel("Cloud sync")
        }
        item {
            SettingsCard {
                SwitchRow(
                    title = "Live mode",
                    subtitle = "Uploads in near real time instead of once per trip. Off by default " +
                        "to save mobile data and battery. Needs Firebase configured.",
                    checked = settings.liveMode,
                    onCheckedChange = { scope.launch { store.setLiveMode(it) } },
                )
                RowDivider()
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Firestore upload is not wired up yet. Drop a google-services.json into " +
                            "app/ and the trip schema is already carrying a synced-at stamp for it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThresholdCard(rule: ThresholdRule, onSave: (ThresholdRule) -> Unit) {
    val pid = metricByKey(rule.metricKey)
    var expanded by remember { mutableStateOf(false) }

    SettingsCard {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pid?.label ?: rule.metricKey, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        summarize(rule, pid?.unit.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onSave(rule.copy(enabled = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Cyan.copy(alpha = 0.5f)),
                )
            }

            if (expanded) {
                var warnAbove by remember(rule) { mutableStateOf(rule.warnAbove?.toString().orEmpty()) }
                var criticalAbove by remember(rule) { mutableStateOf(rule.criticalAbove?.toString().orEmpty()) }
                var warnBelow by remember(rule) { mutableStateOf(rule.warnBelow?.toString().orEmpty()) }
                var criticalBelow by remember(rule) { mutableStateOf(rule.criticalBelow?.toString().orEmpty()) }

                Column(Modifier.padding(horizontal = 14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BoundField("Warn above", warnAbove, ZoneWarn, Modifier.weight(1f)) { warnAbove = it }
                        BoundField("Critical above", criticalAbove, ZoneDanger, Modifier.weight(1f)) { criticalAbove = it }
                    }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BoundField("Warn below", warnBelow, ZoneWarn, Modifier.weight(1f)) { warnBelow = it }
                        BoundField("Critical below", criticalBelow, ZoneDanger, Modifier.weight(1f)) { criticalBelow = it }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { expanded = false }) { Text("Cancel", color = TextMuted) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onSave(
                                    rule.copy(
                                        warnAbove = warnAbove.toFloatOrNull(),
                                        criticalAbove = criticalAbove.toFloatOrNull(),
                                        warnBelow = warnBelow.toFloatOrNull(),
                                        criticalBelow = criticalBelow.toFloatOrNull(),
                                    ),
                                )
                                expanded = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Panel),
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoundField(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = accent) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun summarize(rule: ThresholdRule, unit: String): String {
    val parts = buildList {
        rule.criticalBelow?.let { add("crit < $it") }
        rule.warnBelow?.let { add("warn < $it") }
        rule.warnAbove?.let { add("warn > $it") }
        rule.criticalAbove?.let { add("crit > $it") }
    }
    if (parts.isEmpty()) return "No bounds set"
    return parts.joinToString("   ") + if (unit.isEmpty()) "" else "  $unit"
}

/**
 * A row of tappable colour swatches for the healthy-band accent.
 *
 * Presented as plain filled circles rather than named list rows, since the
 * colour itself is the entire content here and a swatch reads faster than a
 * label ever would.
 */
@Composable
private fun AccentSwatchRow(selected: GaugeAccent, onSelect: (GaugeAccent) -> Unit) {
    // Eight swatches at a comfortable tap size run slightly wider than a
    // narrow phone screen, so this scrolls rather than clipping the last one.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GaugeAccent.entries.forEach { accent ->
            val isSelected = accent == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(accent.color)
                    .clickable(onClick = { onSelect(accent) }),
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = accent.label,
                        tint = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * One choice in the face picker, with a working dial next to it.
 *
 * The preview runs on a fixed sample reading rather than live data: the point
 * is to compare how the faces draw, and four dials all sweeping at once would
 * fight for attention instead of making the difference obvious.
 *
 * Each row is its own card rather than one shared list, since it is now a
 * standalone item in the settings [LazyColumn]: laziness only helps if each
 * row can be composed and discarded on its own.
 */
@Composable
private fun SkinRow(skin: GaugeSkin, selected: Boolean, onSelect: () -> Unit) {
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 6.dp, end = 10.dp),
            ) {
                Text(
                    skin.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Cyan else MaterialTheme.colorScheme.onSurface,
                )
                Text(skin.blurb, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            if (skin != GaugeSkin.SHOWCASE) {
                MetricGauge(
                    label = "RPM",
                    value = PREVIEW_RPM,
                    unit = "rpm",
                    min = 0f,
                    max = 8000f,
                    zones = listOf(
                        GaugeZone(0f, 5800f, ZoneGood, healthy = true),
                        GaugeZone(5800f, 6800f, ZoneWarn),
                        GaugeZone(6800f, 8000f, ZoneDanger),
                    ),
                    valueText = PREVIEW_RPM.toInt().toString(),
                    animationMillis = 1,
                    skin = skin,
                    modifier = Modifier.width(116.dp),
                )
            }
        }
    }
}

/** Mid range and clearly inside the healthy band, so no preview looks alarmed. */
private const val PREVIEW_RPM = 4200f

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan.copy(alpha = 0.5f)),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    subtitle: String,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Cyan,
            )
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = 12,
        )
    }
}

@Composable
private fun RowDivider() {
    Surface(
        color = com.mohid.obd2dash.ui.theme.Hairline,
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp),
    ) {}
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Cyan,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp),
    )
}
