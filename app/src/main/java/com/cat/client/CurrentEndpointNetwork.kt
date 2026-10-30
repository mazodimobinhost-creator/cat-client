package com.cat.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/** Reads only coarse, permission-safe network attributes used to scope probe evidence. */
internal object CurrentEndpointNetwork {
    fun identity(context: Context): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> EndpointTransport.VPN
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> EndpointTransport.CELLULAR
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> EndpointTransport.WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> EndpointTransport.ETHERNET
            capabilities == null -> EndpointTransport.UNKNOWN
            else -> EndpointTransport.OTHER
        }
        val metered = runCatching { connectivity?.isActiveNetworkMetered ?: true }.getOrDefault(true)
        val roaming = if (transport == EndpointTransport.CELLULAR) runCatching {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.isNetworkRoaming ?: false
        }.getOrDefault(false) else false
        val operator = if (transport == EndpointTransport.CELLULAR) CarrierDetector.rawOperatorCode(context) else null
        return EndpointNetworkIdentity.build(transport, operator, metered, roaming)
    }

    fun evidenceKey(
        context: Context,
        endpoint: VerifiedEndpoint,
        protocol: String,
        serverName: String,
        tls: Boolean,
    ): EndpointEvidenceKey = EndpointEvidenceKey(
        networkId = identity(context),
        role = endpoint.role,
        host = endpoint.host,
        port = endpoint.port,
        protocol = protocol.lowercase(),
        serverName = serverName.lowercase(),
        tls = tls,
    )
}
