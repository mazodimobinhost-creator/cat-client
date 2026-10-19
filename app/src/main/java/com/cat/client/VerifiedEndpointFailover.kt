package com.cat.client

internal enum class FailoverStatus { SWITCHED, KEPT_CURRENT, NO_VERIFIED_REPLACEMENT }
internal data class FailoverResult(
    val status: FailoverStatus,
    val selected: VerifiedEndpoint,
    val previous: VerifiedEndpoint,
)

/** Selection policy used by the manual "change route" action and optional auto-failover.
 * It never rewrites ports and never activates a partial/unknown candidate. */
internal object VerifiedEndpointFailover {
    fun choose(
        current: VerifiedEndpoint,
        candidates: List<VerifiedEndpoint>,
        nowMs: Long,
        maxAgeMs: Long,
    ): FailoverResult {
        if (current.eligibleForSubscription(nowMs, maxAgeMs)) {
            return FailoverResult(FailoverStatus.KEPT_CURRENT, current, current)
        }
        val replacement = candidates.asSequence()
            .filter { it.role == current.role }
            .filter { it.authority != current.authority }
            .filter { it.eligibleForSubscription(nowMs, maxAgeMs) }
            .sortedWith(compareBy<VerifiedEndpoint> { it.latencyMs ?: Long.MAX_VALUE }.thenByDescending { it.checkedAtMs })
            .firstOrNull()
            ?: return FailoverResult(FailoverStatus.NO_VERIFIED_REPLACEMENT, current, current)
        return FailoverResult(FailoverStatus.SWITCHED, replacement, current)
    }
}
