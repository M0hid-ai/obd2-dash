package com.mohid.obd2dash

import com.mohid.obd2dash.data.VehicleProfile
import com.mohid.obd2dash.obd.FuelEconomy
import org.junit.Assert.assertEquals
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
