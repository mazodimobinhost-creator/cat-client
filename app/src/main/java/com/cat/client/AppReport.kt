package com.cat.client

import java.net.HttpURLConnection
import java.net.URL

/**
 * beta220 «📨 report to panel» — ONE button, opt-in, nothing is sent without the user seeing the exact text first.
 *
 * What goes out is deliberately small and anonymous (the panel endpoint `/api/app-report` keeps only these fields):
 * app version, Android SDK level, operator family (mci / irancell / …) + its public MCC/MNC code, network kind,
 * engine family, config KIND (scheme/transport/security — never the address, UUID, password or SNI), the real
 * internet test result, the DNS mode, an optional note and a redacted excerpt of the diagnostic log.
 *
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
internal data class AppReportFacts(
    val appVersion: String,
    val sdk: Int,
    val operator: String,
    val operatorCode: String,
    val network: String,
    val engine: String,
    val configKind: String,
    val result: RealInternetResult?,
    val dnsMode: String = "",
    val note: String = "",
    val logExcerpt: String = "",
)

internal object AppReport {
    const val PATH = "/api/app-report"
    const val MAX_LOG_CHARS = 2_000
    const val MAX_NOTE_CHARS = 300
    const val TIMEOUT_MS = 8_000

    /** Hosts that serve files, not a Cat panel: a report must never be posted there. */
    private val PUBLIC_FILE_HOSTS = listOf(
        "github.com", "githubusercontent.com", "jsdelivr.net", "gitlab.com", "gist.github.com",
        "bitbucket.org", "pastebin.com", "dropbox.com", "google.com", "workers.cloudflare.com",
    )

    private val DNS_TUNNEL_SCHEMES = setOf(
        "slipnet", "slipnet-enc", "dnst", "stormdns", "masterdns", "cottendns", "vaydns", "slipstream",
    )

    /** «vless/ws/tls», «vmess», «aether/masque», … — kind only, from the share link's scheme and two query keys. */
    fun configKind(shareLink: String?, type: String, transport: String): String {
        val link = shareLink.orEmpty().trim()
        val scheme = link.substringBefore("://", "").lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '+' }
        val base = scheme.ifBlank { type.lowercase().filter { it.isLetterOrDigit() || it == '-' } }.ifBlank { "unknown" }.take(16)
        val query = link.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').mapNotNull { part ->
            val k = part.substringBefore('=').lowercase()
            val v = part.substringAfter('=', "").lowercase()
            if (k.isBlank() || v.isBlank()) null else k to v
        }.toMap()
        fun clean(v: String?) = v.orEmpty().filter { it.isLetterOrDigit() || it == '-' }.take(12)
        val net = clean(params["type"] ?: params["transport"]).ifBlank { clean(transport.lowercase()) }
        val sec = clean(params["security"])
        return listOf(base, net, sec).filter { it.isNotBlank() && it != "none" }.distinct().joinToString("/").take(40)
    }

    fun engineFor(configKind: String, server: String = ""): String {
        val scheme = configKind.substringBefore('/')
        return when {
            scheme in setOf("aether", "warp", "masque", "h3") -> "cat-warp"
            scheme == "sstp" -> "cat-sstp"
            scheme in DNS_TUNNEL_SCHEMES -> "cat-dns"
            scheme in setOf("wireguard", "wg", "awg", "amneziawg") -> "wireguard"
            scheme.startsWith("socks") && (server == "127.0.0.1" || server == "::1" || server == "localhost") -> "local-bridge"
            else -> "cat-core"
        }
    }

    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b""")
    private val IPV6 = Regex("""(?i)\[?(?<![0-9a-z])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-z])\]?(?::\d{1,5})?""")
    private val UUID = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val URL_RE = Regex("""(?i)\b[a-z][a-z0-9+.-]{1,20}://\S+""")
    /** Domains: dotted names ending in a common TLD or a two-letter country code (log event names such as
     *  `realCheck.result` or `mihomo.health.failed` stay readable). */
    private val DOMAIN = Regex("""(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+([a-z]{2,24})\b""")
    private val COMMON_TLDS = setOf(
        "com", "net", "org", "io", "dev", "app", "info", "xyz", "cloud", "site", "online", "top", "pro", "biz",
        "live", "link", "store", "tech", "space", "website", "club", "shop", "workers", "pages", "vercel",
    )
    private val NOT_TLDS = setOf("ok", "id", "ms", "on", "no", "to", "in", "is", "at", "by", "of", "or", "up")
    private val SECRET_KV = Regex("""(?i)\b(password|passwd|pass|token|key|secret|uuid|id|sni|host|server|path|auth)\s*[=:]\s*\S+""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9+/_=-]{24,}""")

    /** Removes everything that could identify the server or the user: URLs, IPs, domains, UUIDs, key=value secrets. */
    fun redact(text: String): String = text
        .replace(URL_RE, "<link>")
        .replace(UUID, "<uuid>")
        .replace(SECRET_KV) { "${it.groupValues[1]}=<hidden>" }
        .replace(IPV4, "<ip>")
        .replace(IPV6) { m ->
            val v = m.value
            // a clock like 12:34:56 is not an address: require «::», 4+ colons or a hex letter
            val hexDigits = v.count { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            if (hexDigits >= 2 && ("::" in v || v.count { it == ':' } >= 4 || v.any { it.lowercaseChar() in 'a'..'f' })) "<ip>" else v
        }
        .replace(DOMAIN) { m ->
            val tld = m.groupValues[1].lowercase()
            if (tld in COMMON_TLDS || (tld.length == 2 && tld !in NOT_TLDS)) "<domain>" else m.value
        }
        .replace(LONG_TOKEN, "<token>")

    /** Last useful lines of the (already sanitised) diagnostic log, redacted again and capped. */
    fun logExcerpt(log: String, maxChars: Int = MAX_LOG_CHARS): String {
        val interesting = log.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter { line ->
                listOf("realCheck", "error", "failed", "warn", "mihomo", "aether", "fox", "sstp", "dns", "health")
                    .any { line.contains(it, ignoreCase = true) }
            }
            .toList()
            .takeLast(40)
        val text = redact(interesting.joinToString("\n"))
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun payload(f: AppReportFacts): String {
        val r = f.result
        val fields = linkedMapOf<String, Any>(
            "app" to "cat-client",
            "v" to f.appVersion.take(24),
            "sdk" to f.sdk,
            "op" to f.operator.take(16),
            "opc" to f.operatorCode.filter(Char::isDigit).take(6),
            "net" to f.network.take(10),
            "eng" to f.engine.take(24),
            "kind" to f.configKind.take(40),
            "st" to (r?.status?.takeIf { it != RealInternetStatus.Checking }?.wire ?: "na"),
            "ms" to (r?.latencyMs ?: -1L),
            "kbps" to (r?.kbps ?: -1),
            "hops" to (r?.hops ?: 0),
            "dns" to f.dnsMode.take(16),
            "note" to redact(f.note).take(MAX_NOTE_CHARS),
            "log" to f.logExcerpt.take(MAX_LOG_CHARS),
        )
        return fields.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"" + k + "\":" + when (v) {
                is Number -> v.toString()
                else -> jsonString(v.toString())
            }
        }
    }

    /** The text the consent dialog shows (and that is copied when sending fails): exactly what would be sent. */
    fun preview(f: AppReportFacts, fa: Boolean): String {
        val r = f.result
        val st = when (r?.status) {
            RealInternetStatus.Works -> if (fa) "اینترنت کار می‌کند" else "internet works"
            RealInternetStatus.Slow -> if (fa) "وصل ولی کند" else "connected but slow"
            RealInternetStatus.NoInternet -> if (fa) "وصل ولی بدون اینترنت" else "connected, no internet"
            else -> if (fa) "اندازه‌گیری نشد" else "not measured"
        }
        return buildString {
            appendLine("Cat Client ${f.appVersion} · Android SDK ${f.sdk}")
            appendLine((if (fa) "اپراتور: " else "Operator: ") + f.operator + (f.operatorCode.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "") + " · " + f.network)
            appendLine((if (fa) "موتور/نوع کانفیگ: " else "Engine / config kind: ") + f.engine + " · " + f.configKind)
            appendLine((if (fa) "تست واقعی: " else "Real test: ") + st +
                (r?.latencyMs?.takeIf { it >= 0 }?.let { " · ${it}ms" } ?: "") +
                (r?.kbps?.takeIf { it >= 0 }?.let { " · ${it}kbps" } ?: ""))
            if (f.note.isNotBlank()) appendLine((if (fa) "توضیح: " else "Note: ") + redact(f.note).take(MAX_NOTE_CHARS))
            if (f.logExcerpt.isNotBlank()) {
                appendLine()
                appendLine(if (fa) "بخشی از لاگ (آدرس‌ها و کلیدها حذف شده):" else "Log excerpt (addresses and keys removed):")
                append(f.logExcerpt.take(MAX_LOG_CHARS))
            }
        }.trim()
    }

    /** Panel base URL to post to: the user's own deployed panel, else the selected subscription's own origin. */
    fun targetOrigin(panelUrl: String?, subscriptionInput: String?): String? {
        listOf(panelUrl, subscriptionInput).forEach { raw ->
            val origin = originOf(raw) ?: return@forEach
            val host = origin.substringAfter("://").substringBefore(':').lowercase()
            if (PUBLIC_FILE_HOSTS.none { host == it || host.endsWith(".$it") }) return origin
        }
        return null
    }

    fun originOf(raw: String?): String? {
        val s = raw.orEmpty().trim()
        if (!s.startsWith("https://", ignoreCase = true)) return null // cleartext is disabled app-wide
        val rest = s.substring(8)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || '@' in authority || ' ' in authority) return null
        return "https://" + authority.lowercase()
    }

    data class SendResult(val ok: Boolean, val code: Int, val error: String = "")

    fun send(origin: String, json: String, timeoutMs: Int = TIMEOUT_MS): SendResult = try {
        val conn = (URL(origin.trimEnd('/') + PATH).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "CatClient-Report")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            SendResult(code in 200..299, code, if (code in 200..299) "" else "HTTP $code")
        } finally {
            conn.disconnect()
        }
    } catch (error: Throwable) {
        SendResult(false, -1, "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(160))
    }

    fun jsonString(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' || c == '\u2028' || c == '\u2029' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
