package com.mohid.obd2dash

import com.mohid.obd2dash.alerts.AlertEngine
import com.mohid.obd2dash.alerts.AlertSeverity
import com.mohid.obd2dash.alerts.AlertUpdate
import com.mohid.obd2dash.alerts.DefaultThresholds
import com.mohid.obd2dash.data.db.MetricPack
import com.mohid.obd2dash.obd.DiagnosticCode
import com.mohid.obd2dash.obd.MetricSnapshot
import com.mohid.obd2dash.obd.ObdSession
import com.mohid.obd2dash.obd.PidRegistry
import com.mohid.obd2dash.obd.ReadResult
import com.mohid.obd2dash.obd.transport.SimulatedObdTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real [ObdSession] against the simulated adapter.
 *
 * This is the closest thing to an end-to-end test that can run without the car:
 * handshake, support scan, and value reads all go through exactly the code the
 * Bluetooth transport uses.
 */
class SimulatedSessionTest {

    @Test
    fun `handshake reports the adapter and protocol`() = runTest {
        val transport = SimulatedObdTransport()
        transport.open()
        val info = ObdSession(transport).initialize()

        assertTrue("Got '${info.version}'", info.version.contains("ELM327"))
        assertTrue("Got '${info.protocol}'", info.protocol.contains("15765"))
    }

    @Test
    fun `support scan finds what the simulated ECU advertises and nothing else`() = runTest {
        val transport = SimulatedObdTransport()
        transport.open()
        val session = ObdSession(transport)
        session.initialize()

        val supported = session.scanSupportedPids()

        assertTrue("RPM missing", 0x0C in supported)
        assertTrue("Speed missing", 0x0D in supported)
        assertTrue("Coolant missing", 0x05 in supported)
        assertTrue("MAP missing", 0x0B in supported)
        assertTrue("Barometric missing", 0x33 in supported)
        assertTrue("Oil temp missing", 0x5C in supported)
        // Not modelled by the simulator, so it must not be advertised.
        assertTrue("Fuel pressure should be absent", 0x0A !in supported)
        // The enquiry PIDs themselves are not readable values.
        assertTrue(0x00 !in supported)
        assertTrue(0x20 !in supported)
    }

    @Test
    fun `reads land inside the engine's plausible range`() = runTest {
        val transport = SimulatedObdTransport()
        transport.open()
        val session = ObdSession(transport)
        session.initialize()

        val rpm = session.read(PidRegistry.RPM)
        assertTrue("Expected a value, got $rpm", rpm is ReadResult.Value)
        val revs = (rpm as ReadResult.Value).value
        assertTrue("RPM out of range: $revs", revs in 600f..7200f)

        val coolant = session.read(PidRegistry.COOLANT_TEMP)
        assertTrue(coolant is ReadResult.Value)
        assertTrue((coolant as ReadResult.Value).value in -40f..130f)

        val baro = session.read(PidRegistry.BAROMETRIC)
        assertEquals(101f, (baro as ReadResult.Value).value, 0.5f)
    }

    @Test
    fun `an unsupported pid comes back as no data rather than a bogus number`() = runTest {
        val transport = SimulatedObdTransport()
        transport.open()
        val session = ObdSession(transport)
        session.initialize()

        val fuelPressure = PidRegistry.byPid(0x0A)!!
        assertEquals(ReadResult.NoData, session.read(fuelPressure))
    }

    @Test
    fun `injected trouble codes survive the encode and decode round trip`() = runTest {
        val transport = SimulatedObdTransport(injectedCodes = listOf("P0301", "P0420"))
        transport.open()
        val session = ObdSession(transport)
        session.initialize()

        val codes = session.readTroubleCodes(DiagnosticCode.Kind.STORED)
        assertEquals(listOf("P0301", "P0420"), codes.map { it.code })
        assertEquals("Powertrain", codes.first().system)

        val status = session.readMonitorStatus()
        assertNotNull(status)
        assertEquals(true, status!!.milOn)
        assertEquals(2, status.dtcCount)
    }
}

class AlertEngineTest {

    /** Roughly a real Bluetooth Classic cycle: slower than the demo loop. */
    private val cycleMs = 850L

    private fun snapshot(vararg values: Pair<String, Float>, at: Long) =
        MetricSnapshot(at, values.toMap())

    private fun engine() = AlertEngine().apply { setRules(DefaultThresholds.rules) }

    /** Feeds the same reading [times] times, [cycleMs] apart, starting at t=0. */
    private fun AlertEngine.evaluateRepeated(key: String, value: Float, times: Int): AlertUpdate {
        var last: AlertUpdate? = null
        repeat(times) { i -> last = evaluate(snapshot(key to value, at = i * cycleMs)) }
        return last!!
    }

