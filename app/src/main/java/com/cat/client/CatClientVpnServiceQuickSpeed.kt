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
import com.cat.client.CatClientVpnService.Companion.FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS
import com.cat.client.CatClientVpnService.Companion.QUICK_SPEED_DOWNLOAD_TIMEOUT_MS
import com.cat.client.CatClientVpnService.Companion.QUICK_SPEED_SHORTLIST_TIMEOUT_MS
import com.cat.client.CatClientVpnService.QuickSpeedPin
import com.cat.client.CatClientVpnService.QuickSpeedSelectionResult
import com.cat.client.CatClientVpnService.StartedMihomoRuntime
import android.content.Context

// Quick «fastest» reconnect: shortlist, pin and restore of the fastest route.
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

internal suspend fun CatClientVpnService.selectQuickFastestReconnect(
    controller: MihomoControllerClient,
    paths: MihomoRuntimePaths,
    initialResponse: JSONObject,
    rootName: String,
    groupName: String,
    availableProfiles: List<ConnectionProfile>,
): QuickSpeedSelectionResult = controllerSelectionMutex.withLock {
    val startedAt = SystemClock.elapsedRealtime()
    val startingNetworkFingerprint = networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint()
    var response = initialResponse
    var shortlistSnapshots = 1
    var graphReads = 1 // Reused initial controller snapshot.
    val availableProfileNames = availableProfiles.mapTo(hashSetOf(), ConnectionProfile::tag)
    var selectedPlan = MihomoQuickFastestPolicy.plan(
        response = response,
        groupName = groupName,
        availableProfileNames = availableProfileNames,
    )
    var hasRequiredCapabilities = MihomoQuickFastestPolicy.hasRequiredCapabilities(response, groupName)
    if (selectedPlan == null && hasRequiredCapabilities) {
        try {
            val remainingWaitMs = QUICK_SPEED_SHORTLIST_TIMEOUT_MS -
                (SystemClock.elapsedRealtime() - startedAt)
            if (remainingWaitMs > 0L) delay(remainingWaitMs)
            graphReads += 1
            response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
            shortlistSnapshots += 1
            selectedPlan = MihomoQuickFastestPolicy.plan(
                response = response,
                groupName = groupName,
                availableProfileNames = availableProfileNames,
            )
            hasRequiredCapabilities = MihomoQuickFastestPolicy.hasRequiredCapabilities(response, groupName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.shortlist.refresh.failed",
                "error=${error::class.java.simpleName}",
            )
        }
    }
    if (selectedPlan == null) {
        if (!MihomoControllerProxies.isActiveThrough(response, rootName, groupName)) {
            throw IOException("Automatic route changed while waiting for quick speed candidates")
        }
        DiagnosticLogger.info(
            this,
            "mihomo.quickSpeed.skipped",
            "reason=${if (hasRequiredCapabilities) "insufficientLiveCandidates" else "missingCapabilities"} " +
                "shortlistSnapshots=$shortlistSnapshots " +
                "graphReads=$graphReads elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        return@withLock QuickSpeedSelectionResult(response, null)
    }

    val groupSize = response.optJSONObject("proxies")
        ?.optJSONObject(groupName)
        ?.optJSONArray("all")
        ?.length()
        ?: 0
    DiagnosticLogger.info(
        this,
        "mihomo.quickSpeed.plan",
        "groupSize=$groupSize measured=${selectedPlan.candidates.size} " +
            "shortlist=${selectedPlan.candidates.size}",
    )

    val measurements = mutableListOf<MihomoQuickFastestMeasurement>()
    var completedBytes = 0L
    var selectionChanged = false
    var restorationVerified = false
    var committedPin: QuickSpeedPin? = null
    var failure: Throwable? = null
    var lastVerifiedCandidateName: String? = null

    fun ensureQuickSpeedNetworkUnchanged() {
        val currentKey = networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint()
        if (!quickSpeedNetworkUnchanged(startingNetworkFingerprint, currentKey)) {
            throw IOException("Default network changed during the quick speed test")
        }
    }

    try {
        selectedPlan.candidates.forEachIndexed { index, candidate ->
            val attemptStartedAt = SystemClock.elapsedRealtime()
            try {
                selectionChanged = true
                val speedKbps = withTimeoutOrNull(QUICK_SPEED_DOWNLOAD_TIMEOUT_MS.toLong()) {
                    runInterruptible(Dispatchers.IO) {
                        controller.selectProxy(selectedPlan.groupName, candidate.name)
                    }
                    graphReads += 1
                    response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
                    if (
                        !MihomoQuickFastestPolicy.isPinnedActive(
                            response = response,
                            rootName = rootName,
                            groupName = selectedPlan.groupName,
                            selectedName = candidate.name,
                        )
                    ) {
                        throw IOException("Quick speed candidate did not become active")
                    }
                    lastVerifiedCandidateName = candidate.name
                    ensureQuickSpeedNetworkUnchanged()
                    MihomoRuntimeHealth.downloadSpeedKbpsThroughMixedProxy(
                        downloadBytes = MihomoRuntimeDefaults.SPEED_TEST_BYTES,
                        timeoutMs = QUICK_SPEED_DOWNLOAD_TIMEOUT_MS,
                    )
                }
                ensureQuickSpeedNetworkUnchanged()
                if (speedKbps != null) {
                    measurements += MihomoQuickFastestMeasurement(candidate, speedKbps)
                    completedBytes += MihomoRuntimeDefaults.SPEED_TEST_BYTES
                }
                DiagnosticLogger.info(
                    this,
                    "mihomo.quickSpeed.attempt",
                    "attempt=${index + 1}/${selectedPlan.candidates.size} delayMs=${candidate.delayMs} " +
                        "speedKbps=${speedKbps ?: -1} completedBytes=${if (speedKbps == null) 0 else MihomoRuntimeDefaults.SPEED_TEST_BYTES} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAt}",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                DiagnosticLogger.warn(
                    this,
                    "mihomo.quickSpeed.attempt.failed",
                    "attempt=${index + 1}/${selectedPlan.candidates.size} delayMs=${candidate.delayMs} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAt} " +
                        "error=${error::class.java.simpleName}",
                )
            }
        }

        val winner = MihomoQuickFastestPolicy.winner(measurements)
            ?: throw IOException("Quick speed test produced fewer than two complete measurements")
        ensureQuickSpeedNetworkUnchanged()
        if (lastVerifiedCandidateName != winner.candidate.name) {
            runInterruptible(Dispatchers.IO) {
                controller.selectProxy(selectedPlan.groupName, winner.candidate.name)
            }
            graphReads += 1
            response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
            if (
                !MihomoQuickFastestPolicy.isPinnedActive(
                    response = response,
                    rootName = rootName,
                    groupName = selectedPlan.groupName,
                    selectedName = winner.candidate.name,
                )
            ) {
                throw IOException("Quick speed winner did not become active")
            }
        }
        verifyRuntimeHealth(
            timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
            event = "mihomo.quickSpeed.health",
        )
        graphReads += 1
        response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
        if (
            !MihomoQuickFastestPolicy.isPinnedActive(
                response = response,
                rootName = rootName,
                groupName = selectedPlan.groupName,
                selectedName = winner.candidate.name,
            )
        ) {
            throw IOException("Quick speed winner changed before startup completed")
        }
        ensureQuickSpeedNetworkUnchanged()
        committedPin = QuickSpeedPin(
            paths = paths,
            networkFingerprint = startingNetworkFingerprint,
            rootName = rootName,
            groupName = selectedPlan.groupName,
            originalFixed = selectedPlan.originalFixed,
            selectedName = winner.candidate.name,
            delayMs = winner.candidate.delayMs,
            graphReads = AtomicInteger(graphReads),
        )
        activeQuickSpeedPin = committedPin
        invalidatedQuickSpeedPin = null
        DiagnosticLogger.info(
            this,
            "mihomo.quickSpeed.winner",
            "delayMs=${winner.candidate.delayMs} speedKbps=${winner.speedKbps} " +
                "completed=${measurements.size}/${selectedPlan.candidates.size}",
        )
    } catch (error: Throwable) {
        failure = error
    } finally {
        if (committedPin == null && selectionChanged) {
            try {
                graphReads += 1
                response = restoreQuickSpeedSelection(
                    controller,
                    selectedPlan.groupName,
                    selectedPlan.originalFixed,
                )
                restorationVerified = true
                DiagnosticLogger.info(
                    this,
                    "mihomo.quickSpeed.restore.ok",
                    "fixed=${if (selectedPlan.originalFixed.isEmpty()) "automatic" else "preset"}",
                )
            } catch (restoreError: Throwable) {
                failure?.addSuppressed(restoreError)
                if (failure == null) failure = restoreError
                DiagnosticLogger.warn(
                    this,
                    "mihomo.quickSpeed.restore.failed",
                    "error=${restoreError::class.java.simpleName}",
                )
            }
        }
    }

    try {
        failure?.let { error ->
            if (error is CancellationException) throw error
            if (selectionChanged && !restorationVerified) {
                throw IOException("Quick speed selection could not be restored", error)
            }
            DiagnosticLogger.warn(
                this,
                "mihomo.quickSpeed.failedOpen",
                "error=${error::class.java.simpleName}",
            )
            try {
                verifyRuntimeHealth(
                    timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                    event = "mihomo.quickSpeed.restore.health",
                )
                graphReads += 1
                response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
                if (!MihomoControllerProxies.isActiveThrough(response, rootName, selectedPlan.groupName)) {
                    throw IOException("Automatic route changed after the quick speed test")
                }
            } catch (healthError: Throwable) {
                if (healthError is CancellationException) throw healthError
                healthError.addSuppressed(error)
                throw IOException("Automatic route is unhealthy after the quick speed test", healthError)
            }
        }
    } finally {
        DiagnosticLogger.info(
            this,
            "mihomo.quickSpeed.complete",
            "attempts=${selectedPlan.candidates.size} successes=${measurements.size} " +
                "bytes=$completedBytes shortlistSnapshots=$shortlistSnapshots graphReads=$graphReads " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }
    QuickSpeedSelectionResult(response, committedPin)
}

internal suspend fun CatClientVpnService.restoreQuickSpeedSelection(
    controller: MihomoControllerClient,
    groupName: String,
    originalFixed: String,
): JSONObject = withContext(NonCancellable + Dispatchers.IO) {
    if (originalFixed.isEmpty()) {
        controller.clearProxySelection(groupName)
    } else {
        controller.selectProxy(groupName, originalFixed)
    }
    controller.getProxies().also { restored ->
        val group = restored.optJSONObject("proxies")?.optJSONObject(groupName)
            ?: throw IOException("Quick speed group is missing after restore")
        val fixed = group.opt("fixed") as? String
            ?: throw IOException("Quick speed fixed selection is missing after restore")
        if (fixed != originalFixed) {
            throw IOException("Quick speed selection was not restored")
        }
    }
}

internal suspend fun CatClientVpnService.prepareQuickSpeedRuntimeForStarted(
    startedRuntime: StartedMihomoRuntime,
): StartedMihomoRuntime {
    val pin = startedRuntime.quickSpeedPin ?: return startedRuntime
    val controller = MihomoControllerClient(pin.paths.secret, port = pin.paths.controlPort)
    pin.graphReads.incrementAndGet()
    var response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
    val stillValid = quickSpeedNetworkUnchanged(
        pin.networkFingerprint,
        networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint(),
    ) && activeQuickSpeedPin == pin && invalidatedQuickSpeedPin != pin &&
        MihomoQuickFastestPolicy.isPinnedActive(
            response = response,
            rootName = pin.rootName,
            groupName = pin.groupName,
            selectedName = pin.selectedName,
        )
    if (stillValid) {
        DiagnosticLogger.info(
            this,
            "mihomo.quickSpeed.beforeStarted.verified",
            "graphReads=${pin.graphReads.get()}",
        )
        return startedRuntime
    }

    pin.graphReads.incrementAndGet()
    response = restoreQuickSpeedSelection(controller, pin.groupName, pin.originalFixed)
    if (activeQuickSpeedPin == pin) activeQuickSpeedPin = null
    if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
    verifyRuntimeHealth(
        timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
        event = "mihomo.quickSpeed.beforeStarted.health",
    )
    pin.graphReads.incrementAndGet()
    response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
    if (!MihomoControllerProxies.isActiveThrough(response, pin.rootName, pin.groupName)) {
        throw IOException("Automatic route changed before startup publication")
    }
    val activeName = MihomoControllerProxies.activeProxyName(response, pin.rootName)
    val resolvedProfile = startedRuntime.availableProfiles.firstOrNull { it.tag == activeName }
    val profile = resolvedProfile ?: ConnectionProfile(
        tag = pin.groupName,
        type = "mihomo-group",
        server = MihomoRuntimeDefaults.CONTROLLER_HOST,
        port = MihomoRuntimeDefaults.MIXED_PORT,
        transport = "",
        validationHost = MihomoRuntimeDefaults.CONTROLLER_HOST,
    )
    val endpoint = if (resolvedProfile == null) {
        CleanIpResult(
            ip = MihomoRuntimeDefaults.CONTROLLER_HOST,
            port = MihomoRuntimeDefaults.MIXED_PORT,
            latencyMs = -1L,
            lossRate = 0.0,
            checkedAt = System.currentTimeMillis(),
        )
    } else if (profile.fingerprint == startedRuntime.profile.fingerprint) {
        startedRuntime.endpoint
    } else {
        CleanIpResult(
            ip = profile.server,
            port = profile.port,
            latencyMs = -1L,
            lossRate = 0.0,
            checkedAt = System.currentTimeMillis(),
        )
    }
    DiagnosticLogger.info(
        this,
        "mihomo.quickSpeed.beforeStarted.restored",
        "graphReads=${pin.graphReads.get()}",
    )
    return startedRuntime.copy(
        profile = profile,
        endpoint = endpoint,
        delayMs = -1L,
        delayProbeName = resolvedProfile?.tag ?: pin.groupName,
        quickSpeedPin = null,
    )
}
