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
        require(identityFile.isAbsolute) { "Cat Warp identity path must be absolute" }
        require(scanBudgetMillis in 10_000..300_000) { "Cat Warp scan budget is out of range" }
        require(mode != AetherMode.MasquePsiphon) { "Psiphon requires its isolated carrier service" }
        require(ipScan in setOf("both", "v4", "v6")) { "Cat Warp ipScan must be both, v4 or v6" }
        require(exitLocation in setOf("auto", "iran", "foreign")) {
            "Cat Warp exitLocation must be auto, iran or foreign"
        }
        require(masqueTransport in setOf("h2", "h3")) { "Cat Warp masqueTransport must be h2 or h3" }
        require(scanMode in VALID_SCAN_MODES) { "Invalid Cat Warp scanMode: $scanMode" }
        require(noizePreset in VALID_NOIZE_PRESETS) { "Invalid Cat Warp noizePreset: $noizePreset" }
        endpoint?.let { require(AetherOperations.isSafePublicPeer(it)) { "Invalid or non-public Cat Warp endpoint" } }
        innerEndpoint?.let { require(AetherOperations.isSafePublicPeer(it)) { "Invalid or non-public Cat Warp inner endpoint" } }
        upstreamProxy?.let {
            require(it.matches(Regex("socks5h?://127\\.0\\.0\\.1:[0-9]{2,5}"))) {
                "Cat Warp provisioning proxy must be a loopback SOCKS endpoint"
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
        val tls = AetherTlsFingerprint.of(fingerprint)
        return JSONObject()
            .put("mode", runtimeEndpoint.wireName)
            .put("configPath", identityFile.absolutePath)
            .put("transport", resolvedNativeTransport())
            .put("ipScan", ipScan)
            .put("scanMode", scanMode)
            .put("scan", scanMode)
            .put("noize", noizePreset)
            .put("fingerprint", tls.type)
            .put("scanBudgetMillis", scanBudgetMillis)
            .put("validationEnabled", true)
            .put("autoReprovision", false)
            .put("lanSharing", false)
            .put("listenPort", listenPort)
            .put("fragmentTls", fragmentEnabled)
            // The core refuses the whole config for an out-of-range fragment value, so bad input falls back.
            .put("fragmentSize", safeRange(fragmentSize, 1, 16_384) ?: "16-32")
            .put("fragmentDelay", safeRange(fragmentDelay, 0, 100) ?: "2-10")
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
                tls.applyTo(this)
                // Aether 2.4 refuses the WHOLE config when one of these is not a DNS name (an IP, a URL, a
                // typo), which turned a harmless optional field into «خطای اتصال». Only real names are sent.
                safeTlsName(masqueSni)?.let {
                    put("masqueSni", it)
                    put("masque_sni", it)
                }
                safeTlsName(apiHost)?.let {
                    put("apiHost", it)
                    put("api_host", it)
                }
                safeTlsName(apiSni)?.let {
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
        private val DNS_NAME = Regex("^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z][A-Za-z0-9-]{0,62}$")

        /** A host name the native core takes for masqueSni/apiHost/apiSni, or null (IP, URL, blank, junk). */
        fun safeTlsName(raw: String): String? {
            val v = raw.trim().trimEnd('.')
            if (v.isEmpty() || v.length > 253) return null
            if (v.any { it.isWhitespace() || it.isISOControl() || it == '/' || it == ':' || it == '@' }) return null
            if (v.matches(Regex("[0-9.]+"))) return null
            return v.takeIf { DNS_NAME.matches(it) }
        }

        /** `n` or ascending `a-b` inside [min, max] (the native bridge's own rule), else null. */
        fun safeRange(raw: String, min: Long, max: Long): String? {
            val parts = raw.trim().split('-')
            if (parts.isEmpty() || parts.size > 2) return null
            val lo = parts.first().trim().toLongOrNull() ?: return null
            val hi = parts.last().trim().toLongOrNull() ?: return null
            if (lo < min || hi < lo || hi > max) return null
            return if (parts.size == 1) "$lo" else "$lo-$hi"
        }

        val VALID_SCAN_MODES = setOf("turbo", "balanced", "thorough", "verified", "ironclad")
        val VALID_NOIZE_PRESETS = setOf("auto", "off", "light", "firewall", "balanced", "gfw", "aggressive")
        val VALID_FINGERPRINTS = setOf("chrome", "chrome-120", "firefox", "semi-python", "go")
    }
}
