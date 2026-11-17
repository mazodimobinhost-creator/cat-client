package com.cat.client

internal enum class RelayCatalogStep { COUNTRIES, DATACENTERS, SERVERS }

internal data class RelayCatalogState(
    val step: RelayCatalogStep = RelayCatalogStep.COUNTRIES,
    val countryCode: String? = null,
    val datacenter: String? = null,
    val addressFilter: AddressFilter = AddressFilter.ALL,
    val exclude1101Risk: Boolean = false,
    val query: String = "",
)

/** State machine for the native three-step catalogue. Changing a country clears
 * a stale datacenter; filters never silently reset the selected country. */
internal class RelayCatalogController(initialEntries: List<RelayCatalogEntry>) {
    private var entries: List<RelayCatalogEntry> = initialEntries.toList()
    private var evidenceCache: List<CachedEndpointEvidence> = emptyList()
    var state: RelayCatalogState = RelayCatalogState()
        private set

    fun selectCountry(code: String) {
        require(code.isNotBlank())
        state = state.copy(step = RelayCatalogStep.DATACENTERS, countryCode = code.uppercase(), datacenter = null)
    }

    fun selectDatacenter(name: String) {
        require(state.countryCode != null && name.isNotBlank())
        state = state.copy(step = RelayCatalogStep.SERVERS, datacenter = name)
    }

    fun back() {
        state = when (state.step) {
            RelayCatalogStep.COUNTRIES -> state
            RelayCatalogStep.DATACENTERS -> state.copy(step = RelayCatalogStep.COUNTRIES, countryCode = null, datacenter = null)
            RelayCatalogStep.SERVERS -> state.copy(step = RelayCatalogStep.DATACENTERS, datacenter = null)
        }
    }

    fun updateFilters(
        address: AddressFilter = state.addressFilter,
        exclude1101: Boolean = state.exclude1101Risk,
        query: String = state.query,
    ) {
        state = state.copy(addressFilter = address, exclude1101Risk = exclude1101, query = query)
    }

    fun countries(): Map<String, Int> = RelayCatalog.countryCounts(
        RelayCatalog.filter(
            entries,
            RelayCatalogFilter(address = state.addressFilter, exclude1101Risk = state.exclude1101Risk, query = state.query),
        ),
    )

    fun datacenters(): Map<String, Int> = state.countryCode?.let { country ->
        RelayCatalog.datacenterCounts(
            RelayCatalog.filter(
                entries,
                RelayCatalogFilter(
                    countryCode = country,
                    address = state.addressFilter,
                    exclude1101Risk = state.exclude1101Risk,
                    query = state.query,
                ),
            ),
            country,
        )
    } ?: emptyMap()

    fun servers(): List<RelayCatalogEntry> = RelayCatalog.filter(
        entries,
        RelayCatalogFilter(
            countryCode = state.countryCode,
            datacenter = state.datacenter,
            address = state.addressFilter,
            exclude1101Risk = state.exclude1101Risk,
            query = state.query,
        ),
    )

    fun topFiveProbePlan(): List<PortProbeCandidate> = RelayCatalog.topForProbe(servers())

    /** Updates the exact `role + host + port` row after a bounded runtime probe
     * without altering any sibling port or role on the same IP, and optionally
     * caches the runtime evidence under the active `networkId`. */
    fun recordRuntimeResult(
        candidate: PortProbeCandidate,
        evidence: EndpointProbeEvidence,
        checkedAtMs: Long,
        workerOutcome: WorkerRuntimeOutcome? = null,
        networkId: String? = null,
        protocol: String = "vless",
        serverName: String = "worker",
    ): Boolean {
        val cleanHost = candidate.host.removePrefix("[").removeSuffix("]")
        val index = entries.indexOfFirst { row ->
            row.endpoint.host.equals(cleanHost, ignoreCase = true) &&
                row.endpoint.port == candidate.port &&
                RelayRuntimePromotion.promote(
                    row, candidate.kind,
                    EndpointProbeEvidence(
                        tcpConnected = true,
                        tlsCompleted = true,
                        protocolHandshakeCompleted = true,
                        websocketCompleted = true,
                        runtimeCompleted = true,
                    ),
                    checkedAtMs,
                ) != null
        }
        if (index < 0) return false
        val current = entries[index]
        val promoted = RelayRuntimePromotion.promote(current, candidate.kind, evidence, checkedAtMs)
        val updatedVerdictRow = promoted ?: current.copy(
            endpoint = current.endpoint.copy(
                verdict = EndpointProbeEvaluator.verdict(candidate.kind, evidence),
                checkedAtMs = checkedAtMs,
                latencyMs = evidence.latencyMs ?: current.endpoint.latencyMs,
            ),
        )
        val finalRow = if (workerOutcome != null) {
            RelayRiskEvidence.apply(updatedVerdictRow, workerOutcome)
        } else {
            updatedVerdictRow
        }
        val next = entries.toMutableList()
        next[index] = finalRow
        entries = next

        if (!networkId.isNullOrBlank()) {
            val key = EndpointEvidenceKey(
                networkId = networkId,
                role = finalRow.endpoint.role,
                host = finalRow.endpoint.host,
                port = finalRow.endpoint.port,
                protocol = protocol,
                serverName = serverName,
                tls = finalRow.endpoint.port !in EndpointPortDiscovery.cleanPlainPorts,
            )
            evidenceCache = EndpointEvidenceCache.put(
                evidenceCache,
                CachedEndpointEvidence(key = key, evidence = evidence, checkedAtMs = checkedAtMs),
            )
        }
        return true
    }

    /** Recommends ports strictly from fresh runtime evidence measured on
     * `networkId` for the given `role` (defaulting to `CLEAN_IP`). */
    fun recommendPorts(
        networkId: String,
        nowMs: Long,
        maxAgeMs: Long = 3_600_000L,
        role: EndpointRole = EndpointRole.CLEAN_IP,
    ): List<VerifiedPortRecommendation> = OperatorPortRecommendation.fromEvidence(
        entries = evidenceCache,
        currentNetworkId = networkId,
        nowMs = nowMs,
        maxAgeMs = maxAgeMs,
        role = role,
    )

    /** Builds a typed panel batch payload (`cleanIps`, `proxyIps`, `relayUris`,
     * `tunnelChains`) from the currently visible server rows. */
    fun buildVisiblePanelBatch(): PanelEndpointBatchResult =
        PanelEndpointBatch.build(servers().map { it.endpoint to null })

    /** Verifies read-back for the currently visible server rows after a panel push. */
    fun verifyVisiblePanelSync(
        before: List<VerifiedEndpoint>,
        after: List<VerifiedEndpoint>?,
        kvAvailable: Boolean,
    ): PanelEndpointBatchSyncResult = PanelEndpointBatchSync.verify(
        requested = servers().map { it.endpoint },
        before = before,
        after = after,
        kvAvailable = kvAvailable,
    )
}
