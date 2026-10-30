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
import com.cat.client.CatClientVpnService.Companion.CHANNEL_ID
import com.cat.client.CatClientVpnService.Companion.NOTIFICATION_ID
import com.cat.client.CatClientVpnService.Companion.NOTIFICATION_TRAFFIC_INTERVAL_MS
import android.content.Context

// Foreground notification: channel, content, traffic updates, actions.
// Moved out of CatClientVpnService.kt unchanged (beta222 service split); see docs/CODEMAP.md.

internal fun CatClientVpnService.createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_vpn),
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
}

/** Starts a light-weight sampler so the foreground notification shows live tunnel traffic. */
internal fun CatClientVpnService.startNotificationTrafficUpdates() {
    if (notificationTrafficJob?.isActive == true || state != VpnState.Started) return
    notificationLastRxBytes = TrafficStats.UNSUPPORTED.toLong()
    notificationLastTxBytes = TrafficStats.UNSUPPORTED.toLong()
    notificationLastSampleElapsedMs = 0L
    notificationDownloadBps = 0L
    notificationUploadBps = 0L
    notificationTrafficJob = scope.launch(Dispatchers.Default) {
        while (isActive && state == VpnState.Started) {
            delay(NOTIFICATION_TRAFFIC_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            val tx = TrafficStats.getUidTxBytes(android.os.Process.myUid())
            val previousAt = notificationLastSampleElapsedMs
            if (
                rx != TrafficStats.UNSUPPORTED.toLong() &&
                tx != TrafficStats.UNSUPPORTED.toLong() &&
                previousAt > 0L &&
                now > previousAt
            ) {
                val elapsedMs = now - previousAt
                notificationDownloadBps = bytesPerSecond(rx, notificationLastRxBytes, elapsedMs)
                notificationUploadBps = bytesPerSecond(tx, notificationLastTxBytes, elapsedMs)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, serviceNotification(connectedNotificationText()))
            }
            notificationLastRxBytes = rx
            notificationLastTxBytes = tx
            notificationLastSampleElapsedMs = now
        }
    }
    notificationTrafficJob?.invokeOnCompletion {
        if (notificationTrafficJob?.isCompleted == true) notificationTrafficJob = null
    }
}

internal fun CatClientVpnService.bytesPerSecond(currentBytes: Long, previousBytes: Long, elapsedMs: Long): Long {
    if (
        elapsedMs <= 0L ||
        previousBytes == TrafficStats.UNSUPPORTED.toLong() ||
        currentBytes == TrafficStats.UNSUPPORTED.toLong() ||
        currentBytes < previousBytes
    ) return 0L
    return ((currentBytes - previousBytes) * 1_000L / elapsedMs).coerceAtLeast(0L)
}

internal fun CatClientVpnService.stopNotificationTrafficUpdates() {
    notificationTrafficJob?.cancel()
    notificationTrafficJob = null
    notificationLastRxBytes = TrafficStats.UNSUPPORTED.toLong()
    notificationLastTxBytes = TrafficStats.UNSUPPORTED.toLong()
    notificationLastSampleElapsedMs = 0L
    notificationDownloadBps = 0L
    notificationUploadBps = 0L
}

internal fun CatClientVpnService.notificationContent(content: String): String {
    if (state != VpnState.Started) return content
    return getString(
        R.string.notification_connected_traffic,
        content,
        formatTransferSpeed(notificationDownloadBps),
        formatTransferSpeed(notificationUploadBps),
    )
}

internal fun CatClientVpnService.serviceNotification(content: String): android.app.Notification {
    val pendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(notificationContent(content))
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    VpnNotificationActionPolicy.actionsFor(state, disconnectAllowed = !alwaysOnActive).forEach { action ->
        builder.addAction(
            NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                notificationActionTitle(action),
                serviceActionPendingIntent(action),
            ).build(),
        )
    }

    return builder.build()
}

internal fun CatClientVpnService.connectedNotificationText(): String =
    getString(connectedNotificationTextRes(activeSubscriptionId))

internal fun CatClientVpnService.serviceActionPendingIntent(action: String): PendingIntent {
    val requestCode = when (action) {
        Actions.DISCONNECT -> 1
        Actions.RECONNECT -> 2
        else -> 3
    }
    return PendingIntent.getService(
        this,
        requestCode,
        Intent(this, CatClientVpnService::class.java)
            .setAction(action)
            .putExtra(Actions.EXTRA_APP_INITIATED, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

internal fun CatClientVpnService.notificationActionTitle(action: String): String {
    return when (action) {
        Actions.DISCONNECT -> getString(R.string.notification_action_disconnect)
        Actions.RECONNECT -> getString(R.string.notification_action_reconnect)
        else -> action
    }
}

internal fun CatClientVpnService.stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }
}
