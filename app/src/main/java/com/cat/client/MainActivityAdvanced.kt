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
import com.cat.client.MainActivity.Companion.KEYBOARD_SCROLL_DELAY_MS
import com.cat.client.MainActivity.Companion.SCREEN_SETTINGS

// Settings tab: advanced screen, appearance block, chain settings, split tunnel, theme/language/DNS/routing selectors, LAN sharing, Amnezia noise, fronting IPs.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

/**
 * ZedSecure 3.1.6 "Interface": App design picker (Pixel · Portal · Old), Theme, dynamic colour,
 * colour style and contrast. Shown in every design so the user can always switch back.
 */
internal fun MainActivity.buildInterfaceDesignBlock(): LinearLayout {
    val s = zedScheme
    val designStore = AppDesignPreferenceStore(this)
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    column.addView(
        zedPageHero(
            getString(R.string.settings_category_appearance),
            getString(R.string.settings_category_appearance_detail),
            ZedShapeIconView(this, ZedShape.Flower, zedTone(255f).first, R.drawable.ic_zed_palette, zedTone(255f).second, 0f, 0.42f),
            titleSize = 34f,
        ),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    // ---- App design
    val current = designStore.design()
    val designRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        AppDesign.entries.forEachIndexed { index, design ->
            addView(
                zedPickTile(DesignPreviewView(this@buildInterfaceDesignBlock, design, s), getString(design.labelRes), design == current, BACKGROUND, 176) {
                    designStore.saveDesign(design)
                    recreate()
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) },
            )
        }
    }
    val designGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    designGroup.addView(zedTitledCard(R.string.app_design_title, R.string.app_design_detail, designRow), LinearLayout.LayoutParams(-1, -2))
    designGroup.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = zedCardBackground()
            val (container, ink) = zedTone(165f)
            addView(ZedShapeIconView(this@buildInterfaceDesignBlock, ZedShape.Circle, container, R.drawable.ic_zed_info, ink, 0f, 0.5f), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@buildInterfaceDesignBlock).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@buildInterfaceDesignBlock).apply {
                    setText(R.string.app_design_note_title)
                    textSize = 15f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(s.onSurface)
                    includeFontPadding = false
                })
                addView(TextView(this@buildInterfaceDesignBlock).apply {
                    setText(R.string.app_design_note)
                    textSize = 13f
                    typeface = CatClientBodyTypeface
                    setTextColor(s.onSurfaceVariant)
                    includeFontPadding = false
                    setLineSpacing(dp(3).toFloat(), 1f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    zedApplyGroupCorners(designGroup)
    column.addView(designGroup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

    // ---- Colors
    column.addView(zedSectionLabel(R.string.colors_section), LinearLayout.LayoutParams(-1, -2))
    val colorsGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val themeModes = listOf(AppThemeMode.System, AppThemeMode.Light, AppThemeMode.Dark)
    val themeIcons = listOf(R.drawable.ic_zed_auto, R.drawable.ic_zed_light, R.drawable.ic_zed_dark)
    val currentTheme = appThemePreferenceStore.read()
    colorsGroup.addView(
        zedTitledCard(
            R.string.theme_section_title,
            0,
            zedSegmented(themeModes.mapIndexed { i, mode -> getString(mode.labelRes) to themeIcons[i] }, themeModes.indexOf(currentTheme)) { picked ->
                appThemePreferenceStore.save(themeModes[picked])
                recreate()
            },
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    val dynamicSwitch = MaterialSwitch(this).apply {
        isChecked = designStore.dynamicColor()
        isEnabled = dynamicColorAvailable()
        setOnCheckedChangeListener { _, checked ->
            designStore.saveDynamicColor(checked)
            recreate()
        }
    }
    colorsGroup.addView(
        advancedToggleRow(getString(R.string.dynamic_color_title), getString(R.string.dynamic_color_detail), dynamicSwitch).apply {
            background = zedCardBackground()
            setPadding(dp(8), dp(10), dp(8), dp(10))
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    val currentStyle = designStore.colorStyle()
    val night = palette.isDark
    val styleStrip = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        ColorStyle.entries.forEachIndexed { index, style ->
            val stylePalette = CatClientDesignTokens.forStyle(this@buildInterfaceDesignBlock, style, night)
            val styleScheme = ZedScheme.from(stylePalette)
            addView(
                zedPickTile(
                    ColorStylePreviewView(this@buildInterfaceDesignBlock, styleScheme, if (index % 2 == 0) 14f else -10f),
                    getString(style.labelRes),
                    style == currentStyle,
                    stylePalette.background,
                    120,
                ) {
                    designStore.saveColorStyle(style)
                    recreate()
                },
                LinearLayout.LayoutParams(dp(116), -2).apply { if (index > 0) marginStart = dp(8) },
            )
        }
    }
    val styleScroll = android.widget.HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(styleStrip, ViewGroup.LayoutParams(-2, -2))
        post {
            val chosen = styleStrip.getChildAt(ColorStyle.entries.indexOf(currentStyle).coerceAtLeast(0))
            if (chosen != null) smoothScrollTo((chosen.left - dp(24)).coerceAtLeast(0), 0)
        }
    }
    colorsGroup.addView(zedTitledCard(R.string.color_style_title, R.string.color_style_detail, styleScroll), LinearLayout.LayoutParams(-1, -2))
    val contrasts = ColorContrast.entries
    colorsGroup.addView(
        zedTitledCard(
            R.string.contrast_title,
            R.string.contrast_detail,
            zedSegmented(contrasts.map { getString(it.labelRes) to 0 }, contrasts.indexOf(designStore.contrast())) { picked ->
                designStore.saveContrast(contrasts[picked])
                recreate()
            },
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    zedApplyGroupCorners(colorsGroup)
    column.addView(colorsGroup, LinearLayout.LayoutParams(-1, -2))
    return column
}

internal fun MainActivity.buildAdvancedScreen(): View {
    val root = FrameLayout(this).apply { setBackgroundColor(withAlpha(BACKGROUND, 0)) }
    val scrollView = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(withAlpha(BACKGROUND, 0))
    }
    ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
        val topInset = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
        ).top
        val bottomInset = insets.getInsets(
            WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars(),
        ).bottom
        view.setPadding(view.paddingLeft, topInset, view.paddingRight, bottomInset)
        view.post {
            view.findFocus()?.let { focusedView ->
                scrollFieldIntoView(scrollView, focusedView, delayMs = 0L)
            }
        }
        insets
    }
    val advancedBody = MaxWidthLinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        maxWidthPx = dp(520)
        setPadding(dp(24), dp(20), dp(24), dp(104))
    }
    fun settingsContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val appPreferencesSettings = settingsContent()
    val testingSettings = settingsContent()
    val connectionSettings = settingsContent()
    val chainSettings = buildConnectionChainSettings()
    val splitTunnelSettings = settingsContent()
    val sharingSettings = settingsContent()
    val systemSettings = settingsContent()
    fun appPreferenceRow(
        @StringRes titleRes: Int,
        value: String,
        onClick: (View) -> Unit,
    ) = DashboardDataRowView(this).apply {
        val title = getString(titleRes)
        setRow(title, value)
        contentDescription = getString(R.string.settings_value_content_description, title, value)
        setOnRowClickListener { onClick(this) }
    }
    val aetherSelection = aetherPreferenceStore.read()
    val aetherCoreAvailable = AetherRuntimeCapabilities.detect().nativeCoreLoaded
    val aetherPanel = advancedSettingsPanel()
    val aetherSwitch = MaterialSwitch(this).apply {
        isChecked = aetherSelection.enabled && aetherCoreAvailable
        isEnabled = aetherCoreAvailable
        contentDescription = getString(R.string.aether_enable)
        setOnCheckedChangeListener { _, checked ->
            aetherPreferenceStore.save(aetherPreferenceStore.read().copy(enabled = checked))
        }
    }
    aetherPanel.addView(
        advancedToggleRow(
            getString(R.string.aether_enable),
            getString(if (aetherCoreAvailable) R.string.aether_enable_detail else R.string.aether_core_unavailable),
            aetherSwitch,
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    val isFarsiAether = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val aetherModeLabel = when (aetherSelection.mode) {
        AetherMode.MasqueInMasque -> "MASQUE in MASQUE (${aetherSelection.masqueTransport.uppercase(Locale.US)})"
        AetherMode.WarpInWarp -> "WARP in WARP (Gool)"
        AetherMode.MasqueHttp2 -> getString(R.string.aether_mode_h2)
        AetherMode.WireGuard -> if (isFarsiAether) "WARP · WireGuard (${aetherSelection.ipScan.uppercase(Locale.US)})" else "WARP · WireGuard (${aetherSelection.ipScan.uppercase(Locale.US)})"
        AetherMode.WireGuardOverMasque -> getString(R.string.aether_mode_wom)
        AetherMode.MasquePsiphon -> getString(R.string.aether_mode_psiphon)
    }
    val aetherModeRow = appPreferenceRow(
        R.string.aether_mode,
        aetherModeLabel,
    ) {
        if (aetherOperationJob?.isActive == true) return@appPreferenceRow
        val current = aetherPreferenceStore.read()
        val next = when (current.mode) {
            AetherMode.MasqueInMasque -> AetherMode.MasqueHttp2
            AetherMode.MasqueHttp2 -> AetherMode.WireGuard
            AetherMode.WireGuard -> AetherMode.WireGuardOverMasque
            AetherMode.WireGuardOverMasque -> AetherMode.WarpInWarp
            AetherMode.WarpInWarp -> AetherMode.MasquePsiphon
            AetherMode.MasquePsiphon -> AetherMode.MasqueInMasque
        }
        aetherPreferenceStore.save(current.copy(mode = next, endpoint = null, endpointScannedAt = 0L))
        recreate()
    }.apply { isEnabled = currentVpnServiceState == VpnState.Stopped }
    aetherPanel.addView(aetherModeRow, LinearLayout.LayoutParams(-1, -2))
    val exitLocValue = when {
        aetherSelection.chainTarget == "auto-psiphon" ->
            if (isFarsiAether) "🤖 زنجیره شرطی هوشمند (فقط در صورت تحریم: WARP → سایفون)" else "🤖 Smart Conditional Chain (WARP → Psiphon if sanctioned)"
        aetherSelection.chainTarget == "psiphon" ->
            if (isFarsiAether) "🌍 آی‌پی خارج تضمینی (WARP → سایفون)" else "🌍 Foreign IP (WARP → Psiphon)"
        aetherSelection.chainTarget == "tor" ->
            if (isFarsiAether) "🌍 آی‌پی خارج تضمینی (WARP → تور)" else "🌍 Foreign IP (WARP → Tor)"
        aetherSelection.effectiveExitLocation() == "foreign" ->
            if (isFarsiAether) "🌍 آی‌پی خارج (دو لایه کلودفلر · Gool)" else "🌍 Foreign IP (Double-Hop Gool)"
        else ->
            if (isFarsiAether) "🇮🇷 آی‌پی ایران (تک‌لایه مستقیم · سریع‌ترین)" else "🇮🇷 Iran IP (Single-Hop · Fastest)"
    }
    val exitLocTitle = if (isFarsiAether) "مکان آی‌پی خروجی (ایران / خارج)" else "Exit IP Location (Iran / Foreign)"
    val exitLocRow = DashboardDataRowView(this).apply {
        setRow(exitLocTitle, exitLocValue)
        contentDescription = getString(R.string.settings_value_content_description, exitLocTitle, exitLocValue)
        setOnRowClickListener {
            showAetherExitLocationPickerDialog {
                recreate()
            }
        }
        isEnabled = currentVpnServiceState == VpnState.Stopped
    }
    aetherPanel.addView(exitLocRow, LinearLayout.LayoutParams(-1, -2))
    val endpointValue = aetherSelection.endpoint?.let { endpoint ->
        val stamp = if (aetherSelection.endpointScannedAt > 0) {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date(aetherSelection.endpointScannedAt))
        } else getString(R.string.aether_never_scanned)
        val namePrefix = aetherSelection.profileName.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
        "$namePrefix$endpoint (${aetherSelection.ipScan.uppercase(Locale.US)}) · $stamp"
    } ?: getString(R.string.aether_never_scanned)
    aetherPanel.addView(
        appPreferenceRow(R.string.aether_endpoint, endpointValue) { }.apply { isClickable = false },
        LinearLayout.LayoutParams(-1, -2),
    )
    if (aetherSelection.mode == AetherMode.MasquePsiphon || aetherSelection.chainTarget == "psiphon" || aetherSelection.effectiveExitLocation() == "foreign") {
        val regionValue = PsiphonCarrierConfig.regionDisplayLabel(
            aetherSelection.psiphonRegion,
            isFarsiAether,
            getString(R.string.aether_region_automatic),
        )
        aetherPanel.addView(
            appPreferenceRow(R.string.aether_region, regionValue) {
                val regions = listOf("") + PsiphonCarrierConfig.availableRegions(this)
                val labels = regions.map { code ->
                    PsiphonCarrierConfig.regionDisplayLabel(code, isFarsiAether, getString(R.string.aether_region_automatic))
                }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.aether_region)
                    .setItems(labels) { _, index ->
                        val pickedRegion = regions[index]
                        val current = aetherPreferenceStore.read()
                        val pickedSelection = current.copy(
                            psiphonRegion = pickedRegion,
                            chainTarget = if (pickedRegion.isNotEmpty() && current.chainTarget in setOf("none", "tor", "tor-reverse")) "psiphon" else current.chainTarget,
                            exitLocation = if (pickedRegion.isNotEmpty()) "foreign" else current.exitLocation,
                        )
                        aetherPreferenceStore.save(pickedSelection)
                        aetherPreferenceStore.saveExitChoice(pickedSelection.exitLocation, pickedSelection.chainTarget, pickedRegion)
                        recreate()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                    .showCatClientDialog()
            }.apply { isEnabled = currentVpnServiceState == VpnState.Stopped },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    aetherPreferenceStore.readLastExit()?.let { last ->
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(last.atMs))
        val viaLabel = when (last.via) {
            "psiphon" -> if (isFarsiAether) "سایفون" else "Psiphon"
            "psiphon-auto" -> if (isFarsiAether) "سایفون (کشور خودکار)" else "Psiphon (auto country)"
            "warp-2hop" -> if (isFarsiAether) "WARP دولایه (سایفون وصل نشد)" else "two-hop WARP (Psiphon failed)"
            "tor" -> "Tor"
            else -> if (isFarsiAether) "مستقیم" else "direct"
        }
        val lastValue = when {
            !last.measured -> if (isFarsiAether) "❔ اندازه‌گیری نشد · $viaLabel · $time" else "❔ not measured · $viaLabel · $time"
            last.wrongIran -> if (isFarsiAether) {
                "⚠️ ایران (${last.ip}) با اینکه خارج خواسته بودید · $viaLabel · $time\nیک کشور سایفون دیگر یا «خارج (WARP دولایه)» را امتحان کنید."
            } else {
                "⚠️ Iran (${last.ip}) although foreign was requested · $viaLabel · $time\nTry another Psiphon country or Foreign (two-hop WARP)."
            }
            last.otherCountry -> if (isFarsiAether) {
                "ℹ️ ${last.countryCode} (خواسته: ${last.requested}؛ سرور آن کشور در دسترس نبود) · $viaLabel · $time"
            } else {
                "ℹ️ ${last.countryCode} (requested ${last.requested}; no server there was reachable) · $viaLabel · $time"
            }
            else -> "✅ ${last.countryCode} · ${last.ip} · $viaLabel · $time"
        }
        aetherPanel.addView(
            appPreferenceRow(R.string.aether_last_exit, lastValue) { }.apply { isClickable = false },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    fun aetherActionButton(@StringRes label: Int, action: (MaterialButton) -> Unit) = MaterialButton(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        isEnabled = aetherCoreAvailable
        setOnClickListener { action(this) }
    }
    val addAetherButton = MaterialButton(this).apply {
        text = if (isFarsiAether) "➕ افزودن Cat Warp (WARP با IPv6 / MASQUE-HTTP/2)" else "➕ Add Cat Warp (WARP IPv6 / MASQUE-HTTP/2)"
        isAllCaps = false
        minHeight = dp(48)
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(BACKGROUND)
        setOnClickListener { showAddAetherDialog() }
    }
    val provisionButton = aetherActionButton(R.string.aether_get_key) { button ->
        if (aetherOperationJob?.isActive == true) return@aetherActionButton
        button.isEnabled = false
        aetherOperationJob = activityScope.launch {
            val selected = aetherPreferenceStore.read()
            val proxy = if (currentVpnServiceState == VpnState.Started) {
                "socks5://127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}"
            } else null
            val result = withContext(Dispatchers.IO) {
                AetherOperations.provision(
                    selected.mode,
                    java.io.File(filesDir, "aether.toml"),
                    proxy,
                    selected.ipScan,
                    selected.exitLocation,
                )
            }
            Toast.makeText(
                this@buildAdvancedScreen,
                if (result.isSuccess) getString(R.string.aether_key_ready) else result.exceptionOrNull()?.message.orEmpty(),
                Toast.LENGTH_LONG,
            ).show()
            button.isEnabled = aetherCoreAvailable
        }
    }
    val scanButton = aetherActionButton(R.string.aether_scan) { button ->
        if (aetherOperationJob?.isActive == true) return@aetherActionButton
        if (currentVpnServiceState != VpnState.Stopped) {
            Toast.makeText(this, R.string.aether_scan_disconnect, Toast.LENGTH_LONG).show()
            return@aetherActionButton
        }
        button.isEnabled = false
        aetherOperationJob = activityScope.launch {
            val selected = aetherPreferenceStore.read()
            val result = withContext(Dispatchers.IO) {
                AetherOperations.scan(selected.mode, java.io.File(filesDir, "aether.toml"), selected.ipScan)
            }
            val endpoints = result.getOrDefault(emptyList())
            if (endpoints.isNotEmpty()) {
                aetherPreferenceStore.save(selected.copy(
                    endpoint = endpoints.first().peer,
                    endpointScannedAt = System.currentTimeMillis(),
                ))
            }
            Toast.makeText(
                this@buildAdvancedScreen,
                when {
                    endpoints.isNotEmpty() -> getString(R.string.aether_scan_ready, endpoints.first().peer, endpoints.first().rttMillis)
                    result.isFailure -> result.exceptionOrNull()?.message.orEmpty()
                    else -> getString(R.string.aether_scan_empty)
                },
                Toast.LENGTH_LONG,
            ).show()
            button.isEnabled = aetherCoreAvailable
        }
    }
    aetherPanel.addView(addAetherButton, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(8) })
    aetherPanel.addView(provisionButton, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(8) })
    aetherPanel.addView(scanButton, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16); topMargin = dp(8); bottomMargin = dp(12) })
    connectionSettings.addView(
        advancedSectionLabel(getString(R.string.aether_title)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    connectionSettings.addView(aetherPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

    settingsSubscriptionSelectorRow = appPreferenceRow(
        R.string.settings_subscription_title,
        selectedSubscriptionName(),
        this::showSubscriptionSelectorMenu,
    )
    val themeSelectorRow = appPreferenceRow(
        R.string.theme_dialog_title,
        getString(appThemePreferenceStore.read().labelRes),
    ) { showThemeSelector() }
    val accentSelectorRow = appPreferenceRow(
        R.string.accent_setting_title,
        getString(AppAccentPreferenceStore(this).read().labelRes),
    ) { showAccentSelector() }
    val languageSelectorRow = appPreferenceRow(
        R.string.language_setting_title,
        getString(appLanguagePreferenceStore.read().labelRes),
    ) { showLanguageSelector() }
    // ---- Appearance (ZedSecure "Appearance"): theme mode, inline accent swatches, language.
    val appearanceSettings = settingsContent()
    val appearancePanel = advancedSettingsPanel()
    val swatchStrip = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(12), dp(6), dp(12), dp(14))
        val store = AppAccentPreferenceStore(this@buildAdvancedScreen)
        val selected = store.read()
        AppAccent.entries.chunked(4).forEach { rowItems ->
            addView(LinearLayout(this@buildAdvancedScreen).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                rowItems.forEach { preset ->
                    val preview = CatClientDesignTokens.themed(CatClientDesignTokens.palette(palette.isDark), preset, palette.isDark)
                    addView(LinearLayout(this@buildAdvancedScreen).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(6), dp(8), dp(6), dp(8))
                        isClickable = true; isFocusable = true
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat()
                            setColor(preview.surface)
                            setStroke(dp(if (preset == selected) 2 else 1), if (preset == selected) preview.teal else withAlpha(OUTLINE, 150))
                        }
                        setOnClickListener { if (preset != selected) { store.save(preset); recreate() } }
                        // mini "screen": accent dot, secondary chip, button bar
                        addView(View(this@buildAdvancedScreen).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(preview.teal) } }, LinearLayout.LayoutParams(dp(26), dp(26)))
                        addView(LinearLayout(this@buildAdvancedScreen).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(View(this@buildAdvancedScreen).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(preview.surfaceElevated2) } }, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginEnd = dp(3) })
                            addView(View(this@buildAdvancedScreen).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(preview.secondary) } }, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginStart = dp(3) })
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                        addView(View(this@buildAdvancedScreen).apply { background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(preview.brandPillBackground) } }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(5) })
                        addView(View(this@buildAdvancedScreen).apply { background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(preview.teal) } }, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(5) })
                        addView(TextView(this@buildAdvancedScreen).apply {
                            setText(preset.labelRes); textSize = 11f; gravity = Gravity.CENTER; maxLines = 1
                            typeface = if (preset == selected) CatClientBodyBoldTypeface else CatClientBodyTypeface
                            setTextColor(TEXT_PRIMARY)
                        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                }
                repeat(4 - rowItems.size) { addView(View(this@buildAdvancedScreen), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }
    val surfaceStyleStore = AppSurfaceStylePreferenceStore(this)
    val surfaceStyleSelectorRow = appPreferenceRow(
        R.string.surface_style_title,
        getString(surfaceStyleStore.read().labelRes),
    ) {
        val styles = AppSurfaceStyle.entries
        val labels = styles.map { getString(it.labelRes) }.toTypedArray()
        val currentIdx = styles.indexOf(surfaceStyleStore.read()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.surface_style_title)
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                dialog.dismiss()
                val picked = styles[which]
                if (picked != surfaceStyleStore.read()) {
                    surfaceStyleStore.save(picked)
                    recreate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }
    val hapticSwitch = MaterialSwitch(this).apply {
        isChecked = connectHapticEnabled()
        setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE).edit().putBoolean("haptic_connect", checked).apply()
            if (checked) connectHaptic()
        }
    }
    val hapticRow = advancedToggleRow(getString(R.string.haptic_connect_title), getString(R.string.haptic_connect_detail), hapticSwitch)
    // Theme now lives in the Interface block's segmented "Theme" control (ZedSecure 3.1.6).
    themeSelectorRow.visibility = View.GONE
    appearanceSettings.addView(buildInterfaceDesignBlock(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    appearanceSettings.addView(zedSectionLabel(R.string.accent_setting_title), LinearLayout.LayoutParams(-1, -2))
    listOf<View>(
        surfaceStyleSelectorRow,
        accentSelectorRow,
        swatchStrip,
        languageSelectorRow,
        hapticRow,
    ).forEachIndexed { index, row ->
        if (index > 0 && row !== swatchStrip) {
            appearancePanel.addView(
                View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) },
            )
        }
        appearancePanel.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    appearanceSettings.addView(appearancePanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(0) })

    val appPreferencesPanel = advancedSettingsPanel()
    listOf(settingsSubscriptionSelectorRow)
        .forEachIndexed { index, row ->
            if (index > 0) {
                appPreferencesPanel.addView(
                    View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    },
                )
            }
            appPreferencesPanel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    appPreferencesSettings.addView(
        appPreferencesPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    var connectionTestSettings = connectionTestSettingsPreferenceStore.read()
    testingSettings.addView(
        advancedSectionLabel(getString(R.string.config_test_section)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) },
    )
    val connectionTestPanel = advancedSettingsPanel()
    fun testIntegerInput(
        title: String,
        detail: String,
        initialValue: Int,
        range: IntRange,
        imeAction: Int,
        onValueChanged: (Int) -> Unit,
    ): TextInputLayout {
        var savedValue = initialValue
        val input = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            background = null
            textSize = 16f
            typeface = CatClientDataTypeface
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = imeAction
            filters = arrayOf(InputFilter.LengthFilter(3))
            setText(initialValue.toString())
            setSelectAllOnFocus(true)
            setTextColor(TEXT_PRIMARY)
        }
        fun commit() {
            val value = (input.text?.toString()?.toIntOrNull() ?: savedValue)
                .coerceIn(range.first, range.last)
            if (input.text?.toString() != value.toString()) {
                input.setText(value.toString())
                input.setSelection(input.text?.length ?: 0)
            }
            if (value != savedValue) {
                savedValue = value
                onValueChanged(value)
            }
        }
        input.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commit()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
                false
            } else {
                commit()
                input.clearFocus()
                true
            }
        }
        return TextInputLayout(this).apply {
            hint = title
            helperText = getString(R.string.config_test_integer_range, range.first, range.last, detail)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(1)
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(input)
        }
    }
    connectionTestPanel.addView(
        testIntegerInput(
            title = getString(R.string.config_test_timeout_title),
            detail = getString(R.string.config_test_timeout_detail),
            initialValue = connectionTestSettings.timeoutSeconds,
            range = ConnectionTestSettings.MIN_TIMEOUT_SECONDS..ConnectionTestSettings.MAX_TIMEOUT_SECONDS,
            imeAction = EditorInfo.IME_ACTION_NEXT,
        ) { value ->
            connectionTestSettings = connectionTestSettings.copy(timeoutSeconds = value)
            connectionTestSettingsPreferenceStore.save(connectionTestSettings)
        },
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(12)
        },
    )
    connectionTestPanel.addView(
        testIntegerInput(
            title = getString(R.string.config_test_concurrency_title),
            detail = getString(R.string.config_test_concurrency_detail),
            initialValue = connectionTestSettings.concurrency,
            range = ConnectionTestSettings.MIN_CONCURRENCY..ConnectionTestSettings.MAX_CONCURRENCY,
            imeAction = EditorInfo.IME_ACTION_NEXT,
        ) { value ->
            connectionTestSettings = connectionTestSettings.copy(concurrency = value)
            connectionTestSettingsPreferenceStore.save(connectionTestSettings)
        },
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(12)
        },
    )
    connectionTestPanel.addView(
        testIntegerInput(
            title = getString(R.string.config_test_speed_size_title),
            detail = getString(R.string.config_test_speed_size_detail),
            initialValue = connectionTestSettings.speedTestMegabytes,
            range = ConnectionTestSettings.MIN_SPEED_TEST_MEGABYTES..
                ConnectionTestSettings.MAX_SPEED_TEST_MEGABYTES,
            imeAction = EditorInfo.IME_ACTION_DONE,
        ) { value ->
            connectionTestSettings = connectionTestSettings.copy(speedTestMegabytes = value)
            connectionTestSettingsPreferenceStore.save(connectionTestSettings)
        },
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(12)
            bottomMargin = dp(12)
        },
    )
    testingSettings.addView(
        connectionTestPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    connectionSettings.addView(
        advancedSectionLabel(getString(R.string.tls_integrity_section)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    val tlsIntegrityPanel = advancedSettingsPanel()
    tlsIntegrityCheckbox = MaterialSwitch(this).apply {
        isChecked = tlsIntegrityPreferenceStore.isEnabled()
        contentDescription = getString(R.string.tls_integrity_title)
        setOnClickListener { saveTlsIntegrityEnabled(isChecked) }
    }
    tlsIntegrityPanel.addView(
        advancedToggleRow(
            title = getString(R.string.tls_integrity_title),
            detail = getString(R.string.tls_integrity_description),
            toggle = tlsIntegrityCheckbox,
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    tlsFragmentCheckbox = MaterialSwitch(this).apply {
        isChecked = dpiBypassPreferenceStore.isEnabled()
        contentDescription = getString(R.string.tls_fragment_title)
        setOnClickListener { saveTlsFragmentEnabled(isChecked) }
    }
    tlsIntegrityPanel.addView(
        advancedToggleRow(
            title = getString(R.string.tls_fragment_title),
            detail = getString(R.string.tls_fragment_description),
            toggle = tlsFragmentCheckbox,
        ),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    // ISP-specific fragmentation presets (the one-click carriers row):
    // each operator's DPI splits ClientHello differently, so the winning
    // ByeDPI pattern differs too — try one, keep what works.
    val presetChips = mutableListOf<Chip>()
    val presetRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    listOf(
        R.string.dpi_preset_auto to DpiFragmentPreset.AUTO_ID,
        R.string.dpi_preset_default to DpiFragmentPreset.DEFAULT.id,
        R.string.dpi_preset_mci to DpiFragmentPreset.MCI.id,
        R.string.dpi_preset_irancell to DpiFragmentPreset.IRANCELL.id,
        R.string.dpi_preset_rightel to DpiFragmentPreset.RIGHTEL.id,
        R.string.dpi_preset_tci to DpiFragmentPreset.TCI.id,
        R.string.dpi_preset_gaming to DpiFragmentPreset.GAMING.id,
        R.string.dpi_preset_pattn to DpiFragmentPreset.PATTN.id,
    ).forEach { (labelRes, presetId) ->
        val chip = Chip(this).apply {
            text = getString(labelRes)
            isCheckable = true
            isChecked = dpiBypassPreferenceStore.presetId() == presetId
            textSize = 11f
            setTextColor(TEXT_PRIMARY)
            chipStrokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 170))
            chipStrokeWidth = dp(1).toFloat()
            chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    presetChips.filterNot { it === this }.forEach { it.isChecked = false }
                    dpiBypassPreferenceStore.savePreset(presetId)
                    Toast.makeText(
                        this@buildAdvancedScreen,
                        getString(R.string.dpi_preset_applied, getString(labelRes)),
                        Toast.LENGTH_LONG,
                    ).show()
                    renderFragmentCarrierLine()
                }
            }
        }
        presetChips += chip
        presetRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    }
    // «Detected carrier → preset that will be used», so the automatic choice is never
    // invisible: if the phone guesses wrong (dual SIM, Wi-Fi, roaming) the chips below
    // are still there, and this line explains what happened.
    val carrierLine = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    fragmentCarrierLine = carrierLine
    tlsIntegrityPanel.addView(
        carrierLine,
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(8)
        },
    )
    renderFragmentCarrierLine()
    tlsIntegrityPanel.addView(
        advancedSectionLabel(getString(R.string.dpi_preset_section)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    tlsIntegrityPanel.addView(
        presetRow,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )
    connectionSettings.addView(
        tlsIntegrityPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    connectionSettings.addView(
        advancedSectionLabel(getString(R.string.settings_warp_section)),
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) },
    )
    val amneziaPanel = advancedSettingsPanel()
    connectionSettings.addView(
        amneziaPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    amneziaNoiseCheckbox = MaterialSwitch(this).apply {
        isChecked = connectionOptionsPreferenceStore.read().amneziaNoiseEnabled
        contentDescription = getString(R.string.amnezia_noise_enable)
        setOnClickListener { saveAmneziaNoiseEnabled(isChecked) }
    }
    amneziaPanel.addView(
        advancedToggleRow(
            title = getString(R.string.amnezia_noise_title),
            detail = getString(R.string.amnezia_noise_description),
            toggle = amneziaNoiseCheckbox,
        ),
        LinearLayout.LayoutParams(-1, -2),
    )

    fun noiseField(hint: String, numeric: Boolean = false): Pair<EditText, TextInputLayout> {
        val input = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = if (numeric) Gravity.CENTER else Gravity.START or Gravity.CENTER_VERTICAL
            background = null
            textSize = 14f
            inputType = if (numeric) {
                InputType.TYPE_CLASS_NUMBER
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setTextColor(TEXT_PRIMARY)
        }
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(1)
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(input)
        }
        return input to layout
    }

    fun noiseFieldRow(vararg fields: TextInputLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        fields.forEachIndexed { index, field ->
            addView(
                field,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
    }

    fun noiseGroupLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
    }

    val (countInput, countLayout) = noiseField(getString(R.string.amnezia_noise_count), numeric = true)
    val (minSizeInput, minSizeLayout) = noiseField(getString(R.string.amnezia_noise_min_size), numeric = true)
    val (maxSizeInput, maxSizeLayout) = noiseField(getString(R.string.amnezia_noise_max_size), numeric = true)
    val (ttlInput, ttlLayout) = noiseField(getString(R.string.amnezia_noise_fake_ttl), numeric = true)
    val (versionInput, versionLayout) = noiseField(getString(R.string.amnezia_noise_version), numeric = true)
    val (ipStackInput, ipStackLayout) = noiseField(getString(R.string.amnezia_noise_ip_stack))
    val (congestionInput, congestionLayout) = noiseField(getString(R.string.amnezia_noise_congestion))
    val (headerProtectionKeyInput, headerProtectionKeyLayout) =
        noiseField(getString(R.string.amnezia_noise_header_protection_key))
    val (contentPaddingInput, contentPaddingLayout) =
        noiseField(getString(R.string.amnezia_noise_content_padding))
    val (rekeyAfterInput, rekeyAfterLayout) = noiseField(getString(R.string.amnezia_noise_rekey_after))
    val (rekeyTimeoutInput, rekeyTimeoutLayout) = noiseField(getString(R.string.amnezia_noise_rekey_timeout))
    val (rejectAfterInput, rejectAfterLayout) = noiseField(getString(R.string.amnezia_noise_reject_after))
    val (keepaliveTimeoutInput, keepaliveTimeoutLayout) =
        noiseField(getString(R.string.amnezia_noise_keepalive_timeout))
    val (maxHandshakeAttemptsInput, maxHandshakeAttemptsLayout) =
        noiseField(getString(R.string.amnezia_noise_max_handshake_attempts))
    val (randomTrailersInput, randomTrailersLayout) =
        noiseField(getString(R.string.amnezia_noise_random_trailers))
    val (disableCookiesInput, disableCookiesLayout) =
        noiseField(getString(R.string.amnezia_noise_disable_cookies))
    amneziaNoiseCountInput = countInput
    amneziaNoiseMinSizeInput = minSizeInput
    amneziaNoiseMaxSizeInput = maxSizeInput
    amneziaNoiseTtlInput = ttlInput
    amneziaNoiseVersionInput = versionInput
    amneziaNoiseIpStackInput = ipStackInput
    amneziaNoiseCongestionInput = congestionInput
    amneziaNoiseHeaderProtectionKeyInput = headerProtectionKeyInput
    amneziaNoiseContentPaddingInput = contentPaddingInput
    amneziaNoiseRekeyAfterInput = rekeyAfterInput
    amneziaNoiseRekeyTimeoutInput = rekeyTimeoutInput
    amneziaNoiseRejectAfterInput = rejectAfterInput
    amneziaNoiseKeepaliveTimeoutInput = keepaliveTimeoutInput
    amneziaNoiseMaxHandshakeAttemptsInput = maxHandshakeAttemptsInput
    amneziaNoiseRandomTrailersInput = randomTrailersInput
    amneziaNoiseDisableCookiesInput = disableCookiesInput.apply { imeOptions = EditorInfo.IME_ACTION_DONE }
    amneziaNoiseFields = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(noiseGroupLabel(getString(R.string.amnezia_noise_packets_group)))
        addView(noiseFieldRow(countLayout, minSizeLayout, maxSizeLayout), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        addView(noiseFieldRow(ttlLayout, versionLayout), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        addView(noiseGroupLabel(getString(R.string.amnezia_noise_stack_group)), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })
        addView(noiseFieldRow(ipStackLayout, congestionLayout), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        addView(noiseGroupLabel(getString(R.string.amnezia_noise_v3_group)), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })
        addView(headerProtectionKeyLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(contentPaddingLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(noiseFieldRow(rekeyAfterLayout, rekeyTimeoutLayout), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        addView(noiseFieldRow(rejectAfterLayout, keepaliveTimeoutLayout), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        addView(maxHandshakeAttemptsLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(noiseFieldRow(randomTrailersLayout, disableCookiesLayout), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        addView(
            TextView(this@buildAdvancedScreen).apply {
                setText(R.string.amnezia_noise_optional_hint)
                textSize = 11f
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }
    amneziaPanel.addView(
        amneziaNoiseFields,
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(8)
        },
    )
    amneziaNoiseApplyButton = MaterialButton(this).apply {
        setText(R.string.amnezia_noise_apply)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setAllCaps(false)
        minWidth = 0
        minHeight = dp(44)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(8)
        backgroundTintList = ColorStateList.valueOf(SURFACE)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(OUTLINE)
        rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 28))
        setTextColor(TEAL)
        elevation = 0f
        stateListAnimator = null
        setOnClickListener { applyAmneziaNoiseSettings() }
    }
    amneziaPanel.addView(
        amneziaNoiseApplyButton,
        LinearLayout.LayoutParams(-2, dp(44)).apply {
            gravity = Gravity.END
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(12)
        },
    )
    amneziaNoiseErrorText = TextView(this).apply {
        textSize = 12f
        setTextColor(ERROR)
        visibility = View.GONE
    }
    amneziaPanel.addView(
        amneziaNoiseErrorText,
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        },
    )
    connectionSettings.addView(
        advancedSectionLabel(getString(R.string.fronting_section)),
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(24)
        },
    )
    val frontingPanel = advancedSettingsPanel()
    connectionSettings.addView(
        frontingPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    frontingPanel.addView(
        advancedSectionDetail(getString(R.string.fronting_description)),
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(8)
        },
    )
    frontingIps = frontingIpPreferenceStore.readFrontingIps()
    frontingIpChipGroup = ChipGroup(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        isSingleLine = false
        chipSpacingHorizontal = dp(8)
        chipSpacingVertical = dp(4)
    }
    frontingPanel.addView(
        frontingIpChipGroup,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(8)
        },
    )
    renderFrontingIpChips()
    frontingIpInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            commitFrontingIpInput(reconnectIfChanged = true)
            clearFocus()
            true
        }
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollFieldIntoView(scrollView, frontingIpInputLayout)
            } else {
                commitFrontingIpInput(reconnectIfChanged = true)
            }
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (frontingIpInputUpdating) return
                frontingIpErrorText.visibility = View.GONE
                val value = s?.toString().orEmpty()
                if (value.contains(",")) {
                    val parts = value.split(",")
                    val completeParts = parts.dropLast(1)
                    val tail = if (value.endsWith(",")) "" else parts.last()
                    if (addFrontingIpTokens(completeParts, focusOnError = false)) {
                        setFrontingIpInputText(tail)
                    }
                }
            }
        })
    }
    frontingIpInputLayout = TextInputLayout(this).apply {
        hint = getString(R.string.fronting_hint)
        placeholderText = "104.16.0.1:443"
        helperText = frontingIpInputHint()
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        boxStrokeWidth = dp(1)
        boxStrokeWidthFocused = dp(1)
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(frontingIpInput)
    }
    frontingPanel.addView(
        frontingIpInputLayout,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(12)
        },
    )
    frontingIpErrorText = TextView(this).apply {
        textSize = 12f
        setTextColor(ERROR)
        includeFontPadding = true
        visibility = View.GONE
    }
    frontingPanel.addView(
        frontingIpErrorText,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        },
    )
    connectionSettings.addView(
        advancedSectionLabel(getString(R.string.routing_section)),
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(24)
        },
    )
    val routingPanel = advancedSettingsPanel()
    connectionSettings.addView(
        routingPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    routingModeDetailText = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        maxLines = 2
    }
    val routingText = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            TextView(this@buildAdvancedScreen).apply {
                setText(R.string.routing_rules_title)
                textSize = 14f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            },
        )
        addView(
            routingModeDetailText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
    }
    routingModeValueText = TextView(this).apply {
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        includeFontPadding = false
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
    }
    routingModeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setSelectableBackground()
        isClickable = true
        isFocusable = true
        setOnClickListener { showRoutingModeSelector() }
        addView(
            routingText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            routingModeValueText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(16) },
        )
        addView(
            TextView(this@buildAdvancedScreen).apply {
                setText(R.string.chevron_forward)
                textSize = 22f
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEAL)
                includeFontPadding = false
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
    }
    routingPanel.addView(
        routingModeRow,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    adBlockCheckbox = MaterialSwitch(this).apply {
        isChecked = routingModePreferenceStore.isAdBlockEnabled()
        contentDescription = getString(R.string.ad_block_title)
        setOnClickListener { saveAdBlockEnabled(isChecked) }
    }
    routingPanel.addView(
        advancedToggleRow(
            title = getString(R.string.ad_block_title),
            detail = getString(R.string.ad_block_description),
            toggle = adBlockCheckbox,
        ),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    connectionSettings.addView(
        advancedSectionLabel(getString(R.string.dns_privacy_section)),
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(24)
        },
    )
    val dnsPanel = advancedSettingsPanel()
    connectionSettings.addView(
        dnsPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    dnsPrivacyDetailText = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
    }
    val dnsText = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            TextView(this@buildAdvancedScreen).apply {
                setText(R.string.dns_encrypted_title)
                textSize = 14f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            },
        )
        addView(
            dnsPrivacyDetailText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
            },
        )
    }
    dnsPrivacyValueText = TextView(this).apply {
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        includeFontPadding = false
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
    }
    dnsPrivacyRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setSelectableBackground()
        isClickable = true
        isFocusable = true
        setOnClickListener { showDnsPrivacySelector() }
        addView(
            dnsText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            dnsPrivacyValueText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(16) },
        )
        addView(
            TextView(this@buildAdvancedScreen).apply {
                setText(R.string.chevron_forward)
                textSize = 22f
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEAL)
                includeFontPadding = false
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
    }
    dnsPanel.addView(
        dnsPrivacyRow,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    dnsPrivacyEndpointInput = TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            if (commitDnsPrivacyEndpoint(reconnectIfChanged = true, focusOnError = true)) {
                clearFocus()
            }
            true
        }
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                scrollFieldIntoView(scrollView, dnsPrivacyEndpointLayout)
            } else {
                commitDnsPrivacyEndpoint(reconnectIfChanged = true)
            }
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!dnsPrivacyInputUpdating && dnsPrivacyErrorTextReady) {
                    dnsPrivacyErrorText.visibility = View.GONE
                }
            }
        })
    }
    dnsPrivacyEndpointLayout = TextInputLayout(this).apply {
        hint = getString(R.string.dns_server_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        boxStrokeWidth = dp(1)
        boxStrokeWidthFocused = dp(1)
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(dnsPrivacyEndpointInput)
    }
    dnsPanel.addView(
        dnsPrivacyEndpointLayout,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(12)
        },
    )
    dnsPrivacyErrorText = TextView(this).apply {
        textSize = 12f
        setTextColor(ERROR)
        includeFontPadding = true
        visibility = View.GONE
    }
    dnsPanel.addView(
        dnsPrivacyErrorText,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        },
    )
    // ── Orbot / Tor engine ───────────────────────────────────────────────────────────
    // A path that depends on nothing of ours. Orbot runs Tor on this phone and listens on
    // loopback, so while this is on the whole runtime goes out through 127.0.0.1:9050
    // (global-proxy shape, subscription rules dropped — see flClashRuntimeYaml). A switch
    // that is ON while Orbot is closed is a lie, so the row carries a live verdict and a
    // probe button instead of just a toggle.
    sharingSettings.addView(
        advancedSectionLabel(getString(R.string.orbot_section)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    val orbotPanel = advancedSettingsPanel()
    val orbotSwitch = MaterialSwitch(this).apply {
        contentDescription = getString(R.string.orbot_title)
        isChecked = orbotEnginePreferenceStore.isEnabled()
        setOnClickListener {
            orbotEnginePreferenceStore.setEnabled(isChecked)
            DiagnosticLogger.info(this@buildAdvancedScreen, "activity.orbot.saved", "enabled=$isChecked")
            renderOrbotRow()
            reconnectForConnectionOptionChange()
        }
    }
    orbotEngineCheckbox = orbotSwitch
    orbotPanel.addView(
        advancedToggleRow(
            title = getString(R.string.orbot_title),
            detail = getString(R.string.orbot_detail),
            toggle = orbotSwitch,
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    orbotStatusText = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = true
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
    }
    orbotPanel.addView(
        orbotStatusText,
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(4)
        },
    )
    val dnsttEvidenceButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        fun refreshLabel() {
            text = when (orbotEnginePreferenceStore.readDnsttEvidence()) {
                DnsttTransportEvidence.CONFIGURED -> getString(R.string.dnstt_evidence_configured)
                DnsttTransportEvidence.NOT_CONFIGURED -> getString(R.string.dnstt_evidence_not_configured)
                DnsttTransportEvidence.UNKNOWN -> getString(R.string.dnstt_evidence_unknown)
            }
        }
        refreshLabel()
        setOnClickListener {
            MaterialAlertDialogBuilder(this@buildAdvancedScreen)
                .setTitle(R.string.dnstt_evidence_title)
                .setItems(arrayOf(
                    getString(R.string.dnstt_evidence_configured),
                    getString(R.string.dnstt_evidence_not_configured),
                    getString(R.string.dnstt_evidence_unknown),
                )) { _, index ->
                    val selected = arrayOf(
                        DnsttTransportEvidence.CONFIGURED,
                        DnsttTransportEvidence.NOT_CONFIGURED,
                        DnsttTransportEvidence.UNKNOWN,
                    )[index]
                    orbotEnginePreferenceStore.setDnsttEvidence(selected)
                    refreshLabel()
                }
                .setNegativeButton(R.string.split_tunnel_cancel, null)
                .create()
                .showCatClientDialog()
        }
    }
    orbotPanel.addView(dnsttEvidenceButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
    val watchdogSwitch = MaterialSwitch(this).apply {
        contentDescription = getString(R.string.watchdog_title)
        isChecked = pathWatchdogStore.isArmed()
        setOnClickListener {
            pathWatchdogStore.setArmed(isChecked)
            DiagnosticLogger.info(this@buildAdvancedScreen, "activity.watchdog.saved", "armed=$isChecked")
            renderOrbotRow()
            reconnectForConnectionOptionChange()
        }
    }
    watchdogToggle = watchdogSwitch
    orbotPanel.addView(
        advancedToggleRow(
            title = getString(R.string.watchdog_title),
            detail = getString(R.string.watchdog_detail),
            toggle = watchdogSwitch,
        ),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    watchdogStatusText = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = true
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
    }
    orbotPanel.addView(
        watchdogStatusText,
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(4)
        },
    )
    orbotProbeButton = MaterialButton(this).apply {
        setText(R.string.orbot_probe)
        textSize = 13f
        minHeight = dp(44)
        minWidth = dp(44)
        setOnClickListener { probeOrbotEngine(showToast = true) }
    }
    orbotPanel.addView(
        orbotProbeButton,
        LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            topMargin = dp(8)
        },
    )
    sharingSettings.addView(
        orbotPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    renderOrbotRow()
    sharingSettings.addView(
        advancedSectionLabel(getString(R.string.lan_sharing_section)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    val lanSharingPanel = advancedSettingsPanel()
    lanSharingCheckbox = MaterialSwitch(this).apply {
        contentDescription = getString(R.string.lan_sharing_title)
        setOnClickListener { saveLanSharingEnabled(isChecked) }
    }
    lanSharingPanel.addView(
        advancedToggleRow(
            title = getString(R.string.lan_sharing_title),
            detail = getString(R.string.lan_sharing_description),
            toggle = lanSharingCheckbox,
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    lanSharingPasswordCheckbox = MaterialSwitch(this).apply {
        contentDescription = getString(R.string.lan_sharing_require_password)
        setOnClickListener { saveLanSharingPasswordRequired(isChecked) }
    }
    lanSharingPanel.addView(
        advancedToggleRow(
            title = getString(R.string.lan_sharing_require_password),
            detail = getString(R.string.lan_sharing_require_password_description),
            toggle = lanSharingPasswordCheckbox,
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    lanSharingPanel.addView(
        advancedSectionDetail(getString(R.string.lan_sharing_manual_setup)),
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(8)
        },
    )
    lanSharingDetailsText = TextView(this).apply {
        textSize = 13f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        setTextIsSelectable(true)
        includeFontPadding = false
        setLineSpacing(dp(5).toFloat(), 1f)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(TEAL, 28)),
            GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(withAlpha(SURFACE, if (palette.isDark) 232 else 246))
                setStroke(dp(1), withAlpha(OUTLINE, 170), dp(5).toFloat(), dp(4).toFloat())
            },
            null,
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { copyLanSharingSettings() }
    }
    lanSharingPanel.addView(
        lanSharingDetailsText,
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(12)
        },
    )
    lanSharingRegenerateButton = MaterialButton(this).apply {
        setText(R.string.lan_sharing_regenerate)
        setAllCaps(false)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        minWidth = 0
        minHeight = dp(44)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(8)
        backgroundTintList = ColorStateList.valueOf(SURFACE)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(OUTLINE)
        setTextColor(TEAL)
        setOnClickListener { regenerateLanSharingPassword() }
    }
    lanSharingPanel.addView(
        lanSharingRegenerateButton,
        LinearLayout.LayoutParams(-1, dp(44)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(12)
            bottomMargin = dp(16)
        },
    )
    sharingSettings.addView(
        lanSharingPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    systemSettings.addView(
        advancedSectionLabel(getString(R.string.always_on_section)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    val alwaysOnPanel = advancedSettingsPanel()
    alwaysOnStatusText = TextView(this).apply {
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        includeFontPadding = false
    }
    alwaysOnPanel.addView(
        alwaysOnStatusText,
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(16)
        },
    )
    alwaysOnPanel.addView(
        advancedSectionDetail(getString(R.string.always_on_description)),
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(6)
        },
    )
    alwaysOnPanel.addView(
        MaterialButton(this).apply {
            setText(R.string.always_on_open_settings)
            setAllCaps(false)
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 24))
            setTextColor(TEAL)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            }
        },
        LinearLayout.LayoutParams(-2, dp(44)).apply {
            gravity = Gravity.END
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(14)
            bottomMargin = dp(16)
        },
    )
    systemSettings.addView(
        alwaysOnPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    renderAlwaysOnStatus()

    systemSettings.addView(
        advancedSectionLabel(getString(R.string.diagnostics_section)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
    )
    val diagnosticsPanel = advancedSettingsPanel()
    diagnosticsPanel.addView(
        advancedSectionDetail(getString(R.string.diagnostics_description)),
        LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(16)
        },
    )
    val diagnosticsActions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(12), dp(10), dp(12), dp(12))
    }
    fun diagnosticsButton(@StringRes labelRes: Int, action: (View) -> Unit) =
        MaterialButton(this).apply {
            setText(labelRes)
            setAllCaps(false)
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(9)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEXT_SECONDARY, 38))
            setTextColor(TEXT_PRIMARY)
            setPaddingRelative(dp(8), 0, dp(8), 0)
            setOnClickListener(action)
        }
    diagnosticsActions.addView(
        diagnosticsButton(R.string.diagnostics_copy) { copyDiagnosticsToClipboard() },
        LinearLayout.LayoutParams(0, dp(44), 1f),
    )
    diagnosticsActions.addView(
        diagnosticsButton(R.string.diagnostics_share) { shareDiagnostics() },
        LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) },
    )
    diagnosticsPanel.addView(diagnosticsActions, LinearLayout.LayoutParams(-1, -2))
    // 🩺 The owner's 1101 question, answerable on the phone: ask the three URLs and
    // print the raw heads. «The code ran» and «the code never ran» need opposite
    // actions — re-upload the file vs. run the recovery flow — and the error page
    // itself says nothing about which one you have.
    diagnosticsPanel.addView(
        diagnosticsButton(R.string.panel_probe_button) { showPanelProbeDialog() },
        LinearLayout.LayoutParams(-2, dp(44)).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(8)
        },
    )
    // 📄 The last crash IS IN THE APP (beta99): the Downloads copy is a file the
    // owner cannot always hand over — this section shows the same text and copies
    // it with one tap, so a crash report is never «sent but lost» again.
    val crashReport = runCatching { CrashWatch.lastCrashText(this) }.getOrDefault("")
    if (crashReport.isNotBlank()) {
        diagnosticsPanel.addView(
            advancedSectionLabel(getString(R.string.crash_report_section)),
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(14)
            },
        )
        diagnosticsPanel.addView(
            android.widget.ScrollView(this).apply {
                background = glassSurfaceDrawable(radiusDp = 12)
                addView(
                    TextView(this@buildAdvancedScreen).apply {
                        text = crashReport
                        textSize = 10.5f
                        typeface = CatClientDataTypeface
                        setTextColor(TEXT_SECONDARY)
                        // A stack trace must stay LTR and selectable: in the RTL
                        // layout the class names reorder into gibberish.
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                        textDirection = View.TEXT_DIRECTION_LTR
                        setTextIsSelectable(true)
                        includeFontPadding = false
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    },
                    android.widget.FrameLayout.LayoutParams(-1, -2),
                )
            },
            LinearLayout.LayoutParams(-1, dp(170)).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(8)
            },
        )
        diagnosticsPanel.addView(
            diagnosticsButton(R.string.crash_report_copy) { copyCrashToClipboard() },
            LinearLayout.LayoutParams(-2, dp(44)).apply {
                gravity = Gravity.END
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(8)
            },
        )
    }
    diagnosticsPanel.addView(
        MaterialButton(this).apply {
            setText(R.string.diagnostics_clear)
            setAllCaps(false)
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(9)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(ERROR)
            setOnClickListener {
                DiagnosticLogger.clear(this@buildAdvancedScreen)
                Toast.makeText(this@buildAdvancedScreen, R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show()
            }
        },
        LinearLayout.LayoutParams(-2, dp(42)).apply {
            gravity = Gravity.END
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(8)
        },
    )
    systemSettings.addView(
        diagnosticsPanel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )

    scrollView.addView(
        advancedBody,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    scrollView.visibility = View.GONE

    val indexBody = MaxWidthLinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        maxWidthPx = dp(520)
        setPadding(dp(24), dp(34), dp(24), dp(104))
        if (appDesign != AppDesign.Old) {
            setPadding(dp(16), dp(20), dp(16), dp(104))
            addView(
                zedPageHero(
                    getString(R.string.settings_title),
                    getString(R.string.settings_hero_subtitle),
                    ZedHeroArtView(this@buildAdvancedScreen, zedScheme),
                ).apply { setPaddingRelative(dp(10), 0, 0, 0) },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(zedGuideCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        } else addView(
            TextView(this@buildAdvancedScreen).apply {
                setText(R.string.settings_title)
                textSize = 30f
                typeface = CatClientDisplayTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            },
        )
        if (appDesign == AppDesign.Old) addView(
            TextView(this@buildAdvancedScreen).apply {
                setText(R.string.settings_categories_description)
                textSize = 14f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
    }
    val indexScrollView = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(withAlpha(BACKGROUND, 0))
        addView(indexBody, ViewGroup.LayoutParams(-1, -2))
    }
    ViewCompat.setOnApplyWindowInsetsListener(indexScrollView) { view, insets ->
        val topInset = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
        ).top
        val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        view.setPadding(view.paddingLeft, topInset, view.paddingRight, bottomInset)
        insets
    }

    fun closeCategory() {
        currentFocus?.clearFocus()
        activeSettingsCategoryTitleRes = 0
        advancedSettingsBackAction = null
        scrollView.visibility = View.GONE
        indexScrollView.visibility = View.VISIBLE
        indexScrollView.scrollTo(0, 0)
        ViewCompat.requestApplyInsets(indexScrollView)
    }

    fun showCategory(@StringRes titleRes: Int, content: View) {
        activeSettingsCategoryTitleRes = titleRes
        advancedBody.removeAllViews()
        (content.parent as? ViewGroup)?.removeView(content)
        val backButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
            setBackgroundColor(Color.TRANSPARENT)
            setSelectableBackground()
            contentDescription = getString(R.string.settings_category_back)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { closeCategory() }
        }
        advancedBody.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
                addView(
                    TextView(this@buildAdvancedScreen).apply {
                        setText(titleRes)
                        textSize = 24f
                        typeface = CatClientDisplayTypeface
                        setTextColor(TEXT_PRIMARY)
                        includeFontPadding = false
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        textDirection = View.TEXT_DIRECTION_LOCALE
                        // the Interface page carries its own big Zed title + art
                        if (titleRes == R.string.settings_category_appearance) visibility = View.INVISIBLE
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        advancedBody.addView(
            View(this).apply { setBackgroundColor(OUTLINE) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16) },
        )
        advancedBody.addView(content, LinearLayout.LayoutParams(-1, -2))
        indexScrollView.visibility = View.GONE
        ViewCompat.setAccessibilityPaneTitle(scrollView, getString(titleRes))
        scrollView.visibility = View.VISIBLE
        scrollView.scrollTo(0, 0)
        ViewCompat.requestApplyInsets(scrollView)
        advancedSettingsBackAction = { closeCategory() }
        renderAdvancedControls()
    }

    val categoriesPanel = advancedSettingsPanel()
    // ZedSecure 3.1.6 Settings: grouped expressive lists under small section labels.
    val zedPersonalizeGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val zedConnectionGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val zedSystemGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun addCategory(
        @StringRes titleRes: Int,
        @StringRes detailRes: Int,
        content: View,
        addDivider: Boolean = true,
        onOpen: (() -> Unit)? = null,
        badge: View? = null,
    ) {
        settingsCategoryOpeners[titleRes] = {
            showCategory(titleRes, content)
            onOpen?.invoke()
        }
        if (appDesign != AppDesign.Old) {
            data class ZedSpec(val group: LinearLayout, val shape: ZedShape, val hue: Float, val icon: Int)
            val spec = when (titleRes) {
                R.string.settings_category_appearance -> ZedSpec(zedPersonalizeGroup, ZedShape.Flower, 255f, R.drawable.ic_zed_palette)
                R.string.settings_category_app_preferences -> ZedSpec(zedPersonalizeGroup, ZedShape.Puffy, 24f, R.drawable.ic_zed_tune)
                R.string.settings_category_testing -> ZedSpec(zedPersonalizeGroup, ZedShape.Cookie9, 58f, R.drawable.ic_speedometer)
                R.string.settings_category_connections -> ZedSpec(zedConnectionGroup, ZedShape.Burst, 150f, R.drawable.ic_zed_swap)
                R.string.connection_chain_title -> ZedSpec(zedConnectionGroup, ZedShape.Gem, 195f, R.drawable.ic_zed_link)
                R.string.split_tunnel_label -> ZedSpec(zedConnectionGroup, ZedShape.Diamond, 62f, R.drawable.ic_zed_split)
                R.string.settings_category_sharing -> ZedSpec(zedSystemGroup, ZedShape.Clover4, 285f, R.drawable.ic_zed_share)
                R.string.settings_category_system -> ZedSpec(zedSystemGroup, ZedShape.Cookie12, 215f, R.drawable.ic_advanced_tab)
                R.string.update_settings_title -> ZedSpec(zedSystemGroup, ZedShape.Sunny, 120f, R.drawable.ic_zed_download)
                else -> ZedSpec(zedSystemGroup, ZedShape.Pentagon, 330f, R.drawable.ic_zed_description)
            }
            spec.group.addView(
                zedSettingsItem(getString(titleRes), getString(detailRes), spec.shape, spec.hue, spec.icon, badge) {
                    showCategory(titleRes, content)
                    onOpen?.invoke()
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        categoriesPanel.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(76)
                setPadding(dp(16), dp(14), dp(12), dp(14))
                setSelectableBackground()
                isClickable = true
                isFocusable = true
                contentDescription = getString(titleRes)
                setOnClickListener {
                    showCategory(titleRes, content)
                    onOpen?.invoke()
                }
                // ZedSecure SettingsItem: tinted rounded-square leading icon.
                val glyph = when (titleRes) {
                    R.string.settings_category_appearance -> "🎨"
                    R.string.settings_category_app_preferences -> "✦"
                    R.string.settings_category_testing -> "⏱"
                    R.string.settings_category_connections -> "⇄"
                    R.string.connection_chain_title -> "⛓"
                    R.string.split_tunnel_label -> "▦"
                    R.string.settings_category_sharing -> "⇪"
                    R.string.settings_category_system -> "⚙"
                    R.string.update_settings_title -> "⬆"
                    else -> "•"
                }
                addView(
                    TextView(this@buildAdvancedScreen).apply {
                        text = glyph
                        textSize = 18f
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        setTextColor(TEAL)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(14).toFloat()
                            setColor(withAlpha(TEAL, 40))
                        }
                    },
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) },
                )
                addView(
                    LinearLayout(this@buildAdvancedScreen).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(
                            TextView(this@buildAdvancedScreen).apply {
                                setText(titleRes)
                                textSize = 16f
                                typeface = CatClientBodyBoldTypeface
                                setTextColor(TEXT_PRIMARY)
                                includeFontPadding = false
                            },
                        )
                        addView(
                            TextView(this@buildAdvancedScreen).apply {
                                setText(detailRes)
                                textSize = 12f
                                typeface = CatClientBodyTypeface
                                setTextColor(TEXT_SECONDARY)
                                includeFontPadding = false
                                maxLines = 2
                            },
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                        )
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                badge?.let {
                    addView(it, LinearLayout.LayoutParams(-2, -2).apply {
                        marginStart = dp(12)
                        marginEnd = dp(12)
                    })
                }
                addView(
                    TextView(this@buildAdvancedScreen).apply {
                        setText(R.string.chevron_forward)
                        textSize = 22f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEAL)
                        includeFontPadding = false
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                        textDirection = View.TEXT_DIRECTION_LTR
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) },
                )
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        if (addDivider) {
            categoriesPanel.addView(
                View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 90)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(74)
                    marginEnd = dp(16)
                },
            )
        }
    }
    addCategory(
        R.string.settings_category_appearance,
        R.string.settings_category_appearance_detail,
        appearanceSettings,
    )
    addCategory(
        R.string.settings_category_app_preferences,
        R.string.settings_category_app_preferences_detail,
        appPreferencesSettings,
    )
    addCategory(
        R.string.settings_category_testing,
        R.string.settings_category_testing_detail,
        testingSettings,
    )
    addCategory(
        R.string.settings_category_connections,
        R.string.settings_category_connections_detail,
        connectionSettings,
    )
    openConnectionChainSettingsPage = {
        showCategory(R.string.connection_chain_title, chainSettings)
    }
    addCategory(
        R.string.connection_chain_title,
        R.string.connection_chain_category_detail,
        chainSettings,
    )
    addCategory(
        R.string.split_tunnel_label,
        R.string.settings_category_split_tunnel_detail,
        splitTunnelSettings,
        onOpen = { showSplitTunnelSelector(splitTunnelSettings) },
    )
    addCategory(
        R.string.settings_category_sharing,
        R.string.settings_category_sharing_detail,
        sharingSettings,
    )
    addCategory(
        R.string.settings_category_system,
        R.string.settings_category_system_detail,
        systemSettings,
    )
    if (appDesign == AppDesign.Old) categoriesPanel.addView(
        MaterialButton(this).apply {
            setText(R.string.settings_reset)
            setAllCaps(false)
            textSize = 16f
            typeface = CatClientBodyBoldTypeface
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(64)
            insetTop = 0
            insetBottom = 0
            cornerRadius = 0
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            rippleColor = ColorStateList.valueOf(withAlpha(ERROR, 24))
            setTextColor(ERROR)
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { showResetSettingsDialog() }
        },
        LinearLayout.LayoutParams(-1, dp(64)),
    )
    if (appDesign == AppDesign.Old) categoriesPanel.addView(
        View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
        LinearLayout.LayoutParams(-1, dp(1)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        },
    )
    updateSettingsBadge = TextView(this).apply {
        setText(R.string.update_badge)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        includeFontPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    addCategory(
        R.string.update_settings_title,
        R.string.update_check,
        appUpdateUi.createView(advancedSettingsPanel()),
        addDivider = false,
        badge = updateSettingsBadge,
    )
    indexBody.addView(featureFinderBar(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    if (appDesign == AppDesign.Old) {
        indexBody.addView(
            categoriesPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
        )
    } else {
        zedSystemGroup.addView(
            zedSettingsItem(getString(R.string.settings_reset), null, ZedShape.Pentagon, 0f, R.drawable.ic_zed_restore, danger = true) {
                showResetSettingsDialog()
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        listOf(
            R.string.settings_section_personalize to zedPersonalizeGroup,
            R.string.settings_section_connection to zedConnectionGroup,
            R.string.settings_section_system to zedSystemGroup,
        ).forEach { (labelRes, group) ->
            if (group.childCount == 0) return@forEach
            zedApplyGroupCorners(group)
            indexBody.addView(zedSectionLabel(labelRes), LinearLayout.LayoutParams(-1, -2))
            indexBody.addView(group, LinearLayout.LayoutParams(-1, -2))
        }
    }
    val footerCopyrightText = TextView(this).apply {
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        // Version stays visible on the home footer; tapping it runs the
        // in-app update check (GitHub release only — never an API token).
        text = getString(R.string.footer_copyright) + "  ·  v" + BuildConfig.VERSION_NAME
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        minHeight = dp(44)
        setSelectableBackground()
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.update_check)
        setOnClickListener {
            Toast.makeText(this@buildAdvancedScreen, getString(R.string.update_installed_version, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
            checkForUpdates()
        }
    }
    val footerTelegramLink = TextView(this).apply {
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        text = getString(R.string.footer_url)
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minHeight = dp(44)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_telegram, 0, 0, 0)
        compoundDrawablePadding = dp(6)
        compoundDrawableTintList = ColorStateList.valueOf(TEXT_SECONDARY)
        setSelectableBackground()
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.footer_telegram_content_description)
        setOnClickListener { openFooterLink() }
    }
    indexBody.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(footerCopyrightText, LinearLayout.LayoutParams(-1, -2))
            addView(footerTelegramLink, LinearLayout.LayoutParams(-2, dp(44)))
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) },
    )

    root.addView(indexScrollView, FrameLayout.LayoutParams(-1, -1))
    root.addView(scrollView, FrameLayout.LayoutParams(-1, -1))
    renderAdvancedControls()
    return root
}

