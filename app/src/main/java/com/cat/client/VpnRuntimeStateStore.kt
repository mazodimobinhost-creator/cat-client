package com.cat.client

import android.content.Context

private const val CURRENT_RUNTIME_STATE_SCHEMA = 1

internal fun restoredVpnState(state: VpnState, persistedSchema: Int): VpnState {
    if (state == VpnState.DailyLimitReached) return VpnState.Stopped
    if (persistedSchema >= CURRENT_RUNTIME_STATE_SCHEMA) return state
    return if (state == VpnState.Starting || state == VpnState.Started || state == VpnState.Stopping) {
        VpnState.Stopped
    } else {
        state
    }
}

object VpnRuntimeStateStore {
    private const val PREFS = "cat_client_runtime_state"
    private const val KEY_STATE = "state"
    private const val KEY_ERROR = "error"
    private const val KEY_SESSION_STARTED_AT_ELAPSED_MS = "session_started_at_elapsed_ms"
    private const val KEY_CONNECTION_COUNTRY_FLAG = "connection_country_flag"
    private const val KEY_DEBUG_FRONTING_IP = "debug_fronting_ip"
    private const val KEY_CONNECTION_DETAILS = "connection_details"
    private const val KEY_ACTIVE_SUBSCRIPTION_ID = "active_subscription_id"
    private const val KEY_ACTIVE_CONNECTION_TAG = "active_connection_tag"
    private const val KEY_ACTIVE_CONNECTION_FINGERPRINT = "active_connection_fingerprint"
    private const val KEY_CHAIN_HOP_COUNT = "chain_hop_count"
    private const val KEY_LIVE_SELECTOR_READY = "live_selector_ready"
    private const val KEY_SELECTABLE_CONNECTION_FINGERPRINTS = "selectable_connection_fingerprints"
    private const val KEY_ALWAYS_ON = "always_on"
    private const val KEY_LOCKDOWN = "lockdown"
    private const val KEY_STATE_SCHEMA = "state_schema"
    private const val KEY_AUTO_RECONNECT_ON_DROP = "auto_reconnect_on_drop"
    private const val KEY_AUTO_CONNECT_ON_BOOT = "auto_connect_on_boot_or_update"
    private const val KEY_KILL_SWITCH_ENABLED = "kill_switch_enabled"
    private const val KEY_LIVE_CONNECT_STEP = "live_connect_step"
    private const val KEY_GAS_RELAY_URL = "gas_relay_url"
    private const val KEY_GAS_USAGE_DAY = "gas_usage_day"
    private const val KEY_GAS_USAGE_COUNT = "gas_usage_count"
    const val GAS_DAILY_FREE_QUOTA = 20_000
    private const val KEY_REAL_INTERNET_RESULT = "real_internet_result"

