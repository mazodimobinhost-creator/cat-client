package com.cat.client

/** Combines scanner sources without collapsing distinct roles or ports. When the
 * same typed endpoint appears twice, the newest evidence wins. */
internal object RelayCatalogMerge {
    fun merge(vararg sources: List<RelayCatalogEntry>): List<RelayCatalogEntry> =
        sources.asSequence().flatten()
            .groupBy { Triple(it.endpoint.role, it.endpoint.host.lowercase(), it.endpoint.port) }
            .map { (_, rows) -> rows.maxBy { it.endpoint.checkedAtMs } }
            .sortedWith(compareBy<RelayCatalogEntry> { it.countryCode }
                .thenBy { it.datacenter }.thenBy { it.endpoint.authority })
}
