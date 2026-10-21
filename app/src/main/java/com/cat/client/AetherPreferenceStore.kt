package com.cat.client

import android.content.Context

data class AetherSavedProfile(
    val name: String,
    val mode: AetherMode,
    val ipScan: String,
    val endpoint: String,
    val scannedAt: Long,
)

data class AetherSelection(
    val enabled: Boolean = false,
    val mode: AetherMode = AetherMode.MasqueHttp2,
    val endpoint: String? = null,
    val endpointScannedAt: Long = 0L,
    val psiphonRegion: String = "",
    val ipScan: String = "both",
    val profileName: String = "",
) {
    fun freshEndpoint(now: Long = System.currentTimeMillis()): String? = endpoint?.takeIf {
        endpointScannedAt > 0L && now >= endpointScannedAt && now - endpointScannedAt <= ENDPOINT_MAX_AGE_MS
    }

    companion object {
        const val ENDPOINT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
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
        return AetherSelection(enabled, mode, endpoint, prefs.getLong("endpointScannedAt", 0L), region, ipScan, profileName)
    }

    fun save(selection: AetherSelection) {
        require(selection.psiphonRegion.isEmpty() || selection.psiphonRegion.matches(Regex("[A-Z]{2}")))
        val validIpScan = selection.ipScan.takeIf { it in setOf("both", "v4", "v6") } ?: "both"
        prefs.edit()
            .putBoolean("enabled", selection.enabled)
            .putString("mode", selection.mode.wireName)
            .putString("endpoint", selection.endpoint)
            .putLong("endpointScannedAt", selection.endpointScannedAt)
            .putString("psiphonRegion", selection.psiphonRegion)
            .putString("ipScan", validIpScan)
            .putString("profileName", selection.profileName.trim())
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
                    add(AetherSavedProfile(name, mode, ipScan, ep, ts))
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
                    .put("scannedAt", item.scannedAt),
            )
        }
        prefs.edit().putString("savedProfilesJson", arr.toString()).apply()
    }
}
