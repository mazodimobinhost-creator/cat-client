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
import com.cat.client.CatClientVpnService.Companion.REAL_CHECK_PASSIVE_GAP_MS
import android.content.Context

// Real internet check after connect, route memory and automatic next route.
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

/* ---- beta220: real internet test + automatic next route with memory per network ---------------------------
 * «Connected» only proves the tunnel is up. ~2 s after every connect and every route switch we load real pages
 * through the VPN (RealInternetCheck). In Automatic mode a route that brings no internet (checked twice) is
 * recorded as bad for this network and the next route is switched in by itself (max 3 hops); with a route the
 * user picked, the home screen offers «try the next route» instead. */
internal fun CatClientVpnService.onRealInternetCheckState(previous: VpnState, now: VpnState) {
    if (now != VpnState.Started) {
        realCheckJob?.cancel()
        realCheckJob = null
        if (now != VpnState.Starting) {
            realCheckFingerprint = ""
            realCheckHops = 0
            realCheckTried.clear()
            VpnRuntimeStateStore.saveRealInternetResult(this, null)
        }
        return
    }
    val fingerprint = activeConnectionFingerprint
    if (previous == VpnState.Started) {
        if (fingerprint.ifEmpty { "-" } == realCheckFingerprint) return // same route: notices, selector refreshes
        // The core's own url-test may change leaves every few minutes; re-measure those at most every 5 min.
        // Our own hops and the user's switches are always measured.
        val ownSwitch = realCheckHopPending
        if (!ownSwitch && SystemClock.elapsedRealtime() - realCheckLastAtElapsedMs < REAL_CHECK_PASSIVE_GAP_MS) {
            realCheckFingerprint = fingerprint.ifEmpty { "-" }
            return
        }
    }
    realCheckHopPending = false
    if (previous != VpnState.Started && realCheckHops == 0 && realCheckTried.isEmpty()) {
        // A new connection (not one of our own hops): may we hop by ourselves? Only when the user left it on
        // Automatic — a route the user picked is never replaced behind their back.
        realCheckSessionAutomatic = activeSubscriptionId.isNotBlank() &&
            !connectionSelectionPreferenceStore.hasSelectedProfile(activeSubscriptionId) &&
            !activeConnectionChained
    }
    scheduleRealInternetCheck(fingerprint)
}

internal fun CatClientVpnService.realCheckStillOn(fingerprint: String): Boolean =
    !destroyed && state == VpnState.Started && activeConnectionFingerprint == fingerprint

internal fun CatClientVpnService.scheduleRealInternetCheck(fingerprint: String) {
    realCheckJob?.cancel()
    realCheckFingerprint = fingerprint.ifEmpty { "-" }
    val subscriptionId = activeSubscriptionId
    val label = activeProfile?.let { ConnectionLabelPolicy.displayName(it) }.orEmpty()
    realCheckLastAtElapsedMs = SystemClock.elapsedRealtime()
    val job = scope.launch(Dispatchers.IO) {
        fun stamp(r: RealInternetResult) = r.copy(
            fingerprint = fingerprint,
            routeLabel = label,
            atMs = System.currentTimeMillis(),
            hops = realCheckHops,
        )
        publishRealInternetResult(stamp(RealInternetResult(RealInternetStatus.Checking)))
        delay(RealInternetCheck.START_DELAY_MS)
        if (!realCheckStillOn(fingerprint)) return@launch
        if (childProcessEngineActive) {
            // The DNS-tunnel engine makes the app itself bypass the VPN; our own request would test the plain line.
            publishRealInternetResult(stamp(RealInternetResult(RealInternetStatus.NotMeasurable, detail = "app bypasses the VPN for the DNS-tunnel engine")))
            return@launch
        }
        var result = RealInternetCheck.run()
        if (result.status == RealInternetStatus.NoInternet && realCheckStillOn(fingerprint)) {
            delay(RealInternetCheck.RECHECK_DELAY_MS)
            if (!realCheckStillOn(fingerprint)) return@launch
            result = RealInternetCheck.run()
        }
        if (!realCheckStillOn(fingerprint)) return@launch
        val networkKey = routeNetworkKey()
        when (result.status) {
            RealInternetStatus.Works -> routeMemoryStore.record(networkKey, fingerprint, ok = true)
            RealInternetStatus.NoInternet -> routeMemoryStore.record(networkKey, fingerprint, ok = false)
            else -> Unit
        }
        val next = if (result.status.isProblem) runCatching { nextRouteCandidate(fingerprint, networkKey) }.getOrNull() else null
        val autoHop = result.status == RealInternetStatus.NoInternet && realCheckSessionAutomatic &&
            realCheckHops < RealInternetCheck.MAX_AUTO_HOPS && next != null
        DiagnosticLogger.info(
            this@scheduleRealInternetCheck,
            "realCheck.result",
            "status=${result.status.wire} latencyMs=${result.latencyMs} kbps=${result.kbps} hops=$realCheckHops " +
                "network=$networkKey automatic=$realCheckSessionAutomatic next=${next != null} autoHop=$autoHop detail=${result.detail}",
        )
        publishRealInternetResult(
            stamp(result).copy(
                canTryNext = result.status.isProblem && (next != null || state == VpnState.Started),
                autoHopping = autoHop,
            ),
        )
        if (autoHop && next != null) {
            withContext(Dispatchers.Main) {
                if (realCheckStillOn(fingerprint)) hopToRoute(subscriptionId, next, automatic = true)
            }
        }
    }
    realCheckJob = job
    job.invokeOnCompletion { if (realCheckJob === job) realCheckJob = null }
}

