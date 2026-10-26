package com.cat.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background refresh of URL subscriptions (every 6 hours, only with network) plus one-shot
 * warnings when a subscription is about to expire (≤ 3 days) or its quota is ≥ 90 % used / used up,
 * based on the `subscription-userinfo` header the panel sends.
 */
object SubscriptionAutoRefresh {
    private const val WORK = "cat-subscription-auto-refresh"
    private const val PREFS = "cat_client_sub_auto_refresh"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_RUN = "last_run"
    private const val CHANNEL = "subscription_alerts"
    private const val NOTIFY_BASE = 5300
    const val INTERVAL_HOURS = 6L
    private const val EXPIRY_WARN_SECONDS = 3L * 24 * 3600
    private const val USAGE_WARN_PERCENT = 90

    enum class Alert { ExpiresSoon, Expired, QuotaHigh, QuotaUsedUp }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun lastRunMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_RUN, 0L)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        sync(context)
    }

    fun sync(context: Context) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (!isEnabled(context)) {
            runCatching { wm.cancelUniqueWork(WORK) }
            return
        }
        runCatching {
            wm.enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<Worker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }

    /** Pure decision used by the worker and unit tests. */
    fun alertFor(usage: SubscriptionUsage?, nowEpochSeconds: Long): Alert? {
        usage ?: return null
        val expire = usage.expireEpochSeconds
        if (expire != null && expire > 0) {
            if (expire <= nowEpochSeconds) return Alert.Expired
            if (expire - nowEpochSeconds <= EXPIRY_WARN_SECONDS) return Alert.ExpiresSoon
        }
        if (usage.totalBytes > 0) {
            if (usage.usedBytes >= usage.totalBytes) return Alert.QuotaUsedUp
            if (usage.usedPercent >= USAGE_WARN_PERCENT) return Alert.QuotaHigh
        }
        return null
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            if (!isEnabled(ctx)) return Result.success()
            val manager = UserSubscriptionManager(ctx)
            val usageStore = SubscriptionUsageStore(ctx)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val nowSec = System.currentTimeMillis() / 1000L
            manager.list()
                .filter { it.sourceKind == UserSubscriptionSourceKind.Https }
                .forEachIndexed { index, item ->
                    runCatching { manager.update(item.id, item.name, item.input) }
                        .onFailure { DiagnosticLogger.warn(ctx, "subscription.autoRefresh.failed", "id=${item.id}", it) }
                    val alert = alertFor(usageStore.read(item.input), nowSec)
                    val stateKey = "alert_${item.id}"
                    val previous = prefs.getString(stateKey, null)
                    if (alert == null) {
                        if (previous != null) prefs.edit().remove(stateKey).apply()
                    } else if (alert.name != previous) {
                        prefs.edit().putString(stateKey, alert.name).apply()
                        notify(ctx, NOTIFY_BASE + (index % 50), item.name, alert, usageStore.read(item.input))
                    }
                }
            prefs.edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()
            return Result.success()
        }
    }

    private fun notify(context: Context, id: Int, name: String, alert: Alert, usage: SubscriptionUsage?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val fa = runCatching { AppLanguagePreferenceStore(context).read() == AppLanguage.Persian }.getOrDefault(true)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    if (fa) "هشدار اشتراک (انقضا / حجم)" else "Subscription alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val remaining = usage?.let { SubscriptionUsagePolicy.formatBytes(it.remainingBytes) }.orEmpty()
        val text = when (alert) {
            Alert.ExpiresSoon -> if (fa) "اشتراک «$name» کمتر از ۳ روز دیگر تمام می‌شود." else "\"$name\" expires in less than 3 days."
            Alert.Expired -> if (fa) "اشتراک «$name» منقضی شده است." else "\"$name\" has expired."
            Alert.QuotaHigh -> if (fa) "بیش از ۹۰٪ حجم «$name» مصرف شده (باقی‌مانده: $remaining)." else "Over 90% of \"$name\" is used ($remaining left)."
            Alert.QuotaUsedUp -> if (fa) "حجم «$name» تمام شده است." else "\"$name\" quota is used up."
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, id, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_tab)
            .setContentTitle(if (fa) "Cat Client — اشتراک" else "Cat Client — subscription")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
