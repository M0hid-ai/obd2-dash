package com.mohid.obd2dash.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.mohid.obd2dash.data.db.MetricPack
import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.DiagnosticCode
import com.mohid.obd2dash.obd.DtcCatalog
import com.mohid.obd2dash.obd.PidRegistry
import com.mohid.obd2dash.obd.metricByKey
import kotlinx.coroutines.Dispatchers
import com.mohid.obd2dash.obd.FuelEconomy
import com.mohid.obd2dash.obd.FuelUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A file staged in the cache directory, ready to hand to the share sheet. */
data class ExportedFile(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
)

enum class ExportFormat(val label: String, val blurb: String) {
    REPORT("Report", "A single self-contained page: tables, charts and the route, readable in any browser."),
    CSV("Raw CSV", "Every logged sample as one row, for a spreadsheet or your own analysis."),
}

/**
 * Turns a finished trip into something you can send someone.
 *
 * The report is one HTML file with the charts drawn as inline SVG rather than
 * as pictures. That is deliberate: a screenshot of a chart is a fixed number of
 * pixels that goes soft the moment anyone zooms in, and a folder of them is
 * awkward to send. Vector paths stay sharp at any size, the numbers behind them
 * stay selectable and searchable as real table text, and the whole trip travels
 * as a single attachment with no images at all.
 */
