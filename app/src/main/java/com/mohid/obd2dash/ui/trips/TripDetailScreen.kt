package com.mohid.obd2dash.ui.trips

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.sp
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.data.ExportFormat
import com.mohid.obd2dash.data.SeriesPoint
import com.mohid.obd2dash.data.db.TripMetricEntity
import com.mohid.obd2dash.obd.metricByKey
import com.mohid.obd2dash.ui.components.RouteSample
import com.mohid.obd2dash.ui.components.RouteTrace
import com.mohid.obd2dash.ui.components.StatTile
import com.mohid.obd2dash.ui.components.TripLineChart
import com.mohid.obd2dash.ui.components.formatElapsed
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.Ink
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.PanelRaised
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneWarn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val headerFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

/**
 * The post-trip report: what happened, when, and whether anything went wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(graph: AppGraph, tripId: Long, onBack: () -> Unit) {
    val trip by graph.tripRepository.observeTrip(tripId).collectAsStateWithLifecycle(null)
    val summaries by graph.tripRepository.observeMetrics(tripId).collectAsStateWithLifecycle(emptyList())
    val dtcs by graph.tripRepository.observeDtcs(tripId).collectAsStateWithLifecycle(emptyList())

    var metricKeys by remember(tripId) { mutableStateOf<List<String>>(emptyList()) }
    var selectedMetric by remember(tripId) { mutableStateOf<String?>(null) }
    var series by remember(tripId) { mutableStateOf<List<SeriesPoint>>(emptyList()) }
    var route by remember(tripId) { mutableStateOf<List<RouteSample>>(emptyList()) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var shareMenuOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        metricKeys = graph.tripRepository.recordedMetrics(tripId)
        selectedMetric = metricKeys.firstOrNull()
        route = graph.tripRepository.route(tripId)
            .map { RouteSample(it.latitude, it.longitude, it.speedKph) }
    }

    LaunchedEffect(tripId, selectedMetric) {
        val key = selectedMetric
        series = if (key == null) emptyList() else graph.tripRepository.series(tripId, key)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip #$tripId") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { shareMenuOpen = true },
                            // An unfinished trip has no summary rows yet, so
                            // there is nothing coherent to export until it ends.
                            enabled = !exporting && trip?.endedAt != null,
                        ) {
                            if (exporting) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = Cyan,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Icon(Icons.Filled.Share, contentDescription = "Export or share this trip")
                            }
                        }
                        DropdownMenu(
                            expanded = shareMenuOpen,
                            onDismissRequest = { shareMenuOpen = false },
                            containerColor = Panel,
                        ) {
                            ExportFormat.entries.forEach { format ->
                                DropdownMenuItem(
                                    text = {
                                        Column(Modifier.padding(vertical = 4.dp)) {
                                            Text(format.label, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                format.blurb,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted,
                                                fontSize = 11.sp,
                                                modifier = Modifier.width(232.dp),
                                            )
                                        }
                                    },
                                    onClick = {
                                        shareMenuOpen = false
                                        exporting = true
                                        scope.launch {
                                            val file = runCatching {
                                                graph.tripExporter.export(tripId, format)
                                            }.getOrNull()
                                            exporting = false
                                            if (file == null) {
                                                snackbarHost.showSnackbar("Could not build that export.")
                                            } else {
                                                context.startActivity(
                                                    graph.tripExporter.shareIntent(file, tripId),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            trip?.let { entity ->
                Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            headerFormat.format(
                                Instant.ofEpochMilli(entity.startedAt).atZone(ZoneId.systemDefault()),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        entity.adapterName?.let {
                            Text(
                                "$it${entity.protocol?.let { p -> " · $p" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            StatTile("Duration", formatElapsed(entity.durationMs))
                            StatTile("Distance", "%.2f km".format(entity.distanceMeters / 1000))
                            StatTile("Samples", entity.sampleCount.toString())
                        }
                        entity.fuelEconomyLPer100?.let { econ ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                StatTile("Fuel avg", "%.1f L/100 km".format(econ))
                                StatTile(
                                    "Used",
                                    entity.fuelLitres?.let { "%.2f L".format(it) } ?: "—",
                                )
                                StatTile(
                                    "Source",
                                    when (entity.fuelSource) {
                                        "ECU_RATE" -> "ECU"
                                        "MAF_ESTIMATE" -> "MAF"
                                        else -> "—"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (dtcs.isNotEmpty() || trip?.milOn == true) {
                DtcCard(
                    codes = dtcs.map { it.code to it.kind },
                    milOn = trip?.milOn == true,
                )
            }

            SectionLabel("Route")
            Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                RouteTrace(
                    rawSamples = route,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(8.dp),
                )
            }

            if (metricKeys.isNotEmpty()) {
                SectionLabel("Charts")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    metricKeys.forEach { key ->
                        val pid = metricByKey(key)
                        FilterChip(
                            selected = selectedMetric == key,
                            onClick = { selectedMetric = key },
                            label = { Text(pid?.shortLabel ?: key) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Cyan.copy(alpha = 0.22f),
                                selectedLabelColor = Cyan,
                            ),
                        )
                    }
                }

                val pid = selectedMetric?.let { metricByKey(it) }
                Surface(
                    color = PanelRaised,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            pid?.label ?: selectedMetric.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TripLineChart(
                            points = series,
                            color = Cyan,
                            unit = pid?.unit.orEmpty(),
                            decimals = pid?.decimals ?: 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .padding(top = 10.dp),
                        )
                    }
                }
            }

            if (summaries.isNotEmpty()) {
                SectionLabel("Min / Avg / Max")
                SummaryTable(summaries)
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SummaryTable(summaries: List<TripMetricEntity>) {
    val ordered = summaries.sortedBy { metricByKey(it.metricKey)?.pid ?: Int.MAX_VALUE }
    Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("METRIC", style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.weight(1.6f))
                listOf("MIN", "AVG", "MAX").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            ordered.forEach { row ->
                val pid = metricByKey(row.metricKey)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                ) {
                    Text(
                        pid?.shortLabel ?: row.metricKey,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.6f),
                    )
                    listOf(row.minValue, row.avgValue, row.maxValue).forEach { value ->
                        Text(
                            pid?.format(value) ?: "%.1f".format(value),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DtcCard(codes: List<Pair<String, String>>, milOn: Boolean) {
    Surface(
        color = ZoneWarn.copy(alpha = 0.14f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (milOn) "Check engine light was on" else "Trouble codes recorded",
                style = MaterialTheme.typography.titleSmall,
                color = if (milOn) ZoneDanger else ZoneWarn,
                fontWeight = FontWeight.SemiBold,
            )
            if (codes.isEmpty()) {
                Text(
                    "No specific codes were readable during this trip.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            } else {
                codes.forEach { (code, kind) ->
                    Text(
                        "$code · ${kind.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
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
    )
}
