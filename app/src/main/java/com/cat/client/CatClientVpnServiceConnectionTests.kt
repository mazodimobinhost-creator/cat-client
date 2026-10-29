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
import com.cat.client.CatClientVpnService.Companion.CONNECTION_DELAY_PAUSE_POLL_INTERVAL_MS
import com.cat.client.CatClientVpnService.Companion.CONNECTION_DELAY_RECORD_BATCH_SIZE
import com.cat.client.CatClientVpnService.Companion.coreLifecycle
import com.cat.client.CatClientVpnService.ConnectionTestRuntime
import android.content.Context

// Connection ping/speed tests run by the service (list ping, speed, pause/cancel, publish).
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

internal fun CatClientVpnService.startConnectionDelayTest(
    testId: String,
    subscriptionId: String,
    connectionTypes: Set<String>,
    targetFingerprints: Set<String>,
) {
    if (testId.isBlank()) return
    val resolvedSubscriptionId = subscriptionId.ifBlank {
        subscriptionStore.readSelectedSubscriptionId()
    }
    val preparingSession = ConnectionDelayTestState.replace(
        ConnectionDelayTestSession(
            testId = testId,
            subscriptionId = resolvedSubscriptionId,
            connectionTypes = connectionTypes,
            targetFingerprints = targetFingerprints.toList(),
        ),
    )
    val finishingSpeedJobs = connectionSpeedTestJobs.values.toList()
    if (
        state == VpnState.Starting ||
        state == VpnState.Stopping ||
        ConnectionSpeedTestState.isAnyRunning() ||
        connectionSwitchJob?.isActive == true
    ) {
        val failedSession = ConnectionDelayTestState.replace(
            preparingSession.copy(
                status = Actions.DELAY_TEST_FAILED,
                error = getString(R.string.connection_test_busy),
            ),
        )
        publishConnectionDelayTest(
            failedSession,
        )
        return
    }

    val previousJob = connectionDelayTestJob
    activeConnectionDelayTestId = testId
    connectionDelayTestPaused = false
    lateinit var job: Job
    job = scope.launch {
        try {
            finishingSpeedJobs.forEach { it.join() }
            previousJob?.cancelAndJoin()
            val testSettings = connectionTestSettingsPreferenceStore.read()
            val (snapshot, profiles) = connectionTestWakeLock.hold {
                ensureUnderlyingNetworkAvailable()
                val snapshot = configRepository.readCachedMihomoConfigOrNull(resolvedSubscriptionId)
                    ?: throw IOException(getString(R.string.connection_empty))
                val matchingType = ConnectionTypeSelectionPolicy.filterProfiles(
                    snapshot.catalog.profiles,
                    connectionTypes,
                )
                val profiles = if (targetFingerprints.isEmpty()) {
                    matchingType
                } else {
                    matchingType.filter { it.fingerprint in targetFingerprints }
                }
                if (profiles.isEmpty()) {
                    throw IOException(getString(R.string.connection_empty))
                }
                snapshot to profiles
            }
            withConnectionTestController(resolvedSubscriptionId, snapshot, profiles, sstpCoreless = true) { controller, _ ->
                val startedSession = ConnectionDelayTestState.update(testId) {
                    it.copy(
                        targetFingerprints = profiles.map(ConnectionProfile::fingerprint),
                        status = Actions.DELAY_TEST_STARTED,
                        completed = 0,
                        total = profiles.size,
                        available = 0,
                        finishedFingerprints = emptySet(),
                        paused = connectionDelayTestPaused,
                        error = "",
                    )
                } ?: return@withConnectionTestController
                publishConnectionDelayTest(startedSession)

                val progressMutex = Mutex()
                val persistenceMutex = Mutex()
                val pendingRecords = mutableListOf<ConnectionDelayRecord>()
                var flushCount = 0
                suspend fun flushPendingRecords() {
                    persistenceMutex.withLock {
                        if (pendingRecords.isEmpty()) return@withLock
                        subscriptionStore.saveConnectionDelayRecords(pendingRecords)
                        pendingRecords.clear()
                        flushCount += 1
                    }
                }
                suspend fun enqueueRecord(record: ConnectionDelayRecord) {
                    persistenceMutex.withLock {
                        pendingRecords += record
                        if (pendingRecords.size >= CONNECTION_DELAY_RECORD_BATCH_SIZE) {
                            subscriptionStore.saveConnectionDelayRecords(pendingRecords)
                            pendingRecords.clear()
                            flushCount += 1
                        }
                    }
                }
                val nextDelayProfileIndex = AtomicInteger(0)
                try {
                    coroutineScope {
                        val delayJobs = List(minOf(testSettings.concurrency, profiles.size)) {
                            launch(Dispatchers.IO) {
                                while (isActive) {
                                    awaitConnectionDelayTestResumed(testId)
                                    val index = nextDelayProfileIndex.getAndIncrement()
                                    if (index >= profiles.size) break
                                    val profile = profiles[index]
                                    val progressSession = connectionTestWakeLock.hold {
                                        val networkBefore = SmartNetworkSnapshotReader.read(this@startConnectionDelayTest)
                                        val delayMs = realConnectionDelayMs(
                                            controller = controller,
                                            profile = profile,
                                            timeoutMs = testSettings.timeoutSeconds * 1_000,
                                        )
                                        val networkAfter = SmartNetworkSnapshotReader.read(this@startConnectionDelayTest)
                                        val verdict = SmartRouteProbePolicy.httpVerdict(
                                            statusCode = if (delayMs != null) 204 else null,
                                            networkBefore = networkBefore.key,
                                            networkAfter = networkAfter.key,
                                            validatedBefore = networkBefore.validated,
                                            validatedAfter = networkAfter.validated,
                                            captiveBefore = networkBefore.captivePortal,
                                            captiveAfter = networkAfter.captivePortal,
                                        )
                                        if (SmartRouteProbePolicy.shouldPersist(verdict, networkBefore.key, networkAfter.key)) {
                                            enqueueRecord(
                                                ConnectionDelayRecord(
                                                    subscriptionId = resolvedSubscriptionId,
                                                    fingerprint = profile.fingerprint,
                                                    delayMs = delayMs,
                                                    status = if (delayMs != null) {
                                                        ConnectionDelayStatus.Success
                                                    } else {
                                                        ConnectionDelayStatus.Failure
                                                    },
                                                    testedAt = System.currentTimeMillis(),
                                                    networkKey = networkBefore.key,
                                                ),
                                            )
                                        }
                                        progressMutex.withLock {
                                            ConnectionDelayTestState.update(testId) { current ->
                                                current.copy(
                                                    status = Actions.DELAY_TEST_PROGRESS,
                                                    completed = current.completed + 1,
                                                    available = current.available + if (verdict == SmartRouteVerdict.WORKS) 1 else 0,
                                                    finishedFingerprints =
                                                        current.finishedFingerprints + profile.fingerprint,
                                                )
                                            }
                                        }
                                    } ?: break
                                    publishConnectionDelayTest(
                                        progressSession,
                                        finishedFingerprints = listOf(profile.fingerprint),
                                    )
                                }
                            }
                        }
                        delayJobs.forEach { it.join() }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        connectionTestWakeLock.hold { flushPendingRecords() }
                    }
                    DiagnosticLogger.info(
                        this@startConnectionDelayTest,
                        "connection.delay.cache.flushed",
                        "flushes=$flushCount completed=${nextDelayProfileIndex.get().coerceAtMost(profiles.size)} total=${profiles.size}",
                    )
                }
            }
            val completedSession = ConnectionDelayTestState.update(testId) {
                it.copy(
                    status = Actions.DELAY_TEST_COMPLETED,
                    paused = false,
                )
            } ?: return@launch
            publishConnectionDelayTest(completedSession)
        } catch (error: CancellationException) {
            ConnectionDelayTestState.update(testId) {
                it.copy(
                    status = Actions.DELAY_TEST_CANCELED,
                    paused = false,
                )
            }?.let(this@startConnectionDelayTest::publishConnectionDelayTest)
            throw error
        } catch (error: Throwable) {
            DiagnosticLogger.warn(
                this@startConnectionDelayTest,
                "connection.delay.failed",
                "types=${connectionTypes.sorted().joinToString(",")}",
                error,
            )
            val failedSession = ConnectionDelayTestState.update(testId) {
                it.copy(
                    status = Actions.DELAY_TEST_FAILED,
                    paused = false,
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: getString(R.string.connection_test_failed),
                )
            } ?: preparingSession.copy(
                status = Actions.DELAY_TEST_FAILED,
                error = getString(R.string.connection_test_failed),
            )
            publishConnectionDelayTest(failedSession)
        }
    }
    connectionDelayTestJob = job
    job.invokeOnCompletion {
        if (connectionDelayTestJob === job) {
            connectionDelayTestPaused = false
            activeConnectionDelayTestId = null
            connectionDelayTestJob = null
            finishConnectionTests()
        }
    }
}

