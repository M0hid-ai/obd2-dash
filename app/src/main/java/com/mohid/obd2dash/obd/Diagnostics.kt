package com.mohid.obd2dash.obd

/**
 * One of the ECU's emissions self-tests.
 *
 * [complete] is the interesting half. A monitor that is supported but has not
 * finished has simply not seen the driving conditions it needs yet, and until
 * it does the ECU cannot tell you whether that system is healthy. A car whose
 * codes were freshly cleared shows a whole row of these incomplete, which is
 * exactly how a recently wiped fault hides from a casual scan.
 */
data class ReadinessMonitor(
    val name: String,
    val supported: Boolean,
    val complete: Boolean,
)

/**
 * PID 0101: the malfunction indicator lamp, the stored-code count, and the
 * readiness monitors.
 *
 * Byte A carries the lamp and the count. Bytes B, C and D carry the monitors,
 * as a "supported" bit paired with an *incomplete* bit, so a set bit in the
 * upper half means "still running", not "failed".
 */
data class MonitorStatus(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean = false,
    val monitors: List<ReadinessMonitor> = emptyList(),
) {
    /** Supported tests the ECU has not been able to finish yet. */
    val incomplete: List<ReadinessMonitor> get() = monitors.filter { it.supported && !it.complete }

    val supportedCount: Int get() = monitors.count { it.supported }

    companion object {
        /** Byte B, bits 0-2. Present on every OBD2 vehicle. */
        private val CONTINUOUS = listOf("Misfire", "Fuel System", "Comprehensive Components")

        /** Bytes C and D, bits 0-7, petrol. */
        private val SPARK = listOf(
            "Catalyst", "Heated Catalyst", "Evaporative System", "Secondary Air System",
            "A/C Refrigerant", "Oxygen Sensor", "Oxygen Sensor Heater", "EGR System",
        )

        /** Bytes C and D, bits 0-7, diesel. Same bit positions, different systems. */
        private val COMPRESSION = listOf(
            "NMHC Catalyst", "NOx / SCR Aftertreatment", RESERVED, "Boost Pressure",
            RESERVED, "Exhaust Gas Sensor", "PM Filter", "EGR / VVT System",
        )

        fun parse(raw: String): MonitorStatus? {
            val data = ElmProtocol.extractData(raw, "4101", 4) ?: return null
            val a = data[0]
            val b = data[1]
            val c = data[2]
            val d = data[3]

            val compression = (b and 0x08) != 0
            val monitors = ArrayList<ReadinessMonitor>(11)

            // Continuous monitors: supported in bits 0-2, incomplete in bits 4-6.
            CONTINUOUS.forEachIndexed { i, name ->
                monitors += ReadinessMonitor(
                    name = name,
                    supported = (b shr i) and 1 == 1,
                    complete = (b shr (i + 4)) and 1 == 0,
                )
            }

            // Non-continuous monitors: supported in C, incomplete in D, bit for bit.
            val names = if (compression) COMPRESSION else SPARK
            names.forEachIndexed { i, name ->
                if (name == RESERVED) return@forEachIndexed
                monitors += ReadinessMonitor(
                    name = name,
                    supported = (c shr i) and 1 == 1,
                    complete = (d shr i) and 1 == 0,
                )
            }

            return MonitorStatus(
                milOn = (a and 0x80) != 0,
                dtcCount = a and 0x7F,
                compressionIgnition = compression,
                monitors = monitors,
            )
        }
    }
}

private const val RESERVED = "Reserved"

/**
 * Plain-English text for a trouble code.
 *
 * Only the generic SAE codes can be named from a table. Anything in the
 * manufacturer-specific ranges means something different on a Daihatsu than it
 * does on a Nissan, so those are described by their range rather than guessed
 * at. A wrong definition is worse than an honest "look this one up".
 */
object DtcCatalog {

