package com.cat.client

import com.whitedns.whiteaesther.core.EndpointScanResult
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeSocketProtector
import java.io.File
import java.net.InetAddress

internal data class AetherProvisionResult(val identities: List<String>)
internal data class AetherEndpoint(val peer: String, val rttMillis: Long)
internal data class AetherTwoHopScanResult(
    val outerPeer: String,
    val innerPeer: String,
    val rttMillis: Long,
    val allCandidates: List<AetherEndpoint> = emptyList(),
)
internal data class AetherWarpKeysRenewReport(
    val ok: Boolean,
    val kind: String,
    val masquePrimaryId: String,
    val masqueSecondaryId: String,
    val wgPrimaryId: String,
    val wgSecondaryId: String,
    val logLines: List<String>,
)
internal data class AetherExitTrace(
    val ip: String,
    val countryCode: String,
    val colo: String,
    val warpStatus: String,
) {
    val isIranIp: Boolean get() = countryCode.equals("IR", ignoreCase = true)
}

internal data class AetherTwoHopProbeReport(
    val outerPeer: String,
    val outerReachable: Boolean,
    val outerRttMs: Long?,
    val innerPeer: String?,
    val innerReachable: Boolean,
    val innerRttMs: Long?,
    val exitTrace: AetherExitTrace?,
    val sanctionFree: Boolean,
) {
    val outerEndpoint: String get() = outerPeer
    val innerEndpoint: String get() = innerPeer.orEmpty()
    val exitIp: String get() = exitTrace?.ip.orEmpty()
    val exitLoc: String get() = exitTrace?.countryCode.orEmpty()
    val exitColo: String get() = exitTrace?.colo.orEmpty()
}

/** Explicit foreground operations only; callers own coroutine/lifecycle cancellation. */
internal object AetherOperations {
    fun parseExitTrace(raw: String): AetherExitTrace? {
        val map = mutableMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        val ip = map["ip"].orEmpty()
        val loc = map["loc"].orEmpty().uppercase()
        if (ip.isEmpty() || !loc.matches(Regex("[A-Z]{2}"))) return null
        return AetherExitTrace(
            ip = ip,
            countryCode = loc,
            colo = map["colo"].orEmpty().ifEmpty { "?" },
            warpStatus = map["warp"].orEmpty().ifEmpty { "off" },
        )
    }

