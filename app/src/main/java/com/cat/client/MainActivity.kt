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
    internal val nodeHosts: NodeHostController by lazy {
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
    internal lateinit var privacyPolicyStore: PrivacyPolicyAcceptanceStore
    internal lateinit var appLanguagePreferenceStore: AppLanguagePreferenceStore
    internal lateinit var appThemePreferenceStore: AppThemePreferenceStore
    internal lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    internal lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    internal lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    internal lateinit var routingModePreferenceStore: RoutingModePreferenceStore
    internal lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    /** Orbot / Tor engine switch + the multi-path watchdog that may take it over. */
    internal lateinit var orbotEnginePreferenceStore: OrbotEnginePreferenceStore
    internal lateinit var pathWatchdogStore: PathWatchdogStore
    internal lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    internal lateinit var dpiBypassPreferenceStore: DpiBypassPreferenceStore
    internal lateinit var panelTuneStore: PanelTuneStore
    internal lateinit var connectionOptionsPreferenceStore: MihomoConnectionOptionsPreferenceStore
    internal lateinit var connectionModePreferenceStore: ConnectionModePreferenceStore
    internal lateinit var lanSharingPreferenceStore: LanSharingPreferenceStore
    internal lateinit var connectionSelectionPreferenceStore: ConnectionSelectionPreferenceStore
    internal lateinit var connectionTestSettingsPreferenceStore: ConnectionTestSettingsPreferenceStore
    internal lateinit var connectionChainPreferenceStore: ConnectionChainPreferenceStore
    internal lateinit var aetherPreferenceStore: AetherPreferenceStore
    internal lateinit var installedAppRepository: InstalledAppRepository
    internal lateinit var userSubscriptionManager: UserSubscriptionManager
    internal var privacyPolicyDialog: AlertDialog? = null
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
    internal var scannerSpeedTestRunning = false
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
    internal var freeConfigsFetching = false
    internal var freeConfigsJob: Job? = null
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
    internal fun vpnStateEqualsStarted(): Boolean =
        buttonModel.state == VpnState.Started || VpnRuntimeStateStore.read(this) == VpnState.Started

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

    /* ---- share a single connection (long-press on a row) ---- */

    /* ---- share a remote subscription with other clients (v2rayNG / V2Box / …) ---- */

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

    /* ============================== Cloud tab ============================== */

    /* ------------------------------------------------------------------ */
    /* IP scanner screen: clean Cloudflare IPs (SNI + fronting)            */
    /* ------------------------------------------------------------------ */

    /** Detected panel identity (uuid + WS paths) for rebuilding configs with new IPs. */
    internal data class PanelIdentity(
        val host: String,
        val uuid: String,
        val vlessPath: String,
        val trojanPath: String,
        val trojanPassword: String,
    )

    internal var scannerSourceRow: LinearLayout? = null
    internal var scannerSourceSummary: TextView? = null
    internal var scannerForceRow: LinearLayout? = null

    internal var ipHealthJob: Job? = null
    internal var ipHealthSweeping = false
    internal var ipHealthStatusView: TextView? = null
    internal var ipHealthCountryRow: LinearLayout? = null

    // Category chips under the results (like the reference screenshots): all /
    // v4-only / v6-only — v6 chip lights up once the walk found v6 endpoints.
    internal var scannerFilterAll: android.widget.TextView? = null
    internal var scannerFilterV4: android.widget.TextView? = null
    internal var scannerFilterV6: android.widget.TextView? = null
    internal var scannerFilter: String = "all"
    /** Results table: which column is sorted, and in which direction. */
    internal var scannerSortKey: String = "ping"
    internal var scannerSortAsc: Boolean = true
    /** `ip:port` of every row the owner ticked; survives re-renders and re-sorts. */
    internal val scannerSelected = linkedSetOf<String>()
    /** Header cells, rebuilt once per page: (view, sort key, base label). */
    internal val scannerHeaderCells = mutableListOf<Triple<TextView, String, String>>()
    internal var scannerSelectionText: TextView? = null
    internal lateinit var scannerCopySelectedButton: MaterialButton
    internal lateinit var scannerSendSelectedButton: MaterialButton
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
    internal var scannerForeignRunning: Boolean = false
    internal lateinit var scannerForeignList: LinearLayout
    internal lateinit var scannerForeignShareButton: MaterialButton
    internal val scannerForeignResults = linkedMapOf<String, ForeignServiceProbeResult>()
    internal var scannerCountryText: TextView? = null
    internal var scannerHistoryText: TextView? = null
    /** Set by [onTrimMemory] when the system reports a low heap: fewer rows, same data. */
    internal var lowMemoryRows: Boolean = false

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

    internal var scannerPanelPushProgressBar: ProgressBar? = null
    internal var scannerPanelPushStatusText: TextView? = null

    /* ------------------------------------------------------------------ */
    /* Panel status & one-tap recovery (Cloudflare abuse-report 1101)      */
    /* ------------------------------------------------------------------ */

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

    internal data class AetherNetworkProbeCandidate(
        val titleFa: String,
        val titleEn: String,
        val baseMode: AetherMode,
        val ipScan: String,
        val endpoint: String,
        val reachable: Boolean,
        val rttMs: Long,
    )

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

    // ---------------------------------------------------------------------------------------------------------
    // 🔐 Amnezia section: everything for AmneziaVPN keys (vpn://) and AmneziaWG / WireGuard .conf in one place.
    // ---------------------------------------------------------------------------------------------------------

    internal val amneziaSubscriptionPrefix = "🔐 Amnezia"

    internal class AmneziaPreview(val links: Int, val server: String, val ok: List<String>, val bad: List<String>, val problem: String?)

    // ---------------------------------------------------------------- beta212: Cat SSTP

    internal val catSstpSubscriptionName = "🌏 Cat SSTP"
    internal val CAT_SSTP_LIST_LIMIT = 40

    internal var techNamoozDeployInProgress = false

    internal var panelUpdateInProgress = false

    @Volatile
    internal var newestPanelCache: PanelUpdate.PanelScript? = null

    /* ------------------------------------------------------------------ */
    /* Panel wizard — the "I have nothing yet" path                          */
    /* ------------------------------------------------------------------ */

    /** Step 4/4 — done: import + connect, scan clean IPs, open the panel. */
    internal fun showPanelWizardDone(result: CloudflareWorker.DeploymentResult) {
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


    /* ------------------------------------------------------------------ */
    /* Static IP + multi-location + SNI scanner (Scanner tab)               */
    /* ------------------------------------------------------------------ */

    internal fun String.dropLeadingZeros(): String = dropWhile { it == '0' }.ifEmpty { "0" }

    /* ------------------------------------------------------------------ */
    /* Add server sheet (ZedSecure "Add server")                             */
    /* ------------------------------------------------------------------ */

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
    internal var chainExitCountryCodes: List<String>? = null

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

    internal fun updateLocationOptions(
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

    internal fun renderConnectionSelection() {
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

    internal fun renderLocationSelection() {
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

    internal fun renderRoutingModeSelection() {
        if (!::routingModeRow.isInitialized) return
        val mode = routingModePreferenceStore.read()
        val modeLabel = getString(mode.labelRes)
        routingModeValueText.text = modeLabel
        routingModeDetailText.setText(mode.detailRes)
        routingModeRow.contentDescription = getString(R.string.routing_content_description, modeLabel)
    }

    internal fun handleDnsPrivacySelected(mode: DnsPrivacyMode): Boolean {
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

    internal fun renderLanSharingControls(settingsEnabled: Boolean) {
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

    internal fun renderConnectionOptionsControls(settingsEnabled: Boolean) {
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
