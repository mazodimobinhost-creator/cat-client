package com.cat.client

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

enum class ConnectionChainSlot(val wireName: String) {
    Before("before"),
    Base("base"),
    After("after");

    companion object {
        fun fromWireName(value: String?): ConnectionChainSlot? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class ConnectionChainHopMode(val wireName: String) {
    Off("off"),
    Automatic("automatic"),
    Fixed("fixed");

    companion object {
        fun fromWireName(value: String?): ConnectionChainHopMode =
            entries.firstOrNull { it.wireName == value } ?: Off
    }
}

data class ConnectionChainProfileRef(
    val subscriptionId: String,
    val fingerprint: String,
)

data class ConnectionChainHop(
    val mode: ConnectionChainHopMode,
    val profileRef: ConnectionChainProfileRef? = null,
    /**
     * The address this hop's first proxy is dialled at (`ip` or `ip:port`, a scanned clean
     * IP), or null to keep the address the subscription config carries.
     *
     * This is where a bare IP enters a chain. A hop is a *config* — UUID, SNI, transport —
     * and an IP alone cannot be a hop; but the address that config is dialled at can be
     * replaced. Pinning a hop's address is how a scanned IP (an Iran entry, or a
     * Cloudflare IP that came out of ProxyIP) becomes a leg of the tunnel instead of a
     * plain fronting override that the chain runtime never sees.
     */
    val serverOverride: String? = null,
) {
    companion object {
        fun off() = ConnectionChainHop(ConnectionChainHopMode.Off)
        fun automatic() = ConnectionChainHop(ConnectionChainHopMode.Automatic)
        fun fixed(
            subscriptionId: String,
            fingerprint: String,
            serverOverride: String? = null,
        ) = ConnectionChainHop(
            mode = ConnectionChainHopMode.Fixed,
            profileRef = ConnectionChainProfileRef(subscriptionId, fingerprint),
            serverOverride = serverOverride,
        )
    }
}

data class ConnectionChainSettings(
    val enabled: Boolean = false,
    val before: ConnectionChainHop = ConnectionChainHop.off(),
    val base: ConnectionChainHop = ConnectionChainHop.automatic(),
    val after: ConnectionChainHop = ConnectionChainHop.off(),
) {
    fun hop(slot: ConnectionChainSlot): ConnectionChainHop = when (slot) {
        ConnectionChainSlot.Before -> before
        ConnectionChainSlot.Base -> base
        ConnectionChainSlot.After -> after
    }

    fun withHop(slot: ConnectionChainSlot, hop: ConnectionChainHop): ConnectionChainSettings = when (slot) {
        ConnectionChainSlot.Before -> copy(before = hop)
        ConnectionChainSlot.Base -> copy(base = hop)
        ConnectionChainSlot.After -> copy(after = hop)
    }.normalized()

    val configuredHopCount: Int
        get() = listOf(before, base, after).count { it.mode != ConnectionChainHopMode.Off }

    val isActive: Boolean
        get() = enabled && configuredHopCount > 1

    fun normalized(): ConnectionChainSettings = copy(
        before = before.normalized(optional = true),
        base = base.normalized(optional = false),
        after = after.normalized(optional = true),
    )

    private fun ConnectionChainHop.normalized(optional: Boolean): ConnectionChainHop {
        if (mode == ConnectionChainHopMode.Off) {
            return if (optional) ConnectionChainHop.off() else ConnectionChainHop.automatic()
        }
        if (mode == ConnectionChainHopMode.Fixed) {
            val ref = profileRef
            if (ref == null || ref.subscriptionId.isBlank() || ref.fingerprint.isBlank()) {
                return if (optional) ConnectionChainHop.off() else ConnectionChainHop.automatic()
            }
        }
        return copy(
            profileRef = profileRef.takeIf { mode == ConnectionChainHopMode.Fixed },
            serverOverride = serverOverride?.trim()?.takeIf { value ->
                value.isNotEmpty() && connectionChainEndpointOrNull(value) != null
            },
        )
    }
}

class ConnectionChainPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ConnectionChainSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return ConnectionChainSettings()
        return runCatching {
            val root = JSONObject(raw)
            ConnectionChainSettings(
                enabled = root.optBoolean("enabled", false),
                before = root.optJSONObject("before").toHop(),
                base = root.optJSONObject("base").toHop(defaultAutomatic = true),
                after = root.optJSONObject("after").toHop(),
            ).normalized()
        }.getOrDefault(ConnectionChainSettings())
    }

