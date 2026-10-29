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
import com.cat.client.MainActivity.Companion.SCREEN_SERVERS

// Home tab: dashboard, traffic meter row, world map page.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

/** ZedSecure FlowMeter: two tonal halves with asymmetric corners and a cookie badge. */
internal fun MainActivity.buildFlowMeterRow(): LinearLayout {
    val s = zedScheme
    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val badges = ArrayList<Pair<ZedShapeIconView, Boolean>>()
    fun half(labelRes: Int, iconRes: Int, value: TextView, total: TextView, download: Boolean): LinearLayout {
        val badge = ZedShapeIconView(
            this,
            ZedShape.Cookie4,
            if (download) s.primaryContainer else s.tertiaryContainer,
            iconRes,
            if (download) s.onPrimaryContainer else s.onTertiaryContainer,
            0f,
            0.5f,
        )
        badges += badge to download
        value.textSize = 24f
        value.setTextColor(s.onSurface)
        value.gravity = Gravity.START
        value.typeface = CatClientDisplayTypeface
        total.textSize = 11f
        total.setTextColor(s.onSurfaceVariant)
        val big = dp(26).toFloat()
        val small = dp(6).toFloat()
        // the outer edge of the row is round, the edges that meet in the middle are tight
        val outerIsLeft = download != rtl
        val radii = if (outerIsLeft) {
            floatArrayOf(big, big, small, small, small, small, big, big)
        } else {
            floatArrayOf(small, small, big, big, big, big, small, small)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(14), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(s.surfaceContainerHigh)
            }
            addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(
                LinearLayout(this@buildFlowMeterRow).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(
                        TextView(this@buildFlowMeterRow).apply {
                            setText(labelRes)
                            textSize = 12f
                            typeface = CatClientBodyBoldTypeface
                            setTextColor(s.onSurfaceVariant)
                            includeFontPadding = false
                        },
                        LinearLayout.LayoutParams(-1, -2),
                    )
                    addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    addView(total, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) },
            )
        }
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            half(R.string.metric_download, R.drawable.ic_zed_download, downloadSpeedText, downloadTotalText, true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            half(R.string.metric_upload, R.drawable.ic_zed_upload, uploadSpeedText, uploadTotalText, false),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3) },
        )
    }
    flowBadges = badges
    setSpeedText(downloadSpeedText, 0L)
    setSpeedText(uploadSpeedText, 0L)
    return row
}

