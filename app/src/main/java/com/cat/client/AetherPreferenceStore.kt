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