internal fun MainActivity.buildConnectionChainSettings(): View {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    content.addView(
        advancedSectionLabel(getString(R.string.connection_chain_title)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) },
    )
    val panel = advancedSettingsPanel()
    val enabledSwitch = MaterialSwitch(this)
    panel.addView(
        advancedToggleRow(
            title = getString(R.string.connection_chain_enable),
            detail = getString(R.string.connection_chain_enable_detail),
            toggle = enabledSwitch,
        ),
        LinearLayout.LayoutParams(-1, -2),
    )
    val hops = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    panel.addView(hops, LinearLayout.LayoutParams(-1, -2))
    content.addView(
        panel,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )

    fun hopRow(
        slot: ConnectionChainSlot,
        hop: ConnectionChainHop,
        options: List<ConnectionChainPickerOption>,
        controlsEnabled: Boolean,
    ): View {
        val optional = slot != ConnectionChainSlot.Base
        val title = when {
            hop.mode == ConnectionChainHopMode.Off && slot == ConnectionChainSlot.Before ->
                getString(R.string.connection_chain_add_before)
            hop.mode == ConnectionChainHopMode.Off && slot == ConnectionChainSlot.After ->
                getString(R.string.connection_chain_add_after)
            slot == ConnectionChainSlot.Before -> getString(R.string.connection_chain_before)
            slot == ConnectionChainSlot.Base -> getString(R.string.connection_label)
            else -> getString(R.string.connection_chain_after)
        }
        val pinned = hop.serverOverride?.takeIf { it.isNotBlank() }
        val baseDetail = when (hop.mode) {
            ConnectionChainHopMode.Off -> getString(R.string.connection_chain_optional_detail)
            ConnectionChainHopMode.Automatic -> getString(R.string.connection_chain_automatic_detail)
            ConnectionChainHopMode.Fixed -> connectionChainFixedLabel(hop.profileRef, options)
        }
        val detail = if (pinned == null) {
            baseDetail
        } else {
            baseDetail + " · " + getString(R.string.connection_chain_hop_ip_value, pinned)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            setSelectableBackground()
            isEnabled = controlsEnabled
            isClickable = controlsEnabled
            isFocusable = controlsEnabled
            if (controlsEnabled) setOnClickListener { showConnectionTestingPage(chainSlot = slot) }
            addView(
                LinearLayout(this@buildConnectionChainSettings).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(
                        TextView(this@buildConnectionChainSettings).apply {
                            text = title
                            textSize = 15f
                            typeface = CatClientBodyBoldTypeface
                            setTextColor(if (hop.mode == ConnectionChainHopMode.Off) TEAL else TEXT_PRIMARY)
                            includeFontPadding = false
                        },
                    )
                    addView(
                        TextView(this@buildConnectionChainSettings).apply {
                            text = detail
                            textSize = 12f
                            typeface = CatClientBodyTypeface
                            setTextColor(TEXT_SECONDARY)
                            includeFontPadding = false
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            if (hop.mode != ConnectionChainHopMode.Off) {
                addView(
                    TextView(this@buildConnectionChainSettings).apply {
                        text = getString(R.string.connection_chain_hop_ip_chip)
                        textSize = 12f
                        typeface = CatClientBodyBoldTypeface
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        minimumHeight = dp(44)
                        minimumWidth = dp(44)
                        setTextColor(if (pinned == null) TEXT_SECONDARY else TEAL)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setStroke(dp(1), withAlpha(if (pinned == null) OUTLINE else TEAL, 160))
                        }
                        contentDescription = getString(
                            R.string.connection_chain_hop_ip_action,
                            getString(connectionChainSlotLabelRes(slot)),
                        )
                        isEnabled = controlsEnabled
                        isClickable = controlsEnabled
                        isFocusable = controlsEnabled
                        if (controlsEnabled) {
                            setOnClickListener { editConnectionChainHopIp(slot, hop) }
                        }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                )
            }
            if (optional && hop.mode != ConnectionChainHopMode.Off) {
                addView(
                    TextView(this@buildConnectionChainSettings).apply {
                        text = "×"
                        textSize = 24f
                        gravity = Gravity.CENTER
                        setTextColor(TEXT_SECONDARY)
                        contentDescription = getString(
                            R.string.connection_chain_remove_hop,
                            getString(
                                when (slot) {
                                    ConnectionChainSlot.Before -> R.string.connection_chain_before
                                    ConnectionChainSlot.Base -> R.string.connection_chain_base
                                    ConnectionChainSlot.After -> R.string.connection_chain_after
                                },
                            ),
                        )
                        setSelectableBackground()
                        isEnabled = controlsEnabled
                        isClickable = controlsEnabled
                        isFocusable = controlsEnabled
                        if (controlsEnabled) {
                            setOnClickListener {
                                updateConnectionChainSettings(
                                    connectionChainPreferenceStore.read()
                                        .withHop(slot, ConnectionChainHop.off()),
                                )
                            }
                        }
                    },
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
                )
            } else {
                addView(
                    TextView(this@buildConnectionChainSettings).apply {
                        setText(R.string.chevron_forward)
                        textSize = 22f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEAL)
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) },
                )
            }
        }
    }

    fun render() {
        val settings = connectionChainPreferenceStore.read()
        val controlsEnabled = buttonModel.state != VpnState.Starting &&
            buttonModel.state != VpnState.Stopping
        if (enabledSwitch.isChecked != settings.enabled) enabledSwitch.isChecked = settings.enabled
        enabledSwitch.isEnabled = controlsEnabled
        hops.removeAllViews()
        hops.visibility = if (settings.enabled) View.VISIBLE else View.GONE
        hops.alpha = if (controlsEnabled) 1f else 0.45f
        if (!settings.enabled) {
            connectionChainCompatibilityIssue = null
            connectionChainCompatibilityMessage = null
            pendingConnectionChainAccessibilityAnnouncement = null
            return
        }
        if (!hops.isShown) return
        val fixedSubscriptionIds = ConnectionChainSlot.entries
            .map { slot -> settings.hop(slot) }
            .mapNotNull { hop ->
                hop.profileRef?.subscriptionId.takeIf {
                    hop.mode == ConnectionChainHopMode.Fixed
                }
            }
            .toSet()
        val fixedSources = cachedConnectionChainSources(fixedSubscriptionIds)
        val options = connectionChainFixedOptions(settings, fixedSources)
        hops.addView(
            advancedSectionDetail(getString(R.string.connection_chain_order_detail)),
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(10)
                bottomMargin = dp(4)
            },
        )
        val compatibilityIssue = ConnectionChainPlanner.selectedCompatibilityIssue(settings, fixedSources)
        val compatibilityChanged = compatibilityIssue != connectionChainCompatibilityIssue
        connectionChainCompatibilityIssue = compatibilityIssue
        if (compatibilityIssue == null) {
            connectionChainCompatibilityMessage = null
            pendingConnectionChainAccessibilityAnnouncement = null
        }
        compatibilityIssue?.let { issue ->
            val message = getString(
                when (issue) {
                    ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable ->
                        R.string.connection_chain_error_unavailable
                    ConnectionChainCompatibilityIssue.SameConnection ->
                        R.string.connection_chain_error_same
                    ConnectionChainCompatibilityIssue.SharedProxyDependency ->
                        R.string.connection_chain_error_shared_proxy
                    ConnectionChainCompatibilityIssue.DownstreamRequiresUdpCapableUpstream ->
                        R.string.connection_chain_error_udp
                },
            )
            connectionChainCompatibilityMessage = message
            hops.addView(
                TextView(this).apply {
                    text = "⚠  $message"
                    textSize = 12f
                    typeface = CatClientBodyTypeface
                    setTextColor(ERROR)
                    includeFontPadding = false
                    gravity = Gravity.START
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    contentDescription = message
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(withAlpha(ERROR, 18))
                        setStroke(dp(1), withAlpha(ERROR, 110))
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    marginStart = dp(16)
                    marginEnd = dp(16)
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                },
            )
            if (compatibilityChanged) {
                if (connectionTestingPageVisible) {
                    pendingConnectionChainAccessibilityAnnouncement = message
                } else {
                    hops.post { if (hops.isShown) hops.announceAccessibility(message) }
                }
            }
        }
        ConnectionChainSlot.entries.forEachIndexed { index, slot ->
            if (index > 0) {
                hops.addView(
                    View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    },
                )
            }
            hops.addView(
                hopRow(slot, settings.hop(slot), options, controlsEnabled),
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }
    renderChainSettingsPage = ::render
    enabledSwitch.setOnCheckedChangeListener { _, enabled ->
        val current = connectionChainPreferenceStore.read()
        if (current.enabled != enabled) updateConnectionChainSettings(current.copy(enabled = enabled))
    }
    render()
    return content
}

