package com.cat.client

import java.net.URI
import java.net.URLDecoder
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
    val ech: String? = null,
) {
    init {
        require(credential.isNotBlank())
        require(endpoint.role == EndpointRole.CLEAN_IP)
        require(endpoint.verdict == EndpointVerdict.VERIFIED)
        require(workerDomain.isNotBlank() && !workerDomain.contains('/') && workerDomain.none(Char::isWhitespace))
        require(serverName.isNotBlank() && !serverName.contains('/') && serverName.none(Char::isWhitespace))
        require(path.startsWith('/'))
        require(proxyIp == null || (proxyIp.role == EndpointRole.PROXY_IP && proxyIp.verdict == EndpointVerdict.VERIFIED))
        require(ech == null || (tls && ech.isNotBlank() && serverName.equals(workerDomain, ignoreCase = true)))
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
            if (tls && !ech.isNullOrBlank()) add("ech=${ech.urlEncode()}")
        }.joinToString("&")
        return "${protocol.scheme}://${credential.urlEncode()}@${endpoint.uriHost}:${endpoint.port}?$query"
    }
}

internal object VerifiedConfigUriParser {
    /** Losslessly parses a VLESS/Trojan URI so route failover or subscription
     * projection can replace the endpoint without losing SNI, Host, path or ProxyIP. */
    fun parse(
        value: String,
        checkedAtMs: Long,
        source: String,
        verdict: EndpointVerdict = EndpointVerdict.VERIFIED,
    ): VerifiedConfigUri? {
        if (verdict != EndpointVerdict.VERIFIED) return null
        val clean = value.trim().substringBefore('#')
        val uri = runCatching { URI(clean) }.getOrNull() ?: return null
        val protocol = TunnelProtocol.entries.firstOrNull { it.scheme.equals(uri.scheme, ignoreCase = true) } ?: return null
        val credential = uri.rawUserInfo?.urlDecode()?.takeIf { it.isNotBlank() } ?: return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        val family = when {
            host.contains(':') -> EndpointFamily.IPV6
            Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(host) -> EndpointFamily.IPV4
            else -> EndpointFamily.DOMAIN
        }
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val kv = part.split('=', limit = 2)
            if (kv.isEmpty() || kv[0].isBlank()) null
            else kv[0].urlDecode().lowercase() to kv.getOrElse(1) { "" }.urlDecode()
        }.toMap()

        val workerDomain = (params["host"]?.takeIf { it.isNotBlank() }
            ?: params["sni"]?.takeIf { it.isNotBlank() }
            ?: params["servername"]?.takeIf { it.isNotBlank() }
            ?: host)
        val serverName = (params["sni"]?.takeIf { it.isNotBlank() }
            ?: params["servername"]?.takeIf { it.isNotBlank() }
            ?: workerDomain)
        val tls = !params["security"].equals("none", ignoreCase = true)
        val fingerprint = (params["fp"] ?: params["fingerprint"])?.takeIf { it.isNotBlank() }
        val rawEch = params["ech"]?.trim()?.takeIf { it.isNotBlank() && !it.equals("off", ignoreCase = true) }
        val hasEchConflict = !params["fm"].isNullOrBlank() ||
            !params["cs"].isNullOrBlank() ||
            !serverName.equals(workerDomain, ignoreCase = true)
        val ech = if (tls && !hasEchConflict) rawEch else null
        val rawPath = params["path"]?.takeIf { it.isNotBlank() } ?: "/"
        if (!rawPath.startsWith('/')) return null

        val (cleanPath, proxyIp) = extractPathAndProxyIp(rawPath, checkedAtMs, source) ?: return null
        val endpoint = VerifiedEndpoint(host, port, EndpointRole.CLEAN_IP, family, verdict, checkedAtMs, source = source)
        return runCatching {
            VerifiedConfigUri(
                protocol = protocol,
                credential = credential,
                endpoint = endpoint,
                workerDomain = workerDomain,
                path = cleanPath,
                tls = tls,
                fingerprint = fingerprint,
                proxyIp = proxyIp,
                serverName = serverName,
                ech = ech,
            )
        }.getOrNull()
    }

    private fun extractPathAndProxyIp(
        rawPath: String,
        checkedAtMs: Long,
        source: String,
    ): Pair<String, VerifiedEndpoint?>? {
        if ('?' !in rawPath) return rawPath to null
        val basePath = rawPath.substringBefore('?')
        val query = rawPath.substringAfter('?')
        val kept = ArrayList<String>()
        var proxyIp: VerifiedEndpoint? = null
        for (segment in query.split('&').filter { it.isNotBlank() }) {
            val kv = segment.split('=', limit = 2)
            if (kv[0].equals("proxyip", ignoreCase = true)) {
                val rawAuthority = kv.getOrElse(1) { "" }.urlDecode().trim()
                val parsed = VerifiedEndpointParser.parseAuthority(
                    rawAuthority, EndpointRole.PROXY_IP, EndpointVerdict.VERIFIED, checkedAtMs, source,
                ) ?: return null
                proxyIp = parsed
            } else {
                kept += segment
            }
        }
        val cleanPath = if (kept.isEmpty()) basePath else "$basePath?${kept.joinToString("&")}"
        return cleanPath to proxyIp
    }
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.urlDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())
