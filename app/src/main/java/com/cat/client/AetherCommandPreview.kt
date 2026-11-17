package com.cat.client

/**
 * The `aether …` command line equivalent to a Cat Warp profile, shown read-only in the editor (PattNG shows the same
 * box). It is a preview of what the app hands the core, so users can compare it with a working PattNG profile; the app
 * itself talks to the core through JSON, not this string.
 */
internal object AetherCommandPreview {
    fun protocolOf(mode: AetherMode): String = when (mode) {
        AetherMode.MasqueInMasque -> "mim"
        AetherMode.WireGuard -> "wg"
        AetherMode.WarpInWarp -> "gool"
        AetherMode.WireGuardOverMasque -> "wg-over-masque"
        AetherMode.MasqueHttp2, AetherMode.MasquePsiphon -> "masque"
    }

    private fun usesMasque(mode: AetherMode) =
        mode in setOf(AetherMode.MasqueHttp2, AetherMode.MasquePsiphon, AetherMode.MasqueInMasque, AetherMode.WireGuardOverMasque)

    fun build(sel: AetherSelection, port: Int = 1819): String {
        val mode = if (sel.mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else sel.mode
        val h2 = usesMasque(mode) && sel.masqueTransport != "h3"
        val parts = mutableListOf("aether", "--bind", "127.0.0.1:$port", "--protocol", protocolOf(mode))
        parts += listOf("--scan", sel.scanMode.ifBlank { "balanced" })
        val noize = sel.noizePreset.ifBlank { "auto" }
        if (noize != "auto" && !h2) parts += listOf("--noize", noize)
        when (sel.ipScan) {
            "v4", "v6" -> parts += listOf("--ip", sel.ipScan)
        }
        if (usesMasque(mode)) {
            parts += listOf("--masque-sni", AetherRuntimeConfig.effectiveMasqueSni(sel.masqueSni) ?: "www.cloudflare.com")
        }
        if (h2) {
            parts += "--h2"
            if (sel.fragmentEnabled) {
                parts += listOf("--fragment", "--fragment-size", sel.fragmentSize.ifBlank { "16-32" },
                    "--fragment-delay", sel.fragmentDelay.ifBlank { "2-10" })
            }
        }
        if (sel.echEnabled) parts += listOf("--ech", "auto")
        parts += listOf("--fingerprint", sel.fingerprint.ifBlank { "chrome" })
        val outer = sel.endpoint?.trim().orEmpty()
        val inner = sel.innerEndpoint?.trim().orEmpty()
        when (mode) {
            AetherMode.MasqueInMasque, AetherMode.WarpInWarp -> when {
                outer.isNotEmpty() && inner.isNotEmpty() -> parts += listOf("--mim-outer", outer, "--mim-inner", inner)
                outer.isNotEmpty() -> parts += listOf("--mim-outer", outer)
                else -> parts += "--mim-scan"
            }
            else -> if (outer.isNotEmpty()) parts += listOf("--peer", outer)
        }
        parts += if (outer.isEmpty()) "--no-quick-reconnect" else "--quick-reconnect"
        when {
            sel.chainTarget.startsWith("psiphon") || sel.chainTarget == "auto-psiphon" -> {
                parts += listOf("--psiphon", if (sel.chainTarget == "psiphon-reverse") "reverse" else "chain")
                if (sel.psiphonRegion.matches(Regex("[A-Za-z]{2}"))) parts += listOf("--psiphon-region", sel.psiphonRegion.uppercase())
            }
            sel.chainTarget.startsWith("tor") -> parts += listOf("--tor", if (sel.chainTarget == "tor-reverse") "reverse" else "chain")
        }
        return parts.joinToString(" ")
    }
}
