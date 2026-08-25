package com.mohid.obd2dash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mohid.obd2dash.alerts.ActiveAlert
import com.mohid.obd2dash.alerts.AlertEngine
import com.mohid.obd2dash.alerts.AlertNotifier
import com.mohid.obd2dash.alerts.AlertSeverity
import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.data.SettingsStore
import com.mohid.obd2dash.data.TripRecorder
import com.mohid.obd2dash.data.db.AppDatabase
import com.mohid.obd2dash.location.LocationTracker
import com.mohid.obd2dash.obd.transport.BluetoothObdTransport
import com.mohid.obd2dash.obd.transport.ObdTransport
import com.mohid.obd2dash.obd.transport.SimulatedObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val stage: String) : ConnectionState
    data class Connected(
        val adapter: AdapterInfo,
        val transportName: String,
        val supportedPidCount: Int,
        val demo: Boolean,
    ) : ConnectionState

    data class Failed(val reason: String) : ConnectionState
}

sealed interface TripState {
    data object Idle : TripState
    data class Recording(
        val tripId: Long,
        val startedAt: Long,
        val distanceMeters: Double,
        val sampleCount: Int,
        val startedManually: Boolean,
    ) : TripState
}

data class PollStats(
    val samplesPerSecond: Float = 0f,
    val lastCycleMs: Long = 0,
    val failures: Int = 0,
)

/**
 * The single owner of the live OBD2 connection.
 *
 * Everything stateful about "what the car is doing right now" lives here and is
 * published as flows: the UI observes, the foreground service just keeps the
 * process alive. That keeps screens free of connection lifecycle and means
 * rotating the phone or backgrounding the app never interrupts a trip.
 */
