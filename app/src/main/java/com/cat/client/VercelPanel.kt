package com.cat.client

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * "Cat Panel on Vercel": the whole Cat Panel (the same script the app deploys to Cloudflare) runs as
 * a Vercel Node.js Function in the user's own account. Vercel Functions accept WebSocket upgrades
 * (GA 2026-10-20) and may open outbound TCP, so the panel's own VLESS/Trojan-over-WebSocket data
 * plane works there — live-tested: every subscription line (Vercel IPs, vercel.com, the project
 * domain; VLESS and Trojan) carried a real HTTP request end to end.
 *
 * Deploy layout (identical to the live test):
 *   api/index.mjs     assets/vercel/panel-adapter.mjs (Workers → Node shims, ws upgrade)
 *   lib/catpanel.mjs  assets/panels/catpanel.min.js with import("cloudflare:sockets") swapped
 *   vercel.json       framework null, maxDuration 300, includeFiles "lib/" + everything under it, rewrite every path → /api/index
 *   package.json      type module, node 24.x, ws, @vercel/blob (panel storage)
 * Project env: UUID, TROJAN_PASS, DATA_PLANE_ENABLED=true (+ optional PANEL_PASSWORD).
 */
/**
 * What `/api/version` of a deployed Cat panel says, compared with the panel this APK would deploy.
 * A panel without permanent storage (`"kv":false`) is the cause of «subscription shows 0 configs»
 * in other clients (ZedSecure, v2rayNG…): users made in the panel exist only in one instance.
 */
internal sealed class VercelPanelHealth {
    /** Up to date and storing users permanently. */
    data class Ok(val version: String) : VercelPanelHealth()
    /** Answers, but has no permanent storage — panel-made user subscriptions come back empty. */
    data class NoStorage(val version: String, val outdated: Boolean) : VercelPanelHealth()
    /** Has storage but runs an older panel than the one in this APK. */
    data class Outdated(val version: String, val bundled: String) : VercelPanelHealth()
    /** Not a Cat panel answer (deleted project, paused account, blocked network…). */
    data class Unreachable(val code: Int, val detail: String) : VercelPanelHealth()

    val needsUpdate: Boolean get() = this is NoStorage || this is Outdated
}

internal object VercelPanelPlanner {
    const val ADAPTER_ASSET = "vercel/panel-adapter.mjs"
    const val PANEL_ASSET = "panels/catpanel.min.js"

