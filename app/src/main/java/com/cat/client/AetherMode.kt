package com.cat.client

/** User-facing Aether stacks. A mode is never connectable until its native capabilities exist. */
enum class AetherMode(
    val wireName: String,
    val requiresWarpMasqueIdentity: Boolean,
    val requiresWireGuardOverMasqueIdentity: Boolean,
    val requiresPsiphon: Boolean,
) {
    MasqueHttp2("masque-h2", true, false, false),
    WireGuardOverMasque("wireguard-over-masque", false, true, false),
    MasquePsiphon("masque-h2-psiphon", true, false, true),
}

data class AetherCapabilities(
    val nativeCoreLoaded: Boolean,
    val masqueHttp2: Boolean,
    val wireGuardOverMasque: Boolean,
    val psiphonCarrier: Boolean,
)

enum class AetherUnavailableReason {
    NativeCoreMissing,
    MasqueHttp2Missing,
    WireGuardOverMasqueMissing,
    PsiphonMissing,
}

object AetherModePolicy {
    fun unavailableReason(mode: AetherMode, capabilities: AetherCapabilities): AetherUnavailableReason? {
        if (!capabilities.nativeCoreLoaded) return AetherUnavailableReason.NativeCoreMissing
        if (mode == AetherMode.WireGuardOverMasque && !capabilities.wireGuardOverMasque) {
            return AetherUnavailableReason.WireGuardOverMasqueMissing
        }
        if (mode != AetherMode.WireGuardOverMasque && !capabilities.masqueHttp2) {
            return AetherUnavailableReason.MasqueHttp2Missing
        }
        if (mode.requiresPsiphon && !capabilities.psiphonCarrier) return AetherUnavailableReason.PsiphonMissing
        return null
    }
}
