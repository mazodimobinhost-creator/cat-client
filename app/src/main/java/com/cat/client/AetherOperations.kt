package com.cat.client

import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.NativeAetherBridge
import java.io.File
import java.net.InetAddress

internal data class AetherProvisionResult(val identities: List<String>)
internal data class AetherEndpoint(val peer: String, val rttMillis: Long)
internal data class AetherExitTrace(
    val ip: String,
    val countryCode: String,
    val colo: String,
    val warpStatus: String,
) {
    val isIranIp: Boolean get() = countryCode.equals("IR", ignoreCase = true)
}

/** Explicit foreground operations only; callers own coroutine/lifecycle cancellation. */
internal object AetherOperations {
    fun parseExitTrace(raw: String): AetherExitTrace? {
        val map = mutableMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        val ip = map["ip"].orEmpty()
        val loc = map["loc"].orEmpty().uppercase()
        if (ip.isEmpty() || !loc.matches(Regex("[A-Z]{2}"))) return null
        return AetherExitTrace(
            ip = ip,
            countryCode = loc,
            colo = map["colo"].orEmpty().ifEmpty { "?" },
            warpStatus = map["warp"].orEmpty().ifEmpty { "off" },
        )
    }

    fun probeExitTrace(timeoutMs: Int = 5_000): Result<AetherExitTrace> = runCatching {
        val urls = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.cloudflare.com/cdn-cgi/trace",
        )
        var lastError: Throwable? = null
        for (rawUrl in urls) {
            try {
                val conn = (java.net.URL(rawUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CatClient/1.10")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseExitTrace(body)?.let { return@runCatching it }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (err: Throwable) {
                lastError = err
            }
        }
        throw (lastError ?: IllegalStateException("Could not determine exit IP location"))
    }
    fun provision(
        mode: AetherMode,
        identityFile: File,
        upstreamProxy: String? = null,
        ipScan: String = "both",
        exitLocation: String = "auto",
    ): Result<AetherProvisionResult> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
        val aetherMode = if (mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else mode
        val config = AetherRuntimeConfig(
            aetherMode,
            identityFile,
            upstreamProxy = upstreamProxy,
            ipScan = ipScan,
            exitLocation = exitLocation,
        )
        val validation = NativeAetherBridge.validate(config.toNativeJson())
        check(validation.ok) { validation.error ?: "Aether configuration is invalid" }
        val identities = NativeAetherBridge.provision(config.toNativeJson()).getOrThrow()
        check(identities.isNotEmpty()) { "Aether did not return an identity" }
        AetherProvisionResult(identities.map(::redactedIdentity))
    }

    fun scan(
        mode: AetherMode,
        identityFile: File,
        ipScan: String = "both",
    ): Result<List<AetherEndpoint>> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
        val aetherMode = if (mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else mode
        val config = AetherRuntimeConfig(aetherMode, identityFile, ipScan = ipScan)
        NativeAetherBridge.scan(config.toNativeJson()).getOrThrow()
            .asSequence()
            .filter(::safeEndpoint)
            .distinctBy { it.peer }
            .sortedBy { it.rttMillis }
            .take(20)
            .map { AetherEndpoint(it.peer, it.rttMillis.coerceAtLeast(0)) }
            .toList()
    }

    /**
     * Two-stage endpoint confirmation (from WhiteAestherMobile):
     * verifies that the candidate peer actually completes a tunnel handshake and carries traffic.
     */
    fun confirmEndpoint(
        mode: AetherMode,
        identityFile: File,
        peer: String,
        ipScan: String = "both",
        exitLocation: String = "auto",
        budgetMillis: Long = 10_000L,
    ): Result<AetherEndpoint> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
        require(isSafePublicPeer(peer)) { "Peer must be a public IP literal with port" }
        val aetherMode = if (mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else mode
        val baseConfig = AetherRuntimeConfig(
            mode = aetherMode,
            identityFile = identityFile,
            endpoint = peer,
            ipScan = ipScan,
            exitLocation = exitLocation,
        )
        val json = org.json.JSONObject(baseConfig.toNativeJson())
            .put("scanBudgetMillis", budgetMillis.coerceIn(1_000L, 30_000L))
            .put("peerFallback", false)
            .toString()
        val tested = NativeAetherBridge.testEndpoint(json).getOrThrow()
        check(safeEndpoint(tested)) { "Confirmed endpoint failed safety validation" }
        AetherEndpoint(tested.peer, tested.rttMillis.coerceAtLeast(1L))
    }

    fun exportIdentity(identityFile: File): Result<String> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
        require(identityFile.isAbsolute) { "Identity path must be absolute" }
        NativeAetherBridge.exportIdentity(identityFile.absolutePath).getOrThrow()
    }

