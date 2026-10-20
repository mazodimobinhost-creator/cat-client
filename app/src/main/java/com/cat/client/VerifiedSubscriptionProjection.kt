package com.cat.client

import org.json.JSONArray
import org.json.JSONObject

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
    val name: String,
)

internal object VerifiedSubscriptionProjector {
    fun project(config: VerifiedConfigUri, index: Int = 0): VerifiedSubscriptionProjection? {
        val placement = VerifiedEndpointPlacement.forWorker(config.endpoint, config.workerDomain) ?: return null
        val baseName = "${config.protocol.scheme}-${config.endpoint.authority}"
        val uniqueName = if (index > 0) "$baseName (${index + 1})" else baseName
        return VerifiedSubscriptionProjection(
            protocol = config.protocol,
            credential = config.credential,
            server = placement.server,
            serverPort = placement.port,
            hostHeader = placement.hostHeader,
            serverName = config.serverName,
            path = config.effectivePath,
            tls = config.tls,
            fingerprint = config.fingerprint,
            name = uniqueName,
        )
    }

    fun clash(config: VerifiedConfigUri, index: Int = 0): Map<String, Any> = project(config, index).requireProjection().let { p ->
        linkedMapOf(
            "name" to p.name,
            "type" to p.protocol.scheme,
            "server" to p.server,
            "port" to p.serverPort,
            (if (p.protocol == TunnelProtocol.VLESS) "uuid" else "password") to p.credential,
            "udp" to true,
            "tls" to p.tls,
            "servername" to p.serverName,
            "network" to "ws",
            "ws-opts" to mapOf("path" to p.path, "headers" to mapOf("Host" to p.hostHeader)),
        ).apply { p.fingerprint?.takeIf { it.isNotBlank() }?.let { put("client-fingerprint", it) } }
    }

    fun singBox(config: VerifiedConfigUri, index: Int = 0): Map<String, Any> = project(config, index).requireProjection().let { p ->
        val tlsMap = linkedMapOf<String, Any>(
            "enabled" to p.tls,
            "server_name" to p.serverName,
        ).apply {
            p.fingerprint?.takeIf { it.isNotBlank() }?.let {
                put("utls", mapOf("enabled" to true, "fingerprint" to it))
            }
        }
        linkedMapOf(
            "tag" to p.name,
            "type" to p.protocol.scheme,
            "server" to p.server,
            "server_port" to p.serverPort,
            (if (p.protocol == TunnelProtocol.VLESS) "uuid" else "password") to p.credential,
            "tls" to tlsMap,
            "transport" to mapOf("type" to "ws", "path" to p.path, "headers" to mapOf("Host" to p.hostHeader)),
        )
    }

    fun xray(config: VerifiedConfigUri, index: Int = 0): Map<String, Any> = project(config, index).requireProjection().let { p ->
        val tlsSettings = linkedMapOf<String, Any>("serverName" to p.serverName).apply {
            p.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
        }
        val stream = linkedMapOf<String, Any>(
            "security" to if (p.tls) "tls" else "none",
            "serverName" to p.serverName,
            "network" to "ws",
            "wsSettings" to mapOf("path" to p.path, "headers" to mapOf("Host" to p.hostHeader)),
        ).apply {
            p.fingerprint?.takeIf { it.isNotBlank() }?.let { put("fingerprint", it) }
            if (p.tls) put("tlsSettings", tlsSettings)
        }
        val settings = if (p.protocol == TunnelProtocol.VLESS) {
            mapOf(
                "vnext" to listOf(
                    mapOf(
                        "address" to p.server,
                        "port" to p.serverPort,
                        "users" to listOf(mapOf("id" to p.credential, "encryption" to "none")),
                    ),
                ),
            )
        } else {
            mapOf(
                "servers" to listOf(
                    mapOf(
                        "address" to p.server,
                        "port" to p.serverPort,
                        "password" to p.credential,
                    ),
                ),
            )
        }
        linkedMapOf(
            "tag" to "proxy",
            "protocol" to p.protocol.scheme,
            "address" to p.server,
            "port" to p.serverPort,
            (if (p.protocol == TunnelProtocol.VLESS) "id" else "password") to p.credential,
            "settings" to settings,
            "streamSettings" to stream,
        )
    }

    fun toClashJson(configs: List<VerifiedConfigUri>): String = JSONObject()
        .put("proxies", JSONArray().apply { configs.forEachIndexed { idx, cfg -> put(toJsonObject(clash(cfg, idx))) } })
        .toString()

    fun toSingBoxJson(configs: List<VerifiedConfigUri>): String = JSONObject()
        .put("outbounds", JSONArray().apply { configs.forEachIndexed { idx, cfg -> put(toJsonObject(singBox(cfg, idx))) } })
        .toString()

    fun toXrayJson(configs: List<VerifiedConfigUri>): String = JSONArray().apply {
        configs.forEachIndexed { idx, cfg ->
            val p = project(cfg, idx).requireProjection()
            put(
                JSONObject()
                    .put("remarks", p.name)
                    .put("outbounds", JSONArray().put(toJsonObject(xray(cfg, idx)))),
            )
        }
    }.toString()

    private fun toJsonObject(map: Map<*, *>): JSONObject = JSONObject().apply {
        for ((k, v) in map) {
            if (k != null && v != null) put(k.toString(), toJsonValue(v))
        }
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> toJsonObject(value)
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        else -> value
    }

    private fun VerifiedSubscriptionProjection?.requireProjection() = requireNotNull(this) { "unverified endpoint" }
}
