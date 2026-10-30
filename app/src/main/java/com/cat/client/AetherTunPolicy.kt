package com.cat.client

import java.net.InetAddress

internal object AetherTunPolicy {
    /** Cloudflare's cap on MASQUE (H2 and H3) and Cat's long-standing WireGuard value. */
    const val MASQUE_MTU = 1280

    /** Inner hop of WARP-in-WARP and WireGuard-over-MASQUE (the engine's INNER_MTU). */
    const val WARP_IN_WARP_MTU = 1200

    /** Inner hop of nested MASQUE, worst case: (1280 − 48 IPv6/UDP) − 70 MASQUE framing. */
    const val MASQUE_IN_MASQUE_MTU = 1162

    /** IPv6's own floor; Android refuses an interface with an IPv6 address below it. */
    const val IPV6_MINIMUM_MTU = 1280

    /**
     * beta219: the TUN must advertise what the tunnel under it can really carry. Cat always said 1280, also for the
     * two-hop transports whose inner hop carries 1200 (wiw, wom) or 1162 (mim) — the default for every «foreign exit»
     * Cat Warp profile. Small packets passed (so it «connected»), every full-size one was silently dropped: pages,
     * Instagram and downloads hung. Keyed on the `transport` the engine is actually given.
     */
    fun mtuForTransport(transport: String): Int = when (transport.trim().lowercase()) {
        "wiw", "wom" -> WARP_IN_WARP_MTU
        "mim" -> MASQUE_IN_MASQUE_MTU
        else -> MASQUE_MTU
    }

    /** IPv6 can only be put on the interface when the MTU allows it. */
    fun carriesIpv6(mtu: Int): Boolean = mtu >= IPV6_MINIMUM_MTU

    /** The transport written into a native config JSON (`h3` when missing, like the engine). */
    fun transportOfJson(configJson: String): String =
        Regex(""""transport"\s*:\s*"([a-z0-9]+)"""").find(configJson)?.groupValues?.get(1) ?: "h3"

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