internal fun CatClientVpnService.startConnectionSpeedTest(testId: String, subscriptionId: String, fingerprint: String) {
    if (testId.isBlank() || subscriptionId.isBlank() || fingerprint.isBlank()) return
    val finishingDelayJob = connectionDelayTestJob
    if (
        state == VpnState.Starting ||
        state == VpnState.Stopping ||
        ConnectionDelayTestState.isAnyRunning() ||
        ConnectionSpeedTestState.snapshot(subscriptionId, fingerprint)?.isRunning == true ||
        connectionSwitchJob?.isActive == true
    ) {
        publishConnectionSpeedTest(
            ConnectionSpeedTestSession(
                testId = testId,
                subscriptionId = subscriptionId,
                fingerprint = fingerprint,
                status = Actions.SPEED_TEST_FAILED,
                error = getString(R.string.connection_test_busy),
            ),
        )
        return
    }

    val preparingSession = ConnectionSpeedTestState.replace(
        ConnectionSpeedTestSession(testId, subscriptionId, fingerprint),
    )
    publishConnectionSpeedTest(preparingSession)
    lateinit var job: Job
    job = scope.launch {
        connectionTestWakeLock.hold {
            var existingRecord: ConnectionDelayRecord? = null
            try {
                finishingDelayJob?.join()
                ensureUnderlyingNetworkAvailable()
                val snapshot = configRepository.readCachedMihomoConfigOrNull(subscriptionId)
                    ?: throw IOException(getString(R.string.connection_speed_test_unavailable))
                val profile = snapshot.catalog.profiles.firstOrNull { it.fingerprint == fingerprint }
                    ?: throw IOException(getString(R.string.connection_speed_test_unavailable))
                val record = subscriptionStore
                    .readConnectionDelayRecords(subscriptionId, listOf(profile))
                    .firstOrNull()
                    ?.takeIf {
                        it.status == ConnectionDelayStatus.Success && it.delayMs != null &&
                            (it.networkKey.isBlank() || it.networkKey == NetworkContext.key(this@startConnectionSpeedTest))
                    }
                    ?: throw IOException(getString(R.string.connection_speed_test_unavailable))
                val speedTestBytes = connectionTestSettingsPreferenceStore.read().speedTestBytes

                val speedKbps = withConnectionTestController(
                    subscriptionId,
                    snapshot,
                    listOf(profile),
                ) { controller, temporaryCore ->
                    existingRecord = record
                    ConnectionSpeedTestState.update(testId) {
                        it.copy(status = Actions.SPEED_TEST_STARTED, error = "")
                    }?.let(this@startConnectionSpeedTest::publishConnectionSpeedTest)
                    val preferredRoots = listOfNotNull(
                        MihomoSelectionPolicy.trafficProbeGroup(snapshot.summary)?.name,
                        MihomoSelectionPolicy.mainSelectorGroup(snapshot.summary)?.name,
                    )
                    connectionSpeedKbps(
                        controller = controller,
                        profile = profile,
                        preferredRoots = preferredRoots,
                        temporaryCore = temporaryCore,
                        downloadBytes = speedTestBytes,
                    ) ?: throw IOException(getString(R.string.connection_speed_test_failed))
                }

                subscriptionStore.saveConnectionDelayRecord(record.copy(speedKbps = speedKbps))
                ConnectionSpeedTestState.update(testId) {
                    it.copy(status = Actions.SPEED_TEST_COMPLETED, error = "")
                }?.let(this@startConnectionSpeedTest::publishConnectionSpeedTest)
            } catch (error: CancellationException) {
                existingRecord?.copy(speedKbps = null)?.let(subscriptionStore::saveConnectionDelayRecord)
                throw error
            } catch (error: Throwable) {
                existingRecord?.copy(speedKbps = null)?.let(subscriptionStore::saveConnectionDelayRecord)
                DiagnosticLogger.warn(
                    this@startConnectionSpeedTest,
                    "connection.speed.failed",
                    "fingerprint=$fingerprint",
                    error,
                )
                ConnectionSpeedTestState.update(testId) {
                    it.copy(
                        status = Actions.SPEED_TEST_FAILED,
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: getString(R.string.connection_speed_test_failed),
                    )
                }?.let(this@startConnectionSpeedTest::publishConnectionSpeedTest)
            }
        }
    }
    connectionSpeedTestJobs[testId] = job
    job.invokeOnCompletion { error ->
        if (error is CancellationException) {
            ConnectionSpeedTestState.update(testId) {
                it.copy(status = Actions.SPEED_TEST_CANCELED, error = "")
            }?.let(this@startConnectionSpeedTest::publishConnectionSpeedTest)
        }
        connectionSpeedTestJobs.remove(testId, job)
        finishConnectionTests()
    }
}

