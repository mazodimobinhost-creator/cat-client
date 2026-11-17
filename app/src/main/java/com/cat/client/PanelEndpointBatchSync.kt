package com.cat.client

internal data class PanelEndpointBatchSyncResult(
    val results: List<PanelEndpointSyncResult>,
) {
    val saved: Int get() = results.count { it.status == PanelEndpointSyncStatus.SAVED }
    val duplicates: Int get() = results.count { it.status == PanelEndpointSyncStatus.DUPLICATE }
    val rejected: Int get() = results.count { it.status == PanelEndpointSyncStatus.REJECTED }
    val failed: Int get() = results.count {
        it.status == PanelEndpointSyncStatus.NO_KV || it.status == PanelEndpointSyncStatus.READ_BACK_MISMATCH
    }
}

internal object PanelEndpointBatchSync {
    fun verify(
        requested: List<VerifiedEndpoint>,
        before: List<VerifiedEndpoint>,
        after: List<VerifiedEndpoint>?,
        kvAvailable: Boolean,
    ): PanelEndpointBatchSyncResult = PanelEndpointBatchSyncResult(
        requested.distinctBy { Triple(it.role, it.host.lowercase(), it.port) }
            .map { PanelEndpointSync.verify(it, before, after, kvAvailable) },
    )
}