    /** Reads a panel's `/api/version` answer (status [code], [body]) against the [bundled] version. */
    fun health(code: Int, body: String, bundled: String, detail: String = ""): VercelPanelHealth {
        if (code != 200 || !Regex(""""panel"\s*:\s*"cat-panel"""").containsMatchIn(body)) {
            return VercelPanelHealth.Unreachable(code, detail.ifBlank { if (code > 0) "HTTP $code" else "no answer" })
        }
        val version = Regex(""""version"\s*:\s*"([0-9]+(?:\.[0-9]+)+)"""").find(body)?.groupValues?.get(1).orEmpty()
        val storage = Regex(""""kv"\s*:\s*true""").containsMatchIn(body)
        val outdated = bundled.isNotBlank() && (version.isBlank() || AppUpdatePolicy.isNewer(bundled, version))
        return when {
            !storage -> VercelPanelHealth.NoStorage(version, outdated)
            outdated -> VercelPanelHealth.Outdated(version, bundled)
            else -> VercelPanelHealth.Ok(version)
        }
    }

    /** Vercel anycast addresses that answered for the project with SNI = Host = project domain. */
    val EDGE_ADDRESSES = listOf("vercel.com", "76.76.21.21", "64.29.17.65", "216.198.79.65")

    private val SOCKETS_IMPORTS = listOf("import(\"cloudflare:sockets\")", "import('cloudflare:sockets')")
    private const val SOCKETS_SHIM = "Promise.resolve(globalThis.__catSockets)"
    private const val ADAPTER_PANEL_IMPORT = "import('../lib/catpanel.mjs')"

    /** Swap the Workers-only socket import for the adapter's node:net shim. Exactly one must exist. */
    fun patchPanel(source: String): String {
        val total = SOCKETS_IMPORTS.sumOf { countOf(source, it) }
        require(total == 1) { "panel bundle: expected one cloudflare:sockets import, found $total" }
        var out = source
        SOCKETS_IMPORTS.forEach { out = out.replace(it, SOCKETS_SHIM) }
        return out
    }

    fun checkAdapter(source: String): String {
        require(source.contains(ADAPTER_PANEL_IMPORT)) { "adapter: lib/catpanel.mjs import missing" }
        require(source.contains("export default server")) { "adapter: default export missing" }
        return source
    }

    fun vercelJson(region: String?, maxDuration: Int): String {
        val regions = if (region.isNullOrBlank()) "" else ",\"regions\":[\"$region\"]"
        return "{\"version\":2,\"framework\":null$regions,\"functions\":{\"api/index.mjs\":{\"maxDuration\":$maxDuration,\"includeFiles\":\"lib/**\"}}," +
            "\"rewrites\":[{\"source\":\"/(.*)\",\"destination\":\"/api/index\"}]}\n"
    }

    fun packageJson(projectName: String): String =
        "{\"name\":${VercelRelayPlanner.jsonString(projectName)},\"private\":true,\"type\":\"module\",\"engines\":{\"node\":\"24.x\"},\"dependencies\":{\"ws\":\"^8.18.0\",\"@vercel/blob\":\"^2.8.2\"}}\n"

    fun envVars(uuid: String, trojanPass: String, panelPassword: String): LinkedHashMap<String, String> =
        linkedMapOf("UUID" to uuid, "TROJAN_PASS" to trojanPass, "DATA_PLANE_ENABLED" to "true").apply {
            if (panelPassword.isNotBlank()) put("PANEL_PASSWORD", panelPassword)
        }

    fun randomSecret(length: Int = 20, random: java.util.Random = SecureRandom()): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    fun panelUrl(host: String) = "https://$host/"
    fun subUrl(host: String, uuid: String) = "https://$host/sub/$uuid"

    /** The panel serves a base64 URI list; accept plain text too. Only vless/trojan lines survive. */
    fun extractSubLinks(body: String): List<String> {
        val trimmed = body.trim()
        val plain = if (trimmed.contains("://")) trimmed else runCatching {
            String(Base64.getMimeDecoder().decode(trimmed), Charsets.UTF_8)
        }.getOrDefault(trimmed)
        return plain.lines().map(String::trim).filter { it.startsWith("vless://") || it.startsWith("trojan://") }
    }

    /**
     * True when every line is TLS :443 with Host == the project domain and an SNI Vercel accepts for it:
     * the project domain itself, or — for a *.vercel.app project — the bare «vercel.app» or a mixed-case
     * project name (panel 6.69.41+, live-tested). vercel.com or any other SNI is refused by Vercel (421).
     */
    fun linksFitVercel(links: List<String>, host: String): Boolean = links.isNotEmpty() && links.all { link ->
        val query = link.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            val raw = part.substringAfter('=', "")
            if (k.isBlank()) null else k to runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }.toMap()
        val authority = link.substringAfter("://").substringAfter('@').substringBefore('?')
        val lineHost = params["host"].orEmpty()
        authority.endsWith(":443") && params["security"] == "tls" &&
            ((lineHost == host && sniFitsVercel(params["sni"].orEmpty(), host)) ||
                // 6.69.43 «Domain» lines: a custom domain attached to the project — its own certificate, Host = SNI.
                (lineHost == params["sni"] && isCustomDomain(lineHost)))
    }

    /** 6.69.43: ANY *.vercel.app name is a valid SNI for a *.vercel.app project (live-tested, made-up names too). */
    fun sniFitsVercel(sni: String, host: String): Boolean =
        sni == host || (host.lowercase().endsWith(".vercel.app") && sni.lowercase().let { it == "vercel.app" || it.endsWith(".vercel.app") })

    /** A domain the owner attached to the project (not a vercel.app / vercel.com name). */
    fun isCustomDomain(value: String): Boolean {
        val v = value.lowercase()
        return v.length in 4..253 && v.contains('.') && v.all { it.isLetterOrDigit() || it == '.' || it == '-' } &&
            !v.endsWith(".vercel.app") && v != "vercel.app" && !v.endsWith("vercel.com")
    }

    /**
     * Configs the app builds itself when the phone cannot read the panel's subscription yet (for
     * example api.vercel.com reachable but *.vercel.app filtered). Same shape as the panel's lines,
     * on the plain tunnel paths the panel always accepts (/ws, /trojan) — both live-tested.
     */
    fun fallbackLinks(uuid: String, trojanPass: String, host: String, name: String): List<VercelRelayLink> {
        val enc = VercelRelayPlanner::encode
        // Same three SNIs the panel serves (bare vercel.app and mixed-case first: some ISPs choke the project name).
        val snis = if (host.lowercase().endsWith(".vercel.app")) listOf("vercel.app", VercelIpScanner.stableRandomSni(host + "|" + uuid), VercelIpScanner.mixedCase(host), host) else listOf(host)
        val targets = listOf(host) + EDGE_ADDRESSES
        val out = mutableListOf<VercelRelayLink>()
        for (sni in snis) targets.forEach { address ->
            val common = "security=tls&sni=${enc(sni)}&host=$host&fp=chrome&alpn=http%2F1.1&type=ws"
            val sniTag = when (sni) { host -> ""; "vercel.app" -> " · SNI vercel.app"; else -> " · SNI Aa" }
            val tag = (if (address == host) "domain" else address) + sniTag
            out += VercelRelayLink(
                "VLESS · $tag",
                "VLESS · $tag",
                "vless://$uuid@$address:443?encryption=none&$common&path=${enc("/ws")}#${enc("$name · VLESS · $tag")}",
            )
            out += VercelRelayLink(
                "Trojan · $tag",
                "Trojan · $tag",
                "trojan://${enc(trojanPass)}@$address:443?$common&path=${enc("/trojan")}#${enc("$name · Trojan · $tag")}",
            )
        }
        return out
    }

    fun sha1Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) { count++; index = haystack.indexOf(needle, index + needle.length) }
        return count
    }
}

