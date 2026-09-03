package com.mohid.obd2dash.data

import kotlin.math.abs

/**
 * Thinning a trip's samples down to what a chart can actually draw.
 *
 * Kept out of [TripRepository] because which samples survive is the whole
 * reason a post-trip chart is worth looking at, and a rule that only runs
 * behind a database is a rule nothing can test.
 */
object ChartSeries {

    /**
     * A half-hour trip at 3 Hz is ~5,000 samples. Charts are a few hundred
     * pixels wide, so anything past this is invisible detail that only costs
     * allocation and draw time.
     */
    const val MAX_POINTS = 480

    /**
     * Each bucket keeps its most extreme value rather than its mean, so a brief
     * coolant spike or an overboost survives the downsample. Losing those is
     * exactly what makes a post-trip chart useless.
     */
    fun downsample(points: List<SeriesPoint>): List<SeriesPoint> {
        if (points.size <= MAX_POINTS) return points
        val bucketSize = points.size.toDouble() / MAX_POINTS
        val out = ArrayList<SeriesPoint>(MAX_POINTS)
        var index = 0
        while (index < points.size) {
            val end = minOf(((out.size + 1) * bucketSize).toInt(), points.size)
            if (end <= index) break
            var pick = points[index]
            for (i in index until end) {
                // Keep whichever sample is furthest from zero so spikes survive.
                if (abs(points[i].value) > abs(pick.value)) pick = points[i]
            }
            out += pick
            index = end
        }
        return out
    }
}