internal fun MainActivity.advancedToggleRow(
    title: String,
    detail: String,
    toggle: MaterialSwitch,
): View {
    val toggleStates = arrayOf(
        intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
        intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
        intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
        intArrayOf(-android.R.attr.state_enabled, -android.R.attr.state_checked),
    )
    toggle.thumbTintList = ColorStateList(
        toggleStates,
        intArrayOf(SURFACE, SURFACE, withAlpha(SURFACE, 140), withAlpha(SURFACE, 140)),
    )
    toggle.trackTintList = ColorStateList(
        toggleStates,
        intArrayOf(
            TEAL,
            withAlpha(TEXT_SECONDARY, 190),
            withAlpha(TEAL, 80),
            withAlpha(TEXT_SECONDARY, 80),
        ),
    )
    val textColumn = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(
            TextView(this@advancedToggleRow).apply {
                text = title
                textSize = 14f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            },
        )
        addView(
            TextView(this@advancedToggleRow).apply {
                text = detail
                textSize = 12f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
            },
        )
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(12), dp(12), dp(16), dp(12))
        setSelectableBackground()
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        toggle.contentDescription = "$title. $detail"
        setOnClickListener {
            if (toggle.isEnabled) toggle.performClick()
        }
        addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
    }
}

internal fun MainActivity.showSplitTunnelSettings(
    container: LinearLayout,
    apps: List<SplitTunnelInstalledApp>,
) {
    val launchablePackages = apps.map { it.packageName }.toSet()
    val savedSettings = splitTunnelPreferenceStore.readSettings()
    val prunedSettings = SplitTunnelPolicy.sanitizeSettings(
        savedSettings.copy(
            selectedPackages = savedSettings.selectedPackages.filter { it in launchablePackages }.toSet(),
        ),
        packageName,
    )
    if (prunedSettings.selectedPackages != savedSettings.selectedPackages) {
        splitTunnelPreferenceStore.saveSettings(prunedSettings)
        DiagnosticLogger.info(
            this,
            "activity.splitTunnel.pruned",
            "before=${savedSettings.selectedPackages.size} after=${prunedSettings.selectedPackages.size}",
        )
    }

    var currentMode = prunedSettings.mode
    val selectedPackages = prunedSettings.selectedPackages.toMutableSet()
    var controlsEnabled = buttonModel.state != VpnState.Starting && buttonModel.state != VpnState.Stopping

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(0, dp(24), 0, dp(24))
    }

    val modeGroup = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = glassSurfaceDrawable(radiusDp = 12).apply {
            setStroke(dp(1), OUTLINE)
        }
        clipToOutline = true
    }
    val modeById = mutableMapOf<Int, SplitTunnelMode>()
    fun addModeButton(mode: SplitTunnelMode, label: String) {
        val id = View.generateViewId()
        modeById[id] = mode
        modeGroup.addView(
            RadioButton(this).apply {
                this.id = id
                text = label
                textSize = 14f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_PRIMARY)
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(TEAL, TEXT_SECONDARY),
                )
                setSelectableBackground()
                setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
                isChecked = currentMode == mode
            },
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    addModeButton(SplitTunnelMode.Off, getString(R.string.split_tunnel_mode_off))
    addModeButton(SplitTunnelMode.BypassSelected, getString(R.string.split_tunnel_mode_bypass))
    addModeButton(SplitTunnelMode.VpnOnlySelected, getString(R.string.split_tunnel_mode_vpn_only))

    val searchInput = TextInputEditText(this).apply {
        setSingleLine(true)
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        textSize = 14f
        typeface = CatClientBodyTypeface
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
    }
    val searchLayout = TextInputLayout(this).apply {
        hint = getString(R.string.split_tunnel_search_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        boxStrokeWidth = dp(1)
        boxStrokeWidthFocused = dp(1)
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(searchInput)
    }
    val selectedCountText = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        includeFontPadding = false
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    val appListHeight = minOf(dp(520), resources.displayMetrics.heightPixels * 55 / 100)
    val appList = object : ListView(this) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            parent.requestDisallowInterceptTouchEvent(true)
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        divider = ColorDrawable(OUTLINE)
        dividerHeight = dp(1)
        cacheColorHint = Color.TRANSPARENT
    }
    val emptyAppListText = TextView(this).apply {
        setText(
            if (apps.isEmpty()) {
                R.string.split_tunnel_empty_apps
            } else {
                R.string.split_tunnel_empty_search
            },
        )
        textSize = 14f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }
    val appListContainer = FrameLayout(this).apply {
        background = glassSurfaceDrawable(radiusDp = 12).apply {
            setStroke(dp(1), OUTLINE)
        }
        clipToOutline = true
        addView(
            appList,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            emptyAppListText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
    appList.emptyView = emptyAppListText
    val appPicker = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            searchLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            selectedCountText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )
        addView(
            appListContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                appListHeight,
            ).apply {
                topMargin = dp(8)
            },
        )
    }

    val saveButton = MaterialButton(this).apply {
        setText(R.string.split_tunnel_save)
        setAllCaps(false)
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(12)
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(BACKGROUND)
    }

    fun updateSaveState() {
        val requiresSelection = currentMode != SplitTunnelMode.Off
        val isValid = !requiresSelection || selectedPackages.isNotEmpty()
        saveButton.isEnabled = controlsEnabled && isValid
        selectedCountText.text = if (isValid) {
            getString(R.string.split_tunnel_selected_count, selectedPackages.size)
        } else {
            getString(R.string.split_tunnel_select_app_required)
        }
        selectedCountText.setTextColor(if (isValid) TEXT_SECONDARY else ERROR)
    }

    fun updateModeUi() {
        val pickerVisible = currentMode != SplitTunnelMode.Off
        appPicker.visibility = if (pickerVisible) View.VISIBLE else View.GONE
        if (!pickerVisible) searchInput.clearFocus()
        updateSaveState()
    }

    var filteredApps = apps
    val appIconCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    val fallbackAppIcon = packageManager.defaultActivityIcon

    class AppRowHolder(
        val checkBox: CheckBox,
        val icon: ImageView,
        val label: TextView,
        val packageName: TextView,
    )

    val appListAdapter = object : BaseAdapter() {
        override fun getCount(): Int = filteredApps.size

        override fun getItem(position: Int): SplitTunnelInstalledApp = filteredApps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row: LinearLayout
            val holder: AppRowHolder
            if (convertView == null) {
                val checkBox = CheckBox(this@showSplitTunnelSettings).apply {
                    buttonTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(),
                        ),
                        intArrayOf(TEAL, TEXT_SECONDARY),
                    )
                }
                val icon = ImageView(this@showSplitTunnelSettings).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val label = TextView(this@showSplitTunnelSettings).apply {
                    textSize = 14f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                }
                val packageName = TextView(this@showSplitTunnelSettings).apply {
                    textSize = 12f
                    typeface = CatClientBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                    textDirection = View.TEXT_DIRECTION_LTR
                }
                val appText = LinearLayout(this@showSplitTunnelSettings).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(label, LinearLayout.LayoutParams(-1, -2))
                    addView(
                        packageName,
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                    )
                }
                row = LinearLayout(this@showSplitTunnelSettings).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(8), dp(4), dp(12), dp(4))
                    setSelectableBackground()
                    isClickable = true
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    addView(checkBox, LinearLayout.LayoutParams(dp(48), dp(56)))
                    addView(
                        icon,
                        LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) },
                    )
                    addView(
                        appText,
                        LinearLayout.LayoutParams(0, -2, 1f).apply {
                            marginStart = dp(12)
                            marginEnd = dp(8)
                        },
                    )
                }
                holder = AppRowHolder(checkBox, icon, label, packageName)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as AppRowHolder
            }

            val app = getItem(position)
            val appTextGravity =
                if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    Gravity.RIGHT
                } else {
                    Gravity.LEFT
                }
            holder.label.text = app.label
            holder.label.gravity = appTextGravity
            holder.packageName.text = app.packageName
            holder.packageName.gravity = appTextGravity
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = app.packageName in selectedPackages
            holder.checkBox.isEnabled = controlsEnabled
            holder.checkBox.contentDescription = "${app.label}, ${app.packageName}"
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPackages += app.packageName
                } else {
                    selectedPackages -= app.packageName
                }
                updateSaveState()
            }
            row.isEnabled = controlsEnabled
            row.setOnClickListener {
                if (controlsEnabled) holder.checkBox.performClick()
            }

            holder.icon.tag = app.packageName
            val cachedIcon = appIconCache[app.packageName]
            holder.icon.setImageDrawable(cachedIcon ?: fallbackAppIcon)
            if (cachedIcon == null) {
                activityScope.launch {
                    val icon = withContext(Dispatchers.IO) {
                        runCatching { packageManager.getApplicationIcon(app.packageName) }
                            .getOrDefault(fallbackAppIcon)
                    }
                    appIconCache[app.packageName] = icon
                    if (holder.icon.tag == app.packageName) {
                        holder.icon.setImageDrawable(icon)
                    }
                }
            }

            return row
        }
    }
    appList.adapter = appListAdapter

    fun renderAppList(query: String) {
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        filteredApps = if (normalizedQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                    app.packageName.lowercase(Locale.US).contains(normalizedQuery)
            }
        }
        emptyAppListText.setText(
            if (apps.isEmpty()) {
                R.string.split_tunnel_empty_apps
            } else {
                R.string.split_tunnel_empty_search
            },
        )
        appListAdapter.notifyDataSetChanged()
        updateSaveState()
    }

    modeGroup.setOnCheckedChangeListener { _, checkedId ->
        currentMode = modeById[checkedId] ?: SplitTunnelMode.Off
        updateModeUi()
    }
    // beta217: filter the (often several hundred) installed apps once typing pauses, not per key press.
    searchInput.addTextChangedListener(DebouncedTextWatcher(searchInput, delayMs = 180L) { text -> renderAppList(text) })

    content.addView(
        modeGroup,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
    content.addView(
        saveButton,
        LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) },
    )
    content.addView(
        appPicker,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(16)
        },
    )

    saveButton.setOnClickListener {
        val nextSettings = SplitTunnelPolicy.sanitizeSettings(
            SplitTunnelSettings(
                mode = currentMode,
                selectedPackages = selectedPackages,
            ),
            packageName,
        )
        if (nextSettings.mode != SplitTunnelMode.Off && nextSettings.selectedPackages.isEmpty()) {
            updateSaveState()
            return@setOnClickListener
        }

        val previousSettings = splitTunnelPreferenceStore.readSettings()
        splitTunnelPreferenceStore.saveSettings(nextSettings)
        DiagnosticLogger.info(
            this@showSplitTunnelSettings,
            "activity.splitTunnel.saved",
            "mode=${nextSettings.mode.wireName} selected=${nextSettings.selectedPackages.size}",
        )
        if (buttonModel.state == VpnState.Started && previousSettings != nextSettings) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    updateSplitTunnelControlsEnabled = { enabled ->
        controlsEnabled = enabled
        for (index in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(index).isEnabled = enabled
        }
        searchInput.isEnabled = enabled
        appList.isEnabled = enabled
        appListAdapter.notifyDataSetChanged()
        content.alpha = if (enabled) 1f else 0.45f
        updateSaveState()
    }
    updateModeUi()
    container.removeAllViews()
    container.addView(content, LinearLayout.LayoutParams(-1, -2))
    updateSplitTunnelControlsEnabled?.invoke(controlsEnabled)
}