    fun save(settings: ConnectionChainSettings) {
        val value = settings.normalized()
        prefs.edit().putString(
            KEY_SETTINGS,
            JSONObject()
                .put("enabled", value.enabled)
                .put("before", value.before.toJson())
                .put("base", value.base.toJson())
                .put("after", value.after.toJson())
                .toString(),
        ).apply()
    }

    private fun ConnectionChainHop.toJson(): JSONObject = JSONObject()
        .put("mode", mode.wireName)
        .apply {
            profileRef?.let { ref ->
                put("subscriptionId", ref.subscriptionId)
                put("fingerprint", ref.fingerprint)
            }
            serverOverride?.let { pinned -> put("serverOverride", pinned) }
        }

    private fun JSONObject?.toHop(defaultAutomatic: Boolean = false): ConnectionChainHop {
        if (this == null) {
            return if (defaultAutomatic) ConnectionChainHop.automatic() else ConnectionChainHop.off()
        }
        val pinned = optString("serverOverride").takeIf(String::isNotBlank)
        return when (ConnectionChainHopMode.fromWireName(optString("mode"))) {
            ConnectionChainHopMode.Off -> ConnectionChainHop.off()
            ConnectionChainHopMode.Automatic -> ConnectionChainHop.automatic()
                .copy(serverOverride = pinned)
            ConnectionChainHopMode.Fixed -> ConnectionChainHop.fixed(
                subscriptionId = optString("subscriptionId"),
                fingerprint = optString("fingerprint"),
                serverOverride = pinned,
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "cat_client_connection_chain"
        const val KEY_SETTINGS = "settings"
    }
}

data class ConnectionChainSource(
    val subscriptionId: String,
    val subscriptionName: String,
    val snapshot: MihomoSubscriptionSnapshot,
)

data class ConnectionChainPickerOption(
    val subscriptionId: String,
    val subscriptionName: String,
    val profile: ConnectionProfile,
)

enum class ConnectionChainCompatibilityIssue {
    SelectedConnectionUnavailable,
    SameConnection,
    SharedProxyDependency,
    DownstreamRequiresUdpCapableUpstream,
}

data class MihomoProxyBlock(
    val name: String,
    val type: String,
    val server: String,
    val network: String,
    val transport: String,
    val proto: String,
    val udpEnabled: Boolean,
    val udpOverStream: Boolean,
    val dialerProxy: String?,
    val lines: List<String>,
) {
    val requiresUdpDialer: Boolean
        get() = when (type.lowercase(Locale.US)) {
            "openvpn" -> !proto.lowercase(Locale.US).startsWith("tcp")
            "masque" -> !network.equals("h2", ignoreCase = true)
            else -> type.lowercase(Locale.US) in UDP_TRANSPORT_TYPES ||
            network.lowercase(Locale.US) in UDP_NETWORKS ||
            transport.equals("udp", ignoreCase = true) ||
            (
                udpEnabled &&
                    !udpOverStream &&
                    type.lowercase(Locale.US) in NATIVE_PACKET_TYPES
            )
        }

    val supportsUdpDialing: Boolean
        get() = udpEnabled || type.lowercase(Locale.US) in UDP_CAPABLE_TYPES

    fun render(
        runtimeName: String,
        dialerProxy: String?,
        serverOverride: String? = null,
    ): List<String> {
        var nameReplaced = false
        var dialerReplaced = false
        var serverReplaced = false
        var portReplaced = false
        val pinned = serverOverride?.trim()?.takeIf(String::isNotEmpty)?.let(::connectionChainEndpointOrNull)
        val rendered = lines.mapNotNull { line ->
            val indent = indentation(line)
            val content = line.trimStart()
            when {
                indent == 2 && content.startsWith("- name:") -> {
                    nameReplaced = true
                    "  - name: ${yamlSingleQuoted(runtimeName)}"
                }
                indent == 4 && fieldName(line) == "name" -> {
                    nameReplaced = true
                    "    name: ${yamlSingleQuoted(runtimeName)}"
                }
                indent == 4 && fieldName(line) == "dialer-proxy" -> {
                    if (dialerProxy == null) {
                        null
                    } else {
                        dialerReplaced = true
                        "    dialer-proxy: ${yamlSingleQuoted(dialerProxy)}"
                    }
                }
                pinned != null && connectionChainIsInlineProxyMap(line) -> {
                    serverReplaced = true
                    line.replace(Regex("""server:\s*([^,}]+)"""), "server: ${pinned.ip}")
                        .let { patched ->
                            if (pinned.port == null) patched else {
                                portReplaced = true
                                patched.replace(Regex("""(,|\{)port:\s*([^,}]+)""")) { match ->
                                    "${match.groupValues[1]}port: ${pinned.port}"
                                }
                            }
                        }
                }
                pinned != null && indent == 4 && fieldName(line) == "server" -> {
                    serverReplaced = true
                    connectionChainReplaceField(line, "server", pinned.ip)
                }
                pinned != null && pinned.port != null && indent == 4 && fieldName(line) == "port" -> {
                    portReplaced = true
                    connectionChainReplaceField(line, "port", pinned.port.toString())
                }
                else -> line
            }
        }.toMutableList()
        if (!serverReplaced && pinned != null) {
            throw IOException("Proxy [$name] has no patchable server field for the pinned address")
        }
        if (pinned != null && pinned.port != null && !portReplaced) {
            val insertAt = rendered.indexOfLast { it.isNotBlank() }.let { if (it < 0) rendered.size else it + 1 }
            rendered.add(insertAt, "    port: ${pinned.port}")
        }
        if (!nameReplaced) throw IOException("Proxy [$name] does not have a patchable name")
        if (dialerProxy != null && !dialerReplaced) {
            val insertAt = rendered.indexOfLast { it.isNotBlank() }.let { if (it < 0) rendered.size else it + 1 }
            rendered.add(insertAt, "    dialer-proxy: ${yamlSingleQuoted(dialerProxy)}")
        }
        return rendered
    }

    private companion object {
        val UDP_TRANSPORT_TYPES = setOf(
            "wireguard", "hysteria", "hysteria2", "hy2", "tuic", "juicity",
            "shadowquic",
        )
        val UDP_CAPABLE_TYPES = setOf(
            "hysteria", "hysteria2", "hy2", "tuic", "shadowquic", "openvpn",
        )
        val NATIVE_PACKET_TYPES = setOf(
            "ss", "shadowsocks", "ssr", "shadowsocksr", "socks5", "gost-relay",
        )
        val UDP_NETWORKS = setOf("quic", "h3", "kcp", "mkcp")
    }
}

object MihomoProxyBlockParser {
    fun parse(rawYaml: String): List<MihomoProxyBlock> {
        val blocks = mutableListOf<MihomoProxyBlock>()
        var inProxies = false
        var current = mutableListOf<String>()

        fun flush() {
            if (current.isEmpty()) return
            toBlock(current)?.let(blocks::add)
            current = mutableListOf()
        }

        rawYaml.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            val topLevel = topLevelKey(line)
            if (topLevel != null) {
                if (inProxies) flush()
                inProxies = topLevel == "proxies"
                return@forEach
            }
            if (!inProxies) return@forEach
            if (indentation(line) == 2 && line.trimStart().startsWith("- ")) {
                flush()
                current += line
            } else if (current.isNotEmpty()) {
                current += line
            }
        }
        if (inProxies) flush()
        val duplicate = blocks.groupingBy(MihomoProxyBlock::name).eachCount().entries
            .firstOrNull { it.value > 1 }
        if (duplicate != null) throw IOException("Duplicate proxy name [${duplicate.key}]")
        return blocks
    }

    private fun toBlock(lines: List<String>): MihomoProxyBlock? {
        val name = proxyFieldValue(lines, "name")?.takeIf(String::isNotBlank) ?: return null
        val type = proxyFieldValue(lines, "type")?.takeIf(String::isNotBlank) ?: return null
        return MihomoProxyBlock(
            name = name,
            type = type,
            server = proxyFieldValue(lines, "server").orEmpty(),
            network = proxyFieldValue(lines, "network").orEmpty(),
            transport = proxyFieldValue(lines, "transport").orEmpty(),
            proto = proxyFieldValue(lines, "proto").orEmpty(),
            udpEnabled = proxyFieldValue(lines, "udp").equals("true", ignoreCase = true),
            udpOverStream = listOf("udp-over-tcp", "udp-over-stream")
                .any { proxyFieldValue(lines, it).equals("true", ignoreCase = true) },
            dialerProxy = proxyFieldValue(lines, "dialer-proxy")?.takeIf(String::isNotBlank),
            lines = lines.dropLastWhile(String::isBlank),
        )
    }
}

data class ConnectionChainCandidate(
    val subscriptionId: String,
    val subscriptionName: String,
    val profile: ConnectionProfile,
    val blocks: List<MihomoProxyBlock>,
    val rank: Int = 0,
    val automaticEligible: Boolean = true,
    /**
     * Where this config lands, read from the same text the rest of the app reads (tag/server,
     * flags, ISO codes) — null when the subscription names nothing recognisable. It is what
     * makes «the exit must be country X» askable at all: without it the chain could only be
     * chosen by name or by measured delay.
     */
    val countryCode: String? = null,
) {
    val ref: ConnectionChainProfileRef
        get() = ConnectionChainProfileRef(subscriptionId, profile.fingerprint)

    val entryBlock: MihomoProxyBlock get() = blocks.first()
    val rootBlock: MihomoProxyBlock get() = blocks.last()
}

data class ResolvedConnectionChainProxy(
    val block: MihomoProxyBlock,
    val runtimeName: String,
)

data class ResolvedConnectionChainHop(
    val slot: ConnectionChainSlot,
    val candidate: ConnectionChainCandidate,
    val proxies: List<ResolvedConnectionChainProxy>,
    val serverOverride: String? = null,
) {
    val runtimeName: String get() = proxies.last().runtimeName
}

data class ConnectionChainRuntimePlan(
    val hops: List<ResolvedConnectionChainHop>,
    val rawYaml: String,
    /**
     * The requested exit country (`null` = automatic) and whether the final hop actually is a
     * config of that country. Reported instead of assumed: a fallback that stays invisible is
     * indistinguishable from a filter that never worked.
     */
    val requestedExitCountryCode: String? = null,
    val exitCountryMatched: Boolean = true,
) {
    val finalHop: ResolvedConnectionChainHop get() = hops.last()
    val finalRuntimeName: String get() = finalHop.runtimeName
    /** Where the traffic really leaves: read off the final hop, not off the request. */
    val exitCountryCode: String? get() = finalHop.candidate.countryCode
    val summary: String get() = hops.joinToString(" -> ") { it.candidate.profile.tag }
}

object ConnectionChainPlanner {
    const val MAX_RUNTIME_ATTEMPTS = 12
    const val GROUP_NAME = "CatClient Proxy Chain"
    private const val MAX_COMPATIBILITY_CHECKS = 1_000_000

