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
import com.mohid.obd2dash.data.VehicleProfile
import com.mohid.obd2dash.data.VehiclePrompt
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
        val fuelLitres: Double = 0.0,
        val tripLPer100: Float? = null,
        val instantLPer100: Float? = null,
        val fuelSource: FuelSource? = null,
    ) : TripState
}

/**
 * Whether the engine is force fed.
 *
 * Decided from what the manifold actually does rather than from a setting: a
 * naturally aspirated engine can never push manifold pressure above ambient,
 * so one confident reading above atmospheric is proof of a turbo or a blower,
 * and no amount of driving can prove the opposite. Until that reading arrives
 * the boost dial stays scaled for vacuum, which is the useful range on an NA
 * car and still correct on a turbo one that has not been opened up yet.
 */
enum class Induction { UNKNOWN, NATURAL, FORCED }

data class PollStats(
    val samplesPerSecond: Float = 0f,
    val lastCycleMs: Long = 0,
    val failures: Int = 0,
    /** Slow-tier PIDs read in the last cycle. Varies with how fast the link is. */
    val slowReadsPerCycle: Int = 0,
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

        /**
         * One diagnostic mode is read per tick rather than all of them at once,
         * so a scan never costs three round trips inside a single poll cycle.
         * Four ticks covers the lamp plus stored, pending and permanent codes.
         */
        const val DTC_POLL_MS = 15_000L

        /** Ceiling on slow-tier reads per cycle, however fast the link is. */
        const val MAX_SLOW_READS_PER_CYCLE = 5

        /** Starting guess for one request-response round trip over RFCOMM. */
        const val INITIAL_READ_COST_MS = 70.0

        /** Weight given to the newest measurement when re-estimating read cost. */
        const val READ_COST_SMOOTHING = 0.2

        /** Manifold pressure this far above ambient can only come from a compressor. */
        const val FORCED_INDUCTION_KPA = 12f

        /**
         * How long the engine must stay stopped before the trip is closed.
         * Generous on purpose: cars with idle stop cut the engine at lights, and
         * splitting a commute in half at every red light would be worse than
         * running a few seconds long.
         */
        const val ENGINE_OFF_GRACE_MS = 90_000L

        /** After this much engine-off time, give up the link entirely. */
        const val IDLE_DISCONNECT_MS = 300_000L

        /**
         * How long an idle stop is allowed to run before it stops being an
         * idle stop. Nothing with a working starter sits at a light for ten
         * minutes, so past this the car is parked with the ignition on and the
         * trip should close like any other.
         */
        const val IDLE_STOP_MAX_MS = 600_000L

        /**
         * An idle stop shorter than this is a dropped frame or a stall on the
         * restart, not the stop/start system doing its job. Counting those
         * would inflate the tally on every car that hiccups once.
         */
        const val IDLE_STOP_MIN_MS = 1_500L

        /** Above this the engine is turning. Cranking and noise sit below it. */
        const val RUNNING_RPM = 50f
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

    private val _monitorStatus = MutableStateFlow<MonitorStatus?>(null)
    val monitorStatus: StateFlow<MonitorStatus?> = _monitorStatus.asStateFlow()

    private val _troubleCodes = MutableStateFlow<List<DiagnosticCode>>(emptyList())
    val troubleCodes: StateFlow<List<DiagnosticCode>> = _troubleCodes.asStateFlow()

    private val _induction = MutableStateFlow(Induction.UNKNOWN)
    val induction: StateFlow<Induction> = _induction.asStateFlow()

    private val _turboCar = MutableStateFlow(false)
    val turboCar: StateFlow<Boolean> = _turboCar.asStateFlow()

    private val _vehiclePrompt = MutableStateFlow<VehiclePrompt?>(null)
    val vehiclePrompt: StateFlow<VehiclePrompt?> = _vehiclePrompt.asStateFlow()

    private val alertEngine = AlertEngine()
    private val recorder = TripRecorder(db)

    private var transport: ObdTransport? = null
    private var session: ObdSession? = null
    private var pollJob: Job? = null
    private var locationJob: Job? = null

    @Volatile
    private var settings: AppSettings = AppSettings()

    @Volatile
    private var turboEnabled = false

    private var vehicleCache = emptyMap<String, VehicleProfile>()

    /** Everything the support bitmask claimed, decodable by this app or not. */
    private var advertisedPidCount = 0

    /**
     * Where this car's throttle sensor sits when the pedal is untouched.
     *
     * Learned rather than assumed, because "closed" is not zero on most cars:
     * plenty report 12 to 15 percent with the pedal up. The lowest value seen
     * since connecting is the resting position by definition, since nothing
     * can push a throttle below closed.
     */
    private var closedThrottlePct: Float? = null
    private var activeVehicle: VehicleIdentity.Info? = null

    init {
        scope.launch {
            settingsStore.settings.collect { latest ->
                settings = latest
                alertEngine.setRules(latest.thresholds)
                _alerts.value = alertEngine.snapshot()
            }
        }
        scope.launch {
            settingsStore.vehicles.collect { list ->
                vehicleCache = list.associateBy { it.identity }
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
            clearAlerts()
            _connection.value = ConnectionState.Disconnected
            _snapshot.value = MetricSnapshot.EMPTY
            _supportedPids.value = emptyList()
            _vehiclePrompt.value = null
            setTurbo(false)
            activeVehicle = null
            // Learned per car, so it must not survive into the next one.
            closedThrottlePct = null
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
        // Kept separately from the decoded list: the difference between the two
        // is how much of this ECU the app cannot read yet, which is worth
        // reporting rather than hiding.
        advertisedPidCount = supportedNumbers.size
        appendLog("ECU answers ${supported.size} known PIDs")

        _connection.value = ConnectionState.Connecting("Identifying vehicle…")
        val identity = newSession.readVehicleIdentity(supportedNumbers)
        activeVehicle = identity
        val known = vehicleCache[identity.identity]
        if (known != null) {
            setTurbo(known.turbo)
            _vehiclePrompt.value = null
            appendLog(
                if (known.turbo) "Known turbo vehicle ${identity.vin ?: identity.identity}"
                else "Known NA vehicle ${identity.vin ?: identity.identity}",
            )
        } else {
            setTurbo(false)
            val facts = VinDecoder.decode(identity.vin)
            _vehiclePrompt.value = VehiclePrompt(
                identity = identity.identity,
                vin = identity.vin,
                make = facts?.make,
                modelYear = facts?.modelYear,
            )
            appendLog(
                "New vehicle ${facts?.label ?: identity.vin ?: identity.identity}, " +
                    "waiting for turbo/NA",
            )
        }

        session = newSession
        _connection.value = ConnectionState.Connected(
            adapter = adapter,
            transportName = newTransport.name,
            supportedPidCount = supported.size,
            demo = deviceAddress == null,
        )

        // Trips are not started here. Connecting only means the adapter has
        // power, which it has whenever the car is parked too. The poll loop
        // starts a trip once it can see the engine actually running.
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

    /**
     * Enters the failed state and releases the adapter.
     *
     * The teardown is the important half. A failed scan used to leave the
     * RFCOMM socket wide open, so the phone still showed the adapter as
     * connected and no other app could claim the serial port until the process
     * died. Failed now always means no live socket.
     */
    private fun fail(reason: String) {
        teardownTransport()
        clearAlerts()
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
            // Getting this far means the adapter answered the whole handshake,
            // so the adapter is fine and it is the ECU that is not listening.
            // On a car with permanently powered OBD pin 16 that is almost
            // always simply the ignition being off.
            endTripIfRunning()
            fail("Adapter is fine but the ECU is not answering. Switch the ignition on.")
            return
        }

        var slowIndex = 0
        var occasionalIndex = 0
        var lastBaroAt = 0L
        var lastDtcAt = 0L
        var dtcRotation = 0
        var failures = 0
        // Measured, not assumed. A cheap clone on a short cable answers in a
        // third of the time a flaky one does, and the difference decides how
        // many of the long tail fit in a cycle.
        var readCostMs = INITIAL_READ_COST_MS
        var engineOffSince: Long? = null
        var idleShutdown = false
        // True while the engine is off but the ECU is still answering, which
        // is what separates a stop/start cut from the ignition being turned off.
        var idleStopping = false
        // Edge triggered: a trip begins when the engine *starts*, not merely
        // whenever it happens to be running. Otherwise pressing Stop mid-drive
        // would open a fresh trip on the very next cycle.
        var sawEngineOff = true

        withContext(Dispatchers.IO) {
            if (turboEnabled) {
                occasional.firstOrNull { it.key == PidRegistry.BAROMETRIC.key }?.let { baro ->
                    val now = System.currentTimeMillis()
                    readInto(active, baro, values, updatedAt, noDataCount, demoted, now)
                    lastBaroAt = now
                }
            }

            while (isActive) {
                val cycleStart = SystemClock.elapsedRealtime()
                val now = System.currentTimeMillis()
                val turbo = turboEnabled

                for (pid in fastTier) {
                    if (!turbo && pid.key in PidRegistry.turboSpecificKeys) continue
                    if (!readInto(active, pid, values, updatedAt, noDataCount, demoted, now)) {
                        throw IOException("Adapter stopped responding")
                    }
                }

                val rotation = ArrayList<ObdPid>(slowTier.size)
                for (pid in slowTier) {
                    if (pid.key in demoted) continue
                    if (!turbo && pid.key in PidRegistry.turboSpecificKeys) continue
                    rotation += pid
                }
                var slowReads = 0
                if (rotation.isNotEmpty()) {
                    while (slowReads < MAX_SLOW_READS_PER_CYCLE) {
                        val elapsed = SystemClock.elapsedRealtime() - cycleStart
                        val affordable = elapsed + readCostMs <= settings.pollIntervalMs
                        if (slowReads > 0 && !affordable) break

                        val pid = rotation[slowIndex % rotation.size]
                        slowIndex++
                        val startedAt = SystemClock.elapsedRealtime()
                        if (!readInto(active, pid, values, updatedAt, noDataCount, demoted, now)) {
                            throw IOException("Adapter stopped responding")
                        }
                        val cost = (SystemClock.elapsedRealtime() - startedAt).toDouble()
                        readCostMs = readCostMs * (1 - READ_COST_SMOOTHING) + cost * READ_COST_SMOOTHING
                        slowReads++
                    }
                }

                if (now - lastBaroAt >= BARO_REFRESH_MS) {
                    lastBaroAt = now
                    // Fuel level/type and the lifetime counters are useful on
                    // every vehicle. Only barometric pressure is turbo-only.
                    val slow = occasional.filter { pid ->
                        pid.key !in demoted && (turbo || pid.key != PidRegistry.BAROMETRIC.key)
                    }
                    if (slow.isNotEmpty()) {
                        val pid = slow[occasionalIndex % slow.size]
                        occasionalIndex++
                        readInto(active, pid, values, updatedAt, noDataCount, demoted, now)
                    }
                }

                values[PidRegistry.THROTTLE.key]?.let { throttle ->
                    closedThrottlePct = minOf(closedThrottlePct ?: throttle, throttle)
                }
                applyDerivedMetrics(values, updatedAt, now, turbo)
                if (turbo) detectInduction(values)

                val snapshot = MetricSnapshot(now, HashMap(values), HashMap(updatedAt))
                _snapshot.value = snapshot

                evaluateAlerts(snapshot)
                recorder.record(snapshot)
                publishTripProgress()

                when (engineState(values, updatedAt, now)) {
                    EngineState.RUNNING -> {
                        engineOffSince?.let { stoppedAt ->
                            val stoppedFor = now - stoppedAt
                            // The engine came back on its own with the ECU
                            // never having gone quiet, which is stop/start
                            // doing exactly what it exists to do.
                            if (idleStopping && stoppedFor >= IDLE_STOP_MIN_MS) {
                                recorder.onIdleStop(stoppedFor)
                                appendLog("Idle stop of ${stoppedFor / 1000}s ended")
                            }
                        }
                        engineOffSince = null
                        idleStopping = false
                        if (sawEngineOff) {
                            sawEngineOff = false
                            if (recorder.activeTripId == null && settings.autoStartTripOnConnect) {
                                val state = _connection.value as? ConnectionState.Connected
                                beginTrip(false, state?.adapter, state?.transportName)
                            }
                        }
                    }

                    EngineState.IDLE_STOP -> {
                        // Engine off, ECU wide awake. Holding the trip open is
                        // the whole point: a commute is one drive even if the
                        // car switched itself off at nine sets of lights.
                        val since = engineOffSince ?: now.also { engineOffSince = it }
                        val stoppedFor = now - since
                        idleStopping = true
                        if (stoppedFor >= IDLE_STOP_MAX_MS) {
                            sawEngineOff = true
                            idleStopping = false
                            if (settings.autoStartTripOnConnect && recorder.activeTripId != null) {
                                appendLog("Idle stop ran past ${IDLE_STOP_MAX_MS / 60_000} minutes, ending trip")
                                endTripIfRunning()
                            }
                            idleShutdown = true
                            return@withContext
                        }
                    }

                    EngineState.UNKNOWN -> {
                        // Reads stopped coming back. On a car that is the key
                        // being turned off, so the old grace period applies.
                        sawEngineOff = true
                        idleStopping = false
                        val since = engineOffSince ?: now.also { engineOffSince = it }
                        val stoppedFor = now - since
                        // Ending on engine-off belongs to the same setting that
                        // starts on engine-on. With it off the driver owns both
                        // ends, and the idle shutdown below is the only backstop.
                        val autoEnd = settings.autoStartTripOnConnect && recorder.activeTripId != null
                        if (stoppedFor >= ENGINE_OFF_GRACE_MS && autoEnd) {
                            appendLog("Engine stopped, ending trip")
                            endTripIfRunning()
                        }
                        if (stoppedFor >= IDLE_DISCONNECT_MS) {
                            idleShutdown = true
                            return@withContext
                        }
                    }
                }

                if (now - lastDtcAt >= DTC_POLL_MS) {
                    lastDtcAt = now
                    pollTroubleCodes(active, now, dtcRotation++)
                }

                val cycleMs = SystemClock.elapsedRealtime() - cycleStart
                _stats.value = PollStats(
                    samplesPerSecond = if (cycleMs > 0) 1000f / cycleMs else 0f,
                    lastCycleMs = cycleMs,
                    failures = failures,
                    slowReadsPerCycle = slowReads,
                )

                val remaining = settings.pollIntervalMs - cycleMs
                if (remaining > 0) delay(remaining)
            }
        }

        if (idleShutdown) {
            appendLog("Engine off for ${IDLE_DISCONNECT_MS / 60_000} minutes, closing the link")
            endTripIfRunning()
            teardownTransport()
            clearAlerts()
            _connection.value = ConnectionState.Disconnected
            _snapshot.value = MetricSnapshot.EMPTY
            _supportedPids.value = emptyList()
            _vehiclePrompt.value = null
            setTurbo(false)
            activeVehicle = null
            // Learned per car, so it must not survive into the next one.
            closedThrottlePct = null
        }
    }

    /**
     * Whether the engine is actually turning.
     *
     * This cannot be inferred from the connection. The adapter draws power from
     * OBD pin 16, which is live whether or not the key is in, so it stays paired
     * and answering with the car locked and the driver indoors.
     *
     * RPM is the primary signal. Run time since engine start is the fallback for
     * an ECU that does not publish RPM. With neither, assume the engine is
     * running rather than cutting a real drive short.
     */
    /**
     * What the engine is doing, as far as this cycle can tell.
     *
     * The distinction that matters is [IDLE_STOP] versus [UNKNOWN]. A car with
     * stop/start cuts the engine at a light but leaves the ECU powered and
     * answering, so a *fresh* zero from PID 010C means the engine is off and
     * the car is still very much awake. Turning the key off instead takes the
     * ECU with it and the reads simply stop coming back.
     *
     * Freshness is the whole trick: [values] keeps the last good reading until
     * the PID is demoted, so a stale RPM would otherwise read as a running
     * engine for several seconds after the ignition was cut.
     */
    private enum class EngineState { RUNNING, IDLE_STOP, UNKNOWN }

    private fun engineState(
        values: Map<String, Float>,
        updatedAt: Map<String, Long>,
        now: Long,
    ): EngineState {
        val fresh = updatedAt[PidRegistry.RPM.key] == now
        if (fresh) {
            val rpm = values[PidRegistry.RPM.key] ?: return EngineState.UNKNOWN
            return if (rpm > RUNNING_RPM) EngineState.RUNNING else EngineState.IDLE_STOP
        }
        // No fresh tachometer this cycle. Run time still counts as proof of
        // life on an ECU that answers 011F but was slow with 010C.
        if (updatedAt["runTime"] == now) {
            val runTime = values["runTime"] ?: 0f
            return if (runTime > 0f) EngineState.RUNNING else EngineState.IDLE_STOP
        }
        return EngineState.UNKNOWN
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
        turbo: Boolean,
    ) {
        if (turbo) {
            values[PidRegistry.MAP.key]?.let { map ->
                val baro = values[PidRegistry.BAROMETRIC.key] ?: DerivedMetrics.DEFAULT_BAROMETRIC_KPA
                values[DerivedMetrics.BOOST.key] = map - baro
                updatedAt[DerivedMetrics.BOOST.key] = now
            }
        } else {
            values.remove(DerivedMetrics.BOOST.key)
            values.remove(PidRegistry.MAP.key)
            values.remove(PidRegistry.BAROMETRIC.key)
        }

        val diesel = (values["fuelType"] ?: 1f) >= 4f
        val ecuRate = values["fuelRate"]
        if (ecuRate == null) {
            values[PidRegistry.MAF.key]?.let { maf ->
                // Overrun first: on a closed throttle above idle the injectors
                // are shut and the air still moving through the MAF carries no
                // fuel at all, so no mixture correction applies to it.
                val estimated = if (
                    FuelEconomy.isFuelCut(
                        rpm = values[PidRegistry.RPM.key],
                        speedKph = values[PidRegistry.SPEED.key],
                        throttlePct = values[PidRegistry.THROTTLE.key],
                        closedThrottlePct = closedThrottlePct,
                        loadPct = values[PidRegistry.ENGINE_LOAD.key],
                    )
                ) {
                    0f
                } else {
                    FuelEconomy.litresPerHourFromMaf(
                        mafGramsPerSec = maf,
                        diesel = diesel,
                        lambda = values["equivRatio"],
                        shortTrimPct = values["stft1"],
                        longTrimPct = values["ltft1"],
                    )
                }
                values[DerivedMetrics.FUEL_RATE_MAF.key] = estimated
                updatedAt[DerivedMetrics.FUEL_RATE_MAF.key] = now
            }
        }
        val litresPerHour = ecuRate ?: values[DerivedMetrics.FUEL_RATE_MAF.key]
        val speed = values[PidRegistry.SPEED.key] ?: 0f
        if (litresPerHour != null) {
            val instant = FuelEconomy.litresPer100Km(litresPerHour, speed)
            if (instant != null) {
                values[DerivedMetrics.FUEL_ECONOMY.key] = instant
                updatedAt[DerivedMetrics.FUEL_ECONOMY.key] = now
            } else {
                values.remove(DerivedMetrics.FUEL_ECONOMY.key)
            }
        }
    }

    /**
     * Watches for manifold pressure above ambient, which only a compressor can
     * produce. One sided on purpose: no amount of gentle driving proves a car
     * is naturally aspirated, so the state only ever moves toward FORCED.
     */
    private fun detectInduction(values: Map<String, Float>) {
        if (_induction.value == Induction.FORCED) return
        val boost = values[DerivedMetrics.BOOST.key]
        if (boost == null) {
            // No MAP at all: nothing to infer from, and no boost dial either.
            if (_induction.value == Induction.UNKNOWN && values.isNotEmpty()) {
                _induction.value = Induction.NATURAL
            }
            return
        }
        if (boost >= FORCED_INDUCTION_KPA) {
            Log.i(TAG, "Forced induction detected: %.0f kPa over ambient".format(boost))
            _induction.value = Induction.FORCED
            appendLog("Boost above ambient, treating this car as forced induction")
        } else if (_induction.value == Induction.UNKNOWN) {
            _induction.value = Induction.NATURAL
        }
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

    /**
     * Reads the faults the dashboard warning light does not tell you about.
     *
     * The lamp only ever reflects *confirmed* codes. A fault the ECU has seen
     * once but not yet confirmed sits in Mode 07 as pending, and one that
     * survived a code clear without the car re-passing its drive cycle sits in
     * Mode 0A as permanent. Neither lights the lamp and neither is counted by
     * PID 0101, so neither can be gated on the lamp or the count. This used to
     * bail out early whenever the lamp was off and the count was zero, which is
     * precisely the state a car with a pending fault reports, so the two
     * categories worth catching early were the two that were never read.
     *
     * One mode per tick, rotating, so a scan is never three round trips inside
     * one poll cycle.
     */
    private suspend fun pollTroubleCodes(session: ObdSession, now: Long, tick: Int) {
        when (tick % 4) {
            0 -> {
                val status = session.readMonitorStatus() ?: return
                _monitorStatus.value = status
                recorder.onMilStatus(status.milOn)
                recorder.onReadiness(status.incomplete.size, status.supportedCount)
            }
            1 -> refreshCodes(session, DiagnosticCode.Kind.STORED, now)
            2 -> refreshCodes(session, DiagnosticCode.Kind.PENDING, now)
            else -> refreshCodes(session, DiagnosticCode.Kind.PERMANENT, now)
        }
    }

    private suspend fun refreshCodes(session: ObdSession, kind: DiagnosticCode.Kind, now: Long) {
        val found = session.readTroubleCodes(kind)
        // Replace only this category, so a mode that comes back empty clears
        // its own codes without wiping what the other two found.
        val merged = _troubleCodes.value.filter { it.kind != kind } + found
        _troubleCodes.value = merged.sortedWith(compareBy({ it.kind.ordinal }, { it.code }))
        if (found.isNotEmpty()) {
            recorder.onTroubleCodes(found, now)
            appendLog("${kind.label} codes: ${found.joinToString { it.code }}")
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
        val identity = activeVehicle?.identity
        val profile = identity?.let { vehicleCache[it] }
        val id = recorder.start(
            startedManually = manual,
            adapterName = transportName,
            protocol = adapter?.protocol,
            vehicleIdentity = identity,
            // Snapshotted now, not resolved at read time: renaming a car later
            // should not retitle drives already in the history.
            vehicleName = profile?.takeIf { it.isNamed }?.displayName,
            advertisedPids = advertisedPidCount,
            decodableKeys = _supportedPids.value.mapTo(HashSet()) { it.key },
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
            fuelLitres = recorder.currentFuelLitres,
            tripLPer100 = recorder.currentTripEconomy,
            instantLPer100 = _snapshot.value[DerivedMetrics.FUEL_ECONOMY.key],
            fuelSource = recorder.currentFuelSource,
        )
    }

    fun answerVehiclePrompt(turbo: Boolean) {
        val prompt = _vehiclePrompt.value ?: return
        val profile = VehicleProfile(
            identity = prompt.identity,
            vin = prompt.vin,
            turbo = turbo,
            labeledAt = System.currentTimeMillis(),
            make = prompt.make,
            modelYear = prompt.modelYear,
        )
        _vehiclePrompt.value = null
        setTurbo(turbo)
        scope.launch { settingsStore.rememberVehicle(profile) }
        appendLog(if (turbo) "This car is turbocharged" else "This car is naturally aspirated")
    }

    fun setActiveVehicleTurbo(identity: String, turbo: Boolean) {
        val info = activeVehicle ?: return
        if (info.identity != identity) return
        setTurbo(turbo)
        val facts = VinDecoder.decode(info.vin)
        scope.launch {
            // Carries the existing name across rather than rebuilding the
            // profile from scratch: toggling turbo must not erase a model the
            // driver typed in.
            val existing = vehicleCache[identity]
            settingsStore.rememberVehicle(
                VehicleProfile(
                    identity = info.identity,
                    vin = info.vin,
                    turbo = turbo,
                    labeledAt = System.currentTimeMillis(),
                    make = existing?.make ?: facts?.make,
                    modelYear = existing?.modelYear ?: facts?.modelYear,
                    model = existing?.model,
                ),
            )
        }
    }

    /** Saves the model name the driver typed for a car already on file. */
    fun setVehicleModel(identity: String, model: String) {
        val existing = vehicleCache[identity] ?: return
        scope.launch {
            settingsStore.rememberVehicle(
                existing.copy(model = model.trim().ifBlank { null }),
            )
        }
    }

    private fun setTurbo(turbo: Boolean) {
        turboEnabled = turbo
        _turboCar.value = turbo
        // The driver has explicitly classified this vehicle. This is more
        // dependable than waiting for a boost event (a turbo driven gently
        // would otherwise look naturally aspirated), and keeps the dial scale
        // and poll plan in agreement immediately after the choice.
        _induction.value = if (turbo) Induction.FORCED else Induction.NATURAL
    }

    // ---- Alerts & diagnostics ---------------------------------------------

    /**
     * Drops every live alert when a session ends.
     *
     * An alert only clears when its metric is seen back inside its bound, so one
     * raised on a slow-rotation PID could never recover once polling stopped: it
     * would sit there through the next connect, already acknowledged, describing
     * a reading from an earlier drive.
     */
    private fun clearAlerts() {
        alertEngine.snapshot().forEach { notifier.clear(it.metricKey) }
        alertEngine.reset()
        _alerts.value = emptyList()
        _troubleCodes.value = emptyList()
        _monitorStatus.value = null
        _induction.value = Induction.UNKNOWN
        _vehiclePrompt.value = null
    }

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
