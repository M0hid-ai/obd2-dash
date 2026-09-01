package com.mohid.obd2dash.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One drive. Kept indefinitely because it is small, and it is what the trip history
 * and the web dashboard are built from.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    /** Accumulated from phone GPS, not from the OBD2 speed PID. */
    val distanceMeters: Double = 0.0,
    val sampleCount: Int = 0,
    /** True when the driver pressed start rather than it being triggered by the adapter connecting. */
    val startedManually: Boolean = false,
    val adapterName: String? = null,
    val protocol: String? = null,
    /** VIN, or the ECU fingerprint when Mode 09 was silent. */
    val vehicleIdentity: String? = null,
    /**
     * The car's name as it read when the trip started, copied rather than
     * looked up later. Renaming a car should not silently retitle drives that
     * are already in the history.
     */
    val vehicleName: String? = null,
    /** Nth trip for this particular car, counted from one. */
    val vehicleTripNumber: Int = 0,
    /**
     * How many Mode 01 PIDs the ECU advertised in its support bitmask,
     * including the ones this app has no decoder for. The gap between this and
     * [pidsKnown] is the app's limitation; the gap between [pidsKnown] and what
     * [pidsReceived] holds is the ECU or the adapter failing to deliver.
     */
    val pidsAdvertised: Int = 0,
    /** Of those, how many this app can actually decode. */
    val pidsKnown: Int = 0,
    /** Semicolon separated metric keys that produced at least one reading. */
    val pidsReceived: String = "",
    /** Advertised and decodable, but silent for the whole trip. */
    val pidsMissing: String = "",
    val milOn: Boolean = false,
    val dtcCount: Int = 0,
    /**
     * Emissions self-tests that had not finished during this trip, and how many
     * the ECU runs in total. Null when the car never answered PID 0101.
     *
     * Worth keeping per trip rather than only live: a row of incomplete
     * monitors is the fingerprint of a recent code clear, and knowing which
     * drive it was still showing on is what dates that clear.
     */
    val readinessIncomplete: Int? = null,
    val readinessSupported: Int? = null,
    /**
     * Stop/start cuts during this trip, and how long the engine spent off
     * across all of them. Zero on a car without the feature, or with it
     * switched off, which is itself worth being able to see on the report.
     */
    val idleStopCount: Int = 0,
    val idleStopMs: Long = 0,
    /** Litres burned this trip. Null when the ECU never gave a usable fuel signal. */
    val fuelLitres: Double? = null,
    /** Trip average, L/100 km. Null when the drive was too short to be meaningful. */
    val fuelEconomyLPer100: Float? = null,
    /** `ecu` when PID 015E was used, `maf` when estimated from mass air flow. */
    val fuelSource: String? = null,
    /** Null until the post-trip batch upload succeeds. */
    val syncedAt: Long? = null,
)

/**
 * What to call a trip.
 *
 * "Daihatsu Move 2023 - Trip 12" once the car has a name, falling back to the
 * bare trip number for drives recorded before the car was identified, or on an
 * adapter that never produced a VIN.
 */
val TripEntity.title: String
    get() = when {
        vehicleName != null && vehicleTripNumber > 0 -> "$vehicleName - Trip $vehicleTripNumber"
        vehicleName != null -> vehicleName
        else -> "Trip #$id"
    }

/**
 * One polling tick.
 *
 * The four gauge metrics plus GPS get real columns because charts, the route
 * map and the summary all query them directly. Everything else rides along in
 * [extraValues] so adding a PID never needs a schema migration.
 */
@Entity(
    tableName = "readings",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "timestamp"])],
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestamp: Long,
    val rpm: Float? = null,
    val speedKph: Float? = null,
    val coolantC: Float? = null,
    val boostKpa: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gpsSpeedKph: Float? = null,
    val extraValues: String = "",
)

/** Rolled-up min/avg/max for one metric over one trip, written when the trip ends. */
@Entity(
    tableName = "trip_metrics",
    primaryKeys = ["tripId", "metricKey"],
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tripId")],
)
data class TripMetricEntity(
    val tripId: Long,
    val metricKey: String,
    val minValue: Float,
    val maxValue: Float,
    val avgValue: Float,
    val sampleCount: Int,
)

/** A trouble code seen during a trip, so the report can call it out. */
@Entity(
    tableName = "dtc_events",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tripId")],
)
data class DtcEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestamp: Long,
    val code: String,
    val kind: String,
)

/**
 * Serialisation for [ReadingEntity.extraValues].
 *
 * Deliberately not JSON: this runs a few times a second for the whole trip, and
 * a flat `key=value;` list parses in a single pass with no allocation beyond
 * the resulting map.
 */
object MetricPack {

    fun encode(values: Map<String, Float>): String {
        if (values.isEmpty()) return ""
        val sb = StringBuilder(values.size * 12)
        for ((key, value) in values) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(key).append('=').append(value)
        }
        return sb.toString()
    }

    fun decode(packed: String): Map<String, Float> {
        if (packed.isEmpty()) return emptyMap()
        val out = HashMap<String, Float>()
        var i = 0
        while (i < packed.length) {
            val sep = packed.indexOf('=', i)
            if (sep < 0) break
            var end = packed.indexOf(';', sep)
            if (end < 0) end = packed.length
            val value = packed.substring(sep + 1, end).toFloatOrNull()
            if (value != null) out[packed.substring(i, sep)] = value
            i = end + 1
        }
        return out
    }
}
