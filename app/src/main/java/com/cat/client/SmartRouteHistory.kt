package com.cat.client

internal data class SmartRouteHistoryStats(
    val measurement: SmartRouteMeasurement,
    val jitterMs: Long?,
    val failureStreak: Int,
)

/** Pure bounded-history aggregation; Android persistence lives in SmartRouteScoutStore. */
internal object SmartRouteHistory {
    const val MAX_SAMPLES_PER_ROUTE = 20

    fun aggregate(samples: List<SmartRouteMeasurement>): SmartRouteHistoryStats? {
        if (samples.isEmpty()) return null
        val ordered = samples.sortedBy { it.measuredAtMs }.takeLast(MAX_SAMPLES_PER_ROUTE)
        val latest = ordered.last()
        val successful = ordered.filter { it.verdict == SmartRouteVerdict.WORKS }
        val rtts = successful.mapNotNull { it.medianRttMs }.sorted()
        val throughputs = successful.mapNotNull { it.throughputKbps }.sorted()
        val failureStreak = ordered.asReversed().takeWhile { it.verdict != SmartRouteVerdict.WORKS }.size
        return SmartRouteHistoryStats(
            measurement = latest.copy(
                medianRttMs = percentile(rtts, 0.50),
                p95RttMs = percentile(rtts, 0.95),
                throughputKbps = percentile(throughputs, 0.50),
                successRate = successful.size.toDouble() / ordered.size,
                sampleCount = ordered.size,
            ),
            jitterMs = if (rtts.size < 2) null else rtts.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average().toLong(),
            failureStreak = failureStreak,
        )
    }

    private fun percentile(values: List<Long>, fraction: Double): Long? {
        if (values.isEmpty()) return null
        val index = kotlin.math.ceil(fraction * values.size).toInt().coerceIn(1, values.size) - 1
        return values[index]
    }
}
