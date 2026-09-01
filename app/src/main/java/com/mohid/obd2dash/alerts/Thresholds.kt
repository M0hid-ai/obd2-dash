package com.mohid.obd2dash.alerts

import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.PidRegistry

enum class AlertSeverity { WARNING, CRITICAL }

enum class BreachDirection { ABOVE, BELOW }

/**
 * The limits for one metric. Any of the four bounds may be null, which means
 * "don't check that side".
 */
data class ThresholdRule(
    val metricKey: String,
    val warnAbove: Float? = null,
    val criticalAbove: Float? = null,
    val warnBelow: Float? = null,
    val criticalBelow: Float? = null,
    val enabled: Boolean = true,
) {
    /** Worst bound [value] has crossed, or null when it is in range. */
    fun classify(value: Float): Pair<AlertSeverity, BreachDirection>? = when {
        criticalAbove != null && value >= criticalAbove -> AlertSeverity.CRITICAL to BreachDirection.ABOVE
        criticalBelow != null && value <= criticalBelow -> AlertSeverity.CRITICAL to BreachDirection.BELOW
        warnAbove != null && value >= warnAbove -> AlertSeverity.WARNING to BreachDirection.ABOVE
        warnBelow != null && value <= warnBelow -> AlertSeverity.WARNING to BreachDirection.BELOW
        else -> null
    }

    fun boundFor(severity: AlertSeverity, direction: BreachDirection): Float? =
        when (severity to direction) {
            AlertSeverity.CRITICAL to BreachDirection.ABOVE -> criticalAbove
            AlertSeverity.CRITICAL to BreachDirection.BELOW -> criticalBelow
            AlertSeverity.WARNING to BreachDirection.ABOVE -> warnAbove
            else -> warnBelow
        }

    fun serialize(): String = listOf(
        metricKey,
        warnAbove?.toString() ?: "",
        criticalAbove?.toString() ?: "",
        warnBelow?.toString() ?: "",
        criticalBelow?.toString() ?: "",
        if (enabled) "1" else "0",
    ).joinToString("|")

    companion object {
        fun deserialize(line: String): ThresholdRule? {
            val parts = line.split("|")
            if (parts.size < 6) return null
            return ThresholdRule(
                metricKey = parts[0].ifBlank { return null },
                warnAbove = parts[1].toFloatOrNull(),
                criticalAbove = parts[2].toFloatOrNull(),
                warnBelow = parts[3].toFloatOrNull(),
                criticalBelow = parts[4].toFloatOrNull(),
                enabled = parts[5] == "1",
            )
        }
    }
}

/**
 * Conservative starting limits for a typical OBD2 passenger vehicle. They are
 * editable because the correct limits are engine- and driver-specific.
 *
 * These are picked to sit clear of that engine's normal operating envelope.
 * Coolant settles around 90 °C and peak boost is roughly 70 kPa, so a breach
 * means something genuinely changed. The settings screen edits all of them.
 */
object DefaultThresholds {

    val rules: List<ThresholdRule> = listOf(
        ThresholdRule(
            metricKey = PidRegistry.COOLANT_TEMP.key,
            warnAbove = 105f, criticalAbove = 112f,
        ),
        ThresholdRule(
            metricKey = PidRegistry.RPM.key,
            // KF-VET fuel cut is around 7,000; warn before the driver gets there.
            warnAbove = 6300f, criticalAbove = 6900f,
        ),
        ThresholdRule(
            metricKey = DerivedMetrics.BOOST.key,
            // Stock peak is ~70 kPa (0.7 bar). Anything past 95 suggests a stuck
            // wastegate rather than a spirited on-ramp.
            warnAbove = 95f, criticalAbove = 115f,
        ),
        ThresholdRule(
            metricKey = PidRegistry.SPEED.key,
            warnAbove = 120f, criticalAbove = 140f,
        ),
        ThresholdRule(
            metricKey = PidRegistry.INTAKE_AIR_TEMP.key,
            // Heat-soaked intake on a small turbo; pulls timing when it climbs.
            warnAbove = 60f, criticalAbove = 75f,
        ),
        ThresholdRule(
            metricKey = PidRegistry.CONTROL_VOLTAGE.key,
            warnAbove = 15.0f, criticalAbove = 15.6f,
            warnBelow = 12.2f, criticalBelow = 11.6f,
        ),
        ThresholdRule(
            metricKey = PidRegistry.ENGINE_LOAD.key,
            warnAbove = 96f,
        ),
        ThresholdRule(
            metricKey = "oilTemp",
            warnAbove = 120f, criticalAbove = 135f,
        ),
        ThresholdRule(
            metricKey = "stft1",
            warnAbove = 12f, criticalAbove = 22f,
            warnBelow = -12f, criticalBelow = -22f,
        ),
        ThresholdRule(
            metricKey = "ltft1",
            warnAbove = 12f, criticalAbove = 22f,
            warnBelow = -12f, criticalBelow = -22f,
        ),
        ThresholdRule(
            metricKey = "catTempB1S1",
            warnAbove = 850f, criticalAbove = 950f,
        ),
        ThresholdRule(
            metricKey = "fuelLevel",
            warnBelow = 12f,
        ),
    )

    fun forMetric(key: String): ThresholdRule? = rules.firstOrNull { it.metricKey == key }
}
