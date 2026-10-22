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
    val ipScan: String = "both",
    val exitLocation: String = "auto",
) {
    init {
        require(identityFile.isAbsolute) { "Aether identity path must be absolute" }
        require(scanBudgetMillis in 10_000..300_000) { "Aether scan budget is out of range" }
        require(mode != AetherMode.MasquePsiphon) { "Psiphon requires its isolated carrier service" }
        require(ipScan in setOf("both", "v4", "v6")) { "Aether ipScan must be both, v4 or v6" }
        require(exitLocation in setOf("auto", "iran", "foreign")) {
            "Aether exitLocation must be auto, iran or foreign"
        }
        endpoint?.let { require(AetherOperations.isSafePublicPeer(it)) { "Invalid or non-public Aether endpoint" } }
        upstreamProxy?.let {
            require(it.matches(Regex("socks5h?://127\\.0\\.0\\.1:[0-9]{2,5}"))) {
                "Aether provisioning proxy must be a loopback SOCKS endpoint"
            }
        }
        require(listenPort in 1024..65535)
    }

    /**
     * Resolves the native bridge transport code (`wg`, `h2`, `wiw`, `wom`):
     * - `iran`: forces single-hop (`wg` or `h2`) so Cloudflare WARP assigns an Iran IP (`IR`).
     * - `foreign`: upgrades single-hop `wg` to `wiw` (WARP-in-WARP / Gool) and `h2` to `wom`
     *   (WireGuard-over-MASQUE-H2) so the inner tunnel handshakes inside the outer tunnel and
     *   exits with a non-Iran (foreign) IP (`!IR`).
     * - `auto`: uses the mode's native transport as-is.
     */
    fun resolvedNativeTransport(): String = when (mode) {
        AetherMode.MasquePsiphon -> error("Psiphon carrier is not ready")
        AetherMode.WireGuard -> if (exitLocation == "foreign") "wiw" else "wg"
        AetherMode.MasqueHttp2 -> if (exitLocation == "foreign") "wom" else "h2"
        AetherMode.WireGuardOverMasque -> if (exitLocation == "iran") "h2" else "wom"
    }

    fun toNativeJson(): String = JSONObject()
        .put("mode", runtimeEndpoint.wireName)
        .put("configPath", identityFile.absolutePath)
        .put("transport", resolvedNativeTransport())
        .put("ipScan", ipScan)
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
