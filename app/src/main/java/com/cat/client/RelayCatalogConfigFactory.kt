package com.cat.client

/** Backend for the catalogue row's «Worker & config» action. It never invents a
 * port: ingress and optional ProxyIP must both already be runtime-verified. */
internal object RelayCatalogConfigFactory {
    fun build(
        row: RelayCatalogEntry,
        protocol: TunnelProtocol,
        credential: String,
        workerDomain: String,
        path: String,
        proxyIpRow: RelayCatalogEntry? = null,
        fingerprint: String? = null,
    ): VerifiedConfigUri? {
        val ingress = row.endpoint
        if (ingress.role != EndpointRole.CLEAN_IP || ingress.verdict != EndpointVerdict.VERIFIED) return null
        val proxyIp = proxyIpRow?.endpoint
        if (proxyIp != null && (proxyIp.role != EndpointRole.PROXY_IP || proxyIp.verdict != EndpointVerdict.VERIFIED)) return null
        return runCatching {
            VerifiedConfigUri(protocol, credential, ingress, workerDomain, path,
                tls = ingress.port !in EndpointPortDiscovery.cleanPlainPorts,
                fingerprint = fingerprint, proxyIp = proxyIp)
        }.getOrNull()
    }
}
