// Xray engine routing (beta223): which proxies need Xray-core, the Xray JSON config, and the YAML swap to local SOCKS.
package com.cat.client

/**
 * beta223 — the Xray engine.
 *
 * The Cat core (mihomo) silently ignores Xray-only options such as `finalMask` (the Irancell «F&F»
 * recipe `fm=tlshello-0-len`). beta221 approximated it with the ByeByeDPI fragmenting proxy; from
 * beta223 those proxies are carried by the real Xray-core instead:
 *
 * 1. [XrayEligibility] picks the proxies that carry `final-mask` (and nothing Xray could not run).
 * 2. [XrayConfigBuilder] turns each one into an Xray outbound (VLESS / Trojan / VMess with the same
 *    TLS / REALITY / ws / xhttp / grpc / httpupgrade settings, plus the real `finalmask`) behind its
 *    own `127.0.0.1` SOCKS5 inbound.
 * 3. [MihomoXrayPatcher] replaces exactly those proxy entries in the runtime YAML with
 *    `type: socks5, server: 127.0.0.1, port: <inbound>` under the **same name**, so groups, rules,
 *    the location list and the latency tests keep working unchanged.
 *
 * Everything else stays on mihomo. If Xray is missing or does not start, the service falls back to
 * the beta221 ByeByeDPI path (see `startMihomoRuntimeAttempt`).
 *
 * Pure Kotlin (no Android / org.json) so it is unit-tested on the JVM.
 */
internal object XrayEligibility {
    private val SUPPORTED_TYPES = setOf("vless", "trojan", "vmess")

    /** Why a proxy is (not) handed to Xray; `null` reason = eligible. */
    fun reason(proxy: Map<String, Any?>): String? {
        val type = proxy.str("type")?.lowercase() ?: return "no-type"
        if (type !in SUPPORTED_TYPES) return "type-$type"
        if (proxy.str("final-mask") == null && proxy.child("final-mask") == null) return "no-final-mask"
        val server = proxy.str("server") ?: return "no-server"
        if (server.trim().removePrefix("[").removeSuffix("]").let { it == "127.0.0.1" || it == "::1" || it == "localhost" }) {
            return "loopback"
        }
        val port = proxy.str("port")?.toIntOrNull()
        if (port == null || port !in 1..65535) return "bad-port"
        when (type) {
            "vless", "vmess" -> if (proxy.str("uuid") == null) return "no-uuid"
            "trojan" -> if (proxy.str("password") == null) return "no-password"
        }
        // Xray removed `allowInsecure`; such proxies stay on the beta221 path instead of failing Xray's start.
        if (proxy.bool("skip-cert-verify")) return "skip-cert-verify"
        if (proxy.child("ech-opts")?.bool("enable") == true) return "ech"
        if (proxy.str("dialer-proxy") != null) return "dialer-proxy"
        // mihomo's certificate pin format differs from Xray's pinnedPeerCertSha256; keep pinned proxies on mihomo.
        if (proxy.str("fingerprint") != null) return "cert-pin"
        val network = proxy.str("network")?.lowercase() ?: "tcp"
        if (network !in setOf("tcp", "ws", "httpupgrade", "xhttp", "grpc")) return "network-$network"
        if (XrayFinalMask.build(proxy) == null) return "final-mask-unreadable"
        return null
    }

    fun isEligible(proxy: Map<String, Any?>): Boolean = reason(proxy) == null
}

/**
 * Turns the share-link / YAML `finalMask` value into Xray's `streamSettings.finalmask` object.
 *
 * Accepted forms:
 * - Xray JSON: `{"tcp":[{"type":"fragment","settings":{…}}]}` (also `udp`) — passed through as-is.
 * - Legacy JSON `{"fragment":{"packets":"tlshello","length":"6,98,1","interval":"1"}}`.
 * - Short form `tlshello[-delay[-length]]`, e.g. the F&F `tlshello-0-len`: fragment the TLS
 *   ClientHello into several TLS records. Delay `0` makes Xray write all records at once (pure TLS
 *   record splitting); a non-numeric length (`len`) uses Xray's usual `100-200` bytes.
 * - A packet range such as `1-3` (what REALITY needs instead of `tlshello`), optionally followed by
 *   `-delay-length`.
 */
