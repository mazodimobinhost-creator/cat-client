package com.cat.client

internal enum class TlsOptimizationConflictReason {
    ECH_CONFLICTS_WITH_FRAGMENT,
    ECH_CONFLICTS_WITH_CUSTOM_SNI_MASK,
    ECH_CONFLICTS_WITH_CUSTOM_CIPHER_SUITES,
}

internal sealed interface TlsOptimizationValidation {
    data object Allowed : TlsOptimizationValidation
    data class Conflict(val reasons: Set<TlsOptimizationConflictReason>) : TlsOptimizationValidation
}

/** Enforces the TLS 1.3 rule that Encrypted ClientHello (ECH) and Advanced
 * Optimization (ClientHello Fragment, custom SNI Mask, custom Cipher Suites)
 * must never be enabled simultaneously. */
internal object TlsOptimizationConflictPolicy {
    fun validate(
        echEnabled: Boolean,
        fragmentEnabled: Boolean = false,
        sniMaskOverride: String? = null,
        workerDomain: String? = null,
        cipherSuitesOverride: String? = null,
    ): TlsOptimizationValidation {
        if (!echEnabled) return TlsOptimizationValidation.Allowed
        val reasons = LinkedHashSet<TlsOptimizationConflictReason>()
        if (fragmentEnabled) {
            reasons += TlsOptimizationConflictReason.ECH_CONFLICTS_WITH_FRAGMENT
        }
        val cleanMask = sniMaskOverride?.trim().orEmpty()
        val cleanWorker = workerDomain?.trim().orEmpty()
        if (cleanMask.isNotEmpty() && (cleanWorker.isEmpty() || !cleanMask.equals(cleanWorker, ignoreCase = true))) {
            reasons += TlsOptimizationConflictReason.ECH_CONFLICTS_WITH_CUSTOM_SNI_MASK
        }
        if (!cipherSuitesOverride.isNullOrBlank()) {
            reasons += TlsOptimizationConflictReason.ECH_CONFLICTS_WITH_CUSTOM_CIPHER_SUITES
        }
        return if (reasons.isEmpty()) TlsOptimizationValidation.Allowed else TlsOptimizationValidation.Conflict(reasons)
    }
}

internal enum class FirewallBypassProfile(
    val wireId: String,
    val defaultAddress: String,
    val requiresIpv6: Boolean,
) {
    NONE("", "", false),
    MCI_CDN_IPV6("mci-ipv6", FirewallBypassRecipes.MCI_DEFAULT_IPV6, true),
    IRANCELL_CDN_FF("irancell-ff", FirewallBypassRecipes.IRANCELL_DEFAULT_IPV4, false),
}

internal object FirewallBypassRecipes {
    const val MCI_DEFAULT_IPV6 = "2a06:98c1:3101::6812:202f"
    const val IRANCELL_DEFAULT_IPV4 = "188.114.98.0"
    const val FIREWALL_FF_FINAL_MASK =
        """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["5","94","1"],"delays":["0"],"maxSplit":"0"}},{"type":"fragment","settings":{"packets":"1-1","lengths":["109","1"],"delays":["1"],"maxSplit":"355"}}]}"""
    const val FIREWALL_FF_CIPHER_SUITES =
        "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

    fun alpnForTransport(transportType: String): String =
        if (transportType.equals("xhttp", ignoreCase = true) || transportType.equals("splithttp", ignoreCase = true)) {
            "h2,http/1.1"
        } else {
            "http/1.1"
        }
}

internal data class SubscriptionOptimizationItem(
    val originalUri: String,
    val optimizedUri: String,
    val changed: Boolean,
    val changes: List<ConfigFieldChange>,
)

internal data class SubscriptionOptimizationPlan(
    val items: List<SubscriptionOptimizationItem>,
    val conflict: TlsOptimizationValidation.Conflict? = null,
    val pagesDevDomains: Set<String> = emptySet(),
) {
    val changedCount: Int get() = items.count { it.changed }
    val optimizedLines: List<String> get() = items.map { it.optimizedUri }
    val backupLines: List<String> get() = items.map { it.originalUri }
    fun backupPayload(): String = backupLines.joinToString("\n")
}

/** Independent subscription optimizer with non-destructive diff preview and
 * lossless rollback. Never invents ports, never downgrades TLS, never
 * overwrites the Worker Host header when applying a custom/scanner SNI, and
 * blocks simultaneous ECH + Advanced Fragment/SNI-Mask activation. */
