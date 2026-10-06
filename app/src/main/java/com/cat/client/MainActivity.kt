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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.text.DateFormat

internal object ConnectionDelayUiRefreshPolicy {
    const val MIN_REFRESH_INTERVAL_MS = 500L

    fun delayUntilNextRefresh(nowMs: Long, lastRefreshAtMs: Long?): Long {
        if (lastRefreshAtMs == null) return 0L
        val elapsedMs = (nowMs - lastRefreshAtMs).coerceAtLeast(0L)
        return (MIN_REFRESH_INTERVAL_MS - elapsedMs).coerceAtLeast(0L)
    }
}

class SubscriptionQrCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView =
        super.initializeContent().also { scanner ->
            val side = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 4 / 5
            scanner.barcodeView.framingRectSize = Size(side, side)
        }
}

/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app · tone: utilitarian · anchor hue: violet (purple / black / white) */
/* Hallmark · pre-emit critique: P5 H5 E5 S5 R5 V4 · contrast: pass (40–41) · slop: pass */
class MainActivity : Activity() {
    private val palette: CatClientPalette by lazy { CatClientDesignTokens.forContext(this) }
    private val buttonModel = ConnectButtonModel()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateSettingsBadge: TextView? = null
    private val appUpdateUi by lazy {
        AppUpdateUi(this, activityScope, palette) { available ->
            updateSettingsBadge?.let { badge ->
                badge.visibility = if (available) View.VISIBLE else View.GONE
                (badge.parent as? View)?.contentDescription = getString(R.string.update_settings_title) +
                    if (available) ", ${getString(R.string.update_available_title)}" else ""
            }
            if (::appTabs.isInitialized) {
                appTabs.getTabAt(DOCK_SETTINGS)?.let { tab ->
                    tab.contentDescription = getString(R.string.tab_settings)
                    if (available) {
                        tab.orCreateBadge.apply {
                            backgroundColor = TEAL
                            setContentDescriptionNumberless(getString(R.string.update_available_title))
                        }
                    } else {
                        tab.removeBadge()
                    }
                }
            }
        }
    }
    private lateinit var privacyPolicyStore: PrivacyPolicyAcceptanceStore
    private lateinit var appLanguagePreferenceStore: AppLanguagePreferenceStore
    private lateinit var appThemePreferenceStore: AppThemePreferenceStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    private lateinit var routingModePreferenceStore: RoutingModePreferenceStore
    private lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    private lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    private lateinit var dpiBypassPreferenceStore: DpiBypassPreferenceStore
    private lateinit var connectionOptionsPreferenceStore: MihomoConnectionOptionsPreferenceStore
    private lateinit var connectionModePreferenceStore: ConnectionModePreferenceStore
    private lateinit var lanSharingPreferenceStore: LanSharingPreferenceStore
    private lateinit var connectionSelectionPreferenceStore: ConnectionSelectionPreferenceStore
    private lateinit var connectionTestSettingsPreferenceStore: ConnectionTestSettingsPreferenceStore
    private lateinit var connectionChainPreferenceStore: ConnectionChainPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var userSubscriptionManager: UserSubscriptionManager
    private var privacyPolicyDialog: AlertDialog? = null
    private var connectionDelayTestListener: ((Intent) -> Unit)? = null
    private lateinit var appShellView: View
    private lateinit var appTabs: TabLayout
    private lateinit var connectionTestingPageHost: ConnectionTestingPage
    private var connectionTestingPageVisible: Boolean = false
    private var activeChainPickerSlot: ConnectionChainSlot? = null
    private var activeChainPickerSubscriptionId: String? = null
    private var renderChainSettingsPage: (() -> Unit)? = null
    private var connectionChainCompatibilityIssue: ConnectionChainCompatibilityIssue? = null
    private var connectionChainCompatibilityMessage: String? = null
    private var pendingConnectionChainAccessibilityAnnouncement: String? = null
    private var openConnectionChainSettingsPage: (() -> Unit)? = null
    private var advancedSettingsBackAction: (() -> Unit)? = null
    private var sessionStartedAtElapsedMs: Long = 0L
    private var connectFlowPending: Boolean = false
    private var connectFlowAction: String = Actions.CONNECT
    private var disconnectAnalyticsPending: Boolean = false
    private var locationOptions: List<LocationSelectorOption> = emptyList()
    private var connectionProfiles: List<ConnectionProfile> = emptyList()
    private var connectionDelayRecords: Map<String, ConnectionDelayRecord> = emptyMap()
    private var activeRuntimeSubscriptionId: String = ""
    private var activeConnectionTag: String = ""
    private var activeConnectionFingerprint: String = ""
    private var activeChainHopCount: Int = 0
    private var liveSelectorReady: Boolean = false
    private var liveSelectableConnectionFingerprints: Set<String> = emptySet()

    private lateinit var connectionGlobe: ConnectionGlobeView
    private lateinit var connectionRealIpText: TextView
    @Volatile private var liveGeoTunneled: Boolean = false
    private var liveGeoDirectAttempted: Boolean = false
    private lateinit var connectionV6Text: TextView
    private var tunnelPingRunning: Boolean = false
    private var pingAllRunning: Boolean = false
    private lateinit var connectActionButton: MaterialButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var publicServerNotice: TextView
    private lateinit var connectionDetailsText: TextView
    private lateinit var timerText: TextView
    private lateinit var downloadSpeedText: TextView
    private lateinit var uploadSpeedText: TextView
    private lateinit var downloadArrowIcon: AnimatedArrowIcon
    private lateinit var uploadArrowIcon: AnimatedArrowIcon
    private lateinit var connectionCountryText: TextView
    private lateinit var locationSelectorRow: DashboardDataRowView
    private lateinit var connectionSelectorRow: DashboardDataRowView
    private lateinit var homeChainAfterSelectorRow: DashboardDataRowView
    private lateinit var homeChainSelectorRows: LinearLayout
    private lateinit var homeSubscriptionSelectorRow: DashboardDataRowView
    private lateinit var settingsSubscriptionSelectorRow: DashboardDataRowView
    private var updateSplitTunnelControlsEnabled: ((Boolean) -> Unit)? = null
    private lateinit var connectionModeGroup: MaterialButtonToggleGroup
    private lateinit var vpnModeButton: MaterialButton
    private lateinit var proxyModeButton: MaterialButton
    private lateinit var dashboardLocalEndpointText: TextView
    private lateinit var dashboardChainText: TextView
    private lateinit var dashboardConnectionMetadataSection: View
    private lateinit var tlsIntegrityCheckbox: MaterialSwitch
    private lateinit var tlsFragmentCheckbox: MaterialSwitch
    private lateinit var adBlockCheckbox: MaterialSwitch
    private lateinit var alwaysOnStatusText: TextView
    private lateinit var amneziaNoiseCheckbox: MaterialSwitch
    private lateinit var amneziaNoiseFields: LinearLayout
    private lateinit var amneziaNoiseCountInput: EditText
    private lateinit var amneziaNoiseMinSizeInput: EditText
    private lateinit var amneziaNoiseMaxSizeInput: EditText
    private lateinit var amneziaNoiseTtlInput: EditText
    private lateinit var amneziaNoiseVersionInput: EditText
    private lateinit var amneziaNoiseIpStackInput: EditText
    private lateinit var amneziaNoiseCongestionInput: EditText
    private lateinit var amneziaNoiseHeaderProtectionKeyInput: EditText
    private lateinit var amneziaNoiseContentPaddingInput: EditText
    private lateinit var amneziaNoiseRekeyAfterInput: EditText
    private lateinit var amneziaNoiseRekeyTimeoutInput: EditText
    private lateinit var amneziaNoiseRejectAfterInput: EditText
    private lateinit var amneziaNoiseKeepaliveTimeoutInput: EditText
    private lateinit var amneziaNoiseMaxHandshakeAttemptsInput: EditText
    private lateinit var amneziaNoiseRandomTrailersInput: EditText
    private lateinit var amneziaNoiseDisableCookiesInput: EditText
    private lateinit var amneziaNoiseApplyButton: MaterialButton
    private lateinit var amneziaNoiseErrorText: TextView
    private lateinit var lanSharingCheckbox: MaterialSwitch
    private lateinit var lanSharingPasswordCheckbox: MaterialSwitch
    private lateinit var lanSharingDetailsText: TextView
    private lateinit var lanSharingRegenerateButton: MaterialButton
    private lateinit var routingModeRow: LinearLayout
    private lateinit var routingModeValueText: TextView
    private lateinit var routingModeDetailText: TextView
    private lateinit var dnsPrivacyRow: LinearLayout
    private lateinit var dnsPrivacyValueText: TextView
    private lateinit var dnsPrivacyDetailText: TextView
    private lateinit var dnsPrivacyEndpointInput: EditText
    private lateinit var dnsPrivacyEndpointLayout: TextInputLayout
    private lateinit var dnsPrivacyErrorText: TextView
    private lateinit var frontingIpChipGroup: ChipGroup
    private lateinit var frontingIpInput: EditText
    private lateinit var frontingIpInputLayout: TextInputLayout
    private lateinit var frontingIpErrorText: TextView
    private lateinit var refreshActionButton: MaterialButton
    private lateinit var connectionBlob: ZedBlobView
    private lateinit var activeConfigTitle: TextView
    private lateinit var homeLocationPill: View
    private lateinit var heroPingAction: View
    private var homeHeroFrame: FrameLayout? = null
    private var heroClusterParams: FrameLayout.LayoutParams? = null
    private var homeStageFitting = false
    @Volatile private var livePingMs: Long? = null
    private var lastQuietPingAtMs: Long = 0L
    private var liveBackdrop: ZedLiveBackdropDrawable? = null
    private lateinit var homeFlagBadge: FlagBadgeView
    private var homeExtrasSection: View? = null
    private lateinit var serversCountText: TextView
    private lateinit var heroStateText: TextView
    private lateinit var downloadBarFill: ZedWavyProgressView
    private lateinit var uploadBarFill: ZedWavyProgressView
    private lateinit var downloadTotalText: TextView
    private lateinit var uploadTotalText: TextView
    private var homeBackdrop: View? = null
    private var appRootView: FrameLayout? = null
    private var sessionRxStartBytes = -1L
    private var sessionTxStartBytes = -1L
    private lateinit var subscriptionsList: LinearLayout
    private lateinit var vpnTabContent: View
    private lateinit var subscriptionsTabContent: View
    private lateinit var advancedTabContent: View
    private lateinit var cloudTabContent: View
    private var cloudDeploymentHistoryHost: LinearLayout? = null

    /* IP scanner tab (clean Cloudflare IPs with SNI + fronting) */
    private lateinit var scannerTabContent: View
    private lateinit var scannerSniInput: TextInputEditText
    private lateinit var scannerPortGroup: ChipGroup
    private lateinit var scannerSubnetsInput: TextInputEditText
    private lateinit var scannerStartButton: MaterialButton
    private lateinit var scannerStopButton: MaterialButton
    private lateinit var scannerProgressBar: ProgressBar
    private lateinit var scannerStatusText: TextView
    private lateinit var scannerResultsList: LinearLayout
    private lateinit var scannerApplyButton: MaterialButton
    private lateinit var scannerBuildButton: MaterialButton
    private var scannerResults: List<IpScanner.ScanResult> = emptyList()
    private var scannerSpeedTestButton: MaterialButton? = null
    private var scannerSpeedTestRunning = false
    private val scannerLiveResults = mutableListOf<IpScanner.ScanResult>()
    private var scannerPort: Int = 443
    private var scannerRunning: Boolean = false
    private var scannerJob: Job? = null
    private val dockTabs = mutableListOf<DockTab>()
    private lateinit var pingValueText: TextView
    private lateinit var uptimeValueText: TextView

    /* Free configs (community sources, fetched + ping-tested in-app) */
    private lateinit var freeConfigsStatus: TextView
    private lateinit var freeConfigsProgress: ProgressBar
    private lateinit var homeUsageCard: LinearLayout
    private lateinit var homeUsageBar: UsageBarView
    private lateinit var homeUsageTitle: TextView
    private lateinit var homeUsageValue: TextView
    private lateinit var homeUsageLegend: TextView
    private lateinit var homeUsageHint: TextView
    private lateinit var freeConfigsList: LinearLayout
    private lateinit var freeConfigsFetchButton: MaterialButton
    private lateinit var freeConfigsTestButton: MaterialButton
    private lateinit var freeConfigsImportButton: MaterialButton
    private var freeConfigEntries: List<FreeConfigs.FreeEntry> = emptyList()
    private var freeConfigsFetching = false
    private var freeConfigsJob: Job? = null
    private var connectionCountryFlag: String = ""
    private var debugFrontingIp: String = ""
    private var liveGeo: IpGeolocation.Info? = null
    private var liveGeoAtMs: Long = 0L
    private var liveGeoJob: Job? = null
    private var vpnCurrentlyStarted = false
    private var connectionDetails: String = ""
    private var alwaysOnMode: Boolean = false
    private var lockdownMode: Boolean = false
    private var frontingIps: List<String> = emptyList()
    private var frontingIpInputUpdating: Boolean = false
    private var dnsPrivacyInputUpdating: Boolean = false
    private var lastTransferRxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferTxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferSampleElapsedMs: Long = 0L

    private val BACKGROUND: Int get() = palette.background
    private val SURFACE: Int get() = palette.surface
    private val OUTLINE: Int get() = palette.outline
    private val TEXT_PRIMARY: Int get() = palette.textPrimary
    private val TEXT_SECONDARY: Int get() = palette.textSecondary
    private val TIMER_MUTED: Int get() = palette.textSecondary
    private val TEAL: Int get() = palette.teal
    private val AMBER: Int get() = palette.amber
    private val ERROR: Int get() = palette.red

