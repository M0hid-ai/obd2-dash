package com.mohid.obd2dash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.obd.ConnectionState
import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.MetricSnapshot
import com.mohid.obd2dash.obd.ObdPid
import com.mohid.obd2dash.obd.PidRegistry
import com.mohid.obd2dash.obd.TripState
import com.mohid.obd2dash.service.ObdService
import com.mohid.obd2dash.ui.components.AlertBanner
import com.mohid.obd2dash.ui.components.GaugeZone
import com.mohid.obd2dash.ui.components.MetricGauge
import com.mohid.obd2dash.ui.components.StatTile
import com.mohid.obd2dash.ui.components.formatElapsed
import com.mohid.obd2dash.ui.components.zonesFor
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneGood
import com.mohid.obd2dash.ui.theme.ZoneWarn
import kotlinx.coroutines.delay

/**
 * The screen this app exists for: four large dials, readable at a glance from
 * the passenger seat, plus whatever alert is currently live.
 */
@Composable
fun DashboardScreen(
    graph: AppGraph,
    onOpenConnect: () -> Unit,
    onOpenTrip: (Long) -> Unit,
) {
    val context = LocalContext.current
    val connection by graph.controller.connection.collectAsStateWithLifecycle()
    val snapshot by graph.controller.snapshot.collectAsStateWithLifecycle()
    val alerts by graph.controller.alerts.collectAsStateWithLifecycle()
    val trip by graph.controller.trip.collectAsStateWithLifecycle()
    val lastFinished by graph.controller.lastFinishedTrip.collectAsStateWithLifecycle()
    val settings by graph.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionHeader(
            connection = connection,
            demoMode = settings.demoMode,
            onConnect = {
                val address = if (settings.demoMode) null else settings.lastDeviceAddress
                if (!settings.demoMode && address == null) {
                    onOpenConnect()
                } else {
                    ObdService.start(context, address)
                }
            },
            onDisconnect = { ObdService.stop(context) },
            onOpenConnect = onOpenConnect,
        )

        AlertBanner(
            alerts = alerts,
            onAcknowledgeAll = { graph.controller.acknowledgeAllAlerts() },
        )

        lastFinished?.let { tripId ->
            FinishedTripPrompt(
                onOpen = {
                    graph.controller.consumeLastFinishedTrip()
                    onOpenTrip(tripId)
                },
                onDismiss = { graph.controller.consumeLastFinishedTrip() },
            )
        }

        GaugeGrid(snapshot = snapshot, settings = settings)

        TripControls(
            trip = trip,
            connected = connection is ConnectionState.Connected,
            onStart = { graph.controller.startTrip() },
            onStop = { graph.controller.stopTrip() },
        )
    }
}

@Composable
private fun GaugeGrid(snapshot: MetricSnapshot, settings: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PidGauge(PidRegistry.RPM, snapshot, settings, Modifier.weight(1f))
            PidGauge(PidRegistry.SPEED, snapshot, settings, Modifier.weight(1f))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            PidGauge(PidRegistry.COOLANT_TEMP, snapshot, settings, Modifier.weight(1f))
            BoostGauge(snapshot, settings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PidGauge(
    pid: ObdPid,
    snapshot: MetricSnapshot,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val value = snapshot[pid.key]
    MetricGauge(
        label = pid.shortLabel,
        value = value,
        unit = pid.unit,
        min = pid.displayMin,
        max = pid.displayMax,
        zones = zonesFor(pid, settings.thresholdFor(pid.key), pid.displayMin, pid.displayMax),
        valueText = value?.let { pid.format(it) },
        animationMillis = settings.pollIntervalMs + 100,
        modifier = modifier,
    )
}

/**
 * Boost is the one dial that is not a PID and not in SI on screen: it is
 * MAP minus ambient, shown in whichever pressure unit the user picked. The
 * thresholds are stored in kPa, so the bands are converted alongside the value
 * to keep the dial and the alerts consistent.
 */
@Composable
private fun BoostGauge(
    snapshot: MetricSnapshot,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val pid = DerivedMetrics.BOOST
    val unit = settings.pressureUnit
    val kpa = snapshot[pid.key]
    val rule = settings.thresholdFor(pid.key)

    val zonesKpa = zonesFor(pid, rule, pid.displayMin, pid.displayMax)
    val zones = zonesKpa.map { GaugeZone(unit.from(it.from), unit.from(it.to), it.color) }

    MetricGauge(
        label = "Boost",
        value = kpa?.let { unit.from(it) },
        unit = unit.suffix,
        min = unit.from(pid.displayMin),
        max = unit.from(pid.displayMax),
        zones = zones,
        valueText = kpa?.let { "%.${unit.decimals}f".format(unit.from(it)) },
        animationMillis = settings.pollIntervalMs + 100,
        modifier = modifier,
    )
}

@Composable
private fun ConnectionHeader(
    connection: ConnectionState,
    demoMode: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenConnect: () -> Unit,
) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (dotColor, title, detail) = when (connection) {
                is ConnectionState.Connected -> Triple(
                    ZoneGood,
                    if (connection.demo) "Demo adapter" else connection.transportName,
                    "${connection.supportedPidCount} PIDs · ${connection.adapter.protocol}",
                )

                is ConnectionState.Connecting -> Triple(ZoneWarn, "Connecting", connection.stage)
                is ConnectionState.Failed -> Triple(ZoneDanger, "Not connected", connection.reason)
                ConnectionState.Disconnected -> Triple(
                    TextMuted,
                    "Not connected",
                    if (demoMode) "Demo mode is on" else "Pick your ELM327 to begin",
                )
            }

            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 3,
                )
            }

            if (connection is ConnectionState.Connected || connection is ConnectionState.Connecting) {
                OutlinedButton(onClick = onDisconnect) { Text("Stop") }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Panel),
                ) {
                    Icon(Icons.Filled.BluetoothConnected, contentDescription = null, Modifier.size(18.dp))
                    Text("Connect", modifier = Modifier.padding(start = 6.dp))
                }
            }
            TextButton(onClick = onOpenConnect) {
                Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = "Adapters", tint = TextMuted)
            }
        }
    }
}

@Composable
private fun TripControls(
    trip: TripState,
    connected: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            when (trip) {
                is TripState.Recording -> {
                    // Recomputed once a second so the elapsed readout ticks
                    // without the poll loop having to publish trip state faster.
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(trip.tripId) {
                        while (true) {
                            now = System.currentTimeMillis()
                            delay(1_000)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Recording trip #${trip.tripId}",
                                style = MaterialTheme.typography.titleSmall,
                                color = ZoneGood,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (trip.startedManually) "Started manually" else "Started automatically on connect",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = ZoneDanger),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                            Text("Stop", modifier = Modifier.padding(start = 6.dp))
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatTile("Elapsed", formatElapsed(now - trip.startedAt))
                        StatTile("Distance", "%.2f km".format(trip.distanceMeters / 1000))
                        StatTile("Samples", trip.sampleCount.toString())
                    }
                }

                TripState.Idle -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("No trip recording", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (connected) {
                                    "Start one manually, or let it start on the next connect."
                                } else {
                                    "Connect to the adapter first."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Button(onClick = onStart, enabled = connected) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                            Text("Start", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinishedTripPrompt(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = Cyan.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Trip finished. The report is ready.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) { Text("View", color = Cyan) }
            TextButton(onClick = onDismiss) { Text("Later", color = TextMuted) }
        }
    }
}
