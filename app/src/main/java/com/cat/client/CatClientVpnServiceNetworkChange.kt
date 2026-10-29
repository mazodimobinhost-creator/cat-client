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
import com.cat.client.CatClientVpnService.Companion.CONTROLLER_POLL_INTERVAL_MS
import com.cat.client.CatClientVpnService.Companion.NOTIFICATION_ID
import com.cat.client.CatClientVpnService.Companion.QUICK_SPEED_PIN_RESTORE_ATTEMPTS
import com.cat.client.CatClientVpnService.Companion.coreLifecycle
import android.content.Context

// Default-network changes: quick-speed pin restore, DNS replay.
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

internal fun CatClientVpnService.handleDefaultNetworkChanged(candidate: DefaultNetworkCandidate?, force: Boolean = false) {
    val networkChanged = defaultNetworkStateChanged(
        force = force,
        networkKey = candidate.defaultNetworkKey(),
        dns = candidate?.dnsServers.orEmpty().distinct().joinToString(","),
        previousNetworkKey = lastDefaultNetworkKey,
        previousDns = lastDefaultDns,
    )
    pendingDefaultNetworkDnsReplay = !applyDefaultNetworkDns(candidate, force)
    if (networkChanged) {
        if (aetherSession != null && candidate != null) {
            val migrated = AetherOperations.migrateRunningTunnel()
            DiagnosticLogger.info(this, "aether.network.migrate", "migrated=$migrated")
        }
        if (candidate == null && state == VpnState.Started && activeRuntimePaths != null) {
            awaitingPreservedRuntimeHealth = true
            publishState(VpnState.Starting)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                serviceNotification(getString(R.string.notification_starting)),
            )
        }
        synchronized(quickSpeedStartedLock) {
            activeQuickSpeedPin?.let { invalidatedQuickSpeedPin = it }
        }
        restoreActiveQuickSpeedPinAfterNetworkChange()
        if (shouldRunPostConnectHealthWatchdog(state, awaitingPreservedRuntimeHealth)) {
            startPostConnectHealthWatchdog()
        }
    }
}

internal fun CatClientVpnService.restoreActiveQuickSpeedPinAfterNetworkChange() {
    val pin = activeQuickSpeedPin ?: return
    val activePaths = activeRuntimePaths
    if (activePaths != null && activePaths != pin.paths) {
        activeQuickSpeedPin = null
        if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
        return
    }
    scope.launch(Dispatchers.IO) {
        var rebuildRequired = false
        controllerSelectionMutex.withLock {
            if (
                (state != VpnState.Started && state != VpnState.Starting) ||
                (activeRuntimePaths != null && activeRuntimePaths != pin.paths) ||
                activeQuickSpeedPin != pin
            ) {
                return@withLock
            }
            val controller = MihomoControllerClient(pin.paths.secret, port = pin.paths.controlPort)
            repeat(QUICK_SPEED_PIN_RESTORE_ATTEMPTS) { index ->
                try {
                    if (
                        (activeRuntimePaths != null && activeRuntimePaths != pin.paths) ||
                        activeQuickSpeedPin != pin
                    ) {
                        return@withLock
                    }
                    val before = controller.getProxies()
                    val group = before.optJSONObject("proxies")?.optJSONObject(pin.groupName)
                        ?: throw IOException("Quick speed group is missing")
                    val fixed = group.opt("fixed") as? String
                        ?: throw IOException("Quick speed fixed selection is missing")
                    if (fixed != pin.selectedName) {
                        activeQuickSpeedPin = null
                        if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
                        applyLiveSelectorSnapshot(
                            pin.paths,
                            before,
                            publishIfChanged = state == VpnState.Started,
                        )
                        DiagnosticLogger.info(
                            this@restoreActiveQuickSpeedPinAfterNetworkChange,
                            "mihomo.quickSpeed.network.restore.skipped",
                            "reason=ownershipChanged",
                        )
                        return@withLock
                    }
                    val restored = restoreQuickSpeedSelection(
                        controller,
                        pin.groupName,
                        pin.originalFixed,
                    )
                    applyLiveSelectorSnapshot(
                        pin.paths,
                        restored,
                        publishIfChanged = state == VpnState.Started,
                    )
                    activeQuickSpeedPin = null
                    if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
                    DiagnosticLogger.info(
                        this@restoreActiveQuickSpeedPinAfterNetworkChange,
                        "mihomo.quickSpeed.network.restore.ok",
                        "attempt=${index + 1}/$QUICK_SPEED_PIN_RESTORE_ATTEMPTS " +
                            "fixed=${if (pin.originalFixed.isEmpty()) "automatic" else "preset"}",
                    )
                    return@withLock
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    DiagnosticLogger.warn(
                        this@restoreActiveQuickSpeedPinAfterNetworkChange,
                        "mihomo.quickSpeed.network.restore.failed",
                        "attempt=${index + 1}/$QUICK_SPEED_PIN_RESTORE_ATTEMPTS " +
                            "error=${error::class.java.simpleName}",
                    )
                    if (index + 1 < QUICK_SPEED_PIN_RESTORE_ATTEMPTS) {
                        delay(CONTROLLER_POLL_INTERVAL_MS)
                    }
                }
            }
            rebuildRequired = state == VpnState.Started && activeQuickSpeedPin == pin
        }
        if (rebuildRequired) {
            DiagnosticLogger.warn(
                this@restoreActiveQuickSpeedPinAfterNetworkChange,
                "mihomo.quickSpeed.network.restore.exhausted",
                "attempts=$QUICK_SPEED_PIN_RESTORE_ATTEMPTS recovery=rebuild",
            )
            withContext(Dispatchers.Main.immediate) {
                refreshVpn(eventPrefix = "quickSpeedNetworkRecovery", automatic = true)
            }
        }
    }
}

