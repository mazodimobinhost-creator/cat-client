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

    /** True when every line is TLS :443 with SNI == Host == the project domain (what Vercel accepts). */
    fun linksFitVercel(links: List<String>, host: String): Boolean = links.isNotEmpty() && links.all { link ->
        val query = link.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            val raw = part.substringAfter('=', "")
            if (k.isBlank()) null else k to runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        }.toMap()
        val authority = link.substringAfter("://").substringAfter('@').substringBefore('?')
        authority.endsWith(":443") && params["security"] == "tls" && params["sni"] == host && params["host"] == host
    }

    /**
     * Configs the app builds itself when the phone cannot read the panel's subscription yet (for
     * example api.vercel.com reachable but *.vercel.app filtered). Same shape as the panel's lines,
     * on the plain tunnel paths the panel always accepts (/ws, /trojan) — both live-tested.
     */
    fun fallbackLinks(uuid: String, trojanPass: String, host: String, name: String): List<VercelRelayLink> {
        val enc = VercelRelayPlanner::encode
        val common = "security=tls&sni=$host&host=$host&fp=chrome&alpn=http%2F1.1&type=ws"
        val targets = listOf(host) + EDGE_ADDRESSES
        val out = mutableListOf<VercelRelayLink>()
        targets.forEach { address ->
            val tag = if (address == host) "domain" else address
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

internal data class VercelScanHit(val ip: String, val port: Int, val tlsMillis: Long, val tunnelMillis: Long)

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
        val name = "▲ $index · Vercel ${hit.ip} · ${hit.tunnelMillis} ms"
        val body = raw.substring(0, at + 1) + hit.ip + ":" + hit.port + raw.substring(endOfAuthority).substringBefore('#')
        return body + "#" + VercelRelayPlanner.encode(name)
    }

    fun firstRequest(target: VercelScanTarget, payload: ByteArray): ByteArray =
        if (target.scheme == "trojan") VercelPanelProbe.trojanRequest(target.credential, "example.com", 80, payload)
        else VercelPanelProbe.vlessRequest(target.credential, "example.com", 80, payload)
}
