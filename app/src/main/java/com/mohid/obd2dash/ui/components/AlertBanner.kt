package com.mohid.obd2dash.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohid.obd2dash.alerts.ActiveAlert
import com.mohid.obd2dash.alerts.AlertSeverity
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneWarn

/**
 * The persistent half of an alert.
 *
 * Deliberately not a snackbar or a toast: someone driving will not be looking
 * at the phone when the chime fires, so this stays on screen for as long as the
 * condition holds. Acknowledging silences it and drops it to a quiet summary
 * line. It still does not disappear until the reading actually recovers.
 */
@Composable
fun AlertBanner(
    alerts: List<ActiveAlert>,
    onAcknowledgeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = alerts.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        val worst = alerts.minByOrNull { if (it.severity == AlertSeverity.CRITICAL) 0 else 1 }
            ?: return@AnimatedVisibility
        val critical = worst.severity == AlertSeverity.CRITICAL
        val unacknowledged = alerts.any { !it.acknowledged }
        val accent = if (critical) ZoneDanger else ZoneWarn

        // Only an unacknowledged alert pulses; once acknowledged it holds a
        // steady, quieter state so it stops competing with the road.
        val pulse by rememberInfiniteTransition(label = "alert-pulse").animateFloat(
            initialValue = if (unacknowledged) 0.16f else 0.10f,
            targetValue = if (unacknowledged) 0.34f else 0.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (critical) 620 else 1100),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alert-pulse-alpha",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = pulse))
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        text = if (critical) "CRITICAL" else "WARNING",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = worst.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (unacknowledged) {
                    TextButton(onClick = onAcknowledgeAll) {
                        Text("Got it", color = accent)
                    }
                }
            }

            if (alerts.size > 1) {
                Text(
                    text = alerts.drop(1).joinToString("  ·  ") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                )
            }

            if (!unacknowledged) {
                Text(
                    text = "Acknowledged, clears when the reading recovers",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                )
            }
        }
    }
}
