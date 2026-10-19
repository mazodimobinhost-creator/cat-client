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
) {
    init {
        require(credential.isNotBlank())
        require(endpoint.role == EndpointRole.CLEAN_IP)
        require(endpoint.verdict == EndpointVerdict.VERIFIED)
        require(workerDomain.isNotBlank() && !workerDomain.contains('/'))
        require(path.startsWith('/'))
    }

    fun encode(): String {
        val query = buildList {
            add("type=ws")
            add("security=${if (tls) "tls" else "none"}")
            add("host=${workerDomain.urlEncode()}")
            add("sni=${workerDomain.urlEncode()}")
            add("path=${path.urlEncode()}")
            fingerprint?.takeIf { it.isNotBlank() }?.let { add("fp=${it.urlEncode()}") }
        }.joinToString("&")
        return "${protocol.scheme}://${credential.urlEncode()}@${endpoint.uriHost}:${endpoint.port}?$query"
    }
}

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")
