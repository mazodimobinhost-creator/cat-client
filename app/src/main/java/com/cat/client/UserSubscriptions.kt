package com.cat.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class UserSubscriptionFormat(val wireName: String, val label: String) {
    Mihomo("mihomo", "Mihomo YAML"),
    Links("links", "VLESS / VMess / Trojan / SS / WireGuard");

    companion object {
        fun fromWireName(value: String?): UserSubscriptionFormat =
            entries.firstOrNull { it.wireName == value } ?: Links
    }
}

data class UserSubscription(
    val id: String,
    val name: String,
    val input: String,
    val format: UserSubscriptionFormat,
    val connectionCount: Int,
    val updatedAt: Long,
    val lastError: String = "",
    val fetchedAt: Long = updatedAt,
    val sourceKind: UserSubscriptionSourceKind =
        UserSubscriptionSourceKind.fromWireName(null, input),
)

data class ImportedUserSubscription(
    val yaml: String,
    val format: UserSubscriptionFormat,
    val connectionCount: Int,
)

internal fun normalizedSubscriptionSource(contents: String?): String? =
    contents?.trim()?.takeIf(String::isNotEmpty)

internal fun scannedSubscriptionName(source: String): String? {
    fun clean(value: String?): String? = value?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    fun decode(value: String?): String? {
        val encoded = value ?: return null
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.let(::clean)
    }

    val uriName = runCatching {
        val uri = URI(source)
        val query = uri.rawQuery?.split('&').orEmpty()
        listOf("remark", "remarks", "name").firstNotNullOfOrNull { key ->
            query.firstOrNull { it.substringBefore('=') == key }
                ?.substringAfter('=', "")
                ?.let(::decode)
        } ?: decode(uri.rawFragment)
    }.getOrNull()
    if (uriName != null) return uriName

    if (AmneziaVpnKey.looksLikeKey(source)) {
        return AmneziaVpnKey.decode(source)?.serverName?.let { "🔐 Amnezia · ${it.take(40)}" } ?: "🔐 Amnezia"
    }

    if (source.startsWith("slipnet://", ignoreCase = true) ||
        source.startsWith("slipnet-enc://", ignoreCase = true) ||
        source.startsWith("dnst://", ignoreCase = true)
    ) {
        WhiteDnsSmartScanner.parseTunnelUri(source)?.remark?.let(::clean)?.let { return it }
    }

    val jsonSource = if (source.startsWith("vmess://")) {
        SubConvConverter.decodeBase64Text(source.removePrefix("vmess://")) ?: return null
    } else {
        source
    }
    val json = runCatching {
        when (jsonSource.trimStart().firstOrNull()) {
            '{' -> JSONObject(jsonSource)
            '[' -> JSONArray(jsonSource).optJSONObject(0)
            else -> null
        }
    }.getOrNull() ?: return null
    return listOf("remarks", "remark", "ps", "name")
        .firstNotNullOfOrNull { key -> clean(json.optString(key)) }
}