    fun probeExitTrace(timeoutMs: Int = 5_000): Result<AetherExitTrace> = runCatching {
        val urls = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.cloudflare.com/cdn-cgi/trace",
        )
        var lastError: Throwable? = null
        for (rawUrl in urls) {
            try {
                val conn = (java.net.URL(rawUrl).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CatClient/1.10")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseExitTrace(body)?.let { return@runCatching it }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (err: Throwable) {
                lastError = err
            }
        }
        throw (lastError ?: IllegalStateException("Could not determine exit IP location"))
    }
    /**
     * Measures the real exit (Cloudflare `cdn-cgi/trace`) of a local SOCKS5 port, e.g. the Psiphon
     * SOCKS port or the Aether carrier, before the TUN is attached. Returns null when it cannot be measured.
     */
    fun probeExitTraceViaSocks(socksPort: Int, timeoutMs: Int = 8_000, attempts: Int = 2): AetherExitTrace? {
        if (socksPort !in 1..65535) return null
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort))
        val urls = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://speed.cloudflare.com/cdn-cgi/trace",
        )
        repeat(attempts.coerceIn(1, 4)) { round ->
            for (rawUrl in urls) {
                val parsed = runCatching {
                    val conn = (java.net.URL(rawUrl).openConnection(proxy) as java.net.HttpURLConnection).apply {
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        instanceFollowRedirects = true
                        useCaches = false
                        setRequestProperty("User-Agent", "CatClient/1.10")
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            parseExitTrace(conn.inputStream.bufferedReader().use { it.readText() })
                        } else null
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
                if (parsed != null) return parsed
            }
            if (round + 1 < attempts) runCatching { Thread.sleep(1_500L) }
        }
        return null
    }

    fun enforceMasqueHttp2EnvNow(enabled: Boolean) {
        runCatching {
            if (enabled) {
                android.system.Os.setenv("AETHER_MASQUE_HTTP2", "1", true)
            } else {
                android.system.Os.unsetenv("AETHER_MASQUE_HTTP2")
            }
        }
    }

    /**
     * Wraps a JNI call so that when `MASQUE in MASQUE` (`transport = "mim"`) is run over `HTTP/2`,
     * `AETHER_MASQUE_HTTP2=1` is re-asserted immediately after `BridgeConfig::apply_environment()`
     * finishes on the JNI thread (before the spawned Tokio worker reaches `masque_h2::enabled()`).
     */
    fun <T> withMasqueHttp2ForMim(config: AetherRuntimeConfig, block: () -> T): T {
        val needMimH2 = config.resolvedNativeTransport() == "mim" && config.usesMasqueHttp2
        if (!needMimH2) return block()
        enforceMasqueHttp2EnvNow(true)
        val helper = Thread({
            try {
                for (delayMs in longArrayOf(1L, 3L, 7L, 15L, 30L)) {
                    Thread.sleep(delayMs)
                    enforceMasqueHttp2EnvNow(true)
                }
            } catch (_: InterruptedException) {
            }
        }, "aether-mim-h2-env").apply {
            isDaemon = true
            start()
        }
        return try {
            block()
        } finally {
            runCatching { helper.interrupt() }
        }
    }

    fun provision(
        mode: AetherMode,
        identityFile: File,
        upstreamProxy: String? = null,
        ipScan: String = "both",
        exitLocation: String = "auto",
        masqueTransport: String = "h2",
    ): Result<AetherProvisionResult> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Cat Warp native core is unavailable" }
        val aetherMode = if (mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else mode
        val config = AetherRuntimeConfig(
            aetherMode,
            identityFile,
            upstreamProxy = upstreamProxy,
            ipScan = ipScan,
            exitLocation = exitLocation,
            masqueTransport = masqueTransport,
        )
        val validation = NativeAetherBridge.validate(config.toNativeJson())
        check(validation.ok) { validation.error ?: "Cat Warp configuration is invalid" }
        val identities = withMasqueHttp2ForMim(config) {
            NativeAetherBridge.provision(config.toNativeJson())
        }.getOrThrow()
        check(identities.isNotEmpty()) { "Cat Warp did not return an identity" }
        AetherProvisionResult(identities.map(::redactedIdentity))
    }

    /**
     * Implements PattNG's `Get new WARP keys` (`ServerAetherKeysActivity` / `AetherKeysViewModel`):
     * registers fresh keys for `ALL` protocols (`MASQUE`, `MASQUE in MASQUE`, `WireGuard`, `WARP in WARP`,
     * `WireGuard over MASQUE`) or a specific protocol kind, supporting `--api-address`, `--api-sni`,
     * `--api-host` (`api.devices.cloudflare.com` / `api.cloudflareclient.com`), TLS Fragmentation, ECH,
     * and Fingerprint (`chrome`, `chrome-120`, `firefox`, `semi-python`, `go`).
     */
    fun getNewWarpKeys(
        identityFile: File,
        kind: String = "all",
        enrollAddress: String = "",
        apiSni: String = "",
        apiHost: String = "",
        fragment: Boolean = true,
        fragmentSize: String = "16-32",
        fragmentDelay: String = "2-8",
        ech: Boolean = false,
        echDns: String = "https://1.1.1.1/dns-query",
        echDomain: String = "cloudflare-ech.com",
        fingerprint: String = "chrome",
        upstreamProxy: String? = null,
    ): AetherWarpKeysRenewReport {
        val logs = mutableListOf<String>()
        val normKind = kind.trim().lowercase()
        logs += "[*] Starting WARP key registration (kind=$normKind, fingerprint=$fingerprint, fragment=$fragment)"
        // Aether 2.4 takes apiHost/apiSni as their own settings; only a real address goes to enrollAddress
        // (an SNI name is not where to connect).
        val effectiveEnroll = enrollAddress.trim().ifEmpty { AetherRuntimeConfig.safeTlsName(apiHost).orEmpty() }
        if (effectiveEnroll.isNotEmpty()) {
            logs += "[*] API endpoint override: $effectiveEnroll"
        } else {
            logs += "[*] API endpoint: default (api.devices.cloudflare.com -> api.cloudflareclient.com)"
        }

        fun buildProvisionJson(transportCode: String): String {
            return org.json.JSONObject()
                .put("mode", "tun")
                .put("configPath", identityFile.absolutePath)
                .put("transport", transportCode)
                .put("ipScan", "both")
                .put("scanMode", "balanced")
                .put("scanBudgetMillis", 90_000L)
                .put("validationEnabled", true)
                .put("autoReprovision", true)
                .put("lanSharing", false)
                .put("listenPort", 1819)
                .put("fragmentTls", fragment)
                .put("fragmentSize", AetherRuntimeConfig.safeRange(fragmentSize, 1, 16_384) ?: "16-32")
                .put("fragmentDelay", AetherRuntimeConfig.safeRange(fragmentDelay, 0, 100) ?: "2-8")
                .put("encryptedHello", ech)
                .put("echDns", echDns)
                .put("echDomain", echDomain)
                .apply {
                    // beta206: the same browser-capture fingerprint the tunnel uses (see AetherTlsFingerprint).
                    AetherTlsFingerprint.of(fingerprint).applyTo(this)
                    AetherRuntimeConfig.safeTlsName(apiHost)?.let { put("apiHost", it) }
                    AetherRuntimeConfig.safeTlsName(apiSni)?.let { put("apiSni", it) }
                    if (effectiveEnroll.isNotEmpty()) put("enrollAddress", effectiveEnroll)
                    if (!upstreamProxy.isNullOrBlank()) put("upstreamProxy", upstreamProxy)
                }
                .toString()
        }

        var masque1 = ""
        var masque2 = ""
        var wg1 = ""
        var wg2 = ""

        if (NativeAetherBridge.isLoaded) {
            val needMasque = normKind in setOf("all", "mim", "masque", "wg-over-masque", "gool")
            val needMasqueInner = normKind in setOf("all", "mim")
            val needWg = normKind in setOf("all", "wg", "gool", "wiw", "wg-over-masque")
            val needWgInner = normKind in setOf("all", "gool", "wiw")

            if (needMasque) {
                val tCode = if (needMasqueInner) "mim" else "h2"
                enforceMasqueHttp2EnvNow(true)
                val res = NativeAetherBridge.provision(buildProvisionJson(tCode))
                res.onSuccess { ids ->
                    masque1 = ids.getOrNull(0)?.let(::redactedIdentity).orEmpty()
                    masque2 = ids.getOrNull(1)?.let(::redactedIdentity).orEmpty()
                    logs += "[+] MASQUE keys registered: outer=${masque1.ifEmpty { "ok" }}" +
                        (if (masque2.isNotEmpty()) " | inner=$masque2" else "")
                }.onFailure { err ->
                    logs += "[!] Live MASQUE API note: ${err.message ?: "unreachable on direct line"}"
                }
            }
            if (needWg) {
                val tCode = if (needWgInner) "wiw" else "wg"
                val res = NativeAetherBridge.provision(buildProvisionJson(tCode))
                res.onSuccess { ids ->
                    wg1 = ids.getOrNull(0)?.let(::redactedIdentity).orEmpty()
                    wg2 = ids.getOrNull(1)?.let(::redactedIdentity).orEmpty()
                    logs += "[+] WireGuard WARP keys registered: outer=${wg1.ifEmpty { "ok" }}" +
                        (if (wg2.isNotEmpty()) " | inner=$wg2" else "")
                }.onFailure { err ->
                    logs += "[!] Live WireGuard API note: ${err.message ?: "unreachable on direct line"}"
                }
            }
            drainEngineLogs().takeLast(6).forEach { line -> logs += "    $line" }
        }

        // Inspect identity file to confirm which slots are present (existing or newly provisioned)
        if (identityFile.exists()) {
            val rawToml = runCatching { identityFile.readText() }.getOrDefault("")
            if (masque1.isEmpty() && rawToml.contains("[masque]")) masque1 = "ready (cached)"
            if (masque2.isEmpty() && rawToml.contains("[masque_secondary]")) masque2 = "ready (cached)"
            if (wg1.isEmpty() && rawToml.contains("[identity]")) wg1 = "ready (cached)"
            if (wg2.isEmpty() && rawToml.contains("[secondary]")) wg2 = "ready (cached)"
        }

        val anyReady = masque1.isNotEmpty() || wg1.isNotEmpty() || identityFile.exists()
        if (anyReady) {
            logs += "[+] WARP keys ready for MASQUE in MASQUE (HTTP/2) & WARP > Psiphon."
        }
        return AetherWarpKeysRenewReport(
            ok = anyReady,
            kind = normKind,
            masquePrimaryId = masque1.ifEmpty { "auto-on-connect" },
            masqueSecondaryId = masque2.ifEmpty { "auto-on-connect" },
            wgPrimaryId = wg1.ifEmpty { "auto-on-connect" },
            wgSecondaryId = wg2.ifEmpty { "auto-on-connect" },
            logLines = logs,
        )
    }

    fun scan(
        mode: AetherMode,
        identityFile: File,
        ipScan: String = "both",
        masqueTransport: String = "h2",
        scanMode: String = "balanced",
        noizePreset: String = "auto",
    ): Result<List<AetherEndpoint>> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Cat Warp native core is unavailable" }
        // When scanning for MASQUE-in-MASQUE over HTTP/2, run the gateway scanner on HTTP/2 ("h2")
        // so that native `apply_environment()` sets `AETHER_MASQUE_HTTP2=1` during the scan probes.
        val effectiveMode = when {
            mode == AetherMode.MasquePsiphon -> AetherMode.MasqueHttp2
            mode == AetherMode.MasqueInMasque && masqueTransport != "h3" -> AetherMode.MasqueHttp2
            else -> mode
        }
        val config = AetherRuntimeConfig(
            mode = effectiveMode,
            identityFile = identityFile,
            ipScan = ipScan,
            masqueTransport = masqueTransport,
            scanMode = scanMode.takeIf { it in AetherRuntimeConfig.VALID_SCAN_MODES } ?: "balanced",
            noizePreset = noizePreset.takeIf { it in AetherRuntimeConfig.VALID_NOIZE_PRESETS } ?: "auto",
        )
        NativeAetherBridge.scan(config.toNativeJson()).getOrThrow()
            .asSequence()
            .filter(::safeEndpoint)
            .distinctBy { it.peer }
            .sortedBy { it.rttMillis }
            .take(20)
            .map { AetherEndpoint(it.peer, it.rttMillis.coerceAtLeast(0)) }
            .toList()
    }

    /**
     * Scans and picks distinct `Outer hop` and `Inner hop` endpoints for two-hop protocols
     * (`MASQUE in MASQUE` / `WARP in WARP` / `WireGuard over MASQUE`), matching PattNG's
     * `AetherScanner` (`masque-in-masque ready: <outer> (outer) and <inner> (inner)`).
     */
    fun scanTwoHopEndpoints(
        mode: AetherMode,
        identityFile: File,
        ipScan: String = "both",
        masqueTransport: String = "h2",
        scanMode: String = "balanced",
        noizePreset: String = "auto",
    ): Result<AetherTwoHopScanResult> = runCatching {
        val list = scan(mode, identityFile, ipScan, masqueTransport, scanMode, noizePreset).getOrThrow()
        check(list.isNotEmpty()) { "No clean endpoints found" }
        val outer = list.first()
        val outerHost = outer.peer.substringBeforeLast(':')
        val inner = list.firstOrNull { cand ->
            cand.peer.substringBeforeLast(':') != outerHost
        } ?: run {
            val defaultInner = when {
                ipScan == "v6" && (mode == AetherMode.WireGuard || mode == AetherMode.WarpInWarp) ->
                    "[2606:4700:d0::a29f:c002]:2408"
                ipScan == "v6" ->
                    "[2606:4700:d0::a29f:c002]:443"
                mode == AetherMode.WireGuard || mode == AetherMode.WarpInWarp ->
                    if (outerHost == "162.159.192.1") "162.159.193.1:2408" else "162.159.192.1:2408"
                else ->
                    if (outerHost == "162.159.198.1") "162.159.198.2:443" else "162.159.198.1:443"
            }
            AetherEndpoint(defaultInner, outer.rttMillis)
        }
        AetherTwoHopScanResult(
            outerPeer = outer.peer,
            innerPeer = inner.peer,
            rttMillis = outer.rttMillis,
            allCandidates = list,
        )
    }

    /**
     * Replenishes the Warm WARP Key Pool (`AetherPreferenceStore.readWarpKeyPool()`) up to `targetCount`
     * complete keybundles (default 3) so users can create new `MASQUE in MASQUE` / `WARP` configs
     * in 0ms even when Cloudflare's registration API is temporarily throttled.
     */
    fun replenishWarmWarpKeyPool(
        store: AetherPreferenceStore,
        workDir: File,
        targetCount: Int = 3,
        upstreamProxy: String? = null,
    ): List<AetherWarmWarpKeyEntry> {
        val desired = targetCount.coerceIn(1, 5)
        workDir.mkdirs()
        val existingCount = store.readWarpKeyPool().size
        val toFetch = (desired - existingCount).coerceAtLeast(1)
        for (idx in 1..toFetch) {
            val slotFile = File(workDir, "aether_pool_slot_${System.currentTimeMillis()}_$idx.toml")
            try {
                val report = getNewWarpKeys(
                    identityFile = slotFile,
                    kind = "all",
                    upstreamProxy = upstreamProxy,
                )
                if (slotFile.isFile && slotFile.length() > 40L) {
                    val toml = slotFile.readText()
                    val summary = "MASQUE(${report.masquePrimaryId}) + WG(${report.wgPrimaryId})"
                    store.pushWarmWarpKeyEntry(
                        AetherWarmWarpKeyEntry(
                            idSummary = summary,
                            tomlContent = toml,
                            createdAt = System.currentTimeMillis(),
                        ),
                        maxEntries = desired,
                    )
                }
            } finally {
                runCatching { slotFile.delete() }
            }
        }
        // Also seed from active aether.toml if pool is still empty
        val mainToml = File(workDir, "aether.toml")
        if (store.readWarpKeyPool().isEmpty() && mainToml.isFile && mainToml.length() > 40L) {
            store.pushWarmWarpKeyEntry(
                AetherWarmWarpKeyEntry(
                    idSummary = "Cached-Active-WARP-Key",
                    tomlContent = mainToml.readText(),
                    createdAt = System.currentTimeMillis(),
                ),
                maxEntries = desired,
            )
        }
        return store.readWarpKeyPool()
    }

    /**
     * Measures real network RTT (ms) for an `aether://` or `masque://` share URI so that
     * `ConnectionTestingPage` in `MainActivity` displays real ping values for Aether configs.
     */
    fun probeAetherUriLatencyMs(rawUri: String, timeoutMs: Int = 2_500): Int? {
        val sel = AetherSelection.fromAetherUri(rawUri) ?: return null
        val outer = sel.endpoint ?: when {
            sel.ipScan == "v6" && sel.mode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) ->
                "[2606:4700:d0::a29f:c001]:2408"
            sel.ipScan == "v6" -> "[2606:4700:d0::a29f:c001]:443"
            sel.mode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) -> "162.159.192.1:2408"
            else -> "162.159.198.1:443"
        }
        val useTcp = sel.mode in setOf(AetherMode.MasqueInMasque, AetherMode.MasqueHttp2, AetherMode.WireGuardOverMasque) &&
            sel.masqueTransport != "h3"
        val outerRtt = probePeerSocketRttMs(outer, useTcp = useTcp, timeoutMs = timeoutMs) ?: return null
        val inner = sel.innerEndpoint
        if (inner != null && sel.mode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp, AetherMode.WireGuardOverMasque)) {
            val innerRtt = probePeerSocketRttMs(inner, useTcp = useTcp, timeoutMs = timeoutMs)
            if (innerRtt != null) {
                return ((outerRtt + innerRtt) / 2L).toInt().coerceAtLeast(1)
            }
        }
        return outerRtt.toInt().coerceAtLeast(1)
    }

    /**
     * Live 2-Hop diagnostic probe (`Outer Hop` + `Inner Hop` + Cloudflare Trace + Sanction Bypass check).
     */
    fun probeTwoHopLiveReport(selection: AetherSelection, timeoutMs: Int = 2_800): AetherTwoHopProbeReport {
        val outer = selection.endpoint ?: when {
            selection.ipScan == "v6" && selection.mode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) ->
                "[2606:4700:d0::a29f:c001]:2408"
            selection.ipScan == "v6" -> "[2606:4700:d0::a29f:c001]:443"
            selection.mode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) -> "162.159.192.1:2408"
            else -> "162.159.198.1:443"
        }
        val isTwoHop = selection.mode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp, AetherMode.WireGuardOverMasque)
        val inner = if (isTwoHop) {
            selection.innerEndpoint ?: if (outer.startsWith("162.159.198.1:")) "162.159.198.2:443" else "162.159.198.1:443"
        } else null
        val useTcp = selection.mode in setOf(AetherMode.MasqueInMasque, AetherMode.MasqueHttp2, AetherMode.WireGuardOverMasque) &&
            selection.masqueTransport != "h3"
        val outerRtt = probePeerSocketRttMs(outer, useTcp = useTcp, timeoutMs = timeoutMs)
        val innerRtt = inner?.let { probePeerSocketRttMs(it, useTcp = useTcp, timeoutMs = timeoutMs) }
        val trace = probeExitTrace(timeoutMs).getOrNull()
        val sanctioned = runCatching { WhiteDnsSmartScanner.isDirectExitSanctionedOrBlocked(timeoutMs) }.getOrDefault(false)
        return AetherTwoHopProbeReport(
            outerPeer = outer,
            outerReachable = outerRtt != null,
            outerRttMs = outerRtt,
            innerPeer = inner,
            innerReachable = inner == null || innerRtt != null,
            innerRttMs = innerRtt,
            exitTrace = trace,
            sanctionFree = !sanctioned && (trace == null || !trace.isIranIp),
        )
    }

    internal fun probePeerSocketRttMs(peer: String, useTcp: Boolean, timeoutMs: Int = 2_200): Long? {
        val ep = connectionChainEndpointOrNull(peer.trim()) ?: return null
        val port = ep.port ?: 443
        val t0 = System.nanoTime()
        return runCatching {
            val addr = InetAddress.getByName(ep.ip)
            val sockAddr = java.net.InetSocketAddress(addr, port)
            if (useTcp) {
                java.net.Socket().use { sock ->
                    sock.connect(sockAddr, timeoutMs)
                }
            } else {
                // beta206: sending a UDP datagram "succeeds" in ~1 ms whether or not anything answers, which is
                // where the fake «1 ms» results came from. UDP is only measured when a reply proves the path:
                // a real DNS query on port 53; anything else (WireGuard/QUIC) has no handshake to time here.
                if (port != 53) return null
                java.net.DatagramSocket().use { dgram ->
                    dgram.soTimeout = timeoutMs
                    dgram.connect(sockAddr)
                    val query = dnsProbeQuery()
                    dgram.send(java.net.DatagramPacket(query, query.size))
                    val buf = ByteArray(512)
                    val reply = java.net.DatagramPacket(buf, buf.size)
                    dgram.receive(reply)
                    check(reply.length >= 12 && buf[0] == query[0] && buf[1] == query[1]) { "not a DNS reply" }
                }
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    /** A minimal DNS A query for www.google.com with a random id (RFC 1035 §4.1). */
    private fun dnsProbeQuery(): ByteArray {
        val id = java.util.Random().nextInt(0xFFFF)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        "www.google.com".split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    /** Result of [realAetherHandshake]: a measured tunnel RTT, or the native core's reason it failed. */
    data class AetherRealProbe(val rttMs: Long?, val peer: String?, val error: String?)

    private val realProbeLock = Any()

    /**
     * beta206: the connection-test value for an `aether://` profile. Earlier builds showed the time of a bare TCP
     * connect (MASQUE/H2) or of *sending one UDP datagram* (WireGuard/H3 → always «1 ms») to the Cloudflare
     * endpoint, so a profile that could never carry traffic looked healthy. This asks the native core to build the
     * profile's real tunnel (its mode, transport, fingerprint, fragment, SNI) to the endpoint and confirm traffic,
     * exactly like the connect does; no fallback number is invented when it fails.
     */
    fun realAetherHandshake(
        rawUri: String,
        identityFile: File,
        budgetMillis: Long,
        protectSocket: ((Int) -> Boolean)? = null,
    ): AetherRealProbe {
        if (!NativeAetherBridge.isLoaded) return AetherRealProbe(null, null, "Cat Warp native core is unavailable")
        val sel = AetherSelection.fromAetherUri(rawUri) ?: return AetherRealProbe(null, null, "not an Cat Warp link")
        val mode = if (sel.mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else sel.mode
        val udp = mode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) ||
            (sel.masqueTransport == "h3" && mode != AetherMode.WireGuardOverMasque)
        val peer = sel.endpoint?.takeIf { isSafePublicPeer(it) } ?: when {
            sel.ipScan == "v6" && mode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) -> "[2606:4700:d0::a29f:c001]:2408"
            sel.ipScan == "v6" -> "[2606:4700:d0::a29f:c001]:443"
            udp && mode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) -> "162.159.192.1:2408"
            else -> "162.159.198.1:443"
        }
        return runCatching {
            val cfg = AetherRuntimeConfig(
                mode = mode,
                identityFile = identityFile,
                endpoint = peer,
                innerEndpoint = sel.innerEndpoint?.takeIf { isSafePublicPeer(it) },
                ipScan = sel.ipScan.takeIf { it in setOf("both", "v4", "v6") } ?: "both",
                // A Psiphon/Tor chain is tested on its WARP leg only (what the chain rides on).
                exitLocation = if (sel.chainTarget != "none" || sel.mode == AetherMode.MasquePsiphon) "auto" else sel.effectiveExitLocation(),
                masqueTransport = sel.masqueTransport.takeIf { it == "h3" } ?: "h2",
                scanMode = sel.scanMode.takeIf { it in AetherRuntimeConfig.VALID_SCAN_MODES } ?: "balanced",
                noizePreset = sel.noizePreset.takeIf { it in AetherRuntimeConfig.VALID_NOIZE_PRESETS } ?: "auto",
                fingerprint = sel.fingerprint.ifBlank { "chrome" },
                fragmentEnabled = sel.fragmentEnabled,
                fragmentSize = sel.fragmentSize,
                fragmentDelay = sel.fragmentDelay,
                masqueSni = sel.masqueSni,
                echEnabled = sel.echEnabled,
                echDns = sel.echDns,
                echDomain = sel.echDomain,
            )
            val json = org.json.JSONObject(cfg.toNativeJson())
                .put("scanBudgetMillis", budgetMillis.coerceIn(1_000L, 30_000L))
                .put("peerFallback", false)
                .toString()
            // The native core runs one scan/test at a time; parallel test workers queue here.
            val tested = synchronized(realProbeLock) {
                // While another engine owns the VPN, the probe must leave on the real network, not through it.
                protectSocket?.let { p -> NativeAetherBridge.setSocketProtector(NativeSocketProtector { fd -> p(fd) }) }
                try {
                    NativeAetherBridge.testEndpoint(json).getOrThrow()
                } finally {
                    if (protectSocket != null) runCatching { NativeAetherBridge.setSocketProtector(null) }
                }
            }
            AetherRealProbe(tested.rttMillis.coerceAtLeast(1L), tested.peer, null)
        }.getOrElse { AetherRealProbe(null, peer, it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Two-stage endpoint confirmation (from WhiteAestherMobile):
     * verifies that the candidate peer actually completes a tunnel handshake and carries traffic.
     */
    fun confirmEndpoint(
        mode: AetherMode,
        identityFile: File,
        peer: String,
        ipScan: String = "both",
        exitLocation: String = "auto",
        budgetMillis: Long = 10_000L,
    ): Result<AetherEndpoint> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Cat Warp native core is unavailable" }
        require(isSafePublicPeer(peer)) { "Peer must be a public IP literal with port" }
        val aetherMode = if (mode == AetherMode.MasquePsiphon) AetherMode.MasqueHttp2 else mode
        val baseConfig = AetherRuntimeConfig(
            mode = aetherMode,
            identityFile = identityFile,
            endpoint = peer,
            ipScan = ipScan,
            exitLocation = exitLocation,
        )
        val json = org.json.JSONObject(baseConfig.toNativeJson())
            .put("scanBudgetMillis", budgetMillis.coerceIn(1_000L, 30_000L))
            .put("peerFallback", false)
            .toString()
        val tested = NativeAetherBridge.testEndpoint(json).getOrThrow()
        check(safeEndpoint(tested)) { "Confirmed endpoint failed safety validation" }
        AetherEndpoint(tested.peer, tested.rttMillis.coerceAtLeast(1L))
    }

    fun exportIdentity(identityFile: File): Result<String> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Cat Warp native core is unavailable" }
        require(identityFile.isAbsolute) { "Identity path must be absolute" }
        NativeAetherBridge.exportIdentity(identityFile.absolutePath).getOrThrow()
    }

    fun importIdentity(identityFile: File, payload: String): Result<Unit> = runCatching {
        check(NativeAetherBridge.isLoaded) { "Cat Warp native core is unavailable" }
        require(identityFile.isAbsolute) { "Identity path must be absolute" }
        val trimmed = payload.trim()
        require(trimmed.isNotEmpty()) { "Identity payload is empty" }
        val res = NativeAetherBridge.importIdentity(identityFile.absolutePath, trimmed)
        check(res.ok) { res.error ?: "Failed to import Cat Warp identity" }
    }

    fun drainEngineLogs(): List<String> = NativeAetherBridge.drainLog()

    fun migrateRunningTunnel(): Boolean = NativeAetherBridge.migrate()

    /**
     * Parses Tor Moat circumvention JSON response into bridge lines.
     */
    internal fun parseMoatBridgesJson(rawJson: String): List<String> = runCatching {
        val settings = org.json.JSONObject(rawJson).optJSONArray("settings") ?: return emptyList()
        buildList {
            for (i in 0 until settings.length()) {
                val bridgesObj = settings.optJSONObject(i)?.optJSONObject("bridges") ?: continue
                val arr = bridgesObj.optJSONArray("bridge_strings") ?: continue
                for (j in 0 until arr.length()) {
                    val line = arr.optString(j).trim()
                    if (line.isNotEmpty()) add(line)
                }
            }
        }
    }.getOrDefault(emptyList())

    fun fetchMoatTorBridges(country: String = "ir", timeoutMs: Int = 8_000): Result<List<String>> = runCatching {
        val safeCountry = country.trim().lowercase().takeIf { it.matches(Regex("[a-z]{2}")) } ?: "ir"
        val payload = """{"country":"$safeCountry"}"""
        val conn = (java.net.URL("https://bridges.torproject.org/moat/circumvention/settings").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/vnd.api+json")
            setRequestProperty("User-Agent", "")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            check(conn.responseCode in 200..299) { "Moat bridge service HTTP ${conn.responseCode}" }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = parseMoatBridgesJson(body)
            check(parsed.isNotEmpty()) { "No bridges returned by Moat" }
            parsed
        } finally {
            conn.disconnect()
        }
    }

    fun cancel(): Boolean = NativeAetherBridge.cancelPrepare() or NativeAetherBridge.cancelScan()

    internal fun safeEndpoint(result: EndpointScanResult): Boolean =
        result.rttMillis >= 0 && isSafePublicPeer(result.peer)

    internal fun isSafePublicPeer(peer: String): Boolean {
        if (peer.length !in 3..128) return false
        val endpoint = connectionChainEndpointOrNull(peer) ?: return false
        if (endpoint.port !in 1..65535) return false
        val literal = endpoint.ip
        if (!literal.matches(Regex("[0-9.]+|[0-9a-fA-F:]+"))) return false
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4 && bytes[0] == 100 && bytes[1] in 64..127) return false
        // Inet6Address.isSiteLocalAddress does not classify RFC 4193 ULA on all
        // Android/JVM implementations, so reject fc00::/7 explicitly.
        if (bytes.size == 16 && (bytes[0] and 0xfe) == 0xfc) return false
        return !address.isAnyLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress
    }

    private fun redactedIdentity(value: String): String = when {
        value.length <= 8 -> "configured"
        else -> "${value.take(4)}…${value.takeLast(4)}"
    }
}
