package com.whitedns.vpn

import com.cat.client.WorkerArtifactCompatibilityDecision
import com.cat.client.WorkerArtifactCompatibilityPolicy
import com.cat.client.WorkerArtifactCompatibilityRequest
import com.cat.client.WorkerArtifactTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerArtifactCompatibilityTest {
    private val source = "a".repeat(64)
    private val output = "b".repeat(64)

    @Test fun unchangedArtifactMustKeepTheSourceHash() {
        assertTrue(WorkerArtifactCompatibilityPolicy.decide(
            WorkerArtifactCompatibilityRequest(WorkerArtifactTransform.NONE, source, source),
        ) is WorkerArtifactCompatibilityDecision.Allowed)
    }

    @Test fun transformedArtifactRequiresSyntaxAndImportEvidence() {
        val result = WorkerArtifactCompatibilityPolicy.decide(
            WorkerArtifactCompatibilityRequest(WorkerArtifactTransform.MINIFY, source, output),
        )
        assertEquals("syntax-verification-required", (result as WorkerArtifactCompatibilityDecision.Blocked).reason)
    }

    @Test fun obfuscationRequiresASeparateWarningConfirmation() {
        val result = WorkerArtifactCompatibilityPolicy.decide(
            WorkerArtifactCompatibilityRequest(
                WorkerArtifactTransform.OBFUSCATE, source, output,
                syntaxVerified = true, importVerified = true,
            ),
        )
        assertEquals("obfuscation-warning-required", (result as WorkerArtifactCompatibilityDecision.Blocked).reason)
        assertTrue(WorkerArtifactCompatibilityPolicy.decide(
            WorkerArtifactCompatibilityRequest(
                WorkerArtifactTransform.OBFUSCATE, source, output,
                syntaxVerified = true, importVerified = true, warningAccepted = true,
            ),
        ) is WorkerArtifactCompatibilityDecision.Allowed)
    }
}