internal object XrayFinalMask {
    const val DEFAULT_LENGTH = "100-200"
    const val DEFAULT_DELAY = "10-20"

    fun build(proxy: Map<String, Any?>): String? {
        proxy.child("final-mask")?.let { map ->
            return if (map.containsKey("tcp") || map.containsKey("udp")) XrayJson.write(map) else null
        }
        return parse(proxy.str("final-mask") ?: return null)
    }

    fun parse(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("{")) return jsonForm(value)
        val tokens = value.split('-').map(String::trim)
        val head = tokens.first().lowercase()
        return if (head == "tlshello") {
            fragment(
                packets = "tlshello",
                delay = tokens.getOrNull(1).orEmpty(),
                length = tokens.drop(2).joinToString("-"),
                defaultDelay = "0",
            )
        } else {
            val from = tokens.getOrNull(0)?.toIntOrNull() ?: return null
            val to = tokens.getOrNull(1)?.toIntOrNull() ?: return null
            if (from < 1 || to < from) return null
            fragment(
                packets = "$from-$to",
                delay = tokens.getOrNull(2).orEmpty(),
                length = tokens.drop(3).joinToString("-"),
                defaultDelay = DEFAULT_DELAY,
            )
        }
    }

    private fun jsonForm(value: String): String? {
        if (!looksBalanced(value)) return null
        if (Regex(""""(tcp|udp)"\s*:\s*\[""").containsMatchIn(value)) return value
        // Legacy {"fragment":{"packets":…,"length":…,"interval":…,"maxSplit":…}}
        if (!value.contains("\"fragment\"")) return null
        fun field(name: String): String? =
            Regex(""""$name"\s*:\s*"?([^",}]*(?:,[^",}]*)*)"?""").find(value)?.groupValues?.get(1)?.trim()
        val packets = field("packets")?.takeIf { it.isNotBlank() } ?: "tlshello"
        return fragment(
            packets = packets,
            delay = field("interval") ?: field("delay").orEmpty(),
            length = field("length").orEmpty(),
            defaultDelay = if (packets.equals("tlshello", true)) "0" else DEFAULT_DELAY,
            maxSplit = field("maxSplit"),
        )
    }

    private fun fragment(
        packets: String,
        delay: String,
        length: String,
        defaultDelay: String,
        maxSplit: String? = null,
    ): String {
        val settings = LinkedHashMap<String, Any?>()
        settings["packets"] = packets
        val lengths = length.split(',').map(String::trim).filter { it.isNotEmpty() }
        when {
            lengths.size > 1 && lengths.all(::isRange) -> {
                // Xray: the last lengths entry must not start at 0.
                settings["lengths"] = lengths.mapIndexed { index, item ->
                    if (index == lengths.lastIndex && rangeMin(item) == 0) "1" else item
                }
            }
            lengths.size == 1 && isRange(lengths[0]) && rangeMin(lengths[0]) > 0 -> settings["length"] = lengths[0]
            else -> settings["length"] = DEFAULT_LENGTH
        }
        settings["delay"] = delay.takeIf(::isRange) ?: defaultDelay
        maxSplit?.takeIf(::isRange)?.let { settings["maxSplit"] = it }
        return XrayJson.write(mapOf("tcp" to listOf(mapOf("type" to "fragment", "settings" to settings))))
    }

    private fun isRange(text: String): Boolean = Regex("""^\d{1,5}(-\d{1,5})?$""").matches(text.trim())

    private fun rangeMin(text: String): Int = text.trim().substringBefore('-').toIntOrNull() ?: 0

    private fun looksBalanced(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !inString
    }
}

/** One proxy handed to Xray: its mihomo name, the local inbound port and the Xray outbound JSON. */
internal data class XrayRoute(val proxyName: String, val port: Int, val udp: Boolean, val outboundJson: String)

internal object XrayConfigBuilder {
    /** Resolvers Xray uses for proxy host names (most F&F configs use an IP and never need them). */
    val DNS_SERVERS = listOf("https+local://1.1.1.1/dns-query", "https+local://8.8.8.8/dns-query", "1.1.1.1", "8.8.8.8")

