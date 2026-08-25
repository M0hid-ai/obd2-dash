package com.mohid.obd2dash.ui.connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.obd.ConnectionState
import com.mohid.obd2dash.service.ObdService
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.Ink
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.PanelRaised
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneGood
import kotlinx.coroutines.launch

private data class PairedAdapter(val name: String, val address: String)

/**
 * Adapter picker and connection diagnostics.
 *
 * Only bonded devices are listed: an ELM327 has to be paired in system settings
 * before RFCOMM will open anyway, so scanning here would show a lot of noise
 * and no useful extra choices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by graph.controller.connection.collectAsStateWithLifecycle()
    val log by graph.controller.log.collectAsStateWithLifecycle()
    val stats by graph.controller.stats.collectAsStateWithLifecycle()
    val settings by graph.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())

    var devices by remember { mutableStateOf(bondedAdapters(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adapter") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { devices = bondedAdapters(context) }) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusCard(connection, stats.samplesPerSecond, stats.lastCycleMs)

            SectionLabel("Paired adapters")
            if (devices.isEmpty()) {
                HintCard(
                    "No paired Bluetooth devices found. Pair your ELM327 in Android's " +
                        "Bluetooth settings first. The usual PIN is 1234 or 0000.",
                )
            } else {
                devices.forEach { device ->
                    AdapterRow(
                        adapter = device,
                        selected = settings.lastDeviceAddress == device.address && !settings.demoMode,
                        onClick = {
                            scope.launch {
                                graph.settingsStore.setLastDeviceAddress(device.address)
                                graph.settingsStore.setDemoMode(false)
                            }
                            ObdService.start(context, device.address)
                        },
                    )
                }
            }

            SectionLabel("Without the car")
            DemoRow(
                enabled = settings.demoMode,
                onClick = {
                    scope.launch { graph.settingsStore.setDemoMode(true) }
                    ObdService.start(context, null)
                },
            )

            SectionLabel("Session log")
            LogCard(log)
        }
    }
}

@Composable
private fun StatusCard(connection: ConnectionState, samplesPerSecond: Float, cycleMs: Long) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            when (connection) {
                is ConnectionState.Connected -> {
                    Text(
                        if (connection.demo) "Simulated ECU" else connection.transportName,
                        style = MaterialTheme.typography.titleMedium,
                        color = ZoneGood,
                    )
                    Text(
                        "${connection.adapter.version} · ${connection.adapter.protocol}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    Text(
                        "${connection.supportedPidCount} supported PIDs · " +
                            "%.1f samples/s · %d ms per cycle".format(samplesPerSecond, cycleMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                is ConnectionState.Connecting -> {
                    Text("Connecting", style = MaterialTheme.typography.titleMedium)
                    Text(connection.stage, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }

                is ConnectionState.Failed -> {
                    Text(
                        "Connection failed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(connection.reason, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }

                ConnectionState.Disconnected -> {
                    Text("Disconnected", style = MaterialTheme.typography.titleMedium, color = TextMuted)
                    Text(
                        "Pick an adapter below to bring the link up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdapterRow(adapter: PairedAdapter, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Cyan.copy(alpha = 0.14f) else PanelRaised,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = if (selected) Cyan else TextMuted,
                modifier = Modifier.size(20.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(adapter.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    adapter.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (selected) Text("Last used", style = MaterialTheme.typography.labelSmall, color = Cyan)
        }
    }
}

@Composable
private fun DemoRow(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (enabled) Cyan.copy(alpha = 0.14f) else PanelRaised,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Science,
                contentDescription = null,
                tint = if (enabled) Cyan else TextMuted,
                modifier = Modifier.size(20.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text("Demo mode", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "Runs a simulated turbo engine through a two-minute drive cycle. " +
                        "Everything downstream behaves exactly as it does on the car.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }
    }
}

@Composable
private fun LogCard(lines: List<String>) {
    Surface(
        color = PanelRaised,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 90.dp, max = 260.dp)
            .padding(bottom = 16.dp),
    ) {
        if (lines.isEmpty()) {
            Box(Modifier.padding(14.dp)) {
                Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        } else {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Cyan,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun HintCard(text: String) {
    Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier
                .background(PanelRaised)
                .padding(14.dp),
        )
    }
}

@SuppressLint("MissingPermission")
private fun bondedAdapters(context: Context): List<PairedAdapter> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) {
        return emptyList()
    }
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching {
        adapter.bondedDevices.orEmpty().map { PairedAdapter(it.name ?: "Unknown", it.address) }
    }.getOrDefault(emptyList())
        .sortedBy { it.name }
}
