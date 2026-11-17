// beta243: the ONE fallback chain for a Cat Warp prepare — shared by AetherSession (TUN) and AetherProxyCarrier (chains).
package com.cat.client

/**
 * beta243 (review round 2). Before this, AetherSession and AetherProxyCarrier each had their own copy of
 * «pinned → automatic discovery → other mode», and they had drifted apart:
 *  - the carrier still repeated discovery when the first attempt already WAS discovery (no endpoint, both families);
 *  - the carrier's alternate table differed (WoM → MASQUE-H2 resolves to the same `wom` transport: a wasted retry);
 *  - the 60 s budget of an unverified hint leaked into the later search stages, cutting the real search short.
 * Both now build their stages here.
 */
internal object AetherFallbackStages {
    private fun searchBudget(config: AetherRuntimeConfig): Long =
        maxOf(config.scanBudgetMillis, AetherNetworkPlanner.SEARCH_BUDGET_MS)

    /** Stage 2: search this line on both IP families with the full budget — null when stage 1 already was exactly that. */
    fun autoDiscovery(config: AetherRuntimeConfig): AetherRuntimeConfig? {
        if (config.endpoint == null && config.ipScan == "both") return null
        return config.copy(endpoint = null, ipScan = "both", scanBudgetMillis = searchBudget(config))
    }

    /** Stage 3: the other mode (same hop count / exit kind as [requestedMode]), searching with the full budget. */
    fun alternate(config: AetherRuntimeConfig, requestedMode: AetherMode = config.mode): AetherRuntimeConfig = config.copy(
        mode = AetherNetworkPlanner.alternateMode(requestedMode),
        exitLocation = AetherNetworkPlanner.exitForAlternate(requestedMode, config.exitLocation),
        endpoint = null,
        ipScan = "both",
        scanBudgetMillis = searchBudget(config),
    )
}