internal fun MainActivity.buildDashboard(): View {
    val scrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(withAlpha(BACKGROUND, 0))
        clipToPadding = false
        clipChildren = false  // Allow particles to extend beyond bounds
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
    val viewport = FrameLayout(this).apply {
        setPadding(0, 0, 0, dp(104))
        // Allow particles to extend beyond this layout's bounds
        clipChildren = false
        clipToPadding = false
    }
    scrollView.addView(
        viewport,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )

    val dashboardContent = MaxWidthLinearLayout(this).apply {
        maxWidthPx = dp(520)
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(104))
        // Allow particles to extend beyond this layout's bounds
        clipChildren = false
        clipToPadding = false
    }

    fun contentParams(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
            this.topMargin = topMargin
        }
    }

    // ZedSecure brand header: status chip · log icon · spacer · app name.
    statusDot = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(palette.neutral)
        }
    }
    statusText = TextView(this).apply {
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
        textSize = 13f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
    }
    val statusChip = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(withAlpha(palette.surfaceElevated2, 230))
        }
        addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        addView(statusText, LinearLayout.LayoutParams(-2, -2))
    }
    fun headerIconButton(@DrawableRes iconRes: Int, descriptionRes: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(iconRes)
        setColorFilter(TEXT_PRIMARY)
        contentDescription = getString(descriptionRes)
        isClickable = true
        isFocusable = true
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(palette.surfaceElevated2, 230))
        }
        setOnClickListener { onClick() }
    }
    val headerBlock = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        addView(statusChip, LinearLayout.LayoutParams(-2, -2))
        addView(
            headerIconButton(R.drawable.ic_connection_test, R.string.diagnostics_copy) { copyDiagnosticsToClipboard() },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) },
        )
        addView(View(this@buildDashboard), LinearLayout.LayoutParams(0, 0, 1f))
        addView(TextView(this@buildDashboard).apply {
            text = "Cat Client"
            textSize = 17f
            typeface = CatClientDisplayTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
            letterSpacing = -0.01f
        })
    }

    // New segmented tab switcher with rounded corners
    val connectionModeButtonColors = arrayOf(
        intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
        intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
        intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
        intArrayOf(-android.R.attr.state_enabled, -android.R.attr.state_checked),
    )
    fun connectionModeButton(@StringRes textRes: Int) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        id = View.generateViewId()
        setText(textRes)
        setAllCaps(false)
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        isCheckable = true
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(44)
        minimumHeight = dp(44)
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, 0)
        cornerRadius = dp(12)
        strokeWidth = 0
        elevation = 0f
        stateListAnimator = null
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val selectedBackground = withAlpha(TEAL, if (palette.isDark) 64 else 32)
        backgroundTintList = ColorStateList(
            connectionModeButtonColors,
            intArrayOf(selectedBackground, Color.TRANSPARENT, withAlpha(TEAL, 24), Color.TRANSPARENT),
        )
        setTextColor(
            ColorStateList(
                connectionModeButtonColors,
                intArrayOf(
                    TEAL,
                    TEXT_PRIMARY,
                    withAlpha(TEAL, 120),
                    withAlpha(TEXT_PRIMARY, 110),
                ),
            ),
        )
        rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 28))
        gravity = Gravity.CENTER
    }
    vpnModeButton = connectionModeButton(R.string.connection_mode_vpn)
    proxyModeButton = connectionModeButton(R.string.connection_mode_proxy)
    connectionModeGroup = MaterialButtonToggleGroup(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        isSingleSelection = true
        isSelectionRequired = true
        // Container with rounded background
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(withAlpha(SURFACE, 200))
            setStroke(dp(1), withAlpha(OUTLINE, 180))
        }
        minimumHeight = dp(52)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        addView(vpnModeButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(proxyModeButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        check(
            if (connectionModePreferenceStore.read() == ConnectionMode.Proxy) {
                proxyModeButton.id
            } else {
                vpnModeButton.id
            },
        )
        addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && isEnabled) {
                saveConnectionMode(
                    if (checkedId == proxyModeButton.id) ConnectionMode.Proxy else ConnectionMode.Vpn,
                )
            }
        }
    }
    // The globe is the connection surface; the glass action below it is the
    // single clear connect/disconnect action and keeps the gesture obvious.
    connectionGlobe = ConnectionGlobeView(this).apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { handleButtonClick() }
        setOnLongClickListener {
            copyDiagnosticsToClipboard()
            true
        }
    }
    // Aras-style action capsule: a big 56dp pill. Filled purple gradient
    // while the tunnel is up, state-tinted while switching, quiet glass
    // otherwise (see applyConnectPillStyle). The globe stays the playful
    // touch target; this is the clear one-tap switch under it.
    connectActionButton = MaterialButton(this).apply {
        setText(R.string.connect_action_connect)
        setAllCaps(false)
        textSize = 17f
        typeface = CatClientBodyBoldTypeface
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(64)
        minimumHeight = dp(64)
        insetTop = 0
        insetBottom = 0
        setPadding(dp(30), 0, dp(30), 0)
        rippleColor = ColorStateList.valueOf(withAlpha(palette.onAccent, 40))
        elevation = 0f
        stateListAnimator = null
        backgroundTintList = null
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(30).toFloat()
            setColor(TEAL)
        }
        setTextColor(palette.onAccent)
        ZedIosMotion.press(this)
        setOnClickListener { handleButtonClick() }
    }
    publicServerNotice = TextView(this).apply {
        setText(R.string.notification_connected_public)
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(AMBER)
        includeFontPadding = false
        visibility = View.GONE
    }
    timerText = TextView(this).apply {
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        text = "00:00:00"
        textSize = 13f
        letterSpacing = 0.02f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        visibility = View.GONE // the elapsed time is rendered inside the hero blob
    }
    downloadSpeedText = TextView(this).apply {
        text = "0 B/s"
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textSize = 26f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
    }
    uploadSpeedText = TextView(this).apply {
        text = "0 B/s"
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textSize = 26f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
    }
    connectionRealIpText = TextView(this).apply {
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textSize = 11f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        visibility = View.GONE
    }
    connectionV6Text = TextView(this).apply {
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textSize = 11f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        visibility = View.GONE
    }
    connectionCountryText = TextView(this).apply {
        setText(R.string.output_automatic)
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
    }
    connectionDetailsText = TextView(this).apply {
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        minWidth = 0
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    dashboardLocalEndpointText = TextView(this).apply {
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    dashboardChainText = TextView(this).apply {
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        includeFontPadding = false
        maxLines = 1
        visibility = View.GONE
    }

    pingValueText = TextView(this).apply {
        text = "—"
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textSize = 19f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
    }
    uptimeValueText = TextView(this).apply {
        text = "00:00:00"
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        textSize = 19f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
    }
    // Reconnect button - initialize before signalSection
    refreshActionButton = MaterialButton(this).apply {
        text = ""
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, 0)
        cornerRadius = dp(26)
        strokeWidth = 0
        elevation = 0f
        stateListAnimator = null
        setIconResource(R.drawable.ic_refresh)
        iconSize = dp(22)
        iconPadding = 0
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        visibility = View.INVISIBLE  // Use INVISIBLE to preserve space and prevent UI jump
        setOnClickListener { handleRefreshClick() }
    }

    // ---- ZedSecure hero: blob core with the elapsed time inside, big state word under it,
    // two round quick actions floating at the right edge (ping all · reconnect).
    connectionBlob = ZedBlobView(this).apply {
        AppAccentPreferenceStore(this@buildDashboard).read().let { setThemeColors(palette.teal, palette.secondary, it == AppAccent.Lavender) }
        setOnClickListener { handleButtonClick() }
        setOnLongClickListener {
            copyDiagnosticsToClipboard()
            true
        }
    }
    heroStateText = TextView(this).apply {
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textSize = 34f
        typeface = CatClientDisplayTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
        letterSpacing = -0.02f
    }
    fun roundAction(@DrawableRes iconRes: Int, descriptionRes: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(iconRes)
        setColorFilter(TEXT_PRIMARY)
        contentDescription = getString(descriptionRes)
        isClickable = true
        isFocusable = true
        setPadding(dp(14), dp(14), dp(14), dp(14))
        elevation = dp(6).toFloat()
        outlineProvider = ViewOutlineProvider.BACKGROUND
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(selectedRowColor())
        }
        setOnClickListener { onClick() }
    }
    val heroCluster = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            roundAction(R.drawable.ic_map, R.string.map_title) { showMapPage() },
            LinearLayout.LayoutParams(dp(54), dp(54)),
        )
        heroPingAction = roundAction(R.drawable.ic_speedometer, R.string.speedtest_title) { showSpeedTestPage() }
        addView(heroPingAction, LinearLayout.LayoutParams(dp(54), dp(54)).apply { topMargin = dp(10) })
        refreshActionButton.visibility = View.GONE
    }
    val heroFrame = FrameLayout(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        clipChildren = false
        clipToPadding = false
        addView(
            connectionBlob,
            FrameLayout.LayoutParams(dp(250), dp(250)).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(6) },
        )
        addView(
            heroStateText,
            FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.TOP; topMargin = dp(266) },
        )
        heroClusterParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END or Gravity.TOP
            marginEnd = dp(14)
            topMargin = dp(228)
        }
        addView(heroCluster, heroClusterParams)
    }
    homeHintText = TextView(this).apply {
        setText(R.string.portal_hint)
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setTextColor(zedScheme.primary)
        includeFontPadding = false
        visibility = View.GONE
    }
    when (appDesign) {
        AppDesign.Portal -> {
            // ZedSecure Portal: the wall-of-light stage fills the hero; the door is the only connect action.
            connectionBlob.visibility = View.GONE
            portalView = ZedPortalView(this).apply {
                contentDescription = getString(R.string.connect_action_connect)
                setOnClickListener { handleButtonClick() }
                setOnLongClickListener {
                    copyDiagnosticsToClipboard()
                    true
                }
            }
            heroFrame.addView(portalView, 0, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(58) })
            heroStateText.textSize = 40f
            heroStateText.layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(6)
            }
            heroClusterParams?.let { params ->
                params.gravity = Gravity.END or Gravity.BOTTOM
                params.topMargin = 0
                params.bottomMargin = dp(92)
                heroCluster.layoutParams = params
            }
        }
        AppDesign.Pixel -> {
            // ZedSecure Pixel: one big tonal card; the core in the middle is the only connect button.
            connectionBlob.visibility = View.GONE
            heroFrame.removeView(heroStateText)
            heroFrame.removeView(heroCluster)
            pixelCore = ZedPortalView(this, pixelMode = true).apply {
                contentDescription = getString(R.string.connect_action_connect)
                setOnClickListener { handleButtonClick() }
                setOnLongClickListener {
                    copyDiagnosticsToClipboard()
                    true
                }
            }
            heroStateText.textSize = 30f
            pixelCardColor = zedScheme.surfaceContainer
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(dp(18), dp(20), dp(18), dp(20))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(44).toFloat()
                    setColor(pixelCardColor)
                }
                addView(pixelCore, LinearLayout.LayoutParams(dp(200), dp(200)))
                addView(heroStateText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
                addView(homeHintText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            pixelCard = card
            heroFrame.addView(card, FrameLayout.LayoutParams(-1, -1))
            heroCluster.scaleX = 0.82f
            heroCluster.scaleY = 0.82f
            heroFrame.addView(heroCluster, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(8)
                marginEnd = dp(8)
            })
        }
        AppDesign.Old -> Unit
    }
    val signalSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        val heroHeight = when (appDesign) {
            AppDesign.Portal -> dp(450)
            AppDesign.Pixel -> dp(430)
            AppDesign.Old -> dp(350)
        }
        addView(heroFrame, LinearLayout.LayoutParams(-1, heroHeight))
        if (appDesign == AppDesign.Portal) {
            addView(homeHintText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        addView(timerText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        addView(publicServerNotice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    // ---- Location pill (ZedSecure ConnectionInfoPill): flag · city/country · exit IP · ping · chevron.
    connectionCountryText.textSize = 14f
    connectionCountryText.typeface = CatClientBodyBoldTypeface
    connectionCountryText.setTextColor(TEXT_PRIMARY)
    connectionCountryText.maxLines = 1
    connectionCountryText.ellipsize = TextUtils.TruncateAt.END
    pingValueText.textSize = 12f
    pingValueText.setTextColor(TEAL)
    val locationPill = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(14), dp(12))
        background = glassSurfaceDrawable(radiusDp = 22)
        isClickable = true
        isFocusable = true
        setOnClickListener { refreshDashboardIp() }
        homeFlagBadge = FlagBadgeView(this@buildDashboard).apply { fallbackColor = palette.surfaceElevated2 }
        addView(homeFlagBadge, LinearLayout.LayoutParams(dp(42), dp(30)).apply { marginEnd = dp(12) })
        addView(
            LinearLayout(this@buildDashboard).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(connectionCountryText, LinearLayout.LayoutParams(-1, -2))
                addView(
                    LinearLayout(this@buildDashboard).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(connectionRealIpText, LinearLayout.LayoutParams(-2, -2))
                        addView(connectionV6Text, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
                )
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(pingValueText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        addView(
            TextView(this@buildDashboard).apply {
                text = "›"
                textSize = 22f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) },
        )
    }

    // ---- Traffic tiles (ZedSecure CardsTrafficPanel): label · big rate · bar · session total.
    fun trafficTile(labelRes: Int, glyph: String, value: TextView, total: TextView, bar: View, tint: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(withAlpha(palette.surfaceElevated1, 235))
            }
            addView(
                LinearLayout(this@buildDashboard).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@buildDashboard).apply {
                            text = glyph
                            textSize = 11f
                            gravity = Gravity.CENTER
                            typeface = CatClientBodyBoldTypeface
                            setTextColor(tint)
                            includeFontPadding = false
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(10).toFloat()
                                setColor(withAlpha(tint, 40))
                            }
                        },
                        LinearLayout.LayoutParams(dp(36), dp(36)),
                    )
                    addView(
                        TextView(this@buildDashboard).apply {
                            text = getString(labelRes).uppercase(Locale.getDefault())
                            textSize = 11f
                            letterSpacing = 0.06f
                            typeface = CatClientBodyBoldTypeface
                            setTextColor(TEXT_SECONDARY)
                            includeFontPadding = false
                        },
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
                    )
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(bar, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8) })
            addView(total, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    fun barFill(tint: Int) = ZedWavyProgressView(this).apply {
        color = tint
        trackColor = withAlpha(palette.outline, 110)
        idleColor = withAlpha(palette.outline, 200)
    }
    fun totalText() = TextView(this).apply {
        text = "0 B"
        textSize = 12f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
    }
    downloadBarFill = barFill(TEAL)
    uploadBarFill = barFill(palette.secondary)
    downloadTotalText = totalText()
    uploadTotalText = totalText()
    val trafficRow = if (appDesign != AppDesign.Old) buildFlowMeterRow() else LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            trafficTile(R.string.metric_download, "↓", downloadSpeedText, downloadTotalText, downloadBarFill, TEAL),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            trafficTile(R.string.metric_upload, "↑", uploadSpeedText, uploadTotalText, uploadBarFill, palette.secondary),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) },
        )
    }

    locationSelectorRow = DashboardDataRowView(this).apply {
        setRow(getString(R.string.location_label), getString(R.string.option_automatic))
        setOnRowClickListener { showLocationSelector() }
    }
    connectionSelectorRow = DashboardDataRowView(this).apply {
        setRow(getString(R.string.connection_label), getString(R.string.option_automatic))
        setOnRowClickListener {
            if (connectionChainPreferenceStore.read().enabled) {
                openConnectionChainSettingsFromHome()
            } else {
                showConnectionTestingPage()
            }
        }
    }
    homeChainAfterSelectorRow = DashboardDataRowView(this).apply {
        setRow(
            getString(R.string.connection_chain_after),
            getString(R.string.connection_chain_optional_detail),
        )
        setOnRowClickListener { openConnectionChainSettingsFromHome() }
    }
    homeChainSelectorRows = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(
            View(this@buildDashboard).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            },
        )
        addView(homeChainAfterSelectorRow, LinearLayout.LayoutParams(-1, -2))
    }
    homeSubscriptionSelectorRow = DashboardDataRowView(this).apply {
        val subscriptionName = selectedSubscriptionName()
        setRow(getString(R.string.subscriptions_title), subscriptionName)
        contentDescription = getString(
            R.string.settings_value_content_description,
            getString(R.string.subscriptions_title),
            subscriptionName,
        )
        setOnRowClickListener { showSubscriptionSelectorMenu(this) }
    }
    dashboardConnectionMetadataSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(
            View(this@buildDashboard).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            },
        )
        addView(
            LinearLayout(this@buildDashboard).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(12))
                addView(
                    dashboardChainText,
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) },
                )
                addView(connectionDetailsText, LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    dashboardLocalEndpointText,
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) },
                )
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
    // Connection details card
    val dataRowsList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(withAlpha(SURFACE, 200))
            setStroke(dp(1), withAlpha(OUTLINE, 180))
        }
        clipToOutline = true
        addView(locationSelectorRow, LinearLayout.LayoutParams(-1, -2))
        addView(View(this@buildDashboard).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            })
        addView(connectionSelectorRow, LinearLayout.LayoutParams(-1, -2))
        addView(homeChainSelectorRows, LinearLayout.LayoutParams(-1, -2))
        addView(View(this@buildDashboard).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            })
        addView(homeSubscriptionSelectorRow, LinearLayout.LayoutParams(-1, -2))
        addView(dashboardConnectionMetadataSection, LinearLayout.LayoutParams(-1, -2))
    }
    val dataRows = dataRowsList

    homeUsageCard = buildHomeUsageCard()

    // ZedSecure ActiveConfigCard: round badge · server name · "Tap to change server" · chevron.
    activeConfigTitle = TextView(this).apply {
        text = getString(R.string.option_automatic)
        textSize = 16f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }
    val activeConfigCard = if (appDesign != AppDesign.Old) buildZedTicket() else LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(14), dp(12), dp(16), dp(12))
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(TEAL, 40)),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(selectedRowColor())
            },
            null,
        )
        setOnClickListener {
            if (connectionChainPreferenceStore.read().enabled) openConnectionChainSettingsFromHome() else openScreen(SCREEN_SERVERS)
        }
        addView(
            TextView(this@buildDashboard).apply {
                text = "🌍"
                textSize = 22f
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(TEAL)
                }
            },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
        addView(
            LinearLayout(this@buildDashboard).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(activeConfigTitle, LinearLayout.LayoutParams(-1, -2))
                addView(TextView(this@buildDashboard).apply {
                    setText(R.string.home_tap_change_server)
                    textSize = 13f
                    typeface = CatClientBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) },
        )
        addView(TextView(this@buildDashboard).apply {
            text = "›"
            textSize = 24f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-2, -2))
    }
    homeLocationPill = locationPill
    locationPill.visibility = View.GONE
    connectActionButton.visibility = View.GONE // Zed: the blob itself is the switch
    when (appDesign) {
        AppDesign.Portal -> {
            locationPill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(zedScheme.surfaceContainerHigh)
            }
            dashboardContent.apply {
                addView(headerBlock, contentParams(dp(12)))
                addView(signalSection, contentParams(dp(0)))
                addView(locationPill, contentParams(dp(14)))
                addView(trafficRow, contentParams(dp(14)))
                addView(activeConfigCard, contentParams(dp(8)))
                addView(connectActionButton, contentParams(dp(14)))
            }
        }
        AppDesign.Pixel -> {
            // facts live inside the Pixel card: place · IP · ping as a soft chip
            locationPill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(withAlpha(zedScheme.onPrimaryContainer, 24))
            }
            pixelCard?.addView(locationPill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            dashboardContent.apply {
                addView(headerBlock, contentParams(dp(12)))
                addView(signalSection, contentParams(dp(8)))
                addView(activeConfigCard, contentParams(dp(10)))
                addView(trafficRow, contentParams(dp(3)))
                addView(connectActionButton, contentParams(dp(14)))
            }
        }
        AppDesign.Old -> dashboardContent.apply {
            addView(headerBlock, contentParams(dp(12)))
            addView(locationPill, contentParams(dp(14)))
            addView(signalSection, contentParams(dp(4)))
            addView(trafficRow, contentParams(dp(18)))
            addView(activeConfigCard, contentParams(dp(14)))
            addView(connectActionButton, contentParams(dp(14)))
        }
    }
    // Cat-specific controls (routing rows, VPN/Proxy mode, quota graph) move to the Servers screen.
    homeExtrasSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(dataRows, LinearLayout.LayoutParams(-1, -2))
        addView(connectionModeGroup, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addView(homeUsageCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    homeBackdrop = scrollView
    scrollView.isVerticalScrollBarEnabled = false
    scrollView.overScrollMode = View.OVER_SCROLL_NEVER
    homeHeroFrame = heroFrame
    dashboardContent.clipChildren = false
    dashboardContent.clipToPadding = false
    applyHomeBackdrop(VpnState.Stopped)
    renderHomeUsageCard()
    viewport.addView(
        dashboardContent,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ),
    )

    return scrollView
}

