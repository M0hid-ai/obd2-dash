package com.mohid.obd2dash.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.MetricSnapshot
import com.mohid.obd2dash.obd.ObdPid
import com.mohid.obd2dash.obd.PidGroup
import com.mohid.obd2dash.ui.components.MetricCard
import com.mohid.obd2dash.ui.components.zoneOf
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.TextMuted

/**
 * Everything the ECU answers to, beyond the four on the main dial.
 *
 * The list is built from the runtime PID scan, not a fixed table. What a
 * Daihatsu KF-VET reports is not what the next car will, and a card showing a
 * permanent dash is worse than no card.
 */
@Composable
fun AllMetricsScreen(graph: AppGraph) {
    val snapshot by graph.controller.snapshot.collectAsStateWithLifecycle()
    val supported by graph.controller.supportedPids.collectAsStateWithLifecycle()
    val settings by graph.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())

    // Boost belongs with the primaries even though no ECU reports it directly.
    val displayed = remember(supported) {
        val withDerived = if (supported.any { it.key == "map" }) {
            supported + DerivedMetrics.BOOST
        } else {
            supported
        }
        withDerived.groupBy { it.group }.toSortedMap(compareBy { it.ordinal })
    }

    if (supported.isEmpty()) {
        EmptyState()
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        displayed.forEach { (group, pids) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "header-${group.name}") {
                GroupHeader(group, pids.size)
            }
            items(pids, key = { it.key }) { pid ->
                MetricCardFor(pid, snapshot, settings)
            }
        }
    }
}

@Composable
private fun MetricCardFor(pid: ObdPid, snapshot: MetricSnapshot, settings: AppSettings) {
    val value = snapshot[pid.key]
    val span = (pid.displayMax - pid.displayMin).takeIf { it > 0f } ?: 1f
    val rule = settings.thresholdFor(pid.key)

    // Slow-tier PIDs are refreshed on a rotation, so anything not seen for a
    // few seconds is dimmed rather than silently presented as current.
    val stale = (snapshot.ageOf(pid.key) ?: 0L) > 8_000L

    MetricCard(
        label = pid.shortLabel,
        value = value?.let { pid.format(it) } ?: "--",
        unit = pid.unit,
        fraction = value?.let { ((it - pid.displayMin) / span) },
        zone = zoneOf(value, rule),
        stale = stale && value != null,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GroupHeader(group: PidGroup, count: Int) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            text = group.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Cyan,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "$count reported by this ECU",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                "Nothing scanned yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Connect to the adapter and the supported PIDs will be discovered automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
