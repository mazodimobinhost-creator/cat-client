package com.cat.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.follow.clash.core.Core
import com.follow.clash.core.TunInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.cat.client.CatClientVpnService.Companion.coreLifecycle
import android.content.Context

// Cat Warp engine (Aether): start, Psiphon/Tor chains, exit record, session.
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

internal suspend fun CatClientVpnService.runAetherStartup(eventPrefix: String, selection: AetherSelection) {
    // beta218: the first touch of the native bridge loads the (large) Cat Warp library — off the main thread,
    // which this service shares with the screen.
    val nativeLoaded = withContext(Dispatchers.IO) { AetherRuntimeCapabilities.detect().nativeCoreLoaded }
    check(nativeLoaded) { "Cat Warp native core is not packaged" }
    check(coreLifecycle.isIdle()) { "Cat core must stop before Cat Warp starts" }
    val networkKey = AetherPreferenceStore.underlyingNetworkKey(this)
    VpnRuntimeStateStore.saveLiveConnectStep(this, "۱/۴: شناسایی شبکه ($networkKey) و انتخاب سریع‌ترین Endpoint")
    val effectiveEndpoint = selection.freshEndpoint()
        ?: aetherPreferenceStore.recallEndpointForNetwork(networkKey, selection.mode, selection.ipScan)
    if (effectiveEndpoint != null) {
        aetherPreferenceStore.rememberEndpointForNetwork(networkKey, selection.mode, selection.ipScan, effectiveEndpoint)
    }
    val identityFile = File(filesDir, "aether.toml")
    withContext(Dispatchers.IO) { aetherPreferenceStore.applyWarmWarpKeyToFileIfNeeded(identityFile) }
    val effectiveSelection = if (effectiveEndpoint != null && selection.endpoint == null) {
        selection.copy(endpoint = effectiveEndpoint, endpointScannedAt = System.currentTimeMillis())
    } else selection
    if (effectiveSelection.chainTarget == "auto-psiphon") {
        VpnRuntimeStateStore.saveLiveConnectStep(this, "۲/۴: بررسی وضعیت تحریم مستقیم برای زنجیره هوشمند")
        val sanctioned = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.isDirectExitSanctionedOrBlocked(2_200)
        }
        if (sanctioned) {
            DiagnosticLogger.info(this, "$eventPrefix.aether.autoChain", "escalate=psiphon reason=sanctioned")
            VpnRuntimeStateStore.saveLiveConnectStep(this, "۳/۴: راه‌اندازی زنجیره ضدتحریم (WARP -> Psiphon)")
            runAetherPsiphonStartup(eventPrefix, effectiveSelection.copy(chainTarget = "psiphon"))
            return
        }
        DiagnosticLogger.info(this, "$eventPrefix.aether.autoChain", "escalate=none reason=directOpen")
    }
    if (
        effectiveSelection.mode == AetherMode.MasquePsiphon ||
        effectiveSelection.chainTarget == "psiphon" ||
        effectiveSelection.chainTarget == "psiphon-reverse"
    ) {
        val chainLabel = if (effectiveSelection.chainTarget == "psiphon-reverse") {
            "Psiphon > WARP (${effectiveSelection.psiphonRegion.ifBlank { "Auto" }})"
        } else {
            "WARP > Psiphon (${effectiveSelection.psiphonRegion.ifBlank { "Auto" }})"
        }
        VpnRuntimeStateStore.saveLiveConnectStep(this, "۳/۴: راه‌اندازی زنجیره سایفون ($chainLabel)")
        runAetherPsiphonStartup(eventPrefix, effectiveSelection)
        return
    }
    if (effectiveSelection.chainTarget == "tor") {
        VpnRuntimeStateStore.saveLiveConnectStep(this, "۳/۴: راه‌اندازی زنجیره تور (WARP > Tor)")
        runAetherTorStartup(eventPrefix, effectiveSelection)
        return
    }
    val reverseTorProxy = if (effectiveSelection.chainTarget == "tor-reverse") {
        val orbotPort = runCatching { orbotEnginePreferenceStore.readPort() }.getOrDefault(OrbotEnginePolicy.DEFAULT_PORT)
        val reachable = withContext(Dispatchers.IO) {
            runCatching { OrbotProbe.probe(OrbotEnginePolicy.DEFAULT_HOST, orbotPort).reachable }.getOrDefault(false)
        }
        if (reachable) "socks5://127.0.0.1:$orbotPort" else null
    } else null
    VpnRuntimeStateStore.saveLiveConnectStep(this, "۳/۴: هندشیک مستقیم ${effectiveSelection.mode.wireName} (${effectiveEndpoint ?: "auto"})")
    val session = createAetherSession(resolveSplitTunnelRuntimePlan())
    val config = AetherRuntimeConfig(
        mode = effectiveSelection.mode,
        identityFile = identityFile,
        endpoint = effectiveEndpoint,
        innerEndpoint = effectiveSelection.innerEndpoint,
        upstreamProxy = reverseTorProxy,
        ipScan = effectiveSelection.ipScan,
        exitLocation = effectiveSelection.effectiveExitLocation(),
        masqueTransport = effectiveSelection.masqueTransport,
        scanMode = effectiveSelection.scanMode,
        noizePreset = effectiveSelection.noizePreset,
        fingerprint = effectiveSelection.fingerprint.ifBlank {
            // beta206: PattNG's default (a modern Chrome ClientHello) for every mode; semi-python stays a choice.
            "chrome"
        },
        fragmentEnabled = effectiveSelection.fragmentEnabled,
        fragmentSize = effectiveSelection.fragmentSize,
        fragmentDelay = effectiveSelection.fragmentDelay,
        masqueSni = effectiveSelection.masqueSni,
        echEnabled = effectiveSelection.echEnabled,
        echDns = effectiveSelection.echDns,
        echDomain = effectiveSelection.echDomain,
    )
    DiagnosticLogger.info(
        this,
        "$eventPrefix.aether.start",
        "mode=${effectiveSelection.mode.wireName} transport=${config.resolvedNativeTransport()} exitLoc=${effectiveSelection.effectiveExitLocation()} net=$networkKey",
    )
    val watchdogJob = scope.launch(Dispatchers.IO) {
        var consecutiveFailures = 0
        val readyDeadline = System.currentTimeMillis() + 240_000L
        while (isActive && state != VpnState.Started && System.currentTimeMillis() < readyDeadline) delay(500L)
        if (isActive && state == VpnState.Started) {
            delay(2_500L)
            recordAetherExit(effectiveSelection, via = "direct", trace = AetherOperations.probeExitTrace(6_000).getOrNull())
        }
        while (isActive && state == VpnState.Started) {
            delay(45_000L)
            if (state != VpnState.Started) break
            val traceOk = AetherOperations.probeExitTrace(4_000).isSuccess
            if (traceOk) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures += 1
                if (consecutiveFailures >= 2) {
                    DiagnosticLogger.info(this@runAetherStartup, "aether.watchdog.migrate", "failures=$consecutiveFailures")
                    AetherOperations.migrateRunningTunnel()
                    consecutiveFailures = 0
                }
            }
        }
    }
    var result = try {
        withContext(Dispatchers.IO) { session.run(config) }
    } finally {
        watchdogJob.cancel()
        runCatching { session.close() }
        if (aetherSession === session) aetherSession = null
    }
    if (result.error != null && state != VpnState.Stopping && state != VpnState.Stopped) {
        val failoverPeer = aetherPreferenceStore.nextFailoverEndpointForNetwork(
            networkKey = networkKey,
            mode = effectiveSelection.mode,
            ipScan = effectiveSelection.ipScan,
            failedPeer = effectiveEndpoint,
        )
        if (failoverPeer != null) {
            DiagnosticLogger.info(this, "$eventPrefix.aether.failover", "peer=$failoverPeer net=$networkKey")
            val retrySession = createAetherSession(resolveSplitTunnelRuntimePlan())
            val retryConfig = config.copy(endpoint = failoverPeer)
            result = try {
                withContext(Dispatchers.IO) { retrySession.run(retryConfig) }
            } finally {
                runCatching { retrySession.close() }
                if (aetherSession === retrySession) aetherSession = null
            }
        }
    }
    AetherOperations.drainEngineLogs().takeLast(8).forEach { line ->
        DiagnosticLogger.info(this, "aether.engine.log", line)
    }
    if (state != VpnState.Stopping && state != VpnState.Stopped) {
        throw IOException(result.error ?: "Cat Warp tunnel closed")
    }
}