    fun plans(
        settings: ConnectionChainSettings,
        sources: List<ConnectionChainSource>,
        delayRecords: List<ConnectionDelayRecord>,
        allowIpv6Literals: Boolean = true,
        requestedExitCountryCode: String? = null,
    ): List<ConnectionChainRuntimePlan> {
        val normalized = settings.normalized()
        if (!normalized.isActive) return emptyList()
        val configuredSlots = ConnectionChainSlot.entries.filter {
            normalized.hop(it).mode != ConnectionChainHopMode.Off
        }
        val fixedSubscriptionIds = configuredSlots.mapNotNull { slot ->
            normalized.hop(slot).profileRef?.subscriptionId
        }.toSet()
        val hasAutomaticHop = configuredSlots.any {
            normalized.hop(it).mode == ConnectionChainHopMode.Automatic
        }
        val candidateSources = if (hasAutomaticHop) {
            sources
        } else {
            sources.filter { it.subscriptionId in fixedSubscriptionIds }
        }
        val recordsByRef = delayRecords.associateBy {
            ConnectionChainProfileRef(it.subscriptionId, it.fingerprint)
        }
        val candidates = candidateSources.flatMap { source ->
            val blocks = try {
                MihomoProxyBlockParser.parse(source.snapshot.rawConfig).associateBy(MihomoProxyBlock::name)
            } catch (error: IOException) {
                if (source.subscriptionId in fixedSubscriptionIds) throw error
                return@flatMap emptyList()
            }
            source.snapshot.catalog.profiles.mapNotNull { profile ->
                val proxyChain = resolveProxyChain(profile.tag, blocks) ?: return@mapNotNull null
                if (!allowIpv6Literals && proxyChain.any { it.server.isIpv6Literal() }) {
                    return@mapNotNull null
                }
                val record = recordsByRef[ConnectionChainProfileRef(source.subscriptionId, profile.fingerprint)]
                val rank = when {
                    record?.status == ConnectionDelayStatus.Success && record.delayMs != null -> record.delayMs
                    else -> Int.MAX_VALUE / 2
                }
                ConnectionChainCandidate(
                    subscriptionId = source.subscriptionId,
                    subscriptionName = source.subscriptionName,
                    profile = profile,
                    blocks = proxyChain,
                    rank = rank,
                    automaticEligible = record?.status != ConnectionDelayStatus.Failure,
                    // Locale.ENGLISH only decides the display name that is thrown away here —
                    // the CODE it returns is locale-independent.
                    countryCode = ConnectionLocationPolicy
                        .countryForProfile(profile, Locale.ENGLISH)
                        ?.code,
                )
            }
        }.sortedWith(
            compareBy<ConnectionChainCandidate> { it.rank }
                .thenBy { it.subscriptionName.lowercase(Locale.getDefault()) }
                .thenBy { it.profile.tag.lowercase(Locale.getDefault()) },
        )
        if (candidates.isEmpty()) throw IOException("No cached connections are available for chaining")

        // The exit is the LAST hop's job: only that slot is narrowed by the requested country,
        // because the earlier hops are the road, not the destination. A Fixed final slot is
        // left exactly as the user picked it — an explicit pick outranks a country wish — and
        // the plan then reports `exitCountryMatched = false` so nobody has to guess.
        val requestedExit = ConnectionLocationPolicy.normalizeCountryCode(requestedExitCountryCode)
        val choices = configuredSlots.mapIndexed { index, slot ->
            val slotChoices = choicesFor(normalized.hop(slot), candidates)
            val isExitSlot = index == configuredSlots.lastIndex
            if (isExitSlot && requestedExit != null && normalized.hop(slot).mode == ConnectionChainHopMode.Automatic) {
                slotChoices.filter { it.countryCode == requestedExit }.ifEmpty { slotChoices }
            } else {
                slotChoices
            }
        }
        return bestCombinations(configuredSlots, choices)
            .map { combination -> toRuntimePlan(combination, normalized, requestedExit) }
            .also { plans ->
                if (plans.isEmpty()) throw IOException("No compatible connection chain is available")
            }
    }