// ---- moved from MainActivity.kt in beta221 (code split phase 2) ----

internal fun MainActivity.showResetSettingsDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.settings_reset)
        .setMessage(R.string.settings_reset_message)
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .setPositiveButton(R.string.settings_reset_confirm) { _, _ ->
            AppSettingsResetter.reset(this)
            CatClientTileService.requestTileRefresh(this)
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
            recreate()
        }
        .create()
        .showCatClientDialog(positiveColor = ERROR)
}

internal fun MainActivity.scrollFieldIntoView(
    scrollView: ScrollView,
    field: View,
    delayMs: Long = KEYBOARD_SCROLL_DELAY_MS,
) {
    scrollView.postDelayed(
        {
            val scrollLocation = IntArray(2)
            val fieldLocation = IntArray(2)
            scrollView.getLocationInWindow(scrollLocation)
            field.getLocationInWindow(fieldLocation)
            val visibleBottom = scrollLocation[1] + scrollView.height - scrollView.paddingBottom - dp(16)
            val overlap = fieldLocation[1] + field.height - visibleBottom
            if (overlap > 0) scrollView.smoothScrollBy(0, overlap)
        },
        delayMs,
    )
}

internal fun MainActivity.advancedSectionLabel(value: String): TextView {
    return TextView(this).apply {
        text = value
        textSize = 13f
        letterSpacing = 0f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        setPaddingRelative(dp(4), 0, dp(4), 0)
        includeFontPadding = false
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        gravity = Gravity.START
    }
}

