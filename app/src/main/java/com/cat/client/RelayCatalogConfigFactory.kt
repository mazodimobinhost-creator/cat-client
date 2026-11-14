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
        serverName: String = workerDomain,
    ): VerifiedConfigUri? {
        val ingress = row.endpoint
        if (ingress.role != EndpointRole.CLEAN_IP || ingress.verdict != EndpointVerdict.VERIFIED) return null
        val proxyIp = proxyIpRow?.endpoint
        if (proxyIp != null && (proxyIp.role != EndpointRole.PROXY_IP || proxyIp.verdict != EndpointVerdict.VERIFIED)) return null
        return runCatching {
            VerifiedConfigUri(
                protocol = protocol,
                credential = credential,
                endpoint = ingress,
                workerDomain = workerDomain,
                path = path,
                tls = ingress.port !in EndpointPortDiscovery.cleanPlainPorts,
                fingerprint = fingerprint,
                proxyIp = proxyIp,
                serverName = serverName,
            )
        }.getOrNull()
    }

    /** Builds a config directly from a runtime-verified ProxyIP catalogue row paired
     * with a runtime-verified Clean-IP ingress row, keeping both ports independent. */
    fun buildForProxyIpRow(
        proxyIpRow: RelayCatalogEntry,
        ingressRow: RelayCatalogEntry,
        protocol: TunnelProtocol,
        credential: String,
        workerDomain: String,
        path: String,
        fingerprint: String? = null,
        serverName: String = workerDomain,
    ): VerifiedConfigUri? = build(
        row = ingressRow,
        protocol = protocol,
        credential = credential,
        workerDomain = workerDomain,
        path = path,
        proxyIpRow = proxyIpRow,
        fingerprint = fingerprint,
        serverName = serverName,
    )
}
