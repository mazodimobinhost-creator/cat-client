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
import com.cat.client.MainActivity.Companion.FREE_QR_BATCH
import com.cat.client.MainActivity.Companion.FREE_SUBSCRIPTION_LIMIT
import com.cat.client.MainActivity.Companion.SCREEN_HOME
import com.cat.client.MainActivity.Companion.SUBSCRIPTION_ITEM_ID_BASE

// Servers tab: subscription list, cards, add/edit dialog, free configs, optimizer, add/import/share/QR, subscription actions.
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

// ---- moved from MainActivity.kt in beta221 (code split phase 2) ----

internal fun MainActivity.fetchFreeConfigs() {
    if (freeConfigsFetching) return
    // If a tunnel is up we fetch *through* it: inside Iran most of the mirrors
    // are unreachable directly but answer fine once anything is connected.
    val useTunnel = vpnStateEqualsStarted()
    freeConfigsFetching = true
    freeConfigsFetchButton.isEnabled = false
    freeConfigsProgress.visibility = View.VISIBLE
    freeConfigsProgress.progress = 0
    freeConfigsStatus.text = getString(if (useTunnel) R.string.free_loading_tunnel else R.string.free_loading)
    freeConfigsList.removeAllViews()
    freeConfigsJob = activityScope.launch {
        val report = runCatching {
            FreeConfigs.fetchAll(this@fetchFreeConfigs, useTunnel = useTunnel) { name, done, total ->
                mainHandler.post {
                    freeConfigsProgress.progress = (done * 100) / total.coerceAtLeast(1)
                    if (name.isNotEmpty()) {
                        freeConfigsStatus.text = getString(R.string.free_progress, name, done, total)
                    }
                }
            }
        }.getOrNull()
        freeConfigsFetching = false
        freeConfigsFetchButton.isEnabled = true
        freeConfigsProgress.visibility = View.GONE
        if (report == null || report.entries.isEmpty()) {
            val cached = FreeConfigs.loadCache(this@fetchFreeConfigs)
            if (cached != null && cached.entries.isNotEmpty()) {
                freeConfigEntries = cached.entries
                freeConfigsStatus.setText(R.string.free_from_cache)
                renderFreeConfigs()
            } else {
                freeConfigsStatus.setText(R.string.free_empty)
            }
            return@launch
        }
        FreeConfigs.saveCache(this@fetchFreeConfigs, report)
        freeConfigEntries = report.entries
        freeConfigsStatus.text = buildString {
            append(getString(R.string.free_loaded, report.entries.size))
            if (report.sourcesOk.isNotEmpty()) append("\n✓ ").append(report.sourcesOk.joinToString(" · "))
            if (report.sourcesFailed.isNotEmpty()) append("\n✗ ").append(report.sourcesFailed.joinToString(" · "))
        }
        renderFreeConfigs()
    }
}

internal fun MainActivity.freeConfigRow(entry: FreeConfigs.FreeEntry): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        background = glassSurfaceDrawable(radiusDp = 12)
        clipToOutline = true
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    row.addView(
        TextView(this).apply {
            text = entry.tag
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    row.addView(
        TextView(this).apply {
            text = entry.protocol.uppercase(Locale.US) + " · " + entry.host
            textSize = 11.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) },
    )
    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    fun actionButton(labelRes: Int, accent: Boolean, click: (View) -> Unit) =
        MaterialButton(this).apply {
            setText(labelRes)
            textSize = 11.5f
            typeface = CatClientBodyBoldTypeface
            isAllCaps = false
            isSingleLine = true
            cornerRadius = dp(9)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPaddingRelative(dp(6), 0, dp(6), 0)
            backgroundTintList =
                ColorStateList.valueOf(if (accent) TEAL else withAlpha(TEXT_SECONDARY, 40))
            setTextColor(if (accent) palette.onAccent else TEXT_PRIMARY)
            setOnClickListener(click)
        }
    actions.addView(
        actionButton(R.string.free_add_single, true) { addSingleConfig(entry.link, entry.tag) },
        LinearLayout.LayoutParams(0, dp(40), 1f),
    )
    actions.addView(
        actionButton(R.string.free_copy, false) { copyFreeConfig(entry) },
        LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) },
    )
    actions.addView(
        actionButton(R.string.free_qr, false) { showConfigQrCodes(listOf(entry.link), entry.tag) },
        LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) },
    )
    row.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    return row
}

internal fun MainActivity.copyFreeConfig(entry: FreeConfigs.FreeEntry) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("free-config", entry.link))
    Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
}

