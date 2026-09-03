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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One point on a trip chart. */
data class SeriesPoint(
    val elapsedMs: Long,
    val value: Float,
)

class TripRepository(private val db: AppDatabase) {

    /**
     * The most recently read trip's raw samples.
     *
     * The report screen asks for one metric at a time as the user taps through
     * the chart chips, and every one of those used to reload the trip's entire
     * reading table. On a real drive that is a thousand-plus rows re-read and
     * re-parsed per tap, which is exactly the stall you feel when switching
     * charts. One trip's worth is held here instead, so only the first chart
     * touches the database.
     */
    private val readingsLock = Mutex()
    private var cachedTripId: Long? = null
    private var cachedReadings: List<ReadingEntity> = emptyList()

    private suspend fun readings(tripId: Long): List<ReadingEntity> = readingsLock.withLock {
        if (cachedTripId == tripId) return@withLock cachedReadings
        val loaded = db.readingDao().forTrip(tripId)
        cachedTripId = tripId
        cachedReadings = loaded
        loaded
    }

    /** Drops the cache, for a trip whose rows have just been deleted. */
    private suspend fun invalidate(tripId: Long) = readingsLock.withLock {
        if (cachedTripId == tripId) {
            cachedTripId = null
            cachedReadings = emptyList()
        }
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
        invalidate(tripId)
    }

    suspend fun route(tripId: Long): List<RoutePoint> = withContext(Dispatchers.IO) {
        db.readingDao().routeForTrip(tripId)
    }

    /** Reads one metric back out as a chart series, thinned by [ChartSeries]. */
    suspend fun series(tripId: Long, metricKey: String): List<SeriesPoint> =
        withContext(Dispatchers.IO) {
            val readings = readings(tripId)
            if (readings.isEmpty()) return@withContext emptyList()

            val startedAt = readings.first().timestamp
            val raw = ArrayList<SeriesPoint>(readings.size)
            for (reading in readings) {
                val value = valueOf(reading, metricKey) ?: continue
                raw += SeriesPoint(reading.timestamp - startedAt, value)
            }
            ChartSeries.downsample(raw)
        }

    /** Which metrics this trip actually captured, in registry order. */
    suspend fun recordedMetrics(tripId: Long): List<String> = withContext(Dispatchers.IO) {
        val summaries = db.tripMetricDao().forTrip(tripId).map { it.metricKey }.toSet()
        if (summaries.isNotEmpty()) return@withContext orderMetrics(summaries)
        // Fall back to inspecting a sample, for a trip that never got summarised.
        val sample = readings(tripId).firstOrNull() ?: return@withContext emptyList()
        val keys = MetricPack.decode(sample.extraValues).keys.toMutableSet()
        if (sample.rpm != null) keys += PidRegistry.RPM.key
        if (sample.speedKph != null) keys += PidRegistry.SPEED.key
        if (sample.coolantC != null) keys += PidRegistry.COOLANT_TEMP.key
        if (sample.boostKpa != null) keys += DerivedMetrics.BOOST.key
        orderMetrics(keys)
    }

    /** Everything one trip's report or export needs, read in a single pass. */
    suspend fun snapshotForExport(tripId: Long): TripExportData? = withContext(Dispatchers.IO) {
        val trip = db.tripDao().byId(tripId) ?: return@withContext null
        val rows = readings(tripId)
        val startedAt = rows.firstOrNull()?.timestamp ?: trip.startedAt
        val metricKeys = recordedMetrics(tripId)
        val series = metricKeys.associateWith { key ->
            val raw = ArrayList<SeriesPoint>(rows.size)
            for (row in rows) {
                val value = valueOf(row, key) ?: continue
                raw += SeriesPoint(row.timestamp - startedAt, value)
            }
            ChartSeries.downsample(raw)
        }
        TripExportData(
            trip = trip,
            metrics = db.tripMetricDao().forTrip(tripId),
            dtcs = db.dtcEventDao().forTrip(tripId),
            route = db.readingDao().routeForTrip(tripId),
            metricKeys = metricKeys,
            series = series,
            readings = rows,
        )
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
}

/** One trip, fully loaded: what an export or a report needs, with no further queries. */
data class TripExportData(
    val trip: TripEntity,
    val metrics: List<TripMetricEntity>,
    val dtcs: List<DtcEventEntity>,
    val route: List<RoutePoint>,
    val metricKeys: List<String>,
    val series: Map<String, List<SeriesPoint>>,
    val readings: List<ReadingEntity>,
)
