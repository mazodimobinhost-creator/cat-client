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

// Servers tab: subscription list, cards, add/edit dialog, free configs, optimizer.
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

internal fun MainActivity.buildSubscriptionsScreen(): View {
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
    val content = MaxWidthLinearLayout(this).apply {
        maxWidthPx = dp(520)
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(34), dp(24), dp(104))
    }
    serversCountText = TextView(this).apply {
        setText(R.string.subscriptions_description)
        textSize = 14f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        gravity = Gravity.START
    }
    val subscriptionsHeaderCopy = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(TextView(this@buildSubscriptionsScreen).apply {
            setText(R.string.servers_title)
            textSize = 30f
            typeface = CatClientDisplayTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
            gravity = Gravity.START
        })
        addView(serversCountText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }
    // ZedSecure header actions: ping all · add (menu) · more.
    fun headerAction(@DrawableRes iconRes: Int, descriptionRes: Int, onClick: (View) -> Unit) = ImageView(this).apply {
        setImageResource(iconRes)
        setColorFilter(TEXT_PRIMARY)
        contentDescription = getString(descriptionRes)
        isClickable = true
        isFocusable = true
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(TEAL, 40)),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
        )
        setOnClickListener { onClick(this) }
    }
    val headerActions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        addView(headerAction(R.drawable.ic_speedometer, R.string.action_ping_all) { runPingAll(autoConnect = false) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(headerAction(R.drawable.ic_connection_test, R.string.subscription_action_test) { showConnectionTestingPage() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(headerAction(R.drawable.ic_more_vert, R.string.subscription_add) { showAddServerSheet() }, LinearLayout.LayoutParams(dp(42), dp(42)))
    }
    content.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        addView(subscriptionsHeaderCopy, LinearLayout.LayoutParams(0, -2, 1f))
        addView(headerActions, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    })
    content.addView(featureFinderBar(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    // "Auto · All servers" — ZedSecure's first row: bolt badge + connect to the fastest server.
    content.addView(
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(TEAL, 40)),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(22).toFloat()
                    setColor(palette.surfaceElevated1)
                },
                null,
            )
            setOnClickListener { runPingAll(autoConnect = true) }
            if (appDesign != AppDesign.Old) {
                // ZedSecure 3.1.6 Auto card: bolt on a cookie, tonal container
                background = zedCardBackground(zedScheme.surfaceContainer, ripple = true)
                addView(
                    ZedShapeIconView(this@buildSubscriptionsScreen, ZedShape.Cookie9, zedScheme.primaryContainer, R.drawable.ic_zed_bolt, zedScheme.onPrimaryContainer, 0f, 0.48f),
                    LinearLayout.LayoutParams(dp(50), dp(50)),
                )
            } else addView(
                TextView(this@buildSubscriptionsScreen).apply {
                    text = "⚡"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(withAlpha(TEAL, 56))
                        setStroke(dp(1), withAlpha(TEAL, 140))
                    }
                },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
            addView(
                LinearLayout(this@buildSubscriptionsScreen).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(TextView(this@buildSubscriptionsScreen).apply {
                        setText(R.string.servers_auto_title)
                        textSize = 16f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEXT_PRIMARY)
                        includeFontPadding = false
                    })
                    addView(TextView(this@buildSubscriptionsScreen).apply {
                        setText(R.string.servers_auto_detail)
                        textSize = 12.5f
                        typeface = CatClientBodyTypeface
                        setTextColor(TEXT_SECONDARY)
                        includeFontPadding = false
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) },
            )
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) },
    )
    subscriptionsList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    content.addView(subscriptionsList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    homeExtrasSection?.let { extras ->
        (extras.parent as? ViewGroup)?.removeView(extras)
        content.addView(extras, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }
    content.addView(
        buildFreeConfigsSection(),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) },
    )
    scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
    renderSubscriptions()
    // Floating "+" like ZedSecure's Servers FAB (opens the add-server menu).
    val fab = MaterialButton(this).apply {
        text = "+"
        textSize = 26f
        typeface = CatClientBodyBoldTypeface
        setAllCaps(false)
        includeFontPadding = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, dp(2))
        cornerRadius = dp(20)
        strokeWidth = 0
        elevation = dp(6).toFloat()
        backgroundTintList = ColorStateList.valueOf(selectedRowColor())
        setTextColor(TEXT_PRIMARY)
        rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 50))
        contentDescription = getString(R.string.subscription_add)
        setOnClickListener { showAddServerSheet() }
    }
    val root = FrameLayout(this)
    root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    root.addView(
        fab,
        FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = dp(20)
            bottomMargin = dp(104)
        },
    )
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        (fab.layoutParams as FrameLayout.LayoutParams).bottomMargin = navigationBottom + dp(104)
        fab.requestLayout()
        insets
    }
    return root
}

