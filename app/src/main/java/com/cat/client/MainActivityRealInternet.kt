// Home-screen «real internet test» card (beta220) + the opt-in «📨 report to panel» dialog.
// The service measures (RealInternetCheck in CatClientVpnService); this file only shows the result and sends the
// user's button presses back as Actions.TRY_NEXT_ROUTE / Actions.RECHECK_REAL_INTERNET.
package com.cat.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainActivity.buildRealInternetCard(): LinearLayout {
    val fa = isPersianUi()
    fun t(f: String, e: String) = if (fa) f else e
    val title = TextView(this).apply {
        textSize = 15f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    val detail = TextView(this).apply {
        textSize = 13f
        setTextColor(TEXT_SECONDARY)
        setLineSpacing(0f, 1.15f)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        visibility = View.GONE
    }
    fun action(label: String, accent: Boolean, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        setAllCaps(false)
        isSingleLine = true
        textSize = 13f
        typeface = CatClientBodyBoldTypeface
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(44)
        minimumHeight = dp(44)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(16)
        setPaddingRelative(dp(12), 0, dp(12), 0)
        backgroundTintList = ColorStateList.valueOf(if (accent) withAlpha(TEAL, 46) else withAlpha(palette.surfaceElevated2, 190))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(if (accent) withAlpha(TEAL, 170) else withAlpha(OUTLINE, 200))
        setTextColor(if (accent) TEAL else TEXT_PRIMARY)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }
    val next = action(t("⏭️ امتحان راه بعدی", "⏭️ Try next route"), accent = true) {
        if (buttonModel.state == VpnState.Started) startVpnService(Actions.TRY_NEXT_ROUTE)
    }
    val again = action(t("🔁 دوباره", "🔁 Again"), accent = false) {
        if (buttonModel.state == VpnState.Started) startVpnService(Actions.RECHECK_REAL_INTERNET)
    }
    val report = action(t("📨 گزارش", "📨 Report"), accent = false) { showAppReportDialog() }
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        val lp = { LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) } }
        addView(next, LinearLayout.LayoutParams(0, -2, 1.4f).apply { marginEnd = dp(6) })
        addView(again, lp())
        addView(report, LinearLayout.LayoutParams(0, -2, 1f))
    }
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(withAlpha(palette.surfaceElevated2, 210))
            setStroke(dp(1), withAlpha(OUTLINE, 140))
        }
        visibility = View.GONE
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    realInternetViews = RealInternetCardViews(card, title, detail, actions, next)
    renderRealInternetCard(lastRealInternetResult)
    return card
}

/** Text shown for a result — plain words, no jargon (unit-free figures only when they were really measured). */
internal fun realInternetTexts(r: RealInternetResult, fa: Boolean): Pair<String, String> {
    fun t(f: String, e: String) = if (fa) f else e
    val figures = listOfNotNull(
        r.latencyMs.takeIf { it >= 0 }?.let { t("پاسخ ${it}ms", "reply ${it}ms") },
        r.kbps.takeIf { it >= 0 }?.let { if (it >= 1000) "%.1f Mb/s".format(it / 1000.0) else "$it kb/s" },
    ).joinToString(" · ")
    return when (r.status) {
        RealInternetStatus.Checking -> t("⏳ دارم واقعاً اینترنت را امتحان می‌کنم…", "⏳ Checking that pages really load…") to ""
        RealInternetStatus.Works -> t("✅ اینترنت واقعاً کار می‌کند", "✅ Internet really works") to figures
        RealInternetStatus.Slow -> t("🐢 وصل است ولی کند است / گیر می‌کند", "🐢 Connected, but slow / stalls") to
            (if (r.autoHopping) t("دارم خودکار راه بعدی را امتحان می‌کنم…", "Trying the next route by itself…")
            else t("صفحه‌ها یا اینستاگرام ممکن است گیر کنند. «امتحان راه بعدی» را بزنید.", "Pages or Instagram may freeze. Tap «Try next route».") +
                (figures.takeIf(String::isNotBlank)?.let { "\n$it" } ?: ""))
        RealInternetStatus.NoInternet -> t("❌ وصل است ولی اینترنت نمی‌آید", "❌ Connected, but no internet comes through") to
            (if (r.autoHopping) t("دارم خودکار راه بعدی را امتحان می‌کنم…", "Trying the next route by itself…")
            else t("این راه روی شبکهٔ شما کار نمی‌کند. «امتحان راه بعدی» را بزنید.", "This route does not work on your network. Tap «Try next route»."))
        RealInternetStatus.NotMeasurable -> t("ℹ️ با این روش، برنامه نمی‌تواند خودش اینترنت را تست کند", "ℹ️ With this method the app cannot test the internet itself") to ""
    }
}

internal fun MainActivity.renderRealInternetCard(result: RealInternetResult?) {
    lastRealInternetResult = result
    val v = realInternetViews ?: return
    val show = result != null && buttonModel.state == VpnState.Started &&
        (activeConnectionFingerprint.isBlank() || result.fingerprint.isBlank() || result.fingerprint == "-" ||
            result.fingerprint == activeConnectionFingerprint || result.autoHopping)
    if (!show || result == null) {
        v.card.visibility = View.GONE
        return
    }
    val fa = isPersianUi()
    val (title, detail) = realInternetTexts(result, fa)
    v.title.text = title
    v.detail.text = detail
    v.detail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
    v.actions.visibility = if (result.status.isProblem && !result.autoHopping) View.VISIBLE else View.GONE
    v.next.isEnabled = result.canTryNext
    v.card.visibility = View.VISIBLE
}

