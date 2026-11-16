package com.cat.client

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * beta312 — 🌉 Cat Edge: signed public-server list, RSA-OAEP session, then VMess+WS+TLS
 * that Cat's core already runs. Three kinds on one page:
 *  • public — hosts from the signed envelope
 *  • bridge — pasted hosts (or a pasted GitHub/raw list)
 *  • falcon — a single pasted domain, same session
 * Handshake is real (session UUID). Connect is a normal Cat `vmess://` row, never skip-cert-verify.
 * Independent Kotlin of the public handshake; Cat names in the UI.
 */
internal object CatEdgePlanner {
    const val NAME = "🌉 Cat Edge"
    const val UA = "CatClient"
    const val PROTOCOL = "A"

    /** RSA-2048 SPKI used to seal the session request (OAEP SHA-256). */
    const val RSA_SPKI_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAz/M4JOwjHadx2ZsZZOATv0sT+YCtleYy4BPBBYA1KKypT59gVc5y1geVIbV6ssktTGnWE7q2BQsKG6QLZ13Xzdc/uYHm8hcQewlsUrh5i3qs9kyZSHXV9TqPna4sKX9pVxMxO+PfUWABzygRgDdpP3x/Y8my7oh3NVbRKrWrabcAtuyL3mdQGCWHooaVQfYttLWBuCSDUF8C5AnDUNQ5DnOrQFt8bKMQoqt7aj97Ecf2TiU0jxUAKt5QpmuS+Yjrny6/U7aCFU9iP+uQFwKNBRpxwlR/OS4mIq9gCWkrI7FWJ2i4AGU878IwiwGKYwvJK2Pyk3+6fW4dVg7+K+6B7QIDAQAB"

    val TRUSTED_KEYS: Map<String, String> = mapOf(
        "snowcfg" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEe9JfBCUoFo1vmiYHLNz0EbAN/xkzFlBrdBFbCGCQ4N41Cjt6c7vNi6dAfJSdn/a39m5AkbBcn9a/ITxMxZNHJQ==",
        "snowcfg-f" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEfMH+QlcC9f0wsbsHWJYcei+cp4rO2WnTzHcdhQF3OJFP58+I1O/Z/HTJE9GXUZ5SV3WPQgJF8V/YFoObjvmZtw==",
        "snowcfg-b" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvOr0llysYNMT+F4fRE0S7Bh/s29X8EXyKIbcZFwXmm2KebR9YvG+nBasKrH0QzZLgxdNDggIqgTdU0wAJXtMJA==",
    )

    val LIST_URLS: List<String> = listOf(
        "https://raw.githubusercontent.com/legend-wizard/browse/refs/heads/main/data6.br",
        "https://cdn.jsdelivr.net/gh/legend-wizard/browse@main/data6.br",
        "https://api.github.com/repos/legend-wizard/browse/contents/data6.br?ref=main",
        "https://s3.amazonaws.com/argovpn/browse/data6.br",
        "https://akeptumbesnow.com/repo6",
    )

