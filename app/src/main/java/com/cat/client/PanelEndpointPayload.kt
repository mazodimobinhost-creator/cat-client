package com.cat.client

internal data class PanelEndpointPayload(
    val cleanIps: List<String> = emptyList(),
    val proxyIps: List<String> = emptyList(),
    val relayUris: List<String> = emptyList(),
    val tunnelChains: List<String> = emptyList(),
)

/** Shared app/bot/panel routing policy. It prevents every endpoint from being
 * stuffed into a single VLESS or ProxyIP field. */
internal object PanelEndpointPayloadFactory {
    fun from(endpoint: VerifiedEndpoint, chainUri: String? = null): PanelEndpointPayload? {
        if (endpoint.verdict != EndpointVerdict.VERIFIED) return null
        return when (endpoint.role) {
            EndpointRole.CLEAN_IP -> PanelEndpointPayload(cleanIps = listOf(endpoint.authority))
            EndpointRole.PROXY_IP -> PanelEndpointPayload(proxyIps = listOf(endpoint.authority))
            EndpointRole.SOCKS4, EndpointRole.SOCKS5, EndpointRole.HTTP_RELAY ->
                endpoint.relayUri()?.let { PanelEndpointPayload(relayUris = listOf(it)) }
            EndpointRole.VLESS_CHAIN, EndpointRole.TROJAN_CHAIN ->
                chainUri?.takeIf { it.isNotBlank() }?.let { PanelEndpointPayload(tunnelChains = listOf(it)) }
        }
    }
}
