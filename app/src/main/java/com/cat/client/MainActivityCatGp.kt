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

/** 🌐 Cat GP: copy the script → paste the …/exec address → test → certificate → connect. */
internal fun MainActivity.showCatGpDialog() {
    val fa = gpFa()
    fun t(f: String, e: String) = if (fa) f else e
    val ctx = this
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(6))
    }
    box.addView(gpText(t(
        "اینو بزنی این میشه: وقتی همه‌چیز بسته است و فقط گوگل باز است، مرورگر از یک اسکریپت مال خودتان روی گوگل رد می‌شود. رایگان است؛ سرور نمی‌خواهد. فقط مرورگر (کروم). تلگرام و اینستاگرام از این راه باز نمی‌شوند.",
        "Tap: when everything is blocked except Google, the browser goes through YOUR script on Google. Free, no server. Browsers only (Chrome). Telegram and Instagram will not open this way.",
    ), 13f, TEXT_PRIMARY))
    val status = gpText("", 12f)
    val linkedLine = gpText("", 13.5f, TEAL, bold = true)
    fun refreshStatus(extra: String = "") {
        val saved = CatGpEngine.scriptPaths(CatGpEngine.deployments(ctx)).size
        val ca = CatGpEngine.caInstalled(ctx)
        val hop = CatGpScripts.normalizeHop(CatGpEngine.hopUrl(ctx), CatGpEngine.key(ctx))
        val linked = CatGpEngine.hopWanted(ctx) && CatGpScripts.pairLinked(CatGpEngine.deployments(ctx), hop.ifBlank { CatGpEngine.hopUrl(ctx) })
        linkedLine.text = if (linked) {
            t("✅ گوگل اسکریپت و کلادفلر به هم متصل شدند", "✅ Google Script and Cloudflare are connected")
        } else {
            t("⬜ گوگل اسکریپت و کلادفلر هنوز به هم متصل نیستند", "⬜ Google Script and Cloudflare are not connected yet")
        }
        linkedLine.setTextColor(if (linked) TEAL else TEXT_SECONDARY)
        status.text = listOf(
            if (saved > 0) t("✅ آدرس گوگل ذخیره است", "✅ Google address saved") else t("⬜ هنوز آدرس گوگل را نچسبانده‌اید", "⬜ No Google address pasted yet"),
            if (CatGpScripts.dedicatedWorkerUrl(CatGpEngine.hopUrl(ctx)) != null)
                t("✅ ورکر کلادفلر (فقط اسکریپت گوگل) ذخیره است", "✅ Cloudflare Worker (Google Script only) saved")
            else t("⬜ ورکر کلادفلر را نچسبانده‌اید — پنل کَت نیست", "⬜ No Cat GP Cloudflare Worker yet — not the Cat panel"),
            if (ca) t("✅ گواهی نصب است — سایت‌های قفل‌دار باز می‌شوند", "✅ Certificate installed — https sites can open") else t("⬜ گواهی هنوز نصب نیست", "⬜ Certificate not installed yet"),
            extra,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
    refreshStatus()
    box.addView(status, gpLp(8))
    box.addView(linkedLine, gpLp(8))

    box.addView(gpText(t("کلید مشترک (همان CAT_KEY در اسکریپت و ورکر)", "Shared key (CAT_KEY in the script and the Worker)"), 12f), gpLp(10))
    box.addView(gpText(CatGpEngine.key(ctx), 14f, TEAL, bold = true), gpLp(2))

    val hopBox = CheckBox(this).apply {
        text = t("خروج کلادفلر — ورکر جدا فقط برای اسکریپت گوگل (نه پنل کَت)", "Cloudflare exit — a separate Worker only for Google Script (not the Cat panel)")
        setTextColor(TEXT_PRIMARY)
        isChecked = CatGpEngine.hopWanted(ctx)
    }
    box.addView(hopBox, gpLp(8))
    val hopField = EditText(this).apply {
        hint = t("آدرس ورکر Cat GP (…workers.dev)", "Cat GP Worker address (…workers.dev)")
        setText(CatGpEngine.hopUrl(ctx))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(hopField, gpLp(4))
    fun resolvedHopOrEmpty(): String {
        if (!hopBox.isChecked) return ""
        return CatGpScripts.normalizeHop(hopField.text?.toString().orEmpty(), CatGpEngine.key(ctx))
    }

    box.addView(gpText(t("۱) کد را در گوگل بگذارید", "1) Put the code on Google"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    box.addView(gpButton(t("📋 کپی کردن کد گوگل", "📋 Copy the Google code"), accent = true) {
        val hop = resolvedHopOrEmpty()
        CatGpEngine.saveHopWanted(ctx, hopBox.isChecked)
        CatGpEngine.saveHopUrl(ctx, hopField.text?.toString().orEmpty().ifBlank { hop })
        if (hopBox.isChecked && hop.isBlank()) {
            Toast.makeText(
                ctx,
                t("آدرس ورکر Cat GP را بچسبانید (…workers.dev). پنل کَت نیست. کد گوگل بدون ورکر کپی شد.",
                    "Paste the Cat GP Worker address (…workers.dev). Not the Cat panel. Google code was copied without the Worker."),
                Toast.LENGTH_LONG,
            ).show()
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Cat GP Code.gs", CatGpEngine.codeGs(ctx, hop)))
        refreshStatus(
            if (hop.isNotBlank()) t("📋 کد گوگل با آدرس ورکر کپی شد — در گوگل نسخهٔ جدید Deploy کنید",
                "📋 Google code with the Worker address copied — Deploy a new version on Google")
            else t("📋 کد گوگل کپی شد — حالا «باز کردن گوگل» را بزنید", "📋 Google code copied — tap Open Google next"),
        )
    }, gpLp(6))
    box.addView(gpButton(t("🌍 باز کردن گوگل", "🌍 Open Google"), accent = false) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://script.google.com/home/projects/create"))) }
    }, gpLp(6))
    box.addView(gpText(t(
        "در گوگل: پروژهٔ تازه → همه را پاک کنید → کد را بچسبانید → ذخیره.\nبعد Deploy → New deployment → Web app → Execute as: Me → Who has access: Anyone → Deploy.\nآدرسی که به /exec ختم می‌شود را کپی کنید و پایین بچسبانید. نمونهٔ داخل کادر را پاک کنید.",
        "On Google: new project → delete everything → paste → Save.\nThen Deploy → New deployment → Web app → Execute as: Me → Who has access: Anyone → Deploy.\nCopy the address that ends in /exec and paste it below. The box is empty on purpose — do not use an example.",
    ), 12f), gpLp(4))

    box.addView(gpText(t("۲) کد کلادفلر — فقط برای اسکریپت گوگل", "2) Cloudflare code — only for Google Script"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    box.addView(gpText(t(
        "این ورکر پنل کَت نیست. اسکریپت گوگل از سرور گوگل به این ورکر می‌زند. اگر کلادفلر روی گوشی بسته باشد اشکال ندارد.",
        "This Worker is not the Cat panel. Google Script calls it from Google's servers. The phone does not need to reach Cloudflare.",
    ), 12f), gpLp(4))
    box.addView(gpButton(t("📋 کپی کردن کد کلادفلر", "📋 Copy the Cloudflare code"), accent = true) {
        CatGpEngine.saveHopWanted(ctx, hopBox.isChecked)
        CatGpEngine.saveHopUrl(ctx, hopField.text?.toString().orEmpty())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Cat GP worker.js", CatGpEngine.workerJs(ctx)))
        refreshStatus(t("📋 کد کلادفلر کپی شد — در Workers بچسبانید و Deploy کنید",
            "📋 Cloudflare code copied — paste it in Workers and Deploy"))
    }, gpLp(6))
    box.addView(gpButton(t("☁️ باز کردن کلادفلر", "☁️ Open Cloudflare"), accent = false) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/"))) }
    }, gpLp(6))
    box.addView(gpText(t(
        "در کلادفلر: Workers & Pages → Create → همان فایل را بچسبانید → Deploy.\\nآدرس …workers.dev را بالا بچسبانید. بعد کد گوگل را دوباره کپی کنید تا CAT_WORKER داخلش باشد.",
        "On Cloudflare: Workers & Pages → Create → paste that file → Deploy.\\nPaste the …workers.dev address above. Then copy the Google code again so CAT_WORKER is inside it.",
    ), 12f), gpLp(4))

    box.addView(gpText(t("۳) آدرس را از گوگل بچسبانید", "3) Paste the address from Google"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    val input = EditText(this).apply {
        hint = t("آدرس واقعی را اینجا بچسبانید (…/exec)", "Paste the real address here (…/exec)")
        setText(CatGpEngine.deployments(ctx))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 1
        maxLines = 4
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(input, gpLp(4))
    fun addressMessage(raw: String): String? {
        val problem = CatGpScripts.addressProblem(raw)
        return when (problem) {
            null -> null
            "empty" -> t("❌ اول آدرس را از گوگل بچسبانید (باید به /exec ختم شود).", "❌ Paste the address from Google first (it must end in /exec).")
            "example" -> t("❌ این آدرس نمونه است. از صفحهٔ Deploy گوگل آدرس واقعی را کپی کنید.", "❌ That is an example. Copy the real address from Google’s Deploy page.")
            else -> t("❌ این آدرس اسکریپت گوگل نیست. باید به /exec ختم شود.", "❌ That is not a Google script address. It must end in /exec.")
        }
    }
    box.addView(gpButton(t("🧪 آزمایش", "🧪 Test"), accent = false) {
        val text = input.text?.toString().orEmpty()
        addressMessage(text)?.let { refreshStatus(it); return@gpButton }
        refreshStatus(t("⏳ در حال آزمایش…", "⏳ Testing…"))
        activityScope.launch {
            val r = withContext(Dispatchers.IO) { CatGpEngine.test(ctx, text) }
            if (r.ok) CatGpEngine.saveDeployments(ctx, text)
            val detail = r.lines.joinToString("\n").let { lines ->
                when {
                    r.ok -> t("✅ کار می‌کند — اسکریپت جواب داد", "✅ It works — the script answered")
                    lines.contains("unauthorized", true) -> t("❌ کلید جور نیست. کد را دوباره کپی کنید و در گوگل نسخهٔ جدید Deploy کنید.", "❌ Key mismatch. Copy the code again and Deploy a new version on Google.")
                    lines.contains("Anyone", true) || lines.contains("login", true) -> t("❌ دسترسی گوگل روی Anyone نیست. Deploy را با Who has access: Anyone دوباره بزنید.", "❌ Google access is not Anyone. Deploy again with Who has access: Anyone.")
                    else -> t("❌ کار نکرد", "❌ Did not work") + (if (lines.isBlank()) "" else "\n$lines")
                }
            }
            refreshStatus(detail)
        }
    }, gpLp(6))

    box.addView(gpText(t("۴) لبهٔ گوگل — IP و SNI روی خط شما", "4) Google edge — IP and SNI on your line"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    box.addView(gpText(t(
        "خالی = اسکن خودکار. یک آدرس یعنی فقط همان. چند خط = فقط همان‌ها روی این شبکه امتحان می‌شوند.",
        "Empty = auto-scan. One address pins it. Several lines = only those are tested on this network.",
    ), 12f), gpLp(2))
    val ipField = EditText(this).apply {
        hint = t("آدرس لبهٔ گوگل (مثلاً 216.239.38.120)", "Google edge address (e.g. 216.239.38.120)")
        setText(CatGpEngine.edgeIpsText(ctx))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 1
        maxLines = 4
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(ipField, gpLp(4))
    box.addView(gpText(t(
        "SNI باید میزبان گوگل باشد تا اتصال شبیه گوگل دیده شود.",
        "The SNI must be a Google host so the connection looks like one to Google.",
    ), 12f), gpLp(8))
    val sniField = EditText(this).apply {
        hint = t("SNI لبه (مثلاً beacons.gvt2.com)", "Edge SNI (e.g. beacons.gvt2.com)")
        setText(CatGpEngine.edgeSni(ctx))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(sniField, gpLp(4))
    val scanBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(gpButton(t("📡 اسکن این شبکه برای لبهٔ سالم", "📡 Scan this network for a working edge"), accent = false) {
        val typedSni = sniField.text?.toString().orEmpty().trim()
        if (typedSni.isNotBlank() && CatGpEdge.parseSni(typedSni) == null) {
            refreshStatus(t("❌ SNI باید میزبان گوگل باشد.", "❌ SNI must be a Google host."))
            return@gpButton
        }
        refreshStatus(t("⏳ اسکن واقعی روی خط شما…", "⏳ Real scan on your line…"))
        scanBox.removeAllViews()
        activityScope.launch {
            val hits = withContext(Dispatchers.IO) {
                CatGpEngine.scanEdges(ctx, ipField.text?.toString().orEmpty(), typedSni)
            }
            if (hits.isEmpty()) {
                refreshStatus(t("❌ روی این خط لبه‌ای جواب نداد.", "❌ No edge answered on this line."))
                return@launch
            }
            refreshStatus(t("✅ ${hits.size} لبه روی خط شما", "✅ ${hits.size} edges on your line"))
            hits.forEach { hit ->
                fun applyHit() {
                    ipField.setText(hit.ip)
                    sniField.setText(hit.sni)
                    CatGpEngine.saveEdgeIps(ctx, hit.ip)
                    CatGpEngine.saveEdgeSni(ctx, hit.sni)
                    Toast.makeText(
                        ctx,
                        t(
                            "لبه ${hit.ip}، نام ${hit.sni}. ذخیره را بزن تا استفاده شود.",
                            "Edge ${hit.ip}, name ${hit.sni}. Save the profile to use them.",
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                scanBox.addView(gpButton(CatGpEdge.line(hit), accent = false) { applyHit() }.also { btn ->
                    btn.setOnLongClickListener { applyHit(); true }
                }, gpLp(4))
            }
        }
    }, gpLp(6))
    box.addView(scanBox, gpLp(2))

    box.addView(gpText(t("۵) گواهی (یک‌بار، برای سایت‌های قفل‌دار)", "5) Certificate (once, for https sites)"), 13.5f, TEXT_PRIMARY, bold = true), gpLp(14))
    box.addView(gpText(t(
        "بدون این گواهی، کروم سایت‌های https را باز نمی‌کند. فقط روی همین گوشی ساخته می‌شود.",
        "Without this certificate Chrome will not open https sites. It is made on this phone only.",
    ), 12f), gpLp(2))
    box.addView(gpText(t("SHA-256 گواهی:", "Certificate SHA-256:") + "\n" + CatGpEngine.caSha256(ctx), 11.5f), gpLp(4))
    box.addView(gpButton(t("🔐 نصب گواهی", "🔐 Install certificate"), accent = false) {
        val direct = CatGpEngine.directInstallIntent(ctx)
        if (direct != null && runCatching { startActivity(direct) }.isSuccess) return@gpButton
        val where = CatGpEngine.exportCa(ctx)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(t("🔐 نصب گواهی", "🔐 Install the certificate"))
            .setMessage(t(
                "فایل «${where ?: "CatGP-CA.crt"}» ذخیره شد.\n\nتنظیمات ← امنیت ← رمزگذاری و اعتبارنامه‌ها ← نصب گواهی ← گواهی CA ← نصب در هر صورت ← همان فایل.",
                "Saved “${where ?: "CatGP-CA.crt"}”.\n\nSettings → Security → Encryption & credentials → Install a certificate → CA certificate → Install anyway → pick that file.",
            ))
            .setPositiveButton(t("⚙️ باز کردن تنظیمات", "⚙️ Open Settings")) { _, _ -> runCatching { startActivity(CatGpEngine.securitySettingsIntent()) } }
            .setNegativeButton(t("بستن", "Close"), null)
            .create().showCatClientDialog()
    }, gpLp(6))

    MaterialAlertDialogBuilder(this)
        .setTitle(t("🌐 Cat GP — وقتی فقط گوگل باز است", "🌐 Cat GP — when only Google is open"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .setPositiveButton(t("🚀 ذخیره و اتصال", "🚀 Save & connect")) { _, _ ->
            val text = input.text?.toString().orEmpty()
            addressMessage(text)?.let {
                Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            val sniTyped = sniField.text?.toString().orEmpty().trim()
            if (sniTyped.isNotBlank() && CatGpEdge.parseSni(sniTyped) == null) {
                Toast.makeText(ctx, t("❌ SNI باید میزبان گوگل باشد.", "❌ SNI must be a Google host."), Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            CatGpEngine.saveDeployments(ctx, text)
            CatGpEngine.saveEdgeIps(ctx, ipField.text?.toString().orEmpty())
            CatGpEngine.saveEdgeSni(ctx, sniTyped)
            CatGpEngine.saveHopWanted(ctx, hopBox.isChecked)
            CatGpEngine.saveHopUrl(ctx, hopField.text?.toString().orEmpty())
            activityScope.launch {
                val existing = userSubscriptionManager.list().firstOrNull { it.name == CatGpEngine.SUB_NAME }
                val subId = runCatching { saveFreeSubscription(existing?.id.orEmpty(), CatGpEngine.SUB_NAME, CatGpEngine.subscriptionText(ctx)) }.getOrNull()
                if (subId == null) {
                    Toast.makeText(ctx, t("ذخیره نشد.", "Could not save."), Toast.LENGTH_LONG).show()
                    return@launch
                }
                renderSubscriptions()
                if (!CatGpEngine.caInstalled(ctx)) {
                    Toast.makeText(ctx, t("گواهی هنوز نصب نیست: اول «نصب گواهی» را بزنید تا سایت‌ها باز شوند.", "Certificate not installed yet: tap Install certificate first so sites can open."), Toast.LENGTH_LONG).show()
                }
                connectFreeSubscription(subId)
            }
        }
        .create()
        .showCatClientDialog()
}

/** Pencil / long-press on 🌐 Cat GP: edit Google edge IP + SNI and scan this network. */
internal fun MainActivity.showCatGpEdgeDialog() {
    val fa = gpFa()
    fun t(f: String, e: String) = if (fa) f else e
    val ctx = this
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(6))
    }
    box.addView(gpText(t(
        "IP و SNI لبهٔ گوگل را بگذار یا این شبکه را اسکن کن. روی ردیف بزن یا نگه دار تا به Cat GP اضافه شود.",
        "Set the Google edge IP and SNI, or scan this network. Tap or hold a row to add it to Cat GP.",
    ), 13f, TEXT_PRIMARY), gpLp(0))
    val ipField = EditText(this).apply {
        hint = t("آدرس لبهٔ گوگل", "Google edge address")
        setText(CatGpEngine.edgeIpsText(ctx))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 1
        maxLines = 4
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(ipField, gpLp(8))
    val sniField = EditText(this).apply {
        hint = t("SNI لبه (میزبان گوگل)", "Edge SNI (Google host)")
        setText(CatGpEngine.edgeSni(ctx))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(sniField, gpLp(8))
    val status = gpText("", 12.5f, TEAL, bold = true)
    box.addView(status, gpLp(6))
    val scanBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun applyHit(hit: CatGpEdge.Hit) {
        ipField.setText(hit.ip)
        sniField.setText(hit.sni)
        CatGpEngine.saveEdgeIps(ctx, hit.ip)
        CatGpEngine.saveEdgeSni(ctx, hit.sni)
        status.text = t("✅ اضافه شد: ${hit.ip} · ${hit.sni}", "✅ Added: ${hit.ip} · ${hit.sni}")
        Toast.makeText(ctx, status.text, Toast.LENGTH_SHORT).show()
    }
    box.addView(gpButton(t("📡 اسکن این شبکه برای لبهٔ سالم", "📡 Scan this network for a working edge"), accent = true) {
        val typedSni = sniField.text?.toString().orEmpty().trim()
        if (typedSni.isNotBlank() && CatGpEdge.parseSni(typedSni) == null) {
            status.text = t("❌ SNI باید میزبان گوگل باشد.", "❌ SNI must be a Google host.")
            return@gpButton
        }
        status.text = t("⏳ اسکن واقعی روی خط شما…", "⏳ Real scan on your line…")
        scanBox.removeAllViews()
        activityScope.launch {
            val hits = withContext(Dispatchers.IO) {
                CatGpEngine.scanEdges(ctx, ipField.text?.toString().orEmpty(), typedSni)
            }
            if (hits.isEmpty()) {
                status.text = t("❌ روی این خط لبه‌ای جواب نداد.", "❌ No edge answered on this line.")
                return@launch
            }
            status.text = t("✅ ${hits.size} لبه — بزن یا نگه دار", "✅ ${hits.size} edges — tap or hold")
            hits.forEach { hit ->
                scanBox.addView(gpButton(CatGpEdge.line(hit), accent = false) { applyHit(hit) }.also { btn ->
                    btn.setOnLongClickListener { applyHit(hit); true }
                }, gpLp(4))
            }
        }
    }, gpLp(8))
    box.addView(scanBox, gpLp(2))
    MaterialAlertDialogBuilder(this)
        .setTitle(t("✏️ لبهٔ گوگل Cat GP", "✏️ Cat GP Google edge"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .setPositiveButton(t("💾 ذخیره", "💾 Save")) { _, _ ->
            val sniTyped = sniField.text?.toString().orEmpty().trim()
            if (sniTyped.isNotBlank() && CatGpEdge.parseSni(sniTyped) == null) {
                Toast.makeText(ctx, t("❌ SNI باید میزبان گوگل باشد.", "❌ SNI must be a Google host."), Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }
            CatGpEngine.saveEdgeIps(ctx, ipField.text?.toString().orEmpty())
            CatGpEngine.saveEdgeSni(ctx, sniTyped)
            Toast.makeText(ctx, t("✅ IP و SNI ذخیره شد", "✅ IP and SNI saved"), Toast.LENGTH_SHORT).show()
        }
        .create()
        .showCatClientDialog()
}
