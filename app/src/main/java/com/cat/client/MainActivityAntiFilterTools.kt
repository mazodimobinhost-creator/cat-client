package com.cat.client

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.text.DateFormat
import com.cat.client.MainActivity.Companion.SCREEN_HOME
import com.cat.client.MainActivity.Companion.SCREEN_SERVERS

// Anti-filter tool dialogs (fronting, relays, NetMelli, DNS tunnels, fusion, labs).
// Moved out of MainActivity.kt unchanged (beta221 code split phase 2); see docs/CODEMAP.md.

/** Shown once on the test page when every WireGuard / AmneziaWG row failed: what that means and what to do. */
internal fun MainActivity.showWireGuardRowsFailedHelp() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    val body = t(
        "همهٔ ردیف‌های WireGuard / AmneziaWG ناموفق شدند. به زبان ساده:\n\n" +
            "1️⃣ ردیف‌های «Cloudflare edge · WireGuard» همان WARP رایگان هستند و کلیدشان را برنامه خودش می‌سازد. «🔑 کلیدهای WARP» را بزنید و ببینید کلید ✅ دارد یا نه.\n\n" +
            "2️⃣ اگر کلید ✅ است و باز ناموفق است، اپراتور شما WireGuard را بسته (روی همراه اول و ایرانسل زیاد پیش می‌آید). این تقصیر کانفیگ نیست.\n\n" +
            "3️⃣ راهی که معمولاً جواب می‌دهد: Cat Warp با MASQUE روی HTTP/2 (دکمهٔ «⚡ ساخت Cat Warp» همین پایین).\n\n" +
            "4️⃣ کانفیگ Amnezia شخصی (vpn://) را فقط کسی که سرور Amnezia دارد می‌سازد: یا خودتان با برنامهٔ AmneziaVPN روی یک سرور مجازی (VPS) نصب کنید، یا از کسی که سرور دارد بگیرید. بعد ➕ ← «کلید Amnezia».",
        "Every WireGuard / AmneziaWG row failed. In plain words:\n\n" +
            "1️⃣ The «Cloudflare edge · WireGuard» rows are free WARP; the app creates their key itself. Tap «🔑 WARP keys» to see whether the key is ✅.\n\n" +
            "2️⃣ If the key is ✅ and it still fails, your carrier blocks WireGuard (common in Iran). The config is not broken.\n\n" +
            "3️⃣ What usually works: Cat Warp with MASQUE over HTTP/2 (the «⚡ Build Cat Warp» button below).\n\n" +
            "4️⃣ A personal Amnezia config (vpn://) is made by whoever runs an Amnezia server: install one yourself with the AmneziaVPN app on a VPS, or get one from someone who runs a server. Then ➕ → Amnezia key.",
    )
    MaterialAlertDialogBuilder(this)
        .setTitle(t("❓ چرا WireGuard وصل نشد؟", "❓ Why did WireGuard fail?"))
        .setMessage(body)
        .setPositiveButton(t("🔑 کلیدهای WARP", "🔑 WARP keys")) { _, _ -> showWarpKeysStatusDialog() }
        .setNeutralButton(t("⚡ ساخت Cat Warp", "⚡ Build Cat Warp")) { _, _ -> showAddAetherDialog() }
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.probeEndpointReachabilityMs(peer: String, useTcp: Boolean, timeoutMs: Int = 1800): Long? {
    val clean = peer.trim()
    val host: String
    val port: Int
    if (clean.startsWith("[")) {
        val closeIdx = clean.indexOf(']')
        if (closeIdx <= 1 || closeIdx + 2 > clean.length) return null
        host = clean.substring(1, closeIdx)
        port = clean.substring(closeIdx + 2).toIntOrNull() ?: return null
    } else {
        val colonIdx = clean.lastIndexOf(':')
        if (colonIdx <= 0) return null
        host = clean.substring(0, colonIdx)
        port = clean.substring(colonIdx + 1).toIntOrNull() ?: return null
    }
    val t0 = System.nanoTime()
    return runCatching {
        val addr = java.net.InetAddress.getByName(host)
        val sockAddr = java.net.InetSocketAddress(addr, port)
        if (useTcp) {
            java.net.Socket().use { sock ->
                sock.connect(sockAddr, timeoutMs)
            }
        } else {
            java.net.DatagramSocket().use { dgram ->
                dgram.soTimeout = timeoutMs
                dgram.connect(sockAddr)
                // Send a 148-byte WireGuard initiation probe header (type = 1) or wake packet
                val pkt = ByteArray(32).also { it[0] = 1 }
                dgram.send(java.net.DatagramPacket(pkt, pkt.size))
            }
        }
        ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
    }.getOrNull()
}

