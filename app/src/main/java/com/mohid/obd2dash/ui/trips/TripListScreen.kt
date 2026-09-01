package com.mohid.obd2dash.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.data.db.TripEntity
import com.mohid.obd2dash.ui.components.StatTile
import com.mohid.obd2dash.ui.components.formatElapsed
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.PanelRaised
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneWarn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun TripListScreen(graph: AppGraph, onOpenTrip: (Long) -> Unit) {
    val trips by graph.tripRepository.observeTrips().collectAsStateWithLifecycle(emptyList())
    val totalDistance by graph.tripRepository.observeTotalDistance().collectAsStateWithLifecycle(0.0)
    val scope = rememberCoroutineScope()

    if (trips.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Text("No trips yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A trip starts automatically when the adapter connects, or manually from the dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "summary") {
            Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatTile("Trips", trips.size.toString(), accent = Cyan)
                    StatTile("Total distance", "%.1f km".format(totalDistance / 1000))
                    StatTile(
                        "Logged time",
                        formatElapsed(trips.sumOf { it.durationMs }),
                    )
                }
            }
        }

        items(trips, key = { it.id }) { trip ->
            TripRow(
                trip = trip,
                onClick = { onOpenTrip(trip.id) },
                onDelete = { scope.launch { graph.tripRepository.delete(trip.id) } },
            )
        }
    }
}

@Composable
private fun TripRow(trip: TripEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val started = Instant.ofEpochMilli(trip.startedAt).atZone(ZoneId.systemDefault())
    val ongoing = trip.endedAt == null

    Surface(
        color = PanelRaised,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        trip.vehicleName ?: dayFormat.format(started),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (trip.vehicleName != null && trip.vehicleTripNumber > 0) {
                            "  trip ${trip.vehicleTripNumber}"
                        } else {
                            "  ${timeFormat.format(started)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    if (trip.dtcCount > 0 || trip.milOn) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = "Trouble codes recorded",
                            tint = ZoneWarn,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(15.dp),
                        )
                    }
                }
                Text(
                    buildString {
                        if (trip.vehicleName != null) {
                            append(dayFormat.format(started))
                            append(" ")
                            append(timeFormat.format(started))
                            append(" · ")
                        }
                        append("%.2f km".format(trip.distanceMeters / 1000))
                        append(" · ")
                        append(if (ongoing) "recording" else formatElapsed(trip.durationMs))
                        trip.fuelEconomyLPer100?.let {
                            append(" · ")
                            append("%.1f L/100 km".format(it))
                        }
                        append(" · ")
                        append("${trip.sampleCount} samples")
                        if (trip.startedManually) append(" · manual")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete trip",
                    tint = TextMuted,
                )
            }
        }
    }
}
