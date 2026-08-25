package com.mohid.obd2dash.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmProtocolTest {

    @Test
    fun `strips prompt whitespace and SEARCHING notice`() {
        val raw = "SEARCHING...\r41 0C 1A F8\r\r>"
        assertEquals("410C1AF8", ElmProtocol.sanitize(raw))
    }

    @Test
    fun `strips multi-frame line counters`() {
        val raw = "0: 41 00 BE 3E B8 11\r1: 13 00 00 00 00\r>"
        assertEquals("4100BE3EB8111300000000", ElmProtocol.sanitize(raw))
    }

    @Test
    fun `extracts data bytes after the response header`() {
        val data = ElmProtocol.extractData("410C1AF8\r>", "410C", 2)
        assertEquals(listOf(0x1A, 0xF8), data?.toList())
    }

    @Test
    fun `trims extra bytes beyond what the PID declares`() {
        // Some adapters pad the frame out to eight bytes.
        val data = ElmProtocol.extractData("410D3C00000000", "410D", 1)
        assertEquals(listOf(0x3C), data?.toList())
    }

    @Test
    fun `returns null when the header is absent`() {
        assertNull(ElmProtocol.extractData("410D3C", "410C", 2))
    }

    @Test
    fun `recognises adapter errors instead of parsing them as data`() {
        assertEquals("NODATA", ElmProtocol.errorToken(ElmProtocol.sanitize("NO DATA\r>")))
        assertEquals("UNABLETOCONNECT", ElmProtocol.errorToken(ElmProtocol.sanitize("UNABLE TO CONNECT\r>")))
        assertEquals("?", ElmProtocol.errorToken(ElmProtocol.sanitize("?\r>")))
        assertNull(ElmProtocol.errorToken("410C1AF8"))
    }

    @Test
    fun `flags only the errors that require re-initialising the link`() {
        assertTrue(ElmProtocol.isFatal("UNABLETOCONNECT"))
        assertTrue(ElmProtocol.isFatal("BUSINIT"))
        // A single NO DATA is normal for an unsupported PID.
        assertTrue(!ElmProtocol.isFatal("NODATA"))
    }

    @Test
    fun `drops a truncated trailing nibble rather than failing the frame`() {
        assertEquals(listOf(0x41, 0x0C, 0x1A), ElmProtocol.hexToBytes("410C1AF")?.toList())
    }
}

class PidDecodingTest {

    @Test
    fun `rpm is the 16-bit value divided by four`() {
        assertEquals(1726f, PidRegistry.RPM.decode(intArrayOf(0x1A, 0xF8))!!, 0.01f)
    }

    @Test
    fun `coolant temperature carries a forty degree offset`() {
        assertEquals(-40f, PidRegistry.COOLANT_TEMP.decode(intArrayOf(0))!!, 0.01f)
        assertEquals(90f, PidRegistry.COOLANT_TEMP.decode(intArrayOf(130))!!, 0.01f)
    }

    @Test
    fun `speed is a plain byte in kilometres per hour`() {
        assertEquals(60f, PidRegistry.SPEED.decode(intArrayOf(60))!!, 0.01f)
    }

    @Test
    fun `fuel trim is centred on 128`() {
        assertEquals(0f, PidRegistry.byKey("stft1")!!.decode(intArrayOf(128))!!, 0.01f)
        assertEquals(-100f, PidRegistry.byKey("stft1")!!.decode(intArrayOf(0))!!, 0.01f)
    }

    @Test
    fun `control module voltage is millivolts`() {
        // 0x37AA = 14250 mV
        assertEquals(14.25f, PidRegistry.CONTROL_VOLTAGE.decode(intArrayOf(0x37, 0xAA))!!, 0.001f)
        assertEquals(12.0f, PidRegistry.CONTROL_VOLTAGE.decode(intArrayOf(0x2E, 0xE0))!!, 0.001f)
    }

    @Test
    fun `throttle position scales the byte to a percentage`() {
        assertEquals(100f, PidRegistry.THROTTLE.decode(intArrayOf(255))!!, 0.01f)
        assertEquals(0f, PidRegistry.THROTTLE.decode(intArrayOf(0))!!, 0.01f)
    }

