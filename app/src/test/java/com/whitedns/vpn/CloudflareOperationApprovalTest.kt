package com.whitedns.vpn

import com.cat.client.CloudflareApprovalDecision
import com.cat.client.CloudflareOperation
import com.cat.client.CloudflareOperationApproval
import com.cat.client.CloudflareOperationApprovalPolicy
import com.cat.client.CloudflareOperationPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareOperationApprovalTest {
    private fun preview(operation: CloudflareOperation, hash: String? = "abc123") =
        CloudflareOperationPreview(operation, "sky-worker", artifactSha256 = hash)

    @Test fun normalMutationRequiresPreviewConfirmation() {
        val blocked = CloudflareOperationApprovalPolicy.decide(
            preview(CloudflareOperation.BIND_KV), CloudflareOperationApproval(false),
        )
        assertEquals("preview-not-accepted", (blocked as CloudflareApprovalDecision.Blocked).reason)
        assertTrue(CloudflareOperationApprovalPolicy.decide(
            preview(CloudflareOperation.BIND_KV), CloudflareOperationApproval(true),
        ) is CloudflareApprovalDecision.Allowed)
    }

    @Test fun secretAndBindingChangesRequireSecondConfirmation() {
        val result = CloudflareOperationApprovalPolicy.decide(
            preview(CloudflareOperation.CHANGE_SECRET), CloudflareOperationApproval(true),
        )
        assertEquals("second-confirmation-required", (result as CloudflareApprovalDecision.Blocked).reason)
    }

    @Test fun cleanRebuildRequiresBackupAndExactWorkerName() {
        val noBackup = CloudflareOperationApprovalPolicy.decide(
            preview(CloudflareOperation.CLEAN_REBUILD),
            CloudflareOperationApproval(true, destructiveWarningAccepted = true, typedWorkerName = "sky-worker"),
        )
        assertEquals("backup-required", (noBackup as CloudflareApprovalDecision.Blocked).reason)
        val typo = CloudflareOperationApprovalPolicy.decide(
            preview(CloudflareOperation.CLEAN_REBUILD),
            CloudflareOperationApproval(true, true, backupExported = true, typedWorkerName = "other"),
        )
        assertEquals("worker-name-mismatch", (typo as CloudflareApprovalDecision.Blocked).reason)
        assertTrue(CloudflareOperationApprovalPolicy.decide(
            preview(CloudflareOperation.CLEAN_REBUILD),
            CloudflareOperationApproval(true, true, backupExported = true, typedWorkerName = "sky-worker"),
        ) is CloudflareApprovalDecision.Allowed)
    }

    @Test fun updateCannotProceedWithoutArtifactHash() {
        val result = CloudflareOperationApprovalPolicy.decide(
            preview(CloudflareOperation.UPDATE_WORKER, hash = null), CloudflareOperationApproval(true),
        )
        assertEquals("artifact-hash-required", (result as CloudflareApprovalDecision.Blocked).reason)
    }
}
