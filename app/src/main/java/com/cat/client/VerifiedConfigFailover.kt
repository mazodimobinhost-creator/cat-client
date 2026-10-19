package com.cat.client

internal data class ConfigFailoverResult(
    val status: FailoverStatus,
    val config: VerifiedConfigUri,
    val previousAuthority: String,
    val selectedAuthority: String,
)

internal object VerifiedConfigFailover {
    /** Rebuilds only the ingress endpoint. Protocol, credential, Worker Host/SNI,
     * path, TLS, fingerprint and independent ProxyIP all remain unchanged. */
    fun changeRoute(
        current: VerifiedConfigUri,
        candidates: List<VerifiedEndpoint>,
        nowMs: Long,
        maxAgeMs: Long,
    ): ConfigFailoverResult {
        val choice = VerifiedEndpointFailover.choose(current.endpoint, candidates, nowMs, maxAgeMs)
        val rebuilt = if (choice.status == FailoverStatus.SWITCHED) current.copy(endpoint = choice.selected) else current
        return ConfigFailoverResult(choice.status, rebuilt, current.endpoint.authority, rebuilt.endpoint.authority)
    }
}
