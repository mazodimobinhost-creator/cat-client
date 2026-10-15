package com.cat.client

/** A route the app can actually build or activate after the owner approves it. */
internal enum class SmartRouteKind {
    DIRECT,
    CONFIG,
    WORKER,
    ORBOT,
    DNSTT,
    OUTLINE,
}

internal enum class SmartRouteVerdict {
    WORKS,
    INCONCLUSIVE,
    NEEDS_SETUP,
    FAILED,
}

internal enum class CountrySelectionMode {
    AUTOMATIC,
    PREFER,
    LOCK,
}

internal data class SmartNetworkSnapshot(
    val key: String,
    val validated: Boolean,
    val captivePortal: Boolean,
    val ipv4: Boolean,
    val ipv6: Boolean,
    val udp: Boolean,
    val quic: Boolean,
    val dnsTampered: Boolean,
    val metered: Boolean,
)

/**
 * Measurements must come from a probe through this exact route. A reachable port alone must be
 * [SmartRouteVerdict.INCONCLUSIVE], never WORKS.
 */
internal data class SmartRouteMeasurement(
    val id: String,
    val kind: SmartRouteKind,
    val label: String,
    val verdict: SmartRouteVerdict,
    val countryCode: String? = null,
    val exitIp: String? = null,
    val medianRttMs: Long? = null,
    val p95RttMs: Long? = null,
    val throughputKbps: Long? = null,
    val successRate: Double = 0.0,
    val sampleCount: Int = 0,
    val measuredAtMs: Long = 0L,
    val supportsUdp: Boolean = false,
    val supportsIpv6: Boolean = false,
    val requiresUdp: Boolean = false,
    val requiresSetup: Boolean = false,
)

internal data class SmartRoutePreference(
    val countryMode: CountrySelectionMode = CountrySelectionMode.AUTOMATIC,
    val countryCode: String? = null,
    val preferStability: Boolean = true,
    val requireUdp: Boolean = false,
    val requireIpv6: Boolean = false,
)

internal enum class SmartRecommendationReason {
    ONLY_PROVEN_ROUTE,
    LOWEST_STABLE_LATENCY,
    HIGHEST_STABILITY,
    HIGHEST_THROUGHPUT,
    PREFERRED_COUNTRY,
    FALLBACK_COUNTRY,
}

internal data class SmartRouteRecommendation(
    val selected: SmartRouteMeasurement?,
    val alternatives: List<SmartRouteMeasurement>,
    val reasons: List<SmartRecommendationReason>,
    val blockedReason: String? = null,
)

/** Pure decision policy. It never deploys, scans, or changes the active tunnel. */
internal object SmartConnectionAdvisor {
    private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

    fun recommend(
        network: SmartNetworkSnapshot,
        measurements: List<SmartRouteMeasurement>,
        preference: SmartRoutePreference = SmartRoutePreference(),
        nowMs: Long = System.currentTimeMillis(),
    ): SmartRouteRecommendation {
        if (!network.validated || network.captivePortal) {
            return SmartRouteRecommendation(null, emptyList(), emptyList(), "network-not-validated")
        }

        val proven = measurements.asSequence()
            .filter { it.verdict == SmartRouteVerdict.WORKS }
            .filter { it.sampleCount > 0 && it.successRate in 0.0..1.0 }
            .filter { nowMs - it.measuredAtMs in 0..STALE_AFTER_MS }
            .filterNot { it.requiresSetup }
            .filter { !it.requiresUdp || network.udp }
            .filter { !preference.requireUdp || it.supportsUdp }
            .filter { !preference.requireIpv6 || it.supportsIpv6 }
            .toList()

        if (proven.isEmpty()) {
            val setup = measurements.filter {
                it.verdict == SmartRouteVerdict.NEEDS_SETUP || it.requiresSetup
            }
            return SmartRouteRecommendation(null, setup, emptyList(), "no-proven-route")
        }

        val wantedCountry = preference.countryCode?.trim()?.uppercase()?.takeIf { it.length == 2 }
        val inCountry = wantedCountry?.let { wanted ->
            proven.filter { it.countryCode.equals(wanted, ignoreCase = true) }
        }.orEmpty()
        val candidates = when (preference.countryMode) {
            CountrySelectionMode.AUTOMATIC -> proven
            CountrySelectionMode.PREFER -> inCountry.ifEmpty { proven }
            CountrySelectionMode.LOCK -> inCountry
        }
        if (candidates.isEmpty()) {
            return SmartRouteRecommendation(null, proven.sortedByDescending(::score), emptyList(), "locked-country-unavailable")
        }

        val ordered = candidates.sortedWith(
            compareByDescending<SmartRouteMeasurement>(::score)
                .thenBy { it.medianRttMs ?: Long.MAX_VALUE }
                .thenBy { it.id },
        )
        val selected = ordered.first()
        val reasons = buildList {
            if (ordered.size == 1) add(SmartRecommendationReason.ONLY_PROVEN_ROUTE)
            val bestSuccess = ordered.maxOf { it.successRate }
            val bestRtt = ordered.mapNotNull { it.medianRttMs }.minOrNull()
            val bestSpeed = ordered.mapNotNull { it.throughputKbps }.maxOrNull()
            if (selected.successRate >= bestSuccess && preference.preferStability) add(SmartRecommendationReason.HIGHEST_STABILITY)
            if (selected.medianRttMs != null && selected.medianRttMs == bestRtt) add(SmartRecommendationReason.LOWEST_STABLE_LATENCY)
            if (selected.throughputKbps != null && selected.throughputKbps == bestSpeed) add(SmartRecommendationReason.HIGHEST_THROUGHPUT)
            if (wantedCountry != null && selected.countryCode.equals(wantedCountry, true)) add(SmartRecommendationReason.PREFERRED_COUNTRY)
            if (wantedCountry != null && preference.countryMode == CountrySelectionMode.PREFER && inCountry.isEmpty()) add(SmartRecommendationReason.FALLBACK_COUNTRY)
        }.distinct()
        return SmartRouteRecommendation(selected, ordered.drop(1), reasons)
    }

    private fun score(route: SmartRouteMeasurement): Double {
        val latency = route.medianRttMs?.let { 1.0 / (1.0 + it / 180.0) } ?: 0.0
        val tail = route.p95RttMs?.let { 1.0 / (1.0 + it / 500.0) } ?: latency
        val speed = route.throughputKbps?.let { (it / 8_000.0).coerceIn(0.0, 1.0) } ?: 0.0
        val confidence = (route.sampleCount / 6.0).coerceIn(0.0, 1.0)
        return route.successRate * 0.42 + latency * 0.25 + tail * 0.10 + speed * 0.13 + confidence * 0.10
    }
}
