package com.cat.client

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal enum class TunnelProtocol(val scheme: String) { VLESS("vless"), TROJAN("trojan") }

internal data class VerifiedConfigUri(
    val protocol: TunnelProtocol,
    val credential: String,
    val endpoint: VerifiedEndpoint,
    val workerDomain: String,
    val path: String,
    val tls: Boolean = true,
    val fingerprint: String? = null,
    val proxyIp: VerifiedEndpoint? = null,
) {
    init {
        require(credential.isNotBlank())
        require(endpoint.role == EndpointRole.CLEAN_IP)
        require(endpoint.verdict == EndpointVerdict.VERIFIED)
        require(workerDomain.isNotBlank() && !workerDomain.contains('/'))
        require(path.startsWith('/'))
        require(proxyIp == null || (proxyIp.role == EndpointRole.PROXY_IP && proxyIp.verdict == EndpointVerdict.VERIFIED))
    }

    fun encode(): String {
        // ProxyIP belongs to the Worker's WebSocket path, not to the ingress
        // server/port. Preserve its independently verified authority verbatim.
        val workerPath = proxyIp?.let {
            path + (if ('?' in path) "&" else "?") + "proxyip=" + it.authority
        } ?: path
        val query = buildList {
            add("type=ws")
            add("security=${if (tls) "tls" else "none"}")
            add("host=${workerDomain.urlEncode()}")
            add("sni=${workerDomain.urlEncode()}")
            add("path=${workerPath.urlEncode()}")
            fingerprint?.takeIf { it.isNotBlank() }?.let { add("fp=${it.urlEncode()}") }
        }.joinToString("&")
        return "${protocol.scheme}://${credential.urlEncode()}@${endpoint.uriHost}:${endpoint.port}?$query"
    }
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
