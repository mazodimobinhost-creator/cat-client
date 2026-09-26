package com.cat.client

import android.content.Context

internal object AppSettingsResetter {
    private val preferenceNames = listOf(
        "cat_client_language",
        "cat_client_theme",
        "cat_client_connection_location",
        "cat_client_split_tunnel",
        "cat_client_fronting_ip",
        "cat_client_tls_integrity",
        "cat_client_connection_options",
        "cat_client_routing",
        "cat_client_privacy",
        "cat_client_connection_mode",
        "cat_client_lan_sharing",
        "cat_client_connection_selection",
        "cat_client_connection_test",
        "cat_client_connection_chain",
    )

    fun reset(context: Context) {
        preferenceNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
        }
        SubscriptionStore(context).saveSelectedSubscriptionId(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID)
    }
}