internal fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(minOf(maxBytes, 8 * 1024).coerceAtLeast(1))
    while (output.size() < maxBytes) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
        if (count <= 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

object UserSubscriptionImporter {
    fun import(content: String, nowMs: Long = System.currentTimeMillis()): ImportedUserSubscription {
        val trimmed = content.trim()
        require(trimmed.isNotBlank()) { "سابسکریپشن خالی است" }
        require(trimmed.toByteArray().size <= MAX_SUBSCRIPTION_BYTES) { "حجم سابسکریپشن بیش از حد مجاز است" }
        if (CatFusionLink.isCatFusion(trimmed)) {
            val links = CatFusionLink.decode(trimmed.lines().first().trim())
            require(!links.isNullOrEmpty()) { "لینک catfusion:// معتبر نیست" }
            val bundle = LocalSniSpoofProxy.buildFusedMultiTunnelSubscription(listOf(links.joinToString("\n")))
            require(bundle != null && bundle.totalProxiesFused > 0) { "اتصال پشتیبانی‌شده‌ای در catfusion:// پیدا نشد" }
            val count = MihomoConfigParser.parse(bundle.yaml, nowMs).catalog.profiles.size
            require(count > 0) { "اتصال پشتیبانی‌شده‌ای پیدا نشد" }
            return ImportedUserSubscription(bundle.yaml, UserSubscriptionFormat.Links, count)
        }
        // AmneziaVPN keys (vpn://…) → awg:// / wireguard:// / vless:// / ss:// links; other lines are kept.
        if (AmneziaVpnKey.extractKeys(trimmed).isNotEmpty()) {
            val keys = AmneziaVpnKey.extractKeys(trimmed)
            val decoded = keys.map { AmneziaVpnKey.decode(it) }
            val amneziaLinks = decoded.flatMap { it?.links.orEmpty() }
            val others = trimmed.split(Regex("\\s+")).filterNot { it.startsWith("vpn://", ignoreCase = true) }
                .filter { it.contains("://") }
            require(amneziaLinks.isNotEmpty() || others.isNotEmpty()) {
                decoded.firstNotNullOfOrNull { it?.problemFa } ?: "کلید vpn:// معتبر نیست (کلید Amnezia خراب یا ناقص است)"
            }
            val combined = (amneziaLinks + others).joinToString("\n")
            val amYaml = MihomoLinkConfigBuilder.build(SubConvConverter.convert(combined))
            val amCount = MihomoConfigParser.parse(amYaml, nowMs).catalog.profiles.size
            require(amCount > 0) { "کانفیگ قابل اجرایی در کلید Amnezia پیدا نشد" }
            return ImportedUserSubscription(amYaml, UserSubscriptionFormat.Links, amCount)
        }
        // Plain WireGuard / AmneziaWG .conf text (pasted, shared or opened as a file).
        val wgConfUris = WireGuardConfText.toUris(trimmed)
        if (wgConfUris.isNotEmpty()) {
            val wgYaml = MihomoLinkConfigBuilder.build(SubConvConverter.convert(wgConfUris.joinToString("\n")))
            val wgCount = MihomoConfigParser.parse(wgYaml, nowMs).catalog.profiles.size
            require(wgCount > 0) { "فایل WireGuard معتبر نیست (PrivateKey / PublicKey / Endpoint)" }
            return ImportedUserSubscription(wgYaml, UserSubscriptionFormat.Links, wgCount)
        }
        val normalized = SubConvConverter.decodeBase64Text(trimmed)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: trimmed

        JsonSubscriptionImporter.import(normalized, nowMs)?.let { return it }

        val mihomo = runCatching { MihomoConfigParser.parse(normalized, nowMs) }.getOrNull()
        if (mihomo != null && mihomo.catalog.profiles.isNotEmpty()) {
            val yaml = if (mihomo.summary.groups.isEmpty()) {
                MihomoLinkConfigBuilder.addDefaultGroups(normalized, mihomo.catalog.profiles.map { it.tag })
            } else {
                normalized
            }
            return ImportedUserSubscription(yaml, UserSubscriptionFormat.Mihomo, mihomo.catalog.profiles.size)
        }

        val yaml = MihomoLinkConfigBuilder.build(SubConvConverter.convert(normalized))
        val count = MihomoConfigParser.parse(yaml, nowMs).catalog.profiles.size
        require(count > 0) { "اتصال پشتیبانی‌شده‌ای پیدا نشد" }
        return ImportedUserSubscription(yaml, UserSubscriptionFormat.Links, count)
    }

    const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
}

object JsonSubscriptionImporter {
    fun import(content: String, nowMs: Long): ImportedUserSubscription? {
        val first = content.firstOrNull() ?: return null
        val (proxies, format) = when (first) {
            '{' -> {
                val obj = runCatching { JSONObject(content) }.getOrNull() ?: return null
                val clash = clashProxies(obj)
                if (clash.isNotEmpty()) {
                    clash to UserSubscriptionFormat.Mihomo
                } else {
                    val sing = singBoxProxies(obj)
                    if (sing.isNotEmpty()) {
                        sing to UserSubscriptionFormat.Links
                    } else {
                        xrayProxies(JSONArray().put(obj)) to UserSubscriptionFormat.Links
                    }
                }
            }
            '[' -> xrayProxies(runCatching { JSONArray(content) }.getOrNull() ?: return null) to
                UserSubscriptionFormat.Links
            else -> return null
        }
        if (proxies.isEmpty()) return null

        val yaml = MihomoLinkConfigBuilder.build(proxies)
        val count = MihomoConfigParser.parse(yaml, nowMs).catalog.profiles.size
        require(count > 0) { "اتصال پشتیبانی‌شده‌ای پیدا نشد" }
        return ImportedUserSubscription(yaml, format, count)
    }

    private fun clashProxies(config: JSONObject): List<JSONObject> {
        val proxies = config.optJSONArray("proxies") ?: return emptyList()
        return (0 until proxies.length()).mapNotNull { index ->
            val proxy = proxies.optJSONObject(index) ?: return@mapNotNull null
            val rawServer = proxy.optString("server")
            if (rawServer.startsWith("[") && rawServer.endsWith("]")) {
                proxy.put("server", rawServer.removePrefix("[").removeSuffix("]"))
            }
            if (proxy.optString("type") in setOf("socks", "socks5")) {
                proxy.put("type", "socks5")
                if (!proxy.has("udp")) proxy.put("udp", true)
            }
            proxy.takeIf(::isSupportedMihomoProxy)
        }
    }

    private fun singBoxProxies(config: JSONObject): List<JSONObject> {
        val outbounds = config.optJSONArray("outbounds") ?: return emptyList()
        return (0 until outbounds.length()).mapNotNull { index ->
            val outbound = outbounds.optJSONObject(index) ?: return@mapNotNull null
            val protocol = outbound.optString("type")
            if (protocol !in setOf("vless", "vmess", "trojan", "socks")) return@mapNotNull null
            val server = outbound.optString("server").removePrefix("[").removeSuffix("]")
            val port = outbound.optInt("server_port")
            if (server.isBlank() || port !in 1..65535) return@mapNotNull null

            val proxy = JSONObject()
                .put("name", outbound.optString("tag").ifBlank { "SingBox ${index + 1}" })
                .put("type", if (protocol == "socks") "socks5" else protocol)
                .put("server", server)
                .put("port", port)
                .put("udp", true)

            when (protocol) {
                "vless", "vmess" -> {
                    val uuid = outbound.optString("uuid").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    proxy.put("uuid", uuid)
                    if (protocol == "vless") {
                        outbound.optString("flow").takeIf(String::isNotBlank)?.let { proxy.put("flow", it) }
                    } else {
                        proxy.put("alterId", outbound.optInt("alter_id", 0))
                            .put("cipher", outbound.optString("security").ifBlank { "auto" })
                    }
                }
                "trojan" -> {
                    val password = outbound.optString("password").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    proxy.put("password", password)
                }
                "socks" -> {
                    if (outbound.has("username") || outbound.has("password")) {
                        val username = outbound.optString("username").takeIf(String::isNotBlank) ?: return@mapNotNull null
                        proxy.put("username", username).put("password", outbound.optString("password"))
                    }
                    return@mapNotNull proxy.takeIf(::isSupportedMihomoProxy)
                }
            }

            val tlsObj = outbound.optJSONObject("tls")
            if (tlsObj?.optBoolean("enabled", false) == true) {
                proxy.put("tls", true)
                tlsObj.optString("server_name").takeIf(String::isNotBlank)?.let { proxy.put("servername", it) }
                val fp = tlsObj.optJSONObject("utls")?.optString("fingerprint").orEmpty()
                    .ifBlank { tlsObj.optString("fingerprint") }
                if (fp.isNotBlank()) proxy.put("client-fingerprint", fp)
                proxy.put("skip-cert-verify", false)
            } else if (protocol == "trojan") {
                proxy.put("tls", false)
            }

            val transport = outbound.optJSONObject("transport")
            when (transport?.optString("type")) {
                "ws", "httpupgrade" -> {
                    val transportType = transport.optString("type")
                    val wsOpts = JSONObject().put("path", transport.optString("path").ifBlank { "/" })
                    val host = transport.optJSONObject("headers")
                        ?.let { it.optString("Host").ifBlank { it.optString("host") } }
                        .orEmpty()
                        .ifBlank { transport.optString("host") }
                    if (host.isNotBlank()) wsOpts.put("headers", JSONObject().put("Host", host))
                    // Mihomo has no `httpupgrade` network: it is ws + v2ray-http-upgrade.
                    if (transportType == "httpupgrade") wsOpts.put("v2ray-http-upgrade", true)
                    proxy.put("network", "ws").put("ws-opts", wsOpts)
                }
                "grpc" -> {
                    proxy.put(
                        "network",
                        "grpc",
                    ).put(
                        "grpc-opts",
                        JSONObject().put("grpc-service-name", transport.optString("service_name")),
                    )
                }
            }
            proxy.takeIf(::isSupportedMihomoProxy)
        }
    }

    private fun xrayProxies(configs: JSONArray): List<JSONObject> {
        return (0 until configs.length()).flatMap { index ->
            val config = configs.optJSONObject(index) ?: return@flatMap emptyList()
            xrayWireGuardProxies(config, index).ifEmpty { listOfNotNull(xrayProxy(config, index)) }
        }
    }

    private fun xrayWireGuardProxies(config: JSONObject, index: Int): List<JSONObject> {
        val outbounds = config.optJSONArray("outbounds") ?: return emptyList()
        val wireGuard = (0 until outbounds.length())
            .mapNotNull(outbounds::optJSONObject)
            .filter { it.optString("protocol") == "wireguard" }
        if (wireGuard.isEmpty()) return emptyList()

        val baseName = config.optString("remarks").ifBlank { "Xray ${index + 1}" }
        val names = wireGuard.mapIndexed { position, outbound ->
            if (position == 0) baseName else "$baseName (${outbound.optString("tag").ifBlank { "WireGuard ${position + 1}" }})"
        }
        val converted = wireGuard.mapIndexedNotNull { position, outbound ->
            xrayWireGuardProxy(outbound, names[position])?.let { outbound to it }
        }
        val namesByTag = converted.mapNotNull { (outbound, proxy) ->
            outbound.optString("tag").takeIf(String::isNotBlank)?.let { it to proxy.getString("name") }
        }.toMap()

        return converted.mapNotNull { (outbound, proxy) ->
            val dialerTag = outbound.optJSONObject("streamSettings")
                ?.optJSONObject("sockopt")
                ?.optString("dialerProxy")
                .orEmpty()
            if (dialerTag.isBlank()) return@mapNotNull proxy
            val dialerName = namesByTag[dialerTag] ?: return@mapNotNull null
            proxy.put("dialer-proxy", dialerName)
        }
    }

    private fun xrayWireGuardProxy(outbound: JSONObject, name: String): JSONObject? {
        val settings = outbound.optJSONObject("settings") ?: return null
        val peers = settings.optJSONArray("peers") ?: return null
        // ponytail: current Xray WARP feeds use one peer; add Mihomo full `peers` syntax if a real multi-peer feed appears.
        if (peers.length() != 1) return null
        val peer = peers.optJSONObject(0) ?: return null
        val (server, port) = wireGuardEndpoint(peer.optString("endpoint")) ?: return null
        val privateKey = settings.optString("secretKey").takeIf(String::isNotBlank) ?: return null
        val publicKey = peer.optString("publicKey").takeIf(String::isNotBlank) ?: return null
        val addresses = settings.optJSONArray("address") ?: return null
        val localAddresses = (0 until addresses.length())
            .mapNotNull { addresses.opt(it) as? String }
            .map { it.substringBefore('/').trim() }
            .filter(String::isNotBlank)
        val ipv4 = localAddresses.firstOrNull { ':' !in it } ?: return null
        val ipv6 = localAddresses.firstOrNull { ':' in it }

        return JSONObject()
            .put("name", name)
            .put("type", "wireguard")
            .put("server", server)
            .put("port", port)
            .put("ip", ipv4)
            .put("ip-version", if (ipv6 == null) "ipv4" else "ipv4-prefer")
            .put("private-key", privateKey)
            .put("public-key", publicKey)
            .put("allowed-ips", peer.optJSONArray("allowedIPs") ?: JSONArray(listOf("0.0.0.0/0", "::/0")))
            .put("udp", true)
            .apply {
                ipv6?.let { put("ipv6", it) }
                settings.optJSONArray("reserved")?.let { put("reserved", wireGuardReserved(it) ?: return null) }
                settings.optInt("mtu").takeIf { it > 0 }?.let { put("mtu", it) }
                peer.optString("preSharedKey").takeIf(String::isNotBlank)?.let { put("pre-shared-key", it) }
                peer.optInt("keepAlive").takeIf { it > 0 }?.let { put("persistent-keepalive", it) }
                listOf("amnezia-wg-option", "wireguard-dpi-option", "ip-stack").forEach { key ->
                    settings.optJSONObject(key)?.let { put(key, it) }
                }
            }
    }

    private fun wireGuardEndpoint(value: String): Pair<String, Int>? {
        val endpoint = runCatching { URI("wg://$value") }.getOrNull() ?: return null
        val server = endpoint.host?.removePrefix("[")?.removeSuffix("]")?.takeIf(String::isNotBlank) ?: return null
        val port = endpoint.port.takeIf { it in 1..65535 } ?: return null
        return server to port
    }

    private fun wireGuardReserved(source: JSONArray): JSONArray? {
        if (source.length() != 3) return null
        val values = mutableListOf<Int>()
        for (index in 0 until source.length()) {
            val value = (source.opt(index) as? Number)?.toInt()?.takeIf { it in 0..255 } ?: return null
            values += value
        }
        return JSONArray(values)
    }

    private fun xrayProxy(config: JSONObject, index: Int): JSONObject? {
        val outbounds = config.optJSONArray("outbounds") ?: return null
        val supported = (0 until outbounds.length())
            .mapNotNull(outbounds::optJSONObject)
            .filter { it.optString("protocol") in setOf("vless", "vmess", "trojan", "socks") }
        val outbound = supported.firstOrNull { it.optString("tag") == "proxy" }
            ?: supported.singleOrNull()
            ?: return null
        val protocol = outbound.optString("protocol")
        val settings = outbound.optJSONObject("settings")
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val proxy = JSONObject()
            .put("name", config.optString("remarks").ifBlank { "Xray ${index + 1}" })
            .put("type", if (protocol == "socks") "socks5" else protocol)
            .put("udp", true)
        when (protocol) {
            "vless", "vmess" -> {
                val endpoint = settings?.optJSONArray("vnext")?.optJSONObject(0)
                val user = endpoint?.optJSONArray("users")?.optJSONObject(0)
                val address = (endpoint?.optString("address").orEmpty().ifBlank { outbound.optString("address") })
                    .removePrefix("[").removeSuffix("]")
                val port = endpoint?.optInt("port")?.takeIf { it > 0 } ?: outbound.optInt("port")
                val id = user?.optString("id").orEmpty().ifBlank { outbound.optString("id") }
                if (address.isBlank() || port !in 1..65535 || id.isBlank()) return null
                proxy.put("server", address)
                    .put("port", port)
                    .put("uuid", id)
                if (protocol == "vless") {
                    user?.optString("flow")?.takeIf(String::isNotBlank)?.let { proxy.put("flow", it) }
                } else {
                    proxy.put("alterId", user?.optInt("alterId", user.optInt("alter_id")) ?: 0)
                        .put("cipher", user?.optString("security").orEmpty().ifBlank { "auto" })
                }
            }
            "trojan" -> {
                val endpoint = settings?.optJSONArray("servers")?.optJSONObject(0)
                val address = (endpoint?.optString("address").orEmpty().ifBlank { outbound.optString("address") })
                    .removePrefix("[").removeSuffix("]")
                val port = endpoint?.optInt("port")?.takeIf { it > 0 } ?: outbound.optInt("port")
                val password = endpoint?.optString("password").orEmpty().ifBlank { outbound.optString("password") }
                if (address.isBlank() || port !in 1..65535 || password.isBlank()) return null
                proxy.put("server", address)
                    .put("port", port)
                    .put("password", password)
            }
            "socks" -> {
                val s = settings ?: return null
                proxy.put("server", s.optString("address").removePrefix("[").removeSuffix("]"))
                    .put("port", s.optInt("port"))
                if (s.has("user") || s.has("pass")) {
                    val username = (s.opt("user") as? String)
                        ?.takeIf(String::isNotBlank) ?: return null
                    val password = s.opt("pass") as? String ?: return null
                    proxy.put("username", username).put("password", password)
                }
                return proxy.takeIf(::isSupportedMihomoProxy)
            }
        }

        val security = stream.optString("security")
        val tls = when (security) {
            "tls" -> stream.optJSONObject("tlsSettings") ?: JSONObject()
            "reality" -> stream.optJSONObject("realitySettings")
            else -> null
        }
        if (tls != null) {
            proxy.put("tls", true)
            val sni = tls.optString("serverName").ifBlank { stream.optString("serverName") }
            if (sni.isNotBlank()) proxy.put("servername", sni)
            val fp = tls.optString("fingerprint").ifBlank { stream.optString("fingerprint") }
            if (fp.isNotBlank()) proxy.put("client-fingerprint", fp)
            // `allowInsecure` from the imported document is deliberately not propagated; see
            // MihomoLinkConfigBuilder. Certificate validation stays on for every imported proxy.
            proxy.put("skip-cert-verify", false)
            tls.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let { proxy.put("alpn", it) }
            if (security == "reality") {
                val reality = JSONObject()
                    .put("public-key", tls.optString("publicKey"))
                tls.optString("shortId").takeIf(String::isNotBlank)?.let { reality.put("short-id", it) }
                proxy.put("reality-opts", reality)
            } else if (security == "tls") {
                val rawEch = tls.optString("echConfigList").trim()
                val ws = stream.optJSONObject("wsSettings")
                val wsHost = ws?.optString("host").orEmpty()
                    .ifBlank { ws?.optJSONObject("headers")?.optString("Host").orEmpty() }
                val hasCustomSniMask = sni.isNotBlank() && wsHost.isNotBlank() && !sni.equals(wsHost, ignoreCase = true)
                val hasFragmentConflict = stream.optJSONObject("finalmask") != null ||
                    tls.optString("cipherSuites").isNotBlank() ||
                    stream.optJSONObject("sockopt")?.optString("dialerProxy").equals("fragment", ignoreCase = true)
                if (rawEch.isNotBlank() && !rawEch.equals("off", ignoreCase = true) && !hasCustomSniMask && !hasFragmentConflict) {
                    val queryMatch = Regex("""^([A-Za-z0-9][A-Za-z0-9.-]{0,252})(?:\+(?:udp|tcp|https|h2c|tls)://.+)?$""")
                        .matchEntire(rawEch)
                    if (queryMatch != null && '.' in queryMatch.groupValues[1]) {
                        proxy.put("ech-opts", JSONObject().put("enable", true).put("query-server-name", queryMatch.groupValues[1]))
                    } else if (Regex("""^[A-Za-z0-9+/=]{16,}$""").matches(rawEch)) {
                        proxy.put("ech-opts", JSONObject().put("enable", true).put("config", rawEch))
                    }
                } else {
                    tls.optString("cipherSuites").trim().takeIf(String::isNotBlank)?.let {
                        proxy.put("cipher-suites", it)
                    }
                    val fmType = stream.optJSONObject("finalmask")
                        ?.optJSONArray("tcp")
                        ?.optJSONObject(0)
                        ?.optString("type")
                        .orEmpty()
                        .ifBlank { stream.optString("finalMask") }
                        .trim()
                    if (fmType.isNotBlank()) {
                        proxy.put("final-mask", WhiteDnsSmartScanner.normalizeRealityFragmentPackets(security, fmType))
                    }
                }
            }
        } else if (protocol == "trojan") {
            proxy.put("tls", false)
        }

        when (stream.optString("network")) {
            "tcp" -> xrayTcpHttpOptions(stream.optJSONObject("tcpSettings"))?.let { options ->
                proxy.put("network", "http").put("http-opts", options)
            }
            "ws", "httpupgrade" -> {
                val upgrade = stream.optString("network") == "httpupgrade"
                val ws = stream.optJSONObject(if (upgrade) "httpupgradeSettings" else "wsSettings") ?: JSONObject()
                val options = JSONObject().put("path", ws.optString("path").ifBlank { "/" })
                val host = ws.optString("host")
                    .ifBlank { ws.optJSONObject("headers")?.optString("Host").orEmpty() }
                if (host.isNotBlank()) options.put("headers", JSONObject().put("Host", host))
                // Mihomo has no `httpupgrade` network: it is ws + v2ray-http-upgrade.
                if (upgrade) options.put("v2ray-http-upgrade", true)
                proxy.put("network", "ws").put("ws-opts", options)
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                proxy.put("network", "grpc").put(
                    "grpc-opts",
                    JSONObject().put("grpc-service-name", grpc.optString("serviceName")),
                )
            }
        }
        return proxy.takeIf(::isSupportedMihomoProxy)
    }

    private fun xrayTcpHttpOptions(tcpSettings: JSONObject?): JSONObject? {
        val header = tcpSettings?.optJSONObject("header") ?: return null
        if (!header.optString("type").equals("http", ignoreCase = true)) return null
        val request = header.optJSONObject("request") ?: JSONObject()
        val paths = request.optJSONArray("path")?.takeIf { it.length() > 0 }
            ?: JSONArray().put("/")
        val headers = JSONObject().apply {
            request.optJSONObject("headers")?.let { source ->
                source.keys().forEach { key ->
                    when (val value = source.opt(key)) {
                        is JSONArray -> if (value.length() > 0) put(key, value)
                        is String -> if (value.isNotBlank()) put(key, JSONArray().put(value))
                    }
                }
            }
        }
        return JSONObject()
            .apply { request.optString("method").takeIf(String::isNotBlank)?.let { put("method", it) } }
            .put("path", paths)
            .apply { if (headers.length() > 0) put("headers", headers) }
    }

    private fun isSupportedMihomoProxy(proxy: JSONObject): Boolean =
        proxy.optString("type").lowercase() in setOf(
            "vless", "vmess", "trojan", "ss", "socks5", "socks",
            "wireguard", "hysteria2", "hy2", "tuic", "anytls",
        ) &&
            proxy.optString("name").isNotBlank() &&
            proxy.optString("server").isNotBlank() &&
            proxy.optInt("port") in 1..65535

}

object MihomoLinkConfigBuilder {
    private const val SELECT_GROUP = "CatClient Proxy"
    private const val AUTO_GROUP = "CatClient Auto"

    fun build(proxies: List<JSONObject>): String {
        val unique = proxies.filter(::isYamlSafeProxy).distinctBy { it.getString("name") }
        require(unique.isNotEmpty()) { "اتصال سازگار با v1 پیدا نشد" }
        val proxyYaml = buildString {
            appendLine("proxies:")
            unique.forEach { proxy -> append(renderProxyYamlBlock(proxy)) }
        }
        return addDefaultGroups(proxyYaml, unique.map { it.getString("name") })
    }

    internal fun renderProxyYamlBlock(proxy: JSONObject): String = buildString {
        val orderedKeys = listOf("name", "type", "server", "port") +
            proxy.keys().asSequence().filterNot { it in setOf("name", "type", "server", "port") }
        orderedKeys.forEachIndexed { propertyIndex, key ->
            val prefix = if (propertyIndex == 0) "  - " else "    "
            append(prefix).append(key).append(": ").appendLine(yamlValue(proxy.get(key)))
        }
    }

    /** Replaces a single proxy entry (`name == targetTag`) in a Mihomo YAML document
     * with the converted form of `updatedShareLink`, keeping `targetTag`, all other
     * proxies and all `proxy-groups:` / `rules:` sections untouched. */
    internal fun replaceProxyInYaml(
        yaml: String,
        targetTag: String,
        updatedShareLink: String,
    ): String? {
        if (targetTag.isBlank()) return null
        val converted = SubConvConverter.convert(updatedShareLink).firstOrNull() ?: return null
        converted.put("name", targetTag)
        if (!isYamlSafeProxy(converted)) return null
        val replacementBlock = renderProxyYamlBlock(converted).trimEnd('\n')

        val out = StringBuilder(yaml.length + 128)
        var inProxiesSection = false
        val currentItemLines = ArrayList<String>()
        var replaced = false

        fun flushCurrentItem() {
            if (currentItemLines.isEmpty()) return
            val normalizedBlock = currentItemLines.mapIndexed { idx, line ->
                if (idx == 0) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("- ")) "    " + trimmed.removePrefix("- ").trimStart() else line
                } else {
                    line
                }
            }.joinToString("\n")
            val itemName = MihomoProxyYaml.parse(normalizedBlock)["name"]?.toString()
            if (!replaced && itemName == targetTag) {
                out.appendLine(replacementBlock)
                replaced = true
            } else {
                currentItemLines.forEach { out.appendLine(it) }
            }
            currentItemLines.clear()
        }

        yaml.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }
            if (indent == 0) {
                flushCurrentItem()
                val topKey = rawLine.trim().substringBefore(":").trim()
                inProxiesSection = (topKey == "proxies")
                out.appendLine(rawLine)
                return@forEach
            }
            if (!inProxiesSection) {
                out.appendLine(rawLine)
                return@forEach
            }
            val content = rawLine.trimStart()
            if (indent == 2 && content.startsWith("- ")) {
                flushCurrentItem()
                currentItemLines += rawLine
            } else if (currentItemLines.isNotEmpty()) {
                currentItemLines += rawLine
            } else {
                out.appendLine(rawLine)
            }
        }
        flushCurrentItem()
        return if (replaced) out.toString() else null
    }

    fun addDefaultGroups(yaml: String, proxyNames: List<String>): String {
        require(proxyNames.isNotEmpty()) { "سابسکریپشن Mihomo پروکسی ندارد" }
        return yaml.trimEnd() + "\n" + defaultGroups(proxyNames)
    }

    fun migrateGeneratedYaml(yaml: String): String {
        val renamed = yaml
            .replace("'CatClient Select'", quote(SELECT_GROUP))
            .replace("cipher: 'null'", "cipher: 'auto'")
        if ("MATCH,$SELECT_GROUP" in renamed) return renamed
        return renamed.trimEnd() + "\nrules:\n  - ${quote("MATCH,$SELECT_GROUP")}\n"
    }

    private fun defaultGroups(proxyNames: List<String>): String = buildString {
        appendLine("proxy-groups:")
        appendLine("  - name: ${quote(SELECT_GROUP)}")
        appendLine("    type: select")
        appendLine("    proxies:")
        appendLine("      - ${quote(AUTO_GROUP)}")
        proxyNames.forEach { appendLine("      - ${quote(it)}") }
        appendLine("  - name: ${quote(AUTO_GROUP)}")
        appendLine("    type: url-test")
        appendLine("    url: 'https://connectivitycheck.gstatic.com/generate_204'")
        appendLine("    interval: 300")
        appendLine("    tolerance: 100")
        appendLine("    proxies:")
        proxyNames.forEach { appendLine("      - ${quote(it)}") }
        appendLine("rules:")
        appendLine("  - ${quote("MATCH,$SELECT_GROUP")}")
    }

    /**
     * Proxy objects arrive verbatim from an imported Clash or Xray JSON document, and this builder
     * emits their keys and values as YAML lines. A line break anywhere in that data would let a
     * hostile subscription append arbitrary directives — extra proxies, rules, `skip-cert-verify`
     * — to the profile the core then runs. Quoting alone is not enough because a YAML scalar may
     * legally span lines, so a proxy carrying any line-structure character is dropped instead.
     */
    private fun isYamlSafeProxy(proxy: JSONObject): Boolean =
        proxy.keys().asSequence().all { key -> isYamlSafeText(key) && isYamlSafeValue(proxy.get(key)) }

    private fun isYamlSafeValue(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> true
        is String -> isYamlSafeText(value)
        is JSONObject -> value.keys().asSequence()
            .all { key -> isYamlSafeText(key) && isYamlSafeValue(value.get(key)) }
        is JSONArray -> (0 until value.length()).all { index -> isYamlSafeValue(value.get(index)) }
        is Map<*, *> -> value.entries.all { (key, item) ->
            isYamlSafeText(key.toString()) && isYamlSafeValue(item)
        }
        is Iterable<*> -> value.all(::isYamlSafeValue)
        else -> isYamlSafeText(value.toString())
    }

    private fun isYamlSafeText(value: String): Boolean = value.none { it == '\n' || it == '\r' }

    private fun yamlValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> quote(value)
        is JSONObject -> value.keys().asSequence().joinToString(prefix = "{", postfix = "}") { key ->
            "${quote(key)}: ${yamlValue(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
            yamlValue(value.get(index))
        }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${quote(key.toString())}: ${yamlValue(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::yamlValue)
        else -> value.toString()
    }

    private fun quote(value: String): String = "'${value.replace("'", "''")}'"
}

