package com.cat.client

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * ZedSecure SpeedTestScreen, rebuilt with Views: 270° gauge with the live value, phase label,
 * Ping/Jitter and Download/Upload tiles, Start/Stop button, route chip and data-used footer.
 * Measurements go through the mihomo mixed proxy when connected (the exact tunnel path),
 * otherwise over the direct connection — like Zed, against speed.cloudflare.com.
 */
class SpeedTestPage(
    private val activity: Activity,
    private val palette: CatClientPalette,
    private val scope: CoroutineScope,
    private val connected: Boolean,
) {
    enum class Phase { Idle, Ping, Download, Upload, Done, Stopped, Error }

    private val ctx: Context = activity
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private var phase = Phase.Idle
    private var pingMs: Double? = null
    private var jitterMs: Double? = null
    private var downloadMbps: Double? = null
    private var uploadMbps: Double? = null
    private var liveMbps = 0.0
    private var bytesUsed = 0L
    private var job: Job? = null

    private lateinit var gauge: GaugeView
    private lateinit var centerValue: TextView
    private lateinit var centerUnit: TextView
    private lateinit var phaseText: TextView
    private lateinit var pingTile: TextView
    private lateinit var jitterTile: TextView
    private lateinit var downTile: TextView
    private lateinit var upTile: TextView
    private lateinit var dataUsed: TextView
    private lateinit var action: MaterialButton

    fun show() {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(build())
        dialog.window?.setBackgroundDrawable(ColorDrawable(palette.background))
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
    }

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = palette.textPrimary) = TextView(ctx).apply {
        text = value; textSize = size
        typeface = if (bold) CatClientBodyBoldTypeface else CatClientBodyTypeface
        setTextColor(color); includeFontPadding = false
    }

    private fun statTile(label: String, unit: String): Pair<LinearLayout, TextView> {
        val value = text("—", 22f, bold = true).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR }
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat(); setColor(palette.surfaceElevated1) }
            addView(text(label, 12f, color = palette.textSecondary))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                addView(value)
                addView(text(unit, 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); bottomMargin = dp(3) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        return tile to value
    }

    private fun build(): View {
        val s = ctx.getString(R.string.speedtest_ms); val mbps = ctx.getString(R.string.speedtest_mbps)
        gauge = GaugeView(ctx, palette)
        centerValue = text("—", 44f, bold = true).apply { typeface = CatClientDisplayTypeface; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        centerUnit = text(mbps, 15f, color = palette.textSecondary)
        phaseText = text(ctx.getString(R.string.speedtest_phase_idle), 13f, bold = true, color = palette.outline)
        val gaugeFrame = FrameLayout(ctx).apply {
            addView(gauge, FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(centerValue); addView(centerUnit)
                addView(phaseText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val (pingT, pingV) = statTile(ctx.getString(R.string.speedtest_ping), s); pingTile = pingV
        val (jitT, jitV) = statTile(ctx.getString(R.string.speedtest_jitter), s); jitterTile = jitV
        val (downT, downV) = statTile(ctx.getString(R.string.speedtest_download), mbps); downTile = downV
        val (upT, upV) = statTile(ctx.getString(R.string.speedtest_upload), mbps); upTile = upV
        fun row(a: View, b: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        }
        val routeChip = text(
            ctx.getString(if (connected) R.string.speedtest_via_tunnel else R.string.speedtest_via_direct), 12f, bold = true,
            color = if (connected) palette.onAccent else palette.textSecondary,
        ).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat(); setColor(if (connected) palette.teal else palette.surfaceElevated2) }
        }
        action = MaterialButton(ctx).apply {
            setText(R.string.speedtest_start); isAllCaps = false; textSize = 16f; typeface = CatClientBodyBoldTypeface
            cornerRadius = dp(28); insetTop = 0; insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(palette.teal); setTextColor(palette.onAccent)
            setOnClickListener { if (job?.isActive == true) stop() else start() }
        }
        dataUsed = text(ctx.getString(R.string.speedtest_data_note, MAX_RUN_MB), 12f, color = palette.textSecondary).apply { gravity = Gravity.CENTER }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(28), dp(20), dp(24))
            addView(text(ctx.getString(R.string.speedtest_title), 28f).apply { typeface = CatClientDisplayTypeface })
            addView(text(ctx.getString(R.string.speedtest_subtitle), 14f, color = palette.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(routeChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
            if (!connected) addView(text(ctx.getString(R.string.speedtest_not_connected), 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(gaugeFrame, LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18) })
            addView(row(pingT, jitT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            addView(row(downT, upT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(action, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(22) })
            addView(dataUsed, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        return ScrollView(ctx).apply { addView(column); isFillViewport = true }
    }

    private fun fmt(v: Double?): String = when {
        v == null -> "—"
        v >= 100 -> String.format(Locale.US, "%.0f", v)
        v >= 10 -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.2f", v)
    }

    private fun render(progress: Float) {
        val ms = ctx.getString(R.string.speedtest_ms); val mbps = ctx.getString(R.string.speedtest_mbps)
        centerUnit.text = if (phase == Phase.Ping) ms else mbps
        centerValue.text = when (phase) {
            Phase.Ping -> fmt(pingMs)
            Phase.Download, Phase.Upload -> fmt(liveMbps)
            Phase.Done, Phase.Stopped -> fmt(downloadMbps ?: pingMs)
            else -> "—"
        }
        val color = when (phase) {
            Phase.Ping -> ZedBlobView.ZED_HOT_PINK
            Phase.Download, Phase.Done -> palette.teal
            Phase.Upload -> ZedBlobView.ZED_CYAN
            Phase.Error -> palette.red
            else -> palette.outline
        }
        phaseText.setText(
            when (phase) {
                Phase.Idle -> R.string.speedtest_phase_idle
                Phase.Ping -> R.string.speedtest_phase_ping
                Phase.Download -> R.string.speedtest_phase_download
                Phase.Upload -> R.string.speedtest_phase_upload
                Phase.Done -> R.string.speedtest_phase_done
                Phase.Stopped -> R.string.speedtest_phase_stopped
                Phase.Error -> R.string.speedtest_phase_error
            },
        )
        phaseText.setTextColor(color)
        gauge.animateTo(progress, color)
        pingTile.text = fmt(pingMs); jitterTile.text = fmt(jitterMs)
        downTile.text = fmt(downloadMbps); upTile.text = fmt(uploadMbps)
        val running = job?.isActive == true
        action.setText(if (running) R.string.speedtest_stop else if (phase == Phase.Done || phase == Phase.Stopped) R.string.speedtest_restart else R.string.speedtest_start)
        action.backgroundTintList = android.content.res.ColorStateList.valueOf(if (running) palette.surfaceElevated2 else palette.teal)
        action.setTextColor(if (running) palette.textPrimary else palette.onAccent)
        dataUsed.text = if (bytesUsed > 0) ctx.getString(R.string.speedtest_data_used, String.format(Locale.US, "%.1f", bytesUsed / 1e6)) else ctx.getString(R.string.speedtest_data_note, MAX_RUN_MB)
    }

    private fun stop() {
        job?.cancel(); job = null
        phase = Phase.Stopped; liveMbps = 0.0
        render(1f)
    }

    private fun start() {
        pingMs = null; jitterMs = null; downloadMbps = null; uploadMbps = null; liveMbps = 0.0; bytesUsed = 0L
        phase = Phase.Ping
        render(0f)
        val proxy = if (connected) Proxy(Proxy.Type.HTTP, InetSocketAddress(MihomoRuntimeDefaults.CONTROLLER_HOST, MihomoRuntimeDefaults.MIXED_PORT)) else Proxy.NO_PROXY
        job = scope.launch {
            try {
                // ---- latency: 12 tiny requests, median + jitter (mean abs successive difference)
                val samples = ArrayList<Double>()
                repeat(12) { i ->
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val ok = withContext(Dispatchers.IO) { request(proxy, "${DOWN_URL}0", null) }
                    if (ok >= 0) samples += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    pingMs = samples.sorted().getOrNull(samples.size / 2)
                    render((i + 1) / 12f)
                }
                if (samples.isEmpty()) throw IllegalStateException("no reply")
                jitterMs = if (samples.size > 1) samples.zipWithNext { a, b -> abs(a - b) }.average() else 0.0
                // ---- download: 10 MB chunks for ~8 s, live Mbps from a 250 ms sampler
                phase = Phase.Download; liveMbps = 0.0; render(0f)
                downloadMbps = throughput(proxy, upload = false, durationMs = 8_000L)
                // ---- upload: 2 MB POST bodies for ~6 s
                phase = Phase.Upload; liveMbps = 0.0; render(0f)
                uploadMbps = throughput(proxy, upload = true, durationMs = 6_000L)
                phase = Phase.Done; liveMbps = 0.0
                render(1f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                phase = Phase.Error
                render(1f)
            } finally {
                job = null
                render(if (phase == Phase.Download || phase == Phase.Upload) 0f else 1f)
            }
        }
    }

    /** Runs transfers until [durationMs] or the data budget; returns the 90th-percentile Mbps. */
    private suspend fun throughput(proxy: Proxy, upload: Boolean, durationMs: Long): Double {
        val start = SystemClock.elapsedRealtime()
        var transferred = 0L
        val samples = ArrayList<Double>()
        val worker = scope.launch(Dispatchers.IO) {
            while (isActive && SystemClock.elapsedRealtime() - start < durationMs && bytesUsed < MAX_RUN_MB * 1_000_000L) {
                val n = if (upload) request(proxy, UP_URL, ByteArray(2_000_000)) else request(proxy, "${DOWN_URL}10000000", null)
                if (n < 0) break
                transferred += n; bytesUsed += n
            }
        }
        var lastBytes = 0L; var lastAt = start
        while (worker.isActive) {
            delay(250L)
            val now = SystemClock.elapsedRealtime(); val b = transferred
            val dt = (now - lastAt) / 1000.0
            if (dt > 0 && b > lastBytes) {
                val mbps = (b - lastBytes) * 8 / 1e6 / dt
                samples += mbps; liveMbps = mbps
            }
            lastBytes = b; lastAt = now
            render(((now - start).toFloat() / durationMs).coerceIn(0f, 1f))
        }
        worker.cancel()
        return if (samples.isEmpty()) {
            val total = (SystemClock.elapsedRealtime() - start) / 1000.0
            if (total > 0) transferred * 8 / 1e6 / total else 0.0
        } else samples.sorted()[((samples.size - 1) * 0.9).toInt()]
    }

    /** Returns bytes moved (body size for uploads, body length for downloads) or -1 on failure. */
    private fun request(proxy: Proxy, url: String, body: ByteArray?): Long = runCatching {
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 20_000; conn.instanceFollowRedirects = false
        if (body != null) {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        var n = 0L
        if (body == null) {
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { ins -> while (true) { val r = ins.read(buf); if (r < 0) break; n += r } }
        } else runCatching { conn.inputStream.close() }
        conn.disconnect()
        if (code in 200..399) (if (body != null) body.size.toLong() else n) else -1L
    }.getOrDefault(-1L)

    /** Zed Gauge: 270° track from 135°, animated sweep (500 ms) and colour. */
    class GaugeView(context: Context, private val palette: CatClientPalette) : View(context) {
        private var sweep = 0f
        private var color = palette.outline
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val rect = RectF()
        private var sweepAnim: ValueAnimator? = null
        private var colorAnim: ValueAnimator? = null

        fun animateTo(progress: Float, tint: Int) {
            sweepAnim?.cancel()
            sweepAnim = ValueAnimator.ofFloat(sweep, progress.coerceIn(0f, 1f)).apply { duration = 500L; addUpdateListener { sweep = it.animatedValue as Float; invalidate() }; start() }
            if (tint != color) {
                colorAnim?.cancel()
                colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), color, tint).apply { duration = 400L; addUpdateListener { color = it.animatedValue as Int; invalidate() }; start() }
            }
        }

        override fun onDraw(canvas: Canvas) {
            val pad = 10f * resources.displayMetrics.density
            val size = minOf(width, height) - pad * 2
            val strokeW = size * 0.07f
            paint.strokeWidth = strokeW
            rect.set((width - size) / 2f + strokeW / 2f, (height - size) / 2f + strokeW / 2f, (width + size) / 2f - strokeW / 2f, (height + size) / 2f - strokeW / 2f)
            paint.color = palette.surfaceVariant
            canvas.drawArc(rect, 135f, 270f, false, paint)
            paint.color = color
            canvas.drawArc(rect, 135f, 270f * sweep, false, paint)
        }
    }

    private companion object {
        const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes="
        const val UP_URL = "https://speed.cloudflare.com/__up"
        const val MAX_RUN_MB = 150
    }
}
