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
import com.cat.client.CatClientVpnService.Companion.DPI_BYPASS_PORT_POLL_INTERVAL_MS
import com.cat.client.CatClientVpnService.Companion.DPI_BYPASS_START_TIMEOUT_MS
import com.cat.client.CatClientVpnService.Companion.DPI_BYPASS_STOP_TIMEOUT_MS
import android.content.Context

// Fragmenting proxy (ByeByeDPI) start/stop for the Cat core.
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

internal suspend fun CatClientVpnService.startDpiBypassProxy(port: Int): Int {
    stopDpiBypassProxy()

    val protectCallback = object : TunInterface {
        override fun protect(fd: Int) {
            check(this@startDpiBypassProxy.protect(fd)) {
                "Android refused to protect ByeByeDPI socket $fd"
            }
        }

        override fun resolverProcess(protocol: Int, source: String, target: String, uid: Int): String = ""
    }
    // The preset is resolved here, at connect time, because both inputs move: the
    // carrier can change (Wi-Fi ↔ mobile, roaming) and the owner can retune the panel
    // between two connects. `auto` = «decide for me», and a manual pick still wins.
    val carrier = CarrierDetector.detect(this)
    val preset = DpiFragmentPreset.resolve(
        storedId = dpiBypassPreferenceStore.presetId(),
        carrier = carrier,
        tune = panelTuneStore.read(),
    )
    DiagnosticLogger.info(
        this,
        "dpiBypass.preset",
        "carrier=${carrier.id} raw=${CarrierDetector.rawOperatorCode(this)} " +
            "stored=${dpiBypassPreferenceStore.presetId()} applied=${preset.id}",
    )
    val job = scope.launch(Dispatchers.IO) {
        val result = runCatching {
            ByeDpiProxy.start(port, protectCallback, preset)
        }
        result
            .onSuccess { exitCode ->
                if (exitCode != 0 && activeDpiBypassPort == port) {
                    DiagnosticLogger.warn(
                        this@startDpiBypassProxy,
                        "dpiBypass.proxy.exited",
                        "port=$port code=$exitCode",
                    )
                }
            }
            .onFailure { error ->
                if (activeDpiBypassPort == port) {
                    DiagnosticLogger.warn(
                        this@startDpiBypassProxy,
                        "dpiBypass.proxy.failed",
                        "port=$port",
                        error,
                    )
                }
            }
    }
    dpiBypassJob = job
    activeDpiBypassPort = port

    if (!waitForDpiBypassPort(port, job)) {
        stopDpiBypassProxy()
        throw IOException("ByeByeDPI proxy did not start on ${DpiBypassDefaults.PROXY_HOST}:$port")
    }

    val healthStatus = withContext(Dispatchers.IO) {
        MihomoRuntimeDefaults.HEALTH_URLS.firstNotNullOfOrNull { url ->
            runCatching {
                MihomoRuntimeHealth.httpStatusThroughSocksProxy(
                    port = port,
                    url = url,
                    timeoutMs = TlsIntegrityPolicy.PROBE_TIMEOUT_MS,
                )
            }.getOrNull()?.takeIf { code -> code == 204 || code in 200..399 }
        }
    } ?: throw IOException(
        "ByeByeDPI proxy could not reach any connectivity-check endpoint on ${DpiBypassDefaults.PROXY_HOST}:$port",
    )

    DiagnosticLogger.info(
        this,
        "dpiBypass.proxy.started",
        "port=$port health=$healthStatus",
    )
    return port
}

internal suspend fun CatClientVpnService.waitForDpiBypassPort(port: Int, job: Job): Boolean {
    return withTimeoutOrNull(DPI_BYPASS_START_TIMEOUT_MS) {
        while (job.isActive) {
            if (!DpiBypassPort.canBind(port)) return@withTimeoutOrNull true
            delay(DPI_BYPASS_PORT_POLL_INTERVAL_MS)
        }
        false
    } == true
}

internal suspend fun CatClientVpnService.stopDpiBypassProxy() {
    val job = dpiBypassJob ?: return
    val port = activeDpiBypassPort
    dpiBypassJob = null
    activeDpiBypassPort = null

    withContext(Dispatchers.IO) {
        runCatching { ByeDpiProxy.stop() }
            .onFailure { DiagnosticLogger.warn(this@stopDpiBypassProxy, "dpiBypass.proxy.stop.failed", error = it) }
    }
    val stopped = withTimeoutOrNull(DPI_BYPASS_STOP_TIMEOUT_MS) {
        job.join()
        true
    } == true
    if (!stopped) {
        job.cancel(CancellationException("ByeByeDPI proxy stop timed out"))
        DiagnosticLogger.warn(this, "dpiBypass.proxy.stop.timeout", "port=${port ?: 0}")
        return
    }
    DiagnosticLogger.info(this, "dpiBypass.proxy.stopped", "port=${port ?: 0}")
}