internal suspend fun CatClientVpnService.runAetherPsiphonStartup(eventPrefix: String, selection: AetherSelection) {
    runCatching { aetherPsiphonChain?.close() }
    aetherPsiphonChain = null
    val splitPlan = resolveSplitTunnelRuntimePlan()
    val carrierMode = selection.effectiveCarrierMode()
    val chain = AetherPsiphonChain(
        context = this,
        scope = scope,
        identityFile = File(filesDir, "aether.toml"),
        protectSocket = { fd -> protect(fd) },
        endpoint = selection.freshEndpoint(),
        carrierMode = carrierMode,
        ipScan = selection.ipScan,
        masqueTransport = selection.masqueTransport,
        innerEndpoint = selection.innerEndpoint,
        linkOptions = selection,
    ).also { aetherPsiphonChain = it }
    DiagnosticLogger.info(
        this,
        "$eventPrefix.aetherPsiphon.start",
        "carrier=${carrierMode.wireName} region=${selection.psiphonRegion.ifEmpty { "auto" }}",
    )
    try {
        val regionFa = PsiphonCarrierConfig.regionShortLabel(selection.psiphonRegion, true).ifBlank { "خودکار" }
        val carrier = chain.start(selection.psiphonRegion) { step ->
            val text = when {
                step == "WARP" -> "۳/۴: اتصال لایه WARP برای سایفون"
                step == "Psiphon Auto" && selection.psiphonRegion.isNotEmpty() ->
                    "۳/۴: سایفون به $regionFa وصل نشد؛ تلاش با سریع‌ترین کشور خارجی"
                step.startsWith("Psiphon") -> "۳/۴: اتصال سایفون ($regionFa) — تا ۷۵ ثانیه صبر کنید"
                else -> "۳/۴: سایفون وصل نشد؛ WARP دولایه (خروجی خارج) و بررسی کشور خروجی"
            }
            VpnRuntimeStateStore.saveLiveConnectStep(this, text)
            DiagnosticLogger.info(this, "$eventPrefix.aetherPsiphon.step", step)
        }
        val dns = DnsRuntimeSettings(
            mode = dnsPrivacyPreferenceStore.readMode(),
            dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
            dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
            doqEndpoint = dnsPrivacyPreferenceStore.readDoqEndpoint(),
            doh3Url = dnsPrivacyPreferenceStore.readDoh3Url(),
        )
        val paths = withContext(Dispatchers.IO) {
            MihomoRuntimeConfigBuilder(this@runAetherPsiphonStartup).writeCarrier(
                rawYaml = PsiphonTunPlan.yaml(carrier.socksPort),
                splitTunnelPlan = splitPlan,
                dns = dns,
            )
        }
        setupCoreRuntime(paths)
        startCoreTun(paths, splitPlan)
        activeRuntimePaths = paths
        DiagnosticLogger.info(
            this,
            "$eventPrefix.aetherPsiphon.ready",
            "requested=${selection.psiphonRegion.ifEmpty { "auto" }} via=${carrier.via} exit=${carrier.exit?.countryCode ?: "unmeasured"}",
        )
        recordAetherExit(selection, via = carrier.via, trace = carrier.exit)
        publishState(VpnState.Started)
    } catch (error: Throwable) {
        runCatching { chain.close() }
        if (aetherPsiphonChain === chain) aetherPsiphonChain = null
        throw error
    }
}

