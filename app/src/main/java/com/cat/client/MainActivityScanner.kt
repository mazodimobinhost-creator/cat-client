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
import com.cat.client.MainActivity.Companion.DEFAULT_SCANNER_SNI
import com.cat.client.MainActivity.Companion.SCANNER_CONCURRENCY
import com.cat.client.MainActivity.Companion.SCANNER_CONNECT_TIMEOUT_MS
import com.cat.client.MainActivity.Companion.SCANNER_LIVE_REFRESH_EVERY
import com.cat.client.MainActivity.Companion.SCANNER_MEASURE_ROWS
import com.cat.client.MainActivity.Companion.SCANNER_PER_RANGE
import com.cat.client.MainActivity.Companion.SCANNER_PORTS
import com.cat.client.MainActivity.Companion.SCANNER_PREFERENCES
import com.cat.client.MainActivity.Companion.SCANNER_TLS_TIMEOUT_MS
import com.cat.client.MainActivity.Companion.SCREEN_HOME
import com.cat.client.MainActivity.Companion.SCANNER_BUILD_LIMIT
import com.cat.client.MainActivity.Companion.SCANNER_BUILD_PORTS
import com.cat.client.MainActivity.Companion.SCANNER_PORT_KEY
import com.cat.client.MainActivity.Companion.SCANNER_RANGES_KEY
import com.cat.client.MainActivity.Companion.SCANNER_SNI_KEY
import com.cat.client.MainActivity.Companion.SCANNER_VISIBLE_RESULTS
import com.cat.client.MainActivity.PanelIdentity

// IP scanner tab and scanner dialogs (whitelist, SNI, UDP/TCP, inject into subscription), IP health, foreign test, scan history, results list.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

