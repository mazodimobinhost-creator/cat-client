package com.cat.client

internal enum class EndpointRole { CLEAN_IP, PROXY_IP, SOCKS4, SOCKS5, HTTP_RELAY, VLESS_CHAIN, TROJAN_CHAIN }
internal enum class EndpointFamily { IPV4, IPV6, DOMAIN }
internal enum class EndpointVerdict { VERIFIED, PARTIAL, FAILED, UNKNOWN }

/** A scanner result is an indivisible address+port observation. The port that
 * was measured is the only port allowed into a generated subscription. */
internal data class VerifiedEndpoint(
    val host: String,
    val port: Int,
    val role: EndpointRole,
    val family: EndpointFamily,
    val verdict: EndpointVerdict,
    val checkedAtMs: Long,
    val latencyMs: Long? = null,
    val countryCode: String? = null,
    val asn: String? = null,
    val source: String,
) {
    init {
        require(host.isNotBlank())
        require(port in 1..65535)
        require(source.isNotBlank())
    }

    val authority: String
        get() = if (family == EndpointFamily.IPV6) "[${host.removePrefix("[").removeSuffix("]")}]:$port" else "$host:$port"

    /** URI-safe host used by VLESS/Trojan generators. The scanner port remains separate
     * and must be copied verbatim into the generated URI's port component. */
    val uriHost: String
        get() = if (family == EndpointFamily.IPV6) "[${host.removePrefix("[").removeSuffix("]")}]" else host

    fun relayUri(): String? = when (role) {
        EndpointRole.SOCKS4 -> "socks4://$authority"
        EndpointRole.SOCKS5 -> "socks5://$authority"
        EndpointRole.HTTP_RELAY -> "http://$authority"
        EndpointRole.CLEAN_IP, EndpointRole.PROXY_IP, EndpointRole.VLESS_CHAIN, EndpointRole.TROJAN_CHAIN -> null
    }

    fun eligibleForSubscription(nowMs: Long, maxAgeMs: Long): Boolean =
        verdict == EndpointVerdict.VERIFIED && checkedAtMs in (nowMs - maxAgeMs)..nowMs
}

internal object VerifiedEndpointParser {
    /** Parses only explicit host:port authorities. Missing ports are rejected so callers
     * cannot silently replace the port measured by the scanner. */
    fun parseAuthority(
        value: String,
        role: EndpointRole,
        verdict: EndpointVerdict,
        checkedAtMs: Long,
        source: String,
        latencyMs: Long? = null,
    ): VerifiedEndpoint? {
        val raw = value.trim()
        val ipv6 = Regex("^\\[([^]]+)]:(\\d{1,5})$").matchEntire(raw)
        val regular = Regex("^([^:]+):(\\d{1,5})$").matchEntire(raw)
        val match = ipv6 ?: regular ?: return null
        val host = match.groupValues[1].trim()
        val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val family = when {
            ipv6 != null -> EndpointFamily.IPV6
            Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(host) -> EndpointFamily.IPV4
            else -> EndpointFamily.DOMAIN
        }
        return VerifiedEndpoint(host, port, role, family, verdict, checkedAtMs, latencyMs, source = source)
    }
}

internal object VerifiedEndpointAdapters {
    fun fromCleanIpScan(result: IpScanner.ScanResult, checkedAtMs: Long): VerifiedEndpoint =
        VerifiedEndpoint(
            host = result.ip.removePrefix("[").removeSuffix("]"),
            port = result.port,
            role = EndpointRole.CLEAN_IP,
            family = if (result.ip.contains(':')) EndpointFamily.IPV6 else EndpointFamily.IPV4,
            verdict = if (!result.verifyTls || result.tlsOk) EndpointVerdict.VERIFIED else EndpointVerdict.FAILED,
            checkedAtMs = checkedAtMs,
            latencyMs = result.pingMs,
            countryCode = result.countryCode,
            source = result.sourceRange ?: "device-clean-ip-scan",
        )

    fun fromProxyScan(hit: ProxyScannerPage.Hit, checkedAtMs: Long): VerifiedEndpoint =
        VerifiedEndpoint(
            host = hit.host.removePrefix("[").removeSuffix("]"),
            port = hit.port,
            role = when (hit.proto) {
                ProxyScannerPage.Proto.SOCKS5 -> EndpointRole.SOCKS5
                ProxyScannerPage.Proto.SOCKS4 -> EndpointRole.SOCKS4
                ProxyScannerPage.Proto.HTTP -> EndpointRole.HTTP_RELAY
            },
            family = when {
                hit.host.contains(':') -> EndpointFamily.IPV6
                Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(hit.host) -> EndpointFamily.IPV4
                else -> EndpointFamily.DOMAIN
            },
            verdict = EndpointVerdict.VERIFIED,
            checkedAtMs = checkedAtMs,
            latencyMs = hit.latencyMs,
            countryCode = hit.country.ifBlank { null },
            source = "device-proxy-scan",
        )
}

internal data class ConfigEndpointPlacement(
    val server: String,
    val port: Int,
    val hostHeader: String,
    val serverName: String,
)

internal object VerifiedEndpointPlacement {
    /** Places only a verified clean endpoint on the ingress side. Host/SNI deliberately
     * stay on the Worker domain; replacing them with a clean IP breaks TLS routing. */
    fun forWorker(endpoint: VerifiedEndpoint, workerDomain: String): ConfigEndpointPlacement? {
        val domain = workerDomain.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (endpoint.role != EndpointRole.CLEAN_IP || endpoint.verdict != EndpointVerdict.VERIFIED) return null
        if (domain.isBlank() || domain.contains('/') || domain.any { it.isWhitespace() }) return null
        return ConfigEndpointPlacement(endpoint.host, endpoint.port, domain, domain)
    }
}

internal object VerifiedEndpointSelection {
    fun subscriptionEntries(endpoints: List<VerifiedEndpoint>, nowMs: Long, maxAgeMs: Long): List<VerifiedEndpoint> =
        endpoints.filter { it.role == EndpointRole.CLEAN_IP && it.eligibleForSubscription(nowMs, maxAgeMs) }
            .distinctBy { it.authority }
            .sortedWith(compareBy<VerifiedEndpoint> { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.authority })
}