    /**
     * ISO-2 codes the chain's EXIT can really land in, sorted — the options the location
     * selector may offer while a chain is active. Same narrowing as [plans] (only the last
     * configured slot matters), but without building any plan, so the settings screen can call
     * it on every open. A Fixed exit slot yields just its own country (possibly none).
     */
    fun exitCountryCodes(
        settings: ConnectionChainSettings,
        sources: List<ConnectionChainSource>,
        delayRecords: List<ConnectionDelayRecord> = emptyList(),
        allowIpv6Literals: Boolean = true,
    ): List<String> = runCatching {
        val normalized = settings.normalized()
        if (!normalized.isActive) return@runCatching emptyList()
        val configuredSlots = ConnectionChainSlot.entries.filter {
            normalized.hop(it).mode != ConnectionChainHopMode.Off
        }
        if (configuredSlots.isEmpty()) return@runCatching emptyList()
        val exitSlot = configuredSlots.last()
        val fixed = normalized.hop(exitSlot).profileRef
        val recordsByRef = delayRecords.associateBy {
            ConnectionChainProfileRef(it.subscriptionId, it.fingerprint)
        }
        sources.flatMap { source ->
            val blocks = runCatching {
                MihomoProxyBlockParser.parse(source.snapshot.rawConfig).associateBy(MihomoProxyBlock::name)
            }.getOrNull() ?: return@flatMap emptyList()
            source.snapshot.catalog.profiles.mapNotNull { profile ->
                if (fixed != null && (source.subscriptionId != fixed.subscriptionId || profile.fingerprint != fixed.fingerprint)) {
                    return@mapNotNull null
                }
                val proxyChain = resolveProxyChain(profile.tag, blocks) ?: return@mapNotNull null
                if (!allowIpv6Literals && proxyChain.any { it.server.isIpv6Literal() }) {
                    return@mapNotNull null
                }
                val record = recordsByRef[ConnectionChainProfileRef(source.subscriptionId, profile.fingerprint)]
                if (normalized.hop(exitSlot).mode == ConnectionChainHopMode.Automatic &&
                    record?.status == ConnectionDelayStatus.Failure
                ) {
                    return@mapNotNull null
                }
                ConnectionLocationPolicy.countryForProfile(profile, Locale.ENGLISH)?.code
            }
        }.distinct().sorted()
    }.getOrDefault(emptyList())