/**
 * Minimal WebSocket-over-TLS client used to prove a freshly deployed panel really tunnels: it dials
 * [address]:443 with SNI/Host = project domain, upgrades on [path], sends a VLESS request for
 * example.com:80 and waits for the HTTP answer — the same check the live test ran on Vercel.
 */
internal object VercelPanelProbe {
    data class Result(val ok: Boolean, val millis: Long, val detail: String)

    fun vlessRequest(uuid: String, host: String, port: Int, payload: ByteArray): ByteArray {
        val id = uuid.replace("-", "")
        require(id.length == 32) { "bad uuid" }
        val out = ByteArrayOutputStream()
        out.write(0)
        for (i in 0 until 16) out.write(id.substring(i * 2, i * 2 + 2).toInt(16))
        out.write(0) // no addons
        out.write(1) // tcp
        out.write(port shr 8); out.write(port and 0xff)
        val h = host.toByteArray(Charsets.US_ASCII)
        out.write(2); out.write(h.size); out.write(h)
        out.write(payload)
        return out.toByteArray()
    }

    /** Trojan request: hex(SHA-224(password)) CRLF, CONNECT, domain, port, CRLF, payload. */
    fun trojanRequest(password: String, host: String, port: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val hash = MessageDigest.getInstance("SHA-224").digest(password.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        out.write(hash.toByteArray(Charsets.US_ASCII))
        out.write(13); out.write(10)
        out.write(1); out.write(3)
        val h = host.toByteArray(Charsets.US_ASCII)
        out.write(h.size); out.write(h)
        out.write(port shr 8); out.write(port and 0xff)
        out.write(13); out.write(10)
        out.write(payload)
        return out.toByteArray()
    }

    /** Client → server frames must be masked (RFC 6455 §5.3). */
    fun maskedBinaryFrame(payload: ByteArray, mask: ByteArray): ByteArray {
        require(mask.size == 4)
        val out = ByteArrayOutputStream()
        out.write(0x82)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size < 65536 -> { out.write(0x80 or 126); out.write(payload.size shr 8); out.write(payload.size and 0xff) }
            else -> { out.write(0x80 or 127); for (s in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr s) and 0xff).toInt()) }
        }
        out.write(mask)
        payload.forEachIndexed { i, b -> out.write((b.toInt() xor mask[i % 4].toInt()) and 0xff) }
        return out.toByteArray()
    }

    /** Parses consecutive server frames from [buffer]; returns payloads of the complete ones. */
    fun unmaskedPayloads(buffer: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i + 2 <= buffer.size) {
            val opcode = buffer[i].toInt() and 0x0f
            var len = (buffer[i + 1].toInt() and 0x7f).toLong()
            val masked = (buffer[i + 1].toInt() and 0x80) != 0
            var p = i + 2
            if (len == 126L) { if (p + 2 > buffer.size) break; len = (((buffer[p].toInt() and 0xff) shl 8) or (buffer[p + 1].toInt() and 0xff)).toLong(); p += 2 }
            else if (len == 127L) { if (p + 8 > buffer.size) break; len = 0; for (k in 0 until 8) len = (len shl 8) or (buffer[p + k].toLong() and 0xff); p += 8 }
            val maskKey = if (masked) { if (p + 4 > buffer.size) break; buffer.copyOfRange(p, p + 4).also { p += 4 } } else null
            if (p + len > buffer.size) break
            val data = buffer.copyOfRange(p, (p + len).toInt())
            if (maskKey != null) for (k in data.indices) data[k] = (data[k].toInt() xor maskKey[k % 4].toInt()).toByte()
            if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) out += data
            if (opcode == 0x8) break
            i = (p + len).toInt()
        }
        return out
    }

    fun vless(address: String, projectHost: String, path: String, uuid: String, timeoutMs: Int = 12_000): Result =
        tunnel(address, 443, projectHost, projectHost, path, timeoutMs) { http -> vlessRequest(uuid, "example.com", 80, http) }

    /** TLS handshake only (stage 1 of the scanner): SNI + verified certificate name. */
    fun tls(address: String, port: Int, sni: String, timeoutMs: Int = 3_500): Result {
        val started = System.currentTimeMillis()
        val raw = java.net.Socket()
        return try {
            raw.connect(InetSocketAddress(address, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, port, true) as SSLSocket
            socket.use { s ->
                runCatching { s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) } }
                s.startHandshake()
                val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, s.session)
                Result(ok, System.currentTimeMillis() - started, if (ok) "tls ok" else "certificate does not match $sni")
            }
        } catch (e: Exception) {
            runCatching { raw.close() }
            Result(false, System.currentTimeMillis() - started, e.javaClass.simpleName)
        }
    }

    fun tunnel(
        address: String,
        port: Int,
        sni: String,
        projectHost: String,
        path: String,
        timeoutMs: Int = 12_000,
        firstFrame: (ByteArray) -> ByteArray,
    ): Result {
        val started = System.currentTimeMillis()
        val raw = java.net.Socket()
        return try {
            raw.connect(InetSocketAddress(address, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni, port, true) as SSLSocket
            socket.use { s ->
                runCatching { s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) } }
                s.startHandshake()
                // A plain SSLSocket does not check the certificate name: do it, so an intercepting
                // middlebox can never make a dead panel look healthy.
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, s.session)) {
                    return Result(false, System.currentTimeMillis() - started, "certificate does not match $sni")
                }
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val request = "GET $path HTTP/1.1\r\nHost: $projectHost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
                val output = s.outputStream
                output.write(request.toByteArray(Charsets.US_ASCII))
                output.flush()
                val input = s.inputStream
                val head = readHead(input)
                val status = head.lineSequence().firstOrNull().orEmpty()
                if (!status.contains(" 101")) return Result(false, System.currentTimeMillis() - started, status.ifBlank { "no upgrade" })
                val http = "GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: cat-client\r\nConnection: close\r\n\r\n".toByteArray()
                val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
                output.write(maskedBinaryFrame(firstFrame(http), mask))
                output.flush()
                val got = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (got.size() < 256 * 1024) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    got.write(chunk, 0, n)
                    val text = unmaskedPayloads(got.toByteArray()).joinToString("") { String(it, Charsets.ISO_8859_1) }
                    if (text.contains("HTTP/1.")) {
                        val line = text.substring(text.indexOf("HTTP/1.")).lineSequence().first()
                        return Result(true, System.currentTimeMillis() - started, line.trim())
                    }
                }
                Result(false, System.currentTimeMillis() - started, "no answer through the tunnel")
            }
        } catch (e: Exception) {
            runCatching { raw.close() }
            Result(false, System.currentTimeMillis() - started, e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: ""))
        }
    }

    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (out.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }
}

