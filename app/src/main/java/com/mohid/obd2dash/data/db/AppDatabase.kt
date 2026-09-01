package com.mohid.obd2dash.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class,
        ReadingEntity::class,
        TripMetricEntity::class,
        DtcEventEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun readingDao(): ReadingDao
    abstract fun tripMetricDao(): TripMetricDao
    abstract fun dtcEventDao(): DtcEventDao

    companion object {
        private const val NAME = "obd2dash.db"

        /**
         * Adds the readiness-monitor counters to `trips`.
         *
         * Written out rather than falling back to a destructive migration:
         * trip history is the whole point of the app, and the raw readings
         * behind it cannot be re-collected after the drive is over.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN readinessIncomplete INTEGER")
                db.execSQL("ALTER TABLE trips ADD COLUMN readinessSupported INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN fuelLitres REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN fuelEconomyLPer100 REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN fuelSource TEXT")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // Readings are written in batches while driving; WAL keeps those
                // writes from blocking the UI's reads.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
