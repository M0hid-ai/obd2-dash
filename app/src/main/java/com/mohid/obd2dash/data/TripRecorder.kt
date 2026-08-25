package com.mohid.obd2dash.data

import android.location.Location
import android.util.Log
import com.mohid.obd2dash.data.db.AppDatabase
import com.mohid.obd2dash.data.db.DtcEventEntity
import com.mohid.obd2dash.data.db.MetricPack
import com.mohid.obd2dash.data.db.ReadingEntity
import com.mohid.obd2dash.data.db.TripEntity
import com.mohid.obd2dash.data.db.TripMetricEntity
import com.mohid.obd2dash.location.DistanceAccumulator
import com.mohid.obd2dash.obd.DerivedMetrics
import com.mohid.obd2dash.obd.MetricSnapshot
import com.mohid.obd2dash.obd.PidRegistry
import com.mohid.obd2dash.obd.DiagnosticCode

/**
 * Writes one trip to Room.
 *
 * [record] is called from the poll loop several times a second, so samples are
 * buffered and flushed in batches. A database round trip per reading would
 * pace the poll loop instead of the adapter doing it.
 *
 * Single-consumer by design: only the poll loop may call [record], [start] and
 * [stop], which is what makes the unsynchronised buffer safe.
 */
class TripRecorder(private val db: AppDatabase) {

    private companion object {
        const val TAG = "TripRecorder"
        const val BATCH_SIZE = 40
        const val MAX_BUFFER_AGE_MS = 5_000L

        /** Metrics stored as first-class columns rather than in the packed blob. */
        val COLUMN_METRICS = setOf(
            PidRegistry.RPM.key,
            PidRegistry.SPEED.key,
            PidRegistry.COOLANT_TEMP.key,
            DerivedMetrics.BOOST.key,
        )
    }

    private class Accumulator {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var sum = 0.0
        var count = 0

        fun add(value: Float) {
            if (value < min) min = value
            if (value > max) max = value
            sum += value
            count++
        }
    }

    private val buffer = ArrayList<ReadingEntity>(BATCH_SIZE)
    private val accumulators = HashMap<String, Accumulator>()
    private val distance = DistanceAccumulator()
    private val pendingCodes = ArrayList<DtcEventEntity>()

    private var tripId: Long? = null
    private var startedAt = 0L
    private var sampleCount = 0
    private var lastFlushAt = 0L
    private var lastLocation: Location? = null
    private var milOn = false

    val activeTripId: Long? get() = tripId

    val currentDistanceMeters: Double get() = distance.totalMeters

    val currentSampleCount: Int get() = sampleCount

    suspend fun start(
        startedManually: Boolean,
        adapterName: String?,
        protocol: String?,
        now: Long = System.currentTimeMillis(),
    ): Long {
        reset()
        startedAt = now
        lastFlushAt = now
        val id = db.tripDao().insert(
            TripEntity(
                startedAt = now,
                startedManually = startedManually,
                adapterName = adapterName,
                protocol = protocol,
            ),
        )
        tripId = id
        Log.i(TAG, "Trip $id started (manual=$startedManually)")
        return id
    }

    fun onLocation(location: Location) {
        distance.add(location)
        lastLocation = location
    }

    fun onTroubleCodes(codes: List<DiagnosticCode>, now: Long = System.currentTimeMillis()) {
        val id = tripId ?: return
        val known = pendingCodes.mapTo(HashSet()) { it.code }
        for (code in codes) {
            if (!known.add(code.code)) continue
            pendingCodes += DtcEventEntity(
                tripId = id,
                timestamp = now,
                code = code.code,
                kind = code.kind.name,
            )
        }
    }

    fun onMilStatus(on: Boolean) {
        if (on) milOn = true
    }

    suspend fun record(snapshot: MetricSnapshot) {
        val id = tripId ?: return
        if (snapshot.isEmpty) return

        sampleCount++
        for ((key, value) in snapshot.values) {
            accumulators.getOrPut(key) { Accumulator() }.add(value)
        }

        val location = lastLocation
        // The four gauge metrics get real columns; the rest ride in the packed blob.
        val extras = snapshot.values.filterKeys { it !in COLUMN_METRICS }

        buffer += ReadingEntity(
            tripId = id,
            timestamp = snapshot.timestamp,
            rpm = snapshot[PidRegistry.RPM.key],
            speedKph = snapshot[PidRegistry.SPEED.key],
            coolantC = snapshot[PidRegistry.COOLANT_TEMP.key],
            boostKpa = snapshot[DerivedMetrics.BOOST.key],
            latitude = location?.latitude,
            longitude = location?.longitude,
            gpsSpeedKph = location?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
            extraValues = MetricPack.encode(extras),
        )

        val stale = snapshot.timestamp - lastFlushAt >= MAX_BUFFER_AGE_MS
        if (buffer.size >= BATCH_SIZE || stale) flush(snapshot.timestamp)
    }

    private suspend fun flush(now: Long) {
        if (buffer.isEmpty()) return
        val batch = ArrayList(buffer)
        buffer.clear()
        lastFlushAt = now
        try {
            db.readingDao().insertAll(batch)
        } catch (e: Exception) {
            Log.e(TAG, "Dropped ${batch.size} readings", e)
        }
    }

    /**
     * Finalises the trip: flushes what is buffered, writes the per-metric
     * min/avg/max rows and any trouble codes, and stamps the end time.
     *
     * @return the finished trip id, or null if no trip was running.
     */
    suspend fun stop(now: Long = System.currentTimeMillis()): Long? {
        val id = tripId ?: return null
        flush(now)

        val summaries = accumulators
            .filterValues { it.count > 0 }
            .map { (key, acc) ->
                TripMetricEntity(
                    tripId = id,
                    metricKey = key,
                    minValue = acc.min,
                    maxValue = acc.max,
                    avgValue = (acc.sum / acc.count).toFloat(),
                    sampleCount = acc.count,
                )
            }
        if (summaries.isNotEmpty()) db.tripMetricDao().insertAll(summaries)
        if (pendingCodes.isNotEmpty()) db.dtcEventDao().insertAll(pendingCodes)

        db.tripDao().byId(id)?.let { trip ->
            db.tripDao().update(
                trip.copy(
                    endedAt = now,
                    durationMs = now - startedAt,
                    distanceMeters = distance.totalMeters,
                    sampleCount = sampleCount,
                    milOn = milOn,
                    dtcCount = pendingCodes.size,
                ),
            )
        }

        Log.i(TAG, "Trip $id ended: $sampleCount samples, ${"%.2f".format(distance.totalMeters / 1000)} km")
        reset()
        return id
    }

    /**
     * Closes out a trip that was left open by a crash or a battery pull, using
     * the last reading we managed to write as the end time.
     */
    suspend fun closeAbandonedTrip() {
        val open = db.tripDao().openTrip() ?: return
        val readings = db.readingDao().forTrip(open.id)
        val endedAt = readings.lastOrNull()?.timestamp ?: open.startedAt
        db.tripDao().update(
            open.copy(
                endedAt = endedAt,
                durationMs = (endedAt - open.startedAt).coerceAtLeast(0),
                sampleCount = readings.size,
            ),
        )
        Log.w(TAG, "Closed abandoned trip ${open.id}")
    }

    private fun reset() {
        buffer.clear()
        accumulators.clear()
        pendingCodes.clear()
        distance.reset()
        tripId = null
        sampleCount = 0
        milOn = false
        lastLocation = null
    }
}
