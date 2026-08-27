package com.mohid.obd2dash.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readiness monitors and the trouble codes the dashboard lamp never shows.
 */
class ReadinessTest {

    /**
     * Byte B = 0x07: all three continuous monitors supported, none of the
     * incomplete bits set. Byte C = 0xE1, byte D = 0x00: catalyst, O2 sensor,
     * O2 heater and EGR supported, all complete.
     */
    @Test
    fun `a fully tested car reports every supported monitor complete`() {
        val status = MonitorStatus.parse("410100 07 E1 00\r>")!!
        assertTrue(status.monitors.isNotEmpty())
        assertTrue(status.incomplete.isEmpty())
        assertEquals(7, status.supportedCount)
    }

    /**
     * The fingerprint of a recent code clear: same monitors supported, but the
     * incomplete bits are set, so nothing has been re-verified yet.
     */
    @Test
    fun `incomplete bits mark tests the ecu has not finished`() {
        // Byte C = 0xE3 also claims the heated catalyst monitor, but byte D
        // leaves its bit clear, so it is the one supported test that has
        // finished and the only one that must not appear below.
        val status = MonitorStatus.parse("410100 77 E3 E1\r>")!!
        val names = status.incomplete.map { it.name }
        assertTrue("Misfire" in names)
        assertTrue("Fuel System" in names)
        assertTrue("Catalyst" in names)
        assertTrue("EGR System" in names)
        assertFalse("Heated Catalyst" in names)
        assertTrue(status.monitors.any { it.name == "Heated Catalyst" && it.supported && it.complete })
    }

    @Test
    fun `an unsupported monitor is never reported as incomplete`() {
        // Byte C = 0x00: no non-continuous monitor is supported at all.
        val status = MonitorStatus.parse("410100 07 00 FF\r>")!!
        assertTrue(status.incomplete.none { it.name == "Catalyst" })
        assertEquals(3, status.supportedCount)
    }

    @Test
    fun `bit three of byte B selects the diesel monitor names`() {
        val diesel = MonitorStatus.parse("410100 0F 41 00\r>")!!
        assertTrue(diesel.compressionIgnition)
        assertTrue(diesel.monitors.any { it.name == "NMHC Catalyst" })
        assertTrue(diesel.monitors.none { it.name == "Heated Catalyst" })

        val petrol = MonitorStatus.parse("410100 07 41 00\r>")!!
        assertFalse(petrol.compressionIgnition)
        assertTrue(petrol.monitors.any { it.name == "Catalyst" })
    }

    @Test
    fun `the lamp and the count still parse alongside the monitors`() {
        val status = MonitorStatus.parse("4101 83 07 E1 00\r>")!!
        assertTrue(status.milOn)
        assertEquals(3, status.dtcCount)
    }

    @Test
    fun `a short frame yields nothing rather than a partial status`() {
        assertNull(MonitorStatus.parse("4101 83\r>"))
    }
}

class DtcCatalogTest {

    @Test
    fun `known generic codes get their real definition`() {
        assertEquals("Random or multiple cylinder misfire detected", DtcCatalog.describe("P0300"))
        assertEquals("System too lean (bank 1)", DtcCatalog.describe("P0171"))
    }

    @Test
    fun `manufacturer codes are not guessed at`() {
        val text = DtcCatalog.describe("P1234")
        assertTrue(text.contains("Manufacturer-specific"))
        assertFalse(DtcCatalog.isGeneric("P1234"))
    }

    @Test
    fun `an unlisted generic code is described by its range`() {
        val text = DtcCatalog.describe("P0999")
        assertTrue(DtcCatalog.isGeneric("P0999"))
        assertTrue(text.contains("Generic powertrain"))
    }

    @Test
    fun `the system letter drives the wording`() {
        assertTrue(DtcCatalog.describe("C1234").contains("chassis"))
        assertTrue(DtcCatalog.describe("B1234").contains("body"))
        assertTrue(DtcCatalog.describe("U1234").contains("network"))
    }
}

/**
 * Pending and permanent codes come back framed exactly like stored ones, only
 * under a different mode echo. Getting the echo wrong is how they went missing.
 */
class HiddenCodeTest {

    @Test
    fun `pending codes are read from the mode 07 echo`() {
        val codes = DtcDecoder.decode("47 01 01 71 00 00\r>", DiagnosticCode.Kind.PENDING)
        assertEquals(listOf("P0171"), codes.map { it.code })
        assertEquals(DiagnosticCode.Kind.PENDING, codes.single().kind)
    }

    @Test
    fun `permanent codes are read from the mode 0A echo`() {
        val codes = DtcDecoder.decode("4A 01 04 20 00 00\r>", DiagnosticCode.Kind.PERMANENT)
        assertEquals(listOf("P0420"), codes.map { it.code })
        assertEquals(DiagnosticCode.Kind.PERMANENT, codes.single().kind)
    }

    @Test
    fun `a stored-code reply is not mistaken for a pending one`() {
        // Mode 03 framing offered to the mode 07 decoder must yield nothing
        // rather than reinterpreting the bytes under the wrong header.
        val codes = DtcDecoder.decode("43 01 01 71 00 00\r>", DiagnosticCode.Kind.PENDING)
        assertTrue(codes.isEmpty())
    }

    @Test
    fun `each mode carries its own kind through to the report`() {
        for (kind in DiagnosticCode.Kind.entries) {
            val echo = "%02X".format(kind.mode + 0x40)
            val codes = DtcDecoder.decode("$echo 01 03 00 00 00\r>", kind)
            assertEquals(listOf("P0300"), codes.map { it.code })
            assertEquals(kind, codes.single().kind)
        }
    }
}
