package com.mohid.obd2dash.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.alerts.ThresholdRule
import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.data.PressureUnit
import com.mohid.obd2dash.obd.metricByKey
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.PanelRaised
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneWarn
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph) {
    val settings by graph.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val store = graph.settingsStore

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("Connection")
        SettingsCard {
            SwitchRow(
                title = "Demo mode",
                subtitle = "Run against a simulated turbo engine instead of the adapter.",
                checked = settings.demoMode,
                onCheckedChange = { scope.launch { store.setDemoMode(it) } },
            )
            RowDivider()
            SwitchRow(
                title = "Start trips automatically",
                subtitle = "Treat connecting to the adapter as the engine coming on.",
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

        SectionLabel("Display")
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

        SectionLabel("Alerts")
        SettingsCard {
            SwitchRow(
                title = "Alert chime",
                subtitle = "Two-tone dash chime plus a persistent on-screen banner. " +
                    "Turning this off leaves the banner but silences the sound.",
                checked = settings.alertSoundEnabled,
                onCheckedChange = { scope.launch { store.setAlertSound(it) } },
            )
        }

        SectionLabel("Thresholds")
        Text(
            "Defaults are set for a 2023 Move turbo. Leave a field empty to stop checking that bound.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        settings.thresholds.forEach { rule ->
            ThresholdCard(
                rule = rule,
                onSave = { scope.launch { store.saveThreshold(it) } },
            )
        }
        TextButton(onClick = { scope.launch { store.resetThresholds() } }) {
            Text("Reset all thresholds to defaults", color = ZoneWarn)
        }

        SectionLabel("Cloud sync")
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

        Spacer(Modifier.height(24.dp))
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
