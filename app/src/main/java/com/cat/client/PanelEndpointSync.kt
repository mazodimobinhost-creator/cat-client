package com.cat.client

internal enum class PanelEndpointSyncStatus { SAVED, DUPLICATE, REJECTED, NO_KV, READ_BACK_MISMATCH }
internal data class PanelEndpointSyncResult(
    val status: PanelEndpointSyncStatus,
    val endpoint: VerifiedEndpoint,
)

/** Interprets an app/bot/panel write only after reading settings back. A 2xx
 * response alone is never reported as SAVED. */
internal object PanelEndpointSync {
    fun verify(
        requested: VerifiedEndpoint,
        before: List<VerifiedEndpoint>,
        after: List<VerifiedEndpoint>?,
        kvAvailable: Boolean,
    ): PanelEndpointSyncResult {
        if (!kvAvailable) return PanelEndpointSyncResult(PanelEndpointSyncStatus.NO_KV, requested)
        if (requested.verdict != EndpointVerdict.VERIFIED) {
            return PanelEndpointSyncResult(PanelEndpointSyncStatus.REJECTED, requested)
        }
        val same = { value: VerifiedEndpoint ->
            value.role == requested.role && value.host.equals(requested.host, true) && value.port == requested.port
        }
        if (before.any(same)) return PanelEndpointSyncResult(PanelEndpointSyncStatus.DUPLICATE, requested)
        val stored = after ?: return PanelEndpointSyncResult(PanelEndpointSyncStatus.READ_BACK_MISMATCH, requested)
        return PanelEndpointSyncResult(
            if (stored.any(same)) PanelEndpointSyncStatus.SAVED else PanelEndpointSyncStatus.READ_BACK_MISMATCH,
            requested,
        )
    }
}