internal fun MainActivity.buildFreeConfigsSection(): View {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    column.addView(
        TextView(this).apply {
            setText(R.string.free_title)
            textSize = 20f
            typeface = CatClientDisplayTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    column.addView(
        advancedSectionDetail(getString(R.string.free_description)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )
    column.addView(
        advancedSectionDetail(getString(R.string.free_sources_hint, FreeConfigs.sources().size)),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )

    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    fun freeButton(labelRes: Int, accent: Boolean, click: (View) -> Unit): MaterialButton =
        MaterialButton(this).apply {
            setText(labelRes)
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            isAllCaps = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            cornerRadius = dp(10)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPaddingRelative(dp(8), 0, dp(8), 0)
            backgroundTintList = ColorStateList.valueOf(if (accent) TEAL else withAlpha(TEXT_SECONDARY, 40))
            setTextColor(if (accent) palette.onAccent else TEXT_PRIMARY)
            setOnClickListener(click)
        }
    freeConfigsFetchButton = freeButton(R.string.free_fetch, true) { fetchFreeConfigs() }
    buttons.addView(freeConfigsFetchButton, LinearLayout.LayoutParams(0, dp(46), 1f))
    freeConfigsTestButton = freeButton(R.string.free_test_ping, false) { testFreeConfigs() }
    buttons.addView(
        freeConfigsTestButton,
        LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) },
    )
    freeConfigsImportButton = freeButton(R.string.free_import_all, false) { importFreeConfigs() }
    buttons.addView(
        freeConfigsImportButton,
        LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(8) },
    )
    column.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    column.addView(
        freeButton(R.string.free_delta_kronecker, false) { showDeltaKroneckerHubDialog() },
        LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) },
    )

    freeConfigsProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 100
        progress = 0
        visibility = View.GONE
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(withAlpha(OUTLINE, 120))
    }
    column.addView(freeConfigsProgress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) })

    freeConfigsStatus = TextView(this).apply {
        setText(R.string.free_idle)
        textSize = 12.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(8), 0, 0)
    }
    column.addView(freeConfigsStatus, LinearLayout.LayoutParams(-1, -2))

    freeConfigsList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    column.addView(freeConfigsList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    activityScope.launch {
        val cached = withContext(Dispatchers.IO) { FreeConfigs.loadCache(this@buildFreeConfigsSection) }
        if (cached != null && cached.entries.isNotEmpty() && freeConfigEntries.isEmpty()) {
            freeConfigEntries = cached.entries
            freeConfigsStatus.setText(R.string.free_from_cache)
            renderFreeConfigs()
        }
    }

    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    column.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "🚀 اتصال جادویی ۱-کلیکی (⭐ پیشنهادی برای اینترنت شما)"
            } else {
                "🚀 1-Click Magic Connect (⭐ Recommended for Your ISP)"
            }
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            isAllCaps = false
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(TEAL)
            setTextColor(palette.onAccent)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showRecommendedOneClickConnectWizardDialog() }
        },
        LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) },
    )
    column.addView(
        MaterialButton(this).apply {
            text = if (isFarsi) {
                "🩺 تعمیر خودکار کانفیگ‌های من + موتور Cat FLUX (IPv4/IPv6)"
            } else {
                "🩺 Smart Config Doctor & Cat FLUX (IPv4/IPv6 Optimizer)"
            }
            textSize = 12.5f
            typeface = CatClientBodyBoldTypeface
            isAllCaps = false
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 40))
            setTextColor(TEAL)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showSmartConfigDoctorAndFluxDialog() }
        },
        LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) },
    )

    column.addView(
        MaterialButton(this).apply {
            setText(R.string.free_quick_add)
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            isAllCaps = false
            cornerRadius = dp(10)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 40))
            setTextColor(TEAL)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { showQuickConfigMenu(this) }
        },
        LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) },
    )
    return column
}

