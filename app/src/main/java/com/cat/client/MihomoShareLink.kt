package com.cat.client

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Minimal YAML reader for a single Mihomo `proxies:` entry.
 *
 * [MihomoConfigParser] keeps the raw lines of every proxy it sees; this object turns those lines
 * into nested maps so [MihomoShareLink] can rebuild a share link. It understands exactly the
 * subset Mihomo subscriptions use: block mappings, block sequences of scalars, and the flow
 * collections (`{k: v}` / `[a, b]`) that [MihomoLinkConfigBuilder] and most panels emit.
 */
internal object MihomoProxyYaml {
    fun parse(block: String): Map<String, Any?> {
        val lines = block.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (lines.isEmpty()) return emptyMap()
        val cursor = intArrayOf(0)
        return parseMapping(lines, cursor, indentOf(lines[0]))
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)

    /** Index of the first `:` that separates a key from its value (followed by a space or EOL). */
    private fun separatorIndex(content: String): Int? {
        var quote: Char? = null
        content.forEachIndexed { index, char ->
            when {
                quote != null -> {
                    if (char == quote) quote = null
                }
                char == '\'' || char == '"' -> quote = char
                char == ':' && (index == content.lastIndex || content[index + 1] == ' ') -> return index
            }
        }
        return null
    }

    private fun parseMapping(lines: List<String>, cursor: IntArray, indent: Int): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        while (cursor[0] < lines.size) {
            val line = lines[cursor[0]]
            val lineIndent = indentOf(line)
            if (lineIndent < indent) return out
            val content = line.trim()
            if (lineIndent > indent || content.startsWith("- ")) {
                if (content.startsWith("- ") && lineIndent == indent) return out
                cursor[0]++
                continue
            }
            val separator = separatorIndex(content)
            if (separator == null) {
                cursor[0]++
                continue
            }
            val key = MihomoConfigParser.decodeScalar(content.substring(0, separator))
            val rawValue = content.substring(separator + 1).trim()
            cursor[0]++
            if (rawValue.isEmpty()) {
                val next = lines.getOrNull(cursor[0])
                out[key] = if (next != null && indentOf(next) > indent) {
                    if (next.trim().startsWith("- ")) {
                        parseSequence(lines, cursor, indentOf(next))
                    } else {
                        parseMapping(lines, cursor, indentOf(next))
                    }
                } else {
                    null
                }
            } else {
                out[key] = parseValue(rawValue)
            }
        }
        return out
    }

    private fun parseSequence(lines: List<String>, cursor: IntArray, indent: Int): List<Any?> {
        val out = ArrayList<Any?>()
        while (cursor[0] < lines.size) {
            val line = lines[cursor[0]]
            val lineIndent = indentOf(line)
            if (lineIndent < indent) return out
            val content = line.trim()
            if (lineIndent > indent || !content.startsWith("- ")) {
                cursor[0]++
                continue
            }
            val item = content.removePrefix("- ").trim()
            val looksLikeMapping = separatorIndex(item) != null &&
                item.first() !in "{['\""
            if (looksLikeMapping) {
                // `- key: value` starts an inline mapping; re-indent the first line and parse it.
                val rewritten = lines.toMutableList()
                rewritten[cursor[0]] = " ".repeat(indent + 2) + item
                out.add(parseMapping(rewritten, cursor, indent + 2))
            } else {
                out.add(parseValue(item))
                cursor[0]++
            }
        }
        return out
    }

    private fun parseValue(raw: String): Any? {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> FlowParser(trimmed).value()
            else -> MihomoConfigParser.decodeScalar(trimmed)
        }
    }

    /** Recursive-descent parser for YAML flow collections (`{a: 1, b: [x, y]}`). */
    private class FlowParser(private val source: String) {
        private var index = 0

        fun value(): Any? {
            skipWhitespace()
            if (index >= source.length) return ""
            return when (source[index]) {
                '{' -> mapping()
                '[' -> sequence()
                '\'', '"' -> quoted()
                else -> plain()
            }
        }

        private fun mapping(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            index++ // {
            while (true) {
                skipWhitespace()
                if (index >= source.length) return out
                if (source[index] == '}') {
                    index++
                    return out
                }
                if (source[index] == ',' || source[index] == ']') {
                    index++
                    continue
                }
                val before = index
                val key = when (source[index]) {
                    '\'', '"' -> quoted()
                    else -> plain(stopAtColon = true)
                }.toString()
                skipWhitespace()
                if (index < source.length && source[index] == ':') index++
                out[key] = value()
                if (index == before) index++ // malformed input: never stall
            }
        }

        private fun sequence(): List<Any?> {
            val out = ArrayList<Any?>()
            index++ // [
            while (true) {
                skipWhitespace()
                if (index >= source.length) return out
                if (source[index] == ']') {
                    index++
                    return out
                }
                if (source[index] == ',' || source[index] == '}') {
                    index++
                    continue
                }
                val before = index
                out.add(value())
                if (index == before) index++ // malformed input: never stall
            }
        }

        private fun quoted(): String {
            val quote = source[index]
            index++
            val out = StringBuilder()
            while (index < source.length) {
                val char = source[index]
                if (char == quote) {
                    if (quote == '\'' && source.getOrNull(index + 1) == '\'') {
                        out.append('\'')
                        index += 2
                        continue
                    }
                    index++
                    return out.toString()
                }
                if (char == '\\' && quote == '"' && index + 1 < source.length) {
                    out.append(source[index + 1])
                    index += 2
                    continue
                }
                out.append(char)
                index++
            }
            return out.toString()
        }

        private fun plain(stopAtColon: Boolean = false): String {
            val start = index
            while (index < source.length) {
                val char = source[index]
                if (char == ',' || char == '}' || char == ']') break
                if (stopAtColon && char == ':') break
                index++
            }
            return source.substring(start, index).trim()
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }
}

