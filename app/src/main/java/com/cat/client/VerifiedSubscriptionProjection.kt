package com.cat.client

/** Canonical fields shared by Clash/Mihomo, sing-box and Xray serializers. */
internal data class VerifiedSubscriptionProjection(
    val protocol: TunnelProtocol,
    val credential: String,
    val server: String,
    val serverPort: Int,
    val hostHeader: String,
    val serverName: String,
    val path: String,
    val tls: Boolean,
    val fingerprint: String?,
)

internal object VerifiedSubscriptionProjector {
    fun project(config: VerifiedConfigUri): VerifiedSubscriptionProjection? {
        val placement = VerifiedEndpointPlacement.forWorker(config.endpoint, config.workerDomain) ?: return null
        return VerifiedSubscriptionProjection(
            protocol = config.protocol,
            credential = config.credential,
            server = placement.server,
            serverPort = placement.port,
            hostHeader = placement.hostHeader,
            serverName = placement.serverName,
            path = config.path,
            tls = config.tls,
            fingerprint = config.fingerprint,
        )
    }

    fun clash(config: VerifiedConfigUri): Map<String, Any> = project(config).requireProjection().let { p ->
        linkedMapOf(
            "type" to p.protocol.scheme,
            "server" to p.server,
            "port" to p.serverPort,
            (if (p.protocol == TunnelProtocol.VLESS) "uuid" else "password") to p.credential,
            "tls" to p.tls,
            "servername" to p.serverName,
            "network" to "ws",
            "ws-opts" to mapOf("path" to p.path, "headers" to mapOf("Host" to p.hostHeader)),
        ).apply { p.fingerprint?.let { put("client-fingerprint", it) } }
    }

    fun singBox(config: VerifiedConfigUri): Map<String, Any> = project(config).requireProjection().let { p ->
        linkedMapOf(
            "type" to p.protocol.scheme,
            "server" to p.server,
            "server_port" to p.serverPort,
            (if (p.protocol == TunnelProtocol.VLESS) "uuid" else "password") to p.credential,
            "tls" to mapOf("enabled" to p.tls, "server_name" to p.serverName),
            "transport" to mapOf("type" to "ws", "path" to p.path, "headers" to mapOf("Host" to p.hostHeader)),
        )
    }

    fun xray(config: VerifiedConfigUri): Map<String, Any> = project(config).requireProjection().let { p ->
        linkedMapOf(
            "protocol" to p.protocol.scheme,
            "address" to p.server,
            "port" to p.serverPort,
            (if (p.protocol == TunnelProtocol.VLESS) "id" else "password") to p.credential,
            "streamSettings" to mapOf(
                "security" to if (p.tls) "tls" else "none",
                "serverName" to p.serverName,
                "network" to "ws",
                "wsSettings" to mapOf("path" to p.path, "headers" to mapOf("Host" to p.hostHeader)),
            ),
        )
    }

    private fun VerifiedSubscriptionProjection?.requireProjection() = requireNotNull(this) { "unverified endpoint" }
}
