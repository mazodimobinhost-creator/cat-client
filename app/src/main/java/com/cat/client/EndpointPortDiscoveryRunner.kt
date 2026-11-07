package com.cat.client

internal data class PortDiscoveryOutcome(
    val candidate: PortProbeCandidate,
    val evidence: EndpointProbeEvidence,
    val verdict: EndpointVerdict,
    val endpoint: VerifiedEndpoint?,
)

/** Runs the explicit discovery plan through a protocol-aware probe supplied by
 * the caller. Only runtime-verified candidates become persisted endpoints. */
internal object EndpointPortDiscoveryRunner {
    fun run(
        candidates: List<PortProbeCandidate>,
        checkedAtMs: Long,
        source: String,
        probe: (PortProbeCandidate) -> EndpointProbeEvidence,
    ): List<PortDiscoveryOutcome> = candidates.distinct().map { candidate ->
        val evidence = runCatching { probe(candidate) }.getOrElse { error ->
            EndpointProbeEvidence(error = error.message ?: error.javaClass.simpleName)
        }
        val verdict = EndpointProbeEvaluator.verdict(candidate.kind, evidence)
        PortDiscoveryOutcome(
            candidate,
            evidence,
            verdict,
            EndpointProbeEvaluator.verifiedEndpointOrNull(candidate, evidence, checkedAtMs, source),
        )
    }

    fun verified(outcomes: List<PortDiscoveryOutcome>): List<VerifiedEndpoint> =
        outcomes.mapNotNull { it.endpoint }.distinctBy { Triple(it.role, it.host.lowercase(), it.port) }
}
