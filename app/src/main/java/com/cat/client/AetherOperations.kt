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
