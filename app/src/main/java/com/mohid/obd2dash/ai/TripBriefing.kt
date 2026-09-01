package com.mohid.obd2dash.ai

import com.mohid.obd2dash.data.TripExportData
import com.mohid.obd2dash.data.db.MetricPack
import com.mohid.obd2dash.data.db.ReadingEntity
import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.PidRegistry
import com.mohid.obd2dash.obd.FuelEconomy
import com.mohid.obd2dash.obd.FuelUnit
import com.mohid.obd2dash.obd.metricByKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The trip, written out for a language model to read.
 *
 * Deliberately not the raw CSV. A drive at three samples a second is tens of
 * thousands of rows, which costs a fortune in tokens, will not fit in a request
 * anyway, and buries the few numbers that carry the diagnosis. What a specialist
 * would actually look at is the min/avg/max per metric, the fault codes, and how
 * far the fuel trims sat from zero — so that is what gets sent, plus a coarse
 * timeline so the model can see shape and ordering rather than only aggregates.
 *
 * Nothing here identifies the driver. The VIN is deliberately left out: it is
 * the one field in the database that ties a car to a person on a registration
 * document, and no part of the analysis needs it.
 */
object TripBriefing {

    /** Timeline rows. Enough to show shape, few enough to stay cheap. */
    private const val TIMELINE_ROWS = 40

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.UK)

    fun build(data: TripExportData, fuelUnit: FuelUnit): String = buildString {
        val trip = data.trip
        appendLine("## Trip")
        appendLine("Vehicle: ${trip.vehicleName ?: "unidentified"}")
        appendLine(
            "Started: " + stamp.format(
                Instant.ofEpochMilli(trip.startedAt).atZone(ZoneId.systemDefault()),
            ),
        )
        appendLine("Duration: ${formatDuration(trip.durationMs)}")
        appendLine("Distance: %.2f km".format(trip.distanceMeters / 1000))
        appendLine("Samples logged: ${trip.sampleCount}")
        trip.fuelEconomyLPer100?.let {
            appendLine(
                "Fuel average: ${FuelEconomy.format(it, fuelUnit)} " +
                    "(${if (trip.fuelSource == "ECU_RATE") "from the ECU's own fuel rate PID, reliable" else "estimated from MAF, good to a few percent"})",
            )
        }
        trip.fuelLitres?.let { appendLine("Fuel used: %.2f L".format(it)) }
        if (trip.idleStopCount > 0) {
            appendLine(
                "Stop/start: ${trip.idleStopCount} cuts totalling ${formatDuration(trip.idleStopMs)} " +
                    "with the engine off",
            )
        }

        appendLine()
        appendLine("## Adapter and coverage")
        appendLine("Adapter: ${trip.adapterName ?: "unknown"} on ${trip.protocol ?: "unknown protocol"}")
        appendLine("PIDs the ECU advertised: ${trip.pidsAdvertised}")
        appendLine("Of those, decodable by this app: ${trip.pidsKnown}")
        val missing = trip.pidsMissing.split(';').filter { it.isNotBlank() }
        if (missing.isNotEmpty()) {
            appendLine(
                "Advertised but silent for the whole trip: " +
                    missing.joinToString(", ") { metricByKey(it)?.label ?: it },
            )
        }

        appendLine()
        appendLine("## Diagnostics")
        appendLine("MIL (check engine light): ${if (trip.milOn) "ON" else "off"}")
        if (data.dtcs.isEmpty()) {
            appendLine("Trouble codes: none recorded during this trip")
        } else {
            appendLine("Trouble codes:")
            data.dtcs.forEach { appendLine("- ${it.code} (${it.kind.lowercase()})") }
        }
        val incomplete = trip.readinessIncomplete
        val supported = trip.readinessSupported
        if (incomplete != null && supported != null) {
            appendLine("Readiness monitors incomplete: $incomplete of $supported")
        }

        appendLine()
        appendLine("## Metric summary (min / average / max)")
        if (data.metrics.isEmpty()) {
            appendLine("No per-metric summary was recorded.")
        } else {
            for (metric in data.metrics.sortedBy { it.metricKey }) {
                val pid = metricByKey(metric.metricKey)
                val unit = pid?.unit.orEmpty()
                // The unit is appended rather than interpolated into the
                // format string: several of them are literally "%", which
                // would be read as a conversion and blow up the formatter.
                val figures = "%.1f / %.1f / %.1f".format(
                    metric.minValue,
                    metric.avgValue,
                    metric.maxValue,
                )
                appendLine(
                    "- ${pid?.label ?: metric.metricKey}: " +
                        "$figures $unit".trim() +
                        " over ${metric.sampleCount} samples",
                )
            }
        }

        val timeline = timeline(data)
        if (timeline.isNotEmpty()) {
            appendLine()
            appendLine("## Timeline")
            appendLine("Evenly spaced samples across the drive, so shape and ordering are visible.")
            appendLine(timeline)
        }
    }

    /**
     * A fixed number of evenly spaced rows across the drive, whatever its
     * length, so a five minute trip and a two hour one both come back the same
     * size and neither blows the token budget.
     */
    private fun timeline(data: TripExportData): String {
        val rows = data.readings
        if (rows.size < 2) return ""
        val keys = data.metricKeys.take(8)
        if (keys.isEmpty()) return ""
        val step = (rows.size / TIMELINE_ROWS).coerceAtLeast(1)
        val startedAt = rows.first().timestamp
        return buildString {
            append("min:s")
            keys.forEach { append(",").append(metricByKey(it)?.shortLabel ?: it) }
            appendLine()
            var i = 0
            while (i < rows.size) {
                val row = rows[i]
                val elapsed = (row.timestamp - startedAt) / 1000
                append("${elapsed / 60}:${"%02d".format(elapsed % 60)}")
                val extras = MetricPack.decode(row.extraValues)
                for (key in keys) {
                    val value = columnValue(row, key) ?: extras[key]
                    append(",")
                    if (value != null) append(value.roundToTenth()) else append("")
                }
                appendLine()
                i += step
            }
        }
    }

    /** The four metrics that live in real columns rather than the packed blob. */
    private fun columnValue(row: ReadingEntity, key: String): Float? = when (key) {
        PidRegistry.RPM.key -> row.rpm
        PidRegistry.SPEED.key -> row.speedKph
        PidRegistry.COOLANT_TEMP.key -> row.coolantC
        DerivedMetrics.BOOST.key -> row.boostKpa
        else -> null
    }

    private fun Float.roundToTenth(): String =
        if (this == this.roundToInt().toFloat()) roundToInt().toString() else "%.1f".format(this)

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%dh %02dm".format(hours, minutes) else "%dm %02ds".format(minutes, seconds)
    }
}