class UserSubscriptionManager(
    context: Context,
    private val store: SubscriptionStore = SubscriptionStore(context),
) {
    private val appContext = context.applicationContext
    private val snapshots = SubscriptionSnapshotResolver(
        persistence = AndroidSubscriptionSnapshotAdapter(context, store),
    )

    fun list(): List<UserSubscription> = store.readUserSubscriptions()

    fun selectedId(): String = store.readSelectedSubscriptionId()

    fun select(id: String) = store.saveSelectedSubscriptionId(id)

    suspend fun test(input: String): ImportedUserSubscription = compile(input).let { compiled ->
        ImportedUserSubscription(
            yaml = compiled.snapshot.rawConfig,
            format = compiled.format,
            connectionCount = compiled.snapshot.catalog.profiles.size,
        )
    }

    suspend fun add(name: String, input: String): UserSubscription {
        val cleanName = name.trim().take(60)
        require(cleanName.isNotBlank()) { "نام الزامی است" }
        val cleanInput = input.trim()
        val compiled = compile(cleanInput)
        val fetchedAt = compiled.snapshot.catalog.fetchedAt
        val item = UserSubscription(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            input = cleanInput,
            format = compiled.format,
            connectionCount = compiled.snapshot.catalog.profiles.size,
            updatedAt = fetchedAt,
            fetchedAt = fetchedAt,
            sourceKind = UserSubscriptionSourceKind.fromWireName(null, cleanInput),
        )
        store.saveUserSubscription(item, compiled.snapshot.rawConfig)
        return item
    }

    suspend fun update(id: String, name: String, input: String): UserSubscription {
        val existing = store.readUserSubscription(id) ?: throw IOException("سابسکریپشن دیگر وجود ندارد")
        val cleanName = name.trim().take(60)
        require(cleanName.isNotBlank()) { "نام الزامی است" }
        val cleanInput = input.trim()
        val compiled = compile(cleanInput)
        val fetchedAt = compiled.snapshot.catalog.fetchedAt
        return existing.copy(
            name = cleanName,
            input = cleanInput,
            format = compiled.format,
            connectionCount = compiled.snapshot.catalog.profiles.size,
            updatedAt = fetchedAt,
            fetchedAt = fetchedAt,
            sourceKind = UserSubscriptionSourceKind.fromWireName(null, cleanInput),
            lastError = "",
        ).also { store.saveUserSubscription(it, compiled.snapshot.rawConfig) }
    }

    suspend fun refresh(id: String): UserSubscription {
        snapshots.resolve(id, SubscriptionRefreshPolicy.Force)
        return store.readUserSubscription(id)
            ?: throw IOException("سابسکریپشن دیگر وجود ندارد")
    }

    fun delete(id: String) = store.deleteUserSubscription(id)

    fun cachedSnapshot(id: String): MihomoSubscriptionSnapshot? = snapshots.cached(id)

    /** Replaces a single proxy (`targetTag`) in the cached YAML of `subscriptionId`
     * with `updatedShareLink`, persists the updated snapshot, and returns the
     * updated [ConnectionProfile] so the caller can immediately re-test it. */
    fun replaceProfileShareLink(
        subscriptionId: String,
        targetTag: String,
        updatedShareLink: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ConnectionProfile? {
        val adapter = AndroidSubscriptionSnapshotAdapter(appContext, store)
        val entry = adapter.read(subscriptionId) ?: return null
        val currentYaml = entry.cachedYaml?.takeIf(String::isNotBlank) ?: return null
        val updatedYaml = MihomoLinkConfigBuilder.replaceProxyInYaml(currentYaml, targetTag, updatedShareLink)
            ?: return null
        val snapshot = runCatching { MihomoConfigParser.parse(updatedYaml, nowMs) }.getOrNull() ?: return null
        if (snapshot.catalog.profiles.isEmpty()) return null
        val format = store.readUserSubscription(subscriptionId)?.format ?: UserSubscriptionFormat.Mihomo
        adapter.save(subscriptionId, CompiledSubscription(format, snapshot))
        return snapshot.catalog.profiles.firstOrNull { it.tag == targetTag }
    }

    private suspend fun compile(input: String): CompiledSubscription {
        val loaded = SubscriptionSourceLoader.loadDetailed(userSubscriptionSource(input))
        // Panels (Cat Panel included) report quota through this header; keep the
        // latest numbers per Subscription Source so the list can show usage.
        SubscriptionUsagePolicy.parse(loaded.usageHeader)?.let { usage ->
            SubscriptionUsageStore(appContext).save(input, usage)
        }
        return SubscriptionCompiler.compile(loaded.content, System.currentTimeMillis())
    }
}