/** Stores and logs the real exit Cloudflare sees, so the Aether panel can show it and warn on an IR exit. */
internal fun CatClientVpnService.recordAetherExit(selection: AetherSelection, via: String, trace: AetherExitTrace?) {
    val requested = when {
        selection.effectiveExitLocation() == "iran" -> "IR"
        selection.chainTarget.startsWith("psiphon") && selection.psiphonRegion.matches(Regex("[A-Z]{2}")) -> selection.psiphonRegion
        else -> "!IR"
    }
    val status = AetherExitStatus(
        requested = requested,
        countryCode = trace?.countryCode.orEmpty(),
        ip = trace?.ip.orEmpty(),
        via = via,
        atMs = System.currentTimeMillis(),
    )
    runCatching { aetherPreferenceStore.saveLastExit(status) }
    val detail = "requested=$requested exit=${status.countryCode.ifEmpty { "unmeasured" }} ip=${status.ip.ifEmpty { "-" }} via=$via"
    if (status.wrongIran) {
        DiagnosticLogger.warn(this, "aether.exit.iran", detail)
        VpnRuntimeStateStore.saveLiveConnectStep(this, "⚠️ خروجی ایران است با اینکه خارج انتخاب شده ($detail)")
    } else {
        DiagnosticLogger.info(this, "aether.exit", detail)
        VpnRuntimeStateStore.saveLiveConnectStep(this, "✅ وصل شد — کشور خروجی واقعی: ${status.countryCode.ifEmpty { "نامشخص" }}")
    }
}