internal fun MainActivity.runMidStreamFreezeDetectorDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ مرحله ۱/۲: در حال تست جریان داده پیوسته (32KB) برای تشخیص قطع نیمه‌کاره صفحه…"
        else "⏳ Step 1/2: Probing continuous 32KB stream for mid-stream DPI freeze…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val verdict = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.probeMidStreamFreezeAndThroughput(targetBytes = 32_768, timeoutMs = 3_500)
        }
        val msg = if (isFarsi) verdict.summaryFa else verdict.summaryEn
        MaterialAlertDialogBuilder(this@runMidStreamFreezeDetectorDialog)
            .setTitle(
                if (isFarsi) "🩺 نتیجه تشخیص اختلال و قطع نیمه‌کاره (Mid-Stream Check)"
                else "🩺 Mid-Stream Freeze & Throttling Diagnosis",
            )
            .setMessage(msg)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(
                if (isFarsi) "🧭 تشخیص و اعمال پروفایل ضد فایروال منطقه"
                else "🧭 Auto-Apply Regional Anti-Freeze Profile",
            ) { _, _ ->
                runRegionalFirewallFingerprinterDialog()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.showDomainFrontingAndUnpoisonResolverDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val input = EditText(this).apply {
        hint = "youtube.com / instagram.com / chatgpt.com / telegram.org"
        setText("youtube.com")
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(4))
        addView(input)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🛡️ دامین فرانتینگ + پیدا کردن آدرس واقعی سایت فیلترشده"
            else "🛡️ Domain Fronting & Unpoisoned Real IP Finder",
        )
        .setMessage(
            if (isFarsi) "نام دامنه فیلترشده را وارد کنید تا مسمومیت DNS اپراتور (10.10.34.x) دور زده شود، آی‌پی واقعی و سالم پیدا شود و تنظیم Domain Fronting روی خط شما اعمال گردد:"
            else "Enter a filtered domain to bypass ISP DNS poisoning (10.10.34.x), discover its real reachable IP, and apply Domain Fronting:",
        )
        .setView(wrap)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (isFarsi) "🔍 کشف آی‌پی واقعی و تست" else "🔍 Find Real IP & Test") { _, _ ->
            val targetDomain = input.text?.toString()?.trim().orEmpty().ifBlank { "youtube.com" }
            Toast.makeText(
                this,
                if (isFarsi) "⏳ در حال رمزگشایی DNS بدون فیلتر و تست مسیر فرانتینگ برای $targetDomain…"
                else "⏳ Resolving unpoisoned IPs and testing fronting path for $targetDomain…",
                Toast.LENGTH_SHORT,
            ).show()
            activityScope.launch {
                val res = withContext(Dispatchers.IO) {
                    WhiteDnsSmartScanner.resolveUnpoisonedRealIp(targetDomain)
                }
                MaterialAlertDialogBuilder(this@showDomainFrontingAndUnpoisonResolverDialog)
                    .setTitle(if (isFarsi) "✅ نتیجه کشف آدرس واقعی و فرانتینگ" else "✅ Unpoisoned IP & Fronting Result")
                    .setMessage(if (isFarsi) res.summaryFa else res.summaryEn)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(if (isFarsi) "📋 کپی آی‌پی‌های واقعی" else "📋 Copy Real IPs") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("unpoison-ips", res.cleanIps.joinToString("\n")))
                        Toast.makeText(this@showDomainFrontingAndUnpoisonResolverDialog, R.string.free_copied, Toast.LENGTH_SHORT).show()
                    }
                    .setPositiveButton(if (isFarsi) "⚡ اعمال آی‌پی و SNI فرانتینگ" else "⚡ Apply Clean IP & Front SNI") { _, _ ->
                        // beta205: only the IP whose :443 actually answered on this line (no untested
                        // fallback IP), IPv6 bracketed so the saved endpoint stays parseable.
                        val bestIp = res.workingCleanIp
                        if (bestIp == null) {
                            Toast.makeText(
                                this@showDomainFrontingAndUnpoisonResolverDialog,
                                if (isFarsi) "⚠️ هیچ‌کدام از آی‌پی‌های واقعی روی پورت ۴۴۳ خط شما جواب نداد؛ چیزی اعمال نشد."
                                else "⚠️ None of the real IPs answered on port 443 on your line — nothing was applied.",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@setPositiveButton
                        }
                        val endpoint = if (bestIp.contains(':')) "[${bestIp.trim('[', ']')}]:443" else "$bestIp:443"
                        val prev = frontingIpPreferenceStore.readFrontingIp()
                        frontingIps = FrontingIpPolicy.mergeNewestFirst(listOf(endpoint), frontingIps)
                        renderFrontingIpChips()
                        saveFrontingIps(reconnectIfChanged = true, previousValue = prev)
                        applySniOverride(res.recommendedFrontHost)
                        Toast.makeText(
                            this@showDomainFrontingAndUnpoisonResolverDialog,
                            if (isFarsi) "✅ آدرس $endpoint با فرانت ${res.recommendedFrontHost} اعمال شد"
                            else "✅ Applied $endpoint with front host ${res.recommendedFrontHost}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .create()
                    .showCatClientDialog()
            }
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showGoogleAppsScriptRelayDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val (usedToday, dailyLimit) = VpnRuntimeStateStore.readGasDailyUsage(this)
    val pct = ((usedToday * 100) / dailyLimit.coerceAtLeast(1)).coerceIn(0, 100)
    val warnText = if (pct >= 80) {
        if (isFarsi) "⚠️ هشدار: نزدیک به سقف سهمیه روزانه گوگل ($pct%)" else "⚠️ Warning: Approaching daily GAS quota ($pct%)"
    } else {
        if (isFarsi) "✅ سهمیه روزانه سالم ($usedToday از $dailyLimit درخواست · $pct%)" else "✅ Daily quota healthy ($usedToday / $dailyLimit · $pct%)"
    }
    val input = EditText(this).apply {
        hint = "https://script.google.com/macros/s/AKfycb.../exec"
        setText(VpnRuntimeStateStore.readGasRelayUrl(this@showGoogleAppsScriptRelayDialog))
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(4))
        addView(input)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🌐 رله گوگل (Google Apps Script Relay)"
            else "🌐 Google Apps Script (GAS) Relay",
        )
        .setMessage(
            if (isFarsi) "اتصال از راه اسکریپت گوگل شخصی شما روی دامنه باز script.google.com:\n$warnText\n\n۱) با دکمه «📋 کپی کد اسکریپت»، کد آماده را در script.google.com قرار دهید و Deploy کنید.\n۲) لینک Web App (.../exec) را در کادر زیر بگذارید و «🚀 ذخیره و اتصال» را بزنید:"
            else "Connect via your personal Google Apps Script on script.google.com:\n$warnText\n\n1) Copy the ready Code.gs template and deploy as Web App in script.google.com.\n2) Paste your .../exec URL below and tap Save & Connect:",
        )
        .setView(wrap)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "📋 کپی کد اسکریپت گوگل (Code.gs)" else "📋 Copy Code.gs Script") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cat-gas-relay", WhiteDnsSmartScanner.buildGoogleAppsScriptRelaySource()))
            Toast.makeText(
                this,
                if (isFarsi) "✅ کد کامل اسکریپت گوگل (Code.gs) کپی شد" else "✅ Copied Google Apps Script (Code.gs)",
                Toast.LENGTH_LONG,
            ).show()
        }
        .setPositiveButton(if (isFarsi) "🚀 ذخیره و اتصال با رله گوگل" else "🚀 Save & Connect via GAS Relay") { _, _ ->
            val url = input.text?.toString()?.trim().orEmpty()
            VpnRuntimeStateStore.saveGasRelayUrl(this, url)
            VpnRuntimeStateStore.incrementGasDailyUsage(this, 1)
            val gasUri = WhiteDnsSmartScanner.buildGasRelayUri(url, "Google-Apps-Script-Relay")
            activityScope.launch {
                runCatching {
                    WhiteDnsSmartScanner.parseTunnelUri(gasUri)?.let { parsed ->
                        withContext(Dispatchers.IO) {
                            WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
                        }
                    }
                    val title = "🌐 رله اسکریپت گوگل (GAS Relay)"
                    val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                    val saved = withContext(Dispatchers.IO) {
                        if (existing != null) {
                            userSubscriptionManager.update(existing.id, title, gasUri)
                        } else {
                            userSubscriptionManager.add(title, gasUri)
                        }
                    }
                    userSubscriptionManager.select(saved.id)
                    renderSubscriptions()
                    onSubscriptionSelected()
                    renderAdvancedControls()
                    openScreen(SCREEN_HOME)
                    if (currentVpnServiceState == VpnState.Started) {
                        reconnectForConnectionOptionChange()
                    } else {
                        beginConnectFlow(Actions.CONNECT)
                    }
                }
            }
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showLiteWorkerGeneratorDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val liteScript = WhiteDnsSmartScanner.buildLiteWorkerScript(
        uuid = java.util.UUID.randomUUID().toString(),
        proxyIp = "103.237.102.191:11111",
    )
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🪶 ورکر سبک کلودفلر (Cat Client Lite Worker)"
            else "🪶 Cat Client Lite Worker (Zero-KV Fast Worker)",
        )
        .setMessage(
            if (isFarsi) "ورکر فوق‌سبک (~3KB) بدون مصرف سهمیه KV کلودفلر، مجهز به:\n" +
                "• کانفیگ آماده متد F&F ایرانسل (188.114.98.0 + tlshello-0-len + semi-python)\n" +
                "• کانفیگ آماده IPv6 همراه اول (2a06:98c1:3101::6812:202f)\n" +
                "• رله ProxyIP با پورت صریح (103.237.102.191:11111)\n" +
                "• شمارشگر و هشدار سهمیه روزانه ۱۰۰ هزار درخواست رایگان (/health)"
            else "Ultra-lightweight (~3KB) zero-KV Cloudflare Worker with built-in Irancell F&F, MCI IPv6, explicit-port ProxyIP, and daily 100k request quota warning (/health).",
        )
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (isFarsi) "📋 کپی کد ورکر لایت" else "📋 Copy Lite Worker Script") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("catclient-lite-worker", liteScript))
            Toast.makeText(
                this,
                if (isFarsi) "✅ کد ورکر لایت کلودفلر در کلیپ‌بورد کپی شد"
                else "✅ Copied Cat Client Lite Worker script to clipboard",
                Toast.LENGTH_LONG,
            ).show()
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showConnectionResilienceAndKillSwitchDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val checked = booleanArrayOf(
        VpnRuntimeStateStore.isAutoReconnectOnDropEnabled(this),
        VpnRuntimeStateStore.isAutoConnectOnBootOrUpdateEnabled(this),
        VpnRuntimeStateStore.isKillSwitchEnabled(this),
        VpnRuntimeStateStore.isSmartUnspoofedDirectBypassEnabled(this),
    )
    val labels = if (isFarsi) {
        arrayOf(
            "🔄 وصل دوباره اگر اتصال قطع شد (پیش‌فرض: خاموش)",
            "🚀 وصل خودکار بعد از ری‌استارت گوشی یا آپدیت برنامه (پیش‌فرض: خاموش)",
            "🛑 قطع‌کن اضطراری (Kill Switch) برای همه‌ی متودها و کانفیگ‌ها (جلوگیری از لو رفتن IP ایران)",
            "⚡ عبور مستقیم سایت‌های بدون مسمومیت DNS (Smart Direct Bypass — افزایش سرعت و حفظ کوئوتا)",
        )
    } else {
        arrayOf(
            "🔄 Reconnect automatically if dropped (default: off)",
            "🚀 Connect after device reboot or app update (default: off)",
            "🛑 Universal Emergency Kill Switch across all methods & configs",
            "⚡ Smart Unspoofed Direct Bypass (route unpoisoned non-sanctioned sites direct)",
        )
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🔌 تنظیمات وصل خودکار، قطع‌کن اضطراری و مسیریابی هوشمند"
            else "🔌 Auto-Reconnect, Kill Switch & Smart Direct Bypass",
        )
        .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
            checked[which] = isChecked
        }
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(if (isFarsi) "💾 ذخیره تنظیمات" else "💾 Save") { _, _ ->
            VpnRuntimeStateStore.setAutoReconnectOnDropEnabled(this, checked[0])
            VpnRuntimeStateStore.setAutoConnectOnBootOrUpdateEnabled(this, checked[1])
            VpnRuntimeStateStore.setKillSwitchEnabled(this, checked[2])
            VpnRuntimeStateStore.setSmartUnspoofedDirectBypassEnabled(this, checked[3])
            Toast.makeText(
                this,
                if (isFarsi) "✅ تنظیمات پایداری، Kill Switch و مسیریابی هوشمند ذخیره شد"
                else "✅ Saved resilience, Kill Switch & Smart Direct Bypass settings",
                Toast.LENGTH_SHORT,
            ).show()
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.runAiAndGeminiUnblockTesterDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال تست باز بودن Gemini، AI Studio، ChatGPT و یوتیوب روی خروجی فعلی…"
        else "⏳ Testing Gemini, AI Studio, ChatGPT & YouTube unblock status on current exit…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val report = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.probeAiAndGeminiUnblockSuite()
        }
        MaterialAlertDialogBuilder(this@runAiAndGeminiUnblockTesterDialog)
            .setTitle(
                if (isFarsi) "✨ نتیجه تست باز بودن Gemini / AI Studio / ChatGPT"
                else "✨ Gemini, AI Studio & ChatGPT Unblock Report",
            )
            .setMessage(if (isFarsi) report.summaryFa else report.summaryEn)
            .setNegativeButton(android.R.string.ok, null)
            .setNeutralButton(if (isFarsi) "🧦 بخش اختصاصی ساکس۵" else "🧦 Open SOCKS5 Hub") { _, _ ->
                showSocks5HubPage()
            }
            .setPositiveButton(
                if (isFarsi) "🔓 فعال‌سازی WARP ← سایفون (آمریکا US)"
                else "🔓 Enable WARP -> Psiphon (US)",
            ) { _, _ ->
                val cur = aetherPreferenceStore.read()
                aetherPreferenceStore.save(
                    cur.copy(
                        enabled = true,
                        exitLocation = "foreign",
                        chainTarget = "psiphon",
                        psiphonRegion = "US",
                        mode = AetherSelection.simplifyModeForChain(cur.mode, "psiphon"),
                    ),
                )
                renderAdvancedControls()
                Toast.makeText(
                    this@runAiAndGeminiUnblockTesterDialog,
                    if (isFarsi) "✅ زنجیره WARP ← سایفون (آمریکا US) برای باز شدن Gemini و هوش مصنوعی فعال شد"
                    else "✅ Enabled WARP -> Psiphon (US) for Gemini & AI unblock",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.runNetMelliDetectorDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال مقایسه پینگ سرورهای داخل ایران با درگاه‌های بین‌المللی (تشخیص نت ملی)…"
        else "⏳ Comparing domestic Iranian resolvers vs. international edges (Net Melli check)…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val verdict = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.probeNetMelliVsDeadConfig()
        }
        MaterialAlertDialogBuilder(this@runNetMelliDetectorDialog)
            .setTitle(
                if (isFarsi) "🚨 نتیجه تشخیص هوشمند «اینترنت ملی» در برابر «خرابی کانفیگ»"
                else "🚨 Net Melli vs. Dead Config Diagnostic",
            )
            .setMessage(if (isFarsi) verdict.summaryFa else verdict.summaryEn)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(
                if (isFarsi) "📡 باز کردن آزمایشگاه تانل DNS (ضد نت ملی)"
                else "📡 Open Cat DNS Tunnel Lab",
            ) { _, _ ->
                showWhiteDnsResolverMethodsDialog()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.showNineAntiFilterPresetsDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val presets = WhiteDnsSmartScanner.ANTI_FILTER_9_PRESETS
    val titles = presets.map { p ->
        val name = if (isFarsi) p.titleFa else p.titleEn
        "$name · len=${p.fragmentLengths} · delay=${p.fragmentDelaysMs}ms"
    }.toTypedArray()
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🛡️ انتخاب از ۹ پریست ضد فیلترینگ (شامل Max-Stealth، Double-Fragment و Starlink)"
            else "🛡️ Select from 9 Anti-Filtering Presets (Max-Stealth, Double-Fragment & Starlink)",
        )
        .setItems(titles) { _, idx ->
            val p = presets[idx]
            val summary = if (isFarsi) {
                "✅ پریست «${p.titleFa}»:\n" +
                    "• Fingerprint: ${p.fingerprint}\n" +
                    "• CipherSuites: ${p.cipherSuites.ifEmpty { "(خالی / استاندارد)" }}\n" +
                    "• FinalMask: ${p.finalMask.ifEmpty { "(خالی / استاندارد)" }}\n" +
                    "• Fragment: packets=${p.fragmentPackets}, length=${p.fragmentLengths}, interval=${p.fragmentDelaysMs}ms\n" +
                    "• ALPN: ${p.alpn}\n" +
                    "• اصلاح خودکار باگ REALITY: فعال (تبدیل خودکار tlshello به 1-3 روی REALITY)"
            } else {
                "✅ Preset '${p.titleEn}':\n" +
                    "• Fingerprint: ${p.fingerprint}\n" +
                    "• CipherSuites: ${p.cipherSuites.ifEmpty { "(default)" }}\n" +
                    "• FinalMask: ${p.finalMask.ifEmpty { "(default)" }}\n" +
                    "• Fragment: packets=${p.fragmentPackets}, length=${p.fragmentLengths}, interval=${p.fragmentDelaysMs}ms\n" +
                    "• ALPN: ${p.alpn}\n" +
                    "• Auto REALITY Fragment Fix: Active (tlshello -> 1-3)"
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cat-antifilter-preset", summary))
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.detectLocalLanIpv4Address(): String {
    return runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching "192.168.43.1"
        while (interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("192.168.") || host.startsWith("172.") || host.startsWith("10.")) {
                        return@runCatching host
                    }
                }
            }
        }
        "192.168.43.1"
    }.getOrDefault("192.168.43.1")
}

