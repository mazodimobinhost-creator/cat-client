package com.cat.client

import java.net.InetAddress

internal object AetherTunPolicy {
    /** Native preparation must return host addresses, never routes or arbitrary text. */
    fun validatedAddresses(ipv4: String, ipv6: String): Pair<String, String> {
        require(validAddress(ipv4, 4)) { "Invalid Cat Warp IPv4 tunnel address" }
        require(validAddress(ipv6, 16)) { "Invalid Cat Warp IPv6 tunnel address" }
        return ipv4 to ipv6
    }

    private fun validAddress(value: String, expectedBytes: Int): Boolean {
        if (value.isBlank() || value.contains('/') || value.any(Char::isWhitespace)) return false
        // Avoid InetAddress DNS lookup: only literal characters reach getByName.
        if (expectedBytes == 4 && !value.matches(Regex("[0-9.]+"))) return false
        if (expectedBytes == 16 && !value.matches(Regex("[0-9a-fA-F:]+"))) return false
        return runCatching { InetAddress.getByName(value).address.size == expectedBytes }.getOrDefault(false)
    }
}
