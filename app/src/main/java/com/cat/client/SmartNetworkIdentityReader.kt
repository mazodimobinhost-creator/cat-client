package com.cat.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal data class SmartNetworkIdentity(val type: String, val operator: String?)

internal object SmartNetworkIdentityReader {
    fun read(context: Context): SmartNetworkIdentity {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return SmartNetworkIdentity("UNKNOWN", null)
        val network = manager.activeNetwork ?: return SmartNetworkIdentity("UNKNOWN", null)
        val caps = manager.getNetworkCapabilities(network) ?: return SmartNetworkIdentity("UNKNOWN", null)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "UNKNOWN"
        }
        // No phone-state permission is requested merely for display. Multi-SIM/roaming therefore stays unknown.
        return SmartNetworkIdentity(type, null)
    }
}
