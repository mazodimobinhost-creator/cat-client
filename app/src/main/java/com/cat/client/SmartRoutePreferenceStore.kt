package com.cat.client

import android.content.Context

internal class SmartRoutePreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_smart_connection", Context.MODE_PRIVATE)

    fun read(): SmartRoutePreference {
        val mode = runCatching {
            CountrySelectionMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty())
        }.getOrDefault(CountrySelectionMode.AUTOMATIC)
        return SmartRoutePreference(
            countryMode = mode,
            countryCode = prefs.getString(KEY_COUNTRY, null)?.uppercase()?.takeIf { it.length == 2 },
            preferStability = prefs.getBoolean(KEY_STABILITY, true),
            requireUdp = prefs.getBoolean(KEY_UDP, false),
            requireIpv6 = prefs.getBoolean(KEY_IPV6, false),
        )
    }

    fun saveCountry(mode: CountrySelectionMode, countryCode: String?) {
        val normalized = countryCode?.trim()?.uppercase()?.takeIf { it.length == 2 }
        prefs.edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_COUNTRY, normalized)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "country_mode"
        const val KEY_COUNTRY = "country_code"
        const val KEY_STABILITY = "prefer_stability"
        const val KEY_UDP = "require_udp"
        const val KEY_IPV6 = "require_ipv6"
    }
}
