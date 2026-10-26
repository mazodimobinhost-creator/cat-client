/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Kotlin adaptation of the VLESS, VMess, Trojan, Hysteria2, Shadowsocks, and SOCKS conversion
 * flow from https://github.com/SubConv/SubConv.
 */
package com.cat.client

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONArray
import org.json.JSONObject

object SubConvConverter {
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
    )

    private val EXTRACTABLE_SCHEMES = listOf(
        "vless://", "vmess://", "trojan://", "anytls://", "hysteria2://", "hy2://",
        "socks://", "socks5://", "ss://", "wireguard://", "wg://", "awg://", "amneziawg://",
        "fenix://", "firefoxvpn://", "paqet://", "paqetng://", "patth://", "patthng://",
        "dnstt://", "slipstream://", "slipnet://", "doh://",
    )

    private fun extractShareUriFromLine(raw: String): String? {
        val trimmed = raw.trim().trimStart('\uFEFF')
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) return null
        if ("://" !in trimmed) return null
        val firstSchemeBefore = trimmed.substringBefore("://")
        if (firstSchemeBefore.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
            return trimmed
        }
        // Extract embedded link when user copied Telegram caption text before the URI
        val lower = trimmed.lowercase()
        val matchIdx = EXTRACTABLE_SCHEMES
            .map { scheme -> lower.indexOf(scheme) }
            .filter { it >= 0 }
            .minOrNull() ?: return null
        val candidate = trimmed.substring(matchIdx).trim()
        return if ('#' in candidate) {
            val beforeHash = candidate.substringBefore('#').trim().split(Regex("\\s+")).firstOrNull() ?: return null
            val afterHash = candidate.substringAfter('#').trim()
            if (afterHash.isNotEmpty()) "$beforeHash#$afterHash" else beforeHash
        } else {
            candidate.split(Regex("\\s+")).firstOrNull()
        }
    }

    fun convert(input: String): List<JSONObject> {
        val data = decodeBase64Text(input) ?: input
        val names = NameRegistry()
        val proxies = data.lineSequence().mapNotNull { raw ->
            val line = extractShareUriFromLine(raw) ?: return@mapNotNull null
            try {
                when (line.substringBefore("://").lowercase()) {
                    "vless" -> parseVless(line, names)
                    "vmess" -> parseVmess(line, names)
                    "trojan" -> parseTrojan(line, names)
                    "anytls" -> parseAnyTls(line, names)
                    "hysteria2", "hy2" -> parseHysteria2(line, names)
                    "socks", "socks5" -> parseSocks(line, names)
                    "ss" -> parseShadowsocks(line, names)
                    "wireguard", "wg", "awg", "amneziawg" -> parseWireGuard(line, names)
                    "fenix", "firefoxvpn" -> parseFenixVpn(line, names)
                    in PaqetEngine.SUPPORTED_SCHEMES -> parsePaqet(line, names)
                    in WhiteDnsSmartScanner.SUPPORTED_TUNNEL_SCHEMES -> parseWhiteDnsTunnel(line, names)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }.toList()
        if (proxies.isEmpty()) {
            PaqetEngine.parsePaqetFileOrUri(data)?.let { paqetCfg ->
                val obj = PaqetEngine.toMihomoProxyJson(paqetCfg)
                obj.put("name", names.register(obj.optString("name", "PaqetNG")))
                return listOf(obj)
            }
        }
        require(proxies.isNotEmpty()) { "No valid proxies found" }
        return proxies
    }

    private fun parseVless(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val query = parseQuery(uri.rawQuery)
        val proxy = parseVShare(uri, query, names, "vless", decodeHost = true)
        query["flow"]?.takeIf(String::isNotBlank)?.let { proxy.put("flow", it.lowercase()) }
        query["encryption"]?.takeIf(String::isNotBlank)?.let { proxy.put("encryption", it) }
        return proxy
    }

    private fun parseVShare(
        uri: URI,
        query: Map<String, String>,
        names: NameRegistry,
        type: String,
        decodeHost: Boolean = false,
    ): JSONObject {
        val endpoint = endpoint(uri, decodeHost)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val proxy = JSONObject()
            .put("name", names.register(name(uri)))
            .put("type", type)
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("uuid", endpoint.userInfo)
            .put("udp", true)

        val security = query["security"].orEmpty().lowercase()
        val tlsEnabled = security.endsWith("tls") || security == "reality"
        if (tlsEnabled) {
            proxy.put("tls", true)
                .put("skip-cert-verify", false)
                .put("client-fingerprint", query["fp"].orEmpty().ifBlank { "chrome" })
            query["alpn"]?.takeIf(String::isNotBlank)?.let { proxy.put("alpn", it.split(',')) }
            query["pcs"]?.takeIf(String::isNotBlank)?.let { proxy.put("fingerprint", it) }
        }
        (query["sni"] ?: query["servername"])?.takeIf(String::isNotBlank)?.let { proxy.put("servername", it) }
        val ech = echOptions(query, tlsEnabled = security.endsWith("tls"))
        if (ech != null) {
            proxy.put("ech-opts", ech)
        } else if (tlsEnabled) {
            (query["cs"] ?: query["cipherSuites"])?.takeIf(String::isNotBlank)?.let { proxy.put("cipher-suites", it) }
            (query["fm"] ?: query["finalMask"])?.takeIf(String::isNotBlank)?.let {
                proxy.put("final-mask", WhiteDnsSmartScanner.normalizeRealityFragmentPackets(security, it))
            }
        }
        query["pbk"]?.takeIf(String::isNotBlank)?.let { publicKey ->
            proxy.put(
                "reality-opts",
                JSONObject().put("public-key", publicKey).apply {
                    query["sid"]?.takeIf(String::isNotBlank)?.let { put("short-id", it) }
                },
            )
        }

        when (query["packetEncoding"].orEmpty()) {
            "packet" -> proxy.put("packet-addr", true)
            "none" -> Unit
            else -> proxy.put("xudp", true)
        }

        var network = query["type"].orEmpty().lowercase().ifBlank { "tcp" }
        val fakeType = query["headerType"].orEmpty().lowercase()
        if (network == "tcp" && fakeType == "http") network = "http"
        else if (network == "http") network = "h2"
        proxy.put("network", network)

        when (network) {
            "http" -> proxy.put("http-opts", httpOptions(query))
            "h2" -> proxy.put("h2-opts", vShareH2Options(query))
            "ws", "httpupgrade" -> proxy.put("ws-opts", vShareWsOptions(query, network))
            "grpc" -> proxy.put(
                "grpc-opts",
                JSONObject().put("grpc-service-name", query["serviceName"].orEmpty()),
            )
            "xhttp" -> proxy.put("xhttp-opts", xhttpOptions(query))
        }
        return proxy
    }

    private fun parseVmess(line: String, names: NameRegistry): JSONObject {
        val body = line.removePrefix("vmess://")
        val decoded = decodeBase64Text(body)
        if (decoded == null) return parseVmessAead(line, names)
        val values = try {
            JSONObject(decoded)
        } catch (_: Exception) {
            throw ParseError()
        }
        val server = values.optString("add")
        val uuid = values.optString("id")
        val port = jsonInt(values, "port")
        if (server.isBlank() || uuid.isBlank() || port !in 1..65535) throw ParseError()

        val proxy = JSONObject()
            .put("name", names.register(values.optString("ps")))
            .put("type", "vmess")
            .put("server", server)
            .put("port", port)
            .put("uuid", uuid)
            .put("alterId", jsonInt(values, "aid", 0))
            .put(
                "cipher",
                (values.opt("scy") as? String)
                    ?.takeUnless { it.equals("null", ignoreCase = true) }
                    .orEmpty()
                    .ifBlank { "auto" },
            )
            .put("udp", true)
            .put("xudp", true)
            .put("tls", false)
            .put("skip-cert-verify", false)
        values.optString("sni").takeIf(String::isNotBlank)?.let { proxy.put("servername", it) }
        val tls = values.optString("tls")
        if (tls.lowercase().endsWith("tls")) {
            proxy.put("tls", true)
            splitCsv(values.optString("alpn"))?.let { proxy.put("alpn", it) }
        }

        var network = values.optString("net").lowercase()
        if (values.optString("type").lowercase() == "http") network = "http"
        else if (network == "http") network = "h2"
        if (network.isNotBlank()) proxy.put("network", network)
        val host = values.optString("host").takeIf(String::isNotBlank)
        val path = values.optString("path").takeIf(String::isNotBlank)
        when (network) {
            "http" -> proxy.put("http-opts", httpOptions(path, host))
            "h2" -> proxy.put(
                "h2-opts",
                JSONObject().apply {
                    path?.let { put("path", it) }
                    put("headers", JSONObject().apply {
                        host?.let { put("Host", JSONArray().put(it)) }
                    })
                },
            )
            "ws", "httpupgrade" -> proxy.put("ws-opts", vmessWsOptions(path, host, network))
            "grpc" -> proxy.put("grpc-opts", JSONObject().put("grpc-service-name", path.orEmpty()))
        }
        return proxy
    }

    private fun parseVmessAead(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val query = parseQuery(uri.rawQuery)
        return parseVShare(uri, query, names, "vless").apply {
            put("type", "vmess")
            remove("flow")
            remove("encryption")
            put("alterId", 0)
            put("cipher", query["encryption"].orEmpty().ifBlank { "auto" })
            if (!has("udp")) put("udp", true)
            if (!has("tls")) put("tls", false)
            if (!has("xudp")) put("xudp", true)
            if (!has("skip-cert-verify")) put("skip-cert-verify", false)
        }
    }

    private fun parseTrojan(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val query = parseQuery(uri.rawQuery)
        return JSONObject()
            .put("name", names.register(name(uri)))
            .put("type", "trojan")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", endpoint.userInfo)
            .put("udp", true)
            .apply {
                // `allowInsecure` is honoured as an explicit false only. Letting a share link turn
                // certificate validation off would hand anyone who can hand the user a link the
                // ability to read the tunnelled traffic.
                put("skip-cert-verify", false)
                val isPlain = query["security"].equals("none", ignoreCase = true)
                if (isPlain) {
                    put("tls", false)
                }
                (query["sni"] ?: query["servername"])?.takeIf(String::isNotBlank)?.let { put("sni", it) }
                val ech = echOptions(query, tlsEnabled = !isPlain)
                if (ech != null) {
                    put("ech-opts", ech)
                } else if (!isPlain) {
                    (query["cs"] ?: query["cipherSuites"])?.takeIf(String::isNotBlank)?.let { put("cipher-suites", it) }
                    (query["fm"] ?: query["finalMask"])?.takeIf(String::isNotBlank)?.let { put("final-mask", it) }
                }
                query["alpn"]?.takeIf(String::isNotBlank)?.let { put("alpn", it.split(',')) }
                val network = query["type"].orEmpty().lowercase()
                if (network.isNotBlank()) put("network", network)
                when (network) {
                    // Same options as the vless shape — including the Host
                    // header, which this branch used to omit (the trojan half of
                    // every panel subscription then never connected).
                    "ws", "httpupgrade" -> put("ws-opts", vShareWsOptions(query, network))
                    "grpc" -> put(
                        "grpc-opts",
                        JSONObject().put("grpc-service-name", query["serviceName"].orEmpty()),
                    )
                }
                put("client-fingerprint", query["fp"].orEmpty().ifBlank { "chrome" })
                query["pcs"]?.takeIf(String::isNotBlank)?.let { put("fingerprint", it) }
            }
    }

    private fun parseAnyTls(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val query = parseQuery(uri.rawQuery)
        val sni = query["sni"].orEmpty().ifBlank { query["peer"].orEmpty() }
        return JSONObject()
            .put("name", names.register(name(uri)))
            .put("type", "anytls")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", endpoint.userInfo)
            .put("udp", true)
            .put("skip-cert-verify", false)
            .apply {
                sni.takeIf(String::isNotBlank)?.let { put("sni", it) }
                query["hpkp"]?.takeIf(String::isNotBlank)?.let { put("fingerprint", it) }
            }
    }

    private fun parseHysteria2(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val query = parseQuery(uri.rawQuery)
        return JSONObject()
            .put("name", names.register(name(uri)))
            .put("type", "hysteria2")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", endpoint.userInfo)
            .put("skip-cert-verify", false)
            .apply {
                listOf("obfs", "obfs-password", "sni").forEach { key ->
                    query[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
                }
            }
    }

    private fun parseSocks(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val authority = uri.rawAuthority ?: throw ParseError()
        val at = authority.lastIndexOf('@')
        val endpoint = parseHostPort(authority.substring(at + 1)) ?: throw ParseError()
        val query = parseQuery(uri.rawQuery)
        val proxyName = runCatching { name(uri) }.getOrElse { throw ParseError() }
            .ifBlank { "${endpoint.host}:${endpoint.port}" }
        val proxy = JSONObject()
            .put("name", names.register(proxyName))
            .put("type", "socks5")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("udp", query["udp"]?.lowercase() !in setOf("false", "0"))
        if (at >= 0) {
            val (username, password) = socksCredentials(authority.substring(0, at))
            proxy.put("username", username)
            password?.let { proxy.put("password", it) }
        }
        return proxy
    }

    private fun parseWhiteDnsTunnel(line: String, names: NameRegistry): JSONObject {
        val parsed = WhiteDnsSmartScanner.parseTunnelUri(line) ?: throw ParseError()
        val localPort = WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = false)
        return JSONObject()
            .put("name", names.register(parsed.remark))
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", localPort)
            .put("udp", true)
            .put("whitedns-uri", parsed.rawUri)
    }

    private fun parseFenixVpn(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val query = parseQuery(uri.rawQuery)
        val rawPort = uri.port.takeIf { it in 1..65535 } ?: FirefoxVpnEngine.activeProxyPort()
        val countryCode = query["country"].orEmpty().uppercase().ifBlank { "DE" }
        val defaultName = "🦊 Firefox VPN ($countryCode)"
        return JSONObject()
            .put("name", names.register(name(uri).ifBlank { defaultName }))
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", rawPort)
            .put("udp", true)
            .put("fenix-uri", line.trim())
    }

    private fun parsePaqet(line: String, names: NameRegistry): JSONObject {
        val cfg = PaqetEngine.parsePaqetUri(line) ?: throw ParseError()
        val localPort = PaqetEngine.activeProxyPort()
        return JSONObject()
            .put("name", names.register(cfg.remark.ifBlank { "PaqetNG ${cfg.serverHost}:${cfg.serverPort}" }))
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", localPort)
            .put("udp", true)
            .put("paqet-uri", PaqetEngine.buildPaqetUri(cfg))
    }

    private fun socksCredentials(rawUserInfo: String): Pair<String, String?> {
        val separator = rawUserInfo.indexOf(':')
        if (separator >= 0) {
            val username = decodeSocksUserInfo(rawUserInfo.substring(0, separator))
                .takeIf(String::isNotBlank) ?: throw ParseError()
            return username to decodeSocksUserInfo(rawUserInfo.substring(separator + 1))
        }

        val userInfo = decodeSocksUserInfo(rawUserInfo).takeIf(String::isNotBlank) ?: throw ParseError()
        val decoded = decodeBase64Text(userInfo)
        val decodedSeparator = decoded?.indexOf(':') ?: -1
        if (decoded != null && decodedSeparator >= 0) {
            val username = decoded.substring(0, decodedSeparator).takeIf(String::isNotBlank)
                ?: throw ParseError()
            return username to decoded.substring(decodedSeparator + 1)
        }
        return userInfo to null
    }

    private fun decodeSocksUserInfo(value: String): String = try {
        decode(value.replace("+", "%2B"))
    } catch (_: IllegalArgumentException) {
        throw ParseError()
    }

    private fun parseShadowsocks(line: String, names: NameRegistry): JSONObject {
        val content = line.removePrefix("ss://")
        val fragmentSplit = content.split('#', limit = 2)
        val querySplit = fragmentSplit[0].split('?', limit = 2)
        val query = parseQuery(querySplit.getOrNull(1))
        var authority = querySplit[0]
        if ('@' !in authority) authority = decodeBase64Text(authority) ?: throw ParseError()
        val at = authority.lastIndexOf('@')
        if (at < 1) throw ParseError()
        val rawCredentials = authority.substring(0, at)
        val credentials = if (':' in rawCredentials) {
            decode(rawCredentials)
        } else {
            decodeBase64Text(rawCredentials) ?: throw ParseError()
        }
        val separator = credentials.indexOf(':')
        if (separator < 1) throw ParseError()
        val endpoint = parseHostPort(authority.substring(at + 1)) ?: throw ParseError()
        val proxy = JSONObject()
            .put("name", names.register(decode(fragmentSplit.getOrNull(1).orEmpty())))
            .put("type", "ss")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", credentials.substring(separator + 1))
            .put("cipher", credentials.substring(0, separator))
            .put("udp", true)
        query["plugin"]?.let { plugin -> applyPlugin(proxy, plugin) }
        if (query["udp-over-tcp"] == "true" || query["uot"] == "1") {
            proxy.put("udp-over-tcp", true)
        }
        return proxy
    }

    private fun parseWireGuard(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        val query = parseQuery(uri.rawQuery)
        val isAutoKey = endpoint.userInfo.equals("auto", ignoreCase = true)
        val rawPub = (query["publickey"] ?: query["public-key"])?.takeIf(String::isNotBlank)
        val publicKey = when {
            rawPub != null && !rawPub.equals("auto", ignoreCase = true) -> rawPub
            isAutoKey -> "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
            else -> throw ParseError()
        }
        val addresses = splitCsv(query["address"].orEmpty()).orEmpty()
            .map { it.substringBefore('/').trim() }
        val ipv4 = addresses.firstOrNull { ':' !in it }
            ?: if (isAutoKey) "172.16.0.2" else throw ParseError()
        val ipv6 = addresses.firstOrNull { ':' in it }
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val privateKey = if (isAutoKey) "YAutoGeneratedClientPrivateKeyForWarpTunnel=" else endpoint.userInfo

        return JSONObject()
            .put("name", names.register(name(uri).ifBlank { "WireGuard" }))
            .put("type", "wireguard")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("ip", ipv4)
            .put("ip-version", if (ipv6 == null) "ipv4" else "ipv4-prefer")
            .put("private-key", privateKey)
            .put("public-key", publicKey)
            .put(
                "allowed-ips",
                JSONArray(splitCsv(query["allowed-ips"] ?: query["allowedips"].orEmpty())
                    ?: listOf("0.0.0.0/0", "::/0")),
            )
            .put("udp", true)
            .apply {
                ipv6?.let { put("ipv6", it) }
                splitCsv(query["reserved"].orEmpty())
                    ?.mapNotNull(String::toIntOrNull)
                    ?.takeIf { it.size == 3 && it.all { byte -> byte in 0..255 } }
                    ?.let { put("reserved", JSONArray(it)) }
                query["mtu"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("mtu", it) }
                (query["keepalive"] ?: query["persistent-keepalive"])
                    ?.toIntOrNull()?.takeIf { it > 0 }?.let { put("persistent-keepalive", it) }
                (query["presharedkey"] ?: query["pre-shared-key"])
                    ?.takeIf(String::isNotBlank)?.let { put("pre-shared-key", it) }
                val isAmneziaScheme = line.startsWith("awg://", ignoreCase = true) ||
                    line.startsWith("amneziawg://", ignoreCase = true)
                wireGuardAmneziaOptions(query, isAmneziaScheme)?.let { put("amnezia-wg-option", it) }
                wireGuardDpiOptions(query, isAmneziaScheme)?.let { put("wireguard-dpi-option", it) }
                wireGuardIpStackOptions(query)?.let { put("ip-stack", it) }
            }
    }

    private fun wireGuardAmneziaOptions(
        query: Map<String, String>,
        defaultAmnezia: Boolean = false,
    ): JSONObject? = JSONObject().apply {
        listOf("version", "jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4").forEach { key ->
            query[key]?.toIntOrNull()?.let { put(key, it) }
        }
        if (defaultAmnezia && !has("jc")) {
            put("jc", 5)
            put("jmin", 50)
            put("jmax", 1000)
        }
        listOf(
            "header-protection-key",
            "content-padding-addition",
            "rekey-after-time",
            "rekey-timeout",
            "reject-after-time",
            "keepalive-timeout",
            "max-handshake-attempts",
            "i1",
            "i2",
            "i3",
            "i4",
            "i5",
        ).forEach { key ->
            query[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
        }
        listOf("random-trailers", "disable-cookies").forEach { key ->
            query[key]?.lowercase()?.let { value ->
                if (value == "true" || value == "false") put(key, value.toBoolean())
            }
        }
    }.takeIf { it.length() > 0 }

    private fun wireGuardDpiOptions(
        query: Map<String, String>,
        defaultAmnezia: Boolean = false,
    ): JSONObject? {
        val legacyNoiseEnabled = !query["wnoise"].equals("none", ignoreCase = true) &&
            ("wnoisecount" in query || "wpayloadsize" in query || defaultAmnezia)
        val payloadRange = query["wpayloadsize"]?.let(::integerRange)
        val explicitCount = query["fake-count"]?.toIntOrNull()?.takeIf { it in 1..20 }
        val count = explicitCount
            ?: query["wnoisecount"]?.let(::integerRange)?.first?.coerceAtMost(20)?.takeIf { it > 0 }
            ?: 4.takeIf { defaultAmnezia }
        val minSize = query["fake-min-size"]?.toIntOrNull()?.takeIf { it in 1..1280 }
            ?: payloadRange?.first?.takeIf { legacyNoiseEnabled && it in 1..1280 }
            ?: 40.takeIf { defaultAmnezia }
        val maxSize = query["fake-max-size"]?.toIntOrNull()?.takeIf { it in 1..1280 }
            ?: payloadRange?.second?.takeIf { legacyNoiseEnabled && it in 1..1280 }
            ?: 120.takeIf { defaultAmnezia }
        val ttl = query["fake-ttl"]?.toIntOrNull()?.takeIf { it in 0..255 }
            ?: 3.takeIf { legacyNoiseEnabled }
        // ponytail: Mihomo has no noise header/delay fields; preserve its supported count/size/TTL subset.
        return JSONObject().apply {
            count?.takeIf { explicitCount != null || legacyNoiseEnabled }?.let { put("fake-count", it) }
            minSize?.let { put("fake-min-size", it) }
            maxSize?.takeIf { minSize == null || it >= minSize }?.let { put("fake-max-size", it) }
            ttl?.let { put("fake-ttl", it) }
        }.takeIf { it.length() > 0 }
    }

    private fun wireGuardIpStackOptions(query: Map<String, String>): JSONObject? = JSONObject().apply {
        (query["ip-stack"] ?: query["ip-stack-mode"])
            ?.takeIf(String::isNotBlank)?.let { put("mode", it) }
        query["congestion-controller"]?.takeIf(String::isNotBlank)
            ?.let { put("congestion-controller", it) }
    }.takeIf { it.length() > 0 }

    private fun integerRange(value: String): Pair<Int, Int>? {
        val parts = value.split('-', limit = 2).map(String::trim)
        val min = parts.firstOrNull()?.toIntOrNull() ?: return null
        val max = parts.getOrNull(1)?.toIntOrNull() ?: min
        return (min to max).takeIf { min <= max }
    }

    private fun applyPlugin(proxy: JSONObject, plugin: String) {
        val segments = plugin.split(';')
        val name = segments.firstOrNull().orEmpty()
        val options = segments.drop(1).mapNotNull { segment ->
            val split = segment.split('=', limit = 2)
            split.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val output = JSONObject()
        when {
            "obfs" in name -> {
                options["obfs"]?.takeIf(String::isNotBlank)?.let { output.put("mode", it) }
                options["obfs-host"]?.takeIf(String::isNotBlank)?.let { output.put("host", it) }
                proxy.put("plugin", "obfs")
            }
            "v2ray-plugin" in name -> {
                listOf("mode", "host", "path").forEach { key ->
                    options[key]?.takeIf(String::isNotBlank)?.let { output.put(key, it) }
                }
                if ("tls" in plugin) output.put("tls", true)
                proxy.put("plugin", "v2ray-plugin")
            }
            else -> return
        }
        if (output.length() > 0) proxy.put("plugin-opts", output)
    }

    private fun httpOptions(query: Map<String, String>): JSONObject =
        httpOptions(query["path"].orEmpty().ifBlank { "/" }, query["host"]).apply {
            query["method"]?.takeIf(String::isNotBlank)?.let { put("method", it) }
        }

    private fun httpOptions(path: String?, host: String?): JSONObject = JSONObject()
        .put("path", JSONArray().put(path.orEmpty().ifBlank { "/" }))
        .put("headers", JSONObject().apply {
            host?.takeIf(String::isNotBlank)?.let { put("Host", JSONArray().put(it)) }
        })

    private fun vShareH2Options(query: Map<String, String>): JSONObject = JSONObject()
        .put("path", JSONArray().put(query["path"].orEmpty().ifBlank { "/" }))
        .put("headers", JSONObject())
        .apply {
            query["host"]?.takeIf(String::isNotBlank)?.let { put("host", JSONArray().put(it)) }
        }

    /**
     * WebSocket / HTTPUpgrade options for a share link.
     *
     * The `Host` header carries the share link's `host` parameter — for a
     * Cloudflare-fronted deployment that is the worker/panel hostname, and it is
     * what routes the request to the right worker. Omitting it (as this used to
     * for the trojan shape) means every request lands on the wrong place and
     * the config simply never connects.
     *
     * Early data follows the Xray convention: the size can sit at the top level
     * (`&ed=2048`, how v2rayN writes it) or inside the path
     * (`&path=%2F%3Fed%3D2048`, which Cat Panel and BPB emit). Both shapes must
     * enable Sec-WebSocket-Protocol early data. The `ed` parameter is stripped
     * from the transmitted path (the bytes travel in the header instead) while
     * every other path parameter — e.g. `proxyip` — is preserved verbatim.
     * Garbled values are ignored rather than failing the whole link.
     */
    private fun vShareWsOptions(query: Map<String, String>, network: String): JSONObject {
        val rawPath = query["path"].orEmpty()
        // Paths handed out before the worker fix look like
        // `/vl/<seed>?ed=2560?proxyip=host` — the second '?' is a query
        // separator in disguise, so normalise it before parsing; otherwise the
        // relay override would be swallowed by (and lost from) the ed value.
        val pathParams = if ('?' in rawPath) {
            parseQuery(rawPath.substringAfter('?').replace('?', '&'))
        } else {
            emptyMap()
        }
        val sentPath = if (pathParams.containsKey("ed")) {
            val remaining = pathParams.filterKeys { it != "ed" }
                .entries.joinToString("&") { "${encodePathParam(it.key)}=${encodePathParam(it.value)}" }
            rawPath.substringBefore('?') + if (remaining.isEmpty()) "" else "?$remaining"
        } else {
            rawPath
        }
        val earlyDataRaw = query["ed"]?.takeIf(String::isNotBlank) ?: pathParams["ed"]
        return JSONObject()
            .put("path", sentPath)
            .put(
                "headers",
                JSONObject()
                    .put("User-Agent", userAgents.random())
                    .put("Host", query["host"].orEmpty()),
            )
            .apply {
                earlyDataRaw?.takeIf(String::isNotBlank)?.let { raw ->
                    val earlyData = raw.takeWhile { it.isDigit() }.toIntOrNull()
                    if (earlyData != null && earlyData > 0) {
                        if (network == "ws") {
                            put("max-early-data", earlyData)
                            put("early-data-header-name", "Sec-WebSocket-Protocol")
                        } else {
                            put("v2ray-http-upgrade-fast-open", true)
                        }
                    }
                }
                query["eh"]?.takeIf(String::isNotBlank)?.let { put("early-data-header-name", it) }
            }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun encodePathParam(value: String): String =
        encode(value).replace("%3A", ":").replace("%5B", "[").replace("%5D", "]")

    /**
     * Parses `ech` from VLESS/Trojan share links (e.g. `ech=cloudflare-ech.com+udp://1.1.1.1`),
     * while enforcing mutual exclusion with Advanced Optimization (`fm` fragment JSON,
     * `cs` custom cipher suites, or a `TLS Mask` where `sni` is spoofed away from `host`).
     * Enabling ECH and Fragment/TLS-Mask simultaneously breaks TLS 1.3 Inner ClientHello.
     */
    private fun echOptions(query: Map<String, String>, tlsEnabled: Boolean): JSONObject? {
        if (!tlsEnabled) return null
        val rawEch = query["ech"]?.trim()?.takeIf { it.isNotEmpty() && !it.equals("false", ignoreCase = true) }
            ?: return null
        val hasAdvancedFragmentOrCipher = !query["fm"].isNullOrBlank() || !query["cs"].isNullOrBlank()
        val host = query["host"]?.trim().orEmpty()
        val sni = (query["sni"] ?: query["servername"])?.trim().orEmpty()
        val hasTlsMaskSniMismatch = host.isNotEmpty() && sni.isNotEmpty() && !host.equals(sni, ignoreCase = true)
        if (hasAdvancedFragmentOrCipher || hasTlsMaskSniMismatch) {
            return null
        }
        val queryServerName = rawEch.substringBefore('+').substringBefore(' ').trim()
            .takeIf { it.isNotEmpty() && !it.equals("true", ignoreCase = true) && '.' in it }
        return JSONObject().put("enable", true).apply {
            if (queryServerName != null) put("query-server-name", queryServerName)
        }
    }

    private fun vmessWsOptions(path: String?, host: String?, network: String): JSONObject {
        var outputPath = path.orEmpty().ifBlank { "/" }
        val query = parseQuery(outputPath.substringAfter('?', ""))
        val output = JSONObject()
            .put("path", outputPath)
            .put("headers", JSONObject().apply {
                host?.takeIf(String::isNotBlank)?.let { put("Host", it) }
            })
        query["ed"]?.toIntOrNull()?.let { earlyData ->
            if (network == "ws") {
                output.put("max-early-data", earlyData)
                    .put("early-data-header-name", "Sec-WebSocket-Protocol")
            } else {
                output.put("v2ray-http-upgrade-fast-open", true)
            }
            outputPath = removeQueryParameter(outputPath, "ed")
            output.put("path", outputPath)
        }
        query["eh"]?.takeIf(String::isNotBlank)?.let { output.put("early-data-header-name", it) }
        return output
    }

    private fun xhttpOptions(query: Map<String, String>): JSONObject = JSONObject().apply {
        listOf("path", "host", "mode").forEach { key ->
            query[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
        }
        query["extra"]?.let { raw ->
            val extra = runCatching { JSONObject(raw) }.getOrNull() ?: return@let
            applyXhttpExtra(this, extra)
        }
    }

    private fun applyXhttpExtra(target: JSONObject, extra: JSONObject) {
        if (extra.optBoolean("noGRPCHeader")) target.put("no-grpc-header", true)
        val stringFields = mapOf(
            "xPaddingBytes" to "x-padding-bytes",
            "xPaddingKey" to "x-padding-key",
            "xPaddingHeader" to "x-padding-header",
            "xPaddingPlacement" to "x-padding-placement",
            "xPaddingMethod" to "x-padding-method",
            "uplinkHttpMethod" to "uplink-http-method",
            "sessionPlacement" to "session-placement",
            "sessionKey" to "session-key",
            "seqPlacement" to "seq-placement",
            "seqKey" to "seq-key",
            "uplinkDataPlacement" to "uplink-data-placement",
            "uplinkDataKey" to "uplink-data-key",
        )
        stringFields.forEach { (source, destination) ->
            extra.optString(source).takeIf(String::isNotBlank)?.let { target.put(destination, it) }
        }
        if (extra.has("xPaddingObfsMode")) target.put("x-padding-obfs-mode", extra.optBoolean("xPaddingObfsMode"))
        mapOf(
            "uplinkChunkSize" to "uplink-chunk-size",
            "scMaxEachPostBytes" to "sc-max-each-post-bytes",
            "scMinPostsIntervalMs" to "sc-min-posts-interval-ms",
        ).forEach { (source, destination) ->
            (extra.opt(source) as? Number)?.let { target.put(destination, it.toInt()) }
        }
        extra.optJSONObject("xmux")?.let(::reuseSettings)?.let { target.put("reuse-settings", it) }
        extra.optJSONObject("downloadSettings")?.let(::downloadSettings)?.let { target.put("download-settings", it) }
    }

    private fun reuseSettings(xmux: JSONObject): JSONObject? {
        val output = JSONObject()
        mapOf(
            "maxConnections" to "max-connections",
            "maxConcurrency" to "max-concurrency",
            "cMaxReuseTimes" to "c-max-reuse-times",
            "hMaxRequestTimes" to "h-max-request-times",
            "hMaxReusableSecs" to "h-max-reusable-secs",
        ).forEach { (source, destination) ->
            when (val value = xmux.opt(source)) {
                is String -> value.takeIf(String::isNotBlank)?.let { output.put(destination, it) }
                is Number -> output.put(destination, value.toInt().toString())
            }
        }
        (xmux.opt("hKeepAlivePeriod") as? Number)?.let { output.put("h-keep-alive-period", it.toInt()) }
        return output.takeIf { it.length() > 0 }
    }

    private fun downloadSettings(settings: JSONObject): JSONObject? {
        val output = JSONObject()
        settings.optString("address").takeIf(String::isNotBlank)?.let { output.put("server", it) }
        (settings.opt("port") as? Number)?.let { output.put("port", it.toInt()) }
        val security = settings.optString("security").lowercase()
        if (security == "tls" || security == "reality") {
            output.put("tls", true)
            settings.optJSONObject("tlsSettings")?.let { tls ->
                tls.optString("serverName").takeIf(String::isNotBlank)?.let { output.put("servername", it) }
                tls.optString("fingerprint").takeIf(String::isNotBlank)?.let { output.put("client-fingerprint", it) }
                tls.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let { output.put("alpn", it) }
                output.put("skip-cert-verify", false)
            }
            if (security == "reality") settings.optJSONObject("realitySettings")?.let { reality ->
                reality.optString("publicKey").takeIf(String::isNotBlank)?.let { key ->
                    output.put("reality-opts", JSONObject().put("public-key", key).apply {
                        reality.optString("shortId").takeIf(String::isNotBlank)?.let { put("short-id", it) }
                    })
                }
            }
        }
        settings.optJSONObject("xhttpSettings")?.let { xhttp ->
            listOf("path", "host").forEach { key ->
                xhttp.optString(key).takeIf(String::isNotBlank)?.let { output.put(key, it) }
            }
            xhttp.optJSONObject("headers")?.takeIf { it.length() > 0 }?.let { output.put("headers", it) }
            applyXhttpExtra(output, xhttp)
        }
        return output.takeIf { it.length() > 0 }
    }

    private fun endpoint(uri: URI, decodeHost: Boolean = false): Endpoint {
        val authority = uri.rawAuthority ?: throw ParseError()
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) decode(authority.substring(0, at)) else ""
        var hostPort = authority.substring(at + 1)
        var parsed = parseHostPort(hostPort)
        if (parsed == null && decodeHost) {
            hostPort = decodeBase64Text(hostPort) ?: throw ParseError()
            parsed = parseHostPort(hostPort)
        }
        val endpoint = parsed ?: throw ParseError()
        return Endpoint(userInfo, endpoint.host, endpoint.port)
    }

    private fun parseHostPort(raw: String): HostPort? {
        val value = raw.substringBefore('/').trim()
        if (value.startsWith('[')) {
            val end = value.indexOf(']')
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull()
            return if (end > 0 && port in 1..65535) HostPort(value.substring(1, end), port!!) else null
        }
        val separator = value.lastIndexOf(':')
        val port = value.substring(separator + 1).toIntOrNull()
        return if (separator > 0 && port in 1..65535) HostPort(value.substring(0, separator), port!!) else null
    }

    private fun sanitizeUriComponent(raw: String, allowBrackets: Boolean = false): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when {
                ch == ' ' -> sb.append("%20")
                ch == '|' -> sb.append("%7C")
                ch == '{' -> sb.append("%7B")
                ch == '}' -> sb.append("%7D")
                ch == '"' -> sb.append("%22")
                ch == '<' -> sb.append("%3C")
                ch == '>' -> sb.append("%3E")
                ch == '^' -> sb.append("%5E")
                ch == '`' -> sb.append("%60")
                ch == '\\' -> sb.append("%5C")
                !allowBrackets && ch == '[' -> sb.append("%5B")
                !allowBrackets && ch == ']' -> sb.append("%5D")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun sanitizeFullShareUri(raw: String): String {
        val trimmed = raw.trim()
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx <= 0) return sanitizeUriComponent(trimmed)
        val scheme = trimmed.substring(0, schemeIdx)
        val rest = trimmed.substring(schemeIdx + 3)
        val fragIdx = rest.indexOf('#')
        val beforeFrag = if (fragIdx >= 0) rest.substring(0, fragIdx) else rest
        val frag = if (fragIdx >= 0) rest.substring(fragIdx + 1) else null

        val queryIdx = beforeFrag.indexOf('?')
        val authAndPath = if (queryIdx >= 0) beforeFrag.substring(0, queryIdx) else beforeFrag
        val query = if (queryIdx >= 0) beforeFrag.substring(queryIdx + 1) else null

        val slashIdx = authAndPath.indexOf('/')
        val authority = if (slashIdx >= 0) authAndPath.substring(0, slashIdx) else authAndPath
        val path = if (slashIdx >= 0) authAndPath.substring(slashIdx) else ""

        // In authority, keep brackets only for IPv6 host part after '@'
        val atIdx = authority.lastIndexOf('@')
        val sanitizedAuthority = if (atIdx >= 0) {
            val userInfo = sanitizeUriComponent(authority.substring(0, atIdx), allowBrackets = false)
                .replace("/", "%2F")
            val hostPort = sanitizeUriComponent(authority.substring(atIdx + 1), allowBrackets = true)
            "$userInfo@$hostPort"
        } else {
            sanitizeUriComponent(authority, allowBrackets = true)
        }

        return buildString {
            append(scheme).append("://").append(sanitizedAuthority)
            if (path.isNotEmpty()) append(sanitizeUriComponent(path, allowBrackets = false))
            if (query != null) append('?').append(sanitizeUriComponent(query, allowBrackets = false))
            if (frag != null) append('#').append(sanitizeUriComponent(frag, allowBrackets = false))
        }
    }

    private fun parseUri(value: String): URI = try {
        URI(sanitizeFullShareUri(value))
    } catch (_: Exception) {
        throw ParseError()
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val output = linkedMapOf<String, String>()
        raw.split('&').forEach { part ->
            val split = part.split('=', limit = 2)
            val key = decode(split[0])
            if (key.isNotBlank() && key !in output) output[key] = decode(split.getOrNull(1).orEmpty())
        }
        return output
    }

    private fun name(uri: URI): String = decode(uri.rawFragment.orEmpty()).trim().ifBlank {
        val scheme = uri.scheme?.lowercase().orEmpty().ifBlank { "proxy" }
        val hostPort = uri.rawAuthority?.substringAfterLast('@')?.substringBefore('/').orEmpty()
        if (hostPort.isNotBlank()) "$scheme-$hostPort" else scheme
    }

    private fun decode(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Throwable) {
            // Escape lone '%' not followed by two hex characters
            val safe = value.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25")
            runCatching { URLDecoder.decode(safe, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
        }
    }

    private fun removeQueryParameter(path: String, key: String): String {
        val base = path.substringBefore('?')
        val query = parseQuery(path.substringAfter('?', ""))
            .filterKeys { it != key }
            .entries.joinToString("&") { (name, value) ->
                "${encodePathParam(name)}=${encodePathParam(value)}"
            }
        return if (query.isBlank()) base else "$base?$query"
    }

    private fun jsonInt(json: JSONObject, key: String, default: Int = -1): Int =
        when (val value = json.opt(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }

    private fun splitCsv(value: String): List<String>? =
        value.split(',').map(String::trim).filter(String::isNotBlank).takeIf(List<String>::isNotEmpty)

    @OptIn(ExperimentalEncodingApi::class)
    internal fun decodeBase64Text(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return runCatching { Base64.Default.decode(padded) }
            .recoverCatching { Base64.UrlSafe.decode(padded) }
            .getOrNull()
            ?.toString(Charsets.UTF_8)
    }

    private class NameRegistry {
        private val names = mutableMapOf<String, Int>()

        fun register(rawName: String): String {
            val name = rawName.trim().ifBlank { "proxy" }
            val index = names[name]
            if (index == null) {
                names[name] = 0
                return name
            }
            val next = index + 1
            names[name] = next
            return "$name-${next.toString().padStart(2, '0')}"
        }
    }

    private data class Endpoint(val userInfo: String, val host: String, val port: Int)
    private data class HostPort(val host: String, val port: Int)
    private class ParseError : IllegalArgumentException()
}
