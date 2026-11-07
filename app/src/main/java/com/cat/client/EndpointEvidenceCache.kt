package com.cat.client

internal data class EndpointEvidenceKey(
    val networkId: String,
    val role: EndpointRole,
    val host: String,
    val port: Int,
    val protocol: String,
    val serverName: String,
    val tls: Boolean,
) {
    init {
        require(networkId.isNotBlank())
        require(host.isNotBlank())
        require(port in 1..65535)
    }
}

internal data class CachedEndpointEvidence(
    val key: EndpointEvidenceKey,
    val evidence: EndpointProbeEvidence,
    val checkedAtMs: Long,
)

/** Evidence is scoped to the device's current network. A result measured on
 * Wi-Fi or another SIM must never be promoted as verified on this network. */
internal object EndpointEvidenceCache {
    fun fresh(
        entries: List<CachedEndpointEvidence>,
        key: EndpointEvidenceKey,
        nowMs: Long,
        maxAgeMs: Long,
    ): CachedEndpointEvidence? = entries.asSequence()
        .filter { it.key == key }
        .filter { it.checkedAtMs in (nowMs - maxAgeMs)..nowMs }
        .maxByOrNull { it.checkedAtMs }

    fun put(entries: List<CachedEndpointEvidence>, item: CachedEndpointEvidence): List<CachedEndpointEvidence> =
        entries.filterNot { it.key == item.key } + item
}
