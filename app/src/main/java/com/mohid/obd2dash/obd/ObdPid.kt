package com.mohid.obd2dash.obd

/**
 * Grouping used to lay out the "All Metrics" screen.
 */
enum class PidGroup(val label: String) {
    PRIMARY("Primary"),
    ENGINE("Engine"),
    AIR("Air & Intake"),
    FUEL("Fuel & Trim"),
    OXYGEN("Oxygen Sensors"),
    ELECTRICAL("Electrical"),
    COUNTERS("Counters & Status"),
}

/**
 * A single Mode 01 parameter: how to ask for it and how to turn the raw data
 * bytes into a real-world number.
 *
 * [decode] receives only the data bytes (everything after the `41 XX` echo),
 * each already widened to an unsigned 0..255 [Int]. Returning null means the
 * frame was well-formed but the value is not usable (e.g. a sensor the ECU
 * reports as "not present").
 */
class ObdPid(
    val key: String,
    val pid: Int,
    val label: String,
    val shortLabel: String,
    val unit: String,
    val dataBytes: Int,
    val displayMin: Float,
    val displayMax: Float,
    val decimals: Int,
    val group: PidGroup,
    val mode: Int = 0x01,
    val decode: (IntArray) -> Float?,
) {
    /** The bare request string, e.g. `010C`. */
    val command: String = "%02X%02X".format(mode, pid)

    /** The echo the ELM327 prefixes the answer with, e.g. `410C`. */
    val responseHeader: String = "%02X%02X".format(mode + 0x40, pid)

    fun format(value: Float): String = if (decimals == 0) {
        value.toInt().toString()
    } else {
        "%.${decimals}f".format(value)
    }

    // Identity is the key alone; the decoder lambda must not take part.
    override fun equals(other: Any?): Boolean = this === other || (other is ObdPid && other.key == key)
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "ObdPid($key)"
}

private fun pct255(d: IntArray): Float? = d.getOrNull(0)?.let { it * 100f / 255f }
private fun tempA(d: IntArray): Float? = d.getOrNull(0)?.let { it - 40f }
private fun fuelTrimPct(d: IntArray): Float? = d.getOrNull(0)?.let { (it - 128) * 100f / 128f }
private fun word(d: IntArray): Int? {
    val a = d.getOrNull(0) ?: return null
    val b = d.getOrNull(1) ?: return null
    return (a shl 8) or b
}

/**
 * The Mode 01 parameters this app knows how to decode.
 *
 * Which of these a given ECU actually answers is discovered at runtime by
 * [SupportedPids]; nothing here is assumed to be present.
 */
object PidRegistry {

    val RPM = ObdPid(
        key = "rpm", pid = 0x0C, label = "Engine RPM", shortLabel = "RPM", unit = "rpm",
        dataBytes = 2, displayMin = 0f, displayMax = 8000f, decimals = 0, group = PidGroup.PRIMARY,
        decode = { d -> word(d)?.let { it / 4f } },
    )

    val SPEED = ObdPid(
        key = "speed", pid = 0x0D, label = "Vehicle Speed", shortLabel = "Speed", unit = "km/h",
        dataBytes = 1, displayMin = 0f, displayMax = 180f, decimals = 0, group = PidGroup.PRIMARY,
        decode = { d -> d.getOrNull(0)?.toFloat() },
    )

    val COOLANT_TEMP = ObdPid(
        key = "coolantTemp", pid = 0x05, label = "Coolant Temperature", shortLabel = "Coolant",
        unit = "°C", dataBytes = 1, displayMin = -40f, displayMax = 130f, decimals = 0,
        group = PidGroup.PRIMARY, decode = ::tempA,
    )

    val MAP = ObdPid(
        key = "map", pid = 0x0B, label = "Manifold Absolute Pressure", shortLabel = "MAP",
        unit = "kPa", dataBytes = 1, displayMin = 0f, displayMax = 255f, decimals = 0,
        group = PidGroup.AIR, decode = { d -> d.getOrNull(0)?.toFloat() },
    )

    val BAROMETRIC = ObdPid(
        key = "baro", pid = 0x33, label = "Barometric Pressure", shortLabel = "Baro",
        unit = "kPa", dataBytes = 1, displayMin = 0f, displayMax = 255f, decimals = 0,
        group = PidGroup.AIR, decode = { d -> d.getOrNull(0)?.toFloat() },
    )

    val ENGINE_LOAD = ObdPid(
        key = "engineLoad", pid = 0x04, label = "Calculated Engine Load", shortLabel = "Load",
        unit = "%", dataBytes = 1, displayMin = 0f, displayMax = 100f, decimals = 0,
        group = PidGroup.ENGINE, decode = ::pct255,
    )