/** Adds one share-link as its own Subscription and selects it. */
internal fun MainActivity.addSingleConfig(link: String, name: String) {
    if (link.isBlank()) return
    val subscriptionName = name.take(48).ifBlank { "Cat Single" }
    activityScope.launch {
        val added = runCatching {
            withContext(Dispatchers.IO) { userSubscriptionManager.add(subscriptionName, link) }
        }.getOrNull()
        if (added == null) {
            Toast.makeText(this@addSingleConfig, R.string.free_quick_add_failed, Toast.LENGTH_LONG).show()
            return@launch
        }
        userSubscriptionManager.select(added.id)
        renderSubscriptions()
        onSubscriptionSelected()
        MaterialAlertDialogBuilder(this@addSingleConfig)
            .setTitle(R.string.free_quick_add_done)
            .setMessage(subscriptionName)
            .setPositiveButton(R.string.free_quick_add_connect) { _, _ -> beginConnectFlow(Actions.CONNECT) }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
            .showCatClientDialog()
    }
}

/** Paste / clipboard / QR entry point for a single config. */
internal fun MainActivity.showQuickConfigMenu(anchor: View) {
    val menu = catClientPopupMenu(anchor)
    menu.menu.add(R.string.free_quick_paste).setOnMenuItemClickListener {
        showQuickConfigDialog()
        true
    }
    menu.menu.add(R.string.subscription_from_clipboard).setOnMenuItemClickListener {
        addSubscriptionFromClipboard()
        true
    }
    menu.menu.add(R.string.subscription_scan_qr).setOnMenuItemClickListener {
        startSubscriptionQrScan()
        true
    }
    menu.menu.add(R.string.free_qr_all).setOnMenuItemClickListener {
        showConfigQrCodes(freeConfigEntries.take(FREE_QR_BATCH).map { it.link }, getString(R.string.free_title))
        true
    }
    menu.show()
}

internal fun MainActivity.showQuickConfigDialog() {
    val input = EditText(this).apply {
        hint = getString(R.string.free_quick_hint)
        textSize = 13f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        maxLines = 4
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(8), dp(4), 0)
        addView(input, LinearLayout.LayoutParams(-1, -2))
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.free_quick_add)
        .setView(container)
        .setPositiveButton(R.string.free_quick_add_confirm) { _, _ ->
            val link = input.text?.toString()?.trim().orEmpty()
            if (link.isEmpty()) {
                Toast.makeText(this, R.string.free_quick_empty, Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            AetherSelection.fromAetherUri(link)?.let { parsed ->
                importAndConnectAetherSelection(parsed)
                return@setPositiveButton
            }
            normalizedSubscriptionSource(link)?.let { source ->
                addSingleConfig(source, scannedSubscriptionName(source) ?: "Cat Single")
            } ?: Toast.makeText(this, R.string.free_quick_empty, Toast.LENGTH_SHORT).show()
        }
        .setNeutralButton(R.string.subscription_scan_qr) { _, _ -> startSubscriptionQrScan() }
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .show()
}

/** Ping-tests the fetched free configs through the core's connection testing page. */
internal fun MainActivity.testFreeConfigs() {
    val entries = freeConfigEntries
    if (entries.isEmpty()) {
        Toast.makeText(this, R.string.free_empty, Toast.LENGTH_SHORT).show()
        return
    }
    activityScope.launch {
        val id = ensureFreeSubscription(entries.map { it.link }.take(FREE_SUBSCRIPTION_LIMIT))
        if (id != null) openSubscriptionConnectionTesting(id)
    }
}

/** Adds every fetched free config as one Subscription (capped for size). */
internal fun MainActivity.importFreeConfigs() {
    val entries = freeConfigEntries
    if (entries.isEmpty()) {
        Toast.makeText(this, R.string.free_empty, Toast.LENGTH_SHORT).show()
        return
    }
    activityScope.launch {
        val id = ensureFreeSubscription(entries.map { it.link }.take(FREE_SUBSCRIPTION_LIMIT))
        if (id == null) {
            Toast.makeText(this@importFreeConfigs, R.string.free_quick_add_failed, Toast.LENGTH_LONG).show()
            return@launch
        }
        userSubscriptionManager.select(id)
        renderSubscriptions()
        onSubscriptionSelected()
        Toast.makeText(this@importFreeConfigs, R.string.free_imported, Toast.LENGTH_LONG).show()
    }
}

internal suspend fun MainActivity.ensureFreeSubscription(links: List<String>): String? {
    if (links.isEmpty()) return null
    val content = links.joinToString("\n")
    val name = getString(R.string.free_subscription_name)
    val existing = userSubscriptionManager.list().firstOrNull { it.name == name }
    if (existing != null) {
        val updated = runCatching {
            withContext(Dispatchers.IO) { userSubscriptionManager.update(existing.id, name, content) }
        }.getOrNull()
        if (updated != null) return updated.id
        // Fall through to creating a fresh one when the update is rejected.
        userSubscriptionManager.delete(existing.id)
    }
    return runCatching {
        withContext(Dispatchers.IO) { userSubscriptionManager.add(name, content) }
    }.getOrNull()?.id
}

/** QR codes for one or more share links, shown natively (no network needed). */
internal fun MainActivity.showConfigQrCodes(links: List<String>, title: String) {
    val valid = links.filter { it.isNotBlank() }.distinct()
    if (valid.isEmpty()) {
        Toast.makeText(this, R.string.free_empty, Toast.LENGTH_SHORT).show()
        return
    }
    val index = intArrayOf(0)
    val image = ImageView(this).apply {
        adjustViewBounds = true
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.WHITE)
    }
    val caption = TextView(this).apply {
        textSize = 11.5f
        setTextColor(TEXT_SECONDARY)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.MIDDLE
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setPadding(dp(16), dp(6), dp(16), 0)
    }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(image, LinearLayout.LayoutParams(-1, -2))
        addView(caption, LinearLayout.LayoutParams(-1, -2))
    }
    fun render() {
        val link = valid[index[0]]
        image.setImageBitmap(QrCodes.bitmap(link, sizePx = dp(240)))
        caption.text = "${index[0] + 1}/${valid.size} · $link"
    }
    render()
    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.free_qr_title, title))
        .setView(container)
        .setPositiveButton(R.string.free_qr_copy) { _, _ ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("cat-config", valid[index[0]]))
            Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(R.string.split_tunnel_cancel, null)
    if (valid.size > 1) {
        builder.setNeutralButton(R.string.free_qr_next) { _, _ ->
            index[0] = (index[0] + 1) % valid.size
            showConfigQrCodes(listOf(valid[index[0]]), title)
        }
    }
    builder.show()
}