internal fun CatClientVpnService.routeNetworkKey(): String {
    val n = runCatching { networkMonitor.currentDefaultNetwork() }.getOrNull()
    val cellular = n?.isCellular == true
    return RouteMemoryPolicy.networkKey(
        isCellular = cellular,
        isWifi = n?.isWifi == true,
        isEthernet = n?.isEthernet == true,
        operatorCode = if (cellular) CarrierDetector.rawOperatorCode(this) else null,
    )
}

internal fun CatClientVpnService.routeMemoryGood(): List<String> = runCatching {
    RouteMemoryPolicy.good(routeMemoryStore.entries(routeNetworkKey()), System.currentTimeMillis())
}.getOrDefault(emptyList())

internal fun CatClientVpnService.routeMemoryBad(): Set<String> = runCatching {
    RouteMemoryPolicy.bad(routeMemoryStore.entries(routeNetworkKey()), System.currentTimeMillis())
}.getOrDefault(emptySet())

/** Another route of the running Cat core that can be switched in live, or null (chain, SSTP, Cat Warp, …). */
internal fun CatClientVpnService.nextRouteCandidate(current: String, networkKey: String): String? {
    if (activeConnectionChained || activeRuntimePaths == null || !activeSelectorReady || sstpConnection != null) return null
    val selectable = activeSelectableConnectionFingerprints
    val profiles = activeAvailableProfiles.filter { p ->
        p.fingerprint in selectable && p.shareLink?.trim()?.startsWith("sstp://", ignoreCase = true) != true
    }
    if (profiles.size < 2) return null
    val now = System.currentTimeMillis()
    val entries = routeMemoryStore.entries(networkKey)
    val delays = runCatching {
        subscriptionStore.readConnectionDelayRecords(subscriptionId = activeSubscriptionId, profiles = profiles)
    }.getOrDefault(emptyList())
        .filter { it.status == ConnectionDelayStatus.Success && it.delayMs != null }
        .associate { it.fingerprint to it.delayMs!!.toLong() }
    return RouteMemoryPolicy.nextCandidate(
        candidates = profiles.map(ConnectionProfile::fingerprint),
        current = current,
        tried = realCheckTried + current,
        good = RouteMemoryPolicy.good(entries, now),
        bad = RouteMemoryPolicy.bad(entries, now),
        delayMs = delays,
    )
}

internal fun CatClientVpnService.hopToRoute(subscriptionId: String, fingerprint: String, automatic: Boolean) {
    activeConnectionFingerprint.takeIf(String::isNotBlank)?.let(realCheckTried::add)
    if (automatic) realCheckHops += 1
    realCheckHopPending = true
    DiagnosticLogger.info(this, "realCheck.hop", "automatic=$automatic hop=$realCheckHops tried=${realCheckTried.size}")
    switchActiveConnection(subscriptionId, fingerprint, persistSelection = false)
}

/** «Try the next route» button: live switch when the core has another route, otherwise restart without this one. */
internal fun CatClientVpnService.tryNextRoute() {
    if (state != VpnState.Started) return
    val current = activeConnectionFingerprint
    val subscriptionId = activeSubscriptionId
    scope.launch(Dispatchers.IO) {
        val networkKey = routeNetworkKey()
        val next = runCatching { nextRouteCandidate(current, networkKey) }.getOrNull()
        if (next == null && current.isNotBlank()) {
            // The user says this route does not work here: remember it, so the reconnect's automatic order
            // (AutomaticConnectionCandidatePolicy networkBad) starts with another one.
            runCatching { routeMemoryStore.record(networkKey, current, ok = false) }
        }
        withContext(Dispatchers.Main) {
            if (state != VpnState.Started) return@withContext
            if (next != null) {
                hopToRoute(subscriptionId, next, automatic = false)
            } else {
                current.takeIf(String::isNotBlank)?.let(realCheckTried::add)
                DiagnosticLogger.info(this@tryNextRoute, "realCheck.next.restart")
                refreshVpn(eventPrefix = "realCheckNext")
            }
        }
    }
}

internal fun CatClientVpnService.publishRealInternetResult(result: RealInternetResult) {
    VpnRuntimeStateStore.saveRealInternetResult(this, result)
    sendBroadcast(
        Intent(Actions.REAL_INTERNET_CHECK)
            .setPackage(packageName)
            .putExtra(Actions.EXTRA_REAL_INTERNET_RESULT, result.toJson()),
    )
}
