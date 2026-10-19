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
     * without altering any sibling port or role on the same IP. */
    fun recordRuntimeResult(
        candidate: PortProbeCandidate,
        evidence: EndpointProbeEvidence,
        checkedAtMs: Long,
        workerOutcome: WorkerRuntimeOutcome? = null,
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
        return true
    }
}
