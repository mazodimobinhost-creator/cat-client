package com.cat.client

internal enum class DnsttTransportEvidence { CONFIGURED, NOT_CONFIGURED, UNKNOWN }

internal object SmartRouteProbePolicy {
    fun httpVerdict(
        statusCode: Int?,
        timedOut: Boolean = false,
        networkBefore: String,
        networkAfter: String,
        validatedBefore: Boolean,
        validatedAfter: Boolean,
        captiveBefore: Boolean,
        captiveAfter: Boolean,
    ): SmartRouteVerdict {
        if (networkBefore != networkAfter || !validatedBefore || !validatedAfter || captiveBefore || captiveAfter) {
            return SmartRouteVerdict.INCONCLUSIVE
        }
        if (timedOut || statusCode == null) return SmartRouteVerdict.FAILED
        return if (statusCode in 200..299) SmartRouteVerdict.WORKS else SmartRouteVerdict.FAILED
    }

    fun shouldPersist(verdict: SmartRouteVerdict, networkBefore: String, networkAfter: String): Boolean =
        networkBefore == networkAfter && verdict != SmartRouteVerdict.INCONCLUSIVE

    fun dnsttVerdict(
        orbotEnabled: Boolean,
        evidence: DnsttTransportEvidence,
        httpVerdict: SmartRouteVerdict?,
    ): SmartRouteVerdict = when {
        evidence == DnsttTransportEvidence.NOT_CONFIGURED -> SmartRouteVerdict.NOT_CONFIGURED
        !orbotEnabled -> SmartRouteVerdict.ORBOT_OFF
        evidence != DnsttTransportEvidence.CONFIGURED -> SmartRouteVerdict.UNKNOWN
        httpVerdict == SmartRouteVerdict.WORKS -> SmartRouteVerdict.WORKS
        httpVerdict == SmartRouteVerdict.INCONCLUSIVE -> SmartRouteVerdict.INCONCLUSIVE
        else -> SmartRouteVerdict.FAILED
    }
}

internal object SmartOperatorPolicy {
    fun displayName(hasAccess: Boolean, roaming: Boolean, activeSubscriptionNames: List<String>): String? {
        if (!hasAccess || roaming || activeSubscriptionNames.size != 1) return null
        return activeSubscriptionNames.single().trim().takeIf(String::isNotEmpty)
    }
}
