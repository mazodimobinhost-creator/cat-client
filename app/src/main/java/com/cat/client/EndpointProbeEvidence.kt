package com.cat.client

internal data class EndpointProbeEvidence(
    val tcpConnected: Boolean = false,
    val tlsCompleted: Boolean? = null,
    val protocolHandshakeCompleted: Boolean? = null,
    val websocketCompleted: Boolean? = null,
    val runtimeCompleted: Boolean? = null,
    val latencyMs: Long? = null,
    val error: String? = null,
)

internal object EndpointProbeEvaluator {
    /** UNKNOWN/PARTIAL must never be displayed or persisted as healthy. */
    fun verdict(kind: PortProbeKind, evidence: EndpointProbeEvidence): EndpointVerdict {
        if (!evidence.tcpConnected) return EndpointVerdict.FAILED
        val required = when (kind) {
            PortProbeKind.CLEAN_TLS -> listOf(evidence.tlsCompleted, evidence.websocketCompleted, evidence.runtimeCompleted)
            PortProbeKind.CLEAN_PLAIN -> listOf(evidence.websocketCompleted, evidence.runtimeCompleted)
            PortProbeKind.PROXY_IP -> listOf(evidence.protocolHandshakeCompleted, evidence.runtimeCompleted)
            PortProbeKind.SOCKS4, PortProbeKind.SOCKS5,
            PortProbeKind.HTTP_CONNECT, PortProbeKind.HTTPS_CONNECT ->
                listOf(evidence.protocolHandshakeCompleted, evidence.runtimeCompleted)
        }
        if (required.any { it == false }) return EndpointVerdict.FAILED
        if (required.all { it == true }) return EndpointVerdict.VERIFIED
        return EndpointVerdict.PARTIAL
    }

    fun verifiedEndpointOrNull(
        candidate: PortProbeCandidate,
        evidence: EndpointProbeEvidence,
        checkedAtMs: Long,
        source: String,
    ): VerifiedEndpoint? {
        if (verdict(candidate.kind, evidence) != EndpointVerdict.VERIFIED) return null
        return EndpointPortDiscovery.verifiedEndpoint(candidate, checkedAtMs, evidence.latencyMs ?: 0L, source)
    }
}
