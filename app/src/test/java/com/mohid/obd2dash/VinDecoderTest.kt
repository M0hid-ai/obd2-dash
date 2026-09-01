package com.mohid.obd2dash

import com.mohid.obd2dash.data.AppSettings
import com.mohid.obd2dash.data.GaugeSkin
import com.mohid.obd2dash.obd.VinDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinDecoderTest {

    @Test
    fun `rejects anything that is not a well formed vin`() {
        assertFalse(VinDecoder.isWellFormed("TOOSHORT"))
        assertFalse(VinDecoder.isWellFormed("JTDBR32E7200000012"))
        // I, O and Q are never used, because they read as 1 and 0.
        assertFalse(VinDecoder.isWellFormed("JTDBR32E72000000I"))
        assertTrue(VinDecoder.isWellFormed("JTDBR32E720000001"))
    }

    @Test
    fun `reads maker and year off a first cycle vin`() {
        // Position 7 is numeric, so the year code belongs to the 1980 cycle.
        val facts = VinDecoder.decode("JTDBR32E720000001")!!
        assertEquals("Toyota", facts.make)
        assertEquals(2002, facts.modelYear)
        assertEquals("Asia", facts.region)
        assertEquals("Toyota 2002", facts.label)
    }

    @Test
    fun `position seven pushes the year into the second cycle`() {
        // Same year code, but an alphabetic position 7 means 2010 onwards.
        val first = VinDecoder.decode("JDABR3GE1P000001")
        assertNull("a 16 character vin is not decodable", first)

        // Position 7 is G, so year code P is 1993 + 30 rather than 1993.
        val secondCycle = VinDecoder.decode("JDABR3GE1P0000001")!!
        assertEquals("Daihatsu", secondCycle.make)
        assertEquals(2023, secondCycle.modelYear)
    }

    @Test
    fun `falls back to the two character group when the wmi is unlisted`() {
        // JTZ is not in the table; JT still names the manufacturer.
        val facts = VinDecoder.decode("JTZBR32E720000001")!!
        assertEquals("Toyota", facts.make)
    }

    @Test
    fun `an unknown maker still yields region and year`() {
        val facts = VinDecoder.decode("XYZBR32E720000001")!!
        assertNull(facts.make)
        assertEquals("Europe", facts.region)
        assertEquals(2002, facts.modelYear)
        assertEquals("Europe 2002", facts.label)
    }

    @Test
    fun `implausible years are dropped rather than guessed`() {
        // Year code Y on the second cycle is 2030, past what any car can claim.
        val facts = VinDecoder.decode("JTDBR3GE1Y0000001")!!
        assertNull(facts.modelYear)
    }
}

class GaugeSkinTest {

    @Test
    fun `the two meta choices are not offered as faces for a single dial`() {
        assertFalse(GaugeSkin.SHOWCASE in GaugeSkin.selectable)
        assertFalse(GaugeSkin.CUSTOM in GaugeSkin.selectable)
        assertTrue(GaugeSkin.HEXA in GaugeSkin.selectable)
        assertEquals(GaugeSkin.entries.size - 2, GaugeSkin.selectable.size)
    }

    @Test
    fun `per dial choices only apply when the mode asks for them`() {
        val perDial = listOf(
            GaugeSkin.CIRCUIT,
            GaugeSkin.COCKPIT,
            GaugeSkin.HEXA,
            GaugeSkin.HERITAGE_CARBON,
        )
        val custom = AppSettings(gaugeSkin = GaugeSkin.CUSTOM, perDialSkins = perDial)
        assertEquals(GaugeSkin.CIRCUIT, custom.skinFor(0))
        assertEquals(GaugeSkin.HERITAGE_CARBON, custom.skinFor(3))

        // A global face wins over whatever the per-dial list happens to hold.
        val global = AppSettings(gaugeSkin = GaugeSkin.HEXA, perDialSkins = perDial)
        assertEquals(GaugeSkin.HEXA, global.skinFor(0))
        assertEquals(GaugeSkin.HEXA, global.skinFor(3))

        // Compare all still hands out its own pairing.
        val showcase = AppSettings(gaugeSkin = GaugeSkin.SHOWCASE, perDialSkins = perDial)
        assertEquals(GaugeSkin.HEXA, showcase.skinFor(0))
    }

    @Test
    fun `a short or damaged per dial list falls back per position`() {
        val custom = AppSettings(gaugeSkin = GaugeSkin.CUSTOM, perDialSkins = listOf(GaugeSkin.CIRCUIT))
        assertEquals(GaugeSkin.CIRCUIT, custom.skinFor(0))
        assertEquals(GaugeSkin.CLASSIC, custom.skinFor(2))
    }
}
