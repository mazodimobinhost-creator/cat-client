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
import com.cat.client.MainActivity.Companion.SCREEN_AETHER
import com.cat.client.MainActivity.Companion.SCREEN_CLOUD
import com.cat.client.MainActivity.Companion.SCREEN_SCANNER
import com.cat.client.MainActivity.Companion.SCREEN_SERVERS
import com.cat.client.MainActivity.FinderEntry

// Plain-language guides, recommended one-click wizard, feature finder, firewall guide, privacy policy.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

/**
 * Plain-language step-by-step quick guide card displayed at the top of the Anti-Censorship tab
 * so non-technical users immediately know which button to tap ("اینو بزنی این میشه، اونو بزنی این میشه").
 */
internal fun MainActivity.buildPlainLanguageQuickGuideCard(): View {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val card = advancedSettingsPanel()
    card.addView(
        TextView(this).apply {
            text = if (isFarsi) {
                "📖 راهنمای خیلی ساده (بدون دانش فنی: کدوم دکمه رو بزنم؟)"
            } else {
                "📖 Simple Step-by-Step Guide (Which button should I tap?)"
            }
            textSize = 15f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEAL)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    card.addView(
        TextView(this).apply {
            text = if (isFarsi) {
                "🟢 ۱) کانفیگ نداری و می‌خوای رایگان و سریع وصل بشی؟\n" +
                    "   👈 دکمه «🦊 اتصال فوری با Cat Fox VPN» یا «🚀 اتصال مستقیم Cat Warp» رو بزن؛ خودش سرور سالم پیدا می‌کنه و کل گوشی رو وصل می‌کنه.\n\n" +
                    "🟡 ۲) کانفیگ یا اشتراک داری ولی روی همراه اول / ایرانسل کار نمی‌کنه؟\n" +
                    "   👈 دکمه «🪄 تعمیر خودکار کانفیگ من و اتصال فوری» رو بزن؛ کانفیگ واقعی خودت رو برمی‌داره، قفل اپراتور رو با ۵ روش اسپوف دور می‌زنه و وصلش می‌کنه.\n\n" +
                    "🟠 ۳) وصل هستی ولی جمینی (Gemini)، چت‌جی‌پی‌تی یا یوتیوب باز نمیشه؟\n" +
                    "   👈 دکمه «✨ تست باز بودن Gemini» یا «🌍 کشور فایرفاکس / سایفون (آمریکا US)» رو بزن تا آی‌پی آمریکا برات فعال بشه.\n\n" +
                    "🔴 ۴) اینترنت کاملاً ملی شده و کلادفلر هم قطع شده؟\n" +
                    "   👈 دکمه «🧪 آزمایشگاه نت ملی» یا «📡 متدهای DNS و ریزولور» رو بزن تا از سایت‌های سفید (فستلی، آکامای، آروان و DNSهای باز) وصل بشی.\n\n" +
                    "🔵 ۵) نمی‌دونی روی خطت کدوم پروتکل بازه؟\n" +
                    "   👈 در «🚀 مرکز قابلیت‌های ۲۰۲۶» دکمه «📡 ۱۷) رادار یک‌لمسی» رو بزن؛ خط و تک‌تک کانفیگ‌هات واقعاً تست میشن، سبزها رو نشون میده و با یک دکمه فقط از سبزها تانل ترکیبی می‌سازه و وصل می‌کنه.\n\n" +
                    "🟣 ۶) می‌خوای تانل ترکیبی‌ات رو به گوشی دوستت بدی؟\n" +
                    "   👈 در «⚡ ۱۵) ترکیب موازی» دکمه «📤 اشتراک با QR / لینک» رو بزن؛ گوشی دوم فقط QR رو اسکن می‌کنه یا لینک catfusion:// رو در «➕ افزودن سابسکریپشن» می‌چسبونه."
            } else {
                "🟢 1) No config and want free 1-tap VPN?\n" +
                    "   👉 Tap '🦊 Connect Cat Fox VPN Now' or '🚀 Connect Cat Warp Now' — connects the whole phone automatically.\n\n" +
                    "🟡 2) Have a subscription/config that stopped working on MCI / Irancell?\n" +
                    "   👉 Tap '🪄 1-Click Repair My Config & Connect' — grabs your real config, applies anti-DPI spoofing, and connects.\n\n" +
                    "🟠 3) Connected, but Gemini, ChatGPT, or YouTube won't open?\n" +
                    "   👉 Tap '✨ Gemini Unblock Tester' or pick '🇺🇸 United States' in Cat Fox VPN / Psiphon.\n\n" +
                    "🔴 4) Complete Net Melli (International internet & Cloudflare blocked)?\n" +
                    "   👉 Tap '🧪 Net Melli Whitelist Lab' or '📡 DNS & Resolver Methods' to tunnel over whitelisted routes.\n\n" +
                    "🔵 5) Not sure which protocol is open on your line?\n" +
                    "   👉 In the 2026 hub tap '📡 17) 1-Tap Protocol Matrix' — real tests, then fuse only the green configs and connect.\n\n" +
                    "🟣 6) Want to give your fused tunnel to a friend?\n" +
                    "   👉 In '⚡ 15) Speed Fusion' tap '📤 Share (QR / link)' — the other phone scans it or pastes the catfusion:// link."
            }
            textSize = 12f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_PRIMARY)
            setLineSpacing(dp(2).toFloat(), 1.05f)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "🚀 اتصال جادویی ۱-کلیکی (⭐ پیشنهادی برای اینترنت شما — ساده‌ترین راه)"
            } else {
                "🚀 1-Click Magic Connect (⭐ Recommended for Your ISP)"
            }
            isAllCaps = false
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(48)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(TEAL)
            setTextColor(palette.onAccent)
            setOnClickListener { showRecommendedOneClickConnectWizardDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "📦 موتور Paqet و paqetNG (patth / patth ng v1.3.7 + بازیابی DNS)"
            } else {
                "📦 Paqet & paqetNG Engine (patth / patth ng v1.3.7 + DNS Recovery)"
            }
            isAllCaps = false
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, if (palette.isDark) 44 else 28))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            setTextColor(TEAL)
            setOnClickListener { showPaqetPatthEngineDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "⚡ ساخت ۱-کلیکی راهنمای Cat (MASQUE in MASQUE + HTTP/2 + Psiphon)"
            } else {
                "⚡ 1-Tap Cat Setup (MASQUE in MASQUE + HTTP/2 + Psiphon)"
            }
            isAllCaps = false
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(TEAL)
            setTextColor(BACKGROUND)
            setOnClickListener { runOneTapPattNgMimPsiphonSetup() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "➕ تنظیم دستی Cat Warp (کلید WARP · ۲-هاپ · سوییچ کشور سایفون)"
            } else {
                "➕ Add Cat Warp Config Manually (WARP Keys · 2-Hop · Country Switcher)"
            }
            isAllCaps = false
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, if (palette.isDark) 44 else 28))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            setTextColor(TEAL)
            setOnClickListener { showAddAetherDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "🩺 پزشک هوشمند تعمیر کانفیگ‌های من + موتور Cat FLUX (IPv4/IPv6)"
            } else {
                "🩺 Smart Config Doctor + Cat FLUX (IPv4/IPv6 Optimizer)"
            }
            isAllCaps = false
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, if (palette.isDark) 44 else 28))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            setTextColor(TEAL)
            setOnClickListener { showSmartConfigDoctorAndFluxDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "📖 باز کردن راهنمای کامل به زبان ساده (اینو بزنی این میشه!)"
            } else {
                "📖 Open Full Plain-Language Guide (What Every Button Does)"
            }
            isAllCaps = false
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, if (palette.isDark) 42 else 28))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            setTextColor(TEAL)
            setOnClickListener { showPlainLanguageFullGuideDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "🚀 مرکز ۴ قابلیت طلایی (ضد-فریز · تفکیک بانک/تلگرام · گیمینگ · هات‌اسپات QR و تعویض آی‌پی)"
            } else {
                "🚀 4 Power Features Hub (Auto-Heal · Smart Split · Gaming Booster · QR Hotspot & IP Switch)"
            }
            isAllCaps = false
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(46)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(TEAL)
            setTextColor(BACKGROUND)
            setOnClickListener { showFourPowerFeaturesCenterDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    card.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "🌐 مرکز همهٔ VPNهای ۲۰۲۶ (Outline · آمنزیا · PaqetNG · سپر بانک)"
            } else {
                "🌐 All-in-One 2026 VPN Hub (Outline · Amnezia · PaqetNG · Bank Shield)"
            }
            isAllCaps = false
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, if (palette.isDark) 44 else 28))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            setTextColor(TEAL)
            setOnClickListener { showAllVpns2026ComparisonAndMlmHubDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    return card
}