/** Human-readable quota line for a subscription panel that reports usage. */
internal fun MainActivity.subscriptionUsageLine(usage: SubscriptionUsage?): String {
    if (usage == null) return ""
    val parts = mutableListOf<String>()
    if (usage.totalBytes > 0) {
        parts += getString(
            R.string.subscription_usage_traffic,
            SubscriptionUsagePolicy.formatBytes(usage.usedBytes),
            SubscriptionUsagePolicy.formatBytes(usage.totalBytes),
            usage.usedPercent,
        )
    } else if (usage.usedBytes > 0) {
        parts += getString(
            R.string.subscription_usage_used_only,
            SubscriptionUsagePolicy.formatBytes(usage.usedBytes),
        )
    }
    usage.expireEpochSeconds?.let { seconds ->
        val days = ((seconds * 1000L - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L)
        parts += getString(R.string.subscription_usage_expiry, days)
    }
    return if (parts.isEmpty()) "" else "\n" + parts.joinToString(" · ")
}

internal fun MainActivity.openSubscriptionConnectionTesting(subscriptionId: String) {
    if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
    val store = SubscriptionStore(this)
    if (store.readSelectedSubscriptionId() != subscriptionId) {
        userSubscriptionManager.select(subscriptionId)
        onSubscriptionSelected()
    }
    val profiles = if (SubscriptionStore.isBuiltInSubscription(subscriptionId)) {
        store.readCatalog(subscriptionId)?.profiles
    } else {
        runCatching { userSubscriptionManager.cachedSnapshot(subscriptionId) }
            .getOrNull()
            ?.catalog
            ?.profiles
    }.orEmpty()
    updateLocationOptions(profiles, resetMissingSelection = true)
    showConnectionTestingPage()
}

internal fun MainActivity.startSubscriptionQrScan() {
    IntentIntegrator(this)
        .setCaptureActivity(SubscriptionQrCaptureActivity::class.java)
        .setDesiredBarcodeFormats(listOf(IntentIntegrator.QR_CODE))
        .setPrompt(getString(R.string.subscription_scan_qr_prompt))
        .setBeepEnabled(false)
        .setBarcodeImageEnabled(false)
        .initiateScan()
}

internal fun MainActivity.showAddSubscriptionMenu(anchor: View) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    // Most used first; every label says in plain words what happens.
    catClientPopupMenu(anchor).apply {
        menu.add(R.string.subscription_from_clipboard).setOnMenuItemClickListener {
            addSubscriptionFromClipboard()
            true
        }
        menu.add(
            if (isFarsi) "📝 نوشتن یا چسباندن لینک (vless، vmess، لینک اشتراک…)"
            else "📝 Type or paste a link (vless, vmess, subscription URL…)",
        ).setOnMenuItemClickListener {
            showAddSubscriptionDialog()
            true
        }
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            menu.add(R.string.subscription_scan_qr).setOnMenuItemClickListener {
                startSubscriptionQrScan()
                true
            }
        }
        menu.add(
            if (isFarsi) "📂 از فایل (‎.txt / .conf / .vpn / .yaml / .json)"
            else "📂 From a file (.txt / .conf / .vpn / .yaml / .json)",
        ).setOnMenuItemClickListener {
            appTools.openImportFilePicker()
            true
        }
        menu.add(
            if (isFarsi) "🔐 کلید Amnezia (vpn://) یا AmneziaWG"
            else "🔐 Amnezia key (vpn://) or AmneziaWG",
        ).setOnMenuItemClickListener {
            showAmneziaSectionDialog()
            true
        }
        menu.add(
            if (isFarsi) "⚙️ پیشرفته: کانفیگ Cat Warp دستی (MASQUE)"
            else "⚙️ Advanced: manual Cat Warp config (MASQUE)",
        ).setOnMenuItemClickListener {
            showAddAetherDialog()
            true
        }
        menu.add(
            if (isFarsi) "⚙️ پیشرفته: ساخت خودکار Cat (MASQUE + سایفون)"
            else "⚙️ Advanced: build Cat automatically (MASQUE + Psiphon)",
        ).setOnMenuItemClickListener {
            runOneTapPattNgMimPsiphonSetup()
            true
        }
        show()
    }
}

