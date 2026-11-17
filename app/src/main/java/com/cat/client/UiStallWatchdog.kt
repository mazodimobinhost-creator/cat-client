package com.cat.client

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * beta217: «the app is slow when I type or pick a config» cannot be fixed from guesses alone, so the app now notices
 * its own freezes. While the screen is in front, a low-priority thread posts a tiny no-op to the main thread every
 * 200 ms; when the main thread does not run it for [STALL_THRESHOLD_MS], the main thread's current call stack is
 * captured once and, after the freeze ends, its length and that stack are kept in a small file. The diagnostics
 * report (long-press copy / share) then shows exactly which code froze the screen and for how long.
 *
 * Nothing leaves the phone, nothing runs while the app is in the background, and no user data is recorded — only
 * class/method names of the app and the framework.
 */
internal object UiStallWatchdog {
    private const val CHECK_INTERVAL_MS = 200L
    private const val STALL_THRESHOLD_MS = 600L
    private const val MAX_ENTRIES = 15
    private const val FILE_NAME = "ui-stalls.log"

    private val beat = AtomicLong(0L)
    private val lock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var stallFile: File? = null

    fun setActive(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext ?: context
        stallFile = File(appContext.filesDir, FILE_NAME)
        active = enabled
        if (!enabled) return
        synchronized(lock) {
            if (worker?.isAlive == true) return
            worker = Thread({ loop(appContext) }, "cat-ui-watchdog").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    private fun loop(appContext: Context) {
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        while (true) {
            if (!active) {
                runCatching { Thread.sleep(1_000L) }
                continue
            }
            val expected = beat.get()
            main.post { beat.incrementAndGet() }
            runCatching { Thread.sleep(CHECK_INTERVAL_MS) }
            if (beat.get() != expected || !active) continue
            val stallStartedAt = SystemClock.uptimeMillis() - CHECK_INTERVAL_MS
            var stack: Array<StackTraceElement>? = null
            while (beat.get() == expected) {
                runCatching { Thread.sleep(50L) }
                val elapsed = SystemClock.uptimeMillis() - stallStartedAt
                if (stack == null && elapsed >= STALL_THRESHOLD_MS) {
                    stack = runCatching { mainThread.stackTrace }.getOrNull()
                }
                if (elapsed > 30_000L) break
            }
            val duration = SystemClock.uptimeMillis() - stallStartedAt
            val captured = stack
            if (duration >= STALL_THRESHOLD_MS && captured != null) {
                record(appContext, duration, captured)
            }
        }
    }

    private fun record(appContext: Context, durationMs: Long, stack: Array<StackTraceElement>) {
        val frames = describe(stack)
        val line = "${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} " +
            "ui.stall ${durationMs}ms v${BuildConfig.VERSION_NAME} :: $frames"
        runCatching {
            synchronized(lock) {
                val file = stallFile ?: File(appContext.filesDir, FILE_NAME)
                val kept = if (file.isFile) file.readLines().takeLast(MAX_ENTRIES - 1) else emptyList()
                file.writeText((kept + line).joinToString("\n") + "\n")
            }
        }
        DiagnosticLogger.warn(appContext, "ui.stall", "durationMs=$durationMs at=$frames")
    }

    /** App frames first (they say *what* froze), then the top framework frames (they say *how*). */
    internal fun describe(stack: Array<StackTraceElement>): String {
        fun StackTraceElement.short(): String =
            "${className.substringAfterLast('.')}.$methodName:${lineNumber.coerceAtLeast(0)}"
        val appFrames = stack.filter { it.className.startsWith("com.cat.client") }.take(6).map { it.short() }
        val topFrames = stack.take(4).map { it.short() }
        return buildString {
            append(if (appFrames.isEmpty()) "(no app frame)" else appFrames.joinToString(" < "))
            append(" | top: ")
            append(topFrames.joinToString(" < "))
        }
    }

    /** Recent freezes for the diagnostics report; empty when the screen never froze. */
    fun report(context: Context): String = runCatching {
        val file = File((context.applicationContext ?: context).filesDir, FILE_NAME)
        synchronized(lock) { if (file.isFile) file.readText().trim() else "" }
    }.getOrDefault("")
}