/**
 * Simple 1-Click Magic Connect Wizard with "⭐ پیشنهادی" (Recommended) badge for everyone (`MLM Scout v1.2.42`):
 * Probes the user's own configs, Firefox VPN (FENIX), MLM Quick Connect Pool, Serverless Aether, and PaqetNG
 * on the user's current network, highlights the best working option with "⭐ پیشنهادی", and connects in 1 tap.
 */
internal fun MainActivity.showRecommendedOneClickConnectWizardDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "🔍 رادار Cat Scout در حال تست تمام مسیرهای اتصال روی اینترنت فعلی شما..."
        else "🔍 Cat Scout probing all connection methods on your current ISP...",
        Toast.LENGTH_SHORT,
    ).show()

    activityScope.launch {
        val userLinks = withContext(Dispatchers.IO) {
            val store = SubscriptionStore(this@showRecommendedOneClickConnectWizardDialog)
            val selectedId = store.readSelectedSubscriptionId()
            val snap = userSubscriptionManager.cachedSnapshot(selectedId)
            val currentLinks = snap?.catalog?.profiles?.mapNotNull { it.shareLink }.orEmpty()
            if (currentLinks.isNotEmpty()) {
                currentLinks
            } else {
                userSubscriptionManager.list().flatMap { sub ->
                    userSubscriptionManager.cachedSnapshot(sub.id)?.catalog?.profiles?.mapNotNull { it.shareLink }.orEmpty()
                }
            }
        }
        val recommendations = withContext(Dispatchers.IO) {
            MlmAndNextGenVpnHub.runMlmScoutRecommendations(
                userLinks,
                timeoutMs = 1100,
                fenixSignedIn = FirefoxVpnEngine.loadAccounts(this@showRecommendedOneClickConnectWizardDialog).any {
                    it.enabled && (it.refreshToken.isNotBlank() || it.hasUnexpiredProxyPass())
                },
            )
        }
        if (recommendations.isEmpty()) return@launch

        val best = recommendations.first()
        val itemLabels = recommendations.map { rec ->
            "${rec.titleFa}\n   ⚡ ${rec.subtitleFa}"
        }.toTypedArray()

        fun activateRecommendation(rec: MlmAndNextGenVpnHub.MlmScoutRecommendation) {
            activityScope.launch {
                when (rec.category) {
                    "aether" -> {
                        val curAether = aetherPreferenceStore.read()
                        aetherPreferenceStore.save(curAether.copy(enabled = true))
                        renderAdvancedControls()
                        beginConnectFlow(if (buttonModel.state == VpnState.Started) Actions.RECONNECT else Actions.CONNECT)
                    }
                    "fenix" -> {
                        withContext(Dispatchers.IO) { FirefoxVpnEngine.startLocalBridge(this@showRecommendedOneClickConnectWizardDialog) }
                        val subTitle = "⭐ پیشنهادی · 🦊 Cat Fox VPN"
                        val saved = withContext(Dispatchers.IO) {
                            val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                            if (existing != null) userSubscriptionManager.update(existing.id, subTitle, rec.shareUriOrPayload)
                            else userSubscriptionManager.add(subTitle, rec.shareUriOrPayload)
                        }
                        userSubscriptionManager.select(saved.id)
                        onSubscriptionSelected()
                        if (buttonModel.state != VpnState.Started) beginConnectFlow(Actions.CONNECT)
                    }
                    "paqet" -> {
                        withContext(Dispatchers.IO) {
                            PaqetEngine.ensureLocalBridgeRunning(this@showRecommendedOneClickConnectWizardDialog, rec.shareUriOrPayload)
                        }
                        val subTitle = "⭐ پیشنهادی · 📦 PaqetNG (patth ng)"
                        val saved = withContext(Dispatchers.IO) {
                            val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                            if (existing != null) userSubscriptionManager.update(existing.id, subTitle, rec.shareUriOrPayload)
                            else userSubscriptionManager.add(subTitle, rec.shareUriOrPayload)
                        }
                        userSubscriptionManager.select(saved.id)
                        onSubscriptionSelected()
                        if (buttonModel.state != VpnState.Started) beginConnectFlow(Actions.CONNECT)
                    }
                    else -> {
                        val subTitle = if (rec.category == "user_config") {
                            "⭐ کانفیگ‌های تعمیرشده و بهینهٔ من"
                        } else {
                            "⭐ پیشنهادی · استخر اتصال سریع (Cat Pool)"
                        }
                        val saved = withContext(Dispatchers.IO) {
                            val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                            if (existing != null) userSubscriptionManager.update(existing.id, subTitle, rec.shareUriOrPayload)
                            else userSubscriptionManager.add(subTitle, rec.shareUriOrPayload)
                        }
                        userSubscriptionManager.select(saved.id)
                        onSubscriptionSelected()
                        if (buttonModel.state != VpnState.Started) beginConnectFlow(Actions.CONNECT)
                    }
                }
                Toast.makeText(
                    this@showRecommendedOneClickConnectWizardDialog,
                    if (isFarsi) "✅ در حال اتصال به: ${rec.titleFa}" else "✅ Connecting to: ${rec.titleFa}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        MaterialAlertDialogBuilder(this@showRecommendedOneClickConnectWizardDialog)
            .setTitle(
                if (isFarsi) "🚀 اتصال جادویی ۱-کلیکی (⭐ گزینهٔ پیشنهادی برای اینترنت شما)"
                else "🚀 1-Click Magic Connect (⭐ Recommended for Your ISP)",
            )
            .setItems(itemLabels) { _, which ->
                activateRecommendation(recommendations[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "🩺 تعمیر کانفیگ‌های من" else "🩺 Config Doctor") { _, _ ->
                showSmartConfigDoctorAndFluxDialog()
            }
            .setPositiveButton(
                if (isFarsi) "✅ اتصال فوری به گزینهٔ ⭐ پیشنهادی (${best.latencyMs}ms)"
                else "✅ Connect Now to ⭐ Recommended (${best.latencyMs}ms)",
            ) { _, _ ->
                activateRecommendation(best)
            }
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.finderEntries(): List<FinderEntry> = listOf(
    FinderEntry("🚀 فقط می‌خواهم وصل شوم", "🚀 I just want to connect",
        "برنامه خودش بهترین راه را روی اینترنت شما پیدا می‌کند و وصل می‌شود. اگر نمی‌دانید چه بزنید، همین را بزنید.",
        "The app finds the best way on your internet and connects. If unsure, tap this.",
        "وصل اتصال پیشنهادی ساده connect recommended easy start") { showRecommendedOneClickConnectWizardDialog() },
    FinderEntry("➕ کانفیگ یا لینک اشتراک دارم", "➕ I have a config or subscription link",
        "لینک vless / vmess / trojan / ss یا لینک سابسکریپشن را اضافه کنید.",
        "Add a vless / vmess / trojan / ss link or a subscription URL.",
        "افزودن اضافه لینک سابسکریپشن اشتراک add link subscription import") { openScreen(SCREEN_SERVERS); showAddSubscriptionDialog() },
    FinderEntry("🔐 کلید Amnezia (vpn://) یا AmneziaWG", "🔐 Amnezia key (vpn://) or AmneziaWG",
        "کلیدی که با vpn:// شروع می‌شود یا فایل ‎.conf را می‌خواند و وصل می‌شود.",
        "Reads a vpn:// key or a .conf file and connects.",
        "امنزیا آمنزیا امنیزیا amnezia awg wireguard وایرگارد conf vpn://") { showAmneziaSectionDialog() },
    FinderEntry("🌏 Cat SSTP (سرورهای رایگان vpn/vpn)", "🌏 Cat SSTP (free vpn/vpn servers)",
        "سرورهای رایگان SSTP ژاپن و کره؛ بدون حساب. فهرست تازه را می‌گیرد و کل گوشی را وصل می‌کند.",
        "Free SSTP servers in Japan/Korea, no account. Gets a fresh list and connects the whole phone.",
        "sstp اس اس تی پی وی پی ان گیت vpngate vpn gate ژاپن کره japan korea رایگان free ویندوز windows") { showCatSstpDialog() },
    FinderEntry("🦊 Cat Fox VPN (۵۰ گیگ رایگان)", "🦊 Cat Fox VPN (50 GB free)",
        "با حساب فایرفاکس، ماهی ۵۰ گیگ رایگان از سرورهای موزیلا. ورود به حساب لازم است.",
        "50 GB/month free on Mozilla servers with a Firefox account (sign-in needed).",
        "فایرفاکس موزیلا fenix firefox mozilla رایگان free") { showFirefoxVpnFenixHubDialog() },
    FinderEntry("🛡 هیچ‌چیز وصل نمی‌شود / اینترنت ملی", "🛡 Nothing connects / national internet",
        "بخش ضدسانسور: وارپ، سایفون، تور و روش‌های بدون سرور شخصی.",
        "Anti-censorship tab: WARP, Psiphon, Tor and server-less methods.",
        "ضدسانسور فیلتر ملی قطع وارپ سایفون تور warp psiphon tor aether censorship") { openScreen(SCREEN_AETHER) },
    FinderEntry("🌍 آی‌پی خارج با کشور دلخواه", "🌍 Foreign IP in a chosen country",
        "انتخاب کنید خروجی ایران باشد یا خارج (مثلاً آلمان) — از طریق سایفون.",
        "Choose an Iranian or foreign exit (e.g. Germany) via Psiphon.",
        "کشور آلمان خارج آیپی ip country germany exit psiphon تحریم sanction") { showAetherExitLocationPickerDialog() },
    FinderEntry("🩺 کانفیگم کار نمی‌کند، درستش کن", "🩺 My config does not work, fix it",
        "کانفیگ خود شما را با چند روش ضدفیلتر امتحان و تعمیر می‌کند و وصل می‌شود.",
        "Tries several anti-filter fixes on your own config and connects.",
        "تعمیر خراب درست repair fix broken") { runOneClickRepairMyRealConfigAndConnect() },
    FinderEntry("🧹 کانفیگ‌های مرده را پاک و بر اساس پینگ مرتب کن", "🧹 Remove dead configs, sort by ping",
        "همه را واقعی تست می‌کند، سوخته‌ها را کنار می‌گذارد و سریع‌ترها را بالا می‌آورد.",
        "Really tests all, drops dead ones and puts the fastest first.",
        "پینگ مرتب پاک سوخته ping sort clean dead") { runOneClickCleanAndSortConfigsDialog() },
    FinderEntry("📶 کدام پروتکل روی اینترنت من باز است؟", "📶 Which protocols are open on my internet?",
        "تست واقعی خط شما و تک‌تک کانفیگ‌هایتان؛ جدول سبز/قرمز به زبان ساده.",
        "Real test of your line and each config; simple green/red table.",
        "پروتکل اپراتور همراه ایرانسل protocol isp matrix") { showIspProtocolMatrixDialog() },
    FinderEntry("🚨 اینترنت ملی است یا کانفیگم خراب است؟", "🚨 National internet or broken config?",
        "تشخیص می‌دهد مشکل از قطعی اینترنت است یا از خود کانفیگ.",
        "Tells whether the internet is cut or the config is broken.",
        "ملی قطعی تشخیص net melli detect") { runNetMelliDetectorDialog() },
    FinderEntry("🔎 پیدا کردن آی‌پی تمیز (اسکنر)", "🔎 Find clean IPs (scanner)",
        "آی‌پی‌هایی را پیدا می‌کند که روی اینترنت شما باز هستند و سریع‌اند.",
        "Finds IPs that are open and fast on your internet.",
        "اسکن اسکنر آیپی تمیز کلودفلر scan scanner clean ip cloudflare") { openScreen(SCREEN_SCANNER) },
    FinderEntry("🧪 سایت‌ها و آی‌پی‌های سفید (نت ملی)", "🧪 Whitelisted sites & IPs (national net)",
        "وقتی فقط سایت‌های داخلی باز است: لیست سفید و روش‌های عبور.",
        "When only domestic sites work: whitelist and bypass methods.",
        "سفید وایت لیست ملی whitelist") { showNetMelli2026BypassLabDialog() },
    FinderEntry("🆓 کانفیگ رایگان", "🆓 Free configs",
        "از منبع‌های عمومی کانفیگ رایگان می‌گیرد و تست می‌کند.",
        "Gets free configs from public sources and tests them.",
        "رایگان مجانی free configs") { runFetchSevenGitHubFreeConfigSourcesDialog() },
    FinderEntry("☁️ ساخت سرور/پنل شخصی رایگان (کلادفلر)", "☁️ Build a free personal panel (Cloudflare)",
        "قدم‌به‌قدم یک پنل شخصی روی حساب کلادفلر خودتان می‌سازد.",
        "Builds a personal panel on your own Cloudflare account, step by step.",
        "پنل سرور شخصی کلادفلر ورکر panel worker cloudflare") { showPanelWizard() },
    FinderEntry("▲ پنل روی Vercel", "▲ Panel on Vercel",
        "پنل یا واسطه روی حساب Vercel شما، با اسکنر پینگ.",
        "Panel or relay on your Vercel account, with a ping scanner.",
        "ورسل vercel relay") { vercelRelay.showPanel() },
    FinderEntry("🖥 سرور Node (Bot-Hosting / Railway / KataBump / Lucity / Shiprr)", "🖥 Node server (Bot-Hosting / Railway / KataBump / Lucity / Shiprr)",
        "پنل روی هاست‌های Node. Bot-Hosting.net رایگان و کاملاً خودکار است: یک کلید بچسبانید، بقیه را برنامه انجام می‌دهد.",
        "Panel on Node hosts. Bot-Hosting.net is free and fully automatic: paste one key, the app does the rest.",
        "کاتابامپ نود هاست بات هاستینگ ریلوی katabump lucity shiprr node host bot-hosting bothosting railway") { openScreen(SCREEN_CLOUD) },
    FinderEntry("🔺 منابع Cat Feed", "🔺 Cat Feed sources",
        "کانفیگ‌های تست‌شده (VLESS/Trojan/SS/VMess/وارپ)، وارپ برای Cat Warp، تونل DNS، پل‌های Tor ساعتی و دانلود فایل از راه GitHub.",
        "Tested configs (VLESS/Trojan/SS/VMess/WARP), WARP for Cat Warp, DNS tunnels, hourly Tor bridges and file download through GitHub.",
        "دلتا کرونکر delta kronecker رایگان free tor bridge پل تور وارپ warp slipnet دانلود downloader") { showDeltaKroneckerHubDialog() },
    FinderEntry("⚡ تست سرعت", "⚡ Speed test", "سرعت دانلود و آپلود را از داخل تونل اندازه می‌گیرد.",
        "Measures download/upload speed through the tunnel.", "سرعت اسپید speed test") { showSpeedTestPage() },
    FinderEntry("⏱ پینگ و جیتر دقیق", "⏱ Precise ping & jitter", "پینگ و جیتر یک آی‌پی:پورت را روی خط خودت و از راه ورکر کنار هم نشان می‌دهد.",
        "Shows ping and jitter of one IP:port on your own line and through the worker, side by side.",
        "پینگ جیتر تاخیر ping jitter latency خط ورکر worker") { showPingJitterPage() },
    FinderEntry("🗺 نقشهٔ سرورها", "🗺 Server map", "سرورها را روی نقشه نشان می‌دهد.", "Shows servers on a map.",
        "نقشه map") { showMapPage() },
    FinderEntry("📡 پروکسی SOCKS5 / تلگرام", "📡 SOCKS5 / Telegram proxies", "پروکسی‌های عمومی را پیدا و تست می‌کند.",
        "Finds and tests public proxies.", "ساکس پروکسی تلگرام socks proxy telegram") { showSocks5HubPage() },
    FinderEntry("🧱 اسکنر ProxyIP", "🧱 ProxyIP scanner", "ProxyIPها را با پورت دقیق خودشان واقعی تست می‌کند.",
        "Really tests ProxyIPs on their exact ports.", "پروکسی آیپی proxyip") { showProxyIpScannerPage() },
    FinderEntry("📲 اشتراک اینترنت با گوشی/لپ‌تاپ دیگر", "📲 Share the connection with another device",
        "هات‌اسپات با QR تا دستگاه دیگر هم از همین اتصال استفاده کند.", "Hotspot with a QR so another device uses this connection.",
        "هات اسپات اشتراک لپتاپ hotspot share qr") { showEasyHotspotShareWithQrDialog() },
    FinderEntry("🍎 آیفون / آیپد", "🍎 iPhone / iPad", "راه وصل کردن آیفون با کانفیگ‌های همین برنامه.",
        "How to connect an iPhone with this app's configs.", "آیفون ios iphone ipad") { showIphoneIosConnectionHubDialog() },
    FinderEntry("✨ Gemini / ChatGPT / یوتیوب باز است؟", "✨ Are Gemini / ChatGPT / YouTube open?",
        "تست می‌کند این سرویس‌ها از داخل اتصال فعلی باز می‌شوند یا نه.", "Tests whether these services open through the current connection.",
        "جمینای چت جی پی تی یوتیوب gemini chatgpt youtube ai") { runAiAndGeminiUnblockTesterDialog() },
    FinderEntry("🏦 بانک و برنامه‌های ایرانی با VPN روشن", "🏦 Iranian banks & apps with VPN on",
        "برنامه‌های ایرانی مستقیم می‌روند تا با VPN روشن هم کار کنند.", "Iranian apps go direct so they work with the VPN on.",
        "بانک ایرانی bank iran direct") { runSmartIranBankingShieldDialog() },
    FinderEntry("🧭 فایروال همراه اول / ایرانسل", "🧭 MCI / Irancell firewall",
        "کدام روش روی کدام اپراتور جواب می‌دهد، به زبان ساده.", "Which method works on which operator, simply.",
        "همراه اول ایرانسل فایروال mci irancell firewall") { showFirewallConnectionGuideDialog() },
    FinderEntry("🐢 وسط کار قطع می‌شود یا گیر می‌کند", "🐢 It freezes or drops mid-use",
        "پیدا می‌کند اتصال کجا گیر می‌کند و راه درمان پیشنهاد می‌دهد.", "Finds where the connection stalls and suggests a fix.",
        "قطع گیر هنگ کند freeze drop stall") { runMidStreamFreezeDetectorDialog() },
    FinderEntry("🛟 اتصال پایدار و کیل‌سوییچ", "🛟 Stable connection & kill switch",
        "اگر اتصال افتاد، خودش دوباره وصل شود و اینترنت بی‌محافظ نماند.", "Reconnects by itself and never leaves you unprotected.",
        "کیل سوییچ پایدار kill switch resilience") { showConnectionResilienceAndKillSwitchDialog() },
    FinderEntry("📦 patth / paqet", "📦 patth / paqet", "موتورهای patth و paqet برای کانفیگ‌های همین نوع.",
        "patth and paqet engines for those configs.", "پت پکت patth paqet") { showPaqetPatthEngineDialog() },
    FinderEntry("🧵 تونل DNS (Cat DNS / dnstt)", "🧵 DNS tunnels (Cat DNS / dnstt)", "وقتی تقریباً همه‌چیز بسته است، از طریق DNS.",
        "When almost everything is blocked, over DNS.", "دی ان اس اسلیپ نت slipnet dnstt dns tunnel") { showWhiteDnsResolverMethodsDialog() },
    FinderEntry("🔗 دو سرور پشت سر هم (زنجیره)", "🔗 Two servers in a row (chain)", "ترافیک اول از یک سرور، بعد از سرور دوم می‌گذرد.",
        "Traffic goes through one server, then a second.", "زنجیره چین chain") { openConnectionChainSettingsFromHome() },
    FinderEntry("🔀 فقط بعضی برنامه‌ها از VPN بروند", "🔀 Only some apps through the VPN", "انتخاب حالت مسیریابی (همه، فقط خارجی‌ها، …).",
        "Choose the routing mode (all, foreign only, …).", "مسیر برنامه اسپلیت routing split apps") { showRoutingModeSelector() },
    FinderEntry("🌐 DNS امن", "🌐 Secure DNS", "DNS رمزدار (DoH / DoT) را انتخاب کنید.", "Pick encrypted DNS (DoH / DoT).",
        "دی ان اس dns doh dot") { showDnsPrivacySelector() },
    FinderEntry("🎨 تم و رنگ", "🎨 Theme & colour", "روشن/تیره و رنگ اصلی برنامه.", "Light/dark and accent colour.",
        "تم رنگ تیره theme color dark") { showThemeSelector() },
    FinderEntry("🌐 زبان", "🌐 Language", "فارسی یا انگلیسی.", "Persian or English.", "زبان language فارسی english") { showLanguageSelector() },
    FinderEntry("💾 پشتیبان از اشتراک‌ها", "💾 Back up subscriptions", "یک فایل از همهٔ اشتراک‌ها ذخیره می‌کند.",
        "Saves a file with all subscriptions.", "پشتیبان بکاپ backup") { appTools.startBackup() },
    FinderEntry("♻️ برگرداندن تنظیمات به حالت اول", "♻️ Reset settings", "تنظیمات به پیش‌فرض برمی‌گردد (اشتراک‌ها می‌مانند، اول تأیید می‌گیرد).",
        "Settings go back to default (asks first).", "ریست بازنشانی reset") { showResetSettingsDialog() },
    FinderEntry("📖 راهنمای کامل به زبان ساده", "📖 Full plain-language guide", "«اینو بزنی این می‌شه» برای همهٔ بخش‌ها.",
        "“Tap this, this happens” for every part.", "راهنما آموزش guide help") { showPlainLanguageFullGuideDialog() },
)

internal fun MainActivity.showFeatureFinderDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val entries = finderEntries()
    var dialog: AlertDialog? = null
    val search = EditText(this).apply {
        hint = if (isFarsi) "مثلاً: امنزیا، کشور، آیپی تمیز، رایگان…" else "e.g. amnezia, country, clean ip, free…"
        setSingleLine(true)
        textSize = 14f
    }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    fun render(query: String) {
        list.removeAllViews()
        val words = normalizeFinderText(query).split(' ').filter { it.isNotBlank() }
        val shown = entries.filter { e ->
            if (words.isEmpty()) return@filter true
            val hay = normalizeFinderText("${e.titleFa} ${e.titleEn} ${e.whatFa} ${e.whatEn} ${e.keywords}")
            words.all { it in hay }
        }
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (isFarsi) "چیزی پیدا نشد. کلمهٔ ساده‌تری بنویسید یا «📖 راهنمای کامل» را باز کنید." else "Nothing found. Try a simpler word."
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        shown.forEach { e ->
            list.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setSelectableBackground()
                    isClickable = true
                    isFocusable = true
                    addView(TextView(this@showFeatureFinderDialog).apply {
                        text = if (isFarsi) e.titleFa else e.titleEn
                        textSize = 14.5f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEXT_PRIMARY)
                    })
                    addView(TextView(this@showFeatureFinderDialog).apply {
                        text = if (isFarsi) e.whatFa else e.whatEn
                        textSize = 12.5f
                        setTextColor(TEXT_SECONDARY)
                        setLineSpacing(0f, 1.1f)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                    setOnClickListener {
                        dialog?.dismiss()
                        runCatching { e.open() }.onFailure { error ->
                            DiagnosticLogger.warn(this@showFeatureFinderDialog, "finder.open.failed", e.titleEn, error = error)
                        }
                    }
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }
    search.addTextChangedListener(DebouncedTextWatcher(search, delayMs = 150L) { text -> render(text) })
    render("")
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), 0)
        addView(search, LinearLayout.LayoutParams(-1, -2))
        addView(ScrollView(this@showFeatureFinderDialog).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(420)))
    }
    val built = MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "🧭 می‌خواهید چه کار کنید؟" else "🧭 What do you want to do?")
        .setView(box)
        .setNegativeButton(if (isFarsi) "بستن" else "Close", null)
        .create()
    dialog = built
    built.showCatClientDialog()
}

    /**
     * Full conversational guide dialog explaining every section and button in simple, everyday language.
     */
    internal fun MainActivity.showPlainLanguageFullGuideDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val guideText = if (isFarsi) {
            """
            👋 سلام! اگر با اصطلاحات فنی (مثل SNI، فرگمنت، وارپ، ساکس یا DNS) آشنا نیستی، اصلاً نگران نباش. اینجا به زبان خیلی ساده بهت می‌گیم هر دکمه دقیقاً چکار می‌کنه:

            ────────────────────────
            ✅ کارت «اینترنت واقعاً کار می‌کند؟» (زیر کانفیگ، بعد از وصل شدن)
            • این چیه؟ «وصل شد» یعنی تونل باز شد، نه اینکه حتماً سایت‌ها باز می‌شوند. ۲ ثانیه بعد از هر اتصال، برنامه خودش از داخل VPN یک صفحهٔ کوچک و یک فایل ۲۰۰ کیلوبایتی باز می‌کند و نتیجه را می‌گوید: ✅ کار می‌کند، 🐢 کند است/گیر می‌کند، ❌ اینترنت نمی‌آید.
            • در حالت «خودکار» اگر راهی اینترنت نداشت (دو بار تست)، خودش تا ۳ بار راه بعدی را امتحان می‌کند و یادش می‌ماند: روی همین شبکه (مثلاً همراه اول یا ایرانسل جدا، وای‌فای جدا) راه خراب را ۶ ساعت کنار می‌گذارد و راه سالم را دفعهٔ بعد اول امتحان می‌کند.
            • «⏭️ امتحان راه بعدی» رو بزنی چی میشه؟
              👈 بدون قطع کردن، می‌رود روی راه بعدی همین اشتراک (اگر نشد، دوباره وصل می‌شود و این راه را کنار می‌گذارد).
            • «🔁 دوباره» رو بزنی چی میشه؟
              👈 همین الان دوباره تست می‌کند.
            • «📨 گزارش» رو بزنی چی میشه؟
              👈 دقیقاً متنی را که فرستاده می‌شود نشانت می‌دهد (بدون آدرس سرور، UUID یا IP گوشی). فقط اگر «بفرست» را بزنی، به پنل خودت می‌رود تا صاحب پنل ببیند روی کدام اپراتور چه چیزی کار می‌کند. اگر نشد، متن کپی می‌شود.

            ────────────────────────
            🦊 ۱) کارت «Cat Fox VPN (۵۰ گیگ رایگان)»
            • این چیه؟ مرورگر معروف فایرفاکس یک VPN داخلی روی سرورهای شرکت Fastly داره که برخلاف کلادفلر تو ایران فیلتر نیست و به هر ایمیل رایگان ماهی ۵۰ گیگ حجم مجانی میده!
            • «🦊 اتصال فوری با Cat Fox VPN» رو بزنی چی میشه؟
              👈 سریع‌ترین سرور فایرفاکس رو انتخاب می‌کنه، قفل فیلترینگ رو می‌شکنه و کل اینترنت گوشیت رو وصل می‌کنه.
            • «👤 افزودن حساب ۵۰ گیگ» رو بزنی چی میشه؟
              👈 می‌تونی ایمیل و رمز حساب فایرفاکست رو بزنی (حتی می‌تونی ۲ یا ۳ تا اکانت رایگان اضافه کنی تا بشه ۱۵۰ گیگ در ماه و وقتی یکی تموم شد خودش بره رو بعدی!).

            ────────────────────────
            🌏 دکمه «Cat SSTP — سرورهای رایگان SSTP»
            • این چیه؟ همون VPN استاندارد ویندوز (SSTP) روی سرورهای رایگان داوطلبانه ژاپن و کره؛ بدون حساب، با vpn / vpn.
            • «🚀 دریافت و اتصال» رو بزنی چی میشه؟
              👈 فهرست تازه سرورها رو می‌گیره، اشتراک «🌏 Cat SSTP» رو می‌سازه و کل گوشی رو وصل می‌کنه؛ اگه یکی جواب نداد خودش تا ۶ تای بعدی رو امتحان می‌کنه.
            • گواهی این سرورها خودامضاست و صاحبش داوطلبه؛ برای کار حساس فقط سایت‌های https.

            ────────────────────────
            🪄 ۲) دکمه «تعمیر خودکار کانفیگ من و اتصال فوری»
            • کی بزنم؟ وقتی خودت کانفیگ یا اشتراک خریدی (یا ساختی)، ولی روی همراه اول یا ایرانسل وصل نمیشه یا سرعتش صفره.
            • اینو بزنی چی میشه؟
              👈 برنامه خودش می‌گرده کانفیگ واقعی تو رو پیدا می‌کنه (نه کانفیگ فرضی!)، آی‌پی‌های باز رو تست می‌کنه، بستهٔ اول اینترنت رو تکهتکه (برش SNI) می‌کنه تا فایروال مخابرات نفهمه کجا داری میری، و بلافاصله وصلت می‌کنه!

            ────────────────────────
            🚀 ۳) دکمه «اتصال مستقیم Cat Warp» و «⚡ ساخت ۱-کلیکی راهنمای Cat (MASQUE in MASQUE + HTTP/2 + Psiphon)»
            • کی بزنم؟ وقتی هیچ کانفیگ و اکانتی نداری و می‌خوای با روش جدید Cat (دو لایه MASQUE تو در تو روی HTTP/2 + خروج از سایفون آلمان/هلند/آمریکا) وصل بشی که روی شدیدترین فایروال‌های همراه اول و ایرانسل کار می‌کنه.
            • «⚡ ساخت ۱-کلیکی راهنمای Cat» رو بزنی چی میشه؟
              👈 تمام مراحل راهنمای Cat رو خودش اتوماتیک برات انجام میده: ۱) دو جفت کلید جدید WARP می‌گیره (و ۳ تا کلید زاپاس هم توی استخر ذخیره می‌کنه)، ۲) پروتکل رو روی MASQUE in MASQUE و ترنسپورت رو روی HTTP/2 می‌ذاره، ۳) خروجی رو روی WARP > Psiphon (یا زنجیره معکوس Psiphon > WARP برای نداشتن کپچای گوگل) می‌ذاره، ۴) هر دو اندپوینت Outer و Inner رو اسکن می‌کنه و ۵) کانفیگ رو تو لیست اصلی ذخیره و وصل می‌کنه!
            • «🌍 دکمه‌های پرچم آلمان/هلند/آمریکا/فنلاند/انگلیس» رو بزنی چی میشه؟
              👈 بدون نیاز به اسکن دوباره یا گرفتن کلید جدید، در جا کشور سایفونت رو عوض می‌کنه!

            ────────────────────────
            ✨ ۴) دکمه «تست باز بودن Gemini، ChatGPT و یوتیوب»
            • کی بزنم؟ وقتی VPN وصله ولی جمینی میگه «کشور شما پشتیبانی نمی‌شود» یا یوتیوب بالا نمیاد.
            • اینو بزنی چی میشه؟
              👈 هر ۴ سرویس هوش مصنوعی و یوتیوب رو تست می‌کنه و با یک کلیک آی‌پی آمریکا (US) رو برات فعال می‌کنه تا تحریم دور زده بشه.

            ────────────────────────
            🧪 ۵) دکمه «آزمایشگاه نت ملی و سایت‌های سفید»
            • کی بزنم؟ وقتی اینترنت خیلی خراب یا «نت ملی» شده و کلادفلر کامل بسته شده.
            • داخلش چی داره؟
              ۱. اسکنر سایت‌ها و شرکت‌های باز غیرکلادفلر (مثل Fastly، Akamai، گوگل و آروان) که کانفیگ واقعی خودت رو روی آی‌پی باز اون‌ها سوار می‌کنه و وصل می‌کنه.
              ۲. پروکسی محلی برش SNI که نام سایت‌های مجاز بانکی/داخلی رو جلوی دید فایروال می‌ذاره و ترافیک اصلیت رو رد می‌کنه.
              ۳. ۵ روش اسپوف کلادفلر (مخصوص دور زدن قفل همراه اول و ایرانسل روی کانفیگ خودت).

            ────────────────────────
            📡 ۶) دکمه «متدهای DNS و ریزولور (Cat DNS)»
            • کی بزنم؟ وقتی همهٔ پورت‌ها بسته شده و فقط پورت DNS کار می‌کنه.
            • «🚀 اتصال مستقیم تانل DNS» رو بزنی چی میشه؟
              👈 ۵۲ سرور DNS ایران و جهان رو بررسی می‌کنه و ترافیک رو از داخل بسته‌های DNS رد می‌کنه.

            ────────────────────────
            🧦 ۷) دکمه «بخش اختصاصی ساکس۵ (SOCKS5)»
            • اینو بزنی چی میشه؟
              👈 از ۷ منبع زنده دنیا پروکسی‌های سالم رو می‌گیره، پینگ می‌گیره و هم برای تلگرام و هم برای کل گوشی وصلشون می‌کنه.

            ────────────────────────
            🚀 ۸) دکمه «مرکز ۴ قابلیت طلایی (ضد-فریز · تفکیک بانک/تلگرام · گیمینگ · هات‌اسپات QR و تعویض آی‌پی)»
            • ۱) «⚡ تعویض فوری آی‌پی/لبهٔ تمیز» و «🔄 پایشگر خودکار ضد-فریز» رو بزنی چی میشه؟
              👈 بدون اینکه VPN قطع بشه، در زیر ۱ ثانیه آی‌پی کلودفلر رو عوض می‌کنه! و اگر روشنش بذاری، هر وقت مخابرات بعد از چند دقیقه اینترنت رو فریز کرد، خودش اتوماتیک تو پس‌زمینه آی‌پی رو عوض می‌کنه تا تلگرام و یوتیوبت قطع نشه!
            • ۲) «🏦 عبور مستقیم بانک‌ها، اسنپ، تپسی» یا «🎯 فقط تلگرام، اینستاگرام و یوتیوب روی VPN» رو بزنی چی میشه؟
              👈 با یک کلیک همراه بانک‌ها و اسنپ رو از فیلترشکن جدا می‌کنه تا دیگه هیچ‌وقت خطای «فیلترشکن را خاموش کنید» ندن، یا فقط تلگرام/اینستاگرام/یوتیوب رو روی VPN می‌ذاره تا حجمت نسوزه!
            • ۳) «🎮 بوستر گیمینگ و تماس ضد-پکت‌لاس» رو بزنی چی میشه؟
              👈 اولویت بسته‌های صدا و بازی رو بالا می‌بره و نمی‌ذاره آنتن 4G/5G سیم‌کارت به خواب بره تا وسط کالاف/پابجی یا تماس واتس‌اپ پینگت نپره!
            • ۴) «📲 اشتراک‌گذاری با QR Code» و «🧹 حذف کانفیگ‌های سوخته» رو بزنی چی میشه؟
              👈 یک بارکد و آی‌پی بهت میده که آیفون، لپ‌تاپ، تلویزیون یا PS5 رو بدون نصب هیچ برنامه‌ای به اینترنت بدون فیلتر گوشیت وصل کنی، و با یک کلیک همهٔ کانفیگ‌های خراب رو پاک و سالم‌ها رو به ترتیب پینگ مرتب می‌کنه!

            ────────────────────────
            🧰 ۹) دکمه «۱۸) جعبه‌ابزار» (در مرکز ۲۰۲۶ و در منوی «＋ افزودن سرور»)
            • «📂 وارد کردن از فایل» رو بزنی چی میشه؟
              👈 فایل ‎.conf‎ وایرگارد، فایل Clash/YAML، sing-box/JSON یا یک فایل متنی پر از لینک رو از حافظهٔ گوشی انتخاب می‌کنی و مستقیم اشتراک میشه. از تلگرام هم می‌تونی روی لینک یا فایل «اشتراک‌گذاری» بزنی و Cat Client رو انتخاب کنی.
            • «💾 پشتیبان» و «♻️ بازگردانی» رو بزنی چی میشه؟
              👈 همهٔ اشتراک‌هات تو یک فایل ذخیره میشه؛ گوشی رو عوض کردی، همون فایل رو بازگردانی کن.
            • «🔒 قفل برنامه» رو بزنی چی میشه؟
              👈 یک رمز عددی (و اثر انگشت/رمز گوشی) می‌ذاری که هر کی گوشیت دستش بود نتونه کانفیگ‌هات رو ببینه.
            • «⏰ رفرش خودکار» چیه؟
              👈 هر ۶ ساعت اشتراک‌هات رو آپدیت می‌کنه و اگه حجمت داره تموم میشه یا ۳ روز به انقضا مونده، نوتیفیکیشن میده.
            • اسکنرهای Cat DNS (ASN، SNI، پروکسی HTTP/SOCKS5، سرعت و پکت‌لاس، ریزالور DNS، کانفیگ‌ساز):
              👈 مثلاً «کانفیگ‌ساز» رو بزنی: کانفیگ خودت رو می‌دی + آی‌پی‌های تمیزی که اسکنر پیدا کرده، و برات همون کانفیگ رو با آی‌پی‌های تمیز (با همان پورت) می‌سازه.
            • «🩺 وضعیت موتورها» چیه؟
              👈 صادقانه میگه کدوم نوع کانفیگ واقعاً روی گوشیت کار می‌کنه: تانل DNS فقط با لینک واقعی slipnet:// (موتور Cat DNS)، فایرفاکس فقط با حساب، paqet فقط با سرور واسط؛ دیگه هیچ‌کدوم بی‌صدا «مستقیم» وصل نمیشن.
            """.trimIndent()
        } else {
            """
            👋 Welcome! You don't need any technical knowledge to use these tools. Here is what each button does in plain language:

            ✅ «Does the internet really work?» card (under the config, after connecting):
               «Connected» only means the tunnel is up. Two seconds after every connect the app opens a tiny page and a 200 KB file through the VPN and says ✅ works, 🐢 slow / stalls or ❌ no internet. In Automatic mode a route without internet (checked twice) is replaced by the next one (up to 3 times) and remembered per network (each operator and Wi-Fi separately): bad routes are skipped for 6 h, good ones are tried first next time. «⏭️ Try next route» switches without disconnecting; «🔁 Again» re-tests now; «📨 Report» shows exactly what would be sent (no server address, UUID or phone IP) and sends it to your own panel only if you tap «Send».

            1) 🦊 Cat Fox VPN (50 GB/mo Free):
               Uses Mozilla's official Fastly edge servers (which aren't blocked like Cloudflare) to connect your whole phone. You can add multiple free Firefox accounts (50 GB each) with automatic failover.

            🌏 Cat SSTP (free SSTP servers):
               The standard Windows VPN (SSTP) on free volunteer servers in Japan/Korea, no account (vpn / vpn). “🚀 Get & connect” downloads a fresh list into the “🌏 Cat SSTP” subscription and connects; if one server fails it tries up to 6 more. Certificates are self-signed — use https sites for anything sensitive.

            2) 🪄 1-Click Repair My Config & Connect:
               Automatically takes your existing subscription/config, wraps it in our local Anti-DPI SNI Splitter + 5 Cloudflare Spoof methods, and connects immediately.

            3) 🚀 Connect Cat Warp Now / 🤖 Auto-Scan & Build:
               Free serverless Cloudflare WARP/MASQUE + 29-country Psiphon chain. Asks whether you want an Iran IP or Foreign Country IP, scans your ISP, and connects.

            4) 🧪 Net Melli Whitelist Lab:
               For severe blackouts when Cloudflare is blocked: scans open Non-Cloudflare CDNs (Akamai, Fastly, Google, Arvan) and repairs your real configs to connect through them.

            5) 📡 DNS & Resolver Methods:
               Scans 52 DNS resolvers and connects via 9 DNS tunnel engines when standard ports are blocked.

            6) 🧰 18) Toolbox (2026 hub and the "+ Add server" sheet):
               Import a WireGuard .conf / Clash YAML / sing-box JSON / text file, back up and restore all subscriptions, lock the app with a PIN or fingerprint, auto-refresh subscriptions every 6 h with expiry / data-cap alerts, the Cat DNS scanners (ASN export, SNI, HTTP/SOCKS5 proxy, speed & loss, DNS resolver, Config Maker) and an honest engine-status page that says which config types really work on this phone.
            """.trimIndent()
        }

        val tv = TextView(this).apply {
            text = guideText
            textSize = 12.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_PRIMARY)
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "📖 راهنمای کامل به زبان ساده (اینو بزنی این میشه!)"
                else "📖 Complete Plain-Language Guide",
            )
            .setView(scroll)
            .setNegativeButton(android.R.string.ok, null)
            .setNeutralButton(if (isFarsi) "🪄 تعمیر کانفیگ من و اتصال" else "🪄 Repair My Config") { _, _ ->
                runOneClickRepairMyRealConfigAndConnect()
            }
            .setPositiveButton(if (isFarsi) "🦊 اتصال فوری Cat Fox VPN" else "🦊 Connect Cat Fox VPN") { _, _ ->
                connectFirefoxVpnDirectly()
            }
            .create()
            .showCatClientDialog()
    }