/**
 * Writes a Cat Warp config into «⚡ Cat Warp Configs». beta214: builders, the wizard and the country chips pass
 * [replaceAll] = true so the list holds exactly ONE config — the one just built for the chosen country — instead
 * of piling up «United States · Warp», «Germany · Warp», … rows. A link pasted from elsewhere is merged.
 * [onSaved] runs on the main thread after the subscription is written and selected, so a connect started from
 * it really uses the new config (it used to race the save and could start the previously selected list).
 */
internal fun MainActivity.saveAetherConfigIntoMainSubscriptionList(
    selection: AetherSelection,
    activateAetherEngine: Boolean = true,
    replaceAll: Boolean = true,
    onSaved: (() -> Unit)? = null,
) {
    val uri = selection.toAetherUri()
    val subName = CatWarpListPolicy.SUBSCRIPTION_NAME
    activityScope.launch {
        runCatching {
            val existing = userSubscriptionManager.list().firstOrNull {
                it.name.equals(subName, ignoreCase = true)
            }
            val saved = withContext(Dispatchers.IO) {
                if (existing != null) {
                    userSubscriptionManager.update(
                        existing.id,
                        subName,
                        CatWarpListPolicy.mergedInput(existing.input, uri, replaceAll),
                    )
                } else {
                    userSubscriptionManager.add(subName, uri)
                }
            }
            if (activateAetherEngine) {
                userSubscriptionManager.select(saved.id)
                // The new link is put first; drop an older picked entry so connect really uses the config just built.
                connectionSelectionPreferenceStore.saveSelectedProfile(saved.id, null, userPick = true)
                aetherPreferenceStore.save(selection.copy(enabled = true))
                renderSubscriptions()
                renderConnectionDetails(buttonModel.state)
                renderConnectionSelection()
                renderAdvancedControls()
            } else {
                renderSubscriptions()
            }
        }.onFailure { error ->
            DiagnosticLogger.warn(this@saveAetherConfigIntoMainSubscriptionList, "aether.list.save", error.message.orEmpty(), error)
        }
        onSaved?.invoke()
    }
}

/** Connects (or reconnects) after a Cat Warp config was written and selected. */
internal fun MainActivity.connectCatWarpAfterSave() {
    openScreen(SCREEN_HOME)
    if (currentVpnServiceState == VpnState.Started) {
        reconnectForConnectionOptionChange()
    } else {
        beginConnectFlow(Actions.CONNECT)
    }
}

internal fun MainActivity.addSubscriptionFromClipboard() {
    val rawClip = getSystemService(ClipboardManager::class.java).primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.trim()
        .orEmpty()
    AetherSelection.fromAetherUri(rawClip)?.let { parsed ->
        importAndConnectAetherSelection(parsed)
        return
    }
    if (AmneziaVpnKey.extractKeys(rawClip).isNotEmpty()) {
        previewAndImportAmneziaText(rawClip)
        return
    }
    val source = normalizedSubscriptionSource(rawClip)
    if (source == null) {
        Toast.makeText(this, R.string.subscription_clipboard_empty, Toast.LENGTH_SHORT).show()
    } else {
        showAddSubscriptionDialog(source, scannedSubscriptionName(source).orEmpty())
    }
}

