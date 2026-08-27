package com.mohid.obd2dash.ui.components

import androidx.compose.ui.graphics.Color
import com.mohid.obd2dash.alerts.ThresholdRule
import com.mohid.obd2dash.obd.ObdPid
import com.mohid.obd2dash.ui.theme.Zone
import com.mohid.obd2dash.ui.theme.ZoneDanger
import com.mohid.obd2dash.ui.theme.ZoneGood
import com.mohid.obd2dash.ui.theme.ZoneWarn

/**
 * Turns a metric's alert thresholds into the coloured bands painted on its
 * gauge, so the dial and the alerts can never disagree about what "too hot"
 * means. Edit a threshold in settings and the gauge redraws to match.
 *
 * [healthy] is the one thing the user's accent colour choice is allowed to
 * touch: the "everything is fine" band. Warning and danger stay fixed at
 * amber and red regardless, so the glanceable safety coding never changes
 * meaning just because someone picked a different accent.
 */
fun zonesFor(pid: ObdPid, rule: ThresholdRule?, min: Float, max: Float, healthy: Color = ZoneGood): List<GaugeZone> {
    if (rule == null || !rule.enabled) {
        return listOf(GaugeZone(min, max, healthy, healthy = true))
    }

    val bands = mutableListOf<GaugeZone>()

    // Low side, read outward from the bottom of the dial.
    val criticalLow = rule.criticalBelow?.coerceIn(min, max)
    val warnLow = rule.warnBelow?.coerceIn(min, max)
    var cursor = min
    if (criticalLow != null && criticalLow > cursor) {
        bands += GaugeZone(cursor, criticalLow, ZoneDanger)
        cursor = criticalLow
    }
    if (warnLow != null && warnLow > cursor) {
        bands += GaugeZone(cursor, warnLow, ZoneWarn)
        cursor = warnLow
    }

    // High side.
    val warnHigh = rule.warnAbove?.coerceIn(min, max)
    val criticalHigh = rule.criticalAbove?.coerceIn(min, max)
    val healthyEnd = warnHigh ?: criticalHigh ?: max
    if (healthyEnd > cursor) {
        bands += GaugeZone(cursor, healthyEnd, healthy, healthy = true)
        cursor = healthyEnd
    }
    if (criticalHigh != null && criticalHigh > cursor) {
        bands += GaugeZone(cursor, criticalHigh, ZoneWarn)
        cursor = criticalHigh
    }
    if (max > cursor) {
        val tailIsHealthy = criticalHigh == null && warnHigh == null
        val tailColor = when {
            criticalHigh != null -> ZoneDanger
            warnHigh != null -> ZoneWarn
            else -> healthy
        }
        bands += GaugeZone(cursor, max, tailColor, healthy = tailIsHealthy)
    }

    return bands.ifEmpty { listOf(GaugeZone(min, max, healthy, healthy = true)) }
}

/** Which band a live value currently sits in, for the dot on a metric card. */
fun zoneOf(value: Float?, rule: ThresholdRule?): Zone {
    if (value == null) return Zone.UNKNOWN
    if (rule == null || !rule.enabled) return Zone.GOOD
    return when (rule.classify(value)?.first) {
        com.mohid.obd2dash.alerts.AlertSeverity.CRITICAL -> Zone.DANGER
        com.mohid.obd2dash.alerts.AlertSeverity.WARNING -> Zone.WARN
        null -> Zone.GOOD
    }
}
