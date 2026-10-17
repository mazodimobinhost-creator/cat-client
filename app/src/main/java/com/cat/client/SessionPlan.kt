package com.cat.client

internal enum class EffectiveDeviceAccess {
    TunnelAccess,
    ProxyOnlyAccess,
}

internal data class DnsRuntimeSettings(
    val mode: DnsPrivacyMode,
    val dohUrl: String,
    val dotEndpoint: String,
    /** QUIC-based resolver endpoints (DoQ / DoH3). Defaults keep the field non-null for
     *  callers that only care about the TCP modes. */
    val doqEndpoint: String = DnsPrivacyPolicy.DEFAULT_DOQ_ENDPOINT,
    val doh3Url: String = DnsPrivacyPolicy.DEFAULT_DOH3_URL,
)

internal data class SessionPlanPreferences(
    /**
     * The Orbot hop for this attempt, or null while the engine is off / Orbot is not reachable
     * on the loopback port. Decided and probed once per attempt in the service, so the runtime
     * YAML, the switch in the UI and the log line all describe the same choice.
     */
    val orbot: OrbotProxySettings? = null,
    val frontingIps: List<String>,
    val connectionOptions: MihomoConnectionOptions,
    val selectedSubscriptionId: String,
    val explicitProfile: ConnectionProfile?,
    val selectedAutomaticTypes: Set<String>,
    val lanSharing: LanSharingSettings,
    val routingMode: RoutingMode,
    val dns: DnsRuntimeSettings,
    val connectionMode: ConnectionMode,
    val dpiBypassEnabled: Boolean,
    val alwaysOn: Boolean,
    val lockdown: Boolean,
    val tlsIntegrityEnabled: Boolean,
    val sniOverride: String = "",
)

internal data class SessionPlanRequest(
    val snapshot: MihomoSubscriptionSnapshot,
    val splitTunnelPlan: SplitTunnelRuntimePlan,
    val availableProfiles: List<ConnectionProfile> = snapshot.catalog.profiles,
    val selectedCountryCode: String?,
    val topEndpoint: CleanIpResult?,
    val validateConnectivity: Boolean,
    val dpiBypassEnabled: Boolean,
    val dpiBypassPort: Int?,
    val forcedProxyName: String? = null,
    val excludedProfileFingerprint: String = "",
    val allowAutomaticBridge: Boolean = true,
    val quickSpeedRequested: Boolean = false,
)

internal data class SessionPlan(
    val snapshot: MihomoSubscriptionSnapshot,
    val splitTunnelPlan: SplitTunnelRuntimePlan,
    val availableProfiles: List<ConnectionProfile>,
    val selectedCountryCode: String?,
    val topEndpoint: CleanIpResult?,
    val validateConnectivity: Boolean,
    val dpiBypassEnabled: Boolean,
    val dpiBypassPort: Int?,
    val forcedProxyName: String?,
    val excludedProfileFingerprint: String,
    val quickSpeedRequested: Boolean,
    val connectionOptions: MihomoConnectionOptions,
    val selectedSubscriptionId: String,
    val explicitProfile: ConnectionProfile?,
    val selectedAutomaticTypes: Set<String>,
    val lanSharing: LanSharingSettings,
    val routingMode: RoutingMode,
    val dns: DnsRuntimeSettings,
    /** Orbot outbound for this attempt (null = engine off or Orbot absent). */
    val orbot: OrbotProxySettings? = null,
    val effectiveDeviceAccess: EffectiveDeviceAccess,
    val tlsIntegrityEnabled: Boolean,
    val serverOverrideIp: String?,
    val serverOverridePort: Int?,
    val automaticSelections: List<MihomoGroupSelection>,
    val automaticSelectionEligible: Boolean,
    val quickSpeedEligible: Boolean,
    val bridgeEligible: Boolean,
    val bridgeResult: MihomoAutomaticRoutingBridgeResult,
    val runtimeYaml: String,
    val selectedMap: Map<String, String>,
)

