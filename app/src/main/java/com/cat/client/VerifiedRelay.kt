package com.cat.client

import java.net.URI

internal enum class RelayProtocol(val scheme: String, val role: EndpointRole) {
    SOCKS4("socks4", EndpointRole.SOCKS4),
    SOCKS5("socks5", EndpointRole.SOCKS5),
    HTTP("http", EndpointRole.HTTP_RELAY),
    HTTPS("https", EndpointRole.HTTP_RELAY),
}

/** Parsed relay with its protocol and optional raw user-info kept separate from
 * the endpoint. It is never converted into a VLESS/Trojan server. */
internal data class VerifiedRelay(
    val protocol: RelayProtocol,
    val endpoint: VerifiedEndpoint,
    val rawUserInfo: String? = null,
) {
    init { require(endpoint.role == protocol.role) }

    fun encode(): String = buildString {
        append(protocol.scheme).append("://")
        rawUserInfo?.takeIf { it.isNotBlank() }?.let { append(it).append('@') }
        append(endpoint.authority)
    }
}

internal object VerifiedRelayParser {
    fun parse(value: String, checkedAtMs: Long, verdict: EndpointVerdict, source: String): VerifiedRelay? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val protocol = RelayProtocol.entries.firstOrNull { it.scheme.equals(uri.scheme, ignoreCase = true) } ?: return null
        // A missing port is ambiguous and must not silently become 80/443/1080.
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return null
        val family = when {
            host.contains(':') -> EndpointFamily.IPV6
            Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(host) -> EndpointFamily.IPV4
            else -> EndpointFamily.DOMAIN
        }
        val endpoint = VerifiedEndpoint(host, port, protocol.role, family, verdict, checkedAtMs, source = source)
        return VerifiedRelay(protocol, endpoint, uri.rawUserInfo)
    }
}