internal fun CatClientVpnService.finishConnectionTests() {
    if (destroyed) return
    val hasTests = connectionDelayTestJob != null || connectionSpeedTestJobs.isNotEmpty()
    if (shouldStopServiceAfterConnectionTest(state, hasTests)) {
        stopForegroundCompat()
        stopSelf()
    } else if (!hasTests && state == VpnState.Started) {
        scope.launch(Dispatchers.IO) {
            runCatching { refreshLiveSelectorState() }
        }
    }
}

internal suspend fun <T> CatClientVpnService.withConnectionTestController(
    subscriptionId: String,
    snapshot: MihomoSubscriptionSnapshot,
    requiredProfiles: List<ConnectionProfile>,
    sstpCoreless: Boolean = false,
    block: suspend (MihomoControllerClient, temporaryCore: Boolean) -> T,
): T {
    // beta212: SSTP rows are measured with their own full handshake (realSstpDelayMs) and never touch the Cat
    // core, so they can be tested while any tunnel (also a live SSTP call) is up, without a temporary core.
    if (sstpCoreless && requiredProfiles.isNotEmpty() && requiredProfiles.all { it.shareLink?.trim()?.startsWith("sstp://", ignoreCase = true) == true }) {
        return block(MihomoControllerClient(""), false)
    }
    val runtime = connectionTestWakeLock.hold {
        connectionTestControllerMutex.withLock {
            val shared = connectionTestRuntime
            if (shared != null) {
                if (shared.subscriptionId != subscriptionId) throw MihomoCoreBusyException()
                if (requiredProfiles.any { required ->
                        shared.profiles.none { it.fingerprint == required.fingerprint && it.tag == required.tag }
                    }
                ) {
                    throw IOException(getString(R.string.connection_test_reconnect_required))
                }
                shared
            } else {
                var temporaryCore = false
                try {
                    val controller = if (
                        state == VpnState.Started &&
                        activeRuntimePaths != null &&
                        coreLifecycle.isActive() &&
                        !activeConnectionChained &&
                        subscriptionId == activeSubscriptionId
                    ) {
                        val paths = activeRuntimePaths ?: throw MihomoCoreBusyException()
                        MihomoControllerClient(paths.secret, port = paths.controlPort).also { activeController ->
                            if (requiredProfiles.any { required ->
                                    activeAvailableProfiles.none { active ->
                                        active.fingerprint == required.fingerprint && active.tag == required.tag
                                    }
                                }
                            ) {
                                throw IOException(getString(R.string.connection_test_reconnect_required))
                            }
                            val liveProxies = withContext(Dispatchers.IO) { activeController.getProxies() }
                                .optJSONObject("proxies")
                            if (liveProxies == null || requiredProfiles.any { !liveProxies.has(it.tag) }) {
                                throw IOException(getString(R.string.connection_test_reconnect_required))
                            }
                        }
                    } else {
                        if (state == VpnState.Started) {
                            throw IOException(getString(R.string.connection_test_reconnect_required))
                        }
                        if (!coreLifecycle.isIdle() && !stopCoreService()) {
                            throw MihomoCoreBusyException()
                        }
                        val runtimeYaml = MihomoConnectionOptionsPatcher.patch(
                            snapshot.rawConfig,
                            connectionOptionsPreferenceStore.read(),
                        )
                        val paths = MihomoRuntimeConfigBuilder(this@withConnectionTestController).writeProfileTest(
                            ProfileTestRuntimePlan(
                                rawYaml = runtimeYaml,
                                routingMode = routingModePreferenceStore.read(),
                                dns = DnsRuntimeSettings(
                                    mode = dnsPrivacyPreferenceStore.readMode(),
                                    dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
                                    dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
                                    doqEndpoint = dnsPrivacyPreferenceStore.readDoqEndpoint(),
                                    doh3Url = dnsPrivacyPreferenceStore.readDoh3Url(),
                                ),
                            ),
                        )
                        temporaryCore = true
                        setupCore(paths)
                        MihomoControllerClient(paths.secret, port = paths.controlPort).also { probeController ->
                            waitForController(probeController, paths)
                        }
                    }
                    ConnectionTestRuntime(
                        subscriptionId,
                        controller,
                        if (temporaryCore) snapshot.catalog.profiles else activeAvailableProfiles,
                        temporaryCore,
                    ).also { connectionTestRuntime = it }
                } catch (error: Throwable) {
                    if (temporaryCore) withContext(NonCancellable) { stopCoreService() }
                    throw error
                }
            }.also { it.users += 1 }
        }
    }
    try {
        return block(runtime.controller, runtime.temporaryCore)
    } finally {
        withContext(NonCancellable) {
            connectionTestWakeLock.hold {
                connectionTestControllerMutex.withLock {
                    runtime.users -= 1
                    if (runtime.users == 0) {
                        connectionTestRuntime = null
                        if (runtime.temporaryCore) stopCoreService()
                    }
                }
            }
        }
    }
}