    private val XRAY_FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "randomizednoalpn", "unsafe",
    )

    /** mihomo `client-fingerprint` → Xray `fingerprint` (Xray rejects unknown names at start). */
    fun fingerprint(value: String?): String {
        val fp = value?.trim()?.lowercase().orEmpty()
        return when {
            fp.isEmpty() -> "chrome"
            fp in XRAY_FINGERPRINTS -> fp
            fp == "chrome120" -> "hellochrome_120"
            fp == "firefox120" -> "hellofirefox_120"
            fp.startsWith("safari") -> "safari"
            fp.startsWith("chrome") -> "chrome"
            fp.startsWith("firefox") -> "firefox"
            else -> "chrome"
        }
    }

    fun outbound(proxy: Map<String, Any?>, tag: String): String? {
        if (!XrayEligibility.isEligible(proxy)) return null
        val type = proxy.str("type")!!.lowercase()
        val address = proxy.str("server")!!.trim().removePrefix("[").removeSuffix("]")
        val port = proxy.str("port")!!.toInt()
        val settings = LinkedHashMap<String, Any?>()
        settings["address"] = address
        settings["port"] = port
        when (type) {
            "vless" -> {
                settings["id"] = proxy.str("uuid")
                settings["encryption"] = proxy.str("encryption") ?: "none"
                proxy.str("flow")?.let { settings["flow"] = it }
            }
            "vmess" -> {
                settings["id"] = proxy.str("uuid")
                settings["security"] = proxy.str("cipher") ?: "auto"
            }
            "trojan" -> settings["password"] = proxy.str("password")
        }
        val outbound = LinkedHashMap<String, Any?>()
        outbound["tag"] = tag
        outbound["protocol"] = type
        outbound["settings"] = settings
        outbound["streamSettings"] = streamSettings(proxy, type)
        return XrayJson.write(outbound)
    }

    private fun streamSettings(proxy: Map<String, Any?>, type: String): Map<String, Any?> {
        val stream = LinkedHashMap<String, Any?>()
        val network = proxy.str("network")?.lowercase() ?: "tcp"
        val wsOptions = proxy.child("ws-opts")
        val upgrade = wsOptions?.bool("v2ray-http-upgrade") == true || network == "httpupgrade"
        stream["network"] = when {
            network == "tcp" -> "raw"
            network == "ws" && upgrade -> "httpupgrade"
            else -> network
        }
        val reality = proxy.child("reality-opts")?.takeIf { it.str("public-key") != null }
        val tls = proxy.bool("tls") || reality != null || type == "trojan"
        val serverName = proxy.str("servername") ?: proxy.str("sni")
        val alpn = proxy.list("alpn")
        val fp = fingerprint(proxy.str("client-fingerprint"))
        when {
            reality != null -> {
                stream["security"] = "reality"
                stream["realitySettings"] = linkedMapOf<String, Any?>(
                    "serverName" to serverName,
                    "fingerprint" to if (fp == "unsafe") "chrome" else fp,
                    "publicKey" to reality.str("public-key"),
                    "shortId" to reality.str("short-id").orEmpty(),
                ).filterValues { it != null }
            }
            tls -> {
                stream["security"] = "tls"
                val tlsSettings = LinkedHashMap<String, Any?>()
                serverName?.let { tlsSettings["serverName"] = it }
                alpn?.takeIf { it.isNotEmpty() }?.let { tlsSettings["alpn"] = it }
                tlsSettings["fingerprint"] = fp
                proxy.str("cipher-suites")?.let { tlsSettings["cipherSuites"] = it }
                stream["tlsSettings"] = tlsSettings
            }
            else -> stream["security"] = "none"
        }
        when (stream["network"]) {
            "ws", "httpupgrade" -> {
                val ws = LinkedHashMap<String, Any?>()
                var path = wsOptions?.str("path") ?: "/"
                val earlyData = wsOptions?.str("max-early-data")?.toIntOrNull() ?: 0
                if (stream["network"] == "ws" && earlyData > 0 && !Regex("""[?&]ed=""").containsMatchIn(path)) {
                    path += (if ('?' in path) "&" else "?") + "ed=$earlyData"
                }
                ws["path"] = path
                val headers = wsOptions?.child("headers").orEmpty()
                (headers.str("Host") ?: headers.str("host") ?: serverName)?.let { ws["host"] = it }
                val extra = headers.filterKeys { !it.equals("host", ignoreCase = true) }
                    .mapNotNull { (key, value) -> value?.toString()?.takeIf { it.isNotBlank() }?.let { key to it } }
                    .toMap()
                if (extra.isNotEmpty()) ws["headers"] = extra
                stream[if (stream["network"] == "ws") "wsSettings" else "httpupgradeSettings"] = ws
            }
            "xhttp" -> {
                val options = proxy.child("xhttp-opts").orEmpty()
                stream["xhttpSettings"] = linkedMapOf<String, Any?>(
                    "path" to (options.str("path") ?: "/"),
                    "host" to (options.str("host") ?: serverName),
                    "mode" to (options.str("mode") ?: "auto"),
                ).filterValues { it != null }
            }
            "grpc" -> stream["grpcSettings"] = mapOf(
                "serviceName" to proxy.child("grpc-opts")?.str("grpc-service-name").orEmpty(),
            )
        }
        stream["sockopt"] = mapOf("domainStrategy" to "UseIP")
        XrayFinalMask.build(proxy)?.let { stream["finalmask"] = XrayJson.Raw(it) }
        return stream
    }

    /** The complete Xray config: one SOCKS5 inbound per route, routed 1:1 to its outbound. */
    fun config(routes: List<XrayRoute>): String {
        val inbounds = routes.mapIndexed { index, route ->
            linkedMapOf<String, Any?>(
                "tag" to "cat-in-$index",
                "listen" to "127.0.0.1",
                "port" to route.port,
                "protocol" to "socks",
                "settings" to linkedMapOf("auth" to "noauth", "udp" to route.udp, "ip" to "127.0.0.1"),
            )
        }
        val outbounds = routes.map { XrayJson.Raw(it.outboundJson) } +
            listOf(mapOf("tag" to "cat-block", "protocol" to "blackhole"))
        val rules = routes.mapIndexed { index, _ ->
            mapOf("type" to "field", "inboundTag" to listOf("cat-in-$index"), "outboundTag" to "cat-out-$index")
        }
        return XrayJson.write(
            linkedMapOf(
                "log" to mapOf("loglevel" to "warning"),
                "dns" to mapOf("servers" to DNS_SERVERS),
                "inbounds" to inbounds,
                "outbounds" to outbounds,
                "routing" to mapOf("domainStrategy" to "AsIs", "rules" to rules),
            ),
        )
    }
}

