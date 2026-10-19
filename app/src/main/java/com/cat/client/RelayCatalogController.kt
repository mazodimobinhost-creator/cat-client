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
internal class RelayCatalogController(private val entries: List<RelayCatalogEntry>) {
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

    fun updateFilters(address: AddressFilter = state.addressFilter, exclude1101: Boolean = state.exclude1101Risk,
                      query: String = state.query) {
        state = state.copy(addressFilter = address, exclude1101Risk = exclude1101, query = query)
    }

    fun countries(): Map<String, Int> = RelayCatalog.countryCounts(RelayCatalog.filter(entries,
        RelayCatalogFilter(address = state.addressFilter, exclude1101Risk = state.exclude1101Risk, query = state.query)))

    fun datacenters(): Map<String, Int> = state.countryCode?.let { country ->
        RelayCatalog.datacenterCounts(RelayCatalog.filter(entries, RelayCatalogFilter(countryCode = country,
            address = state.addressFilter, exclude1101Risk = state.exclude1101Risk, query = state.query)), country)
    } ?: emptyMap()

    fun servers(): List<RelayCatalogEntry> = RelayCatalog.filter(entries, RelayCatalogFilter(
        countryCode = state.countryCode, datacenter = state.datacenter, address = state.addressFilter,
        exclude1101Risk = state.exclude1101Risk, query = state.query))

    fun topFiveProbePlan(): List<PortProbeCandidate> = RelayCatalog.topForProbe(servers())
}