// ---- moved from MainActivity.kt in beta221 (code split phase 2) ----

internal fun MainActivity.normalizeFinderText(text: String): String = text.lowercase(Locale.ROOT)
    .replace('ي', 'ی').replace('ك', 'ک').replace("\u200c", "").replace("آ", "ا")

/** Compact bar placed under each tab's title: opens the finder. */
internal fun MainActivity.featureFinderBar(): View {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(withAlpha(palette.surfaceElevated2, 230))
            setStroke(dp(1), withAlpha(TEAL, 120))
        }
        isClickable = true
        isFocusable = true
        contentDescription = if (isFarsi) "جست‌وجوی همهٔ ابزارها" else "Search all tools"
        addView(
            TextView(this@featureFinderBar).apply {
                text = if (isFarsi) "🧭 دنبال چی می‌گردی؟ اینجا بنویس یا بزن — همهٔ ابزارها با توضیح ساده"
                else "🧭 Looking for something? Tap here — every tool with a plain explanation"
                textSize = 13.5f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        setOnClickListener { showFeatureFinderDialog() }
    }
}

internal fun MainActivity.showFirewallConnectionGuideDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val msg = if (isFarsi) {
        "🟦 راه‌های اتصال به کلودفلر بر روی فایروال همراه اول:\n\n" +
            "۱) CDN/WORKER با IPv6:\n" +
            "• مخصوص دامنه شخصی فیلترنشده یا *.workers.dev (دامنه *.pages.dev به طور کلی فیلتر است).\n" +
            "• مقادیر echConfigList، cipherSuites و finalMask پاک شوند.\n" +
            "• فینگرپرینت روی chrome و آدرس روی یک IPv6 مانند 2a06:98c1:3101::6812:202f قرار گیرد.\n\n" +
            "۲) WARP با IPv6:\n" +
            "• از قسمت افزودن Cat Warp پروتکل را روی WireGuard و نوع آی‌پی را روی IPv6 بگذارید، اسکن کنید، نام انتخاب و ذخیره کنید.\n\n" +
            "۳) MASQUE/H2:\n" +
            "• از قسمت افزودن Cat Warp پروتکل را روی MASQUE-HTTP/2 بگذارید، اسکن کنید، نام انتخاب و ذخیره کنید.\n\n" +
            "───────────────\n" +
            "🟧 راه‌های اتصال به کلودفلر بر روی فایروال ایرانسل:\n\n" +
            "۱) CDN/WORKER با متد F&F (رفع محدودیت آپلود):\n" +
            "• مقدار echConfigList پاک شود و از آدرسی مانند 188.114.98.0 استفاده شود.\n" +
            "• مقدار finalMask روی tlshello-0-len (متد F&F)، مقدار cipherSuites روی semi-python (متد F&F) و فینگرپرینت روی unsafe قرار گیرد.\n" +
            "• مقدار ALPN برای ws روی http/1.1 و برای XHTTP روی h2,http/1.1 تنظیم شود.\n" +
            "• (سایر روش‌ها روی فایروال ایرانسل با محدودیت سرعت آپلود مواجه هستند.)\n\n" +
            "۲) WARP: همان مراحل همراه اول، با هر نوع IP (IPv4/IPv6).\n" +
            "۳) MASQUE/H2: دقیقاً همان مراحل همراه اول.\n\n" +
            "───────────────\n" +
            "🔓 ۳) رفع تحریم‌ها با چین کردن سایفون / تور (warp -> psiphon/tor):\n" +
            "• در صورت عدم رفع تحریم‌ها حتی با پروتکل wireguard-over-masque-HTTP/2، می‌توانید با چین کردن سایفون/تور (warp -> psiphon/tor) تحریم‌ها را دور بزنید.\n" +
            "• وقتی سایفون/تور را چین می‌کنید، بهتر است از پروتکل‌های ساده wireguard یا masque-h2 استفاده کنید.\n" +
            "• دقت کنید tor/psiphon سرعت متوسطی دارند و بهتر است فقط در صورتی که نیاز به رفع تحریم هم دارید از آن‌ها استفاده کنید.\n\n" +
            "⚠️ توجه مهم: در برخی مناطق سیم‌کارت همراه اول دارای فایروال ایرانسل است و بالعکس! سایر نت‌ها هم معمولاً از یکی از این دو فایروال استفاده می‌کنند."
    } else {
        "🟦 MCI Firewall Methods:\n" +
            "1) CDN/Worker with IPv6 (unfiltered domain or *.workers.dev; *.pages.dev is blocked): clear echConfigList, cipherSuites & finalMask; fingerprint=chrome; IPv6 e.g. 2a06:98c1:3101::6812:202f.\n" +
            "2) WARP with IPv6: Add Cat Warp → WireGuard → IPv6 → Scan → Name & Save.\n" +
            "3) MASQUE/H2: Add Cat Warp → MASQUE-HTTP/2 → Scan → Name & Save.\n\n" +
            "🟧 Irancell Firewall Methods:\n" +
            "1) CDN/Worker with F&F (fixes upload throttling): clear echConfigList; IP e.g. 188.114.98.0; finalMask=tlshello-0-len; cipherSuites=semi-python; fingerprint=unsafe; ALPN=http/1.1 (ws) or h2,http/1.1 (XHTTP).\n" +
            "2) WARP: Add Cat Warp → WireGuard → Any IP → Scan → Save.\n" +
            "3) MASQUE/H2: Same as MCI.\n\n" +
            "🔓 3) Sanction Bypass (warp -> psiphon/tor):\n" +
            "• If geo-sanctions persist even with wireguard-over-masque-HTTP/2, chain Psiphon or Tor (warp -> psiphon/tor).\n" +
            "• When chaining Psiphon/Tor, use simple wireguard or masque-h2 protocols.\n" +
            "• Note: tor/psiphon have medium speed; use them only when you also need sanction bypass.\n\n" +
            "⚠️ Regional firewall may differ from SIM brand; other ISPs use one of these two firewalls."
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "🛡 راهنمای اتصال روی فایروال همراه اول و ایرانسل" else "🛡 MCI & Irancell Firewall Connection Guide")
        .setMessage(msg)
        .setNeutralButton(if (isFarsi) "➕ افزودن Cat Warp" else "➕ Add Cat Warp") { _, _ ->
            showAddAetherDialog()
        }
        .setPositiveButton(android.R.string.ok, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showPrivacyPolicyIfNeeded(onAccepted: (() -> Unit)? = null): Boolean {
    if (privacyPolicyStore.isAccepted()) return false
    showPrivacyPolicyDialog(onAccepted)
    return true
}

internal fun MainActivity.showPrivacyPolicyDialog(onAccepted: (() -> Unit)? = null) {
    if (privacyPolicyDialog?.isShowing == true) return

    val messageText = TextView(this).apply {
        text = getString(R.string.privacy_policy_message)
        textSize = 14f
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = true
        setLineSpacing(dp(2).toFloat(), 1.0f)
    }
    val checkbox = CheckBox(this).apply {
        text = getString(R.string.privacy_policy_checkbox)
        textSize = 14f
        setTextColor(TEXT_PRIMARY)
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), 0)
        addView(
            messageText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            checkbox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )
    }
    val scrollView = ScrollView(this).apply {
        addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.privacy_policy_title)
        .setView(scrollView)
        .setNegativeButton(R.string.privacy_policy_not_now, null)
        .setPositiveButton(R.string.privacy_policy_accept, null)
        .create()

    dialog.setOnDismissListener {
        if (privacyPolicyDialog === dialog) {
            privacyPolicyDialog = null
        }
    }
    privacyPolicyDialog = dialog
    dialog.showCatClientDialog {
        val acceptButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        acceptButton.isEnabled = false
        checkbox.setOnCheckedChangeListener { _, isChecked ->
            acceptButton.isEnabled = isChecked
        }
        acceptButton.setOnClickListener {
            privacyPolicyStore.acceptCurrentVersion()
            DiagnosticLogger.info(
                this@showPrivacyPolicyDialog,
                "privacy.accepted",
                "version=${PrivacyPolicyAcceptancePolicy.CURRENT_VERSION}",
            )
            dialog.dismiss()
            onAccepted?.invoke()
        }
    }
}
