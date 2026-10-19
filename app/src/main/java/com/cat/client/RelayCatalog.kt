package com.cat.client

internal enum class RelayRisk { CLEAN, RISK_1101, UNKNOWN }
internal enum class AddressFilter { ALL, IPV4, IPV6 }

internal data class RelayCatalogEntry(
    val endpoint: VerifiedEndpoint,
    val countryCode: String,
    val countryName: String,
    val datacenter: String,
    val asn: String?,
    val risk: RelayRisk,
)

internal data class RelayCatalogFilter(
    val countryCode: String? = null,
    val datacenter: String? = null,
    val address: AddressFilter = AddressFilter.ALL,
    val exclude1101Risk: Boolean = false,
    val query: String = "",
)

/** Native country → datacenter → server catalogue policy. UNKNOWN is never
 * rendered as CLEAN; the 1101 label requires explicit evidence. */
internal object RelayCatalog {
    fun filter(entries: List<RelayCatalogEntry>, filter: RelayCatalogFilter): List<RelayCatalogEntry> {
        val query = filter.query.trim().lowercase()
        return entries.asSequence()
            .filter { filter.countryCode == null || it.countryCode.equals(filter.countryCode, true) }
            .filter { filter.datacenter == null || it.datacenter.equals(filter.datacenter, true) }
            .filter {
                when (filter.address) {
                    AddressFilter.ALL -> true
                    AddressFilter.IPV4 -> it.endpoint.family == EndpointFamily.IPV4
                    AddressFilter.IPV6 -> it.endpoint.family == EndpointFamily.IPV6
                }
            }
            .filter { !filter.exclude1101Risk || it.risk != RelayRisk.RISK_1101 }
            .filter {
                query.isEmpty() || listOf(it.endpoint.host, it.endpoint.authority, it.countryName,
                    it.countryCode, it.datacenter, it.asn.orEmpty()).any { field -> field.lowercase().contains(query) }
            }
            .sortedWith(compareBy<RelayCatalogEntry> { it.endpoint.latencyMs ?: Long.MAX_VALUE }
                .thenBy { it.endpoint.authority })
            .toList()
    }

    fun countryCounts(entries: List<RelayCatalogEntry>): Map<String, Int> =
        entries.groupingBy { it.countryCode.uppercase() }.eachCount()

    fun datacenterCounts(entries: List<RelayCatalogEntry>, countryCode: String): Map<String, Int> =
        entries.filter { it.countryCode.equals(countryCode, true) }.groupingBy { it.datacenter }.eachCount()
}