internal fun MainActivity.showMapPage() {
    val connected = buttonModel.state == VpnState.Started
    val live = liveGeo
    val vpnOn = connected || deviceVpnActive()
    val prefs = getSharedPreferences("cat_client_map", MODE_PRIVATE)
    // Origin = the real location: the panel worker's "real IP" view when available, otherwise
    // the last lookup made while NO VPN (ours or another app's) was up.
    var originCode = prefs.getString("origin_code", "").orEmpty()
    var originLabel = prefs.getString("origin_label", "").orEmpty()
    if (!live?.realCountryCode.isNullOrBlank()) {
        originCode = live!!.realCountryCode!!.uppercase()
        originLabel = live.realCountryName.orEmpty()
        prefs.edit().putString("origin_code", originCode).putString("origin_label", originLabel).apply()
    } else if (!vpnOn && live != null) {
        originCode = live.countryCode.uppercase()
        originLabel = listOfNotNull(live.city?.takeIf { it.isNotBlank() }, live.countryName).joinToString(", ")
        prefs.edit().putString("origin_code", originCode).putString("origin_label", originLabel).apply()
    }
    val exitCode = if (connected && live != null) live.countryCode.uppercase() else ""
    val exitLabel = if (connected && live != null) listOfNotNull(live.city?.takeIf { it.isNotBlank() }, live.countryName).joinToString(", ") else ""
    if (connected && originCode.isNotBlank() && originCode == exitCode && originLabel == exitLabel) {
        // A lookup recorded through the tunnel is not an origin — drop it (Zed asks for one VPN-off run).
        originCode = ""; originLabel = ""
        prefs.edit().remove("origin_code").remove("origin_label").apply()
    }
    // No recorded origin yet (the IP services often never answer on Iranian lines, which left the
    // map empty for good): fall back to the mobile network / SIM country — offline, VPN-proof, and
    // labelled as such. Replaced as soon as a real lookup answers below.
    var originFromSim = false
    if (originCode.isBlank()) MapOrigin.telephonyCountry(this)?.let { cc ->
        originCode = cc
        originLabel = MapOrigin.countryName(cc, resources.configuration.locales[0])
        originFromSim = true
    }
    val originTint = ZedBlobView.ZED_HOT_PINK
    val exitCodeNow = exitCode
    val exitLabelNow = exitLabel

    val map = ZedWorldMapView(this).apply {
        accent = TEAL
        originColor = originTint
        landColor = TEXT_PRIMARY
    }
    var countriesList: List<WorldMap.Country> = emptyList()
    fun applyRoute() {
        if (countriesList.isEmpty()) return
        val o = WorldMap.anchorOf(countriesList, originCode)?.let { ZedWorldMapView.Point(it.first, it.second, originLabel, originCode) }
        val e = WorldMap.anchorOf(countriesList, exitCodeNow)?.let { ZedWorldMapView.Point(it.first, it.second, exitLabelNow, exitCodeNow) }
        map.setRoute(o, e)
    }
    activityScope.launch {
        countriesList = withContext(kotlinx.coroutines.Dispatchers.IO) { WorldMap.countries(this@showMapPage) }
        map.setCountries(countriesList)
        applyRoute()
    }

    fun endpointRow(dot: Int, hollow: Boolean, caption: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        addView(View(this@showMapPage).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (hollow) setStroke(dp(2), dot) else setColor(dot)
            }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
        addView(LinearLayout(this@showMapPage).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(TextView(this@showMapPage).apply {
                text = caption; textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); includeFontPadding = false
            })
            addView(TextView(this@showMapPage).apply {
                text = value; textSize = 16f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    fun stat(caption: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@showMapPage).apply { text = caption; textSize = 11f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY) })
        addView(TextView(this@showMapPage).apply {
            text = value; textSize = 14f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY)
            layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
        })
    }
    // A direct lookup is worth trying while NO VPN is up (it then measures the real line).
    val lookingUp = !vpnOn && live == null
    fun originCaption() = getString(when {
        originFromSim -> R.string.map_you_sim
        connected && originCode.isNotBlank() -> R.string.map_you_saved
        else -> R.string.map_you
    })
    fun originValue() = originLabel.ifBlank {
        getString(when {
            connected -> R.string.map_no_origin
            lookingUp -> R.string.map_locating
            else -> R.string.map_unknown
        })
    }
    val originRow = endpointRow(originTint, true, originCaption(), originValue())
    val originTexts = originRow.getChildAt(1) as LinearLayout
    val originCaptionView = originTexts.getChildAt(0) as TextView
    val originValueView = originTexts.getChildAt(1) as TextView
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(withAlpha(palette.surfaceElevated1, 225))
        }
        addView(originRow)
        if (connected) {
            addView(endpointRow(TEAL, false, getString(R.string.map_exit), exitLabel.ifBlank { getString(R.string.map_unknown) }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            val ping = livePingMs
            val server = activeConfigTitle.text?.toString()?.takeIf { it.isNotBlank() }
            if (ping != null || server != null) {
                addView(LinearLayout(this@showMapPage).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    ping?.let { addView(stat(getString(R.string.map_ping), "$it ms"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) }) }
                    server?.let { addView(stat(getString(R.string.map_server), it), LinearLayout.LayoutParams(0, -2, 1f)) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            }
        } else {
            addView(TextView(this@showMapPage).apply {
                setText(R.string.map_offline_here); textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }
    // Route badge (Zed RouteBadge): origin flag ─ dotted connector with travelling dot ─ exit flag.
    val badge = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(40).toFloat()
            setColor(withAlpha(palette.surfaceElevated1, 230))
            setStroke(dp(1), withAlpha(OUTLINE, 150))
        }
    }
    fun endpoint(code: String, label: String, tint: Int, hollow: Boolean) = LinearLayout(this@showMapPage).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(FlagBadgeView(this@showMapPage).apply {
            circle = true; ringColor = tint; ringWidthDp = if (hollow) 1.5f else 2f; fallbackColor = palette.surfaceElevated2
            setCountryCode(code)
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        if (label.isNotBlank()) addView(TextView(this@showMapPage).apply {
            text = label.substringBefore(',').trim(); textSize = 11f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
    }
    fun renderBadge() = badge.apply {
        removeAllViews()
        if (originCode.isNotBlank()) addView(endpoint(originCode, originLabel, originTint, true))
        if (connected && exitCodeNow.isNotBlank()) {
            addView(ZedRouteConnectorView(this@showMapPage).apply { color = TEAL }, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(10); marginEnd = dp(10) })
            addView(endpoint(exitCodeNow, exitLabelNow, TEAL, false))
        }
        visibility = if (originCode.isNotBlank() || exitCodeNow.isNotBlank()) View.VISIBLE else View.GONE
    }
    renderBadge()
    val overlay = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(20), dp(12), dp(20), dp(18))
        addView(TextView(this@showMapPage).apply {
            setText(R.string.map_title); textSize = 28f; typeface = CatClientDisplayTypeface; setTextColor(TEXT_PRIMARY); includeFontPadding = false
        })
        addView(wizardText(getString(R.string.map_subtitle), secondary = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(View(this@showMapPage), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(badge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        addView(card, LinearLayout.LayoutParams(-1, -2))
    }
    val page = FrameLayout(this).apply {
        setBackgroundColor(BACKGROUND)
        addView(map, FrameLayout.LayoutParams(-1, -1))
        addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }
    val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.setContentView(page)
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BACKGROUND))
    // Live lookup while the page is open: the map used to read one snapshot and stay empty when
    // no lookup had answered before. A direct (VPN-off) answer becomes the recorded origin and
    // the map, badge and card update in place.
    val lookupJob = if (lookingUp) activityScope.launch {
        val info = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
            runCatching { IpGeolocation.locate(physicalNetwork = activePhysicalNetwork()) }.getOrNull()
        }
        val cc = MapOrigin.usableCode(info?.countryCode)
        if (info != null && !currentVpnStateIsStarted() && !deviceVpnActive()) {
            liveGeo = info; liveGeoTunneled = false; liveGeoAtMs = SystemClock.elapsedRealtime()
            if (cc != null) {
                originCode = cc
                originLabel = listOfNotNull(info.city?.takeIf { it.isNotBlank() }, info.countryName.takeIf { it.isNotBlank() && it != info.ip })
                    .joinToString(", ").ifBlank { MapOrigin.countryName(cc, resources.configuration.locales[0]) }
                originFromSim = false
                prefs.edit()
                    .putString("origin_code", originCode).putString("origin_label", originLabel).apply()
            }
        }
        // whatever happened, never leave «finding your location…» on screen
        originCaptionView.text = originCaption()
        originValueView.text = originLabel.ifBlank { getString(R.string.map_unknown) }
        renderBadge()
        applyRoute()
    } else null
    dialog.setOnDismissListener { lookupJob?.cancel() }
    dialog.show()
}
