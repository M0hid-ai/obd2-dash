package com.mohid.obd2dash

import com.mohid.obd2dash.data.VehicleProfile
import com.mohid.obd2dash.obd.FuelEconomy
import com.mohid.obd2dash.obd.FuelUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelEconomyTest {

    @Test
    fun `MAF conversion produces the expected petrol fuel rate`() {
        // 14.7 g/s / 14.7 AFR = 1 g/s fuel = 3.6 kg/h = 4.884 L/h at 737 g/L.
        assertEquals(4.884f, FuelEconomy.litresPerHourFromMaf(14.7f), 0.001f)
    }

    @Test
    fun `instant economy is suppressed at low speed`() {
        assertNull(FuelEconomy.litresPer100Km(3f, 0f))
        assertNull(FuelEconomy.litresPer100Km(3f, FuelEconomy.MIN_SPEED_KPH - 0.1f))
        assertEquals(30f, FuelEconomy.litresPer100Km(3f, 10f)!!, 0.001f)
    }

    @Test
    fun `commanded lambda moves the mixture off stoichiometric`() {
        // Lambda 0.8 is a deliberately rich commanded mixture, so the
        // effective ratio is 14.7 * 0.8 and the same air carries more fuel.
        assertEquals(11.76f, FuelEconomy.effectiveAfr(lambda = 0.8f), 0.001f)
        val rich = FuelEconomy.litresPerHourFromMaf(14.7f, lambda = 0.8f)
        assertTrue("enrichment must raise the fuel rate", rich > 4.884f)
    }

    @Test
    fun `an implausible lambda is ignored rather than trusted`() {
        assertEquals(FuelEconomy.PETROL_AFR, FuelEconomy.effectiveAfr(lambda = 0.1f), 0.001f)
        assertEquals(FuelEconomy.PETROL_AFR, FuelEconomy.effectiveAfr(lambda = 9f), 0.001f)
    }

    @Test
    fun `fuel trims correct the mixture when lambda is unavailable`() {
        // Ten percent of added fuel means a correspondingly richer mixture.
        assertEquals(14.7f / 1.1f, FuelEconomy.effectiveAfr(shortTrimPct = 4f, longTrimPct = 6f), 0.001f)
        // A trim that large is a fault, not a correction worth applying.
        assertEquals(FuelEconomy.PETROL_AFR, FuelEconomy.effectiveAfr(shortTrimPct = 50f), 0.001f)
        // Lambda wins when both are present.
        assertEquals(11.76f, FuelEconomy.effectiveAfr(lambda = 0.8f, shortTrimPct = 20f), 0.001f)
    }

    @Test
    fun `overrun is only called with every condition met`() {
        // Closed throttle, moving, above idle, no load: injectors are shut.
        assertTrue(FuelEconomy.isFuelCut(2000f, 50f, 13f, 12f, 5f))
        // Idling in neutral is not overrun, however shut the throttle is.
        assertFalse(FuelEconomy.isFuelCut(800f, 0f, 12f, 12f, 5f))
        // Stationary with the engine spinning is not overrun either.
        assertFalse(FuelEconomy.isFuelCut(2000f, 0f, 12f, 12f, 5f))
        // Any real demand for torque rules it out whatever the throttle says.
        assertFalse(FuelEconomy.isFuelCut(2000f, 50f, 13f, 12f, 45f))
        // Pedal genuinely applied.
        assertFalse(FuelEconomy.isFuelCut(2000f, 50f, 30f, 12f, 5f))
        // Nothing to compare the throttle against yet.
        assertFalse(FuelEconomy.isFuelCut(2000f, 50f, 13f, null, 5f))
    }

    @Test
    fun `km per litre is the reciprocal and formats both ways`() {
        assertEquals(12.5f, FuelEconomy.kmPerLitre(8f)!!, 0.001f)
        assertNull(FuelEconomy.kmPerLitre(0f))
        assertEquals("12.5 km/L", FuelEconomy.format(8f, FuelUnit.KM_PER_LITRE))
        assertEquals("8.0 L/100 km", FuelEconomy.format(8f, FuelUnit.L_PER_100KM))
    }

    @Test
    fun `trip economy requires meaningful distance and fuel`() {
        assertNull(FuelEconomy.tripLitresPer100Km(0.01, 1_000.0))
        assertNull(FuelEconomy.tripLitresPer100Km(1.0, 199.0))
        assertEquals(8f, FuelEconomy.tripLitresPer100Km(0.8, 10_000.0)!!, 0.001f)
    }
}

class VehicleProfileTest {

    @Test
    fun `vehicle profile round trips without a VIN`() {
        val original = VehicleProfile("ecu-2ab", null, turbo = false, labeledAt = 123L)
        assertEquals(original, VehicleProfile.deserialize(original.serialize()))
    }

    @Test
    fun `invalid vehicle profile is ignored`() {
        assertNull(VehicleProfile.deserialize("not-a-profile"))
        assertTrue(VehicleProfile.deserialize("id\u001fvin\u001f1\u001fnope") == null)
    }
}