internal data class XrayPatchResult(
    val yaml: String,
    val routes: List<XrayRoute>,
) {
    val applied: Boolean get() = routes.isNotEmpty()
    val configJson: String? get() = if (routes.isEmpty()) null else XrayConfigBuilder.config(routes)
}

/**
 * Swaps the eligible proxy entries of a runtime YAML for local SOCKS5 entries pointing at Xray.
 * Same block layout as [MihomoDpiBypassPatcher]: entries are `  - ` items under `proxies:`.
 */
internal object MihomoXrayPatcher {
    /** Hard cap: one inbound per proxy; huge subscriptions keep the rest on the beta221 path. */
    const val MAX_ROUTES = 64

    fun eligibleNames(rawYaml: String): List<String> =
        proxyBlocks(rawYaml).mapNotNull { block ->
            val proxy = parseBlock(block) ?: return@mapNotNull null
            proxy.str("name")?.takeIf { XrayEligibility.isEligible(proxy) }
        }.distinct().take(MAX_ROUTES)

    /**
     * The YAML as it looks once Xray has taken its proxies (placeholder ports). The service checks this for
     * `final-mask: tlshello` entries Xray could not take, which still go through ByeByeDPI.
     */
    fun withoutXrayProxies(rawYaml: String): String {
        val names = eligibleNames(rawYaml)
        return patch(rawYaml, List(names.size) { 1 + it }).yaml
    }