/**
 * Rebuilds a v2rayNG / V2Box / Streisand compatible share link from a Mihomo proxy entry, so a
 * single connection can be copied out of Cat Client into any other client.
 *
 * Supported: vless, vmess, trojan, ss, hysteria2, anytls, socks5. WireGuard, TUIC and Mihomo
 * groups have no widely shared URI form and return `null`.
 */
object MihomoShareLink {
    fun fromYamlBlock(block: String): String? {
        if (block.isBlank()) return null
        return runCatching { build(MihomoProxyYaml.parse(block)) }.getOrNull()
    }

    fun build(proxy: Map<String, Any?>): String? {
        val type = proxy.str("type")?.lowercase() ?: return null
        val name = proxy.str("name").orEmpty()
        val server = proxy.str("server") ?: return null
        val port = proxy.str("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = hostLiteral(server)
        return when (type) {
            "vless" -> vless(proxy, name, host, port)
            "vmess" -> vmess(proxy, name, server, port)
            "trojan" -> trojan(proxy, name, host, port)
            "ss" -> shadowsocks(proxy, name, host, port)
            "hysteria2", "hy2" -> hysteria2(proxy, name, host, port)
            "anytls" -> anytls(proxy, name, host, port)
            "socks5", "socks" -> socks(proxy, name, host, port)
            else -> null
        }
    }

    /* ---------------------------------------------------------------- protocols */

    private fun vless(proxy: Map<String, Any?>, name: String, host: String, port: Int): String? {
        val uuid = proxy.str("uuid") ?: return null
        val params = LinkedHashMap<String, String>()
        params["encryption"] = proxy.str("encryption")?.takeIf { it != "none" } ?: "none"
        proxy.str("flow")?.let { params["flow"] = it }
        params += tlsParams(proxy, sniKey = "servername")
        params += transportParams(proxy)
        return "vless://$uuid@$host:$port" + query(params) + fragment(name)
    }

    private fun trojan(proxy: Map<String, Any?>, name: String, host: String, port: Int): String? {
        val password = proxy.str("password") ?: return null
        val params = LinkedHashMap<String, String>()
        // Mihomo treats trojan as TLS unless the entry says `tls: false` (Cat Panel plain ports).
        params += tlsParams(proxy, sniKey = "sni", alwaysTls = proxy["tls"] == null)
        params += transportParams(proxy)
        return "trojan://${encode(password)}@$host:$port" + query(params) + fragment(name)
    }

    private fun vmess(proxy: Map<String, Any?>, name: String, server: String, port: Int): String? {
        val uuid = proxy.str("uuid") ?: return null
        val transport = transportParams(proxy)
        val network = transport["type"] ?: "tcp"
        val tls = proxy.bool("tls") || proxy.child("reality-opts") != null
        val json = JSONObject()
            .put("v", "2")
            .put("ps", name)
            .put("add", server)
            .put("port", port.toString())
            .put("id", uuid)
            .put("aid", proxy.str("alterId")?.toIntOrNull()?.toString() ?: "0")
            .put("scy", proxy.str("cipher")?.takeIf { it != "null" } ?: "auto")
            .put("net", network)
            .put("type", transport["headerType"] ?: "none")
            .put("host", transport["host"].orEmpty())
            .put(
                "path",
                when (network) {
                    "grpc" -> transport["serviceName"].orEmpty()
                    else -> transport["path"].orEmpty()
                },
            )
            .put("tls", if (tls) "tls" else "")
            .put("sni", proxy.str("servername") ?: proxy.str("sni").orEmpty())
            .put("alpn", proxy.list("alpn")?.joinToString(",").orEmpty())
            .put("fp", proxy.str("client-fingerprint").orEmpty())
        val encoded = Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return "vmess://$encoded"
    }

    private fun shadowsocks(proxy: Map<String, Any?>, name: String, host: String, port: Int): String? {
        val cipher = proxy.str("cipher") ?: return null
        val password = proxy.str("password") ?: return null
        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$cipher:$password".toByteArray(Charsets.UTF_8))
        val params = LinkedHashMap<String, String>()
        val plugin = proxy.str("plugin")
        val pluginOpts = proxy.child("plugin-opts")
        if (plugin != null && pluginOpts != null) {
            val pluginName = when (plugin) {
                "obfs" -> "obfs-local"
                "v2ray-plugin" -> "v2ray-plugin"
                else -> plugin
            }
            val options = mutableListOf<String>()
            pluginOpts.str("mode")?.let { options.add(if (plugin == "obfs") "obfs=$it" else "mode=$it") }
            pluginOpts.str("host")?.let { options.add(if (plugin == "obfs") "obfs-host=$it" else "host=$it") }
            pluginOpts.str("path")?.let { options.add("path=$it") }
            if (pluginOpts.bool("tls")) options.add("tls")
            params["plugin"] = (listOf(pluginName) + options).joinToString(";")
        }
        return "ss://$userInfo@$host:$port" + query(params) + fragment(name)
    }

    private fun hysteria2(proxy: Map<String, Any?>, name: String, host: String, port: Int): String? {
        val password = proxy.str("password") ?: return null
        val params = LinkedHashMap<String, String>()
        (proxy.str("sni") ?: proxy.str("servername"))?.let { params["sni"] = it }
        params["insecure"] = if (proxy.bool("skip-cert-verify")) "1" else "0"
        proxy.str("obfs")?.let { params["obfs"] = it }
        proxy.str("obfs-password")?.let { params["obfs-password"] = it }
        proxy.list("alpn")?.takeIf { it.isNotEmpty() }?.let { params["alpn"] = it.joinToString(",") }
        proxy.str("fingerprint")?.let { params["pinSHA256"] = it }
        return "hysteria2://${encode(password)}@$host:$port" + query(params) + fragment(name)
    }

    private fun anytls(proxy: Map<String, Any?>, name: String, host: String, port: Int): String? {
        val password = proxy.str("password") ?: return null
        val params = LinkedHashMap<String, String>()
        (proxy.str("sni") ?: proxy.str("servername"))?.let { params["sni"] = it }
        params["insecure"] = if (proxy.bool("skip-cert-verify")) "1" else "0"
        proxy.str("client-fingerprint")?.let { params["fp"] = it }
        proxy.list("alpn")?.takeIf { it.isNotEmpty() }?.let { params["alpn"] = it.joinToString(",") }
        return "anytls://${encode(password)}@$host:$port" + query(params) + fragment(name)
    }

    private fun socks(proxy: Map<String, Any?>, name: String, host: String, port: Int): String {
        val username = proxy.str("username")
        val password = proxy.str("password")
        val credentials = if (username != null) {
            Base64.getEncoder().withoutPadding()
                .encodeToString("$username:${password.orEmpty()}".toByteArray(Charsets.UTF_8)) + "@"
        } else {
            ""
        }
        return "socks://$credentials$host:$port" + fragment(name)
    }

    /* ---------------------------------------------------------------- shared pieces */

    private fun tlsParams(
        proxy: Map<String, Any?>,
        sniKey: String,
        alwaysTls: Boolean = false,
    ): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        val reality = proxy.child("reality-opts")
        val tls = alwaysTls || proxy.bool("tls") || reality != null
        params["security"] = when {
            reality?.str("public-key") != null -> "reality"
            tls -> "tls"
            else -> "none"
        }
        if (!tls) return params
        (proxy.str(sniKey) ?: proxy.str("servername") ?: proxy.str("sni"))?.let { params["sni"] = it }
        proxy.str("client-fingerprint")?.let { params["fp"] = if (it == "random") "randomized" else it }
        proxy.list("alpn")?.takeIf { it.isNotEmpty() }?.let { params["alpn"] = it.joinToString(",") }
        if (proxy.bool("skip-cert-verify")) params["allowInsecure"] = "1"
        reality?.str("public-key")?.let { params["pbk"] = it }
        reality?.str("short-id")?.let { params["sid"] = it }
        proxy.str("fingerprint")?.let { params["pcs"] = it }
        return params
    }

