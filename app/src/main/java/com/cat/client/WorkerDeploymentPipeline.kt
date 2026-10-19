package com.cat.client

import java.security.MessageDigest

internal data class CloudflareTargetSelection(
    val accountId: String,
    val workerName: String,
) {
    val isValid: Boolean
        get() = accountId.trim().length >= 8 &&
            Regex("^[a-z0-9][a-z0-9-_]{0,62}$").matches(workerName.trim())
}

internal data class WorkerBindingSet(
    val kvNamespaces: Map<String, String> = emptyMap(),
    val d1Databases: Map<String, String> = emptyMap(),
    val plainTextVars: Map<String, String> = emptyMap(),
    val secretNames: Set<String> = emptySet(),
) {
    fun allBindingKeys(): Set<String> = buildSet {
        kvNamespaces.keys.forEach { add("kv:$it") }
        d1Databases.keys.forEach { add("d1:$it") }
        plainTextVars.keys.forEach { add("var:$it") }
        secretNames.forEach { add("secret:$it") }
    }

    companion object {
        /** Safe canonical defaults: OPEN_SUB stays false and default panel login stays admin/admin. */
        fun canonicalDefaults(): WorkerBindingSet = WorkerBindingSet(
            plainTextVars = mapOf(
                "OPEN_SUB" to "false",
                "PANEL_USER" to "admin",
                "PANEL_PASSWORD" to "admin",
            ),
        )
    }
}

internal data class WorkerBindingDiff(
    val preserved: Set<String>,
    val added: Set<String>,
    val modified: Set<String>,
    val removed: Set<String>,
) {
    val changed: Set<String> get() = added + modified + removed
}

internal data class WorkerRollbackSnapshot(
    val target: CloudflareTargetSelection,
    val previousVersion: String?,
    val previousSha256: String,
    val previousSourceBytes: ByteArray,
    val bindings: WorkerBindingSet,
    val panelSettingsJson: String? = null,
    val createdAtMs: Long,
) {
    fun isValidForRollback(): Boolean =
        target.isValid &&
            previousSourceBytes.isNotEmpty() &&
            previousSha256.length == 64 &&
            WorkerDeploymentPlanner.sha256Hex(previousSourceBytes).equals(previousSha256, ignoreCase = true)
}

internal sealed interface WorkerDeploymentPlanResult {
    data class Ready(
        val target: CloudflareTargetSelection,
        val inspection: WorkerSourceInspection,
        val sourceSha256: String,
        val deploySha256: String,
        val bindings: WorkerBindingSet,
        val bindingDiff: WorkerBindingDiff,
        val preview: CloudflareOperationPreview,
    ) : WorkerDeploymentPlanResult

    data class Blocked(val reason: String) : WorkerDeploymentPlanResult
}

internal object WorkerDeploymentPlanner {
    fun diffBindings(current: WorkerBindingSet, target: WorkerBindingSet): WorkerBindingDiff {
        val preserved = LinkedHashSet<String>()
        val added = LinkedHashSet<String>()
        val modified = LinkedHashSet<String>()
        val removed = LinkedHashSet<String>()

        fun compareMap(prefix: String, before: Map<String, String>, after: Map<String, String>) {
            for ((k, v) in before) {
                val key = "$prefix:$k"
                when {
                    k !in after -> removed += key
                    after[k] == v -> preserved += key
                    else -> modified += key
                }
            }
            for (k in after.keys) {
                if (k !in before) added += "$prefix:$k"
            }
        }

        compareMap("kv", current.kvNamespaces, target.kvNamespaces)
        compareMap("d1", current.d1Databases, target.d1Databases)
        compareMap("var", current.plainTextVars, target.plainTextVars)

        for (secret in current.secretNames) {
            val key = "secret:$secret"
            if (secret in target.secretNames) preserved += key else removed += key
        }
        for (secret in target.secretNames) {
            if (secret !in current.secretNames) added += "secret:$secret"
        }

        return WorkerBindingDiff(preserved, added, modified, removed)
    }

    /** Non-destructive merge: existing KV, D1, secrets and variables (including UUID)
     * are preserved unless an explicit destructive rebuild is requested. */
    fun mergePreservingExisting(
        current: WorkerBindingSet,
        patch: WorkerBindingSet,
        cleanRebuild: Boolean = false,
    ): WorkerBindingSet {
        if (cleanRebuild) {
            val baseVars = WorkerBindingSet.canonicalDefaults().plainTextVars + patch.plainTextVars
            return patch.copy(plainTextVars = baseVars)
        }
        return WorkerBindingSet(
            kvNamespaces = current.kvNamespaces + patch.kvNamespaces,
            d1Databases = current.d1Databases + patch.d1Databases,
            plainTextVars = WorkerBindingSet.canonicalDefaults().plainTextVars + current.plainTextVars + patch.plainTextVars,
            secretNames = current.secretNames + patch.secretNames,
        )
    }

