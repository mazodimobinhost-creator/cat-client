package com.cat.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Cat Client crash watchdog — turns silent launch failures into visible,
 * copyable evidence.
 *
 * A user's app opened and closed instantly with no stack trace and no way to
 * attach a debugger. R8 keeps, usage.txt guards and emulator smoke tests all
 * come back clean, so the remaining gap was *getting the real exception off the
 * phone*. This watchdog closes it:
 *
 *  - installs an uncaught-exception handler as the very first thing in
 *    Application.onCreate (before any activity, service or provider), so a Java
 *    crash during startup is fully recorded;
 *  - writes the full stack (+ version/fingerprint) to filesDir/cat_crash_last.txt
 *    and appends a short line to the diagnostics log;
 *  - posts a notification carrying the stack (BigText) — a screenshot of that
 *    notification is all a user needs to send, no adb required. It is attempted
 *    from the dying process AND again on every following launch until the crash
 *    has been announced once (tracked by file mtime), because a dying process is
 *    not a reliable moment to post anything;
 *  - detects NON-Java deaths (native SIGSEGV / low-memory kills) with a session
 *    marker: every start writes cat_session_open, reaching onResume deletes it;
 *    if the next start finds a leftover marker and no crash file, the previous
 *    launch died without a Java exception and that is surfaced too.
 *
 * Everything is wrapped in runCatching: a watchdog that itself crashes would be
 * worse than no watchdog.
 */
object CrashWatch {
    private const val CRASH_FILE = "cat_crash_last.txt"
    private const val SESSION_FILE = "cat_session_open"
    private const val ANNOUNCED_FILE = "cat_crash_announced"
    private const val CHANNEL = "crash_report"
    private const val NOTIFY_ID = 4243
    private const val MAX_NOTIFY_CHARS = 1500

    @Volatile
    private var installed = false

    /**
     * Installs the uncaught handler as early as Android allows: called from
     * EarlyCrashProvider.onCreate, which runs before Application.onCreate.
     * Idempotent and never throws.
     */
    fun installEarly(context: Context) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            if (installed) return
            installed = true
        }
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                // Evidence first, in every channel available, then let the
                // process die the normal way (no swallowing: a zombie process
                // helps nobody).
                runCatching { persistCrash(app, thread, error) }
                runCatching { announceCrash(app, force = true) }
                runCatching { previous?.uncaughtException(thread, error) }
            }
            markSessionOpen(app)
        }
    }

    /** Call first thing in Application.onCreate. Never throws. */
    fun install(app: Application) {
        runCatching {
            installEarly(app)
            announceCrash(app, force = false)
            checkPreviousSession(app)
            markSessionOpen(app)
        }
    }

    /** Call from MainActivity.onResume: the app reached an interactive state. */
    fun markHealthy(context: Context) {
        runCatching { sessionFile(context).delete() }
    }

    /** Announces the recorded crash once per recorded stack (mtime-tracked). */
    private fun announceCrash(context: Context, force: Boolean) {
        val crash = crashFile(context)
        if (!crash.isFile || crash.length() == 0L) return
        if (!force) {
            val announced = runCatching { announcedFile(context).readText().trim().toLongOrNull() }.getOrNull()
            if (announced != null && announced == crash.lastModified()) return
        }
        publish(context, context.getString(R.string.crash_title), readCrash(context))
        runCatching { announcedFile(context).writeText(crash.lastModified().toString()) }
    }

    private fun checkPreviousSession(context: Context) {
        val marker = sessionFile(context)
        // A leftover crash file already carries richer evidence than the marker.
        val crash = crashFile(context)
        if (crash.isFile && crash.length() > 0L) return
        if (!marker.isFile) return
        marker.delete()
        publish(context, context.getString(R.string.crash_native_title), context.getString(R.string.crash_native_detail))
    }

    private fun markSessionOpen(context: Context) {
        runCatching { sessionFile(context).writeText(System.currentTimeMillis().toString()) }
    }

    private fun persistCrash(context: Context, thread: Thread, error: Throwable) {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        val text = buildString {
            append("thread=").append(thread.name).append(' ')
            append("version=").append(BuildConfig.VERSION_NAME).append(' ')
            append("code=").append(BuildConfig.VERSION_CODE).append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) append(Build.FINGERPRINT).append('\n')
            append(writer.toString())
        }
        val trimmed = text.take(20_000)
        runCatching { crashFile(context).writeText(trimmed) }
        runCatching { DiagnosticLogger.error(context, "crash.uncaught", trimmed.take(400)) }
        exportCrash(context, trimmed)
    }

    private fun readCrash(context: Context): String =
        runCatching { crashFile(context).readText() }.getOrNull()?.take(20_000).orEmpty()

    /**
     * Copies the crash to the phone's Downloads folder so the user can send the
     * file WITHOUT notification permission and WITHOUT adb — the report must
     * survive every device configuration. MediaStore (API 29+) needs no
     * permission; older releases keep file + notification only.
     */
    private fun exportCrash(context: Context, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val resolver = context.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val name = "catclient-crash.txt"
            runCatching {
                resolver.delete(
                    collection,
                    "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(name),
                )
            }
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
            }
            val uri = resolver.insert(collection, values) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }

    private fun crashFile(context: Context) = File(context.filesDir, CRASH_FILE)
    private fun sessionFile(context: Context) = File(context.filesDir, SESSION_FILE)
    private fun announcedFile(context: Context) = File(context.filesDir, ANNOUNCED_FILE)

    /**
     * Visible-last-resort UI: when the normal shell cannot be built the app must
     * still show *something* instead of closing silently.
     */
    fun fallbackView(context: Context, message: String): android.widget.TextView =
        android.widget.TextView(context).apply {
            text = context.getString(R.string.crash_startup_failed, message)
            setPadding(48, 96, 48, 48)
            textSize = 15f
            isVerticalScrollBarEnabled = true
        }

    private fun publish(context: Context, title: String, body: String) {
        val text = body.ifBlank { "no details recorded" }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.crash_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // permission not granted — the file above still holds the stack
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_tab)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(MAX_NOTIFY_CHARS)))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification) }
    }
}
