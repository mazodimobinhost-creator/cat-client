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

// Emergency & anti-censorship tab: engine cards and their dialogs (DNS, paqet, Outline, WG, CF).
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

internal fun MainActivity.buildAntiCensorshipEnginesCard(): View {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val card = advancedSettingsPanel()
    card.addView(
        TextView(this).apply {
            text = if (isFarsi) "🛡 اتصال اضطراری و ضدسانسور (سایفون · تور/dnstt · وارپ/گول)" else "🛡 Anti-Censorship & Emergency Engines (Psiphon · Tor/dnstt · WARP/Gool)"
            textSize = 15.5f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEAL)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    card.addView(
        TextView(this).apply {
            text = if (isFarsi) {
                "دسترسی یکجا به ۳ موتور ضدسانسور اپ: سایفون (Psiphon روی Masque)، شبکه تور (Orbot + پل اضطراری dnstt برای زمان قطعی کامل) و وارپ/گول (WireGuard-over-MASQUE)."
            } else {
                "Unified controls for the 3 built-in anti-censorship engines: Psiphon over Masque, Tor/Orbot + emergency dnstt DNS bridges, and WARP / Gool."
            }
            textSize = 12f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )

    val statusSummaryText = TextView(this).apply {
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(dp(2).toFloat(), 1f)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    card.addView(statusSummaryText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    val engineProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = false
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(OUTLINE, 180))
        visibility = View.GONE
    }
    val engineProgressLabel = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        visibility = View.GONE
    }
    card.addView(engineProgressLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    card.addView(engineProgressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(4) })

    fun updateEngineProgress(percent: Int, label: String, visible: Boolean = true) {
        val clamped = percent.coerceIn(0, 100)
        engineProgressBar.visibility = if (visible) View.VISIBLE else View.GONE
        engineProgressBar.progress = clamped
        engineProgressLabel.visibility = if (visible) View.VISIBLE else View.GONE
        engineProgressLabel.text = "$label ($clamped%)"
    }

    fun modeLabelOf(mode: AetherMode): String = when (mode) {
        AetherMode.MasqueInMasque -> "MASQUE in MASQUE (HTTP/2)"
        AetherMode.WarpInWarp -> "WARP in WARP (Gool)"
        AetherMode.MasqueHttp2 -> getString(R.string.aether_mode_h2)
        AetherMode.WireGuard -> if (isFarsi) "WARP · WireGuard" else "WARP · WireGuard"
        AetherMode.WireGuardOverMasque -> getString(R.string.aether_mode_wom)
        AetherMode.MasquePsiphon -> getString(R.string.aether_mode_psiphon)
    }

    fun refreshEngineSummary() {
        val aetherSel = aetherPreferenceStore.read()
        val hasKey = java.io.File(filesDir, "aether.toml").exists() && java.io.File(filesDir, "aether.toml").length() > 0L
        val psiphonActive = aetherSel.enabled && (aetherSel.mode == AetherMode.MasquePsiphon || aetherSel.chainTarget == "psiphon")
        val torChainActive = aetherSel.enabled && aetherSel.chainTarget == "tor"
        val psiphonRegion = aetherSel.psiphonRegion.ifBlank { if (isFarsi) "اتوماتیک (سریع‌ترین)" else "Auto" }
        val orbotEnabled = orbotEnginePreferenceStore.isEnabled() || torChainActive
        val namePrefix = aetherSel.profileName.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
        val chainSuffix = when (aetherSel.chainTarget) {
            "psiphon" -> " → Psiphon"
            "tor" -> " → Tor"
            else -> ""
        }
        val endpointLabel = aetherSel.endpoint?.let { "$namePrefix$it (${aetherSel.ipScan.uppercase(Locale.US)}$chainSuffix)" }
            ?: if (isFarsi) "خودکار (${aetherSel.ipScan.uppercase(Locale.US)}$chainSuffix)" else "Auto (${aetherSel.ipScan.uppercase(Locale.US)}$chainSuffix)"
        val exitLocSummary = when {
            aetherSel.chainTarget == "psiphon" ->
                if (isFarsi) "🌍 آی‌پی خارج تضمینی (WARP → سایفون · کشور: $psiphonRegion)"
                else "🌍 Foreign IP (WARP → Psiphon · $psiphonRegion)"
            aetherSel.chainTarget == "tor" ->
                if (isFarsi) "🌍 آی‌پی خارج تضمینی (WARP → تور)"
                else "🌍 Foreign IP (WARP → Tor)"
            aetherSel.effectiveExitLocation() == "foreign" ->
                if (isFarsi) {
                    val goolKind = if (aetherSel.mode == AetherMode.WireGuard) "WARP-in-WARP" else "WG-over-MASQUE"
                    "🌍 آی‌پی خارج (دو لایه کلودفلر Gool · $goolKind)"
                } else {
                    val goolKind = if (aetherSel.mode == AetherMode.WireGuard) "WARP-in-WARP" else "WG-over-MASQUE"
                    "🌍 Foreign IP (Double-Hop Gool · $goolKind)"
                }
            else ->
                if (isFarsi) "🇮🇷 آی‌پی ایران (تک‌لایه مستقیم · بالاترین سرعت)"
                else "🇮🇷 Iran IP (Single-Hop · Fastest)"
        }
        statusSummaryText.text = if (isFarsi) {
            buildString {
                append("🧭 مکان آی‌پی خروجی Cat Warp: $exitLocSummary\n")
                append("🌀 سایفون: ")
                append(if (psiphonActive) "فعال (WARP → Psiphon) ✅" else "آماده (حالت فعلی: ${modeLabelOf(aetherSel.mode)})")
                append(" · کشور: $psiphonRegion\n")
                append("🧅 تور (Orbot): ")
                append(if (torChainActive) "چین فعال (WARP → Tor) ✅" else if (orbotEnabled) "روشن روی 127.0.0.1:9050 ✅" else "خاموش")
                append(" · پل اضطراری dnstt: ۳ پل رسمی آماده\n")
                append("⚡ وارپ/MASQUE: ")
                append(if (hasKey) "کلید آماده ✅" else "نیاز به دریافت کلید")
                append(" · اندپوینت: $endpointLabel")
            }
        } else {
            buildString {
                append("🧭 Cat Warp Exit IP: $exitLocSummary\n")
                append("🌀 Psiphon: ")
                append(if (psiphonActive) "Active (WARP → Psiphon) ✅" else "Ready (Current: ${modeLabelOf(aetherSel.mode)})")
                append(" · Region: $psiphonRegion\n")
                append("🧅 Tor (Orbot): ")
                append(if (torChainActive) "Chained (WARP → Tor) ✅" else if (orbotEnabled) "Enabled on 127.0.0.1:9050 ✅" else "Off")
                append(" · Emergency dnstt: 3 official bridges ready\n")
                append("⚡ WARP/MASQUE: ")
                append(if (hasKey) "Key provisioned ✅" else "No key yet")
                append(" · Endpoint: $endpointLabel")
            }
        }
    }
    refreshEngineSummary()

    fun smallActionBtn(label: String, accent: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(12)
            setPadding(dp(12), 0, dp(12), 0)
            if (accent) {
                backgroundTintList = ColorStateList.valueOf(TEAL)
                setTextColor(BACKGROUND)
            } else {
                backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 215))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 200))
                setTextColor(TEXT_PRIMARY)
            }
            setOnClickListener { onClick() }
        }

    // Primary Row (Focal CTA): 1-Tap Direct Connect + Auto-Scan & Build Wizard
    val primaryCtaRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "🚀 اتصال مستقیم Cat Warp" else "🚀 Connect Cat Warp Now",
                accent = true,
            ) {
                val cur = aetherPreferenceStore.read()
                val peer = cur.endpoint ?: when {
                    cur.ipScan == "v6" && cur.mode == AetherMode.WireGuard -> "[2606:4700:d0::a29f:c001]:2408"
                    cur.ipScan == "v6" -> "[2606:4700:d0::a29f:c001]:443"
                    cur.mode == AetherMode.WireGuard -> "162.159.192.1:2408"
                    else -> "162.159.198.1:443"
                }
                val ready = cur.copy(
                    enabled = true,
                    endpoint = peer,
                    endpointScannedAt = cur.endpointScannedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                )
                aetherPreferenceStore.save(ready)
                refreshEngineSummary()
                renderAdvancedControls()
                // beta214: write this exact config as the one row of «⚡ Cat Warp Configs» and select that list
                // before connecting, so another selected list (or an old picked row) can no longer win.
                saveAetherConfigIntoMainSubscriptionList(ready, activateAetherEngine = true, replaceAll = true) {
                    connectCatWarpAfterSave()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "🤖 اسکن و ساخت خودکار" else "🤖 Auto-Scan & Build",
                accent = true,
            ) {
                showAetherAutoScanAndBuildWizard {
                    refreshEngineSummary()
                    renderAdvancedControls()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    card.addView(primaryCtaRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    // Row 2: Aether Builder (WARP / MASQUE / Chain) + Exit IP Location (Iran 🇮🇷 vs Foreign 🌍)
    val coreConfigRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "➕ افزودن و تنظیم Cat Warp" else "➕ Build & Configure Cat Warp",
                accent = false,
            ) {
                showAddAetherDialog {
                    refreshEngineSummary()
                    renderAdvancedControls()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "🇮🇷/🌍 آی‌پی: ایران یا خارج" else "🇮🇷/🌍 Exit IP: Iran / Foreign",
                accent = false,
            ) {
                showAetherExitLocationPickerDialog {
                    refreshEngineSummary()
                    renderAdvancedControls()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    card.addView(coreConfigRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // beta214: the endpoint scan was only reachable through «ویرایش» of the subscription; now it has its own button.
    card.addView(
        smallActionBtn(
            label = if (isFarsi) "🔍 اسکن اندپوینت و ویرایش کانفیگ Cat Warp" else "🔍 Endpoint scan & edit Cat Warp config",
            accent = false,
        ) {
            openCatWarpEditorForCurrentConfig(autoScan = true)
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Row 3: WhiteDNS & Resolver Connection Methods + Smart Tools Menu
    val dnsAndToolsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "📡 متدهای DNS و ریزولور" else "📡 DNS & Resolver Methods",
                accent = true,
            ) {
                showWhiteDnsResolverMethodsDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "🧰 ابزارهای کمکی ▾" else "🧰 Smart Tools ▾",
                accent = false,
            ) {
                showWhiteDnsAndAetherToolsDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    card.addView(dnsAndToolsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Dedicated SOCKS5 Hub & Regional Firewall Auto-Detect row
    val socks5AndFwRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "🧦 بخش اختصاصی ساکس۵" else "🧦 SOCKS5 Live Hub",
                accent = true,
            ) {
                showSocks5HubPage()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "🧭 تشخیص فایروال منطقه" else "🧭 Detect Firewall",
                accent = false,
            ) {
                runRegionalFirewallFingerprinterDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    card.addView(socks5AndFwRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Collapsible secondary controls container (Progressive Disclosure: keeps main view clean)
    val quickControlsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        visibility = View.GONE
    }

    // Quick Row A: Psiphon controls (Chain WARP -> Psiphon + 29 Countries)
    val psiphonRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "🌀 چین WARP → سایفون" else "🌀 Chain WARP → Psiphon",
                accent = false,
            ) {
                updateEngineProgress(25, if (isFarsi) "در حال آماده‌سازی چین WARP → سایفون (با پروتکل ساده)…" else "Provisioning WARP → Psiphon chain (simple carrier)…")
                val cur = aetherPreferenceStore.read()
                val simpleCarrier = AetherSelection.simplifyModeForChain(cur.mode, "psiphon")
                aetherPreferenceStore.save(cur.copy(enabled = true, mode = simpleCarrier, chainTarget = "psiphon"))
                activityScope.launch {
                    val tomlFile = java.io.File(filesDir, "aether.toml")
                    if (!tomlFile.exists() || tomlFile.length() == 0L) {
                        updateEngineProgress(55, if (isFarsi) "در حال دریافت کلید رایگان برای سایفون…" else "Provisioning free key for Psiphon…")
                        val proxy = if (currentVpnServiceState == VpnState.Started) {
                            "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
                        } else null
                        withContext(Dispatchers.IO) {
                            runCatching { AetherOperations.provision(simpleCarrier, tomlFile, proxy, cur.ipScan) }
                        }
                    }
                    updateEngineProgress(100, if (isFarsi) "✅ چین WARP → سایفون (${simpleCarrier.wireName}) فعال شد" else "✅ WARP → Psiphon (${simpleCarrier.wireName}) enabled")
                    refreshEngineSummary()
                    renderAdvancedControls()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "🌍 کشور سایفون (۲۹ کشور)" else "🌍 Psiphon Country (29)",
                accent = false,
            ) {
                val cur = aetherPreferenceStore.read()
                val regions = listOf("") + PsiphonCarrierConfig.availableRegions(this@buildAntiCensorshipEnginesCard)
                val labels = regions.map { code ->
                    PsiphonCarrierConfig.regionDisplayLabel(code, isFarsi, getString(R.string.aether_region_automatic))
                }.toTypedArray()
                val currentIdx = regions.indexOf(cur.psiphonRegion).coerceAtLeast(0)
                MaterialAlertDialogBuilder(this@buildAntiCensorshipEnginesCard)
                    .setTitle(
                        if (isFarsi) "🌍 انتخاب کشور سایفون (۲۹ کشور فعال)"
                        else "🌍 Select Psiphon Country (29 Active Regions)",
                    )
                    .setSingleChoiceItems(labels, currentIdx) { dlg, index ->
                        dlg.dismiss()
                        val picked = regions[index]
                        val current = aetherPreferenceStore.read()
                        val updated = current.copy(
                            enabled = true,
                            psiphonRegion = picked,
                            chainTarget = "psiphon",
                            exitLocation = "foreign",
                            mode = AetherSelection.simplifyModeForChain(current.mode, "psiphon"),
                        )
                        aetherPreferenceStore.save(updated)
                        aetherPreferenceStore.saveExitChoice("foreign", "psiphon", picked)
                        refreshEngineSummary()
                        renderAdvancedControls()
                        val countryLabel = PsiphonCarrierConfig.regionShortLabel(picked, isFarsi)
                        val hint = updated.smartRecommendation("region")
                        Toast.makeText(
                            this@buildAntiCensorshipEnginesCard,
                            if (isFarsi) "✅ کشور سایفون روی «$countryLabel» تنظیم شد و چین WARP → سایفون فعال گردید.\n${hint.titleFa}"
                            else "✅ Psiphon country set to '$countryLabel' (WARP → Psiphon enabled).\n${hint.titleEn}",
                            Toast.LENGTH_LONG,
                        ).show()
                        if (currentVpnServiceState == VpnState.Started) {
                            reconnectForConnectionOptionChange()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    quickControlsHost.addView(psiphonRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Quick Row B: Tor / Orbot / dnstt emergency bridges
    val torRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "🧅 روشن/خاموش Tor" else "🧅 Toggle Tor/Orbot",
                accent = false,
            ) {
                val next = !orbotEnginePreferenceStore.isEnabled()
                orbotEnginePreferenceStore.setEnabled(next)
                renderOrbotRow()
                refreshEngineSummary()
                reconnectForConnectionOptionChange()
                activityScope.launch {
                    val host = orbotEnginePreferenceStore.readHost()
                    val port = orbotEnginePreferenceStore.readPort()
                    val st = withContext(Dispatchers.IO) { OrbotProbe.probe(host, port) }
                    lastOrbotProbe = st
                    renderOrbotRow()
                    val msg = if (st.reachable) {
                        if (isFarsi) "✅ پورت $port (Orbot) فعال و متصل است" else "✅ Orbot SOCKS5 $host:$port reachable"
                    } else {
                        if (isFarsi) "⚠️ حالت Tor تغییر کرد؛ برای اتصال، اپ Orbot را روشن کنید (پورت $port)" else "⚠️ Tor mode updated; start Orbot on port $port"
                    }
                    Toast.makeText(this@buildAntiCensorshipEnginesCard, msg, Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "📋 کپی پل اضطراری dnstt" else "📋 Copy dnstt Bridges",
                accent = false,
            ) {
                val dnsttLines = listOf(
                    "dnstt 192.0.2.4:1 A998F319ADB60EE344540EC4B21524CC484F96BE doh=https://dns.google/dns-query pubkey=241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f domain=t.ruhnama.net",
                    "dnstt 192.0.2.4:2 80EEFA4F4875ED2B7B5A86DF2D7588AD32E29F15 doh=https://dns.google/dns-query pubkey=a2fb71077eeaa54a02cda7a90be306af5d299ab21822a8b277d4eacbc9168631 domain=t2.bypasscensorship.org",
                    "dnstt 192.0.2.4:3 74D409BED3E2F881F365543A72C8F079CB84FFEB doh=https://dns.google/dns-query pubkey=c596c458fc3453dc40903ab235f5854a2609831075640c4c5584f76de05b8271 domain=t.arkadag.org",
                ).joinToString("\n")
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tor-dnstt-bridges", dnsttLines))
                MaterialAlertDialogBuilder(this@buildAntiCensorshipEnginesCard)
                    .setTitle(if (isFarsi) "🧅 پل‌های اضطراری dnstt تور کپی شد" else "🧅 Tor Emergency dnstt Bridges Copied")
                    .setMessage(
                        if (isFarsi) {
                            "۳ خط پل رسمی dnstt در کلیپ‌بورد کپی شد.\n\nراهنمای استفاده در زمان قطعی شدید:\n۱) در اپ Orbot به بخش Set Transport → Custom Bridges بروید.\n۲) خطوط کپی‌شده را Paste کنید و Connect را بزنید.\n۳) سپس دکمه «🧅 روشن/خاموش Tor» را در همین کارت روشن کنید.\n\n(اگر dns.google روی خط شما بسته بود، مقدار doh= را با dot=1.1.1.1:853 یا udp=8.8.8.8:53 جایگزین کنید.)"
                        } else {
                            "3 official Tor dnstt bridge lines copied to clipboard.\n\n1) In Orbot open Set Transport → Custom Bridges.\n2) Paste the lines and tap Connect.\n3) Enable 'Toggle Tor/Orbot' here to route through 127.0.0.1:9050."
                        },
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    quickControlsHost.addView(torRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Quick Row C: Firewall Guide + Live Exit IP Probe
    val fwAndProbeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "🛡 فایروال همراه اول / ایرانسل" else "🛡 MCI & Irancell Firewall",
                accent = false,
            ) {
                showFirewallConnectionGuideDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "🌐 تست آی‌پی و کشور خروجی" else "🌐 Check Exit IP & Country",
                accent = false,
            ) {
                probeAndShowAetherExitIpDialog {
                    refreshEngineSummary()
                    renderAdvancedControls()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    quickControlsHost.addView(fwAndProbeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Quick Row D: Copy aether:// URI + Switch to Normal Subscription (clean 2-button layout)
    val uriAndModeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "📋 کپی لینک aether://" else "📋 Copy aether://",
                accent = false,
            ) {
                val uri = aetherPreferenceStore.read().toAetherUri()
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("aether-uri", uri))
                Toast.makeText(
                    this@buildAntiCensorshipEnginesCard,
                    if (isFarsi) "✅ لینک کانفیگ کپی شد:\n$uri" else "✅ Copied Cat Warp URI:\n$uri",
                    Toast.LENGTH_LONG,
                ).show()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "🔄 بازگشت به ساب عادی" else "🔄 Normal Subscription",
                accent = false,
            ) {
                val cur = aetherPreferenceStore.read()
                aetherPreferenceStore.save(cur.copy(enabled = false))
                refreshEngineSummary()
                renderAdvancedControls()
                Toast.makeText(
                    this@buildAntiCensorshipEnginesCard,
                    if (isFarsi) "🔄 حالت مستقیم Cat Warp خاموش شد — دکمه اتصال اکنون به کانفیگ‌های ساب/پنل شما وصل می‌شود"
                    else "🔄 Direct Cat Warp disabled — main Connect button now uses your Subscription configs",
                    Toast.LENGTH_LONG,
                ).show()
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    quickControlsHost.addView(uriAndModeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val toggleQuickControlsBtn = smallActionBtn(
        label = if (isFarsi) "⚡ گزینه‌های سریع (کشور سایفون · تور · تست آی‌پی · کپی لینک) ▾"
        else "⚡ Quick Options (Psiphon Country · Tor · IP Check · Copy URI) ▾",
        accent = false,
    ) {
        val nextVisible = quickControlsHost.visibility != View.VISIBLE
        quickControlsHost.visibility = if (nextVisible) View.VISIBLE else View.GONE
    }
    card.addView(toggleQuickControlsBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    card.addView(quickControlsHost, LinearLayout.LayoutParams(-1, -2))

    // Direct 2026 Anti-Censorship Labs Row: Non-CF Net Melli + 5 CF Spoofs & Multi-Engine DNS Tunnels
    val lab2026Row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            smallActionBtn(
                label = if (isFarsi) "🧪 نت ملی ۲۰۲۶ (وایت‌لیست + ۵ اسپوف CF)"
                else "🧪 Net Melli 2026 (Whitelist + 5 CF Spoofs)",
                accent = true,
            ) {
                showNetMelli2026BypassLabDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            smallActionBtn(
                label = if (isFarsi) "📡 تانل DNS (Cat DNS · MasterDNS)"
                else "📡 DNS Tunnels (Cat DNS · MasterDNS)",
                accent = false,
            ) {
                showWhiteDnsResolverMethodsDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    card.addView(lab2026Row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    refreshAetherEngineSummary = { refreshEngineSummary() }

    return card
}

/**
 * Conceptual menu exposing all WhiteDNS, SOCKS5 Hub & WhiteAestherMobile power tools
 * without cluttering the main UI.
 */
internal fun MainActivity.showWhiteDnsAndAetherToolsDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val items = if (isFarsi) {
        arrayOf(
            "📖 ۰) راهنمای خیلی ساده به زبان خودمانی (اینو بزنی این میشه، اونو بزنی این میشه!)",
            "🦊 ۱) Cat Fox VPN (۵۰ گیگ رایگان ماهانه روی سرورهای موزیلا)",
            "🪄 ۲) تعمیر خودکار کانفیگ واقعی من و اتصال فوری (۵ روش اسپوف کلادفلر + برش SNI)",
            "🧦 ۳) بخش اختصاصی ساکس۵ — لیست زنده ۷ مخزن + آپدیت خودکار + اتصال یک‌کلیکی و استخر هوشمند",
            "🧭 ۴) تشخیص خودکار نوع فایروال واقعی منطقه (همراه اول IPv6 یا ایرانسل F&F)",
            "🩺 ۵) تشخیص اختلال و قطع نیمه‌کاره صفحه (Mid-Stream Freeze Detector)",
            "🛡️ ۶) دامین فرانتینگ + پیدا کردن آدرس واقعی سایت‌های فیلترشده روی ایرانسل/همراه اول",
            "🌐 ۷) رله گوگل (Google Apps Script) — اتصال از اسکریپت گوگل خودتان + نمایش سهمیه روزانه",
            "🪶 ۸) ساخت ورکر سبک کلودفلر (Cat Client Lite Worker — سریع و بدون مصرف KV)",
            "🔌 ۹) تنظیمات پایداری: وصل دوباره در قطعی، وصل بعد از ری‌استارت و قطع‌کن اضطراری (Kill Switch)",
            "⚡ ۱۰) تشخیص خودکار MTU، رکورد حامل و بهترین ریزولور (Auto-MTU & Carrier Finder)",
            "📡 ۱۱) آزمایشگاه متدهای اتصال DNS و ریزولور Cat DNS (۵۲ سرور + ۶ پریست ایران + ۹ موتور)",
            "🌐 ۱۲) اسکن ۶ پورت TLS کلودفلر + بهترین SNI روی خط من (443/2053/2083/2087/2096/8443)",
            "🔍 ۱۳) اسکن سریع DNS ایران + ساخت پل اضطراری dnstt و تانل Cat DNS",
            "🧅 ۱۴) دریافت خودکار پل‌های جدید Tor از سرور رسمی (Moat)",
            "💾 ۱۵) کپی پشتیبان کلید کلودفلر (جلوگیری از سوختن کلید در نصب مجدد)",
            "📥 ۱۶) بازیابی کلید کلودفلر از کلیپ‌بورد",
            "✨ ۱۷) تست یک‌کلیکی باز بودن Gemini، AI Studio، ChatGPT و یوتیوب بدون تحریم",
            "🚨 ۱۸) تشخیص هوشمند «اینترنت ملی (قطع بین‌الملل)» در برابر «خرابی کانفیگ»",
            "🛡️ ۱۹) ۹ پریست ضد فیلترینگ (Max-Stealth 45ms / Double-Fragment / Starlink / F&F)",
            "🍎 ۲۰) مرکز جامع اتصال آیفون و iPad (CoreForge / Streisand / پروفایل iOS / هات‌اسپات)",
            "🧪 ۲۱) آزمایشگاه نت ملی ۲۰۲۶ (وایت‌لیست غیرکلادفلر + ۵ روش اسپوف کلادفلر + پروکسی Fake ClientHello + Fact-A + S3 Bypass)",
            "🎯 ۲۲) اسکن ۶ امتیازی Cat DNS (0..6 Score + Prism) و جنریتور MasterDnsVPN / VayDNS / NoizDNS / dnst://",
        )
    } else {
        arrayOf(
            "📖 0) Plain-Language Step-by-Step Guide (What Every Button Does!)",
            "🦊 1) Cat Fox VPN (50 GB/mo free on Mozilla servers)",
            "🪄 2) 1-Click Auto-Repair My Real Config & Connect Immediately",
            "🧦 3) Dedicated SOCKS5 Hub — 7 Live Feeds + Auto-Update + 1-Tap & Smart Pool Connect",
            "🧭 4) Regional Firewall Fingerprinter (Auto-Detect MCI IPv6 vs Irancell F&F)",
            "🩺 5) Mid-Stream Freeze & Throttling Detector (Half-Loaded Page Diagnosis)",
            "🛡️ 6) Domain Fronting + Unpoisoned Real IP Finder on Irancell/MCI",
            "🌐 7) Google Apps Script (GAS) Relay — Personal Script + Quota Tracker",
            "🪶 8) Cat Client Lite Worker Generator (Fast, Zero-KV Quota Cloudflare Worker)",
            "🔌 9) Resilience & Kill Switch Settings (Reconnect on Drop / Boot Connect / Kill Switch)",
            "⚡ 10) Auto-MTU & Carrier Record Finder (QName Probe + Best Resolver & Preset)",
            "📡 11) Cat DNS & Resolver Connection Lab (52 Servers + 6 Iran Presets + 9 Engines)",
            "🌐 12) Scan 6 Cloudflare TLS Ports + Best SNI on My Line (443/2053/2083/2087/2096/8443)",
            "🔍 13) Quick Iran DNS Scan + Build Emergency dnstt & Cat DNS Tunnel",
            "🧅 14) Fetch Fresh Official Tor Bridges Automatically (Moat API)",
            "💾 15) Backup Cloudflare Identity Key to Clipboard",
            "📥 16) Restore Cloudflare Identity Key from Clipboard",
            "✨ 17) 1-Click Gemini, AI Studio, ChatGPT & YouTube Unblock Tester",
            "🚨 18) Smart Net Melli (International Shutdown) vs. Dead Config Detector",
            "🛡️ 19) 9 Anti-Filtering Presets (Max-Stealth 45ms / Double-Fragment / Starlink / F&F)",
            "🍎 20) iPhone & iPad (iOS) Connection Hub (CoreForge / Streisand / .mobileconfig / Hotspot)",
            "🧪 21) Net Melli 2026 Bypass Lab (Non-CF Whitelist + 5 CF Spoof Methods + Local Fake ClientHello Proxy + Fact-A Oracle + S3 Bypass)",
            "🎯 22) Cat DNS 6-Point Score Scanner (0..6 + Prism) & MasterDnsVPN / VayDNS / NoizDNS / dnst:// Generator",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🧰 ابزارهای هوشمند Cat DNS، Cat Fox VPN، ساکس۵ و Cat Warp"
            else "🧰 Cat DNS, Cat Fox VPN, SOCKS5 & Cat Warp Smart Tools",
        )
        .setItems(items) { _, which ->
            when (which) {
                0 -> showPlainLanguageFullGuideDialog()
                1 -> showFirefoxVpnFenixHubDialog()
                2 -> runOneClickRepairMyRealConfigAndConnect()
                3 -> showSocks5HubPage()
                4 -> runRegionalFirewallFingerprinterDialog()
                5 -> runMidStreamFreezeDetectorDialog()
                6 -> showDomainFrontingAndUnpoisonResolverDialog()
                7 -> showGoogleAppsScriptRelayDialog()
                8 -> showLiteWorkerGeneratorDialog()
                9 -> showConnectionResilienceAndKillSwitchDialog()
                10 -> runWhiteDnsAutoMtuAndCarrierDialog()
                11 -> showWhiteDnsResolverMethodsDialog()
                12 -> runCloudflareSixPortsAndSniScanDialog()
                13 -> runIranDnsAndDnsttScanDialog()
                14 -> runFetchMoatTorBridgesDialog()
                15 -> exportAetherIdentityToClipboard()
                16 -> importAetherIdentityFromClipboard()
                17 -> runAiAndGeminiUnblockTesterDialog()
                18 -> runNetMelliDetectorDialog()
                19 -> showNineAntiFilterPresetsDialog()
                20 -> showIphoneIosConnectionHubDialog()
                21 -> showNetMelli2026BypassLabDialog()
                22 -> runSlipNetSixPointScoreDialog()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showIphoneIosConnectionHubDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val dep = PanelDeploymentStore(this).deployments().firstOrNull()
    val workerHost = dep?.workerUrl
        ?.removePrefix("https://")
        ?.removePrefix("http://")
        ?.substringBefore("/")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "panel.example.workers.dev"
    val userUuid = dep?.uuid?.takeIf { it.isNotBlank() } ?: "00000000-0000-4000-8000-000000000001"
    // beta205: without a deployed panel the bundle would carry a placeholder host + UUID — never hand those out.
    val hasRealPanel = dep != null && dep.uuid.isNotBlank() && workerHost != "panel.example.workers.dev"
    val bundle = WhiteDnsSmartScanner.buildIosConnectionBundle(
        workerHost = workerHost,
        uuid = userUuid,
        cleanIp = "188.114.98.0",
        port = 443,
        path = "/$userUuid",
        remark = "CatClient-iOS",
    )
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val options = if (isFarsi) {
        arrayOf(
            "📱 ۱) دریافت کلاینت رسمی آیفون CoreForge (ForgeCore) از whitedns.online (TestFlight)",
            "⚡ ۲) کپی کانفیگ VLESS + Fragment مخصوص آیفون (CoreForge / Streisand / V2Box / Shadowrocket)",
            "🔗 ۳) کپی لینک‌های ایمپورت یک‌کلیکی آیفون (Streisand / V2Box / Sing-box / Karing / Shadowrocket)",
            "🛡️ ۴) ساخت کانفیگ استاندارد WireGuard (.conf) برای اپ رسمی WireGuard در آیفون (MTU 1280)",
            "📲 ۵) ساخت پروفایل بومی iOS (.mobileconfig) — نصب در Safari آیفون بدون نیاز به اپ!",
            "📡 ۶) اشتراک‌گذاری اینترنت تانل‌شده این گوشی به آیفون از طریق هات‌اسپات / وای‌فای (پروکسی LAN + PAC)",
        )
    } else {
        arrayOf(
            "📱 1) Get Official iOS Client CoreForge (ForgeCore) from whitedns.online (TestFlight)",
            "⚡ 2) Copy iOS-Tuned VLESS + Fragment URI (CoreForge / Streisand / V2Box / Shadowrocket)",
            "🔗 3) Copy 1-Click iOS Deep-Links (Streisand / V2Box / Sing-box / Karing / Shadowrocket)",
            "🛡️ 4) Generate Standard WireGuard (.conf) for Official iOS WireGuard App (MTU 1280)",
            "📲 5) Generate Native Apple iOS Profile (.mobileconfig) — Safari Install Without Any App!",
            "📡 6) Share Tunnel from This Android to iPhone via Hotspot / Wi-Fi (LAN Proxy + PAC)",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🍎 مرکز جامع اتصال آیفون و iPad (iOS)"
            else "🍎 iPhone & iPad (iOS) Connection Hub (iOS)",
        )
        .setItems(options) { _, which ->
            if (which in 1..4 && !hasRealPanel) {
                Toast.makeText(
                    this,
                    if (isFarsi) "⚠️ اول پنل خودتان را بسازید یا وصل کنید (بخش پنل‌ها)؛ کانفیگ آیفون با آدرس و UUID همان پنل ساخته می‌شود."
                    else "⚠️ Deploy or connect your own panel first (Panels tab) — the iPhone config is built from its address and UUID.",
                    Toast.LENGTH_LONG,
                ).show()
                return@setItems
            }
            when (which) {
                0 -> {
                    val info = if (isFarsi) {
                        "🍎 کلاینت رسمی iOS در اکوسیستم whitedns.online اپلیکیشن «CoreForge (ForgeCore)» است که برای آیفون و آیپد طراحی شده و از طریق Apple TestFlight نصب می‌شود:\n\n" +
                            "۱) ابتدا اپ رسمی TestFlight را از App Store نصب کنید:\nhttps://apps.apple.com/app/testflight/id899247664\n\n" +
                            "۲) سپس لینک دعوت مستقیم CoreForge را باز کنید:\n${bundle.coreForgeTestFlightUrl}\n(لینک جایگزین: ${bundle.coreForgeBackupTestFlightUrl})\n\n" +
                            "۳) راهنمای رسمی و دریافت کانفیگ از ربات‌ها:\n• صفحه ابزار: ${bundle.coreForgeGuideUrl}\n• ربات زنجیره خروج: https://t.me/WhiteDnsChainbot\n• ربات اضطراری MasterDNS: https://t.me/MasterDnsManager_bot\n\n" +
                            "✅ پس از نصب CoreForge، کانفیگ گزینه (۲) یا لینک اشتراک پنل خود را با یک کلیک در آن ایمپورت کنید."
                    } else {
                        "🍎 The official iOS client in the whitedns.online ecosystem is CoreForge (ForgeCore) for iPhone & iPad via Apple TestFlight:\n\n" +
                            "1) Install Apple TestFlight from App Store:\nhttps://apps.apple.com/app/testflight/id899247664\n\n" +
                            "2) Open the CoreForge TestFlight invitation link:\n${bundle.coreForgeTestFlightUrl}\n(Backup invite: ${bundle.coreForgeBackupTestFlightUrl})\n\n" +
                            "3) Official guide & Telegram bots:\n• Tool page: ${bundle.coreForgeGuideUrl}\n• Exit Chain Bot: https://t.me/WhiteDnsChainbot\n• MasterDNS Emergency Bot: https://t.me/MasterDnsManager_bot"
                    }
                    cm.setPrimaryClip(ClipData.newPlainText("coreforge-ios-testflight", bundle.coreForgeTestFlightUrl))
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (isFarsi) "📱 نصب CoreForge روی آیفون و آیپد" else "📱 Install CoreForge on iPhone & iPad")
                        .setMessage(info)
                        .setPositiveButton(if (isFarsi) "باز کردن TestFlight" else "Open TestFlight") { _, _ ->
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bundle.coreForgeTestFlightUrl)))
                            }
                        }
                        .setNeutralButton(if (isFarsi) "اشتراک‌گذاری برای آیفون" else "Share to iPhone") { _, _ ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "$info\n\nVLESS Config:\n${bundle.vlessFragmentUri}")
                            }
                            startActivity(Intent.createChooser(shareIntent, "CoreForge iOS"))
                        }
                        .setNegativeButton(android.R.string.ok, null)
                        .create()
                        .showCatClientDialog()
                }
                1 -> {
                    cm.setPrimaryClip(ClipData.newPlainText("ios-vless-fragment", bundle.vlessFragmentUri))
                    val msg = if (isFarsi) {
                        "✅ کانفیگ VLESS + Fragment مخصوص آیفون در کلیپ‌بورد کپی شد!\n\n" +
                            "این کانفیگ با تنظیمات فرگمنت (10-30 بایت، تأخیر 15ms و Fingerprint=chrome) برای عبور از فایروال همراه اول و ایرانسل در کلاینت‌های آیفون (CoreForge، Streisand، V2Box، Sing-box و Shadowrocket) بهینه شده است:\n\n" +
                            bundle.vlessFragmentUri
                    } else {
                        "✅ iOS-tuned VLESS + Fragment URI copied to clipboard!\n\n${bundle.vlessFragmentUri}"
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (isFarsi) "⚡ کانفیگ آیفون کپی شد" else "⚡ iOS Config Copied")
                        .setMessage(msg)
                        .setPositiveButton(if (isFarsi) "ارسال به آیفون (Share)" else "Share to iPhone") { _, _ ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, bundle.vlessFragmentUri)
                            }
                            startActivity(Intent.createChooser(shareIntent, "iOS VLESS Config"))
                        }
                        .setNegativeButton(android.R.string.ok, null)
                        .create()
                        .showCatClientDialog()
                }
                2 -> {
                    val allLinks = buildString {
                        appendLine("=== CoreForge (iOS) ===")
                        appendLine(bundle.coreForgeTestFlightUrl)
                        appendLine()
                        appendLine("=== Streisand (1-Click Import) ===")
                        appendLine(bundle.streisandDeepLink)
                        appendLine()
                        appendLine("=== V2Box (1-Click Import) ===")
                        appendLine(bundle.v2boxDeepLink)
                        appendLine()
                        appendLine("=== Sing-box iOS (1-Click Import) ===")
                        appendLine(bundle.singBoxIosDeepLink)
                        appendLine()
                        appendLine("=== Karing iOS (1-Click Import) ===")
                        appendLine(bundle.karingDeepLink)
                        appendLine()
                        appendLine("=== Shadowrocket (1-Click Import) ===")
                        appendLine(bundle.shadowrocketDeepLink)
                        appendLine()
                        appendLine("=== Portal Web URL (Open in iPhone Safari) ===")
                        appendLine(bundle.portalUrl)
                    }
                    cm.setPrimaryClip(ClipData.newPlainText("ios-deeplinks", allLinks))
                    Toast.makeText(
                        this,
                        if (isFarsi) "✅ تمام دیپ‌لینک‌های یک‌کلیکی اپ‌های آیفون در کلیپ‌بورد کپی شد!"
                        else "✅ All 1-click iOS app deep-links copied to clipboard!",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                3 -> {
                    cm.setPrimaryClip(ClipData.newPlainText("ios-wireguard-conf", bundle.wireguardIosConf))
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (isFarsi) "🛡️ کانفیگ WireGuard (.conf) مخصوص آیفون" else "🛡️ iOS WireGuard (.conf)")
                        .setMessage(
                            (if (isFarsi) {
                                "✅ متن فایل `.conf` استاندارد WireGuard (با MTU=1280 مخصوص عبور پایدار در iOS) در کلیپ‌بورد کپی شد.\n" +
                                    "در اپ رسمی WireGuard روی آیفون گزینه «Create from clipboard» یا «Create from file or archive» را بزنید:\n\n"
                            } else {
                                "✅ Standard WireGuard `.conf` (with iOS-tuned MTU=1280) copied to clipboard.\n" +
                                    "In the official WireGuard iOS app, tap '+' -> 'Create from clipboard':\n\n"
                            }) + bundle.wireguardIosConf,
                        )
                        .setPositiveButton(if (isFarsi) "ارسال به آیفون" else "Share to iPhone") { _, _ ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, bundle.wireguardIosConf)
                            }
                            startActivity(Intent.createChooser(shareIntent, "CatClient-iOS-WireGuard.conf"))
                        }
                        .setNegativeButton(android.R.string.ok, null)
                        .create()
                        .showCatClientDialog()
                }
                4 -> {
                    val profileModes = if (isFarsi) {
                        arrayOf(
                            "🛡️ ۱) پروفایل DoH ضد فیلتر و ضد مسمومیت DNS (Cloudflare 1.1.1.1)",
                            "🇮🇷 ۲) پروفایل DoH ضد تحریم شکن (Shecan — باز کردن تحریم‌ها بدون VPN در آیفون)",
                            "🎮 ۳) پروفایل DoH بازی و ضد تحریم الکترو (Electro — پینگ پایین و رفع تحریم)",
                        )
                    } else {
                        arrayOf(
                            "🛡️ 1) Anti-Poisoning Clean DoH Profile (Cloudflare 1.1.1.1)",
                            "🇮🇷 2) Shecan Anti-Sanction DoH Profile (Bypass Sanctions Without VPN on iOS)",
                            "🎮 3) Electro Gaming & Anti-Sanction DoH Profile",
                        )
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(
                            if (isFarsi) "📲 انتخاب پروفایل بومی iOS (.mobileconfig)"
                            else "📲 Select Native iOS Profile (.mobileconfig)",
                        )
                        .setItems(profileModes) { _, modeIdx ->
                            val xml = when (modeIdx) {
                                1 -> WhiteDnsSmartScanner.buildAppleIosMobileConfigProfile(
                                    profileName = "CatClient iOS (Shecan Anti-Sanction)",
                                    dohUrl = "https://free.shecan.ir/dns-query",
                                    bootstrapIps = listOf("178.22.122.100", "185.51.200.2"),
                                )
                                2 -> WhiteDnsSmartScanner.buildAppleIosMobileConfigProfile(
                                    profileName = "CatClient iOS (Electro Gaming)",
                                    dohUrl = "https://dns.electrotm.org/dns-query",
                                    bootstrapIps = listOf("78.157.42.100", "78.157.42.101"),
                                )
                                else -> bundle.mobileConfigXml
                            }
                            cm.setPrimaryClip(ClipData.newPlainText("catclient-ios.mobileconfig", xml))
                            val portalMobileConfigUrl = "https://$workerHost/ios/dns.mobileconfig?mode=" +
                                when (modeIdx) {
                                    1 -> "shecan"
                                    2 -> "electro"
                                    else -> "clean"
                                }
                            val guide = if (isFarsi) {
                                "✅ پروفایل `.mobileconfig` اپل آماده و کپی شد!\n\n" +
                                    "روش ۱ (مستقیم از پنل در Safari آیفون):\n" +
                                    "لینک زیر را در مرورگر **Safari** آیفون باز کنید تا پنجره نصب پروفایل در Settings ظاهر شود:\n" +
                                    "$portalMobileConfigUrl\n\n" +
                                    "روش ۲ (ارسال فایل):\n" +
                                    "دکمه «اشتراک‌گذاری» را بزنید و آن را از طریق تلگرام یا AirDrop/فایل به آیفون بفرستید، سپس در آیفون به:\n" +
                                    "Settings -> Profile Downloaded -> Install\n" +
                                    "بروید تا DNS رمزنگاری‌شده در سطح کل سیستم‌عامل iOS فعال شود!"
                            } else {
                                "✅ Apple `.mobileconfig` profile generated & copied!\n\n" +
                                    "Method 1 (Direct in iPhone Safari):\nOpen this link in **Safari** on iPhone:\n$portalMobileConfigUrl\n\n" +
                                    "Method 2: Share the profile XML to your iPhone and install via Settings -> Profile Downloaded -> Install."
                            }
                            MaterialAlertDialogBuilder(this)
                                .setTitle(if (isFarsi) "📲 نصب پروفایل در آیفون" else "📲 Install Profile on iPhone")
                                .setMessage(guide)
                                .setPositiveButton(if (isFarsi) "اشتراک‌گذاری پروفایل" else "Share Profile") { _, _ ->
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "CatClient-iOS-DNS.mobileconfig")
                                        putExtra(Intent.EXTRA_TEXT, xml)
                                    }
                                    startActivity(Intent.createChooser(shareIntent, "CatClient-iOS-DNS.mobileconfig"))
                                }
                                .setNeutralButton(if (isFarsi) "کپی لینک مستقیم Safari" else "Copy Safari Link") { _, _ ->
                                    cm.setPrimaryClip(ClipData.newPlainText("ios-safari-mobileconfig", portalMobileConfigUrl))
                                    Toast.makeText(this, portalMobileConfigUrl, Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton(android.R.string.ok, null)
                                .create()
                                .showCatClientDialog()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                        .showCatClientDialog()
                }
                5 -> {
                    val lanIp = detectLocalLanIpv4Address()
                    val bridgePort = runCatching {
                        WhiteDnsSmartScanner.startLocalAntiDpiSocksBridge(
                            listenPort = 0,
                            useDoubleFragment = true,
                        )
                    }.getOrDefault(10808)
                    val pacScript = WhiteDnsSmartScanner.buildLanPacScriptForIos(
                        androidLanIp = lanIp,
                        httpProxyPort = bridgePort,
                        socksProxyPort = bridgePort,
                    )
                    val hotspotGuide = if (isFarsi) {
                        "📡 **اشتراک‌گذاری اینترنت تانل‌شده به آیفون (بدون نیاز به نصب اپ روی آیفون):**\n\n" +
                            "۱) هات‌اسپات این گوشی اندرویدی را روشن کنید (یا هر دو گوشی به یک مودم وای‌فای وصل باشند).\n" +
                            "۲) در آیفون به `Settings -> Wi-Fi` بروید و کنار نام وای‌فای روی علامت ⓘ بزنید.\n" +
                            "۳) در پایین صفحه وارد `Configure Proxy` شوید:\n" +
                            "   • در اپ‌های پشتیبان SOCKS5/Shadowrocket/Streisand:\n" +
                            "     Server: `$lanIp`  |  Port: `$bridgePort`\n" +
                            "   • یا از طریق لینک PAC پنل در حالت Automatic:\n" +
                            "     `https://$workerHost/ios/pac?ip=$lanIp&port=$bridgePort`\n\n" +
                            "✅ بریج محلی ضد فیلتر روی پورت `$bridgePort` فعال است و اسکریپت PAC در کلیپ‌بورد کپی شد."
                    } else {
                        "📡 **Share Tunnel to iPhone via Hotspot / Wi-Fi (Zero App Required on iPhone):**\n\n" +
                            "1) Turn on Wi-Fi Hotspot on this Android phone (or connect both to the same Wi-Fi).\n" +
                            "2) On iPhone, open `Settings -> Wi-Fi`, tap ⓘ next to the network.\n" +
                            "3) Scroll down to `Configure Proxy`:\n" +
                            "   • Server: `$lanIp`  |  Port: `$bridgePort`\n" +
                            "   • Or Automatic PAC URL:\n" +
                            "     `https://$workerHost/ios/pac?ip=$lanIp&port=$bridgePort`\n\n" +
                            "✅ Local Anti-DPI bridge is active on port `$bridgePort` and PAC script was copied to clipboard."
                    }
                    cm.setPrimaryClip(ClipData.newPlainText("ios-lan-pac", pacScript))
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (isFarsi) "📡 هات‌اسپات امن اندروید به آیفون" else "📡 Android-to-iPhone Hotspot Bridge")
                        .setMessage(hotspotGuide)
                        .setPositiveButton(if (isFarsi) "کپی آدرس و پورت ($lanIp:$bridgePort)" else "Copy $lanIp:$bridgePort") { _, _ ->
                            cm.setPrimaryClip(ClipData.newPlainText("ios-lan-endpoint", "$lanIp:$bridgePort"))
                            Toast.makeText(this, "$lanIp:$bridgePort", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.ok, null)
                        .create()
                        .showCatClientDialog()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

/**
 * Full WhiteDNS & Resolver Connection Suite:
 * - Multi-transport DNS scanning (UDP :53, TCP :53 RFC 1035, DoT :853) + Carrier Record verification (HTTPS/65, TXT, CNAME, AAAA)
 * - 18-Server Categorized Resolver Catalog (Shecan, Electro, Radar, 403, Begzar, DynX, TCI, Shatel, Cloudflare DoQ/DoH3, AdGuard DoQ)
 * - Iran Auto-Tune Presets (`iran-fast-low-mtu`, `iran-compact-fixed`, `iran-fixed-64-balanced`, `iran-mid-reliable`, `iran-download-heavy`, `tcp-survival`)
 * - Multi-Engine Tunnel Builder (`dnstt`, `stormdns://`, `cottendns://` CottenMax FEC, `soar://`)
 */
internal fun MainActivity.showWhiteDnsResolverMethodsDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    var selectedResolver = WhiteDnsSmartScanner.RESOLVER_CATALOG.first()
    var selectedTransport = "auto"
    var selectedRecordMode = WhiteDnsRecordMode.TXT_HTTPS
    var selectedPreset = WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.first()

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(18), dp(12), dp(18), dp(8))
    }

    val summaryBox = TextView(this).apply {
        textSize = 11.8f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        setLineSpacing(0f, 1.25f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(withAlpha(TEAL, if (palette.isDark) 24 else 14))
            setStroke(dp(1), withAlpha(TEAL, 110))
        }
    }

    val previewBox = TextView(this).apply {
        textSize = 10.8f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setTextIsSelectable(true)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(SURFACE, 220))
            setStroke(dp(1), withAlpha(OUTLINE, 180))
        }
    }

    fun currentBundle(): WhiteDnsTunnelBundle =
        WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
            resolverIp = selectedResolver.primaryIp,
            transport = selectedTransport,
            recordMode = selectedRecordMode,
            preset = selectedPreset,
        )

    fun refreshView() {
        val bundle = currentBundle()
        val resolverTitle = if (isFarsi) selectedResolver.nameFa else selectedResolver.nameEn
        val recordTitle = if (isFarsi) selectedRecordMode.titleFa else selectedRecordMode.titleEn
        val presetTitle = if (isFarsi) selectedPreset.titleFa else selectedPreset.titleEn
        val recordHint = if (isFarsi) selectedRecordMode.hintFa else selectedRecordMode.hintEn
        summaryBox.text = if (isFarsi) {
            "📡 ریزولور انتخابی: $resolverTitle (${selectedResolver.primaryIp})\n" +
                "🔌 ترنسپورت: ${selectedTransport.uppercase(Locale.US)} · 📦 رکورد حامل: $recordTitle\n" +
                "⚡ پریست شبکه ایران: $presetTitle\n" +
                "💡 $recordHint"
        } else {
            "📡 Resolver: $resolverTitle (${selectedResolver.primaryIp})\n" +
                "🔌 Transport: ${selectedTransport.uppercase(Locale.US)} · 📦 Carrier Record: $recordTitle\n" +
                "⚡ Iran Preset: $presetTitle\n" +
                "💡 $recordHint"
        }
        previewBox.text = buildString {
            appendLine("# 1) CottenMax (Reed-Solomon FEC 4:1 + Loss Guard + Timing Mask)")
            appendLine(bundle.cottenMaxUri)
            appendLine()
            appendLine("# 2) MasterDnsVPN (5B Header + Balancer 6 + Duplication + ZSTD)")
            appendLine(bundle.masterDnsUri)
            appendLine()
            appendLine("# 3) StormDNS / Multi-Resolver ARQ")
            appendLine(bundle.stormDnsUri)
            appendLine()
            appendLine("# 4) Soar (IR Profile · Packets-not-Segments)")
            appendLine(bundle.soarUri)
            appendLine()
            appendLine("# 5) Cat DNS VayDNS (2B ClientID + 9 Carrier Records)")
            appendLine(bundle.vayDnsUri)
            appendLine()
            appendLine("# 6) Cat DNS NoizDNS (Anti-DPI Base36 + Query Padding)")
            appendLine(bundle.noizDnsUri)
            appendLine()
            appendLine("# 7) Slipstream (QUIC-over-DNS · BBR)")
            appendLine(bundle.slipstreamUri)
            appendLine()
            appendLine("# 8) Universal dnst:// Standard (Human-Readable & Base64-JSON)")
            appendLine(bundle.dnstHumanUri)
            appendLine(bundle.dnstBase64Uri)
            appendLine()
            appendLine("# 9) Cat DNS Profile (slipnet:// + SSH Chain)")
            appendLine(bundle.slipNetUri)
            appendLine()
            appendLine("# 10) dnstt Bridge (${selectedTransport.uppercase(Locale.US)})")
            val dnsttLine = when (selectedTransport) {
                "dot" -> bundle.dnsttDotBridges.first()
                "doh" -> bundle.dnsttDohBridges.first()
                else -> bundle.dnsttUdpBridges.first()
            }
            append(dnsttLine)
        }
    }

    fun actionBtn(label: String, accent: Boolean = false, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(42)
            minimumHeight = dp(42)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(10)
            setPadding(dp(10), 0, dp(10), 0)
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

    refreshView()
    root.addView(summaryBox, LinearLayout.LayoutParams(-1, -2))

    // Button 1: Live Scan on user's network (UDP:53 + TCP:53 + DoT:853 + Carrier Records)
    root.addView(
        actionBtn(
            if (isFarsi) "🔍 اسکن زنده همه متدهای DNS و رکوردهای باز روی خط من"
            else "🔍 Live Scan DNS Transports & Open Carrier Records on My Line",
            accent = true,
        ) {
            runIranDnsAndDnsttScanDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Button 1b: Auto-MTU & Carrier Record Finder (auto-detects QName limit, carrier record & preset in-place)
    root.addView(
        actionBtn(
            if (isFarsi) "⚡ تشخیص خودکار MTU، رکورد حامل و بهترین پریست (Auto-Tune)"
            else "⚡ Auto-Detect MTU, Carrier Record & Best Preset",
            accent = false,
        ) {
            Toast.makeText(
                this@showWhiteDnsResolverMethodsDialog,
                if (isFarsi) "⏳ در حال تست طول QName، رکوردهای حامل (NULL/TXT/HTTPS) و بهترین ریزولور…"
                else "⏳ Probing QName MTU, carrier records & fastest resolver…",
                Toast.LENGTH_SHORT,
            ).show()
            activityScope.launch {
                val detected = withContext(Dispatchers.IO) {
                    WhiteDnsSmartScanner.autoDetectBestWhiteDnsProfile()
                }
                selectedResolver = detected.resolver
                selectedTransport = detected.transport
                selectedRecordMode = detected.recordMode
                selectedPreset = detected.preset
                refreshView()
                Toast.makeText(
                    this@showWhiteDnsResolverMethodsDialog,
                    if (isFarsi) detected.summaryFa else detected.summaryEn,
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )

    // Row: Select Resolver from 52-Server Catalog + Select Transport (Auto / UDP / TCP / DoT / DoH / DoQ / DoH3)
    val row1 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            actionBtn(if (isFarsi) "۱) کاتالوگ ۵۲ سرور DNS ▾" else "1) 52 DNS Resolvers ▾") {
                val catalog = WhiteDnsSmartScanner.RESOLVER_CATALOG
                val titles = catalog.map { r ->
                    val label = if (isFarsi) r.nameFa else r.nameEn
                    "$label (${r.primaryIp})"
                }.toTypedArray()
                MaterialAlertDialogBuilder(this@showWhiteDnsResolverMethodsDialog)
                    .setTitle(if (isFarsi) "انتخاب ریزولور (تحریم‌شکن / گیمینگ / اپراتور / QUIC)" else "Select DNS Resolver")
                    .setItems(titles) { _, idx ->
                        selectedResolver = catalog[idx]
                        refreshView()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1.1f),
        )
        addView(
            actionBtn(if (isFarsi) "۲) پروتکل ریزولور ▾" else "2) Transport ▾") {
                val transports = listOf("auto", "udp", "tcp", "dot", "doh", "doq", "doh3")
                val labels = if (isFarsi) {
                    arrayOf(
                        "Auto (UDP + TCP همزمان — پیشنهادی Cat DNS)",
                        "UDP :53 (کمترین پینگ — استاندارد)",
                        "TCP :53 (RFC 1035 — مخصوص زمان مسدودی UDP)",
                        "DoT :853 (DNS-over-TLS رمزنگاری‌شده)",
                        "DoH :443 (DNS-over-HTTPS)",
                        "DoQ :853 (DNS-over-QUIC — ضد DPI برای ECH)",
                        "DoH3 :443 (HTTP/3 QUIC — ضد مسدودی TCP)",
                    )
                } else {
                    arrayOf(
                        "Auto (Dual UDP + TCP — Recommended)",
                        "UDP :53 (Lowest Latency)",
                        "TCP :53 (RFC 1035 — When UDP is blocked)",
                        "DoT :853 (DNS-over-TLS)",
                        "DoH :443 (DNS-over-HTTPS)",
                        "DoQ :853 (DNS-over-QUIC — ECH Anti-DPI)",
                        "DoH3 :443 (HTTP/3 QUIC)",
                    )
                }
                MaterialAlertDialogBuilder(this@showWhiteDnsResolverMethodsDialog)
                    .setTitle(if (isFarsi) "انتخاب ترنسپورت ریزولور" else "Select Resolver Transport")
                    .setItems(labels) { _, idx ->
                        selectedTransport = transports[idx]
                        refreshView()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 0.9f).apply { marginStart = dp(6) },
        )
    }
    root.addView(row1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Row: Select Carrier Record Mode (HTTPS/65, TXT+CNAME, TXT+AAAA, NULL 1232B, All) + Select Iran Auto-Tune Preset
    val row2 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            actionBtn(if (isFarsi) "۳) رکورد حامل DNS ▾" else "3) Carrier Record ▾") {
                val modes = WhiteDnsRecordMode.values()
                val labels = modes.map { if (isFarsi) it.titleFa else it.titleEn }.toTypedArray()
                MaterialAlertDialogBuilder(this@showWhiteDnsResolverMethodsDialog)
                    .setTitle(if (isFarsi) "انتخاب رکورد حامل تانل DNS" else "Select DNS Carrier Record")
                    .setItems(labels) { _, idx ->
                        selectedRecordMode = modes[idx]
                        refreshView()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            actionBtn(if (isFarsi) "۴) پریست شبکه ایران ▾" else "4) Iran Network Preset ▾") {
                val presets = WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS
                val labels = presets.map { if (isFarsi) it.titleFa else it.titleEn }.toTypedArray()
                MaterialAlertDialogBuilder(this@showWhiteDnsResolverMethodsDialog)
                    .setTitle(if (isFarsi) "انتخاب پریست خودکار Cat DNS برای ایران" else "Select Cat DNS Iran Auto-Tune Preset")
                    .setItems(labels) { _, idx ->
                        selectedPreset = presets[idx]
                        selectedTransport = selectedPreset.transport
                        refreshView()
                    }
                    .create()
                    .showCatClientDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
    }
    root.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // Row 3: SlipNet 6-Point Score Scanner (0..6 + Prism) & MasterDNS TOML / dnst:// Generator
    val row3 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            actionBtn(if (isFarsi) "🎯 اسکن ۶ امتیازی Cat DNS (0..6)" else "🎯 Cat DNS 0..6 Score Scan") {
                runSlipNetSixPointScoreDialog()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            actionBtn(if (isFarsi) "⚙️ جنریتور MasterDNS / dnst://" else "⚙️ MasterDNS / dnst:// Gen") {
                showMasterDnsAndDnstGeneratorDialog(selectedResolver.primaryIp)
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
        )
    }
    root.addView(row3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // Button: Direct In-App Tunnel Connect (CottenMax / MasterDNS / StormDNS / Soar / VayDNS / NoizDNS / Slipstream / dnst://)
    root.addView(
        actionBtn(
            if (isFarsi) "🚀 اتصال مستقیم تانل DNS در همین اپ (۹ موتور ۲۰۲۶)"
            else "🚀 Connect DNS Tunnel Directly in App (9 Engines)",
            accent = true,
        ) {
            connectWhiteDnsTunnelDirectly(currentBundle())
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    root.addView(previewBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val scroll = ScrollView(this).apply { addView(root) }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "📡 متدهای اتصال بر پایه DNS و ریزولور (Cat DNS)"
            else "📡 Cat DNS & Resolver Connection Methods",
        )
        .setView(scroll)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "📋 کپی بسته لینک‌های تانل" else "📋 Copy Tunnel Links") { _, _ ->
            val text = previewBox.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("whitedns-bundle", text))
            Toast.makeText(
                this,
                if (isFarsi) "✅ لینک‌های CottenMax، StormDNS، Soar و dnstt کپی شد"
                else "✅ Copied CottenMax, StormDNS, Soar & dnstt links",
                Toast.LENGTH_LONG,
            ).show()
        }
        .setPositiveButton(if (isFarsi) "✅ اعمال ریزولور روی DNS اپ" else "✅ Apply Resolver to App DNS") { _, _ ->
            val r = selectedResolver
            when {
                (selectedTransport == "doq" || r.doqEndpoint.isNotEmpty()) && selectedTransport !in setOf("doh", "dot") -> {
                    val ep = r.doqEndpoint.ifEmpty { "quic://${r.primaryIp}:853" }
                    runCatching { dnsPrivacyPreferenceStore.saveDoqEndpoint(ep) }
                    dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoQ)
                }
                selectedTransport == "doh3" && r.doh3Url.isNotEmpty() -> {
                    runCatching { dnsPrivacyPreferenceStore.saveDoh3Url(r.doh3Url) }
                    dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoH3)
                }
                selectedTransport == "dot" || r.dotEndpoint.isNotEmpty() -> {
                    val ep = r.dotEndpoint.ifEmpty { "tls://${r.primaryIp}:853" }
                    runCatching { dnsPrivacyPreferenceStore.saveDotEndpoint(ep) }
                    dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoT)
                }
                else -> {
                    val url = r.dohUrl.ifEmpty { "https://178.22.122.100/dns-query" }
                    runCatching { dnsPrivacyPreferenceStore.saveDohUrl(url) }
                    dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoH)
                }
            }
            renderDnsPrivacySelection()
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            }
            Toast.makeText(
                this,
                if (isFarsi) "✅ ریزولور «${r.nameFa}» روی موتور DNS برنامه فعال شد"
                else "✅ Enabled '${r.nameEn}' on the app DNS engine",
                Toast.LENGTH_LONG,
            ).show()
        }
        .create()
        .showCatClientDialog()
}

/**
 * Generator dialog for MasterDnsVPN (`0..8` balancing strategies, `1..8` packet duplication,
 * `client_config.toml` export) + `VayDNS` (9 carrier records) + `dnst://` (Human & Base64-JSON) + `slipnet://`.
 */
internal fun MainActivity.showMasterDnsAndDnstGeneratorDialog(defaultResolverIp: String = "178.22.122.100") {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    var selectedBalancer = WhiteDnsSmartScanner.MasterDnsBalancingStrategy.TIERED_SHORTLIST_RR_6
    var selectedVayRecord = WhiteDnsSmartScanner.VayDnsRecordType.CNAME
    var duplication = 2
    val domainInput = EditText(this).apply {
        setText("v.example.com")
        hint = if (isFarsi) "دامنه NS تانل (مثلاً v.example.com)" else "Tunnel NS domain (e.g. v.example.com)"
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        textSize = 12.5f
    }
    val preview = TextView(this).apply {
        setTextColor(TEXT_PRIMARY)
        textSize = 11f
        typeface = CatClientDataTypeface
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(withAlpha(SURFACE, 235))
            setStroke(dp(1), withAlpha(OUTLINE, 180))
        }
        setTextIsSelectable(true)
    }
    fun refreshGen() {
        val dom = domainInput.text?.toString()?.trim().orEmpty().ifBlank { "v.example.com" }
        val dnstHuman = WhiteDnsSmartScanner.buildDnstStandardUri(
            domain = dom,
            transport = "vaydns",
            backend = "socks",
            recordType = selectedVayRecord.wireName,
            resolverIp = defaultResolverIp,
            tag = "VayDNS-${selectedVayRecord.wireName.uppercase()}",
            asBase64Json = false,
        )
        val dnstB64 = WhiteDnsSmartScanner.buildDnstStandardUri(
            domain = dom,
            transport = "vaydns",
            backend = "socks",
            recordType = selectedVayRecord.wireName,
            resolverIp = defaultResolverIp,
            tag = "VayDNS-${selectedVayRecord.wireName.uppercase()}",
            asBase64Json = true,
        )
        val slipNetUri = WhiteDnsSmartScanner.buildSlipNetUri(
            domain = dom,
            resolverIp = defaultResolverIp,
            tunnelType = "noizdns",
            recordType = selectedVayRecord.wireName,
            remark = "Cat-DNS-NoizDNS-SSH",
        )
        val masterUri = "masterdns://$defaultResolverIp:53/?domain=$dom&balancer=${selectedBalancer.code}&dup=$duplication&encryption=5&comp=1#MasterDNS-B${selectedBalancer.code}-Dup$duplication"
        preview.text = buildString {
            appendLine("# MasterDnsVPN URI (${if (isFarsi) selectedBalancer.titleFa else selectedBalancer.titleEn}):")
            appendLine(masterUri)
            appendLine()
            appendLine("# dnst:// Human-Readable (VayDNS · ${selectedVayRecord.wireName.uppercase()}):")
            appendLine(dnstHuman)
            appendLine()
            appendLine("# dnst:// Base64-JSON:")
            appendLine(dnstB64)
            appendLine()
            appendLine("# slipnet:// Profile (NoizDNS + Padding + SSH):")
            append(slipNetUri)
        }
    }
    refreshGen()

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(6))
        addView(domainInput, LinearLayout.LayoutParams(-1, -2))
    }
    val btnBalancer = MaterialButton(this).apply {
        text = if (isFarsi) "⚖️ استراتژی بالانسر MasterDNS (0..8) ▾" else "⚖️ MasterDNS Balancer (0..8) ▾"
        isAllCaps = false
        textSize = 11.5f
        setOnClickListener {
            val all = WhiteDnsSmartScanner.MasterDnsBalancingStrategy.entries
            val labels = all.map { if (isFarsi) it.titleFa else it.titleEn }.toTypedArray()
            MaterialAlertDialogBuilder(this@showMasterDnsAndDnstGeneratorDialog)
                .setTitle(if (isFarsi) "انتخاب استراتژی بالانسر ریزولور (0..8)" else "Select Balancer Strategy (0..8)")
                .setItems(labels) { _, idx ->
                    selectedBalancer = all[idx]
                    duplication = if (selectedBalancer.code >= 6) 2 else 3
                    refreshGen()
                }
                .create()
                .showCatClientDialog()
        }
    }
    val btnVayRec = MaterialButton(this).apply {
        text = if (isFarsi) "📦 رکورد حامل VayDNS (۹ رکورد) ▾" else "📦 VayDNS Carrier Record (9 Types) ▾"
        isAllCaps = false
        textSize = 11.5f
        setOnClickListener {
            val all = WhiteDnsSmartScanner.VayDnsRecordType.entries
            val labels = all.map { if (isFarsi) it.titleFa else it.titleEn }.toTypedArray()
            MaterialAlertDialogBuilder(this@showMasterDnsAndDnstGeneratorDialog)
                .setTitle(if (isFarsi) "انتخاب رکورد حامل VayDNS" else "Select VayDNS Record Type")
                .setItems(labels) { _, idx ->
                    selectedVayRecord = all[idx]
                    refreshGen()
                }
                .create()
                .showCatClientDialog()
        }
    }
    container.addView(btnBalancer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    container.addView(btnVayRec, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    container.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val scroll = ScrollView(this).apply { addView(container) }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "⚙️ جنریتور MasterDnsVPN، VayDNS، NoizDNS و dnst://"
            else "⚙️ MasterDnsVPN, VayDNS, NoizDNS & dnst:// Generator",
        )
        .setView(scroll)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "📄 کپی client_config.toml" else "📄 Copy client_config.toml") { _, _ ->
            val dom = domainInput.text?.toString()?.trim().orEmpty().ifBlank { "v.example.com" }
            val toml = WhiteDnsSmartScanner.buildMasterDnsClientToml(
                domains = listOf(dom),
                balancingStrategy = selectedBalancer,
                packetDuplication = duplication,
            )
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("masterdns-toml", toml))
            Toast.makeText(
                this,
                if (isFarsi) "✅ فایل کامل client_config.toml پروژه MasterDnsVPN کپی شد"
                else "✅ Copied full MasterDnsVPN client_config.toml",
                Toast.LENGTH_LONG,
            ).show()
        }
        .setPositiveButton(if (isFarsi) "➕ افزودن لینک‌ها به اشتراک اپ" else "➕ Add Links to App Sub") { _, _ ->
            refreshGen()
            val rawLines = preview.text.toString().lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString("\n")
            val title = "🧬 MasterDNS + Cat DNS + dnst://"
            activityScope.launch {
                val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                val saved = withContext(Dispatchers.IO) {
                    if (existing != null) {
                        userSubscriptionManager.update(existing.id, title, rawLines)
                    } else {
                        userSubscriptionManager.add(title, rawLines)
                    }
                }
                userSubscriptionManager.select(saved.id)
                renderSubscriptions()
                onSubscriptionSelected()
                Toast.makeText(
                    this@showMasterDnsAndDnstGeneratorDialog,
                    if (isFarsi) "✅ کانفیگ‌های MasterDNS، dnst:// و Cat DNS به اشتراک شما اضافه و انتخاب شد"
                    else "✅ Added MasterDNS, dnst:// & Cat DNS configs to your subscriptions",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        .create()
        .showCatClientDialog()
}

/**
 * Full `Paqet` & `paqetNG` (`patth` & `patth ng` v1.3.7 + `paqet-ngv2` DNS Coordinate Recovery) Engine Dialog:
 * - Supports `paqet://`, `paqetng://`, `patth://`, `patthng://` links and YAML/TOML/JSON configs
 * - Auto-detects mobile data interface (`rmnet_data*`, `ccmni*`, `wlan*`, skipping `tun0` per `paqetNG v1.2.4`)
 * - Supports KCP + smux + AES-GCM + TLS Record Obfuscation + Dynamic Port Hopping + DNS Emergency Recovery
 */
internal fun MainActivity.showPaqetPatthEngineDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val savedCfg = PaqetEngine.loadLastConfig(this) ?: PaqetEngine.PaqetConfig(
        variant = PaqetEngine.TransportVariant.PAQET_NG,
        serverHost = "188.114.98.0",
        serverPort = 443,
        portHopStart = 20000,
        portHopEnd = 20500,
        secretKey = "catclient-paqet-key",
        sniHost = "speedtest.net",
        remark = "PaqetNG Anti-DPI",
    )
    val detectedIface = PaqetEngine.detectBestPhysicalNetworkInterface()
    val rootOk = PaqetEngine.isRootAvailableQuick()

    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(8))
    }

    val infoBanner = TextView(this).apply {
        text = if (isFarsi) {
            "📦 موتور سطح پکت Paqet و paqetNG (patth / patth ng v1.3.7 + ngv2)\n" +
                "• رابط شبکهٔ شناسایی‌شده (فیکس دیتای موبایل v1.2.4): $detectedIface\n" +
                "• وضعیت دسترسی: ${if (rootOk) "Root + Userspace" else "بدون نیاز به روت (Userspace KCP + TLS Record Obfs + Entry Relay)"}\n" +
                "• پشتیبانی از لینک‌های paqet:// ، paqetng:// ، patth:// ، patthng:// و فایل‌های YAML/TOML/JSON"
        } else {
            "📦 Paqet & paqetNG Engine (patth / patth ng v1.3.7 + ngv2)\n" +
                "• Detected physical interface (v1.2.4 mobile data fix): $detectedIface\n" +
                "• Mode: ${if (rootOk) "Root + Userspace" else "Non-Root Userspace (KCP + TLS Record Obfs + Entry Relay)"}"
        }
        textSize = 12f
        setTextColor(TEAL)
        typeface = CatClientBodyTypeface
    }
    container.addView(infoBanner, LinearLayout.LayoutParams(-1, -2))

    var selectedVariant = savedCfg.variant
    val variantBtn = MaterialButton(this).apply {
        text = "⚙️ حالت موتور: ${selectedVariant.titleFa} ▾"
        isAllCaps = false
        textSize = 12f
        setOnClickListener {
            val all = PaqetEngine.TransportVariant.values()
            val labels = all.map { it.titleFa }.toTypedArray()
            MaterialAlertDialogBuilder(this@showPaqetPatthEngineDialog)
                .setTitle(if (isFarsi) "انتخاب حالت paqet / patth" else "Select paqet / patth Variant")
                .setItems(labels) { _, which ->
                    selectedVariant = all[which]
                    this.text = "⚙️ حالت موتور: ${selectedVariant.titleFa} ▾"
                }
                .create()
                .showCatClientDialog()
        }
    }
    container.addView(variantBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val serverInput = EditText(this).apply {
        hint = if (isFarsi) "آدرس سرور یا Entry Point (مثلاً 188.114.98.0:443) یا لینک کامل paqetng://..." else "Server IP:Port or full paqetng:// URI"
        setText("${savedCfg.serverHost}:${savedCfg.serverPort}")
        textSize = 13f
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    container.addView(serverInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val keyInput = EditText(this).apply {
        hint = if (isFarsi) "کلید رمزنگاری AES-GCM (Secret Key)" else "AES-GCM Secret Key"
        setText(savedCfg.secretKey)
        textSize = 13f
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    container.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val hopInput = EditText(this).apply {
        hint = if (isFarsi) "بازهٔ چرخش پورت Port Hopping (مثلاً 20000-20500)" else "Port Hopping Range (e.g. 20000-20500)"
        setText(if (savedCfg.hasPortHopping) "${savedCfg.portHopStart}-${savedCfg.portHopEnd}" else "20000-20500")
        textSize = 13f
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    container.addView(hopInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val dnsRecoveryInput = EditText(this).apply {
        hint = if (isFarsi) "دامنهٔ بازیابی اضطراری روی DNS (paqet-ngv2 اختیاری)" else "Emergency DNS Control Domain (paqet-ngv2 optional)"
        setText(savedCfg.dnsControlDomain)
        textSize = 13f
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    container.addView(dnsRecoveryInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val statusView = TextView(this).apply {
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        typeface = CatClientDataTypeface
        text = PaqetEngine.buildPaqetUri(savedCfg)
    }

    fun buildCurrentConfig(): PaqetEngine.PaqetConfig {
        val rawServer = serverInput.text?.toString()?.trim().orEmpty()
        PaqetEngine.parsePaqetFileOrUri(rawServer)?.let { return it }
        val host = if (':' in rawServer) rawServer.substringBeforeLast(':').removePrefix("[").removeSuffix("]") else rawServer.ifBlank { "188.114.98.0" }
        val port = if (':' in rawServer) (rawServer.substringAfterLast(':').toIntOrNull() ?: 443) else 443
        val hopRaw = hopInput.text?.toString()?.trim().orEmpty()
        val hopStart = hopRaw.substringBefore('-', "0").trim().toIntOrNull() ?: 0
        val hopEnd = hopRaw.substringAfter('-', "0").trim().toIntOrNull() ?: 0
        val key = keyInput.text?.toString()?.trim().orEmpty().ifBlank { "catclient-paqet-key" }
        val dnsDom = dnsRecoveryInput.text?.toString()?.trim().orEmpty()
        return PaqetEngine.PaqetConfig(
            variant = selectedVariant,
            serverHost = host,
            serverPort = port.coerceIn(1, 65535),
            portHopStart = hopStart,
            portHopEnd = hopEnd,
            secretKey = key,
            sniHost = "speedtest.net",
            networkInterface = detectedIface,
            dnsControlDomain = dnsDom,
            remark = "📦 ${selectedVariant.id.uppercase(Locale.US)} $host:$port",
        )
    }

    val testBtn = MaterialButton(this).apply {
        text = if (isFarsi) "🔍 تست واقعی پکت و دست‌تکان سرور (Probe)" else "🔍 Probe Server & Packet Handshake"
        isAllCaps = false
        textSize = 12f
        setOnClickListener {
            val cfg = buildCurrentConfig()
            statusView.text = if (isFarsi) "⏳ در حال تست سرور ${cfg.serverHost}:${cfg.serverPort}..." else "⏳ Probing..."
            activityScope.launch {
                val res = withContext(Dispatchers.IO) { PaqetEngine.probePaqetEndpoint(cfg, timeoutMs = 2500) }
                statusView.text = res.messageFa + "\n" + PaqetEngine.buildPaqetUri(cfg)
            }
        }
    }
    container.addView(testBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    container.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val scroll = ScrollView(this).apply { addView(container) }
    MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "📦 موتور Paqet و paqetNG (patth / patth ng v1.3.7)" else "📦 Paqet & paqetNG Engine (patth / patth ng)")
        .setView(scroll)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(if (isFarsi) "📋 کپی لینک paqetng://" else "📋 Copy paqetng:// URI") { _, _ ->
            val uri = PaqetEngine.buildPaqetUri(buildCurrentConfig())
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("paqet-uri", uri))
            Toast.makeText(this, if (isFarsi) "✅ لینک کانفیگ PaqetNG کپی شد" else "✅ Copied PaqetNG URI", Toast.LENGTH_SHORT).show()
        }
        .setPositiveButton(if (isFarsi) "🚀 افزودن به اشتراک و اتصال فوری" else "🚀 Add to Subscriptions & Connect") { _, _ ->
            val cfg = buildCurrentConfig()
            val uri = PaqetEngine.buildPaqetUri(cfg)
            activityScope.launch {
                withContext(Dispatchers.IO) {
                    PaqetEngine.ensureLocalBridgeRunning(this@showPaqetPatthEngineDialog, uri)
                }
                val subTitle = "📦 PaqetNG (patth / patth ng)"
                val saved = withContext(Dispatchers.IO) {
                    val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                    if (existing != null) userSubscriptionManager.update(existing.id, subTitle, uri)
                    else userSubscriptionManager.add(subTitle, uri)
                }
                userSubscriptionManager.select(saved.id)
                onSubscriptionSelected()
                if (buttonModel.state != VpnState.Started) {
                    beginConnectFlow(Actions.CONNECT)
                }
                Toast.makeText(
                    this@showPaqetPatthEngineDialog,
                    if (isFarsi) "✅ موتور ${cfg.variant.id.uppercase(Locale.US)} روشن شد و کانفیگ انتخاب گردید"
                    else "✅ Started ${cfg.variant.id.uppercase(Locale.US)} engine & selected config",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        .create()
        .showCatClientDialog()
}

/**
 * Smart Config Doctor & Cleaner + MLM FLUX (`IPv4 F&F` / `IPv6` / `Both`) Dialog (`MLM v1.2.42`):
 * Scans all configs inside the user's current subscription, removes duplicates, repairs broken Cloudflare
 * edges with MCI IPv6 or Irancell F&F, sorts healthy configs to the top, and marks the fastest with "⭐ پیشنهادی".
 */
internal fun MainActivity.showSmartConfigDoctorAndFluxDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val options = if (isFarsi) {
        arrayOf(
            "🩺 ۱) تعمیر خودکار، حذف تکراری‌ها و علامت‌گذاری «⭐ پیشنهادی» روی اشتراک فعلی",
            "⚡ ۲) موتور Cat FLUX: بهینه‌سازی کانفیگ‌ها برای همراه اول (IPv6 تمیز کلودفلر)",
            "🔥 ۳) موتور Cat FLUX: بهینه‌سازی کانفیگ‌ها برای ایرانسل (IPv4 + روش ضد فیلتر F&F)",
            "🌐 ۴) موتور Cat FLUX: حالت ترکیبی دوگانه (IPv4 F&F + IPv6 همزمان — ⭐ پیشنهادی)",
        )
    } else {
        arrayOf(
            "🩺 1) Smart Config Doctor: Deduplicate, Auto-Heal & Mark '⭐ Recommended'",
            "⚡ 2) Cat FLUX: Optimize for MCI (Clean Cloudflare IPv6)",
            "🔥 3) Cat FLUX: Optimize for Irancell (IPv4 + F&F Fragmentation)",
            "🌐 4) Cat FLUX: Dual-Stack Both (IPv4 F&F + IPv6 — ⭐ Recommended)",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🩺 پزشک هوشمند تعمیر کانفیگ‌ها و موتور Cat FLUX"
            else "🩺 Smart Config Doctor & Cat FLUX Engine",
        )
        .setItems(options) { _, which ->
            activityScope.launch {
                val store = SubscriptionStore(this@showSmartConfigDoctorAndFluxDialog)
                val selectedId = store.readSelectedSubscriptionId()
                val snap = withContext(Dispatchers.IO) { userSubscriptionManager.cachedSnapshot(selectedId) }
                val currentLinks = snap?.catalog?.profiles?.mapNotNull { it.shareLink }.orEmpty().ifEmpty {
                    freeConfigEntries.take(25).map { it.link }
                }
                if (currentLinks.isEmpty()) {
                    Toast.makeText(
                        this@showSmartConfigDoctorAndFluxDialog,
                        if (isFarsi) "⚠️ ابتدا یک کانفیگ یا اشتراک اضافه کنید یا از «🚀 اتصال جادویی ۱-کلیکی» استفاده کنید"
                        else "⚠️ Please add a config first or use '🚀 1-Click Magic Connect'",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }

                Toast.makeText(
                    this@showSmartConfigDoctorAndFluxDialog,
                    if (isFarsi) "⏳ در حال بررسی، تست پینگ واقعی و تعمیر کانفیگ‌ها..."
                    else "⏳ Diagnosing, probing, and repairing configs...",
                    Toast.LENGTH_SHORT,
                ).show()

                if (which == 0) {
                    val report = withContext(Dispatchers.IO) {
                        MlmAndNextGenVpnHub.diagnoseAndRepairUserConfigs(currentLinks, timeoutMs = 1100)
                    }
                    if (report.repairedLinks.isNotEmpty()) {
                        val title = "🩺 کانفیگ‌های تعمیرشده (⭐ ${report.bestRecommendedName.take(18)})"
                        val payload = report.repairedLinks.joinToString("\n")
                        val saved = withContext(Dispatchers.IO) {
                            val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("🩺 کانفیگ‌های تعمیرشده") }
                            if (existing != null) userSubscriptionManager.update(existing.id, title, payload)
                            else userSubscriptionManager.add(title, payload)
                        }
                        userSubscriptionManager.select(saved.id)
                        onSubscriptionSelected()
                        Toast.makeText(
                            this@showSmartConfigDoctorAndFluxDialog,
                            if (isFarsi) {
                                "✅ ${report.validUniqueCount} کانفیگ بررسی شد (${report.healthyDirectCount} سالم + ${report.autoHealedCount} تعمیر خودکار) · بهترین: ⭐ ${report.bestRecommendedName} (${report.bestRecommendedLatencyMs}ms)"
                            } else {
                                "✅ Checked ${report.validUniqueCount} configs (${report.healthyDirectCount} healthy + ${report.autoHealedCount} auto-healed)"
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    val family = when (which) {
                        1 -> MlmAndNextGenVpnHub.MlmFluxIpFamily.IPV6_ONLY
                        2 -> MlmAndNextGenVpnHub.MlmFluxIpFamily.IPV4_FN_F
                        else -> MlmAndNextGenVpnHub.MlmFluxIpFamily.BOTH
                    }
                    val fluxLinks = withContext(Dispatchers.IO) {
                        MlmAndNextGenVpnHub.applyMlmFluxOptimization(currentLinks, family, "AUTO")
                    }
                    if (fluxLinks.isNotEmpty()) {
                        val title = "⚡ Cat FLUX (${family.id.uppercase(Locale.US)})"
                        val payload = fluxLinks.joinToString("\n")
                        val saved = withContext(Dispatchers.IO) {
                            val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                            if (existing != null) userSubscriptionManager.update(existing.id, title, payload)
                            else userSubscriptionManager.add(title, payload)
                        }
                        userSubscriptionManager.select(saved.id)
                        onSubscriptionSelected()
                        Toast.makeText(
                            this@showSmartConfigDoctorAndFluxDialog,
                            if (isFarsi) "✅ ${fluxLinks.size} کانفیگ با موتور Cat FLUX (${family.labelFa}) ساخته و انتخاب شد"
                            else "✅ Built & selected ${fluxLinks.size} Cat FLUX configs",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showOutlineFreeHubDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val options = if (isFarsi) {
        arrayOf(
            "🚀 دریافت خودکار سرورهای رایگان Outline از ۶ مخزن + تست و اتصال به سریع‌ترین",
            "🔑 چسباندن کلید شخصی Outline (`ss://` یا کلید پویای `ssconf://`) و اتصال فوری",
            "⚡ اتصال فوری با ۴ سرور آمادهٔ داخلی Outline (بدون نیاز به دانلود فهرست)",
        )
    } else {
        arrayOf(
            "🚀 Fetch Free Outline Servers from 6 Live Feeds + Scan & Connect Fastest",
            "🔑 Paste Personal Outline Key (`ss://` or Dynamic `ssconf://`) & Connect",
            "⚡ Instant Connect with Built-in Emergency Outline Servers",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🔑 مرکز کلیدهای رایگان Outline و Shadowsocks (۶ مخزن زنده + آینه jsDelivr)"
            else "🔑 Outline & Shadowsocks Free Hub (6 Live Feeds + jsDelivr Mirror)",
        )
        .setItems(options) { _, which ->
            when (which) {
                0 -> {
                    Toast.makeText(
                        this,
                        if (isFarsi) "🔍 در حال دریافت کلیدهای رایگان Outline از ۶ مخزن (با آینه jsDelivr) و تست روی خط شما…"
                        else "🔍 Fetching Outline keys from 6 feeds & testing on your line…",
                        Toast.LENGTH_LONG,
                    ).show()
                    activityScope.launch {
                        val verified = withContext(Dispatchers.IO) {
                            MlmAndNextGenVpnHub.fetchAndScanOutlineKeys(maxCandidates = 42, timeoutMs = 1000)
                        }
                        val uris = if (verified.isNotEmpty()) {
                            verified.take(15).map { it.uri }
                        } else {
                            MlmAndNextGenVpnHub.BUILTIN_OUTLINE_SS_KEYS
                        }
                        val subTitle = "🔑 سرورهای رایگان Outline (تست‌شده روی خط شما)"
                        val raw = uris.joinToString("\n")
                        val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                        val saved = withContext(Dispatchers.IO) {
                            if (existing != null) {
                                userSubscriptionManager.update(existing.id, subTitle, raw)
                            } else {
                                userSubscriptionManager.add(subTitle, raw)
                            }
                        }
                        userSubscriptionManager.select(saved.id)
                        val curAether = aetherPreferenceStore.read()
                        if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                        orbotEnginePreferenceStore.setEnabled(false)
                        renderSubscriptions()
                        onSubscriptionSelected()
                        renderAdvancedControls()
                        val fastestNote = verified.firstOrNull()?.let { "${it.host}:${it.port} (${it.rttMs} ms)" } ?: "Built-in"
                        Toast.makeText(
                            this@showOutlineFreeHubDialog,
                            if (isFarsi) "✅ ${uris.size} سرور سالم Outline آماده شد (سریع‌ترین: $fastestNote) — در حال اتصال…"
                            else "✅ ${uris.size} healthy Outline servers ready (fastest: $fastestNote) — connecting…",
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
                1 -> {
                    val input = EditText(this).apply {
                        hint = "ss://... or ssconf://..."
                        textSize = 13f
                        minLines = 3
                    }
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(12), dp(20), dp(4))
                        addView(input)
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (isFarsi) "🔑 ورود کلید `ss://` یا `ssconf://` اوتلاین" else "🔑 Import Outline `ss://` or `ssconf://` Key")
                        .setView(box)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(if (isFarsi) "🚀 افزودن و اتصال فوری" else "🚀 Add & Connect Now") { _, _ ->
                            val rawKey = input.text?.toString()?.trim().orEmpty()
                            if (rawKey.isEmpty()) return@setPositiveButton
                            activityScope.launch {
                                val resolved = withContext(Dispatchers.IO) {
                                    MlmAndNextGenVpnHub.resolveSsconfOrSsKey(rawKey)
                                }
                                if (resolved == null) {
                                    Toast.makeText(
                                        this@showOutlineFreeHubDialog,
                                        if (isFarsi) "فرمت کلید Outline معتبر نبود (`ss://` یا `ssconf://`)"
                                        else "Invalid Outline key (`ss://` or `ssconf://`)",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    return@launch
                                }
                                val subTitle = "🔑 کلید شخصی Outline VPN"
                                val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                                val saved = withContext(Dispatchers.IO) {
                                    if (existing != null) {
                                        userSubscriptionManager.update(existing.id, subTitle, resolved)
                                    } else {
                                        userSubscriptionManager.add(subTitle, resolved)
                                    }
                                }
                                userSubscriptionManager.select(saved.id)
                                val curAether = aetherPreferenceStore.read()
                                if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                                orbotEnginePreferenceStore.setEnabled(false)
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
                        .create()
                        .showCatClientDialog()
                }
                2 -> {
                    activityScope.launch {
                        val raw = MlmAndNextGenVpnHub.BUILTIN_OUTLINE_SS_KEYS.joinToString("\n")
                        val subTitle = "🔑 سرورهای آمادهٔ Outline VPN"
                        val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                        val saved = withContext(Dispatchers.IO) {
                            if (existing != null) {
                                userSubscriptionManager.update(existing.id, subTitle, raw)
                            } else {
                                userSubscriptionManager.add(subTitle, raw)
                            }
                        }
                        userSubscriptionManager.select(saved.id)
                        val curAether = aetherPreferenceStore.read()
                        if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                        orbotEnginePreferenceStore.setEnabled(false)
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
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showAmneziaAndProtonWgHubDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val options = if (isFarsi) {
        arrayOf(
            "🚀 اسکن و اتصال فوری به سرورهای رایگان AmneziaWG 2.0 و Hysteria2",
            "📄 تبدیل فایل `.conf` پروتون (Proton VPN) یا وایرگارد به AmneziaWG ضد فیلتر و اتصال",
            "🌐 باز کردن سایت رسمی Proton VPN برای دریافت کانفیگ رایگان WireGuard",
        )
    } else {
        arrayOf(
            "🚀 Scan & Connect Free AmneziaWG 2.0 & Hysteria2 Servers",
            "📄 Convert Proton VPN / WireGuard `.conf` into Anti-DPI AmneziaWG & Connect",
            "🌐 Open Official Proton VPN Account Page (Free WireGuard Configs)",
        )
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🛡️ مرکز AmneziaWG 2.0، پروتون رایگان و Hysteria2"
            else "🛡️ AmneziaWG 2.0, Free Proton WireGuard & Hysteria2 Hub",
        )
        .setItems(options) { _, which ->
            when (which) {
                0 -> {
                    Toast.makeText(
                        this,
                        if (isFarsi) "🛡️ در حال دریافت و تست سرورهای AmneziaWG 2.0 و Hysteria2…"
                        else "🛡️ Fetching & probing AmneziaWG 2.0 and Hysteria2 servers…",
                        Toast.LENGTH_SHORT,
                    ).show()
                    activityScope.launch {
                        val nodes = withContext(Dispatchers.IO) {
                            MlmAndNextGenVpnHub.fetchAndScanAmneziaAndProtonConfigs()
                        }
                        val uris = if (nodes.isNotEmpty()) {
                            nodes.map { it.uri }
                        } else {
                            MlmAndNextGenVpnHub.BUILTIN_AWG_AND_HY2_URIS
                        }
                        val subTitle = "🛡️ سرورهای AmneziaWG 2.0 و Hysteria2"
                        val raw = uris.joinToString("\n")
                        val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                        val saved = withContext(Dispatchers.IO) {
                            if (existing != null) {
                                userSubscriptionManager.update(existing.id, subTitle, raw)
                            } else {
                                userSubscriptionManager.add(subTitle, raw)
                            }
                        }
                        userSubscriptionManager.select(saved.id)
                        val curAether = aetherPreferenceStore.read()
                        if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                        orbotEnginePreferenceStore.setEnabled(false)
                        prewarmWarpKeyInBackground()
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
                1 -> {
                    val confInput = EditText(this).apply {
                        hint = "[Interface]\nPrivateKey = ...\nAddress = 10.2.0.2/32\n\n[Peer]\nPublicKey = ...\nEndpoint = ..."
                        textSize = 12f
                        minLines = 6
                    }
                    val box = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(20), dp(12), dp(20), dp(4))
                        addView(confInput)
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(
                            if (isFarsi) "📄 چسباندن محتوای فایل `.conf` پروتون یا وایرگارد"
                            else "📄 Paste Proton VPN / WireGuard `.conf` Content",
                        )
                        .setView(box)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(
                            if (isFarsi) "🛡️ تبدیل به AmneziaWG ضد فیلتر و اتصال"
                            else "🛡️ Convert to Anti-DPI AmneziaWG & Connect",
                        ) { _, _ ->
                            val text = confInput.text?.toString().orEmpty()
                            val awgUri = MlmAndNextGenVpnHub.convertWgConfToAwgUri(text, "Proton-AWG")
                            if (awgUri == null) {
                                Toast.makeText(
                                    this,
                                    if (isFarsi) "فایل `.conf` ناقص است (PrivateKey، PublicKey و Endpoint لازم است)"
                                    else "Incomplete `.conf` (PrivateKey, PublicKey & Endpoint required)",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@setPositiveButton
                            }
                            activityScope.launch {
                                val subTitle = "🛡️ پروتون / وایرگارد ضد فیلتر (AmneziaWG 2.0)"
                                val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                                val saved = withContext(Dispatchers.IO) {
                                    if (existing != null) {
                                        userSubscriptionManager.update(existing.id, subTitle, awgUri)
                                    } else {
                                        userSubscriptionManager.add(subTitle, awgUri)
                                    }
                                }
                                userSubscriptionManager.select(saved.id)
                                val curAether = aetherPreferenceStore.read()
                                if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                                orbotEnginePreferenceStore.setEnabled(false)
                                renderSubscriptions()
                                onSubscriptionSelected()
                                renderAdvancedControls()
                                Toast.makeText(
                                    this@showAmneziaAndProtonWgHubDialog,
                                    if (isFarsi) "✅ کانفیگ پروتون/وایرگارد با هدر ضد فیلتر AmneziaWG 2.0 آماده شد — در حال اتصال…"
                                    else "✅ Converted to Anti-DPI AmneziaWG 2.0 — connecting…",
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
                2 -> openExternalUrl("https://account.protonvpn.com/downloads#wireguard-configuration")
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

/**
 * Diagnoses the Oct 2026 Cloudflare IPv4 Zero-Byte filter, scans Cloudflare IPv6 edges & 5 MASQUE ports,
 * and self-heals the user's real config (or switches Aether onto the open MASQUE port).
 */
internal fun MainActivity.runCloudflareZeroByteAndIpv6HealerDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "🩺 در حال تست فیلترینگ صفر بایت IPv4 کلودفلر، ۱۱ لبهٔ IPv6 و ۵ پورت MASQUE…"
        else "🩺 Probing Cloudflare IPv4 0-byte filter, 11 IPv6 edges & 5 MASQUE ports…",
        Toast.LENGTH_SHORT,
    ).show()

    activityScope.launch {
        val diagnosis = withContext(Dispatchers.IO) {
            MlmAndNextGenVpnHub.diagnoseCloudflareIpv4ZeroByteAndMasquePorts(timeoutMs = 1100)
        }
        val realTemplate = resolveUserRealConfigTemplate()
        val body = buildString {
            appendLine(if (isFarsi) diagnosis.summaryFa else diagnosis.summaryEn)
            appendLine()
            if (realTemplate != null) {
                appendLine(
                    if (isFarsi) "🎯 کانفیگ واقعی شما (`${realTemplate.hostHeader}:${realTemplate.remotePort}`) شناسایی شد و آمادهٔ خودترمیمی با IPv6 کلودفلر + روش F&F + برش SNI محلی است."
                    else "🎯 Your real config (`${realTemplate.hostHeader}:${realTemplate.remotePort}`) was found and is ready for IPv6 + F&F + Local SNI Split healing.",
                )
            } else {
                appendLine(
                    if (isFarsi) "💡 اشتراک شخصی یافت نشد؛ با زدن دکمهٔ زیر، موتور Cat Warp روی بهترین پورت باز MASQUE (`${diagnosis.bestMasqueEndpoint}`) متصل می‌شود."
                    else "💡 No personal subscription found; tapping Connect will attach Cat Warp to the best open MASQUE port (`${diagnosis.bestMasqueEndpoint}`).",
                )
            }
        }

        MaterialAlertDialogBuilder(this@runCloudflareZeroByteAndIpv6HealerDialog)
            .setTitle(
                if (isFarsi) "🔄 خودترمیمی کلودفلر IPv4 به IPv6 + روش F&F و ۵ پورت MASQUE"
                else "🔄 Cloudflare IPv4->IPv6 Self-Healer + F&F & 5-Port MASQUE",
            )
            .setMessage(body.trim())
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (isFarsi) "🚀 اعمال خودترمیمی و اتصال فوری"
                else "🚀 Apply Self-Heal & Connect Now",
            ) { _, _ ->
                if (realTemplate != null) {
                    activityScope.launch {
                        val healedLinks = withContext(Dispatchers.IO) {
                            MlmAndNextGenVpnHub.buildIpv6AndFnFHealedConfigs(realTemplate, diagnosis)
                        }
                        val subTitle = "🔄 خودترمیم IPv6 + F&F (${realTemplate.remark.take(18)})"
                        val raw = healedLinks.joinToString("\n")
                        val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                        val saved = withContext(Dispatchers.IO) {
                            if (existing != null) {
                                userSubscriptionManager.update(existing.id, subTitle, raw)
                            } else {
                                userSubscriptionManager.add(subTitle, raw)
                            }
                        }
                        userSubscriptionManager.select(saved.id)
                        val curAether = aetherPreferenceStore.read()
                        if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                        orbotEnginePreferenceStore.setEnabled(false)
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
                } else {
                    val cur = aetherPreferenceStore.read()
                    aetherPreferenceStore.save(
                        cur.copy(
                            enabled = true,
                            mode = AetherMode.MasqueHttp2,
                            endpoint = diagnosis.bestMasqueEndpoint,
                            echEnabled = true,
                            endpointScannedAt = System.currentTimeMillis(),
                        ),
                    )
                    renderAdvancedControls()
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
}

/**
 * Interactive hub for all 4 requested Power Features:
 * 1) 🔄 Background Auto-Heal / Anti-Freeze Watchdog + 1-Tap Live Edge IP Switch without dropping VPN
 * 2) 📱 1-Click Smart Split Tunneling (Bypass Iranian Banks/Apps vs. Only Telegram/Instagram/YouTube/AI vs. All Apps)
 * 3) 🎮 Gaming & Voice/Video Call Low-Latency Anti-Jitter Booster (DSCP EF QoS + 4G/5G RRC Anti-Sleep Pacer + Live Jitter Meter)
 * 4) 📊 Live Tunnel Status Bar + 📲 1-Click Hotspot/Wi-Fi Share with QR Code + 🧹 1-Click Dead Config Cleaner & Sorter
 */
internal fun MainActivity.showFourPowerFeaturesCenterDialog() {
    LocalSniSpoofProxy.loadPersistedPowerSettings(this)
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val rootScroll = ScrollView(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(10))
    }
    rootScroll.addView(container)

    val liveStatusView = TextView(this).apply {
        textSize = 12.5f
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(0f, 1.2f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(withAlpha(TEAL, if (palette.isDark) 32 else 20))
            setStroke(dp(1), TEAL)
        }
    }
    container.addView(liveStatusView, LinearLayout.LayoutParams(-1, -2))

    fun refreshLiveStatusText(extraNote: String = "") {
        val splitMode = splitTunnelPreferenceStore.readSettings().mode
        val splitLabel = when (splitMode) {
            SplitTunnelMode.BypassSelected -> if (isFarsi) "🏦 عبور مستقیم بانک‌ها و اپ‌های ایرانی" else "🏦 Bypass Iranian Banks & Apps"
            SplitTunnelMode.VpnOnlySelected -> if (isFarsi) "🎯 فقط تلگرام/اینستاگرام/یوتیوب روی VPN" else "🎯 Only Social/Foreign on VPN"
            SplitTunnelMode.Off -> if (isFarsi) "🌐 همهٔ برنامه‌ها روی VPN" else "🌐 All Apps on VPN"
        }
        val edge = LocalSniSpoofProxy.lastActiveEdgeEndpoint
        val rtt = LocalSniSpoofProxy.lastActiveEdgeRttMs
        val rttText = if (rtt > 0) "${rtt}ms" else if (isFarsi) "آماده تست" else "Ready"
        val autoHealOn = LocalSniSpoofProxy.autoHealWatchdogEnabled
        val gamingOn = LocalSniSpoofProxy.gamingLowLatencyBoosterEnabled
        val batteryOn = LocalSniSpoofProxy.batteryAndDataSaverEnabled
        val hotspotOn = LocalSniSpoofProxy.isLanHotspotProxyRunning()
        val rotCount = LocalSniSpoofProxy.autoHealRotationCount.get()
        liveStatusView.text = if (isFarsi) {
            buildString {
                appendLine("📊 وضعیت زندهٔ تانل و مرکز قابلیت‌های طلایی:")
                appendLine("• لبه/آی‌پی فعال فعلی: $edge (پینگ: $rttText)")
                appendLine("• ۱) پایشگر خودکار ضد-فریز: ${if (autoHealOn) "🟢 روشن (تعویض بدون قطعی: $rotCount بار)" else "⚪ خاموش"}")
                appendLine("• ۲) تفکیک هوشمند برنامه‌ها: $splitLabel")
                appendLine("• ۳) بوستر گیمینگ و تماس ضد-پکت‌لاس: ${if (gamingOn) "🟢 روشن (DSCP EF + ضد خواب 4G/5G)" else "⚪ خاموش"}")
                appendLine("• ۴) هات‌اسپات پروکسی شبکه‌ای: ${if (hotspotOn) "🟢 روشن روی پورت ${LocalSniSpoofProxy.activeLanHotspotPort()}" else "⚪ آماده اشتراک‌گذاری"}")
                append("• ۵) ذخیرهٔ هوشمند باتری و حجم: ${if (batteryOn) "🟢 روشن" else "⚪ معمولی"}")
                if (extraNote.isNotBlank()) {
                    appendLine()
                    append(extraNote)
                }
            }
        } else {
            buildString {
                appendLine("📊 Live Tunnel & Power Features Status:")
                appendLine("• Active Clean Edge: $edge (Ping: $rttText)")
                appendLine("• 1) Auto-Heal Watchdog: ${if (autoHealOn) "🟢 ON (Rotations: $rotCount)" else "⚪ OFF"}")
                appendLine("• 2) Smart Split Tunnel: $splitLabel")
                appendLine("• 3) Gaming & Call Booster: ${if (gamingOn) "🟢 ON (DSCP EF + 4G/5G Anti-Sleep)" else "⚪ OFF"}")
                appendLine("• 4) LAN Hotspot Proxy: ${if (hotspotOn) "🟢 ON (Port ${LocalSniSpoofProxy.activeLanHotspotPort()})" else "⚪ Ready"}")
                append("• 5) Battery & Data Saver: ${if (batteryOn) "🟢 ON" else "⚪ Normal"}")
                if (extraNote.isNotBlank()) {
                    appendLine()
                    append(extraNote)
                }
            }
        }
    }
    refreshLiveStatusText()

    fun makeActionButton(label: String, filled: Boolean = false, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(10)
            if (filled) {
                backgroundTintList = ColorStateList.valueOf(TEAL)
                setTextColor(BACKGROUND)
            } else {
                backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, if (palette.isDark) 38 else 24))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(TEAL)
                setTextColor(TEAL)
            }
            setOnClickListener { onClick() }
        }
    }

    // Quick 1-Tap Edge IP Switch + Auto-Heal Watchdog Toggle
    container.addView(
        makeActionButton(
            label = if (isFarsi) "⚡ تعویض فوری آی‌پی/لبهٔ تمیز بدون قطع شدن VPN (۱-کلیکی)"
            else "⚡ 1-Tap Instant Clean Edge IP Switch (Zero VPN Drop)",
            filled = true,
        ) {
            refreshLiveStatusText(if (isFarsi) "⏳ در حال اسکن موازی لبه‌های کلودفلر و سوییچ بدون قطعی…" else "⏳ Probing clean edges & hot-swapping…")
            activityScope.launch {
                val rot = withContext(Dispatchers.IO) {
                    LocalSniSpoofProxy.rotateHealthyEdgeNow(forceDifferentIp = true, timeoutMs = 950)
                }
                val curAether = aetherPreferenceStore.read()
                if (curAether.enabled) {
                    aetherPreferenceStore.save(
                        curAether.copy(
                            endpoint = "${rot.newIp}:${rot.newPort}",
                            endpointScannedAt = System.currentTimeMillis(),
                        ),
                    )
                } else if (rot.updatedForwardersCount == 0) {
                    // Also start a forwarder on the newly rotated edge for the user's real template if present
                    val tpl = resolveUserRealConfigTemplate()
                    if (tpl != null) {
                        withContext(Dispatchers.IO) {
                            LocalSniSpoofProxy.startDirectTcpSpoofForwarder(
                                remoteHost = rot.newIp,
                                remotePort = rot.newPort,
                                strategy = LocalSniSpoofProxy.currentActiveStrategy,
                            )
                        }
                    }
                }
                refreshLiveStatusText(
                    if (isFarsi) "✅ آی‌پی تانل بدون قطعی از ${rot.previousEndpoint} به ${rot.newEndpoint} (${rot.rttMs}ms) منتقل شد!"
                    else "✅ Seamlessly switched from ${rot.previousEndpoint} to ${rot.newEndpoint} (${rot.rttMs}ms)!",
                )
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )

    val autoHealBtn = makeActionButton(
        label = if (isFarsi) {
            if (LocalSniSpoofProxy.autoHealWatchdogEnabled) "🔄 ۱) پایشگر خودکار ضد-فریز در پس‌زمینه: 🟢 روشن (ضربه برای خاموش)"
            else "🔄 ۱) پایشگر خودکار ضد-فریز در پس‌زمینه: ⚪ خاموش (ضربه برای روشن)"
        } else {
            if (LocalSniSpoofProxy.autoHealWatchdogEnabled) "🔄 1) Background Auto-Heal Watchdog: 🟢 ON (Tap to Disable)"
            else "🔄 1) Background Auto-Heal Watchdog: ⚪ OFF (Tap to Enable)"
        },
    ) {}
    autoHealBtn.setOnClickListener {
        val next = !LocalSniSpoofProxy.autoHealWatchdogEnabled
        LocalSniSpoofProxy.autoHealWatchdogEnabled = next
        LocalSniSpoofProxy.savePersistedPowerSettings(this)
        if (next) LocalSniSpoofProxy.ensureAutoHealWatchdogLoop()
        autoHealBtn.text = if (isFarsi) {
            if (next) "🔄 ۱) پایشگر خودکار ضد-فریز در پس‌زمینه: 🟢 روشن (ضربه برای خاموش)"
            else "🔄 ۱) پایشگر خودکار ضد-فریز در پس‌زمینه: ⚪ خاموش (ضربه برای روشن)"
        } else {
            if (next) "🔄 1) Background Auto-Heal Watchdog: 🟢 ON (Tap to Disable)"
            else "🔄 1) Background Auto-Heal Watchdog: ⚪ OFF (Tap to Enable)"
        }
        refreshLiveStatusText(
            if (isFarsi) {
                if (next) "✅ پایشگر ضد-فریز فعال و ذخیره شد؛ در صورت افت پینگ، آی‌پی خودکار در پس‌زمینه عوض می‌شود."
                else "ℹ️ پایشگر خودکار پس‌زمینه غیرفعال شد."
            } else {
                if (next) "✅ Auto-Heal Watchdog enabled & saved." else "ℹ️ Auto-Heal Watchdog disabled."
            },
        )
    }
    container.addView(autoHealBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Feature 2: 1-Click Smart Split Tunneling presets
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🏦 ۲-الف) عبور مستقیم بانک‌ها، اسنپ، تپسی، دیوار و اپ‌های ایرانی (پیشنهادی)"
            else "🏦 2A) Bypass Iranian Banks, Snapp, Tapsi & Domestic Apps (Recommended)",
        ) {
            activityScope.launch {
                val (count, _) = withContext(Dispatchers.IO) {
                    MlmAndNextGenVpnHub.enableSmartIranBankingAppShield(this@showFourPowerFeaturesCenterDialog)
                }
                refreshLiveStatusText(
                    if (isFarsi) "✅ $count برنامهٔ بانکی و ایرانی از VPN مستثنا شدند (بدون خطای خاموش کردن فیلترشکن)."
                    else "✅ $count Iranian banking & domestic apps now bypass the VPN.",
                )
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                }
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    container.addView(
        makeActionButton(
            label = if (isFarsi) "🎯 ۲-ب) فقط تلگرام، اینستاگرام، یوتیوب، واتس‌اپ و هوش مصنوعی روی VPN"
            else "🎯 2B) Route ONLY Telegram, Instagram, YouTube, WhatsApp & AI via VPN",
        ) {
            activityScope.launch {
                val (count, _) = withContext(Dispatchers.IO) {
                    MlmAndNextGenVpnHub.enableSocialAndForeignOnlySplitTunnel(this@showFourPowerFeaturesCenterDialog)
                }
                refreshLiveStatusText(
                    if (isFarsi) "✅ حالت «فقط برنامه‌های خارجی منتخب ($count برنامه) روی VPN» فعال شد!"
                    else "✅ Enabled VPN-only routing for $count social/foreign apps!",
                )
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                }
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    container.addView(
        makeActionButton(
            label = if (isFarsi) "🌐 ۲-ج) عبور همهٔ برنامه‌های گوشی از تونل VPN (لغو تفکیک)"
            else "🌐 2C) Route All Phone Apps Through VPN (Disable Split Tunnel)",
        ) {
            MlmAndNextGenVpnHub.disableSplitTunnelAllApps(this)
            refreshLiveStatusText(
                if (isFarsi) "✅ همهٔ برنامه‌های گوشی اکنون از تونل VPN عبور می‌کنند."
                else "✅ All apps now route through the VPN tunnel.",
            )
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 3: Gaming & Call Low-Latency Anti-Jitter Booster
    val gamingBtn = makeActionButton(
        label = if (isFarsi) {
            if (LocalSniSpoofProxy.gamingLowLatencyBoosterEnabled) "🎮 ۳) بوستر گیمینگ و تماس ضد-پکت‌لاس: 🟢 روشن (ضربه برای خاموش)"
            else "🎮 ۳) بوستر گیمینگ و تماس ضد-پکت‌لاس (کالاف/پابجی/واتس‌اپ): ⚪ خاموش (فعال‌سازی)"
        } else {
            if (LocalSniSpoofProxy.gamingLowLatencyBoosterEnabled) "🎮 3) Gaming & Call Anti-Jitter Booster: 🟢 ON"
            else "🎮 3) Gaming & Call Anti-Jitter Booster: ⚪ OFF (Tap to Enable)"
        },
    ) {}
    gamingBtn.setOnClickListener {
        val next = !LocalSniSpoofProxy.gamingLowLatencyBoosterEnabled
        LocalSniSpoofProxy.gamingLowLatencyBoosterEnabled = next
        LocalSniSpoofProxy.savePersistedPowerSettings(this)
        if (next) LocalSniSpoofProxy.ensureAutoHealWatchdogLoop()
        gamingBtn.text = if (isFarsi) {
            if (next) "🎮 ۳) بوستر گیمینگ و تماس ضد-پکت‌لاس: 🟢 روشن (ضربه برای خاموش)"
            else "🎮 ۳) بوستر گیمینگ و تماس ضد-پکت‌لاس (کالاف/پابجی/واتس‌اپ): ⚪ خاموش (فعال‌سازی)"
        } else {
            if (next) "🎮 3) Gaming & Call Anti-Jitter Booster: 🟢 ON"
            else "🎮 3) Gaming & Call Anti-Jitter Booster: ⚪ OFF (Tap to Enable)"
        }
        activityScope.launch {
            val metrics = withContext(Dispatchers.IO) {
                LocalSniSpoofProxy.measureLiveJitterAndLatency(samples = 4, timeoutMs = 850)
            }
            val jText = if (metrics.avgRttMs > 0) {
                if (isFarsi) "پینگ میانگین: ${metrics.avgRttMs}ms · نوسان (Jitter): ${metrics.jitterMs}ms · پکت‌لاس: ${metrics.packetLossPercent}%"
                else "Avg Ping: ${metrics.avgRttMs}ms · Jitter: ${metrics.jitterMs}ms · Loss: ${metrics.packetLossPercent}%"
            } else {
                if (isFarsi) "آماده در اولین اتصال" else "Ready on connect"
            }
            refreshLiveStatusText("🎯 $jText")
        }
    }
    container.addView(gamingBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Feature 4A: 1-Click Hotspot / Wi-Fi Share with QR Code
    container.addView(
        makeActionButton(
            label = if (isFarsi) "📲 ۴-الف) اشتراک‌گذاری اینترنت VPN به آیفون، لپ‌تاپ، PS5 و تلویزیون با QR Code"
            else "📲 4A) Share VPN over Wi-Fi / Hotspot to iPhone, PC, PS5 & TV (with QR Code)",
            filled = true,
        ) {
            showEasyHotspotShareWithQrDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 4B: 1-Click Dead Config Cleaner & Sorter
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🧹 ۴-ب) تست موازی، حذف کانفیگ‌های سوخته و مرتب‌سازی بر اساس سریع‌ترین پینگ"
            else "🧹 4B) Parallel Dead-Config Cleaner & Fastest-Ping Sorter (1-Click)",
        ) {
            runOneClickCleanAndSortConfigsDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 5: Real Tunnel Speed & 16KB Upload-Freeze Tester
    container.addView(
        makeActionButton(
            label = if (isFarsi) "⚡ ۵) تست واقعی سرعت دانلود، پینگ و تشخیص قفل آپلود ویس/عکس (۱۶ کیلوبایت)"
            else "⚡ 5) Real Download/Upload Speed & 16KB Upload-Freeze Detector",
        ) {
            refreshLiveStatusText(if (isFarsi) "⏳ در حال تست سرعت دانلود و ارسال بستهٔ آپلود ۱۶ کیلوبایتی…" else "⏳ Probing download speed & 16KB upload burst…")
            activityScope.launch {
                val rep = withContext(Dispatchers.IO) {
                    LocalSniSpoofProxy.probeTunnelSpeedAndUploadFreeze(timeoutMs = 2_000)
                }
                refreshLiveStatusText(if (isFarsi) rep.diagnosisFa else rep.diagnosisEn)
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 6: 1-Click Anti-Sanction & Google Captcha Remover (Psiphon -> WARP Reverse Chain + DoH)
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🔓 ۶) رفع تحریم هوش مصنوعی (Gemini/ChatGPT) + حذف کپچای گوگل (Psiphon -> WARP)"
            else "🔓 6) Unblock AI (Gemini/ChatGPT/Spotify) + Zero Google Captcha (Psiphon -> WARP)",
        ) {
            val cur = aetherPreferenceStore.read()
            aetherPreferenceStore.save(
                cur.copy(
                    enabled = true,
                    mode = AetherMode.MasqueInMasque,
                    masqueTransport = "h2",
                    chainTarget = "psiphon-reverse",
                    psiphonRegion = "US",
                    endpoint = cur.endpoint?.takeIf { it.isNotBlank() } ?: "188.114.98.0:443",
                    innerEndpoint = cur.innerEndpoint?.takeIf { it.isNotBlank() } ?: "162.159.198.235:443",
                    masqueSni = "speed.cloudflare.com",
                    echEnabled = true,
                    endpointScannedAt = System.currentTimeMillis(),
                ),
            )
            dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoH)
            dnsPrivacyPreferenceStore.saveDohUrl("https://cloudflare-dns.com/dns-query")
            orbotEnginePreferenceStore.setEnabled(false)
            renderAdvancedControls()
            refreshLiveStatusText(
                if (isFarsi) "✅ زنجیرهٔ معکوس ضد-تحریم و ضد-کپچا (Psiphon -> WARP US + DoH) فعال و آمادهٔ اتصال شد!"
                else "✅ Reverse Anti-Sanction & Zero-Captcha chain (Psiphon -> WARP US + DoH) enabled!",
            )
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            } else {
                beginConnectFlow(Actions.CONNECT)
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 7: Smart Battery & Data Saver Mode
    val batteryBtn = makeActionButton(
        label = if (isFarsi) {
            if (LocalSniSpoofProxy.batteryAndDataSaverEnabled) "🔋 ۷) حالت ذخیرهٔ هوشمند باتری و اینترنت پس‌زمینه: 🟢 روشن (ضربه برای خاموش)"
            else "🔋 ۷) حالت ذخیرهٔ هوشمند باتری و اینترنت پس‌زمینه: ⚪ خاموش (ضربه برای روشن)"
        } else {
            if (LocalSniSpoofProxy.batteryAndDataSaverEnabled) "🔋 7) Smart Battery & Data Saver: 🟢 ON"
            else "🔋 7) Smart Battery & Data Saver: ⚪ OFF (Tap to Enable)"
        },
    ) {}
    batteryBtn.setOnClickListener {
        val next = !LocalSniSpoofProxy.batteryAndDataSaverEnabled
        LocalSniSpoofProxy.batteryAndDataSaverEnabled = next
        LocalSniSpoofProxy.savePersistedPowerSettings(this)
        batteryBtn.text = if (isFarsi) {
            if (next) "🔋 ۷) حالت ذخیرهٔ هوشمند باتری و اینترنت پس‌زمینه: 🟢 روشن (ضربه برای خاموش)"
            else "🔋 ۷) حالت ذخیرهٔ هوشمند باتری و اینترنت پس‌زمینه: ⚪ خاموش (ضربه برای روشن)"
        } else {
            if (next) "🔋 7) Smart Battery & Data Saver: 🟢 ON"
            else "🔋 7) Smart Battery & Data Saver: ⚪ OFF (Tap to Enable)"
        }
        refreshLiveStatusText(
            if (isFarsi) {
                if (next) "✅ حالت ذخیرهٔ هوشمند باتری و اینترنت پس‌زمینه فعال و ذخیره شد."
                else "ℹ️ حالت ذخیرهٔ باتری غیرفعال شد."
            } else {
                if (next) "✅ Smart Battery & Data Saver enabled & saved."
                else "ℹ️ Smart Battery & Data Saver disabled."
            },
        )
    }
    container.addView(batteryBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    // Feature 8: 1-Click Multi-Subscription Merger into a deduplicated Super-Subscription
    container.addView(
        makeActionButton(
            label = if (isFarsi) "💎 ۸) ترکیب همهٔ اشتراک‌ها و کلیپ‌بورد در یک «سوپر-اشتراک» بدون تکرار"
            else "💎 8) Merge All Subscriptions & Clipboard into 1 Deduplicated Super-Subscription",
        ) {
            runOneClickCleanAndSortConfigsDialog(mergeIntoSuperSubscription = true)
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 9: Oct 2026 Serverless-v52 Dual-Stage Fragment (fragA: 6,98,1 & fragB: 0,104,1 + 114,1 maxSplit=11)
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🔥 ۹) متد جدید Serverless-v52 (فرگمنت ۲ مرحله‌ای fragA همراه اول / fragB ایرانسل)"
            else "🔥 9) New Serverless-v52 Method (2-Stage Fragment fragA: 6,98,1 / fragB: 0,104,1)",
            filled = true,
        ) {
            showServerlessV52MethodDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 10: Oct 2026 MITM-DomainFronting v23 Direct YouTube / Instagram / Reddit Unblocker
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🌐 ۱۰) متد جدید Domain-Fronting v23 (بازگشایی مستقیم یوتیوب، اینستاگرام و رددیت)"
            else "🌐 10) New Domain-Fronting v23 (Direct YouTube, Instagram, WhatsApp & Reddit)",
        ) {
            runDirectCdnDomainFrontingV23Dialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 11: Live 7-Source Verified GitHub Free-Configs Aggregator over SNI-Split TLS
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🎁 ۱۱) ابزار جدید: دریافت کانفیگ‌های رایگان امروز از ۷ مخزن گیت‌هاب (با برش SNI)"
            else "🎁 11) New Tool: Fetch Today's Verified Free Configs from 7 GitHub Sources",
            filled = true,
        ) {
            runFetchSevenGitHubFreeConfigSourcesDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 12: CottenMax v1 (Soar ChaCha20-Poly1305 + Dual UDP/53 & TCP/53 Auto Scanner)
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🧬 ۱۲) راهکار جدید CottenMax v1 (اسکن هم‌زمان UDP/53 + TCP/53 و تانل Soar ضد پکت‌لاس)"
            else "🧬 12) New CottenMax v1 Solution (Dual UDP/53 + TCP/53 Scanner & Soar Tunnel)",
        ) {
            runCottenMaxDualUdpTcpScannerDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 13: Ultimate Whitelist, Multi-CDN & Anti-DPI Super-Scanner
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🏳️ ۱۳) ابر-اسکنر آی‌پی سفید، پلتفرم‌ها و نت ملی (اسکن ضد-DPI + همسایه /24 + تزریق کانفیگ)"
            else "🏳️ 13) Ultimate Whitelist, Multi-CDN & Anti-DPI Super-Scanner (/24 Harvest + Config Injector)",
            filled = true,
        ) {
            showWhitelistSuperScannerDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 14: Universal Omni-Config Translator & Runner (SlipNet v1..v28 + Raw JSON/TOML + 28 Protocols)
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🧬 ۱۴) مترجم و اجرای همه‌کارهٔ کانفیگ‌ها (Cat DNS v1..v28 · تانل DNS · JSON · TOML · ۲۸ پروتکل)"
            else "🧬 14) Universal Omni-Config Hub (Cat DNS v1..v28 · DNS · Raw JSON/TOML · 28+ Protocols)",
            filled = true,
        ) {
            showUniversalOmniConfigHubDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 15: Multi-Config Parallel Speed Fusion & Zero-Drop Autopilot
    container.addView(
        makeActionButton(
            label = if (isFarsi) "⚡ ۱۵) ترکیب موازی چند کانفیگ برای چندبرابر کردن سرعت + خلبان خودکار ضدقطع (Multi-Tunnel Fusion)"
            else "⚡ 15) Multi-Config Parallel Speed Fusion & Zero-Drop Autopilot (Load-Balance + Failover)",
        ) {
            showMultiConfigSpeedFusionDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 16: Live Whitelist TLS Camouflage Cloner
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🕵️ ۱۶) کلون‌ساز زندهٔ اثرانگشت TLS سایت‌های سفید ایران (بانک سپه · شاپرک · بله · آپارات)"
            else "🕵️ 16) Live Whitelist TLS Camouflage Cloner (Sepah · Shaparak · Bale · Aparat)",
        ) {
            showLiveWhitelistTlsClonerDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 17: 1-Tap ISP Protocol Matrix (real probes) + green-only fusion
    container.addView(
        makeActionButton(
            label = if (isFarsi) "📡 ۱۷) رادار یک‌لمسی: کدام پروتکل الان روی خط من باز است؟ (تست واقعی + تانل ترکیبی از سبزها)"
            else "📡 17) 1-Tap ISP Protocol Matrix: which protocol is open on my line? (real probes)",
            filled = true,
        ) {
            showIspProtocolMatrixDialog()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 18: Toolbox (file import · backup · app lock · auto-refresh · engine status · WhiteDNS toolkit)
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🧰 ۱۸) جعبه‌ابزار: فایل کانفیگ، پشتیبان، قفل برنامه، رفرش خودکار، اسکنرهای Cat DNS (ASN · SNI · پروکسی · DNS · کانفیگ‌ساز)"
            else "🧰 18) Toolbox: config file, backup, app lock, auto-refresh, Cat DNS scanners (ASN · SNI · proxy · DNS · Config Maker)",
        ) {
            appTools.showToolsHub()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 19: XHTTP relay on the user's own Vercel account (auto deploy + ready configs)
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🔺 ۱۹) کانفیگ روی Vercel: کانفیگ XHTTP فیلترشده یا سرور شخصی را با یک دکمه از پشت Vercel رد کن"
            else "🔺 19) Config on Vercel: put a filtered XHTTP config or your VPS behind Vercel with one tap",
        ) {
            vercelRelay.show()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Feature 20: the whole Cat panel (VLESS/Trojan server included) on the user's own Vercel account
    container.addView(
        makeActionButton(
            label = if (isFarsi) "🐱 ۲۰) پنل Cat روی Vercel: سرور ندارم؛ فقط با توکن Vercel پنل و کانفیگ آماده بساز"
            else "🐱 20) Cat panel on Vercel: no server needed — just a Vercel token for a panel and ready configs",
        ) {
            vercelRelay.showPanel()
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🚀 مرکز قابلیت‌ها و متدهای انحصاری ۲۰۲۶ (Cat DNS · تجمیع سرعت · کلون سفید · اسکنر)"
            else "🚀 2026 Exclusive Power Features Hub (Cat DNS · Speed Fusion · TLS Cloner · Scanner)",
        )
        .setView(rootScroll)
        .setPositiveButton(android.R.string.ok, null)
        .create()
        .showCatClientDialog()
}

/**
 * 🧬 Universal Omni-Config Translator & Runner Dialog:
 * Decodes, inspects, and runs ANY config format directly inside the app:
 * - Official SlipNet v1..v28+ pipe-delimited profiles (`slipnet://`, `slipnet-enc://`, `slipnet-bundle-enc://`)
 * - All DNS tunnels (`dnst://`, `dnstt://`, `vaydns://`, `noizdns://`, `slipstream://`, `cottendns://`, `stormdns://`, `masterdns://`, `soar://`, `qstunnel://`, `qqtunnel://`)
 * - Raw Tor/SlipNet `dnstt 192.0.2.4:1 ...` bridge lines
 * - Paqet / PaqetNG (`paqet://`, `paqetng://`, `patth://`, `patthng://`), Firefox VPN (`fenix://`)
 * - TUIC v5 (`tuic://`), Juicity (`juicity://`), ShadowsocksR (`ssr://`), SSH (`ssh://`), Snell (`snell://`), Mieru (`mieru://`), Naive (`naive+https://`)
 * - WARP / Psiphon / Tor / Gool (`warp://`, `psiphon://`, `tor://`, `gool://`, `aether://`, `masque://`)
 * - Raw Xray / v2rayNG / Sing-box JSON configs (`{"outbounds": [...]}`) and CottenMax / MasterDNS TOML files
 */
internal fun MainActivity.showUniversalOmniConfigHubDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val scroll = android.widget.ScrollView(this).apply { isFillViewport = true }
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(10))
    }
    scroll.addView(root)

    val introView = TextView(this).apply {
        textSize = 12f
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(0f, 1.22f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(withAlpha(TEAL, if (palette.isDark) 32 else 20))
            setStroke(dp(1), TEAL)
        }
        text = if (isFarsi) {
            "🧬 مترجم و اجرای همه‌کارهٔ کانفیگ‌ها (پشتیبانی از ۲۸+ فرمت):\n" +
                "• تمام لینک‌های Cat DNS (نسخه‌های v1 تا v28 پایپ‌دار، JSON و رمزنگاری‌شده slipnet-enc://)\n" +
                "• تانل‌های DNS (dnst://, dnstt, VayDNS, NoizDNS, Slipstream, CottenMax, StormDNS, MasterDNS, QQ-Tunnel)\n" +
                "• پروتکل‌های TUIC v5, SSR, SSH, NaiveProxy, Snell, Mieru, PaqetNG, Cat Fox VPN, WARP, Psiphon\n" +
                "• فایل‌های JSON خام Xray / Sing-box و فایل‌های TOML خام CottenMax / MasterDNS"
        } else {
            "🧬 Universal Omni-Config Translator & Runner (28+ Formats):\n" +
                "• Official Cat DNS v1..v28 pipe-delimited, JSON & slipnet-enc:// profiles\n" +
                "• All DNS Tunnels (dnst://, dnstt, VayDNS, NoizDNS, Slipstream, CottenMax, StormDNS, QQ-Tunnel)\n" +
                "• TUIC v5, SSR, SSH, Naive, Snell, Mieru, PaqetNG, Cat Fox VPN, WARP, Psiphon\n" +
                "• Raw Xray / Sing-box JSON files & CottenMax / MasterDNS TOML configs"
        }
    }
    root.addView(introView, LinearLayout.LayoutParams(-1, -2))

    val inputEdit = TextInputEditText(this).apply {
        minLines = 5
        maxLines = 10
        gravity = Gravity.TOP or Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textSize = 11.5f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = "slipnet://... | vless://... | tuic://... | dnst://... | {\"outbounds\":[...]} | DOMAINS = [...]"
    }
    root.addView(inputEdit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    val reportView = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(0f, 1.2f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = glassSurfaceDrawable(radiusDp = 12)
        text = if (isFarsi) "ℹ️ کانفیگ‌های خود را در کادر بالا وارد کنید یا دکمهٔ «خواندن از کلیپ‌بورد» را بزنید."
        else "ℹ️ Paste your configs above or tap 'Read from Clipboard'."
    }

    var inspectGeneration = 0
    fun renderInspection(inspection: SubConvConverter.UniversalConfigInspection) {
        if (inspection.totalCount == 0) {
            reportView.text = if (isFarsi) {
                "⚠️ کانفیگ معتبری در متن پیدا نشد. مطمئن شوید لینک کامل یا JSON/TOML معتبر است."
            } else {
                "⚠️ No valid configs found in input."
            }
            return
        }
        val summaryBadges = inspection.formatCounts.entries.joinToString(" · ") { "${it.value}× ${it.key}" }
        reportView.text = buildString {
            if (isFarsi) {
                appendLine("✅ ${inspection.totalCount} کانفیگ با موفقیت دیکود و آمادهٔ اتصال شد:")
                appendLine("📊 فرمت‌ها: $summaryBadges")
                appendLine("────────────────────────")
            } else {
                appendLine("✅ Decoded ${inspection.totalCount} configs ready to connect:")
                appendLine("📊 Formats: $summaryBadges")
                appendLine("────────────────────────")
            }
            inspection.items.take(15).forEach { item ->
                appendLine("${item.index}. [${item.detectedFormat}] ${item.proxyName} -> ${item.serverSummary}")
            }
        }.trimEnd()
    }
    // beta217: decoding runs off the main thread (a big paste froze the box); only the newest result is shown.
    fun inspectAndRender(raw: String) {
        val generation = ++inspectGeneration
        val clean = unescapeHtmlEntities(raw).trim()
        if (clean.isBlank()) {
            reportView.text = if (isFarsi) "⚠️ متنی وارد نشده است." else "⚠️ Input is empty."
            return
        }
        activityScope.launch {
            val inspection = withContext(Dispatchers.Default) {
                runCatching { SubConvConverter.inspectUniversalConfigInput(clean) }.getOrNull()
            }
            if (generation != inspectGeneration) return@launch
            if (inspection != null) renderInspection(inspection)
        }
    }

    val pasteBtn = MaterialButton(this).apply {
        text = if (isFarsi) "📋 خواندن و کالبدشکافی خودکار از کلیپ‌بورد" else "📋 Auto-Read & Decode from Clipboard"
        isAllCaps = false
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 36))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(TEAL)
        setTextColor(TEAL)
        setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this@showUniversalOmniConfigHubDialog)?.toString().orEmpty()
            if (clip.isNotBlank()) {
                inputEdit.setText(clip)
                inspectAndRender(clip)
            } else {
                Toast.makeText(
                    this@showUniversalOmniConfigHubDialog,
                    if (isFarsi) "⚠️ کلیپ‌بورد خالی است" else "⚠️ Clipboard is empty",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    root.addView(pasteBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    root.addView(reportView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    inputEdit.addTextChangedListener(DebouncedTextWatcher(inputEdit) { text -> inspectAndRender(text) })

    // Auto-populate if clipboard already has a config
    runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (clip.isNotBlank() && SubConvConverter.inspectUniversalConfigInput(clip).totalCount > 0) {
            inputEdit.setText(clip)
            inspectAndRender(clip)
        }
    }

    val importAndConnectBtn = MaterialButton(this).apply {
        text = if (isFarsi) "🚀 افزودن به اپ و اتصال فوری (پشتیبانی از همهٔ فرمت‌ها)" else "🚀 Import to App & Connect Immediately"
        isAllCaps = false
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        minHeight = dp(46)
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(BACKGROUND)
        setOnClickListener {
            val raw = unescapeHtmlEntities(inputEdit.text?.toString().orEmpty()).trim()
            val inspection = SubConvConverter.inspectUniversalConfigInput(raw)
            val payloadToSave = inspection.normalizedSubscriptionText.ifBlank { raw }
            if (payloadToSave.isBlank()) {
                Toast.makeText(
                    this@showUniversalOmniConfigHubDialog,
                    if (isFarsi) "⚠️ ابتدا کانفیگ را وارد کنید" else "⚠️ Please paste a config first",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            val firstTitle = inspection.items.firstOrNull()?.proxyName?.take(28).orEmpty()
            val subTitle = if (inspection.totalCount == 1 && firstTitle.isNotBlank()) {
                "🧬 $firstTitle"
            } else {
                if (isFarsi) "🧬 اشتراک همه‌کاره (${inspection.totalCount} کانفیگ)" else "🧬 Omni-Config (${inspection.totalCount} nodes)"
            }
            activityScope.launch {
                val saved = runCatching {
                    withContext(Dispatchers.IO) {
                        val existing = userSubscriptionManager.list().firstOrNull { it.name == subTitle }
                        if (existing != null) userSubscriptionManager.update(existing.id, subTitle, payloadToSave)
                        else userSubscriptionManager.add(subTitle, payloadToSave)
                    }
                }.getOrNull()
                if (saved == null) {
                    Toast.makeText(
                        this@showUniversalOmniConfigHubDialog,
                        if (isFarsi) "⚠️ خطا در تبدیل کانفیگ — لطفاً فرمت ورودی را بررسی کنید" else "⚠️ Failed to import config",
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
                Toast.makeText(
                    this@showUniversalOmniConfigHubDialog,
                    if (isFarsi) "✅ ${saved.connectionCount} کانفیگ وارد اپ شد و در حال اتصال است!"
                    else "✅ Imported ${saved.connectionCount} configs & connecting!",
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
    }
    root.addView(importAndConnectBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🧬 مترجم و اجرای همه‌کارهٔ کانفیگ‌ها (Cat DNS + 28 پروتکل)"
            else "🧬 Universal Omni-Config Translator & Runner",
        )
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .create()
        .showCatClientDialog()
}

/**
 * 📡 1-Tap ISP Protocol Matrix: runs REAL probes (TLS / TCP / SSH banner / QUIC version
 * negotiation / DNS through the tunnel resolver / DoH / IPv6) against the user's line and
 * against every one of the user's own configs, then shows a green/red table, a plain-language
 * verdict and lets the user build a fused tunnel from ONLY the configs that really passed.
 */
internal fun MainActivity.showIspProtocolMatrixDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val clipText = readClipboardConfigText()
    val progress = MaterialAlertDialogBuilder(this)
        .setTitle(if (isFarsi) "📡 در حال تست واقعی خط شما…" else "📡 Probing your line…")
        .setMessage(
            if (isFarsi) "۹ تست پایه (داخلی، کلادفلر، گوگل، QUIC، DNS روی UDP/TCP، DoH، IPv6) + تست جداگانهٔ هر کانفیگ خودت (حداکثر ${IspProtocolMatrix.MAX_CONFIGS}).\nمعمولاً ۵ تا ۱۵ ثانیه طول می‌کشد."
            else "9 baseline probes + a real probe of each of your configs (max ${IspProtocolMatrix.MAX_CONFIGS}). Usually 5–15 s.",
        )
        .setCancelable(false)
        .create()
    progress.showCatClientDialog()
    activityScope.launch {
        val report = withContext(Dispatchers.IO) {
            runCatching { IspProtocolMatrix.run(collectFusionRawTexts(clipText)) }.getOrNull()
        }
        runCatching { progress.dismiss() }
        if (report == null) {
            Toast.makeText(
                this@showIspProtocolMatrixDialog,
                if (isFarsi) "⚠️ تست انجام نشد؛ دوباره امتحان کن." else "⚠️ Probe failed — try again.",
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        fun mark(ok: Boolean?) = when (ok) { true -> "🟢"; false -> "🔴"; null -> "⚪" }
        val msg = buildString {
            appendLine(if (isFarsi) "🧭 نتیجه به زبان ساده:" else "🧭 Plain-language verdict:")
            report.verdict(isFarsi).forEach { appendLine("• $it") }
            appendLine()
            appendLine(if (isFarsi) "🌐 تست‌های پایهٔ خط (${report.durationMs / 1000.0}s):" else "🌐 Line baseline (${report.durationMs / 1000.0}s):")
            report.baseline.forEach { row ->
                val ms = row.latencyMs?.let { " · ${it}ms" }.orEmpty()
                appendLine("${mark(row.ok)} ${if (isFarsi) row.labelFa else row.labelEn}$ms")
            }
            val fam = report.familyStatus()
            if (fam.isNotEmpty()) {
                appendLine()
                appendLine(if (isFarsi) "🧩 خانواده‌های پروتکل در کانفیگ‌های خودت (سبز/قرمز/نامعلوم):" else "🧩 Protocol families in your configs (green/red/unknown):")
                fam.forEach { (f, t) ->
                    val icon = when {
                        t.first > 0 -> "🟢"
                        t.second > 0 && t.third == 0 -> "🔴"
                        else -> "⚪"
                    }
                    appendLine("$icon ${if (isFarsi) f.fa else f.en}: ${t.first}/${t.second}/${t.third}")
                }
                appendLine()
                appendLine(if (isFarsi) "📋 تک‌تک کانفیگ‌ها:" else "📋 Each config:")
                report.configs.sortedWith(compareBy({ it.ok != true }, { it.latencyMs ?: Long.MAX_VALUE })).forEach { row ->
                    val ms = row.latencyMs?.let { " · ${it}ms" }.orEmpty()
                    val why = when (row.ok) {
                        null -> if (isFarsi) " (فقط هنگام اتصال قابل‌سنجش)" else " (verifiable only on connect)"
                        false -> " (${row.detail})"
                        true -> ""
                    }
                    appendLine("${mark(row.ok)} ${row.name.take(34)}$ms$why")
                }
            } else {
                appendLine()
                appendLine(
                    if (isFarsi) "ℹ️ هنوز کانفیگی در اپ یا کلیپ‌بورد نیست؛ فقط خط اینترنت تست شد."
                    else "ℹ️ No configs in the app or clipboard yet — only the line was tested.",
                )
            }
            appendLine()
            append(if (isFarsi) "⚪ = بدون اتصال کامل قابل‌سنجش نیست (WireGuard/WARP، Paqet، Cat Fox VPN، Cat Warp) — رنگ سبز الکی نشان داده نمی‌شود." else "⚪ = cannot be verified without a full connection — no fake greens.")
        }
        val green = report.greenConfigs
        val builder = MaterialAlertDialogBuilder(this@showIspProtocolMatrixDialog)
            .setTitle(if (isFarsi) "📡 رادار پروتکل‌های باز روی خط من" else "📡 Open protocols on my line")
            .setMessage(msg.trimEnd())
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "🔁 تست دوباره" else "🔁 Re-test") { _, _ -> showIspProtocolMatrixDialog() }
        if (green.isNotEmpty()) {
            builder.setPositiveButton(
                if (isFarsi) "⚡ تانل ترکیبی فقط از ${green.size} کانفیگ سبز + اتصال" else "⚡ Fuse ${green.size} green configs & connect",
            ) { _, _ ->
                activityScope.launch {
                    val bundle = withContext(Dispatchers.IO) {
                        val greenRaw = green.joinToString("\n") { it.shareUri }.takeIf { green.all { g -> g.shareUri.isNotBlank() } }
                        if (greenRaw != null) {
                            LocalSniSpoofProxy.buildFusedMultiTunnelSubscription(listOf(greenRaw), maxProxies = 12)
                        } else {
                            val proxies = green.mapNotNull { runCatching { org.json.JSONObject(it.proxyJson) }.getOrNull() }
                            runCatching {
                                val yaml = MihomoLinkConfigBuilder.build(proxies)
                                LocalSniSpoofProxy.buildFusedMultiTunnelSubscription(listOf(yaml), maxProxies = 12)
                            }.getOrNull()
                        }
                    }
                    if (bundle == null || bundle.totalProxiesFused == 0) {
                        Toast.makeText(
                            this@showIspProtocolMatrixDialog,
                            if (isFarsi) "⚠️ ساخت تانل از کانفیگ‌های سبز ممکن نشد." else "⚠️ Could not build a tunnel from green configs.",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    activateFusedBundle(bundle, isFarsi, greenOnly = true)
                }
            }
        }
        builder.create().showCatClientDialog()
    }
}

/**
 * 🕵️ Live Whitelist TLS Camouflage Cloner Dialog:
 * Probes real Iranian & international whitelist domains (`ebanksepah.ir`, `shaparak.ir`,
 * `bale.ai`, `aparat.com`, `digikala.com`, `pypi.org`, `github.githubassets.com`) live on
 * the user's ISP, extracts the X.509 certificate Subject CN, Issuer, SHA-256 pin & TLS
 * cipher suite, and clones that exact TLS identity onto the user's VLESS/Trojan configs.
 */
internal fun MainActivity.showLiveWhitelistTlsClonerDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val targets = LocalSniSpoofProxy.LIVE_CLONE_WHITELIST_TARGETS
    val labels = targets.map { it.second }.toTypedArray()

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🕵️ کلون‌ساز زندهٔ اثرانگشت TLS سایت‌های سفید"
            else "🕵️ Live Whitelist TLS Camouflage Cloner",
        )
        .setItems(labels) { _, which ->
            val pickedHost = targets[which].first
            Toast.makeText(
                this,
                if (isFarsi) "⏳ در حال استخراج زندهٔ گواهی و اثرانگشت TLS از $pickedHost…"
                else "⏳ Extracting live TLS certificate & fingerprint from $pickedHost…",
                Toast.LENGTH_SHORT,
            ).show()
            activityScope.launch {
                val report = withContext(Dispatchers.IO) {
                    LocalSniSpoofProxy.cloneLiveWhitelistTlsProfile(pickedHost, timeoutMs = 2600)
                }
                val rawSubs = userSubscriptionManager.list().map { it.input }.toMutableList()
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(this@showLiveWhitelistTlsClonerDialog)?.toString()?.trim().orEmpty()
                    if (clip.isNotBlank()) rawSubs.add(0, clip)
                }
                val clonedLinks = withContext(Dispatchers.IO) {
                    LocalSniSpoofProxy.buildClonedWhitelistCamouflageConfigs(rawSubs, report)
                }
                val detailMsg = if (isFarsi) {
                    buildString {
                        appendLine(report.statusSummaryFa)
                        appendLine("• دامنه هدف سفید: ${report.targetHost}")
                        appendLine("• نسخه TLS و سایفر: ${report.tlsProtocol} (${report.cipherSuite})")
                        appendLine("• صادرکننده گواهی: ${report.issuerCn}")
                        if (report.sha256PinHex.isNotBlank()) {
                            appendLine("• اثرانگشت SHA-256 گواهی: ${report.sha256PinHex.take(24)}…")
                        }
                        appendLine("• کانفیگ‌های آمادهٔ استتار: ${clonedLinks.size} عدد (مجهز به Decoy SNI + Serverless-v52)")
                    }
                } else {
                    buildString {
                        appendLine(report.statusSummaryFa)
                        appendLine("• Target Whitelist Host: ${report.targetHost}")
                        appendLine("• TLS & Cipher: ${report.tlsProtocol} (${report.cipherSuite})")
                        appendLine("• Certificate Issuer: ${report.issuerCn}")
                        appendLine("• Camouflaged Configs Ready: ${clonedLinks.size}")
                    }
                }
                MaterialAlertDialogBuilder(this@showLiveWhitelistTlsClonerDialog)
                    .setTitle("🕵️ ${report.targetHost}")
                    .setMessage(detailMsg.trimEnd())
                    .setPositiveButton(
                        if (isFarsi) "🚀 اعمال استتار روی کانفیگ‌ها و اتصال" else "🚀 Apply Camouflage & Connect",
                    ) { _, _ ->
                        if (clonedLinks.isEmpty()) {
                            LocalSniSpoofProxy.updateDecoySni(report.targetHost)
                            Toast.makeText(
                                this@showLiveWhitelistTlsClonerDialog,
                                if (isFarsi) "✅ دامنهٔ استتار ${report.targetHost} روی موتور ضد-DPI فعال شد!"
                                else "✅ Set ${report.targetHost} as active Anti-DPI decoy!",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@setPositiveButton
                        }
                        activityScope.launch {
                            val subTitle = if (isFarsi) "🕵️ استتار سفید (${report.targetHost})" else "🕵️ Cloned (${report.targetHost})"
                            val joined = clonedLinks.joinToString("\n")
                            val saved = withContext(Dispatchers.IO) {
                                val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("🕵️") }
                                if (existing != null) userSubscriptionManager.update(existing.id, subTitle, joined)
                                else userSubscriptionManager.add(subTitle, joined)
                            }
                            userSubscriptionManager.select(saved.id)
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
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .showCatClientDialog()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.showServerlessV52MethodDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val options = if (isFarsi) {
        arrayOf(
            "⚡ ۱) پروفایل Serverless-v52-fragA (مخصوص همراه اول و مخابرات: tlshello 6,98,1 + پکت 114,1 maxSplit=11)",
            "⚡ ۲) پروفایل Serverless-v52-fragB (مخصوص ایرانسل و رایتل: tlshello 0,104,1 + پکت 114,1 maxSplit=11)",
            "📋 ۳) کپی کانفیگ کامل JSON متد Serverless-v52 برای استفاده در v2rayNG",
        )
    } else {
        arrayOf(
            "⚡ 1) Serverless-v52-fragA Profile (MCI/TCI: tlshello 6,98,1 + packet 114,1 maxSplit=11)",
            "⚡ 2) Serverless-v52-fragB Profile (Irancell/Rightel: tlshello 0,104,1 + packet 114,1 maxSplit=11)",
            "📋 3) Copy Full Serverless-v52 Xray JSON Config to Clipboard",
        )
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🔥 متد جدید Serverless-v52 (فرگمنت ۲ مرحله‌ای + HappyEyeballs IPv6)"
            else "🔥 New Serverless-v52 Dual-Stage Fragment Method",
        )
        .setItems(options) { _, which ->
            when (which) {
                0, 1 -> {
                    val variant = if (which == 0) "fragA" else "fragB"
                    val strategy = if (which == 0) {
                        LocalSniSpoofProxy.SpoofStrategy.FINALMASK_HEADER_CUSTOM
                    } else {
                        LocalSniSpoofProxy.SpoofStrategy.WRONG_TIMESTAMP_PAWS
                    }
                    activityScope.launch {
                        val tpl = resolveUserRealConfigTemplate()
                        withContext(Dispatchers.IO) {
                            LocalSniSpoofProxy.startSpoofSocksProxy(strategy = strategy, decoySni = "challenges.cloudflare.com")
                        }
                        if (tpl != null) {
                            val repaired = withContext(Dispatchers.IO) {
                                LocalSniSpoofProxy.buildRepairedConfigsForRealTemplate(tpl, strategy = strategy)
                            }
                            val title = if (isFarsi) "🔥 کانفیگ مجهز به Serverless-v52-$variant" else "🔥 Serverless-v52-$variant Config"
                            val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                            val saved = withContext(Dispatchers.IO) {
                                if (existing != null) userSubscriptionManager.update(existing.id, title, repaired.joinToString("\n"))
                                else userSubscriptionManager.add(title, repaired.joinToString("\n"))
                            }
                            userSubscriptionManager.select(saved.id)
                            val curAether = aetherPreferenceStore.read()
                            if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                            renderSubscriptions()
                            onSubscriptionSelected()
                        } else {
                            val curAether = aetherPreferenceStore.read()
                            aetherPreferenceStore.save(
                                curAether.copy(
                                    enabled = true,
                                    mode = AetherMode.MasqueInMasque,
                                    masqueTransport = "h2",
                                    fragmentEnabled = true,
                                    fragmentSize = if (which == 0) "6-98" else "1-104",
                                    fragmentDelay = "1-2",
                                    masqueSni = "challenges.cloudflare.com",
                                    endpointScannedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        renderAdvancedControls()
                        openScreen(SCREEN_HOME)
                        if (currentVpnServiceState == VpnState.Started) {
                            reconnectForConnectionOptionChange()
                        } else {
                            beginConnectFlow(Actions.CONNECT)
                        }
                    }
                }
                2 -> {
                    val json = LocalSniSpoofProxy.buildServerlessV52ConfigJson("fragA")
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Serverless-v52-fragA.json", json))
                    Toast.makeText(
                        this,
                        if (isFarsi) "✅ کانفیگ کامل Serverless-v52-fragA در کلیپ‌بورد کپی شد"
                        else "✅ Copied Serverless-v52-fragA JSON to clipboard",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.runFetchSevenGitHubFreeConfigSourcesDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "🎁 در حال دریافت کانفیگ‌های رایگان امروز از ۷ مخزن گیت‌هاب (از دلِ برش SNI) و تست پینگ موازی…"
        else "🎁 Fetching today's free configs from 7 GitHub repos over SNI-Split TLS & probing latency…",
        Toast.LENGTH_LONG,
    ).show()

    activityScope.launch {
        val report = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.fetchAndRankLiveSevenGitHubFreeSources(maxNodesToProbe = 55, timeoutMs = 1_000)
        }
        if (report.sortedAliveLinks.isEmpty()) {
            Toast.makeText(
                this@runFetchSevenGitHubFreeConfigSourcesDialog,
                if (isFarsi) "ℹ️ اتصال مستقیم به گیت‌هاب محدود است؛ در حال اتصال با استخر داخلی و Cat Warp…"
                else "ℹ️ GitHub direct fetch restricted; connecting via built-in pool…",
                Toast.LENGTH_SHORT,
            ).show()
            showRecommendedOneClickConnectWizardDialog()
            return@launch
        }
        val msg = if (isFarsi) {
            buildString {
                appendLine("✅ دریافت و تست موازی از ۷ مخزن گیت‌هاب کامل شد!")
                appendLine()
                appendLine("• کانفیگ‌های بررسی‌شده: ${report.totalChecked}")
                appendLine("• کانفیگ‌های سالم و فعال روی خط شما: ${report.aliveCount}")
                appendLine("• سریع‌ترین پینگ: ${report.fastestLatencyMs} میلی‌ثانیه")
                appendLine()
                appendLine("📋 ۵ سرور برتر امروز:")
                report.sortedAliveLinks.take(5).forEachIndexed { idx, uri ->
                    val remark = uri.substringAfter('#', "Node-${idx + 1}").let {
                        runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                    }
                    appendLine("${idx + 1}. $remark")
                }
            }
        } else {
            buildString {
                appendLine("✅ Fetched & Ranked from 7 GitHub Free Config Sources!")
                appendLine("• Checked: ${report.totalChecked} | Alive: ${report.aliveCount} | Fastest: ${report.fastestLatencyMs}ms")
            }
        }
        MaterialAlertDialogBuilder(this@runFetchSevenGitHubFreeConfigSourcesDialog)
            .setTitle(
                if (isFarsi) "🎁 گلچین کانفیگ‌های رایگان امروز (۷ مخزن گیت‌هاب)"
                else "🎁 Today's Verified Free Configs (7 GitHub Sources)",
            )
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (isFarsi) "🚀 ذخیره در اشتراک و اتصال فوری"
                else "🚀 Save Subscription & Connect Now",
            ) { _, _ ->
                activityScope.launch {
                    val subTitle = if (isFarsi) "🎁 گلچین رایگان روزانه گیت‌هاب (${report.fastestLatencyMs}ms)" else "🎁 GitHub Daily Verified Free (${report.fastestLatencyMs}ms)"
                    val rawJoined = report.sortedAliveLinks.joinToString("\n")
                    val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("🎁") }
                    val saved = withContext(Dispatchers.IO) {
                        if (existing != null) userSubscriptionManager.update(existing.id, subTitle, rawJoined)
                        else userSubscriptionManager.add(subTitle, rawJoined)
                    }
                    userSubscriptionManager.select(saved.id)
                    val curAether = aetherPreferenceStore.read()
                    if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                    orbotEnginePreferenceStore.setEnabled(false)
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
            .create()
            .showCatClientDialog()
    }
}

/**
 * 1-Click Hotspot / Wi-Fi Proxy Share with live QR Code (`HTTP CONNECT` + `SOCKS5` dual-protocol server):
 * Lets iPhones, Windows/Mac laptops, PlayStation/Xbox consoles, and Smart TVs on the same Wi-Fi or Hotspot
 * share the phone's VPN internet without installing any app.
 */
internal fun MainActivity.showEasyHotspotShareWithQrDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    activityScope.launch {
        val (port, lanIps) = withContext(Dispatchers.IO) {
            val p = LocalSniSpoofProxy.startLanHotspotShareProxy(LocalSniSpoofProxy.DEFAULT_LAN_HOTSPOT_PORT)
            val ips = LocalSniSpoofProxy.detectDeviceLanIpAddresses()
            p to ips
        }
        val primaryIp = lanIps.firstOrNull() ?: "192.168.43.1"
        val proxyAddress = "$primaryIp:$port"
        val allIpsText = lanIps.joinToString(" ، ") { "$it:$port" }
        val qrPayload = "socks5://$primaryIp:$port#CatClient-Hotspot-VPN"
        val qrBitmap = withContext(Dispatchers.Default) {
            QrCodes.bitmap(qrPayload, sizePx = dp(210))
        }

        val box = LinearLayout(this@showEasyHotspotShareWithQrDialog).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }

        if (qrBitmap != null) {
            val qrView = ImageView(this@showEasyHotspotShareWithQrDialog).apply {
                setImageBitmap(qrBitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            box.addView(qrView, LinearLayout.LayoutParams(dp(200), dp(200)).apply { bottomMargin = dp(10) })
        }

        val infoView = TextView(this@showEasyHotspotShareWithQrDialog).apply {
            textSize = 13f
            setTextColor(TEXT_PRIMARY)
            setLineSpacing(0f, 1.25f)
            text = if (isFarsi) {
                buildString {
                    appendLine("🟢 پروکسی دوگانهٔ هات‌اسپات و وای‌فای فعال شد!")
                    appendLine()
                    appendLine("📌 آدرس سرور (IP):  $primaryIp")
                    appendLine("📌 پورت (Port):      $port")
                    if (lanIps.size > 1) {
                        appendLine("📌 سایر آدرس‌های شبکه شما: $allIpsText")
                    }
                    appendLine()
                    appendLine("💡 چطور روی دستگاه‌های دیگر وصل شوم؟ (بدون نصب هیچ برنامه‌ای):")
                    appendLine("• 🍎 آیفون و آیپد: وارد Settings -> Wi-Fi شوید، روی ⓘ جلوی وای‌فای بزنید، پایین صفحه Configure Proxy را روی Manual بگذارید و Server = $primaryIp و Port = $port را وارد کنید.")
                    appendLine("• 💻 ویندوز و مک: در تنظیمات Proxy سیستم، آدرس $primaryIp و پورت $port را وارد کنید.")
                    appendLine("• 🎮 پلی‌استیشن / تلویزیون / تلگرام: هم از پروکسی HTTP و هم از پروکسی SOCKS5 روی همین آدرس $proxyAddress پشتیبانی می‌کند!")
                }
            } else {
                buildString {
                    appendLine("🟢 Dual-Protocol (HTTP + SOCKS5) Wi-Fi/Hotspot Proxy is LIVE!")
                    appendLine()
                    appendLine("📌 Server IP:   $primaryIp")
                    appendLine("📌 Port:        $port")
                    appendLine()
                    appendLine("• iPhone / iPad: Settings -> Wi-Fi -> (i) -> Configure Proxy -> Manual -> Server: $primaryIp, Port: $port")
                    appendLine("• Windows / Mac / PS5 / Telegram: Supports both HTTP Proxy and SOCKS5 on $proxyAddress.")
                }
            }
        }
        box.addView(infoView, LinearLayout.LayoutParams(-1, -2))

        MaterialAlertDialogBuilder(this@showEasyHotspotShareWithQrDialog)
            .setTitle(
                if (isFarsi) "📲 اشتراک‌گذاری اینترنت VPN با بارکد QR (هات‌اسپات و وای‌فای)"
                else "📲 Share VPN over Wi-Fi / Hotspot with QR Code",
            )
            .setView(box)
            .setNeutralButton(
                if (isFarsi) "⏹ توقف اشتراک‌گذاری" else "⏹ Stop Proxy",
            ) { _, _ ->
                LocalSniSpoofProxy.stopLanHotspotShareProxy()
                Toast.makeText(
                    this@showEasyHotspotShareWithQrDialog,
                    if (isFarsi) "پروکسی اشتراک‌گذاری هات‌اسپات متوقف شد" else "Hotspot proxy stopped",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(
                if (isFarsi) "📋 کپی آی‌پی و پورت" else "📋 Copy IP:Port",
            ) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Hotspot Proxy", proxyAddress))
                Toast.makeText(
                    this@showEasyHotspotShareWithQrDialog,
                    if (isFarsi) "✅ آدرس $proxyAddress کپی شد" else "✅ Copied $proxyAddress",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .showCatClientDialog()
    }
}

/**
 * 1-Click Parallel Dead-Config Cleaner & Fastest-Ping Sorter:
 * Tests all configs in the user's current subscription (plus clipboard if present),
 * removes dead/timed-out servers, auto-repairs blocked Cloudflare configs via Multi-Chunk SNI Split,
 * sorts by real ping (`ms`), and saves + connects immediately.
 */
internal fun MainActivity.runOneClickCleanAndSortConfigsDialog(mergeIntoSuperSubscription: Boolean = false) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "🧹 در حال تست موازی کانفیگ‌ها، حذف سرورهای سوخته و مرتب‌سازی بر اساس سریع‌ترین پینگ…"
        else "🧹 Probing configs in parallel, removing dead servers & sorting by fastest ping…",
        Toast.LENGTH_SHORT,
    ).show()

    activityScope.launch {
        val rawSources = mutableListOf<String>()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(this@runOneClickCleanAndSortConfigsDialog)?.toString().orEmpty().trim()
        if (clipText.isNotBlank()) rawSources += clipText
        val selectedId = userSubscriptionManager.selectedId()
        val subs = withContext(Dispatchers.IO) { userSubscriptionManager.list() }
        val activeSub = subs.firstOrNull { it.id == selectedId } ?: subs.firstOrNull()
        // Parsing every cached subscription on the UI thread froze the app here.
        rawSources += withContext(Dispatchers.IO) {
            buildList {
                for (sub in subs) {
                    add(sub.input)
                    val snap = runCatching { userSubscriptionManager.cachedSnapshot(sub.id) }.getOrNull()
                    snap?.catalog?.profiles?.forEach { p ->
                        val link = p.shareLink?.trim().orEmpty()
                        if (link.isNotEmpty()) add(link)
                    }
                }
            }
        }

        val report = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.cleanAndSortRawConfigLines(rawSources, timeoutMs = 1_100)
        }

        if (report.sortedAliveLinks.isEmpty()) {
            Toast.makeText(
                this@runOneClickCleanAndSortConfigsDialog,
                if (isFarsi) "⚠️ هیچ کانفیگ شخصی در اشتراک یا کلیپ‌بورد یافت نشد؛ در حال اتصال با موتور پیشنهادی…"
                else "⚠️ No personal configs found; launching recommended connection wizard…",
                Toast.LENGTH_LONG,
            ).show()
            showRecommendedOneClickConnectWizardDialog()
            return@launch
        }

        val summary = if (isFarsi) {
            buildString {
                appendLine("✅ پالایش و مرتب‌سازی کانفیگ‌ها کامل شد!")
                appendLine()
                appendLine("• کل کانفیگ‌های بررسی‌شده: ${report.totalChecked}")
                appendLine("• کانفیگ‌های سالم و فعال: ${report.aliveCount}")
                appendLine("• کانفیگ‌های سوختهٔ حذف‌شده: ${report.deadRemovedCount}")
                appendLine("• سریع‌ترین پینگ ثبت‌شده: ${if (report.fastestLatencyMs > 0) "${report.fastestLatencyMs} میلی‌ثانیه" else "آماده"}")
                appendLine()
                appendLine("📋 ۵ کانفیگ برتر آماده اتصال:")
                report.sortedAliveLinks.take(5).forEachIndexed { idx, uri ->
                    val remark = uri.substringAfter('#', "Server-${idx + 1}").let {
                        runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                    }
                    appendLine("${idx + 1}. $remark")
                }
            }
        } else {
            buildString {
                appendLine("✅ Subscription Cleaned & Sorted by Ping!")
                appendLine()
                appendLine("• Total Checked: ${report.totalChecked}")
                appendLine("• Alive & Ready: ${report.aliveCount}")
                appendLine("• Dead Removed: ${report.deadRemovedCount}")
                appendLine("• Fastest Ping: ${report.fastestLatencyMs} ms")
            }
        }

        MaterialAlertDialogBuilder(this@runOneClickCleanAndSortConfigsDialog)
            .setTitle(
                if (isFarsi) "🧹 نتیجهٔ پالایش و مرتب‌سازی کانفیگ‌ها"
                else "🧹 Dead-Config Cleaner & Ping Sorter Result",
            )
            .setMessage(summary)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                if (isFarsi) "🚀 ذخیرهٔ کانفیگ‌های سالم و اتصال فوری"
                else "🚀 Save Sorted Configs & Connect Now",
            ) { _, _ ->
                activityScope.launch {
                    val superTitle = if (isFarsi) "💎 سوپر-اشتراک ترکیبی (سریع‌ترین‌های همهٔ اشتراک‌ها)" else "💎 Super-Merged Subscription (Fastest)"
                    val subTitle = if (mergeIntoSuperSubscription) {
                        superTitle
                    } else {
                        activeSub?.name ?: if (isFarsi) "⚡ کانفیگ‌های پالایش‌شده (بر اساس پینگ)" else "⚡ Cleaned & Sorted Configs"
                    }
                    val targetExisting = if (mergeIntoSuperSubscription) {
                        subs.firstOrNull { it.name == superTitle }
                    } else {
                        activeSub
                    }
                    val rawJoined = report.sortedAliveLinks.joinToString("\n")
                    val saved = withContext(Dispatchers.IO) {
                        if (targetExisting != null) {
                            userSubscriptionManager.update(targetExisting.id, subTitle, rawJoined)
                        } else {
                            userSubscriptionManager.add(subTitle, rawJoined)
                        }
                    }
                    userSubscriptionManager.select(saved.id)
                    val curAether = aetherPreferenceStore.read()
                    if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
                    orbotEnginePreferenceStore.setEnabled(false)
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
            .create()
            .showCatClientDialog()
    }
}

internal fun MainActivity.showCloudflareFiveSpoofMethodsDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val methods = LocalSniSpoofProxy.CF_SPOOF_5_METHODS
    val items = methods.map { m ->
        if (isFarsi) "${m.titleFa}\n   ${m.summaryFa}" else "${m.titleEn}\n   ${m.summaryEn}"
    }.toTypedArray()

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "☁️ ۵ روش اسپوفینگ کلادفلر (اعمال مستقیم روی کانفیگ واقعی شما)"
            else "☁️ The 5 Cloudflare Spoofing Methods (Applied to Your Real Config)",
        )
        .setItems(items) { _, idx ->
            val m = methods[idx]
            val chosenStrategy = when (m.index) {
                1 -> LocalSniSpoofProxy.SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI
                2 -> LocalSniSpoofProxy.SpoofStrategy.MITM_PINNED_CERT_FRONTING
                3 -> LocalSniSpoofProxy.SpoofStrategy.LOW_TTL_DECOY
                4 -> LocalSniSpoofProxy.SpoofStrategy.WRONG_TIMESTAMP_PAWS
                else -> LocalSniSpoofProxy.SpoofStrategy.FINALMASK_HEADER_CUSTOM
            }
            Toast.makeText(
                this,
                if (isFarsi) "⏳ در حال تست زنده هندشیک TLS روش ${m.index} روی خط شما…"
                else "⏳ Probing live TLS handshake for Method ${m.index}…",
                Toast.LENGTH_SHORT,
            ).show()
            activityScope.launch {
                val realTemplate = resolveUserRealConfigTemplate()
                val hostPart = m.sampleEndpoint.substringBeforeLast(":")
                val portPart = m.sampleEndpoint.substringAfterLast(":").toIntOrNull() ?: 443
                val liveTlsRtt = withContext(Dispatchers.IO) {
                    LocalSniSpoofProxy.probeSpoofedTlsHandshake(
                        ip = if (hostPart.startsWith("[")) "162.159.198.235" else hostPart,
                        port = portPart,
                        sni = realTemplate?.sni ?: m.sampleSni,
                        strategy = chosenStrategy,
                        decoySni = m.sampleSni.ifBlank { LocalSniSpoofProxy.currentDecoySni },
                        timeoutMs = 1_200,
                    )
                }
                val fwdPort = withContext(Dispatchers.IO) {
                    LocalSniSpoofProxy.startDirectTcpSpoofForwarder(
                        remoteHost = realTemplate?.remoteHost ?: hostPart.removePrefix("[").removeSuffix("]"),
                        remotePort = realTemplate?.remotePort ?: portPart,
                        strategy = chosenStrategy,
                        decoySni = m.sampleSni.ifBlank { LocalSniSpoofProxy.currentDecoySni },
                    )
                }
                val encPath = runCatching {
                    java.net.URLEncoder.encode(realTemplate?.path ?: "/ws?ed=2048", "UTF-8")
                }.getOrDefault("%2Fws")
                val realUuid = realTemplate?.uuidOrPass ?: "auto-aether-masque"
                val realHost = realTemplate?.hostHeader ?: "speed.cloudflare.com"
                val realSni = realTemplate?.sni ?: m.sampleSni
                val scheme = realTemplate?.scheme ?: "vless"
                val encParam = if (scheme == "vless") "encryption=none&" else ""
                val targetHostPort = if (realTemplate != null) "${realTemplate.remoteHost}:${realTemplate.remotePort}" else m.sampleEndpoint
                val encTarget = runCatching { java.net.URLEncoder.encode(targetHostPort, "UTF-8") }.getOrDefault(targetHostPort)
                val snippet = if (realTemplate != null) {
                    "$scheme://$realUuid@127.0.0.1:$fwdPort?${encParam}type=${realTemplate.networkType}&host=$realHost&path=$encPath&sni=$realSni&${m.queryParams}&sni_fwd_target=$encTarget#CF-Spoof-${m.index}-${realTemplate.remark}"
                } else {
                    "aether://162.159.198.235:443?protocol=mim&transport=h2&sni=${m.sampleSni}&outer=162.159.198.235:443&inner=162.159.198.2:443&psiphon=chain&region=DE#CF-Spoof-${m.index}"
                }
                val rttBadge = if (liveTlsRtt != null) {
                    if (isFarsi) "🟢 تست هندشیک TLS با برش چندتکه‌ای SNI: موفق ($liveTlsRtt ms)\n"
                    else "🟢 Multi-Chunk SNI TLS Handshake: OK ($liveTlsRtt ms)\n"
                } else {
                    if (isFarsi) "⚡ آماده عبور از طریق فورواردر محلی برش چندتکه‌ای SNI (پورت $fwdPort)\n"
                    else "⚡ Ready via Local Multi-Chunk SNI Splitter (port $fwdPort)\n"
                }
                val statusNote = if (realTemplate != null) {
                    if (isFarsi) "$rttBadge✅ این روش روی سرور واقعی شما (${realTemplate.hostHeader}) آماده شد:\n\n"
                    else "$rttBadge✅ Built for your real server (${realTemplate.hostHeader}):\n\n"
                } else {
                    if (isFarsi) "$rttBadge💡 چون کانفیگ شخصی نداشتید، این روش روی موتور بدون سرور Cat Warp با SNI اسپوف‌شده (${m.sampleSni}) اعمال می‌شود:\n\n"
                    else "$rttBadge💡 Applied to serverless Cat Warp engine with spoofed SNI (${m.sampleSni}):\n\n"
                }
                MaterialAlertDialogBuilder(this@showCloudflareFiveSpoofMethodsDialog)
                    .setTitle(if (isFarsi) m.titleFa else m.titleEn)
                    .setMessage(
                        (if (isFarsi) "${m.summaryFa}\n\n" else "${m.summaryEn}\n\n") + statusNote + snippet,
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(if (isFarsi) "📋 کپی کانفیگ" else "📋 Copy Config") { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("cf-spoof-${m.id}", snippet))
                        Toast.makeText(
                            this@showCloudflareFiveSpoofMethodsDialog,
                            if (isFarsi) "✅ کانفیگ «${m.titleFa}» کپی شد" else "✅ Copied '${m.titleEn}' config",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .setPositiveButton(
                        if (isFarsi) "🚀 اعمال همین روش و اتصال فوری" else "🚀 Apply This Method & Connect Now",
                    ) { _, _ ->
                        LocalSniSpoofProxy.updateDecoySni(m.sampleSni.ifBlank { "ebanksepah.ir" })
                        if (realTemplate != null) {
                            runOneClickRepairMyRealConfigAndConnect(preferredStrategy = chosenStrategy)
                        } else {
                            activityScope.launch {
                                val subTitle = "☁️ اسپوف کلودفلر (روش ${m.index})"
                                val existing = userSubscriptionManager.list().firstOrNull {
                                    it.name.startsWith("☁️ اسپوف کلودفلر")
                                }
                                val saved = withContext(Dispatchers.IO) {
                                    if (existing != null) {
                                        userSubscriptionManager.update(existing.id, subTitle, snippet)
                                    } else {
                                        userSubscriptionManager.add(subTitle, snippet)
                                    }
                                }
                                userSubscriptionManager.select(saved.id)
                                val cur = aetherPreferenceStore.read()
                                aetherPreferenceStore.save(
                                    cur.copy(
                                        enabled = true,
                                        mode = AetherMode.MasqueInMasque,
                                        masqueTransport = "h2",
                                        endpoint = "162.159.198.235:443",
                                        innerEndpoint = "162.159.198.2:443",
                                        masqueSni = m.sampleSni.ifBlank { LocalSniSpoofProxy.currentDecoySni },
                                        echEnabled = m.index == 2,
                                        endpointScannedAt = System.currentTimeMillis(),
                                    ),
                                )
                                LocalSniSpoofProxy.startSpoofSocksProxy(
                                    strategy = chosenStrategy,
                                    decoySni = m.sampleSni.ifBlank { LocalSniSpoofProxy.currentDecoySni },
                                )
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
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.runIranDnsAndDnsttScanDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "⏳ در حال تست سرورهای DNS ایران (UDP:53 / TCP:53 / DoT:853 و رکوردهای حامل) روی خط شما…"
        else "⏳ Probing Iranian DNS resolvers (UDP:53 / TCP:53 / DoT:853 & carrier records) on your line…",
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val hits = withContext(Dispatchers.IO) {
            WhiteDnsSmartScanner.scanIranDnsResolvers()
        }
        val bestHit = hits.firstOrNull()
        val bestIp = bestHit?.ip ?: "178.22.122.100"
        val bestTransport = when {
            bestHit?.transport?.startsWith("DoT") == true -> "dot"
            bestHit?.transport?.startsWith("TCP") == true -> "tcp"
            else -> "udp"
        }
        val openRecords = bestHit?.supportedRecords?.joinToString(", ").orEmpty().ifEmpty { "A, HTTPS(65), TXT" }
        val recommendedRecordMode = when {
            bestHit?.supportedRecords?.contains("HTTPS(65)") == true -> WhiteDnsRecordMode.TXT_HTTPS
            bestHit?.supportedRecords?.contains("CNAME") == true -> WhiteDnsRecordMode.TXT_CNAME
            else -> WhiteDnsRecordMode.TXT_ONLY
        }
        val recommendedPreset = if (bestTransport == "tcp") {
            WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.last()
        } else {
            WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.first()
        }
        val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
            resolverIp = bestIp,
            transport = bestTransport,
            recordMode = recommendedRecordMode,
            preset = recommendedPreset,
        )
        val bridges = WhiteDnsSmartScanner.buildDnsttBridgesForResolver(bestIp, bestTransport).joinToString("\n")
        val listText = if (hits.isNotEmpty()) {
            hits.take(6).mapIndexed { idx, h ->
                val prov = (if (isFarsi) h.providerNameFa else h.providerNameEn).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
                "${idx + 1}) ${h.ip} (${h.transport} · ${h.rttMs}ms$prov)" + if (idx == 0) " ⭐ سریع‌ترین" else ""
            }.joinToString("\n")
        } else {
            if (isFarsi) "ℹ️ پاسخ مستقیم دریافت نشد؛ از پیش‌فرض شکن ($bestIp) استفاده شد."
            else "ℹ️ Using fallback resolver ($bestIp)."
        }
        val msg = if (isFarsi) {
            "📡 سریع‌ترین DNSهای فعال روی خط شما:\n$listText\n\n" +
                "📦 رکوردهای حامل باز روی این خط: $openRecords\n" +
                "⚡ لینک CottenMax FEC و StormDNS پیشنهادی:\n${bundle.cottenMaxUri}\n${bundle.stormDnsUri}\n\n" +
                "🧅 خطوط پل اضطراری dnstt ($bestIp):\n$bridges"
        } else {
            "📡 Fastest active DNS resolvers on your line:\n$listText\n\n" +
                "📦 Open carrier records: $openRecords\n" +
                "⚡ Recommended CottenMax FEC & StormDNS:\n${bundle.cottenMaxUri}\n${bundle.stormDnsUri}\n\n" +
                "🧅 Emergency dnstt bridges ($bestIp):\n$bridges"
        }
        MaterialAlertDialogBuilder(this@runIranDnsAndDnsttScanDialog)
            .setTitle(
                if (isFarsi) "📡 نتیجه اسکن DNS ایران، رکوردها و تانل Cat DNS"
                else "📡 Iran DNS, Carrier Records & Cat DNS Tunnel Results",
            )
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "📋 کپی بسته تانل (CottenMax/Storm)" else "📋 Copy Tunnel Bundle") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("whitedns-bundle", "${bundle.cottenMaxUri}\n${bundle.stormDnsUri}\n${bundle.soarUri}\n$bridges"))
                Toast.makeText(this@runIranDnsAndDnsttScanDialog, R.string.free_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(if (isFarsi) "📋 کپی پل‌های dnstt برای Orbot" else "📋 Copy dnstt Bridges") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dnstt-bridges", bridges))
                Toast.makeText(
                    this@runIranDnsAndDnsttScanDialog,
                    if (isFarsi) "✅ پل‌های dnstt با DNS خط شما ($bestIp) کپی شد"
                    else "✅ Copied dnstt bridges with DNS $bestIp",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .create()
            .showCatClientDialog()
    }
}

/**
 * 1-Click Real Config Repair & Connect:
 * - Finds the user's REAL config (or falls back to serverless Aether/Firefox VPN if the user has no personal config),
 * - Scans open Non-Cloudflare & Cloudflare endpoints,
 * - Starts the local TCP Anti-DPI SNI Splitter forwarding to the user's real server,
 * - Saves the repaired configs, selects them, and immediately connects the VPN!
 */
internal fun MainActivity.runOneClickRepairMyRealConfigAndConnect(
    preferredStrategy: LocalSniSpoofProxy.SpoofStrategy = LocalSniSpoofProxy.SpoofStrategy.TLS_RECORD_SPLIT_AT_SNI,
) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "🪄 در حال بررسی کانفیگ واقعی شما، اسکن آی‌پی‌های باز و راه‌اندازی تانل ضد فیلترینگ…"
        else "🪄 Inspecting your real config, scanning open IPs & starting Anti-DPI tunnel…",
        Toast.LENGTH_SHORT,
    ).show()

    activityScope.launch {
        val realTemplate = resolveUserRealConfigTemplate()
        val openHits = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.scanNonCloudflareWhitelistTargets(timeoutMs = 900)
        }
        if (realTemplate != null) {
            val repairedLinks = withContext(Dispatchers.IO) {
                LocalSniSpoofProxy.buildRepairedConfigsForRealTemplate(
                    template = realTemplate,
                    strategy = preferredStrategy,
                    verifiedNonCfHits = openHits,
                )
            }
            val content = repairedLinks.joinToString("\n")
            val subTitle = "🪄 تعمیرشده خودکار (${realTemplate.remark.take(22)})"
            val existing = userSubscriptionManager.list().firstOrNull {
                it.name.startsWith("🪄 تعمیرشده خودکار")
            }
            val saved = withContext(Dispatchers.IO) {
                if (existing != null) {
                    userSubscriptionManager.update(existing.id, subTitle, content)
                } else {
                    userSubscriptionManager.add(subTitle, content)
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
                this@runOneClickRepairMyRealConfigAndConnect,
                if (isFarsi) "✅ کانفیگ واقعی شما («${realTemplate.remark}») با برش محلی SNI و ۵ روش اسپوف تعمیر شد — در حال اتصال…"
                else "✅ Repaired your real config ('${realTemplate.remark}') with local SNI split & 5 CF spoofs — connecting…",
                Toast.LENGTH_LONG,
            ).show()
            openScreen(SCREEN_HOME)
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            } else {
                beginConnectFlow(Actions.CONNECT)
            }
        } else {
            // User has no personal VLESS/Trojan config yet -> automatically configure serverless Aether MASQUE with clean SNI & connect!
            val bestSni = openHits.firstOrNull()?.target?.sni ?: LocalSniSpoofProxy.currentDecoySni
            val cur = aetherPreferenceStore.read()
            aetherPreferenceStore.save(
                cur.copy(
                    enabled = true,
                    mode = AetherMode.MasqueHttp2,
                    endpoint = cur.endpoint ?: "162.159.198.1:443",
                    masqueSni = bestSni,
                    endpointScannedAt = System.currentTimeMillis(),
                ),
            )
            LocalSniSpoofProxy.startSpoofSocksProxy(strategy = preferredStrategy, decoySni = bestSni)
            renderAdvancedControls()
            Toast.makeText(
                this@runOneClickRepairMyRealConfigAndConnect,
                if (isFarsi) "💡 چون کانفیگ شخصی نداشتید، موتور بدون سرور Cat Warp با SNI سفید «$bestSni» فعال شد — در حال اتصال…"
                else "💡 No personal config found; activated serverless Cat Warp with clean SNI '$bestSni' — connecting…",
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
}
