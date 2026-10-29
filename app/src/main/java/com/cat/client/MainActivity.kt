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
    internal val palette: CatClientPalette by lazy { CatClientDesignTokens.forContext(this) }
    internal val buttonModel = ConnectButtonModel()
    internal val mainHandler = Handler(Looper.getMainLooper())
    private val activityCoroutineExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        runCatching {
            DiagnosticLogger.warn(this@MainActivity, "activity.coroutine.uncaught", error = throwable)
        }
    }
    internal val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + activityCoroutineExceptionHandler)
    internal val appTools: AppToolsController by lazy {
        AppToolsController(
            activity = this,
            scope = activityScope,
            isFarsi = { appLanguagePreferenceStore.read() == AppLanguage.Persian },
            subscriptions = { userSubscriptionManager },
            addSubscription = { content, name ->
                openScreen(SCREEN_SERVERS)
                showAddSubscriptionDialog(content, name)
            },
            sendRangesToScanner = { ranges ->
                saveScannerRanges(ranges)
                if (::scannerSubnetsInput.isInitialized) scannerSubnetsInput.setText(ranges)
                openScreen(SCREEN_SCANNER)
            },
            afterSubscriptionsChanged = { runCatching { renderSubscriptions() } },
            showDialog = { dialog -> dialog.showCatClientDialog() },
        )
    }
    internal val vercelRelay: VercelRelayController by lazy {
        VercelRelayController(
            activity = this,
            scope = activityScope,
            isFarsi = { appLanguagePreferenceStore.read() == AppLanguage.Persian },
            subscriptions = { userSubscriptionManager },
            addSubscription = { content, name ->
                openScreen(SCREEN_SERVERS)
                showAddSubscriptionDialog(content, name)
            },
            showQr = { links, title -> showConfigQrCodes(links, title) },
            showDialog = { dialog -> dialog.showCatClientDialog() },
        )
    }
    // 6.69.45 the Cat panel on KataBump / Lucity / Shiprr — one card per host on the Cloud tab.
    private val nodeHosts: NodeHostController by lazy {
        NodeHostController(
            activity = this,
            scope = activityScope,
            isFarsi = { appLanguagePreferenceStore.read() == AppLanguage.Persian },
            addSubscription = { content, name ->
                openScreen(SCREEN_SERVERS)
                showAddSubscriptionDialog(content, name)
            },
            showDialog = { dialog -> dialog.showCatClientDialog() },
            requestNotifications = {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NODE_RENEW_NOTIFICATION)
                }
            },
        )
    }
    internal var aetherOperationJob: Job? = null
    internal var updateSettingsBadge: TextView? = null
    internal val appUpdateUi by lazy {
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
    internal lateinit var appLanguagePreferenceStore: AppLanguagePreferenceStore
    internal lateinit var appThemePreferenceStore: AppThemePreferenceStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    internal lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    internal lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    internal lateinit var routingModePreferenceStore: RoutingModePreferenceStore
    internal lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    /** Orbot / Tor engine switch + the multi-path watchdog that may take it over. */
    internal lateinit var orbotEnginePreferenceStore: OrbotEnginePreferenceStore
    internal lateinit var pathWatchdogStore: PathWatchdogStore
    internal lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    internal lateinit var dpiBypassPreferenceStore: DpiBypassPreferenceStore
    private lateinit var panelTuneStore: PanelTuneStore
    internal lateinit var connectionOptionsPreferenceStore: MihomoConnectionOptionsPreferenceStore
    internal lateinit var connectionModePreferenceStore: ConnectionModePreferenceStore
    private lateinit var lanSharingPreferenceStore: LanSharingPreferenceStore
    internal lateinit var connectionSelectionPreferenceStore: ConnectionSelectionPreferenceStore
    internal lateinit var connectionTestSettingsPreferenceStore: ConnectionTestSettingsPreferenceStore
    internal lateinit var connectionChainPreferenceStore: ConnectionChainPreferenceStore
    internal lateinit var aetherPreferenceStore: AetherPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    internal lateinit var userSubscriptionManager: UserSubscriptionManager
    private var privacyPolicyDialog: AlertDialog? = null
    internal var connectionDelayTestListener: ((Intent) -> Unit)? = null
    private lateinit var appShellView: View
    private lateinit var appTabs: TabLayout
    internal lateinit var connectionTestingPageHost: ConnectionTestingPage
    internal var connectionTestingPageVisible: Boolean = false
    internal var activeChainPickerSlot: ConnectionChainSlot? = null
    internal var activeChainPickerSubscriptionId: String? = null
    internal var renderChainSettingsPage: (() -> Unit)? = null
    internal var connectionChainCompatibilityIssue: ConnectionChainCompatibilityIssue? = null
    internal var connectionChainCompatibilityMessage: String? = null
    internal var pendingConnectionChainAccessibilityAnnouncement: String? = null
    internal var openConnectionChainSettingsPage: (() -> Unit)? = null
    internal var advancedSettingsBackAction: (() -> Unit)? = null
    private var currentActiveScreen: Int = SCREEN_HOME
    internal var activeSettingsCategoryTitleRes: Int = 0
    internal val settingsCategoryOpeners: MutableMap<Int, () -> Unit> = mutableMapOf()
    internal var refreshAetherEngineSummary: (() -> Unit)? = null
    private var sessionStartedAtElapsedMs: Long = 0L
    private var connectFlowPending: Boolean = false
    private var connectFlowAction: String = Actions.CONNECT
    private var disconnectAnalyticsPending: Boolean = false
    private var locationOptions: List<LocationSelectorOption> = emptyList()
    internal var connectionProfiles: List<ConnectionProfile> = emptyList()
    internal var connectionDelayRecords: Map<String, ConnectionDelayRecord> = emptyMap()
    internal var activeRuntimeSubscriptionId: String = ""
    internal var activeConnectionTag: String = ""
    internal var activeConnectionFingerprint: String = ""
    internal var activeChainHopCount: Int = 0
    internal var liveSelectorReady: Boolean = false
    internal var liveSelectableConnectionFingerprints: Set<String> = emptySet()

    internal lateinit var connectionGlobe: ConnectionGlobeView
    internal lateinit var connectionRealIpText: TextView
    @Volatile internal var liveGeoTunneled: Boolean = false
    private var liveGeoDirectAttempted: Boolean = false
    internal lateinit var connectionV6Text: TextView
    private var tunnelPingRunning: Boolean = false
    private var pingAllRunning: Boolean = false
    internal lateinit var connectActionButton: MaterialButton
    internal lateinit var statusDot: View
    internal lateinit var statusText: TextView
    internal lateinit var publicServerNotice: TextView
    internal lateinit var connectionDetailsText: TextView
    private var automaticConnectionProgress: String = ""
    internal lateinit var timerText: TextView
    internal lateinit var downloadSpeedText: TextView
    internal lateinit var uploadSpeedText: TextView
    private lateinit var downloadArrowIcon: AnimatedArrowIcon
    private lateinit var uploadArrowIcon: AnimatedArrowIcon
    internal lateinit var connectionCountryText: TextView
    internal lateinit var locationSelectorRow: DashboardDataRowView
    internal lateinit var connectionSelectorRow: DashboardDataRowView
    internal lateinit var homeChainAfterSelectorRow: DashboardDataRowView
    internal lateinit var homeChainSelectorRows: LinearLayout
    internal lateinit var homeSubscriptionSelectorRow: DashboardDataRowView
    internal lateinit var settingsSubscriptionSelectorRow: DashboardDataRowView
    internal var updateSplitTunnelControlsEnabled: ((Boolean) -> Unit)? = null
    internal lateinit var connectionModeGroup: MaterialButtonToggleGroup
    internal lateinit var vpnModeButton: MaterialButton
    internal lateinit var proxyModeButton: MaterialButton
    internal lateinit var dashboardLocalEndpointText: TextView
    internal lateinit var dashboardChainText: TextView
    internal lateinit var dashboardConnectionMetadataSection: View
    internal lateinit var tlsIntegrityCheckbox: MaterialSwitch
    internal lateinit var tlsFragmentCheckbox: MaterialSwitch
    internal lateinit var adBlockCheckbox: MaterialSwitch
    internal lateinit var alwaysOnStatusText: TextView
    internal lateinit var amneziaNoiseCheckbox: MaterialSwitch
    internal lateinit var amneziaNoiseFields: LinearLayout
    internal lateinit var amneziaNoiseCountInput: EditText
    internal lateinit var amneziaNoiseMinSizeInput: EditText
    internal lateinit var amneziaNoiseMaxSizeInput: EditText
    internal lateinit var amneziaNoiseTtlInput: EditText
    internal lateinit var amneziaNoiseVersionInput: EditText
    internal lateinit var amneziaNoiseIpStackInput: EditText
    internal lateinit var amneziaNoiseCongestionInput: EditText
    internal lateinit var amneziaNoiseHeaderProtectionKeyInput: EditText
    internal lateinit var amneziaNoiseContentPaddingInput: EditText
    internal lateinit var amneziaNoiseRekeyAfterInput: EditText
    internal lateinit var amneziaNoiseRekeyTimeoutInput: EditText
    internal lateinit var amneziaNoiseRejectAfterInput: EditText
    internal lateinit var amneziaNoiseKeepaliveTimeoutInput: EditText
    internal lateinit var amneziaNoiseMaxHandshakeAttemptsInput: EditText
    internal lateinit var amneziaNoiseRandomTrailersInput: EditText
    internal lateinit var amneziaNoiseDisableCookiesInput: EditText
    internal lateinit var amneziaNoiseApplyButton: MaterialButton
    internal lateinit var amneziaNoiseErrorText: TextView
    internal lateinit var lanSharingCheckbox: MaterialSwitch
    internal lateinit var lanSharingPasswordCheckbox: MaterialSwitch
    internal lateinit var lanSharingDetailsText: TextView
    internal lateinit var lanSharingRegenerateButton: MaterialButton
    internal lateinit var routingModeRow: LinearLayout
    internal lateinit var routingModeValueText: TextView
    internal lateinit var routingModeDetailText: TextView
    internal lateinit var dnsPrivacyRow: LinearLayout
    /** Orbot / Tor engine: the switch, its live verdict line and the probe button. */
    internal var orbotEngineCheckbox: MaterialSwitch? = null
    internal var orbotStatusText: TextView? = null
    internal var orbotProbeButton: MaterialButton? = null
    internal var lastOrbotProbe: OrbotProbeResult? = null
    internal var watchdogToggle: MaterialSwitch? = null
    internal var watchdogStatusText: TextView? = null
    internal lateinit var dnsPrivacyValueText: TextView
    internal lateinit var dnsPrivacyDetailText: TextView
    internal lateinit var dnsPrivacyEndpointInput: EditText
    internal lateinit var dnsPrivacyEndpointLayout: TextInputLayout
    internal lateinit var dnsPrivacyErrorText: TextView
    internal lateinit var frontingIpChipGroup: ChipGroup
    internal lateinit var frontingIpInput: EditText
    internal lateinit var frontingIpInputLayout: TextInputLayout
    internal lateinit var frontingIpErrorText: TextView
    internal lateinit var refreshActionButton: MaterialButton
    internal lateinit var connectionBlob: ZedBlobView
    internal lateinit var activeConfigTitle: TextView
    internal lateinit var homeLocationPill: View
    internal lateinit var heroPingAction: View
    internal var homeHeroFrame: FrameLayout? = null
    internal var heroClusterParams: FrameLayout.LayoutParams? = null
    private var homeStageFitting = false
    @Volatile internal var livePingMs: Long? = null
    private var lastQuietPingAtMs: Long = 0L
    private var liveBackdrop: ZedLiveBackdropDrawable? = null
    internal lateinit var homeFlagBadge: FlagBadgeView
    internal var homeExtrasSection: View? = null
    internal lateinit var serversCountText: TextView
    internal lateinit var heroStateText: TextView
    private var lastShownConnectionFault: String? = null
    /** ZedSecure 3.1.6 App design (Pixel / Portal / Old) and Material 3 roles derived from the palette. */
    internal val appDesign: AppDesign by lazy { AppDesignPreferenceStore(this).design() }
    internal val zedScheme: ZedScheme by lazy { ZedScheme.from(palette) }
    internal var portalView: ZedPortalView? = null
    internal var pixelCore: ZedPortalView? = null
    internal var pixelCard: LinearLayout? = null
    internal var pixelCardColor: Int = 0
    internal var homeHintText: TextView? = null
    internal var flowBadges: List<Pair<ZedShapeIconView, Boolean>> = emptyList()
    internal lateinit var downloadBarFill: ZedWavyProgressView
    internal lateinit var uploadBarFill: ZedWavyProgressView
    internal lateinit var downloadTotalText: TextView
    internal lateinit var uploadTotalText: TextView
    internal var homeBackdrop: View? = null
    private var appRootView: FrameLayout? = null
    private var sessionRxStartBytes = -1L
    private var sessionTxStartBytes = -1L
    internal lateinit var subscriptionsList: LinearLayout
    private lateinit var vpnTabContent: View
    private lateinit var subscriptionsTabContent: View
    private lateinit var advancedTabContent: View
    private lateinit var cloudTabContent: View
    private lateinit var aetherTabContent: View
    internal var cloudDeploymentHistoryHost: LinearLayout? = null

    /* IP scanner tab (clean Cloudflare IPs with SNI + fronting) */
    private lateinit var scannerTabContent: View
    internal lateinit var scannerSniInput: TextInputEditText
    internal lateinit var scannerPortGroup: ChipGroup
    internal lateinit var scannerSubnetsInput: TextInputEditText
    internal lateinit var scannerStartButton: MaterialButton
    internal lateinit var scannerStopButton: MaterialButton
    internal lateinit var scannerProgressBar: ProgressBar
    internal lateinit var scannerStatusText: TextView
    internal lateinit var scannerResultsList: LinearLayout
    internal lateinit var scannerApplyButton: MaterialButton
    internal lateinit var scannerBuildButton: MaterialButton
    internal var scannerResults: List<IpScanner.ScanResult> = emptyList()
    internal var scannerSpeedTestButton: MaterialButton? = null
    private var scannerSpeedTestRunning = false
    internal val scannerLiveResults = mutableListOf<IpScanner.ScanResult>()
    internal var scannerPort: Int = 443
    internal var scannerRunning: Boolean = false
    internal var scannerJob: Job? = null
    private val dockTabs = mutableListOf<DockTab>()
    internal lateinit var pingValueText: TextView
    internal lateinit var uptimeValueText: TextView

    /* Free configs (community sources, fetched + ping-tested in-app) */
    internal lateinit var freeConfigsStatus: TextView
    internal lateinit var freeConfigsProgress: ProgressBar
    internal lateinit var homeUsageCard: LinearLayout
    /** beta220 real internet test card (MainActivityRealInternet.kt). */
    internal var realInternetViews: RealInternetCardViews? = null
    internal var lastRealInternetResult: RealInternetResult? = null
    private lateinit var homeUsageBar: UsageBarView
    private lateinit var homeUsageTitle: TextView
    private lateinit var homeUsageValue: TextView
    private lateinit var homeUsageLegend: TextView
    private lateinit var homeUsageHint: TextView
    internal lateinit var freeConfigsList: LinearLayout
    internal lateinit var freeConfigsFetchButton: MaterialButton
    internal lateinit var freeConfigsTestButton: MaterialButton
    internal lateinit var freeConfigsImportButton: MaterialButton
    internal var freeConfigEntries: List<FreeConfigs.FreeEntry> = emptyList()
    private var freeConfigsFetching = false
    private var freeConfigsJob: Job? = null
    private var connectionCountryFlag: String = ""
    private var debugFrontingIp: String = ""
    internal var liveGeo: IpGeolocation.Info? = null
    internal var liveGeoAtMs: Long = 0L
    private var liveGeoJob: Job? = null
    private var vpnCurrentlyStarted = false
    private var connectionDetails: String = ""
    private var alwaysOnMode: Boolean = false
    private var lockdownMode: Boolean = false
    internal var frontingIps: List<String> = emptyList()
    internal var frontingIpInputUpdating: Boolean = false
    internal var dnsPrivacyInputUpdating: Boolean = false
    private var lastTransferRxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferTxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferSampleElapsedMs: Long = 0L

    internal val BACKGROUND: Int get() = palette.background
    internal val SURFACE: Int get() = palette.surface
    internal val OUTLINE: Int get() = palette.outline
    internal val TEXT_PRIMARY: Int get() = palette.textPrimary
    internal val TEXT_SECONDARY: Int get() = palette.textSecondary
    private val TIMER_MUTED: Int get() = palette.textSecondary
    internal val TEAL: Int get() = palette.teal
    internal val AMBER: Int get() = palette.amber
    internal val ERROR: Int get() = palette.red

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
        val wrapped = AppLocale.wrap(AppTheme.wrap(newBase))
        super.attachBaseContext(wrapped)
        runCatching { applyOverrideConfiguration(android.content.res.Configuration(wrapped.resources.configuration)) }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Actions.STATE_CHANGED -> handleStateChanged(context, intent)
                Actions.CONNECTION_DELAY_TEST_CHANGED -> connectionDelayTestListener?.invoke(intent)
                Actions.CONNECTION_SPEED_TEST_CHANGED -> connectionDelayTestListener?.invoke(intent)
                Actions.AUTOMATIC_CONNECTION_PROGRESS -> handleAutomaticConnectionProgress(intent)
                Actions.REAL_INTERNET_CHECK -> handleRealInternetBroadcast(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { theme.applyStyle(AppAccentPreferenceStore(this).read().overlayStyleRes, true) }
            .onFailure { DiagnosticLogger.warn(this, "activity.themeOverlay.failed", error = it) }
        super.onCreate(savedInstanceState)
        appLanguagePreferenceStore = AppLanguagePreferenceStore(this)
        appThemePreferenceStore = AppThemePreferenceStore(this)
        if (savedInstanceState == null) {
            AnalyticsEvents.appOpened(this)
        }
        runCatching { PanelMonitor.sync(this) } // outward panel health monitor (30min)
        // Fields must not touch Context in their initializers (constructor phase);
        // persisted scanner preferences load here instead.
        scannerForceV6 = runCatching {
            getSharedPreferences("cat_client_theme", MODE_PRIVATE).getBoolean("scanner_force_v6", false)
        }.getOrDefault(false)
        privacyPolicyStore = PrivacyPolicyAcceptanceStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
        routingModePreferenceStore = RoutingModePreferenceStore(this)
        dnsPrivacyPreferenceStore = DnsPrivacyPreferenceStore(this)
        orbotEnginePreferenceStore = OrbotEnginePreferenceStore(this)
        pathWatchdogStore = PathWatchdogStore(this)
        tlsIntegrityPreferenceStore = TlsIntegrityPreferenceStore(this)
        dpiBypassPreferenceStore = DpiBypassPreferenceStore(this)
        panelTuneStore = PanelTuneStore(this)
        connectionOptionsPreferenceStore = MihomoConnectionOptionsPreferenceStore(this)
        connectionModePreferenceStore = ConnectionModePreferenceStore(this)
        lanSharingPreferenceStore = LanSharingPreferenceStore(this)
        connectionSelectionPreferenceStore = ConnectionSelectionPreferenceStore(this)
        connectionTestSettingsPreferenceStore = ConnectionTestSettingsPreferenceStore(this)
        connectionChainPreferenceStore = ConnectionChainPreferenceStore(this)
        aetherPreferenceStore = AetherPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        userSubscriptionManager = UserSubscriptionManager(this)
        connectFlowPending = savedInstanceState?.getBoolean(STATE_CONNECT_FLOW_PENDING) == true
        connectFlowAction = savedInstanceState?.getString(STATE_CONNECT_FLOW_ACTION) ?: Actions.CONNECT
        DiagnosticLogger.info(
            this,
            "activity.onCreate",
            "restored=${savedInstanceState != null} connectPending=$connectFlowPending action=$connectFlowAction",
        )
        runCatching { configureSystemBars() }
            .onFailure { DiagnosticLogger.warn(this, "activity.systemBars.failed", error = it) }
        // Build the shell defensively: a failure anywhere in the UI construction
        // must degrade to a readable message — never to a silent close. The rest
        // of startup is skipped in that case because the views do not exist.
        val shellError = runCatching { setContentView(buildAppShell()) }.exceptionOrNull()
        if (shellError != null) {
            DiagnosticLogger.warn(this, "activity.shell.failed", error = shellError)
            runCatching { setContentView(CrashWatch.fallbackView(this, shellError.toString())) }
            return
        }
        runCatching { renderState(VpnState.Stopped) }
            .onFailure { DiagnosticLogger.warn(this, "activity.render.failed", error = it) }
        runCatching { refreshLocationOptions() }
            .onFailure { DiagnosticLogger.warn(this, "activity.locationRefresh.failed", error = it) }
        runCatching { fetchPrivateSubscriptionOnLoad() }
            .onFailure { DiagnosticLogger.warn(this, "activity.subscriptionLoad.failed", error = it) }
        runCatching { prewarmWarpKeyInBackground() }
            .onFailure { DiagnosticLogger.warn(this, "activity.warpPrewarm.failed", error = it) }
        runCatching {
        if (savedInstanceState != null) {
            val restoredScreen = savedInstanceState.getInt(STATE_ACTIVE_SCREEN, SCREEN_HOME)
            val restoredCategoryRes = savedInstanceState.getInt(STATE_SETTINGS_CATEGORY_RES, 0)
            if (restoredScreen != SCREEN_HOME) {
                openScreen(restoredScreen)
            }
            if (restoredScreen == SCREEN_SETTINGS && restoredCategoryRes != 0) {
                settingsCategoryOpeners[restoredCategoryRes]?.invoke()
            }
        }
        if (savedInstanceState?.getBoolean(STATE_CONNECTION_TESTING_PAGE) == true) {
            val chainSlot = ConnectionChainSlot.fromWireName(
                savedInstanceState.getString(STATE_CHAIN_PICKER_SLOT),
            )
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
        }.onFailure { DiagnosticLogger.warn(this, "activity.restore.failed", error = it) }
        mainHandler.post {
            runCatching {
                val checkUpdatesAfterStartup = { if (savedInstanceState == null) checkForUpdates() }
                if (!showPrivacyPolicyIfNeeded(checkUpdatesAfterStartup)) checkUpdatesAfterStartup()
            }.onFailure { DiagnosticLogger.warn(this, "activity.startupTasks.failed", error = it) }
        }
        runCatching {
            SlipNetCliEngine.attach(this)
            appTools.onCreate(coldStart = savedInstanceState == null)
        }.onFailure { DiagnosticLogger.warn(this, "activity.appTools.failed", error = it) }
        // Only on a fresh start: a rotation must not re-open the same shared link / file.
        if (savedInstanceState == null) {
            runCatching { handleCatClientDeepLink(intent) }
                .onFailure { DiagnosticLogger.warn(this, "activity.deepLink.failed", error = it) }
        }
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
        // Config links (vless://, slipnet://, clash://install-config?url=…), shared text and
        // opened / shared files all land in the normal "add subscription" dialog.
        val schemeLower = intent?.data?.scheme?.lowercase(java.util.Locale.US)
        if (schemeLower != "catclient" && schemeLower != "aether") {
            IncomingConfigIntents.extract(this, intent)?.let { incoming ->
                mainHandler.post {
                    openScreen(SCREEN_SERVERS)
                    showAddSubscriptionDialog(incoming.content, incoming.name)
                }
                return
            }
        }
        val data = intent?.data ?: return
        if (data.scheme.equals("aether", ignoreCase = true)) {
            val parsed = AetherSelection.fromAetherUri(data.toString()) ?: return
            mainHandler.post { importAndConnectAetherSelection(parsed) }
            return
        }
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
                        frontingIps = FrontingIpPolicy.mergeNewestFirst(tokens, frontingIps)
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
        aetherTabContent = buildAetherScreen().apply { visibility = View.GONE }
        val content = FrameLayout(this).apply {
            addView(vpnTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(subscriptionsTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(advancedTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(cloudTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(scannerTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(aetherTabContent, FrameLayout.LayoutParams(-1, -1))
        }
        shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // New bottom navigation with pill-shaped container
        val tabs = TabLayout(this).apply {
            appTabsPending = this
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            minimumHeight = dp(64)
            background = glassDockBackground()
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
            // ZedSecure-style floating capsule: five cells, the active one expands into a
            // lime pill with icon + label, inactive cells show the icon only.
            // Visual order: Home, Servers, Aether (Anti-Censorship), Cloud (panel + IP scanner), Settings.
            layoutDirection = View.LAYOUT_DIRECTION_LTR // Zed keeps the dock order fixed in RTL too
            addDockTab(R.string.dock_home, R.drawable.ic_vpn_tab, selected = true)
            addDockTab(R.string.dock_servers, R.drawable.ic_subscriptions_tab, selected = false)
            addDockTab(R.string.tab_aether, R.drawable.ic_aether_tab, selected = false)
            addDockTab(R.string.tab_cloud, R.drawable.ic_cloud_tab, selected = false)
            addDockTab(R.string.tab_settings, R.drawable.ic_advanced_tab, selected = false)
            post { renderDockSelection(DOCK_HOME) }
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showAppTab(
                        when (tab.position) {
                            DOCK_HOME -> SCREEN_HOME
                            DOCK_SERVERS -> SCREEN_SERVERS
                            DOCK_AETHER -> SCREEN_AETHER
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

    internal fun setConnectionTestingPageVisible(visible: Boolean, animate: Boolean) {
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

    internal fun closeConnectionTestingPage(animate: Boolean = true) {
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
            visibility = View.GONE
        }
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = dockPillBackground(selected)
            addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        }
        icon.setColorFilter(if (selected) palette.onAccent else TEXT_SECONDARY)
        ZedIosMotion.press(pill)
        dockTabs += DockTab(pill, icon, label)
        val tab = appTabsPending.newTab()
        tab.customView = pill
        tab.view.setPadding(dp(2), 0, dp(2), 0)
        tab.view.minimumWidth = 0
        appTabsPending.addTab(tab, selected)
    }

    /** Telegram-iOS style frosted glass dock: translucent gradient fill + hairline. */
    private fun glassDockBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(32).toFloat()
        orientation = GradientDrawable.Orientation.TL_BR
        colors = intArrayOf(withAlpha(palette.surfaceElevated1, 214), withAlpha(palette.surface, 182))
        setStroke(dp(1), withAlpha(palette.outline, 150))
    }

    /** Dock opens (labels spring in) only while the tunnel is up; collapsed icon-only otherwise. */
    private var dockExpanded = false
    private var dockActiveIndex = 0

    private fun setDockExpanded(requested: Boolean) {
        if (!::appTabsPending.isInitialized) return
        // Pixel/Portal (ZedSecure 3.1.6): the active tab always carries its label, the bar never jumps.
        val expanded = requested || appDesign != AppDesign.Old
        if (dockExpanded == expanded) return
        dockExpanded = expanded
        appTabsPending.let { bar ->
            bar.animate().cancel()
            bar.minimumHeight = dp(if (expanded) 64 else 52) // collapsed bar is genuinely shorter
            bar.requestLayout()
            bar.scaleX = 0.97f
            bar.scaleY = 0.97f
            bar.animate().scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(OvershootInterpolator(1.7f))
                .start()
        }
        renderDockSelection(dockActiveIndex)
    }

    private fun dockPillBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        if (selected && appDesign != AppDesign.Old) {
            setColor(palette.teal)
        } else if (selected) {
            // iOS-flavored capsule: accent gradient instead of a flat fill
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(palette.tealGradientStart, palette.secondary)
        } else {
            setColor(Color.TRANSPARENT)
        }
    }

    /** Repaints every dock cell so only the active one is the filled lime pill with a label. */
    private fun renderDockSelection(activeIndex: Int) {
        dockActiveIndex = activeIndex
        dockTabs.forEachIndexed { index, tab ->
            val active = index == activeIndex
            tab.pill.background = dockPillBackground(active)
            tab.icon.setColorFilter(if (active) palette.onAccent else TEXT_SECONDARY)
            tab.label.setTextColor(if (active) palette.onAccent else TEXT_SECONDARY)
            val showLabel = active && dockExpanded
            if (showLabel) {
                tab.label.visibility = View.VISIBLE
                tab.label.animate().cancel()
                tab.label.alpha = 0f
                tab.label.scaleX = 0.4f
                tab.label.scaleY = 0.4f
                tab.label.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            } else {
                tab.label.animate().cancel()
                tab.label.visibility = View.GONE
            }
            val vPad = if (dockExpanded) 10 else 7
            tab.pill.setPadding(dp(10), dp(vPad), dp(10), dp(vPad))
        }
    }

    /** Remembers whether the Cloud dock cell last showed the panel or the IP scanner. */
    private var cloudDockShowsScanner = false

    /** Jump to a content screen and keep the dock in sync (Cloud cell covers panel + scanner). */
    internal fun openScreen(screen: Int) {
        if (screen == SCREEN_SCANNER || screen == SCREEN_CLOUD) cloudDockShowsScanner = screen == SCREEN_SCANNER
        val dockIndex = when (screen) {
            SCREEN_HOME -> DOCK_HOME
            SCREEN_SERVERS -> DOCK_SERVERS
            SCREEN_AETHER -> DOCK_AETHER
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
    internal fun cloudScannerSwitch(scannerActive: Boolean): View {
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

    /* `::x.isInitialized` only compiles where x is declared, so the extension files (MainActivity*.kt) ask here. */
    internal val dnsPrivacyErrorTextReady: Boolean get() = ::dnsPrivacyErrorText.isInitialized
    internal val scannerSniInputReady: Boolean get() = ::scannerSniInput.isInitialized
    internal val subscriptionsListReady: Boolean get() = ::subscriptionsList.isInitialized
    internal val settingsSubscriptionSelectorRowReady: Boolean get() = ::settingsSubscriptionSelectorRow.isInitialized
    internal val serversCountTextReady: Boolean get() = ::serversCountText.isInitialized

    internal class DockTab(val pill: LinearLayout, val icon: ImageView, val label: TextView)

    private lateinit var appTabsPending: TabLayout

    private fun showAppTab(position: Int) {
        currentActiveScreen = position
        if (position != 2) advancedSettingsBackAction?.invoke()
        vpnTabContent.visibility = if (position == 1) View.VISIBLE else View.GONE
        subscriptionsTabContent.visibility = if (position == 0) View.VISIBLE else View.GONE
        advancedTabContent.visibility = if (position == 2) View.VISIBLE else View.GONE
        cloudTabContent.visibility = if (position == 3) View.VISIBLE else View.GONE
        scannerTabContent.visibility = if (position == 4) View.VISIBLE else View.GONE
        aetherTabContent.visibility = if (position == 5) View.VISIBLE else View.GONE
        if (position == 0) renderSubscriptions()
        if (position == 1) {
            renderConnectionSelection()
            renderHomeUsageCard()
        }
        syncShellBackdrop()
        if (position == 2) renderAdvancedControls()
        if (position == 5) refreshAetherEngineSummary?.invoke()
    }

    /* ------------------------------------------------------------------ */
    /* Home usage graph (quota reported by the panel)                       */
    /* ------------------------------------------------------------------ */

    internal fun buildHomeUsageCard(): LinearLayout {
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
    internal fun renderHomeUsageCard() {
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

    /** True when the core reports a live session (used to route fetches). */
    private fun vpnStateEqualsStarted(): Boolean =
        buttonModel.state == VpnState.Started || VpnRuntimeStateStore.read(this) == VpnState.Started

    internal fun fetchFreeConfigs() {
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

    internal fun renderFreeConfigs() {
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
                .create()
                .showCatClientDialog()
        }
    }

    /** Paste / clipboard / QR entry point for a single config. */
    internal fun showQuickConfigMenu(anchor: View) {
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
    internal fun testFreeConfigs() {
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
    internal fun importFreeConfigs() {
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
    internal fun showConfigQrCodes(links: List<String>, title: String) {
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
    internal fun subscriptionUsageLine(usage: SubscriptionUsage?): String {
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

    internal fun openSubscriptionConnectionTesting(subscriptionId: String) {
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
    internal fun saveAetherConfigIntoMainSubscriptionList(
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
                DiagnosticLogger.warn(this@MainActivity, "aether.list.save", error.message.orEmpty(), error)
            }
            onSaved?.invoke()
        }
    }

    /** Connects (or reconnects) after a Cat Warp config was written and selected. */
    internal fun connectCatWarpAfterSave() {
        openScreen(SCREEN_HOME)
        if (currentVpnServiceState == VpnState.Started) {
            reconnectForConnectionOptionChange()
        } else {
            beginConnectFlow(Actions.CONNECT)
        }
    }

    private fun addSubscriptionFromClipboard() {
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

    private fun importAndConnectAetherSelection(selection: AetherSelection) {
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

    internal fun showAddSubscriptionDialog(initialSource: String = "", initialName: String = "") =
        showSubscriptionDialog(initialSource = initialSource, initialName = initialName)

    /** Undo HTML-entity escaping (&amp; …) that survives copy/paste from Telegram and web pages. */
    internal fun unescapeHtmlEntities(s: String): String = s
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
    internal suspend fun pushChainToPanelSuspend(link: String) {
        val base = withContext(Dispatchers.Main) { pickPanelBaseInteractive() }?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) { Toast.makeText(this, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return }
        val storedPass = runCatching { PanelCredentials.defaultPassword(this, base) }.getOrNull().orEmpty()
        withContext(Dispatchers.Main) {
            val input = TextInputEditText(this@MainActivity).apply {
                hint = getString(R.string.pip_password_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (storedPass.isNotBlank()) setText(storedPass)
            }
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.chain_push_title, base.removePrefix("https://")))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chain_push_btn) { d, _ ->
                    val password = input.text.toString()
                    d.dismiss()
                    activityScope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { pushChainToPanelApi(base, password, link) } }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, result.fold({ getString(R.string.chain_push_ok, link) }, { getString(R.string.chain_push_fail, it.message ?: "?") }), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun pushChainToPanelApi(base: String, password: String, chain: String): Unit {
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

    internal fun showEditSubscriptionDialog(item: UserSubscription) = showSubscriptionDialog(item)

    private fun testSubscription(item: UserSubscription) {
        runSubscriptionAction(getString(R.string.subscription_testing, item.name)) {
            val imported = userSubscriptionManager.test(item.input)
            getString(R.string.subscription_found, connectionCountLabel(imported.connectionCount))
        }
    }

    internal fun refreshSubscription(item: UserSubscription) {
        runSubscriptionAction(
            startMessage = getString(R.string.subscription_refreshing, item.name),
            refreshProfiles = item.id == userSubscriptionManager.selectedId(),
        ) {
            val refreshed = userSubscriptionManager.refresh(item.id)
            getString(R.string.subscription_refreshed, connectionCountLabel(refreshed.connectionCount))
        }
    }

    internal fun refreshBuiltInSubscription(subscriptionId: String, name: String) {
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

    internal fun collectVerifiedEndpointsForFailover(nowMs: Long = System.currentTimeMillis()): List<VerifiedEndpoint> {
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

    internal fun rotateProfileRouteInSubscription(
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
    internal fun showConnectionShareMenu(
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

    internal fun copySubscriptionLink(item: UserSubscription) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("subscription", subscriptionLink(item)))
        Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
    }

    internal fun shareSubscriptionLink(item: UserSubscription) {
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
    internal fun openSubscriptionInApp(item: UserSubscription, app: String) {
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
    internal fun openSubscriptionInfoPage(item: UserSubscription) {
        val link = subscriptionLink(item)
        val info = Regex("^(https://[^/]+)/u/([^/?#]+)").find(link)
            ?.let { "${it.groupValues[1]}/info/${it.groupValues[2]}" }
            ?: link.substringBefore('#').let { if (it.contains('?')) "$it&web=1" else "$it?web=1" }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info))) }
            .onFailure { copySubscriptionLink(item) }
    }

    internal fun confirmDeleteSubscription(item: UserSubscription) {
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

    internal fun onSubscriptionSelected() {
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

    internal fun connectionCountLabel(count: Int): String =
        resources.getQuantityString(R.plurals.connection_count, count, count)

    internal fun localizedSubscriptionError(error: String): String = when {
        error.isBlank() -> ""
        else -> getString(R.string.subscription_operation_failed)
    }

    internal fun selectedSubscriptionName(): String {
        val store = SubscriptionStore(this)
        val selectedId = store.readSelectedSubscriptionId()
        return store.readUserSubscription(selectedId)?.name ?: builtInSubscriptionName(selectedId)
    }

    internal fun builtInSubscriptionName(id: String): String = getString(
        if (id == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID) {
            R.string.subscription_public_name
        } else {
            R.string.subscription_private_name
        },
    )

    internal fun renderConnectionDetails(state: VpnState) {
        if (!::connectionDetailsText.isInitialized) return
        connectionDetailsText.text = when {
            state == VpnState.Started -> connectionDetails
            state == VpnState.Starting -> automaticConnectionProgress
            else -> ""
        }
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

    internal fun localizedError(@Suppress("UNUSED_PARAMETER") error: Throwable, @StringRes fallbackRes: Int): String =
        getString(fallbackRes)

    override fun onStart() {
        super.onStart()
        runCatching { appTools.onStart(appRootView) }
        VpnWidgetProvider.refresh(this)
        DiagnosticLogger.info(this, "activity.onStart")
        val filter = IntentFilter().apply {
            addAction(Actions.STATE_CHANGED)
            addAction(Actions.CONNECTION_DELAY_TEST_CHANGED)
            addAction(Actions.CONNECTION_SPEED_TEST_CHANGED)
            addAction(Actions.AUTOMATIC_CONNECTION_PROGRESS)
            addAction(Actions.REAL_INTERNET_CHECK)
        }
        // Below API 33 a bare registerReceiver is implicitly exported, which would let any other
        // app broadcast STATE_CHANGED and paint a false "connected" state over the UI.
        // ContextCompat guards the pre-33 path with a signature-level permission.
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Carrier can change while the app is open (Wi-Fi ↔ mobile, roaming); the fragment
        // preset is decided from it at connect time, so the settings line must follow.
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.registerDefaultNetworkCallback(carrierChangeCallback)
        }
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
        // beta220: the last real internet test of this connection (measured by the service while we were away)
        renderRealInternetCard(VpnRuntimeStateStore.readRealInternetResult(this))
        // Fragmenting is on → keep the recommendation fresh (one request per foreground).
        refreshPanelTuneIfNeeded()
        // The chain decides which countries the location picker may offer.
        refreshChainExitCountries()
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
        runCatching { appTools.onStop() }
        aetherOperationJob?.cancel(CancellationException("Cat Warp page left foreground"))
        aetherOperationJob = null
        AetherOperations.cancel()
        VpnWidgetProvider.refresh(this)
        DiagnosticLogger.info(this, "activity.onStop")
        mainHandler.removeCallbacks(timerRunnable)
        privacyPolicyDialog?.dismiss()
        privacyPolicyDialog = null
        connectionDelayTestListener = null
        unregisterReceiver(stateReceiver)
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(carrierChangeCallback)
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Reached an interactive state → the previous session did not die silently.
        CrashWatch.markHealthy(this)
        renderFragmentCarrierLine()
        appUpdateUi.onResume()
        prewarmSubscriptionSnapshots()
        runCatching { UiStallWatchdog.setActive(this, true) }
    }

    /**
     * Parses the selected subscription (and a few others) in the background so the first tap on a subscription,
     * the connection list or the picker does not freeze the UI while a large YAML is parsed.
     */
    private fun prewarmSubscriptionSnapshots() {
        activityScope.launch(Dispatchers.IO) {
            runCatching {
                val selectedId = SubscriptionStore(this@MainActivity).readSelectedSubscriptionId()
                val ids = (listOf(selectedId) + userSubscriptionManager.list().map { it.id })
                    .distinct()
                    .filterNot { SubscriptionStore.isBuiltInSubscription(it) }
                    .take(3)
                ids.forEach { id -> runCatching { userSubscriptionManager.cachedSnapshot(id) } }
            }
        }
    }

    override fun onPause() {
        runCatching { UiStallWatchdog.setActive(this, false) }
        appUpdateUi.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ACTIVE_SCREEN, currentActiveScreen)
        outState.putInt(STATE_SETTINGS_CATEGORY_RES, activeSettingsCategoryTitleRes)
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

    /**
     * Memory pressure, in the log and in the UI.
     *
     * The owner's beta98 report ends in `OutOfMemoryError` while a result row was being
     * built, and nothing in the diagnostics said the heap had been filling up. This
     * override records the level with the heap numbers (so the next report is readable)
     * and, when the system says the heap is low, drops the rendered rows to a small cap
     * before the next render — «the list gets shorter» beats «the app closes».
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val runtime = Runtime.getRuntime()
        DiagnosticLogger.info(
            this,
            "mem.trim",
            "level=$level maxMb=${runtime.maxMemory() / 1_048_576} " +
                "totalMb=${runtime.totalMemory() / 1_048_576} freeMb=${runtime.freeMemory() / 1_048_576}",
        )
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !lowMemoryRows) {
            lowMemoryRows = true
            runCatching { renderScannerResults() }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (appUpdateUi.onActivityResult(requestCode)) return
        if (appTools.handleActivityResult(requestCode, resultCode, data)) return
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

    /** Big value · small unit, as in ZedSecure's flow meter (Pixel/Portal designs). */
    internal fun setSpeedText(view: TextView, bytesPerSecond: Long) {
        val raw = formatTransferSpeed(bytesPerSecond)
        if (appDesign == AppDesign.Old) {
            view.text = raw
            return
        }
        val cut = raw.lastIndexOf(' ')
        if (cut <= 0) {
            view.text = raw
            return
        }
        val spannable = android.text.SpannableString(raw)
        spannable.setSpan(android.text.style.RelativeSizeSpan(0.52f), cut, raw.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(zedScheme.onSurfaceVariant),
            cut,
            raw.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        view.text = spannable
    }

    /** ZedSecure ticket card: shape badge · server · "Tap to change server" · round chevron. */
    internal fun buildZedTicket(): LinearLayout {
        val s = zedScheme
        val portal = appDesign == AppDesign.Portal
        val container = if (portal) s.primaryContainer else s.surfaceContainerHigh
        val ink = if (portal) s.onPrimaryContainer else s.onSurface
        val sub = if (portal) withAlpha(s.onPrimaryContainer, 190) else s.onSurfaceVariant
        activeConfigTitle.setTextColor(ink)
        activeConfigTitle.textSize = 17f
        val badge = ZedShapeIconView(
            this,
            if (portal) ZedShape.Cookie9 else ZedShape.Cookie4,
            if (portal) s.primary else s.primaryContainer,
            R.drawable.ic_zed_public,
            if (portal) s.onPrimary else s.onPrimaryContainer,
            0f,
            0.48f,
        )
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_zed_chevron_right)
            imageTintList = ColorStateList.valueOf(ink)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(ink, 22))
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(ink, 36)),
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(30).toFloat()
                    setColor(container)
                },
                null,
            )
            setOnClickListener {
                badge.animate().rotationBy(45f).setDuration(380L).start()
                if (connectionChainPreferenceStore.read().enabled) openConnectionChainSettingsFromHome() else openScreen(SCREEN_SERVERS)
            }
            addView(badge, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(activeConfigTitle, LinearLayout.LayoutParams(-1, -2))
                    addView(TextView(this@MainActivity).apply {
                        setText(R.string.home_tap_change_server)
                        textSize = 13f
                        typeface = CatClientBodyTypeface
                        setTextColor(sub)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) },
            )
            addView(chevron, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
    }

    /** Mirrors the VPN state into the Pixel/Portal home (title tint, Pixel card tone, connect hint). */
    private fun applyZedHomeState(state: VpnState) {
        if (appDesign == AppDesign.Old || !::heroStateText.isInitialized) return
        val s = zedScheme
        val live = state == VpnState.Started
        homeHintText?.visibility = if (state == VpnState.Stopped) View.VISIBLE else View.GONE
        val title = heroStateText
        title.paint.shader = null
        when (appDesign) {
            AppDesign.Pixel -> {
                val target = if (live) s.primaryContainer else s.surfaceContainer
                val card = pixelCard
                if (card != null && target != pixelCardColor) {
                    val from = pixelCardColor
                    pixelCardColor = target
                    val bg = card.background as? GradientDrawable
                    android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, target).apply {
                        duration = 420L
                        addUpdateListener { bg?.setColor(it.animatedValue as Int) }
                        start()
                    }
                }
                title.setTextColor(
                    when {
                        state is VpnState.Error -> s.error
                        live -> s.onPrimaryContainer
                        else -> s.onSurface
                    },
                )
                homeHintText?.setTextColor(s.primary)
            }
            AppDesign.Portal -> {
                title.setTextColor(if (state is VpnState.Error) s.error else s.onSurface)
                if (live) {
                    title.post {
                        val textWidth = title.paint.measureText(title.text.toString())
                        val start = ((title.width - textWidth) / 2f).coerceAtLeast(0f)
                        title.paint.shader = android.graphics.LinearGradient(
                            start, 0f, start + textWidth.coerceAtLeast(1f), 0f,
                            s.primary, s.tertiary, android.graphics.Shader.TileMode.CLAMP,
                        )
                        title.invalidate()
                    }
                } else {
                    title.invalidate()
                }
            }
            AppDesign.Old -> Unit
        }
    }

    /* ====================== ZedSecure 3.1.6 Settings pieces (Pixel/Portal) ====================== */

    /** Tonal container + ink for a hue, the way each Zed settings row gets its own colour. */
    internal fun zedTone(hue: Float): Pair<Int, Int> {
        val dark = palette.isDark
        val container = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, if (dark) 0.42f else 0.62f, if (dark) 0.30f else 0.86f))
        val ink = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(hue, if (dark) 0.75f else 0.70f, if (dark) 0.82f else 0.28f))
        return container to ink
    }

    internal fun zedSectionLabel(@StringRes labelRes: Int): TextView = TextView(this).apply {
        setText(labelRes)
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        setTextColor(zedScheme.primary)
        includeFontPadding = false
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        textDirection = View.TEXT_DIRECTION_LOCALE
        setPaddingRelative(dp(26), dp(26), dp(16), dp(12))
    }

    /** Zed grouped list: 28 dp outer corners, 6 dp inner corners, 3 dp gaps. */
    internal fun zedApplyGroupCorners(group: LinearLayout) {
        val items = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility != View.GONE }
        val big = dp(28).toFloat()
        val small = dp(6).toFloat()
        items.forEachIndexed { index, item ->
            val top = if (index == 0) big else small
            val bottom = if (index == items.lastIndex) big else small
            val radii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            when (val bg = item.background) {
                is RippleDrawable -> (bg.getDrawable(0) as? GradientDrawable)?.cornerRadii = radii
                is GradientDrawable -> bg.cornerRadii = radii
            }
            (item.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = if (index == 0) 0 else dp(3)
                item.layoutParams = it
            }
        }
    }

    internal fun zedCardBackground(color: Int = zedScheme.surfaceContainer, ripple: Boolean = false): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            setColor(color)
        }
        return if (ripple) RippleDrawable(ColorStateList.valueOf(withAlpha(zedScheme.onSurface, 28)), shape, null) else shape
    }

    private fun zedChevron(tint: Int = zedScheme.onSurfaceVariant): ImageView = ImageView(this).apply {
        setImageResource(R.drawable.ic_zed_chevron_right)
        imageTintList = ColorStateList.valueOf(tint)
        if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** ZedSecure SettingsItem: shape badge · title · detail · chevron. */
    internal fun zedSettingsItem(
        title: CharSequence,
        detail: CharSequence?,
        shape: ZedShape,
        hue: Float,
        @DrawableRes iconRes: Int,
        badge: View? = null,
        danger: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        val (container, ink) = if (danger) zedScheme.errorContainer to zedScheme.onErrorContainer else zedTone(hue)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(78)
            setPaddingRelative(dp(16), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = title
            background = zedCardBackground(ripple = true)
            val icon = ZedShapeIconView(this@MainActivity, shape, container, iconRes, ink, 0f, 0.48f)
            setOnClickListener {
                icon.animate().rotationBy(30f).setDuration(320L).start()
                onClick()
            }
            addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 17f
                        typeface = CatClientBodyTypeface
                        setTextColor(if (danger) zedScheme.error else zedScheme.onSurface)
                        includeFontPadding = false
                    })
                    if (!detail.isNullOrBlank()) {
                        addView(TextView(this@MainActivity).apply {
                            text = detail
                            textSize = 13.5f
                            typeface = CatClientBodyTypeface
                            setTextColor(zedScheme.onSurfaceVariant)
                            includeFontPadding = false
                            setLineSpacing(dp(2).toFloat(), 1f)
                            maxLines = 3
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                    }
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
            )
            badge?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }
            addView(zedChevron(), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) })
        }
    }

    /** Big page title with the Zed art in the corner ("Settings · Make it yours", "Interface"). */
    internal fun zedPageHero(title: CharSequence, subtitle: CharSequence, art: View, titleSize: Float = 40f): FrameLayout =
        FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            clipChildren = false
            addView(art, FrameLayout.LayoutParams(dp(150), dp(150)).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = titleSize
                        typeface = CatClientDisplayTypeface
                        setTextColor(zedScheme.onSurface)
                        includeFontPadding = false
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        textSize = 15f
                        typeface = CatClientBodyTypeface
                        setTextColor(zedScheme.onSurfaceVariant)
                        includeFontPadding = false
                        maxLines = 2
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                },
                FrameLayout.LayoutParams(-1, -2).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    marginEnd = dp(140)
                },
            )
            minimumHeight = dp(160)
        }

    /** Gradient banner (Zed "Support the project" slot) — here it opens the plain-language guide. */
    internal fun zedGuideCard(): LinearLayout {
        val s = zedScheme
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(18), dp(20), dp(18), dp(20))
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.settings_guide_title)
            val ltr = resources.configuration.layoutDirection != View.LAYOUT_DIRECTION_RTL
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(s.onPrimaryContainer, 30)),
                GradientDrawable(
                    if (ltr) GradientDrawable.Orientation.LEFT_RIGHT else GradientDrawable.Orientation.RIGHT_LEFT,
                    intArrayOf(s.tertiaryContainer, s.primaryContainer),
                ).apply { cornerRadius = dp(34).toFloat() },
                null,
            )
            setOnClickListener { showPlainLanguageFullGuideDialog() }
            addView(
                ZedShapeIconView(this@MainActivity, ZedShape.Cookie4, withAlpha(s.onPrimaryContainer, 40), R.drawable.ic_zed_help, s.onPrimaryContainer, 0f, 0.5f),
                LinearLayout.LayoutParams(dp(54), dp(54)),
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        setText(R.string.settings_guide_title)
                        textSize = 18f
                        typeface = CatClientBodyBoldTypeface
                        setTextColor(s.onPrimaryContainer)
                        includeFontPadding = false
                    })
                    addView(TextView(this@MainActivity).apply {
                        setText(R.string.settings_guide_detail)
                        textSize = 14f
                        typeface = CatClientBodyTypeface
                        setTextColor(withAlpha(s.onPrimaryContainer, 210))
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
            )
            addView(zedChevron(s.onPrimaryContainer), LinearLayout.LayoutParams(dp(22), dp(22)))
        }
    }

    /** Material 3 connected button group (Theme: System · Light · Dark, Contrast: …). */
    internal fun zedSegmented(options: List<Pair<String, Int>>, selectedIndex: Int, onPick: (Int) -> Unit): LinearLayout {
        val s = zedScheme
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            options.forEachIndexed { index, (label, iconRes) ->
                val selected = index == selectedIndex
                val ink = if (selected) s.onPrimaryContainer else s.onSurface
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        isClickable = true
                        isFocusable = true
                        contentDescription = if (selected) getString(R.string.design_selected_description, label) else label
                        setPadding(dp(8), 0, dp(8), 0)
                        background = RippleDrawable(
                            ColorStateList.valueOf(withAlpha(s.primary, 40)),
                            GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(if (selected) 28 else 14).toFloat()
                                if (selected) setColor(s.primaryContainer) else {
                                    setColor(Color.TRANSPARENT)
                                    setStroke(dp(1), s.outlineVariant)
                                }
                            },
                            null,
                        )
                        setOnClickListener { if (!selected) onPick(index) }
                        if (iconRes != 0) {
                            addView(ImageView(this@MainActivity).apply {
                                setImageResource(iconRes)
                                imageTintList = ColorStateList.valueOf(ink)
                            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
                        }
                        addView(TextView(this@MainActivity).apply {
                            text = label
                            textSize = 15f
                            typeface = if (selected) CatClientBodyBoldTypeface else CatClientBodyTypeface
                            setTextColor(ink)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            includeFontPadding = false
                        })
                    },
                    LinearLayout.LayoutParams(0, dp(54), 1f).apply { if (index > 0) marginStart = dp(4) },
                )
            }
        }
    }

    internal fun zedTitledCard(@StringRes titleRes: Int, @StringRes detailRes: Int, body: View?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = zedCardBackground()
            addView(TextView(this@MainActivity).apply {
                setText(titleRes)
                textSize = 18f
                typeface = CatClientBodyTypeface
                setTextColor(zedScheme.onSurface)
                includeFontPadding = false
            })
            if (detailRes != 0) {
                addView(TextView(this@MainActivity).apply {
                    setText(detailRes)
                    textSize = 14f
                    typeface = CatClientBodyTypeface
                    setTextColor(zedScheme.onSurfaceVariant)
                    includeFontPadding = false
                    setLineSpacing(dp(3).toFloat(), 1f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            body?.let { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }) }
        }

    /** Selectable preview tile with a label underneath and a ✓ badge on the chosen one. */
    internal fun zedPickTile(preview: View, label: String, selected: Boolean, tileColor: Int, heightDp: Int, onPick: () -> Unit): LinearLayout {
        val s = zedScheme
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(tileColor)
                setStroke(dp(if (selected) 3 else 1), if (selected) s.primary else s.outlineVariant)
            }
            clipToOutline = true
            val inset = dp(if (selected) 3 else 1)
            addView(preview, FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) })
            if (selected) {
                addView(
                    ZedShapeIconView(this@MainActivity, ZedShape.Circle, s.primaryContainer, R.drawable.ic_zed_check, s.onPrimaryContainer, 0f, 0.56f),
                    FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                        gravity = Gravity.TOP or Gravity.END
                        topMargin = dp(8)
                        marginEnd = dp(8)
                    },
                )
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            contentDescription = if (selected) getString(R.string.design_selected_description, label) else label
            setOnClickListener { if (!selected) onPick() }
            ZedIosMotion.press(this)
            addView(frame, LinearLayout.LayoutParams(-1, dp(heightDp)))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = if (selected) CatClientBodyBoldTypeface else CatClientBodyTypeface
                setTextColor(if (selected) s.primary else s.onSurfaceVariant)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    /* ============================== Cloud tab ============================== */

    /** A Cloud-tab card for one Node host: title, one plain sentence, and the same 7 buttons on every host (🧭 from zero first). */
    internal fun nodeHostCard(kind: String, @StringRes titleRes: Int, @StringRes descRes: Int): View {
        val card = advancedSettingsPanel()
        card.addView(
            TextView(this).apply {
                setText(titleRes)
                textSize = 16f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        card.addView(
            TextView(this).apply {
                setText(descRes)
                textSize = 12.5f
                typeface = CatClientBodyTypeface
                setTextColor(TEXT_SECONDARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
        )
        val git = kind == NodeHostPanel.LUCITY || kind == NodeHostPanel.SHIPRR
        // Bot-Hosting / Railway are driven end-to-end by their API: «⚡» IS the from-zero path (no 🧭 wizard, and
        // Railway has no files to upload, so no 📦 zip there).
        val api = NodeHostPanel.isApiHost(kind)
        val buttons = listOfNotNull<Pair<Int, () -> Unit>>(
            if (api) null else Pair(R.string.cloud_node_start, { nodeHosts.showStart(kind) }),
            Pair(if (git) R.string.cloud_node_auto_git else R.string.cloud_node_auto, { nodeHosts.autoInstall(kind) }),
            if (kind == NodeHostPanel.RAILWAY) null else Pair(R.string.cloud_node_zip, { nodeHosts.saveFiles(kind) }),
            Pair(R.string.cloud_node_register, { nodeHosts.registerManual(kind) }),
            Pair(if (git || kind == NodeHostPanel.RAILWAY) R.string.cloud_node_redeploy else R.string.cloud_node_renew, { nodeHosts.renew(kind) }),
            Pair(R.string.cloud_node_mine, { nodeHosts.showMine(kind) }),
            Pair(R.string.cloud_node_guide, { nodeHosts.showGuide(kind) }),
        )
        buttons.forEachIndexed { i, (labelRes, action) ->
            card.addView(
                cloudActionButton(labelRes, R.drawable.ic_cloud_tab, accent = i == 0) { action() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 10 else 8) },
            )
        }
        return card
    }

    internal fun cloudActionButton(
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

    internal fun cloudScopeLabel(scope: CloudflareWorker.PanelScope): String = when (scope) {
        CloudflareWorker.PanelScope.CF_WORKER -> getString(R.string.cloud_scope_worker)
        CloudflareWorker.PanelScope.SERVER -> getString(R.string.cloud_scope_server)
        CloudflareWorker.PanelScope.TUNNEL -> getString(R.string.cloud_scope_tunnel)
    }

    /* ------------------------------------------------------------------ */
    /* IP scanner screen: clean Cloudflare IPs (SNI + fronting)            */
    /* ------------------------------------------------------------------ */

    internal fun scannerInput(hint: String, initial: String): TextInputEditText =
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

    internal fun scannerFieldLayout(hint: String, input: TextInputEditText): TextInputLayout =
        TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(input)
        }

    internal fun recommendedScannerSnis(): List<String> = buildList {
        addAll(detectPanelSnisFromSubscriptions())
        addAll(IpScanner.RECOMMENDED_SNIS)
    }.map(String::lowercase).filter { it.isNotBlank() }.distinct().take(8)

    internal fun scannerSniPreference(): String {
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
    internal fun detectPanelSnisFromSubscriptions(): List<String> {
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
    internal data class PanelIdentity(
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
    internal fun buildSubscriptionFromScan(results: List<IpScanner.ScanResult>) {
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

    internal fun saveScannerSni(value: String) {
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(SCANNER_SNI_KEY, value)
            .apply()
    }

    internal fun scannerPortPreference(): Int {
        val saved = getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .getInt(SCANNER_PORT_KEY, 443)
        return saved.takeIf { it in 1..65535 } ?: 443
    }

    internal fun saveScannerPort(value: Int) {
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
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
    private fun scannerSourcePreference(key: String, fallback: Boolean): Boolean =
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .getBoolean("source_$key", fallback)

    internal fun saveScannerSource(key: String, value: Boolean) {
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE).edit().putBoolean("source_$key", value).apply()
    }

    internal var scannerSourceRow: LinearLayout? = null
    internal var scannerSourceSummary: TextView? = null
    internal var scannerForceRow: LinearLayout? = null

    /**
     * One switch, one meaning: the v6 source is either walked or it is not.
     *
     * The chip that used to exist only toggled `scannerForceV6` (skip the probe),
     * so a network with working v6 and the chip OFF still scanned v4-only — the
     * owner's «ای‌پی‌های v6 نیست» had two causes and the UI owned neither: the chip
     * was below the buttons, unlabelled as to state, and the probe verdict vanished
     * once the run started. Now the same switch answers all three.
     */
    internal fun scannerSourceEnabled(key: String): Boolean = when (key) {
        "ipv6" -> scannerForceV6 || scannerSourcePreference("ipv6", true)
        "mine" -> scannerSourcePreference("mine", true)
        "cloudflare" -> scannerSourcePreference("cloudflare", true)
        "iran" -> scannerSourcePreference("iran", true)
        else -> true
    }

    internal fun scannerRangesPreference(): String {
        val saved = getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE).getString(SCANNER_RANGES_KEY, null)
        return saved ?: IpScanner.defaultRangesText()
    }

    internal fun saveScannerRanges(value: String) {
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(SCANNER_RANGES_KEY, value)
            .apply()
    }

    internal fun stopIpScanner() {
        scannerJob?.cancel()
        scannerJob = null
        scannerRunning = false
        scannerStartButton.isEnabled = true
        scannerStopButton.isEnabled = false
        scannerProgressBar.visibility = View.GONE
        scannerStatusText.setText(R.string.scanner_stopped)
    }

    /** XIU2-style download throughput for the top verified rows; sorts by speed after. */
    internal fun runScannerSpeedTest() {
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
                    Toast.makeText(this@MainActivity, getString(R.string.scanner_speed_done, best.key, mbps), Toast.LENGTH_LONG).show()
                } else {
                    scannerStatusText.setText(R.string.scanner_no_results)
                }
            }
        }

    }

    private var ipHealthJob: Job? = null
    private var ipHealthSweeping = false
    internal var ipHealthStatusView: TextView? = null
    internal var ipHealthCountryRow: LinearLayout? = null

    /** Pool grouped by location: one chip per country (tap = lock failover/replacements to it), IPs listed under the lock. */
    internal fun renderIpHealthCountries(entries: List<IpHealthEntry>) {
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
                    (if (e.authority == active || e.ip == active) "● " else "   ") + e.authority + (e.colo?.let { "  $it" } ?: "") + (if (e.pingMs > 0) "  ${e.pingMs} ms" else "") + (if (e.fails > 0) "  ✗${e.fails}" else "")
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    internal fun startIpHealthLoop() {
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

    internal fun runIpHealthSweep(silent: Boolean = false, failFast: Boolean = false) {
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

    /** 👑 Neighbor scan: best verified hits → walk ±16 addresses around each in
     * the same /24 (v4) or /64 (v6), verified with the SAME SNI/port; results
     * join the live list, carry source=neighbor when pushed to the panel. */
    internal fun runNeighborScan() {
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
                IpScanner.scan(this@MainActivity, IpScanner.ScanOptions(
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
            Toast.makeText(this@MainActivity, getString(R.string.scanner_neighbor_done, added.size, clean.size), Toast.LENGTH_LONG).show()
        }
    }

    /** ⚡ Scan profile chips: fast / standard / deep — depth of the same engine. */
    internal fun scannerProfileRow(): View {
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
                    getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE).edit().putString("profile", key).apply()
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

    private fun refreshScannerProfileRow(row: LinearLayout) {
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
    private fun scannerPlainList(): String {
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

    internal fun copyScannerPlainList() {
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
    internal fun runScannerForeignTest() {
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
    private fun probeForeignService(target: ForeignServiceTarget): ForeignServiceProbeResult {
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

    private fun foreignServiceVerdict(result: ForeignServiceProbeResult): Pair<Int, Int> {
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

    private fun scannerForeignResultRow(result: ForeignServiceProbeResult): View {
        val (verdictRes, color) = foreignServiceVerdict(result)
        val codeLabel = if (result.httpStatus > 0) result.httpStatus.toString() else "—"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = glassSurfaceDrawable(radiusDp = 12)
            addView(TextView(this@MainActivity).apply {
                text = result.target.label
                textSize = 13f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.scanner_foreign_row, codeLabel, result.latencyMs)
                textSize = 11.5f
                typeface = CatClientBodyBoldTypeface
                setTextColor(color)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(TextView(this@MainActivity).apply {
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
    internal fun shareScannerForeignReport() {
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
    internal fun shareScannerResults() {
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
    internal fun saveScanHistoryRun(bestMs: Int?, verified: Int, total: Int) {
        val entry = "${System.currentTimeMillis()}:${bestMs ?: -1}:$verified:$total"
        val rest = scanHistoryRaw().split("|").filter { it.isNotBlank() }.take(11)
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString("history", (listOf(entry) + rest).joinToString("|"))
            .apply()
    }

    private fun scanHistoryRaw(): String =
        getSharedPreferences(SCANNER_PREFERENCES, MODE_PRIVATE).getString("history", "") ?: ""

    internal fun renderScanHistory() {
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
    private fun refreshScannerCountrySummary(list: List<IpScanner.ScanResult>) {
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

    // Category chips under the results (like the reference screenshots): all /
    // v4-only / v6-only — v6 chip lights up once the walk found v6 endpoints.
    private var scannerFilterAll: android.widget.TextView? = null
    private var scannerFilterV4: android.widget.TextView? = null
    private var scannerFilterV6: android.widget.TextView? = null
    internal var scannerFilter: String = "all"
    /** Results table: which column is sorted, and in which direction. */
    private var scannerSortKey: String = "ping"
    private var scannerSortAsc: Boolean = true
    /** `ip:port` of every row the owner ticked; survives re-renders and re-sorts. */
    internal val scannerSelected = linkedSetOf<String>()
    /** Header cells, rebuilt once per page: (view, sort key, base label). */
    private val scannerHeaderCells = mutableListOf<Triple<TextView, String, String>>()
    private var scannerSelectionText: TextView? = null
    private lateinit var scannerCopySelectedButton: MaterialButton
    private lateinit var scannerSendSelectedButton: MaterialButton
    internal var scannerNeighborRunning: Boolean = false
    /** IPs the 👑 neighbor scan added this session — sent to the panel with
     * source=neighbor so the 🩺 provenance badge shows «👑 همسایه». */
    internal val scannerNeighborIps = mutableSetOf<String>()
    // ⚡ Advanced scanner (beta57): profile «fast/std/deep», jitter of the top
    // hits, country distribution of the visible results, run history line.
    internal var scannerProfile: String = "std"
    // 🌐 Force-IPv6: bypass the connectivity gate — some carriers answer v6
    // even when the quick probe fails (the user's own line, per BPB scans).
    // Persisted flag (loaded in onCreate — reading prefs in a property
    // initializer runs inside the Activity constructor, where the base context
    // is still null and getSharedPreferences throws NullPointerException:
    // «the app opens and closes instantly». See scripts/android-startup-guard.py.)
    internal var scannerForceV6: Boolean = false
    internal var scannerAiRunning: Boolean = false
    internal lateinit var scannerAiList: LinearLayout
    private var scannerForeignRunning: Boolean = false
    internal lateinit var scannerForeignList: LinearLayout
    internal lateinit var scannerForeignShareButton: MaterialButton
    private val scannerForeignResults = linkedMapOf<String, ForeignServiceProbeResult>()
    internal var scannerCountryText: TextView? = null
    internal var scannerHistoryText: TextView? = null
    /** Set by [onTrimMemory] when the system reports a low heap: fewer rows, same data. */
    private var lowMemoryRows: Boolean = false
    /** Rows rendered in the results list right now (see [scannerVisibleCap]). */
    private fun scannerVisibleCap(): Int = if (lowMemoryRows) 12 else SCANNER_VISIBLE_RESULTS

    private fun scannerFilterVisible(list: List<IpScanner.ScanResult>): List<IpScanner.ScanResult> =
        when (scannerFilter) {
            "v4" -> list.filter { !IpScanner.isIpv6(it.ip) }
            "v6" -> list.filter { IpScanner.isIpv6(it.ip) }
            else -> list
        }

    internal fun scannerFilterRow(): View {
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

    private fun refreshScannerFilters() {
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
    internal fun scannerRowKey(result: IpScanner.ScanResult): String = "${result.ip}:${result.port}"

    /** The list the table renders from: live rows while a run is in flight, final rows after it. */
    private fun scannerSourceList(): List<IpScanner.ScanResult> =
        if (scannerRunning) scannerLiveResults.toList() else scannerResults

    /** Applies the column the owner sorted on; rows with no value for it sink to the bottom. */
    private fun scannerSortedRows(list: List<IpScanner.ScanResult>): List<IpScanner.ScanResult> {
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
    private fun scannerSelectedResults(): List<IpScanner.ScanResult> {
        if (scannerSelected.isEmpty()) return emptyList()
        val visible = scannerSortedRows(scannerFilterVisible(scannerSourceList()))
        return visible.filter { scannerSelected.contains(scannerRowKey(it)) }
    }

    private fun toggleScannerRow(result: IpScanner.ScanResult) {
        val key = scannerRowKey(result)
        if (!scannerSelected.add(key)) scannerSelected.remove(key)
        renderScannerResults()
    }

    private fun selectAllScannerResults(selected: Boolean) {
        if (selected) scannerFilterVisible(scannerSourceList()).forEach { scannerSelected += scannerRowKey(it) }
        else scannerSelected.clear()
        renderScannerResults()
    }

    private fun selectVerifiedScannerResults() {
        scannerSelected.clear()
        scannerFilterVisible(scannerSourceList()).filter { it.tlsOk }.forEach { scannerSelected += scannerRowKey(it) }
        renderScannerResults()
    }

    /** Copies the ticked rows as `ip:port#CC` lines — the shape the panel's import box eats. */
    private fun copySelectedScannerResults() {
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
    private fun sendSelectedScannerResults() {
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

    private fun refreshScannerHeader() {
        scannerHeaderCells.forEach { (view, key, label) ->
            val active = scannerSortKey == key
            view.text = if (active) label + (if (scannerSortAsc) " ▲" else " ▼") else label
            view.setTextColor(if (active) TEAL else TEXT_SECONDARY)
        }
    }

    /** Sortable column headers; the widths mirror [scannerResultRow] so the columns line up. */
    internal fun scannerHeaderRow(): LinearLayout {
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
    internal fun scannerSelectionBar(): LinearLayout {
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

    private fun refreshScannerSelection(total: Int) {
        val n = scannerSelected.size
        scannerSelectionText?.text = getString(R.string.scanner_selected_count, n, total)
        if (::scannerCopySelectedButton.isInitialized) scannerCopySelectedButton.isEnabled = n > 0
        if (::scannerSendSelectedButton.isInitialized) scannerSendSelectedButton.isEnabled = n > 0
    }

    internal fun renderScannerResults() {
        if (!::scannerResultsList.isInitialized) return
        val all = scannerSourceList()
        val visible = scannerFilterVisible(all)
        scannerResultsList.removeAllViews()
        refreshScannerFilters()
        refreshScannerHeader()
        refreshScannerCountrySummary(visible)
        refreshScannerSelection(all.size)
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
        runCatching { scannerApplyButton.isEnabled = hasVerifiedResult }
        if (::scannerBuildButton.isInitialized) scannerBuildButton.isEnabled = hasVerifiedResult
        // Count header: the user must always see THAT results exist, even if a
        // fancy row fails to build (regression: chips showed 1114 but zero rows
        // rendered — an exception anywhere in the styled-row loop left the list
        // blank with no trace).
        scannerResultsList.addView(
            TextView(this).apply {
                text = getString(R.string.scanner_results_header, visible.size, minOf(visible.size, scannerVisibleCap()))
                textSize = 11f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
        )
        // The sort itself is inside the guard too: it used to be the only unguarded
        // statement between the count header and the rows, so a throw there left the
        // screen showing «N result» with an empty list under it (again).
        val top = runCatching { scannerSortedRows(visible) }.getOrElse { visible }
            .take(scannerVisibleCap()).toList()
        val buildFailure = runCatching {
            top.forEachIndexed { index, result ->
                val row = runCatching { scannerResultRow(index + 1, result) }.getOrNull()
                scannerResultsList.addView(
                    row ?: fallbackScannerRow(result),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
                )
            }
        }.exceptionOrNull()
        // An OOM while building rows (the beta98 report: RippleDrawable allocation with
        // <1% heap free) must not travel any further up the main thread. Shrink and log.
        if (buildFailure is OutOfMemoryError && !lowMemoryRows) {
            lowMemoryRows = true
            DiagnosticLogger.warn(this, "scanner.rows.oom", "rows=${top.size} cap=${scannerVisibleCap()}", buildFailure)
            scannerResultsList.removeAllViews()
            renderScannerResults()
            return
        }
        if (buildFailure != null && scannerResultsList.childCount <= 1) {
            scannerResultsList.addView(scannerPlainRowsBlock(top), LinearLayout.LayoutParams(-1, -2))
        }
        verifyScannerRowsPainted(top.size, buildFailure)
    }

    /** One plain text line per result — no weights, no backgrounds, no clipping. */
    private fun scannerPlainRowsBlock(rows: List<IpScanner.ScanResult>): TextView =
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
    private fun verifyScannerRowsPainted(expected: Int, buildFailure: Throwable?) {
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
                    this@MainActivity,
                    "scanner.rows.empty",
                    "expected=$expected children=$children paintedPx=$painted " +
                        "failure=${buildFailure?.javaClass?.simpleName}:${buildFailure?.message}",
                )
            }
        }
        runCatching { root.addOnGlobalLayoutListener(listener) }
    }

    /** Cannot-throw plain row — the guaranteed-visible last resort. */
    private fun fallbackScannerRow(result: IpScanner.ScanResult): View =
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
    private fun scannerMeasureLine(r: IpScanner.ScanResult): String? {
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
    internal fun scannerWorkerHost(scanSni: String?): String? {
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
    internal suspend fun measureScannerRows(
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
    private fun scannerResultRow(rank: Int, result: IpScanner.ScanResult): View {
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
    internal fun panelNeedsUsername(base: String): Boolean = runCatching {
        val conn = URL("$base/api/version").openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        conn.instanceFollowRedirects = false
        val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        org.json.JSONObject(text).optBoolean("needsUser", false)
    }.getOrDefault(false)

    internal var scannerPanelPushProgressBar: ProgressBar? = null
    internal var scannerPanelPushStatusText: TextView? = null

    internal fun updateScannerPanelPushProgress(percent: Int, label: String, visible: Boolean = true) {
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

    internal fun sendScanToPanel() = activityScope.launch { sendScanToPanelSuspend() }

    /**
     * Import clean IPs into Cat Panel over its API: POST /api/login (password →
     * session cookie), then POST /api/ips {ips:[…]} which appends and returns the
     * new list size. Nothing is stored on the phone. Mirror of
     * ProxyIpScannerPage.pushToPanel.
     */
    internal fun pushIpsToPanel(base: String, password: String, lines: List<String>, replace: Boolean = false, pingMs: org.json.JSONObject? = null, countries: org.json.JSONObject? = null, neighborLines: List<String> = emptyList()): Int {
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

    private fun copyScannerIp(result: IpScanner.ScanResult) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("clean-ip", result.authority))
        Toast.makeText(this, getString(R.string.scanner_ip_copied, result.authority), Toast.LENGTH_SHORT).show()
    }

    /**
     * Applies a scanned IP:port as a fronting endpoint: connects to that clean CDN
     * endpoint while the profile's SNI/Host (the panel domain) stays untouched.
     */
    internal fun applyScannerResult(result: IpScanner.ScanResult? = null) {
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

    /* ------------------------------------------------------------------ */
    /* Panel status & one-tap recovery (Cloudflare abuse-report 1101)      */
    /* ------------------------------------------------------------------ */

    internal fun buildPanelStatusCard(): View {
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

    internal fun onPanelStatusCheck() {
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
            } else if (health.state == "PENDING") {
                // The route is not live yet — a fresh upload, not a fault. Measured on
                // a real account: up to a minute. The recovery dialog rebuilds the
                // panel under a new name, which is the last thing a working upload
                // needs, so this case gets its own answer and a button that rechecks.
                showPanelActivatingDialog(dep.workerUrl, health.detail)
            } else {
                showPanelRecoveryDialog(dep.workerUrl, health.detail)
            }
        }
    }

    /** Version + update + delete for a healthy panel (deployed from this app). */
    private fun showPanelManageDialog(dep: PanelDeploymentRecord, deployed: String?, newest: PanelUpdate.PanelScript?) {
        // Strictly newer — `!=` used to flag an OLDER source as «⬆ update available».
        val updateAvailable = newest != null && (deployed == null || AppUpdatePolicy.isNewer(newest.version, deployed))
        val rollbackSummary = PanelDeploymentStore(this).rollbackSummaryFor(dep.workerUrl)
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
                ) + if (rollbackSummary != null) "\n\nRollback snapshot: $rollbackSummary" else "",
            )
            .setPositiveButton(R.string.panel_update_btn) { _, _ ->
                val script = newest
                if (script == null) {
                    Toast.makeText(this, R.string.panel_no_source, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (blockPanelDowngrade(deployed, script.version)) return@setPositiveButton
                val token = PanelDeploymentStore(this).tokenFor(dep.workerUrl).orEmpty()
                panelUpdateInProgress = true
                // No token? Token-free guided update — NEVER a token prompt.
                if (token.isBlank()) presentTokenlessPanelUpdate(dep, deployed, script, updateAvailable)
                else presentPanelUpdatePasswordDialog(dep, deployed, script, updateAvailable, token)
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

    /**
     * «Still activating», not «broken». Shown when the panel's own route answers the
     * edge's 404 page (no Worker on it yet) instead of the panel: normal for the first
     * seconds-to-a-minute after an upload, so it offers a recheck instead of the
     * rebuild flow. Every button here does something: the recheck re-runs the same
     * status check, and the panel button opens the URL.
     */
    private fun showPanelActivatingDialog(panelUrl: String, detail: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_status_pending_title)
            .setMessage(getString(R.string.cloud_status_pending_msg, panelUrl, detail))
            .setPositiveButton(R.string.cloud_status_recheck) { _, _ -> onPanelStatusCheck() }
            .setNeutralButton(R.string.cloud_panel_open) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(panelUrl))) }
            }
            .setNegativeButton(android.R.string.cancel, null)
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
                    isRepair = true,
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
                                username = PanelCredentials.defaultUsername(this@MainActivity, result.workerUrl),
                                password = PanelCredentials.defaultPassword(this@MainActivity, result.workerUrl),
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

    internal fun probeAndShowAetherExitIpDialog(onUpdated: (() -> Unit)? = null) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال بررسی آی‌پی و کشور خروجی فعلی…" else "⏳ Checking current exit IP & country…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val result = withContext(Dispatchers.IO) { AetherOperations.probeExitTrace(5_000) }
            val trace = result.getOrNull()
            if (trace == null) {
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "⚠️ امکان دریافت وضعیت آی‌پی خروجی نبود: ${result.exceptionOrNull()?.message.orEmpty()}"
                    else "⚠️ Could not probe exit IP: ${result.exceptionOrNull()?.message.orEmpty()}",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val isIran = trace.isIranIp
            val flagAndTitle = if (isIran) {
                if (isFarsi) "🇮🇷 آی‌پی فعلی شما: ایران (IR)" else "🇮🇷 Current Exit IP: Iran (IR)"
            } else {
                if (isFarsi) "🌍 آی‌پی فعلی شما: خارج از ایران (${trace.countryCode})"
                else "🌍 Current Exit IP: Foreign (${trace.countryCode})"
            }
            val msg = if (isFarsi) {
                "• کشور خروجی: ${if (isIran) "🇮🇷 ایران (IR)" else "🌍 خارج (${trace.countryCode})"}\n" +
                    "• آدرس آی‌پی: ${trace.ip}\n" +
                    "• دیتاسنتر کلودفلر: ${trace.colo}\n" +
                    "• وضعیت WARP: ${trace.warpStatus}\n\n" +
                    if (isIran) {
                        "💡 اگر آی‌پی خارج (غیر ایران) می‌خواهید، دکمه «🌍 تغییر به آی‌پی خارج» را بزنید تا از حالت دو لایه Gool یا چین سایفون استفاده شود."
                    } else {
                        "💡 آی‌پی شما غیر ایران (${trace.countryCode}) است. اگر برای سایت‌های داخلی یا سرعت بالاتر آی‌پی ایران می‌خواهید، دکمه «🇮🇷 تغییر به آی‌پی ایران» را بزنید."
                    }
            } else {
                "• Exit Country: ${if (isIran) "🇮🇷 Iran (IR)" else "🌍 Foreign (${trace.countryCode})"}\n" +
                    "• Exit IP: ${trace.ip}\n" +
                    "• Cloudflare Colo: ${trace.colo}\n" +
                    "• WARP Status: ${trace.warpStatus}\n\n" +
                    if (isIran) {
                        "💡 Want a non-Iran (Foreign) IP? Tap '🌍 Switch to Foreign IP'."
                    } else {
                        "💡 Your IP is outside Iran (${trace.countryCode}). Want an Iran IP instead? Tap '🇮🇷 Switch to Iran IP'."
                    }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(flagAndTitle)
                .setMessage(msg)
                .setNeutralButton(
                    if (isFarsi) "🧭 تغییر مکان آی‌پی (ایران / خارج)" else "🧭 Change Exit IP (Iran / Foreign)",
                ) { _, _ ->
                    showAetherExitLocationPickerDialog(onUpdated)
                }
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .showCatClientDialog()
        }
    }

    /**
     * Interactive "Add Aether config manually" dialog implementing both:
     * - The complete step-by-step PattNG (`v2.3.13-P69`) tutorial:
     *   2-1 & 2-2) `Get new WARP keys`
     *   2-3) `Remarks`
     *   2-4 & 2-5) `Protocol` = `MASQUE in MASQUE` (`mim`), `MASQUE`, `WireGuard over MASQUE`, `WARP in WARP`, `WireGuard`
     *   2-6 & 2-7) `Transport` = `HTTP/2` (`h2`) or `HTTP/3` (`h3`)
     *   2-8 & 2-9) `Psiphon` = `WARP > Psiphon` (`chain`)
     *   2-10 & 2-11) `Exit country` = 29+ Psiphon countries
     *   2-12) `Scan for an endpoint` (populates both Outer & Inner endpoints)
     *   2-13) Save to main config list & connect
     * - Plus MCI / Irancell firewall presets and 1-tap Auto Wizard.
     */
    // =====================================================================================================
    // beta209: per-config Cat Warp editor: fields, our WARP keys, «Scan for an endpoint», the equivalent
    // command line and a log box with Copy. Opened from «ویرایش» on any subscription holding aether:// links.
    // =====================================================================================================

    private fun subscriptionAetherLines(item: UserSubscription): List<Pair<Int, String>> =
        item.input.lines().mapIndexedNotNull { index, raw ->
            val line = raw.trim()
            if (line.startsWith("aether://", ignoreCase = true) || line.startsWith("masque://", ignoreCase = true)) {
                index to line
            } else {
                null
            }
        }

    private fun aetherLineTitle(line: String): String {
        val name = AetherSelection.fromAetherUri(line)?.profileName?.takeIf(String::isNotBlank)
            ?: android.net.Uri.decode(line.substringAfter('#', "Cat Warp"))
        return CatBranding.rebrandName(name)
    }

    internal fun editSubscriptionOrAetherConfig(item: UserSubscription) {
        val lines = subscriptionAetherLines(item)
        if (lines.isEmpty()) {
            showEditSubscriptionDialog(item)
            return
        }
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val labels = lines.map { (_, line) -> "⚡ " + aetherLineTitle(line) } +
            (if (isFarsi) "📝 ویرایش متن کامل سابسکریپشن (برای حرفه‌ای‌ها)" else "📝 Edit the whole subscription text (advanced)")
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "✏️ کدام کانفیگ را ویرایش کنم؟" else "✏️ Which config do you want to edit?")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= lines.size) {
                    showEditSubscriptionDialog(item)
                } else {
                    showAetherConfigEditor(item, lines[which].first, lines[which].second)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }

    /** beta214: opens the per-config Cat Warp editor (with its endpoint scan) for the config in «⚡ Cat Warp Configs». */
    internal fun openCatWarpEditorForCurrentConfig(autoScan: Boolean) {
        activityScope.launch {
            val list = withContext(Dispatchers.IO) {
                userSubscriptionManager.list().firstOrNull { it.name.equals(CatWarpListPolicy.SUBSCRIPTION_NAME, ignoreCase = true) }
            }
            val firstLine = list?.let { subscriptionAetherLines(it).firstOrNull()?.second }
            val link = firstLine ?: aetherPreferenceStore.read().toAetherUri()
            openCatWarpEditorForLink(list?.id, link, autoScan)
        }
    }

    /**
     * beta214: long-press «🔍 اسکن اندپوینت» / «✏️ ویرایش» on a Cat Warp row. Finds the subscription line behind the
     * row and opens the editor on it; a row of a remote (URL) list is first copied into «⚡ Cat Warp Configs».
     */
    private fun openCatWarpEditorForLink(subscriptionId: String?, shareLink: String?, autoScan: Boolean) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        activityScope.launch {
            runCatching {
                val subs = withContext(Dispatchers.IO) { userSubscriptionManager.list() }
                val ordered = subs.filter { it.id == subscriptionId } + subs.filter { it.id != subscriptionId }
                val hit = ordered.firstNotNullOfOrNull { sub ->
                    CatWarpListPolicy.lineIndexFor(sub.input, shareLink).takeIf { it >= 0 }?.let { sub to it }
                }
                if (hit != null) {
                    showAetherConfigEditor(hit.first, hit.second, hit.first.input.lines()[hit.second].trim(), autoScan)
                    return@runCatching
                }
                val parsed = AetherSelection.fromAetherUri(shareLink.orEmpty())
                if (parsed == null) {
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "این ردیف لینک Cat Warp سالم ندارد." else "This row has no valid Cat Warp link.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@runCatching
                }
                val uri = parsed.toAetherUri()
                val existing = subs.firstOrNull { it.name.equals(CatWarpListPolicy.SUBSCRIPTION_NAME, ignoreCase = true) }
                val saved = withContext(Dispatchers.IO) {
                    if (existing != null) {
                        userSubscriptionManager.update(
                            existing.id,
                            existing.name,
                            CatWarpListPolicy.mergedInput(existing.input, uri, replaceAll = false),
                        )
                    } else {
                        userSubscriptionManager.add(CatWarpListPolicy.SUBSCRIPTION_NAME, uri)
                    }
                }
                renderSubscriptions()
                val idx = CatWarpListPolicy.lineIndexFor(saved.input, uri).coerceAtLeast(0)
                showAetherConfigEditor(saved, idx, saved.input.lines().getOrElse(idx) { uri }.trim(), autoScan)
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, error.message ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** beta214: plain-words answer for a «Local 127.0.0.1:port» row that cannot carry traffic. */
    private fun showDeadLocalRowExplanation(profile: ConnectionProfile) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        activityScope.launch {
            val listening = withContext(Dispatchers.IO) { LoopbackProfileGuard.isListening(profile.port) }
            val msg = if (isFarsi) {
                "این ردیف خودش سرور ندارد؛ فقط می‌گوید «برو به پروکسیِ یک برنامهٔ دیگر روی همین گوشی، 127.0.0.1:${profile.port}».\n\n" +
                    (if (listening) "✅ الان برنامه‌ای روی این پورت گوش می‌دهد، پس تا وقتی آن برنامه باز است کار می‌کند.\n\n"
                    else "❌ الان هیچ برنامه‌ای روی این پورت نیست، پس وصل نمی‌شود (همان خطای «health check at 127.0.0.1:2080»).\n\n") +
                    "اگر Cat Warp می‌خواهید: «⚡ Cat Warp» → «🤖 اسکن و ساخت خودکار» یا «🚀 اتصال مستقیم Cat Warp». این سابسکریپشن را می‌توانید حذف کنید."
            } else {
                "This row has no server of its own; it only says «use another app's proxy on this phone at 127.0.0.1:${profile.port}».\n\n" +
                    (if (listening) "✅ Something is listening on that port right now, so it works while that app runs.\n\n"
                    else "❌ Nothing listens on that port now, so it cannot connect (the «health check at 127.0.0.1:2080» error).\n\n") +
                    "For Cat Warp use «⚡ Cat Warp» → «🤖 Auto-Scan & Build» or «🚀 Connect Cat Warp Now». You can delete this subscription."
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (isFarsi) "ℹ️ چرا این ردیف وصل نمی‌شود؟" else "ℹ️ Why does this row not connect?")
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .showCatClientDialog()
        }
    }

    internal fun saveEditedAetherLine(
        item: UserSubscription,
        lineIndex: Int,
        originalLine: String,
        edited: AetherSelection,
        connect: Boolean,
    ) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val before = AetherSelection.fromAetherUri(originalLine)
        val selection = if (edited.endpoint != null && edited.endpoint != before?.endpoint) {
            edited.copy(endpointScannedAt = System.currentTimeMillis())
        } else {
            edited
        }
        val newLine = selection.toAetherUri()
        activityScope.launch {
            runCatching {
                val fresh = withContext(Dispatchers.IO) { userSubscriptionManager.list().firstOrNull { it.id == item.id } } ?: item
                val lines = fresh.input.lines().toMutableList()
                val at = if (lineIndex in lines.indices && lines[lineIndex].trim() == originalLine) {
                    lineIndex
                } else {
                    lines.indexOfFirst { it.trim() == originalLine }
                }
                if (at >= 0) lines[at] = newLine else lines.add(0, newLine)
                // «Save & connect»: Automatic takes the first Cat Warp row, so the edited one goes first.
                val text = if (connect) {
                    CatWarpListPolicy.moveLineFirst(lines.joinToString("\n"), if (at >= 0) at else 0)
                } else {
                    lines.joinToString("\n")
                }
                val saved = withContext(Dispatchers.IO) {
                    userSubscriptionManager.update(fresh.id, fresh.name, text)
                }
                val active = aetherPreferenceStore.read()
                if (connect || (before != null && active.profileName == before.profileName)) {
                    aetherPreferenceStore.save(selection.copy(enabled = connect || active.enabled))
                }
                if (connect) {
                    userSubscriptionManager.select(saved.id)
                    connectionSelectionPreferenceStore.saveSelectedProfile(saved.id, null, userPick = true)
                }
                renderSubscriptions()
                renderConnectionSelection()
                Toast.makeText(this@MainActivity, if (isFarsi) "✅ ذخیره شد" else "✅ Saved", Toast.LENGTH_SHORT).show()
                // beta214: «ذخیره و اتصال» only saved before — it now really connects.
                if (connect) connectCatWarpAfterSave()
            }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** «کلید WARP ما کجاست؟» — every key the app holds, in plain words, plus a button that makes one. */
    /**
     * beta210: fetches our own WARP WireGuard key in the background as soon as the user has any WARP WireGuard rows,
     * so tapping connect just works (no button to press). Cheap checks first; rate-limited inside WarpRegistration.
     */
    internal fun prewarmWarpKeyInBackground(force: Boolean = false) {
        val appContext = applicationContext
        activityScope.launch(Dispatchers.IO) {
            runCatching {
                if (WarpRegistration.hasUsableKey(appContext)) return@runCatching
                val repository = ConfigRepository(appContext)
                val hasWarpRows = force || userSubscriptionManager.list().any { sub ->
                    val input = sub.input
                    input.contains("auto@", ignoreCase = true) ||
                        input.contains(WarpRegistration.WARP_PEER_PUBLIC_KEY) ||
                        input.contains("bmXOC%2BF1FxEMF9dyiK2H5", ignoreCase = true) ||
                        repository.readCachedMihomoConfigOrNullNow(sub.id)?.rawConfig
                            ?.let(WarpRegistration::containsWarpPeer) == true
                }
                val outcome = WarpRegistration.prewarmIfUseful(appContext, hasWarpRows) ?: return@runCatching
                DiagnosticLogger.info(
                    appContext,
                    "warp.prewarm",
                    "ok=${outcome.account != null} source=${outcome.source}",
                )
            }.onFailure { DiagnosticLogger.warn(appContext, "warp.prewarm.failed", error = it) }
        }
    }

    /** Shown once on the test page when every WireGuard / AmneziaWG row failed: what that means and what to do. */
    internal fun showWireGuardRowsFailedHelp() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        fun t(fa: String, en: String) = if (isFarsi) fa else en
        val body = t(
            "همهٔ ردیف‌های WireGuard / AmneziaWG ناموفق شدند. به زبان ساده:\n\n" +
                "1️⃣ ردیف‌های «Cloudflare edge · WireGuard» همان WARP رایگان هستند و کلیدشان را برنامه خودش می‌سازد. «🔑 کلیدهای WARP» را بزنید و ببینید کلید ✅ دارد یا نه.\n\n" +
                "2️⃣ اگر کلید ✅ است و باز ناموفق است، اپراتور شما WireGuard را بسته (روی همراه اول و ایرانسل زیاد پیش می‌آید). این تقصیر کانفیگ نیست.\n\n" +
                "3️⃣ راهی که معمولاً جواب می‌دهد: Cat Warp با MASQUE روی HTTP/2 (دکمهٔ «⚡ ساخت Cat Warp» همین پایین).\n\n" +
                "4️⃣ کانفیگ Amnezia شخصی (vpn://) را فقط کسی که سرور Amnezia دارد می‌سازد: یا خودتان با برنامهٔ AmneziaVPN روی یک سرور مجازی (VPS) نصب کنید، یا از کسی که سرور دارد بگیرید. بعد ➕ ← «کلید Amnezia».",
            "Every WireGuard / AmneziaWG row failed. In plain words:\n\n" +
                "1️⃣ The «Cloudflare edge · WireGuard» rows are free WARP; the app creates their key itself. Tap «🔑 WARP keys» to see whether the key is ✅.\n\n" +
                "2️⃣ If the key is ✅ and it still fails, your carrier blocks WireGuard (common in Iran). The config is not broken.\n\n" +
                "3️⃣ What usually works: Cat Warp with MASQUE over HTTP/2 (the «⚡ Build Cat Warp» button below).\n\n" +
                "4️⃣ A personal Amnezia config (vpn://) is made by whoever runs an Amnezia server: install one yourself with the AmneziaVPN app on a VPS, or get one from someone who runs a server. Then ➕ → Amnezia key.",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(t("❓ چرا WireGuard وصل نشد؟", "❓ Why did WireGuard fail?"))
            .setMessage(body)
            .setPositiveButton(t("🔑 کلیدهای WARP", "🔑 WARP keys")) { _, _ -> showWarpKeysStatusDialog() }
            .setNeutralButton(t("⚡ ساخت Cat Warp", "⚡ Build Cat Warp")) { _, _ -> showAddAetherDialog() }
            .setNegativeButton(t("بستن", "Close"), null)
            .create()
            .showCatClientDialog()
    }

    internal data class AetherNetworkProbeCandidate(
        val titleFa: String,
        val titleEn: String,
        val baseMode: AetherMode,
        val ipScan: String,
        val endpoint: String,
        val reachable: Boolean,
        val rttMs: Long,
    )

    internal fun probeEndpointReachabilityMs(peer: String, useTcp: Boolean, timeoutMs: Int = 1800): Long? {
        val clean = peer.trim()
        val host: String
        val port: Int
        if (clean.startsWith("[")) {
            val closeIdx = clean.indexOf(']')
            if (closeIdx <= 1 || closeIdx + 2 > clean.length) return null
            host = clean.substring(1, closeIdx)
            port = clean.substring(closeIdx + 2).toIntOrNull() ?: return null
        } else {
            val colonIdx = clean.lastIndexOf(':')
            if (colonIdx <= 0) return null
            host = clean.substring(0, colonIdx)
            port = clean.substring(colonIdx + 1).toIntOrNull() ?: return null
        }
        val t0 = System.nanoTime()
        return runCatching {
            val addr = java.net.InetAddress.getByName(host)
            val sockAddr = java.net.InetSocketAddress(addr, port)
            if (useTcp) {
                java.net.Socket().use { sock ->
                    sock.connect(sockAddr, timeoutMs)
                }
            } else {
                java.net.DatagramSocket().use { dgram ->
                    dgram.soTimeout = timeoutMs
                    dgram.connect(sockAddr)
                    // Send a 148-byte WireGuard initiation probe header (type = 1) or wake packet
                    val pkt = ByteArray(32).also { it[0] = 1 }
                    dgram.send(java.net.DatagramPacket(pkt, pkt.size))
                }
            }
            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }

    internal fun runMidStreamFreezeDetectorDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ مرحله ۱/۲: در حال تست جریان داده پیوسته (32KB) برای تشخیص قطع نیمه‌کاره صفحه…"
            else "⏳ Step 1/2: Probing continuous 32KB stream for mid-stream DPI freeze…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val verdict = withContext(Dispatchers.IO) {
                WhiteDnsSmartScanner.probeMidStreamFreezeAndThroughput(targetBytes = 32_768, timeoutMs = 3_500)
            }
            val msg = if (isFarsi) verdict.summaryFa else verdict.summaryEn
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🩺 نتیجه تشخیص اختلال و قطع نیمه‌کاره (Mid-Stream Check)"
                    else "🩺 Mid-Stream Freeze & Throttling Diagnosis",
                )
                .setMessage(msg)
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(
                    if (isFarsi) "🧭 تشخیص و اعمال پروفایل ضد فایروال منطقه"
                    else "🧭 Auto-Apply Regional Anti-Freeze Profile",
                ) { _, _ ->
                    runRegionalFirewallFingerprinterDialog()
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun showDomainFrontingAndUnpoisonResolverDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val input = EditText(this).apply {
            hint = "youtube.com / instagram.com / chatgpt.com / telegram.org"
            setText("youtube.com")
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🛡️ دامین فرانتینگ + پیدا کردن آدرس واقعی سایت فیلترشده"
                else "🛡️ Domain Fronting & Unpoisoned Real IP Finder",
            )
            .setMessage(
                if (isFarsi) "نام دامنه فیلترشده را وارد کنید تا مسمومیت DNS اپراتور (10.10.34.x) دور زده شود، آی‌پی واقعی و سالم پیدا شود و تنظیم Domain Fronting روی خط شما اعمال گردد:"
                else "Enter a filtered domain to bypass ISP DNS poisoning (10.10.34.x), discover its real reachable IP, and apply Domain Fronting:",
            )
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (isFarsi) "🔍 کشف آی‌پی واقعی و تست" else "🔍 Find Real IP & Test") { _, _ ->
                val targetDomain = input.text?.toString()?.trim().orEmpty().ifBlank { "youtube.com" }
                Toast.makeText(
                    this,
                    if (isFarsi) "⏳ در حال رمزگشایی DNS بدون فیلتر و تست مسیر فرانتینگ برای $targetDomain…"
                    else "⏳ Resolving unpoisoned IPs and testing fronting path for $targetDomain…",
                    Toast.LENGTH_SHORT,
                ).show()
                activityScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        WhiteDnsSmartScanner.resolveUnpoisonedRealIp(targetDomain)
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(if (isFarsi) "✅ نتیجه کشف آدرس واقعی و فرانتینگ" else "✅ Unpoisoned IP & Fronting Result")
                        .setMessage(if (isFarsi) res.summaryFa else res.summaryEn)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(if (isFarsi) "📋 کپی آی‌پی‌های واقعی" else "📋 Copy Real IPs") { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("unpoison-ips", res.cleanIps.joinToString("\n")))
                            Toast.makeText(this@MainActivity, R.string.free_copied, Toast.LENGTH_SHORT).show()
                        }
                        .setPositiveButton(if (isFarsi) "⚡ اعمال آی‌پی و SNI فرانتینگ" else "⚡ Apply Clean IP & Front SNI") { _, _ ->
                            // beta205: only the IP whose :443 actually answered on this line (no untested
                            // fallback IP), IPv6 bracketed so the saved endpoint stays parseable.
                            val bestIp = res.workingCleanIp
                            if (bestIp == null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    if (isFarsi) "⚠️ هیچ‌کدام از آی‌پی‌های واقعی روی پورت ۴۴۳ خط شما جواب نداد؛ چیزی اعمال نشد."
                                    else "⚠️ None of the real IPs answered on port 443 on your line — nothing was applied.",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@setPositiveButton
                            }
                            val endpoint = if (bestIp.contains(':')) "[${bestIp.trim('[', ']')}]:443" else "$bestIp:443"
                            val prev = frontingIpPreferenceStore.readFrontingIp()
                            frontingIps = FrontingIpPolicy.mergeNewestFirst(listOf(endpoint), frontingIps)
                            renderFrontingIpChips()
                            saveFrontingIps(reconnectIfChanged = true, previousValue = prev)
                            applySniOverride(res.recommendedFrontHost)
                            Toast.makeText(
                                this@MainActivity,
                                if (isFarsi) "✅ آدرس $endpoint با فرانت ${res.recommendedFrontHost} اعمال شد"
                                else "✅ Applied $endpoint with front host ${res.recommendedFrontHost}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        .create()
                        .showCatClientDialog()
                }
            }
            .create()
            .showCatClientDialog()
    }

    internal fun showGoogleAppsScriptRelayDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val (usedToday, dailyLimit) = VpnRuntimeStateStore.readGasDailyUsage(this)
        val pct = ((usedToday * 100) / dailyLimit.coerceAtLeast(1)).coerceIn(0, 100)
        val warnText = if (pct >= 80) {
            if (isFarsi) "⚠️ هشدار: نزدیک به سقف سهمیه روزانه گوگل ($pct%)" else "⚠️ Warning: Approaching daily GAS quota ($pct%)"
        } else {
            if (isFarsi) "✅ سهمیه روزانه سالم ($usedToday از $dailyLimit درخواست · $pct%)" else "✅ Daily quota healthy ($usedToday / $dailyLimit · $pct%)"
        }
        val input = EditText(this).apply {
            hint = "https://script.google.com/macros/s/AKfycb.../exec"
            setText(VpnRuntimeStateStore.readGasRelayUrl(this@MainActivity))
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🌐 رله گوگل (Google Apps Script Relay)"
                else "🌐 Google Apps Script (GAS) Relay",
            )
            .setMessage(
                if (isFarsi) "اتصال از راه اسکریپت گوگل شخصی شما روی دامنه باز script.google.com:\n$warnText\n\n۱) با دکمه «📋 کپی کد اسکریپت»، کد آماده را در script.google.com قرار دهید و Deploy کنید.\n۲) لینک Web App (.../exec) را در کادر زیر بگذارید و «🚀 ذخیره و اتصال» را بزنید:"
                else "Connect via your personal Google Apps Script on script.google.com:\n$warnText\n\n1) Copy the ready Code.gs template and deploy as Web App in script.google.com.\n2) Paste your .../exec URL below and tap Save & Connect:",
            )
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "📋 کپی کد اسکریپت گوگل (Code.gs)" else "📋 Copy Code.gs Script") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cat-gas-relay", WhiteDnsSmartScanner.buildGoogleAppsScriptRelaySource()))
                Toast.makeText(
                    this,
                    if (isFarsi) "✅ کد کامل اسکریپت گوگل (Code.gs) کپی شد" else "✅ Copied Google Apps Script (Code.gs)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setPositiveButton(if (isFarsi) "🚀 ذخیره و اتصال با رله گوگل" else "🚀 Save & Connect via GAS Relay") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                VpnRuntimeStateStore.saveGasRelayUrl(this, url)
                VpnRuntimeStateStore.incrementGasDailyUsage(this, 1)
                val gasUri = WhiteDnsSmartScanner.buildGasRelayUri(url, "Google-Apps-Script-Relay")
                activityScope.launch {
                    runCatching {
                        WhiteDnsSmartScanner.parseTunnelUri(gasUri)?.let { parsed ->
                            withContext(Dispatchers.IO) {
                                WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
                            }
                        }
                        val title = "🌐 رله اسکریپت گوگل (GAS Relay)"
                        val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                        val saved = withContext(Dispatchers.IO) {
                            if (existing != null) {
                                userSubscriptionManager.update(existing.id, title, gasUri)
                            } else {
                                userSubscriptionManager.add(title, gasUri)
                            }
                        }
                        userSubscriptionManager.select(saved.id)
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

    internal fun showLiteWorkerGeneratorDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val liteScript = WhiteDnsSmartScanner.buildLiteWorkerScript(
            uuid = java.util.UUID.randomUUID().toString(),
            proxyIp = "103.237.102.191:11111",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🪶 ورکر سبک کلودفلر (Cat Client Lite Worker)"
                else "🪶 Cat Client Lite Worker (Zero-KV Fast Worker)",
            )
            .setMessage(
                if (isFarsi) "ورکر فوق‌سبک (~3KB) بدون مصرف سهمیه KV کلودفلر، مجهز به:\n" +
                    "• کانفیگ آماده متد F&F ایرانسل (188.114.98.0 + tlshello-0-len + semi-python)\n" +
                    "• کانفیگ آماده IPv6 همراه اول (2a06:98c1:3101::6812:202f)\n" +
                    "• رله ProxyIP با پورت صریح (103.237.102.191:11111)\n" +
                    "• شمارشگر و هشدار سهمیه روزانه ۱۰۰ هزار درخواست رایگان (/health)"
                else "Ultra-lightweight (~3KB) zero-KV Cloudflare Worker with built-in Irancell F&F, MCI IPv6, explicit-port ProxyIP, and daily 100k request quota warning (/health).",
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (isFarsi) "📋 کپی کد ورکر لایت" else "📋 Copy Lite Worker Script") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("catclient-lite-worker", liteScript))
                Toast.makeText(
                    this,
                    if (isFarsi) "✅ کد ورکر لایت کلودفلر در کلیپ‌بورد کپی شد"
                    else "✅ Copied Cat Client Lite Worker script to clipboard",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .create()
            .showCatClientDialog()
    }

    internal fun showConnectionResilienceAndKillSwitchDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val checked = booleanArrayOf(
            VpnRuntimeStateStore.isAutoReconnectOnDropEnabled(this),
            VpnRuntimeStateStore.isAutoConnectOnBootOrUpdateEnabled(this),
            VpnRuntimeStateStore.isKillSwitchEnabled(this),
            VpnRuntimeStateStore.isSmartUnspoofedDirectBypassEnabled(this),
        )
        val labels = if (isFarsi) {
            arrayOf(
                "🔄 وصل دوباره اگر اتصال قطع شد (پیش‌فرض: خاموش)",
                "🚀 وصل خودکار بعد از ری‌استارت گوشی یا آپدیت برنامه (پیش‌فرض: خاموش)",
                "🛑 قطع‌کن اضطراری (Kill Switch) برای همه‌ی متودها و کانفیگ‌ها (جلوگیری از لو رفتن IP ایران)",
                "⚡ عبور مستقیم سایت‌های بدون مسمومیت DNS (Smart Direct Bypass — افزایش سرعت و حفظ کوئوتا)",
            )
        } else {
            arrayOf(
                "🔄 Reconnect automatically if dropped (default: off)",
                "🚀 Connect after device reboot or app update (default: off)",
                "🛑 Universal Emergency Kill Switch across all methods & configs",
                "⚡ Smart Unspoofed Direct Bypass (route unpoisoned non-sanctioned sites direct)",
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🔌 تنظیمات وصل خودکار، قطع‌کن اضطراری و مسیریابی هوشمند"
                else "🔌 Auto-Reconnect, Kill Switch & Smart Direct Bypass",
            )
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (isFarsi) "💾 ذخیره تنظیمات" else "💾 Save") { _, _ ->
                VpnRuntimeStateStore.setAutoReconnectOnDropEnabled(this, checked[0])
                VpnRuntimeStateStore.setAutoConnectOnBootOrUpdateEnabled(this, checked[1])
                VpnRuntimeStateStore.setKillSwitchEnabled(this, checked[2])
                VpnRuntimeStateStore.setSmartUnspoofedDirectBypassEnabled(this, checked[3])
                Toast.makeText(
                    this,
                    if (isFarsi) "✅ تنظیمات پایداری، Kill Switch و مسیریابی هوشمند ذخیره شد"
                    else "✅ Saved resilience, Kill Switch & Smart Direct Bypass settings",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .create()
            .showCatClientDialog()
    }

    internal fun runAiAndGeminiUnblockTesterDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال تست باز بودن Gemini، AI Studio، ChatGPT و یوتیوب روی خروجی فعلی…"
            else "⏳ Testing Gemini, AI Studio, ChatGPT & YouTube unblock status on current exit…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val report = withContext(Dispatchers.IO) {
                WhiteDnsSmartScanner.probeAiAndGeminiUnblockSuite()
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "✨ نتیجه تست باز بودن Gemini / AI Studio / ChatGPT"
                    else "✨ Gemini, AI Studio & ChatGPT Unblock Report",
                )
                .setMessage(if (isFarsi) report.summaryFa else report.summaryEn)
                .setNegativeButton(android.R.string.ok, null)
                .setNeutralButton(if (isFarsi) "🧦 بخش اختصاصی ساکس۵" else "🧦 Open SOCKS5 Hub") { _, _ ->
                    showSocks5HubPage()
                }
                .setPositiveButton(
                    if (isFarsi) "🔓 فعال‌سازی WARP ← سایفون (آمریکا US)"
                    else "🔓 Enable WARP -> Psiphon (US)",
                ) { _, _ ->
                    val cur = aetherPreferenceStore.read()
                    aetherPreferenceStore.save(
                        cur.copy(
                            enabled = true,
                            exitLocation = "foreign",
                            chainTarget = "psiphon",
                            psiphonRegion = "US",
                            mode = AetherSelection.simplifyModeForChain(cur.mode, "psiphon"),
                        ),
                    )
                    renderAdvancedControls()
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ زنجیره WARP ← سایفون (آمریکا US) برای باز شدن Gemini و هوش مصنوعی فعال شد"
                        else "✅ Enabled WARP -> Psiphon (US) for Gemini & AI unblock",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun runNetMelliDetectorDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال مقایسه پینگ سرورهای داخل ایران با درگاه‌های بین‌المللی (تشخیص نت ملی)…"
            else "⏳ Comparing domestic Iranian resolvers vs. international edges (Net Melli check)…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val verdict = withContext(Dispatchers.IO) {
                WhiteDnsSmartScanner.probeNetMelliVsDeadConfig()
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🚨 نتیجه تشخیص هوشمند «اینترنت ملی» در برابر «خرابی کانفیگ»"
                    else "🚨 Net Melli vs. Dead Config Diagnostic",
                )
                .setMessage(if (isFarsi) verdict.summaryFa else verdict.summaryEn)
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(
                    if (isFarsi) "📡 باز کردن آزمایشگاه تانل DNS (ضد نت ملی)"
                    else "📡 Open Cat DNS Tunnel Lab",
                ) { _, _ ->
                    showWhiteDnsResolverMethodsDialog()
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun showNineAntiFilterPresetsDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val presets = WhiteDnsSmartScanner.ANTI_FILTER_9_PRESETS
        val titles = presets.map { p ->
            val name = if (isFarsi) p.titleFa else p.titleEn
            "$name · len=${p.fragmentLengths} · delay=${p.fragmentDelaysMs}ms"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🛡️ انتخاب از ۹ پریست ضد فیلترینگ (شامل Max-Stealth، Double-Fragment و Starlink)"
                else "🛡️ Select from 9 Anti-Filtering Presets (Max-Stealth, Double-Fragment & Starlink)",
            )
            .setItems(titles) { _, idx ->
                val p = presets[idx]
                val summary = if (isFarsi) {
                    "✅ پریست «${p.titleFa}»:\n" +
                        "• Fingerprint: ${p.fingerprint}\n" +
                        "• CipherSuites: ${p.cipherSuites.ifEmpty { "(خالی / استاندارد)" }}\n" +
                        "• FinalMask: ${p.finalMask.ifEmpty { "(خالی / استاندارد)" }}\n" +
                        "• Fragment: packets=${p.fragmentPackets}, length=${p.fragmentLengths}, interval=${p.fragmentDelaysMs}ms\n" +
                        "• ALPN: ${p.alpn}\n" +
                        "• اصلاح خودکار باگ REALITY: فعال (تبدیل خودکار tlshello به 1-3 روی REALITY)"
                } else {
                    "✅ Preset '${p.titleEn}':\n" +
                        "• Fingerprint: ${p.fingerprint}\n" +
                        "• CipherSuites: ${p.cipherSuites.ifEmpty { "(default)" }}\n" +
                        "• FinalMask: ${p.finalMask.ifEmpty { "(default)" }}\n" +
                        "• Fragment: packets=${p.fragmentPackets}, length=${p.fragmentLengths}, interval=${p.fragmentDelaysMs}ms\n" +
                        "• ALPN: ${p.alpn}\n" +
                        "• Auto REALITY Fragment Fix: Active (tlshello -> 1-3)"
                }
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cat-antifilter-preset", summary))
                Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }

    internal fun detectLocalLanIpv4Address(): String {
        return runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return@runCatching "192.168.43.1"
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("172.") || host.startsWith("10.")) {
                            return@runCatching host
                        }
                    }
                }
            }
            "192.168.43.1"
        }.getOrDefault("192.168.43.1")
    }

    internal fun connectWhiteDnsTunnelDirectly(bundle: WhiteDnsTunnelBundle) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        activityScope.launch {
            runCatching {
                val uris = listOf(
                    bundle.cottenMaxUri,
                    bundle.masterDnsUri,
                    bundle.stormDnsUri,
                    bundle.soarUri,
                    bundle.vayDnsUri,
                    bundle.noizDnsUri,
                    bundle.slipstreamUri,
                    bundle.dnstHumanUri,
                    bundle.slipNetUri,
                ).filter { it.isNotBlank() }
                uris.forEach { uri ->
                    WhiteDnsSmartScanner.parseTunnelUri(uri)?.let { parsed ->
                        withContext(Dispatchers.IO) {
                            WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
                        }
                    }
                }
                val content = uris.joinToString("\n")
                val title = "🧬 تانل Cat DNS (اتصال مستقیم)"
                val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                val saved = withContext(Dispatchers.IO) {
                    if (existing != null) {
                        userSubscriptionManager.update(existing.id, title, content)
                    } else {
                        userSubscriptionManager.add(title, content)
                    }
                }
                userSubscriptionManager.select(saved.id)
                orbotEnginePreferenceStore.setEnabled(false)
                val curAether = aetherPreferenceStore.read()
                if (curAether.enabled) {
                    aetherPreferenceStore.save(curAether.copy(enabled = false))
                }
                renderSubscriptions()
                onSubscriptionSelected()
                renderAdvancedControls()
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "🚀 ۹ موتور تانل DNS (CottenMax / MasterDNS / StormDNS / Soar / VayDNS / NoizDNS / Slipstream / dnst) فعال شد — در حال اتصال…"
                    else "🚀 9 DNS Tunnel Engines activated in app — connecting…",
                    Toast.LENGTH_LONG,
                ).show()
                openScreen(SCREEN_HOME)
                if (currentVpnServiceState == VpnState.Started) {
                    reconnectForConnectionOptionChange()
                } else {
                    beginConnectFlow(Actions.CONNECT)
                }
            }.onFailure { err ->
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "خطا در راه‌اندازی تانل: ${err.message}" else "Tunnel setup error: ${err.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * SlipNet 6-Point Compatibility Scanner (`NS✓ TXT✓ RND✓ DPI✓ EDNS✓(1232) NXD✓`) +
     * Dual UDP/TCP transport & 8s TCP Pre-flight check.
     */
    internal fun runSlipNetSixPointScoreDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال اجرای اسکن ۶ امتیازی Cat DNS (NS, TXT, RND, DPI, EDNS0, NXDOMAIN + UDP/TCP)…"
            else "⏳ Running Cat DNS 6-point resolver compatibility scan (NS, TXT, RND, DPI, EDNS0, NXDOMAIN + UDP/TCP)…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val cards = withContext(Dispatchers.IO) {
                WhiteDnsSmartScanner.scanResolversWithSlipNetScore(maxCandidates = 6, timeoutMs = 800)
            }
            if (cards.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "⚠️ هیچ ریزولوری در این لحظه پاسخ نداد؛ حالت TCP:53 یا رله داخلی را امتحان کنید."
                    else "⚠️ No resolvers responded; try TCP:53 or a domestic relay.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val msg = buildString {
                if (isFarsi) {
                    appendLine("🎯 گزارش ۶ شاخص سازگاری تانل Cat DNS / MasterDNS / Cat DNS:")
                    appendLine("────────────────────────")
                } else {
                    appendLine("🎯 Cat DNS / MasterDNS 6-Point Resolver Compatibility Report:")
                    appendLine("────────────────────────")
                }
                cards.forEachIndexed { i, c ->
                    val name = if (isFarsi) c.providerNameFa else c.providerNameEn
                    appendLine("${i + 1}. $name (${c.resolverIp}) — ${c.statusLabel}")
                    appendLine("   • ${c.badgeText}")
                    appendLine("   • Transport: ${c.transportBadge} · RTT: ${c.rttMs} ms · MTU: ↑${c.upMtu}B / ↓${c.downMtu}B")
                    appendLine()
                }
            }
            val best = cards.first()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🎯 نتیجه اسکن ۶ امتیازی Cat DNS (بهترین: ${best.resolverIp} — ${best.score}/6)"
                    else "🎯 Cat DNS 6-Point Scan (Best: ${best.resolverIp} — ${best.score}/6)",
                )
                .setMessage(msg.trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(if (isFarsi) "⚙️ جنریتور MasterDNS / dnst://" else "⚙️ MasterDNS / dnst:// Gen") { _, _ ->
                    showMasterDnsAndDnstGeneratorDialog(best.resolverIp)
                }
                .setPositiveButton(if (isFarsi) "🚀 ساخت و اتصال با بهترین ریزولور" else "🚀 Build & Connect Best") { _, _ ->
                    val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
                        resolverIp = best.resolverIp,
                        transport = if (best.udpAlive) "udp" else "tcp",
                        recordMode = if (best.txtOk) WhiteDnsRecordMode.TXT_CNAME else WhiteDnsRecordMode.TXT_AAAA,
                        preset = WhiteDnsSmartScanner.IRAN_AUTO_TUNE_PRESETS.first(),
                    )
                    connectWhiteDnsTunnelDirectly(bundle)
                }
                .create()
                .showCatClientDialog()
        }
    }

    /**
     * Interactive 2026 Multi-VPN Hub & Comparison Dialog:
     * Explains what every major VPN (`MLM v1.2.40–v1.2.42`, `Firefox FENIX`, `Outline`,
     * `AmneziaWG 2.0 / Proton`, `SlipNet / MasterDNS`, `WhiteDNS`, `Defyx / Oblivion`,
     * `PaqetNG (patth / patth ng)`, and `3-Layer Smart Iran Banking Shield`) does in plain language and launches each engine directly.
     */
    internal fun showAllVpns2026ComparisonAndMlmHubDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val catalog = MlmAndNextGenVpnHub.ALL_VPNS_2026_CATALOG
        val items = catalog.map { entry ->
            if (isFarsi) {
                "${entry.titleFa}\n   💡 ${entry.howItWorksFa}\n   ${entry.statusInIranFa}"
            } else {
                "${entry.titleEn}\n   💡 ${entry.howItWorksEn}\n   ${entry.statusInIranEn}"
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🌐 مرکز جامع VPNهای ۲۰۲۶ و قابلیت‌های جدید Cat (هر کدوم رو بزنی همون اجرا میشه)"
                else "🌐 2026 Multi-VPN Hub & Cat Capabilities (Tap Any Engine to Launch)",
            )
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showFirefoxVpnFenixHubDialog()
                    1 -> showOutlineFreeHubDialog()
                    2 -> showAmneziaAndProtonWgHubDialog()
                    3 -> runCloudflareZeroByteAndIpv6HealerDialog()
                    4 -> {
                        showAetherAutoScanAndBuildWizard {
                            renderAdvancedControls()
                        }
                    }
                    5 -> runSlipNetSixPointScoreDialog()
                    6 -> runSmartIranBankingShieldDialog()
                    7 -> showPaqetPatthEngineDialog()
                    8 -> showRecommendedOneClickConnectWizardDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "📦 PaqetNG / patth" else "📦 PaqetNG / patth") { _, _ ->
                showPaqetPatthEngineDialog()
            }
            .setPositiveButton(if (isFarsi) "🚀 اتصال پیشنهادی ۱-کلیکی" else "🚀 1-Click Recommended") { _, _ ->
                showRecommendedOneClickConnectWizardDialog()
            }
            .create()
            .showCatClientDialog()
    }

    /**
     * Outline & Free Shadowsocks Hub (`MLM v1.2.41+` equivalent):
     * - Fetches live `ss://` & `ssconf://` keys from 6 public repositories (with `jsDelivr` mirror),
     * - Probes TCP/handshake latency on the user's current ISP line,
     * - Supports pasting custom `ss://` or dynamic `ssconf://` Outline keys,
     * - Connects in 1 tap to the fastest verified server.
     */
    /** 🔺 Delta-Kronecker public feeds: one tap adds a subscription, fills Aether → Tor bridges, or opens the downloader. */
    internal fun showDeltaKroneckerHubDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, dp(8))
        }
        fun note(text: String, size: Float, color: Int, top: Int) {
            box.addView(
                TextView(this).apply {
                    this.text = text
                    textSize = size
                    setTextColor(color)
                    typeface = CatClientBodyTypeface
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) },
            )
        }
        var dialog: AlertDialog? = null
        note(
            if (isFarsi) "فهرست‌های عمومی Cat Feed که مرتب دوباره تست می‌شوند. برنامه فقط لینکشان را روی گوشی شما می‌خواند (چیزی کپی نمی‌شود). هر دکمه را بزنید همان کار را می‌کند؛ اگر github روی اینترنت شما باز نیست اول یک کانفیگ را وصل کنید."
            else "Cat Feed's public lists, re-tested regularly. The app only reads their links on your phone (nothing is copied). Each button does exactly what it says; if github is closed on your line, connect a config first.",
            13f, TEXT_PRIMARY, 0,
        )
        val sections = listOf(
            DeltaKroneckerFeeds.Kind.SUBSCRIPTION to if (isFarsi) "📥 اشتراک کانفیگ (اضافه می‌شود به فهرست سرورها)" else "📥 Config subscriptions (added to the server list)",
            DeltaKroneckerFeeds.Kind.AETHER to if (isFarsi) "🛡 Cat Warp" else "🛡 Cat Warp",
            DeltaKroneckerFeeds.Kind.DNS_TUNNEL to if (isFarsi) "🧵 تونل DNS (وقتی تقریباً همه‌چیز بسته است)" else "🧵 DNS tunnels (when almost everything is blocked)",
            DeltaKroneckerFeeds.Kind.TOR_BRIDGES to if (isFarsi) "🧅 پل‌های Tor" else "🧅 Tor bridges",
            DeltaKroneckerFeeds.Kind.LINK to if (isFarsi) "🧰 ابزار" else "🧰 Tools",
        )
        for ((kind, header) in sections) {
            note(header, 14f, TEXT_PRIMARY, 14)
            for (feed in DeltaKroneckerFeeds.byKind(kind)) {
                box.addView(
                    MaterialButton(this).apply {
                        text = if (isFarsi) feed.titleFa else feed.titleEn
                        isAllCaps = false
                        setOnClickListener {
                            dialog?.dismiss()
                            openDeltaKroneckerFeed(feed)
                        }
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
                )
                note(if (isFarsi) feed.whatFa else feed.whatEn, 12f, TEXT_SECONDARY, 0)
            }
        }
        val built = MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "🔺 منابع Cat Feed" else "🔺 Cat Feed sources")
            .setView(ScrollView(this).apply { addView(box) })
            .setNeutralButton("GitHub") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DeltaKroneckerFeeds.GITHUB))) } }
            .setPositiveButton(if (isFarsi) "بستن" else "Close", null)
            .create()
        dialog = built
        built.showCatClientDialog()
    }

    private fun openDeltaKroneckerFeed(feed: DeltaKroneckerFeeds.Feed) {
        when (feed.kind) {
            DeltaKroneckerFeeds.Kind.SUBSCRIPTION, DeltaKroneckerFeeds.Kind.AETHER, DeltaKroneckerFeeds.Kind.DNS_TUNNEL -> {
                openScreen(SCREEN_SERVERS)
                showAddSubscriptionDialog(feed.url, feed.subName)
            }
            DeltaKroneckerFeeds.Kind.TOR_BRIDGES -> fetchDeltaKroneckerTorBridges(feed)
            DeltaKroneckerFeeds.Kind.LINK -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(feed.url))) }
        }
    }

    /** Hourly-tested bridges → Aether's Tor «own bridges» (mode own); also copyable for Orbot / Tor Browser. */
    private fun fetchDeltaKroneckerTorBridges(feed: DeltaKroneckerFeeds.Feed) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(this, if (isFarsi) "⏳ گرفتن پل‌های تست‌شده…" else "⏳ Fetching tested bridges…", Toast.LENGTH_SHORT).show()
        activityScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (java.net.URL(feed.url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        setRequestProperty("User-Agent", "Cat Client/${BuildConfig.VERSION_NAME}")
                    }
                    try {
                        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull().orEmpty().ifBlank {
                    LocalSniSpoofProxy.fetchGitHubRawOverSniSplit(feed.url, timeoutMs = 4_000).orEmpty()
                }
            }
            val lines = DeltaKroneckerFeeds.parseBridgeLines(text, 10)
            if (lines.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(if (isFarsi) "❌ پلی گرفته نشد" else "❌ No bridges received")
                    .setMessage(
                        if (isFarsi) "فهرست از GitHub نیامد (احتمالاً raw.githubusercontent.com روی خط شما بسته است). یک کانفیگ را وصل کنید و دوباره بزنید، یا «🧅 پل از Moat» را در بخش Aether امتحان کنید."
                        else "The list did not arrive from GitHub (raw.githubusercontent.com is probably blocked on your line). Connect a config and try again, or try «🧅 Moat bridges» in the Cat Warp section.",
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                    .showCatClientDialog()
                return@launch
            }
            val joined = lines.joinToString("\n")
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle((if (isFarsi) "🧅 " + lines.size + " پل سالم" else "🧅 " + lines.size + " working bridges"))
                .setMessage(
                    (if (isFarsi) "«✅ استفاده در Cat Warp» این پل‌ها را در Cat Warp ← Tor به‌عنوان «پل‌های خودم» می‌گذارد؛ دفعهٔ بعد که Tor (یا زنجیرهٔ warp → tor) را وصل کنید با همین‌ها وصل می‌شود.\n\n"
                    else "«✅ Use in Cat Warp» puts these bridges in Cat Warp → Tor as «my own bridges»; the next time you connect Tor (or the warp → tor chain) it uses them.\n\n") + joined,
                )
                .setPositiveButton(if (isFarsi) "✅ استفاده در Cat Warp" else "✅ Use in Cat Warp") { _, _ ->
                    val cur = aetherPreferenceStore.read()
                    aetherPreferenceStore.save(cur.copy(torBridgesMode = "own", torCustomBridges = joined))
                    renderAdvancedControls()
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ در Cat Warp ← Tor ذخیره شد (پل‌های خودم)" else "✅ Saved in Cat Warp → Tor (own bridges)",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .setNeutralButton(if (isFarsi) "📋 کپی (Orbot / Tor Browser)" else "📋 Copy (Orbot / Tor Browser)") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("tor-bridges", joined))
                    Toast.makeText(this@MainActivity, R.string.free_copied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showCatClientDialog()
        }
    }

    /**
     * AmneziaWG 2.0, Proton VPN WireGuard & Hysteria2 Hub (`MLM v1.2.39–v1.2.41` equivalent):
     * - Connects with anti-DPI AmneziaWG 2.0 (`Jc=5, Jmin=50, Jmax=1000, S1, S2` + UDP noise) & Hysteria2,
     * - Converts any standard Proton VPN / WireGuard `.conf` file into an anti-DPI `awg://` profile.
     */
    // ---------------------------------------------------------------------------------------------------------
    // 🧭 Feature finder: "what do you want to do?" → one plain sentence per tool → opens it directly.
    // The app has dozens of tools spread over six tabs; this is the single place to find any of them.
    // ---------------------------------------------------------------------------------------------------------

    internal class FinderEntry(
        val titleFa: String,
        val titleEn: String,
        val whatFa: String,
        val whatEn: String,
        val keywords: String,
        val open: () -> Unit,
    )

    internal fun normalizeFinderText(text: String): String = text.lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace("\u200c", "").replace("آ", "ا")

    /** Compact bar placed under each tab's title: opens the finder. */
    internal fun featureFinderBar(): View {
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
                TextView(this@MainActivity).apply {
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

    // ---------------------------------------------------------------------------------------------------------
    // 🔐 Amnezia section: everything for AmneziaVPN keys (vpn://) and AmneziaWG / WireGuard .conf in one place.
    // ---------------------------------------------------------------------------------------------------------

    private val amneziaSubscriptionPrefix = "🔐 Amnezia"

    internal class AmneziaPreview(val links: Int, val server: String, val ok: List<String>, val bad: List<String>, val problem: String?)

    internal fun showAmneziaSectionDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        fun t(fa: String, en: String) = if (isFarsi) fa else en
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(
            TextView(this).apply {
                text = t(
                    "کلید Amnezia همان متنی است که با vpn:// شروع می‌شود (یا فایل ‎.conf / ‎.vpn). اینجا بدهیدش؛ برنامه خودش می‌خواند، نشان می‌دهد چه چیزی داخلش است و با یک دکمه وصل می‌شود. نصب برنامهٔ Amnezia لازم نیست.",
                    "An Amnezia key is the text starting with vpn:// (or a .conf / .vpn file). Give it here: the app reads it, shows what is inside and connects with one tap. The Amnezia app is not needed.",
                )
                textSize = 13.5f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(0f, 1.15f)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun action(label: String, accent: Boolean = false, run: () -> Unit) {
            box.addView(
                MaterialButton(this).apply {
                    text = label
                    isAllCaps = false
                    textSize = 13.5f
                    typeface = CatClientBodyBoldTypeface
                    minHeight = dp(48)
                    insetTop = 0
                    insetBottom = 0
                    cornerRadius = dp(12)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    if (accent) {
                        backgroundTintList = ColorStateList.valueOf(TEAL)
                        setTextColor(palette.onAccent)
                    } else {
                        backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 220))
                        strokeWidth = dp(1)
                        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 140))
                        setTextColor(TEXT_PRIMARY)
                    }
                    setOnClickListener {
                        dialog?.dismiss()
                        run()
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )
        }
        action(t("📋 کلید را کپی کرده‌ام → بخوان و وصل شو", "📋 I copied the key → read it and connect"), accent = true) {
            val clip = readClipboardConfigText().orEmpty()
            if (clip.isBlank()) {
                Toast.makeText(this, t("کلیپ‌بورد خالی است؛ اول کلید vpn:// را کپی کنید.", "Clipboard is empty; copy the vpn:// key first."), Toast.LENGTH_LONG).show()
            } else {
                previewAndImportAmneziaText(clip)
            }
        }
        action(t("✍️ چسباندن یا نوشتن دستی (vpn:// یا متن ‎.conf)", "✍️ Paste or type it (vpn:// or .conf text)")) { showAmneziaPasteDialog() }
        action(t("📂 باز کردن فایل ‎.vpn یا ‎.conf", "📂 Open a .vpn or .conf file")) { appTools.openImportFilePicker() }
        action(t("📋 کانفیگ‌های Amnezia من (وصل شدن / حذف)", "📋 My Amnezia configs (connect / delete)")) { showMyAmneziaConfigsDialog() }
        action(t("🛡️ سرور رایگان AmneziaWG (روی WARP) + تبدیل پروتون/وایرگارد", "🛡️ Free AmneziaWG (over WARP) + convert Proton/WireGuard")) { showAmneziaAndProtonWgHubDialog() }
        action(t("🔑 کلید WARP ردیف‌های رایگان (کجاست؟ ساختن)", "🔑 WARP key of the free rows (where? create)")) { showWarpKeysStatusDialog() }
        action(t("❓ کلید Amnezia را از کجا بیاورم؟ چه چیزهایی کار می‌کند؟", "❓ Where do I get a key? What works?")) { showAmneziaHelpDialog() }
        val scroll = ScrollView(this).apply { addView(box) }
        val built = MaterialAlertDialogBuilder(this)
            .setTitle(t("🔐 Amnezia (AmneziaVPN و AmneziaWG)", "🔐 Amnezia (AmneziaVPN & AmneziaWG)"))
            .setView(scroll)
            .setNegativeButton(t("بستن", "Close"), null)
            .create()
        dialog = built
        built.showCatClientDialog()
    }

    private fun showAmneziaPasteDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val input = EditText(this).apply {
            hint = if (isFarsi) "vpn://… یا [Interface] …" else "vpn://… or [Interface] …"
            textSize = 12.5f
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "✍️ کلید Amnezia یا متن ‎.conf" else "✍️ Amnezia key or .conf text")
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (isFarsi) "بخوان" else "Read") { _, _ ->
                previewAndImportAmneziaText(input.text?.toString().orEmpty())
            }
            .create()
            .showCatClientDialog()
    }

    /** Reads vpn:// keys or .conf text, shows what is inside, then adds (and optionally connects). */
    private fun previewAndImportAmneziaText(rawText: String) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        fun t(fa: String, en: String) = if (isFarsi) fa else en
        val text = rawText.trim()
        if (text.isBlank()) return
        activityScope.launch {
            val preview: AmneziaPreview = withContext(Dispatchers.IO) {
                val keys = AmneziaVpnKey.extractKeys(text)
                if (keys.isNotEmpty()) {
                    val results = keys.mapNotNull { AmneziaVpnKey.decode(it) }
                    AmneziaPreview(
                        links = results.sumOf { it.links.size },
                        server = results.firstOrNull()?.serverName ?: "Amnezia",
                        ok = results.flatMap { it.protocols }.distinct(),
                        bad = results.flatMap { it.unsupported }.distinct(),
                        problem = if (results.isEmpty()) t("کلید خراب یا ناقص است (کامل کپی نشده؟)", "The key is broken or incomplete (copied fully?)")
                        else results.firstNotNullOfOrNull { if (isFarsi) it.problemFa else it.problemEn },
                    )
                } else {
                    val uris = WireGuardConfText.toUris(text)
                    val endpoint = uris.firstOrNull()?.substringAfter('@')?.substringBefore('?').orEmpty()
                    AmneziaPreview(
                        links = uris.size,
                        server = endpoint.ifBlank { "WireGuard" },
                        ok = uris.map { if (it.startsWith("awg://")) "AmneziaWG" else "WireGuard" }.distinct(),
                        bad = emptyList(),
                        problem = if (uris.isEmpty()) t(
                            "این متن نه کلید vpn:// است نه فایل ‎.conf کامل (PrivateKey، PublicKey و Endpoint لازم است).",
                            "This is neither a vpn:// key nor a complete .conf (PrivateKey, PublicKey and Endpoint are needed).",
                        ) else null,
                    )
                }
            }
            val message = buildString {
                appendLine(t("🖥 سرور: ", "🖥 Server: ") + preview.server)
                if (preview.ok.isNotEmpty()) appendLine(t("✅ قابل اجرا: ", "✅ Runs here: ") + preview.ok.joinToString(", ") + " · " + t("${preview.links} کانفیگ", "${preview.links} config(s)"))
                if (preview.bad.isNotEmpty()) appendLine(t("⛔ اینجا اجرا نمی‌شود: ", "⛔ Cannot run here: ") + preview.bad.joinToString(", "))
                if (preview.links == 0 && preview.problem != null) {
                    appendLine()
                    appendLine(preview.problem)
                }
            }.trim()
            val builder = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (preview.links > 0) t("🔐 کلید خوانده شد", "🔐 Key read") else t("⚠️ قابل استفاده نیست", "⚠️ Cannot be used"))
                .setMessage(message)
            if (preview.links > 0) {
                builder
                    .setPositiveButton(t("🚀 افزودن و اتصال", "🚀 Add & connect")) { _, _ -> saveAmneziaSubscription(text, preview.server, connect = true) }
                    .setNeutralButton(t("➕ فقط افزودن", "➕ Add only")) { _, _ -> saveAmneziaSubscription(text, preview.server, connect = false) }
                    .setNegativeButton(android.R.string.cancel, null)
            } else {
                builder.setPositiveButton(t("باشه", "OK"), null)
                    .setNeutralButton(t("❓ راهنما", "❓ Help")) { _, _ -> showAmneziaHelpDialog() }
            }
            builder.create().showCatClientDialog()
        }
    }

    private fun saveAmneziaSubscription(text: String, serverName: String, connect: Boolean) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val name = "$amneziaSubscriptionPrefix · ${serverName.take(40)}"
        activityScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    val existing = userSubscriptionManager.list().firstOrNull { it.name == name }
                    if (existing != null) userSubscriptionManager.update(existing.id, name, text)
                    else userSubscriptionManager.add(name, text)
                }
            }.getOrElse { error ->
                Toast.makeText(
                    this@MainActivity,
                    (if (isFarsi) "افزودن نشد: " else "Could not add: ") + (error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            Toast.makeText(
                this@MainActivity,
                if (isFarsi) "✅ «$name» اضافه شد (${saved.connectionCount} کانفیگ)" else "✅ Added “$name” (${saved.connectionCount} configs)",
                Toast.LENGTH_LONG,
            ).show()
            renderSubscriptions()
            if (connect) selectSubscriptionAndConnect(saved.id)
        }
    }

    /** Selects a subscription and connects (or reconnects onto it), turning off engines that would override it. */
    private fun selectSubscriptionAndConnect(subscriptionId: String) {
        userSubscriptionManager.select(subscriptionId)
        val curAether = aetherPreferenceStore.read()
        if (curAether.enabled) aetherPreferenceStore.save(curAether.copy(enabled = false))
        orbotEnginePreferenceStore.setEnabled(false)
        val wasStarted = buttonModel.state == VpnState.Started
        onSubscriptionSelected() // reconnects by itself when already connected
        renderAdvancedControls()
        openScreen(SCREEN_HOME)
        if (!wasStarted && buttonModel.state != VpnState.Starting) beginConnectFlow(Actions.CONNECT)
    }

    private fun showMyAmneziaConfigsDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        fun t(fa: String, en: String) = if (isFarsi) fa else en
        activityScope.launch {
            val items = withContext(Dispatchers.IO) {
                userSubscriptionManager.list().filter { sub ->
                    sub.name.startsWith(amneziaSubscriptionPrefix) ||
                        AmneziaVpnKey.looksLikeKey(sub.input) ||
                        sub.input.trimStart().startsWith("awg://", ignoreCase = true) ||
                        sub.input.trimStart().startsWith("amneziawg://", ignoreCase = true)
                }
            }
            if (items.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(t("📋 کانفیگ Amnezia ندارید", "📋 No Amnezia configs yet"))
                    .setMessage(t("هنوز کلید Amnezia اضافه نکرده‌اید. کلید vpn:// را کپی کنید و «📋 کلید را کپی کرده‌ام» را بزنید.", "No Amnezia key added yet. Copy a vpn:// key and tap “📋 I copied the key”."))
                    .setPositiveButton(t("باشه", "OK"), null)
                    .create().showCatClientDialog()
                return@launch
            }
            val selectedId = userSubscriptionManager.selectedId()
            val labels = items.map { sub ->
                (if (sub.id == selectedId) "● " else "") + sub.name + " · " + t("${sub.connectionCount} کانفیگ", "${sub.connectionCount} configs")
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(t("📋 کانفیگ‌های Amnezia من", "📋 My Amnezia configs"))
                .setItems(labels) { _, which ->
                    val sub = items[which]
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(sub.name)
                        .setItems(
                            arrayOf(t("🚀 وصل شو با این", "🚀 Connect with this"), t("🗑️ حذف", "🗑️ Delete")),
                        ) { _, action ->
                            when (action) {
                                0 -> selectSubscriptionAndConnect(sub.id)
                                1 -> MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(t("حذف «${sub.name}»؟", "Delete “${sub.name}”?"))
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(t("حذف", "Delete")) { _, _ ->
                                        activityScope.launch {
                                            withContext(Dispatchers.IO) { runCatching { userSubscriptionManager.delete(sub.id) } }
                                            renderSubscriptions()
                                            refreshLocationOptions()
                                        }
                                    }
                                    .create().showCatClientDialog(positiveColor = ERROR)
                            }
                        }
                        .create().showCatClientDialog()
                }
                .setNegativeButton(t("بستن", "Close"), null)
                .create().showCatClientDialog()
        }
    }

    // ---------------------------------------------------------------- beta212: Cat SSTP

    private val catSstpSubscriptionName = "🌏 Cat SSTP"
    private val CAT_SSTP_LIST_LIMIT = 40

    private fun catSstpGuideText(isFarsi: Boolean): String = if (isFarsi) {
        """
        🌏 Cat SSTP چیه؟
        SSTP یه VPN استاندارده (همونی که ویندوز داره) که داخل HTTPS معمولی می‌ره. سرورهای رایگان داوطلبانه VPN Gate (بیشتر ژاپن و کره) با نام کاربری vpn و رمز vpn کار می‌کنن. سایت خود VPN Gate تو ایران فیلتره؛ برای همین فهرست از sstp.wireconf.workers.dev گرفته می‌شه.

        👆 «📥 دریافت سرورها» رو بزنی چی میشه؟
        • فهرست تازه سرورها گرفته می‌شه و یه اشتراک به اسم «🌏 Cat SSTP» ساخته (یا به‌روز) می‌شه.
        • «🚀 دریافت و اتصال» همون کار رو می‌کنه و بعد کل گوشی رو وصل می‌کنه.
        • اگه سرور اول جواب نده، برنامه خودش تا ۶ سرور بعدی رو امتحان می‌کنه.

        👆 بعدش چی؟
        • تو صفحه سرورها یکی از ردیف‌های «🐱 Cat · Japan · SSTP …» رو انتخاب کن و دکمه اتصال رو بزن.
        • «تست پینگ» یه دست‌دادن کامل SSTP انجام می‌ده (تا گرفتن آی‌پی). عددش از پینگ معمولی بزرگ‌تره ولی واقعیه؛ ردیفی که جواب نده یعنی الان روی خط شما کار نمی‌کنه.
        • عدد Mbps سرعتیه که خود سرور اعلام کرده، نه سرعت خط شما.
        • این سرورها هر روز عوض می‌شن؛ اگه بیشترشون جواب ندادن دوباره «📥 دریافت سرورها» رو بزن.

        ⚠️ صادقانه:
        • گواهی این سرورها خودامضاست و بررسی نمی‌شه؛ صاحب سرور داوطلبه. برای کارهای حساس فقط سایت‌های https (قفل‌دار) رو باز کن.
        • فقط IPv4 از تونل رد می‌شه؛ IPv6 بسته می‌شه که نشت نکنه.
        • روی بعضی خطوط SSTP شناسایی و بسته می‌شه؛ اون وقت یه روش دیگه (Cat Warp یا Cat Fox VPN) رو امتحان کن.
        • لینک دستی هم قبول می‌شه: sstp://vpn:vpn@host:port یا فقط اسم هاست مثل vpn123.opengw.net:1270
        """.trimIndent()
    } else {
        """
        🌏 What is Cat SSTP?
        SSTP is a standard VPN (the one built into Windows) carried inside ordinary HTTPS. The free volunteer VPN Gate servers (mostly Japan/Korea) accept user vpn / password vpn. The VPN Gate site is filtered in Iran, so the list comes from sstp.wireconf.workers.dev.

        👆 Tap “📥 Get servers”:
        • A fresh server list is downloaded into a subscription named “🌏 Cat SSTP” (created or updated).
        • “🚀 Get & connect” does the same and then connects the whole phone.
        • If the first server does not answer, the app tries up to 6 more by itself.

        👆 Then:
        • On the servers screen pick a “🐱 Cat · Japan · SSTP …” row and tap connect.
        • The ping test runs a full SSTP handshake (until an IP is assigned) — bigger than a normal ping but real; no answer means it does not work on your line right now.
        • The Mbps figure is the server's own advertised speed, not your line.
        • These servers change daily; if most fail, tap “📥 Get servers” again.

        ⚠️ Honestly:
        • Their certificates are self-signed and not verified; the operators are volunteers. Use only https sites for anything sensitive.
        • Only IPv4 goes through the tunnel; IPv6 is blocked so it cannot leak.
        • Some networks detect and block SSTP; then try another method (Cat Warp or Cat Fox VPN).
        • Manual links work too: sstp://vpn:vpn@host:port, or just a host such as vpn123.opengw.net:1270
        """.trimIndent()
    }

    internal fun showCatSstpDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        fun t(fa: String, en: String) = if (isFarsi) fa else en
        val sortLabels = arrayOf(
            t("⭐ ترتیب پیشنهادی فهرست", "⭐ List's recommended order"),
            t("⚡ پرسرعت‌ترین (سرعت اعلامی سرور)", "⚡ Fastest (server's advertised speed)"),
            t("🕐 پایدارترین (بیشترین روز روشن بودن)", "🕐 Most stable (longest uptime)"),
            t("🆕 تازه‌ترین سرورها", "🆕 Newest servers"),
        )
        var sortIndex = 0
        val message = TextView(this).apply {
            text = catSstpGuideText(isFarsi)
            textSize = 13f
            setTextColor(TEXT_PRIMARY)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(22), dp(8), dp(22), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        val sortGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
            sortLabels.forEachIndexed { i, label ->
                addView(
                    android.widget.RadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = label
                        textSize = 13.5f
                        minHeight = dp(44)
                        setTextColor(TEXT_PRIMARY)
                        isChecked = i == 0
                        setOnCheckedChangeListener { _, checked -> if (checked) sortIndex = i }
                    },
                )
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(message)
            addView(
                TextView(this@MainActivity).apply {
                    text = t("ترتیب فهرست:", "Order:")
                    textSize = 13.5f
                    typeface = CatClientBodyBoldTypeface
                    setTextColor(TEAL)
                    setPadding(dp(22), dp(8), dp(22), 0)
                },
            )
            addView(sortGroup)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(t("🌏 Cat SSTP — سرورهای رایگان SSTP", "🌏 Cat SSTP — free SSTP servers"))
            .setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton(t("🚀 دریافت و اتصال", "🚀 Get & connect")) { _, _ ->
                fetchAndSaveCatSstpServers(SstpServerList.SORTS[sortIndex], connect = true)
            }
            .setNeutralButton(t("📥 فقط دریافت سرورها", "📥 Get servers only")) { _, _ ->
                fetchAndSaveCatSstpServers(SstpServerList.SORTS[sortIndex], connect = false)
            }
            .setNegativeButton(t("✍️ لینک دستی", "✍️ Manual link")) { _, _ -> showManualCatSstpDialog() }
            .create()
            .showCatClientDialog()
    }

    private fun fetchAndSaveCatSstpServers(sort: String, connect: Boolean) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال گرفتن فهرست تازه سرورهای SSTP…" else "⏳ Downloading a fresh SSTP server list…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val servers = runCatching { withContext(Dispatchers.IO) { SstpServerList.fetch(sort, CAT_SSTP_LIST_LIMIT) } }
                .getOrElse { error ->
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(if (isFarsi) "⚠️ فهرست سرورها گرفته نشد" else "⚠️ Could not get the server list")
                        .setMessage(
                            (if (isFarsi) {
                                "دلیل: ${error.message ?: error.javaClass.simpleName}\n\nاگه sstp.wireconf.workers.dev روی خط شما باز نمی‌شه، اول با یه روش دیگه وصل شو و دوباره بزن، یا لینک/هاست سرور رو دستی وارد کن."
                            } else {
                                "Reason: ${error.message ?: error.javaClass.simpleName}\n\nIf sstp.wireconf.workers.dev does not open on your line, connect another way first and retry, or enter a server link/host manually."
                            }),
                        )
                        .setPositiveButton(if (isFarsi) "✍️ لینک دستی" else "✍️ Manual link") { _, _ -> showManualCatSstpDialog() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                        .showCatClientDialog()
                    return@launch
                }
            saveCatSstpSubscription(SstpServerList.subscriptionText(servers), connect)
        }
    }

    private fun showManualCatSstpDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val input = EditText(this).apply {
            hint = "sstp://vpn:vpn@public-vpn-79.opengw.net:443"
            minLines = 3
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(
                TextView(this@MainActivity).apply {
                    text = if (isFarsi) {
                        "هر خط یک سرور: sstp://user:pass@host:port یا فقط هاست VPN Gate مثل vpn123.opengw.net:1270 (نام کاربری و رمز پیش‌فرض vpn). پورت دقیقاً همونی که می‌نویسی استفاده می‌شه."
                    } else {
                        "One server per line: sstp://user:pass@host:port, or just a VPN Gate host such as vpn123.opengw.net:1270 (default user/password vpn). The port is used exactly as written."
                    }
                    textSize = 12.5f
                    setTextColor(TEXT_SECONDARY)
                },
            )
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "✍️ افزودن سرور SSTP" else "✍️ Add SSTP server")
            .setView(wrap)
            .setPositiveButton(if (isFarsi) "🚀 افزودن و اتصال" else "🚀 Add & connect") { _, _ ->
                val links = input.text.toString().lines().mapNotNull { SstpLink.parse(it) }
                if (links.isEmpty()) {
                    Toast.makeText(
                        this,
                        if (isFarsi) "⚠️ هیچ لینک یا هاست SSTP درستی پیدا نشد" else "⚠️ No valid SSTP link or host found",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    saveCatSstpSubscription(links.joinToString("\n") { it.toUri() }, connect = true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }

    private fun saveCatSstpSubscription(text: String, connect: Boolean) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val name = catSstpSubscriptionName
        activityScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    val existing = userSubscriptionManager.list().firstOrNull { it.name == name }
                    if (existing != null) userSubscriptionManager.update(existing.id, name, text)
                    else userSubscriptionManager.add(name, text)
                }
            }.getOrElse { error ->
                Toast.makeText(
                    this@MainActivity,
                    (if (isFarsi) "افزودن نشد: " else "Could not add: ") + (error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            Toast.makeText(
                this@MainActivity,
                if (isFarsi) "✅ «$name» آماده شد (${saved.connectionCount} سرور)" else "✅ “$name” ready (${saved.connectionCount} servers)",
                Toast.LENGTH_LONG,
            ).show()
            renderSubscriptions()
            if (connect) selectSubscriptionAndConnect(saved.id) else userSubscriptionManager.select(saved.id)
        }
    }

    private fun showAmneziaHelpDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val body = if (isFarsi) {
            """
            🔑 کلید Amnezia چیست؟
            متنی که با vpn:// شروع می‌شود، یا فایل ‎.conf / ‎.vpn. همهٔ اطلاعات اتصال داخل همین متن است.

            📥 از کجا بیاورم؟
            • اگر سرور Amnezia دارید: در برنامهٔ Amnezia → «اشتراک‌گذاری» → سرور و پروتکل را انتخاب کنید → «فرمت AmneziaVPN» (vpn://) یا «فرمت AmneziaWG» (فایل ‎.conf) → کپی یا ذخیره.
            • فروشنده‌ها و ربات‌هایی که «کانفیگ Amnezia» می‌فروشند همین vpn:// یا فایل ‎.conf را می‌دهند.

            ✅ چه چیزهایی اینجا کار می‌کند؟
            AmneziaWG (نسخهٔ ۱، ۱.۵ و ۲)، WireGuard، XRay (VLESS / Reality) و Shadowsocks.

            ⛔ چه چیزهایی کار نمی‌کند؟
            • OpenVPN، Cloak و IKEv2 داخل کلید (برنامه آن‌ها را نشان می‌دهد ولی اجرا نمی‌کند).
            • کلیدهای «Amnezia Premium» و «Amnezia Free»: فقط توکن حساب دارند و سرورشان را خودِ برنامهٔ Amnezia می‌گیرد.
            • کلید «دسترسی کامل مدیر» (SSH): کانفیگ کاربر نیست؛ در برنامهٔ Amnezia یک کاربر بسازید و آن را بفرستید.

            🔒 امنیت: کلید شما فقط روی همین گوشی ذخیره می‌شود و برای کسی فرستاده نمی‌شود.
            """.trimIndent()
        } else {
            """
            🔑 What is an Amnezia key?
            Text starting with vpn://, or a .conf / .vpn file. Everything needed to connect is inside it.

            📥 Where do I get one?
            • Your own Amnezia server: Amnezia app → Share → pick server and protocol → “AmneziaVPN format” (vpn://) or “AmneziaWG format” (.conf) → copy or save.
            • Sellers and bots that sell “Amnezia configs” give exactly this vpn:// text or a .conf file.

            ✅ What works here?
            AmneziaWG (v1, 1.5 and 2), WireGuard, XRay (VLESS / Reality) and Shadowsocks.

            ⛔ What does not?
            • OpenVPN, Cloak and IKEv2 inside a key (shown, but not run).
            • “Amnezia Premium” / “Amnezia Free” keys: they only hold an account token; only the Amnezia app can get their servers.
            • Full-access admin keys (SSH): not a user config; create a user in the Amnezia app and share that.

            🔒 Privacy: the key is stored only on this phone and is not sent anywhere.
            """.trimIndent()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "❓ راهنمای Amnezia" else "❓ Amnezia guide")
            .setMessage(body)
            .setPositiveButton(if (isFarsi) "فهمیدم" else "Got it", null)
            .create()
            .showCatClientDialog()
    }

    /**
     * 1-Tap 3-Layer Smart Iran Banking & Domestic App Shield (`MLM v1.2.41` equivalent):
     * Detects all installed Iranian banking, payment, taxi, messenger, and `.ir` apps and sets
     * `SplitTunnelMode.BypassSelected` so Iranian banks never complain that a VPN is active.
     */
    internal fun runSmartIranBankingShieldDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        activityScope.launch {
            val (totalCount, detectedApps) = withContext(Dispatchers.IO) {
                MlmAndNextGenVpnHub.enableSmartIranBankingAppShield(this@MainActivity)
            }
            val appNamesPreview = detectedApps.take(12).joinToString("، ") { it.label }
                .ifBlank { if (isFarsi) "بانک‌ها، اسنپ، تپسی، دیوار، بله، روبیکا، بازار، مایکت" else "Iranian banking & domestic apps" }
            val msg = if (isFarsi) {
                "✅ سپر هوشمند برنامه‌های بانکی و ایرانی فعال شد!\n\n" +
                    "• تعداد کل بسته‌های مستثناشده از تونل VPN: $totalCount برنامه\n" +
                    "• برنامه‌های شناسایی‌شده روی گوشی شما: $appNamesPreview\n\n" +
                    "از این لحظه وقتی VPN روشن است، همراه بانک‌ها، اسنپ، دیوار، بله، روبیکا، بازار و مایکت کاملاً مستقیم و با اینترنت ایران کار می‌کنند و هرگز خطای «فیلترشکن خود را خاموش کنید» نمی‌دهند."
            } else {
                "✅ 3-Layer Smart Iran Banking & Domestic App Shield enabled!\n\n" +
                    "• Total excluded packages: $totalCount apps\n" +
                    "• Detected on your device: $appNamesPreview\n\n" +
                    "Your Iranian banking, payment, and domestic apps now bypass the VPN tunnel completely."
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🏦 سپر هوشمند همراه بانک‌ها و برنامه‌های ایرانی"
                    else "🏦 Smart Iran Banking & Domestic App Shield",
                )
                .setMessage(msg)
                .setNeutralButton(
                    if (isFarsi) "🚀 مرکز ۴ قابلیت طلایی" else "🚀 4 Power Features",
                ) { _, _ ->
                    showFourPowerFeaturesCenterDialog()
                }
                .setPositiveButton(android.R.string.ok, null)
                .create()
                .showCatClientDialog()
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            }
        }
    }

    /**
     * ⚡ Multi-Config Parallel Speed Fusion & Zero-Drop Autopilot Dialog:
     * Combines the user's heterogeneous configs (SlipNet, VLESS, Trojan, PaqetNG, Firefox VPN,
     * CottenMax, TUIC, WireGuard) into a single Mihomo YAML subscription featuring simultaneous
     * Round-Robin Load-Balancing, 15s Zero-Drop Failover, and Fastest URL-Test.
     */
    private fun isFusedSubscriptionName(name: String): Boolean =
        name.startsWith("⚡ تانل ترکیبی") || name.startsWith("⚡ Fused") || name.startsWith("📡 تانل سبز") || name.startsWith("📡 Green")

    /** Raw texts of every user subscription (inline links + cached YAML snapshots) and, optionally, the clipboard. */
    /** Clipboard text that looks small enough to be configs (UI thread). */
    internal fun readClipboardConfigText(): String? = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            .takeIf { it.isNotBlank() && it.length < UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES }
    }.getOrNull()

    /** Blocking (reads + parses every cached subscription): call on Dispatchers.IO. */
    internal fun collectFusionRawTexts(clipboardText: String?): List<String> {
        val rawTexts = mutableListOf<String>()
        userSubscriptionManager.list().forEach { sub ->
            if (isFusedSubscriptionName(sub.name)) return@forEach
            if (sub.input.isNotBlank() && !sub.input.startsWith("http://", true) && !sub.input.startsWith("https://", true)) {
                rawTexts.add(sub.input)
            }
            userSubscriptionManager.cachedSnapshot(sub.id)?.rawConfig?.takeIf { it.isNotBlank() }?.let { rawTexts.add(it) }
        }
        clipboardText?.takeIf { it.isNotBlank() }?.let { rawTexts.add(0, it) }
        return rawTexts
    }

    internal fun showMultiConfigSpeedFusionDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val clipText = readClipboardConfigText()

        activityScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                val rawTexts = collectFusionRawTexts(clipText)
                LocalSniSpoofProxy.buildFusedMultiTunnelSubscription(rawTexts, maxProxies = 12)
            }
            if (bundle == null || bundle.totalProxiesFused == 0) {
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "⚠️ ابتدا حداقل یک اشتراک یا کانفیگ (در اپ یا کلیپ‌بورد) داشته باشید."
                    else "⚠️ Please add at least one subscription or copy a config first.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val msg = if (isFarsi) {
                buildString {
                    appendLine("⚡ ترکیب موازی چند کانفیگ (Multi-Tunnel Speed Fusion) آماده شد!")
                    appendLine("• تعداد کانفیگ‌های ادغام‌شده: ${bundle.totalProxiesFused} کانفیگ")
                    appendLine("• ترکیب پروتکل‌ها: ${bundle.protocolSummary}")
                    appendLine()
                    appendLine("🎯 با فعال‌سازی این پروفایل، ۳ موتور هم‌زمان برای شما ساخته می‌شود:")
                    appendLine("۱) ⚡ ترکیب موازی سرعت (Round-Robin Load-Balance): پخش هم‌زمان کانکشن‌ها روی همهٔ تانل‌ها برای حداکثر سرعت دانلود")
                    appendLine("۲) 🤖 خلبان خودکار ضدقطع (Zero-Drop Failover 15s): سوییچ آنی در ۰ میلی‌ثانیه بدون قطع شدن VPN")
                    appendLine("۳) 🚀 سریع‌ترین پینگ زنده (Auto URL-Test)")
                    appendLine()
                    appendLine("📋 کانفیگ‌های داخل تانل ترکیبی:")
                    bundle.proxyNames.take(8).forEachIndexed { idx, name ->
                        appendLine("  ${idx + 1}. $name")
                    }
                }
            } else {
                buildString {
                    appendLine("⚡ Multi-Tunnel Speed Fusion Ready!")
                    appendLine("• Fused Configs: ${bundle.totalProxiesFused}")
                    appendLine("• Protocol Mix: ${bundle.protocolSummary}")
                    appendLine()
                    appendLine("🎯 Includes 3 simultaneous smart groups:")
                    appendLine("1) ⚡ Parallel Load-Balance (Round-Robin)")
                    appendLine("2) 🤖 Zero-Drop Failover Autopilot (15s)")
                    appendLine("3) 🚀 Fastest Auto URL-Test")
                }
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "⚡ ترکیب موازی سرعت + خلبان خودکار ضدقطع"
                    else "⚡ Multi-Config Speed Fusion & Autopilot",
                )
                .setMessage(msg.trimEnd())
                .setPositiveButton(
                    if (isFarsi) "🚀 ساخت تانل ترکیبی و اتصال فوری" else "🚀 Activate Fusion & Connect",
                ) { _, _ ->
                    activateFusedBundle(bundle, isFarsi, greenOnly = false)
                }
                .setNeutralButton(
                    if (isFarsi) "📤 اشتراک با QR / لینک" else "📤 Share (QR / link)",
                ) { _, _ ->
                    showCatFusionShareDialog(bundle, isFarsi)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showCatClientDialog()
        }
    }

    /** Saves [bundle] as the single fused subscription, selects it and connects (or reconnects). */
    internal fun activateFusedBundle(
        bundle: LocalSniSpoofProxy.FusedMultiTunnelBundle,
        isFarsi: Boolean,
        greenOnly: Boolean,
    ) {
        activityScope.launch {
            val subTitle = when {
                greenOnly && isFarsi -> "📡 تانل سبز ترکیبی (${bundle.totalProxiesFused} کانفیگ تست‌شده)"
                greenOnly -> "📡 Green fused tunnel (${bundle.totalProxiesFused} verified)"
                isFarsi -> "⚡ تانل ترکیبی موازی (${bundle.totalProxiesFused} کانفیگ)"
                else -> "⚡ Fused Multi-Tunnel (${bundle.totalProxiesFused} nodes)"
            }
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    val existing = userSubscriptionManager.list().firstOrNull { isFusedSubscriptionName(it.name) }
                    if (existing != null) userSubscriptionManager.update(existing.id, subTitle, bundle.yaml)
                    else userSubscriptionManager.add(subTitle, bundle.yaml)
                }
            }.getOrElse { err ->
                Toast.makeText(
                    this@MainActivity,
                    (if (isFarsi) "⚠️ ذخیرهٔ تانل ترکیبی ناموفق بود: " else "⚠️ Could not save fused tunnel: ") + (err.message ?: err.javaClass.simpleName),
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
            openScreen(SCREEN_HOME)
            if (currentVpnServiceState == VpnState.Started) {
                reconnectForConnectionOptionChange()
            } else {
                beginConnectFlow(Actions.CONNECT)
            }
        }
    }

    /**
     * 📤 Exports a fused multi-tunnel as ONE `catfusion://` link + QR code. Scanning the QR with
     * Cat Client on another phone (or pasting the link into «➕ افزودن سابسکریپشن») rebuilds the
     * same 3-group fused profile there.
     */
    private fun showCatFusionShareDialog(bundle: LocalSniSpoofProxy.FusedMultiTunnelBundle, isFarsi: Boolean) {
        val title = if (isFarsi) "تانل ترکیبی ${bundle.totalProxiesFused} کانفیگ" else "Fused ${bundle.totalProxiesFused} configs"
        val link = CatFusionLink.encode(bundle.shareUris, title)
        if (link == null) {
            Toast.makeText(
                this,
                if (isFarsi) "⚠️ هیچ‌کدام از کانفیگ‌های این تانل لینک اشتراکی قابل‌انتقال ندارند."
                else "⚠️ None of the fused configs has a shareable link.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val skipped = bundle.totalProxiesFused - bundle.shareUris.size.coerceAtMost(CatFusionLink.MAX_LINKS)
        val qr = QrCodes.bitmap(link, sizePx = dp(280))
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        if (qr != null) {
            container.addView(
                ImageView(this).apply {
                    adjustViewBounds = true
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundColor(Color.WHITE)
                    setImageBitmap(qr)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        container.addView(
            TextView(this).apply {
                textSize = 12f
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(8), 0, 0)
                text = buildString {
                    if (isFarsi) {
                        appendLine("📱 روی گوشی دیگر: Cat Client ← «اسکن QR» یا کپی لینک ← «➕ افزودن سابسکریپشن».")
                        appendLine("همان تانل ترکیبی (موازی + ضدقطع + سریع‌ترین) آنجا ساخته می‌شود.")
                        if (qr == null) appendLine("⚠️ لینک برای QR خیلی بزرگ است؛ از دکمهٔ کپی/اشتراک استفاده کن.")
                        if (skipped > 0) appendLine("ℹ️ $skipped کانفیگ (مثل TUIC/SSH/WireGuard داخل YAML) لینک استاندارد ندارند و منتقل نمی‌شوند.")
                        append("🔒 این لینک شامل رمز/UUID کانفیگ‌های شماست؛ فقط با افراد مورد اعتماد به اشتراک بگذارید.")
                    } else {
                        appendLine("📱 On another phone: Cat Client → Scan QR, or paste the link into “Add subscription”.")
                        if (qr == null) appendLine("⚠️ Link is too large for a QR code — use copy/share.")
                        if (skipped > 0) appendLine("ℹ️ $skipped configs have no standard share link and are not included.")
                        append("🔒 The link contains your config secrets — share only with people you trust.")
                    }
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(if (isFarsi) "📤 اشتراک تانل ترکیبی (catfusion://)" else "📤 Share fused tunnel (catfusion://)")
            .setView(container)
            .setPositiveButton(if (isFarsi) "📋 کپی لینک" else "📋 Copy link") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("catfusion", link))
                Toast.makeText(this, R.string.free_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(if (isFarsi) "↗️ ارسال" else "↗️ Send") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, link)
                }
                runCatching { startActivity(Intent.createChooser(send, title)) }
                    .onFailure { Toast.makeText(this, R.string.subscription_share_failed, Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }

    internal fun runDirectCdnDomainFrontingV23Dialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال تست زندهٔ ۳ مسیر مستقیم Domain-Fronting v23 (یوتیوب، اینستاگرام/واتس‌اپ و رددیت/فستلی)…"
            else "⏳ Probing 3 direct Domain-Fronting v23 paths (YouTube, Instagram/WhatsApp & Reddit/Fastly)…",
            Toast.LENGTH_SHORT,
        ).show()

        activityScope.launch {
            val hits = withContext(Dispatchers.IO) {
                LocalSniSpoofProxy.probeDirectCdnDomainFrontingV23(timeoutMs = 1_100)
            }
            val msg = buildString {
                if (isFarsi) {
                    appendLine("🌐 نتیجهٔ تست زندهٔ متد جدید Domain-Fronting v23 روی خط شما:")
                    appendLine()
                    hits.forEach { h ->
                        val status = if (h.rttMs != null) "🟢 باز (${h.rttMs}ms) · فورواردر محلی: 127.0.0.1:${h.localForwardPort}" else "🟡 نیازمند برش چندتکه‌ای"
                        appendLine("• ${h.titleFa}")
                        appendLine("   👈 وضعیت: $status | لبه: ${h.edgeIp}:${h.port}")
                        appendLine("   👈 گواهی/SNI: ${h.frontSni} (vcn=${h.verifyNamesCsv.take(42)}…)")
                        appendLine()
                    }
                    append("💡 با زدن دکمهٔ زیر، استراتژی فرانتینگ گواهی (fromMitM + برش چندتکه‌ای SNI) روی تانل شما فعال و متصل می‌شود.")
                } else {
                    appendLine("🌐 Direct CDN Domain-Fronting v23 Live Probe:")
                    appendLine()
                    hits.forEach { h ->
                        val status = if (h.rttMs != null) "🟢 OPEN (${h.rttMs}ms) · Local Forwarder: 127.0.0.1:${h.localForwardPort}" else "🟡 Needs Multi-Chunk Split"
                        appendLine("• ${h.titleEn}: $status")
                    }
                }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🌐 متد جدید MITM-DomainFronting v23"
                    else "🌐 New MITM-DomainFronting v23 Method",
                )
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    if (isFarsi) "🚀 فعال‌سازی فرانتینگ v23 و اتصال"
                    else "🚀 Enable Fronting v23 & Connect",
                ) { _, _ ->
                    activityScope.launch {
                        withContext(Dispatchers.IO) {
                            LocalSniSpoofProxy.startSpoofSocksProxy(
                                strategy = LocalSniSpoofProxy.SpoofStrategy.MITM_PINNED_CERT_FRONTING,
                                decoySni = "github.githubassets.com",
                            )
                        }
                        runOneClickRepairMyRealConfigAndConnect()
                    }
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun showFirefoxCountryPickerDialog(onChanged: () -> Unit = {}) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        // Free (unlocked) locations first; locked ones answer 403 for a free account and fall back to «Recommended».
        val countries = FirefoxVpnEngine.getAvailableCountries(this).let { all ->
            all.filter { !it.locked } + all.filter { it.locked }
        }
        val codes = listOf(FirefoxVpnEngine.COUNTRY_FASTEST) + countries.map { it.code }
        val labels = buildList {
            add(
                if (isFarsi) "⚡ سریع‌ترین کشور خودکار (تست پینگ لحظه‌ای هنگام اتصال)"
                else "⚡ Fastest Country Automatically (Live Ping on Connect)",
            )
            countries.forEach { c ->
                val name = if (isFarsi) c.nameFa else c.nameEn
                val tag = when {
                    !c.locked -> if (isFarsi) "✅ رایگان" else "✅ free"
                    isFarsi -> "🔒 قفل برای حساب رایگان (خودکار میره رو پیشنهادی)"
                    else -> "🔒 locked for free accounts (falls back to Recommended)"
                }
                add("${c.flag} $name · $tag · ${c.exactEndpoint}")
            }
        }.toTypedArray()
        val currentCode = FirefoxVpnEngine.readSelectedCountryCode(this)
        val checkedIdx = codes.indexOfFirst { it.equals(currentCode, ignoreCase = true) }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🌍 انتخاب کشور Cat Fox VPN — ${countries.count { !it.locked }} کشور رایگان از ${countries.size}"
                else "🌍 Cat Fox VPN country — ${countries.count { !it.locked }} free of ${countries.size}",
            )
            .setSingleChoiceItems(labels, checkedIdx) { dlg, idx ->
                dlg.dismiss()
                val chosenCode = codes[idx]
                FirefoxVpnEngine.saveSelectedCountryCode(this, chosenCode)
                onChanged()
                Toast.makeText(
                    this,
                    if (isFarsi) "✅ کشور Cat Fox VPN روی «${labels[idx].substringBefore(" ·")}» تنظیم شد"
                    else "✅ Cat Fox VPN country set to '${labels[idx].substringBefore(" ·")}'",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNeutralButton(if (isFarsi) "🔄 دریافت آخرین لیست سرورهای موزیلا" else "🔄 Sync Mozilla Servers") { _, _ ->
                activityScope.launch {
                    val synced = withContext(Dispatchers.IO) {
                        FirefoxVpnEngine.syncRemoteSettingsCountries(this@MainActivity)
                    }
                    onChanged()
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ لیست ${synced.size} کشور از سرور موزیلا بروزرسانی شد"
                        else "✅ Synced ${synced.size} countries from Mozilla Remote Settings",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }

    internal fun showFirefoxTokenImportDialog(onChanged: () -> Unit = {}) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val labelInput = EditText(this).apply {
            hint = if (isFarsi) "نام یا ایمیل دلخواه (مثلاً اکانت ۱)" else "Label or Email (e.g. Account 1)"
            setSingleLine(true)
        }
        val tokenInput = EditText(this).apply {
            hint = if (isFarsi) "توکن Refresh / Access / ProxyPass JWT (eyJ...) یا JSON" else "Refresh/Access Token, ProxyPass JWT (eyJ...) or JSON"
            minLines = 3
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(labelInput)
            addView(tokenInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🔑 افزودن سریع حساب با توکن یا JSON"
                else "🔑 Quick Add Account via Token or JSON",
            )
            .setMessage(
                if (isFarsi) "اگر توکن حساب فایرفاکس را از مرورگر فایرفاکس کپی کرده‌اید، اینجا قرار دهید تا بلافاصله ۵۰ گیگابایت فعال شود:"
                else "Paste your Firefox OAuth token, ProxyPass JWT (eyJ...), or account JSON:",
            )
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (isFarsi) "💾 ذخیره و فعال‌سازی" else "💾 Save & Activate") { _, _ ->
                val label = labelInput.text?.toString()?.trim().orEmpty()
                val raw = tokenInput.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    Toast.makeText(
                        this,
                        if (isFarsi) "لطفاً توکن را وارد کنید" else "Please paste the token",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                activityScope.launch {
                    val added = withContext(Dispatchers.IO) {
                        FirefoxVpnEngine.addAccountFromTokenOrJson(this@MainActivity, label, raw)
                    }
                    if (added != null) {
                        onChanged()
                        Toast.makeText(
                            this@MainActivity,
                            if (isFarsi) "✅ حساب «${added.email}» اضافه شد (${FirefoxVpnEngine.formatQuotaSummaryFa(added)})"
                            else "✅ Added '${added.email}' (${FirefoxVpnEngine.formatQuotaSummaryEn(added)})",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .create()
            .showCatClientDialog()
    }

    /**
     * Inspects the user's real subscriptions and deployed Worker to find their actual working
     * VLESS/Trojan server (`UUID`, `host`, `port`, `path`) so repair/spoof tools never use
     * fake placeholder UUIDs (`11111111-...`).
     */
    internal suspend fun resolveUserRealConfigTemplate(): LocalSniSpoofProxy.RealConfigTemplate? =
        withContext(Dispatchers.IO) {
            val allSubs = userSubscriptionManager.list()
            val selectedId = userSubscriptionManager.selectedId()
            val orderedSubs = buildList {
                allSubs.firstOrNull { it.id == selectedId }?.let { add(it) }
                allSubs.forEach { if (it.id != selectedId) add(it) }
            }
            val candidateLines = mutableListOf<String>()
            for (sub in orderedSubs) {
                val snap = runCatching { userSubscriptionManager.cachedSnapshot(sub.id) }.getOrNull()
                snap?.catalog?.profiles?.forEach { p ->
                    val link = p.shareLink?.trim().orEmpty()
                    if (link.isNotEmpty()) candidateLines += link
                }
                sub.input.lines().forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("vless://", ignoreCase = true) || t.startsWith("trojan://", ignoreCase = true)) {
                        candidateLines += t
                    }
                }
            }
            val dep = runCatching { PanelDeploymentStore(this@MainActivity).deployments().firstOrNull() }.getOrNull()
            val depHost = dep?.workerUrl
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.substringBefore("/")
                ?.trim()
            LocalSniSpoofProxy.extractRealConfigTemplate(
                rawLines = candidateLines,
                deployedUuid = dep?.uuid,
                deployedWorkerHost = depHost,
            )
        }

    /**
     * Comprehensive 2026 Net Melli Bypass Lab combining:
     * 0) 1-Click Automatic Repair & Connect on User's Real Config
     * 1) Non-Cloudflare & Domestic Whitelist CDN Scanner (`Akamai`, `Fastly`, `Google`, `Azure`, `Arvan`, `Derak`) preserving exact `IP:port`
     * 2) `Fact-A DNS Non-Hijack SNI Oracle`
     * 3) In-App `LocalSniSpoofProxy` (`127.0.0.1` direct TCP forwarder + SOCKS5)
     * 4) The 5 Cloudflare Spoofing Methods applied directly to the user's real config
     * 5) 2026 Hybrid Net Melli Tunnels (`QS-Tunnel`, `QQ-Tunnel`, `Spoof-Tunnel`, `S3 Bypass / Fedarisha`)
     */
    internal fun showNetMelli2026BypassLabDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val proxyRunning = LocalSniSpoofProxy.isRunning()
        val proxyStatus = if (proxyRunning) {
            if (isFarsi) "🟢 روشن (SNI: ${LocalSniSpoofProxy.currentDecoySni})"
            else "🟢 Running (SNI: ${LocalSniSpoofProxy.currentDecoySni})"
        } else {
            if (isFarsi) "⚪ آماده به‌کار"
            else "⚪ Ready"
        }

        val options = if (isFarsi) {
            arrayOf(
                "🪄 ۰) تعمیر خودکار کانفیگ من و اتصال فوری با یک کلیک (پیشنهادی — بدون نیاز به تنظیم فنی!)",
                "🛰️ ۱) اسکن زنده آی‌پی‌های باز غیرکلادفلر (Akamai · Fastly · Google · Arvan) و اتصال روی کانفیگ من",
                "🔎 ۲) کشف خودکار سایت‌های سفید باز روی خط من (Fact-A Oracle) و اعمال روی اتصال",
                "🛡️ ۳) روشن کردن برش بسته TLS / تزریق SNI سفید و اتصال فوری ($proxyStatus)",
                "☁️ ۴) اعمال ۵ روش اسپوفینگ کلادفلر روی کانفیگ واقعی من و اتصال فوری",
                "🚀 ۵) اتصال با ۴ تانل هیبریدی نت ملی ۲۰۲۶ (S3 Bypass · QS-Tunnel · QQ-Tunnel · Spoof-Tunnel)",
            )
        } else {
            arrayOf(
                "🪄 0) 1-Click Auto-Repair My Real Config & Connect Immediately (Recommended!)",
                "🛰️ 1) Live Scan Open Non-Cloudflare CDNs (Akamai · Fastly · Google · Arvan) & Connect My Config",
                "🔎 2) Find Unblocked Whitelist Domains on My ISP (Fact-A Oracle) & Apply",
                "🛡️ 3) Enable Local TLS SNI Split / Fake ClientHello & Connect Now ($proxyStatus)",
                "☁️ 4) Apply the 5 Cloudflare Spoofing Methods to My Real Config & Connect",
                "🚀 5) Connect via 2026 Hybrid Net Melli Tunnels (S3 Bypass · QS · QQ · Spoof)",
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🧪 آزمایشگاه نت ملی و ۵ روش اسپوف کلادفلر (اتصال واقعی)"
                else "🧪 Net Melli & 5 CF Spoof Lab (Real 1-Click Connect)",
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runOneClickRepairMyRealConfigAndConnect()
                    1 -> runNonCloudflareWhitelistScannerDialog()
                    2 -> runFactADnsSniOracleDialog()
                    3 -> showLocalFakeClientHelloProxyDialog()
                    4 -> showCloudflareFiveSpoofMethodsDialog()
                    5 -> generateAndAddHybridNetMelliTunnelsDialog()
                }
            }
            .setNeutralButton(if (isFarsi) "📖 راهنمای ساده" else "📖 Simple Guide") { _, _ ->
                showPlainLanguageFullGuideDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }

    private fun runNonCloudflareWhitelistScannerDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال تست واقعی TCP+TLS روی CDNهای غیرکلادفلر و داخلی (Akamai, Fastly, Google, Azure, Arvan, Derak)…"
            else "⏳ Probing Non-Cloudflare & domestic whitelist CDNs over TCP+TLS…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val results = withContext(Dispatchers.IO) {
                LocalSniSpoofProxy.scanNonCloudflareWhitelistTargets(timeoutMs = 1100)
            }
            val realTemplate = resolveUserRealConfigTemplate()
            val report = buildString {
                if (isFarsi) {
                    appendLine("🛰️ نتیجه تست واقعی بانک وایت‌لیست غیرکلادفلر و داخلی (با حفظ دقیق IP:port):")
                    if (realTemplate != null) {
                        appendLine("✅ کانفیگ واقعی شناسایی‌شده: ${realTemplate.remark} (${realTemplate.hostHeader})")
                    } else {
                        appendLine("💡 کانفیگ شخصی پیدا نشد؛ با زدن دکمه زیر، اتصال بدون سرور روی آی‌پی باز فعال می‌شود.")
                    }
                    appendLine("────────────────────────")
                } else {
                    appendLine("🛰️ Live Non-Cloudflare & Domestic Whitelist Scan (preserving exact IP:port):")
                    appendLine("────────────────────────")
                }
                if (results.isEmpty()) {
                    appendLine(if (isFarsi) "⚠️ هیچ هدفی در این لحظه پاسخ نداد." else "⚠️ No target responded right now.")
                } else {
                    results.forEachIndexed { idx, r ->
                        val t = r.target
                        val status = if (r.tlsVerified) {
                            "🟢 TCP+TLS OK (${r.effectiveRttMs} ms)"
                        } else {
                            "🟡 TCP OPEN (${r.tcpRttMs} ms)"
                        }
                        val title = if (isFarsi) t.provider.titleFa else t.provider.titleEn
                        appendLine("${idx + 1}. $title")
                        appendLine("   • IP:port = ${t.exactEndpoint} · SNI = ${t.sni}")
                        appendLine("   • Status: $status")
                        appendLine()
                    }
                }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🛰️ اسکن وایت‌لیست غیرکلادفلر (${results.size} باز)"
                    else "🛰️ Non-Cloudflare Whitelist Scan (${results.size} Open)",
                )
                .setMessage(report.trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(if (isFarsi) "🦊 اتصال با فایرفاکس Fastly" else "🦊 Connect via Firefox Fastly") { _, _ ->
                    val fastlyHit = results.firstOrNull { it.target.provider == LocalSniSpoofProxy.NonCfCdnProvider.FASTLY_GITHUB }
                    if (fastlyHit != null) {
                        FirefoxVpnEngine.saveCleanFastlyEdgeIp(this@MainActivity, fastlyHit.target.ip)
                    }
                    connectFirefoxVpnDirectly()
                }
                .setPositiveButton(
                    if (isFarsi) "🚀 تعمیر کانفیگ من با این IP:portها و اتصال" else "🚀 Repair My Config & Connect",
                ) { _, _ ->
                    runOneClickRepairMyRealConfigAndConnect()
                }
                .create()
                .showCatClientDialog()
        }
    }

    private fun runFactADnsSniOracleDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال اجرای اوراکل Fact-A DNS برای کشف SNIهای غیراسبوف و باز در DPI…"
            else "⏳ Running Fact-A DNS Oracle to find unhijacked & open SNIs…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val results = withContext(Dispatchers.IO) {
                LocalSniSpoofProxy.probeFactADnsOracle(timeoutMs = 850)
            }
            val cleanSnis = results.filter { !it.poisoned }
            val text = buildString {
                if (isFarsi) {
                    appendLine("🔎 نتیجه اوراکل Fact-A DNS Non-Hijack SNI Finder:")
                    appendLine("💡 هر سایتی که جلوی آن 🟢 سبز خورده یعنی فایروال خط شما آن را مسدود نکرده است:")
                    appendLine("────────────────────────")
                } else {
                    appendLine("🔎 Fact-A DNS Non-Hijack SNI Finder Results:")
                    appendLine("────────────────────────")
                }
                results.forEach { r ->
                    val badge = when {
                        r.poisoned -> "🔴 مسموم در DNS (${r.resolvedIp})"
                        r.tlsPort443Open -> "🟢 سفید و باز (IP: ${r.resolvedIp} · TLS: ${r.tlsRttMs}ms)"
                        else -> "🟡 بدون جعل DNS (IP: ${r.resolvedIp} · DNS: ${r.dnsRttMs}ms)"
                    }
                    appendLine("• ${r.domain} -> $badge")
                }
            }
            val bestSni = cleanSnis.firstOrNull()?.domain ?: "ebanksepah.ir"
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🔎 اوراکل Fact-A (${cleanSnis.size} دامنه سفید تایید شد)"
                    else "🔎 Fact-A Oracle (${cleanSnis.size} Clean SNIs Verified)",
                )
                .setMessage(text.trim())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    if (isFarsi) "🚀 اعمال «$bestSni» و اتصال فوری"
                    else "🚀 Apply '$bestSni' & Connect Now",
                ) { _, _ ->
                    LocalSniSpoofProxy.updateDecoySni(bestSni)
                    val cur = aetherPreferenceStore.read()
                    aetherPreferenceStore.save(cur.copy(masqueSni = bestSni))
                    runOneClickRepairMyRealConfigAndConnect()
                }
                .create()
                .showCatClientDialog()
        }
    }

    private fun showLocalFakeClientHelloProxyDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val strategies = LocalSniSpoofProxy.SpoofStrategy.entries
        val labels = strategies.map { s ->
            val t = if (isFarsi) s.titleFa else s.titleEn
            val d = if (isFarsi) s.descriptionFa else s.descriptionEn
            "$t\n$d"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🛡️ انتخاب روش برش بسته TLS و اتصال فوری کانفیگ شما"
                else "🛡️ Select Local TLS SNI Split Strategy & Connect Now",
            )
            .setItems(labels) { _, idx ->
                val chosen = strategies[idx]
                LocalSniSpoofProxy.startSpoofSocksProxy(
                    strategy = chosen,
                    decoySni = LocalSniSpoofProxy.currentDecoySni,
                )
                runOneClickRepairMyRealConfigAndConnect(preferredStrategy = chosen)
            }
            .setNeutralButton(if (isFarsi) "⏹ توقف پروکسی محلی" else "⏹ Stop Local Proxy") { _, _ ->
                LocalSniSpoofProxy.stopAllSpoofProxies()
                Toast.makeText(
                    this,
                    if (isFarsi) "⏹ پروکسی محلی Fake ClientHello متوقف شد" else "⏹ Stopped Local Fake ClientHello Proxy",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showCatClientDialog()
    }

    private fun generateAndAddHybridNetMelliTunnelsDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val rawBundle = LocalSniSpoofProxy.buildHybridNetMelliTunnelConfigs()
        val uriLines = rawBundle.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                if (isFarsi) "🚀 تانل‌های هیبریدی نت ملی (S3 Bypass · QS · QQ · Spoof)"
                else "🚀 Hybrid Net Melli Tunnels (S3 Bypass · QS · QQ · Spoof)",
            )
            .setMessage(rawBundle.trim())
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(if (isFarsi) "📋 کپی همه لینک‌ها" else "📋 Copy All Links") { _, _ ->
                val raw = uriLines.joinToString("\n")
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("hybrid-netmelli-tunnels", raw))
                Toast.makeText(
                    this,
                    if (isFarsi) "✅ هر ۴ لینک تانل هیبریدی نت ملی کپی شد" else "✅ Copied all 4 Hybrid Net Melli tunnel URIs",
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setPositiveButton(if (isFarsi) "🚀 راه‌اندازی و اتصال فوری" else "🚀 Start & Connect Now") { _, _ ->
                val raw = uriLines.joinToString("\n")
                val title = "🚀 تانل‌های هیبرید نت ملی (S3/QS/QQ/Spoof)"
                activityScope.launch {
                    withContext(Dispatchers.IO) {
                        uriLines.forEach { uri ->
                            WhiteDnsSmartScanner.parseTunnelUri(uri)?.let { parsed ->
                                WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
                            }
                        }
                    }
                    val existing = userSubscriptionManager.list().firstOrNull { it.name == title }
                    val saved = withContext(Dispatchers.IO) {
                        if (existing != null) {
                            userSubscriptionManager.update(existing.id, title, raw)
                        } else {
                            userSubscriptionManager.add(title, raw)
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
                        this@MainActivity,
                        if (isFarsi) "🚀 ۴ تانل هیبریدی نت ملی (S3 Bypass / QS / QQ / Spoof) فعال شد — در حال اتصال…"
                        else "🚀 4 Hybrid Net Melli tunnels activated — connecting…",
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

    internal fun runRegionalFirewallFingerprinterDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال تست نوع فایروال واقعی منطقه شما (IPv6 کلودفلر، 188.114.98.0، MASQUE و ۶ پورت TLS)…"
            else "⏳ Fingerprinting your actual regional firewall (CF IPv6, 188.114.98.0, MASQUE & 6 TLS ports)…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val verdict = withContext(Dispatchers.IO) {
                WhiteDnsSmartScanner.detectRegionalFirewallProfile()
            }
            val title = if (isFarsi) verdict.type.titleFa else verdict.type.titleEn
            val detail = if (isFarsi) verdict.detailFa else verdict.detailEn
            val recEndpoint = when (verdict.type) {
                WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6 -> "[2606:4700:d0::a29f:c001]:2408"
                WhiteDnsSmartScanner.RegionalFirewallType.IRANCELL_FF -> "162.159.198.1:443"
                WhiteDnsSmartScanner.RegionalFirewallType.FIXED_MULTI_PORT -> "162.159.198.1:443"
            }
            val recMode = if (verdict.type == WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6) {
                AetherMode.WireGuard
            } else {
                AetherMode.MasqueHttp2
            }
            val recIpScan = if (verdict.type == WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6) "v6" else "both"
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(title)
                .setMessage(detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(
                    if (isFarsi) "⚡ بهینه‌سازی ساب فعلی" else "⚡ Optimize Active Sub",
                ) { _, _ ->
                    val selectedId = userSubscriptionManager.selectedId()
                    val activeSub = userSubscriptionManager.list().firstOrNull { it.id == selectedId }
                        ?: userSubscriptionManager.list().firstOrNull()
                    if (activeSub != null) {
                        optimizeUserSubscription(activeSub)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            if (isFarsi) "ابتدا یک سابسکریپشن اضافه کنید یا از دکمه اعمال روی Cat Warp استفاده کنید"
                            else "Add a subscription first or tap Apply to Cat Warp",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                .setPositiveButton(
                    if (isFarsi) "🚀 اعمال تنظیم بهینه روی Cat Warp" else "🚀 Apply Optimal Cat Warp Config",
                ) { _, _ ->
                    val cur = aetherPreferenceStore.read()
                    val updated = cur.copy(
                        enabled = true,
                        mode = recMode,
                        ipScan = recIpScan,
                        endpoint = recEndpoint,
                        endpointScannedAt = System.currentTimeMillis(),
                        profileName = when (verdict.type) {
                            WhiteDnsSmartScanner.RegionalFirewallType.MCI_IPV6 -> "MCI-IPv6-Auto"
                            WhiteDnsSmartScanner.RegionalFirewallType.IRANCELL_FF -> "Irancell-FF-Auto"
                            WhiteDnsSmartScanner.RegionalFirewallType.FIXED_MULTI_PORT -> "Fixed-ISP-Auto"
                        },
                    )
                    aetherPreferenceStore.save(updated)
                    renderAdvancedControls()
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ تنظیم بهینه «${updated.profileName}» ($recEndpoint) ذخیره و فعال شد"
                        else "✅ Applied optimal profile '${updated.profileName}' ($recEndpoint)",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun runWhiteDnsAutoMtuAndCarrierDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال تست طول QName، رکوردهای حامل (NULL 1232B / HTTPS 65 / TXT / CNAME) و ۵۲ سرور DNS…"
            else "⏳ Probing QName MTU, carrier records (NULL 1232B / HTTPS 65 / TXT / CNAME) & 52 resolvers…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val res = withContext(Dispatchers.IO) {
                WhiteDnsSmartScanner.autoDetectBestWhiteDnsProfile()
            }
            val bundle = WhiteDnsSmartScanner.buildWhiteDnsTunnelBundle(
                resolverIp = res.resolver.primaryIp,
                transport = res.transport,
                recordMode = res.recordMode,
                preset = res.preset,
            )
            val msg = if (isFarsi) {
                "${res.summaryFa}\n\n" +
                    "🔗 لینک CottenMax پیشنهادی:\n${bundle.cottenMaxUri}\n\n" +
                    "🔗 لینک StormDNS پیشنهادی:\n${bundle.stormDnsUri}"
            } else {
                "${res.summaryEn}\n\n" +
                    "🔗 Recommended CottenMax URI:\n${bundle.cottenMaxUri}\n\n" +
                    "🔗 Recommended StormDNS URI:\n${bundle.stormDnsUri}"
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "⚡ نتیجه تشخیص خودکار MTU و رکورد حامل Cat DNS"
                    else "⚡ Cat DNS Auto-MTU & Carrier Detection Result",
                )
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(if (isFarsi) "📋 کپی بسته تانل" else "📋 Copy Tunnel Bundle") { _, _ ->
                    val text = buildString {
                        appendLine(bundle.cottenMaxUri)
                        appendLine(bundle.stormDnsUri)
                        appendLine(bundle.soarUri)
                        append(bundle.dnsttUdpBridges.first())
                    }
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("whitedns-autotune", text))
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ بسته تانل بهینه کپی شد" else "✅ Copied optimal tunnel bundle",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .setPositiveButton(if (isFarsi) "✅ اعمال ریزولور روی DNS اپ" else "✅ Apply Resolver to App DNS") { _, _ ->
                    val r = res.resolver
                    if (r.dotEndpoint.isNotEmpty()) {
                        runCatching { dnsPrivacyPreferenceStore.saveDotEndpoint(r.dotEndpoint) }
                        dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoT)
                    } else {
                        val url = r.dohUrl.ifEmpty { "https://178.22.122.100/dns-query" }
                        runCatching { dnsPrivacyPreferenceStore.saveDohUrl(url) }
                        dnsPrivacyPreferenceStore.saveMode(DnsPrivacyMode.DoH)
                    }
                    renderDnsPrivacySelection()
                    if (currentVpnServiceState == VpnState.Started) {
                        reconnectForConnectionOptionChange()
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ ریزولور بهینه «${r.nameFa}» فعال شد" else "✅ Enabled optimal resolver '${r.nameEn}'",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun runCloudflareSixPortsAndSniScanDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال تست ۶ پورت TLS کلودفلر (443, 2053, 2083, 2087, 2096, 8443) و SNI روی خط شما…"
            else "⏳ Scanning 6 Cloudflare TLS ports & SNI pool on your line…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val customSni = frontingIpPreferenceStore.readSniOverride().takeIf { it.isNotBlank() }
            val hits = withContext(Dispatchers.IO) {
                WhiteDnsSmartScanner.scanCloudflareSixPortsAndSni(customSni = customSni)
            }
            if (hits.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "⚠️ روی این خط هیچ‌کدام از ۶ پورت TLS مستقیم پاسخ نداد؛ از «🤖 اسکن خودکار Cat Warp (WARP/MASQUE)» استفاده کنید."
                    else "⚠️ No direct TLS port responded; use Cat Warp Auto-Scan (WARP/MASQUE).",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val best = hits.first()
            val lines = hits.take(6).mapIndexed { i, h ->
                val star = if (i == 0) " ⭐" else ""
                "${i + 1}) ${h.endpoint} · SNI: ${h.sni} (${h.rttMs}ms)$star"
            }.joinToString("\n")
            val msg = if (isFarsi) {
                "✅ بهترین پورت و آی‌پی فعال روی خط شما:\n" +
                    "• آدرس دقیق (با حفظ پورت): ${best.endpoint}\n" +
                    "• بهترین SNI: ${best.sni} (${best.rttMs}ms)\n\n" +
                    "لیست پورت‌های باز روی اینترنت شما:\n$lines"
            } else {
                "✅ Best active IP:port & SNI on your line:\n" +
                    "• Exact Endpoint: ${best.endpoint}\n" +
                    "• Best SNI: ${best.sni} (${best.rttMs}ms)\n\n" +
                    "Open ports on your network:\n$lines"
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🌐 نتیجه اسکن ۶ پورت TLS کلودفلر + SNI"
                    else "🌐 6-Port Cloudflare TLS + SNI Scan Results",
                )
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(if (isFarsi) "📋 کپی آدرس و SNI" else "📋 Copy IP:port & SNI") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("cf-6port", "${best.endpoint}\nSNI: ${best.sni}"))
                    Toast.makeText(this@MainActivity, R.string.free_copied, Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton(if (isFarsi) "✅ اعمال روی کانفیگ‌های ساب" else "✅ Apply to Subscription") { _, _ ->
                    val prev = frontingIpPreferenceStore.readFrontingIp()
                    frontingIps = FrontingIpPolicy.mergeNewestFirst(listOf(best.endpoint), frontingIps)
                    renderFrontingIpChips()
                    saveFrontingIps(reconnectIfChanged = true, previousValue = prev)
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ آدرس ${best.endpoint} با همان پورت دقیق اعمال شد"
                        else "✅ Applied exact endpoint ${best.endpoint}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun runFetchMoatTorBridgesDialog() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        Toast.makeText(
            this,
            if (isFarsi) "⏳ در حال دریافت پل‌های جدید Tor از سرور رسمی Moat…"
            else "⏳ Fetching fresh Tor bridges from official Moat service…",
            Toast.LENGTH_SHORT,
        ).show()
        activityScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                AetherOperations.fetchMoatTorBridges("ir").getOrElse {
                    WhiteDnsSmartScanner.buildDnsttBridgesForResolver("185.51.200.2")
                }
            }
            val text = fetched.joinToString("\n")
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("tor-moat-bridges", text))
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(
                    if (isFarsi) "🧅 پل‌های Tor کپی شد (${fetched.size} پل)"
                    else "🧅 Tor Bridges Copied (${fetched.size} bridges)",
                )
                .setMessage(text)
                .apply {
                    val usable = DeltaKroneckerFeeds.parseBridgeLines(text, 10)
                    if (usable.isNotEmpty()) {
                        setPositiveButton(if (isFarsi) "✅ استفاده در Cat Warp" else "✅ Use in Cat Warp") { _, _ ->
                            aetherPreferenceStore.save(
                                aetherPreferenceStore.read().copy(torBridgesMode = "own", torCustomBridges = usable.joinToString("\n")),
                            )
                            renderAdvancedControls()
                        }
                    } else {
                        setPositiveButton(android.R.string.ok, null)
                    }
                }
                .setNeutralButton(if (isFarsi) "🔺 پل‌های ساعتی Cat Feed" else "🔺 Hourly Cat Feed bridges") { _, _ ->
                    DeltaKroneckerFeeds.byKind(DeltaKroneckerFeeds.Kind.TOR_BRIDGES).firstOrNull()?.let { fetchDeltaKroneckerTorBridges(it) }
                }
                .create()
                .showCatClientDialog()
        }
    }

    internal fun exportAetherIdentityToClipboard() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        activityScope.launch {
            val tomlFile = java.io.File(filesDir, "aether.toml")
            val cur = aetherPreferenceStore.read()
            val payload = withContext(Dispatchers.IO) {
                AetherOperations.exportIdentity(tomlFile).recoverCatching {
                    AetherOperations.provision(cur.mode, tomlFile, null, cur.ipScan, cur.exitLocation).getOrThrow()
                    AetherOperations.exportIdentity(tomlFile).getOrThrow()
                }
            }
            payload.fold(
                onSuccess = { exported ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("aether-identity-backup", exported))
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ پشتیبان کلید کلودفلر در کلیپ‌بورد کپی شد (در جایی امن ذخیره کنید تا در نصب مجدد کلیدتان نسوزد)"
                        else "✅ Cloudflare identity backup copied to clipboard!",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "⚠️ خطا در تهیه پشتیبان کلید: ${err.message}"
                        else "⚠️ Failed to export identity: ${err.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    internal fun importAetherIdentityFromClipboard() {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (clip.isEmpty()) {
            Toast.makeText(
                this,
                if (isFarsi) "⚠️ کلیپ‌بورد خالی است؛ ابتدا متن پشتیبان کلید را کپی کنید"
                else "⚠️ Clipboard is empty; copy your identity backup first",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        activityScope.launch {
            val tomlFile = java.io.File(filesDir, "aether.toml")
            val res = withContext(Dispatchers.IO) {
                AetherOperations.importIdentity(tomlFile, clip)
            }
            res.fold(
                onSuccess = {
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "✅ کلید کلودفلر با موفقیت بازیابی و جایگزین شد!"
                        else "✅ Cloudflare identity restored successfully!",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                onFailure = { err ->
                    Toast.makeText(
                        this@MainActivity,
                        if (isFarsi) "⚠️ متن داخل کلیپ‌بورد یک کلید معتبر Cat Warp نیست: ${err.message}"
                        else "⚠️ Invalid Cat Warp identity backup in clipboard: ${err.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    internal fun showFirewallConnectionGuideDialog() {
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

    internal var techNamoozDeployInProgress = false

    internal fun runTechNamoozDeployment(token: String, workerName: String, password: String) {
        if (techNamoozDeployInProgress) return
        techNamoozDeployInProgress = true
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_technamooz_deploying)
            .setView(wizardBody(ProgressBar(this), wizardText(getString(R.string.cloud_technamooz_deploying_detail))))
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
                val result = CloudflareWorker.deployTechNamoozWorker(
                    token = token,
                    accountId = accountId,
                    workerName = workerName,
                    panelPassword = password,
                )
                progress.dismiss()
                showTechNamoozDeploymentResult(result)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.cloud_deploy_failed, e.message ?: e::class.java.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                techNamoozDeployInProgress = false
            }
        }
    }

    private fun showTechNamoozDeploymentResult(result: CloudflareWorker.TechNamoozWorkerDeploymentResult) {
        val details = buildString {
            append(getString(R.string.cloud_technamooz_result_panel, result.panelUrl))
            append("\n")
            append(getString(R.string.cloud_technamooz_result_password, result.panelPassword))
            append("\n")
            append(getString(R.string.cloud_technamooz_result_uuid, result.uuid))
            append("\n")
            append(getString(R.string.cloud_technamooz_result_subpath, result.subPath))
            append("\n")
            append(getString(R.string.cloud_technamooz_result_subscription, result.subscriptionUrl))
            append("\n")
            append(getString(R.string.cloud_technamooz_result_kv, result.kvNamespaceTitle))
            append("\n")
            append(getString(R.string.cloud_technamooz_result_source, result.sourceCommit, result.sourceSha256))
            if (!result.workersDevEnabled) {
                append("\n\n")
                append(getString(R.string.cloud_technamooz_route_disabled))
            }
            append("\n\n")
            append(getString(R.string.cloud_technamooz_result_note))
        }
        val detailsView = TextView(this).apply {
            text = details
            textSize = 12.5f
            setTextColor(TEXT_PRIMARY)
            setTextIsSelectable(true)
            setPadding(dp(22), dp(8), dp(22), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_technamooz_result_title)
            .setView(ScrollView(this).apply { addView(detailsView) })
            .setPositiveButton(R.string.cloud_technamooz_add_sub) { _, _ ->
                showAddSubscriptionDialog(result.subscriptionUrl, "TechNamooz Worker")
            }
            .setNeutralButton(R.string.cloud_technamooz_open_panel) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.panelUrl))) }
            }
            .setNegativeButton(R.string.cloud_technamooz_copy_details) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("technamooz-worker-setup", details))
                Toast.makeText(this, R.string.cloud_technamooz_copied, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    internal var panelUpdateInProgress = false

    @Volatile
    private var newestPanelCache: PanelUpdate.PanelScript? = null

    internal suspend fun newestPanelScript(): PanelUpdate.PanelScript =
        newestPanelCache ?: PanelUpdate.newestPanel(this).also { newestPanelCache = it }

    /**
     * «📊 وضعیت کامل» — live snapshot of everything this panel currently serves:
     * SNI (with the skk.moe-default warning), clean IPs (+pins), ProxyIP relays,
     * fixed exit (chain), extra SNIs, ports, rotation, sub link. Login uses the
     * stored deploy UUID (panel default password); on 401 the user is asked.
     */
    internal fun showPanelStatusDialog(base0: String) {
        val base = base0.trim().trimEnd('/')
        activityScope.launch {
            var password = runCatching { PanelCredentials.defaultPassword(this@MainActivity, base) }.getOrNull().orEmpty()
            var result = withContext(Dispatchers.IO) { runCatching { fetchPanelStatus(base, password) } }
            if (result.isFailure && password.isNotBlank()) {
                // stored password rejected (password changed) → ask once
                val asked = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<String?> { cont ->
                        val input = TextInputEditText(this@MainActivity).apply {
                            hint = getString(R.string.pip_password_hint)
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            setText(password)
                        }
                        val box = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(20), dp(12), dp(20), 0)
                            addView(input, LinearLayout.LayoutParams(-1, -2))
                        }
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.panel_status_btn)
                            .setMessage(base.removePrefix("https://"))
                            .setView(box)
                            .setNegativeButton(android.R.string.cancel) { _, _ -> cont.resume(null) }
                            .setPositiveButton(android.R.string.ok) { d, _ -> cont.resume(input.text.toString()); d.dismiss() }
                            .setOnCancelListener { cont.resume(null) }
                            .show()
                    }
                }
                if (asked.isNullOrBlank()) return@launch
                password = asked
                result = withContext(Dispatchers.IO) { runCatching { fetchPanelStatus(base, password) } }
                if (result.isSuccess) {
                    PanelCredentials.remember(this@MainActivity, base, PanelCredentials.defaultUsername(this@MainActivity, base), password)
                }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                result.onSuccess { text ->
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.panel_status_title)
                        .setMessage(text)
                        .setPositiveButton(R.string.panel_status_close, null)
                        .show()
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, getString(R.string.pip_save_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchPanelStatus(base: String, password: String): String {
        fun call(path: String, method: String, body: String?, cookie: String?): Pair<java.net.HttpURLConnection, String> {
            val conn = java.net.URL("$base$path").openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
            val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            return conn to stream.bufferedReader().readText()
        }
        val version = runCatching {
            val (v, vb) = call("/api/version", "GET", null, null); v.disconnect()
            org.json.JSONObject(vb).optString("version", "?")
        }.getOrDefault("?")
        val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(applicationContext, base, password).toString(), null)
        if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(getString(R.string.pip_wrong_password))
        val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
        login.disconnect()
        val (get, getBody) = call("/api/settings", "GET", null, cookie)
        get.disconnect()
        if (get.responseCode != 200) throw IllegalStateException("HTTP ${get.responseCode}")
        val st = org.json.JSONObject(getBody).optJSONObject("settings") ?: org.json.JSONObject()
        val sni = st.optString("sni", "")
        // Panel >= 6.54 reports sniFront: false means every TLS config carries the worker host as SNI — the only
        // value Cloudflare accepts (it answers 403 when SNI != Host). Older panels have no such key and still
        // front through the skk.moe default / pool, which Cloudflare rejects.
        val sniLine = when {
            st.has("sniFront") && !st.optBoolean("sniFront") -> getString(R.string.panel_st_sni_host)
            st.has("sniFront") -> getString(R.string.panel_st_sni_spoof, sni.ifBlank { "skk.moe" })
            sni.isBlank() -> getString(R.string.panel_st_sni_default)
            else -> sni
        }
        val ips = st.optJSONArray("ips"); var pins = 0
        if (ips != null) { for (i in 0 until ips.length()) if (ips.optString(i).contains(":")) pins++ }
        val px = st.optJSONArray("proxyIps")?.length() ?: 0
        val xs = st.optJSONArray("extraSnis")?.length() ?: 0
        val chain = st.optString("chain", "").ifBlank { getString(R.string.panel_st_chain_off) }
        val tlsArr = st.optJSONArray("tlsPorts")
        val plainArr = st.optJSONArray("plainPorts")
        val tls = (0 until (tlsArr?.length() ?: 0)).take(3).mapNotNull { tlsArr?.optString(it) }.joinToString("/")
        val plain = if (st.optBoolean("plainEnabled")) (0 until (plainArr?.length() ?: 0)).take(3).mapNotNull { plainArr?.optString(it) }.joinToString("/") else null
        val rot = when (st.optString("subRotate", "fetch")) { "off" -> getString(R.string.panel_rot_off); "daily" -> getString(R.string.panel_rot_daily); else -> getString(R.string.panel_rot_fetch) }
        val uuid = runCatching { PanelDeploymentStore(this).uuidFor(base) }.getOrDefault("")
        // The panel knows which key is live better than we do: since 6.67.0 a subscription
        // TOKEN can replace the UUID in /sub/..., and after that rotation the UUID link is
        // dead. Older panels answer no `links` object, so the stored UUID stays the fallback.
        val sub = org.json.JSONObject(getBody).optJSONObject("links")?.optString("sub").orEmpty().ifBlank {
            if (uuid.isNotBlank()) "$base/sub/$uuid" else base + "/sub/<uuid>"
        }
        return getString(R.string.panel_status_body,
            base.removePrefix("https://"), version, sniLine,
            ips?.length() ?: 0, pins, px, chain, xs,
            tls + (plain?.let { " · $it" } ?: ""), rot, sub)
    }

    /**
     * Attach a panel that was deployed manually in the Cloudflare dashboard (no
     * app record exists). After this the card shows in My Panels with its live
     * version and the Update Panel button — the CF token is asked at update time.
     */
    internal fun onAddExistingPanel() {
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

    internal fun applyPanelVersionBadge(
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

    /**
     * A live panel is never moved backwards — not offered, not performed. A stale source once
     * installed panel 5.23.13 over a working 6.x deployment («the panel went back to the old
     * version»). Returns true when the move was blocked (the user has been told why).
     */
    private fun blockPanelDowngrade(deployed: String?, candidate: String): Boolean {
        if (!PanelUpdate.isDowngrade(deployed, candidate)) return false
        panelUpdateInProgress = false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_update_downgrade_title)
            .setMessage(getString(R.string.cloud_update_downgrade_msg, deployed.orEmpty(), candidate))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    internal fun showPanelUpdateDialog(deployment: PanelDeploymentRecord) {
        if (panelUpdateInProgress) return
        panelUpdateInProgress = true
        Toast.makeText(this, R.string.cloud_panel_version_checking, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            try {
                val newest = newestPanelScript()
                val deployed = PanelUpdate.deployedVersion(deployment.workerUrl)
                // (The old dialog ignored hasUpdate and installed whatever source it had found.)
                if (blockPanelDowngrade(deployed, newest.version)) return@launch
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
            .setNeutralButton(R.string.cloud_update_tokenless_btn) { _, _ ->
                panelUpdateInProgress = false
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-worker", newest.text))
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/?to=/:account/workers")))
                }
                Toast.makeText(this, R.string.cloud_update_tokenless_go, Toast.LENGTH_LONG).show()
            }
            .setPositiveButton(R.string.cloud_update_confirm) { _, _ ->
                Toast.makeText(this, R.string.cloud_update_running, Toast.LENGTH_SHORT).show()
                activityScope.launch {
                    val selfUpdated = CloudflareWorker.triggerPanelSelfUpdate(this@MainActivity, deployment.workerUrl)
                    if (selfUpdated != null) {
                        panelUpdateInProgress = false
                        renderCloudDeploymentHistory()
                        showCloudUpdateDialog(selfUpdated)
                    } else {
                        presentPanelTokenPrompt(deployment, deployed, newest)
                    }
                }
            }
            .setOnCancelListener { panelUpdateInProgress = false }
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_update_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> panelUpdateInProgress = false }
            .setPositiveButton(if (hasUpdate) R.string.cloud_update_confirm else R.string.cloud_update_reinstall) { _, _ ->
                runPanelUpdate(deployment, savedToken, deployed, newest)
            }
            .setOnCancelListener { panelUpdateInProgress = false }
            .show()
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

    /**
     * The deploy/update status line (6.67.0). A bare «pending» hid three different
     * situations; this one names the one that happened, using the `/api/version`
     * probe:
     *   • JSON back      → the file loaded and ran (the version is shown);
     *   • a non-JSON page → the edge answered but our code never ran — the classic
     *                       «Error 1101» with no report, i.e. a broken upload;
     *   • silence        → nothing reachable yet.
     * Plus the app/panel skew warning when the two halves disagree.
     */
    private fun panelStatusLine(
        verifiedOnline: Boolean,
        health: CloudflareWorker.PanelVersionProbe?,
        bundledVersion: String = "",
        panelVersion: String = "",
    ): String {
        val lines = mutableListOf<String>()
        when {
            verifiedOnline -> lines += if (panelVersion.isNotBlank()) {
                getString(R.string.cloud_panel_online_version, panelVersion)
            } else {
                getString(R.string.cloud_verified_online)
            }
            // A fresh workers.dev route answers the edge's own 404 page until the
            // Worker takes over. Saying «truncated upload, paste the file by hand»
            // for that is the one message that must never be wrong — it is a wait,
            // not a failure, and it clears itself.
            health != null && health.routeNotLiveYet -> {
                lines += getString(R.string.cloud_panel_route_pending, health.status.toString())
                if (health.bodyHead.isNotBlank()) {
                    lines += getString(R.string.cloud_panel_never_loaded_body, health.bodyHead.take(120))
                }
            }
            health != null && health.scriptNeverLoaded -> {
                lines += getString(R.string.cloud_panel_never_loaded, health.status.toString())
                if (health.bodyHead.isNotBlank()) {
                    lines += getString(R.string.cloud_panel_never_loaded_body, health.bodyHead.take(120))
                }
            }
            health == null || health.unreachable -> lines += getString(R.string.cloud_panel_unreachable)
            else -> lines += getString(R.string.cloud_verify_pending)
        }
        if (bundledVersion.isNotBlank() && panelVersion.isNotBlank() && bundledVersion != panelVersion) {
            lines += getString(R.string.cloud_panel_version_skew, panelVersion, bundledVersion)
        }
        return lines.joinToString("\n")
    }

    private fun showCloudUpdateDialog(result: CloudflareWorker.PanelUpdateOutcome.Success) {
        val status = panelStatusLine(
            verifiedOnline = result.verifiedOnline,
            health = result.health,
            bundledVersion = result.bundledVersion,
            panelVersion = result.panelVersion,
        )
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

    /**
     * Cloudflare account + token entry, in the ONLY order that works when the
     * user is not signed in yet.
     *
     * The token template link carries query parameters; opened while signed
     * out, Cloudflare's login page validates the whole URL as an OAuth
     * redirect_uri and rejects it — the user gets «Invalid redirect_uri» at the
     * exact moment they are creating an account. So this dialog leads with the
     * two parameter-free links (sign-up / login), then the template link, and
     * keeps a plain tokens link as the escape hatch.
     */
    internal fun openCloudflareTokenPage() {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val body = wizardBody(
            wizardText(getString(R.string.cloud_account_tip), secondary = true),
            cloudAccountLinkButton(R.string.cloud_account_signup, CloudflareAccountLinks.SIGNUP) { dialog },
            cloudAccountLinkButton(R.string.cloud_account_login, CloudflareAccountLinks.LOGIN) { dialog },
            cloudAccountLinkButton(R.string.cloud_get_token_open, CloudflareWorker.CF_TOKEN_TEMPLATE_URL) { dialog },
            cloudAccountLinkButton(R.string.cloud_token_plain, CloudflareAccountLinks.TOKENS) { dialog },
        )
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_get_token)
            .setView(body)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    /** Full-width link button for the account/token chooser. */
    private fun cloudAccountLinkButton(
        @StringRes labelRes: Int,
        url: String,
        dialog: () -> androidx.appcompat.app.AlertDialog?,
    ): MaterialButton = cloudActionButton(labelRes, R.drawable.ic_cloud_tab, accent = false) {
        openExternalUrl(url)
        Toast.makeText(this, R.string.cloud_account_after, Toast.LENGTH_LONG).show()
        dialog()?.dismiss()
    }

    /**
     * Copies the official one-click Deploy-to-Cloudflare link and opens the
     * system share sheet — the recipient needs neither Cat Client nor an API
     * token, just a (free) Cloudflare account.
     */
    internal fun shareDeployButtonLink() {
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

    /**
     * «Copy Worker code» — hands the clipboard the OFFICIAL paste artifact: the
     * 3-line `catpanel.min.js` (~474 KB) built by CI, not the ~1.2 MB obfuscated
     * bundle this app uploads over the API. A big string is one interrupted paste
     * away from a truncated Worker, and a truncated Worker returns Cloudflare's
     * «Error 1101» for every request.
     *
     * The big bundle stays the fallback for APKs built without the paste asset
     * (local builds); that fallback is announced in the toast instead of being
     * silent, so nobody pastes 1.2 MB believing it is the small file.
     */
    internal fun copyPanelPasteCode() {
        val paste = CloudflareWorker.builtInPasteScript(this)
        val official = CloudflareWorker.pasteScriptIsSane(paste)
        val localText = if (official) paste else runCatching { CloudflareWorker.builtInWorkerScript(this) }.getOrNull()
        val remoteText = newestPanelCache?.takeIf {
            CloudflareWorker.pasteScriptIsSane(it.text) &&
                PanelUpdate.preferRelease(PanelUpdate.parseVersion(localText.orEmpty()).orEmpty(), it.version)
        }?.text
        val text = remoteText ?: localText
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.cloud_code_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val copied = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("cat-panel-worker", text))
        }.isSuccess
        if (!copied) {
            Toast.makeText(this, R.string.cloud_code_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            this,
            if (official || remoteText != null) R.string.cloud_code_copied else R.string.cloud_code_copied_large,
            Toast.LENGTH_LONG,
        ).show()
    }

    internal fun showWizardDeployedDialog(result: CloudflareWorker.WizardDeploymentResult) {
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

    internal fun wizardText(text: CharSequence, secondary: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = if (secondary) 12.5f else 14f
        typeface = CatClientBodyTypeface
        setTextColor(if (secondary) TEXT_SECONDARY else TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }

    /** Step 1/4 — what is about to happen. */
    internal fun showPanelWizard() {
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
        val (passLayout, passEdit) = wizardField(getString(R.string.cloud_panel_password_hint), password = true, initial = "admin")
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
        val status = panelStatusLine(
            verifiedOnline = result.verifiedOnline,
            health = result.health,
            bundledVersion = result.bundledVersion,
            panelVersion = result.panelVersion,
        )
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

    /** True when any VPN (ours or another app's) owns the default route — Zed's deviceVpnActive(). */
    internal fun deviceVpnActive(): Boolean = runCatching {
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

    internal fun buildStaticIpCard(): View {
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
            val labels = pool.map { e -> (e.countryCode?.toFlagEmoji() ?: "🌐") + " " + e.authority + (e.colo?.let { "  $it" } ?: "") + (if (e.pingMs > 0) "  ${e.pingMs} ms" else "") }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.static_ip_pick_pool)
                .setItems(labels) { _, which -> input.setText(pool[which].authority); if (toggle.isChecked) { applyStaticIp(pool[which].authority); renderStatus(); renderScannerIpHealth() } else toggle.isChecked = true }
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
        frontingIps = FrontingIpPolicy.mergeNewestFirst(ranked.map { it.authority }, emptyList())
        renderFrontingIpChips()
        if (saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) {
            Toast.makeText(this, getString(R.string.multi_location_applied, countryCode.toFlagEmoji(), ranked.size), Toast.LENGTH_SHORT).show()
        }
        renderScannerIpHealth()
    }

    internal fun showSpeedTestPage() {
        SpeedTestPage(this, palette, activityScope, connected = currentVpnStateIsStarted()).show()
    }

    /**
     * ⏱ Precise ping & jitter page. Prefills the ticked (or best verified) scanner row with
     * its exact port, and the worker the scanner measures through — never a made-up value:
     * with no scan yet the target is the worker domain itself on 443.
     */
    internal fun showPingJitterPage() {
        val farsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        activityScope.launch {
            val worker = withContext(Dispatchers.IO) { runCatching { scannerWorkerHost(null) }.getOrNull() }
            val picked = scannerResults.firstOrNull { scannerSelected.contains(scannerRowKey(it)) }
                ?: scannerResults.filter { it.tlsOk }.minByOrNull { it.workerMs ?: it.lineMs ?: it.pingMs }
            val target = picked?.authority ?: worker?.let { "$it:443" }
            PingJitterPage(this@MainActivity, palette, activityScope, farsi, target, worker, vpnOn = currentVpnStateIsStarted()).show()
        }
    }

    /**
     * Panel base for push APIs (ips / extraSnis). Priority: KNOWN deployments
     * recorded in this app (deploys + Add-existing + wizard) — never a guess;
     * falls back to panel hosts detected inside subscriptions only when no
     * explicit record exists.
     */
    private fun panelBaseUrl(): String? {
        PanelDeploymentStore(this).deployments().firstOrNull()?.let { return it.workerUrl.trimEnd('/') }
        return detectPanelSnisFromSubscriptions().firstOrNull()?.let { "https://$it" }
    }

    /** Panel base picked EXPLICITLY by the user when several are known. */
    internal suspend fun pickPanelBaseInteractive(): String? {
        val known = PanelDeploymentStore(this).deployments().map { it.workerUrl.trimEnd('/') }.distinct()
        val detected = detectPanelSnisFromSubscriptions().map { "https://$it" }
        val all = (known + detected).distinct()
        if (all.isEmpty()) return null
        if (all.size == 1) return all[0]
        return suspendCancellableCoroutine { cont ->
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pip_pick_panel)
                .setItems(all.map { it.removePrefix("https://") }.toTypedArray()) { _, which -> cont.resume(all[which]) }
                .setOnCancelListener { cont.resume(null) }
                .show()
        }
    }

    internal fun showProxyIpScannerPage() {
        ProxyIpScannerPage(this, palette, activityScope, panelBaseUrl(), onUseAsEntry = { applyProxyIpAsEntry(it) }, pickPanel = { pickPanelBaseInteractive() }).show()
    }

    /**
     * Apply a verified ProxyIP (`host:port` or `[ipv6]:port`) from the ProxyIP scanner
     * as an outbound ProxyIP (`?proxyip=host:port`) on the currently selected subscription
     * profile — never into the Clean IP `frontingIps` pool.
     */
    private fun applyProxyIpAsEntry(ip: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("proxyip", ip))
        val chain = connectionChainPreferenceStore.read()
        val entrySlot = chain.firstConfiguredSlot()
        if (chain.isActive && entrySlot != null) {
            val pinned = connectionChainEndpointOrNull(ip)?.encoded() ?: ip
            updateConnectionChainSettings(
                chain.withHop(entrySlot, chain.hop(entrySlot).copy(serverOverride = pinned)),
            )
            Toast.makeText(
                this,
                getString(
                    R.string.scanner_applied_chain,
                    pinned,
                    getString(connectionChainSlotLabelRes(entrySlot)),
                ),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val nowMs = System.currentTimeMillis()
        val proxyEndpoint = VerifiedEndpointParser.parseAuthority(
            value = ip,
            role = EndpointRole.PROXY_IP,
            verdict = EndpointVerdict.VERIFIED,
            checkedAtMs = nowMs,
            source = "device-proxyip-scan",
        )
        val selectedSubId = SubscriptionStore(this).readSelectedSubscriptionId()
        val targetProfile = connectionSelectionPreferenceStore.readSelectedProfile(selectedSubId, connectionProfiles)
            ?.takeIf { !it.shareLink.isNullOrBlank() }
            ?: connectionProfiles.firstOrNull { !it.shareLink.isNullOrBlank() }
        if (proxyEndpoint != null && targetProfile?.shareLink != null) {
            val rebuiltUri = VerifiedConfigFailover.changeProxyIpUri(
                rawUri = targetProfile.shareLink,
                candidates = listOf(proxyEndpoint),
                nowMs = nowMs,
                maxAgeMs = 86_400_000L,
            )
            if (rebuiltUri != null) {
                val updated = userSubscriptionManager.replaceProfileShareLink(
                    subscriptionId = selectedSubId,
                    targetTag = targetProfile.tag,
                    updatedShareLink = rebuiltUri,
                    nowMs = nowMs,
                )
                if (updated != null) {
                    connectionProfiles = connectionProfiles.map { if (it.tag == targetProfile.tag) updated else it }
                    val selectedTypes = connectionSelectionPreferenceStore
                        .readAutomaticTypes(selectedSubId, connectionProfiles)
                        .ifEmpty { ConnectionTypeSelectionPolicy.availableTypes(connectionProfiles).toSet() }
                    handleConnectionSelected(updated, selectedTypes)
                    Toast.makeText(
                        this,
                        "🎯 ProxyIP applied: ${proxyEndpoint.authority} (${updated.displayTag})",
                        Toast.LENGTH_LONG,
                    ).show()
                    return
                }
            }
        }
        if (proxyEndpoint != null) {
            Toast.makeText(this, "🎯 ProxyIP copied: ${proxyEndpoint.authority}", Toast.LENGTH_LONG).show()
            return
        }
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        frontingIps = FrontingIpPolicy.mergeNewestFirst(listOf(ip), frontingIps)
        renderFrontingIpChips()
        if (!saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) return
        Toast.makeText(this, getString(R.string.scanner_applied, ip, 0L), Toast.LENGTH_LONG).show()
    }

    /** Put an SNI into the live configs (servername of every TLS proxy) and reconnect; null clears it. */
    internal fun applySniOverride(sni: String?) {
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
    internal fun pushSniToPanel(sni: String) = activityScope.launch { pushSniToPanelSuspend(sni) }

    /** "Make panel main SNI": PUT /api/settings {sni} — every TLS config the panel emits re-fronts through this host (kills the skk.moe default in one tap). */
    internal fun pushSniMainToPanel(sni: String) = activityScope.launch { pushSniMainToPanelSuspend(sni) }

    private suspend fun pushSniMainToPanelSuspend(sni: String) {
        val base = kotlinx.coroutines.withContext(Dispatchers.Main) { pickPanelBaseInteractive() }?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) { Toast.makeText(this, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return }
        val storedPass = runCatching { PanelCredentials.defaultPassword(this, base) }.getOrNull().orEmpty()
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            val input = TextInputEditText(this@MainActivity).apply {
                hint = getString(R.string.pip_password_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (storedPass.isNotBlank()) setText(storedPass)
            }
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.sni_main_title, sni))
                .setMessage(base.removePrefix("https://") + "\n\n" + getString(R.string.sni_main_warn, sni))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.sni_action_main) { d, _ ->
                    val password = input.text.toString()
                    d.dismiss()
                    activityScope.launch {
                        val result = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { pushSniMainToPanelApi(base, password, sni) } }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, result.fold({ getString(R.string.sni_main_ok, sni) }, { getString(R.string.sni_main_fail, it.message ?: "?") }), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun pushSniMainToPanelApi(base: String, password: String, sni: String) {
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
        // beta204: sniRotate=false — otherwise the panel kept rotating its built-in SNI pool (names never tested on this
        // network) and the SNI the user just verified appeared on only a few configs.
        val (put, putBody) = call("/api/settings", "PUT", org.json.JSONObject().put("sni", sni).put("sniFront", true).put("sniRotate", false).toString(), cookie)
        val ok = put.responseCode == 200 && org.json.JSONObject(putBody).optBoolean("ok")
        put.disconnect()
        if (!ok) throw IllegalStateException("HTTP ${put.responseCode}")
    }

    private suspend fun pushSniToPanelSuspend(sni: String) {
        val base = kotlinx.coroutines.withContext(Dispatchers.Main) { pickPanelBaseInteractive() }?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) { Toast.makeText(this, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return }
        val storedPass = runCatching { PanelCredentials.defaultPassword(this, base) }.getOrNull().orEmpty()
        val input = EditText(this).apply {
            if (storedPass.isNotBlank()) setText(storedPass)
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
        val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(applicationContext, base, password).toString(), null)
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

    internal fun showProxyScannerPage() {
        ProxyScannerPage(
            activity = this,
            palette = palette,
            scope = activityScope,
            panelBaseUrl = panelBaseUrl(),
            pickPanel = { pickPanelBaseInteractive() },
            importLink = { link -> showAddSubscriptionDialog(link, "") },
            connectDirect = { hit -> connectSocks5HitDirectly(hit) },
            connectPool = { hits -> connectSocks5PoolDirectly(hits) },
            startWithSocks5Only = false,
        ).show()
    }

    internal fun showSocks5HubPage() {
        ProxyScannerPage(
            activity = this,
            palette = palette,
            scope = activityScope,
            panelBaseUrl = panelBaseUrl(),
            pickPanel = { pickPanelBaseInteractive() },
            importLink = { link -> showAddSubscriptionDialog(link, "") },
            connectDirect = { hit -> connectSocks5HitDirectly(hit) },
            connectPool = { hits -> connectSocks5PoolDirectly(hits) },
            startWithSocks5Only = true,
        ).show()
    }

    private fun connectSocks5HitDirectly(hit: ProxyScannerPage.Hit) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val uri = ProxyScanImport.uri(hit, System.currentTimeMillis()) ?: return
        val countryTag = hit.country.ifBlank { "SOCKS5" }
        val shareLink = "$uri#$countryTag ${hit.authority} (${hit.latencyMs}ms)"
        val subName = "🧦 SOCKS5 Direct ($countryTag ${hit.host}:${hit.port})"
        activityScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("🧦 SOCKS5 Direct") }
                    if (existing != null) {
                        userSubscriptionManager.update(existing.id, subName, shareLink)
                    } else {
                        userSubscriptionManager.add(subName, shareLink)
                    }
                }
            }.getOrNull()
            if (saved == null) {
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "⚠️ ساخت پروفایل اتصال SOCKS5 ناموفق بود" else "⚠️ Failed to create SOCKS5 profile",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val curAether = aetherPreferenceStore.read()
            if (curAether.enabled) {
                aetherPreferenceStore.save(curAether.copy(enabled = false))
            }
            orbotEnginePreferenceStore.setEnabled(false)
            userSubscriptionManager.select(saved.id)
            onSubscriptionSelected()
            Toast.makeText(
                this@MainActivity,
                if (isFarsi) "⚡ در حال اتصال مستقیم به پراکسی ساکس۵ (${hit.authority} · ${hit.latencyMs}ms)…"
                else "⚡ Connecting directly to SOCKS5 (${hit.authority} · ${hit.latencyMs}ms)…",
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

    private fun connectSocks5PoolDirectly(hits: List<ProxyScannerPage.Hit>) {
        val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
        val nowMs = System.currentTimeMillis()
        val links = hits
            .filter { it.proto == ProxyScannerPage.Proto.SOCKS5 }
            .sortedBy { it.latencyMs }
            .distinctBy { "${it.host.lowercase(Locale.US)}:${it.port}" }
            .take(40)
            .mapIndexedNotNull { idx, h ->
                val baseUri = ProxyScanImport.uri(h, nowMs) ?: return@mapIndexedNotNull null
                val cc = h.country.ifBlank { "GL" }
                "$baseUri#$cc-${idx + 1} ${h.host}:${h.port} (${h.latencyMs}ms)"
            }
        if (links.isEmpty()) {
            Toast.makeText(
                this,
                if (isFarsi) "⚠️ ابتدا دکمه شروع اسکن را بزنید تا ساکس۵های سالم پیدا شوند"
                else "⚠️ Start the scan first to find working SOCKS5 proxies",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val subName = "🧦 SOCKS5 Auto-Pool (${links.size})"
        activityScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("🧦 SOCKS5 Auto-Pool") }
                    if (existing != null) {
                        userSubscriptionManager.update(existing.id, subName, links.joinToString("\n"))
                    } else {
                        userSubscriptionManager.add(subName, links.joinToString("\n"))
                    }
                }
            }.getOrNull()
            if (saved == null) {
                Toast.makeText(
                    this@MainActivity,
                    if (isFarsi) "⚠️ ساخت استخر خودکار SOCKS5 ناموفق بود" else "⚠️ Failed to build SOCKS5 pool",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val curAether = aetherPreferenceStore.read()
            if (curAether.enabled) {
                aetherPreferenceStore.save(curAether.copy(enabled = false))
            }
            orbotEnginePreferenceStore.setEnabled(false)
            userSubscriptionManager.select(saved.id)
            onSubscriptionSelected()
            Toast.makeText(
                this@MainActivity,
                if (isFarsi) "🚀 استخر هوشمند ساکس۵ با ${links.size} پراکسی سالم ساخته شد — در حال اتصال خودکار…"
                else "🚀 Smart SOCKS5 pool built with ${links.size} live proxies — connecting…",
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

    /* ------------------------------------------------------------------ */
    /* Add server sheet (ZedSecure "Add server")                             */
    /* ------------------------------------------------------------------ */

    internal fun showAddServerSheet() {
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
            addView(item("📂", R.string.servers_add_file) { appTools.openImportFilePicker() }, LinearLayout.LayoutParams(-1, -2))
            addView(item("🧰", R.string.servers_add_tools) { appTools.showToolsHub() }, LinearLayout.LayoutParams(-1, -2))
            addView(item("🐱", R.string.servers_add_vercel_panel) { vercelRelay.showPanel() }, LinearLayout.LayoutParams(-1, -2))
            addView(item("🔺", R.string.servers_add_vercel) { vercelRelay.show() }, LinearLayout.LayoutParams(-1, -2))
            addView(wizardText(getString(R.string.servers_add_file_hint), secondary = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        sheet.setContentView(body)
        (body.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.show()
    }

    internal fun showCloudDeploymentDialog(result: CloudflareWorker.DeploymentResult) {
        PanelDeploymentStore(this).rememberLast(result.workerUrl, result.uuid)
        renderCloudDeploymentHistory()
        val status = panelStatusLine(
            verifiedOnline = result.verifiedOnline,
            health = if (result.panelUnreachable) null else result.health,
            bundledVersion = result.bundledVersion,
            panelVersion = result.panelVersion,
        )
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

    internal fun showResetSettingsDialog() {
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

    internal fun scrollFieldIntoView(
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

    internal fun advancedSectionLabel(value: String): TextView {
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

    internal fun connectionChainFixedLabel(
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

    internal fun connectionChainSlotLabelRes(slot: ConnectionChainSlot): Int = when (slot) {
        ConnectionChainSlot.Before -> R.string.connection_chain_before
        ConnectionChainSlot.Base -> R.string.connection_chain_base
        ConnectionChainSlot.After -> R.string.connection_chain_after
    }

    /**
     * The pinned address of one hop: the address that hop's first proxy is dialled at — the
     * only place a bare IP can enter a chain, because a hop itself is a full config.
     */
    internal fun editConnectionChainHopIp(slot: ConnectionChainSlot, hop: ConnectionChainHop) {
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
                TextView(this@MainActivity).apply {
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
                        this@MainActivity,
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

    internal var fragmentCarrierLine: TextView? = null

    /**
     * ISO-2 codes the ACTIVE chain can really exit to — `null` when no chain is running, so
     * callers can tell «no chain» from «a chain that can land nowhere recognisable».
     *
     * Why it exists: with a chain, the location list used to be built from the SELECTED
     * subscription's profiles, i.e. from configs the chain may not even include. The user could
     * pick Germany and the chain would leave from wherever — the setting looked applied and did
     * nothing. This is the list the picker must offer instead.
     */
    private var chainExitCountryCodes: List<String>? = null

    private fun refreshChainExitCountries() {
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
                    DiagnosticLogger.warn(this@MainActivity, "activity.chain.exit.failed", error = error)
                }.getOrDefault(emptyList())
            }
            chainExitCountryCodes = codes
            DiagnosticLogger.info(this@MainActivity, "activity.chain.exit.options", "codes=${codes.joinToString(",")}")
            renderLocationSelection()
        }
    }

    internal fun renderFragmentCarrierLine() {
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

    private fun carrierLabelRes(carrier: MobileCarrier): Int = when (carrier) {
        MobileCarrier.MCI -> R.string.dpi_preset_mci
        MobileCarrier.IRANCELL -> R.string.dpi_preset_irancell
        MobileCarrier.RIGHTEL -> R.string.dpi_preset_rightel
        MobileCarrier.TCI -> R.string.dpi_preset_tci
        MobileCarrier.OTHER, MobileCarrier.UNKNOWN -> R.string.dpi_carrier_unknown
    }

    private fun presetLabelRes(preset: DpiFragmentPreset): Int = when (preset) {
        DpiFragmentPreset.MCI -> R.string.dpi_preset_mci
        DpiFragmentPreset.IRANCELL -> R.string.dpi_preset_irancell
        DpiFragmentPreset.RIGHTEL -> R.string.dpi_preset_rightel
        DpiFragmentPreset.TCI -> R.string.dpi_preset_tci
        DpiFragmentPreset.GAMING -> R.string.dpi_preset_gaming
        DpiFragmentPreset.PATTN -> R.string.dpi_preset_pattn
        DpiFragmentPreset.DEFAULT -> R.string.dpi_preset_default
    }

    internal fun updateConnectionChainSettings(settings: ConnectionChainSettings) {
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

    internal fun openConnectionChainSettingsFromHome() {
        openScreen(SCREEN_SETTINGS)
        openConnectionChainSettingsPage?.invoke()
    }

    internal fun cachedConnectionChainPickerOptions(
        subscriptionIds: Set<String>? = null,
    ): List<ConnectionChainPickerOption> =
        connectionChainPickerOptions(cachedConnectionChainSources(subscriptionIds))

    internal fun cachedConnectionChainSources(
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

    internal fun connectionChainFixedOptions(
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

    internal fun advancedSectionDetail(value: String): TextView {
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

    internal fun advancedSettingsPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            background = glassSurfaceDrawable(radiusDp = 22)
            clipToOutline = true
            elevation = 0f
            setPadding(dp(8), dp(8), dp(8), dp(14))
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

    private fun handleAutomaticConnectionProgress(intent: Intent) {
        automaticConnectionProgress = when (
            intent.getStringExtra(Actions.EXTRA_AUTOMATIC_CONNECTION_PHASE)
        ) {
            Actions.AUTO_PHASE_CHECKING_CACHE -> getString(R.string.auto_connection_checking_cache)
            Actions.AUTO_PHASE_QUICK_SCAN -> getString(R.string.auto_connection_quick_scan)
            Actions.AUTO_PHASE_FALLBACK_SCAN -> getString(R.string.auto_connection_fallback_scan)
            Actions.AUTO_PHASE_RUNTIME_TEST -> getString(R.string.auto_connection_runtime_test)
            else -> ""
        }
        renderConnectionDetails(buttonModel.state)
    }

    private fun handleStateChanged(context: Context, intent: Intent) {
        val previousState = buttonModel.state
        val state = VpnState.fromWireName(
            intent.getStringExtra(Actions.EXTRA_STATE),
            intent.getStringExtra(Actions.EXTRA_ERROR),
        )
        if (state != VpnState.Starting) automaticConnectionProgress = ""
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
        if (state is VpnState.Error && previousState == VpnState.Starting && state.message.isNotBlank() &&
            state.message != lastShownConnectionFault
        ) {
            lastShownConnectionFault = state.message
            window.decorView.post { showConnectionFaultDialog(state.message) }
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

    internal fun handleButtonClick() {
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

    internal fun handleRefreshClick() {
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

    internal fun refreshLocationOptions() {
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

    internal fun handleConnectionSelected(
        profile: ConnectionProfile?,
        selectedTypes: Set<String>,
    ) {
        // beta217: the settings panels (renderAdvancedControls) were redrawn up to twice per tap here; the Cat Warp
        // preference is now settled first and the panels are redrawn once, only if it actually changed.
        val currentAether = aetherPreferenceStore.read()
        var aetherSettingsChanged = false
        if (currentAether.enabled) {
            aetherPreferenceStore.save(currentAether.copy(enabled = false))
            aetherSettingsChanged = true
        }
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
        connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, profile, userPick = true)
        val pickedShareLink = profile?.shareLink.orEmpty()
        if (pickedShareLink.startsWith("aether://", ignoreCase = true) || pickedShareLink.startsWith("masque://", ignoreCase = true)) {
            AetherSelection.fromAetherUri(pickedShareLink)?.let { parsed ->
                aetherPreferenceStore.save(parsed.copy(enabled = true))
                aetherSettingsChanged = true
            }
        }
        if (aetherSettingsChanged) renderAdvancedControls()
        renderConnectionSelection()
        renderLocationSelection()

        val usesActiveRuntime = buttonModel.state == VpnState.Started &&
            activeRuntimeSubscriptionId == selectedSubscriptionId
        val activeSelectionChanged = profile != null &&
            activeConnectionFingerprint != profile.fingerprint
        if (
            profile != null &&
            usesActiveRuntime &&
            liveSelectorReady &&
            profile.fingerprint in liveSelectableConnectionFingerprints &&
            (selectionChanged || activeSelectionChanged)
        ) {
            DiagnosticLogger.info(
                this,
                "activity.connection.switch.requested",
                "profile=${profile.tag}",
            )
            requestActiveConnectionSwitch(selectedSubscriptionId, profile.fingerprint)
            Toast.makeText(this, R.string.connection_switching, Toast.LENGTH_SHORT).show()
            return
        }
        if (!selectionChanged && !activeSelectionChanged) return
        DiagnosticLogger.info(
            this,
            "activity.connection.selected",
            "mode=${if (profile == null) "automatic" else "explicit"} " +
                "profile=${profile?.tag.orEmpty()} types=${automaticTypes.sorted().joinToString(",")}",
        )
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

    internal fun renderHomeConnectionRows() {
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

    internal fun showLocationSelector() {
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
        val chainCodes = chainExitCountryCodes
        if (chainCodes != null) {
            // The chain is in charge of the exit, so its reachable countries ARE the list.
            // A code the chain cannot reach is offered as well when it is currently stored:
            // hiding the user's own choice would look like the setting vanished.
            val locale = resources.configuration.locales[0]
            val stored = locationPreferenceStore.readSelectedCountryCode()
            val codes = buildList {
                addAll(chainCodes)
                if (stored != null && stored !in chainCodes) add(stored)
            }
            val options = codes.mapNotNull { code -> ConnectionLocationPolicy.optionForCode(code, locale) }
            return listOf(automaticLocationOption()) + options
        }
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
        val chainCodes = chainExitCountryCodes
        if (chainCodes != null && option.countryCode != null && option.countryCode !in chainCodes) {
            Toast.makeText(
                this,
                getString(R.string.location_chain_unavailable),
                Toast.LENGTH_LONG,
            ).show()
        }
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

    internal fun showSubscriptionSelectorMenu(anchor: View) {
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

    internal fun showThemeSelector() {
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
    internal fun showAccentSelector() {
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

    internal fun showLanguageSelector() {
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

    internal fun showDnsPrivacySelector() {
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

    internal fun showRoutingModeSelector() {
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

    internal fun commitDnsPrivacyEndpoint(
        reconnectIfChanged: Boolean,
        focusOnError: Boolean = false,
    ): Boolean {
        if (!::dnsPrivacyEndpointInput.isInitialized) return true
        val mode = dnsPrivacyPreferenceStore.readMode()
        if (mode == DnsPrivacyMode.Automatic) return true
        val previousValue = when (mode) {
            DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.readDohUrl()
            DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.readDotEndpoint()
            DnsPrivacyMode.DoQ -> dnsPrivacyPreferenceStore.readDoqEndpoint()
            DnsPrivacyMode.DoH3 -> dnsPrivacyPreferenceStore.readDoh3Url()
            DnsPrivacyMode.Automatic -> return true
        }
        val nextValue = runCatching {
            when (mode) {
                DnsPrivacyMode.DoH -> DnsPrivacyPolicy.normalizeDohUrl(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.DoT -> DnsPrivacyPolicy.normalizeDotEndpoint(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.DoQ -> DnsPrivacyPolicy.normalizeDoqEndpoint(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.DoH3 -> DnsPrivacyPolicy.normalizeDoh3Url(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.Automatic -> return true
            }
        }.getOrElse { error ->
            showDnsPrivacyError(localizedError(error, R.string.dns_invalid), focusOnError)
            return false
        }
        when (mode) {
            DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.saveDohUrl(nextValue)
            DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.saveDotEndpoint(nextValue)
            DnsPrivacyMode.DoQ -> dnsPrivacyPreferenceStore.saveDoqEndpoint(nextValue)
            DnsPrivacyMode.DoH3 -> dnsPrivacyPreferenceStore.saveDoh3Url(nextValue)
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

    internal fun renderDnsPrivacySelection() {
        if (!::dnsPrivacyRow.isInitialized) return
        val mode = dnsPrivacyPreferenceStore.readMode()
        val modeLabel = getString(mode.labelRes)
        dnsPrivacyValueText.text = modeLabel
        dnsPrivacyDetailText.text = when (mode) {
            DnsPrivacyMode.Automatic -> getString(R.string.dns_automatic_detail)
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_detail)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_detail)
            DnsPrivacyMode.DoQ -> getString(R.string.dns_doq_detail)
            DnsPrivacyMode.DoH3 -> getString(R.string.dns_doh3_detail)
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
            DnsPrivacyMode.DoQ -> getString(R.string.dns_doq_address)
            DnsPrivacyMode.DoH3 -> getString(R.string.dns_doh3_address)
            DnsPrivacyMode.Automatic -> ""
        }
        dnsPrivacyEndpointLayout.helperText = when (mode) {
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_helper)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_helper)
            DnsPrivacyMode.DoQ -> getString(R.string.dns_doq_helper)
            DnsPrivacyMode.DoH3 -> getString(R.string.dns_doh3_helper)
            DnsPrivacyMode.Automatic -> ""
        }
        if (!dnsPrivacyEndpointInput.hasFocus()) {
            val value = when (mode) {
                DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.readDohUrl()
                DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.readDotEndpoint()
                DnsPrivacyMode.DoQ -> dnsPrivacyPreferenceStore.readDoqEndpoint()
                DnsPrivacyMode.DoH3 -> dnsPrivacyPreferenceStore.readDoh3Url()
                DnsPrivacyMode.Automatic -> ""
            }
            setDnsPrivacyEndpointInputText(displayDnsPrivacyEndpoint(mode, value))
        }
    }

    /**
     * The Orbot row's verdict. Four states, and each one names what the next connection will
     * actually do — «روشن but nothing listens» must never read like «on Tor».
     */
    internal fun renderOrbotRow() {
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
    internal fun probeOrbotEngine(showToast: Boolean) {
        val host = orbotEnginePreferenceStore.readHost()
        val port = orbotEnginePreferenceStore.readPort()
        orbotProbeButton?.isEnabled = false
        activityScope.launch {
            val probe = kotlinx.coroutines.withContext(Dispatchers.IO) { OrbotProbe.probe(host, port) }
            lastOrbotProbe = probe
            orbotProbeButton?.isEnabled = true
            renderOrbotRow()
            DiagnosticLogger.info(
                this@MainActivity,
                "activity.orbot.probe",
                "host=$host port=$port reachable=${probe.reachable} ms=${probe.latencyMs} detail=${probe.detail}",
            )
            if (showToast) {
                Toast.makeText(
                    this@MainActivity,
                    orbotStatusText?.text?.toString().orEmpty(),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun displayDnsPrivacyEndpoint(mode: DnsPrivacyMode, value: String): String {
        // DoT is the one mode whose scheme is stripped: `tls://` is the only DoT spelling, so
        // hiding it keeps the box to host:port. DoQ and DoH3 keep theirs, because `quic://`
        // and `h3://` are what tell the core which transport to use — and a bare `1.1.1.1`
        // would silently become DoT in the owner's head.
        return when (mode) {
            DnsPrivacyMode.DoT -> value.removePrefix("tls://")
            else -> value
        }
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

    internal fun showSplitTunnelSelector(container: LinearLayout) {
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

    internal fun commitFrontingIpInput(
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

    internal fun saveTlsIntegrityEnabled(enabled: Boolean) {
        val previousValue = tlsIntegrityPreferenceStore.isEnabled()
        if (previousValue == enabled) return
        tlsIntegrityPreferenceStore.saveEnabled(enabled)
        if (!enabled) CatClientScanStateStore(this).clearTlsQuarantine()
        DiagnosticLogger.info(this, "activity.tlsIntegrity.saved", "enabled=$enabled")
        reconnectForConnectionOptionChange()
    }

    internal fun saveAdBlockEnabled(enabled: Boolean) {
        if (routingModePreferenceStore.isAdBlockEnabled() == enabled) return
        routingModePreferenceStore.saveAdBlockEnabled(enabled)
        DiagnosticLogger.info(this, "activity.adBlock.saved", "enabled=$enabled")
        Toast.makeText(this, R.string.ad_block_toast, Toast.LENGTH_SHORT).show()
    }

    /**
     * The carrier can change while the app is open (Wi-Fi ↔ mobile, roaming) and the fragment
     * preset is decided from it at connect time, so the settings line has to follow.
     *
     * This is a default-network callback and not a broadcast: `CONNECTIVITY_ACTION` is
     * deprecated (and its delivery to registered receivers keeps being tightened per release),
     * and the telephony «network operator changed» action is not part of the public SDK —
     * `TelephonyManager.ACTION_NETWORK_OPERATOR_CHANGED` does not exist, which is exactly how
     * it failed to compile here. Re-reading the operator code when the default network
     * changes is the supported signal, and it also covers the roaming/Wi-Fi handovers that
     * matter for a fragment preset. The callback arrives on a binder thread → post to main.
     */
    private val carrierChangeCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = carrierMoved()

        override fun onLost(network: Network) = carrierMoved()

        private fun carrierMoved() {
            CarrierDetector.reset()
            mainHandler.post { renderFragmentCarrierLine() }
        }
    }

    /** [refreshPanelTune] but only when fragmenting is on and the panel URL is known. */
    private fun refreshPanelTuneIfNeeded() {
        if (dpiBypassPreferenceStore.isEnabled()) refreshPanelTune()
    }

    /**
     * Pull the owner's carrier table. Best-effort and off the main thread: [panelBaseUrl]
     * reads the deployment store and can walk the subscriptions, and a panel that is asleep
     * must never be able to delay the screen.
     */
    private fun refreshPanelTune() {
        activityScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                val base = panelBaseUrl() ?: return@withContext null
                runCatching { PanelTuneClient.refresh(panelTuneStore, base) }.getOrNull()
            }
            if (refreshed != null) {
                DiagnosticLogger.info(
                    this@MainActivity,
                    "panel.tune.synced",
                    "version=${refreshed.version} carriers=${refreshed.presets.size}",
                )
                renderFragmentCarrierLine()
            }
        }
    }

    internal fun saveTlsFragmentEnabled(enabled: Boolean) {
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

    internal fun saveLanSharingEnabled(enabled: Boolean) {
        if (lanSharingPreferenceStore.read().enabled == enabled) return
        lanSharingPreferenceStore.saveEnabled(enabled)
        DiagnosticLogger.info(this, "activity.lanSharing.saved", "enabled=$enabled")
        renderLanSharingControls(settingsEnabled = true)
        reconnectForConnectionOptionChange()
    }

    internal fun saveConnectionMode(mode: ConnectionMode) {
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

    internal fun saveLanSharingPasswordRequired(required: Boolean) {
        val settings = lanSharingPreferenceStore.read()
        if (settings.passwordRequired == required) return
        lanSharingPreferenceStore.savePasswordRequired(required)
        DiagnosticLogger.info(this, "activity.lanSharing.passwordRequired.saved", "required=$required")
        renderLanSharingControls(settingsEnabled = true)
        if (settings.enabled) reconnectForConnectionOptionChange()
    }

    internal fun regenerateLanSharingPassword() {
        val settings = lanSharingPreferenceStore.read()
        lanSharingPreferenceStore.regeneratePassword()
        DiagnosticLogger.info(this, "activity.lanSharing.password.regenerated", "enabled=${settings.enabled}")
        renderLanSharingControls(settingsEnabled = true)
        if (settings.enabled && settings.passwordRequired) reconnectForConnectionOptionChange()
    }

    internal fun copyLanSharingSettings() {
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

    internal fun saveAmneziaNoiseEnabled(enabled: Boolean) {
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

    internal fun applyAmneziaNoiseSettings() {
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

    internal fun reconnectForConnectionOptionChange() {
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

    internal fun saveFrontingIps(reconnectIfChanged: Boolean, previousValue: String?): Boolean {
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

    internal fun addFrontingIpTokens(tokens: List<String>, focusOnError: Boolean): Boolean {
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

    internal fun renderFrontingIpChips() {
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

    internal fun setFrontingIpInputText(value: String) {
        frontingIpInputUpdating = true
        try {
            frontingIpInput.setText(value)
            frontingIpInput.setSelection(frontingIpInput.text.length)
        } finally {
            frontingIpInputUpdating = false
        }
    }

    internal fun frontingIpInputHint(): String {
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

    internal fun renderAdvancedControls() {
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
        refreshAetherEngineSummary?.invoke()
    }

    internal fun beginConnectFlow(action: String = Actions.CONNECT) {
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

    internal fun startVpnService(action: String) {
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

    internal fun renderAlwaysOnStatus() {
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
    internal fun activePhysicalNetwork(): android.net.Network? = runCatching {
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
            // beta204: the direct fallback is only a TUNNEL ping when this app's own traffic really rides the VPN.
            // With a child-process engine (Psiphon/Tor/…) the app excludes itself, so a «direct» sample measured the
            // phone's bare network and showed a fake, healthy-looking number for a dead config.
            if (samples.isEmpty() && started && appTrafficRidesVpn()) samples = (1..3).mapNotNull { sample(false) }
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

    /** True when this app's default network is the VPN (Android answers per UID, so an excluded app sees the bare network). */
    private fun appTrafficRidesVpn(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    /** Manual "sync with the site" — forces a fresh family-forced probe so
     * the dashboard can be compared against whatismyipaddress right away. */
    internal fun refreshDashboardIp() {
        liveGeoDirectAttempted = false
        liveGeo = null
        beginLiveGeoCheck(force = true)
        Toast.makeText(this, R.string.ip_refreshing, Toast.LENGTH_SHORT).show()
    }

    /** Ping EVERY profile of the active subscription through the running core
     * (the service's own concurrent delay sweep) and, with [autoConnect],
     * switch to the fastest one — v2rayNG 'Ping All' + best-connect. */
    internal fun runPingAll(autoConnect: Boolean) {
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
                    connectionSelectionPreferenceStore.saveSelectedProfile(subscriptionId, bestProfile, userPick = true)
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

    internal fun currentVpnStateIsStarted(): Boolean = vpnCurrentlyStarted

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

    internal fun applyConnectPillStyle(state: VpnState) {
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

    internal fun connectHapticEnabled(): Boolean =
        getSharedPreferences("cat_client_theme", MODE_PRIVATE).getBoolean("haptic_connect", true)

    internal fun connectHaptic() {
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

    internal fun isPersianUi(): Boolean = appLanguagePreferenceStore.read() == AppLanguage.Persian

    /** ZedSecure-style fault card: a failed connect keeps its reason on screen instead of looking
     * like a normal disconnect, with a plain-language "what to do" hint and the raw detail. */
    private fun showConnectionFaultDialog(raw: String) {
        if (isFinishing || isDestroyed) return
        val fa = isPersianUi()
        val fault = ConnectionFaultExplainer.explain(raw)
        val body = buildString {
            append(fault.hint(fa))
            append("\n\n")
            append(if (fa) "جزئیات فنی:\n" else "Technical detail:\n")
            append(fault.raw.take(400))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ " + fault.title(fa))
            .setMessage(body)
            .setPositiveButton(if (fa) "تلاش دوباره" else "Try again") { _, _ ->
                if (buttonModel.state !is VpnState.Starting && buttonModel.state != VpnState.Started) {
                    connectActionButton.performClick()
                }
            }
            .setNeutralButton(if (fa) "کپی جزئیات" else "Copy details") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cat Client error", "${fault.title(false)}: ${fault.raw}"))
                Toast.makeText(this, if (fa) "کپی شد" else "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (fa) "بستن" else "Close", null)
            .show()
    }

    internal fun renderState(state: VpnState) {
        val presentation = DashboardStatePresenter.forState(state)
        if (state == VpnState.Started) renderRealInternetCard(lastRealInternetResult) else realInternetViews?.card?.visibility = View.GONE
        if (state == VpnState.Started && lastHapticState != null && lastHapticState != VpnState.Started && connectHapticEnabled()) connectHaptic()
        lastHapticState = state
        if (!presentation.showTransferSpeeds) resetTransferSpeeds()
        val accent = accentFor(presentation.tone)
        connectionGlobe.setVpnState(state)
        portalView?.setVpnState(state)
        pixelCore?.setVpnState(state)
        if (state != VpnState.Started) {
            portalView?.setCenterText("")
            pixelCore?.setCenterText("")
        }
        if (::connectionBlob.isInitialized) {
            connectionBlob.setVpnState(state)
            if (state != VpnState.Started) connectionBlob.setCenterText("C")
        }
        if (::heroStateText.isInitialized) {
            if (state is VpnState.Error && state.message.isNotBlank()) {
                val fault = ConnectionFaultExplainer.explain(state.message)
                heroStateText.text = getString(presentation.titleRes)
                heroStateText.contentDescription = fault.title(isPersianUi())
                heroStateText.setOnClickListener { showConnectionFaultDialog(state.message) }
            } else {
                heroStateText.text = getString(presentation.titleRes)
                heroStateText.contentDescription = null
                heroStateText.setOnClickListener(null)
                heroStateText.isClickable = false
            }
        }
        if (::homeLocationPill.isInitialized) homeLocationPill.visibility = if (state == VpnState.Started) View.VISIBLE else View.GONE
        if (::heroPingAction.isInitialized) heroPingAction.visibility = if (state == VpnState.Started) View.VISIBLE else View.GONE
        applyZedHomeState(state)
        applyHomeBackdrop(state)
        connectActionButton.setText(buttonModel.labelRes())
        connectActionButton.isEnabled = buttonModel.isEnabled()
        connectActionButton.contentDescription = getString(buttonModel.labelRes())
        applyConnectPillStyle(state)
        setDockExpanded(state == VpnState.Started)
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
        if (buttonModel.state == VpnState.Started && sessionStartedAtElapsedMs > 0L) {
            val clock = formatDuration(elapsedMs).removePrefix("00:")
            portalView?.setCenterText(clock)
            pixelCore?.setCenterText(clock)
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
            setSpeedText(downloadSpeedText, rx)
            setSpeedText(uploadSpeedText, tx)
            renderTrafficBars(rx, tx)
        } else {
            if (sessionRxStartBytes < 0L) {
                sessionRxStartBytes = rxBytes
                sessionTxStartBytes = txBytes
            }
            setSpeedText(downloadSpeedText, 0L)
            setSpeedText(uploadSpeedText, 0L)
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
        portalView?.setRates(rxPerSecond, txPerSecond)
        pixelCore?.setRates(rxPerSecond, txPerSecond)
        for ((badge, download) in flowBadges) {
            val rate = if (download) rxPerSecond else txPerSecond
            // Zed FlowMeter: the badge turns while data is moving through that direction
            if (rate > 2_048L) badge.animate().rotationBy(30f).setDuration(900L).start()
        }
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
    internal fun selectedRowColor(): Int = androidx.core.graphics.ColorUtils.blendARGB(palette.surfaceElevated1, TEAL, if (palette.isDark) 0.28f else 0.22f)

    /** Page backdrop: ZedSecure paints the whole home violet → cyan while connected, flat otherwise. */
    internal fun applyHomeBackdrop(state: VpnState) {
        val drawable = liveBackdrop ?: ZedLiveBackdropDrawable(resources.displayMetrics.density).also {
            liveBackdrop = it
            it.setThemeColors(palette.teal, palette.secondary, AppAccentPreferenceStore(this).read() == AppAccent.Lavender)
        }
        drawable.setVpnState(state)
        syncShellBackdrop()
    }

    /** Zed paints the Home gradient edge to edge — behind the status bar and under the floating dock. */
    private fun syncShellBackdrop() {
        if (appDesign != AppDesign.Old) {
            // Pixel/Portal keep a calm, flat page — the stage itself carries the colour.
            homeBackdrop?.background = null
            liveBackdrop?.stop()
            appRootView?.let { root -> if (root.background !is ColorDrawable) root.setBackgroundColor(BACKGROUND) }
            return
        }
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
            setSpeedText(downloadSpeedText, 0L)
            setSpeedText(uploadSpeedText, 0L)
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

    internal fun copyDiagnosticsToClipboard() {
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

    /**
     * 🩺 Three-point panel test: `/health`, `/api/version`, `/api/selfcheck?throw=1`.
     *
     * The three answers separate the two meanings of «Error 1101» — JSON back means the
     * module loaded and ran (so the failure is per-request and the panel reports it
     * itself), anything else means the file never loaded (re-upload; only if that
     * repeats on a fresh name is it a suspension). Runs against the deployed worker, or
     * against a URL the owner pastes when nothing is stored.
     */
    internal fun showPanelProbeDialog() {
        val deployed = runCatching {
            PanelDeploymentStore(this).deployments().firstOrNull()?.workerUrl
        }.getOrNull().orEmpty().trim()
        if (deployed.isBlank()) {
            val input = EditText(this).apply {
                hint = getString(R.string.panel_probe_url_hint)
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                setText("https://")
                setSelection(text.length)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.panel_probe_title)
                .setView(input)
                .setPositiveButton(R.string.panel_probe_title) { _, _ ->
                    val typed = input.text.toString().trim()
                    if (typed.isBlank() || typed == "https://") {
                        Toast.makeText(this, R.string.panel_probe_need_url, Toast.LENGTH_SHORT).show()
                    } else {
                        runPanelProbe(typed)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showCatClientDialog()
            return
        }
        runPanelProbe(deployed)
    }

    private fun runPanelProbe(workerUrl: String) {
        Toast.makeText(this, R.string.panel_probe_running, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            val probes = withContext(Dispatchers.IO) {
                runCatching { CloudflareWorker.probePanelRoutes(workerUrl) }.getOrDefault(emptyList())
            }
            if (probes.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.panel_probe_need_url, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val text = buildString {
                append(workerUrl.trimEnd('/')).append('\n')
                probes.forEach { probe ->
                    append('\n').append(probe.path).append("  →  HTTP ").append(probe.status)
                    if (probe.contentType.isNotBlank()) append("  ").append(probe.contentType)
                    append('\n')
                    append(
                        getString(
                            when (probe.verdict) {
                                "code-ran" -> R.string.panel_probe_ran
                                "code-never-ran" -> R.string.panel_probe_never
                                "unreachable" -> R.string.panel_probe_unreachable
                                else -> R.string.panel_probe_other
                            },
                        ),
                    ).append('\n')
                    if (probe.bodyHead.isNotBlank()) append("   ").append(probe.bodyHead.take(140)).append('\n')
                }
                append('\n')
                append(getString(if (probes.any { it.codeRan }) R.string.panel_probe_hint_ran else R.string.panel_probe_hint_never))
            }
            DiagnosticLogger.info(this@MainActivity, "panel.probe", text.replace('\n', ' ').take(400))
            val view = TextView(this@MainActivity).apply {
                this.text = text
                textSize = 10.5f
                typeface = CatClientDataTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                setTextIsSelectable(true)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val scroll = android.widget.ScrollView(this@MainActivity).apply { addView(view) }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.panel_probe_title)
                .setView(scroll)
                .setPositiveButton(R.string.panel_probe_copy) { _, _ ->
                    (getSystemService(ClipboardManager::class.java))
                        .setPrimaryClip(ClipData.newPlainText("Cat Client panel probe", text))
                    Toast.makeText(this@MainActivity, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Copies the last crash (thread + version + stack) for a chat paste. */
    internal fun copyCrashToClipboard() {
        val crash = runCatching { CrashWatch.lastCrashText(this) }.getOrDefault("")
        if (crash.isBlank()) {
            Toast.makeText(this, R.string.crash_report_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("Cat Client crash", crash).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
        DiagnosticLogger.info(this, "crash.copy", "chars=${crash.length}")
    }

    internal fun shareDiagnostics() {
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

    internal fun checkForUpdates() {
        appUpdateUi.check(manual = false)
    }

    internal fun openFooterLink() = openExternalUrl(getString(R.string.footer_url))

    internal fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
            DiagnosticLogger.warn(this, "external.open.failed", "url=$url", error)
        }
    }

    /** Opens a parameter-free Cloudflare account link and explains the order. */
    internal fun openAccountEntry(url: String) {
        openExternalUrl(url)
        Toast.makeText(this, R.string.cloud_account_after, Toast.LENGTH_LONG).show()
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

    internal fun glassSurfaceDrawable(radiusDp: Int, highlighted: Boolean = false): GradientDrawable {
        val surfaceStyle = AppSurfaceStylePreferenceStore(this).read()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            when (surfaceStyle) {
                AppSurfaceStyle.Amoled -> {
                    val baseFill = if (palette.isDark) {
                        if (highlighted) 0xFF101018.toInt() else 0xFF07070C.toInt()
                    } else {
                        if (highlighted) palette.surfaceElevated2 else palette.surfaceElevated1
                    }
                    setColor(baseFill)
                    setStroke(dp(1), withAlpha(if (highlighted) TEAL else OUTLINE, if (highlighted) 195 else 155))
                }
                AppSurfaceStyle.Neon -> {
                    orientation = GradientDrawable.Orientation.TL_BR
                    val startFill = androidx.core.graphics.ColorUtils.blendARGB(
                        if (highlighted) palette.surfaceElevated2 else palette.surfaceElevated1,
                        TEAL,
                        if (palette.isDark) 0.14f else 0.10f,
                    )
                    val endFill = androidx.core.graphics.ColorUtils.blendARGB(
                        palette.surface,
                        palette.secondary,
                        if (palette.isDark) 0.12f else 0.08f,
                    )
                    colors = intArrayOf(startFill, endFill)
                    setStroke(dp(if (highlighted) 2 else 1), withAlpha(TEAL, if (highlighted) 225 else 170))
                }
                AppSurfaceStyle.Glass -> {
                    orientation = GradientDrawable.Orientation.TL_BR
                    val topColor = if (highlighted) palette.surfaceElevated2 else palette.surfaceElevated1
                    val bottomColor = if (highlighted) palette.surfaceElevated1 else palette.surface
                    colors = intArrayOf(topColor, bottomColor)
                    setStroke(dp(1), withAlpha(if (highlighted) TEAL else OUTLINE, if (highlighted) 175 else 120))
                }
            }
        }
    }

    internal fun AlertDialog.showCatClientDialog(
        positiveColor: Int = TEAL,
        onShow: AlertDialog.() -> Unit = {},
    ) {
        if (isFinishing || isDestroyed) return
        setOnShowListener {
            styleCatClientDialog(positiveColor)
            onShow(this)
        }
        runCatching { show() }
    }

    private fun AlertDialog.styleCatClientDialog(positiveColor: Int) {
        window?.setBackgroundDrawable(glassSurfaceDrawable(radiusDp = 24))
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
            setTextColor(TEXT_PRIMARY)
            typeface = CatClientBodyBoldTypeface
        }
        findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(TEXT_PRIMARY)
            typeface = CatClientBodyTypeface
        }
        val optionColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(TEAL, TEXT_PRIMARY),
        )
        listView?.let { options ->
            fun styleItem(child: View?) {
                (child as? TextView)?.apply {
                    setTextColor(optionColors)
                    typeface = CatClientBodyTypeface
                }
            }
            repeat(options.childCount) { index ->
                styleItem(options.getChildAt(index))
            }
            options.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) = styleItem(child)
                override fun onChildViewRemoved(parent: View?, child: View?) = Unit
            })
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

    internal fun catClientPopupMenu(anchor: View): PopupMenu =
        PopupMenu(ContextThemeWrapper(this, R.style.CatClientPopupTheme), anchor)

    internal fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    internal fun View.setSelectableBackground() {
        val value = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            setBackgroundResource(value.resourceId)
        }
    }

    @Suppress("DEPRECATION")
    internal fun View.announceAccessibility(message: String) = announceForAccessibility(message)

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun accentFor(tone: DashboardTone): Int = when (tone) {
        DashboardTone.Connected -> TEAL
        DashboardTone.Progress -> AMBER
        DashboardTone.Error -> ERROR
        DashboardTone.Neutral -> palette.neutral
    }

    internal companion object {
        // Dock cell indices (visual order) and content screen indices (fixed, used all over).
        const val DOCK_HOME = 0
        const val DOCK_SERVERS = 1
        const val DOCK_AETHER = 2
        const val DOCK_CLOUD = 3
        const val DOCK_SETTINGS = 4
        const val SCREEN_SERVERS = 0
        const val SCREEN_HOME = 1
        const val SCREEN_SETTINGS = 2
        const val SCREEN_CLOUD = 3
        const val SCREEN_SCANNER = 4
        const val SCREEN_AETHER = 5
        const val REQUEST_VPN_PERMISSION = 10
        const val REQUEST_NOTIFICATION_PERMISSION = 11
        const val REQUEST_NODE_RENEW_NOTIFICATION = 7301
        const val TIMER_TICK_MS = 1_000L
        const val KEYBOARD_SCROLL_DELAY_MS = 250L
        const val CONNECTION_TESTING_PAGE_ANIMATION_MS = 300L
        const val STATE_ACTIVE_SCREEN = "active_screen"
        const val STATE_SETTINGS_CATEGORY_RES = "settings_category_res"
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
        /** Rows the 🏠/☁️ ping-jitter pass measures after a scan (best verified first). */
        const val SCANNER_MEASURE_ROWS = 12
        const val SCANNER_BUILD_LIMIT = 12
        val SCANNER_BUILD_PORTS = listOf(80, 443, 2053)
        const val SCANNER_VISIBLE_RESULTS = 40
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
