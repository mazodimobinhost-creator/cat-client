package com.cat.client

internal enum class PortProbeKind { CLEAN_TLS, CLEAN_PLAIN, PROXY_IP, SOCKS4, SOCKS5, HTTP_CONNECT, HTTPS_CONNECT }

internal data class PortProbeCandidate(val host: String, val port: Int, val kind: PortProbeKind)

/** Builds a bounded, explicit probe plan for a host whose source omitted the port.
 * Probe results are not endpoints until the protocol-specific runtime check succeeds. */
internal object EndpointPortDiscovery {
    val cleanTlsPorts = listOf(443, 2053, 2083, 2087, 2096, 8443)
    val cleanPlainPorts = listOf(80, 2052, 2082, 2086, 2095, 8080, 8880)
    val relaySuggestedPorts = listOf(1080, 3128, 8080, 8888, 8443)

    fun plan(host: String, kinds: Set<PortProbeKind>, customPorts: List<Int> = emptyList()): List<PortProbeCandidate> {
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
        require(normalizedHost.isNotBlank() && normalizedHost.none { it.isWhitespace() })
        val explicit = customPorts.distinct().also { ports -> require(ports.all { it in 1..65535 }) }
        return kinds.flatMap { kind ->
            val defaults = when (kind) {
                PortProbeKind.CLEAN_TLS -> cleanTlsPorts
                PortProbeKind.CLEAN_PLAIN -> cleanPlainPorts
                else -> relaySuggestedPorts
            }
            (explicit.ifEmpty { defaults }).map { PortProbeCandidate(normalizedHost, it, kind) }
        }.distinct()
    }

    fun verifiedEndpoint(candidate: PortProbeCandidate, checkedAtMs: Long, latencyMs: Long, source: String): VerifiedEndpoint {
        val role = when (candidate.kind) {
            PortProbeKind.CLEAN_TLS, PortProbeKind.CLEAN_PLAIN -> EndpointRole.CLEAN_IP
            PortProbeKind.PROXY_IP -> EndpointRole.PROXY_IP
            PortProbeKind.SOCKS4 -> EndpointRole.SOCKS4
            PortProbeKind.SOCKS5 -> EndpointRole.SOCKS5
            PortProbeKind.HTTP_CONNECT, PortProbeKind.HTTPS_CONNECT -> EndpointRole.HTTP_RELAY
        }
        val family = when {
            candidate.host.contains(':') -> EndpointFamily.IPV6
            Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(candidate.host) -> EndpointFamily.IPV4
            else -> EndpointFamily.DOMAIN
        }
        return VerifiedEndpoint(candidate.host, candidate.port, role, family, EndpointVerdict.VERIFIED,
            checkedAtMs, latencyMs, source = source)
    }
}
