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

// Connection testing page (ping/speed lists, chain picker).
// Moved out of MainActivity.kt unchanged (beta220 code split); see docs/CODEMAP.md.

internal fun MainActivity.showConnectionTestingPage(
    animate: Boolean = true,
    rebuild: Boolean = false,
    chainSlot: ConnectionChainSlot? = null,
    pickerSubscriptionId: String? = null,
) {
    if (connectionTestingPageVisible && !rebuild) return
    if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
    val subscriptionStore = SubscriptionStore(this)
    val pickerMode = chainSlot != null
    val pickerOptions = if (pickerMode) cachedConnectionChainPickerOptions() else emptyList()
    val configuredHop = chainSlot?.let { connectionChainPreferenceStore.read().hop(it) }
    val requestedSubscriptionId = if (pickerMode) {
        pickerSubscriptionId
            ?: configuredHop?.profileRef?.subscriptionId
            ?: subscriptionStore.readSelectedSubscriptionId()
    } else {
        subscriptionStore.readSelectedSubscriptionId()
    }
    val selectedSubscriptionId = if (
        pickerMode && pickerOptions.none { it.subscriptionId == requestedSubscriptionId }
    ) {
        pickerOptions.firstOrNull()?.subscriptionId ?: requestedSubscriptionId
    } else {
        requestedSubscriptionId
    }
    activeChainPickerSlot = chainSlot
    activeChainPickerSubscriptionId = selectedSubscriptionId.takeIf { pickerMode }
    if (!pickerMode && connectionProfiles.isEmpty()) {
        val cached = if (SubscriptionStore.isBuiltInSubscription(selectedSubscriptionId)) {
            subscriptionStore.readCatalog(selectedSubscriptionId)?.profiles
        } else {
            userSubscriptionManager.cachedSnapshot(selectedSubscriptionId)?.catalog?.profiles
        }
        if (!cached.isNullOrEmpty()) {
            connectionProfiles = cached
        }
    }
    val pageProfiles = if (pickerMode) {
        pickerOptions.filter { it.subscriptionId == selectedSubscriptionId }.map { it.profile }
    } else {
        connectionProfiles
    }
    val usesActiveRuntime = !pickerMode && buttonModel.state == VpnState.Started &&
        activeRuntimeSubscriptionId == selectedSubscriptionId
    val canRunConnectionTests = buttonModel.state != VpnState.Started ||
        (activeChainHopCount <= 1 && activeRuntimeSubscriptionId == selectedSubscriptionId)
    var selectorProfiles = if (usesActiveRuntime && liveSelectorReady && liveSelectableConnectionFingerprints.isNotEmpty()) {
        pageProfiles.filter { it.fingerprint in liveSelectableConnectionFingerprints }.ifEmpty { pageProfiles }
    } else {
        pageProfiles
    }
    val udpSupportByFingerprint = ConfigRepository(this)
        .readCachedMihomoConfigOrNullNow(selectedSubscriptionId)
        ?.let(ConnectionChainPlanner::udpSupportByFingerprint)
        .orEmpty()
    connectionDelayRecords = subscriptionStore
        .readConnectionDelayRecords(selectedSubscriptionId, selectorProfiles)
        .associateBy(ConnectionDelayRecord::fingerprint)
    val selectedProfile = if (pickerMode) {
        configuredHop
            ?.takeIf { it.mode == ConnectionChainHopMode.Fixed }
            ?.profileRef
            ?.takeIf { it.subscriptionId == selectedSubscriptionId }
            ?.let { ref -> selectorProfiles.firstOrNull { it.fingerprint == ref.fingerprint } }
    } else {
        connectionSelectionPreferenceStore.readSelectedProfile(selectedSubscriptionId, selectorProfiles)
    }
    val displayLocale = resources.configuration.locales[0]
    val allCountriesLabel = getString(R.string.connection_filter_all_countries)
    val countryOptions = ConnectionLocationPolicy.selectorOptions(
        profiles = selectorProfiles,
        automaticLabel = allCountriesLabel,
        displayLocale = displayLocale,
    )
    val selectableCountryOptions = countryOptions.filter { it.countryCode != null }
    val availableCountryCodes = selectableCountryOptions.mapNotNull(LocationSelectorOption::countryCode).toSet()
    val availableTypes = ConnectionTypeSelectionPolicy.availableTypes(selectorProfiles)
    val restoredSession = ConnectionDelayTestState.snapshot(selectedSubscriptionId)
    val profilesByFingerprint = selectorProfiles.associateBy(ConnectionProfile::fingerprint)
    val selectedCountryCodes = restoredSession
        ?.takeIf(ConnectionDelayTestSession::isRunning)
        ?.targetFingerprints
        ?.map { fingerprint ->
            profilesByFingerprint[fingerprint]
                ?.let { ConnectionLocationPolicy.countryForProfile(it, displayLocale) }
                ?.code
        }
        ?.filterNotNull()
        ?.toMutableSet()
        ?.takeIf { it.isNotEmpty() }
        ?: availableCountryCodes.toMutableSet()
    val selectedTypes = restoredSession
        ?.takeIf(ConnectionDelayTestSession::isRunning)
        ?.connectionTypes
        .orEmpty()
        .ifEmpty {
            connectionSelectionPreferenceStore
                .readAutomaticTypes(selectedSubscriptionId, selectorProfiles)
                .ifEmpty { availableTypes.toSet() }
        }
        .toMutableSet()
    val testingFingerprints = if (restoredSession?.isRunning == true) {
        (restoredSession.targetFingerprints - restoredSession.finishedFingerprints).toMutableSet()
    } else {
        mutableSetOf<String>()
    }
    val speedTests = ConnectionSpeedTestState.runningSessions(selectedSubscriptionId)
        .associateBy(ConnectionSpeedTestSession::fingerprint)
        .toMutableMap()
    var speedTestRunning = speedTests.isNotEmpty()
    fun selectedTypesLabel(): String = when {
        selectedTypes.size == availableTypes.size -> getString(R.string.connection_filter_all_types)
        selectedTypes.size == 1 -> selectedTypes.first().uppercase(Locale.US)
        else -> getString(R.string.connection_filter_types_count, selectedTypes.size)
    }
    fun selectedCountryLabel(): String = when {
        selectedCountryCodes == availableCountryCodes -> allCountriesLabel
        selectedCountryCodes.size == 1 -> selectableCountryOptions
            .firstOrNull { it.countryCode in selectedCountryCodes }
            ?.label
            ?: allCountriesLabel
        else -> getString(R.string.connection_filter_countries_count, selectedCountryCodes.size)
    }
    fun visibleProfiles(): List<ConnectionProfile> {
        val matchingCountry = if (selectedCountryCodes == availableCountryCodes) {
            selectorProfiles
        } else {
            selectorProfiles.filter { profile ->
                ConnectionLocationPolicy.countryForProfile(profile, displayLocale)?.code in selectedCountryCodes
            }
        }
        val matchingType = ConnectionTypeSelectionPolicy.filterProfiles(
            matchingCountry,
            selectedTypes,
        )
        return ConnectionTestResultOrder.order(
            profiles = matchingType,
            records = connectionDelayRecords,
            pendingFingerprints = testingFingerprints,
        )
    }
    var filteredProfiles = visibleProfiles()
    var testRunning = restoredSession?.isRunning == true
    var testPaused = restoredSession?.paused == true
    var lastTestCompleted = restoredSession?.completed ?: 0
    var lastTestTotal = restoredSession?.total ?: 0
    var lastTestAvailable = restoredSession?.available ?: 0
    var lastTestStatus = restoredSession?.status
    var lastTestError = restoredSession?.error.orEmpty()
    var delayTestId: String? = restoredSession?.testId

    data class ConnectionRowHolder(
        val container: LinearLayout,
        val title: TextView,
        val detail: TextView,
        val delayBadge: TextView,
        val protocolBadges: LinearLayout,
        val udpBadge: TextView,
        val checkIcon: View,
        val speedAction: FrameLayout,
        val speedButton: TextView,
    )

    val content = MaxWidthLinearLayout(this).apply {
        maxWidthPx = dp(640)
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(104))
    }

    val backButton = ImageButton(this).apply {
        setImageResource(R.drawable.ic_arrow_back)
        imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
        setBackgroundColor(Color.TRANSPARENT)
        setSelectableBackground()
        contentDescription = getString(
            if (pickerMode) R.string.connection_chain_picker_back else R.string.connection_testing_back,
        )
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { closeConnectionTestingPage() }
    }
    val pageTitle = TextView(this).apply {
        setText(
            if (pickerMode) R.string.connection_chain_picker_title
            else R.string.connection_testing_page_title,
        )
        textSize = 24f
        typeface = CatClientDisplayTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
    }
    val settings = connectionTestSettingsPreferenceStore.read()
    val pageConfig = TextView(this).apply {
        text = if (pickerMode) {
            getString(R.string.connection_chain_picker_detail)
        } else {
            getString(
                R.string.connection_testing_page_config,
                settings.timeoutSeconds,
                settings.concurrency,
                settings.speedTestMegabytes,
            ) + (if (pageProfiles.any { it.shareLink != null }) " · " + getString(R.string.connection_share_hint) else "")
        }
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEAL)
        includeFontPadding = false
    }
    val headerCopy = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        addView(pageTitle, LinearLayout.LayoutParams(-1, -2))
        addView(pageConfig, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    val headerRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.TOP
        addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        addView(headerCopy, LinearLayout.LayoutParams(0, -2, 1f))
    }
    val headerRule = View(this).apply { setBackgroundColor(OUTLINE) }

    // Filter row - horizontal with chips
    val filterRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun filterChip(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), OUTLINE)
            setColor(SURFACE)
        }
    }

    val countryFilterButton = filterChip(allCountriesLabel)
    val typeFilterButton = filterChip(getString(R.string.connection_filter_all_types))
    val pickerSubscriptions = pickerOptions.distinctBy(ConnectionChainPickerOption::subscriptionId)
    val subscriptionFilterButton = if (pickerMode) {
        filterChip(
            getString(
                R.string.connection_chain_subscription,
                pickerSubscriptions.firstOrNull { it.subscriptionId == selectedSubscriptionId }
                    ?.subscriptionName
                    ?: selectedSubscriptionId,
            ),
        ).apply {
            isClickable = pickerSubscriptions.size > 1
            isFocusable = pickerSubscriptions.size > 1
            alpha = if (pickerSubscriptions.size > 1) 1f else 0.7f
            if (pickerSubscriptions.size > 1) setOnClickListener {
                val labels = pickerSubscriptions.map(ConnectionChainPickerOption::subscriptionName).toTypedArray()
                val checked = pickerSubscriptions.indexOfFirst {
                    it.subscriptionId == selectedSubscriptionId
                }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(this@showConnectionTestingPage)
                    .setTitle(R.string.connection_chain_subscription_title)
                    .setSingleChoiceItems(labels, checked) { dialog, which ->
                        val selected = pickerSubscriptions[which]
                        dialog.dismiss()
                        showConnectionTestingPage(
                            animate = false,
                            rebuild = true,
                            chainSlot = chainSlot,
                            pickerSubscriptionId = selected.subscriptionId,
                        )
                    }
                    .setNegativeButton(R.string.split_tunnel_cancel, null)
                    .create()
                    .showCatClientDialog()
            }
        }
    } else {
        null
    }

    filterRow.addView(countryFilterButton, LinearLayout.LayoutParams(0, dp(48), 1f))
    filterRow.addView(typeFilterButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })

    // Progress section
    val progressSection = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 100
        progress = 0
        progressTintList = ColorStateList.valueOf(TEAL)
        progressBackgroundTintList = ColorStateList.valueOf(OUTLINE)
        minimumHeight = dp(4)
    }
    val testStatus = TextView(this).apply {
        textSize = 13f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_SECONDARY)
        includeFontPadding = false
        gravity = Gravity.CENTER
    }
    progressSection.addView(progressBar, LinearLayout.LayoutParams(-1, dp(4)))
    progressSection.addView(testStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    // Control buttons
    val testButton = MaterialButton(this).apply {
        setText(R.string.connection_test_visible)
        setIconResource(R.drawable.ic_connection_test)
        iconTint = ColorStateList.valueOf(BACKGROUND)
        iconSize = dp(18)
        iconPadding = dp(8)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(24)
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(BACKGROUND)
        setAllCaps(false)
    }

    val controlRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val pauseButton = MaterialButton(this).apply {
        setIconResource(R.drawable.ic_pause)
        iconTint = ColorStateList.valueOf(TEAL)
        iconSize = dp(20)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        iconPadding = 0
        text = ""
        minWidth = dp(48)
        minimumWidth = dp(48)
        minHeight = dp(48)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(24)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(OUTLINE)
        backgroundTintList = ColorStateList.valueOf(SURFACE)
        visibility = View.GONE
        contentDescription = getString(R.string.connection_test_pause)
        setPadding(0, 0, 0, 0)
    }

    val stopButton = MaterialButton(this).apply {
        setText(R.string.connection_test_stop)
        textSize = 13f
        typeface = CatClientBodyBoldTypeface
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(24)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(ERROR)
        backgroundTintList = ColorStateList.valueOf(SURFACE)
        setTextColor(ERROR)
        setAllCaps(false)
        visibility = View.GONE
    }

    stopButton.setPadding(dp(20), 0, dp(20), 0)
    controlRow.addView(testButton, LinearLayout.LayoutParams(0, dp(48), 1f))
    controlRow.addView(pauseButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
    controlRow.addView(stopButton, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) })

    // Server list with better styling
    val list = ListView(this).apply {
        divider = ColorDrawable(Color.TRANSPARENT)
        dividerHeight = dp(8)
        isVerticalScrollBarEnabled = true
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(4))
    }

    fun protocolBadge(@StringRes label: Int) = TextView(this).apply {
        setText(label)
        textSize = 10f
        typeface = CatClientDataTypeface
        setTextColor(TEAL)
        includeFontPadding = false
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
        setPadding(dp(6), dp(2), dp(6), dp(2))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(withAlpha(TEAL, 24))
            setStroke(dp(1), withAlpha(TEAL, 90))
        }
    }

    val adapter = object : BaseAdapter() {
        override fun getCount(): Int = filteredProfiles.size + 1

        override fun getItem(position: Int): Any? =
            if (position == 0) null else filteredProfiles[position - 1]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: LinearLayout
            val holder: ConnectionRowHolder
            if (convertView == null) {
                val container = LinearLayout(this@showConnectionTestingPage).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    gravity = Gravity.CENTER_VERTICAL
                }
                val title = TextView(this@showConnectionTestingPage).apply {
                    textSize = 14f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val detail = TextView(this@showConnectionTestingPage).apply {
                    textSize = 12f
                    typeface = CatClientDataTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val delayBadge = TextView(this@showConnectionTestingPage).apply {
                    textSize = 12.5f
                    typeface = CatClientBodyBoldTypeface
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    visibility = View.GONE
                }
                val tcpBadge = protocolBadge(R.string.connection_protocol_tcp)
                val udpBadge = protocolBadge(R.string.connection_protocol_udp)
                val protocolBadges = LinearLayout(this@showConnectionTestingPage).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    gravity = Gravity.CENTER_VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    addView(tcpBadge, LinearLayout.LayoutParams(-2, -2))
                    addView(
                        udpBadge,
                        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) },
                    )
                }
                val speedButton = TextView(this@showConnectionTestingPage).apply {
                    setText(R.string.connection_speed_test_label)
                    textSize = 11f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEAL)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    compoundDrawablePadding = dp(4)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_speedometer, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(TEAL)
                    setPadding(dp(8), 0, dp(8), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor((TEAL and 0x00FFFFFF) or (0x18 shl 24))
                    }
                    visibility = View.GONE
                }
                val speedAction = FrameLayout(this@showConnectionTestingPage).apply {
                    visibility = View.GONE
                    isClickable = true
                    isFocusable = true
                    setSelectableBackground()
                    addView(speedButton, FrameLayout.LayoutParams(-2, dp(32), Gravity.CENTER))
                }
                // Selection indicator (small dot)
                val checkIcon = View(this@showConnectionTestingPage).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(TEAL)
                    }
                    visibility = View.INVISIBLE
                }
                val textColumn = LinearLayout(this@showConnectionTestingPage).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(title, LinearLayout.LayoutParams(-1, -2))
                    addView(
                        LinearLayout(this@showConnectionTestingPage).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                protocolBadges,
                                LinearLayout.LayoutParams(-2, -2),
                            )
                            addView(
                                detail,
                                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) },
                            )
                        },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) },
                    )
                }
                // ZedSecure row: text column · latency on the trailing edge · speed test · check.
                container.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
                container.addView(delayBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                container.addView(speedAction, LinearLayout.LayoutParams(dp(96), dp(56)).apply { marginStart = dp(4) })
                container.addView(checkIcon, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginStart = dp(8) })

                row = LinearLayout(this@showConnectionTestingPage).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(14), dp(12))
                    addView(container, LinearLayout.LayoutParams(-1, -2))
                }
                // Card-like background (low surface container; lime when selected)
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(palette.surfaceElevated1)
                }
                holder = ConnectionRowHolder(
                    container,
                    title,
                    detail,
                    delayBadge,
                    protocolBadges,
                    udpBadge,
                    checkIcon,
                    speedAction,
                    speedButton,
                )
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as ConnectionRowHolder
            }

            val profile = getItem(position) as? ConnectionProfile
            val isSelected = if (profile == null) {
                if (pickerMode) {
                    configuredHop?.mode == ConnectionChainHopMode.Automatic
                } else {
                    selectedProfile == null
                }
            } else {
                selectedProfile?.fingerprint == profile.fingerprint
            }

            // Update selection state
            holder.checkIcon.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            (holder.checkIcon.background as? GradientDrawable)?.setColor(TEAL)
            (row.background as? GradientDrawable)?.setColor(if (isSelected) selectedRowColor() else palette.surfaceElevated1)
            (row.background as? GradientDrawable)?.setStroke(0, Color.TRANSPARENT)
            holder.title.setTextColor(TEXT_PRIMARY)
            holder.detail.setTextColor(TEXT_SECONDARY)
            row.isSelected = isSelected
            var protocolDescription: String? = null

            if (profile == null) {
                holder.title.setText(R.string.option_automatic)
                val automaticDetail = if (pickerMode) {
                    getString(R.string.connection_chain_automatic_detail)
                } else if (selectedTypes.size == availableTypes.size) {
                    getString(R.string.connection_automatic_detail)
                } else {
                    getString(R.string.connection_automatic_types_detail, selectedTypesLabel())
                }
                holder.detail.text = activeConnectionTag
                    .takeIf { usesActiveRuntime && it.isNotBlank() }
                    ?.let { getString(R.string.connection_automatic_active_detail, automaticDetail, it) }
                    ?: automaticDetail
                holder.delayBadge.text = ""
                holder.delayBadge.visibility = View.GONE
                holder.delayBadge.background = null
                holder.protocolBadges.visibility = View.GONE
                holder.speedAction.visibility = View.GONE
            } else {
                holder.title.text = profile.displayTag
                holder.protocolBadges.visibility = View.VISIBLE
                val udpSupport = udpSupportByFingerprint[profile.fingerprint]
                val udpColor = when (udpSupport) {
                    true -> TEAL
                    false -> TEXT_SECONDARY
                    null -> AMBER
                }
                holder.udpBadge.setText(
                    when (udpSupport) {
                        true -> R.string.connection_protocol_udp
                        false -> R.string.connection_protocol_udp_unavailable
                        null -> R.string.connection_protocol_udp_unknown
                    },
                )
                holder.udpBadge.setTextColor(udpColor)
                holder.udpBadge.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(withAlpha(udpColor, 24))
                    setStroke(dp(1), withAlpha(udpColor, 90))
                }
                protocolDescription = getString(
                    when (udpSupport) {
                        true -> R.string.connection_protocol_support_tcp_udp
                        false -> R.string.connection_protocol_support_tcp_only
                        null -> R.string.connection_protocol_support_udp_unknown
                    },
                )
                holder.detail.text = if (SubscriptionStore.isBuiltInSubscription(selectedSubscriptionId)) {
                    profile.type.uppercase(Locale.US)
                } else {
                    getString(
                        R.string.connection_detail,
                        profile.type.uppercase(Locale.US),
                        profile.server,
                        profile.port,
                    )
                }
                val delayRecord = connectionDelayRecords[profile.fingerprint]
                val delayMs: Int? = if (delayRecord?.status == ConnectionDelayStatus.Success) {
                    delayRecord.delayMs
                } else {
                    null
                }
                val speedKbps = delayRecord?.speedKbps?.takeUnless { profile.fingerprint in speedTests }
                val isTesting = profile.fingerprint in testingFingerprints
                val isSpeedTesting = profile.fingerprint in speedTests

                holder.delayBadge.text = when {
                    isTesting -> getString(R.string.connection_delay_testing)
                    speedKbps != null && delayMs != null -> getString(
                        R.string.connection_speed_delay_value,
                        speedKbps / 1_000.0,
                        delayMs,
                    )
                    delayMs != null -> getString(R.string.connection_delay_value, delayMs)
                    delayRecord?.status == ConnectionDelayStatus.Failure ->
                        getString(R.string.connection_delay_unavailable)
                    else -> ""
                }

                // Latency colour like ZedSecure: lime when quick, amber when sluggish, red when slow/failed.
                val badgeColor = when {
                    isTesting -> AMBER
                    delayMs != null && delayMs <= 200 -> ZedBlobView.ZED_LIME
                    delayMs != null && delayMs <= 500 -> AMBER
                    delayMs != null -> ERROR
                    delayRecord?.status == ConnectionDelayStatus.Failure -> ERROR
                    else -> TEXT_SECONDARY
                }
                if (holder.delayBadge.text.isNotEmpty()) {
                    holder.delayBadge.visibility = View.VISIBLE
                    holder.delayBadge.setTextColor(badgeColor)
                    holder.delayBadge.background = null
                } else {
                    holder.delayBadge.visibility = View.GONE
                    holder.delayBadge.background = null
                }

                fun triggerFailoverAndRetest(targetProfile: ConnectionProfile, preferProxyIp: Boolean) {
                    if (!canRunConnectionTests || testRunning) return
                    val rotated = rotateProfileRouteInSubscription(selectedSubscriptionId, targetProfile, preferProxyIp)
                    val profileToTest = if (rotated != null) {
                        val (updatedProfile, beforeAfter) = rotated
                        selectorProfiles = selectorProfiles.map { if (it.tag == targetProfile.tag) updatedProfile else it }
                        if (!pickerMode && selectedSubscriptionId == subscriptionStore.readSelectedSubscriptionId()) {
                            connectionProfiles = connectionProfiles.map { if (it.tag == targetProfile.tag) updatedProfile else it }
                        }
                        connectionDelayRecords = connectionDelayRecords - targetProfile.fingerprint - updatedProfile.fingerprint
                        Toast.makeText(
                            this@showConnectionTestingPage,
                            getString(R.string.connection_route_switched, beforeAfter.first, beforeAfter.second),
                            Toast.LENGTH_SHORT,
                        ).show()
                        updatedProfile
                    } else {
                        connectionDelayRecords = connectionDelayRecords - targetProfile.fingerprint
                        Toast.makeText(
                            this@showConnectionTestingPage,
                            R.string.connection_route_no_candidates,
                            Toast.LENGTH_SHORT,
                        ).show()
                        targetProfile
                    }
                    testingFingerprints.clear()
                    testingFingerprints.add(profileToTest.fingerprint)
                    filteredProfiles = visibleProfiles()
                    testRunning = true
                    testPaused = false
                    lastTestCompleted = 0
                    lastTestTotal = 1
                    lastTestAvailable = 0
                    lastTestStatus = null
                    lastTestError = ""
                    val newTestId = SystemClock.elapsedRealtimeNanos().toString()
                    delayTestId = newTestId
                    notifyDataSetChanged()
                    startForegroundService(
                        Intent(this@showConnectionTestingPage, CatClientVpnService::class.java)
                            .setAction(Actions.TEST_CONNECTION_DELAYS)
                            .putExtra(Actions.EXTRA_APP_INITIATED, true)
                            .putExtra(Actions.EXTRA_DELAY_TEST_ID, newTestId)
                            .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, selectedSubscriptionId)
                            .putStringArrayListExtra(Actions.EXTRA_CONNECTION_FINGERPRINTS, arrayListOf(profileToTest.fingerprint))
                            .putStringArrayListExtra(Actions.EXTRA_CONNECTION_TYPES, ArrayList(selectedTypes.sorted())),
                    )
                }

                val isFailedWithShareLink = delayMs == null &&
                    delayRecord?.status == ConnectionDelayStatus.Failure &&
                    !profile.shareLink.isNullOrBlank()
                holder.speedAction.visibility = if (delayMs != null || isFailedWithShareLink) View.VISIBLE else View.GONE
                holder.speedButton.visibility = View.VISIBLE
                holder.speedButton.setText(
                    when {
                        isSpeedTesting -> R.string.connection_test_stop
                        isFailedWithShareLink -> R.string.connection_failover_retest_label
                        else -> R.string.connection_speed_test_label
                    },
                )
                holder.speedButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    when {
                        isSpeedTesting -> R.drawable.ic_stop
                        isFailedWithShareLink -> 0
                        else -> R.drawable.ic_speedometer
                    },
                    0,
                    0,
                    0,
                )
                holder.speedAction.isEnabled = isSpeedTesting ||
                    (canRunConnectionTests && (delayMs != null || isFailedWithShareLink) && !testRunning)
                holder.speedAction.alpha = if (holder.speedAction.isEnabled) 1f else 0.45f
                holder.speedAction.contentDescription = when {
                    isSpeedTesting -> "${getString(R.string.connection_test_stop)}: ${profile.displayTag}"
                    isFailedWithShareLink -> "${getString(R.string.connection_failover_retest_label)}: ${profile.displayTag}"
                    else -> getString(R.string.connection_speed_test_action, profile.displayTag)
                }
                holder.speedAction.setOnClickListener {
                    speedTests[profile.fingerprint]?.let { running ->
                        startService(
                            Intent(this@showConnectionTestingPage, CatClientVpnService::class.java)
                                .setAction(Actions.CANCEL_CONNECTION_SPEED_TEST)
                                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                                .putExtra(Actions.EXTRA_SPEED_TEST_ID, running.testId),
                        )
                        return@setOnClickListener
                    }
                    if (isFailedWithShareLink) {
                        triggerFailoverAndRetest(profile, preferProxyIp = false)
                        return@setOnClickListener
                    }
                    if (!canRunConnectionTests || testRunning || delayMs == null) {
                        return@setOnClickListener
                    }
                    delayRecord?.copy(speedKbps = null)?.let { clearedRecord ->
                        connectionDelayRecords = connectionDelayRecords +
                            (profile.fingerprint to clearedRecord)
                    }
                    val speedTestId = SystemClock.elapsedRealtimeNanos().toString()
                    speedTests[profile.fingerprint] = ConnectionSpeedTestSession(
                        speedTestId, selectedSubscriptionId, profile.fingerprint,
                    )
                    speedTestRunning = true
                    notifyDataSetChanged()
                    testButton.isEnabled = false
                    testButton.alpha = 0.5f
                    typeFilterButton.isEnabled = false
                    typeFilterButton.alpha = 0.5f
                    countryFilterButton.isEnabled = false
                    countryFilterButton.alpha = 0.5f
                    startForegroundService(
                        Intent(this@showConnectionTestingPage, CatClientVpnService::class.java)
                            .setAction(Actions.TEST_CONNECTION_SPEED)
                            .putExtra(Actions.EXTRA_APP_INITIATED, true)
                            .putExtra(Actions.EXTRA_SPEED_TEST_ID, speedTestId)
                            .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, selectedSubscriptionId)
                            .putExtra(Actions.EXTRA_CONNECTION_FINGERPRINT, profile.fingerprint),
                    )
                }
            }

            row.contentDescription = listOfNotNull(
                holder.title.text?.toString()?.takeIf(String::isNotBlank),
                holder.detail.text?.toString()?.takeIf(String::isNotBlank),
                protocolDescription,
                holder.delayBadge.text?.toString()?.takeIf(String::isNotBlank),
            ).joinToString(". ")
            row.setOnClickListener {
                if (speedTestRunning) return@setOnClickListener
                if (pickerMode) {
                    val current = connectionChainPreferenceStore.read()
                    // Choosing a different config for a hop must not throw away an address
                    // the user pinned on that hop: the pin is about the leg, not the config.
                    val pinned = current.hop(chainSlot!!).serverOverride
                    val hop = if (profile == null) {
                        ConnectionChainHop.automatic().copy(serverOverride = pinned)
                    } else {
                        ConnectionChainHop.fixed(
                            selectedSubscriptionId,
                            profile.fingerprint,
                            serverOverride = pinned,
                        )
                    }
                    val updated = current.withHop(chainSlot!!, hop)
                    updateConnectionChainSettings(updated)
                    if (current != updated) {
                        pendingConnectionChainAccessibilityAnnouncement =
                            connectionChainCompatibilityMessage
                    }
                } else {
                    handleConnectionSelected(profile, selectedTypes)
                }
                closeConnectionTestingPage()
            }
            row.setOnLongClickListener {
                val target = profile ?: return@setOnLongClickListener false
                showConnectionShareMenu(
                    anchor = row,
                    profile = target,
                    subscriptionId = selectedSubscriptionId,
                    onRotateAndRetest = if (!pickerMode && canRunConnectionTests) { preferProxyIp ->
                        if (!testRunning) {
                            val rotated = rotateProfileRouteInSubscription(selectedSubscriptionId, target, preferProxyIp)
                            val profileToTest = if (rotated != null) {
                                val (updatedProfile, beforeAfter) = rotated
                                selectorProfiles = selectorProfiles.map { if (it.tag == target.tag) updatedProfile else it }
                                if (selectedSubscriptionId == subscriptionStore.readSelectedSubscriptionId()) {
                                    connectionProfiles = connectionProfiles.map { if (it.tag == target.tag) updatedProfile else it }
                                }
                                connectionDelayRecords = connectionDelayRecords - target.fingerprint - updatedProfile.fingerprint
                                Toast.makeText(
                                    this@showConnectionTestingPage,
                                    getString(R.string.connection_route_switched, beforeAfter.first, beforeAfter.second),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                updatedProfile
                            } else {
                                connectionDelayRecords = connectionDelayRecords - target.fingerprint
                                Toast.makeText(
                                    this@showConnectionTestingPage,
                                    R.string.connection_route_no_candidates,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                target
                            }
                            testingFingerprints.clear()
                            testingFingerprints.add(profileToTest.fingerprint)
                            filteredProfiles = visibleProfiles()
                            testRunning = true
                            testPaused = false
                            lastTestCompleted = 0
                            lastTestTotal = 1
                            lastTestAvailable = 0
                            lastTestStatus = null
                            lastTestError = ""
                            val newTestId = SystemClock.elapsedRealtimeNanos().toString()
                            delayTestId = newTestId
                            notifyDataSetChanged()
                            startForegroundService(
                                Intent(this@showConnectionTestingPage, CatClientVpnService::class.java)
                                    .setAction(Actions.TEST_CONNECTION_DELAYS)
                                    .putExtra(Actions.EXTRA_APP_INITIATED, true)
                                    .putExtra(Actions.EXTRA_DELAY_TEST_ID, newTestId)
                                    .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, selectedSubscriptionId)
                                    .putStringArrayListExtra(Actions.EXTRA_CONNECTION_FINGERPRINTS, arrayListOf(profileToTest.fingerprint))
                                    .putStringArrayListExtra(Actions.EXTRA_CONNECTION_TYPES, ArrayList(selectedTypes.sorted())),
                            )
                        }
                    } else null,
                )
                true
            }
            row.isLongClickable = profile?.shareLink != null
            return row
        }
    }
    list.adapter = adapter

    fun updateTestControls() {
        // Test button state
        testButton.isEnabled = canRunConnectionTests && filteredProfiles.isNotEmpty() &&
            !speedTestRunning && (!testRunning || testPaused)
        testButton.alpha = if (testButton.isEnabled) 1f else 0.5f
        val hasResults = filteredProfiles.any { it.fingerprint in connectionDelayRecords }
        testButton.setText(
            if (testPaused || hasResults) R.string.connection_test_again else R.string.connection_test_visible
        )
        // Show/hide test button based on state
        testButton.visibility = if (testRunning && !testPaused) View.GONE else View.VISIBLE

        // Pause button
        pauseButton.visibility = if (testRunning) View.VISIBLE else View.GONE
        pauseButton.setIconResource(if (testPaused) R.drawable.ic_play else R.drawable.ic_pause)
        pauseButton.contentDescription = getString(
            if (testPaused) R.string.connection_test_resume else R.string.connection_test_pause,
        )

        // Stop button
        stopButton.visibility = if (testRunning) View.VISIBLE else View.GONE

        // Filter chips
        typeFilterButton.isEnabled = !testRunning && !speedTestRunning && availableTypes.isNotEmpty()
        typeFilterButton.alpha = if (typeFilterButton.isEnabled) 1f else 0.5f
        typeFilterButton.text = selectedTypesLabel()
        (typeFilterButton.background as? GradientDrawable)?.setStroke(
            dp(1), if (selectedTypes.size < availableTypes.size) TEAL else OUTLINE
        )

        countryFilterButton.isEnabled = !testRunning && !speedTestRunning && countryOptions.size > 1
        countryFilterButton.alpha = if (countryFilterButton.isEnabled) 1f else 0.5f
        countryFilterButton.text = selectedCountryLabel()
        (countryFilterButton.background as? GradientDrawable)?.setStroke(
            dp(1), if (selectedCountryCodes != availableCountryCodes) TEAL else OUTLINE
        )

        // Progress section
        val failed = lastTestStatus == Actions.DELAY_TEST_FAILED

        progressSection.visibility = if (testRunning || lastTestStatus != null) View.VISIBLE else View.GONE

        // Update progress bar
        val progressPercent = if (lastTestTotal > 0) (lastTestCompleted * 100) / lastTestTotal else 0
        progressBar.progress = progressPercent
        progressBar.progressTintList = ColorStateList.valueOf(
            when {
                failed -> ERROR
                testPaused -> AMBER
                else -> TEAL
            }
        )

        // Status text
        testStatus.setTextColor(if (failed) ERROR else TEXT_SECONDARY)
        testStatus.text = when {
            selectorProfiles.isEmpty() -> getString(R.string.connection_empty)
            testRunning && lastTestTotal == 0 -> getString(R.string.connection_test_preparing)
            testRunning && testPaused ->
                getString(R.string.connection_test_paused, lastTestCompleted, lastTestTotal)
            testRunning ->
                getString(R.string.connection_test_progress, lastTestCompleted, lastTestTotal)
            lastTestStatus == Actions.DELAY_TEST_COMPLETED ->
                getString(R.string.connection_test_complete, lastTestAvailable, lastTestTotal)
            failed -> lastTestError.ifBlank { getString(R.string.connection_test_failed) }
            lastTestStatus == Actions.DELAY_TEST_CANCELED ->
                getString(R.string.connection_test_canceled)
            else -> ""
        }
    }

    fun filterBulkButton(@StringRes textRes: Int) = MaterialButton(this).apply {
        setText(textRes)
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEAL)
        setAllCaps(false)
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(24)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(OUTLINE)
        backgroundTintList = ColorStateList.valueOf(SURFACE)
    }

    typeFilterButton.setOnClickListener {
        val draft = selectedTypes.toMutableSet()
        val typeChecks = mutableListOf<MaterialCheckBox>()
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val bulkActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                filterBulkButton(R.string.connection_filter_select_all).apply {
                    setOnClickListener {
                        draft.clear()
                        draft += availableTypes
                        typeChecks.forEach { it.isChecked = true }
                    }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f),
            )
            addView(
                filterBulkButton(R.string.connection_filter_deselect_all).apply {
                    setOnClickListener {
                        draft.clear()
                        typeChecks.forEach { it.isChecked = false }
                    }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) },
            )
        }
        availableTypes.forEach { type ->
            val check = MaterialCheckBox(this).apply {
                text = type.uppercase(Locale.US)
                textSize = 14f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_PRIMARY)
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                isUseMaterialThemeColors = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(TEAL, TEXT_SECONDARY),
                )
                isChecked = type in draft
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) draft += type else draft -= type
                }
            }
            typeChecks += check
            choices.addView(
                check,
                LinearLayout.LayoutParams(-1, dp(48)),
            )
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(bulkActions, LinearLayout.LayoutParams(-1, dp(48)))
            addView(
                ScrollView(this@showConnectionTestingPage).apply { addView(choices) },
                LinearLayout.LayoutParams(
                    -1,
                    minOf(
                        availableTypes.size.coerceAtLeast(1) * dp(48),
                        (resources.displayMetrics.heightPixels * 0.42f).toInt(),
                    ),
                ).apply { topMargin = dp(8) },
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_filter_types_title)
            .setView(dialogContent)
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .setPositiveButton(R.string.split_tunnel_save) { _, _ ->
                selectedTypes.clear()
                selectedTypes += draft
                filteredProfiles = visibleProfiles()
                adapter.notifyDataSetChanged()
                updateTestControls()
            }
            .create()
            .showCatClientDialog()
    }

    countryFilterButton.setOnClickListener {
        val draft = selectedCountryCodes.toMutableSet()
        val countryChecks = mutableListOf<MaterialCheckBox>()
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val bulkActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                filterBulkButton(R.string.connection_filter_select_all).apply {
                    setOnClickListener {
                        draft.clear()
                        draft += availableCountryCodes
                        countryChecks.forEach { it.isChecked = true }
                    }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f),
            )
            addView(
                filterBulkButton(R.string.connection_filter_deselect_all).apply {
                    setOnClickListener {
                        draft.clear()
                        countryChecks.forEach { it.isChecked = false }
                    }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) },
            )
        }
        selectableCountryOptions.forEach { option ->
            val code = option.countryCode ?: return@forEach
            val check = MaterialCheckBox(this).apply {
                text = option.label
                textSize = 14f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_PRIMARY)
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                isUseMaterialThemeColors = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(TEAL, TEXT_SECONDARY),
                )
                isChecked = code in draft
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) draft += code else draft -= code
                }
            }
            countryChecks += check
            choices.addView(check, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(bulkActions, LinearLayout.LayoutParams(-1, dp(48)))
            addView(
                ScrollView(this@showConnectionTestingPage).apply { addView(choices) },
                LinearLayout.LayoutParams(
                    -1,
                    minOf(
                        selectableCountryOptions.size.coerceAtLeast(1) * dp(48),
                        (resources.displayMetrics.heightPixels * 0.42f).toInt(),
                    ),
                ).apply { topMargin = dp(8) },
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_filter_country_title)
            .setView(dialogContent)
            .setPositiveButton(R.string.split_tunnel_save) { _, _ ->
                selectedCountryCodes.clear()
                selectedCountryCodes += draft
                filteredProfiles = visibleProfiles()
                adapter.notifyDataSetChanged()
                updateTestControls()
            }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
            .showCatClientDialog()
    }

    testButton.setOnClickListener {
        if (!canRunConnectionTests || speedTestRunning || testRunning && !testPaused) {
            return@setOnClickListener
        }
        val targets = filteredProfiles.toList()
        if (targets.isEmpty()) return@setOnClickListener
        testRunning = true
        testPaused = false
        lastTestCompleted = 0
        lastTestTotal = targets.size
        lastTestAvailable = 0
        lastTestStatus = Actions.DELAY_TEST_PREPARING
        lastTestError = ""
        delayTestId = SystemClock.elapsedRealtimeNanos().toString()
        val targetFingerprints = targets.map { it.fingerprint }.toSet()
        testingFingerprints.clear()
        testingFingerprints += targetFingerprints
        ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = delayTestId.orEmpty(),
                subscriptionId = selectedSubscriptionId,
                connectionTypes = selectedTypes,
                targetFingerprints = targets.map(ConnectionProfile::fingerprint),
                status = Actions.DELAY_TEST_PREPARING,
                total = targets.size,
            ),
        )
        filteredProfiles = visibleProfiles()
        adapter.notifyDataSetChanged()
        updateTestControls()
        startForegroundService(
            Intent(this, CatClientVpnService::class.java)
                .setAction(Actions.TEST_CONNECTION_DELAYS)
                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                .putExtra(Actions.EXTRA_DELAY_TEST_ID, delayTestId)
                .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, selectedSubscriptionId)
                .putStringArrayListExtra(
                    Actions.EXTRA_CONNECTION_TYPES,
                    ArrayList(selectedTypes.sorted()),
                )
                .putStringArrayListExtra(
                    Actions.EXTRA_CONNECTION_FINGERPRINTS,
                    ArrayList(targets.map(ConnectionProfile::fingerprint)),
                ),
        )
    }

    pauseButton.setOnClickListener {
        val currentTestId = delayTestId ?: return@setOnClickListener
        if (!testRunning) return@setOnClickListener
        testPaused = !testPaused
        updateTestControls()
        startService(
            Intent(this, CatClientVpnService::class.java)
                .setAction(
                    if (testPaused) {
                        Actions.PAUSE_CONNECTION_DELAY_TEST
                    } else {
                        Actions.RESUME_CONNECTION_DELAY_TEST
                    },
                )
                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                .putExtra(Actions.EXTRA_DELAY_TEST_ID, currentTestId),
        )
    }

    stopButton.setOnClickListener {
        if (!testRunning) return@setOnClickListener
        startService(
            Intent(this, CatClientVpnService::class.java)
                .setAction(Actions.CANCEL_CONNECTION_DELAY_TEST)
                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                .putExtra(Actions.EXTRA_DELAY_TEST_ID, delayTestId),
        )
    }

    val smartRecommendationText = TextView(this).apply {
        textSize = 14f
        typeface = CatClientBodyTypeface
        setTextColor(TEXT_PRIMARY)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }
    val smartScoutButton = MaterialButton(this).apply {
        setText(R.string.smart_connection_test_routes)
        setAllCaps(false)
        minHeight = dp(48)
        minimumHeight = dp(48)
        cornerRadius = dp(24)
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(BACKGROUND)
    }
    val smartCountryButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(R.string.smart_country_automatic)
        setAllCaps(false)
        minHeight = dp(48)
        minimumHeight = dp(48)
        cornerRadius = dp(24)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(OUTLINE)
        backgroundTintList = ColorStateList.valueOf(SURFACE)
        setTextColor(TEXT_PRIMARY)
    }
    val smartApplyButton = MaterialButton(this).apply {
        setText(R.string.smart_apply_recommendation)
        setAllCaps(false)
        minHeight = dp(48)
        minimumHeight = dp(48)
        cornerRadius = dp(24)
        backgroundTintList = ColorStateList.valueOf(ZedBlobView.ZED_LIME)
        setTextColor(BACKGROUND)
        isEnabled = false
        alpha = 0.45f
    }
    var smartSelectedProfile: ConnectionProfile? = null
    var smartSelectedRoute: SmartRouteMeasurement? = null
    val smartAutoFailover = MaterialSwitch(this).apply {
        isChecked = NetworkGenomeStore(this@showConnectionTestingPage).autoFailover
        text = getString(R.string.smart_auto_failover)
        setTextColor(TEXT_PRIMARY)
        textSize = 14f
        minHeight = dp(48)
        setPaddingRelative(dp(8), 0, dp(8), 0)
        setOnCheckedChangeListener { _, checked ->
            NetworkGenomeStore(this@showConnectionTestingPage).autoFailover = checked
        }
    }
    val smartHealthButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(R.string.smart_health_repair)
        setAllCaps(false)
        minHeight = dp(48)
        minimumHeight = dp(48)
    }
    val smartScanButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(R.string.smart_scan_best_ip)
        setAllCaps(false)
        minHeight = dp(48)
        minimumHeight = dp(48)
    }
    val isFarsiSmart = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val currentUserSub = userSubscriptionManager.list().firstOrNull { it.id == selectedSubscriptionId }
    val smartOptimizeSubButton = if (currentUserSub != null) {
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (isFarsiSmart) "⚡ بهینه‌سازی خودکار ساب با آی‌پی‌های سالم خط من" else "⚡ Optimize Subscription with Verified IPs"
            setAllCaps(false)
            minHeight = dp(44)
            minimumHeight = dp(44)
            cornerRadius = dp(22)
            strokeColor = ColorStateList.valueOf(TEAL)
            setTextColor(TEAL)
            setOnClickListener { optimizeUserSubscription(currentUserSub) }
        }
    } else null
    val smartDetailsHost = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(smartRecommendationText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (smartOptimizeSubButton != null) {
            addView(smartOptimizeSubButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        }
        addView(smartScoutButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        addView(smartCountryButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        addView(smartApplyButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        addView(smartAutoFailover, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        val tools = LinearLayout(this@showConnectionTestingPage).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(smartScanButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        tools.addView(smartHealthButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        addView(tools, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
    }
    val smartToggleIndicator = TextView(this).apply {
        text = if (isFarsiSmart) "نمایش ابزارها ▾" else "Show tools ▾"
        textSize = 12f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_SECONDARY)
    }
    val smartHeaderBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        isClickable = true
        isFocusable = true
        addView(
            TextView(this@showConnectionTestingPage).apply {
                setText(R.string.smart_connection_title)
                textSize = 15.5f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEAL)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(smartToggleIndicator, LinearLayout.LayoutParams(-2, -2))
        setOnClickListener {
            val show = smartDetailsHost.visibility != View.VISIBLE
            smartDetailsHost.visibility = if (show) View.VISIBLE else View.GONE
            smartToggleIndicator.text = if (show) {
                if (isFarsiSmart) "بستن ▴" else "Hide ▴"
            } else {
                if (isFarsiSmart) "نمایش ابزارها ▾" else "Show tools ▾"
            }
        }
    }
    val smartRecommendationCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(SURFACE)
            setStroke(dp(1), OUTLINE)
        }
        addView(smartHeaderBar, LinearLayout.LayoutParams(-1, -2))
        addView(smartDetailsHost, LinearLayout.LayoutParams(-1, -2))
    }

    fun renderSmartRecommendation() {
        val networkKey = NetworkContext.key(this@showConnectionTestingPage)
        val smartNetworkRecords = subscriptionStore
            .readConnectionDelayRecordsForNetwork(selectedSubscriptionId, selectorProfiles, networkKey)
            .associateBy(ConnectionDelayRecord::fingerprint)
        val measurements = SmartRouteMeasurementAdapter.fromProfiles(
            profiles = selectorProfiles,
            records = smartNetworkRecords,
            // Config/server geography is not egress evidence; keep country UNKNOWN until a route probe measures it.
            countryByFingerprint = emptyMap(),
            currentNetworkKey = networkKey,
        )
        val scoutMeasurements = SmartRouteScoutStore(this@showConnectionTestingPage).read(networkKey)
        val smartPreference = SmartRoutePreferenceStore(this@showConnectionTestingPage).read()
        smartCountryButton.text = when (smartPreference.countryMode) {
            CountrySelectionMode.AUTOMATIC -> getString(R.string.smart_country_automatic)
            CountrySelectionMode.PREFER -> getString(R.string.smart_country_prefer_value, smartPreference.countryCode ?: "—")
            CountrySelectionMode.LOCK -> getString(R.string.smart_country_lock_value, smartPreference.countryCode ?: "—")
        }
        val allSmartMeasurements = measurements + scoutMeasurements
        val recommendation = SmartConnectionAdvisor.recommend(
            network = SmartNetworkSnapshotReader.read(this@showConnectionTestingPage),
            measurements = allSmartMeasurements,
            preference = smartPreference,
        )
        val selected = recommendation.selected
        val identity = SmartNetworkIdentityReader.read(this@showConnectionTestingPage)
        val unknown = getString(R.string.smart_value_unknown)
        val activeRoute = when {
            buttonModel.state == VpnState.Started && activeConnectionTag.isNotBlank() -> activeConnectionTag
            buttonModel.state != VpnState.Started && buttonModel.state != VpnState.Starting -> getString(R.string.smart_route_direct)
            else -> unknown
        }
        smartSelectedRoute = selected
        smartSelectedProfile = selected?.let { selectedRoute ->
            selectorProfiles.firstOrNull { it.fingerprint == selectedRoute.id }
        }
        smartApplyButton.isEnabled = smartSelectedProfile != null ||
            selected?.kind == SmartRouteKind.ORBOT || selected?.kind == SmartRouteKind.DIRECT
        smartApplyButton.alpha = if (smartApplyButton.isEnabled) 1f else 0.45f
        val measuredAt = selected?.measuredAtMs?.takeIf { it > 0L }?.let {
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
        } ?: unknown
        val routeRows = allSmartMeasurements
            .groupBy { it.id }
            .mapNotNull { (_, samples) -> samples.maxByOrNull(SmartRouteMeasurement::measuredAtMs) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .map { route ->
                getString(
                    R.string.smart_route_status_row,
                    route.label,
                    route.verdict.name,
                    route.medianRttMs?.let { "$it ms" } ?: unknown,
                )
            }
        val liveFacts = listOf(
            getString(R.string.smart_live_network, identity.type, identity.operator ?: unknown),
            getString(R.string.smart_live_active_route, activeRoute),
            getString(
                R.string.smart_live_egress,
                selected?.exitIp ?: unknown,
                selected?.countryCode ?: unknown,
                selected?.medianRttMs?.toString() ?: unknown,
            ),
            getString(R.string.smart_live_measured_at, measuredAt),
        ) + if (routeRows.isEmpty()) emptyList() else listOf(getString(R.string.smart_routes_status_title)) + routeRows
        smartRecommendationText.text = if (selected == null) {
            (liveFacts + getString(
                if (recommendation.blockedReason == "locked-country-unavailable") {
                    R.string.smart_connection_locked_unavailable
                } else {
                    R.string.smart_connection_need_test
                },
            )).joinToString("\n")
        } else {
            val speed = selected.throughputKbps?.let {
                getString(R.string.smart_connection_speed, it / 1_000.0)
            }.orEmpty()
            (liveFacts + listOf(
                getString(R.string.smart_connection_best, selected.label),
                getString(R.string.smart_connection_metrics, selected.medianRttMs ?: 0L, speed),
                getString(R.string.smart_connection_reason_stable),
            )).joinToString("\n")
        }
    }

    smartApplyButton.setOnClickListener {
        val route = smartSelectedRoute ?: return@setOnClickListener
        MaterialAlertDialogBuilder(this@showConnectionTestingPage)
            .setTitle(R.string.smart_apply_title)
            .setMessage(getString(R.string.smart_apply_message, route.label))
            .setPositiveButton(R.string.smart_apply_confirm) { _, _ ->
                when (route.kind) {
                    SmartRouteKind.DIRECT -> {
                        orbotEnginePreferenceStore.setEnabled(false)
                        if (buttonModel.state == VpnState.Started || buttonModel.state == VpnState.Starting) {
                            startVpnService(Actions.DISCONNECT)
                        }
                    }
                    SmartRouteKind.ORBOT, SmartRouteKind.DNSTT -> {
                        orbotEnginePreferenceStore.setEnabled(true)
                        if (buttonModel.state == VpnState.Started) startVpnService(Actions.RECONNECT)
                    }
                    else -> smartSelectedProfile?.let { profile ->
                        handleConnectionSelected(profile, setOf(profile.type.lowercase(Locale.US)))
                    }
                }
                closeConnectionTestingPage()
            }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
            .showCatClientDialog()
    }

    smartScanButton.setOnClickListener {
        closeConnectionTestingPage(animate = false)
        showProxyIpScannerPage()
    }
    smartHealthButton.setOnClickListener { onPanelStatusCheck() }

    smartCountryButton.setOnClickListener {
        val modeLabels = arrayOf(
            getString(R.string.smart_country_automatic),
            getString(R.string.smart_country_prefer),
            getString(R.string.smart_country_lock),
        )
        MaterialAlertDialogBuilder(this@showConnectionTestingPage)
            .setTitle(R.string.smart_country_title)
            .setItems(modeLabels) { _, modeIndex ->
                if (modeIndex == 0) {
                    SmartRoutePreferenceStore(this@showConnectionTestingPage).saveCountry(CountrySelectionMode.AUTOMATIC, null)
                    renderSmartRecommendation()
                } else {
                    val countries = selectableCountryOptions.distinctBy { it.countryCode }.sortedBy { it.label }
                    MaterialAlertDialogBuilder(this@showConnectionTestingPage)
                        .setTitle(R.string.smart_country_choose)
                        .setItems(countries.map { it.label }.toTypedArray()) { _, countryIndex ->
                            val country = countries[countryIndex].countryCode ?: return@setItems
                            SmartRoutePreferenceStore(this@showConnectionTestingPage).saveCountry(
                                if (modeIndex == 1) CountrySelectionMode.PREFER else CountrySelectionMode.LOCK,
                                country,
                            )
                            renderSmartRecommendation()
                        }
                        .setNegativeButton(R.string.split_tunnel_cancel, null)
                        .create()
            .showCatClientDialog()
                }
            }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
            .showCatClientDialog()
    }

    smartScoutButton.setOnClickListener {
        val networkKeyAtStart = NetworkContext.key(this@showConnectionTestingPage)
        val scoutStore = SmartRouteScoutStore(this@showConnectionTestingPage)
        val now = System.currentTimeMillis()
        if (!SmartRouteScout.canProbe(scoutStore.lastProbeAt(networkKeyAtStart), now)) {
            Toast.makeText(this@showConnectionTestingPage, R.string.smart_probe_rate_limited, Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        scoutStore.markProbeAt(networkKeyAtStart, now)
        smartScoutButton.isEnabled = false
        smartScoutButton.setText(R.string.smart_connection_testing_routes)
        activityScope.launch {
            val activeTarget = if (buttonModel.state == VpnState.Started) {
                selectorProfiles.firstOrNull { it.fingerprint == activeConnectionFingerprint }?.let { profile ->
                    SmartScoutTarget(
                        id = profile.fingerprint,
                        kind = SmartRouteMeasurementAdapter.routeKind(profile),
                        label = profile.displayTag,
                    )
                }
            } else null
            val results = SmartRouteScout.run(this@showConnectionTestingPage, activeTarget)
            if (NetworkContext.key(this@showConnectionTestingPage) == networkKeyAtStart) {
                scoutStore.save(networkKeyAtStart, results)
            }
            renderSmartRecommendation()
            smartScoutButton.isEnabled = true
            smartScoutButton.setText(R.string.smart_connection_test_routes)
            val working = results.count { it.verdict == SmartRouteVerdict.WORKS }
            Toast.makeText(
                this@showConnectionTestingPage,
                getString(R.string.smart_connection_test_complete, working, results.size),
                Toast.LENGTH_SHORT,
            ).show()
            // Scout means every available path, not merely the rows left by an old filter.
            selectedCountryCodes.clear()
            selectedCountryCodes += availableCountryCodes
            selectedTypes.clear()
            selectedTypes += availableTypes
            countryFilterButton.text = selectedCountryLabel()
            typeFilterButton.text = selectedTypesLabel()
            filteredProfiles = visibleProfiles()
            adapter.notifyDataSetChanged()
            if (testButton.isEnabled && !testRunning) testButton.performClick()
        }
    }

    lateinit var pageDelayTestListener: (Intent) -> Unit
    var lastDelayResultsRefreshAtMs: Long? = null
    var wireGuardHelpShown = false
    fun explainIfEveryWireGuardRowFailed() {
        if (wireGuardHelpShown) return
        val wgProfiles = selectorProfiles.filter { it.type.lowercase(Locale.US) in setOf("wireguard", "awg", "amneziawg") }
        if (wgProfiles.isEmpty()) return
        val records = runCatching {
            subscriptionStore.readConnectionDelayRecords(subscriptionId = selectedSubscriptionId, profiles = wgProfiles)
        }.getOrDefault(emptyList())
        val byFingerprint = records.associateBy(ConnectionDelayRecord::fingerprint)
        val allFailed = wgProfiles.all { byFingerprint[it.fingerprint]?.status == ConnectionDelayStatus.Failure }
        if (allFailed) {
            wireGuardHelpShown = true
            showWireGuardRowsFailedHelp()
        }
    }
    var delayResultsRefreshScheduled = false

    fun reloadDelayResults() {
        connectionDelayRecords = subscriptionStore
            .readConnectionDelayRecords(
                subscriptionId = selectedSubscriptionId,
                profiles = selectorProfiles,
            )
            .associateBy(ConnectionDelayRecord::fingerprint)
        filteredProfiles = visibleProfiles()
        adapter.notifyDataSetChanged()
        renderSmartRecommendation()
        lastDelayResultsRefreshAtMs = SystemClock.elapsedRealtime()
    }

    val delayedResultsRefresh = Runnable {
        delayResultsRefreshScheduled = false
        if (connectionDelayTestListener !== pageDelayTestListener) return@Runnable
        reloadDelayResults()
    }

    fun scheduleDelayResultsRefresh() {
        if (delayResultsRefreshScheduled) return
        val delayMs = ConnectionDelayUiRefreshPolicy.delayUntilNextRefresh(
            nowMs = SystemClock.elapsedRealtime(),
            lastRefreshAtMs = lastDelayResultsRefreshAtMs,
        )
        if (delayMs == 0L) {
            reloadDelayResults()
        } else {
            delayResultsRefreshScheduled = true
            mainHandler.postDelayed(delayedResultsRefresh, delayMs)
        }
    }

    fun reloadFinalDelayResults() {
        mainHandler.removeCallbacks(delayedResultsRefresh)
        delayResultsRefreshScheduled = false
        scheduleDelayResultsRefresh()
    }

    pageDelayTestListener = listener@{ intent ->
        if (intent.action == Actions.CONNECTION_SPEED_TEST_CHANGED) {
            val broadcastTestId = intent.getStringExtra(Actions.EXTRA_SPEED_TEST_ID) ?: return@listener
            val broadcastSubscriptionId = intent.getStringExtra(Actions.EXTRA_SUBSCRIPTION_ID).orEmpty()
            if (broadcastSubscriptionId != selectedSubscriptionId) return@listener
            val fingerprint = intent.getStringExtra(Actions.EXTRA_CONNECTION_FINGERPRINT).orEmpty()
            if (speedTests[fingerprint]?.testId != broadcastTestId) return@listener
            val session = ConnectionSpeedTestState.snapshot(selectedSubscriptionId, fingerprint)
                ?.takeIf { it.testId == broadcastTestId }
                ?: ConnectionSpeedTestSession(
                    testId = broadcastTestId,
                    subscriptionId = broadcastSubscriptionId,
                    fingerprint = intent.getStringExtra(Actions.EXTRA_CONNECTION_FINGERPRINT).orEmpty(),
                    status = intent.getStringExtra(Actions.EXTRA_SPEED_TEST_STATUS)
                        ?: Actions.SPEED_TEST_FAILED,
                    error = intent.getStringExtra(Actions.EXTRA_SPEED_TEST_ERROR).orEmpty(),
                )
            if (session.isRunning) {
                speedTests[fingerprint] = session
            } else {
                speedTests.remove(fingerprint)
            }
            speedTestRunning = speedTests.isNotEmpty()
            if (!session.isRunning) {
                connectionDelayRecords = subscriptionStore
                    .readConnectionDelayRecords(selectedSubscriptionId, selectorProfiles)
                    .associateBy(ConnectionDelayRecord::fingerprint)
            }
            adapter.notifyDataSetChanged()
            updateTestControls()
            if (session.status == Actions.SPEED_TEST_FAILED) {
                Toast.makeText(
                    this,
                    session.error.ifBlank { getString(R.string.connection_speed_test_failed) },
                    Toast.LENGTH_LONG,
                ).show()
            }
            return@listener
        }

        val broadcastTestId = intent.getStringExtra(Actions.EXTRA_DELAY_TEST_ID)
        if (broadcastTestId != delayTestId) return@listener
        val session = ConnectionDelayTestState.snapshot(selectedSubscriptionId)
            ?.takeIf { it.testId == broadcastTestId }
            ?: return@listener
        val status = session.status
        lastTestCompleted = session.completed
        lastTestTotal = session.total
        lastTestAvailable = session.available
        lastTestStatus = status
        lastTestError = session.error
        testPaused = session.paused
        if (status == Actions.DELAY_TEST_STARTED) {
            connectionDelayRecords = connectionDelayRecords.filterKeys {
                it !in session.targetFingerprints
            }
            filteredProfiles = visibleProfiles()
            lastDelayResultsRefreshAtMs = SystemClock.elapsedRealtime()
        }
        testingFingerprints.clear()
        if (session.isRunning) {
            testingFingerprints += session.targetFingerprints - session.finishedFingerprints
        }
        when (status) {
            Actions.DELAY_TEST_STARTED -> {
                testRunning = true
                adapter.notifyDataSetChanged()
                updateTestControls()
            }

            Actions.DELAY_TEST_PROGRESS -> {
                testRunning = true
                scheduleDelayResultsRefresh()
                updateTestControls()
            }

            Actions.DELAY_TEST_COMPLETED -> {
                testRunning = false
                testPaused = false
                testingFingerprints.clear()
                reloadFinalDelayResults()
                updateTestControls()
                explainIfEveryWireGuardRowFailed()
            }

            Actions.DELAY_TEST_FAILED -> {
                testRunning = false
                testPaused = false
                testingFingerprints.clear()
                reloadFinalDelayResults()
                updateTestControls()
            }

            Actions.DELAY_TEST_CANCELED -> {
                testRunning = false
                testPaused = false
                testingFingerprints.clear()
                reloadFinalDelayResults()
                updateTestControls()
            }
        }
    }
    connectionDelayTestListener = pageDelayTestListener

    // Assemble layout
    content.addView(headerRow, LinearLayout.LayoutParams(-1, -2))
    content.addView(
        headerRule,
        LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(18)
            bottomMargin = dp(16)
        },
    )
    if (!pickerMode) {
        content.addView(
            smartRecommendationCard,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) },
        )
    }
    subscriptionFilterButton?.let { button ->
        content.addView(
            button,
            LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) },
        )
    }
    content.addView(filterRow, LinearLayout.LayoutParams(-1, dp(48)))
    content.addView(
        progressSection,
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
    )
    content.addView(
        controlRow,
        LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) },
    )
    content.addView(
        list,
        LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) },
    )

    connectionTestingPageHost.removeAllViews()
    ViewCompat.setAccessibilityPaneTitle(connectionTestingPageHost, pageTitle.text)
    connectionTestingPageHost.addView(
        content,
        FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL),
    )
    renderSmartRecommendation()
    updateTestControls()
    setConnectionTestingPageVisible(visible = true, animate = animate && !rebuild)
}
