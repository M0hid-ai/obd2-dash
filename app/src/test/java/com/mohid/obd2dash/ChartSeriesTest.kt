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
    fun `a boost peak survives the vacuum around it`() {
        // The case this rule exists for, with the figures off a real trip: a
        // 660cc turbo sits at roughly -57 kPa of vacuum cruising and touches
        // +18 kPa on the one pull. The vacuum is further from zero the whole
        // time, so a rule that keeps the single most extreme sample per bucket
        // drops every boost reading in the drive.
        val points = ramp(5_000) { if (it == 2_500) 18f else -57f }
        val out = ChartSeries.downsample(points)
        assertEquals(18f, out.maxOf { it.value }, 0.001f)
        assertEquals(-57f, out.minOf { it.value }, 0.001f)
    }

    @Test
    fun `a spike on a metric that never goes negative still survives`() {
        // What the old rule got right, kept: a coolant excursion or a rev spike
        // is the highest sample in its bucket, not the lowest.
        val points = ramp(5_000) { if (it == 1_234) 6_800f else 900f }
        val out = ChartSeries.downsample(points)
        assertEquals(6_800f, out.maxOf { it.value }, 0.001f)
    }

    @Test
    fun `both extremes of every bucket come back`() {
        // Nothing about this is boost-specific: whatever the metric, the chart
        // should span what the drive actually did.
        val points = ramp(5_000) { kotlin.math.sin(it / 13f) * 40f - 20f }
        val out = ChartSeries.downsample(points)
        assertEquals(points.maxOf { it.value }, out.maxOf { it.value }, 0.001f)
        assertEquals(points.minOf { it.value }, out.minOf { it.value }, 0.001f)
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
