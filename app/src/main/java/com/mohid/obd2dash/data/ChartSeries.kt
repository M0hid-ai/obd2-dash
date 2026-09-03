package com.mohid.obd2dash.data

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
     * Each bucket keeps both the lowest and the highest sample in it, in the
     * order they were recorded, so the chart traces the envelope of the drive
     * rather than a line through the middle of it. A brief coolant spike or an
     * overboost survives; losing those is exactly what makes a post-trip chart
     * useless.
     *
     * Keeping only the single sample furthest from zero, which is what this
     * used to do, works for every metric that cannot go negative — on RPM or
     * speed, furthest from zero *is* the spike. Boost is the exception, and it
     * is the one metric the app is built around: it swings to roughly -70 kPa
     * of vacuum off throttle and to +18 or so under load on a small turbo, so
     * the vacuum is always further from zero than the boost. Every bucket
     * holding both therefore threw the boost away and drew the vacuum, which
     * also cost the peak marker, since that is picked from whatever survives
     * this. Two samples per bucket needs no per-metric knowledge to get right.
     */
    fun downsample(points: List<SeriesPoint>): List<SeriesPoint> {
        if (points.size <= MAX_POINTS) return points
        // Half the buckets, because each one now yields up to two points, so
        // the cap the chart is drawn against does not move.
        val buckets = MAX_POINTS / 2
        val bucketSize = points.size.toDouble() / buckets
        val out = ArrayList<SeriesPoint>(MAX_POINTS)
        var index = 0
        for (bucket in 1..buckets) {
            // The last bucket takes the remainder, so no sample is stranded by
            // the rounding on the way through.
            val end = if (bucket == buckets) {
                points.size
            } else {
                minOf((bucket * bucketSize).toInt(), points.size)
            }
            if (end <= index) continue
            var lowest = points[index]
            var highest = points[index]
            for (i in index until end) {
                if (points[i].value < lowest.value) lowest = points[i]
                if (points[i].value > highest.value) highest = points[i]
            }
            if (lowest === highest) {
                out += lowest
            } else if (lowest.elapsedMs <= highest.elapsedMs) {
                out += lowest
                out += highest
            } else {
                out += highest
                out += lowest
            }
            index = end
        }
        return out
    }
}