    val THROTTLE = ObdPid(
        key = "throttle", pid = 0x11, label = "Throttle Position", shortLabel = "Throttle",
        unit = "%", dataBytes = 1, displayMin = 0f, displayMax = 100f, decimals = 0,
        group = PidGroup.ENGINE, decode = ::pct255,
    )

    val INTAKE_AIR_TEMP = ObdPid(
        key = "intakeAirTemp", pid = 0x0F, label = "Intake Air Temperature", shortLabel = "IAT",
        unit = "°C", dataBytes = 1, displayMin = -40f, displayMax = 120f, decimals = 0,
        group = PidGroup.AIR, decode = ::tempA,
    )

    val MAF = ObdPid(
        key = "maf", pid = 0x10, label = "MAF Air Flow Rate", shortLabel = "MAF",
        unit = "g/s", dataBytes = 2, displayMin = 0f, displayMax = 200f, decimals = 2,
        group = PidGroup.AIR, decode = { d -> word(d)?.let { it / 100f } },
    )

    val TIMING_ADVANCE = ObdPid(
        key = "timingAdvance", pid = 0x0E, label = "Timing Advance", shortLabel = "Timing",
        unit = "°", dataBytes = 1, displayMin = -64f, displayMax = 64f, decimals = 1,
        group = PidGroup.ENGINE, decode = { d -> d.getOrNull(0)?.let { it / 2f - 64f } },
    )

    val CONTROL_VOLTAGE = ObdPid(
        key = "controlVoltage", pid = 0x42, label = "Control Module Voltage", shortLabel = "Voltage",
        unit = "V", dataBytes = 2, displayMin = 0f, displayMax = 18f, decimals = 2,
        group = PidGroup.ELECTRICAL, decode = { d -> word(d)?.let { it / 1000f } },
    )

    private val fuelTrims = listOf(
        ObdPid("stft1", 0x06, "Short Term Fuel Trim (Bank 1)", "STFT B1", "%", 1, -100f, 99f, 1, PidGroup.FUEL, decode = ::fuelTrimPct),
        ObdPid("ltft1", 0x07, "Long Term Fuel Trim (Bank 1)", "LTFT B1", "%", 1, -100f, 99f, 1, PidGroup.FUEL, decode = ::fuelTrimPct),
        ObdPid("stft2", 0x08, "Short Term Fuel Trim (Bank 2)", "STFT B2", "%", 1, -100f, 99f, 1, PidGroup.FUEL, decode = ::fuelTrimPct),
        ObdPid("ltft2", 0x09, "Long Term Fuel Trim (Bank 2)", "LTFT B2", "%", 1, -100f, 99f, 1, PidGroup.FUEL, decode = ::fuelTrimPct),
    )

    /**
     * PIDs 0x14..0x1B. Byte A is the sensor voltage; byte B carries the
     * associated short-term trim, or 0xFF when the sensor is not used in
     * the trim calculation.
     */
    private val narrowBandO2 = (0x14..0x1B).mapIndexed { index, pid ->
        val n = index + 1
        ObdPid(
            key = "o2s${n}Voltage", pid = pid, label = "O2 Sensor $n Voltage",
            shortLabel = "O2 S$n", unit = "V", dataBytes = 2,
            displayMin = 0f, displayMax = 1.275f, decimals = 3, group = PidGroup.OXYGEN,
            decode = { d -> d.getOrNull(0)?.let { it / 200f } },
        )
    }

    /**
     * PIDs 0x24..0x2B, the wide-band sensors. Reported as the equivalence ratio
     * (lambda); 1.0 is stoichiometric.
     */
    private val wideBandO2 = (0x24..0x2B).mapIndexed { index, pid ->
        val n = index + 1
        ObdPid(
            key = "o2s${n}Lambda", pid = pid, label = "O2 Sensor $n Lambda",
            shortLabel = "λ S$n", unit = "", dataBytes = 4,
            displayMin = 0f, displayMax = 2f, decimals = 3, group = PidGroup.OXYGEN,
            decode = { d -> word(d)?.let { it / 32768f } },
        )
    }

    private val catalystTemps = listOf(
        Triple("catTempB1S1", 0x3C, "Bank 1 Sensor 1"),
        Triple("catTempB2S1", 0x3D, "Bank 2 Sensor 1"),
        Triple("catTempB1S2", 0x3E, "Bank 1 Sensor 2"),
        Triple("catTempB2S2", 0x3F, "Bank 2 Sensor 2"),
    ).map { (key, pid, position) ->
        ObdPid(
            key = key, pid = pid, label = "Catalyst Temperature ($position)",
            shortLabel = position, unit = "°C", dataBytes = 2,
            displayMin = -40f, displayMax = 1000f, decimals = 0, group = PidGroup.ENGINE,
            decode = { d -> word(d)?.let { it / 10f - 40f } },
        )
    }

