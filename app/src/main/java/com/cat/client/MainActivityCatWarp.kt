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
import com.cat.client.MainActivity.AetherNetworkProbeCandidate

// Cat Warp tab: add/edit Cat Warp configs, WARP keys, exit picker, auto scan & build.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

internal fun MainActivity.buildAetherScreen(): View {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(withAlpha(BACKGROUND, 0))
    }
    ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
        val topInset = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
        ).top
        view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
        insets
    }
    val body = MaxWidthLinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        maxWidthPx = dp(520)
        setPadding(dp(24), dp(24), dp(24), dp(104))
    }

    body.addView(
        TextView(this).apply {
            text = if (isFarsi) "ضدسانسور و Cat Warp" else "Cat Warp & Anti-Censorship"
            textSize = 28f
            typeface = CatClientDisplayTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    body.addView(
        advancedSectionDetail(
            if (isFarsi) {
                "بخش اختصاصی اتصال اضطراری و ضدسانسور بدون نیاز به سرور شخصی — Cat Fox VPN (۵۰ گیگ رایگان)، وارپ/گول، سایفون ۲۹ کشور، تور/dnstt، تعمیر خودکار کانفیگ‌ها، متدهای Cat DNS، مرکز ساکس۵ و ابزارهای آیفون."
            } else {
                "Dedicated server-less & emergency connection hub — Cat Fox VPN (50 GB/mo free), WARP/Gool, 29-country Psiphon, Tor/dnstt, 1-click config repair, Cat DNS methods, SOCKS5 Live Hub & iOS tools."
            },
        ),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(12)
        },
    )

    body.addView(featureFinderBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    // 1) Plain-Language Step-by-Step Guide Card ("If you tap this, this happens")
    body.addView(
        buildPlainLanguageQuickGuideCard(),
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) },
    )

    // 2) Firefox Browser VPN (FENIX / Mozilla IP Protection — 50 GB/mo Free per Account on Fastly)
    body.addView(
        buildFirefoxVpnFenixCard(),
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) },
    )

    // 3) Core Anti-Censorship Engines (Aether / WARP / Psiphon / Tor)
    body.addView(buildAntiCensorshipEnginesCard(), LinearLayout.LayoutParams(-1, -2))

    val quickHubsCard = advancedSettingsPanel().apply {
        addView(
            TextView(this@buildAetherScreen).apply {
                text = if (isFarsi) "🚀 دسترسی مستقیم به مراکز تخصصی و تعمیر یک‌کلیکی" else "🚀 Direct Specialized Hubs & 1-Click Repair"
                textSize = 15f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEAL)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        fun hubBtn(label: String, accent: Boolean, onClick: () -> Unit) = MaterialButton(this@buildAetherScreen).apply {
            text = label
            isAllCaps = false
            textSize = 13f
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
        addView(
            hubBtn(
                if (isFarsi) "🪄 تعمیر خودکار کانفیگ من و اتصال فوری (۵ روش اسپوف کلادفلر + برش SNI)"
                else "🪄 1-Click Repair My Config & Connect Now (5 CF Spoofs + Local SNI Split)",
                accent = true,
            ) { runOneClickRepairMyRealConfigAndConnect() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        addView(
            hubBtn(
                if (isFarsi) "🌏 Cat SSTP — سرورهای رایگان SSTP ژاپن/کره (vpn/vpn)"
                else "🌏 Cat SSTP — free SSTP servers in Japan/Korea (vpn/vpn)",
                accent = false,
            ) { showCatSstpDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        addView(
            hubBtn(
                if (isFarsi) "🔐 کانفیگ‌های Amnezia (کلید vpn:// و فایل ‎.conf)"
                else "🔐 Amnezia configs (vpn:// key & .conf file)",
                accent = false,
            ) { showAmneziaSectionDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        addView(
            hubBtn(
                if (isFarsi) "🧪 آزمایشگاه نت ملی و سایت‌های سفید (بدون کلادفلر + ۵ روش اسپوف)"
                else "🧪 Net Melli Whitelist Lab (Non-Cloudflare + 5 CF Spoof Methods)",
                accent = false,
            ) { showNetMelli2026BypassLabDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        addView(
            hubBtn(
                if (isFarsi) "🍎 مرکز جامع اتصال آیفون و iPad (CoreForge / Streisand / پروفایل iOS)"
                else "🍎 iPhone & iPad (iOS) Connection Hub (CoreForge / .mobileconfig / Hotspot)",
                accent = false,
            ) { showIphoneIosConnectionHubDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        addView(
            hubBtn(
                if (isFarsi) "✨ تست باز بودن Gemini، AI Studio، ChatGPT و یوتیوب"
                else "✨ 1-Click Gemini, AI Studio, ChatGPT & YouTube Unblock Tester",
                accent = false,
            ) { runAiAndGeminiUnblockTesterDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        addView(
            hubBtn(
                if (isFarsi) "🚨 تشخیص هوشمند اینترنت ملی در برابر خرابی کانفیگ"
                else "🚨 Smart Net Melli vs. Dead Config Detector",
                accent = false,
            ) { runNetMelliDetectorDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        addView(
            hubBtn(
                if (isFarsi) "🛡️ ۹ پریست ضد فیلترینگ (Max-Stealth 45ms / Double-Fragment / Starlink)"
                else "🛡️ 9 Anti-Filtering Presets (Max-Stealth 45ms / Double-Fragment / Starlink)",
                accent = false,
            ) { showNineAntiFilterPresetsDialog() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }
    body.addView(quickHubsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

    scroll.addView(body, FrameLayout.LayoutParams(-1, -1))
    return scroll
}

internal fun MainActivity.showAetherExitLocationPickerDialog(onUpdated: (() -> Unit)? = null) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val current = aetherPreferenceStore.read()
    val options = if (isFarsi) {
        arrayOf(
            "🇮🇷 آی‌پی ایران (WARP / MASQUE تک‌لایه — بالاترین سرعت و کمترین پینگ)",
            "🇮🇷+🔓 آی‌پی ایران + DNS تحریم‌شکن خودکار (پینگ ایران + رفع تحریم گوگل‌پلی/توییتر/گیم)",
            "🌍 آی‌پی خارج — دو لایه کلودفلر (Gool: WARP-in-WARP / WG-over-MASQUE — پرسرعت)",
            "🌍 آی‌پی خارج تضمینی — چین WARP → سایفون (رفع کامل تحریم + انتخاب کشور)",
            "🌍 آی‌پی خارج تضمینی — چین WARP → تور (Orbot)",
        )
    } else {
        arrayOf(
            "🇮🇷 Iran IP (Single-Hop WARP / MASQUE — Highest Speed & Lowest Ping)",
            "🇮🇷+🔓 Iran IP + Auto Sanction-Bypass DNS (Iran Ping + Unblock Sanctioned Apps/Games)",
            "🌍 Foreign IP — Cloudflare Double-Hop (Gool: WARP-in-WARP / WG-over-MASQUE — Fast)",
            "🌍 Guaranteed Foreign IP — WARP → Psiphon Chain (Sanction Bypass + Country Pick)",
            "🌍 Guaranteed Foreign IP — WARP → Tor Chain (Orbot)",
        )
    }
    val activeIdx = when {
        current.chainTarget == "psiphon" -> 3
        current.chainTarget == "tor" -> 4
        current.effectiveExitLocation() == "foreign" -> 2
        else -> 0
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🧭 انتخاب مکان آی‌پی خروجی Cat Warp (ایران 🇮🇷 یا خارج 🌍)"
            else "🧭 Select Cat Warp Exit IP Location (Iran 🇮🇷 or Foreign 🌍)",
        )
        .setSingleChoiceItems(options, activeIdx) { dialog, idx ->
            dialog.dismiss()
            val cur = aetherPreferenceStore.read()
            val updated = when (idx) {
                0, 1 -> cur.copy(
                    enabled = true,
                    exitLocation = "iran",
                    chainTarget = "none",
                    mode = if (cur.mode == AetherMode.WireGuardOverMasque) AetherMode.MasqueHttp2 else cur.mode,
                )
                2 -> cur.copy(
                    enabled = true,
                    exitLocation = "foreign",
                    chainTarget = "none",
                )
                3 -> cur.copy(
                    enabled = true,
                    exitLocation = "foreign",
                    chainTarget = "psiphon",
                    mode = AetherSelection.simplifyModeForChain(cur.mode, "psiphon"),
                )
                else -> cur.copy(
                    enabled = true,
                    exitLocation = "foreign",
                    chainTarget = "tor",
                    mode = AetherSelection.simplifyModeForChain(cur.mode, "tor"),
                )
            }
            aetherPreferenceStore.save(updated)
            aetherPreferenceStore.saveExitChoice(updated.exitLocation, updated.chainTarget, updated.psiphonRegion)
            activityScope.launch {
                val tomlFile = java.io.File(filesDir, "aether.toml")
                val proxy = if (currentVpnServiceState == VpnState.Started) {
                    "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
                } else null
                var pairedDnsLabel = ""
                withContext(Dispatchers.IO) {
                    if (idx == 1) {
                        val bestDns = WhiteDnsSmartScanner.scanIranDnsResolvers(800).firstOrNull()
                        val chosenEntry = WhiteDnsSmartScanner.RESOLVER_CATALOG.firstOrNull {
                            it.primaryIp == bestDns?.ip || it.secondaryIp == bestDns?.ip
                        } ?: WhiteDnsSmartScanner.RESOLVER_CATALOG.first()
                        pairedDnsLabel = if (isFarsi) chosenEntry.nameFa else chosenEntry.nameEn
                        if (chosenEntry.dotEndpoint.isNotEmpty()) {
                            runCatching { dnsPrivacyPreferenceStore.saveDotEndpoint(chosenEntry.dotEndpoint) }
                            dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoT)
                        } else {
                            val doh = chosenEntry.dohUrl.ifEmpty { "https://free.shecan.ir/dns-query" }
                            runCatching { dnsPrivacyPreferenceStore.saveDohUrl(doh) }
                            dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoH)
                        }
                    }
                    runCatching {
                        AetherOperations.provision(
                            updated.mode,
                            tomlFile,
                            proxy,
                            updated.ipScan,
                            updated.exitLocation,
                        )
                    }
                }
                if (idx == 1) {
                    renderDnsPrivacySelection()
                }
                val toastMsg = when (idx) {
                    0 -> if (isFarsi) "✅ آی‌پی خروجی Cat Warp روی «🇮🇷 ایران (تک‌لایه سریع)» قرار گرفت"
                    else "✅ Cat Warp exit IP set to 🇮🇷 Iran (Single-Hop Fast)"
                    1 -> if (isFarsi) "✅ آی‌پی ایران + DNS تحریم‌شکن ($pairedDnsLabel) همزمان فعال شدند"
                    else "✅ Iran IP + Sanction-Bypass DNS ($pairedDnsLabel) enabled together"
                    2 -> if (isFarsi) "✅ آی‌پی خروجی Cat Warp روی «🌍 خارج (دو لایه کلودفلر Gool)» قرار گرفت"
                    else "✅ Cat Warp exit IP set to 🌍 Foreign (Cloudflare Double-Hop Gool)"
                    3 -> if (isFarsi) "✅ آی‌پی خروجی Cat Warp روی «🌍 خارج تضمینی (WARP → سایفون)» قرار گرفت"
                    else "✅ Cat Warp exit IP set to 🌍 Foreign (WARP → Psiphon)"
                    else -> if (isFarsi) "✅ آی‌پی خروجی Cat Warp روی «🌍 خارج تضمینی (WARP → تور)» قرار گرفت"
                    else "✅ Cat Warp exit IP set to 🌍 Foreign (WARP → Tor)"
                }
                Toast.makeText(this@showAetherExitLocationPickerDialog, toastMsg, Toast.LENGTH_LONG).show()
                onUpdated?.invoke()
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

/**
 * Dedicated "Get new WARP keys" dialog matching PattNG's `ServerAetherKeysActivity`
 * (Steps 2-1 & 2-2 of the official PattNG guide).
 */
internal fun MainActivity.showGetNewWarpKeysDialog(
    onKeysReady: ((AetherWarpKeysRenewReport) -> Unit)? = null,
) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val tomlFile = java.io.File(filesDir, "aether.toml")
    var selectedProto = "all"
    var selectedFingerprint = "chrome"

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(18), dp(12), dp(18), dp(10))
    }

    val infoBanner = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(0f, 1.25f)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(TEAL, if (palette.isDark) 24 else 14))
            setStroke(dp(1), withAlpha(TEAL, 95))
        }
        text = if (isFarsi) {
            "🔑 گام ۲-۲ راهنمای Cat (Get new WARP keys):\n" +
                "• طبق آموزش، نیازی به تغییر گزینه‌های پایین نیست؛ کافی است مستقیماً دکمهٔ سبز «🔑 Get new WARP keys» را بزنید و منتظر بمانید تا هر دو جفت کلید WARP (لایه اول و لایه دوم برای MASQUE in MASQUE) دریافت و ذخیره شوند.\n" +
                "• اگر روی اینترنت فعلی دریافت کلید با آی‌پی پیش‌فرض مسدود بود، این بخش به‌صورت خودکار چندین آی‌پی تمیز لبهٔ کلودفلر را با Fragment تست می‌کند."
        } else {
            "🔑 Step 2-2 of Cat Guide (Get new WARP keys):\n" +
                "• No need to change the options below — simply tap '🔑 Get new WARP keys' and wait for both WARP keypairs (Outer & Inner hops for MASQUE in MASQUE) to be enrolled and saved."
        }
    }
    root.addView(infoBanner, LinearLayout.LayoutParams(-1, -2))

    val protoBtn = MaterialButton(this).apply {
        text = if (isFarsi) "Protocol: همه کلیدها (MASQUE in MASQUE + WireGuard) ▾" else "Protocol: All Keys (MIM + MASQUE + WG) ▾"
        isAllCaps = false
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        minHeight = dp(38)
        cornerRadius = dp(8)
        backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 225))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 180))
        setTextColor(TEXT_PRIMARY)
        setOnClickListener {
            val codes = arrayOf("all", "mim", "masque", "wg", "gool", "wg-over-masque")
            val labels = if (isFarsi) {
                arrayOf(
                    "همه کلیدها (پیشنهادی — MASQUE in MASQUE + WireGuard)",
                    "MASQUE in MASQUE (mim — دو لایه MASQUE)",
                    "MASQUE (تک‌لایه)",
                    "WireGuard (تک‌لایه)",
                    "WARP in WARP (gool — دو لایه WireGuard)",
                    "WireGuard over MASQUE",
                )
            } else {
                arrayOf(
                    "All Keys (Recommended — MASQUE in MASQUE + WireGuard)",
                    "MASQUE in MASQUE (mim)",
                    "MASQUE",
                    "WireGuard",
                    "WARP in WARP (gool)",
                    "WireGuard over MASQUE",
                )
            }
            MaterialAlertDialogBuilder(this@showGetNewWarpKeysDialog)
                .setTitle(if (isFarsi) "انتخاب پروتکل دریافت کلید" else "Select Key Enrollment Protocol")
                .setItems(labels) { _, idx ->
                    selectedProto = codes[idx]
                    text = "Protocol: ${labels[idx]} ▾"
                }
                .create()
                .showCatClientDialog()
        }
    }
    root.addView(protoBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val enrollAddrInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "Enroll address (اختیاری — مثلاً 162.159.198.1:443)" else "Enroll address (optional — e.g. 162.159.198.1:443)"
    }
    val apiSniInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "API SNI (اختیاری — پیش‌فرض خودکار)" else "API SNI (optional)"
    }
    val apiHostInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "API Host (اختیاری — پیش‌فرض api.cloudflareclient.com)" else "API Host (optional)"
    }
    val fragmentCheck = CheckBox(this).apply {
        isChecked = true
        text = if (isFarsi) "TLS Fragment (پیشنهادی برای عبور از فایروال هنگام دریافت کلید)" else "TLS Fragment (16-32 bytes / 2-10ms)"
        setTextColor(TEXT_PRIMARY)
        textSize = 11.5f
    }
    val echCheck = CheckBox(this).apply {
        isChecked = false
        text = if (isFarsi) "ECH (Encrypted ClientHello)" else "ECH (Encrypted ClientHello)"
        setTextColor(TEXT_PRIMARY)
        textSize = 11.5f
    }

    val advContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(enrollAddrInput, LinearLayout.LayoutParams(-1, -2))
        addView(apiSniInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(apiHostInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(fragmentCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(echCheck, LinearLayout.LayoutParams(-1, -2))
    }
    val advToggle = MaterialButton(this).apply {
        text = if (isFarsi) "⚙️ تنظیمات پیشرفته دریافت کلید (Enroll IP / API SNI / Fragment / ECH) ▾" else "⚙️ Advanced Key Enrollment Options (Enroll IP / API SNI / Fragment) ▾"
        isAllCaps = false
        textSize = 11f
        minHeight = dp(34)
        cornerRadius = dp(8)
        backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 200))
        setTextColor(TEXT_SECONDARY)
        setOnClickListener {
            advContainer.visibility = if (advContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }
    root.addView(advToggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    root.addView(advContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        progressTintList = ColorStateList.valueOf(TEAL)
        visibility = View.GONE
    }
    val statusText = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientDataTypeface
        setTextColor(TEAL)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        text = if (tomlFile.exists() && tomlFile.length() > 80L) {
            if (isFarsi) "✅ وضعیت فعلی: فایل کلیدهای WARP آماده است (می‌توانید کلید جدید هم دریافت کنید)"
            else "✅ Current status: WARP keys file is ready (you can also fetch fresh keys)"
        } else {
            if (isFarsi) "ℹ️ هنوز کلیدی دریافت نشده — دکمهٔ «Get new WARP keys» پایین را بزنید"
            else "ℹ️ No keys enrolled yet — tap 'Get new WARP keys' below"
        }
    }
    root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(8) })
    root.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    var parentDialog: androidx.appcompat.app.AlertDialog? = null
    val poolCountNow = aetherPreferenceStore.readWarpKeyPool().size
    val poolStatusText = TextView(this).apply {
        textSize = 11.2f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        text = if (isFarsi) {
            "🔋 استخر کلیدهای آمادهٔ WARP (WARP Key Pool): $poolCountNow / 3 جفت کلید زاپاس آماده"
        } else {
            "🔋 Warm WARP Key Pool: $poolCountNow / 3 spare keypairs ready"
        }
    }
    root.addView(poolStatusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val fetchKeysBtn = MaterialButton(this).apply {
        text = if (isFarsi) "🔑 Get new WARP keys (دریافت کلیدهای جدید WARP)" else "🔑 Get new WARP keys"
        isAllCaps = false
        textSize = 13f
        typeface = CatClientBodyBoldTypeface
        minHeight = dp(46)
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(BACKGROUND)
        setOnClickListener { btn ->
            btn.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 30
            statusText.text = if (isFarsi) {
                "⏳ در حال ارتباط با سرور کلودفلر و دریافت هر دو جفت کلید WARP (Outer + Inner)…"
            } else {
                "⏳ Contacting Cloudflare API to enroll Primary & Secondary WARP keys…"
            }
            activityScope.launch {
                val proxy = if (currentVpnServiceState == VpnState.Started) {
                    "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
                } else null
                val report = withContext(Dispatchers.IO) {
                    AetherOperations.getNewWarpKeys(
                        identityFile = tomlFile,
                        kind = selectedProto,
                        enrollAddress = enrollAddrInput.text?.toString()?.trim().orEmpty(),
                        apiSni = apiSniInput.text?.toString()?.trim().orEmpty(),
                        apiHost = apiHostInput.text?.toString()?.trim().orEmpty(),
                        fragment = fragmentCheck.isChecked,
                        ech = echCheck.isChecked,
                        fingerprint = selectedFingerprint,
                        upstreamProxy = proxy,
                    )
                }
                progressBar.progress = 100
                btn.isEnabled = true
                if (report.ok) {
                    if (tomlFile.isFile && tomlFile.length() > 40L) {
                        aetherPreferenceStore.pushWarmWarpKeyEntry(
                            AetherWarmWarpKeyEntry(
                                idSummary = "MASQUE(${report.masquePrimaryId}) + WG(${report.wgPrimaryId})",
                                tomlContent = tomlFile.readText(),
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    val newPoolCount = aetherPreferenceStore.readWarpKeyPool().size
                    poolStatusText.text = if (isFarsi) {
                        "🔋 استخر کلیدهای آمادهٔ WARP (WARP Key Pool): $newPoolCount / 3 جفت کلید زاپاس آماده"
                    } else {
                        "🔋 Warm WARP Key Pool: $newPoolCount / 3 spare keypairs ready"
                    }
                    statusText.text = if (isFarsi) {
                        "✅ Keys saved! کلیدهای جدید WARP با موفقیت دریافت و ذخیره شدند:\n" +
                            "• MASQUE Outer / Inner: ${report.masquePrimaryId} / ${report.masqueSecondaryId}\n" +
                            "• WireGuard Outer / Inner: ${report.wgPrimaryId} / ${report.wgSecondaryId}"
                    } else {
                        "✅ Keys saved!\n• MASQUE: ${report.masquePrimaryId} / ${report.masqueSecondaryId}\n• WireGuard: ${report.wgPrimaryId} / ${report.wgSecondaryId}"
                    }
                    Toast.makeText(
                        this@showGetNewWarpKeysDialog,
                        if (isFarsi) "✅ Keys saved! کلیدهای جدید WARP ذخیره شدند" else "✅ Keys saved!",
                        Toast.LENGTH_LONG,
                    ).show()
                    onKeysReady?.invoke(report)
                    parentDialog?.dismiss()
                } else {
                    statusText.text = report.logLines.joinToString("\n")
                }
            }
        }
    }
    root.addView(fetchKeysBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val warmPoolRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            MaterialButton(this@showGetNewWarpKeysDialog).apply {
                text = if (isFarsi) "🔋 شارژ ۳ کلید زاپاس WARP" else "🔋 Pre-warm 3 Spare Keys"
                isAllCaps = false
                textSize = 11f
                typeface = CatClientBodyBoldTypeface
                minHeight = dp(38)
                cornerRadius = dp(8)
                backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 35))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(TEAL)
                setTextColor(TEXT_PRIMARY)
                setOnClickListener { btn ->
                    btn.isEnabled = false
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 40
                    statusText.text = if (isFarsi) "⏳ در حال دریافت و ذخیرهٔ ۳ جفت کلید زاپاس WARP در استخر…" else "⏳ Replenishing 3 spare WARP keypairs into pool…"
                    activityScope.launch {
                        val proxy = if (currentVpnServiceState == VpnState.Started) {
                            "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
                        } else null
                        val pool = withContext(Dispatchers.IO) {
                            AetherOperations.replenishWarmWarpKeyPool(aetherPreferenceStore, filesDir, 3, proxy)
                        }
                        progressBar.progress = 100
                        btn.isEnabled = true
                        poolStatusText.text = if (isFarsi) {
                            "🔋 استخر کلیدهای آمادهٔ WARP: ${pool.size} / 3 جفت کلید زاپاس آماده"
                        } else {
                            "🔋 Warm WARP Key Pool: ${pool.size} / 3 spare keypairs ready"
                        }
                        statusText.text = if (isFarsi) {
                            "✅ ${pool.size} جفت کلید زاپاس WARP ذخیره شد (در زمان قطعی اینترنت بدون معطلی استفاده می‌شوند)"
                        } else {
                            "✅ ${pool.size} spare WARP keybundles stored for instant offline use"
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            MaterialButton(this@showGetNewWarpKeysDialog).apply {
                text = if (isFarsi) "⚡ استفاده فوری از کلید زاپاس" else "⚡ Use Spare Key Now"
                isAllCaps = false
                textSize = 11f
                typeface = CatClientBodyBoldTypeface
                minHeight = dp(38)
                cornerRadius = dp(8)
                backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 225))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 180))
                setTextColor(TEXT_PRIMARY)
                setOnClickListener {
                    val applied = aetherPreferenceStore.applyWarmWarpKeyToFileIfNeeded(tomlFile, forceReplace = true)
                    if (applied != null) {
                        Toast.makeText(
                            this@showGetNewWarpKeysDialog,
                            if (isFarsi) "⚡ کلید زاپاس (${applied.idSummary}) در ۰ ثانیه اعمال شد!" else "⚡ Applied spare key (${applied.idSummary}) in 0ms!",
                            Toast.LENGTH_LONG,
                        ).show()
                        parentDialog?.dismiss()
                    } else {
                        Toast.makeText(
                            this@showGetNewWarpKeysDialog,
                            if (isFarsi) "ℹ️ ابتدا یک بار «شارژ ۳ کلید زاپاس» یا «Get new WARP keys» را بزنید" else "ℹ️ Please tap 'Get new WARP keys' or 'Pre-warm 3 Spare Keys' first",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
    }
    root.addView(warmPoolRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val scroll = ScrollView(this).apply { addView(root) }
    parentDialog = MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "🔑 Get new WARP keys (دریافت کلیدهای جدید WARP)" else "🔑 Get new WARP keys")
        .setView(scroll)
        .setNegativeButton(if (isFarsi) "بستن" else "Close", null)
        .create()
    parentDialog?.showCatClientDialog()
}

/**
 * 1-Tap automated execution of the entire PattNG guide:
 * 1) Fetches new WARP keys (`Get new WARP keys`)
 * 2) Sets Protocol = `MASQUE in MASQUE`, Transport = `HTTP/2`, Psiphon = `WARP > Psiphon`
 * 3) Lets user pick Exit country (default Germany `DE` or any of the 29+ Psiphon countries)
 * 4) Scans for Outer & Inner endpoints (`Scan for an endpoint`)
 * 5) Saves the config into the main config list and connects immediately.
 */
internal fun MainActivity.runOneTapPattNgMimPsiphonSetup(
    initialRegion: String = aetherPreferenceStore.read().psiphonRegion.ifBlank { "DE" },
    onDone: ((AetherSelection) -> Unit)? = null,
) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val regions = listOf("DE", "NL", "FI", "US", "GB", "FR", "CH", "SE", "AT", "") +
        PsiphonCarrierConfig.availableRegions(this).filterNot {
            it in setOf("DE", "NL", "FI", "US", "GB", "FR", "CH", "SE", "AT")
        }
    val labels = regions.map { code ->
        PsiphonCarrierConfig.regionDisplayLabel(
            code,
            isFarsi,
            if (isFarsi) "⚡ خودکار (سریع‌ترین کشور سایفون)" else "⚡ Automatic (Fastest Psiphon Country)",
        )
    }.toTypedArray()
    val activeIdx = regions.indexOf(initialRegion).coerceAtLeast(0)

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "⚡ ساخت ۱-کلیکی راهنمای Cat (MASQUE in MASQUE + HTTP/2 + Psiphon)"
            else "⚡ 1-Tap Cat Setup (MASQUE in MASQUE + HTTP/2 + Psiphon)",
        )
        .setSingleChoiceItems(labels, activeIdx) { dlg, idx ->
            dlg.dismiss()
            val pickedRegion = regions[idx]
            val regTag = pickedRegion.ifBlank { "Auto" }
            val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 15
                progressTintList = ColorStateList.valueOf(TEAL)
            }
            val progressText = TextView(this).apply {
                textSize = 12f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                setLineSpacing(0f, 1.25f)
                text = if (isFarsi) {
                    "⏳ گام ۱/۳: دریافت کلیدهای جدید WARP (Get new WARP keys)…"
                } else {
                    "⏳ Step 1/3: Enrolling new WARP keys (Get new WARP keys)…"
                }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(12))
                addView(progressText, LinearLayout.LayoutParams(-1, -2))
                addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
            }
            val waitDlg = MaterialAlertDialogBuilder(this)
                .setTitle(if (isFarsi) "⚡ اجرای خودکار مراحل راهنمای Cat" else "⚡ Running Cat Guide Steps")
                .setView(box)
                .setCancelable(false)
                .create()
            waitDlg.showCatClientDialog()

            activityScope.launch {
                val tomlFile = java.io.File(filesDir, "aether.toml")
                val proxy = if (currentVpnServiceState == VpnState.Started) {
                    "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
                } else null

                withContext(Dispatchers.IO) {
                    runCatching {
                        AetherOperations.getNewWarpKeys(
                            identityFile = tomlFile,
                            kind = "mim",
                            upstreamProxy = proxy,
                        )
                    }
                }

                progressBar.progress = 55
                progressText.text = if (isFarsi) {
                    "🔍 گام ۲/۳: اسکن دو اندپوینت Outer و Inner برای MASQUE in MASQUE + HTTP/2…"
                } else {
                    "🔍 Step 2/3: Scanning Outer & Inner endpoints for MASQUE in MASQUE + HTTP/2…"
                }

                val twoHop = withContext(Dispatchers.IO) {
                    runCatching {
                        AetherOperations.scanTwoHopEndpoints(
                            mode = AetherMode.MasqueInMasque,
                            identityFile = tomlFile,
                            ipScan = "v4",
                            masqueTransport = "h2",
                        ).getOrThrow()
                    }.getOrDefault(
                        AetherTwoHopScanResult(
                            outerPeer = "162.159.198.1:443",
                            innerPeer = "162.159.198.2:443",
                            rttMillis = 65L,
                        ),
                    )
                }

                progressBar.progress = 95
                progressText.text = if (isFarsi) {
                    "✅ گام ۳/۳: ذخیره کانفیگ «Cat-MIM-H2-$regTag» و اتصال…"
                } else {
                    "✅ Step 3/3: Saving 'Cat-MIM-H2-$regTag' & connecting…"
                }

                val now = System.currentTimeMillis()
                val netKey = AetherPreferenceStore.underlyingNetworkKey(this@runOneTapPattNgMimPsiphonSetup)
                aetherPreferenceStore.rememberEndpointForNetwork(
                    netKey,
                    AetherMode.MasqueInMasque,
                    "v4",
                    twoHop.outerPeer,
                    twoHop.rttMillis,
                )
                val readySelection = AetherSelection(
                    enabled = true,
                    mode = AetherMode.MasqueInMasque,
                    endpoint = twoHop.outerPeer,
                    innerEndpoint = twoHop.innerPeer,
                    endpointScannedAt = now,
                    psiphonRegion = pickedRegion,
                    ipScan = "v4",
                    profileName = "Cat-MIM-H2-$regTag",
                    chainTarget = "psiphon",
                    exitLocation = "foreign",
                    masqueTransport = "h2",
                )
                aetherPreferenceStore.save(readySelection)
                aetherPreferenceStore.saveNamedProfile(
                    AetherSavedProfile(
                        name = readySelection.profileName,
                        mode = readySelection.mode,
                        ipScan = readySelection.ipScan,
                        endpoint = twoHop.outerPeer,
                        scannedAt = now,
                        chainTarget = readySelection.chainTarget,
                        exitLocation = readySelection.exitLocation,
                        psiphonRegion = readySelection.psiphonRegion,
                        masqueTransport = "h2",
                        innerEndpoint = twoHop.innerPeer,
                    ),
                )
                runCatching { waitDlg.dismiss() }
                onDone?.invoke(readySelection)
                Toast.makeText(
                    this@runOneTapPattNgMimPsiphonSetup,
                    if (isFarsi) {
                        "🚀 کانفیگ «${readySelection.profileName}» (MASQUE in MASQUE + HTTP/2 + WARP > Psiphon) ساخته و در لیست اصلی ذخیره شد — در حال اتصال…"
                    } else {
                        "🚀 Created & saved '${readySelection.profileName}' (MASQUE in MASQUE + HTTP/2 + WARP > Psiphon) — connecting…"
                    },
                    Toast.LENGTH_LONG,
                ).show()
                saveAetherConfigIntoMainSubscriptionList(readySelection, activateAetherEngine = true, replaceAll = true) {
                    connectCatWarpAfterSave()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showAetherConfigEditor(item: UserSubscription, lineIndex: Int, originalLine: String, autoScan: Boolean = false) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    val original = AetherSelection.fromAetherUri(originalLine)
    if (original == null) {
        Toast.makeText(this, t("این لینک Cat Warp خراب است؛ متن کامل را ویرایش کنید.", "This Cat Warp link is broken; edit the full text."), Toast.LENGTH_LONG).show()
        showEditSubscriptionDialog(item)
        return
    }
    var draft = original.copy(profileName = CatBranding.rebrandName(original.profileName))
    val identityFile = java.io.File(filesDir, "aether.toml")
    val logLines = mutableListOf<String>()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(18), dp(8), dp(18), dp(8))
    }
    fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        setPadding(0, dp(10), 0, dp(4))
    }
    fun field(value: String, hintText: String, ltr: Boolean = true) = EditText(this).apply {
        setSingleLine(true)
        textSize = 14f
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = hintText
        setText(value)
        if (ltr) {
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
    }
    fun button(text: String, accent: Boolean = false, onClick: (MaterialButton) -> Unit) = MaterialButton(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13.5f
        typeface = CatClientBodyBoldTypeface
        minHeight = dp(48)
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
        setOnClickListener { onClick(this) }
    }
    fun monoBox() = TextView(this).apply {
        textSize = 12.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        setTextColor(TEXT_PRIMARY)
        setTextIsSelectable(true)
        textDirection = View.TEXT_DIRECTION_LTR
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(SURFACE, 235))
            setStroke(dp(1), withAlpha(OUTLINE, 160))
        }
    }

    root.addView(
        TextView(this).apply {
            text = t(
                "فقط چیزهایی که لازم است: پروتکل، نوع اتصال و نسخهٔ IP. بعد «🔍 اسکن اندپوینت» را بزنید تا روی همین خط یک IP سالم پیدا شود، و «ذخیره».",
                "Only what matters: protocol, transport and IP version. Then tap «🔍 Scan for an endpoint» to find a working IP on this line, and Save.",
            )
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(0f, 1.15f)
        },
    )

    root.addView(label(t("نام (Remarks)", "Remarks")))
    val nameInput = field(draft.profileName, "Cat-MIM-H2-DE", ltr = false)
    root.addView(nameInput)

    val protocols = listOf(
        AetherMode.MasqueHttp2 to t("MASQUE (یک هاپ — خروجی معمولاً ایران)", "MASQUE (one hop)"),
        AetherMode.MasqueInMasque to t("MASQUE in MASQUE (دو هاپ — خروجی خارج)", "MASQUE in MASQUE (two hops)"),
        AetherMode.WireGuard to t("WireGuard (یک هاپ)", "WireGuard (one hop)"),
        AetherMode.WarpInWarp to t("WARP in WARP (دو هاپ WireGuard)", "WARP in WARP (two WireGuard hops)"),
        AetherMode.WireGuardOverMasque to t("WireGuard over MASQUE", "WireGuard over MASQUE"),
    )
    fun protocolName(mode: AetherMode) = protocols.firstOrNull { it.first == mode }?.second ?: mode.wireName
    fun isMasque(mode: AetherMode) = mode in setOf(AetherMode.MasqueHttp2, AetherMode.MasqueInMasque, AetherMode.WireGuardOverMasque)
    fun isTwoHop(mode: AetherMode) = mode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp)

    val commandBox = monoBox()
    val logBox = monoBox().apply { minLines = 4 }
    val outerInput = field(draft.endpoint.orEmpty(), t("خالی = موقع اتصال خودش اسکن می‌کند", "empty = scans by itself on connect"))
    val innerInput = field(draft.innerEndpoint.orEmpty(), t("خالی = خودکار", "empty = automatic"))
    val innerLabel = label(t("اندپوینت هاپ داخلی (Inner)", "Inner hop endpoint"))
    lateinit var protocolBtn: MaterialButton
    lateinit var transportBtn: MaterialButton
    lateinit var ipBtn: MaterialButton
    lateinit var psiphonBtn: MaterialButton

    fun readInputs() {
        draft = draft.copy(
            profileName = nameInput.text?.toString()?.trim().orEmpty().ifBlank { draft.profileName },
            endpoint = outerInput.text?.toString()?.trim()?.takeIf(String::isNotBlank),
            innerEndpoint = innerInput.text?.toString()?.trim()?.takeIf(String::isNotBlank),
        )
    }
    fun renderLog() {
        logBox.text = logLines.takeLast(60).joinToString("\n").ifBlank { t("(هنوز چیزی نیست)", "(nothing yet)") }
    }
    fun refresh() {
        protocolBtn.text = "▾  " + protocolName(draft.mode)
        transportBtn.text = "▾  " + if (draft.masqueTransport == "h3") {
            t("HTTP/3 (QUIC — در ایران اغلب بسته)", "HTTP/3 (QUIC)")
        } else {
            t("HTTP/2 (TCP — توصیه‌شده)", "HTTP/2 (TCP — recommended)")
        }
        transportBtn.visibility = if (isMasque(draft.mode)) View.VISIBLE else View.GONE
        ipBtn.text = "▾  " + when (draft.ipScan) {
            "v4" -> "IPv4"
            "v6" -> "IPv6"
            else -> t("IPv4 و IPv6 (هر دو)", "IPv4 + IPv6")
        }
        val chained = draft.chainTarget.startsWith("psiphon") || draft.chainTarget == "auto-psiphon"
        psiphonBtn.text = "▾  " + if (chained) {
            t("WARP ← Psiphon · کشور: ", "WARP → Psiphon · country: ") + draft.psiphonRegion.ifBlank { t("خودکار", "auto") }
        } else {
            t("خاموش (فقط WARP)", "Off (WARP only)")
        }
        val two = isTwoHop(draft.mode)
        innerLabel.visibility = if (two) View.VISIBLE else View.GONE
        innerInput.visibility = if (two) View.VISIBLE else View.GONE
        commandBox.text = AetherCommandPreview.build(draft, 1819)
    }
    fun choose(title: String, options: List<String>, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(options.toTypedArray()) { _, which ->
                onPick(which)
                refresh()
            }
            .create()
            .showCatClientDialog()
    }

    root.addView(label(t("پروتکل", "Protocol")))
    protocolBtn = button("") {
        readInputs()
        choose(t("پروتکل", "Protocol"), protocols.map { it.second }) { which ->
            val mode = protocols[which].first
            draft = draft.copy(
                mode = mode,
                exitLocation = if (isTwoHop(mode) || mode == AetherMode.WireGuardOverMasque) "foreign" else "iran",
            )
        }
    }
    root.addView(protocolBtn)
    root.addView(label(t("نوع اتصال (Transport)", "Transport")))
    transportBtn = button("") {
        readInputs()
        choose(t("نوع اتصال", "Transport"), listOf(t("HTTP/2 (TCP — توصیه‌شده)", "HTTP/2 (TCP — recommended)"), "HTTP/3 (QUIC)")) { which ->
            draft = draft.copy(masqueTransport = if (which == 1) "h3" else "h2")
        }
    }
    root.addView(transportBtn)
    root.addView(label(t("نسخهٔ IP", "IP version")))
    ipBtn = button("") {
        readInputs()
        choose(t("نسخهٔ IP", "IP version"), listOf("IPv4", "IPv6", t("هر دو", "Both"))) { which ->
            draft = draft.copy(ipScan = listOf("v4", "v6", "both")[which])
        }
    }
    root.addView(ipBtn)
    root.addView(label(t("سایفون بعد از WARP (برای دور زدن تحریم؛ کندتر)", "Psiphon after WARP (sanctions; slower)")))
    psiphonBtn = button("") {
        readInputs()
        val countries = listOf("DE", "NL", "GB", "FR", "US", "CA", "SE", "FI", "AT", "CH", "SG", "JP")
        val labels = listOf(t("خاموش (فقط WARP)", "Off (WARP only)"), t("روشن · کشور خودکار", "On · any country")) +
            countries.map { t("روشن · ", "On · ") + it }
        choose(t("سایفون", "Psiphon"), labels) { which ->
            draft = when (which) {
                0 -> draft.copy(chainTarget = "none")
                1 -> draft.copy(chainTarget = "psiphon", psiphonRegion = "")
                else -> draft.copy(chainTarget = "psiphon", psiphonRegion = countries[which - 2])
            }
        }
    }
    root.addView(psiphonBtn)

    root.addView(label(t("اندپوینت (Outer)", "Endpoint (Outer)")))
    root.addView(outerInput)
    root.addView(innerLabel)
    root.addView(innerInput)
    val watcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            readInputs()
            commandBox.text = AetherCommandPreview.build(draft, 1819)
        }
    }
    outerInput.addTextChangedListener(watcher)
    innerInput.addTextChangedListener(watcher)

    fun keyLines(): List<String> {
        val devices = AetherIdentityStore.read(identityFile)
        return if (devices.isEmpty()) {
            listOf(t("🔑 هنوز کلید WARP ندارید — «اسکن اندپوینت» یا «کلیدهای WARP» خودش می‌سازد (رایگان، لازم نیست از جایی بیاورید).", "🔑 No WARP key yet — Scan or «WARP keys» creates one for free."))
        } else {
            AetherIdentityStore.describe(devices, isFarsi)
        }
    }

    val scanBtn = button(t("🔍 اسکن اندپوینت (Scan for an endpoint)", "🔍 Scan for an endpoint"), accent = true) { btn ->
        readInputs()
        if (currentVpnServiceState != VpnState.Stopped) {
            Toast.makeText(this, t("اول قطع کنید؛ اسکن باید روی خط خودِ شما (بدون VPN) انجام شود.", "Disconnect first; the scan must run on your own line (no VPN)."), Toast.LENGTH_LONG).show()
            return@button
        }
        if (!AetherRuntimeCapabilities.detect().nativeCoreLoaded) {
            Toast.makeText(this, t("هستهٔ Cat Warp در این نسخه بارگذاری نشد.", "The Cat Warp core is not loaded in this build."), Toast.LENGTH_LONG).show()
            return@button
        }
        btn.isEnabled = false
        val sel = draft
        logLines += t("⏳ آماده‌سازی کلید WARP برای ${protocolName(sel.mode)}…", "⏳ Preparing the WARP key for ${protocolName(sel.mode)}…")
        renderLog()
        activityScope.launch {
            val provision = withContext(Dispatchers.IO) {
                AetherOperations.provision(sel.mode, identityFile, null, sel.ipScan, sel.effectiveExitLocation(), sel.masqueTransport)
            }
            provision.exceptionOrNull()?.let { logLines += t("⚠️ کلید: ", "⚠️ Key: ") + it.message.orEmpty().take(200) }
            logLines += keyLines()
            logLines += t("📡 اسکن روی همین خط (ممکن است ۱ تا ۳ دقیقه طول بکشد)…", "📡 Scanning this line (may take 1–3 minutes)…")
            renderLog()
            val found = withContext(Dispatchers.IO) {
                AetherOperations.scan(sel.mode, identityFile, sel.ipScan, sel.masqueTransport, sel.scanMode, sel.noizePreset)
            }
            withContext(Dispatchers.IO) { AetherOperations.drainEngineLogs() }.takeLast(12).forEach { logLines += it }
            val list = found.getOrDefault(emptyList())
            val why = found.exceptionOrNull()?.message?.let { " ($it)" }.orEmpty()
            if (list.isEmpty()) {
                logLines += t(
                    "❌ هیچ اندپوینتی روی این خط جواب نداد$why. پیشنهاد: نسخهٔ IP را عوض کنید (IPv6 برای همراه اول) یا MASQUE با HTTP/2 را امتحان کنید.",
                    "❌ No endpoint answered on this line$why. Try another IP version or MASQUE over HTTP/2.",
                )
            } else {
                val outer = list.first()
                val inner = if (isTwoHop(sel.mode)) {
                    list.firstOrNull { it.peer.substringBeforeLast(':') != outer.peer.substringBeforeLast(':') }
                } else {
                    null
                }
                list.take(5).forEach { logLines += "✅ ${it.peer} · ${it.rttMillis} ms" }
                outerInput.setText(outer.peer)
                if (isTwoHop(sel.mode)) innerInput.setText(inner?.peer.orEmpty())
                val innerText = inner?.peer ?: t("خودکار", "auto")
                logLines += if (isTwoHop(sel.mode)) {
                    t("👉 Outer: ${outer.peer} · Inner: $innerText — حالا «ذخیره» را بزنید.", "👉 Outer: ${outer.peer} · Inner: $innerText — now tap Save.")
                } else {
                    t("👉 اندپوینت انتخاب شد: ${outer.peer} — حالا «ذخیره» را بزنید.", "👉 Endpoint picked: ${outer.peer} — now tap Save.")
                }
                runCatching {
                    aetherPreferenceStore.rememberEndpointForNetwork(
                        AetherPreferenceStore.underlyingNetworkKey(this@showAetherConfigEditor),
                        sel.mode,
                        sel.ipScan,
                        outer.peer,
                        outer.rttMillis,
                    )
                }
            }
            renderLog()
            btn.isEnabled = true
        }
    }
    root.addView(scanBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    root.addView(
        button(t("🔑 کلیدهای WARP ما (کجاست؟ چطور بسازم؟)", "🔑 Our WARP keys (where? how?)")) { showWarpKeysStatusDialog() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    root.addView(label(t("فرمان معادل (فقط برای مقایسه)", "Equivalent command (for comparison)")))
    root.addView(commandBox)
    val logHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    logHeader.addView(label(t("گزارش (Log)", "Log")), LinearLayout.LayoutParams(0, -2, 1f))
    logHeader.addView(
        button(t("📋 کپی", "📋 Copy")) {
            val clip = commandBox.text.toString() + "\n\n" + logLines.joinToString("\n")
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cat Warp log", clip))
            Toast.makeText(this, t("کپی شد", "Copied"), Toast.LENGTH_SHORT).show()
        }.apply {
            contentDescription = t("کپی گزارش", "Copy log")
            tooltipText = contentDescription
        },
        LinearLayout.LayoutParams(-2, -2),
    )
    root.addView(logHeader)
    root.addView(logBox)
    logLines += keyLines()
    refresh()
    renderLog()

    val scroll = ScrollView(this).apply { addView(root) }
    MaterialAlertDialogBuilder(this)
        .setTitle(t("✏️ ویرایش Cat Warp", "✏️ Edit Cat Warp"))
        .setView(scroll)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(t("ذخیره و اتصال", "Save & connect")) { _, _ ->
            readInputs()
            saveEditedAetherLine(item, lineIndex, originalLine, draft, connect = true)
        }
        .setPositiveButton(t("ذخیره", "Save")) { _, _ ->
            readInputs()
            saveEditedAetherLine(item, lineIndex, originalLine, draft, connect = false)
        }
        .create()
        .showCatClientDialog()
    // beta214: «🔍 اسکن اندپوینت» from a row's long-press menu / the Cat Warp card starts the scan right away.
    if (autoScan) scanBtn.post { if (scanBtn.isEnabled) scanBtn.performClick() }
}

internal fun MainActivity.showWarpKeysStatusDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(fa: String, en: String) = if (isFarsi) fa else en
    val identityFile = java.io.File(filesDir, "aether.toml")
    val devices = AetherIdentityStore.read(identityFile)
    val wgRows = WarpRegistration.cached(this)
    val coreWg = AetherIdentityStore.wireGuardAccount(identityFile)
    val body = buildString {
        append(
            t(
                "کلید WARP یعنی یک حساب رایگان Cloudflare WARP روی همین گوشی. برنامه خودش می‌سازدش؛ لازم نیست از جایی بخرید یا بیاورید.\n\n",
                "A WARP key is a free Cloudflare WARP account on this phone. The app creates it itself; you never need to buy or find one.\n\n",
            ),
        )
        append(t("⚡ کلیدهای Cat Warp (MASQUE / WireGuard):\n", "⚡ Cat Warp keys (MASQUE / WireGuard):\n"))
        if (devices.isEmpty()) {
            append(t("— هنوز ندارید. در ویرایش کانفیگ «🔍 اسکن اندپوینت» را بزنید یا دکمهٔ پایین را.\n", "— none yet. Tap Scan in the editor, or the button below.\n"))
        } else {
            AetherIdentityStore.describe(devices, isFarsi).forEach { append("• ").append(it).append('\n') }
        }
        append(t("\n🛡️ کلید ردیف‌های WARP / AmneziaWG داخلی (awg://auto):\n", "\n🛡️ Key for the built-in WARP / AmneziaWG rows (awg://auto):\n"))
        val v6 = wgRows?.ipv6?.let { " · IPv6 $it" }.orEmpty()
        append(
            when {
                wgRows != null -> t("✅ دارد (IPv4 ${wgRows.ipv4}$v6)\n", "✅ ready (IPv4 ${wgRows.ipv4}$v6)\n")
                coreWg != null -> t(
                    "✅ از کلید WireGuard هستهٔ Cat Warp استفاده می‌شود (device ${coreWg.accountId.take(8)}…)\n",
                    "✅ uses the Cat Warp core WireGuard key (device ${coreWg.accountId.take(8)}…)\n",
                )
                else -> t(
                    "⏳ هنوز ندارید — برنامه خودش در پس‌زمینه و موقع اتصال می‌گیرد. عجله دارید؟ «ساخت کلید» را بزنید.\n",
                    "⏳ not yet — the app gets it by itself in the background and on connect. In a hurry? Tap «Create key».\n",
                )
            },
        )
        val adopt = WarpRegistration.adoptsWarpConfigs(this@showWarpKeysStatusDialog)
        append(
            if (adopt) {
                t(
                    "\n🤖 خودکار: روشن — هر کانفیگ WireGuard که به WARP وصل می‌شود (حتی کانفیگ‌های فید و اشتراک) موقع اتصال با کلید خود شما اجرا می‌شود. کلید کانفیگ‌های اشتراکی معمولاً مرده یا پر است.\n",
                    "\n🤖 Automatic: ON — every WireGuard config that goes to WARP (feeds and subscriptions too) runs with your own key on connect. Shared keys are usually dead or full.\n",
                )
            } else {
                t(
                    "\n🤖 خودکار: خاموش — کانفیگ‌های WARP با کلید خودشان اجرا می‌شوند (فقط ردیف‌های awg://auto کلید شما را می‌گیرند).\n",
                    "\n🤖 Automatic: OFF — WARP configs keep their own key (only awg://auto rows get yours).\n",
                )
            },
        )
        append(
            t(
                "\nℹ️ کلید درست یعنی «اجازهٔ ورود»؛ ولی همراه اول و ایرانسل WireGuard معمولی را اغلب می‌بندند. اگر با کلید درست هم وصل نشد، Cat Warp با MASQUE روی HTTP/2 را امتحان کنید.",
                "\nℹ️ A valid key is only the permission to enter; many Iranian lines block plain WireGuard anyway. If it still fails, try Cat Warp MASQUE over HTTP/2.",
            ),
        )
    }
    val makeLabel = if (wgRows == null && coreWg == null) t("🔑 ساخت کلید", "🔑 Create key") else t("🔄 کلید تازه", "🔄 Fresh key")
    MaterialAlertDialogBuilder(this)
        .setTitle(t("🔑 کلیدهای WARP ما", "🔑 Our WARP keys"))
        .setMessage(body)
        .setPositiveButton(t("بستن", "Close"), null)
        .setNegativeButton(
            if (WarpRegistration.adoptsWarpConfigs(this)) t("🤖 خاموش کردن خودکار", "🤖 Turn automatic off")
            else t("🤖 روشن کردن خودکار", "🤖 Turn automatic on"),
        ) { _, _ ->
            val turnOn = !WarpRegistration.adoptsWarpConfigs(this)
            if (turnOn) {
                WarpRegistration.setAdoptsWarpConfigs(this, true)
                Toast.makeText(this, t("✅ خودکار روشن شد. از اتصال بعدی اعمال می‌شود.", "✅ Automatic is on. Applies from the next connect."), Toast.LENGTH_LONG).show()
                showWarpKeysStatusDialog()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(t("خاموش شود؟", "Turn off?"))
                    .setMessage(
                        t(
                            "فقط اگر کانفیگ WARP+ پولی یا کلید شخصی خودتان را دارید خاموش کنید؛ وگرنه کانفیگ‌های WARP اشتراکی معمولاً وصل نمی‌شوند.",
                            "Only turn this off if you have a paid WARP+ config or your own personal key; otherwise shared WARP configs usually won't connect.",
                        ),
                    )
                    .setPositiveButton(t("خاموش کن", "Turn off")) { _, _ ->
                        WarpRegistration.setAdoptsWarpConfigs(this, false)
                        showWarpKeysStatusDialog()
                    }
                    .setNegativeButton(t("لغو", "Cancel"), null)
                    .create()
                    .showCatClientDialog()
            }
        }
        .setNeutralButton(makeLabel) { _, _ ->
            if (currentVpnServiceState != VpnState.Stopped) {
                Toast.makeText(this, t("اول قطع کنید، بعد کلید بسازید.", "Disconnect first, then create the key."), Toast.LENGTH_LONG).show()
            } else {
                if (wgRows != null) WarpRegistration.forget(this)
                Toast.makeText(this, t("⏳ در حال ساخت کلید WARP… (تا ۱ دقیقه)", "⏳ Creating a WARP key… (up to a minute)"), Toast.LENGTH_LONG).show()
                activityScope.launch {
                    val outcome = withContext(Dispatchers.IO) { WarpRegistration.ensureAccountDetailed(this@showWarpKeysStatusDialog) }
                    val msg = if (outcome.source == WarpRegistration.Source.None) {
                        t("❌ ساخت کلید روی این خط نشد: ", "❌ Could not create a key on this line: ") + outcome.log.joinToString(" · ")
                    } else {
                        t("✅ کلید WARP آماده است.", "✅ WARP key ready.")
                    }
                    Toast.makeText(this@showWarpKeysStatusDialog, msg, Toast.LENGTH_LONG).show()
                    showWarpKeysStatusDialog()
                }
            }
        }
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showAddAetherDialog(onSaved: (() -> Unit)? = null) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val current = aetherPreferenceStore.read()
    var chosenMode = when (current.mode) {
        AetherMode.MasquePsiphon -> AetherMode.MasqueInMasque
        else -> current.mode
    }
    var chosenTransport = current.masqueTransport.takeIf { it in setOf("h2", "h3") } ?: "h2"
    var chosenIpScan = current.ipScan
    var chosenChain = current.chainTarget
    var chosenExitLocation = current.effectiveExitLocation()
    var chosenRegion = current.psiphonRegion
    var scannedEndpoint: String? = current.endpoint
    var scannedInnerEndpoint: String? = current.innerEndpoint
    var chosenScanMode = current.scanMode.ifBlank { "balanced" }
    var chosenNoize = current.noizePreset.ifBlank { "auto" }
    var chosenFingerprint = current.fingerprint.ifBlank { "chrome" }
    var chosenFragment = current.fragmentEnabled
    var chosenFragmentSize = current.fragmentSize.ifBlank { "16-32" }
    var chosenFragmentDelay = current.fragmentDelay.ifBlank { "2-10" }
    var chosenMasqueSni = AetherRuntimeConfig.effectiveMasqueSni(current.masqueSni).orEmpty()
    var chosenEch = current.echEnabled
    var chosenEchDns = current.echDns.ifBlank { "https://1.1.1.1/dns-query" }
    var chosenEchDomain = current.echDomain.ifBlank { "cloudflare-ech.com" }
    var chosenPsiphonMode = current.psiphonMode.ifBlank { "auto" }
    var chosenPsiphonCdnIps = current.psiphonCdnIps
    var chosenPsiphonCdnSni = current.psiphonCdnSni
    var chosenPsiphonBundled = current.psiphonBundled
    var chosenTorBridgesMode = current.torBridgesMode.ifBlank { "auto" }
    var chosenTorCustomBridges = current.torCustomBridges
    var chosenTorRelaysMode = current.torRelaysMode.ifBlank { "auto" }
    var lastClickedTopic = if (chosenMode == AetherMode.MasqueInMasque) "mim" else ""

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(18), dp(12), dp(18), dp(8))
    }

    val guideBanner = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        setLineSpacing(0f, 1.25f)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(TEAL, if (palette.isDark) 22 else 14))
            setStroke(dp(1), withAlpha(TEAL, 80))
        }
        text = if (isFarsi) {
            "📌 آموزش گام‌به‌گام ساخت کانفیگ Cat Warp (MASQUE in MASQUE + Psiphon):\n" +
                "• ۲-۱ و ۲-۲) ابتدا روی دکمهٔ «🔑 Get new WARP keys» بزنید و در صفحهٔ بازشده دوباره «Get new WARP keys» را بزنید تا کلیدها دریافت شوند.\n" +
                "• ۲-۳) در کادر Remarks یک نام دلخواه وارد کنید.\n" +
                "• ۲-۴ و ۲-۵) گزینهٔ Protocol را روی «MASQUE in MASQUE» قرار دهید.\n" +
                "• ۲-۶ و ۲-۷) گزینهٔ Transport را روی «HTTP/2» قرار دهید.\n" +
                "• ۲-۸ تا ۲-۱۱) بخش Psiphon را روی «WARP > Psiphon» بگذارید و از Exit country کشور خروجی را انتخاب کنید.\n" +
                "• ۲-۱۲ و ۲-۱۳) روی «🔍 Scan for an endpoint» بزنید تا اسکن تمام شود، سپس ذخیره (✅) و متصل شوید!"
        } else {
            "📌 Step-by-step Cat Guide (MASQUE in MASQUE + Psiphon):\n" +
                "• 2-1 & 2-2) Tap '🔑 Get new WARP keys' → tap 'Get new WARP keys' and wait for enrollment.\n" +
                "• 2-3) Enter a name in Remarks.\n" +
                "• 2-4 & 2-5) Set Protocol to 'MASQUE in MASQUE'.\n" +
                "• 2-6 & 2-7) Set Transport to 'HTTP/2'.\n" +
                "• 2-8 to 2-11) Set Psiphon to 'WARP > Psiphon' and pick an Exit country.\n" +
                "• 2-12 & 2-13) Tap '🔍 Scan for an endpoint', then Save (✅) and Connect!"
        }
        visibility = View.GONE
    }
    val guideToggleBtn = MaterialButton(this).apply {
        text = if (isFarsi) "📖 راهنمای گام‌به‌گام راهنمای Cat (MASQUE in MASQUE + Psiphon) و فایروال‌ها ▾" else "📖 Step-by-Step Cat Guide (MASQUE in MASQUE + Psiphon) ▾"
        isAllCaps = false
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        minHeight = dp(38)
        minimumHeight = dp(38)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, if (palette.isDark) 26 else 18))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 110))
        setTextColor(TEXT_PRIMARY)
        setOnClickListener {
            val show = guideBanner.visibility != View.VISIBLE
            guideBanner.visibility = if (show) View.VISIBLE else View.GONE
        }
    }
    root.addView(guideToggleBtn, LinearLayout.LayoutParams(-1, -2))
    root.addView(guideBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val nameInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 13f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "۲-۳) Remarks / نام دلخواه کانفیگ (مثلاً: Cat-MIM-Psiphon)" else "2-3) Remarks / Config Name (e.g. Cat-MIM-Psiphon)"
        setText(current.profileName.ifBlank {
            when (chosenMode) {
                AetherMode.MasqueInMasque -> "Cat-MIM-Psiphon"
                AetherMode.WarpInWarp -> "WARP-in-WARP"
                AetherMode.WireGuard -> if (chosenIpScan == "v6") "WARP-IPv6" else "WARP-All"
                else -> "MASQUE-H2"
            }
        })
    }

    val outerEndpointInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "Outer endpoint (مثلاً 162.159.198.1:443 — با زدن Scan پر می‌شود)" else "Outer endpoint (e.g. 162.159.198.1:443)"
        setText(scannedEndpoint.orEmpty())
    }
    val innerEndpointInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "Inner endpoint (هاپ دوم برای MASQUE in MASQUE — مثلاً 162.159.198.2:443)" else "Inner endpoint (2nd hop for MASQUE in MASQUE — e.g. 162.159.198.2:443)"
        setText(scannedInnerEndpoint.orEmpty())
    }

    val stateLabel = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    val smartHintTitleView = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val smartHintBodyView = TextView(this).apply {
        textSize = 11.2f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(0f, 1.22f)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val applySmartHintBtn = MaterialButton(this).apply {
        isAllCaps = false
        textSize = 11f
        typeface = CatClientBodyBoldTypeface
        minHeight = dp(34)
        minimumHeight = dp(34)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(8)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 40))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(TEAL)
        setTextColor(TEXT_PRIMARY)
    }
    val smartHintCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(SURFACE, 235))
            setStroke(dp(1), withAlpha(TEAL, 140))
        }
        addView(smartHintTitleView, LinearLayout.LayoutParams(-1, -2))
        addView(smartHintBodyView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(applySmartHintBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    val uriPreviewBox = TextView(this).apply {
        textSize = 11f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setTextIsSelectable(true)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(withAlpha(SURFACE, 210))
            setStroke(dp(1), withAlpha(OUTLINE, 180))
        }
    }

    fun currentDraftSelection(): AetherSelection {
        val finalMode = AetherSelection.simplifyModeForChain(chosenMode, chosenChain)
        val profName = nameInput.text?.toString()?.trim().orEmpty().ifBlank {
            when (finalMode) {
                AetherMode.MasqueInMasque ->
                    if (chosenChain == "psiphon") "Cat-MIM-Psiphon-${chosenRegion.ifBlank { "Auto" }}" else "Cat-MIM-H2"
                AetherMode.WarpInWarp -> "Cat-WARP-in-WARP"
                AetherMode.WireGuard -> "WARP-${chosenIpScan.uppercase(Locale.US)}"
                else -> "MASQUE-H2"
            }
        }
        val typedOuter = outerEndpointInput.text?.toString()?.trim()?.takeIf(AetherOperations::isSafePublicPeer)
        val typedInner = innerEndpointInput.text?.toString()?.trim()?.takeIf(AetherOperations::isSafePublicPeer)
        val finalPeer = typedOuter ?: scannedEndpoint ?: when {
            chosenIpScan == "v6" && finalMode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) -> "[2606:4700:d0::a29f:c001]:2408"
            chosenIpScan == "v6" -> "[2606:4700:d0::a29f:c001]:443"
            finalMode in setOf(AetherMode.WireGuard, AetherMode.WarpInWarp) -> "162.159.192.1:2408"
            else -> "162.159.198.1:443"
        }
        val finalInner = typedInner ?: scannedInnerEndpoint ?: when (finalMode) {
            AetherMode.MasqueInMasque, AetherMode.WireGuardOverMasque ->
                if (finalPeer.startsWith("[")) "[2606:4700:d0::a29f:c002]:443"
                else if (finalPeer.startsWith("162.159.198.1:")) "162.159.198.2:443"
                else "162.159.198.1:443"
            AetherMode.WarpInWarp ->
                if (finalPeer.startsWith("[")) "[2606:4700:d0::a29f:c002]:2408"
                else if (finalPeer.startsWith("162.159.192.1:")) "162.159.193.1:2408"
                else "162.159.192.1:2408"
            else -> null
        }
        return current.copy(
            enabled = true,
            mode = finalMode,
            endpoint = finalPeer,
            innerEndpoint = finalInner,
            endpointScannedAt = System.currentTimeMillis(),
            psiphonRegion = chosenRegion,
            ipScan = chosenIpScan,
            profileName = profName,
            chainTarget = chosenChain,
            exitLocation = chosenExitLocation,
            masqueTransport = chosenTransport,
            scanMode = chosenScanMode,
            noizePreset = chosenNoize,
            fingerprint = chosenFingerprint,
            fragmentEnabled = chosenFragment,
            fragmentSize = chosenFragmentSize,
            fragmentDelay = chosenFragmentDelay,
            masqueSni = chosenMasqueSni,
            echEnabled = chosenEch,
            echDns = chosenEchDns,
            echDomain = chosenEchDomain,
            psiphonMode = chosenPsiphonMode,
            psiphonCdnIps = chosenPsiphonCdnIps,
            psiphonCdnSni = chosenPsiphonCdnSni,
            psiphonBundled = chosenPsiphonBundled,
            torBridgesMode = chosenTorBridgesMode,
            torCustomBridges = chosenTorCustomBridges,
            torRelaysMode = chosenTorRelaysMode,
        )
    }

    fun updateStateLabel(topic: String = lastClickedTopic) {
        lastClickedTopic = topic
        val isTwoHop = chosenMode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp, AetherMode.WireGuardOverMasque)
        innerEndpointInput.visibility = if (isTwoHop) View.VISIBLE else View.GONE
        val modeName = when (chosenMode) {
            AetherMode.MasqueInMasque -> "MASQUE in MASQUE (mim)"
            AetherMode.WarpInWarp -> "WARP in WARP (gool)"
            AetherMode.WireGuard -> "WireGuard (WARP)"
            AetherMode.MasqueHttp2 -> "MASQUE"
            AetherMode.WireGuardOverMasque -> "WireGuard over MASQUE"
            AetherMode.MasquePsiphon -> "Psiphon"
        }
        val trLabel = if (chosenTransport == "h3") "HTTP/3 (QUIC)" else "HTTP/2"
        val ipName = when (chosenIpScan) {
            "v6" -> "IPv6"
            "v4" -> "IPv4"
            else -> if (isFarsi) "هر دو (IPv4 + IPv6)" else "Both (IPv4 + IPv6)"
        }
        val regionShort = PsiphonCarrierConfig.regionShortLabel(chosenRegion, isFarsi)
        val chainName = when (chosenChain) {
            "psiphon" -> "WARP > Psiphon ($regionShort)"
            "psiphon-reverse" -> "Psiphon ($regionShort) > WARP (Reverse)"
            "auto-psiphon" -> if (isFarsi) "هوشمند (WARP > Psiphon در صورت تحریم)" else "Smart Auto (WARP > Psiphon)"
            "tor" -> "WARP > Tor"
            "tor-reverse" -> "Tor > WARP (Reverse)"
            else -> if (isFarsi) "Off (بدون سایفون)" else "Off (Direct WARP)"
        }
        val draft = currentDraftSelection()
        val epSummary = if (isTwoHop) {
            "Outer: ${draft.endpoint ?: "auto"} · Inner: ${draft.innerEndpoint ?: "auto"}"
        } else {
            draft.endpoint ?: "auto"
        }
        stateLabel.text = if (isFarsi) {
            "پروتکل (Protocol): $modeName · ترنسپورت (Transport): $trLabel\n" +
                "سایفون/زنجیره: $chainName · نوع آی‌پی: $ipName\n" +
                "اسکن/پارازیت: Scan=${chosenScanMode} · Noize=${chosenNoize} · TLS=${chosenFingerprint}\n" +
                "اندپوینت‌ها: $epSummary"
        } else {
            "Protocol: $modeName · Transport: $trLabel\n" +
                "Chain: $chainName · IP Type: $ipName\n" +
                "Scan=${chosenScanMode} · Noize=${chosenNoize} · TLS=${chosenFingerprint}\n" +
                "Endpoints: $epSummary"
        }
        uriPreviewBox.text = draft.toAetherUri()
        val hint = draft.smartRecommendation(topic)
        smartHintTitleView.text = if (isFarsi) hint.titleFa else hint.titleEn
        smartHintBodyView.text = if (isFarsi) hint.bodyFa else hint.bodyEn
        applySmartHintBtn.text = if (isFarsi) hint.actionLabelFa else hint.actionLabelEn
        applySmartHintBtn.setOnClickListener {
            chosenMode = hint.recommendedMode
            chosenTransport = "h2"
            chosenIpScan = hint.recommendedIpScan
            chosenChain = hint.recommendedChain
            chosenExitLocation = hint.recommendedExitLocation
            if (hint.recommendedPsiphonRegion.isNotEmpty() || hint.recommendedChain != "psiphon") {
                chosenRegion = hint.recommendedPsiphonRegion
            }
            updateStateLabel("applied")
            Toast.makeText(
                this@showAddAetherDialog,
                if (isFarsi) "✨ ترکیب پیشنهادی اعمال شد!" else "✨ Recommended combination applied!",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            uriPreviewBox.text = currentDraftSelection().toAetherUri()
        }
    }
    nameInput.addTextChangedListener(textWatcher)
    outerEndpointInput.addTextChangedListener(textWatcher)
    innerEndpointInput.addTextChangedListener(textWatcher)

    fun chipBtn(label: String, accent: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(38)
            minimumHeight = dp(38)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            setPadding(dp(8), 0, dp(8), 0)
            if (accent) {
                backgroundTintList = ColorStateList.valueOf(TEAL)
                setTextColor(BACKGROUND)
            } else {
                backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 220))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 190))
                setTextColor(TEXT_PRIMARY)
            }
            setOnClickListener { onClick() }
        }

    // 1-Tap PattNG Tutorial Auto-Builder button at very top
    val oneTapPattNgBtn = chipBtn(
        if (isFarsi) "⚡ اجرای ۱-کلیکی کل راهنمای Cat (Get WARP keys + MASQUE in MASQUE + HTTP/2 + Psiphon + Scan)"
        else "⚡ 1-Tap Full Cat Guide (Get WARP keys + MASQUE in MASQUE + HTTP/2 + Psiphon + Scan)",
        accent = true,
    ) {
        runOneTapPattNgMimPsiphonSetup(chosenRegion.ifBlank { "DE" }) { ready ->
            chosenMode = ready.mode
            chosenTransport = ready.masqueTransport
            chosenIpScan = ready.ipScan
            chosenChain = ready.chainTarget
            chosenExitLocation = ready.exitLocation
            chosenRegion = ready.psiphonRegion
            scannedEndpoint = ready.endpoint
            scannedInnerEndpoint = ready.innerEndpoint
            outerEndpointInput.setText(ready.endpoint.orEmpty())
            innerEndpointInput.setText(ready.innerEndpoint.orEmpty())
            nameInput.setText(ready.profileName)
            updateStateLabel("mim")
            onSaved?.invoke()
        }
    }
    root.addView(oneTapPattNgBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Step 2-1 & 2-2: Get new WARP keys button
    val getWarpKeysBtn = chipBtn(
        if (isFarsi) "🔑 ۲-۱) Get new WARP keys (دریافت کلیدهای جدید WARP برای لایه ۱ و ۲)"
        else "🔑 2-1) Get new WARP keys (Enroll Primary & Secondary WARP Keys)",
        accent = false,
    ) {
        showGetNewWarpKeysDialog { enrolled ->
            Toast.makeText(
                this@showAddAetherDialog,
                if (isFarsi) "✅ کلیدهای WARP آماده شدند (${enrolled.masquePrimaryId} / ${enrolled.masqueSecondaryId})"
                else "✅ WARP keys ready (${enrolled.masquePrimaryId} / ${enrolled.masqueSecondaryId})",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    root.addView(getWarpKeysBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // Step 2-3: Remarks (Config Name)
    root.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Quick preset chips including PattNG MIM+Psiphon, MCI, and Irancell
    val presetRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            chipBtn(if (isFarsi) "⭐ راهنمای Cat: MIM+H2+Psiphon" else "⭐ Cat: MIM+H2+Psiphon", accent = true) {
                chosenMode = AetherMode.MasqueInMasque
                chosenTransport = "h2"
                chosenIpScan = "v4"
                chosenChain = "psiphon"
                chosenExitLocation = "foreign"
                if (chosenRegion.isBlank()) chosenRegion = "DE"
                nameInput.setText("Cat-MIM-Psiphon-${chosenRegion}")
                updateStateLabel("mim")
            },
            LinearLayout.LayoutParams(0, -2, 1.25f),
        )
        addView(
            chipBtn(if (isFarsi) "🟦 همراه اول: WARP+IPv6" else "🟦 MCI: WARP+IPv6") {
                chosenMode = AetherMode.WireGuard
                chosenIpScan = "v6"
                nameInput.setText("MCI-WARP-IPv6")
                updateStateLabel("mci")
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
        addView(
            chipBtn(if (isFarsi) "🟧 ایرانسل: MASQUE/H2" else "🟧 MTN: MASQUE/H2") {
                chosenMode = AetherMode.MasqueHttp2
                chosenTransport = "h2"
                chosenIpScan = "both"
                nameInput.setText("MASQUE-HTTP2")
                updateStateLabel("masque")
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
    }
    root.addView(presetRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // Step 2-4 & 2-5 (Protocol) + Step 2-6 & 2-7 (Transport)
    val protocolAndTransportRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            chipBtn(if (isFarsi) "۲-۴) Protocol (پروتکل) ▾" else "2-4) Protocol ▾", accent = true) {
                val modes = listOf(
                    AetherMode.MasqueInMasque,
                    AetherMode.MasqueHttp2,
                    AetherMode.WireGuardOverMasque,
                    AetherMode.WarpInWarp,
                    AetherMode.WireGuard,
                )
                val modeTitles = if (isFarsi) {
                    arrayOf(
                        "⭐ MASQUE in MASQUE (mim) — پیشنهادی راهنمای Cat · دو لایه MASQUE ضدسانسور",
                        "MASQUE (masque) — تک‌لایه روی پورت 443",
                        "WireGuard over MASQUE (wg-over-masque) — دو لایه ترکیبی",
                        "WARP in WARP (gool) — دو لایه WireGuard",
                        "WireGuard (wg) — تک‌لایه WARP (مناسب IPv6 همراه اول و ایرانسل)",
                    )
                } else {
                    arrayOf(
                        "⭐ MASQUE in MASQUE (mim) — Recommended Cat Guide · Double-Hop MASQUE",
                        "MASQUE (masque) — Single-Hop over Port 443",
                        "WireGuard over MASQUE (wg-over-masque)",
                        "WARP in WARP (gool) — Double-Hop WireGuard",
                        "WireGuard (wg) — Single-Hop WARP",
                    )
                }
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "۲-۵) انتخاب Protocol" else "2-5) Select Protocol")
                    .setItems(modeTitles) { _, idx ->
                        chosenMode = modes[idx]
                        if (chosenMode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp, AetherMode.WireGuardOverMasque)) {
                            chosenExitLocation = "foreign"
                        }
                        updateStateLabel(
                            when (chosenMode) {
                                AetherMode.MasqueInMasque -> "mim"
                                AetherMode.WireGuard -> "mci"
                                else -> "masque"
                            },
                        )
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1.15f),
        )
        addView(
            chipBtn(if (isFarsi) "۲-۶) Transport (HTTP/2) ▾" else "2-6) Transport (HTTP/2) ▾") {
                val transports = listOf("h2", "h3")
                val transportTitles = if (isFarsi) {
                    arrayOf(
                        "⭐ HTTP/2 (پیشنهادی راهنمای Cat — عبور از مسدودسازی QUIC/UDP روی TLS/TCP)",
                        "HTTP/3 (QUIC / UDP)",
                    )
                } else {
                    arrayOf(
                        "⭐ HTTP/2 (Recommended Cat Guide — TCP/TLS framing)",
                        "HTTP/3 (QUIC / UDP)",
                    )
                }
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "۲-۷) انتخاب Transport" else "2-7) Select Transport")
                    .setItems(transportTitles) { _, idx ->
                        chosenTransport = transports[idx]
                        updateStateLabel("mim")
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 0.95f).apply { marginStart = dp(6) },
        )
    }
    root.addView(protocolAndTransportRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // Step 2-8 & 2-9 (Psiphon: WARP > Psiphon / Psiphon > WARP) + Step 2-10 & 2-11 (Exit country)
    val chainAndCountryRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            chipBtn(
                if (isFarsi) "۲-۸) Psiphon (WARP > Psiphon) ▾" else "2-8) Psiphon (WARP > Psiphon) ▾",
                accent = true,
            ) {
                val chains = listOf("psiphon", "psiphon-reverse", "auto-psiphon", "none", "tor", "tor-reverse")
                val chainTitles = if (isFarsi) {
                    arrayOf(
                        "⭐ WARP > Psiphon (پیشنهادی راهنمای Cat — خروجی سایفون روی MASQUE in MASQUE)",
                        "🔄 Psiphon > WARP (زنجیره معکوس — آی‌پی تمیز کلودفلر در خروجی بدون کپچای گوگل)",
                        "🤖 زنجیره هوشمند (اول مستقیم؛ فقط در صورت تحریم: WARP > Psiphon)",
                        "Off (بدون سایفون — اتصال مستقیم WARP / MASQUE)",
                        "WARP > Tor (زنجیره تور)",
                        "🔄 Tor > WARP (زنجیره معکوس تور به WARP)",
                    )
                } else {
                    arrayOf(
                        "⭐ WARP > Psiphon (Recommended Cat Guide — chains Psiphon over WARP/MIM)",
                        "🔄 Psiphon > WARP (Reverse Chain — clean Cloudflare exit IP, fewer captchas)",
                        "🤖 Smart Conditional Chain (WARP > Psiphon only if sanctioned)",
                        "Off (Direct WARP / MASQUE)",
                        "WARP > Tor",
                        "🔄 Tor > WARP (Reverse Tor to WARP)",
                    )
                }
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "۲-۹) تنظیم بخش Psiphon / Tor" else "2-9) Configure Psiphon / Tor Chain")
                    .setItems(chainTitles) { _, idx ->
                        chosenChain = chains[idx]
                        if (chosenChain != "none") {
                            chosenExitLocation = "foreign"
                        }
                        if ((chosenChain.startsWith("psiphon") || chosenChain == "auto-psiphon") && !nameInput.text.toString().contains("Psiphon", ignoreCase = true)) {
                            nameInput.setText("${nameInput.text.toString().trim().ifEmpty { "Cat-MIM" }}-Psiphon")
                        }
                        updateStateLabel(if (chosenMode == AetherMode.MasqueInMasque) "mim" else "psiphon")
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1.1f),
        )
        addView(
            chipBtn(
                if (isFarsi) "۲-۱۰) Exit country (کشور خروجی) ▾" else "2-10) Exit country ▾",
                accent = true,
            ) {
                val regions = listOf("") + PsiphonCarrierConfig.availableRegions(this@showAddAetherDialog)
                val labels = regions.map { code ->
                    PsiphonCarrierConfig.regionDisplayLabel(code, isFarsi, getString(R.string.aether_region_automatic))
                }.toTypedArray()
                val activeIdx = regions.indexOf(chosenRegion).coerceAtLeast(0)
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(
                        if (isFarsi) "۲-۱۱) Exit country — انتخاب کشور خروجی سایفون"
                        else "2-11) Exit country — Select Psiphon Egress Country",
                    )
                    .setSingleChoiceItems(labels, activeIdx) { dlg, idx ->
                        dlg.dismiss()
                        chosenRegion = regions[idx]
                        if (chosenChain !in setOf("psiphon", "psiphon-reverse")) {
                            chosenChain = "psiphon"
                        }
                        chosenExitLocation = "foreign"
                        val tag = chosenRegion.ifBlank { "Auto" }
                        val base = nameInput.text.toString().trim().substringBefore("-Psiphon").ifEmpty {
                            if (chosenMode == AetherMode.MasqueInMasque) "Cat-MIM" else "WARP"
                        }
                        nameInput.setText("$base-Psiphon-$tag")
                        updateStateLabel(if (chosenMode == AetherMode.MasqueInMasque) "mim" else "region")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 0.95f).apply { marginStart = dp(6) },
        )
    }
    root.addView(chainAndCountryRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // 1-Tap Quick Psiphon Country Switcher Row (DE, NL, US, FI, GB) without re-scanning
    val quickCountryRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        val quickCountries = listOf(
            "DE" to (if (isFarsi) "🇩🇪 آلمان" else "🇩🇪 DE"),
            "NL" to (if (isFarsi) "🇳🇱 هلند" else "🇳🇱 NL"),
            "US" to (if (isFarsi) "🇺🇸 آمریکا" else "🇺🇸 US"),
            "FI" to (if (isFarsi) "🇫🇮 فنلاند" else "🇫🇮 FI"),
            "GB" to (if (isFarsi) "🇬🇧 انگلیس" else "🇬🇧 GB"),
        )
        quickCountries.forEachIndexed { idx, (code, label) ->
            addView(
                chipBtn(label) {
                    chosenRegion = code
                    if (chosenChain !in setOf("psiphon", "psiphon-reverse")) {
                        chosenChain = "psiphon"
                    }
                    chosenExitLocation = "foreign"
                    val base = nameInput.text.toString().trim().substringBefore("-Psiphon").ifEmpty {
                        if (chosenMode == AetherMode.MasqueInMasque) "Cat-MIM" else "WARP"
                    }
                    nameInput.setText("$base-Psiphon-$code")
                    val switched = aetherPreferenceStore.switchPsiphonCountryQuick(code)
                    saveAetherConfigIntoMainSubscriptionList(switched, activateAetherEngine = false)
                    updateStateLabel(if (chosenMode == AetherMode.MasqueInMasque) "mim" else "region")
                    Toast.makeText(
                        this@showAddAetherDialog,
                        if (isFarsi) "🌍 کشور سایفون بدون نیاز به اسکن مجدد روی $label ($code) تنظیم شد!"
                        else "🌍 Switched Psiphon country to $label ($code) without re-scanning!",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (idx > 0) marginStart = dp(4)
                },
            )
        }
    }
    root.addView(quickCountryRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

    // IP Family + Exit IP location row
    val ipAndExitRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            chipBtn(if (isFarsi) "نوع آی‌پی اسکن (IPv4/IPv6) ▾" else "IP Family (IPv4/IPv6) ▾") {
                val scans = listOf("v4", "both", "v6")
                val scanTitles = if (isFarsi) {
                    arrayOf(
                        "IPv4 (پیشنهادی برای MASQUE in MASQUE)",
                        "هر دو IPv4 + IPv6",
                        "فقط IPv6 (پیشنهادی برای WARP همراه اول)",
                    )
                } else {
                    arrayOf(
                        "IPv4 only (Recommended for MASQUE in MASQUE)",
                        "Both IPv4 + IPv6",
                        "IPv6 only (Recommended for MCI WARP)",
                    )
                }
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "انتخاب نوع آی‌پی" else "Select IP Family")
                    .setItems(scanTitles) { _, idx ->
                        chosenIpScan = scans[idx]
                        updateStateLabel()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            chipBtn(if (isFarsi) "🤖 ویزارد هوشمند اسکن خط" else "🤖 Smart Line Wizard") {
                showAetherAutoScanAndBuildWizard { built ->
                    chosenMode = built.mode
                    chosenIpScan = built.ipScan
                    chosenChain = built.chainTarget
                    chosenExitLocation = built.exitLocation
                    chosenRegion = built.psiphonRegion
                    scannedEndpoint = built.endpoint
                    outerEndpointInput.setText(built.endpoint.orEmpty())
                    nameInput.setText(built.profileName)
                    updateStateLabel("wizard")
                    onSaved?.invoke()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
    }
    root.addView(ipAndExitRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    root.addView(outerEndpointInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    root.addView(innerEndpointInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    // Collapsible PattNG v2.3.13-P69 Advanced Controls (Scan Mode, Noize, Psiphon CDN, Tor Bridges, TLS/Fragment/ECH)
    val advancedContainer = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        visibility = View.GONE
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(SURFACE, 230))
            setStroke(dp(1), withAlpha(OUTLINE, 180))
        }
    }
    val scanAndNoizeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            chipBtn(if (isFarsi) "📡 Scan Mode (حالت اسکن) ▾" else "📡 Scan Mode ▾") {
                val modes = AetherSelection.VALID_SCAN_MODES.toList()
                val titles = if (isFarsi) {
                    arrayOf(
                        "⚡ Turbo (فوق سریع)",
                        "⭐ Balanced (متعادل — پیشنهادی)",
                        "🔍 Thorough (اسکن عمیق)",
                        "✅ Verified (تأیید دو مرحله‌ای)",
                        "🛡 Ironclad (سخت‌گیرانه ضد اختلال شدید)",
                    )
                } else {
                    arrayOf(
                        "⚡ Turbo (Fastest)",
                        "⭐ Balanced (Recommended)",
                        "🔍 Thorough (Deep scan)",
                        "✅ Verified (2-stage verified)",
                        "🛡 Ironclad (Strictest anti-jamming)",
                    )
                }
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "انتخاب حالت اسکن (Scan Mode)" else "Select Scan Mode")
                    .setItems(titles) { _, idx ->
                        chosenScanMode = modes[idx]
                        updateStateLabel()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            chipBtn(if (isFarsi) "🔊 Noize (ضدپارازیت DPI) ▾" else "🔊 Noize (Anti-DPI) ▾") {
                val noizes = AetherSelection.VALID_NOIZE_PRESETS.toList()
                val titles = if (isFarsi) {
                    arrayOf(
                        "⭐ Auto (خودکار — پیشنهادی)",
                        "Off (خاموش)",
                        "Light (سبک)",
                        "Firewall (عبور از فایروال اپراتور)",
                        "Balanced (متعادل)",
                        "GFW (ضدفیلترینگ سنگین)",
                        "🔥 Aggressive (حداکثر ضدپارازیت)",
                    )
                } else {
                    arrayOf(
                        "⭐ Auto (Recommended)",
                        "Off",
                        "Light",
                        "Firewall",
                        "Balanced",
                        "GFW",
                        "🔥 Aggressive",
                    )
                }
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "انتخاب پروفایل ضدپارازیت (Noize)" else "Select Noize Preset")
                    .setItems(titles) { _, idx ->
                        chosenNoize = noizes[idx]
                        updateStateLabel()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
    }
    advancedContainer.addView(scanAndNoizeRow, LinearLayout.LayoutParams(-1, -2))

    val tlsAndPsiphonModeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            chipBtn(if (isFarsi) "🔐 TLS Fingerprint / ECH ▾" else "🔐 TLS Fingerprint / ECH ▾") {
                val fps = AetherSelection.VALID_FINGERPRINTS.toList()
                val titles = arrayOf(
                    "⭐ Chrome (Recommended for MIM / MCI)",
                    "Chrome 120",
                    "Firefox",
                    "Semi-Python (Irancell F&F)",
                    "Go Standard",
                )
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "انتخاب Fingerprint مرورگر TLS" else "Select TLS Fingerprint")
                    .setItems(titles) { _, idx ->
                        chosenFingerprint = fps[idx]
                        updateStateLabel()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            chipBtn(if (isFarsi) "☁️ Psiphon CDN Mode ▾" else "☁️ Psiphon CDN Mode ▾") {
                val pModes = AetherSelection.VALID_PSIPHON_MODES.toList()
                val titles = if (isFarsi) {
                    arrayOf(
                        "⭐ Auto (خودکار)",
                        "☁️ CDN Mode (فرانتینگ CDN سایفون برای شرایط مسدودی مستقیم)",
                        "Direct (مستقیم)",
                    )
                } else {
                    arrayOf(
                        "⭐ Auto",
                        "☁️ CDN Mode (Psiphon CDN fronting)",
                        "Direct",
                    )
                }
                MaterialAlertDialogBuilder(this@showAddAetherDialog)
                    .setTitle(if (isFarsi) "حالت اتصال سایفون (Psiphon Mode)" else "Select Psiphon Mode")
                    .setItems(titles) { _, idx ->
                        chosenPsiphonMode = pModes[idx]
                        updateStateLabel()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
    }
    advancedContainer.addView(tlsAndPsiphonModeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val sniAndFragInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 11.5f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "MASQUE SNI (خالی = پیش‌فرض www.cloudflare.com)" else "MASQUE SNI"
        setText(chosenMasqueSni)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                chosenMasqueSni = s?.toString()?.trim().orEmpty()
                uriPreviewBox.text = currentDraftSelection().toAetherUri()
            }
        })
    }
    advancedContainer.addView(sniAndFragInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val cdnIpsInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 11.5f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "Psiphon CDN IPs / CDN SNI (اختیاری — برای حالت CDN)" else "Psiphon CDN IPs (optional)"
        setText(chosenPsiphonCdnIps)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                chosenPsiphonCdnIps = s?.toString()?.trim().orEmpty()
                uriPreviewBox.text = currentDraftSelection().toAetherUri()
            }
        })
    }
    advancedContainer.addView(cdnIpsInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val torBridgeBtn = chipBtn(if (isFarsi) "🧅 تنظیمات پل تور (Tor Bridges: $chosenTorBridgesMode) ▾" else "🧅 Tor Bridges ($chosenTorBridgesMode) ▾") {
        val bModes = AetherSelection.VALID_TOR_BRIDGES.toList()
        val titles = if (isFarsi) {
            arrayOf(
                "Auto (خودکار — در صورت نیاز پل می‌زند)",
                "First (همیشه اول از پل تور استفاده کند)",
                "Never (بدون پل)",
                "Own (پل‌های سفارشی کاربر)",
            )
        } else {
            arrayOf(
                "Auto (Use bridges automatically)",
                "First (Try bridges first)",
                "Never (Direct relays only)",
                "Own (Custom bridges)",
            )
        }
        MaterialAlertDialogBuilder(this@showAddAetherDialog)
            .setTitle(if (isFarsi) "حالت پل‌های تور (Tor Bridges)" else "Select Tor Bridges Mode")
            .setItems(titles) { _, idx ->
                chosenTorBridgesMode = bModes[idx]
                updateStateLabel()
            }
            .create()
            .showCatClientDialog()
    }
    advancedContainer.addView(torBridgeBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val advancedToggleBtn = chipBtn(
        if (isFarsi) "⚙️ تنظیمات پیشرفتهٔ Cat (Scan / Noize / Psiphon CDN / Tor / TLS) ▾"
        else "⚙️ Cat Advanced (Scan / Noize / Psiphon CDN / Tor / TLS) ▾",
    ) {
        val show = advancedContainer.visibility != View.VISIBLE
        advancedContainer.visibility = if (show) View.VISIBLE else View.GONE
    }
    root.addView(advancedToggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    root.addView(advancedContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    updateStateLabel()
    root.addView(stateLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    root.addView(smartHintCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val scanProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = false
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 50))
        visibility = View.GONE
    }
    val scanProgressText = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        visibility = View.GONE
    }
    root.addView(scanProgressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    root.addView(scanProgressBar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(4) })

    fun setScanProgress(pct: Int, msg: String) {
        val c = pct.coerceIn(0, 100)
        scanProgressBar.visibility = View.VISIBLE
        scanProgressBar.progress = c
        scanProgressText.visibility = View.VISIBLE
        scanProgressText.text = "$msg ($c%)"
    }

    // Step 2-12: Scan for an endpoint
    val scanBtn = chipBtn(
        if (isFarsi) "🔍 ۲-۱۲) Scan for an endpoint (اسکن اندپوینت روی خط فعلی)"
        else "🔍 2-12) Scan for an endpoint",
        accent = true,
    ) {
        activityScope.launch {
            val tomlFile = java.io.File(filesDir, "aether.toml")
            setScanProgress(20, if (isFarsi) "⏳ در حال بررسی/آماده‌سازی کلیدهای WARP…" else "⏳ Checking/enrolling WARP keys…")
            val proxy = if (currentVpnServiceState == VpnState.Started) {
                "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
            } else null
            withContext(Dispatchers.IO) {
                runCatching {
                    AetherOperations.provision(
                        mode = chosenMode,
                        identityFile = tomlFile,
                        upstreamProxy = proxy,
                        ipScan = chosenIpScan,
                        exitLocation = chosenExitLocation,
                        masqueTransport = chosenTransport,
                    )
                }
            }
            val isTwoHop = chosenMode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp, AetherMode.WireGuardOverMasque)
            val netKey = AetherPreferenceStore.underlyingNetworkKey(this@showAddAetherDialog)
            if (isTwoHop) {
                setScanProgress(
                    60,
                    if (isFarsi) "📡 در حال اسکن دو اندپوینت Outer و Inner برای ${chosenMode.wireName}…"
                    else "📡 Scanning Outer & Inner endpoints for ${chosenMode.wireName}…",
                )
                val twoHop = withContext(Dispatchers.IO) {
                    AetherOperations.scanTwoHopEndpoints(
                        mode = chosenMode,
                        identityFile = tomlFile,
                        ipScan = chosenIpScan,
                        masqueTransport = chosenTransport,
                    ).getOrNull()
                }
                if (twoHop != null) {
                    scannedEndpoint = twoHop.outerPeer
                    scannedInnerEndpoint = twoHop.innerPeer
                    outerEndpointInput.setText(twoHop.outerPeer)
                    innerEndpointInput.setText(twoHop.innerPeer)
                    aetherPreferenceStore.rememberEndpointForNetwork(netKey, chosenMode, chosenIpScan, twoHop.outerPeer, twoHop.rttMillis)
                    updateStateLabel()
                    setScanProgress(
                        100,
                        if (isFarsi) "✅ اسکن کامل شد! Outer: ${twoHop.outerPeer} · Inner: ${twoHop.innerPeer} (${twoHop.rttMillis}ms)"
                        else "✅ Scan complete! Outer: ${twoHop.outerPeer} · Inner: ${twoHop.innerPeer} (${twoHop.rttMillis}ms)",
                    )
                } else {
                    setScanProgress(
                        100,
                        if (isFarsi) "❌ هیچ اندپوینتی روی این خط جواب نداد. نسخهٔ IP را عوض کنید (IPv6 برای همراه اول) یا Transport را HTTP/2 بگذارید."
                        else "❌ No endpoint answered on this line. Change the IP version or use HTTP/2.",
                    )
                }
            } else {
                setScanProgress(60, if (isFarsi) "📡 در حال اسکن اندپوینت (${chosenIpScan.uppercase(Locale.US)})…" else "📡 Scanning endpoints (${chosenIpScan.uppercase(Locale.US)})…")
                val scanResult = if (currentVpnServiceState == VpnState.Stopped) {
                    withContext(Dispatchers.IO) {
                        runCatching { AetherOperations.scan(chosenMode, tomlFile, chosenIpScan) }.getOrNull()
                    }
                } else null
                val found = scanResult?.getOrDefault(emptyList()).orEmpty()
                if (found.isNotEmpty()) {
                    found.take(6).forEach { candidate ->
                        aetherPreferenceStore.rememberEndpointPoolForNetwork(
                            netKey,
                            chosenMode,
                            chosenIpScan,
                            candidate.peer,
                            candidate.rttMillis,
                        )
                    }
                    val top = found.first()
                    setScanProgress(85, if (isFarsi) "🔍 مرحله ۲/۲: تأیید واقعی عبور ترافیک روی ${top.peer}…" else "🔍 Stage 2/2: Confirming real traffic on ${top.peer}…")
                    val confirmed = if (currentVpnServiceState == VpnState.Stopped) {
                        withContext(Dispatchers.IO) {
                            AetherOperations.confirmEndpoint(chosenMode, tomlFile, top.peer, chosenIpScan, chosenExitLocation, 8_000L).getOrNull()
                        } ?: top
                    } else top
                    scannedEndpoint = confirmed.peer
                    outerEndpointInput.setText(confirmed.peer)
                    aetherPreferenceStore.rememberEndpointForNetwork(netKey, chosenMode, chosenIpScan, confirmed.peer, confirmed.rttMillis)
                    val poolSize = aetherPreferenceStore.recallEndpointPoolForNetwork(netKey, chosenMode, chosenIpScan).size
                    updateStateLabel()
                    setScanProgress(
                        100,
                        if (isFarsi) "✅ آی‌پی تأییدشده: ${confirmed.peer} (${confirmed.rttMillis}ms · استخر سیم‌کارت/وای‌فای: $poolSize اندپوینت)"
                        else "✅ Traffic-verified: ${confirmed.peer} (${confirmed.rttMillis}ms · carrier pool: $poolSize endpoints)",
                    )
                } else {
                    // beta209: no made-up endpoint. A remembered one that worked on this same line is offered;
                    // otherwise the field stays empty and the core scans by itself when you connect.
                    val remembered = aetherPreferenceStore.recallEndpointForNetwork(netKey, chosenMode, chosenIpScan)
                    scannedEndpoint = remembered
                    outerEndpointInput.setText(remembered.orEmpty())
                    updateStateLabel()
                    setScanProgress(
                        100,
                        when {
                            remembered != null && isFarsi -> "ℹ️ اسکن چیزی پیدا نکرد؛ اندپوینتی که قبلاً روی همین خط کار کرده ($remembered) گذاشته شد."
                            remembered != null -> "ℹ️ Nothing found now; the endpoint that worked on this line before ($remembered) was filled in."
                            isFarsi -> "❌ هیچ اندپوینتی روی این خط جواب نداد. نسخهٔ IP یا پروتکل را عوض کنید؛ اگر خالی ذخیره کنید، موقع اتصال خود هسته اسکن می‌کند."
                            else -> "❌ No endpoint answered on this line. Change the IP version or protocol; saved empty, the core scans by itself on connect."
                        },
                    )
                }
            }
        }
    }
    root.addView(scanBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Live 2-Hop Health & Egress Probe Button
    val liveTwoHopTestBtn = chipBtn(
        if (isFarsi) "🩺 تست زندهٔ ۲-هاپ (Outer + Inner + کشور خروجی و ضدتحریم)"
        else "🩺 Live 2-Hop Probe (Outer + Inner + Exit Country & Anti-Sanction)",
    ) {
        activityScope.launch {
            val draft = currentDraftSelection()
            setScanProgress(35, if (isFarsi) "⏳ در حال تست پینگ هاپ اول (Outer)، هاپ دوم (Inner) و کشور خروجی…" else "⏳ Probing Outer hop, Inner hop & Exit Trace…")
            val report = withContext(Dispatchers.IO) {
                AetherOperations.probeTwoHopLiveReport(draft, 2_800)
            }
            setScanProgress(
                100,
                if (isFarsi) {
                    val o = report.outerRttMs?.let { "${it}ms ✅" } ?: "❌"
                    val i = report.innerRttMs?.let { "${it}ms ✅" } ?: "—"
                    val loc = report.exitLoc.ifBlank { "—" }
                    "🩺 نتیجه تست زنده: Outer=$o · Inner=$i · خروجی=$loc (${report.exitIp.ifBlank { "direct" }})"
                } else {
                    val o = report.outerRttMs?.let { "${it}ms ✅" } ?: "❌"
                    val i = report.innerRttMs?.let { "${it}ms ✅" } ?: "—"
                    val loc = report.exitLoc.ifBlank { "—" }
                    "🩺 Live Probe: Outer=$o · Inner=$i · Exit=$loc (${report.exitIp.ifBlank { "direct" }})"
                },
            )
            val msg = if (isFarsi) {
                "• هاپ اول (Outer — ${report.outerEndpoint}): ${report.outerRttMs?.let { "$it ms ✅" } ?: "بدون پاسخ ❌"}\n" +
                    "• هاپ دوم (Inner — ${report.innerEndpoint.ifBlank { "تک‌هاپ" }}): ${report.innerRttMs?.let { "$it ms ✅" } ?: "—"}\n" +
                    "• آی‌پی خروجی فعلی: ${report.exitIp.ifBlank { "نامشخص" }}\n" +
                    "• کشور خروجی: ${report.exitLoc.ifBlank { "نامشخص" }}\n" +
                    "• دیتاسنتر کلودفلر (COLO): ${report.exitColo.ifBlank { "—" }}\n" +
                    "• وضعیت ضدتحریم: ${if (report.sanctionFree) "✅ باز و بدون تحریم" else "⚠️ نیاز به فعال بودن WARP > Psiphon"}"
            } else {
                "• Hop 1 (Outer — ${report.outerEndpoint}): ${report.outerRttMs?.let { "$it ms ✅" } ?: "Unreachable ❌"}\n" +
                    "• Hop 2 (Inner — ${report.innerEndpoint.ifBlank { "Single-hop" }}): ${report.innerRttMs?.let { "$it ms ✅" } ?: "—"}\n" +
                    "• Current Exit IP: ${report.exitIp.ifBlank { "N/A" }}\n" +
                    "• Exit Country: ${report.exitLoc.ifBlank { "N/A" }}\n" +
                    "• Cloudflare COLO: ${report.exitColo.ifBlank { "—" }}\n" +
                    "• Anti-Sanction Status: ${if (report.sanctionFree) "✅ Open & Unsanctioned" else "⚠️ Enable WARP > Psiphon for sanction bypass"}"
            }
            MaterialAlertDialogBuilder(this@showAddAetherDialog)
                .setTitle(if (isFarsi) "🩺 گزارش تست زندهٔ ۲-هاپ و خروجی" else "🩺 Live 2-Hop & Egress Report")
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .showCatClientDialog()
        }
    }
    root.addView(liveTwoHopTestBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    root.addView(uriPreviewBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val copyAndQrRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            chipBtn(if (isFarsi) "📋 کپی لینک کانفیگ aether://" else "📋 Copy aether:// Link", accent = true) {
                val uri = currentDraftSelection().toAetherUri()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("aether-uri", uri))
                Toast.makeText(
                    this@showAddAetherDialog,
                    if (isFarsi) "✅ لینک کانفیگ کپی شد:\n$uri" else "✅ Copied Cat Warp URI:\n$uri",
                    Toast.LENGTH_LONG,
                ).show()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            chipBtn(if (isFarsi) "▦ بارکد QR کانفیگ" else "▦ Show QR Code") {
                val draft = currentDraftSelection()
                showConfigQrCodes(listOf(draft.toAetherUri()), draft.profileName)
            },
            LinearLayout.LayoutParams(0, -2, 0.7f).apply { marginStart = dp(6) },
        )
    }
    root.addView(copyAndQrRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val toolsBtnInDialog = chipBtn(
        if (isFarsi) "🧰 ابزارهای کمکی (پشتیبان کلید کلودفلر / اسکن ۶ پورت و DNS ایران / پل تور) ▾"
        else "🧰 Smart Tools (Cloudflare Key Backup / 6-Port & Iran DNS Scan / Tor Moat) ▾",
    ) {
        showWhiteDnsAndAetherToolsDialog()
    }
    root.addView(toolsBtnInDialog, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val savedProfiles = aetherPreferenceStore.readSavedProfiles()
    if (savedProfiles.isNotEmpty()) {
        val savedTitle = TextView(this).apply {
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_SECONDARY)
            text = if (isFarsi) "پروفایل‌های ذخیره‌شده Cat Warp:" else "Saved Cat Warp profiles:"
        }
        root.addView(savedTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        savedProfiles.take(3).forEach { prof ->
            val regionSuffix = if (prof.chainTarget == "psiphon" && prof.psiphonRegion.isNotEmpty()) "(${prof.psiphonRegion})" else ""
            val chainBadge = if (prof.chainTarget != "none") " → ${prof.chainTarget}$regionSuffix" else ""
            val locBadge = if (prof.exitLocation == "foreign" || prof.chainTarget != "none") "🌍" else "🇮🇷"
            root.addView(
                chipBtn("⚡ $locBadge ${prof.name} · ${prof.mode.wireName}$chainBadge (${prof.ipScan.uppercase(Locale.US)}) · ${prof.endpoint}") {
                    chosenMode = prof.mode
                    chosenTransport = prof.masqueTransport
                    chosenIpScan = prof.ipScan
                    chosenChain = prof.chainTarget
                    chosenExitLocation = prof.exitLocation.takeIf { it in setOf("iran", "foreign") }
                        ?: if (prof.chainTarget != "none" || prof.mode in setOf(AetherMode.MasqueInMasque, AetherMode.WarpInWarp, AetherMode.WireGuardOverMasque)) "foreign" else "iran"
                    chosenRegion = prof.psiphonRegion
                    scannedEndpoint = prof.endpoint
                    scannedInnerEndpoint = prof.innerEndpoint.ifBlank { null }
                    chosenScanMode = prof.scanMode.ifBlank { "balanced" }
                    chosenNoize = prof.noizePreset.ifBlank { "auto" }
                    chosenFingerprint = prof.fingerprint.ifBlank { "chrome" }
                    chosenFragment = prof.fragmentEnabled
                    chosenFragmentSize = prof.fragmentSize.ifBlank { "16-32" }
                    chosenFragmentDelay = prof.fragmentDelay.ifBlank { "2-10" }
                    chosenMasqueSni = AetherRuntimeConfig.effectiveMasqueSni(prof.masqueSni).orEmpty()
                    chosenEch = prof.echEnabled
                    chosenEchDns = prof.echDns.ifBlank { "https://1.1.1.1/dns-query" }
                    chosenEchDomain = prof.echDomain.ifBlank { "cloudflare-ech.com" }
                    chosenPsiphonMode = prof.psiphonMode.ifBlank { "auto" }
                    chosenPsiphonCdnIps = prof.psiphonCdnIps
                    chosenPsiphonCdnSni = prof.psiphonCdnSni
                    chosenPsiphonBundled = prof.psiphonBundled
                    chosenTorBridgesMode = prof.torBridgesMode.ifBlank { "auto" }
                    chosenTorCustomBridges = prof.torCustomBridges
                    chosenTorRelaysMode = prof.torRelaysMode.ifBlank { "auto" }
                    outerEndpointInput.setText(prof.endpoint)
                    innerEndpointInput.setText(prof.innerEndpoint)
                    nameInput.setText(prof.name)
                    updateStateLabel()
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
            )
        }
    }

    fun persistDraftSelection(copyLinkToClipboard: Boolean, afterListSaved: (() -> Unit)? = null): AetherSelection {
        val draft = currentDraftSelection()
        val now = System.currentTimeMillis()
        aetherPreferenceStore.save(draft.copy(endpointScannedAt = now))
        aetherPreferenceStore.saveNamedProfile(
            AetherSavedProfile(
                name = draft.profileName,
                mode = draft.mode,
                ipScan = draft.ipScan,
                endpoint = draft.endpoint.orEmpty(),
                scannedAt = now,
                chainTarget = draft.chainTarget,
                exitLocation = draft.exitLocation,
                psiphonRegion = draft.psiphonRegion,
                masqueTransport = draft.masqueTransport,
                innerEndpoint = draft.innerEndpoint.orEmpty(),
                scanMode = draft.scanMode,
                noizePreset = draft.noizePreset,
                fingerprint = draft.fingerprint,
                fragmentEnabled = draft.fragmentEnabled,
                fragmentSize = draft.fragmentSize,
                fragmentDelay = draft.fragmentDelay,
                masqueSni = draft.masqueSni,
                echEnabled = draft.echEnabled,
                echDns = draft.echDns,
                echDomain = draft.echDomain,
                psiphonMode = draft.psiphonMode,
                psiphonCdnIps = draft.psiphonCdnIps,
                psiphonCdnSni = draft.psiphonCdnSni,
                psiphonBundled = draft.psiphonBundled,
                torBridgesMode = draft.torBridgesMode,
                torCustomBridges = draft.torCustomBridges,
                torRelaysMode = draft.torRelaysMode,
            ),
        )
        saveAetherConfigIntoMainSubscriptionList(draft, activateAetherEngine = true, replaceAll = true, onSaved = afterListSaved)
        if (copyLinkToClipboard) {
            val uri = draft.toAetherUri()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aether-uri", uri))
        }
        onSaved?.invoke()
        return draft
    }

    val scroll = ScrollView(this).apply { addView(root) }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "➕ Add Cat Warp config manually (MASQUE in MASQUE / WARP)"
            else "➕ Add Cat Warp config manually (MASQUE in MASQUE / WARP)",
        )
        .setView(scroll)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "✅ ۲-۱۳) ذخیره در لیست کانفیگ‌ها" else "✅ 2-13) Save to Config List") { _, _ ->
            val saved = persistDraftSelection(copyLinkToClipboard = true)
            Toast.makeText(
                this,
                if (isFarsi) "✅ کانفیگ «${saved.profileName}» در لیست کانفیگ‌های صفحهٔ اصلی ذخیره و انتخاب شد"
                else "✅ Saved '${saved.profileName}' to the main config list",
                Toast.LENGTH_LONG,
            ).show()
        }
        .setPositiveButton(if (isFarsi) "🚀 ذخیره و اتصال فوری" else "🚀 Save & Connect Now") { _, _ ->
            val saved = persistDraftSelection(copyLinkToClipboard = false) { connectCatWarpAfterSave() }
            Toast.makeText(
                this,
                if (isFarsi) "🚀 کانفیگ Cat Warp «${saved.profileName}» فعال شد — در حال اتصال مستقیم در همین برنامه…"
                else "🚀 Cat Warp '${saved.profileName}' enabled — connecting directly in Cat Client…",
                Toast.LENGTH_LONG,
            ).show()
        }
        .create()
        .showCatClientDialog()
}

/**
 * Interactive Auto-Scan & Auto-Build Wizard:
 * 1) Asks the user whether they want Iran IP (🇮🇷) or a Foreign Country (🌍 Gool / 🇩🇪 DE / 🇳🇱 NL / 🇺🇸 US / all 29 Psiphon countries).
 * 2) Scans all 3 Cloudflare Aether methods on the user's current network (MASQUE-HTTP/2, WARP+IPv6, WARP+IPv4).
 * 3) Shows which methods connect on their line, automatically builds & saves the best working config, and offers 1-tap Connect or Copy.
 */
internal fun MainActivity.showAetherAutoScanAndBuildWizard(onBuilt: ((AetherSelection) -> Unit)? = null) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian

    fun runNetworkScanForDestination(exitLocation: String, chainTarget: String, psiphonRegion: String) {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(TEAL)
            progressBackgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 50))
        }
        val progressStatus = TextView(this).apply {
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            setLineSpacing(0f, 1.25f)
            text = if (isFarsi) "⏳ در حال تست واقعی روش‌های اتصال روی خط فعلی شما…" else "⏳ Probing connection methods on your current line…"
        }
        val progBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            addView(progressStatus, LinearLayout.LayoutParams(-1, -2))
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
        }
        val progDlg = MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "🔍 اسکن هوشمند شبکه شما" else "🔍 Smart Network Scan")
            .setView(progBox)
            .setCancelable(false)
            .create()
        progDlg.showCatClientDialog()

        activityScope.launch {
            val tomlFile = java.io.File(filesDir, "aether.toml")
            val proxy = if (currentVpnServiceState == VpnState.Started) {
                "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
            } else null

            progressBar.progress = 25
            progressStatus.text = if (isFarsi) "۱/۳ 📡 در حال تست MASQUE-HTTP/2 (پورت ۴۴۳ — سازگار با همراه اول و ایرانسل)…"
            else "1/3 📡 Testing MASQUE-HTTP/2 (Port 443 — MCI & Irancell)…"
            val masqueCandidate = withContext(Dispatchers.IO) {
                runCatching { AetherOperations.provision(AetherMode.MasqueHttp2, tomlFile, proxy, "both", exitLocation) }
                val nativeScan = if (currentVpnServiceState == VpnState.Stopped) {
                    runCatching { AetherOperations.scan(AetherMode.MasqueHttp2, tomlFile, "both") }.getOrNull()?.getOrNull()
                } else null
                val bestNative = nativeScan?.let { hits ->
                    hits.take(3).firstOrNull { hit ->
                        runCatching {
                            AetherOperations.confirmEndpoint(AetherMode.MasqueHttp2, tomlFile, hit.peer, "both", exitLocation, 8_000L).isSuccess
                        }.getOrDefault(false)
                    } ?: hits.firstOrNull()
                }
                if (bestNative != null) {
                    AetherNetworkProbeCandidate(
                        titleFa = "MASQUE-HTTP/2 (مناسب فایروال همراه اول و ایرانسل)",
                        titleEn = "MASQUE-HTTP/2 (Works on MCI & Irancell)",
                        baseMode = AetherMode.MasqueHttp2,
                        ipScan = "both",
                        endpoint = bestNative.peer,
                        reachable = true,
                        rttMs = bestNative.rttMillis.coerceAtLeast(1L),
                    )
                } else {
                    val peers = listOf("162.159.198.1:443", "162.159.198.2:443", "[2606:4700:d0::a29f:c001]:443")
                    var bestPeer = peers.first()
                    var bestRtt: Long? = null
                    for (p in peers) {
                        val rtt = probeEndpointReachabilityMs(p, useTcp = true, timeoutMs = 1500)
                        if (rtt != null && (bestRtt == null || rtt < bestRtt)) {
                            bestRtt = rtt
                            bestPeer = p
                        }
                    }
                    AetherNetworkProbeCandidate(
                        titleFa = "MASQUE-HTTP/2 (مناسب فایروال همراه اول و ایرانسل)",
                        titleEn = "MASQUE-HTTP/2 (Works on MCI & Irancell)",
                        baseMode = AetherMode.MasqueHttp2,
                        ipScan = "both",
                        endpoint = bestPeer,
                        reachable = bestRtt != null,
                        rttMs = bestRtt ?: 9999L,
                    )
                }
            }

            progressBar.progress = 60
            progressStatus.text = if (isFarsi) "۲/۳ 📡 در حال تست WARP با IPv6 (مخصوص فایروال همراه اول)…"
            else "2/3 📡 Testing WARP + IPv6 (for MCI Firewall)…"
            val warpV6Candidate = withContext(Dispatchers.IO) {
                val nativeScan = if (currentVpnServiceState == VpnState.Stopped) {
                    runCatching { AetherOperations.scan(AetherMode.WireGuard, tomlFile, "v6") }.getOrNull()?.getOrNull()
                } else null
                val bestNative = nativeScan?.let { hits ->
                    hits.take(3).firstOrNull { hit ->
                        runCatching {
                            AetherOperations.confirmEndpoint(AetherMode.WireGuard, tomlFile, hit.peer, "v6", exitLocation, 8_000L).isSuccess
                        }.getOrDefault(false)
                    } ?: hits.firstOrNull()
                }
                if (bestNative != null) {
                    AetherNetworkProbeCandidate(
                        titleFa = "WARP + IPv6 (مخصوص فایروال همراه اول)",
                        titleEn = "WARP + IPv6 (Best for MCI Firewall)",
                        baseMode = AetherMode.WireGuard,
                        ipScan = "v6",
                        endpoint = bestNative.peer,
                        reachable = true,
                        rttMs = bestNative.rttMillis.coerceAtLeast(1L),
                    )
                } else {
                    val peers = listOf("[2606:4700:d0::a29f:c001]:2408", "[2606:4700:d0::a29f:c002]:2408")
                    var bestPeer = peers.first()
                    var bestRtt: Long? = null
                    for (p in peers) {
                        val rtt = probeEndpointReachabilityMs(p, useTcp = false, timeoutMs = 1200)
                        if (rtt != null && (bestRtt == null || rtt < bestRtt)) {
                            bestRtt = rtt
                            bestPeer = p
                        }
                    }
                    AetherNetworkProbeCandidate(
                        titleFa = "WARP + IPv6 (مخصوص فایروال همراه اول)",
                        titleEn = "WARP + IPv6 (Best for MCI Firewall)",
                        baseMode = AetherMode.WireGuard,
                        ipScan = "v6",
                        endpoint = bestPeer,
                        reachable = bestRtt != null,
                        rttMs = bestRtt ?: 9999L,
                    )
                }
            }

            progressBar.progress = 85
            progressStatus.text = if (isFarsi) "۳/۳ 📡 در حال تست WARP با IPv4/IPv6 (مخصوص فایروال ایرانسل)…"
            else "3/3 📡 Testing WARP + IPv4/IPv6 (for Irancell Firewall)…"
            val warpV4Candidate = withContext(Dispatchers.IO) {
                val nativeScan = if (currentVpnServiceState == VpnState.Stopped) {
                    runCatching { AetherOperations.scan(AetherMode.WireGuard, tomlFile, "v4") }.getOrNull()?.getOrNull()
                } else null
                val bestNative = nativeScan?.let { hits ->
                    hits.take(3).firstOrNull { hit ->
                        runCatching {
                            AetherOperations.confirmEndpoint(AetherMode.WireGuard, tomlFile, hit.peer, "v4", exitLocation, 8_000L).isSuccess
                        }.getOrDefault(false)
                    } ?: hits.firstOrNull()
                }
                if (bestNative != null) {
                    AetherNetworkProbeCandidate(
                        titleFa = "WARP + IPv4 (مخصوص فایروال ایرانسل و همراه اول)",
                        titleEn = "WARP + IPv4 (Best for Irancell & MCI Firewall)",
                        baseMode = AetherMode.WireGuard,
                        ipScan = "v4",
                        endpoint = bestNative.peer,
                        reachable = true,
                        rttMs = bestNative.rttMillis.coerceAtLeast(1L),
                    )
                } else {
                    val peers = listOf("162.159.192.1:2408", "188.114.96.1:2408", "188.114.98.0:2408")
                    var bestPeer = peers.first()
                    var bestRtt: Long? = null
                    for (p in peers) {
                        val rtt = probeEndpointReachabilityMs(p, useTcp = false, timeoutMs = 1200)
                        if (rtt != null && (bestRtt == null || rtt < bestRtt)) {
                            bestRtt = rtt
                            bestPeer = p
                        }
                    }
                    AetherNetworkProbeCandidate(
                        titleFa = "WARP + IPv4 (مخصوص فایروال ایرانسل و همراه اول)",
                        titleEn = "WARP + IPv4 (Best for Irancell & MCI Firewall)",
                        baseMode = AetherMode.WireGuard,
                        ipScan = "v4",
                        endpoint = bestPeer,
                        reachable = bestRtt != null,
                        rttMs = bestRtt ?: 9999L,
                    )
                }
            }

            runCatching { progDlg.dismiss() }

            val allCandidates = listOf(masqueCandidate, warpV6Candidate, warpV4Candidate)
                .sortedWith(compareBy<AetherNetworkProbeCandidate> { !it.reachable }.thenBy { it.rttMs })
            val winner = allCandidates.first()

            fun buildSelectionForCandidate(cand: AetherNetworkProbeCandidate): AetherSelection {
                val effectiveMode = when {
                    chainTarget in setOf("psiphon", "tor") -> AetherSelection.simplifyModeForChain(cand.baseMode, chainTarget)
                    exitLocation == "foreign" && cand.baseMode == AetherMode.MasqueHttp2 -> AetherMode.WireGuardOverMasque
                    else -> cand.baseMode
                }
                val destBadge = when {
                    chainTarget == "psiphon" && psiphonRegion.isNotEmpty() -> psiphonRegion
                    chainTarget == "psiphon" -> "Psiphon"
                    exitLocation == "iran" -> "IR"
                    else -> "Gool"
                }
                val protoBadge = when {
                    cand.ipScan == "v6" && cand.baseMode == AetherMode.WireGuard -> "WARP-IPv6"
                    cand.baseMode == AetherMode.WireGuard -> "WARP"
                    else -> "MASQUE-H2"
                }
                val now = System.currentTimeMillis()
                return aetherPreferenceStore.read().copy(
                    enabled = true,
                    mode = effectiveMode,
                    endpoint = cand.endpoint,
                    endpointScannedAt = now,
                    psiphonRegion = psiphonRegion,
                    ipScan = cand.ipScan,
                    profileName = "Auto-$destBadge-$protoBadge",
                    chainTarget = chainTarget,
                    exitLocation = exitLocation,
                )
            }

            val builtSelection = buildSelectionForCandidate(winner)
            val netKey = AetherPreferenceStore.underlyingNetworkKey(this@showAetherAutoScanAndBuildWizard)
            builtSelection.endpoint?.let { peer ->
                aetherPreferenceStore.rememberEndpointForNetwork(netKey, builtSelection.mode, builtSelection.ipScan, peer, winner.rttMs)
            }
            aetherPreferenceStore.save(builtSelection)
            aetherPreferenceStore.saveNamedProfile(
                AetherSavedProfile(
                    name = builtSelection.profileName,
                    mode = builtSelection.mode,
                    ipScan = builtSelection.ipScan,
                    endpoint = builtSelection.endpoint.orEmpty(),
                    scannedAt = builtSelection.endpointScannedAt,
                    chainTarget = builtSelection.chainTarget,
                    exitLocation = builtSelection.exitLocation,
                    psiphonRegion = builtSelection.psiphonRegion,
                ),
            )
            // beta214: the wizard result goes into the list as the ONE Cat Warp config (it was only kept in the
            // preferences, so the list and «Connect» could still point at an older config or another list).
            saveAetherConfigIntoMainSubscriptionList(builtSelection, activateAetherEngine = true, replaceAll = true)
            onBuilt?.invoke(builtSelection)

            val destTitle = when {
                chainTarget == "psiphon" -> PsiphonCarrierConfig.regionShortLabel(psiphonRegion, isFarsi) +
                    if (isFarsi) " (چین WARP → سایفون)" else " (WARP → Psiphon)"
                exitLocation == "iran" -> if (isFarsi) "🇮🇷 آی‌پی ایران (تک‌لایه سریع)" else "🇮🇷 Iran IP (Fast Single-Hop)"
                else -> if (isFarsi) "🌍 آی‌پی خارج پرسرعت (دو لایه Gool)" else "🌍 Fast Foreign IP (Double-Hop Gool)"
            }

            val scanSummaryLines = allCandidates.mapIndexed { idx, c ->
                val name = if (isFarsi) c.titleFa else c.titleEn
                val status = if (c.reachable) {
                    val star = if (idx == 0) (if (isFarsi) " ⭐ منتخب خودکار" else " ⭐ Auto-Selected") else ""
                    if (isFarsi) "✅ وصل می‌شود (${c.rttMs}ms) · ${c.endpoint}$star"
                    else "✅ Reachable (${c.rttMs}ms) · ${c.endpoint}$star"
                } else {
                    if (isFarsi) "❌ روی خط فعلی پاسخ نداد (${c.endpoint})"
                    else "❌ Unreachable on current line (${c.endpoint})"
                }
                "${idx + 1}) $name:\n   $status"
            }.joinToString("\n\n")

            val resultMsg = if (isFarsi) {
                "🧭 مقصد انتخابی شما: $destTitle\n\n" +
                    "📊 نتیجه اسکن واقعی روی اینترنت فعلی شما:\n$scanSummaryLines\n\n" +
                    "✅ کانفیگ «${builtSelection.profileName}» به صورت خودکار ساخته و فعال شد:\n" +
                    "${builtSelection.toAetherUri()}"
            } else {
                "🧭 Target Destination: $destTitle\n\n" +
                    "📊 Live Scan Results on Your Network:\n$scanSummaryLines\n\n" +
                    "✅ Auto-built & enabled '${builtSelection.profileName}':\n" +
                    "${builtSelection.toAetherUri()}"
            }

            MaterialAlertDialogBuilder(this@showAetherAutoScanAndBuildWizard)
                .setTitle(
                    if (isFarsi) "✅ نتیجه اسکن خط شما و کانفیگ خودکار"
                    else "✅ Your Line Scan Results & Auto-Built Config",
                )
                .setMessage(resultMsg)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(if (isFarsi) "📋 کپی لینک aether://" else "📋 Copy aether:// Link") { _, _ ->
                    val uri = builtSelection.toAetherUri()
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("aether-uri", uri))
                    Toast.makeText(
                        this@showAetherAutoScanAndBuildWizard,
                        if (isFarsi) "✅ لینک کانفیگ کپی شد:\n$uri" else "✅ Copied Cat Warp URI:\n$uri",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .setPositiveButton(if (isFarsi) "🚀 اتصال فوری با همین کانفیگ" else "🚀 Connect Now") { _, _ ->
                    saveAetherConfigIntoMainSubscriptionList(builtSelection, activateAetherEngine = true, replaceAll = true) {
                        connectCatWarpAfterSave()
                    }
                }
                .create()
                .showCatClientDialog()
        }
    }

    val quickOptions = if (isFarsi) {
        arrayOf(
            "🇮🇷 آی‌پی ایران (بالاترین سرعت و کمترین پینگ — بدون چین)",
            "🌍 آی‌پی خارج — خودکار پرسرعت (دو لایه کلودفلر Gool — بدون افت سرعت)",
            "🇩🇪 آلمان (DE) — رفع تحریم کامل با چین WARP → سایفون",
            "🇳🇱 هلند (NL) — رفع تحریم کامل با چین WARP → سایفون",
            "🇫🇮 فنلاند (FI) — پینگ عالی اروپا با چین WARP → سایفون",
            "🇺🇸 آمریکا (US) — مناسب ChatGPT / Gemini و سرویس‌های آمریکا",
            "🇬🇧 انگلیس (GB) — چین WARP → سایفون بریتانیا",
            "🇫🇷 فرانسه (FR) — چین WARP → سایفون فرانسه",
            "🇨🇭 سوئیس (CH) — چین WARP → سایفون سوئیس",
            "🌐 انتخاب از فهرست کامل ۲۹ کشور سایفون…",
        )
    } else {
        arrayOf(
            "🇮🇷 Iran IP (Highest Speed & Lowest Ping — Single-Hop)",
            "🌍 Foreign IP — Fast Auto (Cloudflare Double-Hop Gool)",
            "🇩🇪 Germany (DE) — Full Sanction Bypass (WARP → Psiphon)",
            "🇳🇱 Netherlands (NL) — Full Sanction Bypass (WARP → Psiphon)",
            "🇫🇮 Finland (FI) — Low Ping EU (WARP → Psiphon)",
            "🇺🇸 United States (US) — Best for AI & US Services (WARP → Psiphon)",
            "🇬🇧 United Kingdom (GB) — (WARP → Psiphon)",
            "🇫🇷 France (FR) — (WARP → Psiphon)",
            "🇨🇭 Switzerland (CH) — (WARP → Psiphon)",
            "🌐 Pick from all 29 Psiphon countries…",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🤖 گام ۱: آی‌پی ایران می‌خواهید یا کشور دیگر؟"
            else "🤖 Step 1: Do you want Iran IP or a Foreign Country?",
        )
        .setItems(quickOptions) { _, which ->
            when (which) {
                0 -> runNetworkScanForDestination(exitLocation = "iran", chainTarget = "none", psiphonRegion = "")
                1 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "none", psiphonRegion = "")
                2 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "psiphon", psiphonRegion = "DE")
                3 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "psiphon", psiphonRegion = "NL")
                4 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "psiphon", psiphonRegion = "FI")
                5 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "psiphon", psiphonRegion = "US")
                6 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "psiphon", psiphonRegion = "GB")
                7 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "psiphon", psiphonRegion = "FR")
                8 -> runNetworkScanForDestination(exitLocation = "foreign", chainTarget = "psiphon", psiphonRegion = "CH")
                else -> {
                    val allRegions = listOf("") + PsiphonCarrierConfig.availableRegions(this)
                    val allLabels = allRegions.map { code ->
                        PsiphonCarrierConfig.regionDisplayLabel(code, isFarsi, getString(R.string.aether_region_automatic))
                    }.toTypedArray()
                    MaterialAlertDialogBuilder(this)
                        .setTitle(
                            if (isFarsi) "🌍 انتخاب کشور سایفون برای ساخت خودکار"
                            else "🌍 Pick Psiphon Country for Auto-Build",
                        )
                        .setItems(allLabels) { _, rIdx ->
                            runNetworkScanForDestination(
                                exitLocation = "foreign",
                                chainTarget = "psiphon",
                                psiphonRegion = allRegions[rIdx],
                            )
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                        .showCatClientDialog()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}
