package com.cat.client

import org.json.JSONObject
import java.io.File

data class AetherRuntimeConfig(
    val mode: AetherMode,
    val identityFile: File,
    val endpoint: String? = null,
    val scanBudgetMillis: Long = 180_000,
) {
    init {
        require(identityFile.isAbsolute) { "Aether identity path must be absolute" }
        require(scanBudgetMillis in 10_000..300_000) { "Aether scan budget is out of range" }
        require(mode != AetherMode.MasquePsiphon) { "Psiphon requires its isolated carrier service" }
        endpoint?.let { require(connectionChainEndpointOrNull(it) != null) { "Invalid Aether endpoint" } }
    }

    fun toNativeJson(): String = JSONObject()
        .put("mode", "tun")
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
        .put("listenPort", 1819)
        .apply { endpoint?.let { put("peer", it) } }
        .toString()
}
