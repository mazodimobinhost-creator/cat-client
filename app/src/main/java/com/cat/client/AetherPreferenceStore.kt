package com.cat.client

import android.content.Context

data class AetherSelection(val enabled: Boolean = false, val mode: AetherMode = AetherMode.MasqueHttp2)

class AetherPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_aether", Context.MODE_PRIVATE)

    fun read(): AetherSelection {
        val enabled = prefs.getBoolean("enabled", false)
        val mode = AetherMode.entries.firstOrNull { it.wireName == prefs.getString("mode", null) }
            ?: AetherMode.MasqueHttp2
        return AetherSelection(enabled, mode)
    }

    fun save(selection: AetherSelection) {
        // Psiphon cannot be selected until its isolated carrier is packaged.
        require(selection.mode != AetherMode.MasquePsiphon || !selection.enabled)
        prefs.edit()
            .putBoolean("enabled", selection.enabled)
            .putString("mode", selection.mode.wireName)
            .apply()
    }
}
