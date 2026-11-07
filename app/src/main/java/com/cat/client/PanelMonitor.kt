package com.cat.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Outward panel monitor (the app half — the worker's own cron is the inward
 * half): every 30 minutes it asks /health of the user's deployed panel and
 * notifies exactly once when the state changes to DOWN/BLOCKED (e.g. the
 * Error 1101 suspension). While the app is not installed nothing can watch
 * the panel from outside; this closes that gap even when the app is closed.
 */
object PanelMonitor {
    private const val WORK = "cat-panel-monitor"
    private const val PREFS = "cat_client_theme"
    private const val CHANNEL = "panel_monitor"
    private const val NOTIFY_ID = 4242

    fun target(context: Context): String? =
        runCatching { PanelDeploymentStore(context).deployments().firstOrNull()?.workerUrl }.getOrNull()

    /** Keeps the 30-minute periodic check in sync with the deployment state. */
    fun sync(context: Context) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val url = target(context)
        if (url.isNullOrBlank()) {
            runCatching { wm.cancelUniqueWork(WORK) }
            return
        }
        runCatching {
            wm.enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Worker>(30, TimeUnit.MINUTES).build(),
            )
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val url = target(applicationContext) ?: return Result.success()
            val health = withContext(Dispatchers.IO) {
                runCatching { CloudflareWorker.checkPanelHealth(url) }
                    .getOrElse { CloudflareWorker.PanelHealth("DOWN", it.message ?: "network error") }
            }
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = prefs.getString("panel_monitor_state", "HEALTHY") ?: "HEALTHY"
            if (health.state != prev) {
                prefs.edit()
                    .putString("panel_monitor_state", health.state)
                    .putLong("panel_monitor_at", System.currentTimeMillis())
                    .apply()
                if (health.state != "HEALTHY") notifyProblem(applicationContext, health)
            }
            return Result.success()
        }
    }

    private fun notifyProblem(context: Context, health: CloudflareWorker.PanelHealth) {
        val blocked = health.state == "BLOCKED"
        val title = if (blocked) context.getString(R.string.pm_title_1101) else context.getString(R.string.pm_title)
        val detail = if (blocked) context.getString(R.string.pm_detail_1101) else health.detail
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.pm_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_tab)
            .setContentTitle(title)
            .setContentText(detail)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification) }
    }
}
