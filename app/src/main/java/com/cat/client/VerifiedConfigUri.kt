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
    val serverName: String = workerDomain,
) {
    init {
        require(credential.isNotBlank())
        require(endpoint.role == EndpointRole.CLEAN_IP)
        require(endpoint.verdict == EndpointVerdict.VERIFIED)
        require(workerDomain.isNotBlank() && !workerDomain.contains('/') && workerDomain.none(Char::isWhitespace))
        require(serverName.isNotBlank() && !serverName.contains('/') && serverName.none(Char::isWhitespace))
        require(path.startsWith('/'))
        require(proxyIp == null || (proxyIp.role == EndpointRole.PROXY_IP && proxyIp.verdict == EndpointVerdict.VERIFIED))
    }

    /** Path handed to WebSocket transport across URI, Clash/Mihomo, sing-box and Xray.
     * ProxyIP keeps its own verified authority inside the path query without touching ingress port. */
    val effectivePath: String
        get() = proxyIp?.let {
            path + (if ('?' in path) "&" else "?") + "proxyip=" + it.authority
        } ?: path

    fun encode(): String {
        val query = buildList {
            add("type=ws")
            add("security=${if (tls) "tls" else "none"}")
            add("host=${workerDomain.urlEncode()}")
            add("sni=${serverName.urlEncode()}")
            add("path=${effectivePath.urlEncode()}")
            fingerprint?.takeIf { it.isNotBlank() }?.let { add("fp=${it.urlEncode()}") }
        }.joinToString("&")
        return "${protocol.scheme}://${credential.urlEncode()}@${endpoint.uriHost}:${endpoint.port}?$query"
    }
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
