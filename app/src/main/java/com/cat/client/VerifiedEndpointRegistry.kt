package com.cat.client

internal enum class EndpointSaveStatus { SAVED, UPDATED, DUPLICATE, INVALID, NOT_VERIFIED }
internal data class EndpointSaveResult(val status: EndpointSaveStatus, val endpoint: VerifiedEndpoint? = null)

/** Pure registry policy shared by app/panel/bot adapters. Identity includes role and port;
 * therefore one host on two working ports is never collapsed into one record. */
internal object VerifiedEndpointRegistry {
    private fun key(endpoint: VerifiedEndpoint) = Triple(endpoint.role, endpoint.host.lowercase(), endpoint.port)

    fun save(existing: List<VerifiedEndpoint>, candidate: VerifiedEndpoint?): Pair<List<VerifiedEndpoint>, EndpointSaveResult> {
        if (candidate == null || candidate.host.isBlank() || candidate.port !in 1..65535) {
            return existing to EndpointSaveResult(EndpointSaveStatus.INVALID)
        }
        if (candidate.verdict != EndpointVerdict.VERIFIED) {
            return existing to EndpointSaveResult(EndpointSaveStatus.NOT_VERIFIED, candidate)
        }
        val index = existing.indexOfFirst { key(it) == key(candidate) }
        if (index < 0) return (existing + candidate) to EndpointSaveResult(EndpointSaveStatus.SAVED, candidate)
        val previous = existing[index]
        if (previous == candidate) return existing to EndpointSaveResult(EndpointSaveStatus.DUPLICATE, candidate)
        val updated = existing.toMutableList().also { it[index] = candidate }
        return updated to EndpointSaveResult(EndpointSaveStatus.UPDATED, candidate)
    }
}
