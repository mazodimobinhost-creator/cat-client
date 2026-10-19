package com.cat.client

internal data class SubscriptionOptimizationItem(
    val originalUri: String,
    val optimizedUri: String,
    val changed: Boolean,
    val changes: List<ConfigFieldChange>,
)

internal data class SubscriptionOptimizationPlan(
    val items: List<SubscriptionOptimizationItem>,
) {
    val changedCount: Int get() = items.count { it.changed }
    val optimizedLines: List<String> get() = items.map { it.optimizedUri }
    val backupLines: List<String> get() = items.map { it.originalUri }
}

/** Independent subscription optimizer with non-destructive diff preview and
 * lossless rollback. Never invents ports, never downgrades TLS, and never
 * overwrites the Worker Host header when applying a custom/scanner SNI. */
internal object SubscriptionOptimizer {
    fun plan(
        lines: List<String>,
        candidates: List<VerifiedEndpoint>,
        nowMs: Long,
        maxAgeMs: Long,
        sniOverride: String? = null,
        fingerprintOverride: String? = null,
    ): SubscriptionOptimizationPlan {
        val cleanPool = candidates
            .filter { it.role == EndpointRole.CLEAN_IP && it.eligibleForSubscription(nowMs, maxAgeMs) }
            .distinctBy { it.authority }
            .sortedWith(compareBy<VerifiedEndpoint> { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.authority })
        val proxyPool = candidates
            .filter { it.role == EndpointRole.PROXY_IP && it.eligibleForSubscription(nowMs, maxAgeMs) }
            .distinctBy { it.authority }
            .sortedWith(compareBy<VerifiedEndpoint> { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.authority })

        val cleanSni = sniOverride?.trim()?.takeIf {
            it.isNotEmpty() && !it.contains('/') && it.none(Char::isWhitespace)
        }
        val cleanFp = fingerprintOverride?.trim()?.takeIf { it.isNotEmpty() }

        var cleanIndex = 0
        var proxyIndex = 0
        val items = lines.map { raw ->
            val trimmed = raw.trim()
            val remark = trimmed.substringAfter('#', missingDelimiterValue = "")
            val parsed = VerifiedConfigUriParser.parse(trimmed, checkedAtMs = 0L, source = "optimizer")
            if (parsed == null) {
                SubscriptionOptimizationItem(raw, raw, changed = false, changes = emptyList())
            } else {
                val matchingClean = cleanPool.filter {
                    (it.port !in EndpointPortDiscovery.cleanPlainPorts) == parsed.tls
                }
                val nextEndpoint = if (matchingClean.isNotEmpty()) {
                    matchingClean[cleanIndex++ % matchingClean.size]
                } else {
                    parsed.endpoint
                }
                val nextProxy = when {
                    parsed.proxyIp != null && proxyPool.isNotEmpty() ->
                        proxyPool[proxyIndex++ % proxyPool.size]
                    else -> parsed.proxyIp
                }
                val updated = parsed.copy(
                    endpoint = nextEndpoint,
                    proxyIp = nextProxy,
                    serverName = cleanSni ?: parsed.serverName,
                    fingerprint = cleanFp ?: parsed.fingerprint,
                )
                val diff = VerifiedConfigDiff.between(parsed, updated)
                if (diff.isEmpty()) {
                    SubscriptionOptimizationItem(raw, raw, changed = false, changes = emptyList())
                } else {
                    val encoded = updated.encode()
                    val rebuilt = if (remark.isEmpty()) encoded else "$encoded#$remark"
                    SubscriptionOptimizationItem(raw, rebuilt, changed = true, changes = diff)
                }
            }
        }
        return SubscriptionOptimizationPlan(items)
    }
}
