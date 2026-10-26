package com.cat.client

import org.json.JSONObject
import java.io.File

enum class AetherRuntimeEndpoint(val wireName: String) { Tun("tun"), Proxy("proxy") }

data class AetherRuntimeConfig(
    val mode: AetherMode,
    val identityFile: File,
    val endpoint: String? = null,
    val innerEndpoint: String? = null,
    val scanBudgetMillis: Long = 180_000,
    val upstreamProxy: String? = null,
    val runtimeEndpoint: AetherRuntimeEndpoint = AetherRuntimeEndpoint.Tun,
    val listenPort: Int = 1819,
    val ipScan: String = "both",
    val exitLocation: String = "auto",
    val masqueTransport: String = "h2",
    val scanMode: String = "balanced",
    val noizePreset: String = "auto",
    val fingerprint: String = "chrome",
    val fragmentEnabled: Boolean = true,
    val fragmentSize: String = "16-32",
    val fragmentDelay: String = "2-10",
    val masqueSni: String = "",
    val apiHost: String = "",
    val apiSni: String = "",
    val echEnabled: Boolean = false,
    val echDns: String = "https://1.1.1.1/dns-query",
    val echDomain: String = "cloudflare-ech.com",
) {
    init {
        require(identityFile.isAbsolute) { "Aether identity path must be absolute" }
        require(scanBudgetMillis in 10_000..300_000) { "Aether scan budget is out of range" }
        require(mode != AetherMode.MasquePsiphon) { "Psiphon requires its isolated carrier service" }
        require(ipScan in setOf("both", "v4", "v6")) { "Aether ipScan must be both, v4 or v6" }
        require(exitLocation in setOf("auto", "iran", "foreign")) {
            "Aether exitLocation must be auto, iran or foreign"
        }
        require(masqueTransport in setOf("h2", "h3")) { "Aether masqueTransport must be h2 or h3" }
        require(scanMode in VALID_SCAN_MODES) { "Invalid Aether scanMode: $scanMode" }
        require(noizePreset in VALID_NOIZE_PRESETS) { "Invalid Aether noizePreset: $noizePreset" }
        endpoint?.let { require(AetherOperations.isSafePublicPeer(it)) { "Invalid or non-public Aether endpoint" } }
        innerEndpoint?.let { require(AetherOperations.isSafePublicPeer(it)) { "Invalid or non-public Aether inner endpoint" } }
        upstreamProxy?.let {
            require(it.matches(Regex("socks5h?://127\\.0\\.0\\.1:[0-9]{2,5}"))) {
                "Aether provisioning proxy must be a loopback SOCKS endpoint"
            }
        }
        require(listenPort in 1024..65535)
    }

    /**
     * Resolves the native bridge transport code (`wg`, `h2`, `h3`, `wiw`, `mim`, `wom`):
     * - `MasqueInMasque`: always resolves to `"mim"` (MASQUE in MASQUE two-hop tunnel).
     * - `WarpInWarp`: always resolves to `"wiw"` (WARP in WARP / classic Gool two-hop tunnel).
     * - `WireGuardOverMasque`: resolves to `"wom"` (or single-hop if explicitly `iran`).
     * - `iran`: forces single-hop (`wg` or `h2`/`h3`) so Cloudflare WARP assigns an Iran IP (`IR`).
     * - `foreign`: upgrades single-hop `wg` to `wiw` and `h2` to `wom` when not already two-hop.
     */
    fun resolvedNativeTransport(): String = when (mode) {
        AetherMode.MasquePsiphon -> error("Psiphon carrier is not ready")
        AetherMode.MasqueInMasque -> "mim"
        AetherMode.WarpInWarp -> "wiw"
        AetherMode.WireGuard -> if (exitLocation == "foreign") "wiw" else "wg"
        AetherMode.MasqueHttp2 -> when {
            exitLocation == "foreign" -> "wom"
            masqueTransport == "h3" -> "h3"
            else -> "h2"
        }
        AetherMode.WireGuardOverMasque -> if (exitLocation == "iran") {
            if (masqueTransport == "h3") "h3" else "h2"
        } else {
            "wom"
        }
    }

    val usesMasqueHttp2: Boolean
        get() = when (mode) {
            AetherMode.MasqueInMasque, AetherMode.MasqueHttp2, AetherMode.WireGuardOverMasque ->
                masqueTransport != "h3"
            else -> false
        }

    fun toNativeJson(): String {
        val (tlsCiphers, tlsGroups, disableGrease) = when (fingerprint.lowercase()) {
            "firefox" -> Triple(
                "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384",
                "X25519MLKEM768:X25519:P-256:P-384:P-521",
                true,
            )
            "semi-python" -> Triple(
                "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305",
                "X25519MLKEM768:X25519:P-256:P-384:P-521",
                true,
            )
            "go" -> Triple(
                "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305",
                "X25519MLKEM768:X25519:P-256:P-384:P-521",
                true,
            )
            "chrome-120" -> Triple(
                "ALL:!aPSK:!ECDSA+SHA1:!3DES",
                "X25519:P-256:P-384",
                false,
            )
            else -> Triple(
                "ALL:!aPSK:!ECDSA+SHA1:!3DES",
                "X25519MLKEM768:X25519:P-256:P-384",
                false,
            )
        }
        return JSONObject()
            .put("mode", runtimeEndpoint.wireName)
            .put("configPath", identityFile.absolutePath)
            .put("transport", resolvedNativeTransport())
            .put("ipScan", ipScan)
            .put("scanMode", scanMode)
            .put("scan", scanMode)
            .put("noize", noizePreset)
            .put("fingerprint", effectiveFp)
            .put("scanBudgetMillis", scanBudgetMillis)
            .put("validationEnabled", true)
            .put("autoReprovision", false)
            .put("lanSharing", false)
            .put("listenPort", listenPort)
            .put("fragmentTls", fragmentEnabled)
            .put("fragmentSize", fragmentSize.ifBlank { "16-32" })
            .put("fragmentDelay", fragmentDelay.ifBlank { "2-10" })
            .put("tlsCiphers", tlsCiphers)
            .put("tlsGroups", tlsGroups)
            .put("disableGrease", disableGrease)
            .put("tlsKeyShares", tlsGroups)
            .put("tlsCertCompression", "brotli")
            .put("enableGreaseSigalgs", !disableGrease)
            .put("enable_grease_sigalgs", !disableGrease)
            .put("enableEchGrease", !disableGrease)
            .put("enable_ech_grease", !disableGrease)
            .apply {
                if (noizePreset != "auto" && noizePreset.isNotBlank()) {
                    put("noizePreset", noizePreset)
                }
                endpoint?.let {
                    put("peer", it)
                    put("peerFallback", true)
                }
                innerEndpoint?.let { put("innerPeer", it) }
                upstreamProxy?.let { put("upstreamProxy", it) }
                masqueSni.trim().takeIf { it.isNotEmpty() }?.let {
                    put("masqueSni", it)
                    put("masque_sni", it)
                }
                apiHost.trim().takeIf { it.isNotEmpty() }?.let {
                    put("apiHost", it)
                    put("api_host", it)
                }
                apiSni.trim().takeIf { it.isNotEmpty() }?.let {
                    put("apiSni", it)
                    put("api_sni", it)
                }
                if (echEnabled) {
                    put("ech", true)
                    put("encryptedHello", true)
                    put("echDns", echDns.ifBlank { "https://1.1.1.1/dns-query" })
                    put("echDomain", echDomain.ifBlank { "cloudflare-ech.com" })
                }
            }
            .toString()
    }

    companion object {
        val VALID_SCAN_MODES = setOf("turbo", "balanced", "thorough", "verified", "ironclad")
        val VALID_NOIZE_PRESETS = setOf("auto", "off", "light", "firewall", "balanced", "gfw", "aggressive")
        val VALID_FINGERPRINTS = setOf("chrome", "chrome-120", "firefox", "semi-python", "go")
    }
}
