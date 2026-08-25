package com.mohid.obd2dash.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Phone GPS, used for trip distance and the route map.
 *
 * The OBD2 speed PID is not integrated for distance: it is quantised to whole
 * km/h and drifts badly over a trip, whereas GPS gives a usable track for the
 * map anyway.
 */
class LocationTracker(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 1_000L): Flow<Location> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }

        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

/**
 * Turns a stream of fixes into a distance, discarding the noise that would
 * otherwise inflate a trip by a kilometre while parked.
 */
class DistanceAccumulator {

    private companion object {
        /** Fixes worse than this are too vague to measure a short hop with. */
        const val MAX_ACCURACY_M = 25f

        /** Below this, we are almost certainly looking at GPS jitter, not movement. */
        const val MIN_STEP_M = 3f

        /** Above this, the fix jumped: a tunnel exit or a cold start, not a drive. */
        const val MAX_STEP_M = 250f
    }

    private var last: Location? = null

    var totalMeters: Double = 0.0
        private set

    fun add(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val previous = last
        if (previous == null) {
            last = location
            return
        }
        val step = previous.distanceTo(location)
        if (step in MIN_STEP_M..MAX_STEP_M) {
            totalMeters += step
            last = location
        } else if (step > MAX_STEP_M) {
            // Re-anchor without counting the jump.
            last = location
        }
    }

    fun reset() {
        last = null
        totalMeters = 0.0
    }
}
