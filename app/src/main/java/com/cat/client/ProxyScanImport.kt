package com.cat.client

internal object ProxyScanImport {
    /** Converts a protocol-handshake scanner hit to a typed relay URI without
     * changing its host, port, or relay type. */
    fun uri(hit: ProxyScannerPage.Hit, checkedAtMs: Long): String? {
        val endpoint = VerifiedEndpointAdapters.fromProxyScan(hit, checkedAtMs)
        val protocol = when (hit.proto) {
            ProxyScannerPage.Proto.SOCKS4 -> RelayProtocol.SOCKS4
            ProxyScannerPage.Proto.SOCKS5 -> RelayProtocol.SOCKS5
            ProxyScannerPage.Proto.HTTP -> RelayProtocol.HTTP
        }
        if (endpoint.verdict != EndpointVerdict.VERIFIED && endpoint.verdict != EndpointVerdict.PARTIAL) return null
        return VerifiedRelay(protocol, endpoint).encode()
    }
}
