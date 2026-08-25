package com.mohid.obd2dash.obd

/**
 * Every metric the poll loop currently believes to be true, as of [timestamp].
 *
 * Slow-tier PIDs are round-robined, so a value here may be a few hundred
 * milliseconds older than the snapshot itself; [updatedAt] carries the real
 * age of each one for anything that cares.
 */
data class MetricSnapshot(
    val timestamp: Long,
    val values: Map<String, Float>,
    val updatedAt: Map<String, Long> = emptyMap(),
) {
    operator fun get(key: String): Float? = values[key]

    operator fun get(pid: ObdPid): Float? = values[pid.key]

    fun ageOf(key: String, now: Long = timestamp): Long? = updatedAt[key]?.let { now - it }

    val isEmpty: Boolean get() = values.isEmpty()

    companion object {
        val EMPTY = MetricSnapshot(0L, emptyMap())
    }
}