/** What the scanner dials: one VLESS/Trojan + WS + TLS config whose SNI is a domain served by Vercel. */
internal data class VercelScanTarget(
    val scheme: String,
    val credential: String,
    val sni: String,
    val host: String,
    val path: String,
    val port: Int,
    val link: String,
)

/** One scanner winner: the IP, the tested port and the SNI that actually passed on the user's line. */
internal data class VercelScanHit(val ip: String, val port: Int, val tlsMillis: Long, val tunnelMillis: Long, val sni: String = "")

/**
 * Vercel edge IP scanner. Cloudflare clean IPs cannot reach a Vercel project, but Vercel itself
 * answers every project on its own anycast networks (AS922) — and which addresses survive differs
 * per ISP (e.g. x.x.x.1 dead while .65/.129 work). The scanner tries addresses from all of them on
 * the user's own line: stage 1 = TLS handshake with the project SNI and a verified certificate,
 * stage 2 = a real tunnel (the config's own VLESS/Trojan over WebSocket) fetching example.com.
 * Only stage-2 winners become configs, on exactly the port that was tested.
 */
internal object VercelIpScanner {
    /**
     * Newer Vercel /24s: only the first three hosts of each /26 answer (.1–3, .65–67, .129–131,
     * .193–195). Mapped from inside Vercel (iad1, 2026-10-27) with TLS + Host = a real project.
     */
    val NETS_24 = listOf(
        "216.198.79", "64.29.17", "216.150.1", "216.150.16", "216.230.84", "216.230.86",
        "64.239.109", "64.239.123", "198.169.1", "198.169.2",
    )
    private val NEW_OFFSETS = listOf(1, 65, 129, 193, 2, 66, 130, 194, 3, 67, 131, 195)