internal fun MainActivity.connectWhiteDnsTunnelDirectly(bundle: WhiteDnsTunnelBundle) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    activityScope.launch {
        runCatching {
            val uris = listOf(
                bundle.cottenMaxUri,
                bundle.masterDnsUri,
                bundle.stormDnsUri,
                bundle.soarUri,
                bundle.vayDnsUri,
                bundle.noizDnsUri,
                bundle.slipstreamUri,
                bundle.dnstHumanUri,
                bundle.slipNetUri,
            ).filter { it.isNotBlank() }
            uris.forEach { uri ->
                WhiteDnsSmartScanner.parseTunnelUri(uri)?.let { parsed ->
                    withContext(Dispatchers.IO) {
                        WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
                    }
                }
            }
            val content = uris.joinToString("\n")
            val title = "🧬 تانل Cat DNS (اتصال مستقیم)"
            val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
            val saved = withContext(Dispatchers.IO) {
                if (existing != null) {
                    userSubscriptionManager.update(existing.id, title, content)
                } else {
                    userSubscriptionManager.add(title, content)
                }
            }
            userSubscriptionManager.select(saved.id)
            orbotEnginePreferenceStore.setEnabled(false)
            val curAether = aetherPreferenceStore.read()
            if (curAether.enabled) {
                aetherPreferenceStore.save(curAether.copy(enabled = false))
            }
            renderSubscriptions()
            onSubscriptionSelected()
            renderAdvancedControls()
            Toast.makeText(
                this@connectWhiteDnsTunnelDirectly,
                if (isFarsi) "🚀 ۹ موتور تانل DNS (CottenMax / MasterDNS / StormDNS / Soar / VayDNS / NoizDNS / Slipstream / dnst) فعال شد — در حال اتصال…"
                else "🚀 9 DNS Tunnel Engines activated in app — connecting…",
                Toast.LENGTH_LONG,
            ).show()
            openScreen(SCREEN_HOME)
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            } else {
                beginConnectFlow(Actions.CONNECT)
            }
        }.onFailure { err ->
            Toast.makeText(
                this@connectWhiteDnsTunnelDirectly,
                if (isFarsi) "خطا در راه‌اندازی تانل: ${err.message}" else "Tunnel setup error: ${err.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

/**
 * SlipNet 6-Point Compatibility Scanner (`NS✓ TXT✓ RND✓ DPI✓ EDNS✓(1232) NXD✓`) +
 * Dual UDP/TCP transport & 8s TCP Pre-flight check.
 */
internal fun MainActivity.runSlipNetSixPointScoreDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال اجرای اسکن ۶ امتیازی Cat DNS (NS, TXT, RND, DPI, EDNS0, NXDOMAIN + UDP/TCP)…"
        else "⏳ Running Cat DNS 6-point resolver compatibility scan (NS, TXT, RND, DPI, EDNS0, NXDOMAIN + UDP/TCP)…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val cards = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.scanResolversWithSlipNetScore(maxCandidates = 6, timeoutMs = 800)
        }
        if (cards.isEmpty()) {
            Toast.makeText(
                this@runSlipNetSixPointScoreDialog,
                if (isFarsi) "⚠️ هیچ ریزولوری در این لحظه پاسخ نداد؛ حالت TCP:53 یا رله داخلی را امتحان کنید."
                else "⚠️ No resolvers responded; try TCP:53 or a domestic relay.",
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        val msg = buildString {
            if (isFarsi) {
                appendLine("🎯 گزارش ۶ شاخص سازگاری تانل Cat DNS / MasterDNS / Cat DNS:")
                appendLine("────────────────────────")
            } else {
                appendLine("🎯 Cat DNS / MasterDNS 6-Point Resolver Compatibility Report:")
                appendLine("────────────────────────")
            }
            cards.forEachIndexed { i, c ->
                val name = if (isFarsi) c.providerNameFa else c.providerNameEn
                appendLine("${i + 1}. $name (${c.resolverIp}) — ${c.statusLabel}")
                appendLine("   • ${c.badgeText}")
                appendLine("   • Transport: ${c.transportBadge} · RTT: ${c.rttMs} ms · MTU: ↑${c.upMtu}B / ↓${c.downMtu}B")
                appendLine()
            }
        }
        val best = cards.first()
        MaterialAlertDialogBuilder(this@runSlipNetSixPointScoreDialog)
            .setTitle(
                if (isFarsi) "🎯 نتیجه اسکن ۶ امتیازی Cat DNS (بهترین: ${best.resolverIp} — ${best.score}/6)"
                else "🎯 Cat DNS 6-Point Scan (Best: ${best.resolverIp} — ${best.score}/6)",
            )
            .setMessage(msg.trim())
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "⚙️ جنریتور MasterDNS / dnst://" else "⚙️ MasterDNS / dnst:// Gen") { _, _ ->
                showMasterDnsAndDnstGeneratorDialog(best.resolverIp)
            }
            .setPositiveButton(if (isFarsi) "🚀 ساخت و اتصال با بهترین ریزولور" else "🚀 Build & Connect Best") { _, _ ->
                val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
                    resolverIp = best.resolverIp,
                    transport = if (best.udpAlive) "udp" else "tcp",
                    recordMode = if (best.txtOk) WhiteDnsRecordMode.TXT_CNAME else WhiteDnsRecordMode.TXT_AAAA,
                    preset = WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.first(),
                )
                connectWhiteDnsTunnelDirectly(bundle)
            }
            .create()
            .showCatClientDialog()
    }
}

/**
 * Interactive 2026 Multi-VPN Hub & Comparison Dialog:
 * Explains what every major VPN (`MLM v1.2.40–v1.2.42`, `Firefox FENIX`, `Outline`,
 * `AmneziaWG 2.0 / Proton`, `SlipNet / MasterDNS`, `WhiteDNS`, `Defyx / Oblivion`,
 * `PaqetNG (patth / patth ng)`, and `3-Layer Smart Iran Banking Shield`) does in plain language and launches each engine directly.
 */
internal fun MainActivity.showAllVpns2026ComparisonAndMlmHubDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val catalog = MlmAndNextGenVpnHub.ALL_VPNS_2026_CATALOG
    val items = catalog.map { entry ->
        if (isFarsi) {
            "${entry.titleFa}\n   💡 ${entry.howItWorksFa}\n   ${entry.statusInIranFa}"
        } else {
            "${entry.titleEn}\n   💡 ${entry.howItWorksEn}\n   ${entry.statusInIranEn}"
        }
    }.toTypedArray()

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🌐 مرکز جامع VPNهای ۲۰۲۶ و قابلیت‌های جدید Cat (هر کدوم رو بزنی همون اجرا میشه)"
            else "🌐 2026 Multi-VPN Hub & Cat Capabilities (Tap Any Engine to Launch)",
        )
        .setItems(items) { _, which ->
            when (which) {
                0 -> showFirefoxVpnFenixHubDialog()
                1 -> showOutlineFreeHubDialog()
                2 -> showAmneziaAndProtonWgHubDialog()
                3 -> runCloudflareZeroByteAndIpv6HealerDialog()
                4 -> {
                    showAetherAutoScanAndBuildWizard {
                        renderAdvancedControls()
                    }
                }
                5 -> runSlipNetSixPointScoreDialog()
                6 -> runSmartIranBankingShieldDialog()
                7 -> showPaqetPatthEngineDialog()
                8 -> showRecommendedOneClickConnectWizardDialog()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "📦 PaqetNG / patth" else "📦 PaqetNG / patth") { _, _ ->
            showPaqetPatthEngineDialog()
        }
        .setPositiveButton(if (isFarsi) "🚀 اتصال پیشنهادی ۱-کلیکی" else "🚀 1-Click Recommended") { _, _ ->
            showRecommendedOneClickConnectWizardDialog()
        }
        .create()
        .showCatClientDialog()
}