internal fun MainActivity.connectionChainFixedLabel(
    ref: ConnectionChainProfileRef?,
    options: List<ConnectionChainPickerOption>,
): String {
    if (ref == null) return getString(R.string.connection_chain_unavailable)
    val option = options.firstOrNull {
        it.subscriptionId == ref.subscriptionId && it.profile.fingerprint == ref.fingerprint
    }
    return if (option == null) {
        getString(R.string.connection_chain_unavailable)
    } else {
        getString(
            R.string.connection_chain_fixed_value,
            option.subscriptionName,
            option.profile.displayTag,
        )
    }
}

internal fun MainActivity.connectionChainSlotLabelRes(slot: ConnectionChainSlot): Int = when (slot) {
    ConnectionChainSlot.Before -> R.string.connection_chain_before
    ConnectionChainSlot.Base -> R.string.connection_chain_base
    ConnectionChainSlot.After -> R.string.connection_chain_after
}

/**
 * The pinned address of one hop: the address that hop's first proxy is dialled at — the
 * only place a bare IP can enter a chain, because a hop itself is a full config.
 */
internal fun MainActivity.editConnectionChainHopIp(slot: ConnectionChainSlot, hop: ConnectionChainHop) {
    val input = TextInputEditText(this).apply {
        hint = getString(R.string.connection_chain_hop_ip_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setText(hop.serverOverride.orEmpty())
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(20), dp(12), dp(20), 0)
        addView(input, LinearLayout.LayoutParams(-1, -2))
        addView(
            TextView(this@editConnectionChainHopIp).apply {
                text = getString(R.string.connection_chain_hop_ip_detail)
                textSize = 12f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(8), 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(
            getString(
                R.string.connection_chain_hop_ip_title,
                getString(connectionChainSlotLabelRes(slot)),
            ),
        )
        .setView(box)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.connection_chain_hop_ip_save) { _, _ ->
            val raw = input.text?.toString().orEmpty().trim()
            val encoded = raw.takeIf { it.isNotEmpty() }
                ?.let(::connectionChainEndpointOrNull)
                ?.encoded()
            if (raw.isNotEmpty() && encoded == null) {
                Toast.makeText(
                    this@editConnectionChainHopIp,
                    getString(R.string.connection_chain_hop_ip_invalid),
                    Toast.LENGTH_LONG,
                ).show()
                return@setPositiveButton
            }
            updateConnectionChainSettings(
                connectionChainPreferenceStore.read()
                    .withHop(slot, hop.copy(serverOverride = encoded)),
            )
        }
    if (hop.serverOverride.isNullOrBlank()) {
        dialog.setNeutralButton(R.string.connection_chain_hop_ip_clear, null)
    } else {
        dialog.setNeutralButton(R.string.connection_chain_hop_ip_clear) { _, _ ->
            updateConnectionChainSettings(
                connectionChainPreferenceStore.read()
                    .withHop(slot, hop.copy(serverOverride = null)),
            )
        }
    }
    dialog.show()
}

internal fun MainActivity.refreshChainExitCountries() {
    val settings = connectionChainPreferenceStore.read()
    if (!settings.isActive) {
        chainExitCountryCodes = null
        return
    }
    activityScope.launch {
        val codes = withContext(Dispatchers.IO) {
            runCatching {
                ConnectionChainPlanner.exitCountryCodes(
                    settings = settings,
                    sources = cachedConnectionChainSources(),
                )
            }.onFailure { error ->
                DiagnosticLogger.warn(this@refreshChainExitCountries, "activity.chain.exit.failed", error = error)
            }.getOrDefault(emptyList())
        }
        chainExitCountryCodes = codes
        DiagnosticLogger.info(this@refreshChainExitCountries, "activity.chain.exit.options", "codes=${codes.joinToString(",")}")
        renderLocationSelection()
    }
}

internal fun MainActivity.renderFragmentCarrierLine() {
    val line = fragmentCarrierLine ?: return
    val carrier = CarrierDetector.detect(this, refresh = true)
    val stored = dpiBypassPreferenceStore.presetId()
    val tune = panelTuneStore.read()
    val applied = DpiFragmentPreset.resolve(stored, carrier, tune)
    line.text = getString(
        R.string.dpi_carrier_line,
        getString(carrierLabelRes(carrier)),
        getString(presetLabelRes(applied)),
    ) + if (stored == DpiFragmentPreset.AUTO_ID && tune.presetIdFor(carrier) != null) {
        " · " + getString(R.string.dpi_tune_from_panel)
    } else {
        ""
    }
}

internal fun MainActivity.carrierLabelRes(carrier: MobileCarrier): Int = when (carrier) {
    MobileCarrier.MCI -> R.string.dpi_preset_mci
    MobileCarrier.IRANCELL -> R.string.dpi_preset_irancell
    MobileCarrier.RIGHTEL -> R.string.dpi_preset_rightel
    MobileCarrier.TCI -> R.string.dpi_preset_tci
    MobileCarrier.OTHER, MobileCarrier.UNKNOWN -> R.string.dpi_carrier_unknown
}

internal fun MainActivity.presetLabelRes(preset: DpiFragmentPreset): Int = when (preset) {
    DpiFragmentPreset.MCI -> R.string.dpi_preset_mci
    DpiFragmentPreset.IRANCELL -> R.string.dpi_preset_irancell
    DpiFragmentPreset.RIGHTEL -> R.string.dpi_preset_rightel
    DpiFragmentPreset.TCI -> R.string.dpi_preset_tci
    DpiFragmentPreset.GAMING -> R.string.dpi_preset_gaming
    DpiFragmentPreset.PATTN -> R.string.dpi_preset_pattn
    DpiFragmentPreset.DEFAULT -> R.string.dpi_preset_default
}

internal fun MainActivity.updateConnectionChainSettings(settings: ConnectionChainSettings) {
    if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) {
        renderChainSettingsPage?.invoke()
        return
    }
    val previous = connectionChainPreferenceStore.read()
    val normalized = settings.normalized()
    if (previous == normalized) return
    connectionChainPreferenceStore.save(normalized)
    renderChainSettingsPage?.invoke()
    renderConnectionDetails(buttonModel.state)
    renderConnectionSelection()
    refreshChainExitCountries()
    if (
        buttonModel.state == VpnState.Started &&
        (previous.isActive || normalized.isActive) &&
        connectionChainCompatibilityIssue == null
    ) {
        reconnectForConnectionOptionChange()
    }
}

internal fun MainActivity.openConnectionChainSettingsFromHome() {
    openScreen(SCREEN_SETTINGS)
    openConnectionChainSettingsPage?.invoke()
}

internal fun MainActivity.cachedConnectionChainPickerOptions(
    subscriptionIds: Set<String>? = null,
): List<ConnectionChainPickerOption> =
    connectionChainPickerOptions(cachedConnectionChainSources(subscriptionIds))

internal fun MainActivity.cachedConnectionChainSources(
    subscriptionIds: Set<String>? = null,
): List<ConnectionChainSource> {
    val subscriptions = userSubscriptionManager.list()
    val repository = ConfigRepository(this)
    return buildList {
        addAll(
            SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS.map {
                it to builtInSubscriptionName(it)
            },
        )
        addAll(subscriptions.map { it.id to it.name })
    }.filter { (subscriptionId, _) -> subscriptionIds == null || subscriptionId in subscriptionIds }
        .mapNotNull { (subscriptionId, subscriptionName) ->
            val snapshot = repository.readCachedMihomoConfigOrNullNow(subscriptionId)
                ?: return@mapNotNull null
            ConnectionChainSource(subscriptionId, subscriptionName, snapshot)
        }
}

internal fun MainActivity.connectionChainPickerOptions(
    sources: List<ConnectionChainSource>,
): List<ConnectionChainPickerOption> = sources.flatMap { source ->
    val selectable = ConnectionChainPlanner.selectableFingerprints(source.snapshot)
    source.snapshot.catalog.profiles.filter { it.fingerprint in selectable }.map { profile ->
        ConnectionChainPickerOption(
            subscriptionId = source.subscriptionId,
            subscriptionName = source.subscriptionName,
            profile = profile,
        )
    }
}

internal fun MainActivity.connectionChainFixedOptions(
    settings: ConnectionChainSettings,
    sources: List<ConnectionChainSource>,
): List<ConnectionChainPickerOption> {
    val refs = listOf(settings.base, settings.after).mapNotNull { hop ->
        hop.profileRef.takeIf { hop.mode == ConnectionChainHopMode.Fixed }
    }
    return sources.flatMap { source ->
        val fingerprints = refs.filter { it.subscriptionId == source.subscriptionId }
            .mapTo(mutableSetOf(), ConnectionChainProfileRef::fingerprint)
        source.snapshot.catalog.profiles.filter { it.fingerprint in fingerprints }.map { profile ->
            ConnectionChainPickerOption(source.subscriptionId, source.subscriptionName, profile)
        }
    }
}

internal fun MainActivity.advancedSectionDetail(value: String): TextView {
    return TextView(this).apply {
        text = value
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        setLineSpacing(dp(2).toFloat(), 1f)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        gravity = Gravity.START
    }
}

internal fun MainActivity.advancedSettingsPanel(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        background = glassSurfaceDrawable(radiusDp = 22)
        clipToOutline = true
        elevation = 0f
        setPadding(dp(8), dp(8), dp(8), dp(14))
    }
}

