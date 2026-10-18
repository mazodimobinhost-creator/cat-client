package com.cat.client

import android.content.Context

data class AetherSelection(
    val enabled: Boolean = false,
    val mode: AetherMode = AetherMode.MasqueHttp2,
    val endpoint: String? = null,
    val endpointScannedAt: Long = 0L,
)

class AetherPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_aether", Context.MODE_PRIVATE)

    fun read(): AetherSelection {
        val enabled = prefs.getBoolean("enabled", false)
        val mode = AetherMode.entries.firstOrNull { it.wireName == prefs.getString("mode", null) }
            ?: AetherMode.MasqueHttp2
        val endpoint = prefs.getString("endpoint", null)?.takeIf { connectionChainEndpointOrNull(it) != null }
        return AetherSelection(enabled, mode, endpoint, prefs.getLong("endpointScannedAt", 0L))
    }

    fun save(selection: AetherSelection) {
        // Psiphon cannot be selected until its isolated carrier is packaged.
        require(selection.mode != AetherMode.MasquePsiphon || !selection.enabled)
        prefs.edit()
            .putBoolean("enabled", selection.enabled)
            .putString("mode", selection.mode.wireName)
            .putString("endpoint", selection.endpoint)
            .putLong("endpointScannedAt", selection.endpointScannedAt)
            .apply()
    }
}
