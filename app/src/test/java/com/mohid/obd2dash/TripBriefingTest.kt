package com.mohid.obd2dash

import com.mohid.obd2dash.ai.TripBriefing
import com.mohid.obd2dash.data.TripExportData
import com.mohid.obd2dash.data.db.TripEntity
import com.mohid.obd2dash.data.db.TripMetricEntity
import com.mohid.obd2dash.obd.FuelUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripBriefingTest {

    private fun data(metrics: List<TripMetricEntity>) = TripExportData(
        trip = TripEntity(
            id = 1,
            startedAt = 1_700_000_000_000,
            endedAt = 1_700_000_600_000,
            durationMs = 600_000,
            distanceMeters = 8_400.0,
            sampleCount = 1_800,
            vehicleName = "Daihatsu Move 2023",
            vehicleTripNumber = 4,
            pidsAdvertised = 26,
            pidsKnown = 24,
            pidsReceived = "rpm;speed",
            pidsMissing = "maf",
        ),
        metrics = metrics,
        dtcs = emptyList(),
        route = emptyList(),
        metricKeys = emptyList(),
        series = emptyMap(),
        readings = emptyList(),
    )

    private fun metric(key: String) = TripMetricEntity(
        tripId = 1,
        metricKey = key,
        minValue = 1.5f,
        maxValue = 90f,
        avgValue = 42.25f,
        sampleCount = 100,
    )

    @Test
    fun `a percent unit does not get read as a format conversion`() {
        // Throttle, engine load and both fuel trims all carry "%" as their
        // unit. Interpolating that into the format string makes the trailing
        // percent a dangling conversion and throws.
        val briefing = TripBriefing.build(
            data(listOf(metric("throttle"), metric("stft1"), metric("engineLoad"))),
            FuelUnit.KM_PER_LITRE,
        )
        assertTrue(briefing.contains("1.5 / 42.3 / 90.0 %"))
    }

    @Test
    fun `a metric with no unit does not leave a trailing space`() {
        // Commanded equivalence ratio is dimensionless.
        val briefing = TripBriefing.build(data(listOf(metric("equivRatio"))), FuelUnit.KM_PER_LITRE)
        assertTrue(briefing.contains("1.5 / 42.3 / 90.0 over 100 samples"))
    }

    @Test
    fun `the vin never reaches the briefing`() {
        val briefing = TripBriefing.build(data(listOf(metric("rpm"))), FuelUnit.KM_PER_LITRE)
        assertTrue(briefing.contains("Daihatsu Move 2023"))
        assertFalse(briefing.contains("JTDBR32E720000001"))
    }

    @Test
    fun `coverage numbers are stated so the model can weigh the gaps`() {
        val briefing = TripBriefing.build(data(listOf(metric("rpm"))), FuelUnit.KM_PER_LITRE)
        assertTrue(briefing.contains("PIDs the ECU advertised: 26"))
        assertTrue(briefing.contains("Of those, decodable by this app: 24"))
        assertTrue(briefing.contains("Advertised but silent for the whole trip: MAF Air Flow Rate"))
    }
}