    fun importIdentity(identityFile: File, payload: String): Result<Unit> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
        require(identityFile.isAbsolute) { "Identity path must be absolute" }
        val trimmed = payload.trim()
        require(trimmed.isNotEmpty()) { "Identity payload is empty" }
        val res = NativeAetherBridge.importIdentity(identityFile.absolutePath, trimmed)
        check(res.ok) { res.error ?: "Failed to import Aether identity" }
    }

    fun drainEngineLogs(): List<String> = NativeAetherBridge.drainLog()

    fun migrateRunningTunnel(): Boolean = NativeAetherBridge.migrate()

    /**
     * Parses Tor Moat circumvention JSON response into bridge lines.
     */
    internal fun parseMoatBridgesJson(rawJson: String): List<String> = runCatching {
        val settings = org.json.JSONObject(rawJson).optJSONArray("settings") ?: return emptyList()
        buildList {
            for (i in 0 until settings.length()) {
                val bridgesObj = settings.optJSONObject(i)?.optJSONObject("bridges") ?: continue
                val arr = bridgesObj.optJSONArray("bridge_strings") ?: continue
                for (j in 0 until arr.length()) {
                    val line = arr.optString(j).trim()
                    if (line.isNotEmpty()) add(line)
                }
            }
        }
    }.getOrDefault(emptyList())

    fun fetchMoatTorBridges(country: String = "ir", timeoutMs: Int = 8_000): Result<List<String>> = runCatching {
        val safeCountry = country.trim().lowercase().takeIf { it.matches(Regex("[a-z]{2}")) } ?: "ir"
        val payload = """{"country":"$safeCountry"}"""
        val conn = (java.net.URL("https://bridges.torproject.org/moat/circumvention/settings").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/vnd.api+json")
            setRequestProperty("User-Agent", "")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            check(conn.responseCode in 200..299) { "Moat bridge service HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = parseMoatBridgesJson(body)
            check(parsed.isNotEmpty()) { "No bridges returned by Moat" }
            parsed
        } finally {
            conn.disconnect()
        }
    }

    fun cancel(): Boolean = NativeAetherBridge.cancelPrepare() or NativeAetherBridge.cancelScan()

    internal fun safeEndpoint(result: EndpointScanResult): Boolean =
        result.rttMillis >= 0 && isSafePublicPeer(result.peer)

    internal fun isSafePublicPeer(peer: String): Boolean {
        if (peer.length !in 3..128) return false
        val endpoint = connectionChainEndpointOrNull(peer) ?: return false
        if (endpoint.port !in 1..65535) return false
        val literal = endpoint.ip
        if (!literal.matches(Regex("[0-9.]+|[0-9a-fA-F:]+"))) return false
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4 && bytes[0] == 100 && bytes[1] in 64..127) return false
        // Inet6Address.isSiteLocalAddress does not classify RFC 4193 ULA on all
        // Android/JVM implementations, so reject fc00::/7 explicitly.
        if (bytes.size == 16 && (bytes[0] and 0xfe) == 0xfc) return false
        return !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress
    }

    private fun redactedIdentity(value: String): String = when {
        value.length <= 8 -> "configured"
        else -> "${value.take(4)}…${value.takeLast(4)}"
    }
}
