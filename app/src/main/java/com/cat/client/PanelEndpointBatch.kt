package com.cat.client

internal data class PanelEndpointBatchResult(
    val payload: PanelEndpointPayload,
    val accepted: Int,
    val rejected: Int,
    val duplicates: Int,
)

internal object PanelEndpointBatch {
    fun build(items: List<Pair<VerifiedEndpoint, String?>>): PanelEndpointBatchResult {
        val seen = HashSet<Triple<EndpointRole, String, Int>>()
        val clean = ArrayList<String>()
        val proxy = ArrayList<String>()
        val relays = ArrayList<String>()
        val chains = ArrayList<String>()
        var rejected = 0
        var duplicates = 0
        var accepted = 0
        for ((endpoint, chainUri) in items) {
            val key = Triple(endpoint.role, endpoint.host.lowercase(), endpoint.port)
            if (!seen.add(key)) { duplicates++; continue }
            val payload = PanelEndpointPayloadFactory.from(endpoint, chainUri)
            if (payload == null) { rejected++; continue }
            clean += payload.cleanIps
            proxy += payload.proxyIps
            relays += payload.relayUris
            chains += payload.tunnelChains
            accepted++
        }
        return PanelEndpointBatchResult(PanelEndpointPayload(clean, proxy, relays, chains), accepted, rejected, duplicates)
    }
}
