package com.cat.client

import java.io.IOException

object StartupScanPolicy {
    private val primaryPorts = listOf(443, 8443)
    private val secondaryPorts = listOf(2053, 2083, 2087, 2096)

    fun tcpProbePorts(profiles: List<ConnectionProfile>): List<Int> {
        return profiles
            .filterNot { it.type.equals("wireguard", ignoreCase = true) }
            .map { it.port }
            .filter { it > 0 }
            .distinct()
    }

    fun priorityPorts(subscriptionPorts: List<Int>): List<Int> {
        val validPorts = subscriptionPorts.filter { it > 0 }
        val available = validPorts.toSet()
        primaryPorts.filter { it in available }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        secondaryPorts.filter { it in available }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return mostCommonPort(validPorts)?.let(::listOf).orEmpty()
    }

    fun fallbackPorts(subscriptionPorts: List<Int>): List<Int> {
        return subscriptionPorts
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    fun orderedConnectionPorts(subscriptionPorts: List<Int>): List<Int> {
        return (priorityPorts(subscriptionPorts) + fallbackPorts(subscriptionPorts)).distinct()
    }

    fun frontingCandidates(
        frontingIps: List<String>,
        subscriptionPorts: List<Int>,
        checkedAt: Long,
        excludedEndpoint: CleanIpResult? = null,
    ): List<CleanIpResult> {
        val defaultPort = orderedConnectionPorts(subscriptionPorts).firstOrNull() ?: return emptyList()
        return frontingIps
            .map(FrontingIpPolicy::parseEndpoint)
            .map { endpoint -> endpoint to (endpoint.port ?: defaultPort) }
            .filterNot { (endpoint, port) ->
                excludedEndpoint?.let { it.ip == endpoint.ip && it.port == port } == true
            }
            .distinctBy { (endpoint, port) -> "${endpoint.ip}:$port" }
            .map { (endpoint, port) -> CleanIpResult(endpoint.ip, port, 1L, 0.0, checkedAt) }
    }

    fun cachedCandidates(
        subscriptionPorts: List<Int>,
        lastEndpoint: CleanIpResult?,
        cachedResults: List<CleanIpResult>,
        excludedEndpoint: CleanIpResult? = null,
    ): List<CleanIpResult> {
        val ports = orderedConnectionPorts(subscriptionPorts).toSet()
        if (ports.isEmpty()) return emptyList()
        val last = lastEndpoint?.takeIf { it.port in ports }
        val lastKey = last?.endpointKey()
        val candidates = listOfNotNull(last) + cachedResults
            .filter { it.port in ports && it.endpointKey() != lastKey }
            .distinctBy { it.endpointKey() }
            .sortedForConnection()
        return excludeEndpoint(candidates, excludedEndpoint)
    }

    /** Cached scanner evidence used by the one-tap automatic flow.
     * Unlike the legacy retry list, this path is deliberately fail-closed: stale,
     * failed, or synthetic entries cannot silently become an "automatic" profile. */
    fun automaticCandidates(
        subscriptionPorts: List<Int>,
        lastEndpoint: CleanIpResult?,
        cachedResults: List<CleanIpResult>,
        detectedCarrier: MobileCarrier,
        nowMs: Long,
        excludedEndpoint: CleanIpResult? = null,
    ): List<CleanIpResult> {
        val ordered = cachedCandidates(subscriptionPorts, lastEndpoint, cachedResults, excludedEndpoint)
        val byEndpoint = ordered.associateBy { it.endpointKey() }
        val evidence = ordered.map { result ->
            AutoConnectionCandidate(
                id = result.endpointKey(),
                endpoint = result.endpointKey(),
                source = "device-clean-ip-scan",
                carrier = MobileCarrier.UNKNOWN,
                latencyMs = result.latencyMs,
                verifiedAtMs = result.checkedAt,
                succeeded = result.lossRate in 0.0..0.99,
            )
        }.toMutableList()
        val selected = mutableListOf<CleanIpResult>()
        while (evidence.isNotEmpty()) {
            val decision = AutoConnectionPlanner.decide(evidence, detectedCarrier, nowMs, networkAvailable = true)
            val ready = decision as? AutoConnectionDecision.Ready ?: break
            byEndpoint[ready.candidate.id]?.let(selected::add)
            evidence.removeAll { it.id == ready.candidate.id }
        }
        return selected
    }

    fun excludeEndpoint(
        candidates: List<CleanIpResult>,
        excludedEndpoint: CleanIpResult?,
    ): List<CleanIpResult> {
        val excludedKey = excludedEndpoint?.endpointKey() ?: return candidates
        return candidates.filter { it.endpointKey() != excludedKey }
    }

    private fun mostCommonPort(ports: List<Int>): Int? {
        if (ports.isEmpty()) return null
        val counts = ports.groupingBy { it }.eachCount()
        val firstIndex = mutableMapOf<Int, Int>()
        ports.forEachIndexed { index, port ->
            firstIndex.putIfAbsent(port, index)
        }
        return ports.distinct()
            .sortedWith(
                compareByDescending<Int> { counts.getValue(it) }
                    .thenBy { firstIndex.getValue(it) },
            )
            .firstOrNull()
    }

    private fun CleanIpResult.endpointKey(): String = "$ip:$port"
}

object StartupTopIpConnector {
    suspend fun <T> connectFirst(
        candidates: List<CleanIpResult>,
        probeProfiles: suspend (CleanIpResult) -> List<SelectedConnectionProfile>,
        connectRuntime: suspend (CleanIpResult, List<SelectedConnectionProfile>) -> T,
        onAttempt: (attempt: Int, total: Int, endpoint: CleanIpResult) -> Unit = { _, _, _ -> },
        onRejected: (attempt: Int, total: Int, endpoint: CleanIpResult, reason: String, error: Throwable?) -> Unit = { _, _, _, _, _ -> },
        onConnected: (attempt: Int, total: Int, endpoint: CleanIpResult) -> Unit = { _, _, _ -> },
    ): T {
        var lastFailure: Throwable? = null
        for ((index, endpoint) in candidates.withIndex()) {
            val attempt = index + 1
            onAttempt(attempt, candidates.size, endpoint)
            val selections = try {
                probeProfiles(endpoint)
            } catch (error: Throwable) {
                lastFailure = error
                onRejected(attempt, candidates.size, endpoint, "profileProbeFailed", error)
                continue
            }
            if (selections.isEmpty()) {
                onRejected(attempt, candidates.size, endpoint, "noRealDelay", null)
                continue
            }
            try {
                return connectRuntime(endpoint, selections).also {
                    onConnected(attempt, candidates.size, endpoint)
                }
            } catch (error: Throwable) {
                lastFailure = error
                onRejected(attempt, candidates.size, endpoint, "runtimeFailed", error)
            }
        }
        throw IOException("No quick top-IP candidate connected", lastFailure)
    }
}