internal object SubscriptionOptimizer {
    fun plan(
        lines: List<String>,
        candidates: List<VerifiedEndpoint>,
        nowMs: Long,
        maxAgeMs: Long,
        sniOverride: String? = null,
        fingerprintOverride: String? = null,
        echEnabled: Boolean = false,
        fragmentEnabled: Boolean = false,
        cipherSuitesOverride: String? = null,
        firewallProfile: FirewallBypassProfile = FirewallBypassProfile.NONE,
        customEntryIp: String? = null,
    ): SubscriptionOptimizationPlan {
        val effectiveEch = if (firewallProfile != FirewallBypassProfile.NONE) false else echEnabled
        val validation = TlsOptimizationConflictPolicy.validate(
            echEnabled = effectiveEch,
            fragmentEnabled = fragmentEnabled,
            sniMaskOverride = sniOverride,
            cipherSuitesOverride = cipherSuitesOverride,
        )
        if (validation is TlsOptimizationValidation.Conflict) {
            val unchanged = lines.map { raw ->
                SubscriptionOptimizationItem(raw, raw, changed = false, changes = emptyList())
            }
            return SubscriptionOptimizationPlan(unchanged, conflict = validation)
        }

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
        val cleanCustomIp = customEntryIp?.trim()?.removePrefix("[")?.removeSuffix("]")?.takeIf {
            it.isNotEmpty() && !it.contains('/') && it.none(Char::isWhitespace)
        }

        val pagesDevFound = LinkedHashSet<String>()
        var cleanIndex = 0
        var proxyIndex = 0
        val items = lines.map { raw ->
            val trimmed = raw.trim()
            val remark = trimmed.substringAfter('#', missingDelimiterValue = "")
            val parsed = VerifiedConfigUriParser.parse(trimmed, checkedAtMs = 0L, source = "optimizer")
            if (parsed == null) {
                SubscriptionOptimizationItem(raw, raw, changed = false, changes = emptyList())
            } else {
                if (parsed.workerDomain.endsWith(".pages.dev", ignoreCase = true) ||
                    parsed.serverName.endsWith(".pages.dev", ignoreCase = true)
                ) {
                    pagesDevFound += parsed.workerDomain
                }
                val matchingClean = cleanPool.filter {
                    val tlsMatches = (it.port !in EndpointPortDiscovery.cleanPlainPorts) == parsed.tls
                    val familyMatches = when (firewallProfile) {
                        FirewallBypassProfile.MCI_CDN_IPV6 -> it.family == EndpointFamily.IPV6
                        FirewallBypassProfile.IRANCELL_CDN_FF -> it.family == EndpointFamily.IPV4
                        FirewallBypassProfile.NONE -> true
                    }
                    tlsMatches && familyMatches
                }
                val nextEndpoint = when {
                    cleanCustomIp != null && parsed.tls -> {
                        val fam = if (cleanCustomIp.contains(':')) EndpointFamily.IPV6 else EndpointFamily.IPV4
                        VerifiedEndpoint(
                            host = cleanCustomIp,
                            port = parsed.endpoint.port,
                            role = EndpointRole.CLEAN_IP,
                            family = fam,
                            verdict = EndpointVerdict.VERIFIED,
                            checkedAtMs = nowMs,
                            source = "firewall-profile",
                        )
                    }
                    matchingClean.isNotEmpty() -> matchingClean[cleanIndex++ % matchingClean.size]
                    firewallProfile == FirewallBypassProfile.MCI_CDN_IPV6 && parsed.tls -> {
                        VerifiedEndpoint(
                            host = FirewallBypassRecipes.MCI_DEFAULT_IPV6,
                            port = parsed.endpoint.port,
                            role = EndpointRole.CLEAN_IP,
                            family = EndpointFamily.IPV6,
                            verdict = EndpointVerdict.VERIFIED,
                            checkedAtMs = nowMs,
                            source = "mci-ipv6",
                        )
                    }
                    firewallProfile == FirewallBypassProfile.IRANCELL_CDN_FF && parsed.tls -> {
                        VerifiedEndpoint(
                            host = FirewallBypassRecipes.IRANCELL_DEFAULT_IPV4,
                            port = parsed.endpoint.port,
                            role = EndpointRole.CLEAN_IP,
                            family = EndpointFamily.IPV4,
                            verdict = EndpointVerdict.VERIFIED,
                            checkedAtMs = nowMs,
                            source = "irancell-ff",
                        )
                    }
                    else -> parsed.endpoint
                }
                val nextProxy = when {
                    parsed.proxyIp != null && proxyPool.isNotEmpty() ->
                        proxyPool[proxyIndex++ % proxyPool.size]
                    else -> parsed.proxyIp
                }
                val updated = when (firewallProfile) {
                    FirewallBypassProfile.MCI_CDN_IPV6 -> {
                        // MCI Firewall CDN/Worker with IPv6:
                        // Clear echConfigList, cipherSuites and finalMask; set fingerprint = chrome; use IPv6 address.
                        parsed.copy(
                            endpoint = nextEndpoint,
                            proxyIp = nextProxy,
                            serverName = cleanSni ?: parsed.serverName,
                            fingerprint = cleanFp ?: "chrome",
                            ech = null,
                            cipherSuites = null,
                            finalMask = null,
                        )
                    }
                    FirewallBypassProfile.IRANCELL_CDN_FF -> {
                        // Irancell Firewall CDN/Worker with F&F method:
                        // Clear echConfigList; set finalMask = tlshello-0-len (F&F); set cipherSuites = semi-python (F&F);
                        // set fingerprint = unsafe; set ALPN = http/1.1 for ws and h2,http/1.1 for xhttp.
                        parsed.copy(
                            endpoint = nextEndpoint,
                            proxyIp = nextProxy,
                            serverName = cleanSni ?: parsed.serverName,
                            fingerprint = cleanFp ?: "unsafe",
                            ech = null,
                            alpn = FirewallBypassRecipes.alpnForTransport(parsed.transportType),
                            cipherSuites = cipherSuitesOverride?.takeIf { it.isNotBlank() }
                                ?: FirewallBypassRecipes.FIREWALL_FF_CIPHER_SUITES,
                            finalMask = FirewallBypassRecipes.FIREWALL_FF_FINAL_MASK,
                        )
                    }
                    FirewallBypassProfile.NONE -> {
                        parsed.copy(
                            endpoint = nextEndpoint,
                            proxyIp = nextProxy,
                            serverName = cleanSni ?: parsed.serverName,
                            fingerprint = cleanFp ?: parsed.fingerprint,
                        )
                    }
                }
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
        return SubscriptionOptimizationPlan(items, pagesDevDomains = pagesDevFound)
    }
}
