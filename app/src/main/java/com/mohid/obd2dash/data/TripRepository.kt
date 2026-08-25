package com.mohid.obd2dash.data

import com.mohid.obd2dash.data.db.AppDatabase
import com.mohid.obd2dash.data.db.DtcEventEntity
import com.mohid.obd2dash.data.db.MetricPack
import com.mohid.obd2dash.data.db.ReadingEntity
import com.mohid.obd2dash.data.db.RoutePoint
import com.mohid.obd2dash.data.db.TripEntity
import com.mohid.obd2dash.data.db.TripMetricEntity
import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.PidRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** One point on a trip chart. */
data class SeriesPoint(
    val elapsedMs: Long,
    val value: Float,
)

class TripRepository(private val db: AppDatabase) {

    private companion object {
        /**
         * A half-hour trip at 3 Hz is ~5,000 samples. Charts are a few hundred
         * pixels wide, so anything past this is invisible detail that only
         * costs allocation and draw time.
         */
        const val MAX_CHART_POINTS = 480
    }

    fun observeTrips(): Flow<List<TripEntity>> = db.tripDao().observeAll()

    fun observeTrip(tripId: Long): Flow<TripEntity?> = db.tripDao().observe(tripId)

    fun observeMetrics(tripId: Long): Flow<List<TripMetricEntity>> =
        db.tripMetricDao().observeForTrip(tripId)

    fun observeDtcs(tripId: Long): Flow<List<DtcEventEntity>> =
        db.dtcEventDao().observeForTrip(tripId)

    fun observeTripCount(): Flow<Int> = db.tripDao().observeTripCount()

    fun observeTotalDistance(): Flow<Double> = db.tripDao().observeTotalDistance()

    suspend fun delete(tripId: Long) = withContext(Dispatchers.IO) {
        db.tripDao().delete(tripId)
    }

    suspend fun route(tripId: Long): List<RoutePoint> = withContext(Dispatchers.IO) {
        db.readingDao().routeForTrip(tripId)
    }

    /**
     * Reads one metric back out as a chart series, downsampled to
     * [MAX_CHART_POINTS] buckets.
     *
     * Each bucket keeps its most extreme value rather than its mean, so a brief
     * coolant spike or an overboost survives the downsample. Losing those is
     * exactly what makes a post-trip chart useless.
     */
    suspend fun series(tripId: Long, metricKey: String): List<SeriesPoint> =
        withContext(Dispatchers.IO) {
            val readings = db.readingDao().forTrip(tripId)
            if (readings.isEmpty()) return@withContext emptyList()

            val startedAt = readings.first().timestamp
            val raw = ArrayList<SeriesPoint>(readings.size)
            for (reading in readings) {
                val value = valueOf(reading, metricKey) ?: continue
                raw += SeriesPoint(reading.timestamp - startedAt, value)
            }
            downsample(raw)
        }

    /** Which metrics this trip actually captured, in registry order. */
    suspend fun recordedMetrics(tripId: Long): List<String> = withContext(Dispatchers.IO) {
        val summaries = db.tripMetricDao().forTrip(tripId).map { it.metricKey }.toSet()
        if (summaries.isNotEmpty()) return@withContext orderMetrics(summaries)
        // Fall back to inspecting a sample, for a trip that never got summarised.
        val sample = db.readingDao().forTrip(tripId).firstOrNull() ?: return@withContext emptyList()
        val keys = MetricPack.decode(sample.extraValues).keys.toMutableSet()
        if (sample.rpm != null) keys += PidRegistry.RPM.key
        if (sample.speedKph != null) keys += PidRegistry.SPEED.key
        if (sample.coolantC != null) keys += PidRegistry.COOLANT_TEMP.key
        if (sample.boostKpa != null) keys += DerivedMetrics.BOOST.key
        orderMetrics(keys)
    }

    private fun orderMetrics(keys: Set<String>): List<String> {
        val preferred = listOf(
            PidRegistry.RPM.key,
            PidRegistry.SPEED.key,
            DerivedMetrics.BOOST.key,
            PidRegistry.COOLANT_TEMP.key,
        )
        val rest = (PidRegistry.all.map { it.key } - preferred.toSet()).filter { it in keys }
        return preferred.filter { it in keys } + rest
    }

    private fun valueOf(reading: ReadingEntity, metricKey: String): Float? = when (metricKey) {
        PidRegistry.RPM.key -> reading.rpm
        PidRegistry.SPEED.key -> reading.speedKph
        PidRegistry.COOLANT_TEMP.key -> reading.coolantC
        DerivedMetrics.BOOST.key -> reading.boostKpa
        else -> MetricPack.decode(reading.extraValues)[metricKey]
    }

    private fun downsample(points: List<SeriesPoint>): List<SeriesPoint> {
        if (points.size <= MAX_CHART_POINTS) return points
        val bucketSize = points.size.toDouble() / MAX_CHART_POINTS
        val out = ArrayList<SeriesPoint>(MAX_CHART_POINTS)
        var index = 0
        while (index < points.size) {
            val end = minOf(((out.size + 1) * bucketSize).toInt(), points.size)
            if (end <= index) break
            var pick = points[index]
            for (i in index until end) {
                // Keep whichever sample is furthest from zero so spikes survive.
                if (kotlin.math.abs(points[i].value) > kotlin.math.abs(pick.value)) pick = points[i]
            }
            out += pick
            index = end
        }
        return out
    }
}