class TripExporter(
    private val context: Context,
    private val repository: TripRepository,
    private val settingsStore: SettingsStore,
) {

    private companion object {
        const val CHART_W = 720
        const val CHART_H = 200
        const val ROUTE_SIZE = 520

        /** Below this the "route" is GPS noise around a parked car. */
        const val MIN_ROUTE_SPAN_M = 25

        /** Charts worth putting in a report, in the order they should appear. */
        val HEADLINE_METRICS = listOf(
            PidRegistry.RPM.key,
            PidRegistry.SPEED.key,
            DerivedMetrics.BOOST.key,
            PidRegistry.COOLANT_TEMP.key,
            PidRegistry.ENGINE_LOAD.key,
            PidRegistry.THROTTLE.key,
            PidRegistry.MAP.key,
            PidRegistry.INTAKE_AIR_TEMP.key,
            "stft1",
            "ltft1",
        )
    }

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm", Locale.UK)
    private val fileStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.UK)

    suspend fun export(tripId: Long, format: ExportFormat): ExportedFile? =
        withContext(Dispatchers.IO) {
            val data = repository.snapshotForExport(tripId) ?: return@withContext null
            // Read once per export rather than held: a report is a snapshot of
            // how the app was set up at the moment it was produced.
            val fuelUnit = settingsStore.settings.first().fuelUnit
            val zoned = Instant.ofEpochMilli(data.trip.startedAt).atZone(ZoneId.systemDefault())
            val base = "trip-$tripId-${fileStamp.format(zoned)}"
            when (format) {
                ExportFormat.REPORT -> write("$base.html", "text/html", buildHtml(data, fuelUnit))
                ExportFormat.CSV -> write("$base.csv", "text/csv", buildCsv(data))
            }
        }

    /** A share intent for [file], already granted read access to whoever receives it. */
    fun shareIntent(file: ExportedFile, tripId: Long): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, file.uri)
            putExtra(Intent.EXTRA_SUBJECT, "Trip #$tripId")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share trip #$tripId")
    }

    private fun write(fileName: String, mimeType: String, body: String): ExportedFile {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Only the newest export of each trip is worth keeping around.
        dir.listFiles()?.forEach { if (it.name == fileName) it.delete() }
        val file = File(dir, fileName)
        file.writeText(body)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        return ExportedFile(uri, fileName, mimeType)
    }

    // ---- CSV ---------------------------------------------------------------

    private fun buildCsv(data: TripExportData): String {
        val extraKeys = LinkedHashSet<String>()
        for (row in data.readings) extraKeys += MetricPack.decode(row.extraValues).keys
        val ordered = extraKeys.sortedBy { metricByKey(it)?.pid ?: Int.MAX_VALUE }

        val sb = StringBuilder(data.readings.size * 96)
        sb.append("timestampMs,elapsedMs,rpm,speedKph,coolantC,boostKpa,latitude,longitude,gpsSpeedKph")
        for (key in ordered) sb.append(',').append(key)
        sb.append('\n')

        val startedAt = data.readings.firstOrNull()?.timestamp ?: data.trip.startedAt
        for (row in data.readings) {
            val extras = MetricPack.decode(row.extraValues)
            sb.append(row.timestamp).append(',')
                .append(row.timestamp - startedAt).append(',')
                .append(row.rpm.csv()).append(',')
                .append(row.speedKph.csv()).append(',')
                .append(row.coolantC.csv()).append(',')
                .append(row.boostKpa.csv()).append(',')
                .append(row.latitude?.toString().orEmpty()).append(',')
                .append(row.longitude?.toString().orEmpty()).append(',')
                .append(row.gpsSpeedKph.csv())
            for (key in ordered) sb.append(',').append(extras[key].csv())
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun Float?.csv(): String = this?.let { "%.3f".format(Locale.UK, it).trimEnd('0').trimEnd('.') }.orEmpty()

    // ---- HTML report -------------------------------------------------------

    private fun buildHtml(data: TripExportData, fuelUnit: FuelUnit): String {
        val trip = data.trip
        val started = Instant.ofEpochMilli(trip.startedAt).atZone(ZoneId.systemDefault())
        val sb = StringBuilder(32_000)

        sb.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Trip #").append(trip.id).append(" &middot; ").append(esc(stamp.format(started)))
        sb.append("</title>").append(CSS).append("</head><body><main>")

        sb.append("<header><p class=\"eyebrow\">OBD2 Dash trip report</p>")
        sb.append("<h1>Trip #").append(trip.id).append("</h1>")
        sb.append("<p class=\"sub\">").append(esc(stamp.format(started)))
        trip.adapterName?.let { sb.append(" &middot; ").append(esc(it)) }
        trip.protocol?.takeIf { it.isNotBlank() }?.let { sb.append(" &middot; ").append(esc(it)) }
        sb.append("</p></header>")

        // Headline numbers.
        sb.append("<section class=\"tiles\">")
        tile(sb, "Duration", formatElapsed(trip.durationMs))
        tile(sb, "Distance", "%.2f km".format(Locale.UK, trip.distanceMeters / 1000))
        tile(sb, "Samples", trip.sampleCount.toString())
        trip.fuelEconomyLPer100?.let {
            tile(sb, "Fuel average", FuelEconomy.format(it, fuelUnit))
        }
        trip.fuelLitres?.let {
            tile(sb, "Fuel used", "%.2f L".format(Locale.UK, it))
        }
        tile(sb, "Started", if (trip.startedManually) "Manually" else "Engine on")
        sb.append("</section>")

        appendDiagnostics(sb, data)
        appendCharts(sb, data)
        appendRoute(sb, data)
        appendSummaryTable(sb, data)

        sb.append("<footer><p>Generated by OBD2 Dash. Charts are vector paths, not images, so they stay ")
        sb.append("sharp at any zoom and the numbers behind them are in the table above.</p></footer>")
        sb.append("</main></body></html>")
        return sb.toString()
    }

    private fun tile(sb: StringBuilder, label: String, value: String) {
        sb.append("<div class=\"tile\"><span class=\"k\">").append(esc(label))
        sb.append("</span><span class=\"v\">").append(esc(value)).append("</span></div>")
    }

    private fun appendDiagnostics(sb: StringBuilder, data: TripExportData) {
        val trip = data.trip
        val incomplete = trip.readinessIncomplete ?: 0
        val hasSomething = data.dtcs.isNotEmpty() || trip.milOn || incomplete > 0
        if (!hasSomething) {
            sb.append("<section><h2>Diagnostics</h2><p class=\"ok\">No trouble codes were seen ")
            sb.append("during this trip, the check engine light stayed off")
            if (trip.readinessSupported != null) {
                sb.append(", and all ").append(trip.readinessSupported)
                sb.append(" emissions self-tests had already completed")
            }
            sb.append(".</p></section>")
            return
        }

        sb.append("<section><h2>Diagnostics</h2>")
        if (trip.milOn) {
            sb.append("<p class=\"bad\">The check engine light was on during this trip.</p>")
        }
        if (data.dtcs.isNotEmpty()) {
            sb.append("<table><thead><tr><th>Code</th><th>Kind</th><th>Meaning</th></tr></thead><tbody>")
            for (event in data.dtcs) {
                val kind = DiagnosticCode.Kind.entries.firstOrNull { it.name == event.kind }
                sb.append("<tr><td class=\"mono\">").append(esc(event.code)).append("</td><td>")
                sb.append(esc(kind?.label ?: event.kind)).append("</td><td>")
                sb.append(esc(DtcCatalog.describe(event.code))).append("</td></tr>")
            }
            sb.append("</tbody></table>")
            sb.append("<p class=\"note\">Pending codes have been seen once but not confirmed, so they ")
            sb.append("do not light the dashboard lamp. Permanent codes survived a code clear and only ")
            sb.append("drop off once the car passes the relevant self-test on its own.</p>")
        }
        if (incomplete > 0 && trip.readinessSupported != null) {
            sb.append("<p class=\"warn\">").append(incomplete).append(" of ")
            sb.append(trip.readinessSupported).append(" emissions self-tests had not finished. ")
            sb.append("That is normal for a short drive after a battery disconnect or a code clear, ")
            sb.append("and it means those systems could not be assessed on this trip.</p>")
        }
        sb.append("</section>")
    }

    private fun appendCharts(sb: StringBuilder, data: TripExportData) {
        val keys = HEADLINE_METRICS.filter { (data.series[it]?.size ?: 0) >= 2 }
        if (keys.isEmpty()) return
        sb.append("<section><h2>Charts</h2>")
        for (key in keys) {
            val points = data.series[key] ?: continue
            val pid = metricByKey(key)
            sb.append("<figure><figcaption>").append(esc(pid?.label ?: key))
            pid?.unit?.takeIf { it.isNotBlank() }?.let { sb.append(" <span>(").append(esc(it)).append(")</span>") }
            sb.append("</figcaption>")
            appendLineChart(sb, points, pid?.decimals ?: 1)
            sb.append("</figure>")
        }
        sb.append("</section>")
    }

    /** One metric as an SVG polyline with a filled area under it. */
    private fun appendLineChart(sb: StringBuilder, points: List<SeriesPoint>, decimals: Int) {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (p in points) {
            lo = min(lo, p.value)
            hi = max(hi, p.value)
        }
        // A dead-flat series would divide by zero and draw a line on the floor
        // rather than through the middle, so give it a nominal band.
        if (abs(hi - lo) < 1e-4f) {
            lo -= 1f
            hi += 1f
        }
        val span = hi - lo
        val duration = (points.last().elapsedMs - points.first().elapsedMs).coerceAtLeast(1L)
        val first = points.first().elapsedMs

        fun x(p: SeriesPoint) = (p.elapsedMs - first).toFloat() / duration * CHART_W
        fun y(p: SeriesPoint) = CHART_H - (p.value - lo) / span * (CHART_H - 12f) - 6f

        val line = StringBuilder(points.size * 14)
        for (p in points) {
            if (line.isNotEmpty()) line.append(' ')
            line.append("%.1f,%.1f".format(Locale.UK, x(p), y(p)))
        }

        sb.append("<svg viewBox=\"0 0 ").append(CHART_W).append(' ').append(CHART_H)
        sb.append("\" role=\"img\" preserveAspectRatio=\"none\">")
        // Gridlines at the quarter points give the eye something to measure against.
        for (i in 1..3) {
            val gy = CHART_H * i / 4f
            sb.append("<line class=\"grid\" x1=\"0\" y1=\"").append("%.1f".format(Locale.UK, gy))
            sb.append("\" x2=\"").append(CHART_W).append("\" y2=\"")
            sb.append("%.1f".format(Locale.UK, gy)).append("\"/>")
        }
        sb.append("<polygon class=\"area\" points=\"0,").append(CHART_H).append(' ')
        sb.append(line).append(' ').append(CHART_W).append(',').append(CHART_H).append("\"/>")
        sb.append("<polyline class=\"line\" points=\"").append(line).append("\"/>")
        sb.append("</svg>")

        sb.append("<p class=\"axis\"><span>min ").append(fmt(lo, decimals)).append("</span>")
        sb.append("<span>").append(formatElapsed(duration)).append("</span>")
        sb.append("<span>max ").append(fmt(hi, decimals)).append("</span></p>")
    }

    private fun appendRoute(sb: StringBuilder, data: TripExportData) {
        val route = data.route
        if (route.size < 2) return

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in route) {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        // Latitude degrees are always the same length; longitude degrees shrink
        // toward the poles. Without that correction a north-south route comes
        // out visibly stretched sideways.
        val lonScale = Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val latSpan = maxLat - minLat
        val lonSpan = (maxLon - minLon) * lonScale

        // A car that never moved, or a phone that only ever handed out one
        // fix, gives a bounding box of nothing. Drawing that produces a single
        // dot and a section that says less than leaving it out, so it is left
        // out. One degree of latitude is roughly 111 km.
        if (max(latSpan, lonSpan) * 111_000 < MIN_ROUTE_SPAN_M) return

        val scale = ROUTE_SIZE / max(latSpan, lonSpan)
        val w = lonSpan * scale
        val h = latSpan * scale

        sb.append("<section><h2>Route</h2><figure>")
        sb.append("<svg viewBox=\"0 0 ").append("%.0f %.0f".format(Locale.UK, w + 8, h + 8))
        sb.append("\" role=\"img\" class=\"route\">")
        val path = StringBuilder(route.size * 14)
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (p in route) {
            val px = ((p.longitude - minLon) * lonScale * scale + 4).toFloat()
            val py = ((maxLat - p.latitude) * scale + 4).toFloat()
            // Stationary stretches log the same fix over and over. Those repeats
            // draw nothing and are most of the markup on a trip with traffic
            // lights in it, so only movement is written out.
            if (abs(px - lastX) < 0.5f && abs(py - lastY) < 0.5f) continue
            if (path.isNotEmpty()) path.append(' ')
            path.append("%.1f,%.1f".format(Locale.UK, px, py))
            lastX = px
            lastY = py
        }
        sb.append("<polyline class=\"track\" points=\"").append(path).append("\"/>")
        sb.append("</svg><figcaption>")
        sb.append("%.2f km logged from the phone's GPS".format(Locale.UK, data.trip.distanceMeters / 1000))
        sb.append("</figcaption></figure></section>")
    }

    private fun appendSummaryTable(sb: StringBuilder, data: TripExportData) {
        if (data.metrics.isEmpty()) return
        val ordered = data.metrics.sortedBy { metricByKey(it.metricKey)?.pid ?: Int.MAX_VALUE }
        sb.append("<section><h2>Min / average / max</h2><table><thead><tr>")
        sb.append("<th>Metric</th><th>Unit</th><th class=\"n\">Min</th><th class=\"n\">Avg</th>")
        sb.append("<th class=\"n\">Max</th><th class=\"n\">Samples</th></tr></thead><tbody>")
        for (row in ordered) {
            val pid = metricByKey(row.metricKey)
            sb.append("<tr><td>").append(esc(pid?.label ?: row.metricKey)).append("</td>")
            sb.append("<td>").append(esc(pid?.unit.orEmpty())).append("</td>")
            for (value in listOf(row.minValue, row.avgValue, row.maxValue)) {
                sb.append("<td class=\"n mono\">").append(pid?.format(value) ?: fmt(value, 1)).append("</td>")
            }
            sb.append("<td class=\"n mono\">").append(row.sampleCount).append("</td></tr>")
        }
        sb.append("</tbody></table></section>")
    }

    private fun fmt(value: Float, decimals: Int): String =
        if (decimals == 0) value.toInt().toString() else "%.${decimals}f".format(Locale.UK, value)

    private fun esc(text: String): String = buildString(text.length + 8) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

internal fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.UK, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.UK, minutes, seconds)
    }
}

