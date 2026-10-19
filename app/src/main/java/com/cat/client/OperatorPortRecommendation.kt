package com.cat.client

internal data class VerifiedPortRecommendation(
    val port: Int,
    val successfulEndpoints: Int,
    val medianLatencyMs: Long?,
)

/** Recommendations are derived exclusively from fresh runtime evidence measured
 * on the current network. There is deliberately no carrier→port lookup table. */
internal object OperatorPortRecommendation {
    fun fromEvidence(
        entries: List<CachedEndpointEvidence>,
        currentNetworkId: String,
        nowMs: Long,
        maxAgeMs: Long,
    ): List<VerifiedPortRecommendation> = entries.asSequence()
        .filter { it.key.networkId == currentNetworkId }
        .filter { it.checkedAtMs in (nowMs - maxAgeMs)..nowMs }
        .filter { it.evidence.runtimeCompleted == true }
        .groupBy { it.key.port }
        .map { (port, rows) ->
            val latencies = rows.mapNotNull { it.evidence.latencyMs }.sorted()
            VerifiedPortRecommendation(port, rows.map { it.key.host }.distinct().size,
                latencies.takeIf { it.isNotEmpty() }?.get(latencies.size / 2))
        }
        .sortedWith(compareByDescending<VerifiedPortRecommendation> { it.successfulEndpoints }
            .thenBy { it.medianLatencyMs ?: Long.MAX_VALUE }.thenBy { it.port })
        .toList()
}
