package com.cat.client

internal enum class RelayCatalogActionType { USE_AS_ENTRY, USE_AS_PROXY_IP, COPY_RELAY }
internal data class RelayCatalogActionResult(
    val type: RelayCatalogActionType,
    val value: String,
)

/** Backend for each catalogue row's action button. Role conversion is explicit:
 * a SOCKS/HTTP relay can never be silently stored as ProxyIP or clean ingress. */
internal object RelayCatalogAction {
    fun useAsEntry(row: RelayCatalogEntry): RelayCatalogActionResult? {
        val endpoint = row.endpoint
        if (endpoint.role != EndpointRole.CLEAN_IP || endpoint.verdict != EndpointVerdict.VERIFIED) return null
        return RelayCatalogActionResult(RelayCatalogActionType.USE_AS_ENTRY, endpoint.authority)
    }

    fun useAsProxyIp(row: RelayCatalogEntry): RelayCatalogActionResult? {
        val endpoint = row.endpoint
        if (endpoint.role != EndpointRole.PROXY_IP || endpoint.verdict != EndpointVerdict.VERIFIED) return null
        return RelayCatalogActionResult(RelayCatalogActionType.USE_AS_PROXY_IP, endpoint.authority)
    }

    fun copyRelay(row: RelayCatalogEntry): RelayCatalogActionResult? {
        val uri = row.endpoint.relayUri() ?: return null
        if (row.endpoint.verdict != EndpointVerdict.VERIFIED) return null
        return RelayCatalogActionResult(RelayCatalogActionType.COPY_RELAY, uri)
    }
}
