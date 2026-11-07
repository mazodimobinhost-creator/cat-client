package com.cat.client

internal enum class EndpointTransport { CELLULAR, WIFI, ETHERNET, VPN, OTHER, UNKNOWN }

/** Privacy-preserving cache identity: no SSID, phone number, IMSI or subscriber ID.
 * The raw MCC/MNC is included only for cellular paths so dual-carrier results do not mix. */
internal object EndpointNetworkIdentity {
    fun build(
        transport: EndpointTransport,
        rawOperatorCode: String? = null,
        metered: Boolean,
        roaming: Boolean = false,
    ): String {
        val operator = if (transport == EndpointTransport.CELLULAR) {
            rawOperatorCode.orEmpty().filter(Char::isDigit).takeIf { it.length in 5..6 } ?: "unknown"
        } else "na"
        return listOf(transport.name.lowercase(), operator,
            if (metered) "metered" else "unmetered",
            if (roaming) "roaming" else "home").joinToString(":")
    }
}