/**
 * Outline & Free Shadowsocks Hub (`MLM v1.2.41+` equivalent):
 * - Fetches live `ss://` & `ssconf://` keys from 6 public repositories (with `jsDelivr` mirror),
 * - Probes TCP/handshake latency on the user's current ISP line,
 * - Supports pasting custom `ss://` or dynamic `ssconf://` Outline keys,
 * - Connects in 1 tap to the fastest verified server.
 */
/** 🔺 Delta-Kronecker public feeds: one tap adds a subscription, fills Aether → Tor bridges, or opens the downloader. */
internal fun MainActivity.showDeltaKroneckerHubDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val pad = dp(16)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, dp(8), pad, dp(8))
    }
    fun note(text: String, size: Float, color: Int, top: Int) {
        box.addView(
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                typeface = CatClientBodyTypeface
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) },
        )
    }
    var dialog: AlertDialog? = null
    note(
        if (isFarsi) "فهرست‌های عمومی Cat Feed که مرتب دوباره تست می‌شوند. برنامه فقط لینکشان را روی گوشی شما می‌خواند (چیزی کپی نمی‌شود). هر دکمه را بزنید همان کار را می‌کند؛ اگر github روی اینترنت شما باز نیست اول یک کانفیگ را وصل کنید."
        else "Cat Feed's public lists, re-tested regularly. The app only reads their links on your phone (nothing is copied). Each button does exactly what it says; if github is closed on your line, connect a config first.",
        13f, TEXT_PRIMARY, 0,
    )
    val sections = listOf(
        DeltaKroneckerFeeds.Kind.SUBSCRIPTION to if (isFarsi) "📥 اشتراک کانفیگ (اضافه می‌شود به فهرست سرورها)" else "📥 Config subscriptions (added to the server list)",
        DeltaKroneckerFeeds.Kind.AETHER to if (isFarsi) "🛡 Cat Warp" else "🛡 Cat Warp",
        DeltaKroneckerFeeds.Kind.DNS_TUNNEL to if (isFarsi) "🧵 تونل DNS (وقتی تقریباً همه‌چیز بسته است)" else "🧵 DNS tunnels (when almost everything is blocked)",
        DeltaKroneckerFeeds.Kind.TOR_BRIDGES to if (isFarsi) "🧅 پل‌های Tor" else "🧅 Tor bridges",
        DeltaKroneckerFeeds.Kind.LINK to if (isFarsi) "🧰 ابزار" else "🧰 Tools",
    )
    for ((kind, header) in sections) {
        note(header, 14f, TEXT_PRIMARY, 14)
        for (feed in DeltaKroneckerFeeds.byKind(kind)) {
            box.addView(
                MaterialButton(this).apply {
                    text = if (isFarsi) feed.titleFa else feed.titleEn
                    isAllCaps = false
                    setOnClickListener {
                        dialog?.dismiss()
                        openDeltaKroneckerFeed(feed)
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
            note(if (isFarsi) feed.whatFa else feed.whatEn, 12f, TEXT_SECONDARY, 0)
        }
    }
    val built = MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "🔺 منابع Cat Feed" else "🔺 Cat Feed sources")
        .setView(ScrollView(this).apply { addView(box) })
        .setNeutralButton("GitHub") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DeltaKroneckerFeeds.GITHUB))) } }
        .setPositiveButton(if (isFarsi) "بستن" else "Close", null)
        .create()
    dialog = built
    built.showCatClientDialog()
}

internal fun MainActivity.openDeltaKroneckerFeed(feed: DeltaKroneckerFeeds.Feed) {
    when (feed.kind) {
        DeltaKroneckerFeeds.Kind.SUBSCRIPTION, DeltaKroneckerFeeds.Kind.AETHER, DeltaKroneckerFeeds.Kind.DNS_TUNNEL -> {
            openScreen(SCREEN_SERVERS)
            showAddSubscriptionDialog(feed.url, feed.subName)
        }
        DeltaKroneckerFeeds.Kind.TOR_BRIDGES -> fetchDeltaKroneckerTorBridges(feed)
        DeltaKroneckerFeeds.Kind.LINK -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(feed.url))) }
    }
}

/** Hourly-tested bridges → Aether's Tor «own bridges» (mode own); also copyable for Orbot / Tor Browser. */
internal fun MainActivity.fetchDeltaKroneckerTorBridges(feed: DeltaKroneckerFeeds.Feed) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(this, if (isFarsi) "⏳ گرفتن پل‌های تست‌شده…" else "⏳ Fetching tested bridges…", Toast.LENGTH_SHORT).show()
    activityScope.launch {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (java.net.URL(feed.url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Cat Client/${BuildConfig.VERSION_NAME}")
                }
                try {
                    if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
                } finally {
                    conn.disconnect()
                }
            }.getOrNull().orEmpty().ifBlank {
                LocalSniSpoofProxy.fetchGitHubRawOverSniSplit(feed.url, timeoutMs = 4_000).orEmpty()
            }
        }
        val lines = DeltaKroneckerFeeds.parseBridgeLines(text, 10)
        if (lines.isEmpty()) {
            MaterialAlertDialogBuilder(this@fetchDeltaKroneckerTorBridges)
                .setTitle(if (isFarsi) "❌ پلی گرفته نشد" else "❌ No bridges received")
                .setMessage(
                    if (isFarsi) "فهرست از GitHub نیامد (احتمالاً raw.githubusercontent.com روی خط شما بسته است). یک کانفیگ را وصل کنید و دوباره بزنید، یا «🧅 پل از Moat» را در بخش Aether امتحان کنید."
                    else "The list did not arrive from GitHub (raw.githubusercontent.com is probably blocked on your line). Connect a config and try again, or try «🧅 Moat bridges» in the Cat Warp section.",
                )
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .showCatClientDialog()
            return@launch
        }
        val joined = lines.joinToString("\n")
        MaterialAlertDialogBuilder(this@fetchDeltaKroneckerTorBridges)
            .setTitle((if (isFarsi) "🧅 " + lines.size + " پل سالم" else "🧅 " + lines.size + " working bridges"))
            .setMessage(
                (if (isFarsi) "«✅ استفاده در Cat Warp» این پل‌ها را در Cat Warp ← Tor به‌عنوان «پل‌های خودم» می‌گذارد؛ دفعهٔ بعد که Tor (یا زنجیرهٔ warp → tor) را وصل کنید با همین‌ها وصل می‌شود.\n\n"
                else "«✅ Use in Cat Warp» puts these bridges in Cat Warp → Tor as «my own bridges»; the next time you connect Tor (or the warp → tor chain) it uses them.\n\n") + joined,
            )
            .setPositiveButton(if (isFarsi) "✅ استفاده در Cat Warp" else "✅ Use in Cat Warp") { _, _ ->
                val cur = aetherPreferenceStore.read()
                aetherPreferenceStore.save(cur.copy(torBridgesMode = "own", torCustomBridges = joined))
                renderAdvancedControls()
                Toast.makeText(
                    this@fetchDeltaKroneckerTorBridges,
                    if (isFarsi) "✅ در Cat Warp ← Tor ذخیره شد (پل‌های خودم)" else "✅ Saved in Cat Warp → Tor (own bridges)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setNeutralButton(if (isFarsi) "📋 کپی (Orbot / Tor Browser)" else "📋 Copy (Orbot / Tor Browser)") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tor-bridges", joined))
                Toast.makeText(this@fetchDeltaKroneckerTorBridges, R.string.free_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }
}

/**
 * 1-Tap 3-Layer Smart Iran Banking & Domestic App Shield (`MLM v1.2.41` equivalent):
 * Detects all installed Iranian banking, payment, taxi, messenger, and `.ir` apps and sets
 * `SplitTunnelMode.BypassSelected` so Iranian banks never complain that a VPN is active.
 */
internal fun MainActivity.runSmartIranBankingShieldDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    activityScope.launch {
        val (totalCount, detectedApps) = withContext(Dispatchers.IO) {
            MlmAndNextGenVpnHub.enableSmartIranBankingAppShield(this@runSmartIranBankingShieldDialog)
        }
        val appNamesPreview = detectedApps.take(12).joinToString("، ") { it.label }
            .ifBlank { if (isFarsi) "بانک‌ها، اسنپ، تپسی، دیوار، بله، روبیکا، بازار، مایکت" else "Iranian banking & domestic apps" }
        val msg = if (isFarsi) {
            "✅ سپر هوشمند برنامه‌های بانکی و ایرانی فعال شد!\n\n" +
                "• تعداد کل بسته‌های مستثناشده از تونل VPN: $totalCount برنامه\n" +
                "• برنامه‌های شناسایی‌شده روی گوشی شما: $appNamesPreview\n\n" +
                "از این لحظه وقتی VPN روشن است، همراه بانک‌ها، اسنپ، دیوار، بله، روبیکا، بازار و مایکت کاملاً مستقیم و با اینترنت ایران کار می‌کنند و هرگز خطای «فیلترشکن خود را خاموش کنید» نمی‌دهند."
        } else {
            "✅ 3-Layer Smart Iran Banking & Domestic App Shield enabled!\n\n" +
                "• Total excluded packages: $totalCount apps\n" +
                "• Detected on your device: $appNamesPreview\n\n" +
                "Your Iranian banking, payment, and domestic apps now bypass the VPN tunnel completely."
        }
        MaterialAlertDialogBuilder(this@runSmartIranBankingShieldDialog)
            .setTitle(
                if (isFarsi) "🏦 سپر هوشمند همراه بانک‌ها و برنامه‌های ایرانی"
                else "🏦 Smart Iran Banking & Domestic App Shield",
            )
            .setMessage(msg)
            .setNeutralButton(
                if (isFarsi) "🚀 مرکز ۴ قابلیت طلایی" else "🚀 4 Power Features",
            ) { _, _ ->
                showFourPowerFeaturesCenterDialog()
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .showCatClientDialog()
        if (currentVpnServiceState == VpnState.Started) {
            reconnectForConnectionOptionChange()
        }
    }
}

