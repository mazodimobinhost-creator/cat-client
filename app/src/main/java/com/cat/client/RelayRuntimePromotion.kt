package com.cat.client

/** The only path from scanner PARTIAL to catalogue VERIFIED. The exact role,
 * host and port survive promotion; only evidence fields are refreshed. */
internal object RelayRuntimePromotion {
    fun promote(
        row: RelayCatalogEntry,
        kind: PortProbeKind,
        evidence: EndpointProbeEvidence,
        checkedAtMs: Long,
    ): RelayCatalogEntry? {
        if (!kindMatchesRole(kind, row.endpoint.role)) return null
        if (EndpointProbeEvaluator.verdict(kind, evidence) != EndpointVerdict.VERIFIED) return null
        return row.copy(endpoint = row.endpoint.copy(
            verdict = EndpointVerdict.VERIFIED,
            checkedAtMs = checkedAtMs,
            latencyMs = evidence.latencyMs ?: row.endpoint.latencyMs,
        ))
    }

    private fun kindMatchesRole(kind: PortProbeKind, role: EndpointRole): Boolean = when (kind) {
        PortProbeKind.CLEAN_TLS, PortProbeKind.CLEAN_PLAIN -> role == EndpointRole.CLEAN_IP
        PortProbeKind.PROXY_IP -> role == EndpointRole.PROXY_IP
        PortProbeKind.SOCKS4 -> role == EndpointRole.SOCKS4
        PortProbeKind.SOCKS5 -> role == EndpointRole.SOCKS5
        PortProbeKind.HTTP_CONNECT, PortProbeKind.HTTPS_CONNECT -> role == EndpointRole.HTTP_RELAY
    }
}