    /** Legacy ranges (`cname.vercel-dns.com` / `*.vercel.app`): live hosts are scattered, so they are listed. */
    val LEGACY = mapOf(
        "76.76.21" to listOf(21, 93, 164, 9, 61, 75, 98, 123, 142, 200, 241, 22, 100, 104, 108, 112, 116, 201, 101, 102, 103, 105, 106, 107, 109, 110, 111, 113, 114, 115, 117, 202, 203),
        "66.33.60" to listOf(65, 129, 193, 34, 224, 242, 35, 66, 67, 130, 194, 225, 226, 227),
    )

    fun target(link: String): VercelScanTarget? {
        val source = VercelRelayPlanner.parse(link) ?: return null
        if (source.params["type"].orEmpty().lowercase() != "ws") return null
        if (source.params["security"].orEmpty().lowercase() != "tls") return null
        val sni = source.params["sni"].orEmpty().ifBlank { source.params["host"].orEmpty() }.ifBlank { source.address }
        val host = source.params["host"].orEmpty().ifBlank { sni }
        if (!isDomain(sni) || !isDomain(host)) return null
        return VercelScanTarget(source.scheme, source.credential, sni, host, stripEarlyData(source.params["path"].orEmpty().ifBlank { "/" }), source.port, link.trim())
    }

    /**
     * SNIs worth trying for [target] (live-tested from Vercel, 2026-10-27, real VLESS + Trojan tunnels):
     * a *.vercel.app project answers ANY vercel.app name as SNI — the bare «vercel.app» (its own valid
     * certificate) and a mixed-case project name too — while vercel.com, other domains and no SNI get
     * HTTP 421. Some mobile ISPs choke «<project>.vercel.app», so the scanner finds which of these passes
     * on the user's own line. A custom domain keeps its own SNI only.
     */
    fun sniCandidates(target: VercelScanTarget, random: kotlin.random.Random = kotlin.random.Random.Default): List<String> {
        val host = target.host.lowercase()
        if (!host.endsWith(".vercel.app")) return listOf(target.sni)
        // 6.69.43: plus two made-up names — on no blocklist, so they beat exact-name DPI lists (not suffix blocks)
        val list = mutableListOf("vercel.app", randomSni(random), randomSni(random), mixedCase(host), host)
        if (list.none { it == target.sni } && target.sni.lowercase().let { it == "vercel.app" || it.endsWith(".vercel.app") }) list += target.sni
        return list.distinct()
    }