internal fun CatClientVpnService.applyDefaultNetworkDns(candidate: DefaultNetworkCandidate?, force: Boolean): Boolean {
    val networkKey = candidate.defaultNetworkKey()
    val dns = candidate?.dnsServers.orEmpty().distinct().joinToString(",")
    if (!force && networkKey == lastDefaultNetworkKey && dns == lastDefaultDns) return true
    DiagnosticLogger.info(this, "network.default.changed", "network=$networkKey dns=${candidate?.dnsServers?.size ?: 0} state=${state.wireName}")
    if (!coreLifecycle.isActive()) {
        DiagnosticLogger.info(
            this,
            "network.dns.update.skipped",
            "reason=coreNotActive coreState=${coreLifecycle.currentState()}",
        )
        return false
    }
    val applied = if (dns.isBlank()) {
        true
    } else {
        runCatching { Core.updateDNS(dns) }
            .onFailure {
                DiagnosticLogger.warn(
                    this,
                    "network.dns.update.failed",
                    "dnsCount=${candidate?.dnsServers?.size ?: 0}",
                    it,
                )
            }
            .onSuccess {
                DiagnosticLogger.info(
                    this,
                    "network.dns.update.ok",
                    "dnsCount=${candidate?.dnsServers?.size ?: 0}",
                )
            }
            .isSuccess
    }
    if (applied) {
        lastDefaultNetworkKey = networkKey
        lastDefaultDns = dns
    }
    return applied
}

internal fun CatClientVpnService.retryPendingDefaultNetworkDnsReplay() {
    if (!pendingDefaultNetworkDnsReplay || state != VpnState.Started) return
    pendingDefaultNetworkDnsReplay = false
    DiagnosticLogger.info(
        this,
        "network.dns.update.retry",
        "attempt=1/${DefaultNetworkDnsReplayPolicy.MAX_RETRY_ATTEMPTS}",
    )
    if (!applyDefaultNetworkDns(networkMonitor.currentDefaultNetwork(), force = true)) {
        DiagnosticLogger.warn(
            this,
            "network.dns.update.retry.exhausted",
            "attempts=${DefaultNetworkDnsReplayPolicy.MAX_RETRY_ATTEMPTS}",
        )
    }
}
