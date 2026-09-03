package com.mohid.obd2dash

import com.mohid.obd2dash.data.ChartSeries
import com.mohid.obd2dash.data.SeriesPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSeriesTest {

    /** A drive's worth of samples, one every 300ms. */
    private fun ramp(count: Int, value: (Int) -> Float): List<SeriesPoint> =
        List(count) { SeriesPoint(elapsedMs = it * 300L, value = value(it)) }

    @Test
    fun `a short series is handed back untouched`() {
        val points = ramp(ChartSeries.MAX_POINTS) { it.toFloat() }
        assertSame(points, ChartSeries.downsample(points))
    }

    @Test
    fun `a long series is cut to the cap`() {
        val points = ramp(12_000) { it.toFloat() }
        assertTrue(ChartSeries.downsample(points).size <= ChartSeries.MAX_POINTS)
    }

    @Test
    fun `time only ever runs forwards`() {
        // Points are drawn in list order, so one out of sequence draws the line
        // back on itself.
        val points = ramp(5_000) { kotlin.math.sin(it / 40f) * 60f }
        val out = ChartSeries.downsample(points)
        for (i in 1 until out.size) {
            assertTrue(
                "point $i went backwards in time",
                out[i].elapsedMs >= out[i - 1].elapsedMs,
            )
        }
    }

    @Test
    fun `every point that comes back is one that went in`() {
        // Averaging buckets would invent readings the car never reported, which
        // is worse than dropping them: the scrub readout claims to be a sample.
        val points = ramp(5_000) { (it % 97).toFloat() }
        val known = points.toHashSet()
        val out = ChartSeries.downsample(points)
        assertTrue(out.isNotEmpty())
        assertEquals(emptyList<SeriesPoint>(), out.filterNot { it in known })
    }
}