internal suspend fun CatClientVpnService.runAetherTorStartup(eventPrefix: String, selection: AetherSelection) {
    runCatching { aetherTorCarrier?.close() }
    aetherTorCarrier = null
    val splitPlan = resolveSplitTunnelRuntimePlan()
    val carrierMode = selection.effectiveCarrierMode()
    val orbotPort = runCatching { orbotEnginePreferenceStore.readPort() }.getOrDefault(OrbotEnginePolicy.DEFAULT_PORT)
    val torReachable = withContext(Dispatchers.IO) {
        runCatching { OrbotProbe.probe(OrbotEnginePolicy.DEFAULT_HOST, orbotPort).reachable }.getOrDefault(false)
    }
    // Without Orbot the WARP carrier is the exit itself: use two-hop WARP so it is never an Iranian single-hop IP.
    val carrier = AetherProxyCarrier(
        scope = scope,
        identityFile = File(filesDir, "aether.toml"),
        protectSocket = { fd -> protect(fd) },
        endpoint = selection.freshEndpoint(),
        port = 1819,
        carrierMode = carrierMode,
        ipScan = selection.ipScan,
        masqueTransport = selection.masqueTransport,
        innerEndpoint = selection.innerEndpoint,
        exitLocation = if (torReachable) "auto" else "foreign",
        linkOptions = selection,
    ).also { aetherTorCarrier = it }
    DiagnosticLogger.info(this, "$eventPrefix.aetherTor.start", "carrier=${carrierMode.wireName} torReachable=$torReachable")
    if (!torReachable) {
        VpnRuntimeStateStore.saveLiveConnectStep(this, "۳/۴: Orbot (تور) در دسترس نیست؛ WARP دولایه با خروجی خارج")
    }
    try {
        carrier.start()
        val torExit = withContext(Dispatchers.IO) {
            AetherOperations.probeExitTraceViaSocks(if (torReachable) orbotPort else carrier.port)
        }
        if (!torReachable && torExit?.isIranIp == true) {
            throw IOException("Orbot (Tor) is not running and the two-hop WARP fallback still exits in Iran (${torExit.ip}). Start Orbot or pick a Psiphon country.")
        }
        val dns = DnsRuntimeSettings(
            mode = dnsPrivacyPreferenceStore.readMode(),
            dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
            dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
            doqEndpoint = dnsPrivacyPreferenceStore.readDoqEndpoint(),
            doh3Url = dnsPrivacyPreferenceStore.readDoh3Url(),
        )
        val paths = withContext(Dispatchers.IO) {
            MihomoRuntimeConfigBuilder(this@runAetherTorStartup).writeCarrier(
                rawYaml = PsiphonTunPlan.torChainYaml(
                    aetherSocksPort = carrier.port,
                    torSocksPort = orbotPort,
                    torReachable = torReachable,
                ),
                splitTunnelPlan = splitPlan,
                dns = dns,
            )
        }
        setupCoreRuntime(paths)
        startCoreTun(paths, splitPlan)
        activeRuntimePaths = paths
        DiagnosticLogger.info(this, "$eventPrefix.aetherTor.ready", "torReachable=$torReachable")
        recordAetherExit(selection, via = if (torReachable) "tor" else "warp-2hop", trace = torExit)
        publishState(VpnState.Started)
    } catch (error: Throwable) {
        runCatching { carrier.close() }
        if (aetherTorCarrier === carrier) aetherTorCarrier = null
        throw error
    }
}

/** Creates the service-bound native session after mode selection has stopped Mihomo. */
internal fun CatClientVpnService.createAetherSession(splitTunnelPlan: SplitTunnelRuntimePlan): AetherSession {
    aetherSession?.let { stale ->
        runCatching { stale.close() }
        aetherSession = null
    }
    return AetherSession(
        protectSocket = { fd -> protect(fd) },
        establishTun = { ipv4, ipv6, mtu -> establishAetherTun(ipv4, ipv6, splitTunnelPlan, mtu) },
        onReady = {
            scope.launch {
                DiagnosticLogger.info(this@createAetherSession, "aether.tunnel.ready")
                publishState(VpnState.Started)
            }
        },
    ).also { aetherSession = it }
}
