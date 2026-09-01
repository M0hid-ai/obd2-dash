package com.mohid.obd2dash.obd.transport

import com.mohid.obd2dash.obd.DtcDecoder
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A stand-in ELM327 that drives a plausible small turbo engine
 * around a repeating two-minute cycle.
 *
 * The point is that everything above the transport (handshake, PID scan,
 * polling, boost maths, alerts, trip logging) runs unmodified against it, so
 * the app can be developed and demoed without sitting in the car.
 */
class SimulatedObdTransport(
    private val injectedCodes: List<String> = emptyList(),
    /**
     * Faults the car will not tell its own driver about: pending codes have
     * been seen once but not confirmed, permanent ones outlived a code clear.
     * Neither lights the dashboard lamp, so neither shows up in [injectedCodes]
     * or in the PID 0101 count.
     */
    private val pendingCodes: List<String> = emptyList(),
    private val permanentCodes: List<String> = emptyList(),
    /** Emissions self-tests reported as still running. */
    private val incompleteMonitors: Boolean = false,
    private val latencyMs: LongRange = 25L..55L,
) : ObdTransport {

    override val name: String = "Simulated ECU (demo)"

    private var opened = false
    private var startedAtMs = 0L
    private val random = Random(0xB007)

    override val isOpen: Boolean get() = opened

    override suspend fun open() {
        startedAtMs = System.currentTimeMillis()
        opened = true
    }

    override fun close() {
        opened = false
    }

    override suspend fun request(command: String, timeoutMs: Long): String {
        if (!opened) throw IllegalStateException("Transport is not open")
        delay(random.nextLong(latencyMs.first, latencyMs.last + 1))
        val cmd = command.uppercase().replace(" ", "")
        val body = respond(cmd)
        return "$body\r\r>"
    }

    private fun respond(cmd: String): String = when {
        cmd == "ATZ" -> "ELM327 v1.5"
        cmd == "ATI" -> "ELM327 v1.5"
        cmd == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        // 'A' for auto-detected, '6' for ISO 15765-4 CAN 11 bit / 500 kbaud.
        cmd == "ATDPN" -> "A6"
        cmd == "ATRV" -> "%.1fV".format(state().voltage)
        cmd.startsWith("AT") -> "OK"
        cmd.startsWith("03") -> dtcReply(0x03, injectedCodes)
        cmd.startsWith("07") -> dtcReply(0x07, pendingCodes)
        cmd.startsWith("0A") -> dtcReply(0x0A, permanentCodes)
        cmd.startsWith("01") -> mode01Reply(cmd)
        cmd.startsWith("0902") -> vinReply()
        cmd.startsWith("0904") -> calidReply()
        else -> "NO DATA"
    }

    // ---- Mode 01 -----------------------------------------------------------

    private fun mode01Reply(cmd: String): String {
        val pid = cmd.substring(2, 4).toIntOrNull(16) ?: return "NO DATA"

        if (pid in SUPPORT_ENQUIRIES) {
            return "41" + "%02X".format(pid) + supportMask(pid).joinToString("") { "%02X".format(it) }
        }
        if (pid !in supportedPids) return "NO DATA"

        val data = encode(pid, state()) ?: return "NO DATA"
        return "41" + "%02X".format(pid) + data.joinToString("") { "%02X".format(it.coerceIn(0, 255)) }
    }

    /**
     * Builds the four-byte support bitmask for the block starting at [base],
     * derived from [supportedPids] rather than hard-coded, so adding a PID to
     * the simulator automatically advertises it.
     */
    private fun supportMask(base: Int): IntArray {
        var mask = 0L
        for (i in 0 until 32) {
            val pid = base + i + 1
            val advertise = pid in supportedPids ||
                (pid % 0x20 == 0 && supportedPids.any { it > pid })
            if (advertise) mask = mask or (1L shl (31 - i))
        }
        return IntArray(4) { ((mask shr ((3 - it) * 8)) and 0xFF).toInt() }
    }

    private fun encode(pid: Int, s: EngineState): IntArray? {
        fun pct(v: Float) = intArrayOf((v * 255f / 100f).roundToInt())
        fun word(v: Int) = intArrayOf((v shr 8) and 0xFF, v and 0xFF)
        return when (pid) {
            // Byte A: the lamp and the confirmed count, which pending and
            // permanent codes deliberately do not contribute to. Byte D carries
            // the incomplete flags for the monitors byte C says are supported.
            0x01 -> intArrayOf(
                if (injectedCodes.isEmpty()) 0 else 0x80 or injectedCodes.size,
                if (incompleteMonitors) 0x77 else 0x07,
                0xE1,
                if (incompleteMonitors) 0xE1 else 0x00,
            )
            0x03 -> intArrayOf(0x02, 0x00) // closed loop, using O2 feedback
            0x04 -> pct(s.engineLoad)
            0x05 -> intArrayOf((s.coolantC + 40f).roundToInt())
            0x06 -> intArrayOf((s.shortTrim * 128f / 100f + 128f).roundToInt())
            0x07 -> intArrayOf((s.longTrim * 128f / 100f + 128f).roundToInt())
            0x0B -> intArrayOf(s.mapKpa.roundToInt())
            0x0C -> word((s.rpm * 4f).roundToInt())
            0x0D -> intArrayOf(s.speedKph.roundToInt())
            0x0E -> intArrayOf(((s.timingAdvance + 64f) * 2f).roundToInt())
            0x0F -> intArrayOf((s.intakeAirC + 40f).roundToInt())
            0x10 -> word((s.mafGps * 100f).roundToInt())
            0x11 -> pct(s.throttle)
            0x1F -> word(s.runTimeSec)
            0x21 -> word(0)
            0x2F -> pct(s.fuelLevel)
            0x33 -> intArrayOf(BARO_KPA)
            0x42 -> word((s.voltage * 1000f).roundToInt())
            0x43 -> word((s.engineLoad * 255f / 100f).roundToInt())
            0x44 -> word((s.lambda * 32768f).roundToInt())
            0x45 -> pct(s.throttle * 0.94f)
            0x46 -> intArrayOf((AMBIENT_C + 40f).roundToInt())
            0x49 -> pct(s.throttle * 1.02f)
            0x4C -> pct(s.throttle)
            0x5C -> intArrayOf((s.oilC + 40f).roundToInt())
            0x5E -> word((s.fuelLph * 20f).roundToInt())
            else -> null
        }
    }

    private fun dtcReply(mode: Int, codes: List<String>): String {
        if (codes.isEmpty()) return "%02X00".format(mode + 0x40)
        val bytes = codes.mapNotNull(::encodeDtc)
        return "%02X%02X".format(mode + 0x40, bytes.size) +
            bytes.joinToString("") { "%02X%02X".format(it.first, it.second) }
    }

    /** Inverse of [DtcDecoder.format], e.g. "P0301" -> 0x03, 0x01. */
    private fun encodeDtc(code: String): Pair<Int, Int>? {
        if (code.length != 5) return null
        val system = "PCBU".indexOf(code[0]).takeIf { it >= 0 } ?: return null
        val digits = code.drop(1).map { Character.digit(it, 16) }
        if (digits.any { it < 0 }) return null
        val a = (system shl 6) or (digits[0] shl 4) or digits[1]
        val b = (digits[2] shl 4) or digits[3]
        return a to b
    }

    // ---- The pretend engine ------------------------------------------------

    private data class EngineState(
        val rpm: Float,
        val speedKph: Float,
        val throttle: Float,
        val mapKpa: Float,
        val coolantC: Float,
        val oilC: Float,
        val intakeAirC: Float,
        val mafGps: Float,
        val engineLoad: Float,
        val timingAdvance: Float,
        val voltage: Float,
        val shortTrim: Float,
        val longTrim: Float,
        val lambda: Float,
        val fuelLevel: Float,
        val runTimeSec: Int,
        val fuelLph: Float,
    )

    /**
     * Speed keyframes for one 120-second loop: idle, pull away, cruise, an
     * overtake, then back down to a stop. Throttle and everything downstream
     * are derived from this curve rather than scripted separately, which keeps
     * the readings consistent with each other.
     */
    private val speedCurve = listOf(
        0f to 0f, 12f to 0f, 20f to 22f, 30f to 50f, 45f to 52f, 55f to 55f,
        60f to 70f, 66f to 85f, 78f to 86f, 85f to 84f, 95f to 45f,
        100f to 20f, 106f to 40f, 113f to 30f, 120f to 0f,
    )

    private fun state(): EngineState {
        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000f
        val t = elapsed % CYCLE_SECONDS

        val speed = max(0f, speedAt(t))
        // Numerical derivative gives the acceleration the driver is asking for.
        val accel = (speedAt(t + 0.25f) - speedAt(t - 0.25f)) / 0.5f

        val drag = 0.004f * speed * speed + 0.10f * speed
        val throttle = when {
            speed < 0.5f && accel <= 0f -> 0f
            else -> (drag + max(0f, accel) * 9f).coerceIn(0f, 100f)
        }

        // CVT: revs track pedal demand far more than road speed.
        val rpm = (750f + throttle * 46f + speed * 7f)
            .coerceIn(720f, 6900f) + jitter(t, 3.7f, 25f)

        val spool = ((rpm - 1900f) / 2100f).coerceIn(0f, 1f)
        val boost = MAX_BOOST_KPA * pow15(throttle / 100f) * spool
        val vacuum = 62f * (1f - throttle / 100f)
        val mapKpa = (BARO_KPA + boost - vacuum).coerceIn(15f, 250f)

        // Warm-up: exponential approach to the thermostat, then a slow cycle
        // as the fan and thermostat trade off.
        val warm = 1f - Math.exp((-elapsed / 150f).toDouble()).toFloat()
        val coolant = AMBIENT_C + (90f - AMBIENT_C) * warm + sin(elapsed / 24f) * 2.2f
        val oil = AMBIENT_C + (98f - AMBIENT_C) * (1f - Math.exp((-elapsed / 260f).toDouble()).toFloat())

        val intake = AMBIENT_C + 9f + max(0f, boost) * 0.30f - min(speed, 80f) * 0.05f
        val maf = (rpm / 1000f) * (mapKpa / 100f) * 4.6f + 0.4f
        val fuelLph = (maf / 14.7f / 0.737f) * 3.6f
        val load = (throttle * 0.72f + max(0f, boost) * 0.42f + 8f).coerceIn(0f, 100f)
        val timing = (30f - throttle * 0.16f - max(0f, boost) * 0.22f).coerceIn(-8f, 42f)
        val voltage = 14.25f - load * 0.006f + jitter(t, 1.3f, 0.04f)
        val lambda = if (boost > 12f) 0.84f + (1f - spool) * 0.05f else 1.0f + jitter(t, 5.1f, 0.02f)

        return EngineState(
            rpm = rpm,
            speedKph = speed,
            throttle = throttle,
            mapKpa = mapKpa,
            coolantC = coolant,
            oilC = oil,
            intakeAirC = intake,
            mafGps = maf,
            engineLoad = load,
            timingAdvance = timing,
            voltage = voltage,
            shortTrim = jitter(t, 2.3f, 4.5f),
            longTrim = 2.3f + jitter(t, 0.4f, 1.2f),
            lambda = lambda,
            fuelLevel = (66f - elapsed / 900f).coerceIn(4f, 100f),
            runTimeSec = elapsed.toInt(),
            fuelLph = fuelLph,
        )
    }

    private fun speedAt(time: Float): Float {
        val t = ((time % CYCLE_SECONDS) + CYCLE_SECONDS) % CYCLE_SECONDS
        for (i in 0 until speedCurve.size - 1) {
            val (t0, v0) = speedCurve[i]
            val (t1, v1) = speedCurve[i + 1]
            if (t in t0..t1) {
                val f = if (t1 == t0) 0f else (t - t0) / (t1 - t0)
                return v0 + (v1 - v0) * smoothstep(f)
            }
        }
        return speedCurve.last().second
    }

    /** Cubic ease so the derived acceleration is continuous at the keyframes. */
    private fun smoothstep(f: Float): Float = f * f * (3f - 2f * f)

    private fun pow15(x: Float): Float = x * kotlin.math.sqrt(abs(x))

    /** Deterministic wobble so the gauges never look frozen. */
    private fun jitter(t: Float, freq: Float, amplitude: Float): Float =
        sin(t * freq) * sin(t * freq * 2.7f + 1.1f) * amplitude

    private companion object {
        const val CYCLE_SECONDS = 120f
        const val BARO_KPA = 101
        const val AMBIENT_C = 28f
        const val MAX_BOOST_KPA = 72f

        val SUPPORT_ENQUIRIES = setOf(0x00, 0x20, 0x40)

        val supportedPids = setOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x1F, 0x21, 0x2F, 0x33, 0x42, 0x43, 0x44, 0x45, 0x46,
            0x49, 0x4C, 0x5C, 0x5E,
        )

        const val SIM_VIN = "JTDBR32E720000001"
        const val SIM_CALID = "DEMOSIM-ECU-0001"
    }

    private fun vinReply(): String {
        val ascii = SIM_VIN.map { it.code }
        return "490201" + ascii.joinToString("") { "%02X".format(it) }
    }

    private fun calidReply(): String {
        val ascii = SIM_CALID.map { it.code }
        return "490401" + ascii.joinToString("") { "%02X".format(it) }
    }
}
