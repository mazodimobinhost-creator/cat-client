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
 *   vercel.json       framework null, maxDuration 300, includeFiles lib/**, rewrite /(.*) → /api/index
 *   package.json      type module, node 24.x, ws
 * Project env: UUID, TROJAN_PASS, DATA_PLANE_ENABLED=true (+ optional PANEL_PASSWORD).
 */
internal object VercelPanelPlanner {
    const val ADAPTER_ASSET = "vercel/panel-adapter.mjs"
    const val PANEL_ASSET = "panels/catpanel.min.js"

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
        "{\"name\":${VercelRelayPlanner.jsonString(projectName)},\"private\":true,\"type\":\"module\",\"engines\":{\"node\":\"24.x\"},\"dependencies\":{\"ws\":\"^8.18.0\"}}\n"

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

    fun vless(address: String, projectHost: String, path: String, uuid: String, timeoutMs: Int = 12_000): Result {
        val started = System.currentTimeMillis()
        val raw = java.net.Socket()
        return try {
            raw.connect(InetSocketAddress(address, 443), timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, projectHost, 443, true) as SSLSocket
            socket.use { s ->
                runCatching { s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(projectHost)) } }
                s.startHandshake()
                // A plain SSLSocket does not check the certificate name: do it, so an intercepting
                // middlebox can never make a dead panel look healthy.
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(projectHost, s.session)) {
                    return Result(false, System.currentTimeMillis() - started, "certificate does not match $projectHost")
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
                output.write(maskedBinaryFrame(vlessRequest(uuid, "example.com", 80, http), mask))
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