    @Test
    fun `a single bad frame does not raise an alert`() {
        val engine = engine()
        val update = engine.evaluate(snapshot("coolantTemp" to 130f, at = 0L))
        assertTrue(update.active.isEmpty())
        assertTrue(update.newlyRaised.isEmpty())
    }

    @Test
    fun `a sustained breach raises once it has held long enough`() {
        val engine = engine()
        // One sample alone can't clear the minimum hold time no matter how
        // many readings it takes, so the first call must never raise.
        val first = engine.evaluate(snapshot("coolantTemp" to 106f, at = 0L))
        assertTrue(first.newlyRaised.isEmpty())

        val update = engine.evaluate(snapshot("coolantTemp" to 106f, at = cycleMs))

        assertEquals(1, update.newlyRaised.size)
        assertEquals(AlertSeverity.WARNING, update.active.single().severity)
        assertEquals("coolantTemp", update.active.single().metricKey)
    }

    @Test
    fun `a breach that clears before the hold time never raises`() {
        val engine = engine()
        engine.evaluate(snapshot("coolantTemp" to 106f, at = 0L))
        // Back in range well inside the hold window: the streak resets rather
        // than carrying over, so this must not have raised anything.
        val update = engine.evaluate(snapshot("coolantTemp" to 90f, at = 200L))

        assertTrue(update.newlyRaised.isEmpty())
        assertTrue(update.active.isEmpty())
    }

    @Test
    fun `crossing into the critical band re-sounds and clears the acknowledgement`() {
        val engine = engine()
        engine.evaluateRepeated("coolantTemp", 106f, times = 3)
        engine.acknowledge("coolantTemp")
        assertTrue(engine.snapshot().single().acknowledged)

        val update = engine.evaluate(snapshot("coolantTemp" to 115f, at = 3 * cycleMs))

        assertEquals(1, update.newlyRaised.size)
        assertEquals(AlertSeverity.CRITICAL, update.active.single().severity)
        assertTrue("Escalation must un-acknowledge", !update.active.single().acknowledged)
    }

    @Test
    fun `an alert holds through the hysteresis band and clears past it`() {
        val engine = engine()
        engine.evaluateRepeated("coolantTemp", 106f, times = 3)

        // Back under the 105 bound, but only just: the alert must hold rather
        // than flicker off and straight back on.
        val hovering = engine.evaluate(snapshot("coolantTemp" to 104f, at = 3 * cycleMs))
        assertEquals(1, hovering.active.size)

        // Comfortably back in range.
        val recovered = engine.evaluate(snapshot("coolantTemp" to 99f, at = 4 * cycleMs))
        assertTrue(recovered.active.isEmpty())
    }

    @Test
    fun `low-side bounds fire too`() {
        val engine = engine()
        engine.evaluateRepeated("controlVoltage", 11.4f, times = 3)
        val alert = engine.snapshot().single()
        assertEquals("controlVoltage", alert.metricKey)
        assertEquals(AlertSeverity.CRITICAL, alert.severity)
    }

    @Test
    fun `boost thresholds are evaluated on the derived metric`() {
        val engine = engine()
        engine.evaluateRepeated("boost", 120f, times = 3)
        val alert = engine.snapshot().single()
        assertEquals("boost", alert.metricKey)
        assertEquals(AlertSeverity.CRITICAL, alert.severity)
        assertTrue(alert.message.contains("Boost"))
    }

    @Test
    fun `disabling a rule drops its live alert`() {
        val engine = engine()
        engine.evaluateRepeated("coolantTemp", 115f, times = 3)
        assertEquals(1, engine.snapshot().size)

        engine.setRules(DefaultThresholds.rules.map { it.copy(enabled = false) })
        assertTrue(engine.snapshot().isEmpty())
    }
}

class MetricPackTest {

    @Test
    fun `round trips a set of readings`() {
        val values = mapOf("throttle" to 42.5f, "maf" to 6.25f, "intakeAirTemp" to -3f)
        assertEquals(values, MetricPack.decode(MetricPack.encode(values)))
    }

    @Test
    fun `an empty map encodes to an empty string`() {
        assertEquals("", MetricPack.encode(emptyMap()))
        assertEquals(emptyMap<String, Float>(), MetricPack.decode(""))
    }

    @Test
    fun `a malformed entry is skipped without losing the rest`() {
        assertEquals(mapOf("a" to 1f, "c" to 3f), MetricPack.decode("a=1;b=oops;c=3"))
    }
}
