package com.cat.client

import org.json.JSONObject
import java.io.File

enum class AetherRuntimeEndpoint(val wireName: String) { Tun("tun"), Proxy("proxy") }

data class AetherRuntimeConfig(
    val mode: AetherMode,
    val identityFile: File,
    val endpoint: String? = null,
    val scanBudgetMillis: Long = 180_000,
    val upstreamProxy: String? = null,
    val runtimeEndpoint: AetherRuntimeEndpoint = AetherRuntimeEndpoint.Tun,
    val listenPort: Int = 1819,
) {
    init {
        require(identityFile.isAbsolute) { "Aether identity path must be absolute" }
        require(scanBudgetMillis in 10_000..300_000) { "Aether scan budget is out of range" }
        require(mode != AetherMode.MasquePsiphon) { "Psiphon requires its isolated carrier service" }
        endpoint?.let { require(AetherOperations.isSafePublicPeer(it)) { "Invalid or non-public Aether endpoint" } }
        upstreamProxy?.let {
            require(it.matches(Regex("socks5h?://127\\.0\\.0\\.1:[0-9]{2,5}"))) {
                "Aether provisioning proxy must be a loopback SOCKS endpoint"
            }
        }
        require(listenPort in 1024..65535)
    }

    fun toNativeJson(): String = JSONObject()
        .put("mode", runtimeEndpoint.wireName)
        .put("configPath", identityFile.absolutePath)
        .put("transport", when (mode) {
            AetherMode.MasqueHttp2 -> "h2"
            AetherMode.WireGuardOverMasque -> "wom"
            AetherMode.MasquePsiphon -> error("Psiphon carrier is not ready")
        })
        .put("ipScan", "both")
        .put("scanMode", "balanced")
        .put("scanBudgetMillis", scanBudgetMillis)
        .put("validationEnabled", true)
        .put("autoReprovision", false)
        .put("lanSharing", false)
        .put("listenPort", listenPort)
        .apply {
            endpoint?.let { put("peer", it) }
            upstreamProxy?.let { put("upstreamProxy", it) }
        }
        .toString()
}