    /** beta220: last real internet test (JSON of [RealInternetResult]); cleared when the VPN stops. */
    internal fun saveRealInternetResult(context: Context, result: RealInternetResult?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (result == null) remove(KEY_REAL_INTERNET_RESULT) else putString(KEY_REAL_INTERNET_RESULT, result.toJson())
        }.apply()
    }

    internal fun readRealInternetResult(context: Context): RealInternetResult? =
        RealInternetResult.fromJson(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REAL_INTERNET_RESULT, null))

    fun save(
        context: Context,
        state: VpnState,
        sessionStartedAtElapsedMs: Long = 0L,
        connectionCountryFlag: String = "",
        debugFrontingIp: String = "",
        connectionDetails: String = "",
        activeSubscriptionId: String = "",
        activeConnectionTag: String = "",
        activeConnectionFingerprint: String = "",
        chainHopCount: Int = 0,
        liveSelectorReady: Boolean = false,
        selectableConnectionFingerprints: Set<String> = emptySet(),
        alwaysOn: Boolean = false,
        lockdown: Boolean = false,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, state.wireName)
            .putString(KEY_ERROR, (state as? VpnState.Error)?.message.orEmpty())
            .putLong(
                KEY_SESSION_STARTED_AT_ELAPSED_MS,
                if (state == VpnState.Started) sessionStartedAtElapsedMs else 0L,
            )
            .putString(KEY_CONNECTION_COUNTRY_FLAG, if (state == VpnState.Started) connectionCountryFlag else "")
            .putString(KEY_DEBUG_FRONTING_IP, if (state == VpnState.Started) debugFrontingIp else "")
            .putString(KEY_CONNECTION_DETAILS, if (state == VpnState.Started) connectionDetails else "")
            .putString(KEY_ACTIVE_SUBSCRIPTION_ID, if (state == VpnState.Started) activeSubscriptionId else "")
            .putString(KEY_ACTIVE_CONNECTION_TAG, if (state == VpnState.Started) activeConnectionTag else "")
            .putString(
                KEY_ACTIVE_CONNECTION_FINGERPRINT,
                if (state == VpnState.Started) activeConnectionFingerprint else "",
            )
            .putInt(KEY_CHAIN_HOP_COUNT, if (state == VpnState.Started) chainHopCount else 0)
            .putBoolean(KEY_LIVE_SELECTOR_READY, state == VpnState.Started && liveSelectorReady)
            .putStringSet(
                KEY_SELECTABLE_CONNECTION_FINGERPRINTS,
                if (state == VpnState.Started) selectableConnectionFingerprints else emptySet(),
            )
            .putBoolean(KEY_ALWAYS_ON, alwaysOn)
            .putBoolean(KEY_LOCKDOWN, lockdown)
            .putInt(KEY_STATE_SCHEMA, CURRENT_RUNTIME_STATE_SCHEMA)
            .apply()
    }

    fun read(context: Context): VpnState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = VpnState.fromWireName(
            prefs.getString(KEY_STATE, VpnState.Stopped.wireName),
            prefs.getString(KEY_ERROR, null),
        )
        val restoredState = restoredVpnState(state, prefs.getInt(KEY_STATE_SCHEMA, 0))
        if (restoredState != state) {
            save(context, restoredState)
        }
        return restoredState
    }

    fun readSessionStartedAtElapsedMs(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION_STARTED_AT_ELAPSED_MS, 0L)
    }

    fun readConnectionCountryFlag(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONNECTION_COUNTRY_FLAG, null)
            .orEmpty()
    }

    fun readDebugFrontingIp(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEBUG_FRONTING_IP, null)
            .orEmpty()
    }

    fun readConnectionDetails(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONNECTION_DETAILS, null)
            .orEmpty()
    }

    fun readActiveSubscriptionId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_SUBSCRIPTION_ID, null)
            .orEmpty()

    fun readActiveConnectionTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_CONNECTION_TAG, null)
            .orEmpty()

    fun readActiveConnectionFingerprint(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_CONNECTION_FINGERPRINT, null)
            .orEmpty()

    fun readChainHopCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CHAIN_HOP_COUNT, 0)

    fun readLiveSelectorReady(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_SELECTOR_READY, false)

    fun readSelectableConnectionFingerprints(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTABLE_CONNECTION_FINGERPRINTS, emptySet())
            .orEmpty()
            .toSet()

    fun readAlwaysOn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_ON, false)
    }

    fun readLockdown(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKDOWN, false)
    }

    /** Independent switch 1: Reconnect automatically if connection drops (default: false). */
    fun isAutoReconnectOnDropEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RECONNECT_ON_DROP, false)

    fun setAutoReconnectOnDropEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_RECONNECT_ON_DROP, enabled)
            .apply()
    }

    /** Independent switch 2: Connect automatically after device boot or app update (default: false). */
    fun isAutoConnectOnBootOrUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT_ON_BOOT, false)

    fun setAutoConnectOnBootOrUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT_ON_BOOT, enabled)
            .apply()
    }

    /** Universal emergency Kill Switch across all methods and configs (default: false). */
    fun isKillSwitchEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KILL_SWITCH_ENABLED, false)

    fun setKillSwitchEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KILL_SWITCH_ENABLED, enabled)
            .apply()
    }

    /** Smart Unspoofed Direct Bypass (patterniha XTLS/BBS#10): route unspoofed non-sanctioned domains direct. */
    fun isSmartUnspoofedDirectBypassEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("smart_unspoofed_direct_bypass", false)

    fun setSmartUnspoofedDirectBypassEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("smart_unspoofed_direct_bypass", enabled)
            .apply()
    }

    fun readLiveConnectStep(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIVE_CONNECT_STEP, "")
            .orEmpty()

    fun saveLiveConnectStep(context: Context, stepText: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIVE_CONNECT_STEP, stepText)
            .apply()
    }

    fun readGasRelayUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GAS_RELAY_URL, "")
            .orEmpty()

    fun saveGasRelayUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GAS_RELAY_URL, url.trim())
            .apply()
    }

    private fun currentUtcDayKey(): Long = System.currentTimeMillis() / 86_400_000L

    fun readGasDailyUsage(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = currentUtcDayKey()
        val savedDay = prefs.getLong(KEY_GAS_USAGE_DAY, -1L)
        val count = if (savedDay == today) prefs.getInt(KEY_GAS_USAGE_COUNT, 0) else 0
        return count.coerceAtLeast(0) to GAS_DAILY_FREE_QUOTA
    }

    fun incrementGasDailyUsage(context: Context, delta: Int = 1): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = currentUtcDayKey()
        val savedDay = prefs.getLong(KEY_GAS_USAGE_DAY, -1L)
        val base = if (savedDay == today) prefs.getInt(KEY_GAS_USAGE_COUNT, 0) else 0
        val next = (base + delta).coerceAtLeast(0)
        prefs.edit()
            .putLong(KEY_GAS_USAGE_DAY, today)
            .putInt(KEY_GAS_USAGE_COUNT, next)
            .apply()
        return next
    }
}