    // beta217: the connection list asked for these on the main thread every time it opened, and each call parsed
    // the whole subscription YAML again (thousands of proxies → a visible freeze when opening/selecting a config).
    // The parsed blocks are kept for the last raw config seen; a changed subscription is a different string.
    private val blocksMemoLock = Any()
    private var blocksMemoKey: String? = null
    private var blocksMemo: Map<String, MihomoProxyBlock> = emptyMap()

    private fun blocksByName(rawConfig: String): Map<String, MihomoProxyBlock> {
        synchronized(blocksMemoLock) {
            val key = blocksMemoKey
            if (key != null && (key === rawConfig || (key.length == rawConfig.length && key == rawConfig))) return blocksMemo
        }
        val parsed = MihomoProxyBlockParser.parse(rawConfig).associateBy(MihomoProxyBlock::name)
        synchronized(blocksMemoLock) {
            blocksMemoKey = rawConfig
            blocksMemo = parsed
        }
        return parsed
    }

    fun selectableFingerprints(snapshot: MihomoSubscriptionSnapshot): Set<String> = runCatching {
        val blocks = blocksByName(snapshot.rawConfig)
        snapshot.catalog.profiles.mapNotNullTo(mutableSetOf()) { profile ->
            profile.fingerprint.takeIf { resolveProxyChain(profile.tag, blocks) != null }
        }
    }.getOrDefault(emptySet())

