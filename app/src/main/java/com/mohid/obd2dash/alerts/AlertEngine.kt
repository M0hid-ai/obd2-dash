package com.mohid.obd2dash.alerts

import com.mohid.obd2dash.obd.MetricSnapshot
import com.mohid.obd2dash.obd.metricByKey

data class ActiveAlert(
    val metricKey: String,
    val severity: AlertSeverity,
    val direction: BreachDirection,
    val value: Float,
    val bound: Float,
    val raisedAt: Long,
    val acknowledged: Boolean = false,
) {
    val label: String get() = metricByKey(metricKey)?.label ?: metricKey

    val unit: String get() = metricByKey(metricKey)?.unit.orEmpty()

    /** e.g. "Coolant Temperature 114 °C, above 112 °C" */
    val message: String
        get() {
            val pid = metricByKey(metricKey)
            val shown = pid?.format(value) ?: value.toString()
            val limit = pid?.format(bound) ?: bound.toString()
            val word = if (direction == BreachDirection.ABOVE) "above" else "below"
            val suffix = if (unit.isEmpty()) "" else " $unit"
            return "$label $shown$suffix, $word $limit$suffix"
        }
}

data class AlertUpdate(
    val active: List<ActiveAlert>,
    /** Alerts raised or escalated to CRITICAL on this evaluation. These are the ones worth a sound. */
    val newlyRaised: List<ActiveAlert>,
)

/**
 * Threshold checking for the poll loop.
 *
 * Two pieces of hysteresis keep it from flapping, which matters because a
 * banner that blinks on and off is worse than useless to someone driving:
 *
 *  - a breach must persist for [RAISE_AFTER_SAMPLES] consecutive samples
 *    before it raises anything, so one corrupt frame is ignored;
 *  - a raised alert only clears once the value comes back inside its bound by
 *    a margin, rather than the instant it grazes the line.
 *
 * Alerts persist until they clear. Acknowledging one silences it and lets the
 * UI de-emphasise it, but it stays in the list while the condition holds.
 */
class AlertEngine {

    private companion object {
        const val RAISE_AFTER_SAMPLES = 3

        /** Re-entry margin, as a fraction of the metric's display range. */
        const val HYSTERESIS_FRACTION = 0.02f
    }

    private var rules: Map<String, ThresholdRule> = emptyMap()
    private val breachStreak = HashMap<String, Int>()
    private val active = LinkedHashMap<String, ActiveAlert>()

    fun setRules(newRules: List<ThresholdRule>) {
        rules = newRules.filter { it.enabled }.associateBy { it.metricKey }
        // Drop anything whose rule just disappeared or was switched off.
        active.keys.retainAll(rules.keys)
        breachStreak.keys.retainAll(rules.keys)
    }

    fun evaluate(snapshot: MetricSnapshot, now: Long = snapshot.timestamp): AlertUpdate {
        val raised = mutableListOf<ActiveAlert>()

        for ((key, rule) in rules) {
            val value = snapshot[key] ?: continue
            val breach = rule.classify(value)

            if (breach == null) {
                breachStreak[key] = 0
                val existing = active[key] ?: continue
                if (canClear(rule, existing, value)) active.remove(key)
                continue
            }

            val (severity, direction) = breach
            val streak = (breachStreak[key] ?: 0) + 1
            breachStreak[key] = streak

            val existing = active[key]
            if (existing == null) {
                if (streak < RAISE_AFTER_SAMPLES) continue
                val alert = ActiveAlert(
                    metricKey = key,
                    severity = severity,
                    direction = direction,
                    value = value,
                    bound = rule.boundFor(severity, direction) ?: value,
                    raisedAt = now,
                )
                active[key] = alert
                raised += alert
                continue
            }

            val escalated = severity == AlertSeverity.CRITICAL && existing.severity == AlertSeverity.WARNING
            val updated = existing.copy(
                severity = severity,
                direction = direction,
                value = value,
                bound = rule.boundFor(severity, direction) ?: existing.bound,
                // An escalation is a new event: it should sound and un-acknowledge.
                acknowledged = if (escalated) false else existing.acknowledged,
            )
            active[key] = updated
            if (escalated) raised += updated
        }

        return AlertUpdate(active = active.values.toList(), newlyRaised = raised)
    }

    /**
     * True once [value] has returned inside the rule's innermost bound on the
     * breached side by the hysteresis margin.
     */
    private fun canClear(rule: ThresholdRule, alert: ActiveAlert, value: Float): Boolean {
        val margin = marginFor(alert.metricKey)
        return when (alert.direction) {
            BreachDirection.ABOVE -> {
                val bound = rule.warnAbove ?: rule.criticalAbove ?: return true
                value <= bound - margin
            }
            BreachDirection.BELOW -> {
                val bound = rule.warnBelow ?: rule.criticalBelow ?: return true
                value >= bound + margin
            }
        }
    }

    private fun marginFor(metricKey: String): Float {
        val pid = metricByKey(metricKey) ?: return 0f
        return (pid.displayMax - pid.displayMin) * HYSTERESIS_FRACTION
    }

    fun acknowledge(metricKey: String) {
        active[metricKey]?.let { active[metricKey] = it.copy(acknowledged = true) }
    }

    fun acknowledgeAll() {
        for (key in active.keys.toList()) {
            active[key] = active.getValue(key).copy(acknowledged = true)
        }
    }

    fun snapshot(): List<ActiveAlert> = active.values.toList()

    fun reset() {
        active.clear()
        breachStreak.clear()
    }
}
