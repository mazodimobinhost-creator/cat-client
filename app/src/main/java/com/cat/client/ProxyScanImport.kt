package com.cat.client

internal object ProxyScanImport {
    /** Converts a protocol-verified scanner hit to an import URI without changing
     * its host, port, or relay type. */
    fun uri(hit: ProxyScannerPage.Hit, checkedAtMs: Long): String? {
        val endpoint = VerifiedEndpointAdapters.fromProxyScan(hit, checkedAtMs)
        val protocol = when (hit.proto) {
            ProxyScannerPage.Proto.SOCKS4 -> RelayProtocol.SOCKS4
            ProxyScannerPage.Proto.SOCKS5 -> RelayProtocol.SOCKS5
            ProxyScannerPage.Proto.HTTP -> RelayProtocol.HTTP
        }
        if (endpoint.verdict != EndpointVerdict.VERIFIED) return null
        return VerifiedRelay(protocol, endpoint).encode()
    }
}