internal fun MainActivity.importAndConnectAetherSelection(selection: AetherSelection) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val now = System.currentTimeMillis()
    val peer = selection.endpoint ?: when {
        selection.ipScan == "v6" && selection.mode == AetherMode.WireGuard -> "[2606:4700:d0::a29f:c001]:2408"
        selection.ipScan == "v6" -> "[2606:4700:d0::a29f:c001]:443"
        selection.mode == AetherMode.WireGuard -> "162.159.192.1:2408"
        else -> "162.159.198.1:443"
    }
    val innerPeer = selection.innerEndpoint ?: when (selection.mode) {
        AetherMode.MasqueInMasque ->
            if (peer.startsWith("[")) "[2606:4700:d0::a29f:c002]:443"
            else if (peer.startsWith("162.159.198.1:")) "162.159.198.2:443"
            else "162.159.198.1:443"
        AetherMode.WarpInWarp ->
            if (peer.startsWith("[")) "[2606:4700:d0::a29f:c002]:2408"
            else if (peer.startsWith("162.159.192.1:")) "162.159.193.1:2408"
            else "162.159.192.1:2408"
        else -> null
    }
    val ready = selection.copy(
        enabled = true,
        endpoint = peer,
        innerEndpoint = innerPeer,
        endpointScannedAt = now,
    )
    aetherPreferenceStore.save(ready)
    aetherPreferenceStore.saveNamedProfile(
        AetherSavedProfile(
            name = ready.profileName.ifBlank { "Cat Warp" },
            mode = ready.mode,
            ipScan = ready.ipScan,
            endpoint = peer,
            scannedAt = now,
            chainTarget = ready.chainTarget,
            exitLocation = ready.exitLocation,
            psiphonRegion = ready.psiphonRegion,
            masqueTransport = ready.masqueTransport,
            innerEndpoint = innerPeer.orEmpty(),
        ),
    )
    Toast.makeText(
        this,
        if (isFarsi) "🚀 کانفیگ Cat Warp «${ready.profileName}» فعال شد — در حال اتصال مستقیم در همین برنامه…"
        else "🚀 Cat Warp '${ready.profileName}' enabled — connecting directly in Cat Client…",
        Toast.LENGTH_LONG,
    ).show()
    saveAetherConfigIntoMainSubscriptionList(ready, activateAetherEngine = true, replaceAll = false) {
        connectCatWarpAfterSave()
    }
}

internal fun MainActivity.showAddSubscriptionDialog(initialSource: String = "", initialName: String = "") =
    showSubscriptionDialog(initialSource = initialSource, initialName = initialName)

/** Undo HTML-entity escaping (&amp; …) that survives copy/paste from Telegram and web pages. */
internal fun MainActivity.unescapeHtmlEntities(s: String): String = s
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")

/**
 * socks5/http link → panel FIXED EXIT (chain): every config the panel emits
 * then leaves through this server (stable IP & country). Panel picker +
 * pre-filled password, same as the scanner push.
 */
