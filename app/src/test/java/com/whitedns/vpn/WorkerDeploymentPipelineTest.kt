package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerDeploymentPipelineTest {
    private val target = CloudflareTargetSelection("account-12345678", "cat-panel-worker")
    private val validWorkerBytes = """
        const CAT_PANEL_VERSION = '6.68.0';
        export default { async fetch(request, env, ctx) { return new Response('ok'); } };
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun snapshot(): WorkerRollbackSnapshot = WorkerRollbackSnapshot(
        target = target,
        previousVersion = "6.67.0",
        previousSha256 = WorkerDeploymentPlanner.sha256Hex(validWorkerBytes),
        previousSourceBytes = validWorkerBytes,
        bindings = WorkerBindingSet(
            kvNamespaces = mapOf("CAT_KV" to "ns-1"),
            d1Databases = mapOf("DB" to "d1-1"),
            plainTextVars = mapOf("UUID" to "keep-uuid"),
            secretNames = setOf("TG_TOKEN"),
        ),
        createdAtMs = 1_000L,
    )

    @Test fun canonicalDefaultsKeepOpenSubFalseAndAdminCredentials() {
        val defaults = WorkerBindingSet.canonicalDefaults()
        assertEquals("false", defaults.plainTextVars["OPEN_SUB"])
        assertEquals("admin", defaults.plainTextVars["PANEL_USER"])
        assertEquals("admin", defaults.plainTextVars["PANEL_PASSWORD"])
    }

    @Test fun rawUrlUpdatePreservesExistingKvD1UuidAndSecretsWithRollbackSnapshot() {
        val existing = snapshot().bindings
        val result = WorkerDeploymentPlanner.planRawUrlDeploy(
            target = target,
            rawUrl = "https://raw.githubusercontent.com/example/repo/main/worker.js",
            sourceBytes = validWorkerBytes,
            currentVersion = "6.67.0",
            currentBindings = existing,
            desiredBindings = WorkerBindingSet(plainTextVars = mapOf("PROXYIP" to "103.237.102.191:11111")),
            rollbackSnapshot = snapshot(),
            approval = CloudflareOperationApproval(previewAccepted = true),
        )
        assertTrue(result is WorkerDeploymentPlanResult.Ready)
        val ready = result as WorkerDeploymentPlanResult.Ready
        assertEquals("ns-1", ready.bindings.kvNamespaces["CAT_KV"])
        assertEquals("d1-1", ready.bindings.d1Databases["DB"])
        assertEquals("keep-uuid", ready.bindings.plainTextVars["UUID"])
        assertEquals("false", ready.bindings.plainTextVars["OPEN_SUB"])
        assertEquals("103.237.102.191:11111", ready.bindings.plainTextVars["PROXYIP"])
        assertTrue("secret:TG_TOKEN" in ready.bindingDiff.preserved)
        assertTrue("var:PROXYIP" in ready.bindingDiff.added)
        assertTrue(ready.bindingDiff.removed.isEmpty())
    }

    @Test fun existingWorkerUpdateRequiresValidRollbackSnapshot() {
        val result = WorkerDeploymentPlanner.planRawUrlDeploy(
            target = target,
            rawUrl = "https://raw.githubusercontent.com/example/repo/main/worker.js",
            sourceBytes = validWorkerBytes,
            currentVersion = "6.67.0",
            currentBindings = snapshot().bindings,
            rollbackSnapshot = null,
            approval = CloudflareOperationApproval(previewAccepted = true),
        )
        assertEquals(WorkerDeploymentPlanResult.Blocked("rollback-snapshot-required"), result)
    }

    @Test fun obfuscationRequiresExplicitWarningAndTwoStepApproval() {
        val minifiedBytes = """export default{async fetch(r,e){return new Response("ok")}};""".toByteArray()
        val blockedWarning = WorkerDeploymentPlanner.planRawUrlDeploy(
            target = target,
            rawUrl = "https://raw.githubusercontent.com/example/repo/main/worker.js",
            sourceBytes = validWorkerBytes,
            deployedBytes = minifiedBytes,
            transform = WorkerArtifactTransform.OBFUSCATE,
            obfuscationWarningAccepted = false,
            approval = CloudflareOperationApproval(previewAccepted = true, destructiveWarningAccepted = true),
        )
        assertEquals(WorkerDeploymentPlanResult.Blocked("obfuscation-warning-required"), blockedWarning)

        val allowed = WorkerDeploymentPlanner.planRawUrlDeploy(
            target = target,
            rawUrl = "https://raw.githubusercontent.com/example/repo/main/worker.js",
            sourceBytes = validWorkerBytes,
            deployedBytes = minifiedBytes,
            transform = WorkerArtifactTransform.OBFUSCATE,
            obfuscationWarningAccepted = true,
            approval = CloudflareOperationApproval(previewAccepted = true, destructiveWarningAccepted = true),
        )
        assertTrue(allowed is WorkerDeploymentPlanResult.Ready)
    }

    @Test fun cleanRebuildRequiresBackupAndExactTypedWorkerName() {
        val blocked = WorkerDeploymentPlanner.planRawUrlDeploy(
            target = target,
            rawUrl = "https://raw.githubusercontent.com/example/repo/main/worker.js",
            sourceBytes = validWorkerBytes,
            currentVersion = "6.67.0",
            currentBindings = snapshot().bindings,
            cleanRebuild = true,
            rollbackSnapshot = snapshot(),
            approval = CloudflareOperationApproval(
                previewAccepted = true,
                destructiveWarningAccepted = true,
                backupExported = true,
                typedWorkerName = "wrong-name",
            ),
        )
        assertEquals(WorkerDeploymentPlanResult.Blocked("worker-name-mismatch"), blocked)

        val ready = WorkerDeploymentPlanner.planRawUrlDeploy(
            target = target,
            rawUrl = "https://raw.githubusercontent.com/example/repo/main/worker.js",
            sourceBytes = validWorkerBytes,
            currentVersion = "6.67.0",
            currentBindings = snapshot().bindings,
            cleanRebuild = true,
            rollbackSnapshot = snapshot(),
            approval = CloudflareOperationApproval(
                previewAccepted = true,
                destructiveWarningAccepted = true,
                backupExported = true,
                typedWorkerName = "cat-panel-worker",
            ),
        )
        assertTrue(ready is WorkerDeploymentPlanResult.Ready)
    }
}
