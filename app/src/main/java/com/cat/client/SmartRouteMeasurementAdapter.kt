package com.cat.client

/** Converts the app's real HTTPS/speed-test records into advisor evidence. */
internal object SmartRouteMeasurementAdapter {
    fun fromProfiles(
        profiles: List<ConnectionProfile>,
        records: Map<String, ConnectionDelayRecord>,
        countryByFingerprint: Map<String, String?> = emptyMap(),
        currentNetworkKey: String? = null,
    ): List<SmartRouteMeasurement> = profiles.map { profile ->
        val record = records[profile.fingerprint]
        val belongsToNetwork = currentNetworkKey == null || record?.networkKey == currentNetworkKey
        val success = belongsToNetwork && record?.status == ConnectionDelayStatus.Success && record.delayMs != null
        SmartRouteMeasurement(
            id = profile.fingerprint,
            kind = routeKind(profile),
            label = profile.displayTag,
            verdict = when {
                success -> SmartRouteVerdict.WORKS
                belongsToNetwork && record?.status == ConnectionDelayStatus.Failure -> SmartRouteVerdict.FAILED
                else -> SmartRouteVerdict.INCONCLUSIVE
            },
            countryCode = countryByFingerprint[profile.fingerprint],
            medianRttMs = record?.delayMs?.takeIf { success }?.toLong(),
            p95RttMs = record?.delayMs?.takeIf { success }?.toLong(),
            throughputKbps = record?.speedKbps?.takeIf { success }?.toLong(),
            successRate = if (success) 1.0 else 0.0,
            sampleCount = if (record == null || !belongsToNetwork) 0 else 1,
            measuredAtMs = record?.testedAt?.takeIf { belongsToNetwork } ?: 0L,
            supportsUdp = profile.supportsUdpApps,
            supportsIpv6 = profile.isIpv6Literal,
        )
    }

    private fun routeKind(profile: ConnectionProfile): SmartRouteKind {
        val text = (profile.tag + " " + profile.type).lowercase()
        return when {
            "orbot" in text || "tor" in text -> SmartRouteKind.ORBOT
            "dnstt" in text -> SmartRouteKind.DNSTT
            "outline" in text -> SmartRouteKind.OUTLINE
            "worker" in text || "cloudflare" in text -> SmartRouteKind.WORKER
            else -> SmartRouteKind.CONFIG
        }
    }
}