internal suspend fun MainActivity.pushChainToPanelSuspend(link: String) {
    val base = withContext(Dispatchers.Main) { pickPanelBaseInteractive() }?.trim()?.trimEnd('/')
    if (base.isNullOrBlank()) { Toast.makeText(this, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return }
    val storedPass = runCatching { PanelCredentials.defaultPassword(this, base) }.getOrNull().orEmpty()
    withContext(Dispatchers.Main) {
        val input = TextInputEditText(this@pushChainToPanelSuspend).apply {
            hint = getString(R.string.pip_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (storedPass.isNotBlank()) setText(storedPass)
        }
        val box = LinearLayout(this@pushChainToPanelSuspend).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        MaterialAlertDialogBuilder(this@pushChainToPanelSuspend)
            .setTitle(getString(R.string.chain_push_title, base.removePrefix("https://")))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chain_push_btn) { d, _ ->
                val password = input.text.toString()
                d.dismiss()
                activityScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { pushChainToPanelApi(base, password, link) } }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@pushChainToPanelSuspend, result.fold({ getString(R.string.chain_push_ok, link) }, { getString(R.string.chain_push_fail, it.message ?: "?") }), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }
}

internal fun MainActivity.pushChainToPanelApi(base: String, password: String, chain: String): Unit {
    fun call(path: String, method: String, body: String?, cookie: String?): Pair<java.net.HttpURLConnection, String> {
        val conn = java.net.URL("$base$path").openConnection() as java.net.HttpURLConnection
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
    val (put, putBody) = call("/api/settings", "PUT", org.json.JSONObject().put("chain", chain).toString(), cookie)
    val ok = put.responseCode == 200 && org.json.JSONObject(putBody).optBoolean("ok")
    put.disconnect()
    if (!ok) throw IllegalStateException("HTTP ${put.responseCode}")
}

internal fun MainActivity.showEditSubscriptionDialog(item: UserSubscription) = showSubscriptionDialog(item)

internal fun MainActivity.testSubscription(item: UserSubscription) {
    runSubscriptionAction(getString(R.string.subscription_testing, item.name)) {
        val imported = userSubscriptionManager.test(item.input)
        getString(R.string.subscription_found, connectionCountLabel(imported.connectionCount))
    }
}

internal fun MainActivity.refreshSubscription(item: UserSubscription) {
    runSubscriptionAction(
        startMessage = getString(R.string.subscription_refreshing, item.name),
        refreshProfiles = item.id == userSubscriptionManager.selectedId(),
    ) {
        val refreshed = userSubscriptionManager.refresh(item.id)
        getString(R.string.subscription_refreshed, connectionCountLabel(refreshed.connectionCount))
    }
}

internal fun MainActivity.refreshBuiltInSubscription(subscriptionId: String, name: String) {
    runSubscriptionAction(
        startMessage = getString(R.string.subscription_refreshing, name),
        refreshProfiles = userSubscriptionManager.selectedId() == subscriptionId,
    ) {
        val refreshed = ConfigRepository(this@refreshBuiltInSubscription).refreshBuiltInMihomoConfig(subscriptionId)
        getString(R.string.subscription_refreshed, connectionCountLabel(refreshed.catalog.profiles.size))
    }
}

internal fun MainActivity.runSubscriptionAction(
    startMessage: String,
    refreshProfiles: Boolean = false,
    action: suspend () -> String,
) {
    Toast.makeText(this, startMessage, Toast.LENGTH_SHORT).show()
    activityScope.launch {
        val result = runCatching { withContext(Dispatchers.IO) { action() } }
        renderSubscriptions()
        if (result.isSuccess && refreshProfiles) refreshLocationOptions()
        result.onSuccess { message -> Toast.makeText(this@runSubscriptionAction, message, Toast.LENGTH_SHORT).show() }
            .onFailure { error ->
                Toast.makeText(
                    this@runSubscriptionAction,
                    localizedError(error, R.string.subscription_operation_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
    }
}

internal fun MainActivity.collectVerifiedEndpointsForFailover(nowMs: Long = System.currentTimeMillis()): List<VerifiedEndpoint> {
    val out = ArrayList<VerifiedEndpoint>()
    IpHealthStore(this).entries()
        .filter { it.fails == 0 && it.port in 1..65535 }
        .forEach { e ->
            val cleanHost = e.ip.removePrefix("[").removeSuffix("]")
            val family = when {
                cleanHost.contains(':') -> EndpointFamily.IPV6
                Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(cleanHost) -> EndpointFamily.IPV4
                else -> EndpointFamily.DOMAIN
            }
            runCatching {
                out += VerifiedEndpoint(
                    host = cleanHost,
                    port = e.port,
                    role = EndpointRole.CLEAN_IP,
                    family = family,
                    verdict = EndpointVerdict.VERIFIED,
                    checkedAtMs = nowMs,
                    latencyMs = e.pingMs.takeIf { it > 0L },
                    countryCode = e.countryCode?.takeIf(String::isNotBlank),
                    source = "ip-health",
                )
            }
        }
    frontingIps.forEach { raw ->
        val parsed = runCatching { FrontingIpPolicy.parseEndpoint(raw) }.getOrNull() ?: return@forEach
        val port = parsed.port ?: return@forEach
        val cleanHost = parsed.ip.removePrefix("[").removeSuffix("]")
        val family = when {
            cleanHost.contains(':') -> EndpointFamily.IPV6
            Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(cleanHost) -> EndpointFamily.IPV4
            else -> EndpointFamily.DOMAIN
        }
        runCatching {
            out += VerifiedEndpoint(
                host = cleanHost,
                port = port,
                role = EndpointRole.CLEAN_IP,
                family = family,
                verdict = EndpointVerdict.VERIFIED,
                checkedAtMs = nowMs,
                source = "fronting-ip",
            )
        }
    }
    val orderedClean = VerifiedEndpointSelection.subscriptionEntries(out, nowMs, 86_400_000L)
    val combined = ArrayList<VerifiedEndpoint>(orderedClean.size + 64)
    combined += orderedClean
    combined += VerifiedProxyIpStore(this).verifiedEndpoints(nowMs).map { it.copy(checkedAtMs = nowMs) }
    combined += VerifiedRelayStore(this).verifiedEndpoints(nowMs).map { it.copy(checkedAtMs = nowMs) }
    return combined.distinctBy { Triple(it.role, it.host.lowercase(), it.port) }
}

internal fun MainActivity.rotateProfileRouteInSubscription(
    subscriptionId: String,
    profile: ConnectionProfile,
    preferProxyIp: Boolean = false,
): Pair<ConnectionProfile, Pair<String, String>>? {
    val link = profile.shareLink?.takeIf(String::isNotBlank) ?: return null
    val nowMs = System.currentTimeMillis()
    val candidates = collectVerifiedEndpointsForFailover(nowMs)
    val maxAgeMs = 86_400_000L
    val (rebuiltUri, failoverResult) = VerifiedConfigFailover.failoverUriWithResult(
        rawUri = link,
        candidates = candidates,
        nowMs = nowMs,
        maxAgeMs = maxAgeMs,
        preferProxyIp = preferProxyIp,
    ) ?: return null
    val updatedProfile = userSubscriptionManager.replaceProfileShareLink(
        subscriptionId = subscriptionId,
        targetTag = profile.tag,
        updatedShareLink = rebuiltUri,
        nowMs = nowMs,
    ) ?: return null
    val beforeLabel = failoverResult.previousAuthority.ifBlank { "${profile.server}:${profile.port}" }
    val afterLabel = failoverResult.selectedAuthority.ifBlank { "${updatedProfile.server}:${updatedProfile.port}" }
    return updatedProfile to (beforeLabel to afterLabel)
}

/**
 * Long-press menu on a connection row: copy / QR / share the `vless://`-style form of that one
 * connection, so a single working config can be moved to v2rayNG, V2Box, Streisand, etc.
 */
internal fun MainActivity.showConnectionShareMenu(
    anchor: View,
    profile: ConnectionProfile,
    onRotateAndRetest: ((preferProxyIp: Boolean) -> Unit)? = null,
    subscriptionId: String? = null,
) {
    val link = profile.shareLink?.takeIf(String::isNotBlank)
    if (link == null) {
        Toast.makeText(this, R.string.connection_share_unavailable, Toast.LENGTH_SHORT).show()
        return
    }
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val catWarpRow = CatWarpListPolicy.isCatWarpLink(link)
    val plainLocalRow = LoopbackProfileGuard.isPlainLoopback(profile.type, profile.server, link)
    catClientPopupMenu(anchor).apply {
        // beta214: Cat Warp rows get the endpoint scan and the editor here (users looked for it on the row).
        if (catWarpRow) {
            menu.add(if (isFarsi) "🔍 اسکن اندپوینت (پیدا کردن IP سالم روی خط شما)" else "🔍 Endpoint scan (find a working IP on your line)")
                .setOnMenuItemClickListener {
                    openCatWarpEditorForLink(subscriptionId, link, autoScan = true)
                    true
                }
            menu.add(if (isFarsi) "✏️ ویرایش این کانفیگ Cat Warp" else "✏️ Edit this Cat Warp config")
                .setOnMenuItemClickListener {
                    openCatWarpEditorForLink(subscriptionId, link, autoScan = false)
                    true
                }
        }
        if (plainLocalRow) {
            menu.add(if (isFarsi) "ℹ️ چرا این ردیف وصل نمی‌شود؟" else "ℹ️ Why does this row not connect?")
                .setOnMenuItemClickListener {
                    showDeadLocalRowExplanation(profile)
                    true
                }
        }
        // Route / ProxyIP switching rewrites a server IP:port — it has no effect on Cat Warp or local rows.
        if (onRotateAndRetest != null && !catWarpRow && !plainLocalRow && !LoopbackProfileGuard.isLoopbackHost(profile.server)) {
            menu.add(R.string.connection_action_change_route).setOnMenuItemClickListener {
                onRotateAndRetest(false)
                true
            }
            menu.add(R.string.connection_action_change_proxyip).setOnMenuItemClickListener {
                onRotateAndRetest(true)
                true
            }
        }
        menu.add(R.string.subscription_action_copy_link).setOnMenuItemClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("cat-config", link))
            Toast.makeText(this@showConnectionShareMenu, R.string.free_copied, Toast.LENGTH_SHORT).show()
            true
        }
        menu.add(R.string.subscription_action_qr).setOnMenuItemClickListener {
            showConfigQrCodes(listOf(link), profile.displayTag)
            true
        }
        menu.add(R.string.subscription_action_share).setOnMenuItemClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, profile.displayTag)
                putExtra(Intent.EXTRA_TEXT, link)
            }
            runCatching { startActivity(Intent.createChooser(send, profile.displayTag)) }
                .onFailure {
                    Toast.makeText(this@showConnectionShareMenu, R.string.subscription_share_failed, Toast.LENGTH_SHORT).show()
                }
            true
        }
        show()
    }
}

