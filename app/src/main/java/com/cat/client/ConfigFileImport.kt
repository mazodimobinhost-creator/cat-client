package com.cat.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * WireGuard / AmneziaWG `.conf` text → `wireguard://` / `awg://` share links, so a plain `.conf`
 * file can be pasted, shared or opened anywhere a subscription is accepted (previously only the
 * dedicated AmneziaWG dialog understood it).
 */
object WireGuardConfText {
    private val AMNEZIA_KEYS = listOf(
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4",
        "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime",
    )

    /** Special-junk packets (`<r 2><b 0x…>`) keep their inner spaces; stripping them broke AmneziaWG 1.5/2.0 I1–I5. */
    private val KEEP_SPACES = setOf("i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3")

    fun looksLikeConf(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return "[interface]" in lower && "[peer]" in lower && "privatekey" in lower
    }

    fun toUris(text: String): List<String> {
        if (!looksLikeConf(text)) return emptyList()
        // Several configs may be concatenated; each starts with [Interface].
        val chunks = text.split(Regex("(?i)(?=\\[interface])")).filter { it.contains("[Peer]", ignoreCase = true) }
        return chunks.mapIndexedNotNull { index, chunk -> toUri(chunk, index) }
    }

    private fun toUri(chunk: String, index: Int): String? {
        val iface = LinkedHashMap<String, String>()
        val peer = LinkedHashMap<String, String>()
        var section = ""
        var name: String? = null
        chunk.lineSequence().forEach { raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("#")) {
                val comment = trimmed.removePrefix("#").trim()
                if (name == null && comment.startsWith("Name", ignoreCase = true) && '=' in comment) {
                    name = comment.substringAfter('=').trim().takeIf(String::isNotBlank)
                }
                return@forEach
            }
            val line = trimmed.substringBefore(" #").trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[")) {
                section = line.lowercase(Locale.US)
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim().lowercase(Locale.US)
            val value = line.substring(eq + 1).trim()
            if (value.isEmpty()) return@forEach
            when (section) {
                "[interface]" -> iface[key] = value
                "[peer]" -> if (key !in peer) peer[key] = value
            }
        }
        val privateKey = iface["privatekey"] ?: return null
        val publicKey = peer["publickey"] ?: return null
        val endpoint = peer["endpoint"] ?: return null
        val isAmnezia = AMNEZIA_KEYS.any { it in iface }
        val scheme = if (isAmnezia) "awg" else "wireguard"
        val params = mutableListOf<String>()
        fun add(key: String, value: String?) {
            if (value.isNullOrBlank()) return
            val clean = if (key in KEEP_SPACES) value.trim() else value.replace(" ", "")
            params += "$key=${URLEncoder.encode(clean, "UTF-8").replace("+", "%20")}"
        }
        add("address", iface["address"])
        add("publickey", publicKey)
        add("presharedkey", peer["presharedkey"])
        add("allowed-ips", peer["allowedips"])
        add("mtu", iface["mtu"])
        add("keepalive", peer["persistentkeepalive"])
        if (isAmnezia) AMNEZIA_KEYS.forEach { add(it, iface[it]) }
        val label = name ?: "WireGuard ${index + 1} ($endpoint)"
        return "$scheme://${URLEncoder.encode(privateKey, "UTF-8")}@$endpoint?${params.joinToString("&")}" +
            "#${URLEncoder.encode(label, "UTF-8").replace("+", "%20")}"
    }
}

/**
 * Turns anything Android hands the app — a deep link with a config scheme (vless://, slipnet://,
 * clash://install-config?url=…), shared text, or an opened/shared file — into text the normal
 * "add subscription" flow understands.
 */
object IncomingConfigIntents {
    data class Incoming(val content: String, val name: String)