internal fun MainActivity.showThemeSelector() {
    val modes = AppThemeMode.entries
    val selected = appThemePreferenceStore.read()
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.theme_dialog_title)
        .setSingleChoiceItems(
            modes.map { getString(it.labelRes) }.toTypedArray(),
            modes.indexOf(selected),
        ) { dialog, which ->
            val mode = modes[which]
            dialog.dismiss()
            if (mode == selected) return@setSingleChoiceItems
            appThemePreferenceStore.save(mode)
            VpnWidgetProvider.refresh(this)
            recreate()
        }
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .create()
    dialog.showCatClientDialog()
}

/** Accent colour picker (ZedSecure appearance): swatches in a grid, applied with a recreate. */
internal fun MainActivity.showAccentSelector() {
    val store = AppAccentPreferenceStore(this)
    val selected = store.read()
    val night = palette.isDark
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    var dialog: AlertDialog? = null
    AppAccent.entries.chunked(3).forEach { rowItems ->
        grid.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                rowItems.forEach { accent ->
                    val tone = if (night) accent.dark else accent.light
                    addView(
                        LinearLayout(this@showAccentSelector).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            setPadding(dp(6), dp(10), dp(6), dp(10))
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                dialog?.dismiss()
                                if (accent != selected) {
                                    store.save(accent)
                                    recreate()
                                }
                            }
                            addView(
                                TextView(this@showAccentSelector).apply {
                                    text = if (accent == selected) "✓" else ""
                                    gravity = Gravity.CENTER
                                    textSize = 20f
                                    typeface = CatClientBodyBoldTypeface
                                    setTextColor(if (night) accent.onDark else accent.onLight)
                                    background = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(tone)
                                        if (accent == selected) setStroke(dp(3), TEXT_PRIMARY)
                                    }
                                },
                                LinearLayout.LayoutParams(dp(52), dp(52)),
                            )
                            addView(
                                TextView(this@showAccentSelector).apply {
                                    setText(accent.labelRes)
                                    textSize = 12f
                                    typeface = CatClientBodyTypeface
                                    setTextColor(TEXT_SECONDARY)
                                    gravity = Gravity.CENTER
                                },
                                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) },
                            )
                        },
                        LinearLayout.LayoutParams(0, -2, 1f),
                    )
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.accent_setting_title)
        .setView(grid)
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .create()
    dialog.showCatClientDialog()
}