internal fun MainActivity.handleRealInternetBroadcast(intent: Intent) {
    renderRealInternetCard(RealInternetResult.fromJson(intent.getStringExtra(Actions.EXTRA_REAL_INTERNET_RESULT)))
}

private fun MainActivity.currentNetworkKind(): String = runCatching {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // While connected the default network is the VPN; look at the underlying transports.
    val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        .filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    when {
        caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> "wifi"
        caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> "cellular"
        caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> "ethernet"
        else -> "other"
    }
}.getOrDefault("other")

/** «📨 report to panel»: shows exactly what would be sent and where; sends only after the user taps «Send». */
internal fun MainActivity.showAppReportDialog() {
    val fa = isPersianUi()
    fun t(f: String, e: String) = if (fa) f else e
    activityScope.launch {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val subscriptionId = activeRuntimeSubscriptionId
                val profile = userSubscriptionManager.cachedSnapshot(subscriptionId)?.catalog?.profiles
                    ?.firstOrNull { it.fingerprint == activeConnectionFingerprint }
                val kind = profile?.let { AppReport.configKind(it.shareLink, it.type, it.transport) } ?: "unknown"
                val subscriptionInput = userSubscriptionManager.list().firstOrNull { it.id == subscriptionId }?.input
                val facts = AppReportFacts(
                    appVersion = BuildConfig.VERSION_NAME,
                    sdk = Build.VERSION.SDK_INT,
                    operator = CarrierDetector.detect(this@showAppReportDialog).id,
                    operatorCode = CarrierDetector.rawOperatorCode(this@showAppReportDialog),
                    network = currentNetworkKind(),
                    engine = AppReport.engineFor(kind, profile?.server.orEmpty()),
                    configKind = kind,
                    result = lastRealInternetResult,
                    logExcerpt = AppReport.logExcerpt(DiagnosticLogger.read(this@showAppReportDialog)),
                )
                facts to AppReport.targetOrigin(PanelMonitor.target(this@showAppReportDialog), subscriptionInput)
            }.getOrNull()
        }
        if (prepared == null || isFinishing || isDestroyed) return@launch
        val (facts, target) = prepared
        val preview = AppReport.preview(facts, fa)
        val note = EditText(this@showAppReportDialog).apply {
            hint = t("توضیح کوتاه (اختیاری)، مثلاً «اینستا باز نمی‌شود»", "Short note (optional), e.g. «Instagram won't open»")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(AppReport.MAX_NOTE_CHARS))
            maxLines = 3
            textSize = 14f
        }
        val body = LinearLayout(this@showAppReportDialog).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(22), dp(8), dp(22), 0)
            addView(TextView(this@showAppReportDialog).apply {
                text = if (target != null) {
                    t("فقط همین متن، بدون آدرس سرور، UUID یا IP گوشی، به پنل خودتان فرستاده می‌شود:\n", "Only this text — no server address, UUID or phone IP — goes to your own panel:\n") + target
                } else {
                    t("پنلی برای فرستادن پیدا نشد (پنل خودتان را در «ابری» بسازید یا اشتراک پنل خودتان را انتخاب کنید). می‌توانید متن را کپی کنید و برای پشتیبانی بفرستید.",
                        "No panel to send to (deploy your own in «Cloud» or pick your own panel's subscription). You can copy the text and send it to support.")
                }
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
            })
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(ScrollView(this@showAppReportDialog).apply {
                addView(TextView(this@showAppReportDialog).apply {
                    text = preview
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(TEXT_PRIMARY)
                    setTextIsSelectable(true)
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                })
            }, LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(8) })
        }
        fun copy(text: String) {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("Cat Client report", text))
        }
        fun withNote(): AppReportFacts = facts.copy(note = note.text?.toString().orEmpty())
        val builder = MaterialAlertDialogBuilder(this@showAppReportDialog)
            .setTitle(t("📨 گزارش به پنل", "📨 Report to panel"))
            .setView(body)
            .setNeutralButton(t("📋 فقط کپی", "📋 Copy only")) { _, _ ->
                copy(AppReport.preview(withNote(), fa))
                Toast.makeText(this@showAppReportDialog, t("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(t("انصراف", "Cancel"), null)
        if (target != null) {
            builder.setPositiveButton(t("📨 بفرست", "📨 Send")) { _, _ ->
                val f = withNote()
                activityScope.launch {
                    val r = withContext(Dispatchers.IO) { AppReport.send(target, AppReport.payload(f)) }
                    DiagnosticLogger.info(this@showAppReportDialog, "appReport.send", "ok=${r.ok} code=${r.code}")
                    if (r.ok) {
                        Toast.makeText(this@showAppReportDialog, t("✅ گزارش فرستاده شد. ممنون!", "✅ Report sent. Thanks!"), Toast.LENGTH_LONG).show()
                    } else {
                        copy(AppReport.preview(f, fa))
                        val why = when (r.code) {
                            404 -> t("پنل شما این قابلیت را ندارد (قدیمی است؛ از «ابری» به‌روزش کنید).", "Your panel doesn't have this yet (update it from «Cloud»).")
                            429 -> t("کمی بعد دوباره بزنید (حداکثر یک گزارش در دقیقه).", "Try again a bit later (one report per minute).")
                            503 -> t("پنل شما فضای ذخیره (KV) ندارد.", "Your panel has no KV storage attached.")
                            else -> r.error
                        }
                        Toast.makeText(
                            this@showAppReportDialog,
                            t("نشد بفرستم: ", "Could not send: ") + why + t("\nمتن گزارش کپی شد.", "\nThe report text was copied."),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        builder.create().showCatClientDialog()
    }
}
