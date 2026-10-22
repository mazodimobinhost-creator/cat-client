package com.cat.client

import android.content.Context

data class AetherSavedProfile(
    val name: String,
    val mode: AetherMode,
    val ipScan: String,
    val endpoint: String,
    val scannedAt: Long,
    val chainTarget: String = "none",
    val exitLocation: String = "auto",
)

data class AetherSelection(
    val enabled: Boolean = false,
    val mode: AetherMode = AetherMode.MasqueHttp2,
    val endpoint: String? = null,
    val endpointScannedAt: Long = 0L,
    val psiphonRegion: String = "",
    val ipScan: String = "both",
    val profileName: String = "",
    val chainTarget: String = "none",
    val exitLocation: String = "auto",
) {
    fun freshEndpoint(now: Long = System.currentTimeMillis()): String? = endpoint?.takeIf {
        endpointScannedAt > 0L && now >= endpointScannedAt && now - endpointScannedAt <= ENDPOINT_MAX_AGE_MS
    }

    /**
     * When chaining Psiphon or Tor (warp -> psiphon/tor) to bypass geo-sanctions,
     * enforce simple protocols (WireGuard or MASQUE-HTTP/2) rather than
     * double-encapsulated WireGuard-over-MASQUE.
     */
    fun effectiveCarrierMode(): AetherMode = when (mode) {
        AetherMode.WireGuard -> AetherMode.WireGuard
        AetherMode.MasqueHttp2 -> AetherMode.MasqueHttp2
        else -> if (ipScan == "v6") AetherMode.WireGuard else AetherMode.MasqueHttp2
    }

    /**
     * Effective exit location policy:
     * - If Psiphon/Tor chain is active, exit is always `"foreign"`.
     * - If `exitLocation` is explicitly `"iran"` or `"foreign"`, returns it.
     * - If `exitLocation == "auto"`, `WireGuardOverMasque` implies `"foreign"` and single-hop implies `"iran"`.
     */
    fun effectiveExitLocation(): String = when {
        chainTarget in setOf("psiphon", "tor") || mode == AetherMode.MasquePsiphon -> "foreign"
        exitLocation in setOf("iran", "foreign") -> exitLocation
        mode == AetherMode.WireGuardOverMasque -> "foreign"
        else -> "iran"
    }

    /**
     * Builds a shareable/copyable `aether://?...#Name` URI representing this Aether configuration,
     * compatible with both Cat Client and PattNG.
     */
    fun toAetherUri(): String {
        val effExit = effectiveExitLocation()
        val finalMode = simplifyModeForChain(mode, chainTarget)
        val proto = when {
            chainTarget in setOf("psiphon", "tor") ->
                if (finalMode == AetherMode.MasqueHttp2) "masque" else "wg"
            finalMode == AetherMode.WireGuard && effExit == "foreign" -> "gool"
            finalMode == AetherMode.WireGuard -> "wg"
            finalMode == AetherMode.WireGuardOverMasque || (finalMode == AetherMode.MasqueHttp2 && effExit == "foreign") ->
                "wg-over-masque"
            else -> "masque"
        }
        val params = mutableListOf("protocol=$proto")
        if (proto == "masque" || proto == "wg-over-masque") {
            params += "transport=h2"
            params += "fingerprint=semi-python"
            params += "fragment=1"
            params += "fragment_size=8-16"
            params += "fragment_delay=5-10"
        }
        params += "ip=${ipScan.takeIf { it in setOf("both", "v4", "v6") } ?: "both"}"
        params += "scan=balanced"
        endpoint?.takeIf { it.isNotBlank() }?.let { peer ->
            params += "endpoint=${java.net.URLEncoder.encode(peer, "UTF-8")}"
        }
        if (chainTarget == "psiphon" || mode == AetherMode.MasquePsiphon) {
            params += "cfil=psiphon"
            params += "chain=psiphon"
            if (psiphonRegion.matches(Regex("[A-Z]{2}"))) {
                params += "region=$psiphonRegion"
            }
        } else if (chainTarget == "tor") {
            params += "cfil=tor"
            params += "chain=tor"
        }
        params += "exit_loc=${if (effExit == "iran") "IR" else "!IR"}"
        val labelBase = profileName.trim().ifEmpty {
            when (proto) {
                "gool" -> "Aether-Gool"
                "wg-over-masque" -> "Aether-WG-MASQUE"
                "wg" -> "Aether-WARP"
                else -> "Aether-MASQUE-H2"
            }
        }
        val hash = java.net.URLEncoder.encode(labelBase, "UTF-8").replace("+", "%20")
        return "aether://?${params.joinToString("&")}#$hash"
    }

    companion object {
        const val ENDPOINT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        val VALID_CHAIN_TARGETS = setOf("none", "psiphon", "tor")
        val VALID_EXIT_LOCATIONS = setOf("auto", "iran", "foreign")

        fun simplifyModeForChain(mode: AetherMode, chainTarget: String): AetherMode {
            if (chainTarget !in setOf("psiphon", "tor")) return mode
            return when (mode) {
                AetherMode.WireGuard, AetherMode.MasqueHttp2 -> mode
                else -> AetherMode.WireGuard
            }
        }

        fun fromAetherUri(raw: String, nowMs: Long = System.currentTimeMillis()): AetherSelection? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("aether://", ignoreCase = true)) return null
            val withoutScheme = trimmed.substring("aether://".length)
            val hashIdx = withoutScheme.indexOf('#')
            val beforeHash = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
            val rawFragment = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1) else ""
            val qIdx = beforeHash.indexOf('?')
            val query = if (qIdx >= 0) beforeHash.substring(qIdx + 1) else beforeHash.trimStart('/')
            val map = mutableMapOf<String, String>()
            query.split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = runCatching { java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrDefault("")
                    val v = runCatching { java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault("")
                    if (k.isNotBlank()) map[k.lowercase()] = v.trim()
                }
            }
            val proto = map["protocol"].orEmpty().lowercase()
            val chainRaw = (map["chain"] ?: map["cfil"]).orEmpty().lowercase()
            val chainTarget = if (chainRaw in setOf("psiphon", "tor")) chainRaw else "none"
            val exitLocRaw = map["exit_loc"].orEmpty().uppercase()
            val exitLocation = when {
                chainTarget != "none" -> "foreign"
                exitLocRaw == "IR" -> "iran"
                exitLocRaw == "!IR" || proto in setOf("gool", "wiw", "wg-over-masque", "wom") -> "foreign"
                else -> "iran"
            }
            val baseMode = when (proto) {
                "wg", "wireguard", "gool", "wiw" -> AetherMode.WireGuard
                "wg-over-masque", "wom" ->
                    if (chainTarget != "none") AetherMode.MasqueHttp2 else AetherMode.WireGuardOverMasque
                else -> AetherMode.MasqueHttp2
            }
            val ipScan = map["ip"].orEmpty().lowercase().takeIf { it in setOf("both", "v4", "v6") } ?: "both"
            val peer = (map["endpoint"] ?: map["peer"])?.takeIf(AetherOperations::isSafePublicPeer)
            val region = map["region"].orEmpty().uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
            val name = runCatching { java.net.URLDecoder.decode(rawFragment, "UTF-8") }.getOrDefault(rawFragment).trim()
            return AetherSelection(
                enabled = true,
                mode = simplifyModeForChain(baseMode, chainTarget),
                endpoint = peer,
                endpointScannedAt = if (peer != null) nowMs else 0L,
                psiphonRegion = region,
                ipScan = ipScan,
                profileName = name.ifEmpty { "Aether" },
                chainTarget = chainTarget,
                exitLocation = exitLocation,
            )
        }
    }
}

class AetherPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_aether", Context.MODE_PRIVATE)

    fun read(): AetherSelection {
        val enabled = prefs.getBoolean("enabled", false)
        val mode = AetherMode.entries.firstOrNull { it.wireName == prefs.getString("mode", null) }
            ?: AetherMode.MasqueHttp2
        val endpoint = prefs.getString("endpoint", null)?.takeIf(AetherOperations::isSafePublicPeer)
        val region = prefs.getString("psiphonRegion", "").orEmpty()
            .takeIf { it.isEmpty() || it.matches(Regex("[A-Z]{2}")) }.orEmpty()
        val ipScan = prefs.getString("ipScan", "both").orEmpty()
            .takeIf { it in setOf("both", "v4", "v6") } ?: "both"
        val profileName = prefs.getString("profileName", "").orEmpty().trim()
        val rawChain = prefs.getString("chainTarget", null).orEmpty()
        val chainTarget = when {
            rawChain in AetherSelection.VALID_CHAIN_TARGETS -> rawChain
            mode == AetherMode.MasquePsiphon -> "psiphon"
            else -> "none"
        }
        val exitLocation = prefs.getString("exitLocation", "auto").orEmpty()
            .takeIf { it in AetherSelection.VALID_EXIT_LOCATIONS } ?: "auto"
        return AetherSelection(
            enabled = enabled,
            mode = mode,
            endpoint = endpoint,
            endpointScannedAt = prefs.getLong("endpointScannedAt", 0L),
            psiphonRegion = region,
            ipScan = ipScan,
            profileName = profileName,
            chainTarget = chainTarget,
            exitLocation = exitLocation,
        )
    }

    fun save(selection: AetherSelection) {
        require(selection.psiphonRegion.isEmpty() || selection.psiphonRegion.matches(Regex("[A-Z]{2}")))
        val validIpScan = selection.ipScan.takeIf { it in setOf("both", "v4", "v6") } ?: "both"
        val validExitLoc = selection.exitLocation.takeIf { it in AetherSelection.VALID_EXIT_LOCATIONS } ?: "auto"
        val validChain = if (validExitLoc == "iran") {
            "none"
        } else {
            selection.chainTarget.takeIf { it in AetherSelection.VALID_CHAIN_TARGETS } ?: "none"
        }
        val normalizedMode = when {
            validExitLoc == "iran" && selection.mode == AetherMode.WireGuardOverMasque -> AetherMode.MasqueHttp2
            else -> AetherSelection.simplifyModeForChain(selection.mode, validChain)
        }
        prefs.edit()
            .putBoolean("enabled", selection.enabled)
            .putString("mode", normalizedMode.wireName)
            .putString("endpoint", selection.endpoint)
            .putLong("endpointScannedAt", selection.endpointScannedAt)
            .putString("psiphonRegion", selection.psiphonRegion)
            .putString("ipScan", validIpScan)
            .putString("profileName", selection.profileName.trim())
            .putString("chainTarget", validChain)
            .putString("exitLocation", validExitLoc)
            .apply()
    }

    fun readSavedProfiles(): List<AetherSavedProfile> {
        val raw = prefs.getString("savedProfilesJson", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim().ifEmpty { continue }
                    val mode = AetherMode.entries.firstOrNull { it.wireName == obj.optString("mode") }
                        ?: AetherMode.MasqueHttp2
                    val ipScan = obj.optString("ipScan", "both").takeIf { it in setOf("both", "v4", "v6") } ?: "both"
                    val ep = obj.optString("endpoint").trim().takeIf(AetherOperations::isSafePublicPeer) ?: continue
                    val ts = obj.optLong("scannedAt", 0L)
                    val chain = obj.optString("chainTarget", "none")
                        .takeIf { it in AetherSelection.VALID_CHAIN_TARGETS } ?: "none"
                    val exitLoc = obj.optString("exitLocation", "auto")
                        .takeIf { it in AetherSelection.VALID_EXIT_LOCATIONS } ?: "auto"
                    add(AetherSavedProfile(name, mode, ipScan, ep, ts, chain, exitLoc))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveNamedProfile(profile: AetherSavedProfile) {
        val existing = readSavedProfiles().filterNot { it.name.equals(profile.name, ignoreCase = true) }
        val updated = (listOf(profile) + existing).take(16)
        val arr = org.json.JSONArray()
        updated.forEach { item ->
            arr.put(
                org.json.JSONObject()
                    .put("name", item.name)
                    .put("mode", item.mode.wireName)
                    .put("ipScan", item.ipScan)
                    .put("endpoint", item.endpoint)
                    .put("scannedAt", item.scannedAt)
                    .put("chainTarget", item.chainTarget)
                    .put("exitLocation", item.exitLocation),
            )
        }
        prefs.edit().putString("savedProfilesJson", arr.toString()).apply()
    }
}
