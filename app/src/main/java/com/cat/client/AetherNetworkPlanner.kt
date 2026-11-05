// beta241: adapts a Cat Warp config to the network it is used on (IP family, endpoint, learned mode, scan budget).
package com.cat.client

/**
 * beta241 — «the Cat Warp configs my app builds only work on my own internet».
 *
 * A config built by the scan wizard carries what worked on the line it was built on: the endpoint scanned there,
 * the IP family (IPv6 when MCI gave that phone IPv6) and the mode (WireGuard where UDP passes). Used on another
 * line — the other operator, a home Wi-Fi without IPv6, a friend's phone — the service used to dial exactly that:
 *  - an IPv6-only search on a network with no IPv6 at all;
 *  - WireGuard on a line that drops UDP, with MASQUE tried only after two full failed stages;
 *  - every stage with the full 180 s search budget, so the connect looked dead for minutes;
 *  - and the link's endpoint was written into THIS network's memory before it was even tried.
 *
 * The plan below keeps the config's intent (Iran / foreign exit, chain, country) and only adapts the parts that
 * belong to the network. It never invents an endpoint: it uses one that really connected on this network, else
 * the link's endpoint as a short-budget hint, else the core's own search.
 */
internal data class AetherConnectPlan(
    val mode: AetherMode,
    /** Exit kept by the plan: a learned alternate for a two-hop mode stays two-hop (foreign exit). */
    val exitLocation: String,
    val ipScan: String,
    val endpoint: String?,
    val scanBudgetMillis: Long,
    /** Short reasons for the diagnostics log («no-ipv6», «learned-mode», «verified-here», «link-hint», «search»). */
    val notes: List<String>,
) {
    fun describe(): String =
        "mode=${mode.wireName} exit=$exitLocation ip=$ipScan endpoint=${endpoint ?: "search"} budget=${scanBudgetMillis / 1000}s ${notes.joinToString(",")}"
}

internal object AetherNetworkPlanner {
    /** An endpoint that already connected on this network: the normal budget, it will answer at once anyway. */
    const val VERIFIED_BUDGET_MS = 180_000L

    /** An endpoint scanned on some OTHER line: try it, but fall back to searching this line quickly. */
    const val HINT_BUDGET_MS = 60_000L

    /** No endpoint at all: the core searches this line with its normal budget. */
    const val SEARCH_BUDGET_MS = 180_000L

    fun isIpv6Peer(peer: String): Boolean = peer.trim().startsWith("[")

    /** The one alternate tried when [mode] fails on a network (AetherSession) — also the only mode that may be learned. */
    fun alternateMode(mode: AetherMode): AetherMode = when (mode) {
        AetherMode.MasqueInMasque -> AetherMode.WireGuardOverMasque
        AetherMode.WarpInWarp -> AetherMode.WireGuard
        AetherMode.WireGuard -> AetherMode.MasqueHttp2
        else -> AetherMode.WireGuard
    }

    /** Mirrors AetherRuntimeConfig.resolvedNativeTransport(): wiw / mim / wom are the two-hop (foreign exit) tunnels. */
    fun isTwoHop(mode: AetherMode, exitLocation: String): Boolean = when (mode) {
        AetherMode.MasqueInMasque, AetherMode.WarpInWarp -> true
        AetherMode.WireGuard, AetherMode.MasqueHttp2 -> exitLocation == "foreign"
        AetherMode.WireGuardOverMasque -> exitLocation != "iran"
        AetherMode.MasquePsiphon -> false
    }

    /**
     * beta241 (review): the alternate of a two-hop config must stay two-hop — WARP-in-WARP («auto» exit) → WireGuard
     * used to become single-hop `wg`, silently swapping the foreign exit for an Iranian one.
     */
    fun exitForAlternate(mode: AetherMode, exitLocation: String): String =
        if (isTwoHop(mode, exitLocation)) "foreign" else exitLocation

    /**
     * @param hasIpv6 whether the underlying (non-VPN) network has a global IPv6 address and route; null = unknown,
     *   in which case nothing is changed on that account.
     * @param learnedMode the mode that really connected on this network when [requestedMode] was asked for
     *   (e.g. WireGuard → MASQUE on a line that drops UDP); null when nothing was learned.
     * @param verifiedFor endpoint that really connected on this network for (mode, ipScan), or null.
     */
    fun plan(
        requestedMode: AetherMode,
        requestedExitLocation: String,
        requestedIpScan: String,
        linkEndpoint: String?,
        hasIpv6: Boolean?,
        learnedMode: AetherMode?,
        verifiedFor: (AetherMode, String) -> String?,
    ): AetherConnectPlan {
        val notes = mutableListOf<String>()
        // Only the one alternate AetherSession itself would try is accepted from memory (stale / odd values ignored).
        val learned = learnedMode?.takeIf { it != requestedMode && it == alternateMode(requestedMode) }
        val mode = learned?.also { notes += "learned-mode" } ?: requestedMode
        val exitLocation = if (learned != null) exitForAlternate(requestedMode, requestedExitLocation) else requestedExitLocation
        val ipScan = if (requestedIpScan == "v6" && hasIpv6 == false) {
            notes += "no-ipv6"
            "v4"
        } else {
            requestedIpScan
        }
        fun allowed(peer: String?): Boolean {
            if (peer.isNullOrBlank()) return false
            val v6 = isIpv6Peer(peer)
            return when {
                v6 && (hasIpv6 == false || ipScan == "v4") -> false
                !v6 && ipScan == "v6" -> false
                else -> true
            }
        }
        val verified = listOf(ipScan, "both").distinct()
            .firstNotNullOfOrNull { family -> verifiedFor(mode, family)?.takeIf(::allowed) }
        if (verified != null) {
            notes += "verified-here"
            return AetherConnectPlan(mode, exitLocation, ipScan, verified.trim(), VERIFIED_BUDGET_MS, notes)
        }
        // The link's endpoint belongs to the link's mode (WireGuard ports differ from MASQUE ones).
        val hint = linkEndpoint?.takeIf { mode == requestedMode && allowed(it) }
        if (hint != null) {
            notes += "link-hint"
            return AetherConnectPlan(mode, exitLocation, ipScan, hint.trim(), HINT_BUDGET_MS, notes)
        }
        if (!linkEndpoint.isNullOrBlank()) notes += "link-endpoint-dropped"
        notes += "search"
        return AetherConnectPlan(mode, exitLocation, ipScan, null, SEARCH_BUDGET_MS, notes)
    }
}
