package com.cat.client

internal enum class WorkerArtifactTransform { NONE, MINIFY, OBFUSCATE }

internal data class WorkerArtifactCompatibilityRequest(
    val transform: WorkerArtifactTransform = WorkerArtifactTransform.NONE,
    val sourceSha256: String,
    val outputSha256: String? = null,
    val syntaxVerified: Boolean = false,
    val importVerified: Boolean = false,
    val warningAccepted: Boolean = false,
)

internal sealed interface WorkerArtifactCompatibilityDecision {
    data object Allowed : WorkerArtifactCompatibilityDecision
    data class Blocked(val reason: String) : WorkerArtifactCompatibilityDecision
}

/** Obfuscation is an explicit source-protection compatibility option, never a
 * deployment default. It cannot proceed without distinct output evidence and
 * successful syntax/import checks. */
internal object WorkerArtifactCompatibilityPolicy {
    fun decide(request: WorkerArtifactCompatibilityRequest): WorkerArtifactCompatibilityDecision {
        if (request.sourceSha256.length != 64) return WorkerArtifactCompatibilityDecision.Blocked("source-hash-required")
        if (request.transform == WorkerArtifactTransform.NONE) {
            return if (request.outputSha256 == null || request.outputSha256 == request.sourceSha256) {
                WorkerArtifactCompatibilityDecision.Allowed
            } else WorkerArtifactCompatibilityDecision.Blocked("unexpected-output-hash")
        }
        if (request.outputSha256?.length != 64) return WorkerArtifactCompatibilityDecision.Blocked("output-hash-required")
        if (!request.syntaxVerified) return WorkerArtifactCompatibilityDecision.Blocked("syntax-verification-required")
        if (!request.importVerified) return WorkerArtifactCompatibilityDecision.Blocked("import-verification-required")
        if (request.transform == WorkerArtifactTransform.MINIFY) {
            return WorkerArtifactCompatibilityDecision.Allowed
        }
        if (request.transform == WorkerArtifactTransform.OBFUSCATE && !request.warningAccepted) {
            return WorkerArtifactCompatibilityDecision.Blocked("obfuscation-warning-required")
        }
        return WorkerArtifactCompatibilityDecision.Allowed
    }
}
