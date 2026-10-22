package com.cat.client

internal data class WorkerRuntimeOutcome(
    val attempted: Boolean,
    val httpStatus: Int? = null,
    val cloudflareErrorCode: Int? = null,
    val runtimeCompleted: Boolean = false,
)

/** 1101 is a Worker runtime outcome, not a property guessed from an ASN or
 * datacenter name. No attempt means UNKNOWN, never CLEAN. */
internal object RelayRiskEvidence {
    fun classify(outcome: WorkerRuntimeOutcome?): RelayRisk = when {
        outcome == null || !outcome.attempted -> RelayRisk.UNKNOWN
        outcome.cloudflareErrorCode == 1101 -> RelayRisk.RISK_1101
        outcome.runtimeCompleted && outcome.httpStatus in 200..299 -> RelayRisk.CLEAN
        else -> RelayRisk.UNKNOWN
    }

    fun apply(row: RelayCatalogEntry, outcome: WorkerRuntimeOutcome?): RelayCatalogEntry =
        row.copy(risk = classify(outcome))
}
