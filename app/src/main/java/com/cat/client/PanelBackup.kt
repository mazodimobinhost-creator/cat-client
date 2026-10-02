package com.cat.client

import android.content.Context

/**
 * Local, private snapshot of the panel's last known-good settings.
 *
 * If Cloudflare suspends the worker after an abuse report (every route answers
 * Error 1101 and no code runs at all), the panel cannot heal itself. Recovery
 * means deploying a fresh worker under a new neutral name and PUTting this
 * JSON back through /api/settings — users, proxy IPs, ports and routing come
 * back intact. Everything stays on the phone; nothing is uploaded anywhere.
 */
object PanelBackup {
    private const val PREFS = "cat_client_panel_backup"
    private const val KEY_BASE = "base"
    private const val KEY_PATH = "path"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_TS = "ts"

    data class Snapshot(
        val baseUrl: String,
        val panelPath: String,
        val settingsJson: String,
        val savedAt: Long,
    )

    fun save(context: Context, baseUrl: String, settingsJson: String, panelPath: String) {
        if (settingsJson.isBlank() || baseUrl.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE, baseUrl.trim().trimEnd('/'))
            .putString(KEY_PATH, panelPath.trim())
            .putString(KEY_SETTINGS, settingsJson)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }

    fun read(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = prefs.getString(KEY_SETTINGS, null).orEmpty()
        val base = prefs.getString(KEY_BASE, null).orEmpty()
        if (settings.isBlank() || base.isBlank()) return null
        return Snapshot(
            baseUrl = base,
            panelPath = prefs.getString(KEY_PATH, null).orEmpty(),
            settingsJson = settings,
            savedAt = prefs.getLong(KEY_TS, 0L),
        )
    }
}