    /** A made-up «w…….vercel.app» name; Vercel's wildcard certificate covers it and the edge routes by Host. */
    fun randomSni(random: kotlin.random.Random = kotlin.random.Random.Default): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "w" + (1..11).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") + ".vercel.app"
    }

    /** Same idea, but stable for one seed (configs the app builds keep their SNI across rebuilds). */
    fun stableRandomSni(seed: String): String = randomSni(kotlin.random.Random(seed.hashCode().toLong() * 31 + seed.length))

    fun isRandomSni(sni: String, host: String): Boolean =
        sni.lowercase().let { it.endsWith(".vercel.app") && it != host.lowercase() && Regex("^w[a-z0-9]{6,}\\.vercel\\.app$").matches(it) }

    /** Same mixed-case spelling the panel emits («cAt-PaNeL…»): every odd index upper-cased. */
    fun mixedCase(host: String): String =
        host.mapIndexed { i, c -> if (i % 2 == 1 && c in 'a'..'z') c.uppercaseChar() else c }.joinToString("")

    /** Short user-facing label for an SNI candidate. */
    fun sniLabel(sni: String, host: String): String = when {
        sni == "vercel.app" -> "vercel.app"
        isRandomSni(sni, host) -> "🎲 " + sni.take(8) + "…"
        sni != host.lowercase() && sni.lowercase() == host.lowercase() -> "Aa (" + sni.take(12) + "…)"
        else -> sni
    }

    /** First config in [text] (links, base64 subscription body) the scanner can use. */
    fun firstTarget(text: String): VercelScanTarget? =
        VercelPanelPlanner.extractSubLinks(text).firstNotNullOfOrNull(::target)

    private fun isDomain(value: String) = value.contains('.') && value.any { it.isLetter() } && !value.contains(':')

    /** `?ed=2560` asks clients for WebSocket early data; the probe sends a plain upgrade (the panel accepts both). */
    fun stripEarlyData(path: String): String {
        val base = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').filter { it.isNotBlank() && !it.startsWith("ed=") }
        return if (query.isEmpty()) base else base + "?" + query.joinToString("&")
    }

    /** Quick ≈ 60 addresses (4 per network), full = every known live edge address (≈ 167). */
    fun candidates(deep: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        val perNew = if (deep) NEW_OFFSETS.size else 4
        val perLegacy = if (deep) Int.MAX_VALUE else 10
        LEGACY.forEach { (net, hosts) -> hosts.take(perLegacy).forEach { out += "$net.$it" } }
        NETS_24.forEach { net -> NEW_OFFSETS.take(perNew).forEach { out += "$net.$it" } }
        return out.toList()
    }

    /** The original link with only the address (and the name) swapped; the port is the tested one. */
    fun linkFor(target: VercelScanTarget, hit: VercelScanHit, index: Int): String {
        val raw = target.link
        val at = raw.indexOf('@')
        val endOfAuthority = listOf(raw.indexOf('?', at), raw.indexOf('#', at), raw.indexOf('/', at)).filter { it > at }.minOrNull() ?: raw.length
        val sniTag = if (hit.sni.isNotBlank() && hit.sni != target.sni) " · SNI " + sniLabel(hit.sni, target.host) else ""
        val name = "▲ $index · Vercel ${hit.ip}$sniTag · ${hit.tunnelMillis} ms"
        var body = raw.substring(0, at + 1) + hit.ip + ":" + hit.port + raw.substring(endOfAuthority).substringBefore('#')
        if (hit.sni.isNotBlank() && hit.sni != target.sni) body = withParam(body, "sni", hit.sni)
        return body + "#" + VercelRelayPlanner.encode(name)
    }

    /** Replaces (or adds) one query parameter, keeping every other parameter and its order. */
    fun withParam(link: String, key: String, value: String): String {
        val q = link.indexOf('?')
        val pair = key + "=" + VercelRelayPlanner.encode(value)
        if (q < 0) return "$link?$pair"
        val out = mutableListOf<String>()
        var replaced = false
        for (part in link.substring(q + 1).split('&')) {
            if (part.isEmpty()) continue
            if (part.substringBefore('=').equals(key, ignoreCase = true)) {
                if (!replaced) { out += pair; replaced = true } // first occurrence replaced, duplicates dropped
            } else out += part
        }
        if (!replaced) out += pair
        return link.substring(0, q) + "?" + out.joinToString("&")
    }

    fun firstRequest(target: VercelScanTarget, payload: ByteArray): ByteArray =
        if (target.scheme == "trojan") VercelPanelProbe.trojanRequest(target.credential, "example.com", 80, payload)
        else VercelPanelProbe.vlessRequest(target.credential, "example.com", 80, payload)
}

/**
 * 6.69.43 — why a Vercel scan found nothing, in plain words. Pure (unit-tested): the controller collects the
 * stage-2 failure details («HTTP/1.1 421 …», «SSLHandshakeException», …) and shows the most common cause with
 * what to do about it. Every branch maps to a cause seen live from Iran or from the edge itself.
 */
object VercelScanDiagnosis {
    enum class Cause { NONE, SNI_BLOCKED, TLS_RESET, TIMEOUT, VERCEL_421, VERCEL_403, PATH_404, NO_TUNNEL, OTHER }

    fun classify(detail: String): Cause {
        val d = detail.lowercase()
        return when {
            Regex("http/1\\.[01] 421").containsMatchIn(d) -> Cause.VERCEL_421
            Regex("http/1\\.[01] 403").containsMatchIn(d) -> Cause.VERCEL_403
            Regex("http/1\\.[01] 404").containsMatchIn(d) -> Cause.PATH_404
            d.contains("no answer through the tunnel") -> Cause.NO_TUNNEL
            d.contains("timeout") -> Cause.TIMEOUT
            d.contains("ssl") || d.contains("reset") || d.contains("eof") || d.contains("broken pipe") || d.contains("socketexception") || d.contains("certificate") -> Cause.TLS_RESET
            else -> Cause.OTHER
        }
    }