    private val UUID_RE = Regex("^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
    private val HOST_RE = Regex("(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$")
    private val rng = SecureRandom()

    fun interface EdgeHttp {
        fun get(url: String, headers: Map<String, String>): Pair<Int, String>
    }

    data class Node(
        val id: String,
        val host: String,
        val port: Int,
        val profile: String,
        val kind: String,
        val clockOffsetSeconds: Int = 0,
        val country: String = "",
    )

    data class LocationLabel(
        val code: String,
        val flag: String,
        val nameFa: String,
        val nameEn: String,
    ) {
        fun name(fa: Boolean): String = if (fa) nameFa else nameEn
        fun line(fa: Boolean): String = "$flag ${name(fa)}"
    }

    data class AuthAttempt(
        val authorization: String,
        val key: ByteArray,
        val iv: ByteArray,
        val aad: ByteArray,
    )

    data class Session(
        val uuid: String,
        val path: String,
        val token: String,
    )

    data class Ready(
        val node: Node,
        val session: Session,
        val rttMs: Long,
        val link: String,
    )

    data class SignedList(
        val serial: String,
        val keyId: String,
        val nodes: List<Node>,
    )

    fun rsaSpki(): ByteArray = b64(RSA_SPKI_B64)

    fun b64(text: String): ByteArray {
        val t = text.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        return Base64.getDecoder().decode(t + "=".repeat(pad))
    }

    fun b64out(bytes: ByteArray, url: Boolean = false): String {
        val enc = if (url) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder().withoutPadding()
        return enc.encodeToString(bytes)
    }

    fun hmac(key: ByteArray, data: ByteArray, sha: String = "HmacSHA256"): ByteArray {
        val mac = Mac.getInstance(sha)
        mac.init(SecretKeySpec(key, sha))
        return mac.doFinal(data)
    }

    fun expand(prk: ByteArray, info: String, length: Int, sha: String = "HmacSHA256"): ByteArray {
        val infoBytes = info.toByteArray(Charsets.US_ASCII)
        var previous = ByteArray(0)
        val out = ByteArrayOutputStream()
        var i = 1
        while (out.size() < length) {
            if (i > 255) throw IllegalStateException("hkdf too long")
            previous = hmac(prk, previous + infoBytes + byteArrayOf(i.toByte()), sha)
            out.write(previous)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun derive(secret: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val prk = hmac(salt, secret)
        return expand(prk, "Argo-AES-Key", 32) to expand(prk, "Argo-AES-IV", 12)
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    fun inflateZlib(bytes: ByteArray, max: Int = 1_048_576): ByteArray {
        fun once(nowrap: Boolean): ByteArray {
            val inf = Inflater(nowrap)
            inf.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
                if (out.size() > max) throw IllegalArgumentException("payload too large")
            }
            inf.end()
            if (out.size() == 0) throw IllegalArgumentException("empty inflate")
            return out.toByteArray()
        }
        return runCatching { once(false) }.recoverCatching { once(true) }.getOrElse {
            java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        }
    }

    fun deflateZlib(bytes: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        def.setInput(bytes)
        def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    fun encryptPayload(payload: JSONObject, publicKeyDer: ByteArray = rsaSpki()): String {
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        if (plain.size > 190) throw IllegalArgumentException("auth payload too large")
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
        val spec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(Cipher.ENCRYPT_MODE, pub, spec)
        val encrypted = rsa.doFinal(plain)
        val pad = ByteArray(1 + rng.nextInt(32)).also { rng.nextBytes(it) }
        return b64out(gzip(encrypted + pad))
    }

    fun makeAuth(
        protocol: String = PROTOCOL,
        clockOffsetSeconds: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
        publicKeyDer: ByteArray = rsaSpki(),
    ): AuthAttempt {
        val salt = ByteArray(32).also { rng.nextBytes(it) }
        val seed = ByteArray(32).also { rng.nextBytes(it) }
        val ikm = ByteArray(32).also { rng.nextBytes(it) }
        val secret = expand(hmac(ikm, seed, "HmacSHA512"), "ArgoVPN-Derivation", 32, "HmacSHA512")
        val payload = JSONObject()
            .put("protocol", protocol)
            .put("request", "auth")
            .put("salt", b64out(salt))
            .put("secret", b64out(secret))
            .put("t", nowMs + clockOffsetSeconds * 1000L)
            .put("version", 3)
        val authorization = encryptPayload(payload, publicKeyDer)
        val (key, iv) = derive(secret, salt)
        val aad = hmac(expand(publicKeyDer, "Argo-RSA-MAC-Key", 32), authorization.toByteArray(Charsets.US_ASCII))
        return AuthAttempt(authorization, key, iv, aad)
    }

    fun decryptAuth(text: String, auth: AuthAttempt): Session {
        val body = b64(text)
        if (body.size < 17) throw IllegalArgumentException("auth response too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(auth.key, "AES"), GCMParameterSpec(128, auth.iv))
        cipher.updateAAD(auth.aad)
        val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
        val uuid = json.optString("uuid").trim()
        if (!UUID_RE.matches(uuid)) throw IllegalArgumentException("auth uuid missing")
        val path = json.optString("path").ifBlank { json.optString("wsPath") }.ifBlank { "/" }
        return Session(uuid = uuid, path = if (path.startsWith("/")) path else "/$path", token = json.optString("token"))
    }

    fun encryptAuthResponse(json: JSONObject, auth: AuthAttempt): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(auth.key, "AES"), GCMParameterSpec(128, auth.iv))
        cipher.updateAAD(auth.aad)
        return b64out(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)))
    }

    fun verifyEnvelope(text: String, keys: Map<String, String> = TRUSTED_KEYS): Pair<JSONObject, SignedMeta> {
        if (text.toByteArray().size > 262_144) throw IllegalArgumentException("configuration too large")
        var envelope = text.trim()
        if (envelope.startsWith("{")) {
            val wrapper = JSONObject(envelope)
            if (wrapper.optString("encoding") != "base64" || wrapper.optString("content").isBlank()) {
                throw IllegalArgumentException("invalid github wrapper")
            }
            envelope = String(b64(wrapper.getString("content").replace("\\s".toRegex(), "")), Charsets.UTF_8).trim()
        }
        val parts = envelope.split('.')
        if (parts.size != 6 || parts[0] != "v1") throw IllegalArgumentException("unsupported envelope")
        val keyB64 = keys[parts[1]] ?: throw IllegalArgumentException("unknown key id")
        if (!parts[2].matches(Regex("[1-9][0-9]{0,18}")) || !parts[3].matches(Regex("\\d{1,19}"))) {
            throw IllegalArgumentException("invalid serial/time")
        }
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64(keyB64)))
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pub)
        sig.update(parts.take(5).joinToString(".").toByteArray(Charsets.US_ASCII))
        if (!sig.verify(b64(parts[5]))) throw IllegalArgumentException("configuration signature failed")
        val transformed = b64(parts[4]).map { (it.toInt() xor 255).toByte() }.toByteArray().reversedArray()
        val config = JSONObject(String(inflateZlib(transformed), Charsets.UTF_8))
        val parameters = config.optJSONObject("parameters") ?: throw IllegalArgumentException("no parameters")
        return config to SignedMeta(parts[2], parts[1], parts[3].toLong(), parameters)
    }

    data class SignedMeta(val serial: String, val keyId: String, val issuedAt: Long, val parameters: JSONObject)

    fun encodeEnvelope(parameters: JSONObject, keyId: String, serial: String, issuedAt: Long, privateSign: (ByteArray) -> ByteArray): String {
        val config = JSONObject().put("parameters", parameters)
        val packed = deflateZlib(config.toString().toByteArray(Charsets.UTF_8))
        val transformed = packed.reversedArray().map { (it.toInt() xor 255).toByte() }.toByteArray()
        val body = listOf("v1", keyId, serial, issuedAt.toString(), b64out(transformed))
        val sig = b64out(privateSign(body.joinToString(".").toByteArray(Charsets.US_ASCII)), url = true)
        return body.joinToString(".") + "." + sig
    }

    fun nodesFrom(parameters: JSONObject, kindPublic: String = "public"): List<Node> {
        val out = ArrayList<Node>()
        val keys = parameters.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!isServerKey(key)) continue
            val raw = parameters.opt(key) ?: continue
            val obj = when (raw) {
                is JSONObject -> raw
                is String -> runCatching { JSONObject(raw) }.getOrNull() ?: continue
                else -> continue
            }
            val host = obj.optString("server").trim().lowercase(Locale.US)
            val port = obj.optInt("port", 443)
            if (!HOST_RE.matches(host) || port !in 1..65535 || port == 80 || port == 1080) continue
            val profile = obj.optString("profile").ifBlank {
                when {
                    key.contains("_ir_") -> "ir"
                    key.contains("_global_") -> "global"
                    else -> countryFromKey(key).ifBlank { "global" }.lowercase(Locale.US)
                }
            }
            out += Node(
                id = key,
                host = host,
                port = port,
                profile = profile,
                kind = kindPublic,
                clockOffsetSeconds = obj.optInt("clockOffsetSeconds", 0),
                country = countryOf(key, obj, profile),
            )
        }
        return out.distinctBy { it.host + ":" + it.port }
    }

    fun parsePasted(text: String, kind: String = "bridge"): List<Node> {
        val lines = text.replace(',', '\n').replace(' ', '\n').split('\n')
        val out = ArrayList<Node>()
        for (raw in lines) {
            var line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            line = line.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
            val host: String
            val port: Int
            if (line.contains(':')) {
                val h = line.substringBeforeLast(':').trim().lowercase(Locale.US)
                val p = line.substringAfterLast(':').toIntOrNull() ?: 443
                host = h
                port = p
            } else {
                host = line.lowercase(Locale.US)
                port = 443
            }
            if (!HOST_RE.matches(host) || port !in 1..65535 || port == 80 || port == 1080) continue
            val k = if (kind == "falcon" || (kind == "bridge" && lines.size <= 2)) "falcon" else kind
            out += Node(id = "paste_$host:$port", host = host, port = port, profile = "paste", kind = k)
        }
        return out.distinctBy { it.host + ":" + it.port }
    }

    fun vmessLink(node: Node, session: Session): String {
        val loc = locationLabel(node)
        val title = "$NAME · ${loc.flag} ${loc.code} · ${node.host}"
        val json = JSONObject()
            .put("v", "2")
            .put("ps", title)
            .put("add", node.host)
            .put("port", node.port.toString())
            .put("id", session.uuid)
            .put("aid", "0")
            .put("scy", "auto")
            .put("net", "ws")
            .put("type", "none")
            .put("host", node.host)
            .put("path", session.path)
            .put("tls", "tls")
            .put("sni", node.host)
            .put("fp", "chrome")
        val b64 = Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        return "vmess://$b64"
    }

    fun subscriptionText(links: List<String>): String =
        "# $NAME\n" + links.distinct().joinToString("\n")

    fun fetchSignedList(
        http: EdgeHttp,
        urls: List<String> = LIST_URLS,
        keys: Map<String, String> = TRUSTED_KEYS,
    ): SignedList {
        var last: Exception? = null
        for (url in urls) {
            try {
                val (code, body) = http.get(url, mapOf("User-Agent" to UA, "Accept" to "*/*"))
                if (code !in 200..299) {
                    last = IllegalStateException("HTTP $code")
                    continue
                }
                val (_, meta) = verifyEnvelope(body, keys)
                val nodes = nodesFrom(meta.parameters)
                if (nodes.isEmpty()) {
                    last = IllegalStateException("empty list")
                    continue
                }
                return SignedList(meta.serial, meta.keyId, nodes)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("list unavailable")
    }

    fun handshake(
        node: Node,
        http: EdgeHttp,
        publicKeyDer: ByteArray = rsaSpki(),
        nowMs: Long = System.currentTimeMillis(),
        attempt: AuthAttempt? = null,
    ): Ready {
        val t0 = System.nanoTime()
        val auth = attempt ?: makeAuth(clockOffsetSeconds = node.clockOffsetSeconds, nowMs = nowMs, publicKeyDer = publicKeyDer)
        val url = "https://${node.host}:${node.port}/"
        val (code, body) = http.get(
            url,
            mapOf(
                "User-Agent" to UA,
                "Accept" to "*/*",
                "Authorization" to auth.authorization,
            ),
        )
        if (code !in 200..299) throw IllegalStateException("auth HTTP $code")
        val session = decryptAuth(body, auth)
        val rtt = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        val link = vmessLink(node, session)
        if (link.contains("insecure", ignoreCase = true) || link.contains("allowInsecure")) {
            throw IllegalStateException("insecure flag")
        }
        return Ready(node, session, rtt, link)
    }

    fun isServerKey(key: String): Boolean {
        if (key.contains("falcon", ignoreCase = true)) return false
        if (key.contains("template", ignoreCase = true)) return false
        return key.startsWith("server_") || key.startsWith("servers_")
    }

    fun countryFromKey(key: String): String {
        val lower = key.lowercase(Locale.US)
        if (lower.contains("_global_") || lower.startsWith("server_global") || lower.startsWith("servers_global")) {
            return ""
        }
        if (lower.contains("_ir_") || lower.startsWith("server_ir") || lower.startsWith("servers_ir")) {
            return "IR"
        }
        val m = Regex("^servers?_([a-z]{2})_", RegexOption.IGNORE_CASE).find(key) ?: return ""
        return ConnectionLocationPolicy.normalizeCountryCode(m.groupValues[1]).orEmpty()
    }

    fun countryOf(key: String, obj: JSONObject, profile: String): String {
        val explicit = obj.optString("country").ifBlank { obj.optString("cc") }.ifBlank { obj.optString("loc") }
        ConnectionLocationPolicy.normalizeCountryCode(explicit)?.let { return it }
        ConnectionLocationPolicy.normalizeCountryCode(profile)?.let { return it }
        if (profile.equals("ir", ignoreCase = true) || profile.equals("iran", ignoreCase = true)) return "IR"
        return countryFromKey(key)
    }

    fun locationCode(node: Node): String {
        ConnectionLocationPolicy.normalizeCountryCode(node.country)?.let { return it }
        ConnectionLocationPolicy.normalizeCountryCode(node.profile)?.let { return it }
        if (node.profile.equals("ir", ignoreCase = true) || node.profile.equals("iran", ignoreCase = true)) return "IR"
        return "GLOBAL"
    }

    fun locationLabel(node: Node): LocationLabel {
        val code = locationCode(node)
        if (code == "GLOBAL") {
            return LocationLabel("GLOBAL", "🌍", "جهانی", "Global")
        }
        val fa = ConnectionLocationPolicy.countryFromCode(code, Locale.forLanguageTag("fa"))
        val en = ConnectionLocationPolicy.countryFromCode(code, Locale.ENGLISH)
        return LocationLabel(
            code = code,
            flag = en?.flag ?: fa?.flag ?: "🌐",
            nameFa = fa?.country ?: code,
            nameEn = en?.country ?: code,
        )
    }

    fun pickerLine(ready: Ready, fa: Boolean): String {
        val loc = locationLabel(ready.node)
        return "${loc.line(fa)} · ${ready.node.host} · ${ready.rttMs} ms"
    }

    /** At least one host from every country in the signed list; not only IR. */
    fun pickAllCountries(nodes: List<Node>, perCountry: Int = 4, maxTotal: Int = 48): List<Node> {
        if (nodes.isEmpty()) return emptyList()
        val grouped = nodes.groupBy { locationCode(it) }.toSortedMap()
        val queues = grouped.map { it.value.shuffled() }
        val out = ArrayList<Node>()
        var round = 0
        val cap = perCountry.coerceAtLeast(1)
        val max = maxTotal.coerceAtLeast(1)
        while (out.size < max && round < cap) {
            var added = false
            for (q in queues) {
                if (out.size >= max) break
                val next = q.getOrNull(round) ?: continue
                out += next
                added = true
            }
            if (!added) break
            round++
        }
        return out
    }

    fun pickForOneTap(nodes: List<Node>, limit: Int = 16): List<Node> {
        val ir = nodes.filter { it.profile == "ir" }.shuffled()
        val rest = nodes.filter { it.profile != "ir" }.shuffled()
        return (ir + rest).take(limit.coerceAtLeast(1))
    }

    fun raceHandshakes(
        nodes: List<Node>,
        http: EdgeHttp,
        maxParallel: Int = 6,
        maxKeep: Int = 6,
        publicKeyDer: ByteArray = rsaSpki(),
    ): List<Ready> {
        if (nodes.isEmpty()) return emptyList()
        val ready = java.util.Collections.synchronizedList(ArrayList<Ready>())
        val kept = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(maxParallel.coerceIn(1, 8))
        try {
            val futures = nodes.map { node ->
                pool.submit<Ready?> {
                    if (kept.get() >= maxKeep) return@submit null
                    val row = runCatching { handshake(node, http, publicKeyDer = publicKeyDer) }.getOrNull() ?: return@submit null
                    if (kept.incrementAndGet() <= maxKeep) {
                        ready += row
                        row
                    } else null
                }
            }
            futures.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        return ready.sortedBy { it.rttMs }
    }
}
