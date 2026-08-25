package com.mohid.obd2dash.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mohid.obd2dash.Obd2DashApp
import com.mohid.obd2dash.alerts.AlertNotifier
import com.mohid.obd2dash.obd.ConnectionState
import com.mohid.obd2dash.obd.TripState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the process alive and foreground for the duration of a drive.
 *
 * It deliberately owns no state: [com.mohid.obd2dash.obd.ObdController] holds
 * the connection and the trip, and this just renders their current state into
 * the ongoing notification. Screen off, app backgrounded, or the user in maps
 * instead. Polling and logging carry on either way.
 */
class ObdService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.mohid.obd2dash.action.START"
        const val ACTION_STOP = "com.mohid.obd2dash.action.STOP"
        const val EXTRA_DEVICE_ADDRESS = "deviceAddress"

        /** Null address means the simulated adapter. */
        fun start(context: Context, deviceAddress: String?) {
            val intent = Intent(context, ObdService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ObdService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private val graph by lazy { (application as Obd2DashApp).graph }
    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                graph.controller.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                goForeground("Connecting…", "Bringing up the adapter")
                observeState()
                graph.controller.connect(intent.getStringExtra(EXTRA_DEVICE_ADDRESS))
            }

            else -> {
                // A restart with no intent means the process was killed. There
                // is no adapter session to resume, so do not sit in the
                // foreground pretending otherwise.
                stopSelf()
            }
        }
        // Not sticky: silently resurrecting a drive session without the adapter
        // would leave an ongoing notification attached to nothing.
        return START_NOT_STICKY
    }

    /**
     * Declares the location type only when the permission is actually held.
     * Android 14 rejects `startForeground` outright for a type whose backing
     * permission is missing, and trips are perfectly usable without GPS.
     */
    private fun goForeground(title: String, detail: String) {
        ServiceCompat.startForeground(
            this,
            AlertNotifier.SERVICE_NOTIFICATION_ID,
            graph.notifier.buildServiceNotification(title, detail),
            currentTypes(),
        )
    }

    private fun observeState() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            // The controller's connection flow starts at Disconnected, and this
            // subscribes before connect() is called. Without this latch the very
            // first emission would look like a shutdown and stop the service
            // before it ever did anything.
            var everActive = false

            combine(
                graph.controller.connection,
                graph.controller.trip,
            ) { connection, trip -> connection to trip }.collect { (connection, trip) ->
                val title = when (connection) {
                    is ConnectionState.Connected ->
                        if (connection.demo) "Demo adapter" else connection.transportName
                    is ConnectionState.Connecting -> "Connecting…"
                    is ConnectionState.Failed -> "Adapter unavailable"
                    ConnectionState.Disconnected -> "Disconnected"
                }
                val detail = when {
                    trip is TripState.Recording -> {
                        val km = trip.distanceMeters / 1000.0
                        val minutes = ((System.currentTimeMillis() - trip.startedAt) / 60_000.0).roundToInt()
                        "Recording · %.1f km · %d min".format(km, minutes)
                    }

                    connection is ConnectionState.Connected ->
                        "${connection.supportedPidCount} PIDs · not recording"

                    connection is ConnectionState.Failed -> connection.reason
                    connection is ConnectionState.Connecting -> connection.stage
                    else -> "Idle"
                }

                if (connection !is ConnectionState.Disconnected) everActive = true

                // Once the controller has given up or been told to stop, there
                // is nothing left to keep the process alive for.
                val finished = everActive &&
                    (connection is ConnectionState.Disconnected || connection is ConnectionState.Failed)

                if (finished) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    goForeground(title, detail)
                }
            }
        }
    }

    private fun currentTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (graph.locationTracker.hasPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }
}
