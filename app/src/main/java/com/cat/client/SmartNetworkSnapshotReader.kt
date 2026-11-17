package com.cat.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address

/** Reads only facts Android exposes. UDP/QUIC/DNS stay false until an active probe proves them. */
internal object SmartNetworkSnapshotReader {
    fun read(context: Context): SmartNetworkSnapshot {
        val key = NetworkContext.key(context)
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return unknown(key)
        val network = manager.activeNetwork ?: return unknown(key)
        val capabilities = manager.getNetworkCapabilities(network) ?: return unknown(key)
        val addresses = manager.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
        return SmartNetworkSnapshot(
            key = key,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            ipv4 = addresses.any { it is Inet4Address && !it.isLoopbackAddress },
            ipv6 = addresses.any { it is Inet6Address && !it.isLoopbackAddress && !it.isLinkLocalAddress },
            // Android capabilities do not prove either protocol traverses the carrier.
            udp = false,
            quic = false,
            dnsTampered = false,
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    private fun unknown(key: String) = SmartNetworkSnapshot(
        key = key,
        validated = false,
        captivePortal = false,
        ipv4 = false,
        ipv6 = false,
        udp = false,
        quic = false,
        dnsTampered = false,
        metered = false,
    )
}