internal suspend fun CatClientVpnService.awaitConnectionDelayTestResumed(testId: String) {
    while (
        activeConnectionDelayTestId == testId &&
        connectionDelayTestPaused
    ) {
        delay(CONNECTION_DELAY_PAUSE_POLL_INTERVAL_MS)
    }
}

internal suspend fun CatClientVpnService.connectionSpeedKbps(
    controller: MihomoControllerClient,
    profile: ConnectionProfile,
    preferredRoots: List<String>,
    temporaryCore: Boolean,
    downloadBytes: Long,
): Int? {
    val selectionMutex = controllerSelectionMutex
    var restoreSelections = emptyList<MihomoGroupSelection>()
    fun restoreActiveSelection(): Boolean {
        var restored = true
        restoreSelections.forEach { selection ->
            runCatching {
                controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
            }.onFailure { error ->
                restored = false
                DiagnosticLogger.warn(
                    this@connectionSpeedKbps,
                    "connection.speed.restore.failed",
                    "selector=${selection.selectorGroup}",
                    error,
                )
            }
        }
        return restored
    }
    selectionMutex.lock()
    var selectionLocked = true
    return try {
        runInterruptible(Dispatchers.IO) {
            val proxies = controller.getProxies()
            val path = MihomoControllerProxies.selectorPath(proxies, profile.tag, preferredRoots)
            if (path.isEmpty()) throw IOException(getString(R.string.connection_speed_test_unavailable))
            if (!temporaryCore) {
                restoreSelections = MihomoControllerProxies.currentSelections(proxies, path)
            }
            path.forEach { selection ->
                controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
            }
        }
        MihomoRuntimeHealth.downloadSpeedKbpsThroughMixedProxy(
            downloadBytes = downloadBytes,
            onResponseReady = {
                if (restoreActiveSelection()) {
                    selectionLocked = false
                    selectionMutex.unlock()
                }
            },
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        DiagnosticLogger.warn(
            this,
            "connection.speed.failed",
            "profile=${profile.tag}",
            error,
        )
        null
    } finally {
        if (selectionLocked) {
            withContext(NonCancellable + Dispatchers.IO) { restoreActiveSelection() }
            selectionMutex.unlock()
        }
    }
}

internal fun CatClientVpnService.setConnectionDelayTestPaused(testId: String, paused: Boolean) {
    if (
        testId.isBlank() ||
        activeConnectionDelayTestId != testId ||
        connectionDelayTestJob?.isActive != true
    ) {
        return
    }
    connectionDelayTestPaused = paused
    ConnectionDelayTestState.update(testId) {
        it.copy(paused = paused)
    }?.let(this@setConnectionDelayTestPaused::publishConnectionDelayTest)
}

internal fun CatClientVpnService.cancelConnectionDelayTest(testId: String? = null) {
    if (!testId.isNullOrBlank() && activeConnectionDelayTestId != testId) return
    connectionDelayTestPaused = false
    connectionDelayTestJob?.cancel(CancellationException("Connection delay test canceled"))
}

internal fun CatClientVpnService.cancelConnectionSpeedTest(testId: String) {
    connectionSpeedTestJobs[testId]?.cancel(CancellationException("Connection speed test canceled"))
}

internal suspend fun CatClientVpnService.cancelConnectionTestsAndWait() {
    val delayJob = connectionDelayTestJob
    val speedJobs = connectionSpeedTestJobs.values.toList()
    connectionDelayTestPaused = false
    speedJobs.forEach { it.cancel() }
    delayJob?.cancelAndJoin()
    speedJobs.forEach { it.join() }
    if (connectionDelayTestJob === delayJob) {
        activeConnectionDelayTestId = null
        connectionDelayTestJob = null
    }
}

internal suspend fun CatClientVpnService.realConnectionDelayMs(
    controller: MihomoControllerClient,
    profile: ConnectionProfile,
    timeoutMs: Int,
): Int? {
    val rawShare = profile.shareLink?.trim().orEmpty()
    val scheme = rawShare.substringBefore("://", "").lowercase()
    if (scheme == "aether" || scheme == "masque") {
        // beta206: a real tunnel handshake through the Aether core, never a socket-connect «ping».
        return withContext(Dispatchers.IO) { realAetherDelayMs(profile, rawShare, timeoutMs) }
    }
    if (scheme == SstpLink.SCHEME) {
        // beta212: the full SSTP handshake (TLS → SSTP → PPP → login → IP address), then hang up.
        return withContext(Dispatchers.IO) { realSstpDelayMs(rawShare, timeoutMs) }
    }
    if (scheme == "fenix" || scheme == "firefoxvpn") {
        // beta206: a real HTTP request through the local Firefox VPN bridge and Mozilla's proxy.
        return withContext(Dispatchers.IO) { realFenixDelayMs(profile, rawShare, timeoutMs) }
    }
    connectionDelayMs(
        controller = controller,
        profile = profile,
        timeoutMs = timeoutMs,
        url = MihomoRuntimeDefaults.DELAY_TEST_URL,
    )?.let { return it }
    // Direct endpoint fallback for local-bridge schemes when the temporary core hasn't spun up the bridge yet
    val directFallback = withContext(Dispatchers.IO) {
        when {
            scheme in PaqetEngine.SUPPORTED_SCHEMES -> {
                val cfg = PaqetEngine.parsePaqetUri(rawShare)
                if (cfg != null) {
                    AetherOperations.probePeerSocketRttMs(
                        "${cfg.serverHost}:${cfg.serverPort}",
                        useTcp = true,
                        timeoutMs = timeoutMs,
                    )?.toInt()
                } else null
            }
            scheme in WhiteDnsSmartScanner.SUPPORTED_TUNNEL_SCHEMES -> {
                val afterScheme = rawShare.substringAfter("://", "")
                val authority = afterScheme.substringBefore("?").substringBefore("#").substringAfterLast("@").trim()
                val peer = if (":" in authority) authority else "$authority:53"
                val useTcp = scheme in setOf("doh", "slipstream")
                AetherOperations.probePeerSocketRttMs(
                    peer,
                    useTcp = useTcp,
                    timeoutMs = timeoutMs,
                )?.toInt()
            }
            else -> null
        }
    }
    if (directFallback != null) return directFallback
    DiagnosticLogger.warn(
        this,
        "connection.delay.unavailable",
        "profile=${profile.tag}",
    )
    return null
}

internal fun CatClientVpnService.realAetherDelayMs(profile: ConnectionProfile, rawShare: String, timeoutMs: Int): Int? {
    val running = aetherSession != null && state == VpnState.Started
    if (running) {
        // The core is carrying the live tunnel and cannot test another one. The profile that is connected right
        // now is measured over that tunnel (a real request); the others keep their last result.
        val active = aetherPreferenceStore.read()
        val sel = AetherSelection.fromAetherUri(rawShare)
        if (sel == null || active.profileName != sel.profileName) return null
        val t0 = System.nanoTime()
        return if (AetherOperations.probeExitTrace(timeoutMs.coerceIn(2_000, 15_000)).isSuccess) {
            ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
        } else null
    }
    val vpnProtector: (Int) -> Boolean = { fd -> protect(fd) }
    val probe = AetherOperations.realAetherHandshake(
        rawShare,
        File(filesDir, "aether.toml"),
        timeoutMs.toLong().coerceIn(5_000L, 30_000L),
        protectSocket = vpnProtector.takeIf { state == VpnState.Started },
    )
    if (probe.rttMs == null) {
        DiagnosticLogger.warn(this, "connection.delay.aether", "profile=${profile.tag} peer=${probe.peer} error=${probe.error}")
        return null
    }
    return probe.rttMs.toInt().coerceAtLeast(1)
}

internal fun CatClientVpnService.realFenixDelayMs(profile: ConnectionProfile, rawShare: String, timeoutMs: Int): Int? {
    val ctx = applicationContext
    if (FirefoxVpnEngine.selectBestActiveAccount(FirefoxVpnEngine.loadAccounts(ctx)) == null) {
        DiagnosticLogger.warn(this, "connection.delay.fenix", "profile=${profile.tag} error=no signed-in Firefox account")
        return null
    }
    val code = rawShare.substringAfter('?', "").substringBefore('#').split('&')
        .firstOrNull { it.startsWith("country=", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.uppercase(java.util.Locale.US)
    // One bridge, one upstream country: tests run one at a time, and a live Firefox VPN tunnel is never
    // switched to another country just to measure a row.
    return synchronized(fenixTestLock) {
        val live = state == VpnState.Started && FirefoxVpnEngine.isLocalProxyRunning()
        val port = if (live) FirefoxVpnEngine.activeProxyPort() else FirefoxVpnEngine.startLocalBridge(ctx, code)
        if (port !in 1..65535) return@synchronized null
        runCatching {
            val t0 = System.nanoTime()
            java.net.Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
                val out = s.getOutputStream()
                val inp = java.io.DataInputStream(s.getInputStream())
                // SOCKS5 by hand (RFC 1928): no auth, CONNECT www.gstatic.com:443 by name (Mozilla's proxy is a
                // CONNECT proxy meant for HTTPS), then a real TLS request inside it.
                out.write(byteArrayOf(5, 1, 0)); out.flush()
                val greet = ByteArray(2).also { inp.readFully(it) }
                check(greet[0].toInt() == 5 && greet[1].toInt() == 0) { "socks greeting refused" }
                val host = "www.gstatic.com".toByteArray(Charsets.US_ASCII)
                out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(1, 0xBB.toByte())); out.flush()
                val head = ByteArray(4).also { inp.readFully(it) }
                check(head[1].toInt() == 0) { "socks connect failed (${head[1].toInt() and 0xFF})" }
                val addrLen = when (head[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> inp.readUnsignedByte(); else -> 0 }
                val skip = addrLen + 2
                inp.readFully(ByteArray(skip))
                val tls = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                    .createSocket(s, "www.gstatic.com", 443, false) as javax.net.ssl.SSLSocket
                tls.soTimeout = timeoutMs
                tls.startHandshake()
                tls.outputStream.write("GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n".toByteArray())
                tls.outputStream.flush()
                val status = tls.inputStream.bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
                check(status.startsWith("HTTP/1.") && (" 204" in status || " 200" in status)) { "status=$status" }
            }
            ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
        }.onFailure {
            DiagnosticLogger.warn(this, "connection.delay.fenix", "profile=${profile.tag} error=${it.message}")
        }.getOrNull()
    }
}

internal suspend fun CatClientVpnService.connectionDelayMs(
    controller: MihomoControllerClient,
    profile: ConnectionProfile,
    timeoutMs: Int,
    url: String,
): Int? {
    return try {
        runInterruptible(Dispatchers.IO) {
            MihomoDelayPolicy.acceptedDelayMs(
                controller.delay(
                    name = profile.tag,
                    timeoutMs = timeoutMs,
                    url = url,
                ),
            )?.toInt()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }
}

internal fun CatClientVpnService.publishConnectionDelayTest(
    session: ConnectionDelayTestSession,
    finishedFingerprints: List<String> = emptyList(),
) {
    sendBroadcast(
        Intent(Actions.CONNECTION_DELAY_TEST_CHANGED)
            .setPackage(packageName)
            .putExtra(Actions.EXTRA_DELAY_TEST_ID, session.testId)
            .putExtra(Actions.EXTRA_DELAY_TEST_STATUS, session.status)
            .putExtra(Actions.EXTRA_DELAY_TEST_COMPLETED, session.completed)
            .putExtra(Actions.EXTRA_DELAY_TEST_TOTAL, session.total)
            .putExtra(Actions.EXTRA_DELAY_TEST_AVAILABLE, session.available)
            .putExtra(Actions.EXTRA_DELAY_TEST_PAUSED, session.paused)
            .putStringArrayListExtra(
                Actions.EXTRA_DELAY_TEST_FINISHED,
                ArrayList(finishedFingerprints),
            )
            .putExtra(Actions.EXTRA_DELAY_TEST_ERROR, session.error),
    )
}

internal fun CatClientVpnService.publishConnectionSpeedTest(session: ConnectionSpeedTestSession) {
    sendBroadcast(
        Intent(Actions.CONNECTION_SPEED_TEST_CHANGED)
            .setPackage(packageName)
            .putExtra(Actions.EXTRA_SPEED_TEST_ID, session.testId)
            .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, session.subscriptionId)
            .putExtra(Actions.EXTRA_CONNECTION_FINGERPRINT, session.fingerprint)
            .putExtra(Actions.EXTRA_SPEED_TEST_STATUS, session.status)
            .putExtra(Actions.EXTRA_SPEED_TEST_ERROR, session.error),
    )
}