    fun patch(rawYaml: String, ports: List<Int>): XrayPatchResult {
        if (ports.isEmpty()) return XrayPatchResult(rawYaml, emptyList())
        val normalized = rawYaml.replace("\r\n", "\n").replace('\r', '\n')
        val output = mutableListOf<String>()
        val routes = mutableListOf<XrayRoute>()
        val usedNames = HashSet<String>()
        var inProxies = false
        var currentProxy = mutableListOf<String>()

        fun flushProxy() {
            if (currentProxy.isEmpty()) return
            val block = currentProxy
            currentProxy = mutableListOf()
            if (routes.size >= ports.size) {
                output += block
                return
            }
            val proxy = parseBlock(block)
            val name = proxy?.str("name")
            if (proxy == null || name == null || name in usedNames || !XrayEligibility.isEligible(proxy)) {
                output += block
                return
            }
            val index = routes.size
            val outbound = XrayConfigBuilder.outbound(proxy, "cat-out-$index")
            if (outbound == null) {
                output += block
                return
            }
            usedNames += name
            val port = ports[index]
            val udp = proxy["udp"]?.toString()?.equals("false", ignoreCase = true) != true
            routes += XrayRoute(name, port, udp, outbound)
            output += "  - name: ${yamlSingleQuoted(name)}"
            output += "    type: socks5"
            output += "    server: 127.0.0.1"
            output += "    port: $port"
            output += "    udp: $udp"
        }

        normalized.split('\n').forEach { line ->
            val topLevel = topLevelKey(line)
            if (topLevel != null) {
                if (inProxies) flushProxy()
                inProxies = topLevel == "proxies"
                output += line
                return@forEach
            }
            if (!inProxies) {
                output += line
                return@forEach
            }
            if (indentation(line) == 2 && line.trimStart().startsWith("- ")) {
                flushProxy()
                currentProxy += line
                return@forEach
            }
            if (currentProxy.isNotEmpty()) currentProxy += line else output += line
        }
        if (inProxies) flushProxy()
        return XrayPatchResult(output.joinToString("\n"), routes)
    }

    private fun proxyBlocks(rawYaml: String): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var inProxies = false
        var current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) blocks += current
            current = mutableListOf()
        }
        rawYaml.replace("\r\n", "\n").split('\n').forEach { line ->
            val topLevel = topLevelKey(line)
            if (topLevel != null) {
                if (inProxies) flush()
                inProxies = topLevel == "proxies"
                return@forEach
            }
            if (!inProxies) return@forEach
            if (indentation(line) == 2 && line.trimStart().startsWith("- ")) {
                flush()
                current += line
            } else if (current.isNotEmpty()) {
                current += line
            }
        }
        if (inProxies) flush()
        return blocks
    }

    /** Same normalisation as [MihomoConfigParser]'s rawYaml: `  - key: v` becomes `    key: v`. */
    private fun parseBlock(block: List<String>): Map<String, Any?>? {
        if (block.isEmpty()) return null
        val first = block.first()
        val item = first.trimStart().removePrefix("- ").trim()
        val text = if (item.startsWith("{")) item else (listOf("    $item") + block.drop(1)).joinToString("\n")
        return runCatching { MihomoProxyYaml.parse(text) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun yamlSingleQuoted(value: String): String = "'${value.replace("'", "''")}'"

    private fun topLevelKey(line: String): String? {
        if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return null
        return line.substringBefore(':').trim().takeIf { ':' in line && it.isNotBlank() }
    }

    private fun indentation(line: String): Int =
        line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
}

/** Tiny JSON writer (the JVM tests run without org.json). */
internal object XrayJson {
    /** Already-serialised JSON embedded verbatim. */
    class Raw(val json: String)

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Raw -> out.append(value.json)
            is String -> quote(out, value)
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                value.forEach { (key, item) ->
                    if (!first) out.append(',')
                    first = false
                    quote(out, key.toString())
                    out.append(':')
                    append(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        text.forEach { ch ->
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> out.append("\\u%04x".format(ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}

private fun Map<String, Any?>.str(key: String): String? = when (val value = this[key]) {
    null -> null
    is String -> value.trim().takeIf { it.isNotBlank() && it != "null" }
    is Map<*, *>, is List<*> -> null
    else -> value.toString()
}

private fun Map<String, Any?>.bool(key: String): Boolean = this[key]?.toString().equals("true", ignoreCase = true)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.child(key: String): Map<String, Any?>? =
    (this[key] as? Map<*, *>)?.let { it as Map<String, Any?> }

private fun Map<String, Any?>.list(key: String): List<String>? = when (val value = this[key]) {
    is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
    is String -> value.split(',').map(String::trim).filter(String::isNotBlank)
    else -> null
}