internal fun MainActivity.buildScannerScreen(): View {
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
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
        setPadding(dp(24), dp(28), dp(24), dp(104))
    }

    body.addView(cloudScannerSwitch(scannerActive = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    body.addView(featureFinderBar(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    body.addView(
        TextView(this).apply {
            setText(R.string.scanner_title)
            textSize = 28f
            typeface = CatClientDisplayTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    body.addView(
        advancedSectionDetail(getString(R.string.scanner_intro)),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(18)
        },
    )
    val isFarsiScanner = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val unifiedScannerTabs = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        background = glassSurfaceDrawable(radiusDp = 16)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        fun tabBtn(label: String, active: Boolean, onClick: () -> Unit): MaterialButton =
            MaterialButton(this@buildScannerScreen).apply {
                text = label
                isAllCaps = false
                textSize = 11.5f
                typeface = CatClientBodyBoldTypeface
                minHeight = dp(40)
                minimumHeight = dp(40)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(12)
                setPadding(dp(8), 0, dp(8), 0)
                if (active) {
                    backgroundTintList = ColorStateList.valueOf(TEAL)
                    setTextColor(palette.onAccent)
                } else {
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    setTextColor(TEXT_PRIMARY)
                }
                setOnClickListener { onClick() }
            }
        addView(
            tabBtn(if (isFarsiScanner) "💦 آی‌پی تمیز CF" else "💦 Clean IP", active = true) {},
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            tabBtn(if (isFarsiScanner) "🏳️ آی‌پی سفید" else "🏳️ Whitelist", active = false) { showWhitelistSuperScannerDialog() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) },
        )
        addView(
            tabBtn(if (isFarsiScanner) "🎯 ProxyIP" else "🎯 ProxyIP", active = false) { showProxyIpScannerPage() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) },
        )
        addView(
            tabBtn(if (isFarsiScanner) "🧦 SOCKS5" else "🧦 SOCKS5", active = false) { showProxyScannerPage() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) },
        )
    }
    body.addView(unifiedScannerTabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    val extraScannerToolsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    extraScannerToolsHost.addView(
        advancedSettingsPanel().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { showProxyScannerPage() }
            addView(
                LinearLayout(this@buildScannerScreen).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(TextView(this@buildScannerScreen).apply { text = "🛰"; textSize = 22f }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
                    addView(
                        LinearLayout(this@buildScannerScreen).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(this@buildScannerScreen).apply { setText(R.string.scanner_proxy_entry); textSize = 15f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY); includeFontPadding = false })
                            addView(TextView(this@buildScannerScreen).apply { setText(R.string.scanner_proxy_entry_detail); textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); includeFontPadding = false }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                        },
                        LinearLayout.LayoutParams(0, -2, 1f),
                    )
                    addView(TextView(this@buildScannerScreen).apply { text = "›"; textSize = 22f; setTextColor(TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) },
    )

    extraScannerToolsHost.addView(
        advancedSettingsPanel().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { showProxyIpScannerPage() }
            addView(
                LinearLayout(this@buildScannerScreen).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(TextView(this@buildScannerScreen).apply { text = "🔁"; textSize = 22f }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
                    addView(
                        LinearLayout(this@buildScannerScreen).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(this@buildScannerScreen).apply { setText(R.string.pip_entry); textSize = 15f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY); includeFontPadding = false })
                            addView(TextView(this@buildScannerScreen).apply { setText(R.string.pip_entry_detail); textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); includeFontPadding = false }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                        },
                        LinearLayout.LayoutParams(0, -2, 1f),
                    )
                    addView(TextView(this@buildScannerScreen).apply { text = "›"; textSize = 22f; setTextColor(TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) },
    )

    // ---- Static IP: exactly one fronting address, never rotated / failed-over / re-ranked ----
    extraScannerToolsHost.addView(buildStaticIpCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    // ---- SNI scanner: which SNI works best (fastest TLS) with the active IP ----
    extraScannerToolsHost.addView(buildSniScannerCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    // ---- live IP health: auto-refresh the pool and replace broken IPs ----
    val ipHealthStore = IpHealthStore(this)
    val healthCard = advancedSettingsPanel()
    healthCard.addView(
        advancedSectionLabel(getString(R.string.ip_health_section)),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(8)
        },
    )
    healthCard.addView(
        advancedSectionDetail(getString(R.string.ip_health_desc)),
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) },
    )
    ipHealthStatusView = TextView(this).apply {
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    healthCard.addView(ipHealthStatusView, LinearLayout.LayoutParams(-1, -2))
    ipHealthCountryRow = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    healthCard.addView(ipHealthCountryRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    val intervalChips = mutableListOf<Chip>()
    val autoChip = Chip(this@buildScannerScreen).apply {
        setText(R.string.ip_health_auto)
        isCheckable = true
        isChecked = ipHealthStore.autoEnabled
        textSize = 12f
        setTextColor(TEXT_PRIMARY)
        chipStrokeColor = ColorStateList.valueOf(withAlpha(TEAL, 170))
        chipStrokeWidth = dp(1).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
        setOnCheckedChangeListener { _, checked ->
            ipHealthStore.autoEnabled = checked
            startIpHealthLoop()
            Toast.makeText(
                this@buildScannerScreen,
                if (checked) R.string.ip_health_auto_on else R.string.ip_health_auto_off,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val rotateButton = MaterialButton(this).apply {
        setText(R.string.ip_health_rotate_now)
        setAllCaps(false)
        textSize = 11.5f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(36)
        minimumHeight = dp(36)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 34))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 130))
        setTextColor(TEAL)
        setOnClickListener { runIpHealthSweep(failFast = true) }
    }
    val healthRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val pinChip = Chip(this@buildScannerScreen).apply {
        setText(R.string.ip_health_pin)
        isCheckable = true
        isChecked = ipHealthStore.pinned
        textSize = 12f
        setTextColor(TEXT_PRIMARY)
        chipStrokeColor = ColorStateList.valueOf(withAlpha(TEAL, 170))
        chipStrokeWidth = dp(1).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
        setOnCheckedChangeListener { _, checked ->
            ipHealthStore.pinned = checked
            if (checked) startIpHealthLoop() // no-op while pinned; kills a running loop
            Toast.makeText(
                this@buildScannerScreen,
                if (checked) R.string.ip_health_pin_on else R.string.ip_health_pin_off,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val genomeStoreUi = NetworkGenomeStore(this)
    val failoverChip = Chip(this@buildScannerScreen).apply {
        setText(R.string.engine_auto_failover)
        isCheckable = true
        isChecked = genomeStoreUi.autoFailover
        textSize = 12f
        setTextColor(TEXT_PRIMARY)
        chipStrokeColor = ColorStateList.valueOf(withAlpha(TEAL, 170))
        chipStrokeWidth = dp(1).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
        setOnCheckedChangeListener { _, checked ->
            genomeStoreUi.autoFailover = checked
            Toast.makeText(this@buildScannerScreen, if (checked) R.string.engine_auto_failover_on else R.string.engine_auto_failover_off, Toast.LENGTH_SHORT).show()
        }
    }
    healthRow.addView(autoChip, LinearLayout.LayoutParams(-2, -2))
    healthRow.addView(failoverChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    healthRow.addView(pinChip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    healthRow.addView(rotateButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    healthCard.addView(healthRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    val intervalRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    listOf(5, 15, 30).forEach { minutes ->
        val chip = Chip(this@buildScannerScreen).apply {
            text = getString(R.string.ip_health_interval, minutes)
            isCheckable = true
            isChecked = ipHealthStore.intervalMinutes == minutes
            textSize = 11f
            setTextColor(TEXT_PRIMARY)
            chipStrokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 170))
            chipStrokeWidth = dp(1).toFloat()
            chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    intervalChips.filterNot { it === this }.forEach { it.isChecked = false }
                    ipHealthStore.intervalMinutes = minutes
                    startIpHealthLoop()
                }
            }
        }
        intervalChips += chip
        intervalRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
    }
    healthCard.addView(intervalRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    extraScannerToolsHost.addView(healthCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    renderScannerIpHealth()
    startIpHealthLoop()

    // ---- 🏳️ Smart Whitelist & Multi-CDN Target Selector Card ----
    val whitelistPresetCard = advancedSettingsPanel().apply {
        setPadding(dp(14), dp(12), dp(14), dp(14))
    }
    whitelistPresetCard.addView(
        TextView(this).apply {
            text = if (isFarsiScanner) {
                "🏳️ انتخاب سریع آی‌پی‌های سفید، نت ملی و CDNها (۱-کلیکی)"
            } else {
                "🏳️ 1-Tap Whitelist, Net Melli & Multi-CDN Presets"
            }
            textSize = 14f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    val whitelistPresetDesc = TextView(this).apply {
        text = if (isFarsiScanner) {
            "روی هر شبکه بزنید تا رنج‌های آی‌پی سفید، پورت‌ها و بهترین SNI مخصوص همان شبکه خودکار تنظیم شود:"
        } else {
            "Tap any network preset to auto-populate its whitelisted CIDR ranges, ports, and optimal SNI:"
        }
        textSize = 11.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        setPadding(0, dp(4), 0, dp(8))
    }
    whitelistPresetCard.addView(whitelistPresetDesc, LinearLayout.LayoutParams(-1, -2))
    val whitelistPresetChips = ChipGroup(this).apply {
        isSingleLine = false
        chipSpacingHorizontal = dp(6)
        chipSpacingVertical = dp(6)
    }
    whitelistPresetCard.addView(whitelistPresetChips, LinearLayout.LayoutParams(-1, -2))
    whitelistPresetCard.addView(
        MaterialButton(this).apply {
            text = if (isFarsiScanner) {
                "🚀 باز کردن ابر-اسکنر تخصصی آی‌پی سفید، پلتفرم‌ها و تزریق کانفیگ"
            } else {
                "🚀 Open Deep Whitelist & Multi-CDN Super-Scanner"
            }
            isAllCaps = false
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 45))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            setTextColor(TEAL)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showWhitelistSuperScannerDialog() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    body.addView(whitelistPresetCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

    val controls = advancedSettingsPanel()
    controls.addView(
        advancedSectionLabel(getString(R.string.scanner_settings_section)),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(8)
        },
    )
    scannerSniInput = scannerInput(
        hint = getString(R.string.scanner_sni_hint),
        initial = scannerSniPreference(),
    )
    controls.addView(
        scannerFieldLayout(getString(R.string.scanner_sni_label), scannerSniInput),
        LinearLayout.LayoutParams(-1, -2),
    )
    controls.addView(
        TextView(this).apply {
            setText(R.string.scanner_sni_recommended)
            textSize = 11.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(4))
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    val sniSuggestions = ChipGroup(this).apply {
        isSingleLine = true
        chipSpacingHorizontal = dp(6)
        chipSpacingVertical = dp(4)
    }
    fun populateSniSuggestions(snis: List<String>) {
        sniSuggestions.removeAllViews()
        snis.forEach { suggestion ->
            sniSuggestions.addView(Chip(this@buildScannerScreen).apply {
                text = suggestion
                isCheckable = false
                isClickable = true
                textSize = 11f
                setTextColor(TEXT_PRIMARY)
                chipStrokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 170))
                chipStrokeWidth = dp(1).toFloat()
                chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
                setOnClickListener {
                    scannerSniInput.setText(suggestion)
                    scannerSniInput.setSelection(suggestion.length)
                    saveScannerSni(suggestion)
                }
            })
        }
    }
    populateSniSuggestions(recommendedScannerSnis())
    IpScanner.WHITELIST_AND_CDN_RANGE_PRESETS.forEach { preset ->
        whitelistPresetChips.addView(
            Chip(this@buildScannerScreen).apply {
                text = if (isFarsiScanner) preset.badge else preset.titleEn.substringBefore(" (")
                isCheckable = false
                isClickable = true
                textSize = 11.5f
                setTextColor(TEXT_PRIMARY)
                chipStrokeColor = ColorStateList.valueOf(withAlpha(TEAL, 175))
                chipStrokeWidth = dp(1).toFloat()
                chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 215 else 245))
                setOnClickListener {
                    scannerSubnetsInput.setText(preset.rangesText)
                    saveScannerRanges(preset.rangesText)
                    scannerSniInput.setText(preset.recommendedSni)
                    saveScannerSni(preset.recommendedSni)
                    populateSniSuggestions((preset.fallbackSnis + recommendedScannerSnis()).distinct().take(10))
                    saveScannerSource("mine", true)
                    renderScannerSources()
                    whitelistPresetDesc.text = if (isFarsiScanner) {
                        "✅ ${preset.titleFa}\n${preset.summaryFa}"
                    } else {
                        "✅ ${preset.titleEn}\n${preset.summaryEn}"
                    }
                    whitelistPresetDesc.setTextColor(TEAL)
                    Toast.makeText(
                        this@buildScannerScreen,
                        if (isFarsiScanner) "✅ رنج‌ها و SNI روی «${preset.badge}» تنظیم شد!"
                        else "✅ Ranges & SNI set to ${preset.id}!",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )
    }
    controls.addView(sniSuggestions, LinearLayout.LayoutParams(-1, -2))
    controls.addView(
        TextView(this).apply {
            setText(R.string.scanner_port_label)
            textSize = 11.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(10), 0, dp(4))
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    scannerPort = scannerPortPreference()
    scannerPortGroup = ChipGroup(this).apply {
        isSingleLine = true
        isSingleSelection = true
        chipSpacingHorizontal = dp(6)
        chipSpacingVertical = dp(4)
    }
    var refreshPortChips: () -> Unit = {}
    val customPortDialog: () -> Unit = {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }
        val portInput = TextInputEditText(this).apply {
            hint = getString(R.string.scanner_port_custom)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(scannerPort.toString())
        }
        box.addView(portInput, LinearLayout.LayoutParams(-1, -2))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanner_port_custom)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val parsed = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                if (parsed in 1..65535) {
                    scannerPort = parsed
                    saveScannerPort(parsed)
                    refreshPortChips()
                } else {
                    Toast.makeText(this, R.string.scanner_port_invalid, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    // Presets + the last custom port + ✏️ opener: every port 1–65535 is selectable,
    // and «Send to Cat Panel» pins each address to exactly this port (ip:port#CC).
    refreshPortChips = {
        scannerPortGroup.removeAllViews()
        (SCANNER_PORTS.toList() + if (scannerPort in SCANNER_PORTS) emptyList() else listOf(scannerPort)).forEach { port ->
            scannerPortGroup.addView(Chip(this@buildScannerScreen).apply {
                id = View.generateViewId()
                text = getString(R.string.scanner_port_selected, port)
                isCheckable = true
                isChecked = port == scannerPort
                textSize = 11f
                setTextColor(TEXT_PRIMARY)
                chipStrokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 170))
                chipStrokeWidth = dp(1).toFloat()
                chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
                setOnClickListener {
                    scannerPort = port
                    saveScannerPort(port)
                }
            })
        }
        scannerPortGroup.addView(Chip(this@buildScannerScreen).apply {
            text = getString(R.string.scanner_port_custom)
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
            chipStrokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 170))
            chipStrokeWidth = dp(1).toFloat()
            chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
            setOnClickListener { customPortDialog() }
        })
    }
    refreshPortChips()
    controls.addView(scannerPortGroup, LinearLayout.LayoutParams(-1, -2))
    controls.addView(
        TextView(this).apply {
            setText(R.string.scanner_port_hint)
            textSize = 10.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            setPadding(0, dp(3), 0, 0)
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    scannerSubnetsInput = scannerInput(
        hint = getString(R.string.scanner_subnets_hint),
        initial = scannerRangesPreference(),
    ).apply {
        // Range list: allow several lines so long CIDR lists stay readable.
        setSingleLine(false)
        maxLines = 4
        isVerticalScrollBarEnabled = true
        // The «منابع اسکن» counts are drawn from THIS box, so they must follow the
        // typing: a range the user just pasted has to show up as +24 candidates
        // immediately, otherwise the card answers the same question as the old screen.
        addTextChangedListener(DebouncedTextWatcher(this, delayMs = 300L) { renderScannerSources() })
    }
    controls.addView(
        scannerFieldLayout(getString(R.string.scanner_subnets_label), scannerSubnetsInput),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    val rangeRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            TextView(this@buildScannerScreen).apply {
                setText(R.string.scanner_ranges_note)
                textSize = 11.5f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_SECONDARY)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            MaterialButton(this@buildScannerScreen, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.scanner_ranges_reset)
                textSize = 11.5f
                typeface = CatClientBodyBoldTypeface
                isAllCaps = false
                cornerRadius = dp(10)
                strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 160))
                setTextColor(TEXT_PRIMARY)
                insetTop = 0
                insetBottom = 0
                setOnClickListener {
                    scannerSubnetsInput.setText(IpScanner.defaultRangesText())
                    saveScannerRanges(IpScanner.defaultRangesText())
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
    }
    controls.addView(rangeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    // ✍️ Manual single-IP add (v4 / v6 / domain): probe NOW with the current
    // SNI/port and join the results only when it really answers.
    val manualIpInput = TextInputEditText(this).apply {
        hint = getString(R.string.scanner_manual_ip_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
    }
    val manualAddRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            TextInputLayout(this@buildScannerScreen).apply {
                hint = getString(R.string.scanner_manual_ip_label)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                addView(manualIpInput, LinearLayout.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            MaterialButton(this@buildScannerScreen).apply {
                setText(R.string.scanner_manual_ip_add)
                textSize = 12.5f
                typeface = CatClientBodyBoldTypeface
                isAllCaps = false
                cornerRadius = dp(10)
                insetTop = 0
                insetBottom = 0
                setOnClickListener {
                    val value = manualIpInput.text?.toString()?.trim().orEmpty()
                    if (value.isEmpty()) return@setOnClickListener
                    // `1.2.3.4:2053`, `[2606:4700::1]:443`, a bare v6 or a host name —
                    // the old code only stripped brackets, so any ip:port input kept
                    // its colon, the TCP connect failed and the box looked broken.
                    val (target, portOverride) = IpScanner.parseManualTarget(value)
                    val portNow = portOverride ?: scannerPort
                    if (target.isBlank() || !(
                            IpScanner.isValidIpv4(target) || IpScanner.isValidIpv6(target) ||
                                IpScanner.isValidHostname(target)
                            )
                    ) {
                        Toast.makeText(this@buildScannerScreen, R.string.scanner_manual_ip_invalid, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val sniNow = scannerSniInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_SCANNER_SNI }
                    it.isEnabled = false
                    activityScope.launch {
                        val tcpMs = withContext(Dispatchers.IO) {
                            runCatching { IpScanner.tcpConnect(target, portNow, SCANNER_CONNECT_TIMEOUT_MS) }.getOrNull()
                        }
                        val result = if (tcpMs == null) null else withContext(Dispatchers.IO) {
                            runCatching { IpScanner.reprobeOnce(target, sniNow, portNow) }.getOrNull()
                        }
                        it.isEnabled = true
                        when {
                            tcpMs == null -> Toast.makeText(
                                this@buildScannerScreen,
                                getString(R.string.scanner_manual_tcp_fail, target, portNow),
                                Toast.LENGTH_LONG,
                            ).show()
                            result == null -> {
                                // Reachable, but no valid TLS/HTTP answer for this SNI: show the
                                // row (the address IS up) and never send it to the panel.
                                val tcpOnly = IpScanner.ScanResult(
                                    ip = target,
                                    pingMs = tcpMs,
                                    sni = sniNow,
                                    port = portNow,
                                    countryName = "Cloudflare edge",
                                    sourceRange = "manual",
                                )
                                if (scannerResults.none { r -> r.authority == tcpOnly.authority }) {
                                    scannerResults = (scannerResults + tcpOnly).sortedWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
                                }
                                manualIpInput.setText("")
                                scannerFilter = "all"
                                renderScannerResults()
                                Toast.makeText(
                                    this@buildScannerScreen,
                                    getString(R.string.scanner_manual_tls_fail, tcpOnly.authority, tcpMs.toInt()),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            else -> {
                                if (scannerResults.none { r -> r.authority == result.authority }) {
                                    scannerResults = (scannerResults + result).sortedWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
                                }
                                manualIpInput.setText("")
                                scannerFilter = "all"
                                renderScannerResults()
                                Toast.makeText(this@buildScannerScreen, getString(R.string.scanner_manual_ip_ok, result.authority, result.pingMs.toInt()), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
    }
    controls.addView(manualAddRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    // ⚡ Scan profile: fast (shallow + snappy) / standard / deep (wide walk +
    // long timeouts) — same engine, different depth, persisted per device.
    scannerProfile = getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE).getString("profile", "std") ?: "std"
    controls.addView(scannerProfileRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // 🛡️ 2026 Anti-DPI Fragmentation + Limited Network + All 13 CF Ports toggles
    val scanPrefs = getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
    val powerTogglesGroup = ChipGroup(this).apply {
        isSingleLine = false
        chipSpacingHorizontal = dp(6)
        chipSpacingVertical = dp(4)
    }
    val antiDpiChip = Chip(this).apply {
        text = if (isFarsiScanner) "🛡️ اسکن ضد-DPI (فرگمنت v52)" else "🛡️ Anti-DPI Fragment (v52)"
        isCheckable = true
        isChecked = scanPrefs.getBoolean("anti_dpi_scan", true)
        textSize = 11f
        setTextColor(TEXT_PRIMARY)
        chipStrokeColor = ColorStateList.valueOf(withAlpha(TEAL, 175))
        chipStrokeWidth = dp(1).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 215 else 245))
        setOnCheckedChangeListener { _, checked ->
            scanPrefs.edit().putBoolean("anti_dpi_scan", checked).apply()
        }
    }
    val limitedNetChip = Chip(this).apply {
        text = if (isFarsiScanner) "📡 شبکه محدود / ضد پکت‌لاس" else "📡 Limited Network (Retry)"
        isCheckable = true
        isChecked = scanPrefs.getBoolean("limited_net_scan", false)
        textSize = 11f
        setTextColor(TEXT_PRIMARY)
        chipStrokeColor = ColorStateList.valueOf(withAlpha(AMBER, 175))
        chipStrokeWidth = dp(1).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 215 else 245))
        setOnCheckedChangeListener { _, checked ->
            scanPrefs.edit().putBoolean("limited_net_scan", checked).apply()
        }
    }
    val all13PortsChip = Chip(this).apply {
        text = if (isFarsiScanner) "🔌 همه ۱۳ پورت کلادفلر" else "🔌 All 13 CF Ports"
        isCheckable = true
        isChecked = scanPrefs.getBoolean("all_13_ports_scan", false)
        textSize = 11f
        setTextColor(TEXT_PRIMARY)
        chipStrokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 175))
        chipStrokeWidth = dp(1).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 215 else 245))
        setOnCheckedChangeListener { _, checked ->
            scanPrefs.edit().putBoolean("all_13_ports_scan", checked).apply()
        }
    }
    powerTogglesGroup.addView(antiDpiChip)
    powerTogglesGroup.addView(limitedNetChip)
    powerTogglesGroup.addView(all13PortsChip)
    controls.addView(powerTogglesGroup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val actionRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            MaterialButton(this@buildScannerScreen).apply {
                setText(R.string.scanner_start)
                textSize = 13.5f
                typeface = CatClientBodyBoldTypeface
                isAllCaps = false
                cornerRadius = dp(10)
                backgroundTintList = ColorStateList.valueOf(TEAL)
                setTextColor(palette.onAccent)
                insetTop = 0
                insetBottom = 0
                setOnClickListener { startIpScanner() }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            MaterialButton(this@buildScannerScreen).apply {
                setText(R.string.scanner_stop)
                textSize = 13.5f
                typeface = CatClientBodyBoldTypeface
                isAllCaps = false
                cornerRadius = dp(10)
                isEnabled = false
                backgroundTintList = ColorStateList.valueOf(withAlpha(TEXT_SECONDARY, 60))
                setTextColor(TEXT_PRIMARY)
                insetTop = 0
                insetBottom = 0
                setOnClickListener { stopIpScanner() }
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
        )
    }
    controls.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    scannerStartButton = actionRow.getChildAt(0) as MaterialButton
    scannerStopButton = actionRow.getChildAt(1) as MaterialButton

    scannerProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 100
        progress = 0
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(TEAL)
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(OUTLINE, 120))
    }
    controls.addView(
        scannerProgressBar,
        LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(14) },
    )
    scannerStatusText = TextView(this).apply {
        setText(R.string.scanner_idle)
        textSize = 12.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(0, dp(8), 0, dp(4))
    }
    controls.addView(scannerStatusText, LinearLayout.LayoutParams(-1, -2))
    scannerHistoryText = TextView(this).apply {
        setText(R.string.scanner_history_never)
        textSize = 11f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(0, dp(2), 0, 0)
    }
    controls.addView(scannerHistoryText, LinearLayout.LayoutParams(-1, -2))
    renderScanHistory()
    body.addView(controls, LinearLayout.LayoutParams(-1, -2))

    body.addView(
        advancedSectionLabel(getString(R.string.scanner_results_section)),
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
            bottomMargin = dp(10)
        },
    )
    scannerApplyButton = MaterialButton(this).apply {
        setText(R.string.scanner_apply_best)
        textSize = 13.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        isEnabled = false
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 120))
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { applyScannerResult() }
    }
    body.addView(scannerApplyButton, LinearLayout.LayoutParams(-1, -2))
    scannerBuildButton = MaterialButton(this).apply {
        setText(R.string.scanner_build_configs)
        textSize = 13.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        isEnabled = false
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener {
            val source = if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList()
            if (source.isEmpty()) {
                Toast.makeText(this@buildScannerScreen, R.string.scanner_no_results, Toast.LENGTH_SHORT).show()
            } else {
                buildSubscriptionFromScan(source)
            }
        }
    }
    body.addView(scannerBuildButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    val scannerInjectSubButton = MaterialButton(this).apply {
        text = if (isFarsiScanner) {
            "💉 تزریق آی‌پی‌های اسکن‌شده به همهٔ کانفیگ‌های فعلی من (بدون نیاز به پنل)"
        } else {
            "💉 Inject Scanned Clean IPs into My Active Configs"
        }
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(AMBER, 45))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(AMBER)
        setTextColor(AMBER)
        insetTop = 0
        insetBottom = 0
        setOnClickListener {
            val source = if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList()
            injectScannedIpsIntoActiveSubscription(source)
        }
    }
    body.addView(scannerInjectSubButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    // Hand the verified rows to Cat Panel as `addr#CC` lines: copied to the
    // clipboard AND opened in the panel (its import box pre-fills from ?ips=),
    // so the panel learns which address lands in which country for THIS user.
    val scannerPanelButton = MaterialButton(this).apply {
        setText(R.string.scanner_send_panel)
        textSize = 13.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 120))
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { sendScanToPanel() }
    }
    body.addView(scannerPanelButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    val panelPushStatus = TextView(this).apply {
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        visibility = View.GONE
    }
    val panelPushProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        isIndeterminate = false
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(OUTLINE, 180))
        visibility = View.GONE
    }
    scannerPanelPushStatusText = panelPushStatus
    scannerPanelPushProgressBar = panelPushProgress
    body.addView(panelPushStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    body.addView(panelPushProgress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(4) })

    // 👑 Neighbor scan + Copy list + Share + Speed test grouped cleanly in 2 rows
    val scannerNeighborButton = MaterialButton(this).apply {
        setText(R.string.scanner_neighbor_btn)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(AMBER, 90))
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { runNeighborScan() }
    }
    val scannerListButton = MaterialButton(this).apply {
        setText(R.string.scanner_copy_list)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 90))
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { copyScannerPlainList() }
    }
    val scannerShareButton = MaterialButton(this).apply {
        setText(R.string.scanner_share_btn)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 90))
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { shareScannerResults() }
    }
    val scannerSpeedButton = MaterialButton(this).apply {
        setText(R.string.scanner_speed_btn)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        isEnabled = false
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 34))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 130))
        setTextColor(TEAL)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { runScannerSpeedTest() }
    }
    scannerSpeedTestButton = scannerSpeedButton
    val quickScanToolsRow1 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(scannerNeighborButton, LinearLayout.LayoutParams(0, -2, 1f))
        addView(scannerListButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
    }
    val quickScanToolsRow2 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(scannerShareButton, LinearLayout.LayoutParams(0, -2, 1f))
        addView(scannerSpeedButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
    }
    body.addView(quickScanToolsRow1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    body.addView(quickScanToolsRow2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    // ⏱ One exact ip:port, measured long: my line vs through the worker.
    body.addView(
        MaterialButton(this).apply {
            text = if (isFarsiScanner) "⏱ پینگ و جیتر دقیق (خط من / ورکر)" else "⏱ Precise ping & jitter (line / worker)"
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            isAllCaps = false
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 34))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 130))
            setTextColor(TEAL)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showPingJitterPage() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )

    // 🔎 «منابع اسکن» — the card that answers «منبع نداره اسکن کنه».
    val sourcesCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        background = glassSurfaceDrawable(radiusDp = 18)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    sourcesCard.addView(
        TextView(this).apply {
            setText(R.string.scanner_sources_title)
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    sourcesCard.addView(
        TextView(this).apply {
            setText(R.string.scanner_sources_hint)
            textSize = 11f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(0, dp(2), 0, dp(6))
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    val sourceRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    scannerSourceRow = sourceRow
    sourcesCard.addView(sourceRow, LinearLayout.LayoutParams(-1, -2))
    val sourceSummary = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(0, dp(6), 0, 0)
    }
    scannerSourceSummary = sourceSummary
    sourcesCard.addView(sourceSummary, LinearLayout.LayoutParams(-1, -2))
    val forceRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    scannerForceRow = forceRow
    sourcesCard.addView(forceRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    body.addView(sourcesCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    renderScannerSources()

    // Results table right below controls + sources so the user sees results immediately!
    body.addView(
        scannerFilterRow(),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    body.addView(
        scannerSelectionBar(),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    body.addView(scannerHeaderRow(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    body.addView(
        TextView(this).apply {
            setText(R.string.scanner_sort_hint)
            textSize = 10f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(12), dp(2), dp(12), 0)
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    scannerCountryText = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        visibility = View.GONE
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(0, dp(8), 0, 0)
    }
    body.addView(scannerCountryText, LinearLayout.LayoutParams(-1, -2))

    scannerResultsList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    body.addView(
        scannerResultsList,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    renderScannerResults()

    // Secondary tools placed inside collapsible extraScannerToolsHost
    addDeploymentProfilesSection(extraScannerToolsHost)

    scannerForeignList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val foreignButton = MaterialButton(this).apply {
        setText(R.string.scanner_foreign_btn)
        textSize = 13.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 78))
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { runScannerForeignTest() }
    }
    extraScannerToolsHost.addView(foreignButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    val foreignHint = TextView(this).apply {
        setText(R.string.scanner_foreign_hint)
        textSize = 11.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(4), dp(4), dp(4), dp(2))
    }
    extraScannerToolsHost.addView(foreignHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    extraScannerToolsHost.addView(scannerForeignList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    scannerForeignShareButton = MaterialButton(this).apply {
        setText(R.string.scanner_foreign_share)
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 30))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 105))
        setTextColor(TEAL)
        insetTop = 0
        insetBottom = 0
        visibility = View.GONE
        setOnClickListener { shareScannerForeignReport() }
    }
    extraScannerToolsHost.addView(scannerForeignShareButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    scannerAiList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val aiButton = MaterialButton(this).apply {
        setText(R.string.scanner_ai_btn)
        textSize = 13.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(withAlpha(AMBER, 70))
        setTextColor(palette.onAccent)
        insetTop = 0
        insetBottom = 0
        setOnClickListener { runScannerAiTest() }
    }
    extraScannerToolsHost.addView(aiButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    extraScannerToolsHost.addView(scannerAiList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val extraScannerToggleBtn = MaterialButton(this).apply {
        val closedText = if (isFarsiScanner) "🛠️ ابزارهای تکمیلی اسکنر (اسکنر SNI · ایپی ثابت · سلامت خودکار · تست سرویس‌ها) ▾" else "🛠️ More Scanner Tools (SNI Scanner · Static IP · Auto-Health · Service Probes) ▾"
        val openText = if (isFarsiScanner) "🛠️ بستن ابزارهای تکمیلی اسکنر ▴" else "🛠️ Hide More Scanner Tools ▴"
        text = closedText
        isAllCaps = false
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 180))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 180))
        cornerRadius = dp(12)
        minHeight = dp(44)
        setOnClickListener {
            val show = extraScannerToolsHost.visibility != View.VISIBLE
            extraScannerToolsHost.visibility = if (show) View.VISIBLE else View.GONE
            text = if (show) openText else closedText
        }
    }
    body.addView(extraScannerToggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    body.addView(extraScannerToolsHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

    return scroll.apply { addView(body, FrameLayout.LayoutParams(-1, -2)) }
}

/**
 * Draws the four source switches with their live counts.
 *
 * `count` is what IpScanner says this source will REALLY contribute (24 hosts per
 * range by default, v6 as 0 unless the network/forced switch allows it) — not the
 * number of entries in the list, which is the number that made «5 ranges» look
 * like plenty while a v6-less line produced zero v6 rows forever.
 */
internal fun MainActivity.renderScannerSources() {
    val row = scannerSourceRow ?: return
    row.removeAllViews()
    val customSubnets = scannerSubnetsInput?.text?.toString()?.trim().orEmpty()
    val options = IpScanner.ScanOptions(
        customSubnets = customSubnets,
        includeBuiltin = scannerSourceEnabled("cloudflare"),
        includeIranLibrary = scannerSourceEnabled("iran"),
        includeIpv6 = scannerSourceEnabled("ipv6"),
        perRange = when (scannerProfile) {
            "fast" -> (SCANNER_PER_RANGE / 2).coerceAtLeast(2)
            "deep" -> SCANNER_PER_RANGE * 3
            else -> SCANNER_PER_RANGE
        },
        randomSample = true,
    )
    val report = IpScanner.sourceReport(options)
    val byKey = report.associateBy { it.key }
    IpScanner.SCAN_SOURCE_KEYS.forEach { key ->
        val item = byKey[key] ?: return@forEach
        val on = when (key) {
            "mine" -> scannerSourceEnabled("mine")
            "cloudflare" -> scannerSourceEnabled("cloudflare")
            "iran" -> scannerSourceEnabled("iran")
            else -> scannerSourceEnabled("ipv6")
        }
        val label = getString(
            when (key) {
                "mine" -> R.string.scanner_src_mine
                "cloudflare" -> R.string.scanner_src_cloudflare
                "iran" -> R.string.scanner_src_iran
                else -> R.string.scanner_src_ipv6
            },
        )
        val chip = TextView(this).apply {
            text = if (on) "$label · ${item.count}" else label
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            // Highlight only a source that really contributes: with a CIDR in the box the
            // Cloudflare library is switched on but suppressed, and a highlighted «· 0»
            // chip would read as «on and finding nothing».
            val active = on && item.count > 0
            background = glassSurfaceDrawable(radiusDp = 16, highlighted = active)
            setTextColor(if (active) palette.onAccent else TEXT_SECONDARY)
            setOnClickListener {
                // "mine" is the ranges box itself: switching it off means the box is
                // ignored (the same as clearing it) while the text stays for the next run.
                // Switching v6 off also clears the old «force» flag, otherwise the force
                // path would keep the source on and the switch would look broken.
                val next = !on
                saveScannerSource(key, next)
                if (key == "ipv6" && !next) {
                    scannerForceV6 = false
                    getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE)
                        .edit().putBoolean("scanner_force_v6", false).apply()
                }
                renderScannerSources()
            }
        }
        row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    }
    // «enabled» already folds in the switches AND the reality (a v6 source with no v6
    // addresses enabled is not counted), so the total matches what a run will walk.
    val total = report.filter { it.enabled }.sumOf { it.count }
    scannerSourceSummary?.text = if (total <= 0) {
        getString(R.string.scanner_sources_empty)
    } else {
        getString(R.string.scanner_sources_total, total, report.count { it.enabled && it.count > 0 })
    }
    scannerSourceSummary?.setTextColor(if (total <= 0) ERROR else TEXT_SECONDARY)

    val forceRow = scannerForceRow ?: return
    forceRow.removeAllViews()
    val forceChip = TextView(this).apply {
        text = getString(R.string.scanner_force_v6) + if (scannerForceV6) " ✓" else ""
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isClickable = true
        isFocusable = true
        background = glassSurfaceDrawable(radiusDp = 16, highlighted = scannerForceV6)
        setTextColor(if (scannerForceV6) palette.onAccent else TEXT_SECONDARY)
        setOnClickListener {
            scannerForceV6 = !scannerForceV6
            getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE)
                .edit().putBoolean("scanner_force_v6", scannerForceV6).apply()
            if (scannerForceV6) saveScannerSource("ipv6", true) // forcing implies the source is on
            renderScannerSources()
        }
    }
    forceRow.addView(forceChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
}

internal fun MainActivity.startIpScanner() {
    if (scannerRunning || scannerNeighborRunning) return
    val sni = scannerSniInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_SCANNER_SNI }
    saveScannerSni(sni)
    val customSubnets = scannerSubnetsInput.text?.toString()?.trim().orEmpty()
    saveScannerRanges(customSubnets)
    val parsedRanges = IpScanner.parseRangeList(customSubnets)
    if (customSubnets.isNotBlank() && parsedRanges.isEmpty()) {
        Toast.makeText(this, R.string.scanner_ranges_invalid, Toast.LENGTH_LONG).show()
        return
    }
    scannerRunning = true
    scannerStartButton.isEnabled = false
    scannerStopButton.isEnabled = true
    scannerProgressBar.visibility = View.VISIBLE
    scannerStatusText.setText(R.string.scanner_progress_hint)
    scannerLiveResults.clear()
    scannerSelected.clear()
    scannerResultsList.removeAllViews()
    scannerApplyButton.isEnabled = false
    val scanPrefs = getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
    val useAntiDpi = scanPrefs.getBoolean("anti_dpi_scan", true)
    val useLimitedNet = scanPrefs.getBoolean("limited_net_scan", false)
    val useAll13Ports = scanPrefs.getBoolean("all_13_ports_scan", false)
    val activePorts = if (useAll13Ports) {
        (listOf(scannerPort) + IpScanner.CLOUDFLARE_ALL_13_PORTS).distinct()
    } else {
        (listOf(scannerPort) + IpScanner.DEFAULT_SCAN_PORTS).distinct()
    }
    val options = IpScanner.ScanOptions(
        sni = sni,
        port = scannerPort,
        ports = activePorts,
        customSubnets = customSubnets,
        // The four switches in the «منابع اسکن» card own this decision now. They
        // default to the old hardcoded values (ranges + both libraries on, v6 via
        // the probe below), so an untouched install scans exactly what it did.
        includeBuiltin = scannerSourceEnabled("cloudflare"),
        includeIranLibrary = scannerSourceEnabled("iran"),
        perRange = when (scannerProfile) {
            "fast" -> (SCANNER_PER_RANGE / 2).coerceAtLeast(2)
            "deep" -> SCANNER_PER_RANGE * 3
            else -> SCANNER_PER_RANGE
        },
        randomSample = true,
        // 🌙 Gentle (SenPai-style): 6 workers only — for ISPs that cut the
        // whole connection when a scan floods SYN packets.
        concurrency = if (scannerProfile == "gentle" || useLimitedNet) 6 else SCANNER_CONCURRENCY,
        // Profile depth: fast = snappy timeouts, deep = doubled TLS patience,
        // gentle = calm pacing with generous timeouts.
        connectTimeoutMs = when {
            scannerProfile == "fast" -> 1000
            scannerProfile == "gentle" || useLimitedNet -> 4000
            else -> SCANNER_CONNECT_TIMEOUT_MS
        },
        tlsTimeoutMs = when {
            scannerProfile == "deep" || scannerProfile == "gentle" || useLimitedNet -> SCANNER_TLS_TIMEOUT_MS * 2
            else -> SCANNER_TLS_TIMEOUT_MS
        },
        verifyHttp = true,
        antiDpiFragment = useAntiDpi,
        fragmentMode = "v52-fragA",
        limitedNetworkMode = useLimitedNet,
    )
    scannerProgressBar.progress = 0
    scannerJob = activityScope.launch {
        // Dual-stack: probe once whether v6 really works here; if so the v6
        // ranges join the walk and results of both families are ranked together.
        val ipv6 = (scannerForceV6 || withContext(Dispatchers.IO) { IpScanner.hasIpv6Connectivity() }) &&
            scannerSourceEnabled("ipv6")
        mainHandler.post {
            scannerStatusText.setText(
                when {
                    // The switch, not just the probe: if the owner turned the v6
                    // source off, saying «no v6 on this network» would be a lie.
                    !scannerSourceEnabled("ipv6") -> R.string.scanner_ipv6_off
                    scannerForceV6 -> R.string.scanner_ipv6_forced
                    ipv6 -> R.string.scanner_ipv6_detected
                    else -> R.string.scanner_ipv6_absent
                },
            )
        }
        val found = runCatching {
            IpScanner.scan(this@startIpScanner, options.copy(includeIpv6 = ipv6)) { done, total, result ->
                // Called from an IO thread for every finished candidate.
                mainHandler.post {
                    if (!scannerRunning) return@post
                    val percent = (done * 100) / total.coerceAtLeast(1)
                    scannerProgressBar.progress = percent
                    if (result != null && scannerLiveResults.none { it.authority == result.authority }) {
                        scannerLiveResults += result
                    }
                    val best = scannerLiveResults
                        .filter { it.tlsOk }
                        .minByOrNull { it.pingMs }
                        ?.pingMs
                        ?: scannerLiveResults.minByOrNull { it.pingMs }?.pingMs
                    scannerStatusText.text = if (best == null) {
                        getString(R.string.scanner_progress_empty, done, total, percent)
                    } else {
                        getString(R.string.scanner_progress, done, total, percent, best)
                    }
                    if (done % SCANNER_LIVE_REFRESH_EVERY == 0 || done == total) {
                        scannerLiveResults.sortWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
                        renderScannerResults()
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (!scannerRunning) return@launch
        scannerRunning = false
        scannerStartButton.isEnabled = true
        scannerStopButton.isEnabled = false
        scannerProgressBar.visibility = View.GONE
        scannerResults = found.sortedWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
        // Drop ticks left over from an earlier run (rows this scan did not produce).
        scannerSelected.removeAll { key -> scannerResults.none { scannerRowKey(it) == key } }
        IpHealthMonitor.seed(
            IpHealthStore(this@startIpScanner),
            scannerResults,
            scannerSniPreference(),
            scannerPortPreference(),
        )
        // ⚡ Advanced: record the run (best ms + verified count), then the
        // 🏠/☁️ pass: real ping + jitter of the best rows on YOUR line (TCP) and
        // through the worker (warm TLS, request → first byte), per exact ip:port.
        saveScanHistoryRun(found.filter { it.tlsOk }.minByOrNull { it.pingMs }?.pingMs?.toInt(), found.count { it.tlsOk }, found.size)
        renderScannerResults()
        val measureRows = scannerResults.filter { it.tlsOk }.take(SCANNER_MEASURE_ROWS)
        if (measureRows.isNotEmpty()) {
            val farsiMeasure = appLanguagePreferenceStore.read() == AppLanguage.Persian
            scannerStatusText.text = if (farsiMeasure) {
                "⏱ سنجش پینگ و جیتر ${measureRows.size} آی‌پی برتر: روی خط تو و از راه ورکر…"
            } else {
                "⏱ Measuring ping & jitter of the top ${measureRows.size}: on your line and through the worker…"
            }
            val measured = withContext(Dispatchers.IO) {
                val workerHost = runCatching { scannerWorkerHost(sni) }.getOrNull()
                measureScannerRows(measureRows, workerHost)
            }
            // A new scan started meanwhile: its rows are not ours to rewrite.
            if (scannerRunning) return@launch
            scannerResults = scannerResults.map { measured[scannerRowKey(it)] ?: it }
        }
        renderScanHistory()
        renderScannerResults()
        renderScannerIpHealth()
        val v6Hint = if (!ipv6) " · " + getString(R.string.scanner_ipv6_hint) else ""
        scannerStatusText.text = if (found.isEmpty()) {
            getString(R.string.scanner_no_results) + v6Hint
        } else {
            getString(R.string.scanner_done_detailed, found.size, found.count { it.tlsOk }) +
                if (!ipv6 && found.none { IpScanner.isIpv6(it.ip) }) v6Hint else ""
        }
    }
}

internal fun MainActivity.renderScannerIpHealth() {
    val view = ipHealthStatusView ?: return
    val store = IpHealthStore(this)
    val entries = store.entries()
    val last = store.events().firstOrNull()
    renderIpHealthCountries(entries)
    view.text = buildString {
        append(getString(R.string.ip_health_pool, entries.size))
        val best = entries.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }
        if (best != null) {
            append("  ·  ")
            append(getString(R.string.ip_health_best, best.authority, best.pingMs))
        }
        if (last != null) {
            append('\n')
            append(getString(R.string.ip_health_last_event, last.removedIp, last.addedIp ?: "—"))
        }
        val genomes = NetworkGenomeStore(this@renderScannerIpHealth)
        val stats = genomes.stats()
        val network = NetworkContext.key(this@renderScannerIpHealth)
        val active = frontingIps.firstOrNull()
        if (active != null) {
            val g = genomes.genome(active).forNetwork(network)
            if (g.count > 0) {
                append('\n')
                append(getString(R.string.engine_active_line, active, g.median, g.p95, (g.successRate * 100).toInt(), (g.confidence * 100).toInt()))
                g.anomaly()?.let { append("  ⚠ ").append(it.type).append(' ').append(it.detail) }
            }
        }
        if (stats.probes > 0) {
            append('\n')
            append(getString(R.string.engine_stats_line, stats.observations, (stats.probeSuccessRate * 100).toInt(), stats.failovers, stats.lastRecoveryMs / 1000.0, (stats.predictionAccuracy * 100).toInt(), network))
            if (stats.lastFailoverTo.isNotBlank()) {
                append('\n')
                append(getString(R.string.engine_last_failover, stats.lastFailoverFrom, stats.lastFailoverTo, stats.lastFailoverReason))
            }
        }
        val nowMs = System.currentTimeMillis()
        val currentNetId = CurrentEndpointNetwork.identity(applicationContext)
        val cleanEvidence = entries.filter { it.fails == 0 && it.pingMs > 0L && it.port in 1..65535 }.mapNotNull { e ->
            val ep = VerifiedEndpointParser.parseAuthority(
                "${e.ip}:${e.port}",
                EndpointRole.CLEAN_IP,
                EndpointVerdict.VERIFIED,
                e.checkedAt.takeIf { it > 0L } ?: nowMs,
                "scanner",
            ) ?: return@mapNotNull null
            val isTls = e.port !in EndpointPortDiscovery.cleanPlainPorts
            CachedEndpointEvidence(
                key = CurrentEndpointNetwork.evidenceKey(
                    context = applicationContext,
                    endpoint = ep,
                    protocol = "vless",
                    serverName = "worker",
                    tls = isTls,
                ),
                evidence = EndpointProbeEvidence(
                    tcpConnected = true,
                    tlsCompleted = isTls,
                    protocolHandshakeCompleted = true,
                    websocketCompleted = true,
                    runtimeCompleted = true,
                    latencyMs = e.pingMs,
                ),
                checkedAtMs = ep.checkedAtMs,
            )
        }
        val recommendedPorts = OperatorPortRecommendation.fromEvidence(
            entries = cleanEvidence,
            currentNetworkId = currentNetId,
            nowMs = nowMs,
            maxAgeMs = 86_400_000L,
            role = EndpointRole.CLEAN_IP,
        )
        if (recommendedPorts.isNotEmpty()) {
            append("\n⚡ Port recommendation ($network): ")
            append(recommendedPorts.take(3).joinToString(" · ") { "${it.port} (${it.medianLatencyMs ?: "—"} ms)" })
        }
    }
}

/** 🛡 Retains the existing AI endpoint checks over the current network. A
 * 403/404 means the endpoint replied but does not prove the app is usable. */
internal fun MainActivity.runScannerAiTest() {
    if (scannerAiRunning) return
    scannerAiRunning = true
    scannerAiList.removeAllViews()
    val status = TextView(this).apply {
        setText(R.string.scanner_ai_running)
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
    }
    scannerAiList.addView(status)
    activityScope.launch {
        val targets = listOf(
            Triple("ChatGPT", "https://chatgpt.com/", true),
            Triple("Claude", "https://claude.ai/", true),
            Triple("Gemini API", "https://generativelanguage.googleapis.com/", true),
            Triple("Gemini Web", "https://gemini.google.com/", false),
            Triple("AI Studio", "https://aistudio.google.com/", false),
        )
        for ((name, url, preflight) in targets) {
            val t0 = System.currentTimeMillis()
            val code = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                        requestMethod = "GET"
                        responseCode
                    }
                    val c = conn.responseCode
                    runCatching { conn.disconnect() }
                    c
                }.getOrDefault(0)
            }
            val ms = (System.currentTimeMillis() - t0).toInt()
            val verdict = when {
                code == 200 -> R.string.scanner_ai_ok
                preflight && code in 401..404 -> R.string.scanner_ai_pre
                code == 429 -> R.string.scanner_ai_rate
                else -> R.string.scanner_ai_fail
            }
            val color = if (verdict == R.string.scanner_ai_fail) AMBER else TEAL
            mainHandler.post {
                val row = LinearLayout(this@runScannerAiTest).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = glassSurfaceDrawable(radiusDp = 12)
                    addView(TextView(this@runScannerAiTest).apply {
                        text = name
                        textSize = 13f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEXT_PRIMARY)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@runScannerAiTest).apply {
                        text = getString(R.string.scanner_ai_row, code, ms)
                        textSize = 11.5f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(color)
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    addView(TextView(this@runScannerAiTest).apply {
                        setText(verdict)
                        textSize = 11f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(color)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(6).toFloat()
                            setColor(withAlpha(color, 26))
                        }
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                    })
                }
                if (status.parent === scannerAiList) scannerAiList.removeView(status)
                scannerAiList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
        }
        scannerAiRunning = false
    }
}

internal suspend fun MainActivity.sendScanToPanelSuspend(override: List<IpScanner.ScanResult>? = null) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val source = override ?: (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
    // ONLY IPs whose TLS handshake actually passed ON THIS NETWORK are sent
    // (checked → then → panel). No unchecked fallback: an unverified IP in
    // the panel produces dead 💦 configs, which is exactly what the user
    // complained about («پروکسی آی‌پیا کار نمی‌کنن»).
    val verified = source.filter { it.tlsOk }
    if (verified.isEmpty()) {
        Toast.makeText(this, R.string.scanner_unverified_only, Toast.LENGTH_LONG).show()
        return
    }
    val selected = verified.take(60)
    val lines = selected.map { it.panelLine }
    updateScannerPanelPushProgress(
        15,
        if (isFarsi) "⏳ در حال آماده‌سازی ${lines.size} آی‌پی تمیز برای ارسال به پنل…" else "⏳ Preparing ${lines.size} clean IPs for panel…",
    )
    // Provenance travels WITH the IPs: every entry is tagged with its source
    // (this scanner), the latency measured ON THIS NETWORK and the country
    // seen by this device — the panel shows the badge next to each IP.
    val ping = org.json.JSONObject()
    val countries = org.json.JSONObject()
    selected.forEach { r ->
        ping.put(r.panelLine, r.pingMs)
        if (!r.countryCode.isNullOrBlank()) countries.put(r.panelLine, r.countryCode)
    }
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-ips", lines.joinToString("\n")))
    // The paste-fallback is always available, so SAY it: Panel → Tools →
    // «Import scan results» accepts these very lines (ip:port#CC).
    Toast.makeText(this, getString(R.string.scanner_panel_clipboard, lines.size), Toast.LENGTH_LONG).show()
    // Push straight into the panel over its API (login → POST /api/ips, append).
    // The old deep link opened the scanner SNI instead of the panel, so IPs
    // never arrived — the panel host comes from the subscriptions now.
    val base = kotlinx.coroutines.withContext(Dispatchers.Main) { pickPanelBaseInteractive() }?.trim()?.trimEnd('/')
    if (base.isNullOrBlank()) {
        updateScannerPanelPushProgress(100, if (isFarsi) "📋 در کلیپ‌بورد کپی شد (آدرس پنل انتخاب نشد)" else "📋 Copied to clipboard")
        Toast.makeText(this, getString(R.string.scanner_panel_none, lines.size), Toast.LENGTH_LONG).show()
        return
    }
    val storedPass = runCatching { PanelCredentials.defaultPassword(this, base) }.getOrNull().orEmpty()
    // A panel deployed with a username rejects password-only logins, so ask
    // for it right here (once — it is remembered per panel) instead of
    // letting the push fail with «wrong password».
    val storedUser = PanelCredentials.username(this, base)
    val needsUser = storedUser.isBlank() && withContext(Dispatchers.IO) { panelNeedsUsername(base) }
    val userInput = TextInputEditText(this).apply {
        hint = getString(R.string.cloud_panel_user_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        setText("admin")
    }
    val input = TextInputEditText(this).apply {
        hint = getString(R.string.pip_password_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (storedPass.isNotBlank()) setText(storedPass)
    }
    val replaceToggle = android.widget.CheckBox(this).apply {
        text = getString(R.string.scanner_replace_mode)
        textSize = 13f
        setTextColor(TEXT_PRIMARY)
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), 0)
        addView(replaceToggle, LinearLayout.LayoutParams(-1, -2))
        if (needsUser) addView(userInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.scanner_send_panel)
        .setMessage(base.removePrefix("https://"))
        .setView(box)
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            updateScannerPanelPushProgress(0, "", visible = false)
        }
        .setPositiveButton(android.R.string.ok) { dialog, _ ->
            val password = input.text?.toString().orEmpty()
            if (needsUser) PanelCredentials.remember(this, base, userInput.text?.toString().orEmpty(), "")
            activityScope.launch {
                val replacing = replaceToggle.isChecked
                val neighborLines = selected.filter { scannerNeighborIps.contains(it.ip) }.map { it.panelLine }
                updateScannerPanelPushProgress(
                    55,
                    if (isFarsi) "📡 در حال ورود و ثبت ${lines.size} آی‌پی سالم در پنل…" else "📡 Pushing ${lines.size} verified IPs to panel…",
                )
                val result = withContext(Dispatchers.IO) { runCatching { pushIpsToPanel(base, password, lines, replacing, ping, countries, neighborLines) } }
                result.onSuccess { n ->
                    val msg = when {
                        replacing -> R.string.scanner_panel_replaced
                        n > 0 -> R.string.scanner_panel_saved
                        else -> R.string.scanner_panel_zero
                    }
                    val doneLabel = getString(msg, n)
                    updateScannerPanelPushProgress(100, "✅ $doneLabel")
                    Toast.makeText(this@sendScanToPanelSuspend, doneLabel, Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    val errLabel = getString(R.string.pip_save_failed, e.message ?: "?")
                    updateScannerPanelPushProgress(100, "❌ $errLabel")
                    Toast.makeText(this@sendScanToPanelSuspend, errLabel, Toast.LENGTH_LONG).show()
                }
            }
            dialog.dismiss()
        }
        .show()
}

internal fun MainActivity.buildSniScannerCard(): View {
    val card = advancedSettingsPanel()
    card.addView(advancedSectionLabel(getString(R.string.sni_scanner_title)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })
    card.addView(advancedSectionDetail(getString(R.string.sni_scanner_desc)), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    val targetInput = scannerInput(getString(R.string.sni_scanner_target_hint), frontingIps.firstOrNull() ?: IpHealthStore(this).entries().firstOrNull()?.authority.orEmpty())
    card.addView(scannerFieldLayout(getString(R.string.sni_scanner_target), targetInput), LinearLayout.LayoutParams(-1, -2))
    val listInput = TextInputEditText(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        background = null
        minLines = 3; maxLines = 8
        gravity = Gravity.TOP or Gravity.START
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        setTextColor(TEXT_PRIMARY); setHintTextColor(TEXT_SECONDARY)
        setHint(getString(R.string.sni_scanner_list_hint))
        setText((detectPanelSnisFromSubscriptions() + IpScanner.RECOMMENDED_SNIS).distinct().joinToString("\n"))
    }
    card.addView(scannerFieldLayout(getString(R.string.sni_scanner_list), listInput), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE; setPadding(dp(8), dp(6), dp(8), 0) }
    val progress = TextView(this).apply { textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); setPadding(dp(8), dp(6), dp(8), 0) }
    val overrideStatus = TextView(this).apply {
        textSize = 12f; typeface = CatClientDataTypeface; setTextColor(TEXT_SECONDARY); setPadding(dp(8), dp(8), dp(8), 0)
        val current = frontingIpPreferenceStore.readSniOverride()
        text = if (current.isBlank()) getString(R.string.sni_override_none) else getString(R.string.sni_override_current, current)
    }
    var running = false
    val run = MaterialButton(this).apply {
        setText(R.string.sni_scanner_run); setAllCaps(false); textSize = 12f; minHeight = dp(40); insetTop = 0; insetBottom = 0; cornerRadius = dp(16)
        backgroundTintList = ColorStateList.valueOf(TEAL); setTextColor(palette.onAccent)
    }
    run.setOnClickListener {
        if (running) return@setOnClickListener
        val target = targetInput.text?.toString()?.trim().orEmpty()
        if (target.isBlank()) { Toast.makeText(this, R.string.sni_scanner_no_target, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
        val snis = listInput.text?.toString().orEmpty().split('\n', ',', ' ', ';').map { it.trim().lowercase().removePrefix("https://").trimEnd('/') }.filter { it.contains('.') }.distinct()
        if (snis.isEmpty()) return@setOnClickListener
        running = true; run.isEnabled = false; results.removeAllViews()
        val port = scannerPortPreference()
        activityScope.launch {
            val done = java.util.concurrent.atomic.AtomicInteger()
            progress.text = getString(R.string.sni_scanner_progress, 0, snis.size)
            val rows = withContext(Dispatchers.IO) {
                val gate = kotlinx.coroutines.sync.Semaphore(8)
                snis.map { sni ->
                    async {
                        gate.withPermit {
                            val best = (1..2).mapNotNull { IpScanner.probe(target, IpScanner.ScanOptions(sni = sni, port = port, includeBuiltin = false, includeIranLibrary = false, verifyHttp = true)) }.minByOrNull { it.pingMs }
                            val n = done.incrementAndGet()
                            withContext(Dispatchers.Main) { progress.text = getString(R.string.sni_scanner_progress, n, snis.size) }
                            sni to best
                        }
                    }
                }.awaitAll()
            }.sortedWith(compareByDescending<Pair<String, IpScanner.ScanResult?>> { it.second?.tlsOk == true }.thenBy { it.second?.pingMs ?: Long.MAX_VALUE })
            running = false; run.isEnabled = true
            val okCount = rows.count { it.second?.tlsOk == true }
            progress.text = getString(R.string.sni_scanner_done, okCount, snis.size)
            rows.forEach { (sni, r) ->
                val ok = r?.tlsOk == true
                results.addView(LinearLayout(this@buildSniScannerCard).apply {
                    orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = glassSurfaceDrawable(radiusDp = 12); clipToOutline = true
                    isClickable = ok; isFocusable = ok
                    if (ok) setOnClickListener {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@buildSniScannerCard)
                            .setTitle(sni)
                            .setItems(arrayOf(getString(R.string.sni_action_configs), getString(R.string.sni_action_scanner), getString(R.string.sni_action_copy), getString(R.string.sni_action_panel), getString(R.string.sni_action_main))) { _, which ->
                                when (which) {
                                    0 -> { applySniOverride(sni); overrideStatus.text = getString(R.string.sni_override_current, sni) }
                                    1 -> {
                                        saveScannerSni(sni)
                                        if (scannerSniInputReady) scannerSniInput.setText(sni)
                                        Toast.makeText(this@buildSniScannerCard, getString(R.string.sni_scanner_applied, sni), Toast.LENGTH_SHORT).show()
                                    }
                                    2 -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("sni", sni))
                                    3 -> pushSniToPanel(sni)
                                    else -> pushSniMainToPanel(sni)
                                }
                            }
                            .show()
                    }
                    addView(TextView(this@buildSniScannerCard).apply { text = if (ok) "TLS ✓" else "✗"; textSize = 10.5f; typeface = CatClientBodyBoldTypeface; setTextColor(if (ok) TEAL else TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                    addView(TextView(this@buildSniScannerCard).apply { text = sni; textSize = 13f; typeface = CatClientDataTypeface; setTextColor(TEXT_PRIMARY); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@buildSniScannerCard).apply { text = if (ok) "${r!!.pingMs} ms" + (r.tlsMs?.let { " · tls $it" } ?: "") else "—"; textSize = 11.5f; typeface = CatClientDataTypeface; setTextColor(TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
    }
    card.addView(LinearLayout(this).apply { setPadding(dp(8), dp(10), dp(8), 0); addView(run, LinearLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2))
    card.addView(progress, LinearLayout.LayoutParams(-1, -2))
    card.addView(results, LinearLayout.LayoutParams(-1, -2))
    card.addView(overrideStatus, LinearLayout.LayoutParams(-1, -2))
    val clearOverride = MaterialButton(this).apply {
        setText(R.string.sni_override_clear); setAllCaps(false); textSize = 11.5f; minWidth = 0; minimumWidth = 0; minHeight = dp(34); minimumHeight = dp(34)
        insetTop = 0; insetBottom = 0; cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEXT_SECONDARY, 30)); setTextColor(TEXT_PRIMARY)
        setOnClickListener { applySniOverride(null); overrideStatus.text = getString(R.string.sni_override_none) }
    }
    card.addView(LinearLayout(this).apply { setPadding(dp(8), dp(4), dp(8), 0); addView(clearOverride, LinearLayout.LayoutParams(-2, -2)) }, LinearLayout.LayoutParams(-1, -2))
    return card
}

/**
 * 🏳️ Ultimate Whitelist, Multi-CDN & Anti-DPI Super-Scanner Dialog:
 * Combines parallel Non-Cloudflare & Iran Domestic Whitelist scanning (22 targets),
 * Anti-DPI Serverless-v52 fragmented TLS verification, Deep `/24` Neighbor Harvesting around
 * live whitelist hits, 16KB Upload-Freeze Speed Testing, and 1-tap Subscription Injection.
 */
internal fun MainActivity.showWhitelistSuperScannerDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val rootScroll = ScrollView(this).apply {
        isFillViewport = true
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(18), dp(14), dp(18), dp(18))
    }
    rootScroll.addView(container)

    container.addView(
        TextView(this).apply {
            text = if (isFarsi) {
                "🏳️ ابر-اسکنر آی‌پی‌های سفید، پلتفرم‌ها و نت ملی (نسخه ۲۰۲۶)\n" +
                    "• اسکن موازی ۲۲ هدف سفید (آروان، دراک، پارس‌پک، ستون، فستلی، گیت‌هاب، آکامای، گوگل، آژور، Vercel، Render، Fly.io)\n" +
                    "• مجهز به برش دو مرحله‌ای Serverless-v52 در هندشیک TLS برای کشف آی‌پی‌هایی که اسکنرهای عادی نمی‌بینند\n" +
                    "• استخراج خودکار همسایه‌های زیرشبکه /24 اطراف هر آی‌پی سفید باز و تزریق ۱-کلیکی به اشتراک"
            } else {
                "🏳️ 2026 Ultimate Whitelist, Multi-CDN & Anti-DPI Super-Scanner\n" +
                    "• Parallel scan across 22 Whitelist & Non-CF CDN targets (Arvan, Derak, ParsPack, Sotoon, Fastly, GitHub, Akamai, Google, Azure, Vercel, Render, Fly.io)\n" +
                    "• Powered by Serverless-v52 two-stage TLS fragmentation\n" +
                    "• Deep /24 subnet neighbor harvesting + 1-tap config injection"
            }
            textSize = 12f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(dp(3).toFloat(), 1f)
        },
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) },
    )

    val liveStatusView = TextView(this).apply {
        text = if (isFarsi) {
            "📊 آماده اسکن — یکی از عملیات‌های زیر را انتخاب کنید:"
        } else {
            "📊 Ready — choose a scan operation below:"
        }
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEAL)
        background = glassSurfaceDrawable(radiusDp = 14)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextIsSelectable(true)
    }
    container.addView(liveStatusView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

    val discoveredWhitelistResults = mutableListOf<IpScanner.ScanResult>()

    fun makeSuperBtn(label: String, filled: Boolean = false, accentColor: Int = TEAL, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            cornerRadius = dp(12)
            minHeight = dp(46)
            if (filled) {
                backgroundTintList = ColorStateList.valueOf(accentColor)
                setTextColor(palette.onAccent)
            } else {
                backgroundTintList = ColorStateList.valueOf(withAlpha(accentColor, 34))
                strokeColor = ColorStateList.valueOf(accentColor)
                strokeWidth = dp(1)
                setTextColor(TEXT_PRIMARY)
            }
            setOnClickListener { onClick() }
        }

    // Action 1: Parallel Scan of All 22 Whitelist & Non-CF CDN Targets (with Anti-DPI Split Fallback)
    container.addView(
        makeSuperBtn(
            label = if (isFarsi) "🏳️ ۱) اسکن موازی همهٔ ۲۲ هدف آی‌پی سفید و CDNهای غیرکلادفلر (ایران + جهانی)"
            else "🏳️ 1) Parallel Scan All 22 Whitelist & Non-CF CDN Targets (Iran + Global)",
            filled = true,
        ) {
            liveStatusView.text = if (isFarsi) {
                "⏳ در حال اسکن موازی ۲۲ درگاه سفید (آروان، دراک، پارس‌پک، ستون، فستلی، گیت‌هاب، آکامای، گوگل، آژور، Vercel، Render، Fly.io) با برش ضد-DPI…"
            } else {
                "⏳ Scanning 22 Whitelist & Non-CF CDN targets in parallel with Anti-DPI fragmentation…"
            }
            activityScope.launch {
                val hits = withContext(Dispatchers.IO) {
                    LocalSniSpoofProxy.scanNonCloudflareWhitelistTargets(
                        providerFilter = null,
                        timeoutMs = 1_100,
                        useAntiDpiSplit = true,
                    )
                }
                val mapped = hits.map { h ->
                    IpScanner.ScanResult(
                        ip = h.target.ip,
                        pingMs = h.effectiveRttMs,
                        sni = h.target.sni,
                        tlsMs = h.tlsRttMs,
                        colo = h.target.provider.id.uppercase(),
                        tlsOk = h.tlsVerified,
                        httpStatus = if (h.tlsVerified) 200 else null,
                        port = h.target.port,
                        countryCode = IpScanner.detectCountryCodeForWhitelistIp(h.target.ip),
                        countryName = IpScanner.detectEdgeLabelForIp(h.target.ip, h.target.provider.id),
                        sourceRange = "whitelist-${h.target.provider.id}",
                        verifyTls = true,
                    )
                }
                discoveredWhitelistResults.clear()
                discoveredWhitelistResults.addAll(mapped)
                if (mapped.isNotEmpty()) {
                    val merged = (mapped + scannerResults).distinctBy { it.authority }
                        .sortedWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
                    scannerResults = merged
                    runCatching { renderScannerResults() }
                    runCatching {
                        scannerApplyButton.isEnabled = merged.any { it.tlsOk }
                        scannerBuildButton.isEnabled = merged.any { it.tlsOk }
                        scannerSpeedTestButton?.isEnabled = merged.any { it.tlsOk }
                    }
                }
                liveStatusView.text = buildString {
                    if (isFarsi) {
                        appendLine("✅ اسکن موازی وایت‌لیست کامل شد: ${hits.size} آی‌پی سفید پاسخ‌گو (${hits.count { it.tlsVerified }} تایید کامل TLS)")
                        appendLine("────────────────────────────")
                        hits.take(12).forEachIndexed { i, h ->
                            val tlsTag = if (h.tlsVerified) "TLS✓ (${h.tlsRttMs}ms)" else "TCP✓ (${h.tcpRttMs}ms)"
                            appendLine("${i + 1}. ${h.exactEndpoint} | $tlsTag | SNI: ${h.target.sni}")
                            appendLine("   👈 ${h.target.noteFa}")
                        }
                        if (hits.isNotEmpty()) {
                            appendLine("────────────────────────────")
                            append("💡 این آی‌پی‌ها به جدول اصلی اسکنر هم اضافه شدند! حالا می‌توانید دکمه ۲ (کشف همسایه‌های /24) یا دکمه ۵ (تزریق به اشتراک) را بزنید.")
                        }
                    } else {
                        appendLine("✅ Whitelist Scan Complete: ${hits.size} reachable (${hits.count { it.tlsVerified }} TLS verified)")
                        hits.take(12).forEachIndexed { i, h ->
                            val tlsTag = if (h.tlsVerified) "TLS✓ (${h.tlsRttMs}ms)" else "TCP✓ (${h.tcpRttMs}ms)"
                            appendLine("${i + 1}. ${h.exactEndpoint} | $tlsTag | SNI: ${h.target.sni}")
                        }
                    }
                }
            }
        },
        LinearLayout.LayoutParams(-1, -2),
    )

    // Action 2: Deep /24 Whitelist Subnet Neighbor Harvester
    container.addView(
        makeSuperBtn(
            label = if (isFarsi) "👑 ۲) کشف خودکار همسایه‌های /24 اطراف آی‌پی‌های سفید باز (استخراج ده‌ها آی‌پی سفید جدید)"
            else "👑 2) Harvest /24 Subnet Neighbors Around Live Whitelist Hits",
            accentColor = AMBER,
        ) {
            val basePool = if (discoveredWhitelistResults.isNotEmpty()) discoveredWhitelistResults else scannerResults
            val topSeeds = basePool.filter { it.tlsOk }.ifEmpty { basePool }.take(3)
            if (topSeeds.isEmpty()) {
                Toast.makeText(
                    this,
                    if (isFarsi) "⚠️ ابتدا دکمه ۱ را بزنید تا آی‌پی‌های سفید اولیه پیدا شوند."
                    else "⚠️ Run step 1 first to discover initial whitelist seeds.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@makeSuperBtn
            }
            val seedIps = topSeeds.map { it.ip }
            val primarySni = topSeeds.first().sni.ifBlank { "ebanksepah.ir" }
            val primaryPort = topSeeds.first().port
            val expandedNeighbors = IpScanner.expandAroundGoldenHits(seedIps, radius = 16)
            liveStatusView.text = if (isFarsi) {
                "👑 در حال اسکن موازی ${expandedNeighbors.size} آی‌پی همسایه در زیرشبکه /24 اطراف ${seedIps.joinToString(", ")} با SNI=$primarySni و برش ضد-DPI…"
            } else {
                "👑 Scanning ${expandedNeighbors.size} neighbor IPs in /24 around ${seedIps.joinToString(", ")}…"
            }
            activityScope.launch {
                val foundNeighbors = withContext(Dispatchers.IO) {
                    IpScanner.scan(
                        this@showWhitelistSuperScannerDialog,
                        IpScanner.ScanOptions(
                            sni = primarySni,
                            port = primaryPort,
                            ports = listOf(primaryPort),
                            customSubnets = expandedNeighbors.joinToString(","),
                            includeBuiltin = false,
                            includeIranLibrary = false,
                            includeIpv6 = true,
                            perRange = 1,
                            randomSample = false,
                            concurrency = 20,
                            connectTimeoutMs = 1_100,
                            tlsTimeoutMs = 1_800,
                            verifyHttp = false,
                            antiDpiFragment = true,
                            fragmentMode = "v52-fragA",
                        ),
                    )
                }
                val verifiedNeighbors = foundNeighbors.filter { it.tlsOk }.ifEmpty { foundNeighbors }
                if (verifiedNeighbors.isNotEmpty()) {
                    discoveredWhitelistResults.addAll(verifiedNeighbors)
                    val merged = (verifiedNeighbors + scannerResults).distinctBy { it.authority }
                        .sortedWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
                    scannerResults = merged
                    runCatching { renderScannerResults() }
                    runCatching {
                        scannerApplyButton.isEnabled = merged.any { it.tlsOk }
                        scannerBuildButton.isEnabled = merged.any { it.tlsOk }
                    }
                }
                liveStatusView.text = buildString {
                    if (isFarsi) {
                        appendLine("👑 نتیجهٔ کشف زیرشبکه /24 وایت‌لیست: ${verifiedNeighbors.size} آی‌پی همسایهٔ جدید از ${expandedNeighbors.size} آی‌پی پاسخ دادند!")
                        appendLine("────────────────────────────")
                        verifiedNeighbors.take(10).forEachIndexed { idx, r ->
                            appendLine("${idx + 1}. ${r.authority} · ${r.pingMs}ms · ${r.countryName ?: "Whitelist"} (SNI: ${r.sni})")
                        }
                    } else {
                        appendLine("👑 /24 Neighbor Harvest: ${verifiedNeighbors.size}/${expandedNeighbors.size} new sibling IPs responded!")
                        verifiedNeighbors.take(10).forEachIndexed { idx, r ->
                            appendLine("${idx + 1}. ${r.authority} · ${r.pingMs}ms (SNI: ${r.sni})")
                        }
                    }
                }
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Action 3: Multi-Port Cloudflare Golden F&F + IPv6 Anti-DPI Scan (6 TLS ports)
    container.addView(
        makeSuperBtn(
            label = if (isFarsi) "🔥 ۳) اسکن ۶ پورت TLS روی لبه‌های طلایی کلادفلر (F&F ایرانسل + همراه اول IPv6)"
            else "🔥 3) Scan 6 TLS Ports on Golden Cloudflare F&F + MCI IPv6 Edges",
        ) {
            liveStatusView.text = if (isFarsi) {
                "🔥 در حال اسکن ۶ پورت TLS (443, 2053, 2083, 2087, 2096, 8443) روی لبه‌های طلایی کلادفلر…"
            } else {
                "🔥 Scanning 6 TLS ports across golden Cloudflare F&F & IPv6 edges…"
            }
            activityScope.launch {
                val cfHits = withContext(Dispatchers.IO) {
                    WhiteDnsSmartScanner.scanCloudflareSixPortsAndSni(
                        customSni = scannerSniPreference().ifBlank { "speed.cloudflare.com" },
                        maxHosts = 6,
                        timeoutMs = 1_100,
                    )
                }
                val mappedCf = cfHits.map { h ->
                    IpScanner.ScanResult(
                        ip = h.host.removeSurrounding("[", "]"),
                        pingMs = h.rttMs,
                        sni = h.sni,
                        tlsMs = h.rttMs,
                        tlsOk = true,
                        port = h.port,
                        countryName = "Cloudflare Golden Edge",
                        sourceRange = "cf-golden-6port",
                        verifyTls = true,
                    )
                }
                if (mappedCf.isNotEmpty()) {
                    discoveredWhitelistResults.addAll(mappedCf)
                    val merged = (mappedCf + scannerResults).distinctBy { it.authority }
                        .sortedWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
                    scannerResults = merged
                    runCatching { renderScannerResults() }
                }
                liveStatusView.text = buildString {
                    if (isFarsi) {
                        appendLine("🔥 نتیجهٔ اسکن ۶ پورت طلایی کلادفلر: ${cfHits.size} زوج IP:port باز پیدا شد!")
                        cfHits.forEachIndexed { idx, h ->
                            appendLine("${idx + 1}. ${h.endpoint} · ${h.rttMs}ms · SNI: ${h.sni}")
                        }
                    } else {
                        appendLine("🔥 Golden 6-Port Scan: ${cfHits.size} open IP:port pairs found!")
                        cfHits.forEachIndexed { idx, h ->
                            appendLine("${idx + 1}. ${h.endpoint} · ${h.rttMs}ms · SNI: ${h.sni}")
                        }
                    }
                }
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Action 4: Real 16KB Upload-Freeze & Download Throughput Test on Best Discovered IPs
    container.addView(
        makeSuperBtn(
            label = if (isFarsi) "🧪 ۴) تست واقعی سرعت دانلود + تست ضد-فریز آپلود ۱۶ کیلوبایت روی بهترین آی‌پی‌ها"
            else "🧪 4) Real Download Speed + 16KB Upload-Freeze Probe on Best Hits",
        ) {
            val pool = (discoveredWhitelistResults + scannerResults).distinctBy { it.authority }
                .filter { it.tlsOk }
                .take(3)
            if (pool.isEmpty()) {
                Toast.makeText(
                    this,
                    if (isFarsi) "⚠️ ابتدا دکمه ۱ یا ۳ را بزنید تا آی‌پی‌های باز پیدا شوند."
                    else "⚠️ Run step 1 or 3 first.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@makeSuperBtn
            }
            liveStatusView.text = if (isFarsi) {
                "🧪 در حال تست واقعی انتقال دیتا و بررسی قفل آپلود ۱۶ کیلوبایت روی ${pool.size} آی‌پی برتر…"
            } else {
                "🧪 Probing download throughput & 16KB upload-freeze survival on top ${pool.size} endpoints…"
            }
            activityScope.launch {
                val lines = withContext(Dispatchers.IO) {
                    pool.map { r ->
                        val rep = LocalSniSpoofProxy.probeTunnelSpeedAndUploadFreeze(
                            host = r.ip,
                            port = r.port,
                            sni = r.sni.ifBlank { "speed.cloudflare.com" },
                            timeoutMs = 2_400,
                        )
                        r to rep
                    }
                }
                liveStatusView.text = buildString {
                    if (isFarsi) {
                        appendLine("🧪 نتیجهٔ تست واقعی سرعت و ضد-فریز ۱۶ کیلوبایت:")
                        appendLine("────────────────────────────")
                        lines.forEachIndexed { idx, (r, rep) ->
                            val upBadge = if (rep.uploadBurstHealthy) "🟢 بدون قفل آپلود 16KB" else "🟠 حساس به آپلود (نیازمند برش v52)"
                            appendLine("${idx + 1}. ${r.authority} (${r.countryName ?: "Edge"})")
                            appendLine("   👈 پینگ: ${rep.pingMs}ms | دانلود: ${rep.downloadSpeedMbps} Mbps | آپلود: ${rep.uploadBurstSpeedMbps} Mbps | $upBadge")
                        }
                    } else {
                        appendLine("🧪 Speed & 16KB Upload-Freeze Results:")
                        lines.forEachIndexed { idx, (r, rep) ->
                            appendLine("${idx + 1}. ${r.authority} -> ${rep.pingMs}ms | Down: ${rep.downloadSpeedMbps} Mbps | 16KB Upload OK: ${rep.uploadBurstHealthy}")
                        }
                    }
                }
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )

    // Action 5: 1-Tap Inject Verified Whitelist/Clean IPs into Subscriptions & Connect
    container.addView(
        makeSuperBtn(
            label = if (isFarsi) "💉 ۵) تزریق آی‌پی‌های سفید/تمیز پیدا شده به کانفیگ‌های من و اتصال فوری"
            else "💉 5) Inject Discovered Whitelist/Clean IPs into My Configs & Connect",
            filled = true,
        ) {
            val combined = (discoveredWhitelistResults + scannerResults).distinctBy { it.authority }
            if (combined.isEmpty()) {
                Toast.makeText(
                    this,
                    if (isFarsi) "⚠️ ابتدا دکمه ۱ را بزنید تا آی‌پی‌های سفید اسکن شوند."
                    else "⚠️ Run step 1 first to scan whitelist IPs.",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                injectScannedIpsIntoActiveSubscription(combined)
            }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )

    MaterialAlertDialogBuilder(this)
        .setTitle(
            if (isFarsi) "🏳️ ابر-اسکنر آی‌پی سفید، پلتفرم‌ها و نت ملی"
            else "🏳️ Whitelist, Multi-CDN & Net Melli Super-Scanner",
        )
        .setView(rootScroll)
        .setPositiveButton(android.R.string.ok, null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.runCottenMaxDualUdpTcpScannerDialog() {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    Toast.makeText(
        this,
        if (isFarsi) "🧬 در حال اسکن هم‌زمان UDP/53 و TCP/53 برای متد جدید CottenMax v1 (Soar ChaCha20-Poly1305)…"
        else "🧬 Scanning UDP/53 & TCP/53 in parallel for CottenMax v1 (Soar ChaCha20-Poly1305)…",
        Toast.LENGTH_SHORT,
    ).show()

    activityScope.launch {
        val hits = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.probeCottenMaxDualUdpTcpResolvers(timeoutMs = 850)
        }
        val toml = LocalSniSpoofProxy.buildCottenMaxClientConfigToml(
            tunnelDomain = "t.whitedns.ir",
            sharedKeyHex = "c077e4dd55aa2026c077e4dd55aa2026",
            hits = hits,
        )
        val msg = buildString {
            if (isFarsi) {
                appendLine("🧬 نتیجهٔ اسکن دوگانهٔ UDP/53 + TCP/53 برای CottenMax v1:")
                appendLine()
                if (hits.isEmpty()) {
                    appendLine("⚠️ پورت ۵۳ مستقیم محدود است؛ پیشنهاد می‌شود از حالت DoH/DoT در CottenMax استفاده شود.")
                } else {
                    hits.forEachIndexed { idx, h ->
                        val u = h.udp53RttMs?.let { "${it}ms" } ?: "بسته"
                        val t = h.tcp53RttMs?.let { "${it}ms" } ?: "بسته"
                        appendLine("${idx + 1}. ${h.resolverNameFa} (${h.resolverIp})")
                        appendLine("   👈 UDP/53: $u | TCP/53: $t | ترنسپورت: ${h.recommendedTransport.uppercase()} | MTU: ${h.observedQueryMtuBytes}/${h.observedResponseMtuBytes}B")
                    }
                }
                appendLine()
                append("📄 پیش‌نمایش پروفایل `TUNNEL_MODE = \"cottenmax\"` آمادهٔ کپی یا اعمال روی تانل DNS:")
                appendLine()
                append(toml)
            } else {
                appendLine("🧬 CottenMax v1 Dual UDP/53 + TCP/53 Scan Results:")
                hits.forEachIndexed { idx, h ->
                    appendLine("${idx + 1}. ${h.resolverIp} -> UDP: ${h.udp53RttMs ?: -1}ms, TCP: ${h.tcp53RttMs ?: -1}ms (${h.recommendedTransport})")
                }
                appendLine()
                append(toml)
            }
        }
        MaterialAlertDialogBuilder(this@runCottenMaxDualUdpTcpScannerDialog)
            .setTitle(
                if (isFarsi) "🧬 راهکار جدید CottenMax v1 (Soar v0.2.3 + UDP/TCP Auto)"
                else "🧬 New CottenMax v1 Solution (Soar v0.2.3 + UDP/TCP Auto)",
            )
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(
                if (isFarsi) "📋 کپی client_config.toml" else "📋 Copy TOML",
            ) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cottenmax_client_config.toml", toml))
                Toast.makeText(
                    this@runCottenMaxDualUdpTcpScannerDialog,
                    if (isFarsi) "✅ فایل تنظیمات CottenMax v1 در کلیپ‌بورد کپی شد"
                    else "✅ Copied CottenMax v1 config to clipboard",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setPositiveButton(
                if (isFarsi) "🚀 اعمال بهترین ریزولور و اتصال"
                else "🚀 Apply Best Resolver & Connect",
            ) { _, _ ->
                val bestIp = hits.firstOrNull()?.resolverIp ?: "178.22.122.100"
                dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoH)
                dnsPrivacyPreferenceStore.saveDohUrl("https://free.shecan.ir/dns-query")
                Toast.makeText(
                    this@runCottenMaxDualUdpTcpScannerDialog,
                    if (isFarsi) "✅ ریزولور بهینهٔ CottenMax ($bestIp) فعال شد!"
                    else "✅ Optimal CottenMax resolver ($bestIp) applied!",
                    Toast.LENGTH_SHORT,
                ).show()
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                } else {
                    beginConnectFlow(Actions.CONNECT)
                }
            }
            .create()
            .showCatClientDialog()
    }
}

/**
 * Injects verified `IP:port` endpoints from the scanner into the user's existing VLESS/Trojan
 * configs (preserving exact `IP:port` and original `host`/`sni`), or generates a ready-to-use
 * Whitelist + 5 CF Spoof bundle when no VLESS/Trojan template is stored yet.
 */
internal fun MainActivity.injectScannedIpsIntoActiveSubscription(results: List<IpScanner.ScanResult>) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    // beta205: never fall back to rows whose TLS check was attempted and FAILED — those IP:port pairs are
    // reachable but unusable on this network and only produced dead configs.
    val verified = results.filter { it.tlsOk }.sortedBy { it.pingMs }.ifEmpty {
        results.filter { !it.verifyTls }.sortedBy { it.pingMs }
    }
    if (verified.isEmpty()) {
        Toast.makeText(
            this,
            if (isFarsi) "⚠️ ابتدا یک اسکن اجرا کنید یا از ابر-اسکنر آی‌پی سفید استفاده کنید."
            else "⚠️ Run a scan first or open the Whitelist Super-Scanner.",
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val topAuthorities = verified.map { it.authority }.distinct().take(6)
    activityScope.launch {
        val allRaw = withContext(Dispatchers.IO) {
            val lines = mutableListOf<String>()
            for (sub in userSubscriptionManager.list()) {
                val snap = runCatching { userSubscriptionManager.cachedSnapshot(sub.id) }.getOrNull()
                snap?.catalog?.profiles?.forEach { p ->
                    val link = p.shareLink?.trim().orEmpty()
                    if (link.isNotEmpty()) lines += link
                }
                sub.input.lines().forEach { l ->
                    val t = l.trim()
                    if (t.startsWith("vless://", ignoreCase = true) || t.startsWith("trojan://", ignoreCase = true)) {
                        lines += t
                    }
                }
            }
            lines.distinct().joinToString("\n")
        }
        val injected = withContext(Dispatchers.IO) {
            LocalSniSpoofProxy.injectVerifiedCleanIpsIntoConfigs(
                rawConfigsText = allRaw,
                verifiedEndpoints = topAuthorities,
                maxVariantsPerConfig = 4,
            )
        }
        val finalLinks = if (injected.isNotEmpty()) {
            injected
        } else {
            // beta205: no made-up UUID / host any more — a config with a placeholder UUID can never connect.
            // Without a real VLESS/Trojan config of the user there is nothing to inject into: say so plainly.
            val tpl = resolveUserRealConfigTemplate()
            if (tpl == null) {
                Toast.makeText(
                    this@injectScannedIpsIntoActiveSubscription,
                    if (isFarsi) "⚠️ اول یک کانفیگ VLESS/Trojan واقعی (یا لینک اشتراک پنل خودتان) اضافه کنید؛ بعد این دکمه آی‌پی‌های اسکن‌شده را داخل همان کانفیگ می‌گذارد."
                    else "⚠️ Add a real VLESS/Trojan config (or your panel's subscription link) first — this button puts the scanned IPs into it.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val host = tpl.hostHeader.ifBlank { tpl.remoteHost }
            val path = tpl.path.ifBlank { "/" }
            val scheme = if (tpl.scheme.equals("trojan", ignoreCase = true)) "trojan" else "vless"
            topAuthorities.mapIndexedNotNull { idx, ep ->
                val row = verified.firstOrNull { it.authority == ep } ?: return@mapIndexedNotNull null
                val port = row.port
                // a plain Cloudflare port (80, 8080, …) speaks no TLS — a tls link there is dead on arrival
                val plain = port in setOf(80, 8080, 8880, 2052, 2082, 2086, 2095)
                val encPath = runCatching { java.net.URLEncoder.encode(path, "UTF-8") }.getOrDefault("%2F")
                val sni = tpl.sni.ifBlank { host }
                val encRem = runCatching {
                    java.net.URLEncoder.encode("⚡ CleanIP-$ep (${row.pingMs}ms)", "UTF-8")
                }.getOrDefault("CleanIP-$idx")
                val enc = if (scheme == "vless") "encryption=none&" else ""
                val sec = if (plain) "security=none" else "security=tls&sni=$sni&fp=chrome&alpn=http%2F1.1"
                "$scheme://${tpl.uuidOrPass}@$ep?${enc}$sec&type=ws&host=$host&path=$encPath#$encRem"
            }
        }
        if (finalLinks.isEmpty()) return@launch
        val subTitle = if (isFarsi) {
            "💉 کانفیگ‌های احیاشده با آی‌پی سفید/تمیز (${topAuthorities.first()})"
        } else {
            "💉 Clean-IP Injected Configs (${topAuthorities.first()})"
        }
        val joined = finalLinks.joinToString("\n")
        val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("💉") }
        val saved = withContext(Dispatchers.IO) {
            if (existing != null) userSubscriptionManager.update(existing.id, subTitle, joined)
            else userSubscriptionManager.add(subTitle, joined)
        }
        userSubscriptionManager.select(saved.id)
        val bestEp = topAuthorities.first()
        frontingIps = FrontingIpPolicy.mergeNewestFirst(topAuthorities, emptyList())
        saveFrontingIps(reconnectIfChanged = false, previousValue = frontingIpPreferenceStore.readFrontingIp())
        renderSubscriptions()
        onSubscriptionSelected()
        Toast.makeText(
            this@injectScannedIpsIntoActiveSubscription,
            if (isFarsi) "✅ ${finalLinks.size} کانفیگ با آی‌پی‌های اسکن‌شده ($bestEp) ساخته شد و در حال اتصال است!"
            else "✅ Built ${finalLinks.size} configs with scanned clean IPs ($bestEp) & connecting!",
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

// ---- moved from MainActivity.kt in beta221 (code split phase 2) ----

internal fun MainActivity.scannerInput(hint: String, initial: String): TextInputEditText =
    TextInputEditText(this).apply {
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setHint(hint)
        setText(initial)
    }

internal fun MainActivity.scannerFieldLayout(hint: String, input: TextInputEditText): TextInputLayout =
    TextInputLayout(this).apply {
        this.hint = hint
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(input)
    }

internal fun MainActivity.recommendedScannerSnis(): List<String> = buildList {
    addAll(detectPanelSnisFromSubscriptions())
    addAll(IpScanner.RECOMMENDED_SNIS)
}.map(String::lowercase).filter { it.isNotBlank() }.distinct().take(8)

internal fun MainActivity.scannerSniPreference(): String {
    val saved = getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
        .getString(SCANNER_SNI_KEY, null)
        ?.trim()
    if (!saved.isNullOrEmpty() && saved != DEFAULT_SCANNER_SNI) return saved
    return detectPanelSniFromSubscriptions() ?: saved?.takeIf { it.isNotEmpty() } ?: DEFAULT_SCANNER_SNI
}

    /**
     * Cat-compatible panels put the worker host in `servername:` / ws `Host:`; read it
     * from the selected (or first) user subscription so the scanner tests the
     * right SNI without the user typing anything.
     */
    internal fun MainActivity.detectPanelSniFromSubscriptions(): String? {
        val store = SubscriptionStore(this)
        val ids = buildList {
            add(store.readSelectedSubscriptionId())
            userSubscriptionManager.list().forEach { add(it.id) }
        }.filter { it.isNotBlank() }.distinct()
        val pattern = Regex("""(?:servername|sni|Host):\s*['"]?([a-z0-9.-]+\.[a-z]{2,})['"]?""", RegexOption.IGNORE_CASE)
        ids.forEach { id ->
            val yaml = runCatching { store.readUserSubscriptionYaml(id) }.getOrNull().orEmpty()
            pattern.findAll(yaml).map { it.groupValues[1].lowercase(Locale.US) }
                .firstOrNull { it.endsWith(".workers.dev") || it.endsWith(".pages.dev") }
                ?.let { return it }
            pattern.find(yaml)?.groupValues?.get(1)?.lowercase(Locale.US)?.let { return it }
        }
        return null
    }

    /**
     * Every distinct SNI baked into the user's subscriptions — the panel writes
     * its operator-verified winners (verifiedByOp) into each config, so this is
     * the operator-verified SNI pool surfaced in scanner suggestions + health
     * sweeps. Panel-host names (workers.dev/pages.dev) sort first.
     */
    internal fun MainActivity.detectPanelSnisFromSubscriptions(): List<String> {
        val store = SubscriptionStore(this)
        val ids = buildList {
            add(store.readSelectedSubscriptionId())
            userSubscriptionManager.list().forEach { add(it.id) }
        }.filter { it.isNotBlank() }.distinct()
        val pattern = Regex("""(?:servername|sni|Host):\s*['"]?([a-z0-9.-]+\.[a-z]{2,})['"]?""", RegexOption.IGNORE_CASE)
        val out = LinkedHashSet<String>()
        ids.forEach { id ->
            val yaml = runCatching { store.readUserSubscriptionYaml(id) }.getOrNull().orEmpty()
            pattern.findAll(yaml).forEach { out.add(it.groupValues[1].lowercase(Locale.US)) }
        }
        return out
            .sortedByDescending { it.endsWith(".workers.dev") || it.endsWith(".pages.dev") }
            .toList()
    }

    internal fun MainActivity.detectPanelIdentity(sni: String): PanelIdentity? {
        val store = SubscriptionStore(this)
        val ids = (listOf(store.readSelectedSubscriptionId()) + userSubscriptionManager.list().map { it.id })
            .filter { it.isNotBlank() }.distinct()
        val uuidRe = Regex("""uuid:\s*['"]?([0-9a-fA-F-]{36})""")
        val pathRe = Regex("""path:\s*['"]?([^'"\n]+)""")
        val trojanRe = Regex("""type:\s*trojan[\s\S]{0,400}?path:\s*['"]?([^'"\n]+)""")
        ids.forEach { id ->
            val yaml = runCatching { store.readUserSubscriptionYaml(id) }.getOrNull().orEmpty()
            if (!yaml.contains(sni, ignoreCase = true)) return@forEach
            val uuid = uuidRe.find(yaml)?.groupValues?.get(1) ?: return@forEach
            val path = pathRe.find(yaml)?.groupValues?.get(1)?.trim() ?: "/ws?ed=2048"
            val trojanPath = trojanRe.find(yaml)?.groupValues?.get(1)?.trim() ?: path
            val trojanPassword = Regex(
                """type:\s*trojan[\s\S]{0,500}?password:\s*['\"]?([^'\"\n]+)""",
            ).find(yaml)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { uuid }
            return PanelIdentity(sni, uuid, path, trojanPath, trojanPassword)
        }
        return null
    }

/**
 * Cat behaviour: take the scanned IPs and build real configs (address = clean IP,
 * SNI/Host = panel) as a new subscription the core can pick from — no fronting
 * layer involved, so the app connects to those IPs directly.
 */
internal fun MainActivity.buildSubscriptionFromScan(results: List<IpScanner.ScanResult>) {
    val sni = scannerSniInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_SCANNER_SNI }
    val identity = detectPanelIdentity(sni)
    if (identity == null) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanner_build_title)
            .setMessage(getString(R.string.scanner_build_no_panel, sni))
            .setPositiveButton(R.string.scanner_build_apply_fronting) { _, _ -> applyScannerResult(results.firstOrNull()) }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
            .showCatClientDialog()
        return
    }
    val verified = results.filter { it.tlsOk }
    if (verified.isEmpty()) {
        Toast.makeText(this, R.string.scanner_no_verified, Toast.LENGTH_LONG).show()
        return
    }
    val top = verified.sortedBy { it.pingMs }.take(SCANNER_BUILD_LIMIT)
    // Cat layout: plain-HTTP :80 first (no SNI on the wire), then TLS 443/2053.
    val tlsParams = "security=tls&sni=" + Uri.encode(identity.host) + "&fp=chrome&alpn=" + Uri.encode("http/1.1")
    val links = buildList {
        SCANNER_BUILD_PORTS.forEach { port ->
            top.forEach { r ->
                val tls = port != 80
                val location = r.countryName?.takeIf { it.isNotBlank() }
                    ?: r.colo?.takeIf { it.isNotBlank() }
                    ?: "Cloudflare edge"
                val transport = if (tls) tlsParams else "security=none"
                // IPv6 hosts must be bracketed in URI authority (bare :: breaks the URL)
                val hostLit = if (r.ip.contains(':')) "[$r.ip]" else r.ip
                val vlessLabel = "🐱 Cat · " + location + " · VLESS · " + port + " · " + r.flag
                val vlessCommon = "&type=ws&path=" + Uri.encode(identity.vlessPath) + "&host=" + Uri.encode(identity.host)
                add(
                    "vless://" + identity.uuid + "@" + hostLit + ":" + port + "?encryption=none&" +
                        transport + vlessCommon + "#" + Uri.encode(vlessLabel),
                )
                val trojanLabel = "🐱 Cat · " + location + " · Trojan · " + port + " · " + r.flag
                val trojanCommon = "&type=ws&path=" + Uri.encode(identity.trojanPath) + "&host=" + Uri.encode(identity.host)
                add(
                    "trojan://" + Uri.encode(identity.trojanPassword) + "@" + hostLit + ":" + port + "?" +
                        transport + trojanCommon + "#" + Uri.encode(trojanLabel),
                )
            }
        }
    }
    val name = getString(R.string.scanner_build_sub_name, identity.host.substringBefore('.'))
    activityScope.launch {
        val added = runCatching {
            withContext(Dispatchers.IO) { userSubscriptionManager.add(name, links.joinToString("\n")) }
        }.getOrNull()
        if (added == null) {
            Toast.makeText(this@buildSubscriptionFromScan, R.string.free_quick_add_failed, Toast.LENGTH_LONG).show()
            return@launch
        }
        userSubscriptionManager.select(added.id)
        renderSubscriptions()
        onSubscriptionSelected()
        Toast.makeText(this@buildSubscriptionFromScan, getString(R.string.scanner_build_done, links.size), Toast.LENGTH_LONG).show()
        beginConnectFlow(Actions.CONNECT)
    }
}

internal fun MainActivity.saveScannerSni(value: String) {
    getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(SCANNER_SNI_KEY, value)
        .apply()
}

internal fun MainActivity.scannerPortPreference(): Int {
    val saved = getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(SCANNER_PORT_KEY, 443)
    return saved.takeIf { it in 1..65535 } ?: 443
}

internal fun MainActivity.saveScannerPort(value: Int) {
    getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putInt(SCANNER_PORT_KEY, value)
        .apply()
}

/**
 * The four scan sources the owner can switch on/off — defaults kept exactly as
 * the historical behaviour (ranges + Cloudflare library + Iran/community
 * library on, v6 gated by the connectivity probe). Persisted per source so a
 * scan is reproducible after a restart.
 */
internal fun MainActivity.scannerSourcePreference(key: String, fallback: Boolean): Boolean =
    getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean("source_$key", fallback)

internal fun MainActivity.saveScannerSource(key: String, value: Boolean) {
    getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("source_$key", value).apply()
}

/**
 * One switch, one meaning: the v6 source is either walked or it is not.
 *
 * The chip that used to exist only toggled `scannerForceV6` (skip the probe),
 * so a network with working v6 and the chip OFF still scanned v4-only — the
 * owner's «ای‌پی‌های v6 نیست» had two causes and the UI owned neither: the chip
 * was below the buttons, unlabelled as to state, and the probe verdict vanished
 * once the run started. Now the same switch answers all three.
 */
internal fun MainActivity.scannerSourceEnabled(key: String): Boolean = when (key) {
    "ipv6" -> scannerForceV6 || scannerSourcePreference("ipv6", true)
    "mine" -> scannerSourcePreference("mine", true)
    "cloudflare" -> scannerSourcePreference("cloudflare", true)
    "iran" -> scannerSourcePreference("iran", true)
    else -> true
}

internal fun MainActivity.scannerRangesPreference(): String {
    val saved = getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE).getString(SCANNER_RANGES_KEY, null)
    return saved ?: IpScanner.defaultRangesText()
}

internal fun MainActivity.saveScannerRanges(value: String) {
    getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(SCANNER_RANGES_KEY, value)
        .apply()
}

internal fun MainActivity.stopIpScanner() {
    scannerJob?.cancel()
    scannerJob = null
    scannerRunning = false
    scannerStartButton.isEnabled = true
    scannerStopButton.isEnabled = false
    scannerProgressBar.visibility = View.GONE
    scannerStatusText.setText(R.string.scanner_stopped)
}

/** XIU2-style download throughput for the top verified rows; sorts by speed after. */
internal fun MainActivity.runScannerSpeedTest() {
    if (scannerSpeedTestRunning) return
    val targets = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
        .filter { it.tlsOk }
        .distinctBy { it.ip to it.port }
        .take(8)
    if (targets.isEmpty()) {
        Toast.makeText(this, R.string.scanner_no_results, Toast.LENGTH_SHORT).show()
        return
    }
    scannerSpeedTestRunning = true
    scannerSpeedTestButton?.isEnabled = false
    activityScope.launch {
        val measured = LinkedHashMap<String, Long>()
        for ((index, target) in targets.withIndex()) {
            mainHandler.post {
                scannerStatusText.text = getString(R.string.scanner_speed_running, index + 1, targets.size)
            }
            val speed = withContext(Dispatchers.IO) {
                runCatching { IpScanner.measureDownloadSpeed(target.ip, target.port) }.getOrNull()
            }
            // Upload runs after the download probe on the same target: it spends
            // the user's own mobile data, so it is capped (see MAX_UPLOAD_BYTES)
            // and never runs on its own — only when a speed test was asked for.
            val up = withContext(Dispatchers.IO) {
                runCatching { IpScanner.measureUploadSpeed(target.ip, target.port) }.getOrNull()
            }
            if (speed != null) measured["${target.ip}:${target.port}"] = speed
            mainHandler.post {
                val updated = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
                    .map {
                        if (it.ip == target.ip && it.port == target.port) it.copy(speedBps = speed ?: it.speedBps, uploadBps = up ?: it.uploadBps)
                        else it
                    }
                scannerResults = updated
                renderScannerResults()
            }
        }
        mainHandler.post {
            scannerResults = scannerResults.sortedWith(
                compareBy(
                    { if (it.tlsOk) 0 else 1 },
                    { it.speedBps?.let { s -> -s } ?: Long.MAX_VALUE },
                    { it.pingMs },
                ),
            )
            renderScannerResults()
            scannerSpeedTestRunning = false
            scannerSpeedTestButton?.isEnabled = scannerResults.any { it.tlsOk }
            val best = measured.maxByOrNull { it.value }
            if (best != null) {
                val mbps = getString(R.string.scanner_speed_mbps, best.value / 1_000_000.0)
                scannerStatusText.text = getString(R.string.scanner_speed_done, best.key, mbps)
                Toast.makeText(this@runScannerSpeedTest, getString(R.string.scanner_speed_done, best.key, mbps), Toast.LENGTH_LONG).show()
            } else {
                scannerStatusText.setText(R.string.scanner_no_results)
            }
        }
    }

}

/** Pool grouped by location: one chip per country (tap = lock failover/replacements to it), IPs listed under the lock. */
internal fun MainActivity.renderIpHealthCountries(entries: List<IpHealthEntry>) {
    val row = ipHealthCountryRow ?: return
    row.removeAllViews()
    if (entries.isEmpty()) return
    val store = IpHealthStore(this)
    val active = frontingIps.firstOrNull()
    val groups = entries.groupBy { it.countryCode?.uppercase().orEmpty() }
        .entries.sortedWith(compareByDescending<Map.Entry<String, List<IpHealthEntry>>> { it.value.size }.thenBy { it.key })
    val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
    fun chip(label: String, selected: Boolean, onClick: () -> Unit) = Chip(this@renderIpHealthCountries).apply {
        text = label
        isCheckable = true
        isChecked = selected
        textSize = 11.5f
        setTextColor(TEXT_PRIMARY)
        chipStrokeColor = ColorStateList.valueOf(withAlpha(if (selected) TEAL else OUTLINE, 170))
        chipStrokeWidth = dp(1).toFloat()
        chipBackgroundColor = ColorStateList.valueOf(if (selected) withAlpha(TEAL, 40) else withAlpha(SURFACE, if (palette.isDark) 210 else 245))
        setOnClickListener { onClick() }
    }
    chips.addView(chip(getString(R.string.ip_health_country_auto), store.preferredCountry.isBlank()) {
        store.preferredCountry = ""
        renderScannerIpHealth()
    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    groups.forEach { (cc, list) ->
        val flag = if (cc.length == 2) cc.toFlagEmoji() else "🌐"
        val name = list.firstNotNullOfOrNull { it.countryName } ?: cc.ifBlank { "?" }
        val hasActive = active != null && list.any { it.ip == active }
        chips.addView(chip("$flag $name (${list.size})" + if (hasActive) " ●" else "", store.preferredCountry.equals(cc, true) && cc.isNotBlank()) {
            store.preferredCountry = if (store.preferredCountry.equals(cc, true)) "" else cc
            renderScannerIpHealth()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    }
    row.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2))
    val shown = groups.firstOrNull { it.key.equals(store.preferredCountry, true) && it.key.isNotBlank() }
        ?: groups.firstOrNull { g -> active != null && g.value.any { it.ip == active } }
        ?: groups.firstOrNull()
    if (shown != null && shown.key.isNotBlank()) {
        row.addView(MaterialButton(this).apply {
            text = getString(R.string.multi_location_use, shown.key.toFlagEmoji(), shown.value.firstNotNullOfOrNull { it.countryName } ?: shown.key)
            setAllCaps(false); textSize = 11.5f; minWidth = 0; minimumWidth = 0; minHeight = dp(34); minimumHeight = dp(34)
            insetTop = 0; insetBottom = 0; cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 34)); strokeWidth = dp(1); strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 130)); setTextColor(TEAL)
            isEnabled = !staticIpEnabled()
            setOnClickListener { useCountryIps(shown.key) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
    }
    if (shown != null) {
        row.addView(TextView(this).apply {
            textSize = 11.5f
            typeface = CatClientDataTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setLineSpacing(dp(2).toFloat(), 1f)
            text = shown.value.sortedBy { it.pingMs.takeIf { p -> p > 0 } ?: Long.MAX_VALUE }.joinToString("\n") { e ->
                (if (e.authority == active || e.ip == active) "● " else "   ") + e.authority + (e.colo?.let { "  $it" } ?: "") + (if (e.pingMs > 0) "  ${e.pingMs} ms" else "") + (if (e.fails > 0) "  ✗${e.fails}" else "")
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
}

internal fun MainActivity.startIpHealthLoop() {
    ipHealthJob?.cancel()
    ipHealthJob = null
    // Static-IP pin: nothing is ever swapped automatically.
    if (!IpHealthStore(this).autoEnabled || IpHealthStore(this).pinned) return
    ipHealthJob = activityScope.launch {
        while (true) {
            runIpHealthSweep(silent = true)
            delay(IpHealthStore(this@startIpHealthLoop).intervalMinutes * 60_000L)
        }
    }
}

/**
 * Self-healing control plane: DETECT (anomaly on the active fronting address from its
 * own history) → SELECT (best-ranked standby) → WARM/VERIFY (two fresh probes) →
 * PROMOTE (move to the front of the fronting list, reconnect) → LEARN (stats).
 */
internal suspend fun MainActivity.runCognitiveFailover(pool: List<IpHealthEntry>, genomes: NetworkGenomeStore, network: String, sni: String, port: Int) {
    if (!genomes.autoFailover || IpHealthStore(this).pinned) return
    if (!currentVpnStateIsStarted()) return
    val active = frontingIps.firstOrNull() ?: return
    val plan = withContext(Dispatchers.IO) {
        val options = IpScanner.ScanOptions(sni = sni, port = port, includeBuiltin = false, includeIranLibrary = false, verifyHttp = true)
        CognitiveEngine.planMigration(active, pool, genomes.genomes(pool.map { it.ip }), network, country = IpHealthStore(this@runCognitiveFailover).preferredCountry) { candidate ->
            val r = IpScanner.probe(candidate.ip, options.copy(sni = candidate.sni.ifBlank { sni }, port = candidate.port))
            val ok = r != null && r.tlsOk && r.pingMs <= IpHealthMonitor.SLOW_MS
            genomes.record(candidate.ip, Observation(System.currentTimeMillis(), network, r?.pingMs ?: -1L, r?.tlsMs ?: -1L, ok, if (ok) FailureClass.OK else CognitiveEngine.classify(r, IpHealthMonitor.SLOW_MS, genomes.genome(candidate.ip))))
            ok
        }
    } ?: return
    val started = System.currentTimeMillis()
    val previousValue = frontingIpPreferenceStore.readFrontingIp()
    frontingIps = FrontingIpPolicy.normalizeIps(
        (listOf(plan.to.authority) + frontingIps.filter { it != plan.to.authority && it != plan.to.ip }).take(5).joinToString(","),
    )
    renderFrontingIpChips()
    if (!saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) return
    genomes.updateStats {
        it.copy(
            failovers = it.failovers + 1,
            predictedDegradations = it.predictedDegradations + if (plan.predicted) 1 else 0,
            predictionsConfirmed = it.predictionsConfirmed + if (plan.predicted) 1 else 0,
            lastRecoveryMs = System.currentTimeMillis() - started,
            lastFailoverAt = started,
            lastFailoverFrom = plan.from,
            lastFailoverTo = plan.to.authority,
            lastFailoverReason = plan.reason,
        )
    }
    renderScannerIpHealth()
    Toast.makeText(this, getString(if (plan.countryChanged) R.string.engine_failover_country_toast else R.string.engine_failover_toast, plan.from, plan.to.authority, plan.reason), Toast.LENGTH_LONG).show()
}

internal fun MainActivity.runIpHealthSweep(silent: Boolean = false, failFast: Boolean = false) {
    if (ipHealthSweeping) return
    ipHealthSweeping = true
    val store = IpHealthStore(this)
    val sni = scannerSniPreference().ifBlank { IpScanner.RECOMMENDED_SNIS.first() }
    val port = scannerPortPreference()
    if (!silent) Toast.makeText(this, R.string.ip_health_sweeping, Toast.LENGTH_SHORT).show()
    activityScope.launch {
        try {
            val genomeStore = NetworkGenomeStore(this@runIpHealthSweep)
            val network = NetworkContext.key(this@runIpHealthSweep)
            val result = IpHealthMonitor.sweep(
                store,
                sni,
                port,
                failFast = failFast,
                extraSnis = detectPanelSnisFromSubscriptions(),
                genomes = genomeStore,
                network = network,
                baseIntervalMinutes = store.intervalMinutes,
            )
            renderScannerIpHealth()
            runCognitiveFailover(result.kept, genomeStore, network, sni, port)
            if (!silent) {
                if (result.removed.isEmpty() && result.added.isEmpty()) {
                    Toast.makeText(this@runIpHealthSweep, R.string.ip_health_all_ok, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@runIpHealthSweep,
                        getString(R.string.ip_health_rotated, result.removed.size, result.added.size),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } catch (e: Exception) {
            if (!silent) {
                Toast.makeText(
                    this@runIpHealthSweep,
                    getString(R.string.ip_health_failed, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        } finally {
            ipHealthSweeping = false
        }
    }
}

/** 👑 Neighbor scan: best verified hits → walk ±16 addresses around each in
 * the same /24 (v4) or /64 (v6), verified with the SAME SNI/port; results
 * join the live list, carry source=neighbor when pushed to the panel. */
internal fun MainActivity.runNeighborScan() {
    if (scannerRunning || scannerNeighborRunning) return
    val seeds = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
        .filter { it.tlsOk }
        .distinctBy { it.ip to it.port }
        .take(3)
    if (seeds.isEmpty()) {
        Toast.makeText(this, R.string.scanner_no_results, Toast.LENGTH_SHORT).show()
        return
    }
    val sni = scannerSniInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_SCANNER_SNI }
    val candidates = LinkedHashSet<String>()
    seeds.forEach { seed ->
        val ip = seed.ip
        if (IpScanner.isIpv6(ip)) {
            // Scanner v6 seeds come from InetAddress → full 8-group form; the
            // /64 prefix is the first four groups. Compressed forms keep
            // everything before «::». Neighbours: ::0 … ::f in that prefix.
            val head = if (ip.contains("::")) ip.substringBefore("::") + "::"
            else ip.split(":").take(4).joinToString(":") + "::"
            (0..15).forEach { k -> candidates += head + Integer.toHexString(k) }
        } else {
            val parts = ip.split(".")
            val p3 = parts.take(3).joinToString(".")
            val last = parts[3].toIntOrNull() ?: 0
            (-16..16).forEach { k ->
                val o = last + k
                if (k != 0 && o in 0..255) candidates += "$p3.$o"
            }
        }
    }
    val clean = candidates.filter { IpScanner.isValidIpv4(it) || IpScanner.isValidIpv6(it) }.toList()
    if (clean.isEmpty()) return
    scannerNeighborRunning = true
    scannerStatusText.setText(R.string.scanner_neighbor_running)
    val collected = java.util.concurrent.CopyOnWriteArrayList<IpScanner.ScanResult>()
    scannerJob = activityScope.launch {
        val found = runCatching {
            IpScanner.scan(this@runNeighborScan, IpScanner.ScanOptions(
                sni = sni,
                port = scannerPort,
                customSubnets = clean.joinToString(","),
                includeBuiltin = false,
                includeIranLibrary = false,
                // v6 neighbors must survive the candidate build (the default
                // drops v6 literals when includeIpv6 is off — seeds carry v6
                // only on dual-stack networks, so always-on here is safe).
                includeIpv6 = true,
                perRange = 1,
                randomSample = false,
                concurrency = SCANNER_CONCURRENCY,
                connectTimeoutMs = SCANNER_CONNECT_TIMEOUT_MS,
                tlsTimeoutMs = SCANNER_TLS_TIMEOUT_MS,
                verifyHttp = true,
            )) { done, total, result ->
                // Called from an IO thread: collect first, render on main.
                if (result != null && result.tlsOk && collected.none { it.ip == result.ip }) collected += result
                mainHandler.post {
                    val percent = (done * 100) / total.coerceAtLeast(1)
                    scannerProgressBar.progress = percent
                    scannerStatusText.text = getString(R.string.scanner_progress_empty, done, total, percent)
                }
            }
        }.getOrDefault(emptyList())
        val added = found.filter { it.tlsOk }.distinctBy { it.ip to it.port }.filter { base -> scannerResults.none { it.ip == base.ip && it.port == base.port } }
        scannerNeighborIps.addAll(added.map { it.ip })
        scannerResults = (scannerResults + added).sortedWith(compareBy({ if (it.tlsOk) 0 else 1 }, { it.pingMs }))
        scannerFilter = "all"
        scannerNeighborRunning = false
        renderScannerResults()
        scannerStatusText.text = getString(R.string.scanner_neighbor_done, added.size, clean.size)
        Toast.makeText(this@runNeighborScan, getString(R.string.scanner_neighbor_done, added.size, clean.size), Toast.LENGTH_LONG).show()
    }
}

/** ⚡ Scan profile chips: fast / standard / deep — depth of the same engine. */
internal fun MainActivity.scannerProfileRow(): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    row.addView(
        TextView(this).apply {
            setText(R.string.scanner_profile_label)
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
    )
    fun chip(labelText: String, key: String): android.widget.TextView =
        TextView(this).apply {
            text = labelText
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = glassSurfaceDrawable(radiusDp = 14)
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setOnClickListener {
                scannerProfile = key
                getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE).edit().putString("profile", key).apply()
                refreshScannerProfileRow(row)
            }
        }
    row.addView(chip(getString(R.string.scanner_profile_fast), "fast"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    row.addView(chip(getString(R.string.scanner_profile_std), "std"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    row.addView(chip(getString(R.string.scanner_profile_deep), "deep"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
    row.addView(chip(getString(R.string.scanner_profile_gentle), "gentle"), LinearLayout.LayoutParams(-2, -2))
    refreshScannerProfileRow(row)
    return row
}

internal fun MainActivity.refreshScannerProfileRow(row: LinearLayout) {
    val keys = listOf("fast", "std", "deep", "gentle")
    for (i in 1 until row.childCount) {
        val chip = row.getChildAt(i) as? android.widget.TextView ?: continue
        val key = keys.getOrNull(i - 1) ?: continue
        val active = scannerProfile == key
        chip.setTextColor(if (active) palette.onAccent else TEXT_SECONDARY)
        chip.background = glassSurfaceDrawable(radiusDp = 14, highlighted = active)
    }
}

/** 📋 Port-pinned list grouped by address family. Dropping ports here used to
 * turn multiple verified rows into ambiguous bare IPs on paste/import. */
internal fun MainActivity.scannerPlainList(): String {
    val verified = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
        .filter { it.tlsOk }
        .distinctBy { it.ip to it.port }
    val v4 = verified.filter { !it.ip.contains(':') }.map { it.authority }
    val v6 = verified.filter { it.ip.contains(':') }.map { it.authority }
    val sb = StringBuilder()
    if (v4.isNotEmpty()) sb.append("IPv4:\n\n").append(v4.joinToString("\n")).append("\n")
    if (v6.isNotEmpty()) {
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append("IPv6:\n\n").append(v6.joinToString("\n")).append("\n")
    }
    return sb.toString()
}

internal fun MainActivity.copyScannerPlainList() {
    val text = scannerPlainList()
    if (text.isBlank()) {
        Toast.makeText(this, R.string.scanner_share_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cb.setPrimaryClip(android.content.ClipData.newPlainText("cat-scanner-list", text))
    Toast.makeText(this, R.string.scanner_list_copied, Toast.LENGTH_SHORT).show()
}

/** 🌍 Probe selected HTTPS endpoints from the current app network. */
internal fun MainActivity.runScannerForeignTest() {
    if (scannerForeignRunning) return
    scannerForeignRunning = true
    scannerForeignResults.clear()
    scannerForeignShareButton.visibility = View.GONE
    scannerForeignList.removeAllViews()
    val status = TextView(this).apply {
        setText(R.string.scanner_foreign_running)
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    scannerForeignList.addView(status)

    activityScope.launch {
        val gate = Semaphore(3)
        try {
            val results = ForeignServiceCatalog.targets.map { target ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val result = probeForeignService(target)
                        mainHandler.post {
                            if (isDestroyed) return@post
                            if (status.parent === scannerForeignList) scannerForeignList.removeView(status)
                            scannerForeignList.addView(
                                scannerForeignResultRow(result),
                                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                            )
                        }
                        result
                    }
                }
            }.awaitAll()
            if (!isDestroyed) {
                results.forEach { result -> scannerForeignResults[result.target.id] = result }
                scannerForeignShareButton.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
            }
        } finally {
            scannerForeignRunning = false
        }
    }
}

/** Performs a tiny range GET and always closes the connection without reading page content. */
internal fun MainActivity.probeForeignService(target: ForeignServiceTarget): ForeignServiceProbeResult {
    val startedAt = SystemClock.elapsedRealtime()
    var httpStatus = 0
    var failure: ForeignServiceFailure? = null
    try {
        val connection = (URL(target.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 4_500
            readTimeout = 4_500
            useCaches = false
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            )
            setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Range", "bytes=0-0")
        }
        try {
            httpStatus = connection.responseCode
        } finally {
            connection.disconnect()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failure = classifyForeignServiceFailure(error)
    }
    val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L).toInt()
    return ForeignServiceProbeResult(target, httpStatus, elapsed, failure)
}

internal fun MainActivity.foreignServiceVerdict(result: ForeignServiceProbeResult): Pair<Int, Int> {
    if (result.httpStatus == 0) {
        return when (result.failure) {
            ForeignServiceFailure.DNS -> R.string.scanner_foreign_dns to AMBER
            ForeignServiceFailure.TIMEOUT -> R.string.scanner_foreign_timeout to AMBER
            ForeignServiceFailure.TLS -> R.string.scanner_foreign_tls to AMBER
            ForeignServiceFailure.NETWORK, null -> R.string.scanner_foreign_network to ERROR
        }
    }
    return when (result.status) {
        ForeignServiceStatus.OPEN -> R.string.scanner_foreign_open to TEAL
        ForeignServiceStatus.HTTP_RESPONSE -> R.string.scanner_foreign_response to AMBER
        ForeignServiceStatus.RATE_LIMITED -> R.string.scanner_foreign_rate to AMBER
        ForeignServiceStatus.SERVER_ERROR -> R.string.scanner_foreign_server_error to AMBER
        ForeignServiceStatus.UNREACHABLE -> R.string.scanner_foreign_network to ERROR
    }
}

internal fun MainActivity.scannerForeignResultRow(result: ForeignServiceProbeResult): View {
    val (verdictRes, color) = foreignServiceVerdict(result)
    val codeLabel = if (result.httpStatus > 0) result.httpStatus.toString() else "—"
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = glassSurfaceDrawable(radiusDp = 12)
        addView(TextView(this@scannerForeignResultRow).apply {
            text = result.target.label
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@scannerForeignResultRow).apply {
            text = getString(R.string.scanner_foreign_row, codeLabel, result.latencyMs)
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            setTextColor(color)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(TextView(this@scannerForeignResultRow).apply {
            setText(verdictRes)
            textSize = 11f
            typeface = CatClientBodyBoldTypeface
            setTextColor(color)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(withAlpha(color, 26))
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
        })
    }
}

/** Shares only service, HTTP result, timing and a coarse failure category; never config or account data. */
internal fun MainActivity.shareScannerForeignReport() {
    val results = ForeignServiceCatalog.targets.mapNotNull { scannerForeignResults[it.id] }
    if (results.isEmpty()) return
    val rows = results.joinToString("\n") { result ->
        val (verdictRes, _) = foreignServiceVerdict(result)
        val codeLabel = if (result.httpStatus > 0) result.httpStatus.toString() else "—"
        getString(
            R.string.scanner_foreign_report_row,
            result.target.label,
            codeLabel,
            getString(verdictRes),
            result.latencyMs,
        )
    }
    val report = buildString {
        appendLine(getString(R.string.scanner_foreign_report_title))
        appendLine(getString(R.string.scanner_foreign_report_context))
        appendLine()
        append(rows)
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, report)
    }
    startActivity(Intent.createChooser(sendIntent, getString(R.string.scanner_foreign_share)))
}

/** ⤴ Shares the verified hits (ip:port#CC lines) with any app. */
internal fun MainActivity.shareScannerResults() {
    val verified = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
        .filter { it.tlsOk }
        .distinctBy { it.ip to it.port }
        .take(60)
    if (verified.isEmpty()) {
        Toast.makeText(this, R.string.scanner_share_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val text = verified.joinToString("\n") { it.panelLine }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, getString(R.string.scanner_share_btn)))
}

/** 🕘 One scanner run → prefs history (timestamp:best:verified:total). */
internal fun MainActivity.saveScanHistoryRun(bestMs: Int?, verified: Int, total: Int) {
    val entry = "${System.currentTimeMillis()}:${bestMs ?: -1}:$verified:$total"
    val rest = scanHistoryRaw().split("|").filter { it.isNotBlank() }.take(11)
    getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString("history", (listOf(entry) + rest).joinToString("|"))
        .apply()
}

internal fun MainActivity.scanHistoryRaw(): String =
    getSharedPreferences(SCANNER_PREFERENCES, Context.MODE_PRIVATE).getString("history", "") ?: ""

internal fun MainActivity.renderScanHistory() {
    val view = scannerHistoryText ?: return
    // ⚠️ The scanner screen is built AT STARTUP (createAndAttachViews) — a
    // throw here kills the whole app. A run with zero TLS-verified IPs
    // stores best = -1; formatting «%2$d» with a String crashed every cold
    // start (IllegalFormatConversionException). Proper branch string now,
    // and the whole render is guarded so history can never crash again.
    runCatching {
        val rows = scanHistoryRaw().split("|").filter { it.isNotBlank() }
        if (rows.isEmpty()) {
            view.setText(R.string.scanner_history_never)
            return
        }
        val parts = rows.first().split(":")
        val at = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val best = parts.getOrNull(1)?.toIntOrNull() ?: -1
        val ok = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val bestEver = rows.mapNotNull { it.split(":").getOrNull(1)?.toIntOrNull() }.filter { it > 0 }.minOrNull()
        val ago = android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
        view.text = if (best > 0) {
            val line = getString(R.string.scanner_history_last, ago, best, ok)
            if (bestEver != null && bestEver > 0) "$line · ${getString(R.string.scanner_history_record, bestEver)}" else line
        } else {
            getString(R.string.scanner_history_last_nobest, ago, ok)
        }
    }.onFailure { view.setText(R.string.scanner_history_never) }
}

/** 🌐 Country distribution of the visible results (line under the filters). */
internal fun MainActivity.refreshScannerCountrySummary(list: List<IpScanner.ScanResult>) {
    val view = scannerCountryText ?: return
    val groups = list.filter { !it.countryCode.isNullOrBlank() }
        .groupingBy { it.countryCode!! }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(6)
    // The 🌐 bucket is counted out loud: the flags are the location of the edge
    // that ANSWERED the trace, so a row whose TLS/trace never completed has no
    // country at all — showing only the resolved ones made «۶۸ ایران» look like
    // a filter (the owner asked why the scanner is «mostly Iran»).
    val unknown = list.count { it.countryCode.isNullOrBlank() }
    val head = if (unknown > 0) "🌐 $unknown" else ""
    val body = groups.joinToString("   ") { "${it.key.toFlagEmoji()} ${it.value}" }
    view.text = listOf(head, body).filter { it.isNotBlank() }.joinToString("   ")
    view.visibility = if (groups.isEmpty() && unknown == 0) View.GONE else View.VISIBLE
}

/** Rows rendered in the results list right now (see [scannerVisibleCap]). */
internal fun MainActivity.scannerVisibleCap(): Int = if (lowMemoryRows) 12 else SCANNER_VISIBLE_RESULTS

internal fun MainActivity.scannerFilterVisible(list: List<IpScanner.ScanResult>): List<IpScanner.ScanResult> =
    when (scannerFilter) {
        "v4" -> list.filter { !IpScanner.isIpv6(it.ip) }
        "v6" -> list.filter { IpScanner.isIpv6(it.ip) }
        else -> list
    }

internal fun MainActivity.scannerFilterRow(): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    fun chip(label: String, key: String): android.widget.TextView =
        TextView(this).apply {
            text = label
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = glassSurfaceDrawable(radiusDp = 16)
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setOnClickListener {
                scannerFilter = key
                refreshScannerFilters()
                renderScannerResults()
            }
        }
    scannerFilterAll = chip(getString(R.string.scanner_filter_all), "all")
    scannerFilterV4 = chip(getString(R.string.scanner_filter_v4), "v4")
    scannerFilterV6 = chip(getString(R.string.scanner_filter_v6), "v6")
    row.addView(scannerFilterAll, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    row.addView(scannerFilterV4, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    row.addView(scannerFilterV6, LinearLayout.LayoutParams(-2, -2))
    return row
}

internal fun MainActivity.refreshScannerFilters() {
    val list = if (scannerRunning) scannerLiveResults.toList() else scannerResults
    val v6Count = list.count { IpScanner.isIpv6(it.ip) }
    val v4Count = list.size - v6Count
    fun style(v: android.widget.TextView?, active: Boolean, enabled: Boolean) {
        v ?: return
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.45f
        v.setTextColor(if (active) palette.onAccent else TEXT_SECONDARY)
        v.background = glassSurfaceDrawable(radiusDp = 16, highlighted = active)
    }
    style(scannerFilterAll, scannerFilter == "all", list.isNotEmpty())
    style(scannerFilterV4, scannerFilter == "v4", v4Count > 0)
    style(scannerFilterV6, scannerFilter == "v6", v6Count > 0)
    scannerFilterV4?.text = getString(R.string.scanner_filter_v4) + " " + v4Count
    scannerFilterV6?.text = getString(R.string.scanner_filter_v6) + " " + v6Count
}

/** `ip:port` — the stable identity of a row across re-sorts and re-renders. */
internal fun MainActivity.scannerRowKey(result: IpScanner.ScanResult): String = "${result.ip}:${result.port}"

/** The list the table renders from: live rows while a run is in flight, final rows after it. */
internal fun MainActivity.scannerSourceList(): List<IpScanner.ScanResult> =
    if (scannerRunning) scannerLiveResults.toList() else scannerResults

/** Applies the column the owner sorted on; rows with no value for it sink to the bottom. */
internal fun MainActivity.scannerSortedRows(list: List<IpScanner.ScanResult>): List<IpScanner.ScanResult> {
    val cmp: Comparator<IpScanner.ScanResult> = when (scannerSortKey) {
        // ☁️ worker: measured rows first (fastest worker answer), unmeasured/failed sink.
        "worker" -> compareBy<IpScanner.ScanResult> { it.workerMs ?: Long.MAX_VALUE }
        "jitter" -> compareBy<IpScanner.ScanResult> { it.workerJitterMs ?: it.jitterMs }
        "speed" -> compareByDescending<IpScanner.ScanResult> { it.speedBps ?: -1L }
        "country" -> compareBy<IpScanner.ScanResult>({ it.countryCode ?: "zz" }, { it.pingMs })
        "ip" -> compareBy<IpScanner.ScanResult> { it.ip }
        // 🏠 line: the measured median when there is one, the scan ping otherwise.
        else -> compareBy<IpScanner.ScanResult> { it.lineMs ?: it.pingMs }
    }
    val ordered = list.sortedWith(cmp.thenBy { it.pingMs }.thenBy { it.ip })
    return if (scannerSortAsc) ordered else ordered.reversed()
}

/** Ticked rows, in the order they are currently displayed (copy/send keep that order). */
internal fun MainActivity.scannerSelectedResults(): List<IpScanner.ScanResult> {
    if (scannerSelected.isEmpty()) return emptyList()
    val visible = scannerSortedRows(scannerFilterVisible(scannerSourceList()))
    return visible.filter { scannerSelected.contains(scannerRowKey(it)) }
}

internal fun MainActivity.toggleScannerRow(result: IpScanner.ScanResult) {
    val key = scannerRowKey(result)
    if (!scannerSelected.add(key)) scannerSelected.remove(key)
    renderScannerResults()
}

internal fun MainActivity.selectAllScannerResults(selected: Boolean) {
    if (selected) scannerFilterVisible(scannerSourceList()).forEach { scannerSelected += scannerRowKey(it) }
    else scannerSelected.clear()
    renderScannerResults()
}

internal fun MainActivity.selectVerifiedScannerResults() {
    scannerSelected.clear()
    scannerFilterVisible(scannerSourceList()).filter { it.tlsOk }.forEach { scannerSelected += scannerRowKey(it) }
    renderScannerResults()
}

/** Copies the ticked rows as `ip:port#CC` lines — the shape the panel's import box eats. */
internal fun MainActivity.copySelectedScannerResults() {
    val rows = scannerSelectedResults()
    if (rows.isEmpty()) {
        Toast.makeText(this, R.string.scanner_none_selected, Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-ips", rows.joinToString("\n") { it.panelLine }))
    Toast.makeText(this, getString(R.string.scanner_copy_selected_done, rows.size), Toast.LENGTH_LONG).show()
}

/** Sends ONLY the ticked rows — and only the TLS-verified ones among them. */
internal fun MainActivity.sendSelectedScannerResults() {
    val rows = scannerSelectedResults()
    if (rows.isEmpty()) {
        Toast.makeText(this, R.string.scanner_none_selected, Toast.LENGTH_SHORT).show()
        return
    }
    if (rows.none { it.tlsOk }) {
        Toast.makeText(this, R.string.scanner_unverified_only, Toast.LENGTH_LONG).show()
        return
    }
    activityScope.launch { sendScanToPanelSuspend(rows) }
}

internal fun MainActivity.refreshScannerHeader() {
    scannerHeaderCells.forEach { (view, key, label) ->
        val active = scannerSortKey == key
        view.text = if (active) label + (if (scannerSortAsc) " ▲" else " ▼") else label
        view.setTextColor(if (active) TEAL else TEXT_SECONDARY)
    }
}

/** Sortable column headers; the widths mirror [scannerResultRow] so the columns line up. */
internal fun MainActivity.scannerHeaderRow(): LinearLayout {
    // The page can be rebuilt (screen re-entry); stale cells would leak and be
    // repainted by refreshScannerHeader for views nobody shows any more.
    scannerHeaderCells.clear()
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        // Same horizontal padding as scannerResultRow: the sortable cells must sit
        // exactly above the cells they sort.
        setPadding(dp(10), dp(2), dp(10), dp(2))
    }
    fun head(label: String, key: String, align: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 10.5f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_SECONDARY)
            gravity = align
            includeFontPadding = false
            setPadding(dp(3), dp(5), dp(3), dp(5))
            isClickable = true
            isFocusable = true
            setSelectableBackground()
            setOnClickListener {
                if (scannerSortKey == key) {
                    scannerSortAsc = !scannerSortAsc
                } else {
                    scannerSortKey = key
                    // Speed reads best high-to-low; every other column low-to-high.
                    scannerSortAsc = key != "speed"
                }
                renderScannerResults()
            }
            scannerHeaderCells += Triple(this, key, label)
        }
    row.addView(
        TextView(this).apply {
            text = "☑"
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            isFocusable = true
            setSelectableBackground()
            setOnClickListener {
                val visible = scannerFilterVisible(scannerSourceList())
                val every = visible.isNotEmpty() && visible.all { scannerSelected.contains(scannerRowKey(it)) }
                selectAllScannerResults(!every)
            }
        },
        LinearLayout.LayoutParams(dp(26), -2),
    )
    row.addView(head(getString(R.string.scanner_col_address), "ip", Gravity.START), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(head(getString(R.string.scanner_col_country), "country", Gravity.CENTER), LinearLayout.LayoutParams(dp(44), -2))
    row.addView(head(getString(R.string.scanner_col_line), "ping", Gravity.CENTER), LinearLayout.LayoutParams(dp(46), -2))
    row.addView(head(getString(R.string.scanner_col_worker), "worker", Gravity.CENTER), LinearLayout.LayoutParams(dp(50), -2))
    row.addView(head(getString(R.string.scanner_col_jitter), "jitter", Gravity.CENTER), LinearLayout.LayoutParams(dp(40), -2))
    row.addView(head(getString(R.string.scanner_col_speed), "speed", Gravity.CENTER), LinearLayout.LayoutParams(dp(54), -2))
    return row
}

/** Bulk-select chips + the two things the owner actually asked for: copy, send to the panel. */
internal fun MainActivity.scannerSelectionBar(): LinearLayout {
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    val chipRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        setPadding(dp(13), dp(6), dp(13), dp(6))
        includeFontPadding = false
        background = glassSurfaceDrawable(radiusDp = 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
    chipRow.addView(chip(getString(R.string.scanner_select_all)) { selectAllScannerResults(true) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    chipRow.addView(chip(getString(R.string.scanner_select_verified)) { selectVerifiedScannerResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
    chipRow.addView(chip(getString(R.string.scanner_select_none)) { selectAllScannerResults(false) }, LinearLayout.LayoutParams(-2, -2))
    scannerSelectionText = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    chipRow.addView(scannerSelectionText, LinearLayout.LayoutParams(0, -2, 1f))
    bar.addView(chipRow, LinearLayout.LayoutParams(-1, -2))
    val actionRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    scannerCopySelectedButton = MaterialButton(this).apply {
        setText(R.string.scanner_copy_selected)
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        insetTop = 0
        insetBottom = 0
        isEnabled = false
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 90))
        setTextColor(palette.onAccent)
        setOnClickListener { copySelectedScannerResults() }
    }
    scannerSendSelectedButton = MaterialButton(this).apply {
        setText(R.string.scanner_send_selected)
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        insetTop = 0
        insetBottom = 0
        isEnabled = false
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(palette.onAccent)
        setOnClickListener { sendSelectedScannerResults() }
    }
    actionRow.addView(scannerCopySelectedButton, LinearLayout.LayoutParams(0, -2, 1f))
    actionRow.addView(scannerSendSelectedButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
    bar.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    return bar
}

/** One plain text line per result — no weights, no backgrounds, no clipping. */
internal fun MainActivity.scannerPlainRowsBlock(rows: List<IpScanner.ScanResult>): TextView =
    TextView(this).apply {
        text = rows.joinToString("\n") { r ->
            buildString {
                append(r.flag); append(' '); append(r.ip); append(':'); append(r.port)
                append(" · "); append(getString(R.string.scanner_result_ping, r.pingMs))
                val cc = r.countryCode
                if (!cc.isNullOrBlank()) { append(" · "); append(cc) }
                if (r.tlsOk) append(" · TLS ✓")
            }
        }
        textSize = 11.5f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = glassSurfaceDrawable(radiusDp = 12)
    }

/**
 * Last line of defence for the results table. The count header has been rendered
 * with zero rows under it three times now (1073/1114/1265 reports), and neither a
 * crash nor a logged exception ever showed up — so instead of guessing again we
 * MEASURE: after the frame is laid out, if the rows carry no height (or were never
 * added) the same rows are appended as one plain text block and the numbers are
 * written to the diagnostics log, so the next report says exactly what happened.
 */
internal fun MainActivity.verifyScannerRowsPainted(expected: Int, buildFailure: Throwable?) {
    val root = scannerResultsList.viewTreeObserver
    val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            val vto = runCatching { scannerResultsList.viewTreeObserver }.getOrNull()
            runCatching { vto?.removeOnGlobalLayoutListener(this) }
            val children = scannerResultsList.childCount
            var painted = 0
            for (i in 1 until children) painted += scannerResultsList.getChildAt(i).height
            if (children > 1 && painted > 0) return
            val rows = runCatching {
                scannerSortedRows(scannerFilterVisible(scannerSourceList()))
            }.getOrElse { scannerFilterVisible(scannerSourceList()) }.take(scannerVisibleCap()).toList()
            if (rows.isEmpty()) return
            scannerResultsList.addView(scannerPlainRowsBlock(rows), LinearLayout.LayoutParams(-1, -2))
            DiagnosticLogger.warn(
                this@verifyScannerRowsPainted,
                "scanner.rows.empty",
                "expected=$expected children=$children paintedPx=$painted " +
                    "failure=${buildFailure?.javaClass?.simpleName}:${buildFailure?.message}",
            )
        }
    }
    runCatching { root.addOnGlobalLayoutListener(listener) }
}

/** Cannot-throw plain row — the guaranteed-visible last resort. */
internal fun MainActivity.fallbackScannerRow(result: IpScanner.ScanResult): View =
    TextView(this).apply {
        text = "${result.flag} ${result.ip}:${result.port} · ${result.pingMs}ms${if (result.tlsOk) " · TLS ✓" else ""}"
        textSize = 12.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

/**
 * «🏠 line 38ms ±4 · ☁️ worker 61ms ±9 FRA (+23)» — ping AND jitter of this exact
 * ip:port on the phone's own line and through the worker. null = nothing measured yet:
 * the scan's own sample count tells whether the line numbers are real.
 */
internal fun MainActivity.scannerMeasureLine(r: IpScanner.ScanResult): String? {
    val fa = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val lineMs = r.lineMs ?: if (r.sampleCount >= 2) r.pingMs else null
    if (lineMs == null && r.workerState == null) return null
    return buildString {
        append(if (fa) "🏠 خط من " else "🏠 line ")
        if (lineMs != null) {
            append(lineMs).append("ms")
            if (r.sampleCount >= 2) append(" ±").append(r.jitterMs)
            if (r.lossPct >= 1f) append(" ").append(r.lossPct.toInt()).append("%✗")
        } else {
            append("—")
        }
        val state = r.workerState ?: return@buildString
        val edge = state == PingJitterMeter.WorkerState.EDGE.name
        append(if (edge) { if (fa) "  ·  ☁️ لبه " else "  ·  ☁️ edge " } else { if (fa) "  ·  ☁️ ورکر " else "  ·  ☁️ worker " })
        val w = r.workerMs
        if (w != null) {
            append(w).append("ms")
            r.workerJitterMs?.let { append(" ±").append(it) }
            r.workerLossPct?.takeIf { it >= 1f }?.let { append(" ").append(it.toInt()).append("%✗") }
            r.workerColo?.let { append(" ").append(it) }
            if (lineMs != null) append(" (+").append((w - lineMs).coerceAtLeast(0L)).append(")")
        } else {
            val ws = runCatching { PingJitterMeter.WorkerState.valueOf(state) }.getOrNull()
            append("✗ ").append(if (ws != null) PingJitterMeter.stateLabel(ws, fa) else state)
        }
    }
}

/**
 * The worker the ☁️ column measures through: a deployed Cat panel first, then the
 * worker host baked into the subscriptions, then the scan SNI when it is a
 * workers.dev name. Vercel and bare-IP panels cannot ride a Cloudflare IP → null
 * (the column then measures Cloudflare's own edge and says so). Reads storage: IO only.
 */
internal fun MainActivity.scannerWorkerHost(scanSni: String?): String? {
    fun usable(h: String?): String? {
        val host = h?.trim()?.lowercase(Locale.US)?.trimEnd('.').orEmpty()
        if (host.isEmpty() || !host.contains('.') || host.contains(':')) return null
        if (host.all { it.isDigit() || it == '.' }) return null
        if (PingJitterMeter.isNonCloudflareHost(host)) return null
        return host
    }
    runCatching { PanelDeploymentStore(this).deployments() }.getOrNull().orEmpty().forEach { d ->
        usable(runCatching { java.net.URI(d.workerUrl).host }.getOrNull())?.let { return it }
    }
    usable(runCatching { detectPanelSniFromSubscriptions() }.getOrNull())
        ?.takeIf { it.endsWith(".workers.dev") || it.endsWith(".pages.dev") || !IpScanner.RECOMMENDED_SNIS.contains(it) }
        ?.let { return it }
    return usable(scanSni)?.takeIf { it.endsWith(".workers.dev") }
}

/** 🏠/☁️ pass over [rows]: 6 TCP samples + 6 warm-TLS worker samples each, 4 at a time. */
internal suspend fun MainActivity.measureScannerRows(
    rows: List<IpScanner.ScanResult>,
    workerHost: String?,
): Map<String, IpScanner.ScanResult> = kotlinx.coroutines.coroutineScope {
    val gate = Semaphore(4)
    rows.map { r ->
        async(Dispatchers.IO) {
            gate.withPermit {
                val report = runCatching {
                    PingJitterMeter.measure(r.ip, r.port, workerHost, lineSamples = 6, workerSamples = 6)
                }.getOrNull() ?: return@withPermit null
                scannerRowKey(r) to PingJitterMeter.applyTo(r, report)
            }
        }
    }.awaitAll().filterNotNull().toMap()
}

/**
 * One result as a column row: ☑ · address (+ provenance) · country · ping ·
 * jitter · speed. Tapping the row ticks it; long-press copies the address.
 */
internal fun MainActivity.scannerResultRow(rank: Int, result: IpScanner.ScanResult): View {
    val selected = scannerSelected.contains(scannerRowKey(result))
    val speed = result.speedBps
    val up = result.uploadBps
    val location = if (!result.countryCode.isNullOrBlank()) {
        result.countryName ?: getString(R.string.scanner_result_edge_fallback)
    } else {
        result.colo?.takeIf { it.isNotBlank() } ?: getString(R.string.scanner_result_edge_fallback)
    }
    val metrics = buildString {
        when {
            speed != null && up != null -> append(" · ").append(getString(R.string.scanner_result_speeds, speed / 1_000_000.0, up / 1_000_000.0))
            speed != null -> append(" · ").append(getString(R.string.scanner_result_speed, speed / 1_000_000.0))
            up != null -> append(" · ").append(getString(R.string.scanner_result_upload, up / 1_000_000.0))
        }
    }
    // ONE TextView per result — deliberately. The old column layout spent 280dp on
    // fixed-width cells (checkbox 26 + country 54 + ping 56 + jitter 46 + speed 78
    // + padding 20) before the weighted address column got anything, so on a
    // 320dp-class screen (or Display size = Large) the address was squeezed to a
    // few dp and the trailing cells were clipped by clipToOutline: the row kept its
    // box and its flag and showed no IP. It has been reported as «no rows at all»
    // three times since. A single wrapping text cannot be squeezed, clipped or
    // zero-width; if it ever fails to appear, the failure is the list itself —
    // which verifyScannerRowsPainted() now reports instead of leaving it blank.
    return TextView(this).apply {
        text = buildString {
            append(if (selected) "☑ " else "☐ ")
            append(result.flag).append(' ').append(result.ip).append(':').append(result.port)
            append('\n')
            append(if (result.tlsOk) "TLS ✓" else "TCP")
            if (rank == 1 && result.tlsOk) append(" · ").append(getString(R.string.scanner_recommended))
            append(" · ").append(getString(R.string.scanner_result_ping, result.pingMs))
            append(metrics)
            append(" · ").append(location)
            scannerMeasureLine(result)?.let { append('\n').append(it) }
        }
        textSize = 12.5f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        // Order matters: setSelectableBackground() replaces the background, so the
        // glass card (and the teal edge on a selected row) must be applied after it.
        setSelectableBackground()
        background = glassSurfaceDrawable(radiusDp = 12, highlighted = selected)
        clipToOutline = true
        isClickable = true
        isFocusable = true
        setOnClickListener { toggleScannerRow(result) }
        setOnLongClickListener { copyScannerIp(result); true }
    }
}

/**
 * True when the live panel was deployed with a username (PANEL_USER): its
 * /api/login then needs both fields. Asked over the public /api/version,
 * which reports the same flag its login page uses to render the field.
 */
internal fun MainActivity.panelNeedsUsername(base: String): Boolean = runCatching {
    val conn = URL("$base/api/version").openConnection() as HttpURLConnection
    conn.connectTimeout = 6_000
    conn.readTimeout = 6_000
    conn.instanceFollowRedirects = false
    val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()
    org.json.JSONObject(text).optBoolean("needsUser", false)
}.getOrDefault(false)

internal fun MainActivity.updateScannerPanelPushProgress(percent: Int, label: String, visible: Boolean = true) {
    val clamped = percent.coerceIn(0, 100)
    scannerPanelPushProgressBar?.apply {
        visibility = if (visible) View.VISIBLE else View.GONE
        progress = clamped
    }
    scannerPanelPushStatusText?.apply {
        visibility = if (visible) View.VISIBLE else View.GONE
        text = "$label ($clamped%)"
    }
}

internal fun MainActivity.sendScanToPanel() = activityScope.launch { sendScanToPanelSuspend() }

/**
 * Import clean IPs into Cat Panel over its API: POST /api/login (password →
 * session cookie), then POST /api/ips {ips:[…]} which appends and returns the
 * new list size. Nothing is stored on the phone. Mirror of
 * ProxyIpScannerPage.pushToPanel.
 */
internal fun MainActivity.pushIpsToPanel(base: String, password: String, lines: List<String>, replace: Boolean = false, pingMs: org.json.JSONObject? = null, countries: org.json.JSONObject? = null, neighborLines: List<String> = emptyList()): Int {
    fun call(path: String, method: String, body: String?, cookie: String?): Pair<HttpURLConnection, String> {
        val conn = URL("$base$path").openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
        val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return conn to stream.bufferedReader().readText()
    }
    val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(applicationContext, base, password).toString(), null)
    if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(getString(R.string.pip_wrong_password))
    val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
    login.disconnect()
    val body = org.json.JSONObject().put("ips", org.json.JSONArray(lines))
    if (replace) body.put("replace", true)
    // Source tag + per-IP latency measured from THIS network + country tags.
    body.put("source", "scanner")
    if (pingMs != null && pingMs.length() > 0) body.put("pingMs", pingMs)
    if (countries != null && countries.length() > 0) body.put("countries", countries)
    // 👑 Provenance: IPs discovered by the neighbor scan are flagged so the
    // panel shows the crown badge («👑 همسایه») next to them.
    if (neighborLines.isNotEmpty()) body.put("neighborIps", org.json.JSONArray(neighborLines))
    val (post, postBody) = call("/api/ips", "POST", body.toString(), cookie)
    val ok = post.responseCode == 200 && org.json.JSONObject(postBody).optBoolean("ok")
    post.disconnect()
    if (!ok) throw IllegalStateException("HTTP ${post.responseCode}")
    val outcome = PanelIpsReadBack.verify(postBody, lines)
    if (!outcome.persisted) throw IllegalStateException("NO_KV")
    return outcome.stored
}

internal fun MainActivity.copyScannerIp(result: IpScanner.ScanResult) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("clean-ip", result.authority))
    Toast.makeText(this, getString(R.string.scanner_ip_copied, result.authority), Toast.LENGTH_SHORT).show()
}

/**
 * Applies a scanned IP:port as a fronting endpoint: connects to that clean CDN
 * endpoint while the profile's SNI/Host (the panel domain) stays untouched.
 */
internal fun MainActivity.applyScannerResult(result: IpScanner.ScanResult? = null) {
    val target = result ?: scannerResults.firstOrNull()
    if (target == null) {
        Toast.makeText(this, R.string.scanner_no_results, Toast.LENGTH_SHORT).show()
        return
    }
    val entryAuthority = RelayCatalogAction.useAsEntry(
        target.toCatalogEntry(System.currentTimeMillis()),
    )?.value ?: target.authority
    val previousValue = frontingIpPreferenceStore.readFrontingIp()
    // Dual-stack apply: when nothing specific was tapped, take the best v4 AND
    // the best v6 so the tunnel survives either family dropping.
    val companions = if (result == null) {
        val pool = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList()).filter { it.tlsOk }
        val otherFamily = pool.firstOrNull { IpScanner.isIpv6(it.ip) != IpScanner.isIpv6(target.ip) }
        listOfNotNull(otherFamily?.authority)
    } else {
        emptyList()
    }
    frontingIps = FrontingIpPolicy.mergeNewestFirst(listOf(entryAuthority) + companions, frontingIps)
    renderFrontingIpChips()
    if (!saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) return
    Toast.makeText(
        this,
        getString(R.string.scanner_applied, entryAuthority, target.pingMs),
        Toast.LENGTH_LONG,
    ).show()
}