/**
 * Styling for the report.
 *
 * Inline, because the file has to survive being emailed, dropped in a chat or
 * opened straight off the filesystem with no network. Colours follow the
 * viewer's own light or dark preference rather than forcing the app's dark
 * theme onto a page that might get printed.
 */
private val CSS = """
<style>
:root{--bg:#f7f8fa;--panel:#fff;--ink:#12161c;--muted:#5b6472;--line:#e2e6ec;--accent:#0d8fa4;--warn:#b26a00;--bad:#c02b2b;--ok:#1f7a4d;}
@media (prefers-color-scheme:dark){:root{--bg:#0b0e13;--panel:#141920;--ink:#e8edf4;--muted:#8b96a6;--line:#232c37;--accent:#35d0e0;--warn:#e8a33d;--bad:#ff5c5c;--ok:#2ed573;}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;}
main{max-width:940px;margin:0 auto;padding:28px 18px 56px}
header{border-bottom:1px solid var(--line);padding-bottom:16px;margin-bottom:22px}
.eyebrow{margin:0;color:var(--accent);font-size:12px;letter-spacing:.14em;text-transform:uppercase;font-weight:700}
h1{margin:6px 0 4px;font-size:30px;letter-spacing:-.02em}
.sub{margin:0;color:var(--muted);font-size:14px}
h2{font-size:13px;letter-spacing:.12em;text-transform:uppercase;color:var(--accent);margin:34px 0 12px}
section:first-of-type h2{margin-top:0}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:8px}
.tile{background:var(--panel);border:1px solid var(--line);border-radius:11px;padding:13px 15px;display:flex;flex-direction:column;gap:3px}
.tile .k{font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}
.tile .v{font-size:21px;font-weight:650;letter-spacing:-.01em}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:11px;overflow:hidden;font-size:14px}
th,td{padding:8px 12px;text-align:left;border-bottom:1px solid var(--line)}
th{font-size:11px;letter-spacing:.09em;text-transform:uppercase;color:var(--muted);font-weight:600}
tbody tr:last-child td{border-bottom:none}
td.n,th.n{text-align:right}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-variant-numeric:tabular-nums}
figure{margin:0 0 18px;background:var(--panel);border:1px solid var(--line);border-radius:11px;padding:13px 15px}
figcaption{font-size:14px;font-weight:600;margin-bottom:8px}
figcaption span{color:var(--muted);font-weight:400}
svg{display:block;width:100%;height:auto}
svg:not(.route){height:190px}
.grid{stroke:var(--line);stroke-width:1}
.line{fill:none;stroke:var(--accent);stroke-width:2;vector-effect:non-scaling-stroke;stroke-linejoin:round}
.area{fill:var(--accent);opacity:.11;stroke:none}
.track{fill:none;stroke:var(--accent);stroke-width:2.5;stroke-linejoin:round;stroke-linecap:round}
.route{max-height:420px}
.axis{display:flex;justify-content:space-between;margin:6px 0 0;font-size:11px;color:var(--muted);font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.note{font-size:13px;color:var(--muted);margin:10px 0 0}
.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad);font-weight:600}
footer{margin-top:38px;padding-top:14px;border-top:1px solid var(--line);color:var(--muted);font-size:12px}
</style>
""".trimIndent()