/**
 * ⚡ Multi-Config Parallel Speed Fusion & Zero-Drop Autopilot Dialog:
 * Combines the user's heterogeneous configs (SlipNet, VLESS, Trojan, PaqetNG, Firefox VPN,
 * CottenMax, TUIC, WireGuard) into a single Mihomo YAML subscription featuring simultaneous
 * Round-Robin Load-Balancing, 15s Zero-Drop Failover, and Fastest URL-Test.
 */
internal fun MainActivity.isFusedSubscriptionName(name: String): Boolean =
    name.startsWith("⚡ تانل ترکیبی") || name.startsWith("⚡ Fused") || name.startsWith("📡 تانل سبز") || name.startsWith("📡 Green")

/** Raw texts of every user subscription (inline links + cached YAML snapshots) and, optionally, the clipboard. */
/** Clipboard text that looks small enough to be configs (UI thread). */
internal fun MainActivity.readClipboardConfigText(): String? = runCatching {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        .takeIf { it.isNotBlank() && it.length < UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES }
}.getOrNull()

/** Blocking (reads + parses every cached subscription): call on Dispatchers.IO. */
internal fun MainActivity.collectFusionRawTexts(clipboardText: String?): List<String> {
    val rawTexts = mutableListOf<String>()
    userSubscriptionManager.list().forEach { sub ->
        if (isFusedSubscriptionName(sub.name)) return@forEach
        if (sub.input.isNotBlank() && !sub.input.startsWith("http://", true) && !sub.input.startsWith("https://", true)) {
            rawTexts.add(sub.input)
        }
        userSubscriptionManager.cachedSnapshot(sub.id)?.rawConfig?.takeIf { it.isNotBlank() }?.let { rawTexts.add(it) }
    }
    clipboardText?.takeIf { it.isNotBlank() }?.let { rawTexts.add(0, it) }
    return rawTexts
}

