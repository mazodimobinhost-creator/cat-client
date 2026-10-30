package com.cat.client

/** Safety contract shared by the in-app Cloudflare installer UI.
 * Every mutating operation must be previewed; destructive operations additionally
 * require an exported backup and an exact worker-name confirmation. */
internal enum class CloudflareOperation {
    VERIFY_TOKEN,
    CREATE_WORKER,
    UPDATE_WORKER,
    BIND_KV,
    BIND_D1,
    CHANGE_SECRET,
    REMOVE_BINDING,
    RESTORE_BACKUP,
    DOWNGRADE_WORKER,
    OBFUSCATE_ARTIFACT,
    CLEAN_REBUILD,
    DELETE_WORKER,
}

internal data class CloudflareOperationPreview(
    val operation: CloudflareOperation,
    val workerName: String,
    val currentVersion: String? = null,
    val targetVersion: String? = null,
    val artifactSha256: String? = null,
    val preservedBindings: Set<String> = emptySet(),
    val changedBindings: Set<String> = emptySet(),
)

internal data class CloudflareOperationApproval(
    val previewAccepted: Boolean,
    val destructiveWarningAccepted: Boolean = false,
    val backupExported: Boolean = false,
    val typedWorkerName: String = "",
)

internal sealed interface CloudflareApprovalDecision {
    data object Allowed : CloudflareApprovalDecision
    data class Blocked(val reason: String) : CloudflareApprovalDecision
}

internal object CloudflareOperationApprovalPolicy {
    private val singleStep = setOf(
        CloudflareOperation.CREATE_WORKER,
        CloudflareOperation.UPDATE_WORKER,
        CloudflareOperation.BIND_KV,
    )
    private val twoStep = setOf(
        CloudflareOperation.BIND_D1,
        CloudflareOperation.CHANGE_SECRET,
        CloudflareOperation.REMOVE_BINDING,
        CloudflareOperation.RESTORE_BACKUP,
        CloudflareOperation.DOWNGRADE_WORKER,
        CloudflareOperation.OBFUSCATE_ARTIFACT,
    )
    private val destructive = setOf(
        CloudflareOperation.CLEAN_REBUILD,
        CloudflareOperation.DELETE_WORKER,
    )

    fun decide(
        preview: CloudflareOperationPreview,
        approval: CloudflareOperationApproval,
    ): CloudflareApprovalDecision {
        if (preview.workerName.isBlank() && preview.operation != CloudflareOperation.VERIFY_TOKEN) {
            return CloudflareApprovalDecision.Blocked("worker-name-required")
        }
        if (preview.operation == CloudflareOperation.VERIFY_TOKEN) return CloudflareApprovalDecision.Allowed
        if (!approval.previewAccepted) return CloudflareApprovalDecision.Blocked("preview-not-accepted")
        if (preview.operation in singleStep && preview.operation == CloudflareOperation.UPDATE_WORKER && preview.artifactSha256.isNullOrBlank()) {
            return CloudflareApprovalDecision.Blocked("artifact-hash-required")
        }
        if (preview.operation in twoStep && !approval.destructiveWarningAccepted) {
            return CloudflareApprovalDecision.Blocked("second-confirmation-required")
        }
        if (preview.operation in destructive) {
            if (!approval.destructiveWarningAccepted) {
                return CloudflareApprovalDecision.Blocked("destructive-confirmation-required")
            }
            if (!approval.backupExported) {
                return CloudflareApprovalDecision.Blocked("backup-required")
            }
            if (approval.typedWorkerName != preview.workerName) {
                return CloudflareApprovalDecision.Blocked("worker-name-mismatch")
            }
        }
        return CloudflareApprovalDecision.Allowed
    }
}
