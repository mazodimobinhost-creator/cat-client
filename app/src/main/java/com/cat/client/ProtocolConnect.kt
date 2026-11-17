package com.cat.client

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Dedicated Cat editors for TUIC, WireGuard and Cat Warp (Aether).
 * Independent of other clients; Cat branding only. No GPL copy.
 */
object ProtocolConnect {
    enum class Kind { Tuic, WireGuard, Aether }

    data class Draft(
        val kind: Kind,
        val name: String = "",
        val server: String = "",
        val port: Int = 443,
        val uuid: String = "",
        val password: String = "",
        val sni: String = "",
        val congestion: String = "bbr",
        val udpRelay: String = "native",
        val privateKey: String = "auto",
        val publicKey: String = WarpRegistration.WARP_PEER_PUBLIC_KEY,
        val address: String = "172.16.0.2/32",
        val reserved: String = "",
        val aetherMode: String = "masque",
        val ipScan: String = "v4",
    )

    fun empty(kind: Kind): Draft = Draft(
        kind = kind,
        name = when (kind) {
            Kind.Tuic -> "Cat-TUIC"
            Kind.WireGuard -> "Cat-WireGuard"
            Kind.Aether -> "Cat-Warp"
        },
        server = when (kind) {
            Kind.Tuic -> ""
            Kind.WireGuard -> "162.159.193.1"
            Kind.Aether -> "162.159.198.1"
        },
        port = 443,
        aetherMode = "masque",
        ipScan = "v4",
    )

    fun parse(raw: String): Draft? {
        val line = raw.trim().lineSequence().map { it.trim() }.firstOrNull { "://" in it } ?: return null
        return when (line.substringBefore("://").lowercase()) {
            "tuic", "juicity" -> parseTuic(line)
            "wireguard", "wg", "awg", "amneziawg" -> parseWireGuard(line)
            else -> null
        }
    }

    fun toShareUri(draft: Draft): String {
        val name = encode(draft.name.ifBlank { "Cat" })
        val port = draft.port.coerceIn(1, 65535)
        val host = draft.server.trim().ifBlank {
            when (draft.kind) {
                Kind.WireGuard -> "162.159.193.1"
                Kind.Aether -> "162.159.198.1"
                Kind.Tuic -> "127.0.0.1"
            }
        }
        return when (draft.kind) {
            Kind.Tuic -> {
                val user = encode(draft.uuid.trim()) +
                    if (draft.password.isNotBlank()) ":" + encode(draft.password) else ""
                val q = mutableListOf(
                    "congestion_control=" + encode(draft.congestion.ifBlank { "bbr" }),
                    "udp_relay_mode=" + encode(draft.udpRelay.ifBlank { "native" }),
                )
                if (draft.sni.isNotBlank()) q.add(0, "sni=" + encode(draft.sni))
                "tuic://$user@$host:$port?${q.joinToString("&")}#$name"
            }
            Kind.WireGuard -> {
                val key = draft.privateKey.trim().ifBlank { "auto" }
                val user = if (key.equals("auto", ignoreCase = true)) "auto" else encodeKey(key)
                val q = mutableListOf(
                    "publickey=" + encodeKey(draft.publicKey.ifBlank { WarpRegistration.WARP_PEER_PUBLIC_KEY }),
                )
                if (draft.address.isNotBlank()) q += "address=" + encode(draft.address)
                if (draft.reserved.isNotBlank()) q += "reserved=" + encode(draft.reserved)
                "wireguard://$user@$host:$port?${q.joinToString("&")}#$name"
            }
            Kind.Aether -> {
                val proto = when (draft.aetherMode.trim().lowercase()) {
                    "wg", "wireguard" -> "wg"
                    "gool", "wiw", "warp-in-warp" -> "gool"
                    "mim", "masque-in-masque" -> "mim"
                    else -> "masque"
                }
                val ip = draft.ipScan.trim().lowercase().takeIf { it in setOf("v4", "v6", "both") } ?: "v4"
                "aether://$host:$port?protocol=$proto&transport=h2&ip=$ip#$name"
            }
        }
    }

    fun toAetherSelection(draft: Draft): AetherSelection {
        val proto = draft.aetherMode.trim().lowercase()
        val mode = when (proto) {
            "wg", "wireguard" -> AetherMode.WireGuard
            "gool", "wiw", "warp-in-warp" -> AetherMode.WarpInWarp
            "mim", "masque-in-masque" -> AetherMode.MasqueInMasque
            else -> AetherMode.MasqueHttp2
        }
        val host = draft.server.trim().ifBlank {
            if (mode == AetherMode.WireGuard || mode == AetherMode.WarpInWarp) "162.159.192.1" else "162.159.198.1"
        }
        val port = draft.port.takeIf { it in 1..65535 } ?: 443
        val peer = if (host.startsWith("[")) "$host:$port" else "$host:$port"
        return AetherSelection(
            enabled = true,
            mode = mode,
            endpoint = peer,
            ipScan = draft.ipScan.trim().lowercase().takeIf { it in setOf("v4", "v6", "both") } ?: "v4",
            profileName = draft.name.ifBlank { "Cat Warp" },
            masqueTransport = "h2",
        )
    }

    private fun parseTuic(line: String): Draft? = runCatching {
        val uri = URI(line)
        val userInfo = uri.userInfo.orEmpty()
        if (userInfo.isBlank()) return null
        val sep = userInfo.indexOf(':')
        val uuid = if (sep > 0) decode(userInfo.substring(0, sep)) else decode(userInfo)
        val pass = if (sep > 0) decode(userInfo.substring(sep + 1)) else ""
        val query = queryMap(uri.rawQuery)
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank() || uuid.isBlank()) return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        Draft(
            kind = Kind.Tuic,
            name = fragmentName(uri),
            server = host,
            port = port,
            uuid = uuid,
            password = pass,
            sni = (query["sni"] ?: query["servername"]).orEmpty(),
            congestion = (query["congestion_control"] ?: query["congestion-controller"] ?: query["cc"]).orEmpty().ifBlank { "bbr" },
            udpRelay = (query["udp_relay_mode"] ?: query["udp-relay-mode"]).orEmpty().ifBlank { "native" },
        )
    }.getOrNull()

    private fun parseWireGuard(line: String): Draft? = runCatching {
        val uri = URI(line)
        val userInfo = uri.userInfo.orEmpty().ifBlank { "auto" }
        val query = queryMap(uri.rawQuery)
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        Draft(
            kind = Kind.WireGuard,
            name = fragmentName(uri),
            server = host,
            port = port,
            privateKey = decode(userInfo).ifBlank { "auto" },
            publicKey = (query["publickey"] ?: query["public-key"]).orEmpty().ifBlank { WarpRegistration.WARP_PEER_PUBLIC_KEY },
            address = (query["address"] ?: query["ip"]).orEmpty().ifBlank { "172.16.0.2/32" },
            reserved = query["reserved"].orEmpty(),
        )
    }.getOrNull()

    private fun fragmentName(uri: URI): String {
        val raw = uri.rawFragment ?: uri.fragment ?: return ""
        return decode(raw)
    }

    private fun queryMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            decode(pair.substring(0, eq)).lowercase() to decode(pair.substring(eq + 1))
        }.toMap()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodeKey(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%2B")

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
