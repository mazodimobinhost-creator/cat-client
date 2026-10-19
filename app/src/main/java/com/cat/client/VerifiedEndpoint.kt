package com.cat.client

internal enum class EndpointRole { CLEAN_IP, PROXY_IP, SOCKS5, HTTP_RELAY }
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

    fun eligibleForSubscription(nowMs: Long, maxAgeMs: Long): Boolean =
        verdict == EndpointVerdict.VERIFIED && checkedAtMs in (nowMs - maxAgeMs)..nowMs
}

internal object VerifiedEndpointSelection {
    fun subscriptionEntries(endpoints: List<VerifiedEndpoint>, nowMs: Long, maxAgeMs: Long): List<VerifiedEndpoint> =
        endpoints.filter { it.role == EndpointRole.CLEAN_IP && it.eligibleForSubscription(nowMs, maxAgeMs) }
            .distinctBy { it.authority }
            .sortedWith(compareBy<VerifiedEndpoint> { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.authority })
}