internal fun MainActivity.showLanguageSelector() {
    val languages = AppLanguage.entries
    val selected = appLanguagePreferenceStore.read()
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.language_dialog_title)
        .setSingleChoiceItems(
            languages.map { getString(it.labelRes) }.toTypedArray(),
            languages.indexOf(selected),
        ) { dialog, which ->
            val language = languages[which]
            dialog.dismiss()
            if (language == selected) return@setSingleChoiceItems
            AppLocale.apply(applicationContext, language)
            VpnWidgetProvider.refresh(this)
            CatClientTileService.requestTileRefresh(this)
            recreate()
        }
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .create()
    dialog.showCatClientDialog()
}

internal fun MainActivity.showDnsPrivacySelector() {
    if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
    val modes = DnsPrivacyMode.values()
    val selectedMode = dnsPrivacyPreferenceStore.readMode()
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dns_encrypted_title)
        .setSingleChoiceItems(
            modes.map { getString(it.labelRes) }.toTypedArray(),
            modes.indexOf(selectedMode),
        ) { dialog, which ->
            if (handleDnsPrivacySelected(modes[which])) dialog.dismiss()
        }
        .create()
    dialog.showCatClientDialog()
}

internal fun MainActivity.showRoutingModeSelector() {
    if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
    val modes = RoutingMode.values()
    val selectedMode = routingModePreferenceStore.read()
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.routing_rules_title)
        .setSingleChoiceItems(
            modes.map { getString(it.labelRes) }.toTypedArray(),
            modes.indexOf(selectedMode),
        ) { dialog, which ->
            val mode = modes[which]
            dialog.dismiss()
            if (mode == selectedMode) return@setSingleChoiceItems
            routingModePreferenceStore.save(mode)
            DiagnosticLogger.info(this, "activity.routing.saved", "mode=${mode.wireName}")
            renderRoutingModeSelection()
            reconnectForConnectionOptionChange()
        }
        .create()
    dialog.showCatClientDialog()
}

/**
 * The Orbot row's verdict. Four states, and each one names what the next connection will
 * actually do — «روشن but nothing listens» must never read like «on Tor».
 */
internal fun MainActivity.renderOrbotRow() {
    val status = orbotStatusText ?: return
    val enabled = orbotEnginePreferenceStore.isEnabled()
    val endpoint = "${orbotEnginePreferenceStore.readHost()}:${orbotEnginePreferenceStore.readPort()}"
    if (orbotEngineCheckbox?.isChecked != enabled) orbotEngineCheckbox?.isChecked = enabled
    val probe = lastOrbotProbe
    when {
        !enabled -> status.setText(getString(R.string.orbot_off, endpoint))
        probe == null -> status.setText(getString(R.string.orbot_idle, endpoint))
        probe.reachable -> status.setText(
            getString(R.string.orbot_ready, endpoint, probe.latencyMs ?: 0L),
        )
        else -> status.setText(getString(R.string.orbot_absent, endpoint, probe.detail))
    }
    status.setTextColor(if (enabled && probe?.reachable == true) TEAL else TEXT_SECONDARY)

    val watchdog = watchdogStatusText ?: return
    val armed = pathWatchdogStore.isArmed()
    val state = pathWatchdogStore.readState()
    watchdogToggle?.isChecked = armed
    watchdog.setText(
        when {
            !armed -> getString(R.string.watchdog_off)
            state.usingFallback -> getString(
                R.string.watchdog_on_fallback,
                PathWatchdogPolicy.PRIMARY_RETRY_INTERVAL_MS / 60_000L,
            )
            else -> getString(
                R.string.watchdog_on_primary,
                state.consecutivePrimaryFailures,
                PathWatchdogPolicy.FAILURES_BEFORE_SWITCH,
            )
        },
    )
    watchdog.setTextColor(if (armed && state.usingFallback) TEAL else TEXT_SECONDARY)
}

