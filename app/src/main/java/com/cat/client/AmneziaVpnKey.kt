package com.cat.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.zip.Inflater

/**
 * AmneziaVPN connection keys (`vpn://…`, what the Amnezia app shares with «Share → Amnezia format» and what
 * Amnezia-based sellers/bots hand out) → normal share links the rest of the app already runs.
 *
 * Key format (public, documented by the AmneziaVPN client): `vpn://` + URL-safe Base64 of either the JSON itself
 * (old keys) or Qt `qCompress` output = 4-byte big-endian length + a zlib stream of the JSON. The JSON lists
 * `containers`, each with the protocol block that holds the client config in `last_config`.
 *
 * Supported containers:
 *  - `amnezia-awg` / `amnezia-awg2` (AmneziaWG) and `amnezia-wireguard` → `awg://` / `wireguard://`
 *  - `amnezia-xray` (VLESS / Reality etc.) → `vless://` (also `vmess`/`trojan`/`ss` outbounds)
 *  - `amnezia-shadowsocks` → `ss://`
 * Not runnable here: OpenVPN, Cloak, IKEv2, and «Amnezia Premium / Free» keys that only carry an account token
 * (their servers are handed out by Amnezia's own API inside the Amnezia app). Those are reported, never guessed.
 */
object AmneziaVpnKey {
    data class Result(
        val links: List<String>,
        val serverName: String,
        val protocols: List<String>,
        /** Containers that exist in the key but cannot run in this app (plain-language names). */
        val unsupported: List<String>,
        /** Set when the key carries no client config at all (Premium/Free account key, admin key…). */
        val problemFa: String? = null,
        val problemEn: String? = null,
    )

    private const val PREFIX = "vpn://"
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024

    fun looksLikeKey(text: String): Boolean = text.trim().startsWith(PREFIX, ignoreCase = true)

    /** All `vpn://` keys found in [text] (one per line or whitespace separated). */
    fun extractKeys(text: String): List<String> =
        text.split(Regex("\\s+")).map { it.trim() }.filter { it.startsWith(PREFIX, ignoreCase = true) && it.length > 12 }

    /** Converts every key in [text]; keys that give no links are skipped (use [decode] to show why). */
    fun toUris(text: String): List<String> = extractKeys(text).flatMap { key -> decode(key)?.links.orEmpty() }

    fun decode(key: String): Result? {
        val json = decodeJson(key) ?: return null
        return runCatching { fromJson(JSONObject(json)) }.getOrNull()
    }