internal fun MainActivity.renderSubscriptions() {
    if (!subscriptionsListReady) return
    subscriptionsList.removeAllViews()
    val store = SubscriptionStore(this)
    val selectedId = store.readSelectedSubscriptionId()
    val selectedName = selectedSubscriptionName()
    homeSubscriptionSelectorRow.setValue(selectedName)
    homeSubscriptionSelectorRow.contentDescription =
        getString(
            R.string.settings_value_content_description,
            getString(R.string.subscriptions_title),
            selectedName,
        )
    if (settingsSubscriptionSelectorRowReady) {
        settingsSubscriptionSelectorRow.setValue(selectedName)
        settingsSubscriptionSelectorRow.contentDescription =
            getString(
                R.string.settings_value_content_description,
                getString(R.string.settings_subscription_title),
                selectedName,
            )
    }
    if (serversCountTextReady) {
        val total = SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS.sumOf { store.readCatalog(it)?.profiles?.size ?: 0 } +
            userSubscriptionManager.list().sumOf { it.connectionCount }
        serversCountText.text = connectionCountLabel(total)
    }
    SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS.forEachIndexed { index, subscriptionId ->
        val name = builtInSubscriptionName(subscriptionId)
        val count = store.readCatalog(subscriptionId)?.profiles?.size ?: 0
        subscriptionsList.addView(
            subscriptionCard(
                title = name,
                detail = getString(R.string.subscription_builtin_detail, connectionCountLabel(count)),
                selected = selectedId == subscriptionId,
                error = "",
                onTestConnections = { openSubscriptionConnectionTesting(subscriptionId) },
                actions = listOf(
                    R.string.subscription_action_select to {
                        userSubscriptionManager.select(subscriptionId)
                        onSubscriptionSelected()
                    },
                    R.string.subscription_action_refresh to {
                        refreshBuiltInSubscription(subscriptionId, name)
                    },
                ),
            ),
            LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(8)
            },
        )
    }
    val usageStore = SubscriptionUsageStore(this)
    userSubscriptionManager.list().forEach { item ->
        val updated = item.updatedAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it)
        } ?: getString(R.string.subscription_never_updated)
        val detail = getString(
            R.string.subscription_detail,
            item.format.label,
            connectionCountLabel(item.connectionCount),
            updated,
        )
        val usage = usageStore.read(item.input)
        val isRemote = item.input.trim().startsWith("https://", ignoreCase = true)
        val shareActions: List<Pair<Int, () -> Unit>> = if (isRemote) listOf(
            R.string.subscription_action_copy_link to { copySubscriptionLink(item) },
            R.string.subscription_action_qr to { showConfigQrCodes(listOf(item.input.trim()), item.name) },
            R.string.subscription_action_share to { shareSubscriptionLink(item) },
            R.string.subscription_action_open_v2rayng to { openSubscriptionInApp(item, "v2rayng") },
            R.string.subscription_action_open_v2box to { openSubscriptionInApp(item, "v2box") },
            R.string.subscription_action_open_info to { openSubscriptionInfoPage(item) },
        ) else emptyList()
        subscriptionsList.addView(
            subscriptionCard(
                title = item.name,
                detail = detail + subscriptionUsageLine(usage),
                selected = selectedId == item.id,
                error = localizedSubscriptionError(item.lastError),
                onTestConnections = { openSubscriptionConnectionTesting(item.id) },
                actions = listOf(
                    R.string.subscription_action_select to {
                        userSubscriptionManager.select(item.id)
                        onSubscriptionSelected()
                    },
                    R.string.subscription_action_edit to { editSubscriptionOrAetherConfig(item) },
                    R.string.subscription_action_refresh to { refreshSubscription(item) },
                    R.string.subscription_action_optimize to { optimizeUserSubscription(item) },
                ) + shareActions + listOf(
                    R.string.subscription_action_delete to { confirmDeleteSubscription(item) },
                ),
                usage = usage,
            ),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }
    renderHomeConnectionRows()
}

/* Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V4 */
/* Hallmark · component: subscription card · genre: modern-minimal · design-system: design.md · designed-as-app */
internal fun MainActivity.subscriptionCard(
    title: String,
    detail: String,
    selected: Boolean,
    error: String,
    onTestConnections: () -> Unit,
    actions: List<Pair<Int, () -> Unit>>,
    usage: SubscriptionUsage? = null,
): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    gravity = Gravity.START
    minimumHeight = dp(72)
    setPaddingRelative(dp(16), dp(14), dp(12), dp(14))
    elevation = 0f
    val selectAction = actions.firstOrNull { it.first == R.string.subscription_action_select }
    val overflowActions = actions.filterNot { it.first == R.string.subscription_action_select }
    val canSelect = selectAction != null && !selected
    isClickable = canSelect
    isFocusable = canSelect
    contentDescription = "$title, ${getString(
        if (selected) R.string.subscription_selected_badge else R.string.subscription_action_select,
    )}"
    // ZedSecure row: the selected server is a solid lime card with dark text; the rest sit on
    // the low surface container.
    val rowPrimary = TEXT_PRIMARY
    val rowSecondary = TEXT_SECONDARY
    background = RippleDrawable(
        ColorStateList.valueOf(withAlpha(TEAL, 36)),
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(if (selected) selectedRowColor() else palette.surfaceElevated1)
        },
        null,
    )
    clipToOutline = true
    if (canSelect) {
        setOnClickListener { selectAction?.second?.invoke() }
    }

    addView(
        LinearLayout(this@subscriptionCard).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@subscriptionCard).apply {
                    text = title
                    textSize = 16f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(rowPrimary)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    gravity = Gravity.START
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            if (selected) addView(
                TextView(this@subscriptionCard).apply {
                    text = "✓"
                    textSize = 14f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEAL)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(dp(2), TEAL)
                    }
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) },
            )
            // ⋮ overflow (edit · refresh · share · delete …), like Zed's per-row menu.
            addView(
                ImageView(this@subscriptionCard).apply {
                    setImageResource(R.drawable.ic_more_vert)
                    setColorFilter(rowSecondary)
                    contentDescription = getString(R.string.subscription_action_options)
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { view ->
                        catClientPopupMenu(view).apply {
                            overflowActions.forEachIndexed { index, (labelRes, _) ->
                                menu.add(0, index, index, labelRes)
                            }
                            setOnMenuItemClickListener { item ->
                                overflowActions[item.itemId].second()
                                true
                            }
                        }.show()
                    }
                },
                LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) },
            )
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    addView(TextView(this@subscriptionCard).apply {
        text = detail
        textSize = 12f
        typeface = CatClientBodyTypeface
        setTextColor(rowSecondary)
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        gravity = Gravity.START
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    if (usage != null && usage.totalBytes > 0) {
        // Quota bar (panel reported total=): green → amber → red as it fills up.
        val fraction = usage.usedFraction
        val barColor = when {
            fraction >= 0.9f -> ERROR
            fraction >= 0.7f -> palette.amber
            else -> TEAL
        }
        addView(
            ProgressBar(this@subscriptionCard, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 1000
                progress = (fraction * 1000f).toInt().coerceIn(0, 1000)
                progressTintList = ColorStateList.valueOf(barColor)
                progressBackgroundTintList = ColorStateList.valueOf(withAlpha(OUTLINE, 120))
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                contentDescription = getString(
                    R.string.subscription_usage_traffic,
                    SubscriptionUsagePolicy.formatBytes(usage.usedBytes),
                    SubscriptionUsagePolicy.formatBytes(usage.totalBytes),
                    usage.usedPercent,
                )
            },
            LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) },
        )
    }
    if (error.isNotBlank()) addView(TextView(this@subscriptionCard).apply {
        text = getString(R.string.subscription_error, error)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(ERROR)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        gravity = Gravity.START
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    val refreshAction = actions.firstOrNull { it.first == R.string.subscription_action_refresh }?.second
    val optimizeAction = actions.firstOrNull { it.first == R.string.subscription_action_optimize }?.second
    val isFarsiSub = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val bottomActionRow = LinearLayout(this@subscriptionCard).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(this@subscriptionCard).apply {
                text = getString(R.string.servers_open_list) + "  ›"
                textSize = 13f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEAL)
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                setPaddingRelative(0, dp(6), dp(8), dp(6))
                setOnClickListener { onTestConnections() }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (refreshAction != null) {
            addView(
                TextView(this@subscriptionCard).apply {
                    text = if (isFarsiSub) "🔄 بروزرسانی" else "🔄 Refresh"
                    textSize = 11.5f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(withAlpha(TEAL, 24))
                        setStroke(dp(1), withAlpha(TEAL, 110))
                    }
                    setOnClickListener { refreshAction() }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
            )
        }
        if (optimizeAction != null) {
            addView(
                TextView(this@subscriptionCard).apply {
                    text = if (isFarsiSub) "⚡ بهینه‌سازی خط من" else "⚡ Optimize"
                    textSize = 11.5f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEAL)
                    includeFontPadding = false
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(withAlpha(TEAL, 32))
                        setStroke(dp(1), TEAL)
                    }
                    setOnClickListener { optimizeAction() }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
            )
        }
    }
    addView(
        bottomActionRow,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
    )
}