internal object SessionPlanner {
    fun resolve(request: SessionPlanRequest, preferences: SessionPlanPreferences): SessionPlan {
        val serverOverrideIp = request.topEndpoint?.ip
        val serverOverridePort = request.topEndpoint?.let { endpoint ->
            FrontingIpPolicy.explicitPortFor(preferences.frontingIps, endpoint.ip, endpoint.port)
        }
        val frontedYaml = MihomoFrontingPatcher.patchProxyServers(
            rawYaml = request.snapshot.rawConfig,
            serverOverrideIp = serverOverrideIp,
            serverOverridePort = serverOverridePort,
            sniOverride = preferences.sniOverride.takeIf { it.isNotBlank() },
        )
        val optionsYaml = MihomoConnectionOptionsPatcher.patch(frontedYaml, preferences.connectionOptions)
        val baseRuntimeYaml = MihomoDpiBypassPatcher.patch(
            rawYaml = optionsYaml,
            enabled = request.dpiBypassEnabled,
            proxyPort = request.dpiBypassPort ?: DpiBypassDefaults.FALLBACK_PROXY_PORT,
        )
        val automaticSelections = MihomoSelectionPolicy.desiredSelections(
            request.snapshot.summary,
            request.selectedCountryCode,
        )
        val automaticSelectionEligible = request.topEndpoint == null &&
            request.forcedProxyName == null &&
            preferences.explicitProfile == null &&
            preferences.selectedAutomaticTypes.isEmpty()
        val quickSpeedEligible = request.quickSpeedRequested &&
            automaticSelectionEligible &&
            request.selectedCountryCode == null
        val bridgeEligible = request.allowAutomaticBridge &&
            automaticSelectionEligible &&
            request.selectedCountryCode == null
        val bridgeResult = if (bridgeEligible) {
            MihomoAutomaticRoutingBridge.patch(baseRuntimeYaml)
        } else {
            MihomoAutomaticRoutingBridgeResult(
                yaml = baseRuntimeYaml,
                applied = false,
                rootName = null,
                targetName = null,
                reason = if (request.allowAutomaticBridge) "mode-ineligible" else "retry-original-runtime",
            )
        }
        val selectedMap = automaticSelections
            .associate { it.selectorGroup to it.selectedGroup }
            .toMutableMap()
            .apply {
                if (bridgeResult.applied) this[bridgeResult.rootName!!] = bridgeResult.targetName!!
            }

        return SessionPlan(
            snapshot = request.snapshot,
            splitTunnelPlan = request.splitTunnelPlan,
            availableProfiles = request.availableProfiles,
            selectedCountryCode = request.selectedCountryCode,
            topEndpoint = request.topEndpoint,
            validateConnectivity = request.validateConnectivity,
            dpiBypassEnabled = request.dpiBypassEnabled,
            dpiBypassPort = request.dpiBypassPort,
            forcedProxyName = request.forcedProxyName,
            excludedProfileFingerprint = request.excludedProfileFingerprint,
            quickSpeedRequested = request.quickSpeedRequested,
            connectionOptions = preferences.connectionOptions,
            selectedSubscriptionId = preferences.selectedSubscriptionId,
            explicitProfile = preferences.explicitProfile,
            selectedAutomaticTypes = preferences.selectedAutomaticTypes,
            lanSharing = preferences.lanSharing,
            routingMode = preferences.routingMode,
            dns = preferences.dns,
            orbot = preferences.orbot,
            effectiveDeviceAccess = if (
                ConnectionModePolicy.shouldStartTun(
                    preferences.connectionMode,
                    preferences.alwaysOn,
                    preferences.lockdown,
                )
            ) {
                EffectiveDeviceAccess.TunnelAccess
            } else {
                EffectiveDeviceAccess.ProxyOnlyAccess
            },
            tlsIntegrityEnabled = preferences.tlsIntegrityEnabled,
            serverOverrideIp = serverOverrideIp,
            serverOverridePort = serverOverridePort,
            automaticSelections = automaticSelections,
            automaticSelectionEligible = automaticSelectionEligible,
            quickSpeedEligible = quickSpeedEligible,
            bridgeEligible = bridgeEligible,
            bridgeResult = bridgeResult,
            runtimeYaml = bridgeResult.yaml,
            selectedMap = selectedMap,
        )
    }
}