    fun udpSupportByFingerprint(snapshot: MihomoSubscriptionSnapshot): Map<String, Boolean> = runCatching {
        val blocks = blocksByName(snapshot.rawConfig)
        snapshot.catalog.profiles.mapNotNull { profile ->
            blocks[profile.tag]?.let { block -> profile.fingerprint to block.supportsUdpDialing }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun selectedCompatibilityIssue(
        settings: ConnectionChainSettings,
        sources: List<ConnectionChainSource>,
    ): ConnectionChainCompatibilityIssue? {
        val normalized = settings.normalized()
        if (!normalized.isActive) return null
        val slots = ConnectionChainSlot.entries.filter {
            normalized.hop(it).mode != ConnectionChainHopMode.Off
        }
        val sourcesById = sources.associateBy(ConnectionChainSource::subscriptionId)
        val parsedBlocks = mutableMapOf<String, Map<String, MihomoProxyBlock>>()
        val candidates = mutableListOf<ConnectionChainCandidate>()
        var hasAutomaticHop = false

        for (slot in slots) {
            val hop = normalized.hop(slot)
            if (hop.mode == ConnectionChainHopMode.Automatic) {
                hasAutomaticHop = true
                continue
            }
            val ref = hop.profileRef
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val source = sourcesById[ref.subscriptionId]
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val blocks = parsedBlocks[ref.subscriptionId] ?: runCatching {
                MihomoProxyBlockParser.parse(source.snapshot.rawConfig).associateBy(MihomoProxyBlock::name)
            }.getOrNull()?.also { parsedBlocks[ref.subscriptionId] = it }
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val profile = source.snapshot.catalog.profiles.firstOrNull {
                it.fingerprint == ref.fingerprint
            } ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val proxyChain = resolveProxyChain(profile.tag, blocks)
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            candidates += ConnectionChainCandidate(
                subscriptionId = source.subscriptionId,
                subscriptionName = source.subscriptionName,
                profile = profile,
                blocks = proxyChain,
            )
        }
        if (hasAutomaticHop) return null

        for (firstIndex in candidates.indices) {
            for (secondIndex in firstIndex + 1 until candidates.size) {
                distinctIssue(candidates[firstIndex], candidates[secondIndex])?.let { return it }
            }
        }
        for (index in 0 until candidates.lastIndex) {
            pairIssue(candidates[index], candidates[index + 1])?.let { return it }
        }
        return null
    }

    private fun choicesFor(
        hop: ConnectionChainHop,
        candidates: List<ConnectionChainCandidate>,
    ): List<ConnectionChainCandidate> = when (hop.mode) {
        ConnectionChainHopMode.Off -> emptyList()
        ConnectionChainHopMode.Automatic -> candidates
            .filter(ConnectionChainCandidate::automaticEligible)
        ConnectionChainHopMode.Fixed -> {
            val ref = hop.profileRef
            listOfNotNull(candidates.firstOrNull { it.ref == ref }).also { resolved ->
                if (resolved.isEmpty()) throw IOException("A selected chain connection is unavailable")
            }
        }
    }

    private fun bestCombinations(
        slots: List<ConnectionChainSlot>,
        choices: List<List<ConnectionChainCandidate>>,
    ): List<List<Pair<ConnectionChainSlot, ConnectionChainCandidate>>> {
        if (choices.any(List<ConnectionChainCandidate>::isEmpty)) return emptyList()
        val viable = choices.toMutableList()
        for (index in viable.lastIndex - 1 downTo 0) {
            viable[index] = viable[index].filter { upstream ->
                viable[index + 1].any { downstream ->
                    canFollow(upstream, downstream)
                }
            }
        }
        val usage = mutableMapOf<Pair<String, String>, Int>()
        val seen = mutableSetOf<List<ConnectionChainProfileRef>>()
        return buildList {
            repeat(MAX_RUNTIME_ATTEMPTS) {
                val ordered = viable.map { slotChoices ->
                    slotChoices.sortedWith(
                        compareBy<ConnectionChainCandidate> { candidate ->
                            physicalRefs(candidate).sumOf { usage[it] ?: 0 }
                        }
                            .thenBy(ConnectionChainCandidate::rank)
                            .thenBy { it.subscriptionName.lowercase(Locale.getDefault()) }
                            .thenBy { it.profile.tag.lowercase(Locale.getDefault()) },
                    )
                }
                val searchOrder = slots.indices.sortedWith(
                    compareBy<Int> { ordered[it].size }.thenBy { it },
                )
                val selected = MutableList<ConnectionChainCandidate?>(slots.size) { null }
                var checks = 0
                var exhausted = false
                fun find(orderIndex: Int):
                    List<Pair<ConnectionChainSlot, ConnectionChainCandidate>>? {
                    if (orderIndex == searchOrder.size) {
                        val combination = slots.indices.map { index -> slots[index] to selected[index]!! }
                        return combination.takeIf {
                            combination.map { it.second.ref } !in seen
                        }
                    }
                    val slotIndex = searchOrder[orderIndex]
                    for (candidate in ordered[slotIndex]) {
                        if (selected.any { it != null && !areDistinct(it, candidate) }) continue
                        val previous = selected.getOrNull(slotIndex - 1)
                        if (previous != null && !canFollow(previous, candidate)) continue
                        val next = selected.getOrNull(slotIndex + 1)
                        if (next != null && !canFollow(candidate, next)) continue
                        // ponytail: chain depth is capped at three; stop a pathological catalog
                        // after one million valid partial assignments and add indexed joins if observed.
                        if (++checks > MAX_COMPATIBILITY_CHECKS) {
                            exhausted = true
                            return null
                        }
                        selected[slotIndex] = candidate
                        find(orderIndex + 1)?.let { return it }
                        selected[slotIndex] = null
                        if (exhausted) return null
                    }
                    return null
                }
                val next = find(0) ?: return@buildList
                add(next)
                seen += next.map { it.second.ref }
                next.forEach { (_, candidate) ->
                    physicalRefs(candidate).forEach { ref ->
                        usage[ref] = (usage[ref] ?: 0) + 1
                    }
                }
            }
        }
    }

    private fun canFollow(
        upstream: ConnectionChainCandidate,
        downstream: ConnectionChainCandidate,
    ): Boolean = pairIssue(upstream, downstream) == null

    private fun pairIssue(
        upstream: ConnectionChainCandidate,
        downstream: ConnectionChainCandidate,
    ): ConnectionChainCompatibilityIssue? =
        distinctIssue(upstream, downstream)
            ?: if (downstream.entryBlock.requiresUdpDialer && !upstream.rootBlock.supportsUdpDialing) {
                ConnectionChainCompatibilityIssue.DownstreamRequiresUdpCapableUpstream
            } else {
                null
            }

    private fun distinctIssue(
        first: ConnectionChainCandidate,
        second: ConnectionChainCandidate,
    ): ConnectionChainCompatibilityIssue? = when {
        first.profile.fingerprint == second.profile.fingerprint ->
            ConnectionChainCompatibilityIssue.SameConnection
        first.blocks.any { firstBlock ->
            second.blocks.any { secondBlock ->
                first.subscriptionId == second.subscriptionId && firstBlock.name == secondBlock.name
            }
        } -> ConnectionChainCompatibilityIssue.SharedProxyDependency
        else -> null
    }

    private fun areDistinct(
        first: ConnectionChainCandidate,
        second: ConnectionChainCandidate,
    ): Boolean = distinctIssue(first, second) == null

    private fun physicalRefs(candidate: ConnectionChainCandidate): List<Pair<String, String>> =
        candidate.blocks.map { candidate.subscriptionId to it.name }

    private fun toRuntimePlan(
        combination: List<Pair<ConnectionChainSlot, ConnectionChainCandidate>>,
        settings: ConnectionChainSettings,
        requestedExitCountryCode: String? = null,
    ): ConnectionChainRuntimePlan {
        val hops = combination.mapIndexed { index, (slot, candidate) ->
            val pinned = settings.hop(slot).serverOverride?.trim()?.takeIf(String::isNotEmpty)
            ResolvedConnectionChainHop(
                slot = slot,
                candidate = candidate,
                proxies = candidate.blocks.mapIndexed { proxyIndex, block ->
                    ResolvedConnectionChainProxy(
                        block = block,
                        runtimeName = "CatClient Chain ${index + 1}.${proxyIndex + 1}",
                    )
                },
                serverOverride = pinned,
            )
        }
        val rawYaml = buildString {
            append("proxies:\n")
            var previousLogicalRoot: String? = null
            hops.forEach { hop ->
                hop.proxies.forEachIndexed { proxyIndex, proxy ->
                    val dialerProxy = if (proxyIndex == 0) {
                        previousLogicalRoot
                    } else {
                        hop.proxies[proxyIndex - 1].runtimeName
                    }
                    // Only the first proxy of a hop is dialled from outside the hop: the
                    // device reaches hop 1's first proxy directly, and every later hop's
                    // first proxy through the previous hop's last one. That leg is the one a
                    // pinned address replaces; patching the rest would silently strip the
                    // address a chained config needs to reach its own upstream.
                    val pinned = hop.serverOverride.takeIf { proxyIndex == 0 }
                    proxy.block.render(proxy.runtimeName, dialerProxy, pinned).forEach { line ->
                        append(line)
                        append('\n')
                    }
                }
                previousLogicalRoot = hop.runtimeName
            }
            append("proxy-groups:\n")
            append("  - name: ${yamlSingleQuoted(GROUP_NAME)}\n")
            append("    type: select\n")
            append("    proxies:\n")
            append("      - ${yamlSingleQuoted(hops.last().runtimeName)}\n")
            append("rules:\n")
            append("  - ${yamlSingleQuoted("MATCH,$GROUP_NAME")}\n")
        }
        val exitCountry = hops.last().candidate.countryCode
        return ConnectionChainRuntimePlan(
            hops = hops,
            rawYaml = rawYaml,
            requestedExitCountryCode = requestedExitCountryCode,
            // Only a real match counts: an unknown country on the chosen config (a subscription
            // that names nothing) is NOT a match, so the caller reports «not the country you
            // asked for» instead of quietly claiming success.
            exitCountryMatched = requestedExitCountryCode == null || exitCountry == requestedExitCountryCode,
        )
    }

    private fun resolveProxyChain(
        rootName: String,
        blocks: Map<String, MihomoProxyBlock>,
    ): List<MihomoProxyBlock>? {
        val rootFirst = mutableListOf<MihomoProxyBlock>()
        val visited = mutableSetOf<String>()
        var name: String? = rootName
        while (name != null) {
            if (!visited.add(name)) return null
            val block = blocks[name] ?: return null
            rootFirst += block
            name = block.dialerProxy
        }
        return rootFirst.asReversed().takeIf { ordered ->
            ordered.zipWithNext().all { (upstream, downstream) ->
                !downstream.requiresUdpDialer || upstream.supportsUdpDialing
            }
        }
    }
}

/**
 * `ip` or `ip:port` (bracketed IPv6 too) — the same shape the fronting editor accepts.
 * Null for anything a user can type by accident, so a bad stored value is dropped when the
 * settings are normalised instead of throwing inside the chain runtime.
 */
internal fun connectionChainEndpointOrNull(value: String): FrontingEndpoint? =
    runCatching { FrontingIpPolicy.parseEndpoint(value) }.getOrNull()

/**
 * The first slot that is actually configured (Before, then Base, then After) — the leg the
 * device dials first. `Base` defaults to Automatic, so callers that need a *real* chain
 * must check `isActive` first.
 */
internal fun ConnectionChainSettings.firstConfiguredSlot(): ConnectionChainSlot? =
    ConnectionChainSlot.entries.firstOrNull { hop(it).mode != ConnectionChainHopMode.Off }

private fun connectionChainReplaceField(line: String, key: String, value: String): String {
    val indent = line.takeWhile(Char::isWhitespace)
    val comment = line.substringAfter(':', "").let { tail ->
        val index = tail.indexOf(" #")
        if (index >= 0) tail.substring(index) else ""
    }
    return "$indent$key: $value$comment"
}

private fun connectionChainIsInlineProxyMap(line: String): Boolean {
    val content = line.trimStart()
    return content.startsWith("- {") && content.contains("server:")
}

private fun proxyFieldValue(lines: List<String>, key: String): String? {
    lines.forEach { line ->
        val content = line.trimStart()
        val value = when {
            indentation(line) == 2 && content.startsWith("- $key:") -> content.substringAfter(":")
            indentation(line) == 4 && fieldName(line) == key -> content.substringAfter(":")
            else -> null
        }
        value?.let { return MihomoConfigParser.decodeScalar(it) }
    }
    return null
}

private fun fieldName(line: String): String =
    line.trimStart().substringBefore(':').trim().removeSurrounding("\"").removeSurrounding("'")

private fun yamlSingleQuoted(value: String): String = "'${value.replace("'", "''")}'"

private fun String.isIpv6Literal(): Boolean =
    trim().removePrefix("[").removeSuffix("]").contains(":")

private fun topLevelKey(line: String): String? {
    if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return null
    val index = line.indexOf(':')
    if (index <= 0) return null
    return line.substring(0, index).trim().takeIf(String::isNotBlank)
}

private fun indentation(line: String): Int =
    line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
