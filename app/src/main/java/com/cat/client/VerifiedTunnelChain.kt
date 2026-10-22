package com.cat.client

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class VerifiedTunnelChain(
    val protocol: TunnelProtocol,
    val credential: String,
    val endpoint: VerifiedEndpoint,
    val hostHeader: String?,
    val serverName: String?,
    val path: String?,
    val security: String?,
    val fingerprint: String?,
)

internal object VerifiedTunnelChainParser {
    fun parse(value: String, checkedAtMs: Long, verdict: EndpointVerdict, source: String): VerifiedTunnelChain? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val protocol = TunnelProtocol.entries.firstOrNull { it.scheme.equals(uri.scheme, true) } ?: return null
        val credential = uri.rawUserInfo?.decode()?.takeIf { it.isNotBlank() } ?: return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val family = when {
            host.contains(':') -> EndpointFamily.IPV6
            Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(host) -> EndpointFamily.IPV4
            else -> EndpointFamily.DOMAIN
        }
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.isEmpty() || pair[0].isBlank()) null else pair[0].decode().lowercase() to pair.getOrElse(1) { "" }.decode()
        }.toMap()
        val role = if (protocol == TunnelProtocol.VLESS) EndpointRole.VLESS_CHAIN else EndpointRole.TROJAN_CHAIN
        val endpoint = VerifiedEndpoint(host, port, role, family, verdict, checkedAtMs, source = source)
        return VerifiedTunnelChain(protocol, credential, endpoint, params["host"], params["sni"] ?: params["servername"],
            params["path"], params["security"], params["fp"] ?: params["fingerprint"])
    }
}

private fun String.decode(): String = URLDecoder.decode(this, StandardCharsets.UTF_8.name())
