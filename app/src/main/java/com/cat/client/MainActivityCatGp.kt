package com.cat.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.CheckBox
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

// beta249 🌐 Cat GP (UI). Engine: CatGpRelay.kt (pure, unit-tested) + CatGpEngine.kt (phone glue). See docs/CODEMAP.md.

private fun MainActivity.gpFa(): Boolean = appLanguagePreferenceStore.read() == AppLanguage.Persian

private fun MainActivity.gpButton(label: String, accent: Boolean, onClick: () -> Unit) = MaterialButton(this).apply {
    text = label
    isAllCaps = false
    textSize = 13.5f
    typeface = CatClientBodyBoldTypeface
    minHeight = dp(46)
    insetTop = 0
    insetBottom = 0
    cornerRadius = dp(12)
    if (accent) {
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(palette.onAccent)
    } else {
        backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 220))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 140))
        setTextColor(TEXT_PRIMARY)
    }
    setOnClickListener { onClick() }
}

private fun MainActivity.gpText(value: String, size: Float = 12.5f, color: Int = TEXT_SECONDARY, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = CatClientBodyBoldTypeface
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    setTextIsSelectable(true)
}

private fun MainActivity.gpLp(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

/** 🌐 Cat GP: copy Code.gs → paste the …/exec address → 🧪 real test → 🔐 certificate → 🚀 save & connect. */
internal fun MainActivity.showCatGpDialog() {
    val fa = gpFa()
    fun t(f: String, e: String) = if (fa) f else e
    val ctx = this
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(6))
    }
    val (used, limit) = VpnRuntimeStateStore.readGasDailyUsage(this)
    box.addView(gpText(t(
        "اینو بزنی: ترافیک مرورگر از راه «اسکریپت شخصی خودتان در گوگل» رد می‌شود؛ اپراتور فقط اتصال به گوگل را می‌بیند. برای وقتی که همه‌چیز جز گوگل بسته است. رایگان است و سرور نمی‌خواهد، فقط یک حساب گوگل.",
        "Tap: browser traffic goes through YOUR OWN script on Google; the operator only sees a connection to Google. For when everything except Google is blocked. Free, no server — just a Google account.",
    ), 12.5f, TEXT_PRIMARY))
    val status = gpText("", 12f)
    fun refreshStatus(extra: String = "") {
        val saved = CatGpEngine.scriptPaths(CatGpEngine.deployments(ctx)).size
        val ca = CatGpEngine.caInstalled(ctx)
        status.text = listOf(
            t("سهمیهٔ امروز: $used از $limit درخواست", "Today's quota: $used of $limit requests"),
            if (saved > 0) t("✅ آدرس اسکریپت: $saved عدد ذخیره است", "✅ Script address: $saved saved") else t("⬜ آدرس اسکریپت هنوز ذخیره نشده", "⬜ No script address saved yet"),
            if (ca) t("✅ گواهی Cat GP روی گوشی نصب است", "✅ Cat GP certificate is installed") else t("⬜ گواهی نصب نشده (سایت‌های https بدون آن باز نمی‌شوند)", "⬜ Certificate not installed (https sites need it)"),
            extra,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
    refreshStatus()
    box.addView(status, gpLp(8))

    // Step 1 — the script
    box.addView(gpText(t("۱) اسکریپت", "1) The script"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    val panels = runCatching { VercelRoutePlanner.panelsFromSubscriptions(userSubscriptionManager.list().map { it.input }) }.getOrDefault(emptyList())
    val hops = CatGpEngine.hopCandidates(panels)
    val hopBox = CheckBox(this).apply {
        text = t("خروج از Cloudflare با پنل Cat خودتان (اختیاری)", "Exit through your own Cat panel on Cloudflare (optional)")
        setTextColor(TEXT_PRIMARY)
        visibility = if (hops.isEmpty()) View.GONE else View.VISIBLE
    }
    box.addView(hopBox, gpLp(4))
    if (hops.isNotEmpty()) box.addView(gpText(t(
        "اگر تیک بزنید سایت‌ها آی‌پی Cloudflare را می‌بینند نه گوگل (کپچای کمتر). پنل باید نسخهٔ 6.82.2 یا جدیدتر باشد؛ برنامه قبل از کپی بررسی می‌کند.",
        "Ticked: sites see a Cloudflare IP instead of Google's (fewer captchas). The panel must be 6.82.2+; the app checks before copying.",
    ), 11.5f), gpLp(2))
    box.addView(gpButton(t("📋 کپی کد اسکریپت (Code.gs)", "📋 Copy the script (Code.gs)"), accent = false) {
        activityScope.launch {
            var hop = ""
            if (hopBox.isChecked && hops.isNotEmpty()) {
                hop = withContext(Dispatchers.IO) {
                    hops.firstOrNull { url ->
                        runCatching { FreeNetHttp.request(url, timeoutMs = 10_000).let { it.code == 200 && it.body.contains("cat-gp-hop") } }.getOrDefault(false)
                    }.orEmpty()
                }
                if (hop.isBlank()) {
                    Toast.makeText(ctx, t("پنل شما /gp ندارد (قدیمی است). پنل را به 6.82.2 به‌روز کنید؛ فعلاً کد بدون Cloudflare کپی شد.",
                        "Your panel has no /gp (old version). Update it to 6.82.2; the script was copied without Cloudflare for now."), Toast.LENGTH_LONG).show()
                }
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Cat GP Code.gs", CatGpEngine.codeGs(ctx, hop)))
            refreshStatus(t("📋 کد کپی شد" + (if (hop.isNotBlank()) " (با خروج Cloudflare)" else ""), "📋 Script copied" + (if (hop.isNotBlank()) " (with Cloudflare exit)" else "")))
        }
    }, gpLp(6))
    box.addView(gpButton(t("🌍 باز کردن script.google.com", "🌍 Open script.google.com"), accent = false) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://script.google.com/home/projects/create"))) }
    }, gpLp(6))
    box.addView(gpText(t(
        "در گوگل: پروژهٔ تازه → همه را پاک کنید و کد را بچسبانید → ذخیره → Deploy ← New deployment ← نوع Web app ← Execute as: Me ← Who has access: Anyone ← Deploy ← اجازه بدهید. آدرس Web app (که به ‎/exec‎ ختم می‌شود) را کپی کنید.",
        "On Google: new project → delete everything, paste → Save → Deploy → New deployment → type Web app → Execute as: Me → Who has access: Anyone → Deploy → allow. Copy the Web app address (ends in /exec).",
    ), 11.5f), gpLp(4))

    // Step 2 — the address
    box.addView(gpText(t("۲) آدرس اسکریپت", "2) Script address"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    val input = EditText(this).apply {
        hint = "https://script.google.com/macros/s/AKfycb…/exec"
        setText(CatGpEngine.deployments(ctx))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 1
        maxLines = 4
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(input, gpLp(4))
    box.addView(gpText(t("چند آدرس (هر کدام در یک خط، از چند حساب گوگل) = سهمیهٔ روزانهٔ بیشتر.", "Several addresses (one per line, from several Google accounts) = more daily quota."), 11.5f), gpLp(2))
    box.addView(gpButton(t("🧪 آزمایش واقعی", "🧪 Real test"), accent = false) {
        val text = input.text?.toString().orEmpty()
        refreshStatus(t("⏳ در حال آزمایش از راه گوگل…", "⏳ Testing through Google…"))
        activityScope.launch {
            val r = withContext(Dispatchers.IO) { CatGpEngine.test(ctx, text) }
            if (r.ok) CatGpEngine.saveDeployments(ctx, text)
            refreshStatus((if (r.ok) t("✅ کار می‌کند — اسکریپت جواب داد", "✅ Works — the script answered") else t("❌ کار نکرد", "❌ Did not work")) + "\n" + r.lines.joinToString("\n"))
        }
    }, gpLp(6))

    // Step 3 — the certificate
    box.addView(gpText(t("۳) گواهی (برای سایت‌های https)", "3) Certificate (for https sites)"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    box.addView(gpText(t(
        "Cat GP صفحه‌های https را خودش روی گوشی باز می‌کند و دوباره قفل می‌کند؛ برای همین مرورگر باید گواهی «Cat GP Local CA» را بشناسد. این گواهی فقط روی همین گوشی ساخته شده و کلیدش از برنامه بیرون نمی‌رود.",
        "Cat GP opens https pages on the phone itself and re-locks them, so the browser must trust the “Cat GP Local CA” certificate. It was made on this phone only and its key never leaves the app.",
    ), 11.5f), gpLp(2))
    box.addView(gpButton(t("🔐 نصب گواهی", "🔐 Install certificate"), accent = false) {
        val direct = CatGpEngine.directInstallIntent(ctx)
        if (direct != null && runCatching { startActivity(direct) }.isSuccess) return@gpButton
        val where = CatGpEngine.exportCa(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(t("🔐 نصب گواهی Cat GP", "🔐 Install the Cat GP certificate"))
            .setMessage(t(
                "فایل «${where ?: "CatGP-CA.crt"}» ذخیره شد.\n\nتنظیمات ← امنیت (یا امنیت و حریم خصوصی) ← تنظیمات بیشتر ← رمزگذاری و اعتبارنامه‌ها ← نصب گواهی ← «گواهی CA» ← «نصب در هر صورت» ← فایل CatGP-CA.crt را از Download انتخاب کنید.\n\nاگر گوشی قفل صفحه نداشته باشد، اندروید اول قفل صفحه می‌خواهد.",
                "Saved “${where ?: "CatGP-CA.crt"}”.\n\nSettings → Security (or Security & privacy) → More settings → Encryption & credentials → Install a certificate → “CA certificate” → “Install anyway” → pick CatGP-CA.crt from Download.\n\nWithout a screen lock Android asks you to set one first.",
            ))
            .setPositiveButton(t("⚙️ باز کردن تنظیمات", "⚙️ Open Settings")) { _, _ -> runCatching { startActivity(CatGpEngine.securitySettingsIntent()) } }
            .setNegativeButton(t("بستن", "Close"), null)
            .create().showCatClientDialog()
    }, gpLp(6))

    box.addView(gpText(t(
        "محدودیت‌ها (صادقانه): فقط مرورگرها (Chrome و…) با گواهی کاربر کار می‌کنند؛ تلگرام، اینستاگرام و اپ‌هایی که گواهی خودشان را دارند از این راه باز نمی‌شوند. تماس صوتی، بازی و WebSocket کار نمی‌کند. هر حساب گوگل حدود ۲۰٬۰۰۰ درخواست در روز دارد و هر صفحه ده‌ها درخواست است. سرعت از کانفیگ معمولی کمتر است.",
        "Limits (honestly): only browsers (Chrome etc.) that accept user certificates work; Telegram, Instagram and apps with their own trust store will not open this way. Voice calls, games and WebSockets do not work. Each Google account has ~20,000 requests a day and one page is dozens of requests. Slower than a normal config.",
    ), 11.5f, TEXT_SECONDARY), gpLp(14))

    MaterialAlertDialogBuilder(this)
        .setTitle(t("🌐 Cat GP — رلهٔ شخصی گوگل", "🌐 Cat GP — personal Google relay"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .setPositiveButton(t("🚀 ذخیره و اتصال", "🚀 Save & connect")) { _, _ ->
            val text = input.text?.toString().orEmpty()
            if (CatGpEngine.scriptPaths(text).isEmpty()) {
                Toast.makeText(ctx, t("اول آدرس اسکریپت (…/exec) را بچسبانید.", "Paste the script address (…/exec) first."), Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            CatGpEngine.saveDeployments(ctx, text)
            activityScope.launch {
                val existing = userSubscriptionManager.list().firstOrNull { it.name == CatGpEngine.SUB_NAME }
                val subId = runCatching { saveFreeSubscription(existing?.id.orEmpty(), CatGpEngine.SUB_NAME, CatGpEngine.subscriptionText(ctx)) }.getOrNull()
                if (subId == null) {
                    Toast.makeText(ctx, t("ذخیره نشد.", "Could not save."), Toast.LENGTH_LONG).show()
                    return@launch
                }
                renderSubscriptions()
                if (!CatGpEngine.caInstalled(ctx)) {
                    Toast.makeText(ctx, t("گواهی هنوز نصب نیست: سایت‌های https تا نصب آن خطا می‌دهند.", "Certificate not installed yet: https sites will show an error until it is."), Toast.LENGTH_LONG).show()
                }
                connectFreeSubscription(subId)
            }
        }
        .create()
        .showCatClientDialog()
}
