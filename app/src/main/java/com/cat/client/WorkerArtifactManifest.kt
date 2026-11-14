package com.cat.client

internal enum class WorkerArtifactChannel {
    APK_BUNDLE,
    RELEASE_ASSET,
    RAW_URL,
    UPDATE,
    REPAIR,
}

internal data class WorkerArtifactRecord(
    val channel: WorkerArtifactChannel,
    val version: String,
    val sha256: String,
    val sizeBytes: Int,
    val module: Boolean,
)

internal sealed interface WorkerManifestDecision {
    data class Verified(val record: WorkerArtifactRecord) : WorkerManifestDecision
    data class Rejected(val reason: String) : WorkerManifestDecision
}

/** Verifies canonical Cat Client Worker artifacts across APK bundle, GitHub
 * release asset, Raw URL, Update and Repair channels using SHA-256 and version checks. */
internal object WorkerArtifactManifest {
    private val blocked1101Versions = setOf("6.69.12")

    fun verify(
        channel: WorkerArtifactChannel,
        bytes: ByteArray,
        expectedSha256: String? = null,
    ): WorkerManifestDecision {
        val inspection = WorkerSourceInspector.inspect(bytes)
        if (!inspection.valid) {
            return WorkerManifestDecision.Rejected(inspection.reason ?: "invalid-worker-source")
        }
        val version = inspection.version ?: if (channel == WorkerArtifactChannel.RAW_URL) "custom" else {
            return WorkerManifestDecision.Rejected("version-marker-required")
        }
        if (version in blocked1101Versions) {
            return WorkerManifestDecision.Rejected("blocked-1101-version")
        }
        val sha256 = WorkerDeploymentPlanner.sha256Hex(bytes)
        if (expectedSha256 != null && !sha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            return WorkerManifestDecision.Rejected("sha256-mismatch")
        }
        return WorkerManifestDecision.Verified(
            WorkerArtifactRecord(
                channel = channel,
                version = version,
                sha256 = sha256,
                sizeBytes = bytes.size,
                module = inspection.module,
            ),
        )
    }

    fun verifyCanonicalConsistency(
        reference: WorkerArtifactRecord,
        candidate: WorkerArtifactRecord,
    ): Boolean = reference.version == candidate.version &&
        reference.sha256.equals(candidate.sha256, ignoreCase = true) &&
        reference.sizeBytes == candidate.sizeBytes
}