    /** Most common cause: no SNI passed stage 0 / no TLS at all → SNI block; otherwise the dominant stage-2 failure. */
    fun cause(tlsCount: Int, sniPassed: Int, hits: Int, failures: List<String>): Cause = when {
        hits > 0 -> Cause.NONE
        sniPassed == 0 && tlsCount == 0 -> Cause.SNI_BLOCKED
        failures.isEmpty() -> if (tlsCount == 0) Cause.SNI_BLOCKED else Cause.OTHER
        else -> failures.groupingBy(::classify).eachCount().maxByOrNull { it.value }!!.key
    }

    fun explain(cause: Cause, fa: Boolean): String = when (cause) {
        Cause.NONE -> ""
        Cause.SNI_BLOCKED -> if (fa) "🔒 اپراتور شما همهٔ نام‌های vercel.app را در SNI می‌بندد (حتی نام‌های تصادفی). راه حل: ۱) خطوط «✂️ Frag» اشتراک را در v2rayNG / همین برنامه امتحان کنید (ClientHello تکه‌تکه). ۲) مطمئن‌ترین: یک دامنهٔ شخصی به پروژه وصل کنید (Vercel → Settings → Domains، رکورد A به 76.76.21.21) و Redeploy کنید؛ پنل خطوط «Domain» می‌سازد."
            else "🔒 Your operator blocks every vercel.app name in the SNI (even random ones). Fix: 1) try the subscription’s “✂️ Frag” lines in v2rayNG / this app (split ClientHello). 2) Surest: attach a custom domain to the project (Vercel → Settings → Domains, A record to 76.76.21.21) and redeploy; the panel builds “Domain” lines."
        Cause.TLS_RESET -> if (fa) "✂️ اتصال TLS وسط راه قطع شد (فیلترینگ روی نام SNI). خطوط «✂️ Frag» یا دامنهٔ شخصی را امتحان کنید."
            else "✂️ The TLS connection was cut mid-way (SNI filtering). Try the “✂️ Frag” lines or a custom domain."
        Cause.TIMEOUT -> if (fa) "🐢 اتصال‌ها تا ته نرسیدند (کندسازی/اختلال روی خط). چند دقیقه بعد یا روی اپراتور دیگر دوباره اسکن کنید؛ «کامل» آی‌پی‌های بیشتری را امتحان می‌کند."
            else "🐢 Connections never finished (throttling on the line). Scan again later or on another operator; “Full” tries more IPs."
        Cause.VERCEL_421 -> if (fa) "↩️ خود Vercel جواب 421 داد: SNI با Host این کانفیگ جور نیست (مثلاً SNI غیر vercel.app، یا دامنهٔ شخصی‌ای که هنوز گواهی ندارد). اشتراک را از پنل دوباره بگیرید."
            else "↩️ Vercel itself answered 421: the SNI does not match this config’s Host (a non-vercel.app SNI, or a custom domain without a certificate yet). Re-fetch the subscription from the panel."
        Cause.VERCEL_403 -> if (fa) "⛔ Vercel جواب 403 داد: محافظ DDoS ورسل آی‌پی شما را رد کرد (برای ترافیکی که از درگاه‌های ملی می‌آید رایج است). از اپراتور/اینترنت دیگر امتحان کنید؛ این را با تغییر کانفیگ نمی‌شود دور زد."
            else "⛔ Vercel answered 403: its DDoS shield refused your IP (common for traffic leaving through national gateways). Try another operator/network; no config change can bypass it."
        Cause.PATH_404 -> if (fa) "🧭 مسیر تونل پیدا نشد (404): پنل قدیمی است یا کانفیگ مال پنل دیگری است. «🔧 به‌روزرسانی پنل» را بزنید."
            else "🧭 Tunnel path not found (404): the panel is outdated or the config is from another panel. Run “🔧 Update panel”."
        Cause.NO_TUNNEL -> if (fa) "🔌 Vercel وصل شد (101) ولی از داخل تونل جوابی نیامد: UUID/رمز اشتباه است، کاربر غیرفعال/منقضی است، یا پنل به اینترنت نرسید."
            else "🔌 Vercel connected (101) but nothing came back through the tunnel: wrong UUID/password, a disabled/expired user, or the panel could not reach the internet."
        Cause.OTHER -> if (fa) "❔ علت مشخص نشد؛ جزئیات خطا در پایین آمده." else "❔ Unclear cause; the error details are below."
    }
}

/**
 * 6.69.44 🟧 Cloudflare front for a Vercel panel: a tiny Worker (assets/vercel/cf-front.js) on the user's own
 * Cloudflare account passes the panel, the subscription and the WebSocket tunnels on to the Vercel project.
 * Used when *.vercel.app is filtered on the line but Cloudflare is open. Pure helpers (unit-tested).
 */