/** Probe off the main thread: a TCP connect that hangs must not freeze the settings page. */
internal fun MainActivity.probeOrbotEngine(showToast: Boolean) {
    val host = orbotEnginePreferenceStore.readHost()
    val port = orbotEnginePreferenceStore.readPort()
    orbotProbeButton?.isEnabled = false
    activityScope.launch {
        val probe = kotlinx.coroutines.withContext(Dispatchers.IO) { OrbotProbe.probe(host, port) }
        lastOrbotProbe = probe
        orbotProbeButton?.isEnabled = true
        renderOrbotRow()
        DiagnosticLogger.info(
            this@probeOrbotEngine,
            "activity.orbot.probe",
            "host=$host port=$port reachable=${probe.reachable} ms=${probe.latencyMs} detail=${probe.detail}",
        )
        if (showToast) {
            Toast.makeText(
                this@probeOrbotEngine,
                orbotStatusText?.text?.toString().orEmpty(),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

internal fun MainActivity.displayDnsPrivacyEndpoint(mode: DnsPrivacyMode, value: String): String {
    // DoT is the one mode whose scheme is stripped: `tls://` is the only DoT spelling, so
    // hiding it keeps the box to host:port. DoQ and DoH3 keep theirs, because `quic://`
    // and `h3://` are what tell the core which transport to use — and a bare `1.1.1.1`
    // would silently become DoT in the owner's head.
    return when (mode) {
        DnsPrivacyMode.DoT -> value.removePrefix("tls://")
        else -> value
    }
}

internal fun MainActivity.setDnsPrivacyEndpointInputText(value: String) {
    if (dnsPrivacyEndpointInput.text.toString() == value) return
    dnsPrivacyInputUpdating = true
    try {
        dnsPrivacyEndpointInput.setText(value)
        dnsPrivacyEndpointInput.setSelection(dnsPrivacyEndpointInput.text.length)
    } finally {
        dnsPrivacyInputUpdating = false
    }
}

internal fun MainActivity.showDnsPrivacyError(message: String, focusOnError: Boolean) {
    dnsPrivacyErrorText.text = message
    dnsPrivacyErrorText.visibility = View.VISIBLE
    if (focusOnError) dnsPrivacyEndpointInput.requestFocus()
}

internal fun MainActivity.showSplitTunnelSelector(container: LinearLayout) {
    updateSplitTunnelControlsEnabled = null
    container.removeAllViews()
    container.addView(
        ProgressBar(this).apply {
            isIndeterminate = true
            contentDescription = getString(R.string.split_tunnel_title)
        },
        LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(32)
        },
    )
    activityScope.launch {
        val apps = runCatching {
            withContext(Dispatchers.IO) {
                installedAppRepository.loadLaunchableApps()
            }
        }.onFailure { error ->
            DiagnosticLogger.warn(this@showSplitTunnelSelector, "activity.splitTunnel.apps.failed", error = error)
        }.getOrNull()

        if (apps == null) {
            container.removeAllViews()
            container.addView(
                advancedSectionDetail(getString(R.string.split_tunnel_empty_apps)),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
            )
            Toast.makeText(this@showSplitTunnelSelector, R.string.split_tunnel_empty_apps, Toast.LENGTH_SHORT).show()
            return@launch
        }
        showSplitTunnelSettings(container, apps)
    }
}

internal fun MainActivity.saveTlsIntegrityEnabled(enabled: Boolean) {
    val previousValue = tlsIntegrityPreferenceStore.isEnabled()
    if (previousValue == enabled) return
    tlsIntegrityPreferenceStore.saveEnabled(enabled)
    if (!enabled) CatClientScanStateStore(this).clearTlsQuarantine()
    DiagnosticLogger.info(this, "activity.tlsIntegrity.saved", "enabled=$enabled")
    reconnectForConnectionOptionChange()
}

internal fun MainActivity.saveAdBlockEnabled(enabled: Boolean) {
    if (routingModePreferenceStore.isAdBlockEnabled() == enabled) return
    routingModePreferenceStore.saveAdBlockEnabled(enabled)
    DiagnosticLogger.info(this, "activity.adBlock.saved", "enabled=$enabled")
    Toast.makeText(this, R.string.ad_block_toast, Toast.LENGTH_SHORT).show()
}

/** [refreshPanelTune] but only when fragmenting is on and the panel URL is known. */
internal fun MainActivity.refreshPanelTuneIfNeeded() {
    if (dpiBypassPreferenceStore.isEnabled()) refreshPanelTune()
}

/**
 * Pull the owner's carrier table. Best-effort and off the main thread: [panelBaseUrl]
 * reads the deployment store and can walk the subscriptions, and a panel that is asleep
 * must never be able to delay the screen.
 */
internal fun MainActivity.refreshPanelTune() {
    activityScope.launch {
        val refreshed = withContext(Dispatchers.IO) {
            val base = panelBaseUrl() ?: return@withContext null
            runCatching { PanelTuneClient.refresh(panelTuneStore, base) }.getOrNull()
        }
        if (refreshed != null) {
            DiagnosticLogger.info(
                this@refreshPanelTune,
                "panel.tune.synced",
                "version=${refreshed.version} carriers=${refreshed.presets.size}",
            )
            renderFragmentCarrierLine()
        }
    }
}

internal fun MainActivity.saveTlsFragmentEnabled(enabled: Boolean) {
    if (dpiBypassPreferenceStore.isEnabled() == enabled) return
    dpiBypassPreferenceStore.saveEnabled(enabled)
    DiagnosticLogger.info(this, "activity.tlsFragment.saved", "enabled=$enabled")
    if (enabled) refreshPanelTune()
    Toast.makeText(
        this,
        if (enabled) R.string.tls_fragment_enabled_toast else R.string.tls_fragment_disabled_toast,
        Toast.LENGTH_SHORT,
    ).show()
    reconnectForConnectionOptionChange()
}

internal fun MainActivity.saveLanSharingEnabled(enabled: Boolean) {
    if (lanSharingPreferenceStore.read().enabled == enabled) return
    lanSharingPreferenceStore.saveEnabled(enabled)
    DiagnosticLogger.info(this, "activity.lanSharing.saved", "enabled=$enabled")
    renderLanSharingControls(settingsEnabled = true)
    reconnectForConnectionOptionChange()
}

internal fun MainActivity.saveConnectionMode(mode: ConnectionMode) {
    if (connectionModePreferenceStore.read() == mode) return
    connectionModePreferenceStore.save(mode)
    DiagnosticLogger.info(this, "activity.connectionMode.saved", "mode=${mode.wireName}")
    renderLanSharingControls(settingsEnabled = true)
    if (buttonModel.state != VpnState.Started) return
    if (mode == ConnectionMode.Vpn) {
        beginConnectFlow(Actions.RECONNECT)
    } else {
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        startVpnService(Actions.RECONNECT)
    }
}

internal fun MainActivity.saveLanSharingPasswordRequired(required: Boolean) {
    val settings = lanSharingPreferenceStore.read()
    if (settings.passwordRequired == required) return
    lanSharingPreferenceStore.savePasswordRequired(required)
    DiagnosticLogger.info(this, "activity.lanSharing.passwordRequired.saved", "required=$required")
    renderLanSharingControls(settingsEnabled = true)
    if (settings.enabled) reconnectForConnectionOptionChange()
}

internal fun MainActivity.regenerateLanSharingPassword() {
    val settings = lanSharingPreferenceStore.read()
    lanSharingPreferenceStore.regeneratePassword()
    DiagnosticLogger.info(this, "activity.lanSharing.password.regenerated", "enabled=${settings.enabled}")
    renderLanSharingControls(settingsEnabled = true)
    if (settings.enabled && settings.passwordRequired) reconnectForConnectionOptionChange()
}

internal fun MainActivity.copyLanSharingSettings() {
    val settings = lanSharingPreferenceStore.read()
    val value = lanSharingDetails(settings)
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Cat Client LAN proxy", value))
    Toast.makeText(this, R.string.lan_sharing_copied, Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.lanSharingDetails(settings: LanSharingSettings): String {
    val localEndpoint = getString(
        R.string.lan_sharing_local_endpoint,
        MihomoRuntimeDefaults.MIXED_PORT,
    )
    val addresses = LanSharingAddresses.reachablePrivateIpv4Addresses()
    val endpoints = if (addresses.isEmpty()) {
        getString(R.string.lan_sharing_no_address)
    } else {
        addresses.joinToString("\n") { address ->
            getString(R.string.lan_sharing_endpoint, address, MihomoRuntimeDefaults.MIXED_PORT)
        }
    }
    val lanEndpoints = if (settings.passwordRequired) {
        getString(R.string.lan_sharing_credentials, endpoints, settings.username, settings.password)
    } else {
        getString(R.string.lan_sharing_no_credentials, endpoints)
    }
    return getString(R.string.lan_sharing_details, localEndpoint, lanEndpoints)
}

internal fun MainActivity.saveAmneziaNoiseEnabled(enabled: Boolean) {
    val previous = connectionOptionsPreferenceStore.read()
    val settings = if (enabled) {
        runCatching(this::readAmneziaNoiseInputs).getOrElse { error ->
            amneziaNoiseCheckbox.isChecked = false
            showAmneziaNoiseError(localizedError(error, R.string.amnezia_noise_invalid))
            return
        }
    } else {
        previous.amneziaNoise
    }
    connectionOptionsPreferenceStore.saveAmneziaNoise(enabled, settings)
    DiagnosticLogger.info(
        this,
        "activity.amneziaNoise.saved",
        "enabled=$enabled count=${settings.count} min=${settings.minSize} max=${settings.maxSize} " +
            "ttl=${settings.fakeTtl ?: "default"} version=${settings.version ?: "default"} " +
            "stack=${settings.ipStackMode ?: "default"}",
    )
    renderConnectionOptionsControls(settingsEnabled = true)
    if (previous.amneziaNoiseEnabled != enabled) reconnectForConnectionOptionChange()
}

internal fun MainActivity.applyAmneziaNoiseSettings() {
    val previous = connectionOptionsPreferenceStore.read()
    val settings = runCatching(this::readAmneziaNoiseInputs).getOrElse { error ->
        showAmneziaNoiseError(localizedError(error, R.string.amnezia_noise_invalid))
        return
    }
    connectionOptionsPreferenceStore.saveAmneziaNoise(enabled = true, settings)
    amneziaNoiseErrorText.visibility = View.GONE
    if (previous.amneziaNoise != settings || !previous.amneziaNoiseEnabled) {
        DiagnosticLogger.info(
            this,
            "activity.amneziaNoise.applied",
            "count=${settings.count} min=${settings.minSize} max=${settings.maxSize} " +
                "ttl=${settings.fakeTtl ?: "default"} version=${settings.version ?: "default"} " +
                "stack=${settings.ipStackMode ?: "default"}",
        )
        reconnectForConnectionOptionChange()
    }
}

internal fun MainActivity.readAmneziaNoiseInputs(): AmneziaNoiseSettings {
    val settings = AmneziaNoiseSettings(
        count = amneziaNoiseCountInput.text.toString().toIntOrNull()
            ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_count_invalid)),
        minSize = amneziaNoiseMinSizeInput.text.toString().toIntOrNull()
            ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_min_size_invalid)),
        maxSize = amneziaNoiseMaxSizeInput.text.toString().toIntOrNull()
            ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_max_size_invalid)),
        fakeTtl = optionalAmneziaInt(amneziaNoiseTtlInput),
        version = optionalAmneziaInt(amneziaNoiseVersionInput),
        ipStackMode = optionalAmneziaText(amneziaNoiseIpStackInput),
        congestionController = optionalAmneziaText(amneziaNoiseCongestionInput),
        headerProtectionKey = amneziaNoiseHeaderProtectionKeyInput.text.toString(),
        contentPaddingAddition = amneziaNoiseContentPaddingInput.text.toString(),
        rekeyAfterTime = amneziaNoiseRekeyAfterInput.text.toString(),
        rekeyTimeout = amneziaNoiseRekeyTimeoutInput.text.toString(),
        rejectAfterTime = amneziaNoiseRejectAfterInput.text.toString(),
        keepaliveTimeout = amneziaNoiseKeepaliveTimeoutInput.text.toString(),
        maxHandshakeAttempts = amneziaNoiseMaxHandshakeAttemptsInput.text.toString(),
        randomTrailers = optionalAmneziaBoolean(amneziaNoiseRandomTrailersInput),
        disableCookies = optionalAmneziaBoolean(amneziaNoiseDisableCookiesInput),
    )
    return MihomoConnectionOptionsPolicy.validateNoise(settings)
}

internal fun MainActivity.optionalAmneziaInt(input: EditText): Int? {
    val value = input.text.toString().trim()
    if (value.isEmpty()) return null
    return value.toIntOrNull() ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_invalid))
}

internal fun MainActivity.optionalAmneziaText(input: EditText): String? =
    input.text.toString().trim().takeIf(String::isNotEmpty)

internal fun MainActivity.optionalAmneziaBoolean(input: EditText): Boolean? {
    return when (input.text.toString().trim().lowercase()) {
        "" -> null
        "true", "on", "1" -> true
        "false", "off", "0" -> false
        else -> throw IllegalArgumentException(getString(R.string.amnezia_noise_invalid))
    }
}

internal fun MainActivity.showAmneziaNoiseError(message: String) {
    amneziaNoiseErrorText.text = message
    amneziaNoiseErrorText.visibility = View.VISIBLE
}

internal fun MainActivity.reconnectForConnectionOptionChange() {
    if (buttonModel.state != VpnState.Started) return
    buttonModel.onStateChanged(VpnState.Starting)
    renderState(VpnState.Starting)
    startVpnService(Actions.RECONNECT)
}

internal fun MainActivity.saveFrontingIps(reconnectIfChanged: Boolean, previousValue: String?): Boolean {
    val nextValue = FrontingIpPolicy.normalize(frontingIps.joinToString(","))
    frontingIpPreferenceStore.saveFrontingIp(nextValue)
    frontingIps = FrontingIpPolicy.normalizeIps(nextValue)
    renderFrontingIpChips()
    frontingIpErrorText.visibility = View.GONE

    if (previousValue != nextValue) {
        DiagnosticLogger.info(this, "activity.frontingIp.saved", "enabled=${nextValue != null} count=${frontingIps.size}")
        if (reconnectIfChanged && buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }
    return true
}

internal fun MainActivity.addFrontingIpTokens(tokens: List<String>, focusOnError: Boolean): Boolean {
    val nextTokens = tokens.map { it.trim() }.filter { it.isNotEmpty() }
    if (nextTokens.isEmpty()) return true
    val nextIps = runCatching {
        FrontingIpPolicy.normalizeIps((frontingIps + nextTokens).joinToString(","))
    }.getOrElse { error ->
        showFrontingIpError(localizedError(error, R.string.fronting_invalid), focusOnError)
        return false
    }
    frontingIps = nextIps
    renderFrontingIpChips()
    return true
}

internal fun MainActivity.removeFrontingIp(ip: String) {
    val previousValue = frontingIpPreferenceStore.readFrontingIp()
    frontingIps = frontingIps.filterNot { it == ip }
    renderFrontingIpChips()
    saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)
}

internal fun MainActivity.setFrontingIpInputText(value: String) {
    frontingIpInputUpdating = true
    try {
        frontingIpInput.setText(value)
        frontingIpInput.setSelection(frontingIpInput.text.length)
    } finally {
        frontingIpInputUpdating = false
    }
}

internal fun MainActivity.frontingIpInputHint(): String {
    return if (frontingIps.size >= 5) {
        getString(R.string.fronting_capacity_full)
    } else {
        getString(R.string.fronting_helper)
    }
}

internal fun MainActivity.showFrontingIpError(message: String, focusOnError: Boolean) {
    frontingIpErrorText.text = message
    frontingIpErrorText.visibility = View.VISIBLE
    if (focusOnError) {
        frontingIpInput.requestFocus()
    }
}