    @Test
    fun `decoders reject a short frame instead of reading garbage`() {
        assertNull(PidRegistry.RPM.decode(intArrayOf(0x1A)))
        assertNull(PidRegistry.MAF.decode(intArrayOf()))
    }

    @Test
    fun `every registered pid has a unique key and command`() {
        val keys = PidRegistry.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val pids = PidRegistry.all.map { it.pid }
        assertEquals(pids.size, pids.toSet().size)
        assertEquals("010C", PidRegistry.RPM.command)
        assertEquals("410C", PidRegistry.RPM.responseHeader)
    }
}

class SupportedPidsTest {

    @Test
    fun `most significant bit maps to the first pid in the block`() {
        // 0x80000000 -> only PID 0x01 supported.
        val supported = SupportedPids.decode(0x00, intArrayOf(0x80, 0x00, 0x00, 0x00))
        assertEquals(setOf(0x01), supported)
    }

    @Test
    fun `least significant bit maps to the last pid in the block`() {
        val supported = SupportedPids.decode(0x00, intArrayOf(0x00, 0x00, 0x00, 0x01))
        assertEquals(setOf(0x20), supported)
    }

    @Test
    fun `decodes a realistic bitmask`() {
        // BE 3E B8 11 is a commonly seen 0100 reply.
        val supported = SupportedPids.decode(0x00, intArrayOf(0xBE, 0x3E, 0xB8, 0x11))
        assertTrue(0x01 in supported)
        assertTrue(0x0C in supported)
        assertTrue(0x0D in supported)
        assertTrue(0x05 in supported)
        assertTrue(0x02 !in supported)
    }

    @Test
    fun `the block-continuation bit is what drives the next enquiry`() {
        val withMore = SupportedPids.decode(0x00, intArrayOf(0x00, 0x00, 0x00, 0x01))
        assertTrue(SupportedPids.continuesPastBlock(0x00, withMore))
        val withoutMore = SupportedPids.decode(0x00, intArrayOf(0x80, 0x00, 0x00, 0x00))
        assertTrue(!SupportedPids.continuesPastBlock(0x00, withoutMore))
    }

    @Test
    fun `a short frame yields nothing rather than a partial guess`() {
        assertEquals(emptySet<Int>(), SupportedPids.decode(0x00, intArrayOf(0xBE, 0x3E)))
    }
}

class DtcDecoderTest {

    @Test
    fun `formats the system letter and digits`() {
        assertEquals("P0133", DtcDecoder.format(0x01, 0x33))
        assertEquals("C0300", DtcDecoder.format(0x43, 0x00))
        assertEquals("U0100", DtcDecoder.format(0xC1, 0x00))
        assertEquals("P1234", DtcDecoder.format(0x12, 0x34))
    }

    @Test
    fun `skips the count byte that CAN ECUs prepend`() {
        // 43 02 <code> <code>. An odd byte count after the echo means a count byte.
        val codes = DtcDecoder.decode("4302013303 20\r>", DiagnosticCode.Kind.STORED)
        assertEquals(listOf("P0133", "P0320"), codes.map { it.code })
    }

    @Test
    fun `reads codes with no count byte and stops at the padding`() {
        val codes = DtcDecoder.decode("4301330000\r>", DiagnosticCode.Kind.STORED)
        assertEquals(listOf("P0133"), codes.map { it.code })
    }

    @Test
    fun `an empty reply means no codes`() {
        assertEquals(emptyList<DiagnosticCode>(), DtcDecoder.decode("4300\r>", DiagnosticCode.Kind.STORED))
        assertEquals(emptyList<DiagnosticCode>(), DtcDecoder.decode("NO DATA\r>", DiagnosticCode.Kind.STORED))
    }

    @Test
    fun `monitor status reports the lamp and the stored count`() {
        val status = MonitorStatus.parse("4101 83 07 E1 00\r>")
        assertEquals(true, status?.milOn)
        assertEquals(3, status?.dtcCount)

        val clean = MonitorStatus.parse("410100 07 E1 00\r>")
        assertEquals(false, clean?.milOn)
        assertEquals(0, clean?.dtcCount)
    }
}
