package com.mohid.obd2dash.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun observe(tripId: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun byId(tripId: Long): TripEntity?

    /** A trip left open by a crash or a battery pull. */
    @Query("SELECT * FROM trips WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openTrip(): TripEntity?

    @Query("SELECT * FROM trips WHERE syncedAt IS NULL AND endedAt IS NOT NULL ORDER BY startedAt")
    suspend fun pendingUpload(): List<TripEntity>

    @Query("UPDATE trips SET syncedAt = :at WHERE id = :tripId")
    suspend fun markSynced(tripId: Long, at: Long)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun delete(tripId: Long)

    @Query("SELECT COUNT(*) FROM trips")
    fun observeTripCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM trips")
    fun observeTotalDistance(): Flow<Double>
}

@Dao
interface ReadingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(readings: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE tripId = :tripId ORDER BY timestamp")
    suspend fun forTrip(tripId: Long): List<ReadingEntity>

    @Query("SELECT COUNT(*) FROM readings WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    @Query("SELECT latitude, longitude FROM readings WHERE tripId = :tripId AND latitude IS NOT NULL ORDER BY timestamp")
    suspend fun routeForTrip(tripId: Long): List<RoutePoint>

    /** Retention hook: raw samples are the bulk of the database, trip summaries are not. */
    @Query("DELETE FROM readings WHERE tripId IN (SELECT id FROM trips WHERE endedAt < :before)")
    suspend fun deleteReadingsBefore(before: Long): Int
}

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

@Dao
interface TripMetricDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metrics: List<TripMetricEntity>)

    @Query("SELECT * FROM trip_metrics WHERE tripId = :tripId")
    fun observeForTrip(tripId: Long): Flow<List<TripMetricEntity>>

    @Query("SELECT * FROM trip_metrics WHERE tripId = :tripId")
    suspend fun forTrip(tripId: Long): List<TripMetricEntity>
}

@Dao
interface DtcEventDao {

    @Insert
    suspend fun insertAll(events: List<DtcEventEntity>)

    @Query("SELECT * FROM dtc_events WHERE tripId = :tripId ORDER BY timestamp")
    fun observeForTrip(tripId: Long): Flow<List<DtcEventEntity>>

    @Query("SELECT * FROM dtc_events WHERE tripId = :tripId ORDER BY timestamp")
    suspend fun forTrip(tripId: Long): List<DtcEventEntity>

    @Transaction
    @Query("SELECT COUNT(*) FROM dtc_events WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int
}
