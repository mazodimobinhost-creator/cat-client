package com.cat.client

/** Evidence consumed by the automatic-connection flow. Nothing here performs a
 * network request: device-side scanners produce these bounded observations and
 * the planner only selects a candidate whose success was actually observed. */
internal data class AutoConnectionCandidate(
    val id: String,
    val endpoint: String,
    val source: String,
    val carrier: MobileCarrier = MobileCarrier.UNKNOWN,
    val latencyMs: Long? = null,
    val verifiedAtMs: Long? = null,
    val succeeded: Boolean = false,
)

internal sealed interface AutoConnectionDecision {
    data class Ready(
        val candidate: AutoConnectionCandidate,
        val reason: String,
    ) : AutoConnectionDecision

    data class Inconclusive(val reason: String) : AutoConnectionDecision
    data class Failed(val reason: String) : AutoConnectionDecision
}

/** Fail-closed selection policy for “build for this network”.
 *
 * - Never turns a feed entry or an unmeasured endpoint into an active profile.
 * - Never labels UNKNOWN as a carrier-specific result.
 * - Expires measurements so changing networks cannot reuse stale evidence.
 * - Gives a matching carrier priority, then chooses the lowest measured latency.
 */
internal object AutoConnectionPlanner {
    const val DEFAULT_EVIDENCE_TTL_MS = 15 * 60 * 1000L

    fun decide(
        candidates: List<AutoConnectionCandidate>,
        detectedCarrier: MobileCarrier,
        nowMs: Long,
        networkAvailable: Boolean,
        evidenceTtlMs: Long = DEFAULT_EVIDENCE_TTL_MS,
    ): AutoConnectionDecision {
        if (!networkAvailable) return AutoConnectionDecision.Inconclusive("network-unavailable")
        if (evidenceTtlMs <= 0) return AutoConnectionDecision.Failed("invalid-evidence-ttl")

        val valid = candidates.asSequence()
            .filter { it.id.isNotBlank() && it.endpoint.isNotBlank() && it.source.isNotBlank() }
            .filter { it.succeeded && it.latencyMs != null && it.latencyMs >= 0 }
            .filter { it.verifiedAtMs != null && nowMs >= it.verifiedAtMs && nowMs - it.verifiedAtMs <= evidenceTtlMs }
            .toList()

        if (valid.isEmpty()) {
            return if (candidates.isEmpty()) {
                AutoConnectionDecision.Inconclusive("no-candidates")
            } else {
                AutoConnectionDecision.Inconclusive("no-fresh-verified-candidate")
            }
        }

        val matching = if (detectedCarrier == MobileCarrier.UNKNOWN) {
            emptyList()
        } else {
            valid.filter { it.carrier == detectedCarrier }
        }
        val pool = matching.ifEmpty { valid.filter { it.carrier == MobileCarrier.UNKNOWN } }
            .ifEmpty { valid }
        val selected = pool.minWithOrNull(compareBy<AutoConnectionCandidate> { it.latencyMs }.thenBy { it.id })
            ?: return AutoConnectionDecision.Inconclusive("no-selectable-candidate")
        val reason = if (matching.contains(selected)) "verified-carrier-match" else "verified-lowest-latency"
        return AutoConnectionDecision.Ready(selected, reason)
    }
}