    private val generic = mapOf(
        "P0100" to "Mass or volume air flow circuit",
        "P0101" to "Mass or volume air flow circuit range/performance",
        "P0102" to "Mass or volume air flow circuit low input",
        "P0103" to "Mass or volume air flow circuit high input",
        "P0105" to "Manifold absolute pressure circuit",
        "P0106" to "Manifold absolute pressure range/performance",
        "P0107" to "Manifold absolute pressure circuit low input",
        "P0108" to "Manifold absolute pressure circuit high input",
        "P0110" to "Intake air temperature sensor circuit",
        "P0111" to "Intake air temperature range/performance",
        "P0112" to "Intake air temperature circuit low input",
        "P0113" to "Intake air temperature circuit high input",
        "P0115" to "Engine coolant temperature circuit",
        "P0116" to "Engine coolant temperature range/performance",
        "P0117" to "Engine coolant temperature circuit low input",
        "P0118" to "Engine coolant temperature circuit high input",
        "P0120" to "Throttle/pedal position sensor A circuit",
        "P0121" to "Throttle/pedal position sensor A range/performance",
        "P0122" to "Throttle/pedal position sensor A circuit low",
        "P0123" to "Throttle/pedal position sensor A circuit high",
        "P0125" to "Insufficient coolant temperature for closed loop fuel control",
        "P0128" to "Coolant thermostat below regulating temperature",
        "P0130" to "O2 sensor circuit (bank 1, sensor 1)",
        "P0131" to "O2 sensor circuit low voltage (bank 1, sensor 1)",
        "P0132" to "O2 sensor circuit high voltage (bank 1, sensor 1)",
        "P0133" to "O2 sensor circuit slow response (bank 1, sensor 1)",
        "P0134" to "O2 sensor circuit no activity (bank 1, sensor 1)",
        "P0135" to "O2 sensor heater circuit (bank 1, sensor 1)",
        "P0136" to "O2 sensor circuit (bank 1, sensor 2)",
        "P0137" to "O2 sensor circuit low voltage (bank 1, sensor 2)",
        "P0138" to "O2 sensor circuit high voltage (bank 1, sensor 2)",
        "P0139" to "O2 sensor circuit slow response (bank 1, sensor 2)",
        "P0140" to "O2 sensor circuit no activity (bank 1, sensor 2)",
        "P0141" to "O2 sensor heater circuit (bank 1, sensor 2)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0174" to "System too lean (bank 2)",
        "P0175" to "System too rich (bank 2)",
        "P0200" to "Injector circuit",
        "P0217" to "Engine over-temperature condition",
        "P0219" to "Engine over-speed condition",
        "P0230" to "Fuel pump primary circuit",
        "P0234" to "Turbocharger/supercharger overboost",
        "P0299" to "Turbocharger/supercharger underboost",
        "P0300" to "Random or multiple cylinder misfire detected",
        "P0301" to "Cylinder 1 misfire detected",
        "P0302" to "Cylinder 2 misfire detected",
        "P0303" to "Cylinder 3 misfire detected",
        "P0304" to "Cylinder 4 misfire detected",
        "P0305" to "Cylinder 5 misfire detected",
        "P0306" to "Cylinder 6 misfire detected",
        "P0315" to "Crankshaft position system variation not learned",
        "P0325" to "Knock sensor 1 circuit",
        "P0327" to "Knock sensor 1 circuit low input",
        "P0328" to "Knock sensor 1 circuit high input",
        "P0335" to "Crankshaft position sensor A circuit",
        "P0336" to "Crankshaft position sensor A range/performance",
        "P0340" to "Camshaft position sensor A circuit",
        "P0341" to "Camshaft position sensor A range/performance",
        "P0400" to "Exhaust gas recirculation flow",
        "P0401" to "Exhaust gas recirculation flow insufficient",
        "P0402" to "Exhaust gas recirculation flow excessive",
        "P0403" to "Exhaust gas recirculation control circuit",
        "P0420" to "Catalyst system efficiency below threshold (bank 1)",
        "P0430" to "Catalyst system efficiency below threshold (bank 2)",
        "P0440" to "Evaporative emission system",
        "P0441" to "Evaporative emission system incorrect purge flow",
        "P0442" to "Evaporative emission system small leak detected",
        "P0443" to "Evaporative emission purge control valve circuit",
        "P0446" to "Evaporative emission vent control circuit",
        "P0455" to "Evaporative emission system large leak detected",
        "P0456" to "Evaporative emission system very small leak detected",
        "P0500" to "Vehicle speed sensor A",
        "P0505" to "Idle air control system",
        "P0506" to "Idle air control system RPM lower than expected",
        "P0507" to "Idle air control system RPM higher than expected",
        "P0520" to "Engine oil pressure sensor/switch circuit",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0600" to "Serial communication link",
        "P0606" to "ECM/PCM processor fault",
        "P0620" to "Generator control circuit",
        "P0700" to "Transmission control system, MIL request",
        "P0705" to "Transmission range sensor circuit",
        "P0715" to "Input/turbine speed sensor circuit",
        "P0720" to "Output speed sensor circuit",
        "P0730" to "Incorrect gear ratio",
        "P0740" to "Torque converter clutch circuit",
        "P0741" to "Torque converter clutch stuck off",
        "U0100" to "Lost communication with ECM/PCM A",
        "U0101" to "Lost communication with the transmission control module",
        "U0121" to "Lost communication with the ABS control module",
        "U0140" to "Lost communication with the body control module",
        "U0155" to "Lost communication with the instrument cluster",
    )

    /** True when the code is defined by SAE rather than by the carmaker. */
    fun isGeneric(code: String): Boolean {
        if (code.length < 5) return false
        return when (code[0]) {
            'P' -> code[1] == '0' || code[1] == '2' || (code[1] == '3' && code[2] in '0'..'3')
            'C', 'B', 'U' -> code[1] == '0' || code[1] == '3'
            else -> false
        }
    }

    fun describe(code: String): String {
        generic[code]?.let { return it }
        val system = when (code.firstOrNull()) {
            'P' -> "powertrain"
            'C' -> "chassis"
            'B' -> "body"
            'U' -> "network"
            else -> "unknown"
        }
        return if (isGeneric(code)) {
            "Generic $system code, not in this app's table. Worth looking up for the exact wording."
        } else {
            "Manufacturer-specific $system code. Its meaning is set by the carmaker, so it needs " +
                "a marque-specific lookup rather than a generic one."
        }
    }
}
