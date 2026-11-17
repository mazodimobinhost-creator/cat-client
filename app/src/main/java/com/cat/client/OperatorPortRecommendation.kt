package com.cat.client

internal data class VerifiedPortRecommendation(
    val port: Int,
    val successfulEndpoints: Int,
    val medianLatencyMs: Long?,
    val role: EndpointRole = EndpointRole.CLEAN_IP,
)

/** Recommendations are derived exclusively from fresh runtime evidence measured
 * on the current network and for the requested endpoint role. There is
 * deliberately no carrier→port lookup table, and ProxyIP/SOCKS ports are never
 * mixed into Clean-IP ingress port suggestions. */
internal object OperatorPortRecommendation {
    fun fromEvidence(
        entries: List<CachedEndpointEvidence>,
        currentNetworkId: String,
        nowMs: Long,
        maxAgeMs: Long,
        role: EndpointRole = EndpointRole.CLEAN_IP,
    ): List<VerifiedPortRecommendation> = entries.asSequence()
        .filter { it.key.networkId == currentNetworkId }
        .filter { it.key.role == role }
        .filter { it.checkedAtMs in (nowMs - maxAgeMs)..nowMs }
        .filter { it.evidence.runtimeCompleted == true }
        .groupBy { it.key.port }
        .map { (port, rows) ->
            val latencies = rows.mapNotNull { it.evidence.latencyMs }.sorted()
            VerifiedPortRecommendation(
                port = port,
                successfulEndpoints = rows.map { it.key.host }.distinct().size,
                medianLatencyMs = latencies.takeIf { it.isNotEmpty() }?.get(latencies.size / 2),
                role = role,
            )
        }
        .sortedWith(
            compareByDescending<VerifiedPortRecommendation> { it.successfulEndpoints }
                .thenBy { it.medianLatencyMs ?: Long.MAX_VALUE }
                .thenBy { it.port },
        )
        .toList()
}
