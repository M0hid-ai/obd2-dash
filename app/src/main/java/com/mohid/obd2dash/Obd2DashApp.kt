package com.mohid.obd2dash

import android.app.Application
import android.content.Context
import com.mohid.obd2dash.ai.TripAnalyst
import com.mohid.obd2dash.alerts.AlertNotifier
import com.mohid.obd2dash.data.SettingsStore
import com.mohid.obd2dash.data.TripExporter
import com.mohid.obd2dash.data.TripRepository
import com.mohid.obd2dash.data.db.AppDatabase
import com.mohid.obd2dash.location.LocationTracker
import com.mohid.obd2dash.obd.ObdController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled object graph.
 *
 * The app is a single module with one long-lived object that matters, the
 * controller, so a DI framework would cost build time and an annotation
 * processor without buying anything back.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Outlives any screen: a trip must survive rotation, backgrounding, and the
     * activity being destroyed entirely while the phone sits in a cradle.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val notifier: AlertNotifier by lazy { AlertNotifier(appContext).also { it.ensureChannels() } }

    val locationTracker: LocationTracker by lazy { LocationTracker(appContext) }

    val tripRepository: TripRepository by lazy { TripRepository(database) }

    val tripExporter: TripExporter by lazy { TripExporter(appContext, tripRepository, settingsStore) }

    val tripAnalyst: TripAnalyst by lazy { TripAnalyst() }

    val controller: ObdController by lazy {
        ObdController(
            context = appContext,
            db = database,
            settingsStore = settingsStore,
            notifier = notifier,
            locationTracker = locationTracker,
            scope = applicationScope,
        )
    }
}

class Obd2DashApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Channels must exist before the first alert fires, and creating them
        // is idempotent, so do it up front rather than on the polling path.
        graph.notifier.ensureChannels()
    }
}