@SuppressLint("MissingPermission")
class ObdController(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val notifier: AlertNotifier,
    private val locationTracker: LocationTracker,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "ObdController"

        /** Consecutive NO DATA replies before a PID is dropped from the rotation. */
        const val DEMOTE_AFTER_NO_DATA = 3

        const val BARO_REFRESH_MS = 60_000L
        const val DTC_POLL_MS = 30_000L
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_BACKOFF_MS = 1_500L
    }

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _snapshot = MutableStateFlow(MetricSnapshot.EMPTY)
    val snapshot: StateFlow<MetricSnapshot> = _snapshot.asStateFlow()

    private val _alerts = MutableStateFlow<List<ActiveAlert>>(emptyList())
    val alerts: StateFlow<List<ActiveAlert>> = _alerts.asStateFlow()

    private val _trip = MutableStateFlow<TripState>(TripState.Idle)
    val trip: StateFlow<TripState> = _trip.asStateFlow()

    private val _supportedPids = MutableStateFlow<List<ObdPid>>(emptyList())
    val supportedPids: StateFlow<List<ObdPid>> = _supportedPids.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _stats = MutableStateFlow(PollStats())
    val stats: StateFlow<PollStats> = _stats.asStateFlow()

    private val _lastFinishedTrip = MutableStateFlow<Long?>(null)
    val lastFinishedTrip: StateFlow<Long?> = _lastFinishedTrip.asStateFlow()

    private val alertEngine = AlertEngine()
    private val recorder = TripRecorder(db)

    private var transport: ObdTransport? = null
    private var session: ObdSession? = null
    private var pollJob: Job? = null
    private var locationJob: Job? = null

    @Volatile
    private var settings: AppSettings = AppSettings()

    init {
        scope.launch {
            settingsStore.settings.collect { latest ->
                settings = latest
                alertEngine.setRules(latest.thresholds)
                _alerts.value = alertEngine.snapshot()
            }
        }
        scope.launch { recorder.closeAbandonedTrip() }
    }

    // ---- Connection --------------------------------------------------------

    fun connect(deviceAddress: String?) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { runConnection(deviceAddress) }
    }

    fun disconnect() {
        scope.launch {
            pollJob?.cancel()
            pollJob = null
            endTripIfRunning()
            teardownTransport()
            _connection.value = ConnectionState.Disconnected
            _snapshot.value = MetricSnapshot.EMPTY
            _supportedPids.value = emptyList()
        }
    }

    private suspend fun runConnection(deviceAddress: String?) {
        var attempt = 0
        while (scope.isActive) {
            try {
                openAndInitialize(deviceAddress)
                attempt = 0
                pollForever()
                return
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (e: SecurityException) {
                fail("Bluetooth permission was denied. Grant Nearby devices and try again.")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt ${attempt + 1} failed", e)
                teardownTransport()
                attempt++
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    fail(e.message ?: "Could not reach the adapter.")
                    endTripIfRunning()
                    return
                }
                _connection.value = ConnectionState.Connecting(
                    "Link dropped, retrying ($attempt of $MAX_RECONNECT_ATTEMPTS)…",
                )
                delay(RECONNECT_BACKOFF_MS * attempt)
            }
        }
    }

    private suspend fun openAndInitialize(deviceAddress: String?) {
        appendLog("Opening ${if (deviceAddress == null) "demo transport" else deviceAddress}")
        _connection.value = ConnectionState.Connecting("Opening link…")

        val newTransport = buildTransport(deviceAddress)
        newTransport.open()
        transport = newTransport

        val newSession = ObdSession(newTransport, useFrameCountHint = settings.useFrameCountHint)
        val adapter = newSession.initialize { stage ->
            _connection.value = ConnectionState.Connecting(stage)
            appendLog(stage)
        }
        appendLog("Adapter ${adapter.version} on ${adapter.protocol}")

        _connection.value = ConnectionState.Connecting("Scanning supported PIDs…")
        val supportedNumbers = newSession.scanSupportedPids { count ->
            _connection.value = ConnectionState.Connecting("Scanning supported PIDs ($count)…")
        }
        val supported = supportedNumbers.mapNotNull { PidRegistry.byPid(it) }
        _supportedPids.value = supported
        appendLog("ECU answers ${supported.size} known PIDs")

        session = newSession
        _connection.value = ConnectionState.Connected(
            adapter = adapter,
            transportName = newTransport.name,
            supportedPidCount = supported.size,
            demo = deviceAddress == null,
        )

        if (settings.autoStartTripOnConnect && recorder.activeTripId == null) {
            beginTrip(manual = false, adapter = adapter, transportName = newTransport.name)
        }
    }

    private fun buildTransport(deviceAddress: String?): ObdTransport {
        if (deviceAddress == null) return SimulatedObdTransport()
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw IOException("This device has no Bluetooth support.")
        val adapter = manager.adapter ?: throw IOException("Bluetooth is unavailable.")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off.")
        val device = adapter.getRemoteDevice(deviceAddress)
            ?: throw IOException("No paired adapter at $deviceAddress.")
        return BluetoothObdTransport(device, adapter)
    }

    private fun teardownTransport() {
        runCatching { transport?.close() }
        transport = null
        session = null
    }

    private fun fail(reason: String) {
        _connection.value = ConnectionState.Failed(reason)
        appendLog("Failed: $reason")
    }

    // ---- Polling -----------------------------------------------------------

    /**
     * The poll loop.
     *
     * The four gauge metrics need every cycle to animate smoothly, but the
     * adapter can only answer one request at a time, roughly 40-70ms each over
     * RFCOMM. So the high-rate tier is read every cycle and everything else is
     * round-robined one PID per cycle, which keeps a cycle inside the 200-500ms
     * budget while still refreshing the long tail every few seconds.
     */
    private suspend fun pollForever() {
        val active = session ?: return
        val values = HashMap<String, Float>()
        val updatedAt = HashMap<String, Long>()
        val noDataCount = HashMap<String, Int>()
        val demoted = HashSet<String>()

        val supported = _supportedPids.value
        val fastTier = PidRegistry.highRate.filter { it in supported }
        val slowTier = supported.filter { it !in fastTier && it.key !in PidRegistry.rarelyChanging }
        val occasional = supported.filter { it.key in PidRegistry.rarelyChanging }

        if (fastTier.isEmpty() && slowTier.isEmpty()) {
            fail("The ECU did not report any readable PIDs.")
            return
        }

        var slowIndex = 0
        var occasionalIndex = 0
        var lastBaroAt = 0L
        var lastDtcAt = 0L
        var failures = 0

        withContext(Dispatchers.IO) {
            while (isActive) {
                val cycleStart = SystemClock.elapsedRealtime()
                val now = System.currentTimeMillis()

                for (pid in fastTier) {
                    if (!readInto(active, pid, values, updatedAt, noDataCount, demoted, now)) {
                        throw IOException("Adapter stopped responding")
                    }
                }

                // One slow PID per cycle keeps the long tail fresh without
                // stealing sample rate from the gauges.
                val rotation = slowTier.filter { it.key !in demoted }
                if (rotation.isNotEmpty()) {
                    val pid = rotation[slowIndex % rotation.size]
                    slowIndex++
                    if (!readInto(active, pid, values, updatedAt, noDataCount, demoted, now)) {
                        throw IOException("Adapter stopped responding")
                    }
                }

                // Barometric pressure tracks the weather, not the throttle.
                if (now - lastBaroAt >= BARO_REFRESH_MS) {
                    lastBaroAt = now
                    val slow = occasional.filter { it.key !in demoted }
                    if (slow.isNotEmpty()) {
                        val pid = slow[occasionalIndex % slow.size]
                        occasionalIndex++
                        readInto(active, pid, values, updatedAt, noDataCount, demoted, now)
                    }
                }

                applyDerivedMetrics(values, updatedAt, now)

                val snapshot = MetricSnapshot(now, HashMap(values), HashMap(updatedAt))
                _snapshot.value = snapshot

                evaluateAlerts(snapshot)
                recorder.record(snapshot)
                publishTripProgress()

                if (now - lastDtcAt >= DTC_POLL_MS) {
                    lastDtcAt = now
                    pollTroubleCodes(active, now)
                }

                val cycleMs = SystemClock.elapsedRealtime() - cycleStart
                _stats.value = PollStats(
                    samplesPerSecond = if (cycleMs > 0) 1000f / cycleMs else 0f,
                    lastCycleMs = cycleMs,
                    failures = failures,
                )

                val remaining = settings.pollIntervalMs - cycleMs
                if (remaining > 0) delay(remaining)
            }
        }
    }

    /** @return false when the link is dead and the connection must be rebuilt. */
    private suspend fun readInto(
        session: ObdSession,
        pid: ObdPid,
        values: MutableMap<String, Float>,
        updatedAt: MutableMap<String, Long>,
        noDataCount: MutableMap<String, Int>,
        demoted: MutableSet<String>,
        now: Long,
    ): Boolean {
        when (val result = session.read(pid)) {
            is ReadResult.Value -> {
                values[pid.key] = result.value
                updatedAt[pid.key] = now
                noDataCount[pid.key] = 0
            }

            ReadResult.NoData -> {
                val misses = (noDataCount[pid.key] ?: 0) + 1
                noDataCount[pid.key] = misses
                if (misses >= DEMOTE_AFTER_NO_DATA && demoted.add(pid.key)) {
                    // Advertised in the support bitmask but never actually answered.
                    appendLog("Dropping ${pid.shortLabel}: no data")
                    values.remove(pid.key)
                }
            }

            is ReadResult.Failure -> {
                if (result.fatal) return false
                Log.w(TAG, "${pid.command} -> ${result.token}")
            }
        }
        return true
    }

    /**
     * Boost is not a PID: it is manifold pressure relative to ambient, so it
     * needs MAP and a barometric reference. If the ECU does not publish 0133 we
     * fall back to a sea-level constant, which is a fixed offset error rather
     * than a wrong shape.
     */
    private fun applyDerivedMetrics(
        values: MutableMap<String, Float>,
        updatedAt: MutableMap<String, Long>,
        now: Long,
    ) {
        val map = values[PidRegistry.MAP.key] ?: return
        val baro = values[PidRegistry.BAROMETRIC.key] ?: DerivedMetrics.DEFAULT_BAROMETRIC_KPA
        values[DerivedMetrics.BOOST.key] = map - baro
        updatedAt[DerivedMetrics.BOOST.key] = now
    }

    private fun evaluateAlerts(snapshot: MetricSnapshot) {
        val update = alertEngine.evaluate(snapshot)
        _alerts.value = update.active
        if (!settings.alertSoundEnabled) return
        for (alert in update.newlyRaised) {
            notifier.post(alert)
            Log.i(TAG, "ALERT ${alert.severity}: ${alert.message}")
        }
    }

    private suspend fun pollTroubleCodes(session: ObdSession, now: Long) {
        val status = session.readMonitorStatus()
        if (status != null) {
            recorder.onMilStatus(status.milOn)
            if (status.dtcCount == 0 && !status.milOn) return
        }
        val stored = session.readTroubleCodes(DiagnosticCode.Kind.STORED)
        val pending = session.readTroubleCodes(DiagnosticCode.Kind.PENDING)
        val found = stored + pending
        if (found.isNotEmpty()) {
            recorder.onTroubleCodes(found, now)
            appendLog("Trouble codes: ${found.joinToString { it.code }}")
        }
    }

    // ---- Trips -------------------------------------------------------------

    fun startTrip() {
        scope.launch {
            if (recorder.activeTripId != null) return@launch
            val state = _connection.value as? ConnectionState.Connected ?: return@launch
            beginTrip(manual = true, adapter = state.adapter, transportName = state.transportName)
        }
    }

    fun stopTrip() {
        scope.launch { endTripIfRunning() }
    }

    private suspend fun beginTrip(manual: Boolean, adapter: AdapterInfo?, transportName: String?) {
        val id = recorder.start(
            startedManually = manual,
            adapterName = transportName,
            protocol = adapter?.protocol,
        )
        _trip.value = TripState.Recording(
            tripId = id,
            startedAt = System.currentTimeMillis(),
            distanceMeters = 0.0,
            sampleCount = 0,
            startedManually = manual,
        )
        startLocationUpdates()
        appendLog("Trip $id started")
    }

    private suspend fun endTripIfRunning() {
        locationJob?.cancel()
        locationJob = null
        val finished = recorder.stop() ?: return
        _trip.value = TripState.Idle
        _lastFinishedTrip.value = finished
        appendLog("Trip $finished ended")
    }

    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        if (!locationTracker.hasPermission()) {
            appendLog("Location permission missing, distance and route will be empty")
            return
        }
        locationJob = scope.launch {
            runCatching {
                locationTracker.updates().collect { recorder.onLocation(it) }
            }.onFailure { Log.w(TAG, "Location updates stopped", it) }
        }
    }

    private fun publishTripProgress() {
        val current = _trip.value
        if (current !is TripState.Recording) return
        _trip.value = current.copy(
            distanceMeters = recorder.currentDistanceMeters,
            sampleCount = recorder.currentSampleCount,
        )
    }

    // ---- Alerts & diagnostics ---------------------------------------------

    fun acknowledgeAlert(metricKey: String) {
        alertEngine.acknowledge(metricKey)
        _alerts.value = alertEngine.snapshot()
        notifier.clear(metricKey)
    }

    fun acknowledgeAllAlerts() {
        alertEngine.snapshot().forEach { notifier.clear(it.metricKey) }
        alertEngine.acknowledgeAll()
        _alerts.value = alertEngine.snapshot()
    }

    fun hasUnacknowledgedCritical(): Boolean =
        _alerts.value.any { it.severity == AlertSeverity.CRITICAL && !it.acknowledged }

    fun consumeLastFinishedTrip() {
        _lastFinishedTrip.value = null
    }

    private fun appendLog(line: String) {
        val stamped = "%tT  %s".format(System.currentTimeMillis(), line)
        _log.value = (_log.value + stamped).takeLast(80)
    }
}