    private val others = listOf(
        ObdPid("fuelPressure", 0x0A, "Fuel Pressure", "Fuel Press", "kPa", 1, 0f, 765f, 0, PidGroup.FUEL,
            decode = { d -> d.getOrNull(0)?.let { it * 3f } }),
        ObdPid("runTime", 0x1F, "Run Time Since Engine Start", "Run Time", "s", 2, 0f, 65535f, 0, PidGroup.COUNTERS,
            decode = { d -> word(d)?.toFloat() }),
        ObdPid("distanceMil", 0x21, "Distance With MIL On", "Dist. MIL", "km", 2, 0f, 65535f, 0, PidGroup.COUNTERS,
            decode = { d -> word(d)?.toFloat() }),
        ObdPid("fuelRailPressure", 0x23, "Fuel Rail Gauge Pressure", "Rail Press", "kPa", 2, 0f, 655350f, 0, PidGroup.FUEL,
            decode = { d -> word(d)?.let { it * 10f } }),
        ObdPid("egrCommanded", 0x2C, "Commanded EGR", "EGR Cmd", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("egrError", 0x2D, "EGR Error", "EGR Err", "%", 1, -100f, 99f, 1, PidGroup.ENGINE, decode = ::fuelTrimPct),
        ObdPid("evapPurge", 0x2E, "Commanded Evaporative Purge", "Evap Purge", "%", 1, 0f, 100f, 1, PidGroup.FUEL, decode = ::pct255),
        ObdPid("fuelLevel", 0x2F, "Fuel Tank Level", "Fuel Level", "%", 1, 0f, 100f, 0, PidGroup.FUEL, decode = ::pct255),
        ObdPid("warmups", 0x30, "Warm-ups Since Codes Cleared", "Warm-ups", "", 1, 0f, 255f, 0, PidGroup.COUNTERS,
            decode = { d -> d.getOrNull(0)?.toFloat() }),
        ObdPid("distanceCleared", 0x31, "Distance Since Codes Cleared", "Dist. Cleared", "km", 2, 0f, 65535f, 0, PidGroup.COUNTERS,
            decode = { d -> word(d)?.toFloat() }),
        ObdPid("absoluteLoad", 0x43, "Absolute Load Value", "Abs Load", "%", 2, 0f, 400f, 1, PidGroup.ENGINE,
            decode = { d -> word(d)?.let { it * 100f / 255f } }),
        ObdPid("equivRatio", 0x44, "Commanded Equivalence Ratio", "λ Cmd", "", 2, 0f, 2f, 3, PidGroup.FUEL,
            decode = { d -> word(d)?.let { it / 32768f } }),
        ObdPid("relThrottle", 0x45, "Relative Throttle Position", "Rel Throttle", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("ambientTemp", 0x46, "Ambient Air Temperature", "Ambient", "°C", 1, -40f, 60f, 0, PidGroup.AIR, decode = ::tempA),
        ObdPid("throttleB", 0x47, "Absolute Throttle Position B", "Throttle B", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("throttleC", 0x48, "Absolute Throttle Position C", "Throttle C", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("pedalD", 0x49, "Accelerator Pedal Position D", "Pedal D", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("pedalE", 0x4A, "Accelerator Pedal Position E", "Pedal E", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("pedalF", 0x4B, "Accelerator Pedal Position F", "Pedal F", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("throttleActuator", 0x4C, "Commanded Throttle Actuator", "Throttle Act", "%", 1, 0f, 100f, 1, PidGroup.ENGINE, decode = ::pct255),
        ObdPid("milTime", 0x4D, "Run Time With MIL On", "MIL Time", "min", 2, 0f, 65535f, 0, PidGroup.COUNTERS,
            decode = { d -> word(d)?.toFloat() }),
        ObdPid("clearedTime", 0x4E, "Time Since Codes Cleared", "Since Cleared", "min", 2, 0f, 65535f, 0, PidGroup.COUNTERS,
            decode = { d -> word(d)?.toFloat() }),
        ObdPid("oilTemp", 0x5C, "Engine Oil Temperature", "Oil Temp", "°C", 1, -40f, 210f, 0, PidGroup.ENGINE, decode = ::tempA),
        ObdPid("fuelRate", 0x5E, "Engine Fuel Rate", "Fuel Rate", "L/h", 2, 0f, 3212f, 2, PidGroup.FUEL,
            decode = { d -> word(d)?.let { it / 20f } }),
        ObdPid("fuelType", 0x51, "Fuel Type", "Fuel Type", "", 1, 0f, 23f, 0, PidGroup.FUEL,
            decode = { d -> d.getOrNull(0)?.toFloat() }),
    )

    /** Every decodable PID, keyed by its stable string id. */
    val all: List<ObdPid> = buildList {
        add(RPM); add(SPEED); add(COOLANT_TEMP); add(MAP); add(BAROMETRIC)
        add(ENGINE_LOAD); add(THROTTLE); add(INTAKE_AIR_TEMP); add(MAF)
        add(TIMING_ADVANCE); add(CONTROL_VOLTAGE)
        addAll(fuelTrims); addAll(narrowBandO2); addAll(wideBandO2)
        addAll(catalystTemps); addAll(others)
    }.sortedBy { it.pid }

    private val pidIndex: Map<Int, ObdPid> = all.associateBy { it.pid }
    private val keyIndex: Map<String, ObdPid> = all.associateBy { it.key }

    fun byPid(pid: Int): ObdPid? = pidIndex[pid]
    fun byKey(key: String): ObdPid? = keyIndex[key]

    /**
     * Polled every cycle. These are what the four dashboard gauges move on and
     * what the alert engine watches for a fast-moving breach, so they need the
     * full sample rate. Everything else is round-robined one per cycle.
     *
     * Throttle and engine load used to be in here too, but neither drives a
     * gauge or a time-critical alert, and each one was a full request-response
     * round trip added to every cycle. On a real Bluetooth Classic connection
     * that round trip is the actual bottleneck, so dropping two of five fast
     * reads measurably speeds up the cycle everything else depends on.
     */
    val highRate: List<ObdPid> = listOf(RPM, SPEED, MAP)

    /**
     * Only useful on a force-fed engine. MAP still exists on plenty of NA cars,
     * but spending a full RFCOMM round trip every cycle on it just to watch
     * vacuum is a waste when that slot could keep the gauges at rate instead.
     * Boost is derived from MAP, so it goes with it.
     */
    val turboSpecificKeys: Set<String> = setOf(MAP.key, BAROMETRIC.key, "boost")

    /**
     * Barometric pressure only moves with the weather and your altitude, so it
     * is read once at connect and refreshed occasionally rather than polled.
     */
    val rarelyChanging: Set<String> = setOf(
        BAROMETRIC.key,
        // Odometer-style counters that only move over whole drives, plus the
        // MIL counters, which sit at zero on a healthy car and can only start
        // moving after a fault the diagnostics poll has already caught.
        "fuelLevel", "warmups", "distanceCleared", "clearedTime", "distanceMil", "milTime", "fuelType",
    )
}

/**
 * Metrics the app computes rather than reads. They live alongside real PIDs
 * everywhere downstream (gauges, logging, alerts), so they need stable keys.
 */
object DerivedMetrics {

    /**
     * Turbo boost, as gauge pressure relative to ambient. Positive is boost,
     * negative is vacuum (off throttle).
     */
    val BOOST = ObdPid(
        key = "boost", pid = -1, label = "Boost Pressure", shortLabel = "Boost",
        unit = "kPa", dataBytes = 0, displayMin = -100f, displayMax = 150f, decimals = 0,
        group = PidGroup.PRIMARY, decode = { null },
    )

    /**
     * Litres per hour inferred from MAF when PID 015E is absent. Same unit as
     * the real fuel-rate PID so trip integration can treat them alike.
     */
    val FUEL_RATE_MAF = ObdPid(
        key = "fuelRateMaf", pid = -2, label = "Estimated Fuel Rate (MAF)", shortLabel = "Fuel Est.",
        unit = "L/h", dataBytes = 0, displayMin = 0f, displayMax = 40f, decimals = 2,
        group = PidGroup.FUEL, decode = { null },
    )

    val FUEL_ECONOMY = ObdPid(
        key = "fuelEcon", pid = -3, label = "Instant Fuel Economy", shortLabel = "Fuel",
        unit = "L/100 km", dataBytes = 0, displayMin = 0f, displayMax = 40f, decimals = 1,
        group = PidGroup.FUEL, decode = { null },
    )

    /** Fallback used when the ECU does not report PID 0133: sea-level standard. */
    const val DEFAULT_BAROMETRIC_KPA = 101f

    val all: List<ObdPid> = listOf(BOOST, FUEL_RATE_MAF, FUEL_ECONOMY)

    fun byKey(key: String): ObdPid? = all.firstOrNull { it.key == key }
}

/** Lookup across both real and derived metrics. */
fun metricByKey(key: String): ObdPid? = PidRegistry.byKey(key) ?: DerivedMetrics.byKey(key)

fun kpaToBar(kpa: Float): Float = kpa / 100f
fun kpaToPsi(kpa: Float): Float = kpa * 0.1450377f
