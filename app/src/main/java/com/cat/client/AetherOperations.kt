package com.cat.client

import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.NativeAetherBridge
import java.io.File
import java.net.InetAddress

internal data class AetherProvisionResult(val identities: List<String>)
internal data class AetherEndpoint(val peer: String, val rttMillis: Long)

/** Explicit foreground operations only; callers own coroutine/lifecycle cancellation. */
internal object AetherOperations {
    fun provision(
        mode: AetherMode,
        identityFile: File,
        upstreamProxy: String? = null,
        ipScan: String = "both",
    ): Result<AetherProvisionResult> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
        val aetherMode = if (mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else mode
        val config = AetherRuntimeConfig(aetherMode, identityFile, upstreamProxy = upstreamProxy, ipScan = ipScan)
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