    /**
     * Maps Mihomo `network` + `*-opts` to the `type/host/path/serviceName/headerType` query keys
     * every v2ray-family client understands.
     */
    private fun transportParams(proxy: Map<String, Any?>): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        when (proxy.str("network")?.lowercase() ?: "tcp") {
            "ws", "httpupgrade" -> {
                val network = proxy.str("network")!!.lowercase()
                params["type"] = network
                val options = proxy.child("ws-opts") ?: emptyMap<String, Any?>()
                var path = options.str("path") ?: "/"
                val earlyData = options.str("max-early-data")?.toIntOrNull() ?: 0
                if (network == "ws" && earlyData > 0 && !Regex("""[?&]ed=""").containsMatchIn(path)) {
                    path += (if ('?' in path) "&" else "?") + "ed=$earlyData"
                }
                val host = options.child("headers")?.let { headers ->
                    headers.str("Host") ?: headers.str("host")
                }
                host?.let { params["host"] = it }
                params["path"] = path
                options.str("early-data-header-name")
                    ?.takeIf { network == "ws" && !it.equals("Sec-WebSocket-Protocol", ignoreCase = true) }
                    ?.let { params["eh"] = it }
            }
            "grpc" -> {
                params["type"] = "grpc"
                val options = proxy.child("grpc-opts")
                params["serviceName"] = options?.str("grpc-service-name").orEmpty()
                params["mode"] = "gun"
            }
            "http" -> {
                params["type"] = "tcp"
                params["headerType"] = "http"
                val options = proxy.child("http-opts")
                options?.firstOf("path")?.let { params["path"] = it }
                options?.child("headers")?.firstOf("Host")?.let { params["host"] = it }
            }
            "h2" -> {
                params["type"] = "http"
                val options = proxy.child("h2-opts")
                options?.str("path")?.let { params["path"] = it }
                options?.firstOf("host")?.let { params["host"] = it }
            }
            "xhttp" -> {
                params["type"] = "xhttp"
                val options = proxy.child("xhttp-opts")
                options?.str("path")?.let { params["path"] = it }
                options?.str("host")?.let { params["host"] = it }
                options?.str("mode")?.let { params["mode"] = it }
            }
            else -> params["type"] = "tcp"
        }
        return params
    }

    private fun hostLiteral(server: String): String {
        val trimmed = server.trim()
        return if (':' in trimmed && !trimmed.startsWith("[")) "[$trimmed]" else trimmed
    }

    private fun query(params: Map<String, String>): String =
        if (params.isEmpty()) "" else params.entries.joinToString("&", prefix = "?") { (key, value) ->
            "$key=${encode(value)}"
        }

    private fun fragment(name: String): String = if (name.isBlank()) "" else "#" + encode(name)

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /* ---------------------------------------------------------------- map helpers */

    private fun Map<String, Any?>.str(key: String): String? = when (val value = this[key]) {
        null -> null
        is String -> value.takeIf { it.isNotBlank() && it != "null" }
        is Map<*, *>, is List<*> -> null
        else -> value.toString()
    }

    private fun Map<String, Any?>.bool(key: String): Boolean = this[key]?.toString().equals("true", ignoreCase = true)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.child(key: String): Map<String, Any?>? =
        (this[key] as? Map<*, *>)?.let { it as Map<String, Any?> }

    private fun Map<String, Any?>.list(key: String): List<String>? = when (val value = this[key]) {
        is List<*> -> value.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        is String -> value.split(',').map(String::trim).filter(String::isNotBlank)
        else -> null
    }

    /** First element for keys Mihomo accepts both as scalar and as list (`path`, `Host`). */
    private fun Map<String, Any?>.firstOf(key: String): String? = when (val value = this[key]) {
        is List<*> -> value.firstOrNull()?.toString()?.takeIf(String::isNotBlank)
        else -> str(key)
    }
}