    private val timerRunnable = object : Runnable {
        override fun run() {
            val startedAt = sessionStartedAtElapsedMs
            if (buttonModel.state == VpnState.Started && startedAt > 0L) {
                setTimerText(SystemClock.elapsedRealtime() - startedAt)
                updateTransferSpeeds()
                mainHandler.postDelayed(this, TIMER_TICK_MS)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(AppTheme.wrap(newBase)))
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Actions.STATE_CHANGED -> handleStateChanged(context, intent)
                Actions.CONNECTION_DELAY_TEST_CHANGED -> connectionDelayTestListener?.invoke(intent)
                Actions.CONNECTION_SPEED_TEST_CHANGED -> connectionDelayTestListener?.invoke(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(AppAccentPreferenceStore(this).read().overlayStyleRes, true)
        super.onCreate(savedInstanceState)
        appLanguagePreferenceStore = AppLanguagePreferenceStore(this)
        appThemePreferenceStore = AppThemePreferenceStore(this)
        if (savedInstanceState == null) {
            AnalyticsEvents.appOpened(this)
        }
        privacyPolicyStore = PrivacyPolicyAcceptanceStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
        routingModePreferenceStore = RoutingModePreferenceStore(this)
        dnsPrivacyPreferenceStore = DnsPrivacyPreferenceStore(this)
        tlsIntegrityPreferenceStore = TlsIntegrityPreferenceStore(this)
        dpiBypassPreferenceStore = DpiBypassPreferenceStore(this)
        connectionOptionsPreferenceStore = MihomoConnectionOptionsPreferenceStore(this)
        connectionModePreferenceStore = ConnectionModePreferenceStore(this)
        lanSharingPreferenceStore = LanSharingPreferenceStore(this)
        connectionSelectionPreferenceStore = ConnectionSelectionPreferenceStore(this)
        connectionTestSettingsPreferenceStore = ConnectionTestSettingsPreferenceStore(this)
        connectionChainPreferenceStore = ConnectionChainPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        userSubscriptionManager = UserSubscriptionManager(this)
        connectFlowPending = savedInstanceState?.getBoolean(STATE_CONNECT_FLOW_PENDING) == true
        connectFlowAction = savedInstanceState?.getString(STATE_CONNECT_FLOW_ACTION) ?: Actions.CONNECT
        DiagnosticLogger.info(
            this,
            "activity.onCreate",
            "restored=${savedInstanceState != null} connectPending=$connectFlowPending action=$connectFlowAction",
        )
        configureSystemBars()
        setContentView(buildAppShell())
        renderState(VpnState.Stopped)
        refreshLocationOptions()
        fetchPrivateSubscriptionOnLoad()
        if (savedInstanceState?.getBoolean(STATE_CONNECTION_TESTING_PAGE) == true) {
            val chainSlot = ConnectionChainSlot.fromWireName(
                savedInstanceState.getString(STATE_CHAIN_PICKER_SLOT),
            )
            if (chainSlot != ConnectionChainSlot.Before) {
                if (chainSlot != null) {
                    openScreen(SCREEN_SETTINGS)
                    openConnectionChainSettingsPage?.invoke()
                }
                mainHandler.post {
                    showConnectionTestingPage(
                        animate = false,
                        chainSlot = chainSlot,
                        pickerSubscriptionId = savedInstanceState.getString(STATE_CHAIN_PICKER_SUBSCRIPTION),
                    )
                }
            }
        }
        mainHandler.post {
            val checkUpdatesAfterStartup = { if (savedInstanceState == null) checkForUpdates() }
            if (!showPrivacyPolicyIfNeeded(checkUpdatesAfterStartup)) checkUpdatesAfterStartup()
        }
        handleCatClientDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCatClientDeepLink(intent)
    }

    /**
     * Deep links from the Cat Panel web UI:
     *   catclient://add-sub?url=<subscription or share links>&name=<optional>
     *   catclient://scan?sni=<panel host>            → opens the IP scanner tab
     *   catclient://scan?sni=…&ip=<clean ip,ip,…>    → opens and applies clean IPs
     */
    private fun handleCatClientDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "catclient") return
        when (data.host) {
            "add-sub" -> {
                val source = data.getQueryParameter("url")?.trim().orEmpty()
                if (source.isEmpty()) return
                val name = data.getQueryParameter("name")?.trim().orEmpty().ifEmpty { "Cat Panel" }
                mainHandler.post {
                    openScreen(SCREEN_SERVERS)
                    showAddSubscriptionDialog(source, name)
                }
            }
            "scan" -> {
                val sni = data.getQueryParameter("sni")?.trim().orEmpty()
                val ips = data.getQueryParameter("ip")?.trim().orEmpty()
                mainHandler.post {
                    if (sni.isNotEmpty() && ::scannerSniInput.isInitialized) {
                        scannerSniInput.setText(sni)
                        saveScannerSni(sni)
                    }
                    openScreen(SCREEN_SCANNER)
                    val tokens = ips.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                    if (tokens.isNotEmpty()) {
                        val previousValue = frontingIpPreferenceStore.readFrontingIp()
                        frontingIps = runCatching {
                            FrontingIpPolicy.normalizeIps((frontingIps + tokens).joinToString(","))
                        }.getOrDefault(frontingIps)
                        renderFrontingIpChips()
                        saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)
                        Toast.makeText(
                            this,
                            getString(R.string.scanner_applied, tokens.first(), 0L),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun buildAppShell(): View {
        val (tvInsetX, tvInsetY) = televisionSafeInsets(
            resources.configuration.uiMode,
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        )
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setBackgroundColor(BACKGROUND)
            setPadding(tvInsetX, tvInsetY, tvInsetX, tvInsetY)
        }
        appRootView = root

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        appShellView = shell
        vpnTabContent = buildDashboard()
        subscriptionsTabContent = buildSubscriptionsScreen().apply { visibility = View.GONE }
        advancedTabContent = buildAdvancedScreen().apply { visibility = View.GONE }
        cloudTabContent = buildCloudScreen().apply { visibility = View.GONE }
        scannerTabContent = buildScannerScreen().apply { visibility = View.GONE }
        val content = FrameLayout(this).apply {
            addView(vpnTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(subscriptionsTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(advancedTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(cloudTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(scannerTabContent, FrameLayout.LayoutParams(-1, -1))
        }
        shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // New bottom navigation with pill-shaped container
        val tabs = TabLayout(this).apply {
            appTabsPending = this
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            minimumHeight = dp(64)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(32).toFloat()
                setColor(palette.surfaceElevated1)
                setStroke(dp(1), withAlpha(palette.outline, 110))
            }
            elevation = dp(6).toFloat()
            setSelectedTabIndicatorHeight(0)
            setSelectedTabIndicatorColor(TEAL)
            setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_BOTTOM)
            setTabIndicatorFullWidth(false)
            setTabTextColors(TEXT_SECONDARY, TEAL)
            tabRippleColor = ColorStateList.valueOf(withAlpha(TEAL, 24))
            tabIconTint = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(TEAL, TEXT_SECONDARY),
            )
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            // ZedSecure-style floating capsule: four cells, the active one expands into a
            // lime pill with icon + label, inactive cells show the icon only.
            // Visual order: Home, Servers, Cloud (panel + IP scanner), Settings.
            layoutDirection = View.LAYOUT_DIRECTION_LTR // Zed keeps the dock order fixed in RTL too
            addDockTab(R.string.dock_home, R.drawable.ic_vpn_tab, selected = true)
            addDockTab(R.string.dock_servers, R.drawable.ic_subscriptions_tab, selected = false)
            addDockTab(R.string.tab_cloud, R.drawable.ic_cloud_tab, selected = false)
            addDockTab(R.string.tab_settings, R.drawable.ic_advanced_tab, selected = false)
            post { renderDockSelection(DOCK_HOME) }
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showAppTab(
                        when (tab.position) {
                            DOCK_HOME -> SCREEN_HOME
                            DOCK_SERVERS -> SCREEN_SERVERS
                            DOCK_CLOUD -> if (cloudDockShowsScanner) SCREEN_SCANNER else SCREEN_CLOUD
                            DOCK_SETTINGS -> SCREEN_SETTINGS
                            else -> SCREEN_HOME
                        },
                    )
                    renderDockSelection(tab.position)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        appTabs = tabs
        val tabsHost = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(18), 0, dp(18), dp(18))
            addView(tabs, FrameLayout.LayoutParams(-1, dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        ViewCompat.setOnApplyWindowInsetsListener(tabsHost) { view, insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(dp(28), 0, dp(28), navigationBottom + dp(14))
            insets
        }
        shell.addView(tabsHost, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        connectionTestingPageHost = ConnectionTestingPage(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setBackgroundColor(BACKGROUND)
            visibility = View.GONE
            onSwipeBack = { closeConnectionTestingPage() }
        }
        ViewCompat.setOnApplyWindowInsetsListener(connectionTestingPageHost) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or
                    WindowInsetsCompat.Type.navigationBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
        root.addView(connectionTestingPageHost, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun setConnectionTestingPageVisible(visible: Boolean, animate: Boolean) {
        connectionTestingPageVisible = visible
        connectionTestingPageHost.animate().cancel()
        appShellView.animate().cancel()
        val shouldAnimate = animate && ValueAnimator.areAnimatorsEnabled()
        val slideDirection = if (connectionTestingPageHost.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        fun restoreAppShellAccessibility() {
            appShellView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            pendingConnectionChainAccessibilityAnnouncement?.let { appShellView.announceAccessibility(it) }
            pendingConnectionChainAccessibilityAnnouncement = null
        }
        if (visible) {
            appShellView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            connectionTestingPageHost.visibility = View.VISIBLE
            ViewCompat.requestApplyInsets(connectionTestingPageHost)
            if (!shouldAnimate) {
                connectionTestingPageHost.translationX = 0f
                appShellView.translationX = 0f
                return
            }
            connectionTestingPageHost.translationX = resources.displayMetrics.widthPixels * slideDirection
            connectionTestingPageHost.animate()
                .translationX(0f)
                .setDuration(CONNECTION_TESTING_PAGE_ANIMATION_MS)
                .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .start()
            appShellView.animate()
                .translationX(-dp(20) * slideDirection)
                .setDuration(CONNECTION_TESTING_PAGE_ANIMATION_MS)
                .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .start()
            return
        }

        connectionDelayTestListener = null
        appShellView.animate()
            .translationX(0f)
            .setDuration(CONNECTION_TESTING_PAGE_ANIMATION_MS)
            .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
            .start()
        if (!shouldAnimate) {
            connectionTestingPageHost.translationX = 0f
            connectionTestingPageHost.visibility = View.GONE
            connectionTestingPageHost.removeAllViews()
            restoreAppShellAccessibility()
            return
        }
        connectionTestingPageHost.animate()
            .translationX(resources.displayMetrics.widthPixels * slideDirection)
            .setDuration(CONNECTION_TESTING_PAGE_ANIMATION_MS)
            .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f))
            .withEndAction {
                if (!connectionTestingPageVisible) {
                    connectionTestingPageHost.visibility = View.GONE
                    connectionTestingPageHost.removeAllViews()
                    restoreAppShellAccessibility()
                }
            }
            .start()
    }

    private fun closeConnectionTestingPage(animate: Boolean = true) {
        if (!connectionTestingPageVisible) return
        setConnectionTestingPageVisible(visible = false, animate = animate)
        activeChainPickerSlot = null
        activeChainPickerSubscriptionId = null
    }

    /** One bottom-dock cell: rounded pill + icon + label. */
    private fun addDockTab(@StringRes labelRes: Int, @DrawableRes iconRes: Int, selected: Boolean) {
        val context = ContextThemeWrapper(this, R.style.CatClientPopupTheme)
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(if (selected) TEAL else TEXT_SECONDARY)
        }
        val label = TextView(context).apply {
            setText(labelRes)
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            setTextColor(if (selected) palette.onAccent else TEXT_SECONDARY)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = if (selected) View.VISIBLE else View.GONE
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = dockPillBackground(selected)
            addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        icon.setColorFilter(if (selected) palette.onAccent else TEXT_SECONDARY)
        dockTabs += DockTab(pill, icon, label)
        val tab = appTabsPending.newTab()
        tab.customView = pill
        tab.view.setPadding(dp(2), 0, dp(2), 0)
        tab.view.minimumWidth = 0
        appTabsPending.addTab(tab, selected)
    }

    private fun dockPillBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        setColor(if (selected) TEAL else Color.TRANSPARENT)
    }

    /** Repaints every dock cell so only the active one is the filled lime pill with a label. */
    private fun renderDockSelection(activeIndex: Int) {
        dockTabs.forEachIndexed { index, tab ->
            val active = index == activeIndex
            tab.pill.background = dockPillBackground(active)
            tab.icon.setColorFilter(if (active) palette.onAccent else TEXT_SECONDARY)
            tab.label.setTextColor(if (active) palette.onAccent else TEXT_SECONDARY)
            tab.label.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    /** Remembers whether the Cloud dock cell last showed the panel or the IP scanner. */
    private var cloudDockShowsScanner = false

    /** Jump to a content screen and keep the dock in sync (Cloud cell covers panel + scanner). */
    private fun openScreen(screen: Int) {
        if (screen == SCREEN_SCANNER || screen == SCREEN_CLOUD) cloudDockShowsScanner = screen == SCREEN_SCANNER
        val dockIndex = when (screen) {
            SCREEN_HOME -> DOCK_HOME
            SCREEN_SERVERS -> DOCK_SERVERS
            SCREEN_SETTINGS -> DOCK_SETTINGS
            else -> DOCK_CLOUD
        }
        showAppTab(screen)
        if (::appTabs.isInitialized) {
            val tab = appTabs.getTabAt(dockIndex)
            if (tab != null && !tab.isSelected) tab.select() else renderDockSelection(dockIndex)
        }
    }

    /** Segmented header shared by the Cloud and Scanner screens (ZedSecure-style pill switch). */
    private fun cloudScannerSwitch(scannerActive: Boolean): View {
        fun chip(@StringRes labelRes: Int, active: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(labelRes)
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setTextColor(if (active) palette.onAccent else TEXT_SECONDARY)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(if (active) TEAL else Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(palette.surfaceElevated1)
            }
            addView(chip(R.string.tab_cloud, !scannerActive) { openScreen(SCREEN_CLOUD) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip(R.string.tab_scanner, scannerActive) { openScreen(SCREEN_SCANNER) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private class DockTab(val pill: LinearLayout, val icon: ImageView, val label: TextView)

    private lateinit var appTabsPending: TabLayout

    private fun showAppTab(position: Int) {
        if (position != 2) advancedSettingsBackAction?.invoke()
        vpnTabContent.visibility = if (position == 1) View.VISIBLE else View.GONE
        subscriptionsTabContent.visibility = if (position == 0) View.VISIBLE else View.GONE
        advancedTabContent.visibility = if (position == 2) View.VISIBLE else View.GONE
        cloudTabContent.visibility = if (position == 3) View.VISIBLE else View.GONE
        scannerTabContent.visibility = if (position == 4) View.VISIBLE else View.GONE
        if (position == 0) renderSubscriptions()
        if (position == 1) {
            renderConnectionSelection()
            renderHomeUsageCard()
        }
        syncShellBackdrop()
        if (position == 2) renderAdvancedControls()
    }

    private fun buildSubscriptionsScreen(): View {
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
            addView(TextView(this@MainActivity).apply {
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
                addView(
                    TextView(this@MainActivity).apply {
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
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(TextView(this@MainActivity).apply {
                            setText(R.string.servers_auto_title)
                            textSize = 16f
                            typeface = CatClientBodyBoldTypeface
                            setTextColor(TEXT_PRIMARY)
                            includeFontPadding = false
                        })
                        addView(TextView(this@MainActivity).apply {
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

    /* ------------------------------------------------------------------ */
    /* Home usage graph (quota reported by the panel)                       */
    /* ------------------------------------------------------------------ */

    private fun buildHomeUsageCard(): LinearLayout {
        homeUsageBar = UsageBarView(this).apply {
            configureColors(
                download = TEAL,
                upload = withAlpha(TEAL, 130),
                track = withAlpha(TEXT_SECONDARY, 36),
            )
            layoutParams = LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(12) }
        }
        homeUsageTitle = TextView(this).apply {
            setText(R.string.home_usage_title)
            textSize = 13f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        }
        homeUsageValue = TextView(this).apply {
            textSize = 12.5f
            typeface = CatClientDataTypeface
            setTextColor(TEAL)
            includeFontPadding = false
            gravity = Gravity.END
        }
        homeUsageLegend = TextView(this).apply {
            textSize = 11.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        homeUsageHint = TextView(this).apply {
            setText(R.string.home_usage_empty)
            textSize = 11.5f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            includeFontPadding = false
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            addView(homeUsageTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(homeUsageValue, LinearLayout.LayoutParams(-2, -2))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassSurfaceDrawable(radiusDp = 16)
            clipToOutline = true
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(homeUsageBar, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(12) })
            addView(
                homeUsageLegend,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
            )
            addView(
                homeUsageHint,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
        }
    }

    /** Reads the last usage snapshot of the selected subscription and redraws the bar. */
    private fun renderHomeUsageCard() {
        if (!::homeUsageCard.isInitialized) return
        val store = SubscriptionStore(this)
        val subscription = store.readUserSubscription(store.readSelectedSubscriptionId())
        val usage = subscription?.input?.let { SubscriptionUsageStore(this).read(it) }
        if (usage == null || (usage.totalBytes <= 0 && usage.usedBytes <= 0)) {
            homeUsageBar.setSplit(0f, 0f, withAlpha(TEXT_SECONDARY, 36))
            homeUsageValue.text = "—"
            homeUsageLegend.text = selectedSubscriptionName()
            homeUsageHint.setText(R.string.home_usage_empty)
            homeUsageHint.visibility = View.VISIBLE
            return
        }
        val used = if (usage.totalBytes > 0) usage.usedFraction else 1f
        val uploadShare = if (usage.usedBytes > 0) {
            (usage.uploadBytes.toFloat() / usage.usedBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        homeUsageBar.setSplit(used, uploadShare, withAlpha(TEXT_SECONDARY, 36))
        homeUsageValue.text = if (usage.totalBytes > 0) "${usage.usedPercent}%" else
            SubscriptionUsagePolicy.formatBytes(usage.usedBytes)
        val parts = mutableListOf<String>()
        parts += getString(
            R.string.home_usage_download,
            SubscriptionUsagePolicy.formatBytes(usage.downloadBytes),
        )
        parts += getString(
            R.string.home_usage_upload,
            SubscriptionUsagePolicy.formatBytes(usage.uploadBytes),
        )
        if (usage.totalBytes > 0) {
            parts += getString(
                R.string.home_usage_remaining,
                SubscriptionUsagePolicy.formatBytes(usage.remainingBytes),
            )
        }
        usage.expireEpochSeconds?.let { seconds ->
            val days = ((seconds * 1000L) - System.currentTimeMillis()) / 86_400_000L
            parts += getString(R.string.home_usage_expires, days.coerceAtLeast(0L))
        }
        homeUsageLegend.text = parts.joinToString(" · ")
        homeUsageHint.setText(R.string.home_usage_footer)
        homeUsageHint.visibility = View.VISIBLE
    }

    /* ------------------------------------------------------------------ */
    /* Free configs (community sources) + single-config quick add           */
    /* ------------------------------------------------------------------ */

    private fun buildFreeConfigsSection(): View {
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
        FreeConfigs.loadCache(this)?.takeIf { it.entries.isNotEmpty() }?.let { cached ->
            freeConfigEntries = cached.entries
            freeConfigsStatus.setText(R.string.free_from_cache)
            renderFreeConfigs()
        }

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
            LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(14) },
        )
        return column
    }

    /** True when the core reports a live session (used to route fetches). */
    private fun vpnStateEqualsStarted(): Boolean =
        buttonModel.state == VpnState.Started || VpnRuntimeStateStore.read(this) == VpnState.Started

    private fun fetchFreeConfigs() {
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
                FreeConfigs.fetchAll(this@MainActivity, useTunnel = useTunnel) { name, done, total ->
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
                val cached = FreeConfigs.loadCache(this@MainActivity)
                if (cached != null && cached.entries.isNotEmpty()) {
                    freeConfigEntries = cached.entries
                    freeConfigsStatus.setText(R.string.free_from_cache)
                    renderFreeConfigs()
                } else {
                    freeConfigsStatus.setText(R.string.free_empty)
                }
                return@launch
            }
            FreeConfigs.saveCache(this@MainActivity, report)
            freeConfigEntries = report.entries
            freeConfigsStatus.text = buildString {
                append(getString(R.string.free_loaded, report.entries.size))
                if (report.sourcesOk.isNotEmpty()) append("\n✓ ").append(report.sourcesOk.joinToString(" · "))
                if (report.sourcesFailed.isNotEmpty()) append("\n✗ ").append(report.sourcesFailed.joinToString(" · "))
            }
            renderFreeConfigs()
        }
    }

    private fun renderFreeConfigs() {
        if (!::freeConfigsList.isInitialized) return
        freeConfigsList.removeAllViews()
        val preview = freeConfigEntries.take(FREE_PREVIEW_ROWS)
        preview.forEach { entry ->
            freeConfigsList.addView(
                freeConfigRow(entry),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
            )
        }
        if (freeConfigEntries.size > preview.size) {
            freeConfigsList.addView(
                TextView(this).apply {
                    text = getString(R.string.free_more_rows, freeConfigEntries.size - preview.size)
                    textSize = 12f
                    setTextColor(TEXT_SECONDARY)
                    typeface = CatClientBodyTypeface
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }

    private fun freeConfigRow(entry: FreeConfigs.FreeEntry): View {
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

    private fun copyFreeConfig(entry: FreeConfigs.FreeEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("free-config", entry.link))
        Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
    }

    /** Adds one share-link as its own Subscription and selects it. */
    private fun addSingleConfig(link: String, name: String) {
        if (link.isBlank()) return
        val subscriptionName = name.take(48).ifBlank { "Cat Single" }
        activityScope.launch {
            val added = runCatching {
                withContext(Dispatchers.IO) { userSubscriptionManager.add(subscriptionName, link) }
            }.getOrNull()
            if (added == null) {
                Toast.makeText(this@MainActivity, R.string.free_quick_add_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            userSubscriptionManager.select(added.id)
            renderSubscriptions()
            onSubscriptionSelected()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.free_quick_add_done)
                .setMessage(subscriptionName)
                .setPositiveButton(R.string.free_quick_add_connect) { _, _ -> beginConnectFlow(Actions.CONNECT) }
                .setNegativeButton(R.string.split_tunnel_cancel, null)
                .show()
        }
    }

    /** Paste / clipboard / QR entry point for a single config. */
    private fun showQuickConfigMenu(anchor: View) {
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

    private fun showQuickConfigDialog() {
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
                normalizedSubscriptionSource(link)?.let { source ->
                    addSingleConfig(source, scannedSubscriptionName(source) ?: "Cat Single")
                } ?: Toast.makeText(this, R.string.free_quick_empty, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.subscription_scan_qr) { _, _ -> startSubscriptionQrScan() }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .show()
    }

    /** Ping-tests the fetched free configs through the core's connection testing page. */
    private fun testFreeConfigs() {
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
    private fun importFreeConfigs() {
        val entries = freeConfigEntries
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.free_empty, Toast.LENGTH_SHORT).show()
            return
        }
        activityScope.launch {
            val id = ensureFreeSubscription(entries.map { it.link }.take(FREE_SUBSCRIPTION_LIMIT))
            if (id == null) {
                Toast.makeText(this@MainActivity, R.string.free_quick_add_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            userSubscriptionManager.select(id)
            renderSubscriptions()
            onSubscriptionSelected()
            Toast.makeText(this@MainActivity, R.string.free_imported, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun ensureFreeSubscription(links: List<String>): String? {
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
    private fun showConfigQrCodes(links: List<String>, title: String) {
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
    private fun subscriptionUsageLine(usage: SubscriptionUsage?): String {
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

    private fun renderSubscriptions() {
        if (!::subscriptionsList.isInitialized) return
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
        if (::settingsSubscriptionSelectorRow.isInitialized) {
            settingsSubscriptionSelectorRow.setValue(selectedName)
            settingsSubscriptionSelectorRow.contentDescription =
                getString(
                    R.string.settings_value_content_description,
                    getString(R.string.settings_subscription_title),
                    selectedName,
                )
        }
        if (::serversCountText.isInitialized) {
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
                        R.string.subscription_action_edit to { showEditSubscriptionDialog(item) },
                        R.string.subscription_action_refresh to { refreshSubscription(item) },
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
    private fun subscriptionCard(
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
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@MainActivity).apply {
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
                    TextView(this@MainActivity).apply {
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
                    ImageView(this@MainActivity).apply {
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
        addView(TextView(this@MainActivity).apply {
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
                ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
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
        if (error.isNotBlank()) addView(TextView(this@MainActivity).apply {
            text = getString(R.string.subscription_error, error)
            textSize = 12f
            typeface = CatClientBodyBoldTypeface
            setTextColor(ERROR)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.START
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        addView(
            TextView(this@MainActivity).apply {
                text = getString(R.string.servers_open_list) + "  ›"
                textSize = 13f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEAL)
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                setPaddingRelative(0, dp(6), dp(8), 0)
                setOnClickListener { onTestConnections() }
            },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) },
        )
    }

    private fun openSubscriptionConnectionTesting(subscriptionId: String) {
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

    private fun startSubscriptionQrScan() {
        IntentIntegrator(this)
            .setCaptureActivity(SubscriptionQrCaptureActivity::class.java)
            .setDesiredBarcodeFormats(listOf(IntentIntegrator.QR_CODE))
            .setPrompt(getString(R.string.subscription_scan_qr_prompt))
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .initiateScan()
    }

    private fun showAddSubscriptionMenu(anchor: View) {
        catClientPopupMenu(anchor).apply {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                menu.add(R.string.subscription_scan_qr).setOnMenuItemClickListener {
                    startSubscriptionQrScan()
                    true
                }
            }
            menu.add(R.string.subscription_from_clipboard).setOnMenuItemClickListener {
                addSubscriptionFromClipboard()
                true
            }
            show()
        }
    }

    private fun addSubscriptionFromClipboard() {
        val source = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .let(::normalizedSubscriptionSource)
        if (source == null) {
            Toast.makeText(this, R.string.subscription_clipboard_empty, Toast.LENGTH_SHORT).show()
        } else {
            showAddSubscriptionDialog(source, scannedSubscriptionName(source).orEmpty())
        }
    }

    private fun showAddSubscriptionDialog(initialSource: String = "", initialName: String = "") =
        showSubscriptionDialog(initialSource = initialSource, initialName = initialName)

    private fun showEditSubscriptionDialog(item: UserSubscription) = showSubscriptionDialog(item)

    private fun showSubscriptionDialog(
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
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(nameLayout, LinearLayout.LayoutParams(-1, -2))
            addView(sourceLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
                val name = nameInput.text.toString()
                val source = sourceInput.text.toString()
                if (name.isBlank() || source.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
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
                        renderSubscriptions()
                        renderConnectionDetails(buttonModel.state)
                        if (existing?.id == userSubscriptionManager.selectedId()) {
                            refreshLocationOptions()
                        }
                        Toast.makeText(
                            this@MainActivity,
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
                            this@MainActivity,
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

    private fun testSubscription(item: UserSubscription) {
        runSubscriptionAction(getString(R.string.subscription_testing, item.name)) {
            val imported = userSubscriptionManager.test(item.input)
            getString(R.string.subscription_found, connectionCountLabel(imported.connectionCount))
        }
    }

    private fun refreshSubscription(item: UserSubscription) {
        runSubscriptionAction(
            startMessage = getString(R.string.subscription_refreshing, item.name),
            refreshProfiles = item.id == userSubscriptionManager.selectedId(),
        ) {
            val refreshed = userSubscriptionManager.refresh(item.id)
            getString(R.string.subscription_refreshed, connectionCountLabel(refreshed.connectionCount))
        }
    }

    private fun refreshBuiltInSubscription(subscriptionId: String, name: String) {
        runSubscriptionAction(
            startMessage = getString(R.string.subscription_refreshing, name),
            refreshProfiles = userSubscriptionManager.selectedId() == subscriptionId,
        ) {
            val refreshed = ConfigRepository(this@MainActivity).refreshBuiltInMihomoConfig(subscriptionId)
            getString(R.string.subscription_refreshed, connectionCountLabel(refreshed.catalog.profiles.size))
        }
    }

    private fun runSubscriptionAction(
        startMessage: String,
        refreshProfiles: Boolean = false,
        action: suspend () -> String,
    ) {
        Toast.makeText(this, startMessage, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { action() } }
            renderSubscriptions()
            if (result.isSuccess && refreshProfiles) refreshLocationOptions()
            result.onSuccess { message -> Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
                .onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        localizedError(error, R.string.subscription_operation_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    /* ---- share a single connection (long-press on a row) ---- */

    /**
     * Long-press menu on a connection row: copy / QR / share the `vless://`-style form of that one
     * connection, so a single working config can be moved to v2rayNG, V2Box, Streisand, etc.
     */
    private fun showConnectionShareMenu(anchor: View, profile: ConnectionProfile) {
        val link = profile.shareLink?.takeIf(String::isNotBlank)
        if (link == null) {
            Toast.makeText(this, R.string.connection_share_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        catClientPopupMenu(anchor).apply {
            menu.add(R.string.subscription_action_copy_link).setOnMenuItemClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cat-config", link))
                Toast.makeText(this@MainActivity, R.string.free_copied, Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MainActivity, R.string.subscription_share_failed, Toast.LENGTH_SHORT).show()
                    }
                true
            }
            show()
        }
    }

    /* ---- share a remote subscription with other clients (v2rayNG / V2Box / …) ---- */

    private fun subscriptionLink(item: UserSubscription): String = item.input.trim()

    private fun copySubscriptionLink(item: UserSubscription) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("subscription", subscriptionLink(item)))
        Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareSubscriptionLink(item: UserSubscription) {
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
    private fun openSubscriptionInApp(item: UserSubscription, app: String) {
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
    private fun openSubscriptionInfoPage(item: UserSubscription) {
        val link = subscriptionLink(item)
        val info = Regex("^(https://[^/]+)/u/([^/?#]+)").find(link)
            ?.let { "${it.groupValues[1]}/info/${it.groupValues[2]}" }
            ?: link.substringBefore('#').let { if (it.contains('?')) "$it&web=1" else "$it?web=1" }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info))) }
            .onFailure { copySubscriptionLink(item) }
    }

    private fun confirmDeleteSubscription(item: UserSubscription) {
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

    private fun onSubscriptionSelected() {
        locationPreferenceStore.clearSelectedCountry()
        renderSubscriptions()
        renderConnectionDetails(buttonModel.state)
        renderConnectionSelection()
        refreshLocationOptions()
        if (buttonModel.state == VpnState.Started) {
            Toast.makeText(this, R.string.subscription_selected_reconnect, Toast.LENGTH_LONG).show()
        }
    }

    private fun connectionCountLabel(count: Int): String =
        resources.getQuantityString(R.plurals.connection_count, count, count)

    private fun localizedSubscriptionError(error: String): String = when {
        error.isBlank() -> ""
        else -> getString(R.string.subscription_operation_failed)
    }

    private fun selectedSubscriptionName(): String {
        val store = SubscriptionStore(this)
        val selectedId = store.readSelectedSubscriptionId()
        return store.readUserSubscription(selectedId)?.name ?: builtInSubscriptionName(selectedId)
    }

    private fun builtInSubscriptionName(id: String): String = getString(
        if (id == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID) {
            R.string.subscription_public_name
        } else {
            R.string.subscription_private_name
        },
    )

    private fun renderConnectionDetails(state: VpnState) {
        if (!::connectionDetailsText.isInitialized) return
        connectionDetailsText.text = connectionDetails.takeIf { state == VpnState.Started }.orEmpty()
        connectionDetailsText.visibility =
            if (connectionDetailsText.text.isNotBlank()) View.VISIBLE else View.GONE
        if (::dashboardChainText.isInitialized) {
            dashboardChainText.text = when {
                state == VpnState.Started && activeChainHopCount > 1 ->
                    getString(R.string.connection_chain_active, activeChainHopCount)
                connectionChainPreferenceStore.read().enabled ->
                    getString(R.string.connection_chain_enabled)
                else -> ""
            }
            dashboardChainText.visibility =
                if (dashboardChainText.text.isNotBlank()) View.VISIBLE else View.GONE
        }
        renderDashboardConnectionMetadataVisibility()
    }

    private fun renderDashboardLocalEndpoint(mode: ConnectionMode) {
        if (!::dashboardLocalEndpointText.isInitialized) return
        dashboardLocalEndpointText.text = if (mode == ConnectionMode.Proxy) {
            getString(
                R.string.connection_mode_local_endpoint,
                "127.0.0.1",
                MihomoRuntimeDefaults.MIXED_PORT.toString(),
            )
        } else {
            ""
        }
        dashboardLocalEndpointText.visibility =
            if (dashboardLocalEndpointText.text.isNotEmpty()) View.VISIBLE else View.GONE
        renderDashboardConnectionMetadataVisibility()
    }

    private fun renderDashboardConnectionMetadataVisibility() {
        if (!::dashboardConnectionMetadataSection.isInitialized) return
        dashboardConnectionMetadataSection.visibility = if (
            connectionDetailsText.text.isNotBlank() ||
            dashboardLocalEndpointText.text.isNotBlank() ||
            (::dashboardChainText.isInitialized && dashboardChainText.text.isNotBlank())
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun localizedError(@Suppress("UNUSED_PARAMETER") error: Throwable, @StringRes fallbackRes: Int): String =
        getString(fallbackRes)

    override fun onStart() {
        super.onStart()
        VpnWidgetProvider.refresh(this)
        DiagnosticLogger.info(this, "activity.onStart")
        val filter = IntentFilter().apply {
            addAction(Actions.STATE_CHANGED)
            addAction(Actions.CONNECTION_DELAY_TEST_CHANGED)
            addAction(Actions.CONNECTION_SPEED_TEST_CHANGED)
        }
        // Below API 33 a bare registerReceiver is implicitly exported, which would let any other
        // app broadcast STATE_CHANGED and paint a false "connected" state over the UI.
        // ContextCompat guards the pre-33 path with a signature-level permission.
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        applyRuntimeState(
            VpnRuntimeStateStore.read(this),
            VpnRuntimeStateStore.readSessionStartedAtElapsedMs(this),
            VpnRuntimeStateStore.readConnectionCountryFlag(this),
            VpnRuntimeStateStore.readDebugFrontingIp(this)
                .takeIf { it in frontingIpPreferenceStore.readFrontingIps() }
                .orEmpty(),
            VpnRuntimeStateStore.readConnectionDetails(this),
            VpnRuntimeStateStore.readActiveSubscriptionId(this),
            VpnRuntimeStateStore.readActiveConnectionTag(this),
            VpnRuntimeStateStore.readActiveConnectionFingerprint(this),
            VpnRuntimeStateStore.readChainHopCount(this),
            VpnRuntimeStateStore.readLiveSelectorReady(this),
            VpnRuntimeStateStore.readSelectableConnectionFingerprints(this),
            VpnRuntimeStateStore.readAlwaysOn(this),
            VpnRuntimeStateStore.readLockdown(this),
        )
        if (connectionTestingPageVisible) {
            showConnectionTestingPage(
                animate = false,
                rebuild = true,
                chainSlot = activeChainPickerSlot,
                pickerSubscriptionId = activeChainPickerSubscriptionId,
            )
        }
    }

    override fun onStop() {
        VpnWidgetProvider.refresh(this)
        DiagnosticLogger.info(this, "activity.onStop")
        mainHandler.removeCallbacks(timerRunnable)
        privacyPolicyDialog?.dismiss()
        privacyPolicyDialog = null
        connectionDelayTestListener = null
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        appUpdateUi.onResume()
    }

    override fun onPause() {
        appUpdateUi.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONNECTION_TESTING_PAGE, connectionTestingPageVisible)
        outState.putString(STATE_CHAIN_PICKER_SLOT, activeChainPickerSlot?.wireName)
        outState.putString(STATE_CHAIN_PICKER_SUBSCRIPTION, activeChainPickerSubscriptionId)
        outState.putBoolean(STATE_CONNECT_FLOW_PENDING, connectFlowPending)
        outState.putString(STATE_CONNECT_FLOW_ACTION, connectFlowAction)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            connectionTestingPageVisible -> closeConnectionTestingPage()
            advancedSettingsBackAction != null -> advancedSettingsBackAction?.invoke()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (appUpdateUi.onActivityResult(requestCode)) return
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            normalizedSubscriptionSource(scanResult.contents)?.let { source ->
                showAddSubscriptionDialog(source, scannedSubscriptionName(source).orEmpty())
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PERMISSION) return
        if (!connectFlowPending) {
            DiagnosticLogger.info(this, "permission.vpn.ignored", "resultCode=$resultCode reason=connect-canceled")
            return
        }
        val pendingAction = connectFlowAction
        connectFlowPending = false
        connectFlowAction = Actions.CONNECT
        if (resultCode == RESULT_OK) {
            DiagnosticLogger.info(this, "permission.vpn", "granted")
            startVpnService(pendingAction)
        } else {
            DiagnosticLogger.warn(this, "permission.vpn", "denied resultCode=$resultCode")
            AnalyticsEvents.connectionTryFailed(this)
            if (pendingAction == Actions.RECONNECT) {
                connectionModePreferenceStore.save(ConnectionMode.Proxy)
                buttonModel.onStateChanged(VpnState.Started)
                renderState(VpnState.Started)
            } else {
                buttonModel.onStateChanged(VpnState.Stopped)
                renderState(VpnState.Stopped)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            DiagnosticLogger.info(
                this,
                "permission.notification",
                "result=${grantResults.joinToString()}",
            )
            if (!connectFlowPending) {
                DiagnosticLogger.info(this, "permission.notification.ignored", "reason=connect-canceled")
                return
            }
            requestVpnPermissionThenConnect()
        }
    }

    private fun buildDashboard(): View {
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
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(TextView(this@MainActivity).apply {
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
            AppAccentPreferenceStore(this@MainActivity).read().let { setThemeColors(palette.teal, palette.secondary, it == AppAccent.Lavender) }
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
        val signalSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(heroFrame, LinearLayout.LayoutParams(-1, dp(350)))
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(withAlpha(palette.surfaceElevated1, 235))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { refreshDashboardIp() }
            homeFlagBadge = FlagBadgeView(this@MainActivity).apply { fallbackColor = palette.surfaceElevated2 }
            addView(homeFlagBadge, LinearLayout.LayoutParams(dp(42), dp(30)).apply { marginEnd = dp(12) })
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(connectionCountryText, LinearLayout.LayoutParams(-1, -2))
                    addView(
                        LinearLayout(this@MainActivity).apply {
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
                TextView(this@MainActivity).apply {
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
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            TextView(this@MainActivity).apply {
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
                            TextView(this@MainActivity).apply {
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
        val trafficRow = LinearLayout(this).apply {
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
                View(this@MainActivity).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
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
                View(this@MainActivity).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(18)
                    marginEnd = dp(18)
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
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
            addView(View(this@MainActivity).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(18)
                    marginEnd = dp(18)
                })
            addView(connectionSelectorRow, LinearLayout.LayoutParams(-1, -2))
            addView(homeChainSelectorRows, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@MainActivity).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
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
        val activeConfigCard = LinearLayout(this).apply {
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
                TextView(this@MainActivity).apply {
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
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(activeConfigTitle, LinearLayout.LayoutParams(-1, -2))
                    addView(TextView(this@MainActivity).apply {
                        setText(R.string.home_tap_change_server)
                        textSize = 13f
                        typeface = CatClientBodyTypeface
                        setTextColor(TEXT_SECONDARY)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) },
            )
            addView(TextView(this@MainActivity).apply {
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
        dashboardContent.apply {
            addView(headerBlock, contentParams(dp(12)))
            addView(locationPill, contentParams(dp(14)))
            addView(signalSection, contentParams(dp(4)))
            addView(trafficRow, contentParams(dp(18)))
            addView(activeConfigCard, contentParams(dp(14)))
            addView(connectActionButton, contentParams(dp(14)))
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

    private fun buildAdvancedScreen(): View {
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
        settingsSubscriptionSelectorRow = appPreferenceRow(
            R.string.settings_subscription_title,
            selectedSubscriptionName(),
            ::showSubscriptionSelectorMenu,
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
            val store = AppAccentPreferenceStore(this@MainActivity)
            val selected = store.read()
            AppAccent.entries.chunked(4).forEach { rowItems ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    rowItems.forEach { preset ->
                        val preview = CatClientDesignTokens.themed(CatClientDesignTokens.palette(palette.isDark), preset, palette.isDark)
                        addView(LinearLayout(this@MainActivity).apply {
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
                            addView(View(this@MainActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(preview.teal) } }, LinearLayout.LayoutParams(dp(26), dp(26)))
                            addView(LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                addView(View(this@MainActivity).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(preview.surfaceElevated2) } }, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginEnd = dp(3) })
                                addView(View(this@MainActivity).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(preview.secondary) } }, LinearLayout.LayoutParams(0, dp(12), 1f).apply { marginStart = dp(3) })
                            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                            addView(View(this@MainActivity).apply { background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(preview.brandPillBackground) } }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(5) })
                            addView(View(this@MainActivity).apply { background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(preview.teal) } }, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(5) })
                            addView(TextView(this@MainActivity).apply {
                                setText(preset.labelRes); textSize = 11f; gravity = Gravity.CENTER; maxLines = 1
                                typeface = if (preset == selected) CatClientBodyBoldTypeface else CatClientBodyTypeface
                                setTextColor(TEXT_PRIMARY)
                            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                    }
                    repeat(4 - rowItems.size) { addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        val hapticSwitch = MaterialSwitch(this).apply {
            isChecked = connectHapticEnabled()
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("cat_client_theme", MODE_PRIVATE).edit().putBoolean("haptic_connect", checked).apply()
                if (checked) connectHaptic()
            }
        }
        val hapticRow = advancedToggleRow(getString(R.string.haptic_connect_title), getString(R.string.haptic_connect_detail), hapticSwitch)
        listOf<View>(themeSelectorRow, accentSelectorRow, swatchStrip, languageSelectorRow, hapticRow).forEachIndexed { index, row ->
            if (index > 0 && row !== swatchStrip) {
                appearancePanel.addView(
                    View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16); marginEnd = dp(16) },
                )
            }
            appearancePanel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        appearanceSettings.addView(appearancePanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

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
            R.string.dpi_preset_default to DpiFragmentPreset.DEFAULT,
            R.string.dpi_preset_mci to DpiFragmentPreset.MCI,
            R.string.dpi_preset_irancell to DpiFragmentPreset.IRANCELL,
            R.string.dpi_preset_rightel to DpiFragmentPreset.RIGHTEL,
            R.string.dpi_preset_tci to DpiFragmentPreset.TCI,
            R.string.dpi_preset_gaming to DpiFragmentPreset.GAMING,
        ).forEach { (labelRes, preset) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                isChecked = dpiBypassPreferenceStore.presetId() == preset.id
                textSize = 11f
                setTextColor(TEXT_PRIMARY)
                chipStrokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 170))
                chipStrokeWidth = dp(1).toFloat()
                chipBackgroundColor = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        presetChips.filterNot { it === this }.forEach { it.isChecked = false }
                        dpiBypassPreferenceStore.savePreset(preset.id)
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.dpi_preset_applied, getString(labelRes)),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            presetChips += chip
            presetRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
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
                TextView(this@MainActivity).apply {
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
                TextView(this@MainActivity).apply {
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
                TextView(this@MainActivity).apply {
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
                TextView(this@MainActivity).apply {
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
                TextView(this@MainActivity).apply {
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
                    if (!dnsPrivacyInputUpdating && ::dnsPrivacyErrorText.isInitialized) {
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
                    DiagnosticLogger.clear(this@MainActivity)
                    Toast.makeText(this@MainActivity, R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show()
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
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.settings_title)
                    textSize = 30f
                    typeface = CatClientDisplayTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
            )
            addView(
                TextView(this@MainActivity).apply {
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
            advancedSettingsBackAction = null
            scrollView.visibility = View.GONE
            indexScrollView.visibility = View.VISIBLE
            indexScrollView.scrollTo(0, 0)
            ViewCompat.requestApplyInsets(indexScrollView)
        }

        fun showCategory(@StringRes titleRes: Int, content: View) {
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
                        TextView(this@MainActivity).apply {
                            setText(titleRes)
                            textSize = 24f
                            typeface = CatClientDisplayTypeface
                            setTextColor(TEXT_PRIMARY)
                            includeFontPadding = false
                            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                            textDirection = View.TEXT_DIRECTION_LOCALE
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
        fun addCategory(
            @StringRes titleRes: Int,
            @StringRes detailRes: Int,
            content: View,
            addDivider: Boolean = true,
            onOpen: (() -> Unit)? = null,
            badge: View? = null,
        ) {
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
                        TextView(this@MainActivity).apply {
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
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                            addView(
                                TextView(this@MainActivity).apply {
                                    setText(titleRes)
                                    textSize = 16f
                                    typeface = CatClientBodyBoldTypeface
                                    setTextColor(TEXT_PRIMARY)
                                    includeFontPadding = false
                                },
                            )
                            addView(
                                TextView(this@MainActivity).apply {
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
                        TextView(this@MainActivity).apply {
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
        categoriesPanel.addView(
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
        categoriesPanel.addView(
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
        indexBody.addView(
            categoriesPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
        )
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
                Toast.makeText(this@MainActivity, getString(R.string.update_installed_version, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
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

    /* ============================== Cloud tab ============================== */

    private fun cloudActionButton(
        @StringRes labelRes: Int,
        @DrawableRes iconRes: Int,
        accent: Boolean,
        action: (View) -> Unit,
    ): MaterialButton = MaterialButton(this).apply {
        setText(labelRes)
        setIconResource(iconRes)
        iconTint = ColorStateList.valueOf(if (accent) TEAL else TEXT_SECONDARY)
        iconSize = dp(18)
        iconPadding = dp(8)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        setAllCaps(false)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(48)
        minimumHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(18)
        setPaddingRelative(dp(16), 0, dp(16), 0)
        backgroundTintList = ColorStateList.valueOf(if (accent) withAlpha(TEAL, 42) else withAlpha(palette.surfaceElevated2, 190))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(if (accent) withAlpha(TEAL, 180) else withAlpha(OUTLINE, 220))
        rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 36))
        elevation = dp(2).toFloat()
        stateListAnimator = null
        setTextColor(if (accent) TEAL else TEXT_PRIMARY)
        setOnClickListener(action)
    }

    private fun cloudScopeLabel(scope: CloudflareWorker.PanelScope): String = when (scope) {
        CloudflareWorker.PanelScope.CF_WORKER -> getString(R.string.cloud_scope_worker)
        CloudflareWorker.PanelScope.SERVER -> getString(R.string.cloud_scope_server)
        CloudflareWorker.PanelScope.TUNNEL -> getString(R.string.cloud_scope_tunnel)
    }

    /* ------------------------------------------------------------------ */
    /* IP scanner screen: clean Cloudflare IPs (SNI + fronting)            */
    /* ------------------------------------------------------------------ */

    private fun buildScannerScreen(): View {
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
        body.addView(
            advancedSettingsPanel().apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { showProxyScannerPage() }
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        addView(TextView(this@MainActivity).apply { text = "🛰"; textSize = 22f }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(TextView(this@MainActivity).apply { setText(R.string.scanner_proxy_entry); textSize = 15f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY); includeFontPadding = false })
                                addView(TextView(this@MainActivity).apply { setText(R.string.scanner_proxy_entry_detail); textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); includeFontPadding = false }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                        addView(TextView(this@MainActivity).apply { text = "›"; textSize = 22f; setTextColor(TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2))
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) },
        )

        body.addView(
            advancedSettingsPanel().apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { showProxyIpScannerPage() }
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        addView(TextView(this@MainActivity).apply { text = "🔁"; textSize = 22f }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(TextView(this@MainActivity).apply { setText(R.string.pip_entry); textSize = 15f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY); includeFontPadding = false })
                                addView(TextView(this@MainActivity).apply { setText(R.string.pip_entry_detail); textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); includeFontPadding = false }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                        addView(TextView(this@MainActivity).apply { text = "›"; textSize = 22f; setTextColor(TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2))
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) },
        )

        // ---- Static IP: exactly one fronting address, never rotated / failed-over / re-ranked ----
        body.addView(buildStaticIpCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // ---- SNI scanner: which SNI works best (fastest TLS) with the active IP ----
        body.addView(buildSniScannerCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

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
        val autoChip = Chip(this@MainActivity).apply {
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
                    this@MainActivity,
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
        val pinChip = Chip(this@MainActivity).apply {
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
                    this@MainActivity,
                    if (checked) R.string.ip_health_pin_on else R.string.ip_health_pin_off,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val genomeStoreUi = NetworkGenomeStore(this)
        val failoverChip = Chip(this@MainActivity).apply {
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
                Toast.makeText(this@MainActivity, if (checked) R.string.engine_auto_failover_on else R.string.engine_auto_failover_off, Toast.LENGTH_SHORT).show()
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
            val chip = Chip(this@MainActivity).apply {
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
        body.addView(healthCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        renderScannerIpHealth()
        startIpHealthLoop()

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
            recommendedScannerSnis().forEach { suggestion ->
                addView(Chip(this@MainActivity).apply {
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
                scannerPortGroup.addView(Chip(this@MainActivity).apply {
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
            scannerPortGroup.addView(Chip(this@MainActivity).apply {
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
        }
        controls.addView(
            scannerFieldLayout(getString(R.string.scanner_subnets_label), scannerSubnetsInput),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.scanner_ranges_note)
                    textSize = 11.5f
                    typeface = CatClientBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
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
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(
                MaterialButton(this@MainActivity).apply {
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
                MaterialButton(this@MainActivity).apply {
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
                    Toast.makeText(this@MainActivity, R.string.scanner_no_results, Toast.LENGTH_SHORT).show()
                } else {
                    buildSubscriptionFromScan(source)
                }
            }
        }
        body.addView(scannerBuildButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
        val scannerSpeedButton = MaterialButton(this).apply {
            setText(R.string.scanner_speed_btn)
            textSize = 13.5f
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
        body.addView(scannerSpeedButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        scannerResultsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        body.addView(
            scannerResultsList,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        renderScannerResults()

        return scroll.apply { addView(body, FrameLayout.LayoutParams(-1, -2)) }
    }

    private fun scannerInput(hint: String, initial: String): TextInputEditText =
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

    private fun scannerFieldLayout(hint: String, input: TextInputEditText): TextInputLayout =
        TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(input)
        }

    private fun recommendedScannerSnis(): List<String> = buildList {
        addAll(detectPanelSnisFromSubscriptions())
        addAll(IpScanner.RECOMMENDED_SNIS)
    }.map(String::lowercase).filter { it.isNotBlank() }.distinct().take(8)

    private fun scannerSniPreference(): String {
        val saved = getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
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
    private fun detectPanelSniFromSubscriptions(): String? {
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
    private fun detectPanelSnisFromSubscriptions(): List<String> {
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

    /** Detected panel identity (uuid + WS paths) for rebuilding configs with new IPs. */
    private data class PanelIdentity(
        val host: String,
        val uuid: String,
        val vlessPath: String,
        val trojanPath: String,
        val trojanPassword: String,
    )

    private fun detectPanelIdentity(sni: String): PanelIdentity? {
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
    private fun buildSubscriptionFromScan(results: List<IpScanner.ScanResult>) {
        val sni = scannerSniInput.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_SCANNER_SNI }
        val identity = detectPanelIdentity(sni)
        if (identity == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scanner_build_title)
                .setMessage(getString(R.string.scanner_build_no_panel, sni))
                .setPositiveButton(R.string.scanner_build_apply_fronting) { _, _ -> applyScannerResult(results.firstOrNull()) }
                .setNegativeButton(R.string.split_tunnel_cancel, null)
                .show()
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
                    val vlessLabel = "🐱 Cat · " + location + " · VLESS · " + port + " · " + r.flag
                    val vlessCommon = "&type=ws&path=" + Uri.encode(identity.vlessPath) + "&host=" + Uri.encode(identity.host)
                    add(
                        "vless://" + identity.uuid + "@" + r.ip + ":" + port + "?encryption=none&" +
                            transport + vlessCommon + "#" + Uri.encode(vlessLabel),
                    )
                    val trojanLabel = "🐱 Cat · " + location + " · Trojan · " + port + " · " + r.flag
                    val trojanCommon = "&type=ws&path=" + Uri.encode(identity.trojanPath) + "&host=" + Uri.encode(identity.host)
                    add(
                        "trojan://" + Uri.encode(identity.trojanPassword) + "@" + r.ip + ":" + port + "?" +
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
                Toast.makeText(this@MainActivity, R.string.free_quick_add_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            userSubscriptionManager.select(added.id)
            renderSubscriptions()
            onSubscriptionSelected()
            Toast.makeText(this@MainActivity, getString(R.string.scanner_build_done, links.size), Toast.LENGTH_LONG).show()
            beginConnectFlow(Actions.CONNECT)
        }
    }

    private fun saveScannerSni(value: String) {
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(SCANNER_SNI_KEY, value)
            .apply()
    }

    private fun scannerPortPreference(): Int {
        val saved = getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .getInt(SCANNER_PORT_KEY, 443)
        return saved.takeIf { it in 1..65535 } ?: 443
    }

    private fun saveScannerPort(value: Int) {
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putInt(SCANNER_PORT_KEY, value)
            .apply()
    }

    private fun scannerRangesPreference(): String {
        val saved = getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE).getString(SCANNER_RANGES_KEY, null)
        return saved ?: IpScanner.defaultRangesText()
    }

    private fun saveScannerRanges(value: String) {
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(SCANNER_RANGES_KEY, value)
            .apply()
    }

    private fun startIpScanner() {
        if (scannerRunning) return
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
        scannerResultsList.removeAllViews()
        scannerApplyButton.isEnabled = false
        val options = IpScanner.ScanOptions(
            sni = sni,
            port = scannerPort,
            customSubnets = customSubnets,
            includeBuiltin = true,
            includeIranLibrary = true,
            perRange = SCANNER_PER_RANGE,
            randomSample = true,
            concurrency = SCANNER_CONCURRENCY,
            connectTimeoutMs = SCANNER_CONNECT_TIMEOUT_MS,
            tlsTimeoutMs = SCANNER_TLS_TIMEOUT_MS,
            verifyHttp = true,
        )
        scannerProgressBar.progress = 0
        scannerJob = activityScope.launch {
            // Dual-stack: probe once whether v6 really works here; if so the v6
            // ranges join the walk and results of both families are ranked together.
            val ipv6 = withContext(Dispatchers.IO) { IpScanner.hasIpv6Connectivity() }
            if (ipv6) mainHandler.post { scannerStatusText.setText(R.string.scanner_ipv6_detected) }
            val found = runCatching {
                IpScanner.scan(this@MainActivity, options.copy(includeIpv6 = ipv6)) { done, total, result ->
                    // Called from an IO thread for every finished candidate.
                    mainHandler.post {
                        if (!scannerRunning) return@post
                        val percent = (done * 100) / total.coerceAtLeast(1)
                        scannerProgressBar.progress = percent
                        if (result != null && scannerLiveResults.none { it.ip == result.ip }) {
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
            IpHealthMonitor.seed(
                IpHealthStore(this@MainActivity),
                scannerResults,
                scannerSniPreference(),
                scannerPortPreference(),
            )
            renderScannerResults()
            renderScannerIpHealth()
            scannerStatusText.text = if (found.isEmpty()) {
                getString(R.string.scanner_no_results)
            } else {
                getString(R.string.scanner_done_detailed, found.size, found.count { it.tlsOk })
            }
        }
    }

    private fun stopIpScanner() {
        scannerJob?.cancel()
        scannerJob = null
        scannerRunning = false
        scannerStartButton.isEnabled = true
        scannerStopButton.isEnabled = false
        scannerProgressBar.visibility = View.GONE
        scannerStatusText.setText(R.string.scanner_stopped)
    }

    /** XIU2-style download throughput for the top verified rows; sorts by speed after. */
    private fun runScannerSpeedTest() {
        if (scannerSpeedTestRunning) return
        val targets = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
            .filter { it.tlsOk }
            .distinctBy { it.ip }
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
                if (speed != null) measured[target.ip] = speed
                mainHandler.post {
                    val updated = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList())
                        .map { if (it.ip == target.ip && speed != null) it.copy(speedBps = speed) else it }
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
                    Toast.makeText(this@MainActivity, getString(R.string.scanner_speed_done, best.key, mbps), Toast.LENGTH_LONG).show()
                } else {
                    scannerStatusText.setText(R.string.scanner_no_results)
                }
            }
        }

    }

    private var ipHealthJob: Job? = null
    private var ipHealthSweeping = false
    private var ipHealthStatusView: TextView? = null
    private var ipHealthCountryRow: LinearLayout? = null

    /** Pool grouped by location: one chip per country (tap = lock failover/replacements to it), IPs listed under the lock. */
    private fun renderIpHealthCountries(entries: List<IpHealthEntry>) {
        val row = ipHealthCountryRow ?: return
        row.removeAllViews()
        if (entries.isEmpty()) return
        val store = IpHealthStore(this)
        val active = frontingIps.firstOrNull()
        val groups = entries.groupBy { it.countryCode?.uppercase().orEmpty() }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<IpHealthEntry>>> { it.value.size }.thenBy { it.key })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        fun chip(label: String, selected: Boolean, onClick: () -> Unit) = Chip(this@MainActivity).apply {
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
                    (if (e.ip == active) "● " else "   ") + e.ip + (e.colo?.let { "  $it" } ?: "") + (if (e.pingMs > 0) "  ${e.pingMs} ms" else "") + (if (e.fails > 0) "  ✗${e.fails}" else "")
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun renderScannerIpHealth() {
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
                append(getString(R.string.ip_health_best, best.ip, best.pingMs))
            }
            if (last != null) {
                append('\n')
                append(getString(R.string.ip_health_last_event, last.removedIp, last.addedIp ?: "—"))
            }
            val genomes = NetworkGenomeStore(this@MainActivity)
            val stats = genomes.stats()
            val network = NetworkContext.key(this@MainActivity)
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
        }
    }

    private fun startIpHealthLoop() {
        ipHealthJob?.cancel()
        ipHealthJob = null
        // Static-IP pin: nothing is ever swapped automatically.
        if (!IpHealthStore(this).autoEnabled || IpHealthStore(this).pinned) return
        ipHealthJob = activityScope.launch {
            while (true) {
                runIpHealthSweep(silent = true)
                delay(IpHealthStore(this@MainActivity).intervalMinutes * 60_000L)
            }
        }
    }

    /**
     * Self-healing control plane: DETECT (anomaly on the active fronting address from its
     * own history) → SELECT (best-ranked standby) → WARM/VERIFY (two fresh probes) →
     * PROMOTE (move to the front of the fronting list, reconnect) → LEARN (stats).
     */
    private suspend fun runCognitiveFailover(pool: List<IpHealthEntry>, genomes: NetworkGenomeStore, network: String, sni: String, port: Int) {
        if (!genomes.autoFailover || IpHealthStore(this).pinned) return
        if (!currentVpnStateIsStarted()) return
        val active = frontingIps.firstOrNull() ?: return
        val plan = withContext(Dispatchers.IO) {
            val options = IpScanner.ScanOptions(sni = sni, port = port, includeBuiltin = false, includeIranLibrary = false, verifyHttp = true)
            CognitiveEngine.planMigration(active, pool, genomes.genomes(pool.map { it.ip }), network, country = IpHealthStore(this@MainActivity).preferredCountry) { candidate ->
                val r = IpScanner.probe(candidate.ip, options.copy(sni = candidate.sni.ifBlank { sni }))
                val ok = r != null && r.tlsOk && r.pingMs <= IpHealthMonitor.SLOW_MS
                genomes.record(candidate.ip, Observation(System.currentTimeMillis(), network, r?.pingMs ?: -1L, r?.tlsMs ?: -1L, ok, if (ok) FailureClass.OK else CognitiveEngine.classify(r, IpHealthMonitor.SLOW_MS, genomes.genome(candidate.ip))))
                ok
            }
        } ?: return
        val started = System.currentTimeMillis()
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        frontingIps = FrontingIpPolicy.normalizeIps((listOf(plan.to.ip) + frontingIps.filter { it != plan.to.ip }).joinToString(","))
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
                lastFailoverTo = plan.to.ip,
                lastFailoverReason = plan.reason,
            )
        }
        renderScannerIpHealth()
        Toast.makeText(this, getString(if (plan.countryChanged) R.string.engine_failover_country_toast else R.string.engine_failover_toast, plan.from, plan.to.ip, plan.reason), Toast.LENGTH_LONG).show()
    }

    private fun runIpHealthSweep(silent: Boolean = false, failFast: Boolean = false) {
        if (ipHealthSweeping) return
        ipHealthSweeping = true
        val store = IpHealthStore(this)
        val sni = scannerSniPreference().ifBlank { IpScanner.RECOMMENDED_SNIS.first() }
        val port = scannerPortPreference()
        if (!silent) Toast.makeText(this, R.string.ip_health_sweeping, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            try {
                val genomeStore = NetworkGenomeStore(this@MainActivity)
                val network = NetworkContext.key(this@MainActivity)
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
                        Toast.makeText(this@MainActivity, R.string.ip_health_all_ok, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.ip_health_rotated, result.removed.size, result.added.size),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.ip_health_failed, e.message ?: e::class.java.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                ipHealthSweeping = false
            }
        }
    }

    private fun renderScannerResults() {
        if (!::scannerResultsList.isInitialized) return
        val visible = if (scannerRunning) scannerLiveResults.toList() else scannerResults
        scannerResultsList.removeAllViews()
        if (visible.isEmpty()) {
            scannerResultsList.addView(
                TextView(this).apply {
                    setText(R.string.scanner_results_empty)
                    textSize = 12.5f
                    typeface = CatClientBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            scannerApplyButton.isEnabled = false
            if (::scannerBuildButton.isInitialized) scannerBuildButton.isEnabled = false
            return
        }
        val hasVerifiedResult = visible.any { it.tlsOk }
        scannerApplyButton.isEnabled = hasVerifiedResult
        if (::scannerBuildButton.isInitialized) scannerBuildButton.isEnabled = hasVerifiedResult
        visible.take(SCANNER_VISIBLE_RESULTS).forEachIndexed { index, result ->
            scannerResultsList.addView(
                scannerResultRow(index + 1, result),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
            )
        }
    }

    private fun scannerResultRow(rank: Int, result: IpScanner.ScanResult): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            background = glassSurfaceDrawable(radiusDp = 12)
            clipToOutline = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setSelectableBackground()
            setOnClickListener {
                copyScannerIp(result)
            }
        }
        row.addView(
            TextView(this).apply {
                text = if (result.tlsOk) "TLS ✓" else "TCP"
                textSize = 10.5f
                typeface = CatClientBodyBoldTypeface
                setTextColor(if (result.tlsOk) TEAL else TEXT_SECONDARY)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(withAlpha(if (result.tlsOk) TEAL else TEXT_SECONDARY, 24))
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
        )
        row.addView(
            TextView(this).apply {
                text = getString(R.string.scanner_result_rank, rank, result.flag)
                textSize = 12.5f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        val locationColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(TextView(this@MainActivity).apply {
                text = "${result.ip}:${result.port}"
                textSize = 14f
                typeface = CatClientDataTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                includeFontPadding = false
            }, LinearLayout.LayoutParams(-1, -2))
            val location = if (result.countryCode != null) {
                result.countryName ?: getString(R.string.scanner_result_edge_fallback)
            } else {
                result.colo?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.scanner_result_edge_fallback)
            }
            if (location.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.scanner_result_location, result.flag, location)
                    textSize = 10.5f
                    typeface = CatClientBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            result.speedBps?.let { bps ->
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.scanner_result_speed, bps / 1_000_000.0)
                    textSize = 10f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEAL)
                    includeFontPadding = false
                    maxLines = 1
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
            result.sourceRange?.takeIf { it.isNotBlank() }?.let { range ->
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.scanner_result_range, range)
                    textSize = 9.5f
                    typeface = CatClientBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
            if (rank == 1) {
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.scanner_recommended)
                    textSize = 9.5f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEAL)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }
        }
        row.addView(
            locationColumn,
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) },
        )
        row.addView(
            TextView(this).apply {
                text = getString(R.string.scanner_result_ping, result.pingMs)
                textSize = 13f
                typeface = CatClientBodyBoldTypeface
                setTextColor(
                    when {
                        result.pingMs < SCANNER_GOOD_MS -> TEAL
                        result.pingMs < SCANNER_FAIR_MS -> AMBER
                        else -> ERROR
                    },
                )
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-2, -2),
        )
        row.addView(
            MaterialButton(this).apply {
                setText(if (rank == 1) R.string.scanner_apply_best else R.string.scanner_use)
                textSize = 12f
                typeface = CatClientBodyBoldTypeface
                isAllCaps = false
                cornerRadius = dp(15)
                minWidth = 0
                minimumWidth = 0
                backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 38))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 150))
                elevation = dp(1).toFloat()
                stateListAnimator = null
                setTextColor(TEAL)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { applyScannerResult(result) }
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
        )
        return row
    }

    private fun sendScanToPanel() {
        val source = if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList()
        // ONLY IPs whose TLS handshake actually passed ON THIS NETWORK are sent
        // (checked → then → panel). No unchecked fallback: an unverified IP in
        // the panel produces dead 💦 configs, which is exactly what the user
        // complained about («پروکسی آی‌پیا کار نمی‌کنن»).
        val verified = source.filter { it.tlsOk }
        if (verified.isEmpty()) {
            Toast.makeText(this, R.string.scanner_unverified_only, Toast.LENGTH_LONG).show()
            return
        }
        val lines = verified.take(60).map { it.panelLine }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-ips", lines.joinToString("\n")))
        // The paste-fallback is always available, so SAY it: Panel → Tools →
        // «Import scan results» accepts these very lines (ip:port#CC).
        Toast.makeText(this, getString(R.string.scanner_panel_clipboard, lines.size), Toast.LENGTH_LONG).show()
        // Push straight into the panel over its API (login → POST /api/ips, append).
        // The old deep link opened the scanner SNI instead of the panel, so IPs
        // never arrived — the panel host comes from the subscriptions now.
        val base = panelBaseUrl()?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.scanner_panel_none, lines.size), Toast.LENGTH_LONG).show()
            return
        }
        val storedUuid = runCatching { PanelDeploymentStore(this).uuidFor(base) }.getOrNull().orEmpty()
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.pip_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Default panel password IS the deploy UUID — the app already knows
            // it, so prefill: send-to-panel becomes one tap (still editable for
            // users who changed their panel password).
            if (storedUuid.isNotBlank()) setText(storedUuid)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scanner_send_panel)
            .setMessage(base.removePrefix("https://"))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val password = input.text?.toString().orEmpty()
                activityScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { pushIpsToPanel(base, password, lines) } }
                    result.onSuccess { n ->
                        val msg = when {
                            n > 0 -> R.string.scanner_panel_saved
                            else -> R.string.scanner_panel_zero
                        }
                        Toast.makeText(this@MainActivity, getString(msg, n), Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(this@MainActivity, getString(R.string.pip_save_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Import clean IPs into Cat Panel over its API: POST /api/login (password →
     * session cookie), then POST /api/ips {ips:[…]} which appends and returns the
     * new list size. Nothing is stored on the phone. Mirror of
     * ProxyIpScannerPage.pushToPanel.
     */
    private fun pushIpsToPanel(base: String, password: String, lines: List<String>): Int {
        fun call(path: String, method: String, body: String?, cookie: String?): Pair<HttpURLConnection, String> {
            val conn = URL("$base$path").openConnection() as HttpURLConnection
            conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
            val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            return conn to stream.bufferedReader().readText()
        }
        val (login, loginBody) = call("/api/login", "POST", org.json.JSONObject().put("password", password).toString(), null)
        if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(getString(R.string.pip_wrong_password))
        val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
        login.disconnect()
        val (post, postBody) = call("/api/ips", "POST", org.json.JSONObject().put("ips", org.json.JSONArray(lines)).toString(), cookie)
        val ok = post.responseCode == 200 && org.json.JSONObject(postBody).optBoolean("ok")
        val count = if (ok) org.json.JSONObject(postBody).optInt("count", 0) else 0
        post.disconnect()
        if (!ok) throw IllegalStateException("HTTP ${post.responseCode}")
        return count
    }

    private fun copyScannerIp(result: IpScanner.ScanResult) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("clean-ip", result.ip))
        Toast.makeText(this, getString(R.string.scanner_ip_copied, result.ip), Toast.LENGTH_SHORT).show()
    }

    /**
     * Applies a scanned IP as a fronting endpoint: connects to that clean CDN IP
     * while the profile's SNI/Host (the panel domain) stays untouched.
     */
    private fun applyScannerResult(result: IpScanner.ScanResult? = null) {
        val target = result ?: scannerResults.firstOrNull()
        if (target == null) {
            Toast.makeText(this, R.string.scanner_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        // Dual-stack apply: when nothing specific was tapped, take the best v4 AND
        // the best v6 so the tunnel survives either family dropping.
        val companions = if (result == null) {
            val pool = (if (scannerResults.isNotEmpty()) scannerResults else scannerLiveResults.toList()).filter { it.tlsOk }
            val otherFamily = pool.firstOrNull { IpScanner.isIpv6(it.ip) != IpScanner.isIpv6(target.ip) }
            listOfNotNull(otherFamily?.ip)
        } else {
            emptyList()
        }
        frontingIps = FrontingIpPolicy.normalizeIps((frontingIps + target.ip + companions).joinToString(","))
        renderFrontingIpChips()
        if (!saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) return
        Toast.makeText(
            this,
            getString(R.string.scanner_applied, target.ip, target.pingMs),
            Toast.LENGTH_LONG,
        ).show()
    }

    /* ------------------------------------------------------------------ */
    /* Panel status & one-tap recovery (Cloudflare abuse-report 1101)      */
    /* ------------------------------------------------------------------ */

    private fun buildPanelStatusCard(): View {
        val card = advancedSettingsPanel()
        card.addView(
            TextView(this).apply {
                setText(R.string.cloud_status_title)
                textSize = 15f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            },
        )
        card.addView(
            TextView(this).apply {
                setText(R.string.cloud_status_detail)
                textSize = 12f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
        )
        val check = MaterialButton(this).apply {
            text = getString(R.string.cloud_status_check)
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(14)
            setPaddingRelative(dp(16), 0, dp(16), 0)
            backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 190))
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 220))
            setTextColor(TEXT_PRIMARY)
            setOnClickListener { onPanelStatusCheck() }
        }
        card.addView(check, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return card
    }

    private fun onPanelStatusCheck() {
        val dep = PanelDeploymentStore(this).deployments().firstOrNull()
        if (dep == null) {
            Toast.makeText(this, R.string.cloud_status_none, Toast.LENGTH_SHORT).show()
            return
        }
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_status_checking))
            .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_status_checking_detail))))
            .setCancelable(false)
            .create()
        progress.show()
        activityScope.launch {
            val health = withContext(Dispatchers.IO) {
                runCatching { CloudflareWorker.checkPanelHealth(dep.workerUrl) }
                    .getOrElse { CloudflareWorker.PanelHealth("DOWN", it.message ?: "network error") }
            }
            progress.dismiss()
            if (health.state == "HEALTHY") {
                val deployed = runCatching { PanelUpdate.deployedVersion(dep.workerUrl) }.getOrNull()
                val newest = runCatching { newestPanelScript() }.getOrNull()
                showPanelManageDialog(dep, deployed, newest)
            } else {
                showPanelRecoveryDialog(dep.workerUrl, health.detail)
            }
        }
    }

    /** Version + update + delete for a healthy panel (deployed from this app). */
    private fun showPanelManageDialog(dep: PanelDeploymentRecord, deployed: String?, newest: PanelUpdate.PanelScript?) {
        val updateAvailable = newest != null && newest.version != deployed
        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(R.string.panel_manage_title) +
                    if (updateAvailable) "  ·  ⬆ v" + newest!!.version else "",
            )
            .setMessage(
                getString(
                    R.string.panel_manage_msg,
                    dep.workerUrl,
                    deployed ?: getString(R.string.panel_version_unknown),
                    newest?.version ?: getString(R.string.panel_version_unknown),
                ),
            )
            .setPositiveButton(R.string.panel_update_btn) { _, _ ->
                val script = newest
                if (script == null) {
                    Toast.makeText(this, R.string.panel_no_source, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val token = PanelDeploymentStore(this).tokenFor(dep.workerUrl).orEmpty()
                panelUpdateInProgress = true
                // No token? Token-free guided update — NEVER a token prompt.
                if (token.isBlank()) presentTokenlessPanelUpdate(dep, deployed, script, updateAvailable)
                else runPanelUpdate(dep, token, deployed, script)
            }
            .setNeutralButton(R.string.panel_delete) { _, _ -> confirmPanelDelete(dep) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmPanelDelete(dep: PanelDeploymentRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.panel_delete_confirm_title)
            .setMessage(getString(R.string.panel_delete_confirm_msg, dep.workerUrl))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.panel_delete) { _, _ ->
                val token = PanelDeploymentStore(this).tokenFor(dep.workerUrl).orEmpty()
                if (token.isBlank()) {
                    Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val progress = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.panel_delete_confirm_title)
                    .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_recover_running))))
                    .setCancelable(false)
                    .create()
                progress.show()
                activityScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { CloudflareWorker.deletePanel(token, dep.workerUrl) }
                    }
                    progress.dismiss()
                    result.onSuccess { summary ->
                        PanelDeploymentStore(this@MainActivity).remove(dep.workerUrl)
                        Toast.makeText(this@MainActivity, getString(R.string.panel_delete_done, summary), Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(this@MainActivity, getString(R.string.panel_delete_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                    }
                    renderCloudDeploymentHistory()
                }
            }
            .show()
    }

    private fun showPanelRecoveryDialog(panelUrl: String, detail: String) {
        val backup = PanelBackup.read(this)
        val explain = TextView(this).apply {
            text = getString(R.string.cloud_recover_explain, panelUrl, detail) + "\n\n" +
                if (backup != null) {
                    getString(R.string.cloud_recover_backup_ok, android.text.format.DateUtils.getRelativeTimeSpanString(backup.savedAt).toString())
                } else {
                    getString(R.string.cloud_recover_backup_none)
                }
            textSize = 13f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_SECONDARY)
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val tokenEdit = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.cloud_token_help)
            setText(PanelDeploymentStore(this@MainActivity).tokenFor(panelUrl).orEmpty())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(explain)
            addView(
                tokenEdit,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), dp(10), dp(20), 0) },
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_recover_title))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cloud_recover_start) { _, _ ->
                val token = tokenEdit.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runPanelRecovery(token, backup)
            }
            .show()
    }

    private fun runPanelRecovery(token: String, backup: PanelBackup.Snapshot?) {
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_recover_title))
            .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_recover_running))))
            .setCancelable(false)
            .create()
        progress.show()
        activityScope.launch {
            try {
                val permissions = CloudflareWorker.verifyToken(token)
                val accountId = permissions.accountId
                if (!permissions.valid || accountId == null) {
                    progress.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                // The snapshot must be captured BEFORE deploying: the deploy's own
                // stealth step replaces the stored backup with the new panel's state.
                val result = CloudflareWorker.deployBuiltIn(
                    this@MainActivity,
                    token,
                    accountId,
                    CloudflareWorker.randomWorkerName(),
                )
                PanelDeploymentStore(this@MainActivity).rememberToken(result.workerUrl, token)
                var usersNote = ""
                if (backup != null) {
                    val restored = withContext(Dispatchers.IO) {
                        runCatching {
                            CloudflareWorker.restorePanelState(
                                workerUrl = result.workerUrl,
                                settingsJson = backup.settingsJson,
                                usersJson = backup.usersJson,
                                password = result.uuid,
                            )
                        }.getOrElse { CloudflareWorker.PanelRestoreResult(false, 0) }
                    }
                    if (restored.settingsApplied) {
                        usersNote = getString(R.string.cloud_recover_users, restored.usersRestored)
                    }
                }
                progress.dismiss()
                showPanelRecoveryDone(result, usersNote)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.cloud_recover_failed, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showPanelRecoveryDone(result: CloudflareWorker.DeploymentResult, usersNote: String) {
        val message = buildString {
            append(getString(R.string.cloud_recover_done, result.panelUrl))
            append("\n")
            append(getString(R.string.cloud_recover_done_sub, result.subscriptionUrl))
            if (usersNote.isNotEmpty()) {
                append("\n")
                append(usersNote)
            }
            append("\n\n")
            append(getString(R.string.cloud_recover_done_note))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cloud_recover_done_title))
            .setMessage(message)
            .setPositiveButton(R.string.cloud_recover_addsub) { _, _ ->
                showAddSubscriptionDialog(result.subscriptionUrl, "Cat Panel")
            }
            .setNeutralButton(R.string.cloud_recover_open) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl))) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildCloudScreen(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val body = MaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            maxWidthPx = dp(520)
            setPadding(dp(24), dp(20), dp(24), dp(104))
        }

        body.addView(cloudScannerSwitch(scannerActive = false), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // Wizard card (BPB/Zeus-style): token → deploy → import → scan, step by step.
        val setupWizardCard = advancedSettingsPanel()
        setupWizardCard.addView(
            TextView(this).apply {
                setText(R.string.wizard_title)
                textSize = 16f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        setupWizardCard.addView(
            TextView(this).apply {
                setText(R.string.wizard_subtitle)
                textSize = 12.5f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_SECONDARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
        )
        setupWizardCard.addView(
            cloudActionButton(R.string.wizard_start, R.drawable.ic_cloud_tab, accent = true) { showPanelWizard() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        body.addView(setupWizardCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        body.addView(
            advancedSectionLabel(getString(R.string.cloud_section_catpanel)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
        )

        val catPanelCard = advancedSettingsPanel()
        catPanelCard.addView(
            TextView(this).apply {
                text = CloudflareWorker.PANELS.first { it.id == "cat-panel" }.let {
                    if (appLanguagePreferenceStore.read() == AppLanguage.Persian) it.displayNameFa else it.displayName
                }
                textSize = 16f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        catPanelCard.addView(
            TextView(this).apply {
                text = getString(R.string.cloud_catpanel_desc)
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(0, dp(6), 0, dp(14))
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        catPanelCard.addView(
            cloudActionButton(R.string.cloud_copy_code, R.drawable.ic_cloud_tab, accent = true) {
                val script = runCatching { CloudflareWorker.builtInWorkerScript(this) }
                    .getOrNull()
                if (script.isNullOrBlank()) {
                    Toast.makeText(this, R.string.cloud_code_failed, Toast.LENGTH_SHORT).show()
                    return@cloudActionButton
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-worker", script))
                Toast.makeText(this, R.string.cloud_code_copied, Toast.LENGTH_LONG).show()
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        // The no-app path (v1.9.48): share the official one-click Deploy-to-
        // Cloudflare link — the same button as the README — with anyone. The
        // recipient deploys their own panel straight from their browser.
        catPanelCard.addView(
            cloudActionButton(R.string.cloud_share_deploy_link, R.drawable.ic_cloud_tab, accent = false) {
                shareDeployButtonLink()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        val workerNameInput = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            setText(CloudflareWorker.randomWorkerName())
        }
        val workerNameLayout = TextInputLayout(this).apply {
            hint = getString(R.string.cloud_worker_name_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(workerNameInput)
        }
        catPanelCard.addView(
            workerNameLayout,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        // Admin gate: every new panel gets a username + password (management
        // panel — not open access). Suggested values are prefilled.
        val panelUserInput = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            setText("admin")
        }
        val panelUserLayout = TextInputLayout(this).apply {
            hint = getString(R.string.cloud_panel_user_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(panelUserInput)
        }
        catPanelCard.addView(
            panelUserLayout,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        val suggestedPanelPassword = List(12) { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        val panelPassInput = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(suggestedPanelPassword)
        }
        val panelPassLayout = TextInputLayout(this).apply {
            hint = getString(R.string.cloud_panel_password_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(panelPassInput)
        }
        catPanelCard.addView(
            panelPassLayout,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        val tokenInput = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val tokenLayout = TextInputLayout(this).apply {
            hint = getString(R.string.cloud_token_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
            helperText = getString(R.string.cloud_token_help)
            isHelperTextEnabled = true
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(tokenInput)
        }
        // Step 1 — open Cloudflare with the permissions pre-selected (token template URL).
        catPanelCard.addView(
            cloudActionButton(R.string.cloud_get_token, R.drawable.ic_cloud_tab, accent = true) {
                openCloudflareTokenPage()
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        catPanelCard.addView(
            advancedSectionDetail(getString(R.string.cloud_get_token_steps)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        tokenLayout.helperText = getString(R.string.cloud_token_help)
        catPanelCard.addView(
            tokenLayout,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )

        val deployButton = cloudActionButton(R.string.cloud_deploy, R.drawable.ic_cloud_tab, accent = false) { }
        var cloudDeployInProgress = false
        deployButton.setOnClickListener {
            if (cloudDeployInProgress) return@setOnClickListener
            val token = tokenInput.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val workerName = workerNameInput.text?.toString()?.trim()
                ?.lowercase(Locale.US)
                ?.replace(Regex("[^a-z0-9-]"), "-")
                ?.replace(Regex("-{2,}"), "-")
                ?.trim('-')
                ?.ifEmpty { CloudflareWorker.randomWorkerName() } ?: CloudflareWorker.randomWorkerName()
            cloudDeployInProgress = true
            deployButton.isEnabled = false
            activityScope.launch {
                try {
                    Toast.makeText(this@MainActivity, R.string.cloud_verifying, Toast.LENGTH_SHORT).show()
                    val permissions = CloudflareWorker.verifyToken(token)
                    val accountId = permissions.accountId
                    if (!permissions.valid || accountId == null) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cloud_deploying, workerName),
                        Toast.LENGTH_SHORT,
                    ).show()
                    val result = CloudflareWorker.deployBuiltIn(
                        this@MainActivity,
                        token,
                        accountId,
                        workerName,
                        panelUser = panelUserInput.text?.toString()?.trim().orEmpty(),
                        panelPassword = panelPassInput.text?.toString()?.trim().orEmpty(),
                    )
                    PanelDeploymentStore(this@MainActivity).rememberToken(result.workerUrl, token)
                    showCloudDeploymentDialog(result)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    cloudDeployInProgress = false
                    deployButton.isEnabled = true
                }
            }
        }
        catPanelCard.addView(deployButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        body.addView(catPanelCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        body.addView(buildPanelStatusCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val deploymentHistoryCard = advancedSettingsPanel()
        deploymentHistoryCard.addView(
            TextView(this).apply {
                setText(R.string.cloud_my_deployments)
                textSize = 16f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        deploymentHistoryCard.addView(
            advancedSectionDetail(getString(R.string.cloud_no_deployments)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) },
        )
        cloudDeploymentHistoryHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        deploymentHistoryCard.addView(
            cloudDeploymentHistoryHost,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
        body.addView(deploymentHistoryCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        renderCloudDeploymentHistory()

        // ---- Cat Wizard: a shareable one-click installer page on the user's own account ----
        val wizardCard = advancedSettingsPanel()
        wizardCard.addView(
            TextView(this).apply {
                text = getString(R.string.cloud_wizard_title)
                textSize = 16f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        wizardCard.addView(
            advancedSectionDetail(getString(R.string.cloud_wizard_desc)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        val lastWizard = PanelDeploymentStore(this).lastWizardUrl()
        val wizardUrlView = TextView(this).apply {
            text = lastWizard ?: ""
            visibility = if (lastWizard.isNullOrBlank()) View.GONE else View.VISIBLE
            textSize = 13f
            setTextColor(TEAL)
            setTextIsSelectable(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                val u = text?.toString().orEmpty()
                if (u.isNotBlank()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
            }
        }
        wizardCard.addView(wizardUrlView, LinearLayout.LayoutParams(-1, -2))
        var wizardInProgress = false
        val wizardButton = cloudActionButton(R.string.cloud_wizard_deploy, R.drawable.ic_cloud_tab, accent = false) { }
        wizardButton.setOnClickListener {
            if (wizardInProgress) return@setOnClickListener
            val token = tokenInput.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            wizardInProgress = true
            wizardButton.isEnabled = false
            activityScope.launch {
                try {
                    Toast.makeText(this@MainActivity, R.string.cloud_verifying, Toast.LENGTH_SHORT).show()
                    val permissions = CloudflareWorker.verifyToken(token)
                    val accountId = permissions.accountId
                    if (!permissions.valid || accountId == null) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cloud_deploying, "cat-wizard"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    val result = CloudflareWorker.deployWizard(this@MainActivity, token, accountId)
                    PanelDeploymentStore(this@MainActivity).rememberWizard(result.wizardUrl)
                    wizardUrlView.text = result.wizardUrl
                    wizardUrlView.visibility = View.VISIBLE
                    showWizardDeployedDialog(result)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    wizardInProgress = false
                    wizardButton.isEnabled = true
                }
            }
        }
        wizardCard.addView(wizardButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        body.addView(wizardCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // Cross-link: the built-in panel is only useful with a clean IP — jump to the scanner.
        val scannerLinkCard = advancedSettingsPanel()
        scannerLinkCard.addView(
            advancedSectionDetail(getString(R.string.cloud_scanner_hint)),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(4)
            },
        )
        scannerLinkCard.addView(
            cloudActionButton(R.string.cloud_open_scanner, R.drawable.ic_speedometer, accent = false) {
                openScreen(SCREEN_SCANNER)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
        body.addView(scannerLinkCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---- panel catalog ----
        body.addView(
            advancedSectionLabel(getString(R.string.cloud_section_catalog)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) },
        )
        body.addView(
            TextView(this).apply {
                text = getString(R.string.cloud_intro)
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(0, 0, 0, dp(10))
            },
            LinearLayout.LayoutParams(-1, -2),
        )

        val catalogCard = advancedSettingsPanel()
        CloudflareWorker.PANELS.filter { it.id != "cat-panel" }.forEachIndexed { index, panel ->
            val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nameRow.addView(
                TextView(this).apply {
                    text = if (isFarsi) panel.displayNameFa else panel.displayName
                    textSize = 14.5f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            nameRow.addView(
                TextView(this).apply {
                    text = cloudScopeLabel(panel.scope)
                    textSize = 11f
                    setTextColor(TEAL)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(999).toFloat()
                        setColor(withAlpha(TEAL, 26))
                        setStroke(dp(1), withAlpha(TEAL, 120))
                    }
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) },
            )
            row.addView(nameRow, LinearLayout.LayoutParams(-1, -2))
            row.addView(
                TextView(this).apply {
                    text = if (isFarsi) panel.descriptionFa else panel.description
                    textSize = 12.5f
                    setTextColor(TEXT_SECONDARY)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    setPadding(0, dp(4), 0, 0)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            val openButton = MaterialButton(this).apply {
                setText(if (panel.scope == CloudflareWorker.PanelScope.CF_WORKER) R.string.cloud_open_builder else R.string.cloud_panel_open)
                setAllCaps(false)
                textSize = 12f
                typeface = CatClientBodyBoldTypeface
                isSingleLine = true
                setPadding(dp(12), 0, dp(12), 0)
                minWidth = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(15)
                minHeight = dp(40)
                minimumHeight = dp(40)
                backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 170))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 220))
                elevation = dp(1).toFloat()
                stateListAnimator = null
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(panel.url)))
                    }.onFailure {
                        Toast.makeText(this@MainActivity, panel.url, Toast.LENGTH_LONG).show()
                    }
                }
            }
            row.addView(
                openButton,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
            )
            catalogCard.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < CloudflareWorker.PANELS.size - 2) {
                catalogCard.addView(
                    View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply {
                        marginStart = dp(12)
                        marginEnd = dp(12)
                    },
                )
            }
        }
        body.addView(catalogCard, LinearLayout.LayoutParams(-1, -2))

        scroll.addView(body, FrameLayout.LayoutParams(-1, -1))
        return scroll
    }

    private var panelUpdateInProgress = false

    @Volatile
    private var newestPanelCache: PanelUpdate.PanelScript? = null

    private suspend fun newestPanelScript(): PanelUpdate.PanelScript =
        newestPanelCache ?: PanelUpdate.newestPanel(this).also { newestPanelCache = it }

    private fun renderCloudDeploymentHistory() {
        val host = cloudDeploymentHistoryHost ?: return
        host.removeAllViews()
        val history = PanelDeploymentStore(this).deployments()
        history.forEachIndexed { index, deployment ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(dp(10), dp(9), dp(8), dp(9))
                background = glassSurfaceDrawable(radiusDp = 14)
            }
            val titleLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }
            titleLine.addView(
                TextView(this).apply {
                    text = "🐱  " + deployment.workerUrl.removePrefix("https://").take(42)
                    textSize = 12.5f
                    typeface = CatClientDataTypeface
                    setTextColor(TEXT_PRIMARY)
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            val versionBadge = TextView(this).apply {
                text = getString(R.string.cloud_panel_version_checking)
                textSize = 11f
                typeface = CatClientDataTypeface
                setTextColor(TEXT_SECONDARY)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                maxLines = 1
            }
            titleLine.addView(versionBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            card.addView(titleLine, LinearLayout.LayoutParams(-1, -2))

            fun rowButton(labelText: CharSequence): MaterialButton = MaterialButton(this@MainActivity).apply {
                text = labelText
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
            }

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            }
            val open = rowButton(getString(R.string.cloud_open_deployment)).apply {
                setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deployment.panelUrl))) }
                }
            }
            val update = rowButton(getString(R.string.cloud_update_panel)).apply {
                setOnClickListener { showPanelUpdateDialog(deployment) }
            }
            buttons.addView(open, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(8) })
            buttons.addView(update, LinearLayout.LayoutParams(0, -2, 1f).apply {
                topMargin = dp(8)
                marginStart = dp(8)
            })
            card.addView(buttons, LinearLayout.LayoutParams(-1, -2))
            host.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(7)
            })

            // Live version check: deployed (/api/version) vs newest (release asset / bundle).
            activityScope.launch {
                val deployed = PanelUpdate.deployedVersion(deployment.workerUrl)
                val newest = runCatching { newestPanelScript() }.getOrNull()
                if (!versionBadge.isAttachedToWindow) return@launch
                applyPanelVersionBadge(versionBadge, deployed, newest)
            }
        }
        host.addView(
            MaterialButton(this).apply {
                setText(R.string.cloud_add_existing)
                textSize = 12.5f
                typeface = CatClientBodyBoldTypeface
                isAllCaps = false
                cornerRadius = dp(12)
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(withAlpha(OUTLINE, 220))
                setTextColor(TEXT_PRIMARY)
                backgroundTintList = ColorStateList.valueOf(withAlpha(SURFACE, if (palette.isDark) 210 else 245))
                setOnClickListener { onAddExistingPanel() }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }

    /**
     * Attach a panel that was deployed manually in the Cloudflare dashboard (no
     * app record exists). After this the card shows in My Panels with its live
     * version and the Update Panel button — the CF token is asked at update time.
     */
    private fun onAddExistingPanel() {
        val input = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            hint = getString(R.string.cloud_add_existing_hint)
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_add_existing)
            .setMessage(R.string.cloud_add_existing_msg)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { button ->
            var raw = input.text?.toString()?.trim().orEmpty()
            if (raw.isBlank()) return@setOnClickListener
            if (!raw.startsWith("http")) raw = "https://$raw"
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            val host = uri?.host.orEmpty()
            if (!host.contains('.')) {
                Toast.makeText(this, R.string.cloud_panel_notcat, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val path = uri?.path.orEmpty()
            val url = "https://" + host + if (path.isNotBlank() && path != "/") path else ""
            button.isEnabled = false
            activityScope.launch {
                val version = runCatching { PanelUpdate.deployedVersion(url) }.getOrNull()
                if (version == null) {
                    withContext(Dispatchers.Main) {
                        button.isEnabled = true
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.cloud_add_existing)
                            .setMessage(R.string.cloud_panel_notcat_confirm)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                PanelDeploymentStore(this@MainActivity).rememberLast(url, "")
                                renderCloudDeploymentHistory()
                                Toast.makeText(this@MainActivity, R.string.cloud_panel_added, Toast.LENGTH_LONG).show()
                                dialog.dismiss()
                            }
                            .show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        PanelDeploymentStore(this@MainActivity).rememberLast(url, "")
                        renderCloudDeploymentHistory()
                        Toast.makeText(this@MainActivity, getString(R.string.cloud_panel_added_version, version), Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    private fun applyPanelVersionBadge(
        badge: TextView,
        deployed: String?,
        newest: PanelUpdate.PanelScript?,
    ) {
        when {
            deployed == null -> {
                badge.setTextColor(TEXT_SECONDARY)
                badge.text = getString(R.string.cloud_panel_version_unknown)
            }
            newest != null && AppUpdatePolicy.isNewer(newest.version, deployed) -> {
                badge.setTextColor(TEAL)
                badge.text = getString(R.string.cloud_panel_update_ready, deployed, newest.version)
            }
            else -> {
                badge.setTextColor(TEXT_SECONDARY)
                badge.text = getString(R.string.cloud_panel_uptodate, deployed)
            }
        }
    }

    private fun showPanelUpdateDialog(deployment: PanelDeploymentRecord) {
        if (panelUpdateInProgress) return
        panelUpdateInProgress = true
        Toast.makeText(this, R.string.cloud_panel_version_checking, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            try {
                val newest = newestPanelScript()
                val deployed = PanelUpdate.deployedVersion(deployment.workerUrl)
                val hasUpdate = deployed == null || AppUpdatePolicy.isNewer(newest.version, deployed)
                presentPanelUpdateDialog(deployment, deployed, newest, hasUpdate)
            } catch (e: Exception) {
                panelUpdateInProgress = false
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.cloud_update_failed, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun presentPanelUpdateDialog(
        deployment: PanelDeploymentRecord,
        deployed: String?,
        newest: PanelUpdate.PanelScript,
        hasUpdate: Boolean,
    ) {
        // The Cloudflare token was saved at deploy/update time: this flow only
        // confirms the panel password, the saved token authorizes the upload.
        val savedToken = PanelDeploymentStore(this).tokenFor(deployment.workerUrl)
        if (!savedToken.isNullOrBlank()) {
            presentPanelUpdatePasswordDialog(deployment, deployed, newest, hasUpdate, savedToken)
            return
        }
        // No stored token: default to the token-free guided update. The user
        // explicitly never wants to be asked for a Cloudflare API token.
        presentTokenlessPanelUpdate(deployment, deployed, newest, hasUpdate)
    }

    /**
     * Token-free panel update: the newest worker code (release asset, or the copy
     * bundled in this APK when GitHub is unreachable) goes to the clipboard and the
     * Cloudflare dashboard opens — paste into the worker's Edit code and Deploy.
     * No API token is ever requested.
     */
    private fun presentTokenlessPanelUpdate(
        deployment: PanelDeploymentRecord,
        deployed: String?,
        newest: PanelUpdate.PanelScript,
        @Suppress("UNUSED_PARAMETER") hasUpdate: Boolean,
    ) {
        val host = deployment.workerUrl.removePrefix("https://")
        val workerName = if (host.endsWith(".workers.dev")) {
            host.removeSuffix(".workers.dev").substringAfterLast('.')
        } else {
            host.substringBefore('/')
        }
        val message = getString(R.string.cloud_update_tokenless_msg, workerName, deployed ?: "?", newest.version)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_update_tokenless_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
            .setPositiveButton(R.string.cloud_update_tokenless_btn) { _, _ ->
                panelUpdateInProgress = false
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-worker", newest.text))
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/?to=/:account/workers")))
                }
                Toast.makeText(this, R.string.cloud_update_tokenless_go, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    /** Inline Cloudflare-token prompt (also the recovery path for an expired token). */
    private fun presentPanelTokenPrompt(
        deployment: PanelDeploymentRecord,
        deployed: String?,
        newest: PanelUpdate.PanelScript,
    ) {
        val hasUpdate = deployed == null || AppUpdatePolicy.isNewer(newest.version, deployed)
        val tokenInput = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val tokenLayout = TextInputLayout(this).apply {
            hint = getString(R.string.cloud_token_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
            helperText = getString(R.string.cloud_token_help)
            isHelperTextEnabled = true
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(tokenInput)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(24), dp(6), dp(24), 0)
            addView(tokenLayout, LinearLayout.LayoutParams(-1, -2))
        }
        val message = if (hasUpdate) {
            getString(
                R.string.cloud_update_message,
                deployment.workerUrl.removePrefix("https://"),
                deployed ?: "?",
                newest.version,
            )
        } else {
            getString(R.string.cloud_update_uptodate_message, newest.version)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_update_title)
            .setMessage(message)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
            .setNeutralButton(R.string.cloud_get_token_short) { _, _ ->
                panelUpdateInProgress = false
                openCloudflareTokenPage()
            }
            .setPositiveButton(
                if (hasUpdate) R.string.cloud_update_confirm else R.string.cloud_update_reinstall,
                null,
            )
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = tokenInput.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    Toast.makeText(this, R.string.cloud_token_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                runPanelUpdate(deployment, token, deployed, newest)
            }
        }
        dialog.setOnCancelListener { panelUpdateInProgress = false }
        dialog.show()
    }

    /**
     * Update confirmation. The saved Cloudflare token IS the ownership proof —
     * it performs the upload — so the panel password never gates a redeploy.
     * (A crashed panel cannot answer a login challenge; asking for a password
     * first is what used to brick the recovery path.)
     */
    private fun presentPanelUpdatePasswordDialog(
        deployment: PanelDeploymentRecord,
        deployed: String?,
        newest: PanelUpdate.PanelScript,
        hasUpdate: Boolean,
        savedToken: String,
    ) {
        runPanelUpdate(deployment, savedToken, deployed, newest)
    }

    private fun runPanelUpdate(
        deployment: PanelDeploymentRecord,
        token: String,
        deployed: String?,
        script: PanelUpdate.PanelScript,
        secretValues: Map<String, String> = emptyMap(),
    ) {
        Toast.makeText(this, R.string.cloud_update_running, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            try {
                val permissions = CloudflareWorker.verifyToken(token)
                val accountId = permissions.accountId
                if (!permissions.valid || accountId == null) {
                    panelUpdateInProgress = false
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.cloud_token_invalid, permissions.missingScopes.joinToString(" + ")),
                        Toast.LENGTH_LONG,
                    ).show()
                    // The saved token can no longer upload (expired/revoked/scopes) —
                    // NEVER ask for a token again: fall back to the token-free flow.
                    panelUpdateInProgress = false
                    presentTokenlessPanelUpdate(deployment, deployed, script, true)
                    return@launch
                }
                PanelDeploymentStore(this@MainActivity).rememberToken(deployment.workerUrl, token)
                // No secret re-entry prompts: updateBuiltIn preserves unreadable
                // secrets via keep_secrets, so the update just runs.
                val outcome = CloudflareWorker.updateBuiltIn(
                    this@MainActivity,
                    token,
                    accountId,
                    deployment.workerUrl,
                    deployed ?: "",
                    script,
                    secretValues,
                )
                when (outcome) {
                    is CloudflareWorker.PanelUpdateOutcome.Blocked -> {
                        // Defensive: with keep_secrets this no longer triggers.
                        panelUpdateInProgress = false
                        Toast.makeText(this@MainActivity, R.string.cloud_update_running, Toast.LENGTH_LONG).show()
                    }
                    is CloudflareWorker.PanelUpdateOutcome.Success -> showCloudUpdateDialog(outcome)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.cloud_update_failed, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                panelUpdateInProgress = false
                renderCloudDeploymentHistory()
            }
        }
    }

    private fun showPanelSecretsDialog(
        deployment: PanelDeploymentRecord,
        token: String,
        deployed: String?,
        script: PanelUpdate.PanelScript,
        secretNames: List<String>,
        onValues: (Map<String, String>) -> Unit,
    ) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        column.addView(
            TextView(this).apply {
                setText(R.string.cloud_secrets_desc)
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        val inputs = mutableMapOf<String, TextInputEditText>()
        secretNames.forEach { name ->
            val input = TextInputEditText(this).apply {
                setSingleLine(true)
                hint = name
                background = null
                setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
                setTextColor(TEXT_PRIMARY)
                setHintTextColor(TEXT_SECONDARY)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            inputs[name] = input
            column.addView(
                TextInputLayout(this).apply {
                    this.hint = name
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
                    boxStrokeColor = TEAL
                    defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
                    setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
                    addView(input)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_secrets_title)
            .setView(column)
            .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
            .setPositiveButton(R.string.cloud_secrets_confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = mutableMapOf<String, String>()
                for ((name, input) in inputs) {
                    val value = input.text?.toString().orEmpty()
                    if (value.isEmpty()) {
                        Toast.makeText(this, getString(R.string.cloud_secrets_missing, name), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    values[name] = value
                }
                dialog.dismiss()
                panelUpdateInProgress = true
                onValues(values)
            }
        }
        dialog.setOnCancelListener { panelUpdateInProgress = false }
        dialog.show()
    }

    private fun showCloudUpdateDialog(result: CloudflareWorker.PanelUpdateOutcome.Success) {
        val status = if (result.verifiedOnline) {
            getString(R.string.cloud_verified_online)
        } else {
            getString(R.string.cloud_verify_pending)
        }
        val kvLine = if (result.kvBound) getString(R.string.cloud_kv_bound) else getString(R.string.cloud_kv_missing)
        val message = getString(R.string.cloud_update_done, result.toVersion) + "\n" + status + "\n" + kvLine +
            "\n\n" + result.panelUrl
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_update_title)
            .setMessage(message)
            .setPositiveButton(R.string.cloud_open_panel) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl))) }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** Opens dash.cloudflare.com with the Cat Panel permissions pre-selected. */
    private fun openCloudflareTokenPage() {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CloudflareWorker.CF_TOKEN_TEMPLATE_URL)))
        }.isSuccess
        if (!opened) {
            runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cf-token-url", CloudflareWorker.CF_TOKEN_TEMPLATE_URL))
            }
        }
        Toast.makeText(this, R.string.cloud_get_token_toast, Toast.LENGTH_LONG).show()
    }

    /**
     * Copies the official one-click Deploy-to-Cloudflare link and opens the
     * system share sheet — the recipient needs neither Cat Client nor an API
     * token, just a (free) Cloudflare account.
     */
    private fun shareDeployButtonLink() {
        val url = CloudflareWorker.DEPLOY_BUTTON_URL
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-deploy-link", url))
        }
        Toast.makeText(this, R.string.cloud_share_deploy_copied, Toast.LENGTH_SHORT).show()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.cloud_share_deploy_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.cloud_share_deploy_text, url))
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.cloud_share_deploy_subject))) }
    }

    private fun showWizardDeployedDialog(result: CloudflareWorker.WizardDeploymentResult) {
        val status = if (result.verifiedOnline) {
            getString(R.string.cloud_verified_online)
        } else {
            getString(R.string.cloud_verify_pending)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_wizard_deployed)
            .setMessage(status + "\n\n" + result.wizardUrl + "\n\n" + getString(R.string.cloud_wizard_share_hint))
            .setPositiveButton(R.string.cloud_open_panel) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.wizardUrl))) }
            }
            .setNeutralButton(R.string.cloud_copy_all) { _, _ ->
                runCatching {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("cat-wizard", result.wizardUrl))
                    Toast.makeText(this, R.string.cloud_sub_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /* ------------------------------------------------------------------ */
    /* Panel wizard — the "I have nothing yet" path                          */
    /* ------------------------------------------------------------------ */

    private fun wizardField(hint: String, password: Boolean = false, initial: String = ""): Pair<TextInputLayout, TextInputEditText> {
        val edit = TextInputEditText(this).apply {
            setText(initial)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
            typeface = CatClientBodyTypeface
        }
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(edit, LinearLayout.LayoutParams(-1, -2))
        }
        return layout to edit
    }

    private fun wizardBody(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(24), dp(12), dp(24), dp(4))
        views.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }
    }

    private fun wizardText(text: CharSequence, secondary: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = if (secondary) 12.5f else 14f
        typeface = CatClientBodyTypeface
        setTextColor(if (secondary) TEXT_SECONDARY else TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    /** Step 1/4 — what is about to happen. */
    private fun showPanelWizard() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wizard_step, 1, 4) + " · " + getString(R.string.wizard_title))
            .setView(wizardBody(wizardText(getString(R.string.wizard_intro)), wizardText(getString(R.string.wizard_intro_note), secondary = true)))
            .setPositiveButton(R.string.wizard_next) { _, _ -> showPanelWizardToken() }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .show()
    }

    /** Step 2/4 — Cloudflare API token (verified before moving on). */
    private fun showPanelWizardToken(prefill: String = "", error: String? = null) {
        val (tokenLayout, tokenEdit) = wizardField(getString(R.string.cloud_token_hint), initial = prefill)
        if (error != null) tokenLayout.error = error
        val getToken = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.wizard_get_token)
            isAllCaps = false
            typeface = CatClientBodyBoldTypeface
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CloudflareWorker.CF_TOKEN_TEMPLATE_URL))) } }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wizard_step, 2, 4) + " · " + getString(R.string.wizard_token_title))
            .setView(wizardBody(wizardText(getString(R.string.wizard_token_help)), getToken, tokenLayout, wizardText(getString(R.string.wizard_token_privacy), secondary = true)))
            .setPositiveButton(R.string.wizard_next, null)
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = tokenEdit.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) { tokenLayout.error = getString(R.string.cloud_token_required); return@setOnClickListener }
                tokenLayout.error = null
                tokenLayout.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                activityScope.launch {
                    val permissions = runCatching { CloudflareWorker.verifyToken(token) }.getOrNull()
                    val accountId = permissions?.accountId
                    if (permissions == null || !permissions.valid || accountId == null) {
                        dialog.dismiss()
                        showPanelWizardToken(token, getString(R.string.cloud_token_invalid, permissions?.missingScopes?.joinToString(" + ") ?: "network"))
                    } else {
                        dialog.dismiss()
                        showPanelWizardOptions(token, accountId)
                    }
                }
            }
        }
        dialog.show()
    }

    /** Step 3/4 — name + password, then deploy with a progress dialog. */
    private fun showPanelWizardOptions(token: String, accountId: String) {
        val (nameLayout, nameEdit) = wizardField(getString(R.string.cloud_worker_name_hint), initial = CloudflareWorker.randomWorkerName())
        val (passLayout, passEdit) = wizardField(getString(R.string.cloud_panel_password_hint), password = true)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.wizard_step, 3, 4) + " · " + getString(R.string.wizard_options_title))
            .setView(wizardBody(wizardText(getString(R.string.wizard_options_help)), nameLayout, passLayout, wizardText(getString(R.string.wizard_options_note), secondary = true)))
            .setPositiveButton(R.string.wizard_deploy) { _, _ ->
                val workerName = nameEdit.text?.toString()?.trim()?.lowercase(Locale.US)
                    ?.replace(Regex("[^a-z0-9-]"), "-")?.replace(Regex("-{2,}"), "-")?.trim('-')?.ifEmpty { CloudflareWorker.randomWorkerName() } ?: CloudflareWorker.randomWorkerName()
                val password = passEdit.text?.toString()?.trim().orEmpty()
                val progress = MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.wizard_step, 4, 4) + " · " + getString(R.string.wizard_deploying_title))
                    .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_deploying, workerName))))
                    .setCancelable(false)
                    .show()
                activityScope.launch {
                    try {
                        val result = CloudflareWorker.deployBuiltIn(this@MainActivity, token, accountId, workerName, panelPassword = password)
                        PanelDeploymentStore(this@MainActivity).rememberToken(result.workerUrl, token)
                        PanelDeploymentStore(this@MainActivity).rememberLast(result.workerUrl, result.uuid)
                        renderCloudDeploymentHistory()
                        progress.dismiss()
                        showPanelWizardDone(result)
                    } catch (e: Exception) {
                        progress.dismiss()
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.wizard_failed_title)
                            .setMessage(getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName))
                            .setPositiveButton(R.string.wizard_retry) { _, _ -> showPanelWizardOptions(token, accountId) }
                            .setNegativeButton(R.string.split_tunnel_cancel, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .show()
    }

    /** Step 4/4 — done: import + connect, scan clean IPs, open the panel. */
    private fun showPanelWizardDone(result: CloudflareWorker.DeploymentResult) {
        val host = Uri.parse(result.workerUrl).host.orEmpty()
        val status = if (result.verifiedOnline) getString(R.string.cloud_verified_online) else getString(R.string.cloud_verify_pending)
        val kvLine = if (result.kvBound) getString(R.string.cloud_kv_bound) else getString(R.string.cloud_kv_missing)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wizard_done_title)
            .setView(
                wizardBody(
                    wizardText(status + "\n" + kvLine),
                    wizardText(getString(R.string.cloud_panel_url) + ":\n" + result.panelUrl, secondary = true),
                    wizardText(getString(R.string.cloud_uuid_is_password, result.uuid), secondary = true),
                    wizardText(getString(R.string.wizard_done_help)),
                ),
            )
            .setPositiveButton(R.string.wizard_import_connect) { _, _ ->
                showAddSubscriptionDialog(result.subscriptionUrl, "Cat Panel")
            }
            .setNeutralButton(R.string.wizard_scan_now) { _, _ ->
                if (::scannerSniInput.isInitialized && host.isNotEmpty()) {
                    scannerSniInput.setText(host)
                    saveScannerSni(host)
                }
                openScreen(SCREEN_SCANNER)
                Toast.makeText(this, R.string.wizard_scan_hint, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cloud_open_panel) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl))) }
            }
            .show()
    }

    /* ------------------------------------------------------------------ */
    /* Map page (ZedSecure MapScreen): real location → exit                 */
    /* ------------------------------------------------------------------ */

    private fun showMapPage() {
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
        val originTint = ZedBlobView.ZED_HOT_PINK

        val map = ZedWorldMapView(this).apply {
            accent = TEAL
            originColor = originTint
            landColor = TEXT_PRIMARY
        }
        activityScope.launch {
            val list = withContext(kotlinx.coroutines.Dispatchers.IO) { WorldMap.countries(this@MainActivity) }
            map.setCountries(list)
            val o = WorldMap.anchorOf(list, originCode)?.let { ZedWorldMapView.Point(it.first, it.second, originLabel, originCode) }
            val e = WorldMap.anchorOf(list, exitCode)?.let { ZedWorldMapView.Point(it.first, it.second, exitLabel, exitCode) }
            map.setRoute(o, e)
        }

        fun endpointRow(dot: Int, hollow: Boolean, caption: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (hollow) setStroke(dp(2), dot) else setColor(dot)
                }
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(TextView(this@MainActivity).apply {
                    text = caption; textSize = 12f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); includeFontPadding = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = value; textSize = 16f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        fun stat(caption: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = caption; textSize = 11f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY) })
            addView(TextView(this@MainActivity).apply {
                text = value; textSize = 14f; typeface = CatClientBodyBoldTypeface; setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
            })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(withAlpha(palette.surfaceElevated1, 225))
            }
            addView(endpointRow(
                originTint, true,
                getString(if (connected && originCode.isNotBlank()) R.string.map_you_saved else R.string.map_you),
                originLabel.ifBlank { getString(if (connected) R.string.map_no_origin else R.string.map_unknown) },
            ))
            if (connected) {
                addView(endpointRow(TEAL, false, getString(R.string.map_exit), exitLabel.ifBlank { getString(R.string.map_unknown) }),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                val ping = livePingMs
                val server = activeConfigTitle.text?.toString()?.takeIf { it.isNotBlank() }
                if (ping != null || server != null) {
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        ping?.let { addView(stat(getString(R.string.map_ping), "$it ms"), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(18) }) }
                        server?.let { addView(stat(getString(R.string.map_server), it), LinearLayout.LayoutParams(0, -2, 1f)) }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                }
            } else {
                addView(TextView(this@MainActivity).apply {
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
            fun endpoint(code: String, label: String, tint: Int, hollow: Boolean) = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(FlagBadgeView(this@MainActivity).apply {
                    circle = true; ringColor = tint; ringWidthDp = if (hollow) 1.5f else 2f; fallbackColor = palette.surfaceElevated2
                    setCountryCode(code)
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                if (label.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = label.substringBefore(',').trim(); textSize = 11f; typeface = CatClientBodyTypeface; setTextColor(TEXT_SECONDARY); maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            }
            if (originCode.isNotBlank()) addView(endpoint(originCode, originLabel, originTint, true))
            if (connected && exitCode.isNotBlank()) {
                addView(ZedRouteConnectorView(this@MainActivity).apply { color = TEAL }, LinearLayout.LayoutParams(dp(38), dp(34)).apply { marginStart = dp(10); marginEnd = dp(10) })
                addView(endpoint(exitCode, exitLabel, TEAL, false))
            }
            visibility = if (originCode.isNotBlank() || exitCode.isNotBlank()) View.VISIBLE else View.GONE
        }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(12), dp(20), dp(18))
            addView(TextView(this@MainActivity).apply {
                setText(R.string.map_title); textSize = 28f; typeface = CatClientDisplayTypeface; setTextColor(TEXT_PRIMARY); includeFontPadding = false
            })
            addView(wizardText(getString(R.string.map_subtitle), secondary = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, 0, 1f))
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
        dialog.show()
    }

    /** True when any VPN (ours or another app's) owns the default route — Zed's deviceVpnActive(). */
    private fun deviceVpnActive(): Boolean = runCatching {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)


    /* ------------------------------------------------------------------ */
    /* Static IP + multi-location + SNI scanner (Scanner tab)               */
    /* ------------------------------------------------------------------ */

    private fun staticIpEnabled(): Boolean = IpHealthStore(this).pinned && frontingIps.size == 1

    private fun applyStaticIp(ip: String): Boolean {
        val normalized = runCatching { FrontingIpPolicy.normalizeIps(ip) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        frontingIps = normalized.take(1)
        IpHealthStore(this).pinned = true
        NetworkGenomeStore(this).autoFailover = false
        startIpHealthLoop() // no-op while pinned; stops a running loop
        renderFrontingIpChips()
        return saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)
    }

    /** NAT64 address from an IPv4: <96-bit prefix>:<hex(ipv4)> — BPB's NAT64 trick. */
    private fun nat64Address(prefix: String, ipv4: String): String? {
        val parts = ipv4.trim().split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { it.toIntOrNull() ?: return null }
        if (bytes.any { it < 0 || it > 255 }) return null
        val hex1 = "%02x%02x".format(bytes[0], bytes[1]).dropLeadingZeros()
        val hex2 = "%02x%02x".format(bytes[2], bytes[3]).dropLeadingZeros()
        val base = prefix.trim().trim('[', ']', ':')
        if (!Regex("^[0-9a-fA-F:]{6,45}$").matches(base)) return null
        return "[" + base.trimEnd(':') + "::" + hex1 + ":" + hex2 + "]"
    }

    private fun String.dropLeadingZeros(): String = dropWhile { it == '0' }.ifEmpty { "0" }

    private fun showNat64Dialog() {
        val currentIp = frontingIps.firstOrNull()?.removeSurrounding("[", "]")?.substringBeforeLast(':').orEmpty()
        val prefixes = arrayOf(
            "2a02:898:146:64:: (NL)",
            "2602:fc59:b0:64:: (US)",
            "2602:fc59:11:64:: (US)",
        )
        val chosen = arrayOf(prefixes[0])
        val ipInput = EditText(this).apply {
            hint = getString(R.string.nat64_ip_hint)
            setText(currentIp)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setTextColor(TEXT_PRIMARY); setHintTextColor(palette.textTertiary)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val preview = TextView(this).apply {
            textSize = 12f; typeface = CatClientDataTypeface; setTextColor(TEXT_SECONDARY)
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        fun renderPreview() {
            val built = nat64Address(chosen[0].substringBefore(" ("), ipInput.text?.toString().orEmpty())
            preview.text = built ?: getString(R.string.nat64_invalid)
        }
        ipInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = renderPreview()
        })
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.nat64_title))
            .setMessage(getString(R.string.nat64_explain))
            .setSingleChoiceItems(prefixes, 0) { _, which -> chosen[0] = prefixes[which]; renderPreview() }
            .setView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ipInput, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(12), dp(8), 0) })
                    addView(preview)
                },
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.nat64_copy) { _, _ ->
                nat64Address(chosen[0].substringBefore(" ("), ipInput.text?.toString().orEmpty())?.let {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("nat64", it))
                    Toast.makeText(this, R.string.nat64_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(R.string.nat64_apply) { _, _ ->
                val built = nat64Address(chosen[0].substringBefore(" ("), ipInput.text?.toString().orEmpty())
                if (built == null || !applyStaticIp(built)) {
                    Toast.makeText(this, R.string.nat64_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.nat64_applied, built), Toast.LENGTH_LONG).show()
                    renderFrontingIpChips()
                }
            }
            .show()
    }

    private fun clearStaticIp() {
        IpHealthStore(this).pinned = false
        NetworkGenomeStore(this).autoFailover = true
        startIpHealthLoop()
    }

    private fun buildStaticIpCard(): View {
        val card = advancedSettingsPanel()
        card.addView(advancedSectionLabel(getString(R.string.static_ip_title)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })
        card.addView(advancedSectionDetail(getString(R.string.static_ip_desc)), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val input = scannerInput(getString(R.string.static_ip_hint), if (staticIpEnabled()) frontingIps.first() else "")
        card.addView(scannerFieldLayout(getString(R.string.static_ip_label), input), LinearLayout.LayoutParams(-1, -2))
        val status = TextView(this).apply {
            textSize = 12f
            typeface = CatClientDataTypeface
            setTextColor(TEXT_SECONDARY)
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        fun renderStatus() {
            status.text = if (staticIpEnabled()) getString(R.string.static_ip_on, frontingIps.first()) else getString(R.string.static_ip_off)
        }
        renderStatus()
        card.addView(status, LinearLayout.LayoutParams(-1, -2))
        val toggle = MaterialSwitch(this).apply {
            isChecked = staticIpEnabled()
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    val value = input.text?.toString()?.trim().orEmpty().ifBlank { frontingIps.firstOrNull().orEmpty() }
                    if (value.isBlank() || !applyStaticIp(value)) {
                        isChecked = false
                        Toast.makeText(this@MainActivity, R.string.static_ip_invalid, Toast.LENGTH_SHORT).show()
                    } else {
                        input.setText(frontingIps.first())
                        Toast.makeText(this@MainActivity, getString(R.string.static_ip_on, frontingIps.first()), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    clearStaticIp()
                }
                renderStatus()
                renderScannerIpHealth()
            }
        }
        card.addView(advancedToggleRow(getString(R.string.static_ip_switch), getString(R.string.static_ip_switch_detail), toggle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE; setPadding(dp(8), dp(4), dp(8), 0) }
        fun smallButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
            text = label; setAllCaps(false); textSize = 11.5f; minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
            insetTop = 0; insetBottom = 0; cornerRadius = dp(14)
            backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 34)); strokeWidth = dp(1); strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 130)); setTextColor(TEAL)
            setOnClickListener { onClick() }
        }
        pickRow.addView(smallButton(getString(R.string.static_ip_pick_pool)) {
            val pool = IpHealthStore(this).entries().sortedBy { it.pingMs.takeIf { p -> p > 0 } ?: Long.MAX_VALUE }
            if (pool.isEmpty()) { Toast.makeText(this, R.string.static_ip_pool_empty, Toast.LENGTH_SHORT).show(); return@smallButton }
            val labels = pool.map { e -> (e.countryCode?.toFlagEmoji() ?: "🌐") + " " + e.ip + (e.colo?.let { "  $it" } ?: "") + (if (e.pingMs > 0) "  ${e.pingMs} ms" else "") }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.static_ip_pick_pool)
                .setItems(labels) { _, which -> input.setText(pool[which].ip); if (toggle.isChecked) { applyStaticIp(pool[which].ip); renderStatus(); renderScannerIpHealth() } else toggle.isChecked = true }
                .show()
        }, LinearLayout.LayoutParams(-2, -2))
        pickRow.addView(smallButton(getString(R.string.static_ip_use_current)) {
            val current = frontingIps.firstOrNull()
            if (current == null) Toast.makeText(this, R.string.static_ip_no_current, Toast.LENGTH_SHORT).show()
            else { input.setText(current); if (!toggle.isChecked) toggle.isChecked = true }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        pickRow.addView(smallButton(getString(R.string.nat64_btn)) { showNat64Dialog() })
        card.addView(pickRow, LinearLayout.LayoutParams(-1, -2))
        // Sticky location: reconnects keep the last working server instead of hopping to the fastest one.
        val stickySwitch = MaterialSwitch(this).apply {
            isChecked = getSharedPreferences("cat_client_theme", MODE_PRIVATE).getBoolean("sticky_location", true)
            setOnCheckedChangeListener { _, checked -> getSharedPreferences("cat_client_theme", MODE_PRIVATE).edit().putBoolean("sticky_location", checked).apply() }
        }
        card.addView(advancedToggleRow(getString(R.string.sticky_location_title), getString(R.string.sticky_location_detail), stickySwitch), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return card
    }

    /** Multi-location: make a whole country the active fronting set (best-ranked first) and reconnect. */
    private fun useCountryIps(countryCode: String) {
        val store = IpHealthStore(this)
        val entries = store.entries().filter { it.countryCode.equals(countryCode, true) && it.fails == 0 }
        if (entries.isEmpty()) { Toast.makeText(this, R.string.multi_location_empty, Toast.LENGTH_SHORT).show(); return }
        val genomes = NetworkGenomeStore(this)
        val ranked = CognitiveEngine.rank(entries, genomes.genomes(entries.map { it.ip }), NetworkContext.key(this))
        store.preferredCountry = countryCode
        store.pinned = false
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        frontingIps = FrontingIpPolicy.normalizeIps(ranked.map { it.ip }.take(6).joinToString(","))
        renderFrontingIpChips()
        if (saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) {
            Toast.makeText(this, getString(R.string.multi_location_applied, countryCode.toFlagEmoji(), ranked.size), Toast.LENGTH_SHORT).show()
        }
        renderScannerIpHealth()
    }

    private fun buildSniScannerCard(): View {
        val card = advancedSettingsPanel()
        card.addView(advancedSectionLabel(getString(R.string.sni_scanner_title)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })
        card.addView(advancedSectionDetail(getString(R.string.sni_scanner_desc)), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val targetInput = scannerInput(getString(R.string.sni_scanner_target_hint), frontingIps.firstOrNull() ?: IpHealthStore(this).entries().firstOrNull()?.ip.orEmpty())
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
                    results.addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = glassSurfaceDrawable(radiusDp = 12); clipToOutline = true
                        isClickable = ok; isFocusable = ok
                        if (ok) setOnClickListener {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle(sni)
                                .setItems(arrayOf(getString(R.string.sni_action_configs), getString(R.string.sni_action_scanner), getString(R.string.sni_action_copy), getString(R.string.sni_action_panel))) { _, which ->
                                    when (which) {
                                        0 -> { applySniOverride(sni); overrideStatus.text = getString(R.string.sni_override_current, sni) }
                                        1 -> {
                                            saveScannerSni(sni)
                                            if (::scannerSniInput.isInitialized) scannerSniInput.setText(sni)
                                            Toast.makeText(this@MainActivity, getString(R.string.sni_scanner_applied, sni), Toast.LENGTH_SHORT).show()
                                        }
                                        2 -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("sni", sni))
                                        else -> pushSniToPanel(sni)
                                    }
                                }
                                .show()
                        }
                        addView(TextView(this@MainActivity).apply { text = if (ok) "TLS ✓" else "✗"; textSize = 10.5f; typeface = CatClientBodyBoldTypeface; setTextColor(if (ok) TEAL else TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                        addView(TextView(this@MainActivity).apply { text = sni; textSize = 13f; typeface = CatClientDataTypeface; setTextColor(TEXT_PRIMARY); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(TextView(this@MainActivity).apply { text = if (ok) "${r!!.pingMs} ms" + (r.tlsMs?.let { " · tls $it" } ?: "") else "—"; textSize = 11.5f; typeface = CatClientDataTypeface; setTextColor(TEXT_SECONDARY) }, LinearLayout.LayoutParams(-2, -2))
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

    private fun showSpeedTestPage() {
        SpeedTestPage(this, palette, activityScope, connected = currentVpnStateIsStarted()).show()
    }

    private fun panelBaseUrl(): String? = detectPanelSnisFromSubscriptions().firstOrNull()?.let { "https://$it" }

    private fun showProxyIpScannerPage() {
        ProxyIpScannerPage(this, palette, activityScope, panelBaseUrl(), onUseAsEntry = { applyProxyIpAsEntry(it) }).show()
    }

    /**
     * "Connect via this IP" from the ProxyIP scanner: the scanned Cloudflare edge
     * IP becomes a fronting address — the tunnel dials the IP directly while the
     * configs' SNI/Host stay the panel domain (works even when the workers.dev
     * hostname is DNS-poisoned).
     */
    private fun applyProxyIpAsEntry(ip: String) {
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        frontingIps = runCatching {
            FrontingIpPolicy.normalizeIps((frontingIps + ip).joinToString(","))
        }.getOrDefault(frontingIps)
        renderFrontingIpChips()
        if (!saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) return
        Toast.makeText(this, getString(R.string.scanner_applied, ip, 0L), Toast.LENGTH_LONG).show()
    }

    /** Put an SNI into the live configs (servername of every TLS proxy) and reconnect; null clears it. */
    private fun applySniOverride(sni: String?) {
        val previous = frontingIpPreferenceStore.readSniOverride()
        frontingIpPreferenceStore.saveSniOverride(sni)
        val next = frontingIpPreferenceStore.readSniOverride()
        if (next != previous && buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
        Toast.makeText(this, if (next.isBlank()) getString(R.string.sni_override_cleared) else getString(R.string.sni_override_applied, next), Toast.LENGTH_LONG).show()
    }

    /** "Add to panel" from the SNI scanner: merges the host into settings.extraSnis (🧬 SNI configs). */
    private fun pushSniToPanel(sni: String) {
        val base = panelBaseUrl()?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) { Toast.makeText(this, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return }
        val input = EditText(this).apply {
            hint = getString(R.string.pip_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sni_push_title))
            .setMessage(base.removePrefix("https://"))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sni_action_panel) { _, _ ->
                val password = input.text.toString()
                activityScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { pushSniToPanelApi(base, password, sni) } }
                    result.onSuccess { added ->
                        Toast.makeText(this@MainActivity, getString(if (added) R.string.sni_push_ok else R.string.sni_push_exists, sni), Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(this@MainActivity, getString(R.string.sni_push_fail, e.message ?: "?"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun pushSniToPanelApi(base: String, password: String, sni: String): Boolean {
        fun call(path: String, method: String, body: String?, cookie: String?): Pair<java.net.HttpURLConnection, String> {
            val conn = java.net.URL("$base$path").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
            val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            return conn to stream.bufferedReader().readText()
        }
        val (login, loginBody) = call("/api/login", "POST", org.json.JSONObject().put("password", password).toString(), null)
        if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(getString(R.string.pip_wrong_password))
        val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
        login.disconnect()
        val (get, getBody) = call("/api/settings", "GET", null, cookie)
        val existing = ArrayList<String>()
        if (get.responseCode == 200) {
            val arr = org.json.JSONObject(getBody).optJSONObject("settings")?.optJSONArray("extraSnis")
            if (arr != null) for (i in 0 until arr.length()) existing += arr.optString(i)
        }
        get.disconnect()
        if (existing.any { it.equals(sni, ignoreCase = true) }) return false
        val merged = (existing + sni).distinct().take(8)
        val (put, putBody) = call("/api/settings", "PUT", org.json.JSONObject().put("extraSnis", org.json.JSONArray(merged)).toString(), cookie)
        val ok = put.responseCode == 200 && org.json.JSONObject(putBody).optBoolean("ok")
        put.disconnect()
        if (!ok) throw IllegalStateException("HTTP ${put.responseCode}")
        return true
    }

    private fun showProxyScannerPage() {
        ProxyScannerPage(this, palette, activityScope) { link -> showAddSubscriptionDialog(link, "") }.show()
    }

    /* ------------------------------------------------------------------ */
    /* Add server sheet (ZedSecure "Add server")                             */
    /* ------------------------------------------------------------------ */

    private fun showAddServerSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        fun item(glyph: String, labelRes: Int, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(TEAL, 40)), null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() })
            setOnClickListener { sheet.dismiss(); onClick() }
            addView(TextView(this@MainActivity).apply {
                text = glyph
                textSize = 18f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(TEAL)
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(TextView(this@MainActivity).apply {
                setText(labelRes)
                textSize = 16f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(16), dp(20), dp(28))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
                setColor(palette.surfaceElevated1)
            }
            addView(TextView(this@MainActivity).apply {
                setText(R.string.servers_add_title)
                textSize = 26f
                typeface = CatClientDisplayTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            })
            addView(advancedSectionLabel(getString(R.string.servers_add_import)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(item("📋", R.string.servers_add_clipboard) { addSubscriptionFromClipboard() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                addView(item("▣", R.string.servers_add_qr) { startSubscriptionQrScan() }, LinearLayout.LayoutParams(-1, -2))
            }
            addView(item("✎", R.string.servers_add_manual) { showAddSubscriptionDialog() }, LinearLayout.LayoutParams(-1, -2))
            addView(wizardText(getString(R.string.servers_add_file_hint), secondary = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        sheet.setContentView(body)
        (body.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.show()
    }

    private fun showCloudDeploymentDialog(result: CloudflareWorker.DeploymentResult) {
        PanelDeploymentStore(this).rememberLast(result.workerUrl, result.uuid)
        renderCloudDeploymentHistory()
        val status = if (result.verifiedOnline) {
            getString(R.string.cloud_verified_online)
        } else {
            getString(R.string.cloud_verify_pending)
        }
        val kvLine = if (result.kvBound) getString(R.string.cloud_kv_bound) else getString(R.string.cloud_kv_missing)
        val message = status + "\n" + kvLine + "\n\n" +
            getString(R.string.cloud_panel_url) + ":\n" + result.panelUrl + "\n\n" +
            getString(R.string.cloud_sub_label) + ":\n" + result.subscriptionUrl + "\n\n" +
            getString(R.string.cloud_uuid_is_password, result.uuid)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_deployed)
            .setMessage(message)
            .setPositiveButton(R.string.cloud_import_sub) { _, _ ->
                showAddSubscriptionDialog(result.subscriptionUrl, "Cat Panel")
            }
            .setNegativeButton(R.string.cloud_open_panel) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl)))
                }
            }
            .setNeutralButton(R.string.cloud_copy_all) { _, _ ->
                runCatching {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = "Panel: ${result.panelUrl}\nSub: ${result.subscriptionUrl}\nUUID / password: ${result.uuid}"
                    clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel", text))
                    Toast.makeText(this, R.string.cloud_sub_copied, Toast.LENGTH_LONG).show()
                }
            }
            .setOnCancelListener {
                runCatching {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-sub", result.subscriptionUrl))
                    Toast.makeText(this, R.string.cloud_sub_copied, Toast.LENGTH_LONG).show()
                    openScreen(SCREEN_SERVERS)
                }
            }
            .show()
    }

    private fun showResetSettingsDialog() {
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

    private fun scrollFieldIntoView(
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

    private fun advancedSectionLabel(value: String): TextView {
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

    private fun connectionChainFixedLabel(
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

    private fun buildConnectionChainSettings(): View {
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
            val detail = when (hop.mode) {
                ConnectionChainHopMode.Off -> getString(R.string.connection_chain_optional_detail)
                ConnectionChainHopMode.Automatic -> getString(R.string.connection_chain_automatic_detail)
                ConnectionChainHopMode.Fixed -> connectionChainFixedLabel(hop.profileRef, options)
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
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(
                            TextView(this@MainActivity).apply {
                                text = title
                                textSize = 15f
                                typeface = CatClientBodyBoldTypeface
                                setTextColor(if (hop.mode == ConnectionChainHopMode.Off) TEAL else TEXT_PRIMARY)
                                includeFontPadding = false
                            },
                        )
                        addView(
                            TextView(this@MainActivity).apply {
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
                if (optional && hop.mode != ConnectionChainHopMode.Off) {
                    addView(
                        TextView(this@MainActivity).apply {
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
                        TextView(this@MainActivity).apply {
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
            val fixedSubscriptionIds = listOf(settings.base, settings.after)
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
            listOf(ConnectionChainSlot.Base, ConnectionChainSlot.After).forEachIndexed { index, slot ->
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

    private fun updateConnectionChainSettings(settings: ConnectionChainSettings) {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) {
            renderChainSettingsPage?.invoke()
            return
        }
        val previous = connectionChainPreferenceStore.read()
        val normalized = settings.copy(before = ConnectionChainHop.off()).normalized()
        if (previous == normalized) return
        connectionChainPreferenceStore.save(normalized)
        renderChainSettingsPage?.invoke()
        renderConnectionDetails(buttonModel.state)
        renderConnectionSelection()
        if (
            buttonModel.state == VpnState.Started &&
            (previous.isActive || normalized.isActive) &&
            connectionChainCompatibilityIssue == null
        ) {
            reconnectForConnectionOptionChange()
        }
    }

    private fun openConnectionChainSettingsFromHome() {
        openScreen(SCREEN_SETTINGS)
        openConnectionChainSettingsPage?.invoke()
    }

    private fun cachedConnectionChainPickerOptions(
        subscriptionIds: Set<String>? = null,
    ): List<ConnectionChainPickerOption> =
        connectionChainPickerOptions(cachedConnectionChainSources(subscriptionIds))

    private fun cachedConnectionChainSources(
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

    private fun connectionChainPickerOptions(
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

    private fun connectionChainFixedOptions(
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

    private fun advancedSectionDetail(value: String): TextView {
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

    private fun advancedSettingsPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            background = glassSurfaceDrawable(radiusDp = 22)
            clipToOutline = true
            elevation = 0f
            setPadding(dp(8), dp(8), dp(8), dp(14))
        }
    }

    private fun advancedToggleRow(
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
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 14f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
            )
            addView(
                TextView(this@MainActivity).apply {
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

    private fun showPrivacyPolicyIfNeeded(onAccepted: (() -> Unit)? = null): Boolean {
        if (privacyPolicyStore.isAccepted()) return false
        showPrivacyPolicyDialog(onAccepted)
        return true
    }

    private fun showPrivacyPolicyDialog(onAccepted: (() -> Unit)? = null) {
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
                    this@MainActivity,
                    "privacy.accepted",
                    "version=${PrivacyPolicyAcceptancePolicy.CURRENT_VERSION}",
                )
                dialog.dismiss()
                onAccepted?.invoke()
            }
        }
    }

    private fun handleStateChanged(context: Context, intent: Intent) {
        val previousState = buttonModel.state
        val state = VpnState.fromWireName(
            intent.getStringExtra(Actions.EXTRA_STATE),
            intent.getStringExtra(Actions.EXTRA_ERROR),
        )
        VpnAnalyticsEventPolicy.forStatePublished(previousState, state)?.let {
            AnalyticsEvents.log(this, it)
        }
        if (previousState == VpnState.Started && state == VpnState.Stopping) {
            disconnectAnalyticsPending = true
        }
        if (state == VpnState.Stopped) {
            VpnAnalyticsEventPolicy.forDisconnectFinished(disconnectAnalyticsPending)?.let {
                AnalyticsEvents.log(this, it)
            }
            disconnectAnalyticsPending = false
        } else if (state == VpnState.Started || state is VpnState.Error) {
            disconnectAnalyticsPending = false
        }
        DiagnosticLogger.info(
            context,
            "activity.stateChanged",
            "state=${state.wireName}" + if (state is VpnState.Error) " message=${state.message}" else "",
        )
        if (state != VpnState.Starting) {
            connectFlowPending = false
        }
        if (state == VpnState.Started && previousState != VpnState.Started) {
            refreshLocationOptions()
        }
        if (state == VpnState.Starting || state == VpnState.Stopping) {
            closeConnectionTestingPage()
        }
        applyRuntimeState(
            state,
            intent.getLongExtra(Actions.EXTRA_SESSION_STARTED_AT_ELAPSED_MS, 0L),
            intent.getStringExtra(Actions.EXTRA_CONNECTION_COUNTRY_FLAG).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_DEBUG_FRONTING_IP).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_CONNECTION_DETAILS).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_ACTIVE_SUBSCRIPTION_ID).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_ACTIVE_CONNECTION_TAG).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_ACTIVE_CONNECTION_FINGERPRINT).orEmpty(),
            intent.getIntExtra(Actions.EXTRA_CHAIN_HOP_COUNT, 0),
            intent.getBooleanExtra(Actions.EXTRA_LIVE_SELECTOR_READY, false),
            intent.getStringArrayListExtra(Actions.EXTRA_SELECTABLE_CONNECTION_FINGERPRINTS)
                .orEmpty()
                .toSet(),
            intent.getBooleanExtra(Actions.EXTRA_ALWAYS_ON, false),
            intent.getBooleanExtra(Actions.EXTRA_LOCKDOWN, false),
        )
        intent.getStringExtra(Actions.EXTRA_NOTICE)
            ?.takeIf(String::isNotBlank)
            ?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    private fun applyRuntimeState(
        state: VpnState,
        startedAt: Long,
        countryFlag: String = "",
        frontingIp: String = "",
        details: String = "",
        runtimeSubscriptionId: String = "",
        runtimeConnectionTag: String = "",
        runtimeConnectionFingerprint: String = "",
        chainHopCount: Int = 0,
        selectorReady: Boolean = false,
        selectableConnectionFingerprints: Set<String> = emptySet(),
        alwaysOn: Boolean = false,
        lockdown: Boolean = false,
    ) {
        alwaysOnMode = alwaysOn
        lockdownMode = lockdown
        buttonModel.onStateChanged(state, alwaysOn)
        if (state == VpnState.Started) {
            activeRuntimeSubscriptionId = runtimeSubscriptionId
            activeConnectionTag = runtimeConnectionTag
            activeConnectionFingerprint = runtimeConnectionFingerprint
            activeChainHopCount = chainHopCount
            liveSelectorReady = selectorReady
            liveSelectableConnectionFingerprints = selectableConnectionFingerprints
            connectionCountryFlag = countryFlag
            debugFrontingIp = frontingIp
            connectionDetails = details
            sessionStartedAtElapsedMs = if (startedAt > 0L) startedAt else SystemClock.elapsedRealtime()
            startTimerUpdates()
            // Enable arrow animations when connected
            if (::downloadArrowIcon.isInitialized) downloadArrowIcon.isAnimating = true
            if (::uploadArrowIcon.isInitialized) uploadArrowIcon.isAnimating = true
            beginLiveGeoCheck()
        } else if (state == VpnState.Stopped || state == VpnState.DailyLimitReached || state is VpnState.Error) {
            activeRuntimeSubscriptionId = ""
            activeConnectionTag = ""
            activeConnectionFingerprint = ""
            activeChainHopCount = 0
            liveSelectorReady = false
            liveSelectableConnectionFingerprints = emptySet()
            connectionCountryFlag = ""
            debugFrontingIp = ""
            connectionDetails = ""
            sessionStartedAtElapsedMs = 0L
            mainHandler.removeCallbacks(timerRunnable)
            resetTransferSpeeds()
            // Disable arrow animations when disconnected
            if (::downloadArrowIcon.isInitialized) downloadArrowIcon.isAnimating = false
            liveGeo = null
            liveGeoAtMs = 0L
            if (::uploadArrowIcon.isInitialized) uploadArrowIcon.isAnimating = false
        }
        renderAlwaysOnStatus()
        renderState(state)
        if (!connectionChainPreferenceStore.read().enabled) renderConnectionSelection()
    }

    private fun handleButtonClick() {
        DiagnosticLogger.beginCapture(this)
        val nextAction = buttonModel.nextAction()
        if (nextAction == null) {
            DiagnosticLogger.info(
                this,
                "button.click.ignored",
                "currentState=${buttonModel.state.wireName} " +
                    "storedState=${VpnRuntimeStateStore.read(this).wireName} " +
                    "alwaysOn=$alwaysOnMode lockdown=$lockdownMode",
            )
            return
        }
        DiagnosticLogger.info(
            this,
            "button.click",
            "currentState=${buttonModel.state.wireName} nextAction=$nextAction",
        )
        when (nextAction) {
            Actions.CONNECT -> {
                if (!commitFrontingIpInput(reconnectIfChanged = false, focusOnError = true)) return
                if (!commitDnsPrivacyEndpoint(reconnectIfChanged = false, focusOnError = true)) return
                if (showPrivacyPolicyIfNeeded { beginConnectFlow() }) return
                beginConnectFlow()
            }
            Actions.DISCONNECT -> {
                connectFlowPending = false
                buttonModel.onStateChanged(VpnState.Stopping)
                renderState(VpnState.Stopping)
                startVpnService(Actions.DISCONNECT)
            }
        }
    }

    private fun handleRefreshClick() {
        if (buttonModel.state != VpnState.Started) return
        if (!commitFrontingIpInput(reconnectIfChanged = false, focusOnError = true)) return
        if (!commitDnsPrivacyEndpoint(reconnectIfChanged = false, focusOnError = true)) return
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val quickSpeedEligible =
            connectionProfiles.isNotEmpty() &&
                locationPreferenceStore.readSelectedCountryCode() == null &&
                connectionSelectionPreferenceStore.readSelectedProfile(
                    selectedSubscriptionId,
                    connectionProfiles,
                ) == null &&
                connectionSelectionPreferenceStore.readAutomaticTypes(
                    selectedSubscriptionId,
                    connectionProfiles,
                ).isEmpty() &&
                !connectionChainPreferenceStore.read().isActive &&
                frontingIpPreferenceStore.readFrontingIps().isEmpty()
        if (quickSpeedEligible) {
            Toast.makeText(
                this,
                R.string.connection_quick_fastest_reconnecting,
                Toast.LENGTH_LONG,
            ).show()
        }
        DiagnosticLogger.info(this, "button.refresh", "currentState=${buttonModel.state.wireName}")
        connectFlowPending = false
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        startVpnService(Actions.REFRESH)
    }

    private fun refreshLocationOptions() {
        renderLocationSelection()
        renderConnectionSelection()
        activityScope.launch {
            val cachedCatalog = withContext(Dispatchers.IO) {
                val store = SubscriptionStore(this@MainActivity)
                val selectedId = store.readSelectedSubscriptionId()
                if (SubscriptionStore.isBuiltInSubscription(selectedId)) {
                    store.readCatalog(selectedId)
                } else {
                    userSubscriptionManager.cachedSnapshot(selectedId)?.catalog
                }
            }
            if (cachedCatalog != null) {
                updateLocationOptions(cachedCatalog.profiles, resetMissingSelection = true)
            }

            val fetchedCatalog = runCatching {
                ConfigRepository(this@MainActivity).fetchOrCachedCatalog()
            }.onFailure { error ->
                DiagnosticLogger.warn(this@MainActivity, "activity.location.fetch.failed", error = error)
            }.getOrNull()

            if (fetchedCatalog != null) {
                updateLocationOptions(fetchedCatalog.profiles, resetMissingSelection = true)
            }
        }
    }

    private fun fetchPrivateSubscriptionOnLoad() {
        if (!SubscriptionStore.isBuiltInSubscription(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID)) return
        if (userSubscriptionManager.selectedId() == SubscriptionStore.PRIVATE_SUBSCRIPTION_ID) return
        activityScope.launch {
            runCatching {
                ConfigRepository(this@MainActivity).fetchOrCachedMihomoConfig(
                    SubscriptionStore.PRIVATE_SUBSCRIPTION_ID,
                )
            }.onSuccess {
                renderSubscriptions()
            }.onFailure { error ->
                DiagnosticLogger.warn(
                    this@MainActivity,
                    "activity.privateSubscription.fetch.failed",
                    error = error,
                )
            }
        }
    }

    private fun updateLocationOptions(
        profiles: List<ConnectionProfile>,
        resetMissingSelection: Boolean,
    ) {
        connectionProfiles = profiles
        val subscriptionStore = SubscriptionStore(this)
        connectionDelayRecords = subscriptionStore
            .readConnectionDelayRecords(
                subscriptionId = subscriptionStore.readSelectedSubscriptionId(),
                profiles = profiles,
            )
            .associateBy(ConnectionDelayRecord::fingerprint)
        val options = ConnectionLocationPolicy.selectorOptions(
            profiles = profiles,
            automaticLabel = getString(R.string.option_automatic),
            displayLocale = resources.configuration.locales[0],
        )
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        if (
            resetMissingSelection &&
            selectedCountryCode != null &&
            options.none { it.countryCode == selectedCountryCode }
        ) {
            locationPreferenceStore.clearSelectedCountry()
            DiagnosticLogger.info(
                this,
                "activity.location.reset",
                "missingCountry=$selectedCountryCode profiles=${profiles.size}",
            )
        }
        locationOptions = options
        DiagnosticLogger.info(
            this,
            "activity.location.options",
            "countries=${(options.size - 1).coerceAtLeast(0)} profiles=${profiles.size}",
        )
        renderLocationSelection()
        renderConnectionSelection()
    }

    private fun showConnectionTestingPage(
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
        val pageProfiles = if (pickerMode) {
            pickerOptions.filter { it.subscriptionId == selectedSubscriptionId }.map { it.profile }
        } else {
            connectionProfiles
        }
        val usesActiveRuntime = !pickerMode && buttonModel.state == VpnState.Started &&
            activeRuntimeSubscriptionId == selectedSubscriptionId
        val canRunConnectionTests = buttonModel.state != VpnState.Started ||
            (activeChainHopCount <= 1 && activeRuntimeSubscriptionId == selectedSubscriptionId)
        if (usesActiveRuntime && !liveSelectorReady) {
            Toast.makeText(this, R.string.connection_selector_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val selectorProfiles = if (usesActiveRuntime) {
            pageProfiles.filter { it.fingerprint in liveSelectableConnectionFingerprints }
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
                    MaterialAlertDialogBuilder(this@MainActivity)
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
                    val container = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val title = TextView(this@MainActivity).apply {
                        textSize = 14f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEXT_PRIMARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val detail = TextView(this@MainActivity).apply {
                        textSize = 12f
                        typeface = CatClientDataTypeface
                        setTextColor(TEXT_SECONDARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val delayBadge = TextView(this@MainActivity).apply {
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
                    val protocolBadges = LinearLayout(this@MainActivity).apply {
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
                    val speedButton = TextView(this@MainActivity).apply {
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
                    val speedAction = FrameLayout(this@MainActivity).apply {
                        visibility = View.GONE
                        isClickable = true
                        isFocusable = true
                        setSelectableBackground()
                        addView(speedButton, FrameLayout.LayoutParams(-2, dp(32), Gravity.CENTER))
                    }
                    // Selection indicator (small dot)
                    val checkIcon = View(this@MainActivity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(TEAL)
                        }
                        visibility = View.INVISIBLE
                    }
                    val textColumn = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(title, LinearLayout.LayoutParams(-1, -2))
                        addView(
                            LinearLayout(this@MainActivity).apply {
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

                    row = LinearLayout(this@MainActivity).apply {
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

                    holder.speedAction.visibility = if (delayMs != null) View.VISIBLE else View.GONE
                    holder.speedButton.visibility = View.VISIBLE
                    holder.speedButton.setText(
                        if (isSpeedTesting) R.string.connection_test_stop else R.string.connection_speed_test_label,
                    )
                    holder.speedButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        if (isSpeedTesting) R.drawable.ic_stop else R.drawable.ic_speedometer, 0, 0, 0,
                    )
                    holder.speedAction.isEnabled = isSpeedTesting ||
                        (canRunConnectionTests && delayMs != null && !testRunning)
                    holder.speedAction.alpha = if (holder.speedAction.isEnabled) 1f else 0.45f
                    holder.speedAction.contentDescription = if (isSpeedTesting) {
                        "${getString(R.string.connection_test_stop)}: ${profile.displayTag}"
                    } else {
                        getString(R.string.connection_speed_test_action, profile.displayTag)
                    }
                    holder.speedAction.setOnClickListener {
                        speedTests[profile.fingerprint]?.let { running ->
                            startService(
                                Intent(this@MainActivity, CatClientVpnService::class.java)
                                    .setAction(Actions.CANCEL_CONNECTION_SPEED_TEST)
                                    .putExtra(Actions.EXTRA_APP_INITIATED, true)
                                    .putExtra(Actions.EXTRA_SPEED_TEST_ID, running.testId),
                            )
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
                            Intent(this@MainActivity, CatClientVpnService::class.java)
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
                        val hop = if (profile == null) {
                            ConnectionChainHop.automatic()
                        } else {
                            ConnectionChainHop.fixed(selectedSubscriptionId, profile.fingerprint)
                        }
                        val current = connectionChainPreferenceStore.read()
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
                    showConnectionShareMenu(row, target)
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
                    ScrollView(this@MainActivity).apply { addView(choices) },
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
                    ScrollView(this@MainActivity).apply { addView(choices) },
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

        lateinit var pageDelayTestListener: (Intent) -> Unit
        var lastDelayResultsRefreshAtMs: Long? = null
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
        updateTestControls()
        setConnectionTestingPageVisible(visible = true, animate = animate && !rebuild)
    }

    private fun handleConnectionSelected(
        profile: ConnectionProfile?,
        selectedTypes: Set<String>,
    ) {
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val previous = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val previousAutomaticTypes = connectionSelectionPreferenceStore.readAutomaticTypes(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val automaticTypes = ConnectionTypeSelectionPolicy.restrictedTypes(
            selectedTypes,
            connectionProfiles,
        )
        val selectionChanged = previous?.fingerprint != profile?.fingerprint ||
            (profile == null && previousAutomaticTypes != automaticTypes)
        connectionSelectionPreferenceStore.saveAutomaticTypes(
            selectedSubscriptionId,
            selectedTypes,
            connectionProfiles,
        )
        val usesActiveRuntime = buttonModel.state == VpnState.Started &&
            activeRuntimeSubscriptionId == selectedSubscriptionId
        if (
            profile != null &&
            usesActiveRuntime &&
            (!liveSelectorReady || profile.fingerprint !in liveSelectableConnectionFingerprints)
        ) {
            Toast.makeText(this, R.string.connection_switch_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val activeSelectionChanged = profile != null &&
            activeConnectionFingerprint != profile.fingerprint
        if (profile != null && usesActiveRuntime && (selectionChanged || activeSelectionChanged)) {
            DiagnosticLogger.info(
                this,
                "activity.connection.switch.requested",
                "profile=${profile.tag}",
            )
            requestActiveConnectionSwitch(selectedSubscriptionId, profile.fingerprint)
            Toast.makeText(this, R.string.connection_switching, Toast.LENGTH_SHORT).show()
            return
        }
        if (!selectionChanged) return
        connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, profile)
        DiagnosticLogger.info(
            this,
            "activity.connection.selected",
            "mode=${if (profile == null) "automatic" else "explicit"} " +
                "profile=${profile?.tag.orEmpty()} types=${automaticTypes.sorted().joinToString(",")}",
        )
        renderConnectionSelection()
        renderLocationSelection()
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    private fun renderConnectionSelection() {
        if (!::connectionSelectorRow.isInitialized) return
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val profile = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val automaticTypes = connectionSelectionPreferenceStore.readAutomaticTypes(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val configuredValue = profile?.tag ?: if (automaticTypes.isEmpty()) {
            getString(R.string.option_automatic)
        } else {
            getString(
                R.string.connection_automatic_types_value,
                if (automaticTypes.size == 1) {
                    automaticTypes.first().uppercase(Locale.US)
                } else {
                    getString(R.string.connection_filter_types_count, automaticTypes.size)
                },
            )
        }
        val value = activeConnectionTag.takeIf {
            buttonModel.state == VpnState.Started && it.isNotBlank()
        }?.let { activeTag ->
            when {
                activeRuntimeSubscriptionId != selectedSubscriptionId ->
                    getString(R.string.connection_active_value, activeTag)
                profile == null -> getString(R.string.connection_automatic_active_value, activeTag)
                else -> activeTag
            }
        } ?: configuredValue
        connectionSelectorRow.setValue(value)
        connectionSelectorRow.contentDescription = getString(R.string.connection_content_description, value)
        if (::activeConfigTitle.isInitialized) activeConfigTitle.text = value
        renderHomeConnectionRows()
    }

    private fun renderHomeConnectionRows() {
        if (!::homeChainSelectorRows.isInitialized) return
        val settings = connectionChainPreferenceStore.read()
        homeChainSelectorRows.visibility = if (settings.enabled) View.VISIBLE else View.GONE
        if (!settings.enabled) return

        val fixedSubscriptionIds = listOf(settings.base, settings.after)
            .filter { it.mode == ConnectionChainHopMode.Fixed }
            .mapNotNull { it.profileRef?.subscriptionId }
            .toSet()
        val options = if (fixedSubscriptionIds.isNotEmpty()) {
            connectionChainFixedOptions(settings, cachedConnectionChainSources(fixedSubscriptionIds))
        } else {
            emptyList()
        }
        fun value(hop: ConnectionChainHop): String = when (hop.mode) {
            ConnectionChainHopMode.Off -> getString(R.string.connection_chain_not_set)
            ConnectionChainHopMode.Automatic -> getString(R.string.option_automatic)
            ConnectionChainHopMode.Fixed -> connectionChainFixedLabel(hop.profileRef, options)
        }
        fun render(row: DashboardDataRowView, @StringRes labelRes: Int, hop: ConnectionChainHop) {
            val label = getString(labelRes)
            val hopValue = value(hop)
            row.setValue(hopValue)
            row.contentDescription = getString(
                R.string.connection_chain_home_content_description,
                label,
                hopValue,
            )
        }
        render(connectionSelectorRow, R.string.connection_label, settings.base)
        render(homeChainAfterSelectorRow, R.string.connection_chain_after, settings.after)
    }

    private fun showLocationSelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        val options = dialogLocationOptions()
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val checkedIndex = options.indexOfFirst { it.countryCode == selectedCountryCode }.takeIf { it >= 0 } ?: 0
        val labels = options.map { option ->
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                "\u200F${option.label}"
            } else {
                option.label
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_selector_title)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
                handleLocationSelected(options[which])
                dialog.dismiss()
            }
            .create()
        dialog.showCatClientDialog()
    }

    private fun dialogLocationOptions(): List<LocationSelectorOption> {
        if (locationOptions.isEmpty()) return listOf(automaticLocationOption())
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val selectedOption = if (
            selectedCountryCode != null &&
            locationOptions.none { it.countryCode == selectedCountryCode }
        ) {
            ConnectionLocationPolicy.optionForCode(
                selectedCountryCode,
                resources.configuration.locales[0],
            )
        } else {
            null
        }
        if (selectedOption == null) return locationOptions
        return listOf(automaticLocationOption(), selectedOption) +
            locationOptions.filter { it.countryCode != null && it.countryCode != selectedCountryCode }
    }

    private fun handleLocationSelected(option: LocationSelectorOption) {
        val previousCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val hadExplicitConnection = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        ) != null
        if (previousCountryCode == option.countryCode && !hadExplicitConnection) return
        connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, null)
        locationPreferenceStore.saveSelectedCountryCode(option.countryCode)
        DiagnosticLogger.info(
            this,
            "activity.location.selected",
            "code=${option.countryCode ?: "auto"} label=${option.label}",
        )
        renderLocationSelection()
        renderConnectionSelection()
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    private fun renderLocationSelection() {
        if (!::locationSelectorRow.isInitialized) return
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val selectedCountryCode = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        )?.let(ConnectionLocationPolicy::countryForProfile)?.code
            ?: locationPreferenceStore.readSelectedCountryCode()
        val option = locationOptions.firstOrNull { it.countryCode == selectedCountryCode }
            ?: ConnectionLocationPolicy.optionForCode(
                selectedCountryCode,
                resources.configuration.locales[0],
            )
            ?: automaticLocationOption()
        locationSelectorRow.setValue(option.label)
        locationSelectorRow.contentDescription = getString(R.string.location_content_description, option.label)
    }

    private fun automaticLocationOption(): LocationSelectorOption =
        LocationSelectorOption(countryCode = null, label = getString(R.string.option_automatic))

    private fun showSubscriptionSelectorMenu(anchor: View) {
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

    private fun showThemeSelector() {
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
    private fun showAccentSelector() {
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
                            LinearLayout(this@MainActivity).apply {
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
                                    TextView(this@MainActivity).apply {
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
                                    TextView(this@MainActivity).apply {
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

    private fun showLanguageSelector() {
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

    private fun showDnsPrivacySelector() {
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

    private fun showRoutingModeSelector() {
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

    private fun renderRoutingModeSelection() {
        if (!::routingModeRow.isInitialized) return
        val mode = routingModePreferenceStore.read()
        val modeLabel = getString(mode.labelRes)
        routingModeValueText.text = modeLabel
        routingModeDetailText.setText(mode.detailRes)
        routingModeRow.contentDescription = getString(R.string.routing_content_description, modeLabel)
    }

    private fun handleDnsPrivacySelected(mode: DnsPrivacyMode): Boolean {
        val previousMode = dnsPrivacyPreferenceStore.readMode()
        val sameMode = previousMode == mode
        if (!commitDnsPrivacyEndpoint(reconnectIfChanged = sameMode, focusOnError = true)) return false
        if (sameMode) return true
        dnsPrivacyPreferenceStore.saveMode(mode)
        DiagnosticLogger.info(this, "activity.dnsPrivacy.saved", "mode=${mode.wireName}")
        if (::dnsPrivacyEndpointInput.isInitialized) dnsPrivacyEndpointInput.clearFocus()
        renderDnsPrivacySelection()
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
        return true
    }

    private fun commitDnsPrivacyEndpoint(
        reconnectIfChanged: Boolean,
        focusOnError: Boolean = false,
    ): Boolean {
        if (!::dnsPrivacyEndpointInput.isInitialized) return true
        val mode = dnsPrivacyPreferenceStore.readMode()
        if (mode == DnsPrivacyMode.Automatic) return true
        val previousValue = when (mode) {
            DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.readDohUrl()
            DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.readDotEndpoint()
            DnsPrivacyMode.Automatic -> return true
        }
        val nextValue = runCatching {
            when (mode) {
                DnsPrivacyMode.DoH -> DnsPrivacyPolicy.normalizeDohUrl(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.DoT -> DnsPrivacyPolicy.normalizeDotEndpoint(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.Automatic -> return true
            }
        }.getOrElse { error ->
            showDnsPrivacyError(localizedError(error, R.string.dns_invalid), focusOnError)
            return false
        }
        when (mode) {
            DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.saveDohUrl(nextValue)
            DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.saveDotEndpoint(nextValue)
            DnsPrivacyMode.Automatic -> Unit
        }
        setDnsPrivacyEndpointInputText(displayDnsPrivacyEndpoint(mode, nextValue))
        dnsPrivacyErrorText.visibility = View.GONE
        if (previousValue != nextValue && reconnectIfChanged && buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
        return true
    }

    private fun renderDnsPrivacySelection() {
        if (!::dnsPrivacyRow.isInitialized) return
        val mode = dnsPrivacyPreferenceStore.readMode()
        val modeLabel = getString(mode.labelRes)
        dnsPrivacyValueText.text = modeLabel
        dnsPrivacyDetailText.text = when (mode) {
            DnsPrivacyMode.Automatic -> getString(R.string.dns_automatic_detail)
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_detail)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_detail)
        }
        dnsPrivacyRow.contentDescription = getString(R.string.dns_content_description, modeLabel)
        if (
            !::dnsPrivacyEndpointInput.isInitialized ||
            !::dnsPrivacyEndpointLayout.isInitialized ||
            !::dnsPrivacyErrorText.isInitialized
        ) return
        val endpointVisible = mode != DnsPrivacyMode.Automatic
        dnsPrivacyEndpointLayout.visibility = if (endpointVisible) View.VISIBLE else View.GONE
        dnsPrivacyErrorText.visibility = View.GONE
        if (!endpointVisible) return
        dnsPrivacyEndpointLayout.hint = when (mode) {
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_address)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_address)
            DnsPrivacyMode.Automatic -> ""
        }
        dnsPrivacyEndpointLayout.helperText = when (mode) {
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_helper)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_helper)
            DnsPrivacyMode.Automatic -> ""
        }
        if (!dnsPrivacyEndpointInput.hasFocus()) {
            val value = when (mode) {
                DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.readDohUrl()
                DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.readDotEndpoint()
                DnsPrivacyMode.Automatic -> ""
            }
            setDnsPrivacyEndpointInputText(displayDnsPrivacyEndpoint(mode, value))
        }
    }

    private fun displayDnsPrivacyEndpoint(mode: DnsPrivacyMode, value: String): String {
        return if (mode == DnsPrivacyMode.DoT) value.removePrefix("tls://") else value
    }

    private fun setDnsPrivacyEndpointInputText(value: String) {
        if (dnsPrivacyEndpointInput.text.toString() == value) return
        dnsPrivacyInputUpdating = true
        try {
            dnsPrivacyEndpointInput.setText(value)
            dnsPrivacyEndpointInput.setSelection(dnsPrivacyEndpointInput.text.length)
        } finally {
            dnsPrivacyInputUpdating = false
        }
    }

    private fun showDnsPrivacyError(message: String, focusOnError: Boolean) {
        dnsPrivacyErrorText.text = message
        dnsPrivacyErrorText.visibility = View.VISIBLE
        if (focusOnError) dnsPrivacyEndpointInput.requestFocus()
    }

    private fun showSplitTunnelSelector(container: LinearLayout) {
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
                DiagnosticLogger.warn(this@MainActivity, "activity.splitTunnel.apps.failed", error = error)
            }.getOrNull()

            if (apps == null) {
                container.removeAllViews()
                container.addView(
                    advancedSectionDetail(getString(R.string.split_tunnel_empty_apps)),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
                )
                Toast.makeText(this@MainActivity, R.string.split_tunnel_empty_apps, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showSplitTunnelSettings(container, apps)
        }
    }

    private fun showSplitTunnelSettings(
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
                    val checkBox = CheckBox(this@MainActivity).apply {
                        buttonTintList = ColorStateList(
                            arrayOf(
                                intArrayOf(android.R.attr.state_checked),
                                intArrayOf(),
                            ),
                            intArrayOf(TEAL, TEXT_SECONDARY),
                        )
                    }
                    val icon = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    val label = TextView(this@MainActivity).apply {
                        textSize = 14f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(TEXT_PRIMARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                    }
                    val packageName = TextView(this@MainActivity).apply {
                        textSize = 12f
                        typeface = CatClientBodyTypeface
                        setTextColor(TEXT_SECONDARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                        textDirection = View.TEXT_DIRECTION_LTR
                    }
                    val appText = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(label, LinearLayout.LayoutParams(-1, -2))
                        addView(
                            packageName,
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                        )
                    }
                    row = LinearLayout(this@MainActivity).apply {
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
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderAppList(s?.toString().orEmpty())
            }
        })

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
                this@MainActivity,
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

    private fun commitFrontingIpInput(
        reconnectIfChanged: Boolean,
        focusOnError: Boolean = false,
    ): Boolean {
        if (!::frontingIpInput.isInitialized) return true
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        val pendingValue = frontingIpInput.text?.toString().orEmpty()
        if (pendingValue.isNotBlank()) {
            val nextIps = runCatching {
                FrontingIpPolicy.normalizeIps((frontingIps + pendingValue.split(",")).joinToString(","))
            }.getOrElse { error ->
                showFrontingIpError(localizedError(error, R.string.fronting_invalid), focusOnError)
                return false
            }
            frontingIps = nextIps
            setFrontingIpInputText("")
        }
        return saveFrontingIps(reconnectIfChanged, previousValue)
    }

    private fun saveTlsIntegrityEnabled(enabled: Boolean) {
        val previousValue = tlsIntegrityPreferenceStore.isEnabled()
        if (previousValue == enabled) return
        tlsIntegrityPreferenceStore.saveEnabled(enabled)
        if (!enabled) CatClientScanStateStore(this).clearTlsQuarantine()
        DiagnosticLogger.info(this, "activity.tlsIntegrity.saved", "enabled=$enabled")
        reconnectForConnectionOptionChange()
    }

    private fun saveAdBlockEnabled(enabled: Boolean) {
        if (routingModePreferenceStore.isAdBlockEnabled() == enabled) return
        routingModePreferenceStore.saveAdBlockEnabled(enabled)
        DiagnosticLogger.info(this, "activity.adBlock.saved", "enabled=$enabled")
        Toast.makeText(this, R.string.ad_block_toast, Toast.LENGTH_SHORT).show()
    }

    private fun saveTlsFragmentEnabled(enabled: Boolean) {
        if (dpiBypassPreferenceStore.isEnabled() == enabled) return
        dpiBypassPreferenceStore.saveEnabled(enabled)
        DiagnosticLogger.info(this, "activity.tlsFragment.saved", "enabled=$enabled")
        Toast.makeText(
            this,
            if (enabled) R.string.tls_fragment_enabled_toast else R.string.tls_fragment_disabled_toast,
            Toast.LENGTH_SHORT,
        ).show()
        reconnectForConnectionOptionChange()
    }

    private fun saveLanSharingEnabled(enabled: Boolean) {
        if (lanSharingPreferenceStore.read().enabled == enabled) return
        lanSharingPreferenceStore.saveEnabled(enabled)
        DiagnosticLogger.info(this, "activity.lanSharing.saved", "enabled=$enabled")
        renderLanSharingControls(settingsEnabled = true)
        reconnectForConnectionOptionChange()
    }

    private fun saveConnectionMode(mode: ConnectionMode) {
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

    private fun saveLanSharingPasswordRequired(required: Boolean) {
        val settings = lanSharingPreferenceStore.read()
        if (settings.passwordRequired == required) return
        lanSharingPreferenceStore.savePasswordRequired(required)
        DiagnosticLogger.info(this, "activity.lanSharing.passwordRequired.saved", "required=$required")
        renderLanSharingControls(settingsEnabled = true)
        if (settings.enabled) reconnectForConnectionOptionChange()
    }

    private fun regenerateLanSharingPassword() {
        val settings = lanSharingPreferenceStore.read()
        lanSharingPreferenceStore.regeneratePassword()
        DiagnosticLogger.info(this, "activity.lanSharing.password.regenerated", "enabled=${settings.enabled}")
        renderLanSharingControls(settingsEnabled = true)
        if (settings.enabled && settings.passwordRequired) reconnectForConnectionOptionChange()
    }

    private fun copyLanSharingSettings() {
        val settings = lanSharingPreferenceStore.read()
        val value = lanSharingDetails(settings)
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Cat Client LAN proxy", value))
        Toast.makeText(this, R.string.lan_sharing_copied, Toast.LENGTH_SHORT).show()
    }

    private fun renderLanSharingControls(settingsEnabled: Boolean) {
        val settings = lanSharingPreferenceStore.read()
        val selectedMode = connectionModePreferenceStore.read()
        val usesVpnTunnel = ConnectionModePolicy.shouldStartTun(selectedMode, alwaysOnMode, lockdownMode)
        if (::connectionModeGroup.isInitialized) {
            val modeSelectionEnabled = settingsEnabled && !alwaysOnMode && !lockdownMode
            connectionModeGroup.isEnabled = modeSelectionEnabled
            vpnModeButton.isEnabled = modeSelectionEnabled
            proxyModeButton.isEnabled = modeSelectionEnabled
            val selectedId = if (usesVpnTunnel) vpnModeButton.id else proxyModeButton.id
            if (connectionModeGroup.checkedButtonId != selectedId) connectionModeGroup.check(selectedId)
        }
        renderDashboardLocalEndpoint(if (usesVpnTunnel) ConnectionMode.Vpn else ConnectionMode.Proxy)
        if (::lanSharingCheckbox.isInitialized) {
            val details = lanSharingDetails(settings)
            lanSharingCheckbox.isChecked = settings.enabled
            lanSharingCheckbox.isEnabled = settingsEnabled
            lanSharingPasswordCheckbox.isChecked = settings.passwordRequired
            lanSharingPasswordCheckbox.isEnabled = settingsEnabled
            lanSharingDetailsText.text = details
            lanSharingDetailsText.contentDescription =
                getString(R.string.lan_sharing_details_accessibility, details)
            lanSharingDetailsText.isEnabled = settingsEnabled
            lanSharingDetailsText.alpha = when {
                !settingsEnabled -> 0.45f
                settings.enabled -> 1f
                else -> 0.7f
            }
            lanSharingRegenerateButton.visibility =
                if (settings.passwordRequired) View.VISIBLE else View.GONE
            lanSharingRegenerateButton.isEnabled = settingsEnabled
        }
    }

    private fun lanSharingDetails(settings: LanSharingSettings): String {
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

    private fun saveAmneziaNoiseEnabled(enabled: Boolean) {
        val previous = connectionOptionsPreferenceStore.read()
        val settings = if (enabled) {
            runCatching(::readAmneziaNoiseInputs).getOrElse { error ->
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

    private fun applyAmneziaNoiseSettings() {
        val previous = connectionOptionsPreferenceStore.read()
        val settings = runCatching(::readAmneziaNoiseInputs).getOrElse { error ->
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

    private fun readAmneziaNoiseInputs(): AmneziaNoiseSettings {
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

    private fun optionalAmneziaInt(input: EditText): Int? {
        val value = input.text.toString().trim()
        if (value.isEmpty()) return null
        return value.toIntOrNull() ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_invalid))
    }

    private fun optionalAmneziaText(input: EditText): String? =
        input.text.toString().trim().takeIf(String::isNotEmpty)

    private fun optionalAmneziaBoolean(input: EditText): Boolean? {
        return when (input.text.toString().trim().lowercase()) {
            "" -> null
            "true", "on", "1" -> true
            "false", "off", "0" -> false
            else -> throw IllegalArgumentException(getString(R.string.amnezia_noise_invalid))
        }
    }

    private fun showAmneziaNoiseError(message: String) {
        amneziaNoiseErrorText.text = message
        amneziaNoiseErrorText.visibility = View.VISIBLE
    }

    private fun reconnectForConnectionOptionChange() {
        if (buttonModel.state != VpnState.Started) return
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        startVpnService(Actions.RECONNECT)
    }

    private fun renderConnectionOptionsControls(settingsEnabled: Boolean) {
        if (!::amneziaNoiseCheckbox.isInitialized) return
        val options = connectionOptionsPreferenceStore.read()
        amneziaNoiseCheckbox.isChecked = options.amneziaNoiseEnabled
        amneziaNoiseCheckbox.isEnabled = settingsEnabled
        val noiseFieldsEnabled = settingsEnabled && options.amneziaNoiseEnabled
        val noiseInputs = listOf(
            amneziaNoiseCountInput,
            amneziaNoiseMinSizeInput,
            amneziaNoiseMaxSizeInput,
            amneziaNoiseTtlInput,
            amneziaNoiseVersionInput,
            amneziaNoiseIpStackInput,
            amneziaNoiseCongestionInput,
            amneziaNoiseHeaderProtectionKeyInput,
            amneziaNoiseContentPaddingInput,
            amneziaNoiseRekeyAfterInput,
            amneziaNoiseRekeyTimeoutInput,
            amneziaNoiseRejectAfterInput,
            amneziaNoiseKeepaliveTimeoutInput,
            amneziaNoiseMaxHandshakeAttemptsInput,
            amneziaNoiseRandomTrailersInput,
            amneziaNoiseDisableCookiesInput,
        )
        noiseInputs.forEach {
            it.isEnabled = noiseFieldsEnabled
        }
        amneziaNoiseFields.alpha = if (noiseFieldsEnabled) 1f else 0.45f
        amneziaNoiseApplyButton.visibility = if (options.amneziaNoiseEnabled) View.VISIBLE else View.GONE
        amneziaNoiseApplyButton.isEnabled = noiseFieldsEnabled
        fun renderInput(input: EditText, value: Any?) {
            if (!input.hasFocus()) input.setText(value?.toString().orEmpty())
        }
        with(options.amneziaNoise) {
            renderInput(amneziaNoiseCountInput, count)
            renderInput(amneziaNoiseMinSizeInput, minSize)
            renderInput(amneziaNoiseMaxSizeInput, maxSize)
            renderInput(amneziaNoiseTtlInput, fakeTtl)
            renderInput(amneziaNoiseVersionInput, version)
            renderInput(amneziaNoiseIpStackInput, ipStackMode)
            renderInput(amneziaNoiseCongestionInput, congestionController)
            renderInput(amneziaNoiseHeaderProtectionKeyInput, headerProtectionKey)
            renderInput(amneziaNoiseContentPaddingInput, contentPaddingAddition)
            renderInput(amneziaNoiseRekeyAfterInput, rekeyAfterTime)
            renderInput(amneziaNoiseRekeyTimeoutInput, rekeyTimeout)
            renderInput(amneziaNoiseRejectAfterInput, rejectAfterTime)
            renderInput(amneziaNoiseKeepaliveTimeoutInput, keepaliveTimeout)
            renderInput(amneziaNoiseMaxHandshakeAttemptsInput, maxHandshakeAttempts)
            renderInput(amneziaNoiseRandomTrailersInput, randomTrailers)
            renderInput(amneziaNoiseDisableCookiesInput, disableCookies)
        }
        if (noiseFieldsEnabled) amneziaNoiseErrorText.visibility = View.GONE
    }

    private fun saveFrontingIps(reconnectIfChanged: Boolean, previousValue: String?): Boolean {
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

    private fun addFrontingIpTokens(tokens: List<String>, focusOnError: Boolean): Boolean {
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

    private fun removeFrontingIp(ip: String) {
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        frontingIps = frontingIps.filterNot { it == ip }
        renderFrontingIpChips()
        saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)
    }

    private fun renderFrontingIpChips() {
        if (!::frontingIpChipGroup.isInitialized) return
        val controlsEnabled = !::frontingIpInput.isInitialized || frontingIpInput.isEnabled
        frontingIpChipGroup.removeAllViews()
        frontingIpChipGroup.visibility = if (frontingIps.isEmpty()) View.GONE else View.VISIBLE
        frontingIps.forEach { ip ->
            frontingIpChipGroup.addView(
                Chip(this).apply {
                    text = ip
                    textSize = 12f
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    isCheckable = false
                    isCloseIconVisible = true
                    setTextColor(TEXT_PRIMARY)
                    chipBackgroundColor = ColorStateList.valueOf(SURFACE)
                    chipStrokeColor = ColorStateList.valueOf(OUTLINE)
                    chipStrokeWidth = dp(1).toFloat()
                    closeIconTint = ColorStateList.valueOf(TEXT_SECONDARY)
                    isEnabled = controlsEnabled
                    setOnCloseIconClickListener { removeFrontingIp(ip) }
                },
            )
        }
        if (::frontingIpInputLayout.isInitialized) {
            frontingIpInputLayout.helperText = frontingIpInputHint()
        }
    }

    private fun setFrontingIpInputText(value: String) {
        frontingIpInputUpdating = true
        try {
            frontingIpInput.setText(value)
            frontingIpInput.setSelection(frontingIpInput.text.length)
        } finally {
            frontingIpInputUpdating = false
        }
    }

    private fun frontingIpInputHint(): String {
        return if (frontingIps.size >= 5) {
            getString(R.string.fronting_capacity_full)
        } else {
            getString(R.string.fronting_helper)
        }
    }

    private fun showFrontingIpError(message: String, focusOnError: Boolean) {
        frontingIpErrorText.text = message
        frontingIpErrorText.visibility = View.VISIBLE
        if (focusOnError) {
            frontingIpInput.requestFocus()
        }
    }

    private fun renderAdvancedControls() {
        val settingsEnabled = buttonModel.state != VpnState.Starting && buttonModel.state != VpnState.Stopping
        if (::tlsIntegrityCheckbox.isInitialized) {
            tlsIntegrityCheckbox.isChecked = tlsIntegrityPreferenceStore.isEnabled()
            tlsIntegrityCheckbox.isEnabled = settingsEnabled
        }
        renderConnectionOptionsControls(settingsEnabled)
        renderLanSharingControls(settingsEnabled)
        renderChainSettingsPage?.invoke()
        renderRoutingModeSelection()
        if (::routingModeRow.isInitialized) {
            routingModeRow.isEnabled = settingsEnabled
            routingModeRow.alpha = if (settingsEnabled) 1f else 0.45f
        }
        renderDnsPrivacySelection()
        updateSplitTunnelControlsEnabled?.invoke(settingsEnabled)
    }

    private fun beginConnectFlow(action: String = Actions.CONNECT) {
        connectFlowPending = true
        connectFlowAction = action
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (!connectFlowPending) {
            DiagnosticLogger.info(this, "permission.notification.skip", "reason=connect-canceled")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            DiagnosticLogger.info(this, "permission.notification", "requesting")
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        DiagnosticLogger.info(this, "permission.notification", "already granted or not required")
        requestVpnPermissionThenConnect()
    }

    private fun requestVpnPermissionThenConnect() {
        if (!connectFlowPending) {
            DiagnosticLogger.info(this, "permission.vpn.skip", "reason=connect-canceled")
            return
        }
        if (!ConnectionModePolicy.shouldStartTun(
                connectionModePreferenceStore.read(),
                alwaysOnMode,
                lockdownMode,
            )
        ) {
            DiagnosticLogger.info(this, "permission.vpn", "skipped mode=proxy")
            startPendingConnectAction()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            DiagnosticLogger.info(this, "permission.vpn", "requesting")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_VPN_PERMISSION)
        } else {
            DiagnosticLogger.info(this, "permission.vpn", "already granted")
            startPendingConnectAction()
        }
    }

    private fun startPendingConnectAction() {
        val action = connectFlowAction
        connectFlowPending = false
        connectFlowAction = Actions.CONNECT
        startVpnService(action)
    }

    private fun startVpnService(action: String) {
        DiagnosticLogger.info(this, "service.intent", "action=$action")
        val intent = Intent(this, CatClientVpnService::class.java)
            .setAction(action)
            .putExtra(Actions.EXTRA_APP_INITIATED, true)
        if (action == Actions.CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestActiveConnectionSwitch(subscriptionId: String, fingerprint: String) {
        startService(
            Intent(this, CatClientVpnService::class.java)
                .setAction(Actions.SWITCH_CONNECTION)
                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                .putExtra(Actions.EXTRA_CONNECTION_FINGERPRINT, fingerprint),
        )
    }

    private fun renderAlwaysOnStatus() {
        if (!::alwaysOnStatusText.isInitialized) return
        alwaysOnStatusText.setText(
            when {
                lockdownMode -> R.string.always_on_status_lockdown
                alwaysOnMode -> R.string.always_on_status_active
                else -> R.string.always_on_status_inactive
            },
        )
        alwaysOnStatusText.setTextColor(if (alwaysOnMode) TEAL else TEXT_SECONDARY)
    }

    /**
     * Resolve the REAL exit location through the live tunnel (Cloudflare trace
     * first: it reports the actual edge colo + exit IP) and feed the world map.
     * Re-checked at most every 45 seconds while connected.
     */
    private fun beginLiveGeoCheck(force: Boolean = false) {
        if (liveGeoJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!force && liveGeo != null && now - liveGeoAtMs < 45_000L) return
        val tunneledAtFetch = currentVpnStateIsStarted()
        liveGeoJob = activityScope.launch {
            // The deployed panel worker knows BOTH IPs in one call: the phone's
            // real ISP IP (tunnel entry) and the exit IP websites see.
            val workerHost = runCatching {
                PanelDeploymentStore(this@MainActivity).deployments()
                    .firstOrNull()?.workerUrl?.removePrefix("https://")?.trimEnd('/')
            }.getOrNull()
            val tunnelProxy = if (tunneledAtFetch) {
                runCatching {
                    java.net.Proxy(
                        java.net.Proxy.Type.HTTP,
                        java.net.InetSocketAddress(MihomoRuntimeDefaults.CONTROLLER_HOST, MihomoRuntimeDefaults.MIXED_PORT),
                    )
                }.getOrNull()
            } else null
            val info = runCatching {
                IpGeolocation.locate(
                    proxy = tunnelProxy,
                    workerHost = workerHost,
                    physicalNetwork = activePhysicalNetwork(),
                    includeReal = tunneledAtFetch,
                )
            }.getOrNull()
            liveGeoAtMs = SystemClock.elapsedRealtime()
            if (info == null) return@launch
            liveGeo = info
            liveGeoTunneled = tunneledAtFetch
            if (tunneledAtFetch) {
                getSharedPreferences("cat_client_map", MODE_PRIVATE).let { prefs ->
                    if (!info.realCountryCode.isNullOrBlank() && prefs.getString("origin_code", "").isNullOrBlank()) {
                        prefs.edit().putString("origin_code", info.realCountryCode.uppercase())
                            .putString("origin_label", info.realCountryName.orEmpty()).apply()
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) { measureTunnelPingQuietly() }
            } else if (!currentVpnStateIsStarted() && !deviceVpnActive()) {
                getSharedPreferences("cat_client_map", MODE_PRIVATE).edit()
                    .putString("origin_code", info.countryCode.uppercase())
                    .putString("origin_label", listOfNotNull(info.city?.takeIf { it.isNotBlank() }, info.countryName).joinToString(", "))
                    .apply()
            }
            val label = buildString {
                if (!tunneledAtFetch) append(getString(R.string.route_direct_state) + " · ")
                append(info.countryName)
                info.colo?.let { colo -> append(" · ").append(colo) }
            }
            connectionGlobe.setDestination(info.flag, label, info.ip)
            if (::connectionCountryText.isInitialized && liveGeo != null && tunneledAtFetch) {
                connectionCountryText.text = getString(R.string.route_location, info.flag, info.countryName)
            }
            renderRealIpLine(info, tunneledAtFetch)
        }
    }

    /** The underlying physical network (Wi-Fi / cellular) — binding a request
     * to it bypasses our own TUN, so the caller sees the REAL ISP IP. */
    private fun activePhysicalNetwork(): android.net.Network? = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrNull()

    /** "Real IP: 80.x.x.x 🇮🇷" — the tunnel ENTRY, shown next to the exit so the
     * dashboard always agrees with what "what is my ip" pages display. */
    /** Zed ConnectionInfoPill ping: lime when < 250 ms, error-red otherwise, "—" while unknown. */
    private fun renderPingValue() {
        if (!::pingValueText.isInitialized) return
        val ping = livePingMs
        pingValueText.text = if (ping != null) "$ping ms" else "—"
        pingValueText.setTextColor(if (ping == null) TEXT_SECONDARY else if (ping < 250) ZedBlobView.ZED_LIME else ERROR)
    }

    /** Silent background ping through the tunnel (feeds the Home pill and the Map card). */
    private fun measureTunnelPingQuietly() {
        if (tunnelPingRunning || !currentVpnStateIsStarted()) return
        tunnelPingRunning = true
        activityScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val tunnelProxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress(MihomoRuntimeDefaults.CONTROLLER_HOST, MihomoRuntimeDefaults.MIXED_PORT),
            )
            val samples = (1..2).mapNotNull {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val code = runCatching {
                    val conn = java.net.URL(MihomoRuntimeDefaults.DELAY_TEST_URL).openConnection(tunnelProxy) as java.net.HttpURLConnection
                    conn.connectTimeout = 4_000; conn.readTimeout = 4_000; conn.instanceFollowRedirects = false
                    val c = conn.responseCode
                    runCatching { conn.inputStream.close() }
                    conn.disconnect()
                    c
                }.getOrNull()
                if (code != null && code in 200..399) android.os.SystemClock.elapsedRealtime() - t0 else null
            }
            if (samples.isNotEmpty()) livePingMs = samples.min()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                tunnelPingRunning = false
                renderPingValue()
            }
        }
    }

    /** Real ping THROUGH the active config: samples the local mihomo mixed
     * proxy (the exact tunnel path), like v2rayNG/V2Box delay tests — with an
     * on-the-spot result toast so the feedback is never missed. */
    private fun runTunnelPing() {
        if (tunnelPingRunning) return
        if (!currentVpnStateIsStarted()) {
            Toast.makeText(this, R.string.route_ping_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        tunnelPingRunning = true
        pingValueText.text = "…"
        Toast.makeText(this, R.string.route_ping_testing, Toast.LENGTH_SHORT).show()
        val started = currentVpnStateIsStarted()
        activityScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val tunnelProxy = java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress(MihomoRuntimeDefaults.CONTROLLER_HOST, MihomoRuntimeDefaults.MIXED_PORT),
            )
            fun sample(throughProxy: Boolean): Long? {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val ok = runCatching {
                    val conn = (if (throughProxy) {
                        java.net.URL(MihomoRuntimeDefaults.DELAY_TEST_URL).openConnection(tunnelProxy)
                    } else {
                        java.net.URL(MihomoRuntimeDefaults.DELAY_TEST_URL).openConnection()
                    }) as java.net.HttpURLConnection
                    conn.connectTimeout = 4_000
                    conn.readTimeout = 4_000
                    conn.instanceFollowRedirects = false
                    val code = conn.responseCode
                    runCatching { conn.inputStream.close() }
                    conn.disconnect()
                    code
                }.getOrNull()
                return if (ok != null && ok in 200..399) android.os.SystemClock.elapsedRealtime() - t0 else null
            }
            var samples = (1..3).mapNotNull { sample(true) }
            if (samples.isEmpty() && started) samples = (1..3).mapNotNull { sample(false) }
            val best = samples.minOrNull()
            livePingMs = best
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                renderPingValue()
                Toast.makeText(
                    this@MainActivity,
                    if (best != null) getString(R.string.route_ping_result, best) else getString(R.string.route_ping_failed),
                    Toast.LENGTH_LONG,
                ).show()
                tunnelPingRunning = false
            }
        }
    }

    /** Manual "sync with the site" — forces a fresh family-forced probe so
     * the dashboard can be compared against whatismyipaddress right away. */
    private fun refreshDashboardIp() {
        liveGeoDirectAttempted = false
        liveGeo = null
        beginLiveGeoCheck(force = true)
        Toast.makeText(this, R.string.ip_refreshing, Toast.LENGTH_SHORT).show()
    }

    /** Ping EVERY profile of the active subscription through the running core
     * (the service's own concurrent delay sweep) and, with [autoConnect],
     * switch to the fastest one — v2rayNG 'Ping All' + best-connect. */
    private fun runPingAll(autoConnect: Boolean) {
        if (pingAllRunning) return
        val profiles = connectionProfiles
        if (profiles.isEmpty()) {
            Toast.makeText(this, R.string.ping_all_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val subscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val testId = android.os.SystemClock.elapsedRealtimeNanos().toString()
        pingAllRunning = true
        Toast.makeText(this, R.string.ping_all_started, Toast.LENGTH_LONG).show()
        ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = testId,
                subscriptionId = subscriptionId,
                connectionTypes = emptySet(),
                targetFingerprints = profiles.map(ConnectionProfile::fingerprint),
                status = Actions.DELAY_TEST_PREPARING,
                total = profiles.size,
            ),
        )
        startForegroundService(
            Intent(this, CatClientVpnService::class.java)
                .setAction(Actions.TEST_CONNECTION_DELAYS)
                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                .putExtra(Actions.EXTRA_DELAY_TEST_ID, testId)
                .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                .putStringArrayListExtra(Actions.EXTRA_CONNECTION_TYPES, ArrayList<String>())
                .putStringArrayListExtra(
                    Actions.EXTRA_CONNECTION_FINGERPRINTS,
                    ArrayList(profiles.map(ConnectionProfile::fingerprint)),
                ),
        )
        activityScope.launch {
            val deadline = android.os.SystemClock.elapsedRealtime() + 4 * 60_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                kotlinx.coroutines.delay(700)
                val snap = ConnectionDelayTestState.snapshot(subscriptionId) ?: continue
                if (!snap.isRunning || snap.completed >= snap.total) break
            }
            val records = SubscriptionStore(this@MainActivity).readConnectionDelayRecords(subscriptionId, profiles)
            val best = records.firstOrNull { it.status == ConnectionDelayStatus.Success }
            val bestProfile = best?.let { r -> profiles.firstOrNull { it.fingerprint == r.fingerprint } }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                pingAllRunning = false
                if (bestProfile == null) {
                    Toast.makeText(this@MainActivity, R.string.ping_all_none, Toast.LENGTH_LONG).show()
                } else if (autoConnect) {
                    connectionSelectionPreferenceStore.saveSelectedProfile(subscriptionId, bestProfile)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.ping_all_connecting, bestProfile.displayTag, best?.delayMs ?: 0),
                        Toast.LENGTH_LONG,
                    ).show()
                    startVpnService(Actions.RECONNECT)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.ping_all_done,
                            bestProfile.displayTag,
                            best?.delayMs ?: 0,
                            records.count { it.status == ConnectionDelayStatus.Success },
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** Zed ConnectionInfoPill second line: the exit (server) IP only — never the phone's own IP. */
    private fun renderRealIpLine(info: IpGeolocation.Info?, tunneled: Boolean) {
        if (!::connectionRealIpText.isInitialized) return
        connectionV6Text.visibility = View.GONE
        if (info == null || !tunneled || info.ip.isBlank()) {
            connectionRealIpText.visibility = View.GONE
            return
        }
        connectionRealIpText.text = info.ip
        connectionRealIpText.visibility = View.VISIBLE
    }

    private fun currentVpnStateIsStarted(): Boolean = vpnCurrentlyStarted

    private fun pendingGlobeCountry(): ConnectionCountry? {
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val explicitProfile = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        )
        return explicitProfile?.let(ConnectionLocationPolicy::countryForProfile)
            ?: locationPreferenceStore.readSelectedCountryCode()?.let(ConnectionLocationPolicy::countryFromCode)
    }

    /** Aras-style capsule painting: filled state gradient while the tunnel is
     * up/transitioning/broken, quiet glass while idle. Purple = Cat identity. */
    private fun connectPillGradient(state: VpnState): IntArray? = when {
        state == VpnState.Started -> intArrayOf(palette.tealGradientStart, palette.tealGradientEnd)
        state == VpnState.Starting || state == VpnState.Stopping ->
            intArrayOf(withAlpha(palette.amberGradientStart, 240), withAlpha(palette.amberGradientEnd, 240))
        state is VpnState.Error || state == VpnState.DailyLimitReached ->
            intArrayOf(withAlpha(palette.redGradientStart, 240), withAlpha(palette.redGradientEnd, 240))
        else -> null
    }

    private fun applyConnectPillStyle(state: VpnState) {
        if (!::connectActionButton.isInitialized) return
        val gradient = connectPillGradient(state)
        connectActionButton.backgroundTintList = null
        if (gradient != null) {
            connectActionButton.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                orientation = GradientDrawable.Orientation.TL_BR
                colors = gradient
            }
            connectActionButton.setTextColor(Color.WHITE)
        } else {
            connectActionButton.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(withAlpha(SURFACE, 235))
                setStroke(dp(1), withAlpha(OUTLINE, 210))
            }
            connectActionButton.setTextColor(TEAL)
        }
    }

    private var lastHapticState: VpnState? = null

    private fun connectHapticEnabled(): Boolean =
        getSharedPreferences("cat_client_theme", MODE_PRIVATE).getBoolean("haptic_connect", true)

    private fun connectHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
            } else {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(25L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun renderState(state: VpnState) {
        val presentation = DashboardStatePresenter.forState(state)
        if (state == VpnState.Started && lastHapticState != null && lastHapticState != VpnState.Started && connectHapticEnabled()) connectHaptic()
        lastHapticState = state
        if (!presentation.showTransferSpeeds) resetTransferSpeeds()
        val accent = accentFor(presentation.tone)
        connectionGlobe.setVpnState(state)
        if (::connectionBlob.isInitialized) {
            connectionBlob.setVpnState(state)
            if (state != VpnState.Started) connectionBlob.setCenterText("C")
        }
        if (::heroStateText.isInitialized) heroStateText.text = getString(presentation.titleRes)
        if (::homeLocationPill.isInitialized) homeLocationPill.visibility = if (state == VpnState.Started) View.VISIBLE else View.GONE
        if (::heroPingAction.isInitialized) heroPingAction.visibility = if (state == VpnState.Started) View.VISIBLE else View.GONE
        applyHomeBackdrop(state)
        connectActionButton.setText(buttonModel.labelRes())
        connectActionButton.isEnabled = buttonModel.isEnabled()
        connectActionButton.contentDescription = getString(buttonModel.labelRes())
        applyConnectPillStyle(state)
        // Update status dot color based on state
        (statusDot.background as? GradientDrawable)?.setColor(
            when (state) {
                VpnState.Started -> TEAL
                VpnState.Starting, VpnState.Stopping -> AMBER
                is VpnState.Error, VpnState.DailyLimitReached -> ERROR
                VpnState.Stopped -> palette.neutral
            }
        )
        statusText.text = getString(presentation.titleRes)
        statusText.setTextColor(TEXT_PRIMARY)
        timerText.setTextColor(if (state == VpnState.Started) TEXT_PRIMARY else TEXT_SECONDARY)
        val live = liveGeo?.takeIf { state == VpnState.Started }
        connectionCountryText.text = when {
            state == VpnState.Started && live != null ->
                listOfNotNull(live.city?.takeIf { it.isNotBlank() }, live.countryName).joinToString(", ")
            state == VpnState.Started && connectionCountryFlag.isNotBlank() ->
                ConnectionLocationPolicy.countryFromText(connectionCountryFlag)?.country
                    ?: getString(R.string.route_edge_fallback)
            state == VpnState.Started -> getString(R.string.route_edge_fallback)
            state == VpnState.Starting -> getString(R.string.route_selecting)
            state == VpnState.Stopping -> getString(R.string.route_closing)
            state is VpnState.Error -> getString(R.string.route_unavailable)
            state == VpnState.DailyLimitReached -> getString(R.string.state_daily_limit)
            else -> getString(R.string.route_automatic)
        }
        connectionCountryText.setTextColor(TEXT_PRIMARY)
        if (::homeFlagBadge.isInitialized) {
            homeFlagBadge.setCountryCode(
                when {
                    state == VpnState.Started && live != null -> live.countryCode
                    state == VpnState.Started && connectionCountryFlag.isNotBlank() ->
                        ConnectionLocationPolicy.countryFromText(connectionCountryFlag)?.code
                    else -> null
                },
            )
        }
        val pendingCountry = pendingGlobeCountry()
        val liveLabel = live?.let { info ->
            buildString {
                append(info.countryName)
                info.colo?.let { colo -> append(" · ").append(colo) }
            }
        }
        connectionGlobe.setDestination(
            when {
                state == VpnState.Started && live != null -> live.flag
                state == VpnState.Started -> connectionCountryFlag
                live != null && liveGeoTunneled == false -> live.flag
                else -> pendingCountry?.flag ?: "🌐"
            },
            when {
                state == VpnState.Started && liveLabel != null -> liveLabel
                state == VpnState.Started -> connectionCountryText.text.toString()
                live != null && liveGeoTunneled == false ->
                    getString(R.string.route_direct_state) + " · " + live.countryName
                else -> pendingCountry?.label.orEmpty()
            },
            when {
                state == VpnState.Started && live != null -> live.ip
                state == VpnState.Started -> debugFrontingIp
                live != null && liveGeoTunneled == false -> live.ip
                else -> ""
            },
        )
        vpnCurrentlyStarted = state == VpnState.Started
        if (vpnCurrentlyStarted) {
            // A stale direct-state label must never survive the connect.
            beginLiveGeoCheck(force = !liveGeoTunneled)
        } else {
            if (liveGeo != null && liveGeoTunneled) beginLiveGeoCheck(force = true)
            else if (liveGeo == null && !liveGeoDirectAttempted) {
                // Fresh app open while disconnected: show the REAL IP right away.
                liveGeoDirectAttempted = true
                beginLiveGeoCheck(force = true)
            }
            renderRealIpLine(live, false)
        }
        publicServerNotice.visibility = if (
            state == VpnState.Started &&
            activeRuntimeSubscriptionId == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID
        ) View.VISIBLE else View.GONE
        renderConnectionDetails(state)
        if (state != VpnState.Started) livePingMs = null
        renderPingValue()
        if (::uptimeValueText.isInitialized && ::timerText.isInitialized) {
            uptimeValueText.text = timerText.text
        }
        if (::homeUsageCard.isInitialized) renderHomeUsageCard()

        refreshActionButton.isEnabled = state == VpnState.Started
        refreshActionButton.contentDescription = getString(R.string.action_reconnect)
        // Glass reconnect action with the Cat accent.
        refreshActionButton.backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 235))
        refreshActionButton.strokeWidth = 0
        refreshActionButton.elevation = 0f
        refreshActionButton.stateListAnimator = null
        refreshActionButton.rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 50))
        refreshActionButton.iconTint = ColorStateList.valueOf(TEXT_PRIMARY)
        val settingsEnabled = state != VpnState.Starting && state != VpnState.Stopping
        locationSelectorRow.isEnabled = settingsEnabled
        connectionSelectorRow.isEnabled = settingsEnabled
        homeChainAfterSelectorRow.isEnabled = settingsEnabled
        updateSplitTunnelControlsEnabled?.invoke(settingsEnabled)
        if (::tlsIntegrityCheckbox.isInitialized) tlsIntegrityCheckbox.isEnabled = settingsEnabled
        renderConnectionOptionsControls(settingsEnabled)
        renderLanSharingControls(settingsEnabled)
        renderChainSettingsPage?.invoke()
        if (::routingModeRow.isInitialized) {
            routingModeRow.isEnabled = settingsEnabled
            routingModeRow.alpha = if (settingsEnabled) 1f else 0.45f
        }
        if (::dnsPrivacyRow.isInitialized) {
            dnsPrivacyRow.isEnabled = settingsEnabled
            dnsPrivacyRow.alpha = if (settingsEnabled) 1f else 0.45f
        }
        if (::dnsPrivacyEndpointLayout.isInitialized) {
            dnsPrivacyEndpointLayout.isEnabled = settingsEnabled
        }
        if (::dnsPrivacyEndpointInput.isInitialized) {
            dnsPrivacyEndpointInput.isEnabled = settingsEnabled
        }
        if (::frontingIpInputLayout.isInitialized) {
            frontingIpInputLayout.isEnabled = settingsEnabled
        }
        if (::frontingIpInput.isInitialized) {
            frontingIpInput.isEnabled = settingsEnabled
        }
        if (::frontingIpChipGroup.isInitialized) {
            frontingIpChipGroup.isEnabled = settingsEnabled
            for (index in 0 until frontingIpChipGroup.childCount) {
                frontingIpChipGroup.getChildAt(index).isEnabled = settingsEnabled
            }
        }
        renderLocationSelection()
        if (
            state == VpnState.Stopped ||
            state == VpnState.DailyLimitReached ||
            state is VpnState.Error ||
            (state == VpnState.Started && sessionStartedAtElapsedMs <= 0L)
        ) {
            setTimerText(0L)
        }
    }

    private fun startTimerUpdates() {
        mainHandler.removeCallbacks(timerRunnable)
        resetTransferSpeeds()
        mainHandler.post(timerRunnable)
    }

    private fun setTimerText(elapsedMs: Long) {
        val isActive = buttonModel.state == VpnState.Started && sessionStartedAtElapsedMs > 0L
        timerText.setTextColor(if (isActive) TEXT_PRIMARY else TEXT_SECONDARY)
        timerText.text = formatDuration(elapsedMs)
        if (::connectionBlob.isInitialized && buttonModel.state == VpnState.Started) {
            connectionBlob.setCenterText(formatDuration(elapsedMs).removePrefix("00:"))
        }
    }

    private fun toggleAppTheme() {
        val currentTheme = appThemePreferenceStore.read()
        val newTheme = if (currentTheme == AppThemeMode.Dark) AppThemeMode.Light else AppThemeMode.Dark
        appThemePreferenceStore.save(newTheme)
        recreate()
    }

    private fun updateTransferSpeeds() {
        if (!DashboardStatePresenter.forState(buttonModel.state).showTransferSpeeds) {
            resetTransferSpeeds()
            return
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val rxBytes = TrafficStats.getUidRxBytes(Process.myUid())
        val txBytes = TrafficStats.getUidTxBytes(Process.myUid())
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        if (rxBytes == unsupported || txBytes == unsupported) {
            resetTransferSpeeds()
            return
        }

        if (lastTransferSampleElapsedMs > 0L && nowElapsedMs > lastTransferSampleElapsedMs) {
            val elapsedMs = nowElapsedMs - lastTransferSampleElapsedMs
            val rx = bytesPerSecond(rxBytes, lastTransferRxBytes, elapsedMs)
            val tx = bytesPerSecond(txBytes, lastTransferTxBytes, elapsedMs)
            downloadSpeedText.text = formatTransferSpeed(rx)
            uploadSpeedText.text = formatTransferSpeed(tx)
            renderTrafficBars(rx, tx)
        } else {
            if (sessionRxStartBytes < 0L) {
                sessionRxStartBytes = rxBytes
                sessionTxStartBytes = txBytes
            }
            downloadSpeedText.text = formatTransferSpeed(0L)
            uploadSpeedText.text = formatTransferSpeed(0L)
        }
        lastTransferRxBytes = rxBytes
        lastTransferTxBytes = txBytes
        lastTransferSampleElapsedMs = nowElapsedMs
        if (currentVpnStateIsStarted() && nowElapsedMs - lastQuietPingAtMs > 30_000L) {
            lastQuietPingAtMs = nowElapsedMs
            measureTunnelPingQuietly()
        }
        if (::downloadTotalText.isInitialized && sessionRxStartBytes >= 0L) {
            downloadTotalText.text = SubscriptionUsagePolicy.formatBytes((rxBytes - sessionRxStartBytes).coerceAtLeast(0L))
            uploadTotalText.text = SubscriptionUsagePolicy.formatBytes((txBytes - sessionTxStartBytes).coerceAtLeast(0L))
        }
    }

    /** Bar length grows logarithmically with the rate (ZedSecure rateFraction): 1 KB/s → ~0, 10 MB/s → full. */
    private fun renderTrafficBars(rxPerSecond: Long, txPerSecond: Long) {
        if (!::downloadBarFill.isInitialized) return
        val live = currentVpnStateIsStarted()
        downloadBarFill.setRate(rxPerSecond, live)
        uploadBarFill.setRate(txPerSecond, live)
    }

    /**
     * Zed StageColumn: the hero stage takes whatever height is left so the Home never scrolls;
     * it only shrinks (down to 210 dp, blob down to 180 dp) when the viewport is short.
     */
    private fun fitHomeStage(scrollView: ScrollView) {
        val hero = homeHeroFrame ?: return
        if (homeStageFitting || scrollView.height == 0) return
        val wrapper = scrollView.getChildAt(0) as? ViewGroup ?: return
        val content = wrapper.getChildAt(0) ?: return // dashboardContent (wrap_content; the wrapper is fillViewport)
        if (content.height == 0) return
        val viewport = scrollView.height - scrollView.paddingTop - scrollView.paddingBottom
        val current = hero.height.takeIf { it > 0 } ?: hero.layoutParams.height
        val fixed = content.height - current + wrapper.paddingTop + wrapper.paddingBottom // everything except the stage (incl. dock padding)
        val full = dp(300)
        val minStage = dp(210)
        val target = (viewport - fixed).coerceIn(minStage, full)
        if (target == current) return
        homeStageFitting = true
        val blob = (dp(250) * target / full.toFloat()).toInt().coerceIn(dp(180), dp(250))
        // Zed HomeFabCluster: anchored at the blob's lower-right, running down past the state label.
        heroClusterParams?.topMargin = target / 2 + blob / 2 - dp(32)
        connectionBlob.layoutParams = (connectionBlob.layoutParams as FrameLayout.LayoutParams).apply { width = blob; height = blob }
        hero.layoutParams = hero.layoutParams.apply { height = target }
        hero.post {
            homeStageFitting = false
            fitHomeStage(scrollView)
        }
    }

    /** Zed's selected-row tint: accent blended into the card surface (primaryContainer feel). */
    private fun selectedRowColor(): Int = androidx.core.graphics.ColorUtils.blendARGB(palette.surfaceElevated1, TEAL, if (palette.isDark) 0.28f else 0.22f)

    /** Page backdrop: ZedSecure paints the whole home violet → cyan while connected, flat otherwise. */
    private fun applyHomeBackdrop(state: VpnState) {
        val drawable = liveBackdrop ?: ZedLiveBackdropDrawable(resources.displayMetrics.density).also {
            liveBackdrop = it
            it.setThemeColors(palette.teal, palette.secondary, AppAccentPreferenceStore(this).read() == AppAccent.Lavender)
        }
        drawable.setVpnState(state)
        syncShellBackdrop()
    }

    /** Zed paints the Home gradient edge to edge — behind the status bar and under the floating dock. */
    private fun syncShellBackdrop() {
        val root = appRootView
        val drawable = liveBackdrop
        if (root == null || drawable == null) { homeBackdrop?.background = drawable; return }
        val home = ::vpnTabContent.isInitialized && vpnTabContent.visibility == View.VISIBLE
        homeBackdrop?.background = null
        if (home) {
            if (root.background !== drawable) root.background = drawable
            drawable.start()
        } else if (root.background === drawable) {
            root.setBackgroundColor(BACKGROUND)
        }
    }

    private fun resetTransferSpeeds() {
        lastTransferRxBytes = TrafficStats.UNSUPPORTED.toLong()
        lastTransferTxBytes = TrafficStats.UNSUPPORTED.toLong()
        lastTransferSampleElapsedMs = 0L
        sessionRxStartBytes = -1L
        sessionTxStartBytes = -1L
        if (::downloadBarFill.isInitialized) renderTrafficBars(0L, 0L)
        if (::downloadTotalText.isInitialized) {
            downloadTotalText.text = SubscriptionUsagePolicy.formatBytes(0L)
            uploadTotalText.text = SubscriptionUsagePolicy.formatBytes(0L)
        }
        if (::downloadSpeedText.isInitialized) {
            downloadSpeedText.text = formatTransferSpeed(0L)
            uploadSpeedText.text = formatTransferSpeed(0L)
        }
    }

    private fun bytesPerSecond(currentBytes: Long, previousBytes: Long, elapsedMs: Long): Long {
        if (elapsedMs <= 0L || previousBytes == TrafficStats.UNSUPPORTED.toLong()) return 0L
        return ((currentBytes - previousBytes).coerceAtLeast(0L).toDouble() * 1_000.0 / elapsedMs).toLong()
    }

    private fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = maxOf(0L, elapsedMs) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun copyDiagnosticsToClipboard() {
        val diagnostics = DiagnosticLogger.read(this).ifBlank { getString(R.string.diagnostics_empty) }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("Cat Client diagnostics", diagnostics).apply {
            // Keeps the clipboard preview toast from rendering the log on screen (Android 13+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        DiagnosticLogger.info(this, "diagnostics.copy", "chars=${diagnostics.length}")
    }

    private fun shareDiagnostics() {
        activityScope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) { DiagnosticLogger.reportFile(this@MainActivity) }
            }.getOrNull()
            if (file == null) {
                Toast.makeText(this@MainActivity, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = runCatching {
                FileProvider.getUriForFile(this@MainActivity, "${packageName}.updates", file)
            }.getOrNull()
            if (uri == null) {
                Toast.makeText(this@MainActivity, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.app_name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.diagnostics_share)))
                Toast.makeText(this@MainActivity, R.string.diagnostics_shared, Toast.LENGTH_SHORT).show()
                DiagnosticLogger.info(this@MainActivity, "diagnostics.share")
            } catch (error: Exception) {
                DiagnosticLogger.warn(this@MainActivity, "diagnostics.share.failed", error = error)
                Toast.makeText(this@MainActivity, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkForUpdates() {
        appUpdateUi.check(manual = false)
    }

    private fun openFooterLink() = openExternalUrl(getString(R.string.footer_url))

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
            DiagnosticLogger.warn(this, "external.open.failed", "url=$url", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        window.decorView.textDirection = View.TEXT_DIRECTION_LOCALE
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        var flags = window.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = if (palette.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = if (palette.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun glassSurfaceDrawable(radiusDp: Int, highlighted: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            // ZedSecure tonal cards: surface container, no outline; highlighted = one step brighter + accent edge.
            setColor(if (highlighted) palette.surfaceElevated2 else palette.surfaceElevated1)
            if (highlighted) setStroke(dp(1), withAlpha(TEAL, 170))
        }
    }

    private fun AlertDialog.showCatClientDialog(
        positiveColor: Int = TEAL,
        onShow: AlertDialog.() -> Unit = {},
    ) {
        setOnShowListener {
            styleCatClientDialog(positiveColor)
            onShow(this)
        }
        show()
    }

    private fun AlertDialog.styleCatClientDialog(positiveColor: Int) {
        window?.setBackgroundDrawable(glassSurfaceDrawable(radiusDp = 24))
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(TEXT_PRIMARY)
        val optionColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(TEAL, TEXT_PRIMARY),
        )
        listView?.let { options ->
            repeat(options.childCount) { index ->
                (options.getChildAt(index) as? TextView)?.apply {
                    setTextColor(optionColors)
                    typeface = CatClientBodyTypeface
                }
            }
        }
        getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isAllCaps = false
            typeface = CatClientBodyBoldTypeface
            setTextColor(positiveColor)
        }
        listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { buttonId ->
            getButton(buttonId)?.apply {
                isAllCaps = false
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_SECONDARY)
            }
        }
    }

    private fun catClientPopupMenu(anchor: View): PopupMenu =
        PopupMenu(ContextThemeWrapper(this, R.style.CatClientPopupTheme), anchor)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun View.setSelectableBackground() {
        val value = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            setBackgroundResource(value.resourceId)
        }
    }

    @Suppress("DEPRECATION")
    private fun View.announceAccessibility(message: String) = announceForAccessibility(message)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun accentFor(tone: DashboardTone): Int = when (tone) {
        DashboardTone.Connected -> TEAL
        DashboardTone.Progress -> AMBER
        DashboardTone.Error -> ERROR
        DashboardTone.Neutral -> palette.neutral
    }

    private companion object {
        // Dock cell indices (visual order) and content screen indices (fixed, used all over).
        const val DOCK_HOME = 0
        const val DOCK_SERVERS = 1
        const val DOCK_CLOUD = 2
        const val DOCK_SETTINGS = 3
        const val SCREEN_SERVERS = 0
        const val SCREEN_HOME = 1
        const val SCREEN_SETTINGS = 2
        const val SCREEN_CLOUD = 3
        const val SCREEN_SCANNER = 4
        const val REQUEST_VPN_PERMISSION = 10
        const val REQUEST_NOTIFICATION_PERMISSION = 11
        const val TIMER_TICK_MS = 1_000L
        const val KEYBOARD_SCROLL_DELAY_MS = 250L
        const val CONNECTION_TESTING_PAGE_ANIMATION_MS = 300L
        const val STATE_CONNECTION_TESTING_PAGE = "connection_testing_page"
        const val STATE_CHAIN_PICKER_SLOT = "chain_picker_slot"
        const val STATE_CHAIN_PICKER_SUBSCRIPTION = "chain_picker_subscription"
        const val STATE_CONNECT_FLOW_PENDING = "connect_flow_pending"
        const val STATE_CONNECT_FLOW_ACTION = "connect_flow_action"
        const val SUBSCRIPTION_ITEM_ID_BASE = 200

        /* IP scanner */
        const val SCANNER_PREFERENCES = "cat_client_scanner"
        const val SCANNER_RANGES_KEY = "ranges"
        const val SCANNER_PER_RANGE = IpScanner.DEFAULT_PER_RANGE
        const val SCANNER_SNI_KEY = "scanner_sni"
        const val SCANNER_PORT_KEY = "scanner_port"
        val SCANNER_PORTS = setOf(443, 2053, 2083, 8443)
        const val DEFAULT_SCANNER_SNI = "skk.moe"
        const val SCANNER_BUILD_LIMIT = 12
        val SCANNER_BUILD_PORTS = listOf(80, 443, 2053)
        const val SCANNER_VISIBLE_RESULTS = 24
        const val SCANNER_LIVE_REFRESH_EVERY = 5
        const val SCANNER_CONCURRENCY = 24
        const val SCANNER_CONNECT_TIMEOUT_MS = 1500
        const val SCANNER_TLS_TIMEOUT_MS = 2500

        /* Free configs */
        const val FREE_PREVIEW_ROWS = 12
        const val FREE_QR_BATCH = 12
        const val FREE_SUBSCRIPTION_LIMIT = 200
        const val SCANNER_GOOD_MS = 300L
        const val SCANNER_FAIR_MS = 700L
    }
}