internal fun MainActivity.showMultiConfigSpeedFusionDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val clipText = readClipboardConfigText()

    activityScope.launch {
        val bundle = withContext(Dispatchers.IO) {
            val rawTexts = collectFusionRawTexts(clipText)
            LocalSniSpoofProxy.buildFusedMultiTunnelSubscription(rawTexts, maxProxies = 12)
        }
        if (bundle == null || bundle.totalProxiesFused == 0) {
            Toast.makeText(
                this@showMultiConfigSpeedFusionDialog,
                if (isFarsi) "⚠️ ابتدا حداقل یک اشتراک یا کانفیگ (در اپ یا کلیپ‌بورد) داشته باشید."
                else "⚠️ Please add at least one subscription or copy a config first.",
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        val msg = if (isFarsi) {
            buildString {
                appendLine("⚡ ترکیب موازی چند کانفیگ (Multi-Tunnel Speed Fusion) آماده شد!")
                appendLine("• تعداد کانفیگ‌های ادغام‌شده: ${bundle.totalProxiesFused} کانفیگ")
                appendLine("• ترکیب پروتکل‌ها: ${bundle.protocolSummary}")
                appendLine()
                appendLine("🎯 با فعال‌سازی این پروفایل، ۳ موتور هم‌زمان برای شما ساخته می‌شود:")
                appendLine("۱) ⚡ ترکیب موازی سرعت (Round-Robin Load-Balance): پخش هم‌زمان کانکشن‌ها روی همهٔ تانل‌ها برای حداکثر سرعت دانلود")
                appendLine("۲) 🤖 خلبان خودکار ضدقطع (Zero-Drop Failover 15s): سوییچ آنی در ۰ میلی‌ثانیه بدون قطع شدن VPN")
                appendLine("۳) 🚀 سریع‌ترین پینگ زنده (Auto URL-Test)")
                appendLine()
                appendLine("📋 کانفیگ‌های داخل تانل ترکیبی:")
                bundle.proxyNames.take(8).forEachIndexed { idx, name ->
                    appendLine("  ${idx + 1}. $name")
                }
            }
        } else {
            buildString {
                appendLine("⚡ Multi-Tunnel Speed Fusion Ready!")
                appendLine("• Fused Configs: ${bundle.totalProxiesFused}")
                appendLine("• Protocol Mix: ${bundle.protocolSummary}")
                appendLine()
                appendLine("🎯 Includes 3 simultaneous smart groups:")
                appendLine("1) ⚡ Parallel Load-Balance (Round-Robin)")
                appendLine("2) 🤖 Zero-Drop Failover Autopilot (15s)")
                appendLine("3) 🚀 Fastest Auto URL-Test")
            }
        }

        MaterialAlertDialogBuilder(this@showMultiConfigSpeedFusionDialog)
            .setTitle(
                if (isFarsi) "⚡ ترکیب موازی سرعت + خلبان خودکار ضدقطع"
                else "⚡ Multi-Config Speed Fusion & Autopilot",
            )
            .setMessage(msg.trimEnd())
            .setPositiveButton(
                if (isFarsi) "🚀 ساخت تانل ترکیبی و اتصال فوری" else "🚀 Activate Fusion & Connect",
            ) { _, _ ->
                activateFusedBundle(bundle, isFarsi, greenOnly = false)
            }
            .setNeutralButton(
                if (isFarsi) "📤 اشتراک با QR / لینک" else "📤 Share (QR / link)",
            ) { _, _ ->
                showCatFusionShareDialog(bundle, isFarsi)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }
}

/** Saves [bundle] as the single fused subscription, selects it and connects (or reconnects). */
internal fun MainActivity.activateFusedBundle(
    bundle: LocalSniSpoofProxy.FusedMultiTunnelBundle,
    isFarsi: Boolean,
    greenOnly: Boolean,
) {
    activityScope.launch {
        val subTitle = when {
            greenOnly && isFarsi -> "📡 تانل سبز ترکیبی (${bundle.totalProxiesFused} کانفیگ تست‌شده)"
            greenOnly -> "📡 Green fused tunnel (${bundle.totalProxiesFused} verified)"
            isFarsi -> "⚡ تانل ترکیبی موازی (${bundle.totalProxiesFused} کانفیگ)"
            else -> "⚡ Fused Multi-Tunnel (${bundle.totalProxiesFused} nodes)"
        }
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val existing = userSubscriptionManager.list().firstOrNull { isFusedSubscriptionName(it.name) }
                if (existing != null) userSubscriptionManager.update(existing.id, subTitle, bundle.yaml)
                else userSubscriptionManager.add(subTitle, bundle.yaml)
            }
        }.getOrElse { err ->
            Toast.makeText(
                this@activateFusedBundle,
                (if (isFarsi) "⚠️ ذخیرهٔ تانل ترکیبی ناموفق بود: " else "⚠️ Could not save fused tunnel: ") + (err.message ?: err.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        userSubscriptionManager.select(saved.id)
        val curAether = aetherPreferenceStore.read()
        if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
        orbotEnginePreferenceStore.setEnabled(false)
        renderSubscriptions()
        onSubscriptionSelected()
        openScreen(SCREEN_HOME)
        if (currentVpnServiceState == VpnState.Started) {
            reconnectForConnectionOptionChange()
        } else {
            beginConnectFlow(Actions.CONNECT)
        }
    }
}

/**
 * 📤 Exports a fused multi-tunnel as ONE `catfusion://` link + QR code. Scanning the QR with
 * Cat Client on another phone (or pasting the link into «➕ افزودن سابسکریپشن») rebuilds the
 * same 3-group fused profile there.
 */
internal fun MainActivity.showCatFusionShareDialog(bundle: LocalSniSpoofProxy.FusedMultiTunnelBundle, isFarsi: Boolean) {
    val title = if (isFarsi) "تانل ترکیبی ${bundle.totalProxiesFused} کانفیگ" else "Fused ${bundle.totalProxiesFused} configs"
    val link = CatFusionLink.encode(bundle.shareUris, title)
    if (link == null) {
        Toast.makeText(
            this,
            if (isFarsi) "⚠️ هیچ‌کدام از کانفیگ‌های این تانل لینک اشتراکی قابل‌انتقال ندارند."
            else "⚠️ None of the fused configs has a shareable link.",
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val skipped = bundle.totalProxiesFused - bundle.shareUris.size.coerceAtMost(CatFusionLink.MAX_LINKS)
    val qr = QrCodes.bitmap(link, sizePx = dp(280))
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(10), dp(16), dp(4))
    }
    if (qr != null) {
        container.addView(
            ImageView(this).apply {
                adjustViewBounds = true
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(Color.WHITE)
                setImageBitmap(qr)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    container.addView(
        TextView(this).apply {
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(8), 0, 0)
            text = buildString {
                if (isFarsi) {
                    appendLine("📱 روی گوشی دیگر: Cat Client ← «اسکن QR» یا کپی لینک ← «➕ افزودن سابسکریپشن».")
                    appendLine("همان تانل ترکیبی (موازی + ضدقطع + سریع‌ترین) آنجا ساخته می‌شود.")
                    if (qr == null) appendLine("⚠️ لینک برای QR خیلی بزرگ است؛ از دکمهٔ کپی/اشتراک استفاده کن.")
                    if (skipped > 0) appendLine("ℹ️ $skipped کانفیگ (مثل TUIC/SSH/WireGuard داخل YAML) لینک استاندارد ندارند و منتقل نمی‌شوند.")
                    append("🔒 این لینک شامل رمز/UUID کانفیگ‌های شماست؛ فقط با افراد مورد اعتماد به اشتراک بگذارید.")
                } else {
                    appendLine("📱 On another phone: Cat Client → Scan QR, or paste the link into “Add subscription”.")
                    if (qr == null) appendLine("⚠️ Link is too large for a QR code — use copy/share.")
                    if (skipped > 0) appendLine("ℹ️ $skipped configs have no standard share link and are not included.")
                    append("🔒 The link contains your config secrets — share only with people you trust.")
                }
            }
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "📤 اشتراک تانل ترکیبی (catfusion://)" else "📤 Share fused tunnel (catfusion://)")
        .setView(container)
        .setPositiveButton(if (isFarsi) "📋 کپی لینک" else "📋 Copy link") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("catfusion", link))
            Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton(if (isFarsi) "↗️ ارسال" else "↗️ Send") { _, _ ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, link)
            }
            runCatching { startActivity(Intent.createChooser(send, title)) }
                .onFailure { Toast.makeText(this, R.string.subscription_share_failed, Toast.LENGTH_SHORT).show() }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.runDirectCdnDomainFrontingV23Dialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال تست زندهٔ ۳ مسیر مستقیم Domain-Fronting v23 (یوتیوب، اینستاگرام/واتس‌اپ و رددیت/فستلی)…"
        else "⏳ Probing 3 direct Domain-Fronting v23 paths (YouTube, Instagram/WhatsApp & Reddit/Fastly)…",
        Toast.LENGTH_SHORT,
    ).show()

    activityScope.launch {
        val hits = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.probeDirectCdnDomainFrontingV23(timeoutMs = 1_100)
        }
        val msg = buildString {
            if (isFarsi) {
                appendLine("🌐 نتیجهٔ تست زندهٔ متد جدید Domain-Fronting v23 روی خط شما:")
                appendLine()
                hits.forEach { h ->
                    val status = if (h.rttMs != null) "🟢 باز (${h.rttMs}ms) · فورواردر محلی: 127.0.0.1:${h.localForwardPort}" else "🟡 نیازمند برش چندتکه‌ای"
                    appendLine("• ${h.titleFa}")
                    appendLine("   👈 وضعیت: $status | لبه: ${h.edgeIp}:${h.port}")
                    appendLine("   👈 گواهی/SNI: ${h.frontSni} (vcn=${h.verifyNamesCsv.take(42)}…)")
                    appendLine()
                }
                append("💡 با زدن دکمهٔ زیر، استراتژی فرانتینگ گواهی (fromMitM + برش چندتکه‌ای SNI) روی تانل شما فعال و متصل می‌شود.")
            } else {
                appendLine("🌐 Direct CDN Domain-Fronting v23 Live Probe:")
                appendLine()
                hits.forEach { h ->
                    val status = if (h.rttMs != null) "🟢 OPEN (${h.rttMs}ms) · Local Forwarder: 127.0.0.1:${h.localForwardPort}" else "🟡 Needs Multi-Chunk Split"
                    appendLine("• ${h.titleEn}: $status")
                }
            }
        }
        MaterialAlertDialogBuilder(this@runDirectCdnDomainFrontingV23Dialog)
            .setTitle(
                if (isFarsi) "🌐 متد جدید MITM-DomainFronting v23"
                else "🌐 New MITM-DomainFronting v23 Method",
            )
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (isFarsi) "🚀 فعال‌سازی فرانتینگ v23 و اتصال"
                else "🚀 Enable Fronting v23 & Connect",
            ) { _, _ ->
                activityScope.launch {
                    withContext(Dispatchers.IO) {
                        LocalSniSpoofProxy.startSpoofSocksProxy(
                            strategy = LocalSniSpoofProxy.SpoofStrategy.MITM_PINNED_CERT_FRONTING,
                            decoySni = "github.githubassets.com",
                        )
                    }
                    runOneClickRepairMyRealConfigAndConnect()
                }
            }
            .create()
            .showCatClientDialog()
    }
}

/**
 * Inspects the user's real subscriptions and deployed Worker to find their actual working
 * VLESS/Trojan server (`UUID`, `host`, `port`, `path`) so repair/spoof tools never use
 * fake placeholder UUIDs (`11111111-...`).
 */
internal suspend fun MainActivity.resolveUserRealConfigTemplate(): LocalSniSpoofProxy.RealConfigTemplate? =
    withContext(Dispatchers.IO) {
        val allSubs = userSubscriptionManager.list()
        val selectedId = userSubscriptionManager.selectedId()
        val orderedSubs = buildList {
            allSubs.firstOrNull { it.id == selectedId }?.let { add(it) }
            allSubs.forEach { if (it.id != selectedId) add(it) }
        }
        val candidateLines = mutableListOf<String>()
        for (sub in orderedSubs) {
            val snap = runCatching { userSubscriptionManager.cachedSnapshot(sub.id) }.getOrNull()
            snap?.catalog?.profiles?.forEach { p ->
                val link = p.shareLink?.trim().orEmpty()
                if (link.isNotEmpty()) candidateLines += link
            }
            sub.input.lines().forEach { line ->
                val t = line.trim()
                if (t.startsWith("vless://", ignoreCase = true) || t.startsWith("trojan://", ignoreCase = true)) {
                    candidateLines += t
                }
            }
        }
        val dep = runCatching { PanelDeploymentStore(this@resolveUserRealConfigTemplate).deployments().firstOrNull() }.getOrNull()
        val depHost = dep?.workerUrl
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.substringBefore("/")
            ?.trim()
        LocalSniSpoofProxy.extractRealConfigTemplate(
            rawLines = candidateLines,
            deployedUuid = dep?.uuid,
            deployedWorkerHost = depHost,
        )
    }

/**
 * Comprehensive 2026 Net Melli Bypass Lab combining:
 * 0) 1-Click Automatic Repair & Connect on User's Real Config
 * 1) Non-Cloudflare & Domestic Whitelist CDN Scanner (`Akamai`, `Fastly`, `Google`, `Azure`, `Arvan`, `Derak`) preserving exact `IP:port`
 * 2) `Fact-A DNS Non-Hijack SNI Oracle`
 * 3) In-App `LocalSniSpoofProxy` (`127.0.0.1` direct TCP forwarder + SOCKS5)
 * 4) The 5 Cloudflare Spoofing Methods applied directly to the user's real config
 * 5) 2026 Hybrid Net Melli Tunnels (`QS-Tunnel`, `QQ-Tunnel`, `Spoof-Tunnel`, `S3 Bypass / Fedarisha`)
 */
internal fun MainActivity.showNetMelli2026BypassLabDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val proxyRunning = LocalSniSpoofProxy.isRunning()
    val proxyStatus = if (proxyRunning) {
        if (isFarsi) "🟢 روشن (SNI: ${LocalSniSpoofProxy.currentDecoySni})"
        else "🟢 Running (SNI: ${LocalSniSpoofProxy.currentDecoySni})"
    } else {
        if (isFarsi) "⚪ آماده به‌کار"
        else "⚪ Ready"
    }

    val options = if (isFarsi) {
        arrayOf(
            "🪄 ۰) تعمیر خودکار کانفیگ من و اتصال فوری با یک کلیک (پیشنهادی — بدون نیاز به تنظیم فنی!)",
            "🛰️ ۱) اسکن زنده آی‌پی‌های باز غیرکلادفلر (Akamai · Fastly · Google · Arvan) و اتصال روی کانفیگ من",
            "🔎 ۲) کشف خودکار سایت‌های سفید باز روی خط من (Fact-A Oracle) و اعمال روی اتصال",
            "🛡️ ۳) روشن کردن برش بسته TLS / تزریق SNI سفید و اتصال فوری ($proxyStatus)",
            "☁️ ۴) اعمال ۵ روش اسپوفینگ کلادفلر روی کانفیگ واقعی من و اتصال فوری",
            "🚀 ۵) اتصال با ۴ تانل هیبریدی نت ملی ۲۰۲۶ (S3 Bypass · QS-Tunnel · QQ-Tunnel · Spoof-Tunnel)",
        )
    } else {
        arrayOf(
            "🪄 0) 1-Click Auto-Repair My Real Config & Connect Immediately (Recommended!)",
            "🛰️ 1) Live Scan Open Non-Cloudflare CDNs (Akamai · Fastly · Google · Arvan) & Connect My Config",
            "🔎 2) Find Unblocked Whitelist Domains on My ISP (Fact-A Oracle) & Apply",
            "🛡️ 3) Enable Local TLS SNI Split / Fake ClientHello & Connect Now ($proxyStatus)",
            "☁️ 4) Apply the 5 Cloudflare Spoofing Methods to My Real Config & Connect",
            "🚀 5) Connect via 2026 Hybrid Net Melli Tunnels (S3 Bypass · QS · QQ · Spoof)",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🧪 آزمایشگاه نت ملی و ۵ روش اسپوف کلادفلر (اتصال واقعی)"
            else "🧪 Net Melli & 5 CF Spoof Lab (Real 1-Click Connect)",
        )
        .setItems(options) { _, which ->
            when (which) {
                0 -> runOneClickRepairMyRealConfigAndConnect()
                1 -> runNonCloudflareWhitelistScannerDialog()
                2 -> runFactADnsSniOracleDialog()
                3 -> showLocalFakeClientHelloProxyDialog()
                4 -> showCloudflareFiveSpoofMethodsDialog()
                5 -> generateAndAddHybridNetMelliTunnelsDialog()
            }
        }
        .setNeutralButton(if (isFarsi) "📖 راهنمای ساده" else "📖 Simple Guide") { _, _ ->
            showPlainLanguageFullGuideDialog()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.runNonCloudflareWhitelistScannerDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال تست واقعی TCP+TLS روی CDNهای غیرکلادفلر و داخلی (Akamai, Fastly, Google, Azure, Arvan, Derak)…"
        else "⏳ Probing Non-Cloudflare & domestic whitelist CDNs over TCP+TLS…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val results = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.scanNonCloudflareWhitelistTargets(timeoutMs = 1100)
        }
        val realTemplate = resolveUserRealConfigTemplate()
        val report = buildString {
            if (isFarsi) {
                appendLine("🛰️ نتیجه تست واقعی بانک وایت‌لیست غیرکلادفلر و داخلی (با حفظ دقیق IP:port):")
                if (realTemplate != null) {
                    appendLine("✅ کانفیگ واقعی شناسایی‌شده: ${realTemplate.remark} (${realTemplate.hostHeader})")
                } else {
                    appendLine("💡 کانفیگ شخصی پیدا نشد؛ با زدن دکمه زیر، اتصال بدون سرور روی آی‌پی باز فعال می‌شود.")
                }
                appendLine("────────────────────────")
            } else {
                appendLine("🛰️ Live Non-Cloudflare & Domestic Whitelist Scan (preserving exact IP:port):")
                appendLine("────────────────────────")
            }
            if (results.isEmpty()) {
                appendLine(if (isFarsi) "⚠️ هیچ هدفی در این لحظه پاسخ نداد." else "⚠️ No target responded right now.")
            } else {
                results.forEachIndexed { idx, r ->
                    val t = r.target
                    val status = if (r.tlsVerified) {
                        "🟢 TCP+TLS OK (${r.effectiveRttMs} ms)"
                    } else {
                        "🟡 TCP OPEN (${r.tcpRttMs} ms)"
                    }
                    val title = if (isFarsi) t.provider.titleFa else t.provider.titleEn
                    appendLine("${idx + 1}. $title")
                    appendLine("   • IP:port = ${t.exactEndpoint} · SNI = ${t.sni}")
                    appendLine("   • Status: $status")
                    appendLine()
                }
            }
        }
        MaterialAlertDialogBuilder(this@runNonCloudflareWhitelistScannerDialog)
            .setTitle(
                if (isFarsi) "🛰️ اسکن وایت‌لیست غیرکلادفلر (${results.size} باز)"
                else "🛰️ Non-Cloudflare Whitelist Scan (${results.size} Open)",
            )
            .setMessage(report.trim())
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "🦊 اتصال با فایرفاکس Fastly" else "🦊 Connect via Firefox Fastly") { _, _ ->
                val fastlyHit = results.firstOrNull { it.target.provider == LocalSniSpoofProxy.NonCfCdnProvider.FASTLY_GITHUB }
                if (fastlyHit != null) {
                    FirefoxVpnEngine.saveCleanFastlyEdgeIp(this@runNonCloudflareWhitelistScannerDialog, fastlyHit.target.ip)
                }
                connectFirefoxVpnDirectly()
            }
            .setPositiveButton(
                if (isFarsi) "🚀 تعمیر کانفیگ من با این IP:portها و اتصال" else "🚀 Repair My Config & Connect",
            ) { _, _ ->
                runOneClickRepairMyRealConfigAndConnect()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.runFactADnsSniOracleDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال اجرای اوراکل Fact-A DNS برای کشف SNIهای غیراسبوف و باز در DPI…"
        else "⏳ Running Fact-A DNS Oracle to find unhijacked & open SNIs…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val results = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.probeFactADnsOracle(timeoutMs = 850)
        }
        val cleanSnis = results.filter { !it.poisoned }
        val text = buildString {
            if (isFarsi) {
                appendLine("🔎 نتیجه اوراکل Fact-A DNS Non-Hijack SNI Finder:")
                appendLine("💡 هر سایتی که جلوی آن 🟢 سبز خورده یعنی فایروال خط شما آن را مسدود نکرده است:")
                appendLine("────────────────────────")
            } else {
                appendLine("🔎 Fact-A DNS Non-Hijack SNI Finder Results:")
                appendLine("────────────────────────")
            }
            results.forEach { r ->
                val badge = when {
                    r.poisoned -> "🔴 مسموم در DNS (${r.resolvedIp})"
                    r.tlsPort443Open -> "🟢 سفید و باز (IP: ${r.resolvedIp} · TLS: ${r.tlsRttMs}ms)"
                    else -> "🟡 بدون جعل DNS (IP: ${r.resolvedIp} · DNS: ${r.dnsRttMs}ms)"
                }
                appendLine("• ${r.domain} -> $badge")
            }
        }
        val bestSni = cleanSnis.firstOrNull()?.domain ?: "ebanksepah.ir"
        MaterialAlertDialogBuilder(this@runFactADnsSniOracleDialog)
            .setTitle(
                if (isFarsi) "🔎 اوراکل Fact-A (${cleanSnis.size} دامنه سفید تایید شد)"
                else "🔎 Fact-A Oracle (${cleanSnis.size} Clean SNIs Verified)",
            )
            .setMessage(text.trim())
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (isFarsi) "🚀 اعمال «$bestSni» و اتصال فوری"
                else "🚀 Apply '$bestSni' & Connect Now",
            ) { _, _ ->
                LocalSniSpoofProxy.updateDecoySni(bestSni)
                val cur = aetherPreferenceStore.read()
                aetherPreferenceStore.save(cur.copy(masqueSni = bestSni))
                runOneClickRepairMyRealConfigAndConnect()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.showLocalFakeClientHelloProxyDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val strategies = LocalSniSpoofProxy.SpoofStrategy.entries
    val labels = strategies.map { s ->
        val t = if (isFarsi) s.titleFa else s.titleEn
        val d = if (isFarsi) s.descriptionFa else s.descriptionEn
        "$t\n$d"
    }.toTypedArray()

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🛡️ انتخاب روش برش بسته TLS و اتصال فوری کانفیگ شما"
            else "🛡️ Select Local TLS SNI Split Strategy & Connect Now",
        )
        .setItems(labels) { _, idx ->
            val chosen = strategies[idx]
            LocalSniSpoofProxy.startSpoofSocksProxy(
                strategy = chosen,
                decoySni = LocalSniSpoofProxy.currentDecoySni,
            )
            runOneClickRepairMyRealConfigAndConnect(preferredStrategy = chosen)
        }
        .setNeutralButton(if (isFarsi) "⏹ توقف پروکسی محلی" else "⏹ Stop Local Proxy") { _, _ ->
            LocalSniSpoofProxy.stopAllSpoofProxies()
            Toast.makeText(
                this,
                if (isFarsi) "⏹ پروکسی محلی Fake ClientHello متوقف شد" else "⏹ Stopped Local Fake ClientHello Proxy",
                Toast.LENGTH_SHORT,
            ).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.generateAndAddHybridNetMelliTunnelsDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val rawBundle = LocalSniSpoofProxy.buildHybridNetMelliTunnelConfigs()
    val uriLines = rawBundle.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🚀 تانل‌های هیبریدی نت ملی (S3 Bypass · QS · QQ · Spoof)"
            else "🚀 Hybrid Net Melli Tunnels (S3 Bypass · QS · QQ · Spoof)",
        )
        .setMessage(rawBundle.trim())
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "📋 کپی همه لینک‌ها" else "📋 Copy All Links") { _, _ ->
            val raw = uriLines.joinToString("\n")
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hybrid-netmelli-tunnels", raw))
            Toast.makeText(
                this,
                if (isFarsi) "✅ هر ۴ لینک تانل هیبریدی نت ملی کپی شد" else "✅ Copied all 4 Hybrid Net Melli tunnel URIs",
                Toast.LENGTH_LONG,
            ).show()
        }
        .setPositiveButton(if (isFarsi) "🚀 راه‌اندازی و اتصال فوری" else "🚀 Start & Connect Now") { _, _ ->
            val raw = uriLines.joinToString("\n")
            val title = "🚀 تانل‌های هیبرید نت ملی (S3/QS/QQ/Spoof)"
            activityScope.launch {
                withContext(Dispatchers.IO) {
                    uriLines.forEach { uri ->
                        WhiteDnsSmartScanner.parseTunnelUri(uri)?.let { parsed ->
                            WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
                        }
                    }
                }
                val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                val saved = withContext(Dispatchers.IO) {
                    if (existing != null) {
                        userSubscriptionManager.update(existing.id, title, raw)
                    } else {
                        userSubscriptionManager.add(title, raw)
                    }
                }
                userSubscriptionManager.select(saved.id)
                val curAether = aetherPreferenceStore.read()
                if (curAether.enabled) {
                    aetherPreferenceStore.save(curAether.copy(enabled = false))
                }
                orbotEnginePreferenceStore.setEnabled(false)
                renderSubscriptions()
                onSubscriptionSelected()
                renderAdvancedControls()
                Toast.makeText(
                    this@generateAndAddHybridNetMelliTunnelsDialog,
                    if (isFarsi) "🚀 ۴ تانل هیبریدی نت ملی (S3 Bypass / QS / QQ / Spoof) فعال شد — در حال اتصال…"
                    else "🚀 4 Hybrid Net Melli tunnels activated — connecting…",
                    Toast.LENGTH_LONG,
                ).show()
                openScreen(SCREEN_HOME)
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                } else {
                    beginConnectFlow(Actions.CONNECT)
                }
            }
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.runRegionalFirewallFingerprinterDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال تست نوع فایروال واقعی منطقه شما (IPv6 کلودفلر، 188.114.98.0، MASQUE و ۶ پورت TLS)…"
        else "⏳ Fingerprinting your actual regional firewall (CF IPv6, 188.114.98.0, MASQUE & 6 TLS ports)…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val verdict = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.detectRegionalFirewallProfile()
        }
        val title = if (isFarsi) verdict.type.titleFa else verdict.type.titleEn
        val detail = if (isFarsi) verdict.detailFa else verdict.detailEn
        val recEndpoint = when (verdict.type) {
            WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6 -> "[2606:4700:d0::a29f:c001]:2408"
            WhiteDnsSmartScanner.RegionalFirewallType.IRANCELL_FF -> "162.159.198.1:443"
            WhiteDnsSmartScanner.RegionalFirewallType.FIXED_MULTI_PORT -> "162.159.198.1:443"
        }
        val recMode = if (verdict.type == WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6) {
            AetherMode.WireGuard
        } else {
            AetherMode.MasqueHttp2
        }
        val recIpScan = if (verdict.type == WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6) "v6" else "both"
        MaterialAlertDialogBuilder(this@runRegionalFirewallFingerprinterDialog)
            .setTitle(title)
            .setMessage(detail)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(
                if (isFarsi) "⚡ بهینه‌سازی ساب فعلی" else "⚡ Optimize Active Sub",
            ) { _, _ ->
                val selectedId = userSubscriptionManager.selectedId()
                val activeSub = userSubscriptionManager.list().firstOrNull { it.id == selectedId }
                    ?: userSubscriptionManager.list().firstOrNull()
                if (activeSub != null) {
                    optimizeUserSubscription(activeSub)
                } else {
                    Toast.makeText(
                        this@runRegionalFirewallFingerprinterDialog,
                        if (isFarsi) "ابتدا یک سابسکریپشن اضافه کنید یا از دکمه اعمال روی Cat Warp استفاده کنید"
                        else "Add a subscription first or tap Apply to Cat Warp",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setPositiveButton(
                if (isFarsi) "🚀 اعمال تنظیم بهینه روی Cat Warp" else "🚀 Apply Optimal Cat Warp Config",
            ) { _, _ ->
                val cur = aetherPreferenceStore.read()
                val updated = cur.copy(
                    enabled = true,
                    mode = recMode,
                    ipScan = recIpScan,
                    endpoint = recEndpoint,
                    endpointScannedAt = System.currentTimeMillis(),
                    profileName = when (verdict.type) {
                        WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6 -> "MCI-IPv6-Auto"
                        WhiteDnsSmartScanner.RegionalFirewallType.IRANCELL_FF -> "Irancell-FF-Auto"
                        WhiteDnsSmartScanner.RegionalFirewallType.FIXED_MULTI_PORT -> "Fixed-ISP-Auto"
                    },
                )
                aetherPreferenceStore.save(updated)
                renderAdvancedControls()
                Toast.makeText(
                    this@runRegionalFirewallFingerprinterDialog,
                    if (isFarsi) "✅ تنظیم بهینه «${updated.profileName}» ($recEndpoint) ذخیره و فعال شد"
                    else "✅ Applied optimal profile '${updated.profileName}' ($recEndpoint)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.runWhiteDnsAutoMtuAndCarrierDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال تست طول QName، رکوردهای حامل (NULL 1232B / HTTPS 65 / TXT / CNAME) و ۵۲ سرور DNS…"
        else "⏳ Probing QName MTU, carrier records (NULL 1232B / HTTPS 65 / TXT / CNAME) & 52 resolvers…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val res = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.autoDetectBestWhiteDnsProfile()
        }
        val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
            resolverIp = res.resolver.primaryIp,
            transport = res.transport,
            recordMode = res.recordMode,
            preset = res.preset,
        )
        val msg = if (isFarsi) {
            "${res.summaryFa}\n\n" +
                "🔗 لینک CottenMax پیشنهادی:\n${bundle.cottenMaxUri}\n\n" +
                "🔗 لینک StormDNS پیشنهادی:\n${bundle.stormDnsUri}"
        } else {
            "${res.summaryEn}\n\n" +
                "🔗 Recommended CottenMax URI:\n${bundle.cottenMaxUri}\n\n" +
                "🔗 Recommended StormDNS URI:\n${bundle.stormDnsUri}"
        }
        MaterialAlertDialogBuilder(this@runWhiteDnsAutoMtuAndCarrierDialog)
            .setTitle(
                if (isFarsi) "⚡ نتیجه تشخیص خودکار MTU و رکورد حامل Cat DNS"
                else "⚡ Cat DNS Auto-MTU & Carrier Detection Result",
            )
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "📋 کپی بسته تانل" else "📋 Copy Tunnel Bundle") { _, _ ->
                val text = buildString {
                    appendLine(bundle.cottenMaxUri)
                    appendLine(bundle.stormDnsUri)
                    appendLine(bundle.soarUri)
                    append(bundle.dnsttUdpBridges.first())
                }
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("whitedns-autotune", text))
                Toast.makeText(
                    this@runWhiteDnsAutoMtuAndCarrierDialog,
                    if (isFarsi) "✅ بسته تانل بهینه کپی شد" else "✅ Copied optimal tunnel bundle",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setPositiveButton(if (isFarsi) "✅ اعمال ریزولور روی DNS اپ" else "✅ Apply Resolver to App DNS") { _, _ ->
                val r = res.resolver
                if (r.dotEndpoint.isNotEmpty()) {
                    runCatching { dnsPrivacyPreferenceStore.saveDotEndpoint(r.dotEndpoint) }
                    dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoT)
                } else {
                    val url = r.dohUrl.ifEmpty { "https://178.22.122.100/dns-query" }
                    runCatching { dnsPrivacyPreferenceStore.saveDohUrl(url) }
                    dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoH)
                }
                renderDnsPrivacySelection()
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                }
                Toast.makeText(
                    this@runWhiteDnsAutoMtuAndCarrierDialog,
                    if (isFarsi) "✅ ریزولور بهینه «${r.nameFa}» فعال شد" else "✅ Enabled optimal resolver '${r.nameEn}'",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.runCloudflareSixPortsAndSniScanDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال تست ۶ پورت TLS کلودفلر (443, 2053, 2083, 2087, 2096, 8443) و SNI روی خط شما…"
        else "⏳ Scanning 6 Cloudflare TLS ports & SNI pool on your line…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val customSni = frontingIpPreferenceStore.readSniOverride().takeIf { it.isNotBlank() }
        val hits = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.scanCloudflareSixPortsAndSni(customSni = customSni)
        }
        if (hits.isEmpty()) {
            Toast.makeText(
                this@runCloudflareSixPortsAndSniScanDialog,
                if (isFarsi) "⚠️ روی این خط هیچ‌کدام از ۶ پورت TLS مستقیم پاسخ نداد؛ از «🤖 اسکن خودکار Cat Warp (WARP/MASQUE)» استفاده کنید."
                else "⚠️ No direct TLS port responded; use Cat Warp Auto-Scan (WARP/MASQUE).",
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        val best = hits.first()
        val lines = hits.take(6).mapIndexed { i, h ->
            val star = if (i == 0) " ⭐" else ""
            "${i + 1}) ${h.endpoint} · SNI: ${h.sni} (${h.rttMs}ms)$star"
        }.joinToString("\n")
        val msg = if (isFarsi) {
            "✅ بهترین پورت و آی‌پی فعال روی خط شما:\n" +
                "• آدرس دقیق (با حفظ پورت): ${best.endpoint}\n" +
                "• بهترین SNI: ${best.sni} (${best.rttMs}ms)\n\n" +
                "لیست پورت‌های باز روی اینترنت شما:\n$lines"
        } else {
            "✅ Best active IP:port & SNI on your line:\n" +
                "• Exact Endpoint: ${best.endpoint}\n" +
                "• Best SNI: ${best.sni} (${best.rttMs}ms)\n\n" +
                "Open ports on your network:\n$lines"
        }
        MaterialAlertDialogBuilder(this@runCloudflareSixPortsAndSniScanDialog)
            .setTitle(
                if (isFarsi) "🌐 نتیجه اسکن ۶ پورت TLS کلودفلر + SNI"
                else "🌐 6-Port Cloudflare TLS + SNI Scan Results",
            )
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "📋 کپی آدرس و SNI" else "📋 Copy IP:port & SNI") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cf-6port", "${best.endpoint}\nSNI: ${best.sni}"))
                Toast.makeText(this@runCloudflareSixPortsAndSniScanDialog, R.string.free_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(if (isFarsi) "✅ اعمال روی کانفیگ‌های ساب" else "✅ Apply to Subscription") { _, _ ->
                val prev = frontingIpPreferenceStore.readFrontingIp()
                frontingIps = FrontingIpPolicy.mergeNewestFirst(listOf(best.endpoint), frontingIps)
                renderFrontingIpChips()
                saveFrontingIps(reconnectIfChanged = true, previousValue = prev)
                Toast.makeText(
                    this@runCloudflareSixPortsAndSniScanDialog,
                    if (isFarsi) "✅ آدرس ${best.endpoint} با همان پورت دقیق اعمال شد"
                    else "✅ Applied exact endpoint ${best.endpoint}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.runFetchMoatTorBridgesDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال دریافت پل‌های جدید Tor از سرور رسمی Moat…"
        else "⏳ Fetching fresh Tor bridges from official Moat service…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val fetched = withContext(Dispatchers.IO) {
            AetherOperations.fetchMoatTorBridges("ir").getOrElse {
                WhiteDnsSmartScanner.buildDnsttBridgesForResolver("185.51.200.2")
            }
        }
        val text = fetched.joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("tor-moat-bridges", text))
        MaterialAlertDialogBuilder(this@runFetchMoatTorBridgesDialog)
            .setTitle(
                if (isFarsi) "🧅 پل‌های Tor کپی شد (${fetched.size} پل)"
                else "🧅 Tor Bridges Copied (${fetched.size} bridges)",
            )
            .setMessage(text)
            .apply {
                val usable = DeltaKroneckerFeeds.parseBridgeLines(text, 10)
                if (usable.isNotEmpty()) {
                    setPositiveButton(if (isFarsi) "✅ استفاده در Cat Warp" else "✅ Use in Cat Warp") { _, _ ->
                        aetherPreferenceStore.save(
                            aetherPreferenceStore.read().copy(torBridgesMode = "own", torCustomBridges = usable.joinToString("\n")),
                        )
                        renderAdvancedControls()
                    }
                } else {
                    setPositiveButton(android.R.string.ok, null)
                }
            }
            .setNeutralButton(if (isFarsi) "🔺 پل‌های ساعتی Cat Feed" else "🔺 Hourly Cat Feed bridges") { _, _ ->
                DeltaKroneckerFeeds.byKind(DeltaKroneckerFeeds.Kind.TOR_BRIDGES).firstOrNull()?.let { fetchDeltaKroneckerTorBridges(it) }
            }
            .create()
            .showCatClientDialog()
    }
}
