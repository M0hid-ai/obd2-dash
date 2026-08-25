package com.mohid.obd2dash.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class,
        ReadingEntity::class,
        TripMetricEntity::class,
        DtcEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun readingDao(): ReadingDao
    abstract fun tripMetricDao(): TripMetricDao
    abstract fun dtcEventDao(): DtcEventDao

    companion object {
        private const val NAME = "obd2dash.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // Readings are written in batches while driving; WAL keeps those
                // writes from blocking the UI's reads.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