internal fun MainActivity.subscriptionLink(item: UserSubscription): String = item.input.trim()

internal fun MainActivity.copySubscriptionLink(item: UserSubscription) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("subscription", subscriptionLink(item)))
    Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.shareSubscriptionLink(item: UserSubscription) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, item.name)
        putExtra(Intent.EXTRA_TEXT, subscriptionLink(item))
    }
    runCatching { startActivity(Intent.createChooser(send, item.name)) }
        .onFailure { Toast.makeText(this, R.string.subscription_share_failed, Toast.LENGTH_SHORT).show() }
}

/**
 * Hands the subscription to another installed client through its URL scheme
 * (the same standard deep-link schemes used by common client pages). Falls back to copying the link.
 */
internal fun MainActivity.openSubscriptionInApp(item: UserSubscription, app: String) {
    val link = subscriptionLink(item)
    val encoded = Uri.encode(link)
    val name = Uri.encode(item.name)
    val target = when (app) {
        "v2rayng" -> "v2rayng://install-sub?url=$encoded&name=$name"
        "v2box" -> "v2box://install-sub?url=$encoded&name=$name"
        "hiddify" -> "hiddify://import/$link#${item.name}"
        else -> return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launched = runCatching { startActivity(intent); true }.getOrDefault(false)
    if (!launched) {
        copySubscriptionLink(item)
        Toast.makeText(this, getString(R.string.subscription_app_missing, app), Toast.LENGTH_LONG).show()
    }
}

/** Cat Panel per-user pages live at /info/<token>; other panels get the plain link. */
internal fun MainActivity.openSubscriptionInfoPage(item: UserSubscription) {
    val link = subscriptionLink(item)
    val info = Regex("^(https://[^/]+)/u/([^/?#]+)").find(link)
        ?.let { "${it.groupValues[1]}/info/${it.groupValues[2]}" }
        ?: link.substringBefore('#').let { if (it.contains('?')) "$it&web=1" else "$it?web=1" }
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info))) }
        .onFailure { copySubscriptionLink(item) }
}

