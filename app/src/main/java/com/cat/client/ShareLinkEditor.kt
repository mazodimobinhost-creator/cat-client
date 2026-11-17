package com.cat.client

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * v2rayNG / V2Box-style fields for one share link. Parse through [SubConvConverter], rebuild a
 * share URI. Certificate skipping is never written.
 */
internal data class ShareLinkDraft(
    val source: String,
    val type: String,
    val name: String = "",
    val server: String = "",
    val port: Int = 443,
    val uuid: String = "",
    val password: String = "",
    val alterId: String = "0",
    val cipher: String = "",
    val flow: String = "",
    val encryption: String = "none",
    val network: String = "tcp",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val headerType: String = "",
    val security: String = "none",
    val sni: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val obfs: String = "",
    val obfsPassword: String = "",
    val ports: String = "",
    val plugin: String = "",
    val username: String = "",
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val address: String = "",
    val reserved: String = "",
    val mode: String = "",
) {
    fun listLabel(): String {
        val title = name.ifBlank { server }
        return "$type · $server:$port · $title"
    }

    fun toLink(): String? {
        if (server.isBlank() || port !in 1..65535) return null
        return when (type.lowercase()) {
            "vless" -> vlessLink()
            "vmess" -> vmessLink()
            "trojan" -> trojanLink()
            "ss" -> ssLink()
            "hysteria2", "hy2" -> hy2Link()
            "hysteria" -> hysteriaLink()
            "anytls" -> anytlsLink()
            "socks5", "socks" -> socksLink()
            "http", "https" -> httpLink()
            "tuic" -> tuicLink()
            "wireguard" -> wireguardLink()
            else -> MihomoShareLink.build(jsonToMap(toJson())) ?: source.takeIf { it.contains("://") }
        }
    }

    fun toJson(): JSONObject {
        val obj = JSONObject()
            .put("name", name.ifBlank { server })
            .put("type", type)
            .put("server", server)
            .put("port", port)
            .put("udp", true)
            .put("skip-cert-verify", false)
        when (type.lowercase()) {
            "vless", "vmess", "tuic" -> if (uuid.isNotBlank()) obj.put("uuid", uuid)
            "trojan", "ss", "hysteria2", "hy2", "hysteria", "anytls" -> if (password.isNotBlank()) obj.put("password", password)
            "socks5", "socks" -> {
                if (username.isNotBlank()) obj.put("username", username)
                if (password.isNotBlank()) obj.put("password", password)
            }
            "wireguard" -> {
                if (privateKey.isNotBlank()) obj.put("private-key", privateKey)
                if (peerPublicKey.isNotBlank()) obj.put("public-key", peerPublicKey)
            }
        }
        if (type.equals("vmess", true)) {
            obj.put("alterId", alterId.toIntOrNull() ?: 0)
            obj.put("cipher", cipher.ifBlank { "auto" })
        }
        if (flow.isNotBlank()) obj.put("flow", flow)
        if (encryption.isNotBlank()) obj.put("encryption", encryption)
        val net = network.ifBlank { "tcp" }
        obj.put("network", net)
        when (net) {
            "ws", "httpupgrade" -> {
                val ws = JSONObject().put("path", path.ifBlank { "/" })
                if (host.isNotBlank()) ws.put("headers", JSONObject().put("Host", host))
                if (net == "httpupgrade") ws.put("v2ray-http-upgrade", true)
                obj.put("ws-opts", ws)
            }
            "grpc" -> obj.put("grpc-opts", JSONObject().put("grpc-service-name", serviceName))
            "h2" -> {
                val h2 = JSONObject()
                if (path.isNotBlank()) h2.put("path", path)
                if (host.isNotBlank()) h2.put("host", JSONArray().put(host))
                obj.put("h2-opts", h2)
            }
            "xhttp" -> {
                val x = JSONObject()
                if (path.isNotBlank()) x.put("path", path)
                if (host.isNotBlank()) x.put("host", host)
                if (mode.isNotBlank()) x.put("mode", mode)
                obj.put("xhttp-opts", x)
            }
            "http" -> {
                val http = JSONObject()
                if (path.isNotBlank()) http.put("path", JSONArray().put(path))
                if (host.isNotBlank()) http.put("headers", JSONObject().put("Host", JSONArray().put(host)))
                obj.put("http-opts", http)
            }
        }
        val reality = publicKey.isNotBlank()
        val tlsOn = security.equals("tls", true) || security.equals("reality", true) || reality
        if (tlsOn) obj.put("tls", true)
        val sniKey = if (type.lowercase() in setOf("trojan", "hysteria2", "hy2", "hysteria", "tuic", "anytls")) "sni" else "servername"
        if (sni.isNotBlank()) obj.put(sniKey, sni)
        if (fingerprint.isNotBlank()) obj.put("client-fingerprint", fingerprint)
        if (alpn.isNotBlank()) obj.put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
        if (reality) {
            obj.put(
                "reality-opts",
                JSONObject().put("public-key", publicKey).apply {
                    if (shortId.isNotBlank()) put("short-id", shortId)
                },
            )
        }
        if (obfs.isNotBlank() && obfsPassword.isNotBlank()) {
            obj.put("obfs", obfs)
            obj.put("obfs-password", obfsPassword)
        }
        if (ports.isNotBlank()) obj.put("ports", ports)
        if (plugin.isNotBlank()) obj.put("plugin", plugin)
        if (cipher.isNotBlank() && type.equals("ss", true)) obj.put("cipher", cipher)
        return obj
    }

    private fun vlessLink(): String? {
        if (uuid.isBlank()) return null
        val q = LinkedHashMap<String, String>()
        q["encryption"] = encryption.ifBlank { "none" }
        if (flow.isNotBlank()) q["flow"] = flow
        q["type"] = network.ifBlank { "tcp" }
        if (headerType.isNotBlank()) q["headerType"] = headerType
        if (host.isNotBlank()) q["host"] = host
        if (path.isNotBlank()) q["path"] = path
        if (serviceName.isNotBlank()) q["serviceName"] = serviceName
        if (mode.isNotBlank()) q["mode"] = mode
        q["security"] = when {
            publicKey.isNotBlank() -> "reality"
            security.isBlank() -> "none"
            else -> security
        }
        if (sni.isNotBlank()) q["sni"] = sni
        if (fingerprint.isNotBlank()) q["fp"] = fingerprint
        if (alpn.isNotBlank()) q["alpn"] = alpn
        if (publicKey.isNotBlank()) q["pbk"] = publicKey
        if (shortId.isNotBlank()) q["sid"] = shortId
        if (spiderX.isNotBlank()) q["spx"] = spiderX
        return "vless://$uuid@${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    private fun trojanLink(): String? {
        if (password.isBlank()) return null
        val q = LinkedHashMap<String, String>()
        q["security"] = when {
            publicKey.isNotBlank() -> "reality"
            security.isBlank() -> "tls"
            else -> security
        }
        q["type"] = network.ifBlank { "tcp" }
        if (host.isNotBlank()) q["host"] = host
        if (path.isNotBlank()) q["path"] = path
        if (serviceName.isNotBlank()) q["serviceName"] = serviceName
        if (sni.isNotBlank()) q["sni"] = sni
        if (fingerprint.isNotBlank()) q["fp"] = fingerprint
        if (alpn.isNotBlank()) q["alpn"] = alpn
        if (publicKey.isNotBlank()) q["pbk"] = publicKey
        if (shortId.isNotBlank()) q["sid"] = shortId
        return "trojan://${enc(password)}@${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    private fun vmessLink(): String? {
        if (uuid.isBlank()) return null
        val tlsOn = publicKey.isNotBlank() || security.equals("tls", true) || security.equals("reality", true)
        val json = JSONObject()
            .put("v", "2")
            .put("ps", name)
            .put("add", server)
            .put("port", port.toString())
            .put("id", uuid)
            .put("aid", alterId.ifBlank { "0" })
            .put("scy", cipher.ifBlank { "auto" })
            .put("net", network.ifBlank { "tcp" })
            .put("type", headerType.ifBlank { "none" })
            .put("host", host)
            .put("path", if (network == "grpc") serviceName else path)
            .put("tls", if (tlsOn) "tls" else "")
            .put("sni", sni)
            .put("alpn", alpn)
            .put("fp", fingerprint)
        val b64 = Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return "vmess://$b64"
    }

    private fun ssLink(): String? {
        if (cipher.isBlank() || password.isBlank()) return null
        val user = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$cipher:$password".toByteArray(Charsets.UTF_8))
        val q = LinkedHashMap<String, String>()
        if (plugin.isNotBlank()) q["plugin"] = plugin
        return "ss://$user@${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    private fun hy2Link(): String? {
        if (password.isBlank()) return null
        val q = LinkedHashMap<String, String>()
        if (sni.isNotBlank()) q["sni"] = sni
        q["insecure"] = "0"
        if (obfs.isNotBlank() && obfsPassword.isNotBlank()) {
            q["obfs"] = obfs
            q["obfs-password"] = obfsPassword
        }
        if (alpn.isNotBlank()) q["alpn"] = alpn
        if (ports.isNotBlank()) q["mport"] = ports
        return "hysteria2://${enc(password)}@${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    private fun hysteriaLink(): String {
        val q = LinkedHashMap<String, String>()
        if (password.isNotBlank()) q["auth"] = password
        if (sni.isNotBlank()) q["peer"] = sni
        q["insecure"] = "0"
        if (obfsPassword.isNotBlank()) q["obfsParam"] = obfsPassword
        if (alpn.isNotBlank()) q["alpn"] = alpn
        if (ports.isNotBlank()) q["mport"] = ports
        return "hysteria://${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    private fun anytlsLink(): String? {
        if (password.isBlank()) return null
        val q = LinkedHashMap<String, String>()
        if (sni.isNotBlank()) q["sni"] = sni
        q["insecure"] = "0"
        if (fingerprint.isNotBlank()) q["fp"] = fingerprint
        if (alpn.isNotBlank()) q["alpn"] = alpn
        return "anytls://${enc(password)}@${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    private fun httpLink(): String {
        val creds = if (username.isNotBlank()) "${enc(username)}:${enc(password)}@" else ""
        return "$type://$creds${hostLiteral(server)}:$port${fragment(name)}"
    }

    private fun socksLink(): String {
        val creds = if (username.isNotBlank()) {
            Base64.getEncoder().withoutPadding()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8)) + "@"
        } else {
            ""
        }
        return "socks://$creds${hostLiteral(server)}:$port${fragment(name)}"
    }

    private fun tuicLink(): String? {
        if (uuid.isBlank()) return null
        val user = enc(uuid) + if (password.isNotBlank()) ":${enc(password)}" else ""
        val q = LinkedHashMap<String, String>()
        if (sni.isNotBlank()) q["sni"] = sni
        q["congestion_control"] = cipher.ifBlank { "bbr" }
        q["udp_relay_mode"] = mode.ifBlank { "native" }
        if (alpn.isNotBlank()) q["alpn"] = alpn
        return "tuic://$user@${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    private fun wireguardLink(): String? {
        val key = privateKey.ifBlank { "auto" }
        val q = LinkedHashMap<String, String>()
        if (peerPublicKey.isNotBlank()) q["publickey"] = peerPublicKey
        if (address.isNotBlank()) q["address"] = address
        if (reserved.isNotBlank()) q["reserved"] = reserved
        return "wireguard://${enc(key)}@${hostLiteral(server)}:$port${query(q)}${fragment(name)}"
    }

    companion object {
        private val URL_SCHEMES = setOf("http", "https")
        private val SKIP_SCHEMES = setOf("aether", "masque")

        fun lines(input: String): List<Pair<Int, String>> =
            input.lines().mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
                val scheme = line.substringBefore("://", "").lowercase()
                if (scheme.isEmpty() || scheme in SKIP_SCHEMES) return@mapIndexedNotNull null
                if (scheme in URL_SCHEMES && "@" !in line.substringBefore('#')) return@mapIndexedNotNull null
                if ("://" in line) index to line else null
            }

        fun parse(link: String): ShareLinkDraft? {
            val trimmed = link.trim()
            if (trimmed.isEmpty()) return null
            val obj = SubConvConverter.convert(trimmed).firstOrNull() ?: return null
            val type = obj.optString("type").ifBlank { return null }
            val extra = queryOf(trimmed)
            val reality = obj.optJSONObject("reality-opts")
            val pbk = reality?.optString("public-key").orEmpty().ifBlank { extra["pbk"].orEmpty() }
            val sid = reality?.optString("short-id").orEmpty().ifBlank { extra["sid"].orEmpty() }
            val net = obj.optString("network").ifBlank { extra["type"].orEmpty().ifBlank { "tcp" } }
            val ws = obj.optJSONObject("ws-opts")
            val wsHeaders = ws?.optJSONObject("headers")
            val h2 = obj.optJSONObject("h2-opts")
            val http = obj.optJSONObject("http-opts")
            val xhttp = obj.optJSONObject("xhttp-opts")
            val grpc = obj.optJSONObject("grpc-opts")
            val path = when (net) {
                "ws", "httpupgrade" -> ws?.optString("path").orEmpty()
                "h2" -> h2?.optString("path").orEmpty()
                "http" -> firstString(http?.opt("path"))
                "xhttp" -> xhttp?.optString("path").orEmpty()
                else -> extra["path"].orEmpty()
            }
            val hostHeader = when (net) {
                "ws", "httpupgrade" -> wsHeaders?.optString("Host")?.ifBlank { wsHeaders.optString("host") }.orEmpty()
                "h2" -> firstString(h2?.opt("host"))
                "http" -> firstString(http?.optJSONObject("headers")?.opt("Host"))
                "xhttp" -> xhttp?.optString("host").orEmpty()
                else -> extra["host"].orEmpty()
            }
            val tls = obj.optBoolean("tls") || pbk.isNotBlank() || extra["security"].equals("tls", true) || extra["security"].equals("reality", true)
            val security = when {
                pbk.isNotBlank() || extra["security"].equals("reality", true) -> "reality"
                tls -> "tls"
                extra["security"].equals("none", true) -> "none"
                else -> "none"
            }
            return ShareLinkDraft(
                source = trimmed,
                type = type,
                name = obj.optString("name"),
                server = obj.optString("server"),
                port = obj.optInt("port").takeIf { it in 1..65535 } ?: 443,
                uuid = obj.optString("uuid"),
                password = obj.optString("password"),
                alterId = obj.opt("alterId")?.toString()?.substringBefore('.') ?: "0",
                cipher = obj.optString("cipher").ifBlank { obj.optString("congestion-controller") },
                flow = obj.optString("flow").ifBlank { extra["flow"].orEmpty() },
                encryption = obj.optString("encryption").ifBlank { extra["encryption"].orEmpty().ifBlank { "none" } },
                network = net,
                path = path.ifBlank { extra["path"].orEmpty() },
                host = hostHeader.ifBlank { extra["host"].orEmpty() },
                serviceName = grpc?.optString("grpc-service-name").orEmpty().ifBlank { extra["servicename"].orEmpty() },
                headerType = extra["headertype"].orEmpty(),
                security = security,
                sni = obj.optString("servername").ifBlank { obj.optString("sni") }.ifBlank { extra["sni"].orEmpty() },
                fingerprint = obj.optString("client-fingerprint").ifBlank { extra["fp"].orEmpty() },
                alpn = alpnOf(obj).ifBlank { extra["alpn"].orEmpty() },
                publicKey = pbk,
                shortId = sid,
                spiderX = extra["spx"].orEmpty(),
                obfs = obj.optString("obfs").ifBlank { extra["obfs"].orEmpty() },
                obfsPassword = obj.optString("obfs-password").ifBlank { extra["obfs-password"].orEmpty() },
                ports = obj.optString("ports").ifBlank { extra["mport"].orEmpty() },
                plugin = obj.optString("plugin").ifBlank { extra["plugin"].orEmpty() },
                username = obj.optString("username"),
                privateKey = obj.optString("private-key").ifBlank { extra["privatekey"].orEmpty() },
                peerPublicKey = obj.optString("public-key").ifBlank { extra["publickey"].orEmpty() },
                address = obj.optString("ip").ifBlank { extra["address"].orEmpty() },
                reserved = reservedOf(obj).ifBlank { extra["reserved"].orEmpty() },
                mode = extra["mode"].orEmpty().ifBlank { obj.optString("udp-relay-mode") },
            )
        }

        private fun alpnOf(obj: JSONObject): String = when (val value = obj.opt("alpn")) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }.joinToString(",")
            is Collection<*> -> value.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }.joinToString(",")
            else -> obj.optString("alpn")
        }

        private fun reservedOf(obj: JSONObject): String = when (val value = obj.opt("reserved")) {
            is JSONArray -> (0 until value.length()).map { value.opt(it)?.toString() }.joinToString(",")
            else -> obj.optString("reserved")
        }

        private fun firstString(value: Any?): String = when (value) {
            null, JSONObject.NULL -> ""
            is JSONArray -> value.optString(0)
            is Collection<*> -> value.firstOrNull()?.toString().orEmpty()
            else -> value.toString()
        }

        private fun queryOf(link: String): Map<String, String> {
            val rest = link.substringAfter("://", "")
            val q = rest.substringBefore('#').substringAfter('?', "")
            if (q.isEmpty()) return emptyMap()
            return q.split('&').mapNotNull { kv ->
                val key = kv.substringBefore('=').trim().lowercase()
                if (key.isEmpty()) null else key to decode(kv.substringAfter('=', ""))
            }.toMap()
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

        private fun enc(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun query(params: Map<String, String>): String =
            if (params.isEmpty()) "" else params.entries.joinToString("&", prefix = "?") { (k, v) -> "$k=${enc(v)}" }

        private fun fragment(name: String): String = if (name.isBlank()) "" else "#" + enc(name)

        private fun hostLiteral(server: String): String {
            val clean = server.trim().removePrefix("[").removeSuffix("]")
            return if (':' in clean) "[$clean]" else clean
        }

        internal fun jsonToMap(obj: JSONObject): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                out[key] = wrap(obj.opt(key))
            }
            return out
        }

        private fun wrap(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonToMap(value)
            is JSONArray -> (0 until value.length()).map { wrap(value.opt(it)) }
            else -> value
        }
    }
}

internal object ShareLinkEditor {
    fun lines(input: String): List<Pair<Int, String>> = ShareLinkDraft.lines(input)
    fun parse(link: String): ShareLinkDraft? = ShareLinkDraft.parse(link)
}