internal fun MainActivity.showSubscriptionDialog(
    existing: UserSubscription? = null,
    initialSource: String = "",
    initialName: String = "",
) {
    val nameInput = TextInputEditText(this).apply {
        setSingleLine(true)
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setText(existing?.name ?: initialName)
    }
    val nameLayout = TextInputLayout(this).apply {
        hint = getString(R.string.subscription_name_hint)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(nameInput)
    }
    val sourceInput = TextInputEditText(this).apply {
        minLines = 4
        maxLines = 9
        gravity = Gravity.TOP or Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        background = null
        setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        setText(existing?.input ?: initialSource)
    }
    val sourceLayout = TextInputLayout(this).apply {
        hint = getString(R.string.subscription_source_hint)
        helperText = getString(R.string.subscription_source_helper)
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
        boxStrokeColor = TEAL
        defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
        setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
        setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        addView(sourceInput)
    }
    // socks5/http proxy links are valid CLIENT profiles AND panel fixed-exit
    // (chain) material — give them a one-tap "→ panel" action right here.
    val chainPush = MaterialButton(this).apply {
        setText(R.string.chain_push_btn)
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(12)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 150))
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 30))
        setTextColor(TEAL)
        visibility = View.GONE
        setOnClickListener {
            val link = sourceInput.text.toString().trim().lines().firstOrNull { it.startsWith("socks5://") || it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
            if (link.isNotBlank()) activityScope.launch { pushChainToPanelSuspend(link) }
        }
    }
    val isFarsiSubDialog = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val liveFormatBadge = TextView(this).apply {
        textSize = 11.5f
        setTextColor(TEAL)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        visibility = View.GONE
    }
    var liveFormatBadgeGeneration = 0
    fun renderLiveFormatInspection(inspection: SubConvConverter.UniversalConfigInspection?) {
        if (inspection != null && inspection.totalCount > 0) {
            val summary = inspection.formatCounts.entries.joinToString(" · ") { "${it.value}× ${it.key}" }
            liveFormatBadge.text = if (isFarsiSubDialog) {
                "✅ شناسایی شد (${inspection.totalCount} کانفیگ): $summary"
            } else {
                "✅ Detected (${inspection.totalCount} configs): $summary"
            }
            liveFormatBadge.visibility = View.VISIBLE
            if (nameInput.text.isNullOrBlank()) {
                val firstName = inspection.items.firstOrNull()?.proxyName.orEmpty()
                if (firstName.isNotBlank()) {
                    nameInput.setText(firstName.take(40))
                }
            }
        } else {
            liveFormatBadge.visibility = View.GONE
        }
    }
    fun updateLiveFormatBadge(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            liveFormatBadgeGeneration++
            liveFormatBadge.visibility = View.GONE
            return
        }
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val hasAt = '@' in trimmed.substringAfter("://").substringBefore('/')
            if (!hasAt) {
                liveFormatBadge.text = if (isFarsiSubDialog) {
                    "🌐 لینک سابسکریپشن ابری تشخیص داده شد (آماده دانلود و اتصال)"
                } else {
                    "🌐 Remote Subscription URL detected (Ready to fetch & connect)"
                }
                liveFormatBadge.visibility = View.VISIBLE
                liveFormatBadgeGeneration++
                return
            }
        }
        if (CatFusionLink.isCatFusion(trimmed)) {
            liveFormatBadgeGeneration++
            val fusedCount = CatFusionLink.decode(trimmed.lines().first().trim())?.size ?: 0
            liveFormatBadge.text = when {
                fusedCount == 0 && isFarsiSubDialog -> "⚠️ لینک catfusion:// خراب یا ناقص است"
                fusedCount == 0 -> "⚠️ Broken catfusion:// link"
                isFarsiSubDialog -> "📤 تانل ترکیبی catfusion:// با $fusedCount کانفیگ (موازی + ضدقطع + سریع‌ترین)"
                else -> "📤 catfusion:// fused tunnel with $fusedCount configs"
            }
            liveFormatBadge.visibility = View.VISIBLE
            if (nameInput.text.isNullOrBlank()) {
                CatFusionLink.title(trimmed)?.let { nameInput.setText(it.take(40)) }
            }
            return
        }
        // beta217: decoding a large paste took long enough to freeze typing; it now runs off the main thread
        // and only the newest request is allowed to update the badge.
        val generation = ++liveFormatBadgeGeneration
        activityScope.launch {
            val inspection = withContext(Dispatchers.Default) {
                runCatching { SubConvConverter.inspectUniversalConfigInput(trimmed) }.getOrNull()
            }
            if (generation != liveFormatBadgeGeneration) return@launch
            renderLiveFormatInspection(inspection)
        }
    }
    val pasteClipboardBtn = MaterialButton(this).apply {
        text = if (isFarsiSubDialog) {
            "📋 جای‌گذاری هوشمند از کلیپ‌بورد (DNS · VLESS · JSON · TOML · 28+)"
        } else {
            "📋 Smart Paste from Clipboard (DNS · VLESS · JSON · TOML · 28+)"
        }
        textSize = 11.5f
        typeface = CatClientBodyBoldTypeface
        isAllCaps = false
        cornerRadius = dp(10)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 160))
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 28))
        setTextColor(TEAL)
        setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(this@showSubscriptionDialog)?.toString()?.trim().orEmpty()
            if (clipText.isBlank()) {
                Toast.makeText(
                    this@showSubscriptionDialog,
                    if (isFarsiSubDialog) "⚠️ کلیپ‌بورد خالی است" else "⚠️ Clipboard is empty",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                sourceInput.setText(clipText)
                updateLiveFormatBadge(clipText)
            }
        }
    }
    sourceInput.addTextChangedListener(
        DebouncedTextWatcher(sourceInput) { txt ->
            chainPush.visibility = if (txt.trim().startsWith("socks5://")) View.VISIBLE else View.GONE
            updateLiveFormatBadge(txt)
        },
    )
    updateLiveFormatBadge(sourceInput.text?.toString().orEmpty())
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
        addView(nameLayout, LinearLayout.LayoutParams(-1, -2))
        addView(pasteClipboardBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(sourceLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(liveFormatBadge, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(chainPush, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(
            if (existing == null) {
                R.string.subscription_add_title
            } else {
                R.string.subscription_edit_title
            },
        )
        .setView(body)
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .setPositiveButton(
            if (existing == null) R.string.subscription_add else R.string.split_tunnel_save,
            null,
        )
        .create()
    dialog.showCatClientDialog {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Links copied from Telegram/HTML surfaces arrive as &amp; —
            // saving them verbatim silently breaks ports/limit params.
            val source = unescapeHtmlEntities(sourceInput.text.toString()).trim()
            val name = nameInput.text.toString().trim().ifBlank {
                scannedSubscriptionName(source)?.takeIf { it.isNotBlank() } ?: "Cat Config"
            }
            if (source.isBlank()) {
                Toast.makeText(
                    this@showSubscriptionDialog,
                    R.string.subscription_fields_required,
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            activityScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        if (existing == null) {
                            userSubscriptionManager.add(name, source)
                        } else {
                            userSubscriptionManager.update(existing.id, name, source)
                        }
                    }
                }
                result.onSuccess { item ->
                    dialog.dismiss()
                    if (existing == null) {
                        userSubscriptionManager.select(item.id)
                        onSubscriptionSelected()
                    } else {
                        renderSubscriptions()
                        renderConnectionDetails(buttonModel.state)
                        if (existing.id == userSubscriptionManager.selectedId()) {
                            refreshLocationOptions()
                        }
                    }
                    Toast.makeText(
                        this@showSubscriptionDialog,
                        getString(
                            if (existing == null) {
                                R.string.subscription_added
                            } else {
                                R.string.subscription_updated
                            },
                            connectionCountLabel(item.connectionCount),
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    Toast.makeText(
                        this@showSubscriptionDialog,
                        localizedError(
                            error,
                            if (existing == null) {
                                R.string.subscription_add_failed
                            } else {
                                R.string.subscription_update_failed
                            },
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}

internal fun MainActivity.optimizeUserSubscription(item: UserSubscription) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val backupPrefs = getSharedPreferences("cat_sub_optimizer_backups", Context.MODE_PRIVATE)
    val savedBackup = backupPrefs.getString("backup:${item.id}", null)?.takeIf(String::isNotBlank)
    val snapshot = runCatching { userSubscriptionManager.cachedSnapshot(item.id) }.getOrNull()
    val links = snapshot?.catalog?.profiles?.mapNotNull { it.shareLink?.takeIf(String::isNotBlank) }.orEmpty()
    if (links.isEmpty()) {
        Toast.makeText(this, R.string.connection_share_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    val nowMs = System.currentTimeMillis()
    val candidates = collectVerifiedEndpointsForFailover(nowMs)
    val sniOverride = frontingIpPreferenceStore.readSniOverride().takeIf(String::isNotBlank)
    val hasEch = snapshot?.catalog?.profiles?.any { it.echEnabled } == true

    var selectedFw = if (candidates.isNotEmpty()) {
        FirewallBypassProfile.NONE
    } else {
        FirewallBypassProfile.MCI_CDN_IPV6
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(18), dp(10), dp(18), dp(8))
    }

    val regionalNote = TextView(this).apply {
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
            "⚠️ دقت کنید: در برخی مناطق سیم‌کارت شما می‌تواند همراه اول باشد ولی فایروال منطقه ایرانسل باشد و بالعکس! سایر نت‌ها هم معمولاً از یکی از این دو فایروال استفاده می‌کنند."
        } else {
            "⚠️ Note: In some regions an MCI SIM sits behind an Irancell firewall and vice versa. Other ISPs typically use one of these two firewall profiles."
        }
    }
    root.addView(regionalNote, LinearLayout.LayoutParams(-1, -2))

    val ipInput = EditText(this).apply {
        setSingleLine(true)
        textSize = 12.5f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        hint = if (isFarsi) "آدرس IP ورودی (مثلاً 2a06:98c1:3101::6812:202f یا 188.114.98.0)" else "Entry IP (e.g. 2a06:98c1:3101::6812:202f or 188.114.98.0)"
        setText(selectedFw.defaultAddress)
    }

    val previewText = TextView(this).apply {
        textSize = 11.5f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_PRIMARY)
        setLineSpacing(0f, 1.2f)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    fun buildCurrentPlan(): SubscriptionOptimizationPlan {
        val customIp = ipInput.text?.toString()?.trim().orEmpty().takeIf { it.isNotEmpty() }
        return SubscriptionOptimizer.plan(
            lines = links,
            candidates = candidates,
            nowMs = nowMs,
            maxAgeMs = 86_400_000L,
            sniOverride = if (hasEch && selectedFw == FirewallBypassProfile.NONE) null else sniOverride,
            echEnabled = if (selectedFw == FirewallBypassProfile.NONE) hasEch else false,
            firewallProfile = selectedFw,
            customEntryIp = customIp,
        )
    }

    fun refreshPreview() {
        val plan = buildCurrentPlan()
        val profileDesc = when (selectedFw) {
            FirewallBypassProfile.MCI_CDN_IPV6 ->
                if (isFarsi) {
                    "🟦 فایروال همراه اول (CDN/WORKER with IPv6):\n" +
                        "• پاک شدن echConfigList، cipherSuites و finalMask\n" +
                        "• فینگرپرینت = chrome · آدرس IPv6 = ${ipInput.text?.toString()?.ifBlank { FirewallBypassRecipes.MCI_DEFAULT_IPV6 }}\n" +
                        "• مناسب دامنه شخصی فیلترنشده یا *.workers.dev (دامنه *.pages.dev فیلتر است)"
                } else {
                    "🟦 MCI Firewall (CDN/WORKER with IPv6): clears echConfigList, cipherSuites & finalMask; fingerprint=chrome; IPv6=${ipInput.text?.toString()?.ifBlank { FirewallBypassRecipes.MCI_DEFAULT_IPV6 }}."
                }
            FirewallBypassProfile.IRANCELL_CDN_FF ->
                if (isFarsi) {
                    "🟧 فایروال ایرانسل (CDN/WORKER with F&F method):\n" +
                        "• پاک شدن echConfigList · آدرس = ${ipInput.text?.toString()?.ifBlank { FirewallBypassRecipes.IRANCELL_DEFAULT_IPV4 }}\n" +
                        "• finalMask = tlshello-0-len (F&F) · cipherSuites = semi-python (F&F)\n" +
                        "• فینگرپرینت = unsafe · ALPN = http/1.1 (برای ws) و h2,http/1.1 (برای XHTTP)\n" +
                        "• رفع محدودیت سرعت آپلود روی فایروال ایرانسل"
                } else {
                    "🟧 Irancell Firewall (CDN/WORKER with F&F): clears echConfigList; IP=${ipInput.text?.toString()?.ifBlank { FirewallBypassRecipes.IRANCELL_DEFAULT_IPV4 }}; finalMask=tlshello-0-len; cipherSuites=semi-python; fingerprint=unsafe; ALPN=http/1.1 (ws) / h2,http/1.1 (XHTTP)."
                }
            FirewallBypassProfile.NONE ->
                if (isFarsi) {
                    "🟩 بهینه‌سازی با آی‌پی‌های تمیز اسکن‌شده روی خط فعلی (${candidates.size} کاندید سالم)"
                } else {
                    "🟩 Optimize with verified scanner IPs on current line (${candidates.size} verified candidates)"
                }
        }
        val pagesWarn = if (plan.pagesDevDomains.isNotEmpty()) {
            if (isFarsi) "\n⚠️ هشدار: دامنه ${plan.pagesDevDomains.joinToString()} از نوع pages.dev است که روی فایروال به طور کلی فیلتر است (از workers.dev یا دامنه شخصی استفاده کنید)."
            else "\n⚠️ Warning: ${plan.pagesDevDomains.joinToString()} uses *.pages.dev which is blocked."
        } else ""
        val diffLines = plan.items.filter { it.changed }.take(4).joinToString("\n") { row ->
            val changes = row.changes.joinToString(", ") { "${it.field}: ${it.before ?: "—"} → ${it.after ?: "—"}" }
            "• $changes"
        }
        val backupNote = if (savedBackup != null) {
            if (isFarsi) "\n↩ نسخه پشتیبان قبلی آماده بازگردانی است (${savedBackup.lines().size} کانفیگ)"
            else "\n↩ Backup snapshot available (${savedBackup.lines().size} lines)"
        } else ""
        previewText.text = buildString {
            append(profileDesc)
            append(pagesWarn)
            append("\n\n")
            append(
                if (isFarsi) "تعداد کانفیگ‌های قابل بهینه‌سازی: ${plan.changedCount} از ${plan.items.size}"
                else "Configs to optimize: ${plan.changedCount} / ${plan.items.size}",
            )
            if (diffLines.isNotEmpty()) append("\n").append(diffLines)
            append(backupNote)
        }
    }

    fun modeBtn(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            typeface = CatClientBodyBoldTypeface
            minHeight = dp(38)
            minimumHeight = dp(38)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(9)
            setPadding(dp(8), 0, dp(8), 0)
            backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, 220))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 190))
            setTextColor(TEXT_PRIMARY)
            setOnClickListener { onClick() }
        }

    val fwBtnRow = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(
            modeBtn(if (isFarsi) "🟦 ۱) فایروال همراه اول: CDN/Worker با IPv6 (2a06:98c1:3101::6812:202f)" else "🟦 1) MCI Firewall: CDN/Worker + IPv6") {
                selectedFw = FirewallBypassProfile.MCI_CDN_IPV6
                ipInput.setText(FirewallBypassRecipes.MCI_DEFAULT_IPV6)
                refreshPreview()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        addView(
            modeBtn(if (isFarsi) "🟧 ۲) فایروال ایرانسل: CDN/Worker با متد F&F (188.114.98.0 · رفع محدودیت آپلود)" else "🟧 2) Irancell Firewall: CDN/Worker + F&F (188.114.98.0)") {
                selectedFw = FirewallBypassProfile.IRANCELL_CDN_FF
                ipInput.setText(FirewallBypassRecipes.IRANCELL_DEFAULT_IPV4)
                refreshPreview()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        addView(
            modeBtn(if (isFarsi) "🟩 ۳) جایگزینی با آی‌پی‌های تمیز اسکن‌شده در اپ" else "🟩 3) Replace with Verified App Scanner IPs") {
                selectedFw = FirewallBypassProfile.NONE
                ipInput.setText("")
                refreshPreview()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        addView(
            modeBtn(if (isFarsi) "➕ روش‌های ۲ و ۳: افزودن Cat Warp (WARP با IPv6 / MASQUE-HTTP/2)" else "➕ Methods 2 & 3: Add Cat Warp (WARP IPv6 / MASQUE-HTTP/2)") {
                showAddAetherDialog()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
    }
    root.addView(fwBtnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    root.addView(ipInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    ipInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { refreshPreview() }
    })
    root.addView(previewText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    refreshPreview()

    val scroll = ScrollView(this).apply { addView(root) }
    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.subscription_optimize_title, item.name))
        .setView(scroll)
        .setNegativeButton(R.string.split_tunnel_cancel, null)
    if (savedBackup != null) {
        builder.setNeutralButton(if (isFarsi) "↩ بازگردانی قبل" else "↩ Rollback") { _, _ ->
            activityScope.launch {
                runCatching {
                    val restoredName = item.name
                        .removeSuffix(" (Optimized)")
                        .removeSuffix(" (MCI-IPv6)")
                        .removeSuffix(" (Irancell-F&F)")
                    userSubscriptionManager.update(item.id, restoredName, savedBackup)
                }.onSuccess { restored ->
                    userSubscriptionManager.select(restored.id)
                    onSubscriptionSelected()
                    Toast.makeText(this@optimizeUserSubscription, "↩ ${restored.name}", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@optimizeUserSubscription,
                        localizedError(error, R.string.subscription_operation_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
    builder.setPositiveButton(R.string.subscription_optimize_apply) { _, _ ->
        val plan = buildCurrentPlan()
        if (plan.conflict != null) {
            Toast.makeText(
                this,
                "TLS conflict: ${plan.conflict.reasons.joinToString()}",
                Toast.LENGTH_LONG,
            ).show()
            return@setPositiveButton
        }
        if (plan.changedCount == 0) {
            Toast.makeText(this, R.string.connection_route_no_candidates, Toast.LENGTH_LONG).show()
            return@setPositiveButton
        }
        activityScope.launch {
            runCatching {
                val backupPayload = plan.backupPayload()
                val optimizedText = plan.optimizedLines.joinToString("\n")
                val baseCleanName = item.name
                    .removeSuffix(" (Optimized)")
                    .removeSuffix(" (MCI-IPv6)")
                    .removeSuffix(" (Irancell-F&F)")
                val suffix = when (selectedFw) {
                    FirewallBypassProfile.MCI_CDN_IPV6 -> " (MCI-IPv6)"
                    FirewallBypassProfile.IRANCELL_CDN_FF -> " (Irancell-F&F)"
                    FirewallBypassProfile.NONE -> " (Optimized)"
                }
                val targetName = "$baseCleanName$suffix"
                val existingOpt = userSubscriptionManager.list().firstOrNull { it.name == targetName }
                val saved = if (existingOpt != null) {
                    userSubscriptionManager.update(existingOpt.id, targetName, optimizedText)
                } else {
                    userSubscriptionManager.add(targetName, optimizedText)
                }
                backupPrefs.edit()
                    .putString("backup:${item.id}", backupPayload)
                    .putString("backup:${saved.id}", backupPayload)
                    .apply()
                saved
            }.onSuccess { saved ->
                userSubscriptionManager.select(saved.id)
                onSubscriptionSelected()
                Toast.makeText(this@optimizeUserSubscription, "✓ ${saved.name} (${plan.changedCount})", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@optimizeUserSubscription,
                    localizedError(error, R.string.subscription_operation_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    builder.create().showCatClientDialog()
}