    /** Schemes registered in the manifest; the full link itself is the config. */
    val CONFIG_SCHEMES = setOf(
        "vless", "vmess", "trojan", "ss", "ssr", "hysteria", "hysteria2", "hy2", "tuic", "wireguard", "wg",
        "awg", "amneziawg", "anytls", "socks", "socks5", "slipnet", "dnstt", "dnst", "noizdns", "vaydns",
        "catfusion", "paqet", "fenix", "ssh", "naive+https", "juicity", "mieru", "snell", "vpn",
    )

    /** Schemes whose `url` query parameter carries a subscription link (Clash / sing-box / Hiddify / v2rayNG style). */
    private val INSTALL_SCHEMES = setOf("clash", "clashmeta", "sing-box", "sn", "hiddify", "v2rayng", "v2box", "streisand", "karing")

    private const val MAX_FILE_BYTES = UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES

    fun extract(context: Context, intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> fromSend(context, intent)
            Intent.ACTION_VIEW -> fromView(context, intent)
            else -> null
        }
    }

    private fun fromSend(context: Context, intent: Intent): Incoming? {
        val stream = streamUri(intent)
        if (stream != null) return fromFile(context, stream)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        return Incoming(text, subject.ifBlank { guessName(text) })
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }.getOrNull()

    private fun fromView(context: Context, intent: Intent): Incoming? {
        val data = intent.data ?: return null
        val scheme = data.scheme?.lowercase(Locale.US).orEmpty()
        return when {
            scheme == "content" || scheme == "file" -> fromFile(context, data)
            scheme in INSTALL_SCHEMES -> {
                val url = data.getQueryParameter("url")?.trim().orEmpty()
                if (url.isEmpty()) return null
                val name = (data.getQueryParameter("name") ?: data.fragment)?.trim().orEmpty()
                Incoming(url, name.ifBlank { guessName(url) })
            }
            scheme in CONFIG_SCHEMES -> {
                val link = data.toString().trim()
                Incoming(link, guessName(link))
            }
            else -> null
        }
    }

    fun fromFile(context: Context, uri: Uri): Incoming? {
        val text = readText(context, uri) ?: return null
        val display = displayName(context, uri)
        return Incoming(text, display?.substringBeforeLast('.')?.take(60)?.ifBlank { null } ?: guessName(text))
    }

    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytesLimited(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) return null
            String(bytes, Charsets.UTF_8).trimStart('\uFEFF').trim()
        }
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    fun guessName(text: String): String {
        val first = text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty()
        if (AmneziaVpnKey.looksLikeKey(first)) {
            return AmneziaVpnKey.decode(first)?.serverName?.take(50)?.let { "🔐 Amnezia · $it" } ?: "🔐 Amnezia"
        }
        val fragment = first.substringAfter('#', "").takeIf { it.isNotBlank() }
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        if (!fragment.isNullOrBlank()) return fragment.take(60)
        if (first.startsWith("http", ignoreCase = true)) {
            return runCatching { Uri.parse(first).host }.getOrNull()?.take(60) ?: "Subscription"
        }
        if (WireGuardConfText.looksLikeConf(text)) return "WireGuard"
        return first.substringBefore("://", "").takeIf { it.isNotBlank() && it.length < 20 }?.uppercase(Locale.US) ?: "Imported"
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, minOf(n, limit - out.size()))
        }
        return out.toByteArray()
    }
}

/** Plain JSON backup of the user's subscriptions (names + sources), written/read through SAF. */
object SubscriptionBackup {
    const val FORMAT = "cat-client-backup"
    const val VERSION = 1

    data class Entry(val name: String, val input: String)

    fun export(items: List<UserSubscription>, nowMs: Long = System.currentTimeMillis()): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("name", item.name).put("input", item.input).put("format", item.format.wireName))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", nowMs)
            .put("subscriptions", array)
            .toString(2)
    }

    fun parse(text: String): List<Entry>? = runCatching {
        val root = JSONObject(text.trim())
        if (root.optString("format") != FORMAT) return null
        val array = root.optJSONArray("subscriptions") ?: return null
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim().take(60)
            val input = o.optString("input").trim()
            if (name.isEmpty() || input.isEmpty()) null else Entry(name, input)
        }
    }.getOrNull()
}
