package com.physdaq.mobile.library

/** Helpers that shape data for the Library's charts. */
object ChartData {

    /**
     * Shrinks [values] to at most [maxPoints] by averaging buckets, so a 30-minute
     * heart-rate trace fits on a phone screen. NaN (no reading) is ignored inside a
     * bucket; a bucket with no readings stays NaN so the chart shows a gap.
     */
    fun downsample(values: FloatArray, maxPoints: Int): FloatArray {
        require(maxPoints >= 1)
        if (values.size <= maxPoints) return values.copyOf()
        val out = FloatArray(maxPoints)
        for (b in 0 until maxPoints) {
            val from = (b.toLong() * values.size / maxPoints).toInt()
            val to = ((b + 1).toLong() * values.size / maxPoints).toInt().coerceAtLeast(from + 1)
            var sum = 0.0
            var n = 0
            for (i in from until to) {
                val v = values[i]
                if (!v.isNaN()) { sum += v; n++ }
            }
            out[b] = if (n == 0) Float.NaN else (sum / n).toFloat()
        }
        return out
    }

    /** "mm:ss", or "h:mm:ss" from one hour up. */
    fun formatDuration(seconds: Double): String {
        val s = seconds.toLong().coerceAtLeast(0)
        return if (s >= 3600) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        } else {
            String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
        }
    }
}
