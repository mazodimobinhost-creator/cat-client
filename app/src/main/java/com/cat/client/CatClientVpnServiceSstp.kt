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
import com.cat.client.CatClientVpnService.Companion.SSTP_CONNECT_TIMEOUT_MS
import com.cat.client.CatClientVpnService.Companion.SSTP_HANDSHAKE_TIMEOUT_MS
import com.cat.client.CatClientVpnService.Companion.coreLifecycle
import android.content.Context

// Cat SSTP engine: start, delay test, close.
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

internal fun CatClientVpnService.closeSstp(reason: String) {
    sstpConnection?.let { runCatching { it.close(reason) } }
    sstpConnection = null
    sstpTunFd?.let { runCatching { it.close() } }
    sstpTunFd = null
}

/** Closes one call; the shared fields are cleared only if a newer startup has not replaced them meanwhile. */
internal fun CatClientVpnService.closeSstpCall(connection: SstpConnection?, tun: android.os.ParcelFileDescriptor?, reason: String) {
    connection?.let { runCatching { it.close(reason) } }
    tun?.let { runCatching { it.close() } }
    if (connection != null && sstpConnection === connection) sstpConnection = null
    if (tun != null && sstpTunFd === tun) sstpTunFd = null
}

/** Real SSTP delay: time from TCP connect until the server has handed out an IP address. */
internal fun CatClientVpnService.realSstpDelayMs(rawShare: String, timeoutMs: Int): Int? {
    val link = SstpLink.parse(rawShare) ?: return null
    val budget = timeoutMs.coerceIn(5_000, 15_000)
    val t0 = System.nanoTime()
    var conn: SstpConnection? = null
    return try {
        val opened = SstpDialer.open(link, protect = { s -> if (!protect(s)) throw IOException("protect failed") }, resolve = { h -> FirefoxVpnEngine.resolveEdgeAddress(h) }, timeoutMs = budget)
        conn = opened.connection
        opened.connection.handshake(budget.toLong())
        ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
    } catch (e: Exception) {
        DiagnosticLogger.info(this, "sstp.delay.failed", "server=${link.endpoint} reason=${e.message}")
        null
    } finally {
        conn?.close("delay test done")
    }
}

internal suspend fun CatClientVpnService.runSstpStartup(
    eventPrefix: String,
    subscriptionId: String,
    candidates: List<Pair<ConnectionProfile, SstpLink>>,
) {
    val links = candidates.map { it.second }
    check(coreLifecycle.isIdle()) { "Cat core must stop before Cat SSTP starts" }
    closeSstp("restart")
    val splitPlan = resolveSplitTunnelRuntimePlan()
    var lastError: Throwable? = null
    var connection: SstpConnection? = null
    var lease: SstpLease? = null
    var chosen: SstpLink? = null
    for ((index, link) in links.withIndex()) {
        ensureStartupActive(eventPrefix)
        val name = link.remark.ifBlank { link.host }
        VpnRuntimeStateStore.saveLiveConnectStep(this, "${index + 1}/${links.size}: Cat SSTP — وصل شدن به $name")
        DiagnosticLogger.info(this, "$eventPrefix.sstp.try", "server=${link.endpoint} ip=${link.ip.ifBlank { "dns" }}")
        var attempt: SstpConnection? = null
        try {
            val opened = withContext(Dispatchers.IO) {
                SstpDialer.open(
                    link,
                    protect = { s ->
                        if (!protect(s)) {
                            throw IOException("Android refused to protect the SSTP socket (VPN permission revoked or another VPN app is always-on)")
                        }
                    },
                    resolve = { h -> FirefoxVpnEngine.resolveEdgeAddress(h) },
                    timeoutMs = SSTP_CONNECT_TIMEOUT_MS,
                    log = { line -> DiagnosticLogger.info(this@runSstpStartup, "sstp.engine", line) },
                )
            }
            attempt = opened.connection
            sstpConnection = opened.connection
            lease = runInterruptible(Dispatchers.IO) { opened.connection.handshake(SSTP_HANDSHAKE_TIMEOUT_MS) }
            connection = opened.connection
            chosen = link
            break
        } catch (error: CancellationException) {
            closeSstpCall(attempt, null, "canceled")
            throw error
        } catch (error: Exception) {
            lastError = error
            DiagnosticLogger.warn(this, "$eventPrefix.sstp.failed", "server=${link.endpoint} reason=${error.message}")
            closeSstpCall(attempt, null, "candidate failed")
        }
    }
    if (connection == null || lease == null || chosen == null) {
        throw IOException("Cat SSTP: هیچ‌کدام از ${links.size} سرور وصل نشد (${lastError?.message ?: "بدون پاسخ"})", lastError)
    }
    VpnRuntimeStateStore.saveLiveConnectStep(this, "Cat SSTP: آی‌پی ${lease.address} گرفته شد — ساخت تونل")
    val tun = try {
        establishSstpTun(lease, splitPlan, chosen.remark.ifBlank { chosen.host })
    } catch (error: Exception) {
        closeSstpCall(connection, null, "tun failed")
        throw error
    }
    sstpTunFd = tun
    val ended = CompletableDeferred<String>()
    connection.startDataPlane(
        java.io.FileInputStream(tun.fileDescriptor),
        java.io.FileOutputStream(tun.fileDescriptor),
    ) { reason -> ended.complete(reason) }
    DiagnosticLogger.info(
        this,
        "$eventPrefix.sstp.ready",
        "server=${chosen.endpoint} ip=${lease.address} dns=${lease.dns.joinToString(",")} mtu=${lease.mtu}",
    )
    // Home screen / notification show the SSTP row that is really carrying the phone.
    candidates.firstOrNull { it.second == chosen }?.first?.let { profile ->
        activeProfile = profile
        activeProfileShowsServer = false
        activeSubscriptionId = subscriptionId
        activeConnectionTag = profile.tag
        activeConnectionFingerprint = profile.fingerprint
        activeConnectionCountryFlag = ConnectionLocationPolicy.countryForProfile(profile)?.flag.orEmpty()
    }
    publishState(VpnState.Started)
    val reason = try {
        ended.await()
    } finally {
        closeSstpCall(connection, tun, "tunnel finished")
    }
    if (state != VpnState.Stopping && state != VpnState.Stopped) {
        throw IOException("Cat SSTP: اتصال قطع شد ($reason)")
    }
}
