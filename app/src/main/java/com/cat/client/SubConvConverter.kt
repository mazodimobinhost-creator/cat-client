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
        "socks://", "socks5://", "ss://", "ssr://", "tuic://", "juicity://",
        "wireguard://", "wg://", "awg://", "amneziawg://",
        "ssh://", "snell://", "mieru://", "naive+https://", "naive+quic://",
        "warp://", "psiphon://", "tor://", "gool://",
        "fenix://", "firefoxvpn://", "paqet://", "paqetng://", "patth://", "patthng://",
        "dnstt://", "dnst://", "slipstream://", "slipnet://", "slipnet-enc://", "slipnet-bundle-enc://",
        "vaydns://", "noizdns://", "cottendns://", "stormdns://", "masterdns://", "soar://",
        "qstunnel://", "qqtunnel://", "spooftunnel://", "s3bypass://", "phantun://", "nm://",
        "doh://", "aether://", "masque://", "sstp://",
    )

    data class UniversalConfigItemSummary(
        val index: Int,
        val detectedFormat: String,
        val proxyName: String,
        val serverSummary: String,
        val normalizedShareUri: String,
    )

    data class UniversalConfigInspection(
        val totalCount: Int,
        val formatCounts: Map<String, Int>,
        val items: List<UniversalConfigItemSummary>,
        val normalizedSubscriptionText: String,
    )

    /**
     * Converts raw Tor/SlipNet `dnstt` bridge lines (e.g.
     * `dnstt 192.0.2.4:1 ... doh=https://... pubkey=0123... domain=t.example.com`)
     * that lack `://` into a standard `dnstt://` share URI.
     */
    private fun normalizeRawBridgeOrProxyLine(trimmed: String): String? {
        val cleaned = trimmed.removePrefix("Bridge ").removePrefix("bridge ").trim()
        if (cleaned.startsWith("dnstt ", ignoreCase = true) && "://" !in cleaned.substringBefore(' ')) {
            val tokens = cleaned.split(Regex("\\s+"))
            var pubkey = ""
            var domain = ""
            var resolver = "178.22.122.100:53"
            var doh = ""
            for (tok in tokens.drop(1)) {
                val lower = tok.lowercase()
                when {
                    lower.startsWith("pubkey=") -> pubkey = tok.substringAfter('=').trim()
                    lower.startsWith("domain=") -> domain = tok.substringAfter('=').trim()
                    lower.startsWith("udp=") || lower.startsWith("resolver=") -> resolver = tok.substringAfter('=').trim()
                    lower.startsWith("doh=") -> doh = tok.substringAfter('=').trim()
                    tok.length == 64 && tok.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> pubkey = tok
                }
            }
            if (domain.isNotBlank()) {
                val resPart = if (':' in resolver) resolver else "$resolver:53"
                val dohParam = if (doh.isNotBlank()) "&doh=${URLEncoder.encode(doh, "UTF-8")}" else ""
                return "dnstt://$resPart/$domain?pubkey=$pubkey$dohParam#DNSTT-$domain"
            }
        }
        return null
    }

    private fun extractShareUriFromLine(raw: String): String? {
        val trimmed = raw.trim().trimStart('\uFEFF')
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) return null
        // beta212: a bare VPN Gate host from an SSTP server list ("vpn123.opengw.net:1270") is an SSTP server.
        if ("://" !in trimmed) SstpLink.parse(trimmed)?.let { return it.toUri() }
        normalizeRawBridgeOrProxyLine(trimmed)?.let { return it }
        if ("://" !in trimmed) return null
        val firstSchemeBefore = trimmed.substringBefore("://")
        if (firstSchemeBefore.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) {
            val lowerScheme = firstSchemeBefore.lowercase()
            if (lowerScheme == "http" || lowerScheme == "https") {
                val afterScheme = trimmed.substringAfter("://").substringBefore('#').substringBefore('?')
                val hostPart = afterScheme.substringAfter('@').substringBefore('/')
                val hasAuth = '@' in afterScheme.substringBefore('/')
                val hasExplicitPort = ':' in hostPart && hostPart.substringAfterLast(':').toIntOrNull() != null
                val lowerHost = hostPart.substringBefore(':').lowercase()
                val isWebLink = lowerHost.endsWith("t.me") || lowerHost.endsWith("github.com") ||
                    lowerHost.endsWith("google.com") || lowerHost.endsWith("youtube.com")
                if (isWebLink || (!hasAuth && !hasExplicitPort)) return null
            }
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
        val data = CatFusionLink.expandInText(decodeBase64Text(input) ?: input)
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
                    "tuic", "juicity" -> parseTuic(line, names)
                    "socks", "socks5" -> parseSocks(line, names)
                    "http", "https", "naive+https", "naive+quic" -> parseHttpProxy(line, names)
                    "ssh" -> parseSsh(line, names)
                    "ss" -> parseShadowsocks(line, names)
                    "ssr" -> parseShadowsocksR(line, names)
                    "snell", "mieru" -> parseSnellOrMieru(line, names)
                    "wireguard", "wg", "awg", "amneziawg" -> parseWireGuard(line, names)
                    "aether", "masque" -> parseAether(line, names)
                    "warp", "psiphon", "tor", "gool" -> parseWarpOrPsiphonUri(line, names)
                    "fenix", "firefoxvpn" -> parseFenixVpn(line, names)
                    SstpLink.SCHEME -> parseSstp(line, names)
                    in PaqetEngine.SUPPORTED_SCHEMES -> parsePaqet(line, names)
                    in WhiteDnsSmartScanner.SUPPORTED_TUNNEL_SCHEMES -> parseWhiteDnsTunnel(line, names)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }.toList()
        if (proxies.isEmpty()) {
            val structured = parseRawJsonOrTomlConfigs(data, names).map(::normalizeMihomoTransport)
            if (structured.isNotEmpty()) return structured
            PaqetEngine.parsePaqetFileOrUri(data)?.let { paqetCfg ->
                val obj = PaqetEngine.toMihomoProxyJson(paqetCfg)
                obj.put("name", names.register(obj.optString("name", "PaqetNG")))
                return listOf(obj)
            }
        }
        require(proxies.isNotEmpty()) { "No valid proxies found" }
        return proxies.map(::normalizeMihomoTransport)
    }

    /**
     * Mihomo has NO `network: httpupgrade` — an unknown network silently falls back to plain
     * TCP(+TLS), so every `type=httpupgrade` share link (Xray/sing-box «HTTPUpgrade», e.g. panels
     * that hand out `/ws/vl/<uuid>` over httpupgrade) used to produce a config that could never
     * connect. Mihomo's real shape is `network: ws` + `ws-opts.v2ray-http-upgrade: true`
     * (early data → `v2ray-http-upgrade-fast-open`). Applied to every converted proxy.
     */
    internal fun normalizeMihomoTransport(proxy: JSONObject): JSONObject {
        if (!proxy.optString("network").equals("httpupgrade", ignoreCase = true)) return proxy
        val opts = proxy.optJSONObject("ws-opts") ?: JSONObject()
        if (!opts.has("path")) opts.put("path", "/")
        opts.remove("max-early-data")
        opts.remove("early-data-header-name")
        opts.put("v2ray-http-upgrade", true)
        proxy.put("network", "ws").put("ws-opts", opts)
        return proxy
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
        query["sni_fwd_target"]?.takeIf(String::isNotBlank)?.let { proxy.put("sni-fwd-target", it.trim()) }
        return proxy
    }

    private fun parseVmess(line: String, names: NameRegistry): JSONObject {
        // Some exporters append a name: vmess://<base64-json>#Name. '#' is not base64, so the
        // whole link used to fail to decode and the config was dropped.
        val fullBody = line.removePrefix("vmess://")
        val body = fullBody.substringBefore('#')
        val fragmentName = fullBody.substringAfter('#', "")
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            .trim()
        val decoded = decodeBase64Text(body)?.takeIf { it.trimStart().startsWith("{") }
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
            .put("name", names.register(fragmentName.ifBlank { values.optString("ps") }))
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
                query["sni_fwd_target"]?.takeIf(String::isNotBlank)?.let { put("sni-fwd-target", it.trim()) }
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

    private fun parseTuic(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val sep = endpoint.userInfo.indexOf(':')
        val uuid = if (sep > 0) endpoint.userInfo.substring(0, sep) else endpoint.userInfo
        val pass = if (sep > 0) endpoint.userInfo.substring(sep + 1) else ""
        val query = parseQuery(uri.rawQuery)
        val sni = (query["sni"] ?: query["servername"]).orEmpty()
        val cc = (query["congestion_control"] ?: query["congestion-controller"] ?: query["cc"]).orEmpty().ifBlank { "bbr" }
        val udpMode = (query["udp_relay_mode"] ?: query["udp-relay-mode"]).orEmpty().ifBlank { "native" }
        return JSONObject()
            .put("name", names.register(name(uri).ifBlank { "TUIC-${endpoint.host}" }))
            .put("type", "tuic")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("uuid", uuid)
            .put("password", pass)
            .put("udp", true)
            .put("skip-cert-verify", false)
            .put("congestion-controller", cc)
            .put("udp-relay-mode", udpMode)
            .apply {
                sni.takeIf(String::isNotBlank)?.let { put("sni", it) }
                query["alpn"]?.takeIf(String::isNotBlank)?.let { put("alpn", JSONArray(it.split(','))) }
            }
    }

    private fun parseHttpProxy(line: String, names: NameRegistry): JSONObject {
        val normalizedLine = line
            .replaceFirst(Regex("^naive\\+https://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^naive\\+quic://", RegexOption.IGNORE_CASE), "https://")
        val uri = parseUri(normalizedLine)
        val authority = uri.rawAuthority ?: throw ParseError()
        val at = authority.lastIndexOf('@')
        val endpoint = parseHostPort(authority.substring(at + 1)) ?: throw ParseError()
        val query = parseQuery(uri.rawQuery)
        val isTls = normalizedLine.startsWith("https://", ignoreCase = true) ||
            query["security"].equals("tls", ignoreCase = true)
        val proxyName = runCatching { name(uri) }.getOrDefault("")
            .ifBlank { "${if (isTls) "HTTPS" else "HTTP"}-${endpoint.host}:${endpoint.port}" }
        val proxy = JSONObject()
            .put("name", names.register(proxyName))
            .put("type", "http")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("tls", isTls)
            .put("skip-cert-verify", false)
        if (at >= 0) {
            val (username, password) = socksCredentials(authority.substring(0, at))
            proxy.put("username", username)
            password?.let { proxy.put("password", it) }
        }
        (query["sni"] ?: query["servername"])?.takeIf(String::isNotBlank)?.let { proxy.put("sni", it) }
        return proxy
    }

    private fun parseSsh(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val authority = uri.rawAuthority ?: throw ParseError()
        val at = authority.lastIndexOf('@')
        val hostPart = authority.substring(at + 1).substringBefore('/')
        val endpoint = parseHostPort(hostPart)
            ?: HostPort(hostPart.substringBefore(':'), hostPart.substringAfter(':', "22").toIntOrNull() ?: 22)
        if (endpoint.host.isBlank()) throw ParseError()
        val (username, password) = if (at >= 0) {
            socksCredentials(authority.substring(0, at))
        } else {
            "root" to ""
        }
        val proxyName = runCatching { name(uri) }.getOrDefault("")
            .ifBlank { "SSH-${endpoint.host}:${endpoint.port}" }
        return JSONObject()
            .put("name", names.register(proxyName))
            .put("type", "ssh")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("username", username)
            .apply {
                password?.takeIf(String::isNotBlank)?.let { put("password", it) }
            }
    }

    private fun parseShadowsocksR(line: String, names: NameRegistry): JSONObject {
        val rawBody = line.substringAfter("://").substringBefore('#').trim()
        val decoded = decodeBase64Text(rawBody) ?: throw ParseError()
        val mainAndParams = decoded.split("/?", limit = 2)
        val parts = mainAndParams[0].split(':')
        if (parts.size < 6) throw ParseError()
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: throw ParseError()
        val protocol = parts[2].trim().ifBlank { "origin" }
        val cipher = parts[3].trim().ifBlank { "aes-256-cfb" }
        val obfs = parts[4].trim().ifBlank { "plain" }
        val passB64 = parts.drop(5).joinToString(":").trim()
        val password = decodeBase64Text(passB64) ?: passB64
        val params = parseQuery(mainAndParams.getOrNull(1))
        val remark = params["remarks"]?.let { decodeBase64Text(it) ?: it }
            ?: line.substringAfter('#', "").let { decode(it) }
        val obfsParam = params["obfsparam"]?.let { decodeBase64Text(it) ?: it }.orEmpty()
        val protoParam = params["protoparam"]?.let { decodeBase64Text(it) ?: it }.orEmpty()
        return JSONObject()
            .put("name", names.register(remark.ifBlank { "SSR-$host:$port" }))
            .put("type", "ssr")
            .put("server", host)
            .put("port", port)
            .put("cipher", cipher)
            .put("password", password)
            .put("obfs", obfs)
            .put("protocol", protocol)
            .put("udp", true)
            .apply {
                obfsParam.takeIf(String::isNotBlank)?.let { put("obfs-param", it) }
                protoParam.takeIf(String::isNotBlank)?.let { put("protocol-param", it) }
            }
    }

    private fun parseSnellOrMieru(line: String, names: NameRegistry): JSONObject {
        val scheme = line.substringBefore("://").lowercase()
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        val query = parseQuery(uri.rawQuery)
        return if (scheme == "snell") {
            JSONObject()
                .put("name", names.register(name(uri).ifBlank { "Snell-${endpoint.host}" }))
                .put("type", "snell")
                .put("server", endpoint.host)
                .put("port", endpoint.port)
                .put("psk", endpoint.userInfo)
                .put("version", query["version"]?.toIntOrNull() ?: 4)
                .put("udp", true)
                .apply {
                    val obfsMode = query["obfs"].orEmpty()
                    if (obfsMode.isNotBlank()) {
                        put("obfs-opts", JSONObject().apply {
                            put("mode", obfsMode)
                            (query["obfs-host"] ?: query["sni"])?.takeIf(String::isNotBlank)?.let { put("host", it) }
                        })
                    }
                }
        } else {
            val (user, pass) = socksCredentials(endpoint.userInfo.ifBlank { "user:pass" })
            JSONObject()
                .put("name", names.register(name(uri).ifBlank { "Mieru-${endpoint.host}" }))
                .put("type", "mieru")
                .put("server", endpoint.host)
                .put("port", endpoint.port)
                .put("username", user)
                .put("password", pass.orEmpty())
                .put("transport", (query["transport"] ?: query["network"]).orEmpty().uppercase().ifBlank { "TCP" })
                .put("udp", true)
        }
    }

    private fun parseWarpOrPsiphonUri(line: String, names: NameRegistry): JSONObject {
        val scheme = line.substringBefore("://").lowercase()
        val rest = line.substringAfter("://")
        val remark = rest.substringAfter('#', "").let { decode(it) }.trim()
        val beforeHash = rest.substringBefore('#')
        val query = parseQuery(beforeHash.substringAfter('?', ""))
        val hostPart = beforeHash.substringBefore('?').substringAfter('@').trim('/')
        val sel = when (scheme) {
            "psiphon" -> {
                val region = hostPart.uppercase().takeIf { it.length == 2 } ?: query["country"]?.uppercase() ?: ""
                AetherSelection(
                    mode = AetherMode.MasqueInMasque,
                    chainTarget = "psiphon",
                    psiphonRegion = region,
                    profileName = remark.ifBlank { "Psiphon (${region.ifBlank { "Auto" }})" },
                )
            }
            "tor" -> {
                AetherSelection(
                    mode = AetherMode.MasqueInMasque,
                    chainTarget = "tor",
                    profileName = remark.ifBlank { "Cat Warp + Tor Bridge" },
                )
            }
            "gool" -> {
                AetherSelection(
                    mode = AetherMode.WarpInWarp,
                    endpoint = hostPart.takeIf { ':' in it && !it.equals("auto", ignoreCase = true) },
                    profileName = remark.ifBlank { "WARP-in-WARP (Gool)" },
                )
            }
            else -> {
                val ep = hostPart.takeIf { ':' in it && !it.equals("auto", ignoreCase = true) }
                val det = query["detour"]?.lowercase()
                AetherSelection(
                    mode = if (det == "warp" || query["gool"] == "1") AetherMode.WarpInWarp else AetherMode.WireGuard,
                    endpoint = ep,
                    chainTarget = if (det == "psiphon") "psiphon" else "none",
                    profileName = remark.ifBlank { "Cloudflare WARP (${ep ?: "Auto"})" },
                )
            }
        }
        return JSONObject()
            .put("name", names.register(sel.profileName))
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", 1819)
            .put("udp", true)
            .put("aether-uri", sel.toAetherUri())
    }

    private fun parseWhiteDnsTunnel(line: String, names: NameRegistry): JSONObject {
        // First check if a `slipnet://` v6..v28 pipe-delimited URI actually wraps a direct VLESS/SSH/SOCKS5/Naive config
        WhiteDnsSmartScanner.extractEmbeddedSlipNetDirectUri(line)?.let { directUri ->
            val directScheme = directUri.substringBefore("://").lowercase()
            runCatching {
                when (directScheme) {
                    "vless" -> return parseVless(directUri, names)
                    "ssh" -> return parseSsh(directUri, names)
                    "socks5", "socks" -> return parseSocks(directUri, names)
                    "https", "http" -> return parseHttpProxy(directUri, names)
                }
            }
        }
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

    /**
     * Parses raw Xray / v2rayNG / Sing-box JSON files (`{"outbounds": [...]}` or `[{"outbounds": ...}]`)
     * AND raw CottenDNS / CottenMax / MasterDnsVPN TOML files (`DOMAINS = [...]`, `TUNNEL_MODE = ...`)
     * directly into Mihomo proxy objects so users can paste any raw config file into the app.
     */
    private fun parseRawJsonOrTomlConfigs(rawText: String, names: NameRegistry): List<JSONObject> {
        val trimmed = rawText.trim().trimStart('\uFEFF')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val out = mutableListOf<JSONObject>()
            runCatching {
                val roots = if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                } else {
                    listOf(JSONObject(trimmed))
                }
                for (root in roots) {
                    // Case 1: Single Mihomo/Clash proxy object (`{"name": ..., "type": ..., "server": ...}`)
                    if (root.has("type") && root.has("server") && root.has("port")) {
                        val copy = JSONObject(root.toString())
                        copy.put("name", names.register(copy.optString("name", copy.optString("type", "proxy"))))
                        out.add(copy)
                        continue
                    }
                    // Case 2: Xray / Sing-box `outbounds` array
                    val outbounds = root.optJSONArray("outbounds") ?: continue
                    val rootRemark = root.optString("remarks", root.optString("name", "")).trim()
                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.optJSONObject(i) ?: continue
                        val proto = ob.optString("protocol", ob.optString("type", "")).lowercase()
                        val tag = ob.optString("tag", "").trim()
                        if (proto in setOf("direct", "block", "dns", "blackhole", "freedom")) continue
                        convertXrayOrSingBoxOutbound(ob, proto, rootRemark.ifBlank { tag }, names)?.let { out.add(it) }
                    }
                }
            }
            // beta214: a socks/http "outbound" on 127.0.0.1 inside another app's JSON is that app's own local engine
            // (it only works while that app runs). Imported alone it became a dead «Local 127.0.0.1:10819» row.
            val engineKeys = listOf("aether-uri", "sstp-uri", "whitedns-uri", "fenix-uri", "paqet-uri")
            val usable = out.filterNot { proxy ->
                engineKeys.none(proxy::has) &&
                    LoopbackProfileGuard.isPlainLoopback(proxy.optString("type"), proxy.optString("server"), null)
            }
            if (usable.isNotEmpty()) return usable
        }

        // Case 3: Raw CottenDNS / CottenMax / MasterDnsVPN TOML config (`DOMAINS = [...]`, `RESOLVERS = [...]`)
        if ("DOMAINS" in trimmed || "TUNNEL_MODE" in trimmed || "SHARED_KEY" in trimmed || "ENCRYPTION_KEY" in trimmed) {
            val domainRegex = Regex("""DOMAINS\s*=\s*\[\s*"([^"]+)"""")
            val resolverRegex = Regex("""(?:RESOLVERS|RESOLVER)\s*=\s*\[?\s*"([^"]+)"""")
            val keyRegex = Regex("""(?:SHARED_KEY|ENCRYPTION_KEY|PUBLIC_KEY)\s*=\s*"([^"]+)"""")
            val modeRegex = Regex("""TUNNEL_MODE\s*=\s*"([^"]+)"""")
            val domain = domainRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (domain.isNotBlank()) {
                val resolver = resolverRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
                    ?.substringBefore(':')?.ifBlank { "178.22.122.100" } ?: "178.22.122.100"
                val key = keyRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val mode = modeRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.lowercase().orEmpty()
                val scheme = when {
                    mode.contains("master") -> "masterdns"
                    mode.contains("storm") -> "stormdns"
                    else -> "cottendns"
                }
                val syntheticUri = "$scheme://$resolver:53/$domain?pubkey=$key#TOML-${scheme.uppercase()}-$domain"
                return runCatching { listOf(parseWhiteDnsTunnel(syntheticUri, names)) }.getOrDefault(emptyList())
            }
        }
        return emptyList()
    }

    private fun convertXrayOrSingBoxOutbound(
        ob: JSONObject,
        proto: String,
        remarkHint: String,
        names: NameRegistry,
    ): JSONObject? {
        // Sing-box flat outbound format (`{"type":"vless","server":"...","server_port":443,"uuid":"..."}`)
        if (ob.has("server") && (ob.has("server_port") || ob.has("port"))) {
            val server = ob.optString("server").trim()
            val port = ob.optInt("server_port", ob.optInt("port", 443))
            if (server.isBlank() || port !in 1..65535) return null
            val tlsObj = ob.optJSONObject("tls")
            val tlsEnabled = tlsObj?.optBoolean("enabled", false) ?: ob.optBoolean("tls", false)
            val sni = tlsObj?.optString("server_name", "")?.ifBlank { ob.optString("sni", "") }.orEmpty()
            val trObj = ob.optJSONObject("transport")
            val netType = trObj?.optString("type", "")?.lowercase().orEmpty()
            val proxy = JSONObject()
                .put("name", names.register(remarkHint.ifBlank { "${proto.uppercase()}-$server:$port" }))
                .put("server", server)
                .put("port", port)
                .put("udp", true)
            when (proto) {
                "vless" -> {
                    proxy.put("type", "vless")
                    proxy.put("uuid", ob.optString("uuid"))
                    ob.optString("flow").takeIf(String::isNotBlank)?.let { proxy.put("flow", it) }
                }
                "vmess" -> {
                    proxy.put("type", "vmess")
                    proxy.put("uuid", ob.optString("uuid"))
                    proxy.put("alterId", ob.optInt("alter_id", 0))
                    proxy.put("cipher", ob.optString("security", "auto").ifBlank { "auto" })
                }
                "trojan" -> {
                    proxy.put("type", "trojan")
                    proxy.put("password", ob.optString("password"))
                }
                "shadowsocks", "ss" -> {
                    proxy.put("type", "ss")
                    proxy.put("cipher", ob.optString("method", ob.optString("cipher", "aes-256-gcm")))
                    proxy.put("password", ob.optString("password"))
                }
                "hysteria2", "hy2" -> {
                    proxy.put("type", "hysteria2")
                    proxy.put("password", ob.optString("password"))
                }
                "tuic" -> {
                    proxy.put("type", "tuic")
                    proxy.put("uuid", ob.optString("uuid"))
                    proxy.put("password", ob.optString("password"))
                    proxy.put("congestion-controller", ob.optString("congestion_control", "bbr"))
                }
                "socks" -> {
                    proxy.put("type", "socks5")
                    ob.optString("username").takeIf(String::isNotBlank)?.let { proxy.put("username", it) }
                    ob.optString("password").takeIf(String::isNotBlank)?.let { proxy.put("password", it) }
                }
                else -> return null
            }
            if (tlsEnabled) {
                proxy.put("tls", true)
                proxy.put("skip-cert-verify", false)
                sni.takeIf(String::isNotBlank)?.let {
                    proxy.put(if (proto == "trojan" || proto == "tuic" || proto == "hysteria2") "sni" else "servername", it)
                }
            }
            if (netType == "ws" || netType == "httpupgrade") {
                proxy.put("network", "ws")
                val wsPath = trObj?.optString("path", "/")?.ifBlank { "/" } ?: "/"
                val wsHost = trObj?.optJSONObject("headers")?.optString("Host", "")?.ifBlank { null }
                    ?: trObj?.optString("host", "")?.ifBlank { null } ?: sni
                val wsOpts = JSONObject().put("path", wsPath).put("headers", JSONObject().put("Host", wsHost))
                if (netType == "httpupgrade") wsOpts.put("v2ray-http-upgrade", true)
                proxy.put("ws-opts", wsOpts)
            }
            return proxy
        }

        // Xray `settings.vnext` or `settings.servers` format
        val settings = ob.optJSONObject("settings") ?: return null
        val stream = ob.optJSONObject("streamSettings")
        val net = stream?.optString("network", "tcp")?.lowercase().orEmpty().ifBlank { "tcp" }
        val sec = stream?.optString("security", "none")?.lowercase().orEmpty()
        val tlsSettings = stream?.optJSONObject("tlsSettings") ?: stream?.optJSONObject("realitySettings")
        val sni = tlsSettings?.optString("serverName", "").orEmpty()
        val fp = tlsSettings?.optString("fingerprint", "chrome").orEmpty().ifBlank { "chrome" }
        val wsSettings = stream?.optJSONObject("wsSettings") ?: stream?.optJSONObject("httpupgradeSettings")
        val wsPath = wsSettings?.optString("path", "/").orEmpty().ifBlank { "/" }
        val wsHost = wsSettings?.optJSONObject("headers")?.optString("Host", "")
            ?.ifBlank { wsSettings.optString("host", sni) }.orEmpty()

        val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
        if (vnext != null && (proto == "vless" || proto == "vmess")) {
            val addr = vnext.optString("address").trim()
            val port = vnext.optInt("port", 443)
            val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: return null
            val uuid = user.optString("id").trim()
            if (addr.isBlank() || uuid.isBlank()) return null
            return JSONObject()
                .put("name", names.register(remarkHint.ifBlank { "${proto.uppercase()}-$addr:$port" }))
                .put("type", proto)
                .put("server", addr)
                .put("port", port)
                .put("uuid", uuid)
                .put("udp", true)
                .put("network", net)
                .apply {
                    if (proto == "vmess") {
                        put("alterId", user.optInt("alterId", 0))
                        put("cipher", user.optString("security", "auto").ifBlank { "auto" })
                    } else {
                        user.optString("flow").takeIf(String::isNotBlank)?.let { put("flow", it) }
                    }
                    if (sec == "tls" || sec == "reality") {
                        put("tls", true)
                        put("skip-cert-verify", false)
                        put("client-fingerprint", fp)
                        sni.takeIf(String::isNotBlank)?.let { put("servername", it) }
                    }
                    if (net == "ws" || net == "httpupgrade") {
                        put("ws-opts", JSONObject().put("path", wsPath).put("headers", JSONObject().put("Host", wsHost)))
                    }
                }
        }

        val srv = settings.optJSONArray("servers")?.optJSONObject(0)
        if (srv != null && (proto == "trojan" || proto == "shadowsocks" || proto == "socks")) {
            val addr = srv.optString("address").trim()
            val port = srv.optInt("port", 443)
            if (addr.isBlank()) return null
            return when (proto) {
                "trojan" -> JSONObject()
                    .put("name", names.register(remarkHint.ifBlank { "Trojan-$addr:$port" }))
                    .put("type", "trojan")
                    .put("server", addr)
                    .put("port", port)
                    .put("password", srv.optString("password"))
                    .put("udp", true)
                    .put("skip-cert-verify", false)
                    .apply {
                        sni.takeIf(String::isNotBlank)?.let { put("sni", it) }
                        if (net == "ws") {
                            put("network", "ws")
                            put("ws-opts", JSONObject().put("path", wsPath).put("headers", JSONObject().put("Host", wsHost)))
                        }
                    }
                "shadowsocks" -> JSONObject()
                    .put("name", names.register(remarkHint.ifBlank { "SS-$addr:$port" }))
                    .put("type", "ss")
                    .put("server", addr)
                    .put("port", port)
                    .put("cipher", srv.optString("method", "aes-256-gcm"))
                    .put("password", srv.optString("password"))
                    .put("udp", true)
                else -> JSONObject()
                    .put("name", names.register(remarkHint.ifBlank { "SOCKS-$addr:$port" }))
                    .put("type", "socks5")
                    .put("server", addr)
                    .put("port", port)
                    .put("udp", true)
            }
        }
        return null
    }

    /**
     * Universal Omni-Config Inspector:
     * Analyzes any raw text (SlipNet v1..v28, dnst://, PaqetNG, Firefox VPN, TUIC, SSR, SSH,
     * WARP/Psiphon, Xray/Sing-box raw JSON, CottenMax TOML, or mixed Telegram posts) and returns
     * a structured breakdown + normalized subscription text ready for instant import & connection.
     */
    fun inspectUniversalConfigInput(input: String): UniversalConfigInspection {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return UniversalConfigInspection(0, emptyMap(), emptyList(), "")
        }
        val decoded = CatFusionLink.expandInText(decodeBase64Text(trimmed) ?: trimmed)
        val items = mutableListOf<UniversalConfigItemSummary>()
        val counts = linkedMapOf<String, Int>()
        val normalizedLines = mutableListOf<String>()
        val names = NameRegistry()

        for (rawLine in decoded.lineSequence()) {
            val shareUri = extractShareUriFromLine(rawLine) ?: continue
            val scheme = shareUri.substringBefore("://").lowercase()
            val proxyObj = runCatching { convert(shareUri).firstOrNull() }.getOrNull() ?: continue
            val pName = proxyObj.optString("name", scheme.uppercase())
            val srv = proxyObj.optString("server", "127.0.0.1")
            val prt = proxyObj.optInt("port", 0)
            val formatLabel = when (scheme) {
                "slipnet", "slipnet-enc", "slipnet-bundle-enc" -> "Cat DNS (v1..v28)"
                "dnst", "dnstt", "vaydns", "noizdns", "slipstream" -> "DNS Tunnel ($scheme)"
                "cottendns", "stormdns", "masterdns", "soar", "qstunnel", "qqtunnel" -> "Multi-DNS ($scheme)"
                "paqet", "paqetng", "patth", "patthng" -> "Paqet / PaqetNG"
                "fenix", "firefoxvpn" -> "Cat Fox VPN"
                SstpLink.SCHEME -> "Cat SSTP"
                "aether", "masque", "warp", "psiphon", "tor", "gool" -> "Cat Warp / WARP / Psiphon"
                else -> scheme.uppercase()
            }
            counts[formatLabel] = (counts[formatLabel] ?: 0) + 1
            val effectiveUri = WhiteDnsSmartScanner.extractEmbeddedSlipNetDirectUri(shareUri) ?: shareUri
            normalizedLines.add(effectiveUri)
            items.add(
                UniversalConfigItemSummary(
                    index = items.size + 1,
                    detectedFormat = formatLabel,
                    proxyName = pName,
                    serverSummary = "$srv:$prt",
                    normalizedShareUri = effectiveUri,
                ),
            )
        }

        if (items.isEmpty()) {
            val structuredProxies = parseRawJsonOrTomlConfigs(decoded, names)
            for (p in structuredProxies) {
                val pType = p.optString("type", "proxy").uppercase()
                val pName = p.optString("name", pType)
                val srv = p.optString("server", "127.0.0.1")
                val prt = p.optInt("port", 443)
                val fmt = if (p.has("whitedns-uri")) "TOML DNS Tunnel" else "Raw JSON ($pType)"
                counts[fmt] = (counts[fmt] ?: 0) + 1
                val synthUri = when {
                    p.has("whitedns-uri") -> p.optString("whitedns-uri")
                    p.optString("type") == "vless" -> {
                        val uuid = p.optString("uuid")
                        val net = p.optString("network", "tcp")
                        val sni = p.optString("servername", srv)
                        val wsOpts = p.optJSONObject("ws-opts")
                        val path = wsOpts?.optString("path", "/") ?: "/"
                        val host = wsOpts?.optJSONObject("headers")?.optString("Host", sni) ?: sni
                        "vless://$uuid@$srv:$prt?encryption=none&security=tls&type=$net&host=$host&sni=$sni&path=${encode(path)}#${encode(pName)}"
                    }
                    p.optString("type") == "trojan" -> {
                        val pass = p.optString("password")
                        val sni = p.optString("sni", srv)
                        "trojan://$pass@$srv:$prt?security=tls&sni=$sni#${encode(pName)}"
                    }
                    else -> "socks5://$srv:$prt#${encode(pName)}"
                }
                normalizedLines.add(synthUri)
                items.add(
                    UniversalConfigItemSummary(
                        index = items.size + 1,
                        detectedFormat = fmt,
                        proxyName = pName,
                        serverSummary = "$srv:$prt",
                        normalizedShareUri = synthUri,
                    ),
                )
            }
        }

        return UniversalConfigInspection(
            totalCount = items.size,
            formatCounts = counts,
            items = items,
            normalizedSubscriptionText = normalizedLines.joinToString("\n"),
        )
    }

    private fun parseAether(line: String, names: NameRegistry): JSONObject {
        val sel = AetherSelection.fromAetherUri(line) ?: throw ParseError()
        val defaultLabel = when (sel.mode) {
            AetherMode.MasqueInMasque ->
                if (sel.chainTarget == "psiphon") "Cat MIM + Psiphon (${sel.psiphonRegion.ifBlank { "Auto" }})"
                else "Cat MASQUE in MASQUE"
            AetherMode.WarpInWarp -> "Cat Warp in WARP"
            AetherMode.WireGuardOverMasque -> "Cat Warp WG over MASQUE"
            AetherMode.WireGuard -> "Cat Warp"
            else -> "Cat Warp MASQUE H2"
        }
        val label = sel.profileName.takeIf { it.isNotBlank() && it != "Cat Warp" } ?: defaultLabel
        return JSONObject()
            .put("name", names.register(label))
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", 1819)
            .put("udp", true)
            // beta206: keep a PattNG `aether://` link byte-for-byte. Re-exporting it rewrote PattNG's semantics
            // (fragment, fingerprint, sni defaults) into ours, so the profile that connects in PattNG was not the
            // one Cat dialled. `masque://` (no PattNG form) is still normalised.
            .put("aether-uri", if (line.trim().startsWith("aether://", ignoreCase = true)) line.trim() else sel.toAetherUri())
    }

    /**
     * beta212: `sstp://user:pass@host:port?ip=…#remark`. The Cat core has no SSTP outbound, so the row is a loopback
     * placeholder (port [SstpLink.PLACEHOLDER_PORT], nothing listens there) and the VPN service runs the SSTP tunnel
     * itself when the row is picked; the original link is kept in `sstp-uri`.
     */
    private fun parseSstp(line: String, names: NameRegistry): JSONObject {
        val link = SstpLink.parse(line) ?: throw ParseError()
        val label = link.remark.ifBlank { "Cat SSTP · ${link.host}" }
        return JSONObject()
            .put("name", names.register(label))
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", SstpLink.PLACEHOLDER_PORT)
            .put("udp", false)
            .put("sstp-uri", link.toUri())
    }

    private fun parseFenixVpn(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val query = parseQuery(uri.rawQuery)
        val rawPort = uri.port.takeIf { it in 1..65535 } ?: FirefoxVpnEngine.activeProxyPort()
        val countryCode = query["country"].orEmpty().uppercase().ifBlank { "DE" }
        val defaultName = "🦊 Cat Fox VPN ($countryCode)"
        return JSONObject()
            .put("name", names.register(name(uri).ifBlank { defaultName }))
            .put("type", "socks5")
            .put("server", "127.0.0.1")
            .put("port", rawPort)
            // Mozilla's proxy only does HTTP CONNECT (TCP); declaring UDP made mihomo send UDP ASSOCIATE that always failed.
            .put("udp", false)
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
        val credentials = if (':' in rawCredentials || "%3A" in rawCredentials || "%3a" in rawCredentials) {
            decodeSocksUserInfo(rawCredentials)
        } else {
            decodeBase64Text(decodeSocksUserInfo(rawCredentials)) ?: throw ParseError()
        }
        val separator = credentials.indexOf(':')
        if (separator < 1) throw ParseError()
        val endpoint = parseHostPort(authority.substring(at + 1)) ?: throw ParseError()
        val (validCipher, validPassword) = normalizeAndValidateShadowsocksCredentials(
            cipherRaw = credentials.substring(0, separator),
            passwordRaw = credentials.substring(separator + 1),
        )
        val proxy = JSONObject()
            .put("name", names.register(decode(fragmentSplit.getOrNull(1).orEmpty())))
            .put("type", "ss")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", validPassword)
            .put("cipher", validCipher)
            .put("udp", true)
        query["plugin"]?.let { plugin -> applyPlugin(proxy, plugin) }
        if (query["udp-over-tcp"] == "true" || query["uot"] == "1") {
            proxy.put("udp-over-tcp", true)
        }
        return proxy
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun normalizeAndValidateShadowsocksCredentials(
        cipherRaw: String,
        passwordRaw: String,
    ): Pair<String, String> {
        val cipher = cipherRaw.trim().lowercase()
        val password = passwordRaw.trim()
        if (cipher.isEmpty() || password.isEmpty()) throw ParseError()

        if (cipher.startsWith("2022-blake3-")) {
            val expectedKeyLen = when (cipher) {
                "2022-blake3-aes-128-gcm" -> 16
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
                -> 32
                else -> throw ParseError()
            }
            val keyParts = password.split(':').filter { it.isNotBlank() }
            if (keyParts.isEmpty()) throw ParseError()
            val normalizedParts = keyParts.map { rawPart ->
                // Restore '+' if a previous URLDecoder turned '+' into ' ' in a base64 key,
                // convert URL-safe alphabet ('-', '_') to standard ('+', '/'), and pad with '='.
                val stdAlphabet = rawPart.trim()
                    .replace(' ', '+')
                    .replace('-', '+')
                    .replace('_', '/')
                val noPad = stdAlphabet.trimEnd('=')
                if (noPad.isEmpty() || !noPad.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }) {
                    throw ParseError()
                }
                val padded = noPad + "=".repeat((4 - noPad.length % 4) % 4)
                val decodedBytes = runCatching { Base64.Default.decode(padded) }.getOrNull()
                    ?: throw ParseError()
                if (decodedBytes.size != expectedKeyLen) {
                    throw ParseError()
                }
                Base64.Default.encode(decodedBytes)
            }
            return cipher to normalizedParts.joinToString(":")
        }

        val supportedLegacyAndAeadCiphers = setOf(
            "aes-128-gcm",
            "aes-192-gcm",
            "aes-256-gcm",
            "chacha20-ietf-poly1305",
            "xchacha20-ietf-poly1305",
            "aes-128-cfb",
            "aes-192-cfb",
            "aes-256-cfb",
            "aes-128-ctr",
            "aes-192-ctr",
            "aes-256-ctr",
            "rc4-md5",
            "chacha20",
            "chacha20-ietf",
            "none",
            "dummy",
        )
        if (cipher !in supportedLegacyAndAeadCiphers) throw ParseError()
        return cipher to password
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
        listOf("version", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4").forEach { key ->
            query[key]?.trim()?.toIntOrNull()?.let { put(key, it) }
        }
        // H1-H4 are uint32 (often > Int.MAX_VALUE) and AmneziaWG 2.0 allows ranges ("a-b").
        // Parsing them as Int silently dropped real values: the handshake then used the
        // default headers and the tunnel "connected" but carried no data.
        listOf("h1", "h2", "h3", "h4").forEach { key ->
            AmneziaHeaders.value(query[key])?.let { put(key, it) }
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
            "j1",
            "j2",
            "j3",
        ).forEach { key ->
            query[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
        }
        query["itime"]?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { put("itime", it) }
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

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun sanitizeUriComponent(raw: String, allowBrackets: Boolean = false): String {
        val sb = StringBuilder(raw.length + 16)
        for (i in raw.indices) {
            val ch = raw[i]
            when {
                ch == '%' -> {
                    val hasValidHex = i + 2 < raw.length && isHexDigit(raw[i + 1]) && isHexDigit(raw[i + 2])
                    if (hasValidHex) sb.append('%') else sb.append("%25")
                }
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