internal fun MainActivity.confirmDeleteSubscription(item: UserSubscription) {
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.subscription_delete_title, item.name))
        .setMessage(R.string.subscription_delete_message)
        .setNegativeButton(R.string.split_tunnel_cancel, null)
        .setPositiveButton(R.string.subscription_action_delete) { _, _ ->
            userSubscriptionManager.delete(item.id)
            renderSubscriptions()
            refreshLocationOptions()
        }
        .create()
    dialog.showCatClientDialog(positiveColor = ERROR)
}

internal fun MainActivity.onSubscriptionSelected() {
    val selectedId = SubscriptionStore(this).readSelectedSubscriptionId()
    val cachedCatalog = if (SubscriptionStore.isBuiltInSubscription(selectedId)) {
        SubscriptionStore(this).readCatalog(selectedId)
    } else {
        userSubscriptionManager.cachedSnapshot(selectedId)?.catalog
    }
    val firstShare = cachedCatalog?.profiles?.firstOrNull()?.shareLink.orEmpty()
    val allAether = cachedCatalog != null &&
        cachedCatalog.profiles.isNotEmpty() &&
        cachedCatalog.profiles.all {
            val link = it.shareLink.orEmpty()
            link.startsWith("aether://", ignoreCase = true) || link.startsWith("masque://", ignoreCase = true)
        }
    val currentAether = aetherPreferenceStore.read()
    if (allAether) {
        AetherSelection.fromAetherUri(firstShare)?.let { parsed ->
            aetherPreferenceStore.save(parsed.copy(enabled = true))
            renderAdvancedControls()
        }
    } else if (currentAether.enabled) {
        aetherPreferenceStore.save(currentAether.copy(enabled = false))
        renderAdvancedControls()
    }
    locationPreferenceStore.clearSelectedCountry()
    if (cachedCatalog != null) {
        updateLocationOptions(cachedCatalog.profiles, resetMissingSelection = true)
    }
    renderSubscriptions()
    renderConnectionDetails(buttonModel.state)
    renderConnectionSelection()
    refreshLocationOptions()
    if (buttonModel.state == VpnState.Started) {
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        startVpnService(Actions.RECONNECT)
    }
}

internal fun MainActivity.connectionCountLabel(count: Int): String =
    resources.getQuantityString(R.plurals.connection_count, count, count)

internal fun MainActivity.localizedSubscriptionError(error: String): String = when {
    error.isBlank() -> ""
    else -> getString(R.string.subscription_operation_failed)
}

internal fun MainActivity.selectedSubscriptionName(): String {
    val store = SubscriptionStore(this)
    val selectedId = store.readSelectedSubscriptionId()
    return store.readUserSubscription(selectedId)?.name ?: builtInSubscriptionName(selectedId)
}

internal fun MainActivity.builtInSubscriptionName(id: String): String = getString(
    if (id == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID) {
        R.string.subscription_public_name
    } else {
        R.string.subscription_private_name
    },
)

internal fun MainActivity.showSubscriptionSelectorMenu(anchor: View) {
    val subscriptions = SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS.map {
        it to builtInSubscriptionName(it)
    } + userSubscriptionManager.list().map { it.id to it.name }
    val selectedId = userSubscriptionManager.selectedId()
    catClientPopupMenu(anchor).apply {
        subscriptions.forEachIndexed { index, (id, name) ->
            menu.add(0, SUBSCRIPTION_ITEM_ID_BASE + index, index, name).apply {
                isCheckable = true
                isChecked = id == selectedId
            }
        }
        menu.setGroupCheckable(0, true, true)
        setOnMenuItemClickListener { item ->
            val subscriptionId = subscriptions[item.itemId - SUBSCRIPTION_ITEM_ID_BASE].first
            if (subscriptionId != selectedId) {
                userSubscriptionManager.select(subscriptionId)
                onSubscriptionSelected()
            }
            true
        }
    }.show()
}
