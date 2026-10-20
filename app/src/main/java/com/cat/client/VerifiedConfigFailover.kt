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

    /** Rebuilds only the ProxyIP relay inside the Worker path while preserving the
     * ingress endpoint, port, Host, SNI, protocol and credential. */
    fun changeProxyIp(
        current: VerifiedConfigUri,
        candidates: List<VerifiedEndpoint>,
        nowMs: Long,
        maxAgeMs: Long,
    ): ConfigFailoverResult {
        val currentProxy = current.proxyIp ?: VerifiedEndpoint(
            host = "0.0.0.0",
            port = 443,
            role = EndpointRole.PROXY_IP,
            family = EndpointFamily.IPV4,
            verdict = EndpointVerdict.VERIFIED,
            checkedAtMs = Long.MIN_VALUE,
            source = "none",
        )
        val choice = VerifiedEndpointFailover.choose(currentProxy, candidates, nowMs, maxAgeMs)
        if (choice.status != FailoverStatus.SWITCHED) {
            return ConfigFailoverResult(
                choice.status,
                current,
                current.proxyIp?.authority.orEmpty(),
                current.proxyIp?.authority.orEmpty(),
            )
        }
        val rebuilt = current.copy(proxyIp = choice.selected)
        return ConfigFailoverResult(
            FailoverStatus.SWITCHED,
            rebuilt,
            current.proxyIp?.authority.orEmpty(),
            rebuilt.proxyIp?.authority.orEmpty(),
        )
    }

    /** Retest/failover entry point for a broken VLESS/Trojan share link. */
    fun changeRouteUri(
        rawUri: String,
        candidates: List<VerifiedEndpoint>,
        nowMs: Long,
        maxAgeMs: Long,
        source: String = "retest",
    ): String? {
        val remark = rawUri.trim().substringAfter('#', missingDelimiterValue = "")
        val parsed = VerifiedConfigUriParser.parse(rawUri, checkedAtMs = Long.MIN_VALUE, source = source) ?: return null
        val result = changeRoute(parsed, candidates, nowMs, maxAgeMs)
        if (result.status != FailoverStatus.SWITCHED) return null
        val encoded = result.config.encode()
        return if (remark.isEmpty()) encoded else "$encoded#$remark"
    }

    /** Retest/failover entry point that rotates or attaches a verified ProxyIP relay. */
    fun changeProxyIpUri(
        rawUri: String,
        candidates: List<VerifiedEndpoint>,
        nowMs: Long,
        maxAgeMs: Long,
        source: String = "retest",
    ): String? {
        val remark = rawUri.trim().substringAfter('#', missingDelimiterValue = "")
        val parsed = VerifiedConfigUriParser.parse(rawUri, checkedAtMs = Long.MIN_VALUE, source = source) ?: return null
        val result = changeProxyIp(parsed, candidates, nowMs, maxAgeMs)
        if (result.status != FailoverStatus.SWITCHED) return null
        val encoded = result.config.encode()
        return if (remark.isEmpty()) encoded else "$encoded#$remark"
    }
}
