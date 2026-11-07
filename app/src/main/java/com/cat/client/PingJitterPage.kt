package com.cat.client

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⏱ Precise ping & jitter for ONE exact ip:port: «my line» (bare TCP round trips) next to
 * «through the worker» (warm TLS, request → first byte), with loss, min/median/p90, the
 * Cloudflare colo and a plain-words verdict. Engine: [PingJitterMeter].
 */
class PingJitterPage(
    private val activity: Activity,
    private val palette: CatClientPalette,
    private val scope: CoroutineScope,
    private val farsi: Boolean,
    private val initialTarget: String?,
    private val initialWorkerHost: String?,
    private val vpnOn: Boolean,
) {
    private val ctx: Context = activity
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun t(fa: String, en: String) = if (farsi) fa else en

    private var job: Job? = null
    private var samples = 20
    private var lastReport: PingJitterMeter.Report? = null

    private lateinit var targetInput: EditText
    private lateinit var workerInput: EditText
    private lateinit var phaseText: TextView
    private lateinit var lineCard: TextView
    private lateinit var workerCard: TextView
    private lateinit var verdictText: TextView
    private lateinit var action: MaterialButton
    private lateinit var copyButton: MaterialButton
    private val sampleChips = mutableListOf<Pair<TextView, Int>>()

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
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat(); setColor(palette.surfaceElevated1)
    }

    private fun input(hint: String, value: String?): EditText = EditText(ctx).apply {
        this.hint = hint
        setText(value.orEmpty())
        textSize = 14f
        typeface = CatClientDataTypeface
        setTextColor(palette.textPrimary)
        setHintTextColor(palette.textTertiary)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = card()
    }

    private fun build(): View {
        targetInput = input(t("آی‌پی:پورت (مثلاً یکی از نتایج اسکنر)", "IP:port (e.g. a scanner result)"), initialTarget)
        workerInput = input(t("دامنهٔ ورکر (خالی = لبهٔ کلودفلر)", "Worker domain (empty = Cloudflare edge)"), initialWorkerHost)
        phaseText = text(t("آماده", "Ready"), 13f, bold = true, color = palette.outline)
        lineCard = text("🏠 " + t("خط من", "My line") + "\n—", 14f).apply { setPadding(dp(16), dp(14), dp(16), dp(14)); background = card(); typeface = CatClientDataTypeface }
        workerCard = text("☁️ " + t("از راه ورکر", "Through the worker") + "\n—", 14f).apply { setPadding(dp(16), dp(14), dp(16), dp(14)); background = card(); typeface = CatClientDataTypeface }
        verdictText = text("", 16f, bold = true).apply { setPadding(dp(16), dp(14), dp(16), dp(14)); background = card(); visibility = View.GONE }
        action = MaterialButton(ctx).apply {
            text = t("شروع سنجش", "Start"); isAllCaps = false; textSize = 16f; typeface = CatClientBodyBoldTypeface
            cornerRadius = dp(28); insetTop = 0; insetBottom = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(palette.teal); setTextColor(palette.onAccent)
            setOnClickListener { if (job?.isActive == true) stop() else start() }
        }
        copyButton = MaterialButton(ctx).apply {
            text = t("📋 کپی نتیجه", "📋 Copy result"); isAllCaps = false; textSize = 13f; typeface = CatClientBodyBoldTypeface
            cornerRadius = dp(18); insetTop = 0; insetBottom = 0; isEnabled = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(palette.surfaceElevated2); setTextColor(palette.textPrimary)
            setOnClickListener { copyReport() }
        }
        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(t("تعداد نمونه:", "Samples:"), 13f, color = palette.textSecondary), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            listOf(10, 20, 40).forEach { n ->
                val chip = text(n.toString(), 13f, bold = true).apply {
                    setPadding(dp(16), dp(8), dp(16), dp(8)); isClickable = true; isFocusable = true
                    setOnClickListener { samples = n; paintChips() }
                }
                sampleChips += chip to n
                addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
        }
        paintChips()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(28), dp(20), dp(24))
            addView(text(t("⏱ پینگ و جیتر دقیق", "⏱ Precise ping & jitter"), 26f).apply { typeface = CatClientDisplayTypeface })
            addView(
                text(
                    t(
                        "دو عدد کنار هم: «خط من» یعنی رفت‌وبرگشت خالص اینترنت تو تا این آی‌پی. «از راه ورکر» یعنی همان مسیر به‌اضافهٔ کاری که کلودفلر و ورکرت انجام می‌دهند — همانی که موقع وصل بودن حس می‌کنی. جیتر یعنی عدد چقدر بالا و پایین می‌پرد؛ هرچه کمتر، تماس و بازی روان‌تر.",
                        "Two numbers side by side: «my line» is the bare round trip from your internet to this IP. «Through the worker» is that plus what Cloudflare and your worker add — what you feel once connected. Jitter is how much the number jumps; lower means smoother calls and games.",
                    ),
                    13f, color = palette.textSecondary,
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
            if (vpnOn) {
                addView(
                    text(
                        t("⚠️ وی‌پی‌ان روشن است؛ ممکن است اندازه‌گیری از داخل تونل رد شود. برای عدد واقعی «خط من» اول قطع کن.",
                            "⚠️ The VPN is on; the measurement may go through the tunnel. Disconnect first for a true «my line» number."),
                        12.5f, color = palette.amber,
                    ),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
                )
            }
            addView(targetInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(workerInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(chipRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(action, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(16) })
            addView(phaseText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(verdictText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(lineCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(workerCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(copyButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            addView(
                text(
                    t(
                        "راهنما: زیر ۱۵۰ میلی‌ثانیه با جیتر زیر ۲۰ = عالی برای بازی و تماس تصویری. زیر ۳۰۰ = تماس و یوتیوب خوب است. زیر ۶۰۰ = وب‌گردی و پیام‌رسان. «گم‌شده» بالای ۵٪ یعنی قطع‌ووصل. اگر «خط من» خوب است ولی «ورکر» بد، مشکل از مسیر کلودفلر/ورکر است، نه اینترنت تو — آی‌پی یا پورت دیگری امتحان کن.",
                        "Guide: under 150 ms with jitter under 20 = great for games and video calls. Under 300 = calls and YouTube are fine. Under 600 = browsing and messaging. Over 5% lost means it drops. If «my line» is good but «worker» is bad, the problem is the Cloudflare/worker path, not your internet — try another IP or port.",
                    ),
                    12.5f, color = palette.textSecondary,
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
            )
        }
        return ScrollView(ctx).apply { addView(column); isFillViewport = true }
    }

    private fun paintChips() {
        sampleChips.forEach { (chip, n) ->
            val on = n == samples
            chip.setTextColor(if (on) palette.onAccent else palette.textPrimary)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(16).toFloat()
                setColor(if (on) palette.teal else palette.surfaceElevated2)
            }
        }
    }

    private fun stop() {
        job?.cancel(); job = null
        phaseText.text = t("متوقف شد", "Stopped")
        action.text = t("شروع سنجش", "Start")
    }

    private fun start() {
        val target = PingJitterMeter.parseTarget(targetInput.text?.toString().orEmpty())
        if (target == null) {
            phaseText.text = t("❌ آی‌پی:پورت درست نیست. مثل نتیجه‌های اسکنر بنویس: آی‌پی، دو‌نقطه، پورت.", "❌ Not a valid IP:port. Write it like a scanner result: IP, colon, port.")
            phaseText.setTextColor(palette.red)
            return
        }
        val worker = workerInput.text?.toString()?.trim()?.lowercase()?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')?.takeIf { it.contains('.') }
        val n = samples
        action.text = t("توقف", "Stop")
        verdictText.visibility = View.GONE
        copyButton.isEnabled = false
        phaseText.setTextColor(palette.outline)
        job = scope.launch {
            phaseText.text = t("🏠 سنجش خط تو تا ${target.first}:${target.second} ($n نمونه)…", "🏠 Measuring your line to ${target.first}:${target.second} ($n samples)…")
            val line = withContext(Dispatchers.IO) { PingJitterMeter.measureLine(target.first, target.second, count = n, gapMs = 80) }
            lineCard.text = lineText(line)
            phaseText.text = if (worker != null) t("☁️ سنجش از راه ورکر $worker…", "☁️ Measuring through the worker $worker…") else t("☁️ سنجش لبهٔ کلودفلر…", "☁️ Measuring the Cloudflare edge…")
            val w = withContext(Dispatchers.IO) {
                if (worker != null) PingJitterMeter.measureWorker(target.first, target.second, worker, edgeOnly = false, count = n, gapMs = 100)
                else PingJitterMeter.measureWorker(target.first, target.second, IpScanner.SPEED_TEST_SNI, edgeOnly = true, count = n, gapMs = 100)
            }
            val report = PingJitterMeter.Report(target.first, target.second, line, w)
            lastReport = report
            workerCard.text = workerText(report)
            verdictText.text = PingJitterMeter.verdict(report, farsi)
            verdictText.visibility = View.VISIBLE
            phaseText.text = t("✅ تمام شد", "✅ Done")
            action.text = t("دوباره", "Again")
            copyButton.isEnabled = true
        }
    }

    private fun lineText(s: PingJitterMeter.Stats): String = buildString {
        append("🏠 ").append(t("خط من (TCP خالص)", "My line (bare TCP)")).append('\n')
        if (s.answered == 0) { append(t("❌ هیچ جوابی نیامد — این پورت روی اینترنت تو بسته است.", "❌ No answer — this port is closed on your internet.")); return@buildString }
        append(t("پینگ (میانه): ", "Ping (median): ")).append(s.medianMs).append(" ms\n")
        append(t("جیتر: ", "Jitter: ")).append(s.jitterMs ?: 0).append(" ms\n")
        append(t("کمترین / ۹۰٪: ", "Min / p90: ")).append(s.minMs).append(" / ").append(s.p90Ms).append(" ms\n")
        append(t("گم‌شده: ", "Lost: ")).append(s.sent - s.answered).append('/').append(s.sent).append(" (").append(s.lossPct.toInt()).append("%)")
    }

    private fun workerText(r: PingJitterMeter.Report): String = buildString {
        val w = r.worker ?: return@buildString
        val edge = w.state == PingJitterMeter.WorkerState.EDGE
        append("☁️ ").append(if (edge) t("لبهٔ کلودفلر (بدون ورکر)", "Cloudflare edge (no worker)") else t("از راه ورکر", "Through the worker"))
        append(" · ").append(w.host).append('\n')
        val s = w.stats
        if (s.answered == 0) { append("❌ ").append(PingJitterMeter.stateLabel(w.state, farsi)); return@buildString }
        append(t("پاسخ (میانه): ", "Answer (median): ")).append(s.medianMs).append(" ms\n")
        append(t("جیتر: ", "Jitter: ")).append(s.jitterMs ?: 0).append(" ms\n")
        append(t("کمترین / ۹۰٪: ", "Min / p90: ")).append(s.minMs).append(" / ").append(s.p90Ms).append(" ms\n")
        append(t("گم‌شده: ", "Lost: ")).append(s.sent - s.answered).append('/').append(s.sent).append(" (").append(s.lossPct.toInt()).append("%)\n")
        w.handshakeMs?.let { append(t("دست‌دادن TLS اول: ", "First TLS handshake: ")).append(it).append(" ms\n") }
        w.colo?.let { append(t("دیتاسنتر کلودفلر: ", "Cloudflare colo: ")).append(it).append('\n') }
        PingJitterMeter.workerOverheadMs(r)?.let { append(t("سربار کلودفلر/ورکر: +", "Cloudflare/worker overhead: +")).append(it).append(" ms") }
    }

    private fun copyReport() {
        val r = lastReport ?: return
        val body = "${r.ip}:${r.port}\n" + lineText(r.line) + "\n\n" + workerText(r) + "\n\n" + PingJitterMeter.verdict(r, farsi)
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("ping-jitter", body))
        Toast.makeText(ctx, t("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
    }
}
