package com.mohid.obd2dash.ui.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohid.obd2dash.obd.DiagnosticCode
import com.mohid.obd2dash.obd.DtcCatalog
import com.mohid.obd2dash.obd.MonitorStatus
import com.mohid.obd2dash.obd.ReadinessMonitor
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.PanelRaised
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneGood
import com.mohid.obd2dash.ui.theme.ZoneWarn

/**
 * What the car knows about itself that the dashboard does not show you.
 *
 * The warning lamp on the cluster only ever reflects confirmed faults. A fault
 * the ECU has noticed once but not yet confirmed sits quietly as a pending
 * code, one that outlived a code clear sits as a permanent code, and a self
 * test that has not finished simply reports nothing at all. None of those three
 * reach the driver through the car's own instruments, and all three are worth
 * knowing about before they become a lamp on the dash.
 */
@Composable
fun HealthSection(
    status: MonitorStatus?,
    codes: List<DiagnosticCode>,
    modifier: Modifier = Modifier,
) {
    if (status == null && codes.isEmpty()) return

    Surface(
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Headline(status, codes)
            if (codes.isNotEmpty()) CodeList(codes)
            status?.monitors?.takeIf { it.isNotEmpty() }?.let { Readiness(it) }
        }
    }
}

@Composable
private fun Headline(status: MonitorStatus?, codes: List<DiagnosticCode>) {
    val milOn = status?.milOn == true
    val hidden = codes.count { it.kind != DiagnosticCode.Kind.STORED }
    val incomplete = status?.incomplete?.size ?: 0

    val (dot, title) = when {
        milOn -> ZoneDanger to "Check engine light is on"
        codes.isNotEmpty() -> ZoneWarn to "${codes.size} trouble ${plural(codes.size, "code")} stored"
        incomplete > 0 -> ZoneWarn to "$incomplete self-${plural(incomplete, "test")} not finished"
        else -> ZoneGood to "No faults reported"
    }

    val detail = when {
        hidden > 0 -> "$hidden of these never light the dashboard lamp."
        codes.isNotEmpty() -> "Confirmed by the ECU."
        incomplete > 0 ->
            "The ECU has not had the conditions it needs to check everything yet. " +
                "A full row of these usually means the codes were recently cleared or the battery was off."
        status != null -> "All ${status.supportedCount} emissions self-tests have completed and none failed."
        else -> ""
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = dot, shape = CircleShape, modifier = Modifier.size(9.dp)) {}
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }
}

@Composable
private fun CodeList(codes: List<DiagnosticCode>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        codes.forEach { code ->
            Surface(color = PanelRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            code.code,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        KindBadge(code.kind, Modifier.padding(start = 8.dp))
                    }
                    Text(
                        DtcCatalog.describe(code.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        Text(
            "Pending codes have been seen once but not confirmed, so the car's own warning lamp " +
                "stays off. Permanent codes survived a clear and only drop off once the car passes " +
                "the relevant self-test on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }
}

@Composable
private fun KindBadge(kind: DiagnosticCode.Kind, modifier: Modifier = Modifier) {
    val color = when (kind) {
        DiagnosticCode.Kind.STORED -> ZoneDanger
        DiagnosticCode.Kind.PENDING -> ZoneWarn
        DiagnosticCode.Kind.PERMANENT -> ZoneDanger
    }
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(5.dp), modifier = modifier) {
        Text(
            kind.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Readiness(monitors: List<ReadinessMonitor>) {
    val shown = monitors.filter { it.supported }
    if (shown.isEmpty()) return
    Column {
        Text(
            "EMISSIONS SELF-TESTS",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            shown.forEach { monitor ->
                val color = if (monitor.complete) ZoneGood else ZoneWarn
                Surface(
                    color = color.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(7.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(color)
                        Text(
                            monitor.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(6.dp)) {}
}

private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"