internal object VercelCfFront {
    const val ASSET = "vercel/cf-front.js"
    const val ENV_KEY = "CAT_CF_FRONT"
    private const val ORIGIN_PLACEHOLDER = "'__CAT_ORIGIN__'"

    fun randomWorkerName(random: kotlin.random.Random = kotlin.random.Random.Default): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "cat-front-" + (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    fun isHostname(value: String): Boolean =
        value.length in 4..253 && Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(value)

    /** The shipped script with the Vercel project written in (the CAT_VERCEL_ORIGIN binding is set too). */
    fun script(template: String, origin: String): String {
        val o = origin.trim().lowercase()
        require(isHostname(o)) { "origin must be a hostname" }
        require(template.contains(ORIGIN_PLACEHOLDER)) { "front script: origin placeholder missing" }
        return template.replace(ORIGIN_PLACEHOLDER, "'$o'")
    }

    /** True when /__cat_front answered as OUR front script pointing at [origin]. */
    fun healthy(body: String, origin: String): Boolean = runCatching {
        val j = org.json.JSONObject(body)
        j.optBoolean("ok") && j.optString("front") == "cat-cf-front" && j.optString("origin").equals(origin, ignoreCase = true)
    }.getOrDefault(false)

    /**
     * One VLESS + one Trojan config through the front, made from the panel's own lines: address, Host and
     * SNI become the front's name (:443, TLS), path / UUID / password stay; Vercel-only knobs (fm, cs, ech) go.
     */
    fun links(panelLinks: List<String>, front: String): List<String> {
        val out = mutableListOf<String>()
        for (scheme in listOf("vless", "trojan")) {
            val src = panelLinks.firstOrNull { l ->
                l.startsWith("$scheme://") && param(l, "security") == "tls" && param(l, "type") == "ws" && param(l, "path").isNotBlank()
            } ?: continue
            val at = src.indexOf('@')
            val q = src.indexOf('?', at)
            if (at < 0 || q < 0) continue
            var body = src.substring(0, at + 1) + front + ":443" + src.substring(q).substringBefore('#')
            for (k in listOf("fm", "cs", "ech")) body = withoutParam(body, k)
            body = VercelIpScanner.withParam(body, "host", front)
            body = VercelIpScanner.withParam(body, "sni", front)
            out += body + "#" + VercelRelayPlanner.encode("🟧 " + scheme.uppercase() + " · Cloudflare front")
        }
        return out
    }

    fun param(link: String, key: String): String {
        val query = link.substringAfter('?', "").substringBefore('#')
        val raw = query.split('&').firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "") ?: return ""
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    fun withoutParam(link: String, key: String): String {
        val q = link.indexOf('?')
        if (q < 0) return link
        val hash = link.indexOf('#', q).let { if (it < 0) link.length else it }
        val kept = link.substring(q + 1, hash).split('&').filter { it.isNotBlank() && it.substringBefore('=') != key }
        return link.substring(0, q) + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + link.substring(hash)
    }
}

/**
 * 6.69.45 Cat panel on any Node.js host (KataBump free · Lucity · Shiprr · Railway · a VPS). The user uploads the
 * 2-file launcher (cat-node-panel.zip from THIS app version's release: index.js + package.json + README); it fetches
 * catclient.worker.js + cat-panel-adapter.mjs from the same release, checks them against cat-node-panel.sha256 and
 * runs the panel. Pure helpers only — the dialog lives in VercelRelayController.showNodeHostGuide().
 */
object NodeHostPanel {
    const val REPO = "mazodimobinhost-creator/cat-client"
    const val ZIP_ASSET = "cat-node-panel.zip"

    data class Host(val id: String, val name: String, val url: String, val free: Boolean)

    val HOSTS: List<Host> = listOf(
        Host("katabump", "KataBump", "https://katabump.com", free = true),
        Host("lucity", "Lucity", "https://lucity.cloud", free = false),
        Host("shiprr", "Shiprr", "https://shiprr.app", free = false),
    )

    /** The release tag CI makes for an app version: «v» + versionName (e.g. v1.10.0-beta190). */
    fun releaseTag(versionName: String): String = "v" + versionName.trim().removePrefix("v")

    fun zipUrl(versionName: String): String =
        "https://github.com/$REPO/releases/download/${releaseTag(versionName)}/$ZIP_ASSET"

    fun releasePage(versionName: String): String =
        "https://github.com/$REPO/releases/tag/${releaseTag(versionName)}"
}