    fun planRawUrlDeploy(
        target: CloudflareTargetSelection,
        rawUrl: String,
        sourceBytes: ByteArray,
        deployedBytes: ByteArray = sourceBytes,
        transform: WorkerArtifactTransform = WorkerArtifactTransform.NONE,
        syntaxVerified: Boolean = true,
        importVerified: Boolean = true,
        obfuscationWarningAccepted: Boolean = false,
        currentVersion: String? = null,
        currentBindings: WorkerBindingSet = WorkerBindingSet(),
        desiredBindings: WorkerBindingSet = WorkerBindingSet(),
        cleanRebuild: Boolean = false,
        rollbackSnapshot: WorkerRollbackSnapshot? = null,
        approval: CloudflareOperationApproval,
    ): WorkerDeploymentPlanResult {
        if (!target.isValid) return WorkerDeploymentPlanResult.Blocked("invalid-target-selection")
        when (val urlCheck = WorkerSourcePolicy.validateUrl(rawUrl)) {
            is WorkerSourceDecision.Blocked -> return WorkerDeploymentPlanResult.Blocked(urlCheck.reason)
            is WorkerSourceDecision.Allowed -> Unit
        }
        when (val sizeCheck = WorkerSourcePolicy.validateDownloadedSize(sourceBytes.size.toLong(), sourceBytes.size.toLong())) {
            is WorkerSourceDecision.Blocked -> return WorkerDeploymentPlanResult.Blocked(sizeCheck.reason)
            is WorkerSourceDecision.Allowed -> Unit
        }
        val inspection = WorkerSourceInspector.inspect(sourceBytes)
        if (!inspection.valid) return WorkerDeploymentPlanResult.Blocked(inspection.reason ?: "invalid-worker-source")

        val sourceSha = sha256Hex(sourceBytes)
        val deploySha = sha256Hex(deployedBytes)
        val compat = WorkerArtifactCompatibilityPolicy.decide(
            WorkerArtifactCompatibilityRequest(
                transform = transform,
                sourceSha256 = sourceSha,
                outputSha256 = deploySha,
                syntaxVerified = syntaxVerified,
                importVerified = importVerified,
                warningAccepted = obfuscationWarningAccepted,
            ),
        )
        if (compat is WorkerArtifactCompatibilityDecision.Blocked) {
            return WorkerDeploymentPlanResult.Blocked(compat.reason)
        }

        val isExistingWorker = currentVersion != null || currentBindings.allBindingKeys().isNotEmpty()
        if (isExistingWorker && (rollbackSnapshot == null || !rollbackSnapshot.isValidForRollback())) {
            return WorkerDeploymentPlanResult.Blocked("rollback-snapshot-required")
        }

        val mergedBindings = mergePreservingExisting(currentBindings, desiredBindings, cleanRebuild = cleanRebuild)
        val diff = diffBindings(currentBindings, mergedBindings)
        val operation = when {
            cleanRebuild -> CloudflareOperation.CLEAN_REBUILD
            transform == WorkerArtifactTransform.OBFUSCATE -> CloudflareOperation.OBFUSCATE_ARTIFACT
            diff.removed.isNotEmpty() -> CloudflareOperation.REMOVE_BINDING
            isExistingWorker -> CloudflareOperation.UPDATE_WORKER
            else -> CloudflareOperation.CREATE_WORKER
        }
        val preview = CloudflareOperationPreview(
            operation = operation,
            workerName = target.workerName.trim(),
            currentVersion = currentVersion,
            targetVersion = inspection.version,
            artifactSha256 = deploySha,
            preservedBindings = diff.preserved,
            changedBindings = diff.changed,
        )
        return when (val decision = CloudflareOperationApprovalPolicy.decide(preview, approval)) {
            is CloudflareApprovalDecision.Blocked -> WorkerDeploymentPlanResult.Blocked(decision.reason)
            CloudflareApprovalDecision.Allowed -> WorkerDeploymentPlanResult.Ready(
                target = target,
                inspection = inspection,
                sourceSha256 = sourceSha,
                deploySha256 = deploySha,
                bindings = mergedBindings,
                bindingDiff = diff,
                preview = preview,
            )
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