    /** Raw JSON text of a key, or null when it is not a valid Amnezia key. */
    fun decodeJson(key: String): String? {
        val payload = key.trim().let { if (it.startsWith(PREFIX, ignoreCase = true)) it.substring(PREFIX.length) else it }
            .trim().trimEnd('=').replace('+', '-').replace('/', '_')
        if (payload.isEmpty()) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val firstNonSpace = bytes.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() }
        if (firstNonSpace == '{'.code.toByte()) return String(bytes, Charsets.UTF_8)
        return inflateQt(bytes)?.let { String(it, Charsets.UTF_8) }?.takeIf { it.trimStart().startsWith("{") }
    }

    /** Qt qCompress: 4-byte big-endian uncompressed length, then zlib. Falls back to a bare zlib stream. */
    private fun inflateQt(bytes: ByteArray): ByteArray? {
        fun inflate(data: ByteArray, offset: Int): ByteArray? = runCatching {
            val inflater = Inflater()
            try {
                inflater.setInput(data, offset, data.size - offset)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_JSON_BYTES) return@runCatching null
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            } finally {
                inflater.end()
            }
        }.getOrNull()
        if (bytes.size > 6) inflate(bytes, 4)?.let { return it }
        return inflate(bytes, 0)
    }

    internal fun fromJson(root: JSONObject): Result {
        val description = root.optString("description").trim()
        val host = root.optString("hostName").trim()
        val serverName = description.ifBlank { host }.ifBlank { "Amnezia" }
        val dns1 = root.optString("dns1").trim().ifBlank { "1.1.1.1" }
        val dns2 = root.optString("dns2").trim().ifBlank { "1.0.0.1" }
        val containers = root.optJSONArray("containers") ?: JSONArray()
        val links = mutableListOf<String>()
        val protocols = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        for (i in 0 until containers.length()) {
            val c = containers.optJSONObject(i) ?: continue
            val type = c.optString("container").lowercase(Locale.US)
            val label = "$serverName · ${prettyContainer(type)}"
            val produced: List<String> = when {
                type.startsWith("amnezia-awg") || type == "amnezia-wireguard" -> {
                    val block = c.optJSONObject("awg") ?: c.optJSONObject("awg2") ?: c.optJSONObject("wireguard")
                    listOfNotNull(wireGuardLink(block, host, dns1, dns2, label))
                }
                type == "amnezia-xray" || type == "amnezia-ssxray" -> {
                    val block = c.optJSONObject("xray") ?: c.optJSONObject("ssxray")
                    xrayLinks(block?.optString("last_config").orEmpty(), label)
                }
                type == "amnezia-shadowsocks" -> {
                    listOfNotNull(shadowsocksLink(c.optJSONObject("shadowsocks")?.optString("last_config").orEmpty(), host, label))
                }
                else -> {
                    unsupported += prettyContainer(type)
                    emptyList()
                }
            }
            if (produced.isNotEmpty()) {
                links += produced
                protocols += prettyContainer(type)
            } else if (type.startsWith("amnezia-awg") || type == "amnezia-wireguard" || type == "amnezia-xray" ||
                type == "amnezia-ssxray" || type == "amnezia-shadowsocks"
            ) {
                unsupported += "${prettyContainer(type)} (بدون کانفیگ کاربر)"
            }
        }
        val accountOnly = links.isEmpty() && (root.has("api_key") || root.has("auth_data") || root.has("api_config") ||
            root.optString("configVersion").isNotBlank())
        val adminOnly = links.isEmpty() && root.has("userName") && root.has("password")
        val (fa, en) = when {
            links.isNotEmpty() -> null to null
            accountOnly ->
                "این کلید مال سرویس Amnezia Premium / Amnezia Free است و فقط یک توکن حساب دارد، نه کانفیگ سرور. سرورش را خودِ برنامهٔ Amnezia از سایت آن‌ها می‌گیرد؛ اینجا قابل اجرا نیست. از فروشنده کلید «AmneziaWG» یا فایل .conf بخواهید." to
                    "This is an Amnezia Premium / Free account key: it only holds an account token, not a server config. Only the Amnezia app can turn it into a server, so it cannot run here. Ask for an AmneziaWG key or a .conf file."
            adminOnly ->
                "این کلید «دسترسی کامل مدیر» است (ورود SSH به سرور) و کانفیگ کاربر ندارد. در برنامهٔ Amnezia یک کاربر بسازید و «اشتراک‌گذاری → AmneziaWG» را بفرستید." to
                    "This is a full-access admin key (SSH login to the server) without a user config. Create a user in the Amnezia app and share it as AmneziaWG."
            unsupported.isNotEmpty() ->
                "این کلید فقط پروتکل‌هایی دارد که این برنامه اجرا نمی‌کند (${unsupported.joinToString("، ")}). در برنامهٔ Amnezia پروتکل AmneziaWG یا XRay را برای این کاربر فعال کنید." to
                    "This key only has protocols this app cannot run (${unsupported.joinToString(", ")}). Enable AmneziaWG or XRay for this user in the Amnezia app."
            else -> "کلید Amnezia خالی است (هیچ پروتکلی ندارد)." to "The Amnezia key is empty (no protocols)."
        }
        return Result(links.distinct(), serverName, protocols.distinct(), unsupported.distinct(), fa, en)
    }

    private fun prettyContainer(type: String): String = when (type) {
        "amnezia-awg", "amnezia-awg2" -> "AmneziaWG"
        "amnezia-wireguard" -> "WireGuard"
        "amnezia-xray" -> "XRay (VLESS Reality)"
        "amnezia-ssxray" -> "Shadowsocks (XRay)"
        "amnezia-shadowsocks" -> "Shadowsocks"
        "amnezia-openvpn" -> "OpenVPN"
        "amnezia-cloak" -> "Cloak"
        "amnezia-ikev2" -> "IKEv2"
        "amnezia-sftp" -> "SFTP"
        "amnezia-dns" -> "AmneziaDNS"
        "amnezia-tor" -> "Tor website"
        "amnezia-socks5proxy" -> "SOCKS5"
        else -> type.removePrefix("amnezia-").ifBlank { "?" }
    }

    // ---- AmneziaWG / WireGuard ----------------------------------------------------------------------------

    private val AWG_FIELDS = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5")

    private fun wireGuardLink(block: JSONObject?, host: String, dns1: String, dns2: String, label: String): String? {
        block ?: return null
        val last = parseLastConfig(block.optString("last_config")) ?: return null
        var conf = last.optString("config")
        if (conf.isBlank() || !conf.contains("[Interface]", ignoreCase = true)) {
            conf = buildConf(block, last, host) ?: return null
        }
        val port = last.optString("port").ifBlank { block.optString("port") }
        conf = conf
            .replace("\$PRIMARY_DNS", dns1)
            .replace("\$SECONDARY_DNS", dns2)
            .replace("\$SERVER_IP_ADDRESS", last.optString("hostName").ifBlank { host })
            .replace("\$WIREGUARD_SERVER_PORT", port)
            .replace("\$AWG_SERVER_PORT", port)
        // MTU kept beside the text by some exports.
        val mtu = last.optString("mtu").trim()
        if (mtu.toIntOrNull()?.let { it in 576..9000 } == true && !Regex("(?im)^\\s*MTU\\s*=").containsMatchIn(conf)) {
            conf = conf.replaceFirst(Regex("(?i)\\[Interface]"), "[Interface]\nMTU = $mtu")
        }
        // Endpoint missing (some exports leave it to the app): rebuild from hostName + port.
        if (!Regex("(?im)^\\s*Endpoint\\s*=").containsMatchIn(conf)) {
            val epHost = last.optString("hostName").ifBlank { host }
            if (epHost.isBlank() || port.isBlank()) return null
            conf = conf.replace(Regex("(?i)\\[Peer]"), "[Peer]\nEndpoint = ${formatHost(epHost)}:$port")
        }
        val link = WireGuardConfText.toUris(conf).firstOrNull() ?: return null
        return link.substringBefore('#') + "#" + encodeFragment(label)
    }

    /** Builds a wg-quick text from the individual fields when the key has no ready `config`. */
    private fun buildConf(block: JSONObject, last: JSONObject, host: String): String? {
        val priv = last.optString("client_priv_key").ifBlank { return null }
        val serverPub = last.optString("server_pub_key").ifBlank { return null }
        val ip = last.optString("client_ip").ifBlank { return null }
        val epHost = last.optString("hostName").ifBlank { host }.ifBlank { return null }
        val port = last.optString("port").ifBlank { block.optString("port") }.ifBlank { return null }
        return buildString {
            appendLine("[Interface]")
            appendLine("Address = ${if ('/' in ip) ip else "$ip/32"}")
            appendLine("PrivateKey = $priv")
            last.optString("mtu").takeIf { it.isNotBlank() }?.let { appendLine("MTU = $it") }
            AWG_FIELDS.forEach { k ->
                val v = last.optString(k).ifBlank { block.optString(k) }
                if (v.isNotBlank()) appendLine("$k = $v")
            }
            appendLine("[Peer]")
            appendLine("PublicKey = $serverPub")
            last.optString("psk_key").takeIf { it.isNotBlank() }?.let { appendLine("PresharedKey = $it") }
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${formatHost(epHost)}:$port")
            appendLine("PersistentKeepalive = 25")
        }
    }

    private fun parseLastConfig(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    // ---- Shadowsocks ---------------------------------------------------------------------------------------

    private fun shadowsocksLink(lastConfig: String, host: String, label: String): String? {
        val o = parseLastConfig(lastConfig) ?: return null
        val server = o.optString("server").ifBlank { host }.ifBlank { return null }
        val port = o.optInt("server_port", 0).takeIf { it in 1..65535 } ?: return null
        val method = o.optString("method").ifBlank { return null }
        val password = o.optString("password").ifBlank { return null }
        val userInfo = Base64.getUrlEncoder().withoutPadding().encodeToString("$method:$password".toByteArray(Charsets.UTF_8))
        return "ss://$userInfo@${formatHost(server)}:$port#${encodeFragment(label)}"
    }

    // ---- XRay ----------------------------------------------------------------------------------------------

    internal fun xrayLinks(lastConfig: String, label: String): List<String> {
        val root = parseLastConfig(lastConfig) ?: return emptyList()
        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            xrayOutboundLink(ob, label)?.let { out += it }
        }
        return out
    }

    private fun xrayOutboundLink(ob: JSONObject, label: String): String? {
        val protocol = ob.optString("protocol").lowercase(Locale.US)
        val settings = ob.optJSONObject("settings") ?: return null
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        val params = linkedMapOf<String, String>()
        val network = stream.optString("network").ifBlank { "tcp" }.let { if (it == "raw") "tcp" else it }
        params["type"] = network
        val security = stream.optString("security").ifBlank { "none" }
        params["security"] = security
        when (security) {
            "reality" -> stream.optJSONObject("realitySettings")?.let { r ->
                r.optString("serverName").takeIf { it.isNotBlank() }?.let { params["sni"] = it }
                r.optString("fingerprint").takeIf { it.isNotBlank() }?.let { params["fp"] = it }
                r.optString("publicKey").takeIf { it.isNotBlank() }?.let { params["pbk"] = it }
                r.optString("shortId").takeIf { it.isNotBlank() }?.let { params["sid"] = it }
                r.optString("spiderX").takeIf { it.isNotBlank() }?.let { params["spx"] = it }
            }
            "tls" -> stream.optJSONObject("tlsSettings")?.let { t ->
                t.optString("serverName").takeIf { it.isNotBlank() }?.let { params["sni"] = it }
                t.optString("fingerprint").takeIf { it.isNotBlank() }?.let { params["fp"] = it }
                t.optJSONArray("alpn")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
                    ?.takeIf { it.isNotEmpty() }?.let { params["alpn"] = it.joinToString(",") }
            }
        }
        when (network) {
            "ws" -> stream.optJSONObject("wsSettings")?.let { w ->
                w.optString("path").takeIf { it.isNotBlank() }?.let { params["path"] = it }
                (w.optString("host").ifBlank { w.optJSONObject("headers")?.optString("Host").orEmpty() })
                    .takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }
            "grpc" -> stream.optJSONObject("grpcSettings")?.let { g ->
                g.optString("serviceName").takeIf { it.isNotBlank() }?.let { params["serviceName"] = it }
                if (g.optBoolean("multiMode", false)) params["mode"] = "multi"
            }
            "xhttp", "splithttp" -> (stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings"))?.let { x ->
                x.optString("path").takeIf { it.isNotBlank() }?.let { params["path"] = it }
                x.optString("host").takeIf { it.isNotBlank() }?.let { params["host"] = it }
                x.optString("mode").takeIf { it.isNotBlank() }?.let { params["mode"] = it }
            }
            "httpupgrade" -> stream.optJSONObject("httpupgradeSettings")?.let { h ->
                h.optString("path").takeIf { it.isNotBlank() }?.let { params["path"] = it }
                h.optString("host").takeIf { it.isNotBlank() }?.let { params["host"] = it }
            }
        }
        val fragment = encodeFragment(label)
        fun query() = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return when (protocol) {
            "vless", "vmess" -> {
                val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
                val address = vnext?.optString("address") ?: settings.optString("address")
                val port = vnext?.optInt("port", 0) ?: settings.optInt("port", 0)
                val user = vnext?.optJSONArray("users")?.optJSONObject(0) ?: settings
                val id = user.optString("id")
                if (address.isNullOrBlank() || port !in 1..65535 || id.isBlank()) return null
                if (protocol == "vless") {
                    params["encryption"] = user.optString("encryption").ifBlank { "none" }
                    user.optString("flow").takeIf { it.isNotBlank() }?.let { params["flow"] = it }
                    "vless://$id@${formatHost(address)}:$port?${query()}#$fragment"
                } else {
                    val vm = JSONObject()
                        .put("v", "2").put("ps", label).put("add", address).put("port", port.toString())
                        .put("id", id).put("aid", user.optInt("alterId", 0).toString())
                        .put("scy", user.optString("security").ifBlank { "auto" })
                        .put("net", network).put("type", "none")
                        .put("host", params["host"].orEmpty()).put("path", params["path"] ?: params["serviceName"].orEmpty())
                        .put("tls", if (security == "none") "" else security)
                        .put("sni", params["sni"].orEmpty()).put("fp", params["fp"].orEmpty())
                    "vmess://" + Base64.getEncoder().encodeToString(vm.toString().toByteArray(Charsets.UTF_8))
                }
            }
            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: settings
                val address = server.optString("address")
                val port = server.optInt("port", 0)
                val password = server.optString("password")
                if (address.isBlank() || port !in 1..65535 || password.isBlank()) return null
                "trojan://${URLEncoder.encode(password, "UTF-8")}@${formatHost(address)}:$port?${query()}#$fragment"
            }
            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: settings
                val address = server.optString("address")
                val port = server.optInt("port", 0)
                val method = server.optString("method")
                val password = server.optString("password")
                if (address.isBlank() || port !in 1..65535 || method.isBlank() || password.isBlank()) return null
                val userInfo = Base64.getUrlEncoder().withoutPadding().encodeToString("$method:$password".toByteArray(Charsets.UTF_8))
                "ss://$userInfo@${formatHost(address)}:$port#$fragment"
            }
            else -> null
        }
    }

    private fun formatHost(host: String): String = if (':' in host && !host.startsWith("[")) "[$host]" else host

    private fun encodeFragment(label: String): String = URLEncoder.encode(label, "UTF-8").replace("+", "%20")
}
