package com.cat.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.follow.clash.core.Core
import com.follow.clash.core.TunInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class MihomoCoreBusyException(cause: Throwable? = null) :
    IOException("Cat core is still finishing startup. Please try again.", cause)

internal class MihomoCoreSetupTimeoutException :
    IOException("Cat core setup did not finish within 60 seconds")

internal data class ConnectionStartupExclusion(
    val subscriptionId: String = "",
    val endpoint: CleanIpResult? = null,
    val profileFingerprint: String = "",
) {
    fun forSubscription(id: String): ConnectionStartupExclusion =
        takeIf { subscriptionId == id } ?: ConnectionStartupExclusion()
}

internal enum class AutomaticBridgeFailurePhase {
    ConfigCoreOrController,
    Selector,
    HttpHealth,
}

internal fun isNonRetryableStartupFailure(error: Throwable): Boolean =
    error is CancellationException ||
        error is MihomoCoreBusyException ||
        error is MihomoCoreSetupTimeoutException

internal fun shouldRetryOriginalAfterAutomaticBridgeFailure(
    bridgeApplied: Boolean,
    phase: AutomaticBridgeFailurePhase,
    error: Throwable,
): Boolean = bridgeApplied &&
    phase != AutomaticBridgeFailurePhase.HttpHealth &&
    !isNonRetryableStartupFailure(error)

internal fun shouldPublishStartupError(startupActive: Boolean, state: VpnState): Boolean =
    startupActive && (state == VpnState.Starting || state == VpnState.Started)

internal fun shouldStopServiceAfterConnectionTest(state: VpnState, hasTests: Boolean = false): Boolean =
    !hasTests && (state == VpnState.Stopped || state == VpnState.DailyLimitReached || state is VpnState.Error)

internal fun disconnectTerminalState(coreStopped: Boolean, errorMessage: String): VpnState =
    if (coreStopped) VpnState.Stopped else VpnState.Error(errorMessage)

internal fun shouldRunPostConnectHealthWatchdog(
    state: VpnState,
    awaitingPreservedRuntimeHealth: Boolean,
): Boolean = state == VpnState.Started || (state == VpnState.Starting && awaitingPreservedRuntimeHealth)

internal fun canStartVpnRefresh(
    state: VpnState,
    automatic: Boolean,
    awaitingPreservedRuntimeHealth: Boolean,
): Boolean = state == VpnState.Started ||
    (automatic && state == VpnState.Starting && awaitingPreservedRuntimeHealth)

internal fun shouldExcludeRecoveryChainPlan(
    excludedFingerprint: String,
    finalFingerprint: String,
    finalHopMode: ConnectionChainHopMode,
): Boolean = excludedFingerprint.isNotBlank() &&
    finalHopMode == ConnectionChainHopMode.Automatic &&
    finalFingerprint == excludedFingerprint

internal fun quickSpeedNetworkUnchanged(startKey: String, currentKey: String): Boolean =
    startKey == currentKey

internal fun defaultNetworkStateChanged(
    force: Boolean,
    networkKey: String,
    dns: String,
    previousNetworkKey: String?,
    previousDns: String,
): Boolean = force || networkKey != previousNetworkKey || dns != previousDns

internal suspend fun <T> connectWithStartupFallback(
    primary: suspend () -> T,
    fallback: suspend (Throwable) -> T,
): T {
    return try {
        primary()
    } catch (error: Throwable) {
        if (isNonRetryableStartupFailure(error)) {
            throw error
        }
        fallback(error)
    }
}

internal object PostConnectHealthPolicy {
    const val INITIAL_CHECK_DELAY_MS = 5_000L
    const val FAILURE_RECHECK_DELAY_MS = 5_000L
    const val CHECK_INTERVAL_MS = 60_000L
    const val FAILURES_BEFORE_RECOVERY = 2
    const val RECOVERY_COOLDOWN_MS = 10 * 60_000L

    fun shouldRecover(consecutiveFailures: Int, nowElapsedMs: Long, lastRecoveryElapsedMs: Long): Boolean {
        return consecutiveFailures >= FAILURES_BEFORE_RECOVERY &&
            (lastRecoveryElapsedMs <= 0L || nowElapsedMs - lastRecoveryElapsedMs >= RECOVERY_COOLDOWN_MS)
    }

    fun isHealthyStatus(statusCode: Int): Boolean = statusCode in 200..399

    fun preservedRuntimeState(hasUsableDefaultNetwork: Boolean, statusCode: Int): VpnState? = when {
        !hasUsableDefaultNetwork -> VpnState.Starting
        isHealthyStatus(statusCode) -> VpnState.Started
        else -> null
    }
}

internal object DefaultNetworkDnsReplayPolicy {
    const val MAX_RETRY_ATTEMPTS = 1
}

internal object BuiltInSubscriptionStartupPolicy {
    fun sourceIds(selectedSubscriptionId: String, explicitProfile: ConnectionProfile?): List<String> =
        if (
            SubscriptionStore.isBuiltInSubscription(selectedSubscriptionId) &&
            explicitProfile == null
        ) {
            SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS
        } else {
            listOf(selectedSubscriptionId)
        }

    fun canFallback(error: Throwable): Boolean =
        error is IOException &&
            error !is MihomoCoreBusyException &&
            error !is MihomoCoreSetupTimeoutException

    suspend fun <T> firstSuccessful(
        sourceIds: List<String>,
        onFallback: (from: String, to: String, error: Throwable) -> Unit = { _, _, _ -> },
        attempt: suspend (String) -> T,
    ): T {
        require(sourceIds.isNotEmpty())
        sourceIds.forEachIndexed { index, sourceId ->
            try {
                return attempt(sourceId)
            } catch (error: Throwable) {
                if (index == sourceIds.lastIndex || !canFallback(error)) throw error
                onFallback(sourceId, sourceIds[index + 1], error)
            }
        }
        error("No subscription source was attempted")
    }
}

internal fun connectedNotificationTextRes(subscriptionId: String): Int =
    if (subscriptionId == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID) {
        R.string.notification_connected_public
    } else {
        R.string.notification_connected
    }

internal object VpnTunnelNetwork {
    const val IPV4_ADDRESS = "172.19.0.1"
    const val IPV4_PREFIX_LENGTH = 30
    const val IPV4_DNS_SERVER = "172.19.0.2"
    const val IPV4_DEFAULT_ROUTE = "0.0.0.0"
    const val IPV6_ADDRESS = "fdfe:dcba:9876::1"
    const val IPV6_PREFIX_LENGTH = 126
    const val IPV6_DNS_SERVER = "fdfe:dcba:9876::2"
    const val IPV6_DEFAULT_ROUTE = "::"

    val addresses = listOf(IPV4_ADDRESS to IPV4_PREFIX_LENGTH, IPV6_ADDRESS to IPV6_PREFIX_LENGTH)
    val defaultRoutes = listOf(IPV4_DEFAULT_ROUTE, IPV6_DEFAULT_ROUTE)
    val dnsServers = listOf(IPV4_DNS_SERVER, IPV6_DNS_SERVER)
    val coreAddresses = addresses.joinToString(",") { (address, prefixLength) -> "$address/$prefixLength" }
    val coreDnsServers = dnsServers.joinToString(",")
}

// Process-local: a cached Started value is not evidence that this service still exists.
@Volatile
internal var currentVpnServiceState: VpnState = VpnState.Stopped
    private set

@Volatile
private var currentVpnService: CatClientVpnService? = null

internal fun protectOutboundSocket(socket: java.net.Socket): Boolean =
    runCatching {
        val service = currentVpnService ?: return@runCatching true
        // beta204: a fresh `Socket()` has no file descriptor until its impl is created (first option / bind /
        // connect), and protect() on it fails silently — the probe then ran THROUGH the tunnel and reported the VPN
        // exit's view instead of this network's. Touching an option creates the descriptor first.
        runCatching { socket.tcpNoDelay = true }
        service.protect(socket)
    }.getOrDefault(false)

/** True for mihomo's fake-ip range (198.18.0.0/15), which only means something inside the tunnel. */
internal fun isMihomoFakeIp(addr: java.net.InetAddress): Boolean {
    val b = addr.address
    return b.size == 4 && (b[0].toInt() and 0xFF) == 198 && ((b[1].toInt() and 0xFF) == 18 || (b[1].toInt() and 0xFF) == 19)
}

/**
 * beta206: resolve the destination of a PROTECTED socket (one that deliberately bypasses the tunnel).
 *
 * While Cat is connected the app's own DNS lookups go to mihomo, which answers in fake-ip mode with 198.18.x.x.
 * A protected socket then dials that fake address on the real network and never connects — this is why the
 * local Firefox VPN / paqet bridges "pinged" (tested before connect) but failed once the VPN was up. Lookups are
 * made on the underlying (non-VPN) network instead, and a fake-ip answer is never returned.
 */
internal fun resolveForProtectedSocket(host: String): java.net.InetAddress {
    val clean = host.trim().removePrefix("[").removeSuffix("]")
    val literal = Regex("""^[0-9.]+$""").matches(clean) ||
        (clean.count { it == ':' } >= 2 && Regex("""^[0-9A-Fa-f:.]+(%[0-9A-Za-z_.-]+)?$""").matches(clean))
    if (literal) return java.net.InetAddress.getByName(clean)
    currentVpnService?.let { service ->
        runCatching {
            val cm = service.getSystemService(android.net.ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val physical = cm.allNetworks.firstOrNull { n ->
                cm.getNetworkCapabilities(n)?.let {
                    !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                        it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
            }
            physical?.getAllByName(clean)?.firstOrNull { !isMihomoFakeIp(it) }
        }.getOrNull()?.let { return it }
    }
    val all = java.net.InetAddress.getAllByName(clean)
    return all.firstOrNull { !isMihomoFakeIp(it) }
        ?: throw java.net.UnknownHostException("$clean resolved only to the tunnel's fake-ip ${all.firstOrNull()?.hostAddress}")
}

internal fun protectOutboundDatagramSocket(socket: java.net.DatagramSocket): Boolean =
    runCatching { currentVpnService?.protect(socket) ?: true }.getOrDefault(false)

internal const val ALWAYS_ON_VPN_SETTING = "always_on_vpn_app"

internal fun vpnAlwaysOnEnabled(context: android.content.Context, fallback: Boolean = VpnRuntimeStateStore.readAlwaysOn(context)): Boolean =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentVpnService?.isAlwaysOn ?: fallback
        } else {
            // Android 8/9 predate isAlwaysOn(); this legacy setting is readable on those releases.
            android.provider.Settings.Secure.getString(context.contentResolver, ALWAYS_ON_VPN_SETTING) == context.packageName
        }
    }.getOrDefault(fallback)

class CatClientVpnService : VpnService() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    internal lateinit var configRepository: ConfigRepository
    internal lateinit var subscriptionStore: SubscriptionStore
    private lateinit var scanStateStore: CatClientScanStateStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    internal lateinit var networkMonitor: DefaultNetworkMonitor
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var cleanIpCache: CleanIpCache
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    internal lateinit var dpiBypassPreferenceStore: DpiBypassPreferenceStore
    internal lateinit var panelTuneStore: PanelTuneStore
    internal lateinit var routingModePreferenceStore: RoutingModePreferenceStore
    internal lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    /** The ORBOT engine switch + loopback address (see resolveOrbotHop). */
    internal lateinit var orbotEnginePreferenceStore: OrbotEnginePreferenceStore
    /** The multi-path watchdog: when it may take over (see resolveOrbotHop / recordPathOutcome). */
    private lateinit var pathWatchdogStore: PathWatchdogStore
    private lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    internal lateinit var connectionOptionsPreferenceStore: MihomoConnectionOptionsPreferenceStore
    private lateinit var connectionModePreferenceStore: ConnectionModePreferenceStore
    private lateinit var lanSharingPreferenceStore: LanSharingPreferenceStore
    internal lateinit var connectionSelectionPreferenceStore: ConnectionSelectionPreferenceStore
    internal lateinit var connectionTestSettingsPreferenceStore: ConnectionTestSettingsPreferenceStore
    private lateinit var connectionChainPreferenceStore: ConnectionChainPreferenceStore
    internal lateinit var aetherPreferenceStore: AetherPreferenceStore

    private var startupJob: Job? = null
    /** Optional native Aether runtime; never runs concurrently with Mihomo. */
    internal var aetherSession: AetherSession? = null
    /** beta212: the live Cat SSTP call and its TUN (never runs together with Mihomo or Cat Warp). */
    @Volatile
    internal var sstpConnection: SstpConnection? = null
    @Volatile
    internal var sstpTunFd: android.os.ParcelFileDescriptor? = null
    internal var aetherPsiphonChain: AetherPsiphonChain? = null
    internal var aetherTorCarrier: AetherProxyCarrier? = null
    private var stopJob: Job? = null
    private var subscriptionRefreshJob: Job? = null
    internal var notificationTrafficJob: Job? = null
    @Volatile
    internal var notificationDownloadBps: Long = 0L
    @Volatile
    internal var notificationUploadBps: Long = 0L
    internal var notificationLastRxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    internal var notificationLastTxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    internal var notificationLastSampleElapsedMs: Long = 0L
    private var postConnectHealthJob: Job? = null
    /* beta220: real internet test after connect + route memory per network (RealInternetCheck.kt, RouteMemory.kt) */
    internal var realCheckJob: Job? = null
    internal var realCheckFingerprint: String = ""
    internal var realCheckHops: Int = 0
    internal var realCheckSessionAutomatic: Boolean = false
    internal val realCheckTried = linkedSetOf<String>()
    internal var realCheckHopPending: Boolean = false
    internal var realCheckLastAtElapsedMs: Long = 0L
    internal lateinit var routeMemoryStore: RouteMemoryStore
    @Volatile
    internal var awaitingPreservedRuntimeHealth = false
    internal var dpiBypassJob: Job? = null
    internal var connectionDelayTestJob: Job? = null
    internal val connectionSpeedTestJobs = ConcurrentHashMap<String, Job>()
    internal val connectionTestControllerMutex = Mutex()
    internal var connectionTestRuntime: ConnectionTestRuntime? = null
    internal lateinit var connectionTestWakeLock: ConnectionTestWakeLock
    internal var destroyed = false

    internal data class ConnectionTestRuntime(
        val subscriptionId: String,
        val controller: MihomoControllerClient,
        val profiles: List<ConnectionProfile>,
        val temporaryCore: Boolean,
        var users: Int = 0,
    )
    internal var connectionSwitchJob: Job? = null
    internal val controllerSelectionMutex = Mutex()
    internal val quickSpeedStartedLock = Any()
    @Volatile
    internal var activeConnectionDelayTestId: String? = null
    @Volatile
    internal var connectionDelayTestPaused = false
    private val uidPackageNameCache = mutableMapOf<Int, String>()
    internal var activeConnectionChained = false
    private var activeChainHopCount = 0

    internal class DpiBypassStartupException(cause: Throwable) :
        IOException("ByeByeDPI failed to start", cause)

    internal class AutomaticRoutingBridgeStartupException(cause: Throwable) :
        IOException("Bridged automatic routing failed during core startup", cause)

    internal data class StartedMihomoRuntime(
        val profile: ConnectionProfile,
        val endpoint: CleanIpResult,
        val delayMs: Long,
        val paths: MihomoRuntimePaths,
        val delayProbeName: String,
        val cacheEndpoint: Boolean,
        val selectedCountryCode: String?,
        val availableProfiles: List<ConnectionProfile>,
        val nativeAutomaticStart: Boolean,
        val subscriptionId: String,
        val selectorRootName: String,
        val selectorRoots: List<String>,
        val quickSpeedPin: QuickSpeedPin? = null,
        val chainHopCount: Int = 0,
    )

    internal data class QuickSpeedPin(
        val paths: MihomoRuntimePaths,
        val networkFingerprint: String,
        val rootName: String,
        val groupName: String,
        val originalFixed: String,
        val selectedName: String,
        val delayMs: Int,
        val graphReads: AtomicInteger,
    )

    internal data class QuickSpeedSelectionResult(
        val response: JSONObject,
        val pin: QuickSpeedPin?,
    )

    internal data class StartupTopIpCandidatePlan(
        val primaryPhase: String,
        val primaryCandidates: List<CleanIpResult>,
        val exhaustiveCandidates: List<CleanIpResult>,
    )

    @Volatile
    internal var state: VpnState = VpnState.Stopped
    @Volatile
    private var sessionStartedAtElapsedMs: Long = 0L
    @Volatile
    internal var activeProfile: ConnectionProfile? = null
    @Volatile
    private var activeDelayMs: Long = -1L
    @Volatile
    internal var activeRuntimePaths: MihomoRuntimePaths? = null
    @Volatile
    private var activeEndpoint: CleanIpResult? = null
    @Volatile
    internal var activeConnectionCountryFlag: String = ""
    @Volatile
    private var activeFrontingIp: String = ""
    @Volatile
    internal var lastDefaultNetworkKey: String? = null
    @Volatile
    internal var lastDefaultDns: String = ""
    @Volatile
    internal var pendingDefaultNetworkDnsReplay: Boolean = false
    @Volatile
    internal var activeDpiBypassPort: Int? = null
    @Volatile
    internal var alwaysOnActive: Boolean = false
    @Volatile
    private var lockdownActive: Boolean = false
    @Volatile
    private var lastPostConnectRecoveryElapsedMs: Long = 0L
    @Volatile
    private var activeNativeAutomaticStart: Boolean = false
    @Volatile
    internal var activeSubscriptionId: String = ""
    @Volatile
    internal var activeConnectionTag: String = ""
    @Volatile
    internal var activeConnectionFingerprint: String = ""
    @Volatile
    internal var activeSelectorReady: Boolean = false
    @Volatile
    internal var activeSelectableConnectionFingerprints: Set<String> = emptySet()
    @Volatile
    private var activeSelectorRootName: String = ""
    @Volatile
    private var activeSelectorRoots: List<String> = emptyList()
    @Volatile
    internal var activeAvailableProfiles: List<ConnectionProfile> = emptyList()
    @Volatile
    private var activeRuntimeUsesEndpointOverride: Boolean = false
    @Volatile
    internal var activeQuickSpeedPin: QuickSpeedPin? = null
    @Volatile
    internal var invalidatedQuickSpeedPin: QuickSpeedPin? = null
    internal var activeProfileShowsServer: Boolean = false

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.beginCapture(this)
        configRepository = ConfigRepository(this)
        subscriptionStore = SubscriptionStore(this)
        scanStateStore = CatClientScanStateStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        networkMonitor = DefaultNetworkMonitor(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        cleanIpCache = CleanIpCache(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
        dpiBypassPreferenceStore = DpiBypassPreferenceStore(this)
        panelTuneStore = PanelTuneStore(this)
        routingModePreferenceStore = RoutingModePreferenceStore(this)
        dnsPrivacyPreferenceStore = DnsPrivacyPreferenceStore(this)
        orbotEnginePreferenceStore = OrbotEnginePreferenceStore(this)
        pathWatchdogStore = PathWatchdogStore(this)
        routeMemoryStore = RouteMemoryStore(this)
        tlsIntegrityPreferenceStore = TlsIntegrityPreferenceStore(this)
        connectionOptionsPreferenceStore = MihomoConnectionOptionsPreferenceStore(this)
        connectionModePreferenceStore = ConnectionModePreferenceStore(this)
        lanSharingPreferenceStore = LanSharingPreferenceStore(this)
        connectionSelectionPreferenceStore = ConnectionSelectionPreferenceStore(this)
        connectionTestSettingsPreferenceStore = ConnectionTestSettingsPreferenceStore(this)
        connectionChainPreferenceStore = ConnectionChainPreferenceStore(this)
        aetherPreferenceStore = AetherPreferenceStore(this)
        networkMonitor.setDefaultNetworkChangeListener { candidate ->
            handleDefaultNetworkChanged(candidate)
        }
        createNotificationChannel()
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cat Client:ConnectionTests")
            .apply { setReferenceCounted(false) }
        connectionTestWakeLock = ConnectionTestWakeLock(
            scope,
            acquire = { timeoutMs -> wakeLock.acquire(timeoutMs) },
            release = { if (wakeLock.isHeld) wakeLock.release() },
        )
        DiagnosticLogger.info(this, "service.onCreate")
        currentVpnService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appInitiated = intent?.getBooleanExtra(Actions.EXTRA_APP_INITIATED, false) == true
        updateAlwaysOnMode(appInitiated)
        DiagnosticLogger.info(
            this,
            "service.onStartCommand",
            "action=${intent?.action} appInitiated=$appInitiated alwaysOn=$alwaysOnActive lockdown=$lockdownActive " +
                "startId=$startId flags=$flags state=${state.wireName}",
        )
        val action = Actions.resolveServiceAction(intent?.action, appInitiated)
        val startsConnectionTest = action == Actions.TEST_CONNECTION_DELAYS || action == Actions.TEST_CONNECTION_SPEED
        if (startsConnectionTest) {
            val notifText = when (state) {
                VpnState.Started -> getString(R.string.notification_connected)
                VpnState.Starting -> getString(R.string.notification_starting)
                else -> getString(R.string.connection_delay_testing)
            }
            runCatching {
                startForeground(NOTIFICATION_ID, serviceNotification(notifText))
            }
        }
        when (action) {
            Actions.CONNECT -> startVpn()
            Actions.DISCONNECT -> stopVpn()
            Actions.RECONNECT -> reconnectVpn()
            Actions.REFRESH -> refreshVpn(quickSpeedRequested = true)
            Actions.TRY_NEXT_ROUTE -> tryNextRoute()
            Actions.RECHECK_REAL_INTERNET -> if (state == VpnState.Started) scheduleRealInternetCheck(activeConnectionFingerprint)
            Actions.SWITCH_CONNECTION -> switchActiveConnection(
                switchRequestedByUser = true,
                subscriptionId = intent?.getStringExtra(Actions.EXTRA_SUBSCRIPTION_ID).orEmpty(),
                fingerprint = intent?.getStringExtra(Actions.EXTRA_CONNECTION_FINGERPRINT).orEmpty(),
            )
            Actions.TEST_CONNECTION_DELAYS -> startConnectionDelayTest(
                testId = intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID).orEmpty(),
                subscriptionId = intent?.getStringExtra(Actions.EXTRA_SUBSCRIPTION_ID).orEmpty(),
                connectionTypes = intent
                    ?.getStringArrayListExtra(Actions.EXTRA_CONNECTION_TYPES)
                    .orEmpty()
                    .toSet(),
                targetFingerprints = intent
                    ?.getStringArrayListExtra(Actions.EXTRA_CONNECTION_FINGERPRINTS)
                    .orEmpty()
                    .toSet(),
            )
            Actions.TEST_CONNECTION_SPEED -> startConnectionSpeedTest(
                testId = intent?.getStringExtra(Actions.EXTRA_SPEED_TEST_ID).orEmpty(),
                subscriptionId = intent?.getStringExtra(Actions.EXTRA_SUBSCRIPTION_ID).orEmpty(),
                fingerprint = intent?.getStringExtra(Actions.EXTRA_CONNECTION_FINGERPRINT).orEmpty(),
            )
            Actions.CANCEL_CONNECTION_SPEED_TEST -> cancelConnectionSpeedTest(
                intent?.getStringExtra(Actions.EXTRA_SPEED_TEST_ID).orEmpty(),
            )
            Actions.PAUSE_CONNECTION_DELAY_TEST -> setConnectionDelayTestPaused(
                testId = intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID).orEmpty(),
                paused = true,
            )
            Actions.RESUME_CONNECTION_DELAY_TEST -> setConnectionDelayTestPaused(
                testId = intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID).orEmpty(),
                paused = false,
            )
            Actions.CANCEL_CONNECTION_DELAY_TEST -> cancelConnectionDelayTest(
                intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID),
            )
        }
        // Invalid or busy test requests still have to satisfy the foreground-start contract.
        if (startsConnectionTest) finishConnectionTests()
        return START_NOT_STICKY
    }

    private fun updateAlwaysOnMode(appInitiated: Boolean) {
        val previousAlwaysOn = alwaysOnActive
        val previousLockdown = lockdownActive
        alwaysOnActive = vpnAlwaysOnEnabled(this, fallback = alwaysOnActive || !appInitiated)
        lockdownActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled()
        if (alwaysOnActive != previousAlwaysOn || lockdownActive != previousLockdown) {
            publishState(state)
        }
    }

    override fun onDestroy() {
        destroyed = true
        connectionTestWakeLock.close()
        DiagnosticLogger.info(this, "service.onDestroy", "state=${state.wireName}")
        startupJob?.cancel(CancellationException("Service destroyed"))
        connectionSwitchJob?.cancel(CancellationException("Service destroyed"))
        connectionDelayTestJob?.cancel(CancellationException("Service destroyed"))
        connectionSpeedTestJobs.values.toList().forEach { it.cancel(CancellationException("Service destroyed")) }
        stopJob?.cancel()
        subscriptionRefreshJob?.cancel()
        stopNotificationTrafficUpdates()
        cancelPostConnectHealthWatchdog()
        realCheckJob?.cancel()
        realCheckJob = null
        aetherSession?.close()
        aetherSession = null
        aetherPsiphonChain?.close()
        aetherPsiphonChain = null
        aetherTorCarrier?.close()
        aetherTorCarrier = null
        closeSstp("service destroyed")
        stopCoreImmediately()
        scope.cancel()
        currentVpnService = null
        if (currentVpnServiceState !is VpnState.Error) currentVpnServiceState = VpnState.Stopped
        VpnWidgetProvider.refresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        return null
    }

    override fun onLowMemory() {
        runCatching { Core.forceGC() }
        super.onLowMemory()
    }

    override fun onRevoke() {
        alwaysOnActive = false
        lockdownActive = false
        // Android has already deactivated the VPN interface; service destruction continues native cleanup.
        finishStoppedState("disconnect.revoked")
    }

    private fun startVpn() {
        if (state == VpnState.Starting || state == VpnState.Started) {
            val notifText = if (state == VpnState.Started) {
                getString(R.string.notification_connected)
            } else {
                getString(R.string.notification_starting)
            }
            runCatching { startForeground(NOTIFICATION_ID, serviceNotification(notifText)) }
            DiagnosticLogger.info(this, "connect.ignored", "state=${state.wireName}")
            return
        }
        lastPostConnectRecoveryElapsedMs = 0L
        awaitingPreservedRuntimeHealth = false
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        DiagnosticLogger.info(this, "connect.start")
        publishState(VpnState.Starting)
        launchConnectionStartup("connect")
    }

    private fun reconnectVpn() {
        if (state == VpnState.Starting || state == VpnState.Stopping) {
            runCatching {
                startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
            }
            DiagnosticLogger.info(this, "reconnect.ignored", "state=${state.wireName}")
            return
        }
        if (state == VpnState.Stopped || state is VpnState.Error) {
            startVpn()
            return
        }
        lastPostConnectRecoveryElapsedMs = 0L
        DiagnosticLogger.info(this, "reconnect.start")
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup("reconnect") { stopActiveCoreForReplacement() }
    }

    internal fun switchActiveConnection(
        subscriptionId: String,
        fingerprint: String,
        persistSelection: Boolean = true,
        switchRequestedByUser: Boolean = false,
    ) {
        if (subscriptionId.isBlank() || fingerprint.isBlank()) return
        if (switchRequestedByUser) {
            realCheckHopPending = true // a route the user picks is always measured …
            realCheckSessionAutomatic = false // … and never replaced behind their back
        }
        if (state != VpnState.Started) {
            DiagnosticLogger.info(this, "connection.switch.ignored", "reason=state state=${state.wireName}")
            return
        }
        if (activeConnectionChained) {
            publishState(VpnState.Started, getString(R.string.connection_chain_switch_unavailable))
            return
        }
        // beta212: Cat SSTP is not a Cat core outbound, so switching to or away from an SSTP row restarts the tunnel
        // with the picked row first (the core selector would only reach the row's dead loopback placeholder).
        val targetIsSstp = activeAvailableProfiles.firstOrNull { it.fingerprint == fingerprint }
            ?.shareLink?.trim()?.startsWith("sstp://", ignoreCase = true) == true
        if (sstpConnection != null || targetIsSstp) {
            scope.launch {
                val picked = runCatching {
                    withContext(Dispatchers.IO) {
                        configRepository.fetchOrCachedMihomoConfig(subscriptionId).catalog.profiles
                            .firstOrNull { it.fingerprint == fingerprint }
                    }
                }.getOrNull()
                if (picked == null) {
                    publishState(VpnState.Started, getString(R.string.connection_switch_unavailable))
                    return@launch
                }
                DiagnosticLogger.info(this@CatClientVpnService, "connection.switch.sstpRestart", "profile=${picked.tag}")
                connectionSelectionPreferenceStore.saveSelectedProfile(subscriptionId, picked, userPick = true)
                reconnectVpn()
            }
            return
        }
        if (
            connectionDelayTestJob?.isActive == true ||
            connectionSpeedTestJobs.isNotEmpty() ||
            connectionSwitchJob?.isActive == true
        ) {
            publishState(VpnState.Started, getString(R.string.connection_switch_busy))
            return
        }
        val runtimePaths = activeRuntimePaths ?: return
        val profile = activeAvailableProfiles.firstOrNull { it.fingerprint == fingerprint }
        if (subscriptionId != activeSubscriptionId || profile == null) {
            publishState(VpnState.Started, getString(R.string.connection_switch_unavailable))
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            controllerSelectionMutex.withLock {
                val controller = MihomoControllerClient(runtimePaths.secret, port = runtimePaths.controlPort)
                var rollbackSelections = emptyList<MihomoGroupSelection>()
                try {
                    if (state != VpnState.Started || activeRuntimePaths != runtimePaths) return@withLock
                    val before = controller.getProxies()
                    val path = MihomoControllerProxies.selectorPath(
                        response = before,
                        targetName = profile.tag,
                        preferredRoots = activeSelectorRootName
                            .takeIf(String::isNotBlank)
                            ?.let(::listOf)
                            ?: activeSelectorRoots,
                    )
                    if (path.isEmpty()) {
                        throw IOException("Connection is not reachable from the active Cat core selector")
                    }
                    rollbackSelections = MihomoControllerProxies.currentSelections(before, path)
                    path.forEach { selection ->
                        controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                    }

                    val selectedRoot = path.last().selectorGroup
                    val switched = controller.getProxies()
                    val activeName = MihomoControllerProxies.activeProxyName(switched, selectedRoot)
                    if (activeName != profile.tag) {
                        throw IOException("Cat core selected ${activeName.orEmpty()} instead of ${profile.tag}")
                    }
                    val measuredDelayMs = MihomoRuntimeDefaults.HEALTH_URLS.firstNotNullOfOrNull { url ->
                        MihomoDelayPolicy.acceptedDelayMs(
                            controller.delay(
                                profile.tag,
                                timeoutMs = FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS,
                                url = url,
                            ),
                        )
                    }
                    if (measuredDelayMs == null) verifyRuntimeHealth()
                    if (state != VpnState.Started || activeRuntimePaths != runtimePaths) return@withLock

                    activeSelectorRootName = selectedRoot
                    applyLiveSelectorSnapshot(runtimePaths, controller.getProxies(), publishIfChanged = false)
                    if (activeConnectionFingerprint != profile.fingerprint) {
                        throw IOException("Cat core did not keep the requested connection active")
                    }
                    activeNativeAutomaticStart = false
                    activeQuickSpeedPin = null
                    invalidatedQuickSpeedPin = null
                    measuredDelayMs?.let { activeDelayMs = it }
                    if (!activeRuntimeUsesEndpointOverride) {
                        activeEndpoint = CleanIpResult(
                            ip = profile.server,
                            port = profile.port,
                            latencyMs = measuredDelayMs ?: activeDelayMs,
                            lossRate = 0.0,
                            checkedAt = System.currentTimeMillis(),
                        )
                    }
                    // beta220: an automatic «next route» hop must not turn the user's Automatic choice into a pin.
                    if (persistSelection) connectionSelectionPreferenceStore.saveSelectedProfile(subscriptionId, profile)
                    scanStateStore.saveLastSelectedProfile(
                        SelectedConnectionProfile(
                            profile = profile,
                            delayMs = measuredDelayMs?.toInt() ?: Int.MAX_VALUE,
                            selectedAt = System.currentTimeMillis(),
                        ),
                    )
                    publishState(VpnState.Started)
                    DiagnosticLogger.info(
                        this@CatClientVpnService,
                        "connection.switch.ok",
                        "profile=${profile.tag} selectors=${path.size} delayMs=${measuredDelayMs ?: -1L}",
                    )
                } catch (error: Throwable) {
                    if (state == VpnState.Started && activeRuntimePaths == runtimePaths) {
                        rollbackSelections.forEach { selection ->
                            runCatching {
                                controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                            }.onFailure { rollbackError ->
                                DiagnosticLogger.warn(
                                    this@CatClientVpnService,
                                    "connection.switch.rollback.failed",
                                    "selector=${selection.selectorGroup}",
                                    rollbackError,
                                )
                            }
                        }
                        runCatching { controller.getProxies() }
                            .onSuccess { applyLiveSelectorSnapshot(runtimePaths, it, publishIfChanged = false) }
                    }
                    if (error is CancellationException) throw error
                    DiagnosticLogger.warn(
                        this@CatClientVpnService,
                        "connection.switch.failed",
                        "profile=${profile.tag}",
                        error,
                    )
                    if (state == VpnState.Started && activeRuntimePaths == runtimePaths) {
                        publishState(VpnState.Started, getString(R.string.connection_switch_failed))
                    }
                }
            }
        }
        connectionSwitchJob = job
        job.invokeOnCompletion {
            if (connectionSwitchJob === job) connectionSwitchJob = null
        }
    }

    internal fun refreshVpn(
        eventPrefix: String = "refresh",
        automatic: Boolean = false,
        quickSpeedRequested: Boolean = false,
    ) {
        if (!canStartVpnRefresh(state, automatic, awaitingPreservedRuntimeHealth)) {
            DiagnosticLogger.info(this, "$eventPrefix.ignored", "state=${state.wireName}")
            return
        }
        awaitingPreservedRuntimeHealth = false
        if (!automatic) {
            lastPostConnectRecoveryElapsedMs = 0L
        }
        DiagnosticLogger.info(this, "$eventPrefix.start")
        val exclusion = ConnectionStartupExclusion(
            subscriptionId = activeSubscriptionId,
            endpoint = activeEndpoint,
            profileFingerprint = activeConnectionFingerprint,
        )
        DiagnosticLogger.info(
            this,
            "$eventPrefix.connection.excluded",
            "subscription=${exclusion.subscriptionId} endpoint=${exclusion.endpoint != null} " +
                "profile=${exclusion.profileFingerprint.isNotBlank()}",
        )
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup(
            eventPrefix = eventPrefix,
            exclusion = exclusion,
            preserveRuntimeOnFailure = activeRuntimePaths.takeUnless { automatic },
            quickSpeedRequested = quickSpeedRequested,
        )
    }

    internal val fenixTestLock = Any()

    private fun launchConnectionStartup(
        eventPrefix: String,
        exclusion: ConnectionStartupExclusion = ConnectionStartupExclusion(),
        preserveRuntimeOnFailure: MihomoRuntimePaths? = null,
        quickSpeedRequested: Boolean = false,
        beforeStartup: suspend () -> Unit = {},
    ) {
        startupJob?.cancel(CancellationException("Startup superseded"))
        subscriptionRefreshJob?.cancel()
        cancelPostConnectHealthWatchdog()
        val job = scope.launch {
            try {
                connectionSwitchJob?.cancelAndJoin()
                cancelConnectionTestsAndWait()
                beforeStartup()
                runConnectionStartup(eventPrefix, exclusion, quickSpeedRequested)
            } catch (error: CancellationException) {
                DiagnosticLogger.info(this@CatClientVpnService, "$eventPrefix.canceled", error.message.orEmpty())
            } catch (error: Throwable) {
                if (!shouldPublishStartupError(isActive, state)) {
                    DiagnosticLogger.info(
                        this@CatClientVpnService,
                        "$eventPrefix.failure.ignored",
                        "reason=startupCanceled state=${state.wireName}",
                    )
                    return@launch
                }
                if (preserveRuntimeOnFailure != null && activeRuntimePaths == preserveRuntimeOnFailure) {
                    keepActiveRuntimeAfterStartupFailure(eventPrefix, error)
                } else {
                    DiagnosticLogger.error(this@CatClientVpnService, "$eventPrefix.failed", error = error)
                    stopAfterFailure(error)
                }
            }
        }
        startupJob = job
        job.invokeOnCompletion {
            if (startupJob === job) {
                startupJob = null
            }
        }
    }

    private suspend fun runConnectionStartup(
        eventPrefix: String,
        exclusion: ConnectionStartupExclusion = ConnectionStartupExclusion(),
        quickSpeedRequested: Boolean = false,
    ) {
        ensureStartupActive(eventPrefix)
        ensureUnderlyingNetworkAvailable()
        networkMonitor.start()

        val selectedSubscriptionId = subscriptionStore.readSelectedSubscriptionId()
        val snapshots = mutableMapOf<String, MihomoSubscriptionSnapshot>()
        val initialSnapshot = runCatching {
            configRepository.fetchOrCachedMihomoConfig(selectedSubscriptionId).also {
                snapshots[selectedSubscriptionId] = it
            }
        }.getOrNull()

        // Check if the selected subscription or explicitly selected profile is an aether:// / masque:// config
        val explicitCandidate = if (initialSnapshot != null && connectionSelectionPreferenceStore.hasSelectedProfile(selectedSubscriptionId)) {
            connectionSelectionPreferenceStore.readSelectedProfile(
                selectedSubscriptionId,
                initialSnapshot.catalog.profiles,
            )
        } else null
        // beta212: Cat SSTP (PPP inside HTTPS, e.g. the public VPN Gate servers with vpn/vpn) has its own tunnel
        // straight into the TUN — the Cat core has no SSTP outbound. Used when an sstp:// row is picked, or in
        // Automatic mode when every row of the subscription is SSTP.
        val allProfiles = initialSnapshot?.catalog?.profiles.orEmpty()
        val sstpProfiles = allProfiles.filter { p -> p.shareLink?.trim().orEmpty().startsWith("sstp://", ignoreCase = true) }
        val explicitSstp = explicitCandidate?.takeIf { p -> p.shareLink?.trim().orEmpty().startsWith("sstp://", ignoreCase = true) }
        val autoSstp = explicitCandidate == null && sstpProfiles.isNotEmpty() && sstpProfiles.size == allProfiles.size
        if (explicitSstp != null || autoSstp) {
            val ordered = listOfNotNull(explicitSstp) + sstpProfiles.filter { p -> p.shareLink != explicitSstp?.shareLink }
            val candidates = ordered.mapNotNull { p ->
                SstpLink.parse(p.shareLink.orEmpty())?.let { l -> p to l.copy(remark = l.remark.ifBlank { ConnectionLabelPolicy.displayName(p) }) }
            }.distinctBy { it.second.endpoint }.take(SSTP_MAX_CANDIDATES)
            if (candidates.isNotEmpty()) {
                runSstpStartup(eventPrefix, selectedSubscriptionId, candidates)
                return
            }
        }
        // beta214: plain socks/http rows on 127.0.0.1 with nothing listening (copied out of another app's config)
        // can never work; they are left out of the Automatic decision and explained instead of failing with
        // «health check at 127.0.0.1:2080».
        val deadLocalTags = withContext(Dispatchers.IO) {
            allProfiles.filter { p ->
                LoopbackProfileGuard.isPlainLoopback(p.type, p.server, p.shareLink) && !LoopbackProfileGuard.isListening(p.port)
            }.map { it.tag }.toSet()
        }
        val autoAetherCandidate = if (explicitCandidate == null && initialSnapshot != null) {
            initialSnapshot.catalog.profiles.firstOrNull { p ->
                CatWarpListPolicy.isCatWarpLink(p.shareLink)
            }?.takeIf {
                // In Automatic mode, launch Cat Warp directly when the subscription holds only Cat Warp rows
                // (plus dead local rows that cannot carry traffic anyway).
                initialSnapshot.catalog.profiles.all { p ->
                    CatWarpListPolicy.isCatWarpLink(p.shareLink) || p.tag in deadLocalTags
                }
            }
        } else null
        val chosenAetherProfile = explicitCandidate ?: autoAetherCandidate
        chosenAetherProfile?.shareLink?.let { link ->
            if (link.startsWith("aether://", ignoreCase = true) || link.startsWith("masque://", ignoreCase = true)) {
                val parsedAether = AetherSelection.fromAetherUri(link)
                if (parsedAether != null) {
                    // A country / Iran-vs-foreign picked in the Aether UI after the list entry was chosen wins over the
                    // link's own (often empty) region; otherwise "Germany" silently became single-hop WARP (IR IP).
                    val choice = aetherPreferenceStore.readExitChoice()
                        ?.takeIf { it.atMs > connectionSelectionPreferenceStore.readLastProfilePickAt() }
                    val mergedAether = AetherSelection.applyExitChoice(parsedAether, choice).copy(enabled = true)
                    if (choice != null) {
                        DiagnosticLogger.info(
                            this,
                            "$eventPrefix.aether.exitChoice",
                            "exit=${mergedAether.exitLocation} chain=${mergedAether.chainTarget} region=${mergedAether.psiphonRegion.ifEmpty { "auto" }}",
                        )
                    }
                    aetherPreferenceStore.save(mergedAether)
                    runAetherStartup(eventPrefix, mergedAether)
                    return
                }
            }
        }

        val aetherSelection = aetherPreferenceStore.read()
        if (aetherSelection.enabled) {
            runAetherStartup(eventPrefix, aetherSelection)
            return
        }

        val explicitDeadLocal = explicitCandidate?.takeIf { it.tag in deadLocalTags }
        val onlyDeadLocal = explicitCandidate == null && allProfiles.isNotEmpty() && allProfiles.all { it.tag in deadLocalTags }
        if (explicitDeadLocal != null || onlyDeadLocal) {
            val p = explicitDeadLocal ?: allProfiles.first()
            DiagnosticLogger.warn(this, "$eventPrefix.deadLocalProxy", "port=${p.port} explicit=${explicitDeadLocal != null}")
            throw IOException(LoopbackProfileGuard.deadLocalMessage(p.port, ConnectionLabelPolicy.displayName(p)))
        }

        val chainSettings = connectionChainPreferenceStore.read()
        if (chainSettings.isActive) {
            if (quickSpeedRequested) {
                DiagnosticLogger.info(this, "mihomo.quickSpeed.skipped", "reason=connectionChain")
            }
            val startedRuntime = withContext(Dispatchers.IO) {
                configRepository.refreshAllSubscriptions()
                connectWithConnectionChain(
                    settings = chainSettings,
                    splitTunnelPlan = resolveSplitTunnelRuntimePlan(),
                    preferences = captureSessionPlanPreferences(
                        selectedSubscriptionId = selectedSubscriptionId,
                        explicitProfile = null,
                        selectedAutomaticTypes = emptySet(),
                    ),
                    excludedProfileFingerprint = exclusion.profileFingerprint,
                    // The location the user picked in the app applies to a chain too — it used
                    // to be dropped here (hardcoded null), so a chain always left from whatever
                    // was fastest and the setting silently did nothing.
                    requestedExitCountryCode = locationPreferenceStore.readSelectedCountryCode(),
                )
            }
            applyStartedRuntime(
                startedRuntimeCandidate = startedRuntime,
                eventPrefix = eventPrefix,
                showServer = true,
            )
            return
        }

        var explicitProfile: ConnectionProfile? = null
        if (connectionSelectionPreferenceStore.hasSelectedProfile(selectedSubscriptionId)) {
            val selectedSnapshot = snapshots[selectedSubscriptionId]
                ?: configRepository.fetchOrCachedMihomoConfig(selectedSubscriptionId).also {
                    snapshots[selectedSubscriptionId] = it
                }
            val requested = explicitCandidate ?: connectionSelectionPreferenceStore.readSelectedProfile(
                selectedSubscriptionId,
                selectedSnapshot.catalog.profiles,
            )
            explicitProfile = requested?.let { profile ->
                eligibleProfilesForRuntime(selectedSnapshot.catalog)
                    .firstOrNull { it.fingerprint == profile.fingerprint }
            }
            if (requested == null || explicitProfile == null) {
                connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, null)
                DiagnosticLogger.warn(
                    this,
                    "connection.selection.ineligible",
                    "profile=${requested?.tag.orEmpty()} fallback=automatic",
                )
            }
        }

        val selectedAutomaticTypes = connectionSelectionPreferenceStore
            .readAutomaticTypes(selectedSubscriptionId)
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
            .takeIf { explicitProfile == null }
        val splitTunnelPlan = resolveSplitTunnelRuntimePlan()
        val frontingIps = frontingIpPreferenceStore.readFrontingIps()
        if (quickSpeedRequested && frontingIps.isNotEmpty()) {
            DiagnosticLogger.info(this, "mihomo.quickSpeed.skipped", "reason=fronting")
        }

        val sourceIds = BuiltInSubscriptionStartupPolicy.sourceIds(
            selectedSubscriptionId,
            explicitProfile,
        )
        val (startedRuntime, startupNotice, showServer) =
            BuiltInSubscriptionStartupPolicy.firstSuccessful(
                sourceIds = sourceIds,
                onFallback = { from, to, error ->
                    DiagnosticLogger.warn(
                        this,
                        "connection.subscription.fallback",
                        "from=$from to=$to",
                        error,
                    )
                },
            ) { sourceId ->
                // Country-sticky startup: try the selected country first; if every
                // server of that country is dead, fall back to Automatic (fastest of the
                // rest) instead of failing — the user's choice stays saved for next time.
                try {
                    startRuntimeForSource(
                        sourceId = sourceId,
                        snapshots = snapshots,
                        eventPrefix = eventPrefix,
                        explicitProfile = explicitProfile,
                        selectedSubscriptionId = selectedSubscriptionId,
                        selectedAutomaticTypes = selectedAutomaticTypes,
                        selectedCountryCode = selectedCountryCode,
                        splitTunnelPlan = splitTunnelPlan,
                        frontingIps = frontingIps,
                        exclusion = exclusion,
                        quickSpeedRequested = quickSpeedRequested,
                    )
                } catch (error: Throwable) {
                    if (error is CancellationException || selectedCountryCode == null) throw error
                    DiagnosticLogger.warn(
                        this,
                        "location.fallback.auto",
                        "country=$selectedCountryCode reason=${error.message.orEmpty()}",
                        error,
                    )
                    ensureStartupActive(eventPrefix)
                    val (runtime, notice, showServer) = startRuntimeForSource(
                        sourceId = sourceId,
                        snapshots = snapshots,
                        eventPrefix = eventPrefix,
                        explicitProfile = explicitProfile,
                        selectedSubscriptionId = selectedSubscriptionId,
                        selectedAutomaticTypes = selectedAutomaticTypes,
                        selectedCountryCode = null,
                        splitTunnelPlan = splitTunnelPlan,
                        frontingIps = frontingIps,
                        exclusion = exclusion,
                        quickSpeedRequested = quickSpeedRequested,
                    )
                    val countryLabel = ConnectionLocationPolicy
                        .countryFromCode(selectedCountryCode)
                        ?.let { "${it.flag} ${it.country}" } ?: selectedCountryCode
                    Triple(
                        runtime,
                        notice ?: getString(R.string.location_fallback_notice, countryLabel),
                        showServer,
                    )
                }
            }
        applyStartedRuntime(
            startedRuntimeCandidate = startedRuntime,
            eventPrefix = eventPrefix,
            notice = startupNotice,
            showServer = showServer,
        )
    }

    private suspend fun startRuntimeForSource(
        sourceId: String,
        snapshots: Map<String, MihomoSubscriptionSnapshot>,
        eventPrefix: String,
        explicitProfile: ConnectionProfile?,
        selectedSubscriptionId: String,
        selectedAutomaticTypes: Set<String>,
        selectedCountryCode: String?,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        frontingIps: List<String>,
        exclusion: ConnectionStartupExclusion,
        quickSpeedRequested: Boolean,
    ): Triple<StartedMihomoRuntime, String?, Boolean> {
                val snapshot = snapshots[sourceId]
                    ?: configRepository.fetchOrCachedMihomoConfig(sourceId)
                ensureStartupActive(eventPrefix)
                val eligibleProfiles = eligibleProfilesForRuntime(snapshot.catalog)
                val sourceExplicitProfile = explicitProfile.takeIf { sourceId == selectedSubscriptionId }
                val automaticProfiles = ConnectionTypeSelectionPolicy.filterProfiles(
                    eligibleProfiles,
                    selectedAutomaticTypes,
                )
                if (sourceExplicitProfile == null && automaticProfiles.isEmpty()) {
                    throw IOException("No eligible connections match the selected types")
                }
                val profiles = sourceExplicitProfile?.let(::listOf) ?: profilesForSelectedLocation(
                    profiles = automaticProfiles,
                    selectedCountryCode = selectedCountryCode,
                )
                if (profiles.isEmpty()) {
                    throw IOException("No eligible connections match the selected location")
                }
                DiagnosticLogger.info(
                    this,
                    "connection.selection",
                    "subscription=$sourceId mode=${if (sourceExplicitProfile == null) "automatic" else "explicit"} " +
                        "profile=${sourceExplicitProfile?.tag.orEmpty()} types=${selectedAutomaticTypes.sorted().joinToString(",")}",
                )
                val preferences = captureSessionPlanPreferences(
                    selectedSubscriptionId = sourceId,
                    explicitProfile = sourceExplicitProfile,
                    selectedAutomaticTypes = selectedAutomaticTypes,
                    frontingIps = frontingIps,
                )
                val sourceExclusion = exclusion.forSubscription(sourceId)
                val attemptUsedOrbot = preferences.orbot != null
                val (startedRuntime, startupNotice) = try {
                    withContext(Dispatchers.IO) {
                        if (frontingIps.isNotEmpty()) {
                            connectWithFrontingIpsOrOriginal(
                                eventPrefix = eventPrefix,
                                snapshot = snapshot,
                                profiles = profiles,
                                splitTunnelPlan = splitTunnelPlan,
                                selectedCountryCode = selectedCountryCode,
                                exclusion = sourceExclusion,
                                frontingIps = frontingIps,
                                preferences = preferences,
                                availableProfiles = eligibleProfiles,
                            )
                        } else {
                            connectWithOriginal(
                                snapshot = snapshot,
                                splitTunnelPlan = splitTunnelPlan,
                                selectedCountryCode = selectedCountryCode,
                                preferences = preferences,
                                availableProfiles = eligibleProfiles,
                                excludedProfileFingerprint = sourceExclusion.profileFingerprint,
                                quickSpeedRequested = quickSpeedRequested,
                            ) to null
                        }
                    }
                } catch (error: Throwable) {
                    recordPathOutcome(
                        succeeded = false,
                        usedOrbot = attemptUsedOrbot,
                        detail = error.javaClass.simpleName,
                    )
                    throw error
                }
                recordPathOutcome(succeeded = true, usedOrbot = attemptUsedOrbot, detail = "started")
                return Triple(
                    startedRuntime,
                    startupNotice,
                    !SubscriptionStore.isBuiltInSubscription(sourceId),
                )
    }

    private suspend fun captureSessionPlanPreferences(
        selectedSubscriptionId: String,
        explicitProfile: ConnectionProfile?,
        selectedAutomaticTypes: Set<String>,
        frontingIps: List<String> = frontingIpPreferenceStore.readFrontingIps(),
    ): SessionPlanPreferences = SessionPlanPreferences(
        frontingIps = frontingIps,
        connectionOptions = connectionOptionsPreferenceStore.read(),
        selectedSubscriptionId = selectedSubscriptionId,
        explicitProfile = explicitProfile,
        selectedAutomaticTypes = selectedAutomaticTypes,
        lanSharing = lanSharingPreferenceStore.read(),
        routingMode = routingModePreferenceStore.read(),
        dns = DnsRuntimeSettings(
            mode = dnsPrivacyPreferenceStore.readMode(),
            dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
            dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
            doqEndpoint = dnsPrivacyPreferenceStore.readDoqEndpoint(),
            doh3Url = dnsPrivacyPreferenceStore.readDoh3Url(),
        ),
        orbot = resolveOrbotHop(),
        connectionMode = connectionModePreferenceStore.read(),
        dpiBypassEnabled = dpiBypassPreferenceStore.isEnabled(),
        alwaysOn = alwaysOnActive,
        lockdown = lockdownActive,
        tlsIntegrityEnabled = tlsIntegrityPreferenceStore.isEnabled(),
        sniOverride = frontingIpPreferenceStore.readSniOverride(),
    )

    /**
     * The Orbot engine, decided once per attempt: the switch being ON is not enough — the
     * loopback SOCKS port has to answer, because routing everything into a dead local port
     * would look like a working switch and produce a connection that never leaves the phone.
     * A switch that is on while Orbot is closed is therefore reported (not silently ignored),
     * and the attempt continues on the normal path.
     */
    private suspend fun resolveOrbotHop(): OrbotProxySettings? {
        val settings = OrbotProxySettings(
            orbotEnginePreferenceStore.readHost(),
            orbotEnginePreferenceStore.readPort(),
        )
        // Nothing to do — and no socket to open — when neither half can act.
        val switchOn = orbotEnginePreferenceStore.isEnabled()
        if (!switchOn && !pathWatchdogStore.isArmed()) return null
        // This runs on the connect path, which is entered from the main thread (scope.launch on
        // Dispatchers.Main): a 400 ms connect there would be visible jank, so the probe goes to
        // IO like every other blocking call in this service.
        val probe = withContext(Dispatchers.IO) { OrbotProbe.probe(settings.host, settings.port) }
        // The owner's switch is unconditional (apart from Orbot having to answer); the watchdog
        // is the automatic half and only moves when its own run says so.
        if (switchOn) {
            if (probe.reachable) {
                DiagnosticLogger.info(
                    this,
                    "orbot.engine.active",
                    "host=${settings.host} port=${settings.port} ms=${probe.latencyMs} routing=global reason=switch",
                )
                return settings
            }
            DiagnosticLogger.warn(
                this,
                "orbot.engine.skipped",
                "host=${settings.host} port=${settings.port} detail=${probe.detail} reason=switch",
            )
            return null
        }
        val decision = PathWatchdog.decide(
            armed = true,
            // A fallback that does not answer is not a fallback: with Orbot closed the watchdog
            // stays on the primary instead of routing into a dead local port.
            fallbackAvailable = probe.reachable,
            state = pathWatchdogStore.readState(),
            nowMs = System.currentTimeMillis(),
        )
        return when (decision) {
            is PathWatchdogDecision.UseFallback -> {
                pathWatchdogStore.applyDecision(decision)
                DiagnosticLogger.info(
                    this,
                    "orbot.engine.active",
                    "host=${settings.host} port=${settings.port} ms=${probe.latencyMs} routing=global reason=${decision.reason}",
                )
                settings
            }
            is PathWatchdogDecision.ReturnToPrimary -> {
                pathWatchdogStore.applyDecision(decision)
                DiagnosticLogger.info(this, "orbot.watchdog.return", "reason=${decision.reason}")
                null
            }
            is PathWatchdogDecision.Stay -> null
        }
    }

    /**
     * One attempt's outcome, recorded on the PRIMARY path only: while an attempt rode Orbot, its
     * result says nothing about whether the normal path works, and counting it would make the
     * watchdog forget that it took the fallback in the first place.
     */
    private fun recordPathOutcome(succeeded: Boolean, usedOrbot: Boolean, detail: String) {
        if (usedOrbot) return
        val state = if (succeeded) {
            pathWatchdogStore.recordPrimarySuccess()
        } else {
            pathWatchdogStore.recordPrimaryFailure()
        }
        DiagnosticLogger.info(
            this,
            "orbot.watchdog.observe",
            "succeeded=$succeeded detail=$detail state=${PathWatchdog.describe(state)}",
        )
    }

    private suspend fun connectWithFrontingIpsOrOriginal(
        eventPrefix: String,
        snapshot: MihomoSubscriptionSnapshot,
        profiles: List<ConnectionProfile>,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        exclusion: ConnectionStartupExclusion,
        frontingIps: List<String>,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
    ): Pair<StartedMihomoRuntime, String?> {
        val frontingNetworkKey = networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint()
        if (FrontingFailureMemory.shouldTryOriginalFirst(frontingNetworkKey, frontingIps)) {
            DiagnosticLogger.info(
                this,
                "frontingIp.originalFirst",
                "reason=frontingFailedRecentlyOnThisNetwork ips=${frontingIps.size}",
            )
            try {
                return connectWithOriginal(
                    snapshot = snapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    preferences = preferences,
                    availableProfiles = availableProfiles,
                    excludedProfileFingerprint = exclusion.profileFingerprint,
                ) to FRONTING_SKIPPED_NOTICE
            } catch (error: Throwable) {
                if (
                    error is CancellationException ||
                    error is MihomoCoreBusyException ||
                    error is MihomoCoreSetupTimeoutException
                ) {
                    throw error
                }
                DiagnosticLogger.warn(this, "frontingIp.originalFirst.failed", "retry=frontingIps", error)
                if (!stopCoreService()) throw MihomoCoreBusyException(error)
            }
        }
        return try {
            connectWithTopIpCandidates(
                eventPrefix = eventPrefix,
                snapshot = snapshot,
                profiles = profiles,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                bypassConnectionCache = false,
                excludedEndpoint = exclusion.endpoint,
                excludedProfileFingerprint = exclusion.profileFingerprint,
                frontingIps = frontingIps,
                useCachedFirst = false,
                preferences = preferences,
                availableProfiles = availableProfiles,
            ).also { FrontingFailureMemory.frontingWorked(frontingNetworkKey, frontingIps) } to null
        } catch (error: Throwable) {
            if (
                error is CancellationException ||
                error is MihomoCoreBusyException ||
                error is MihomoCoreSetupTimeoutException
            ) {
                throw error
            }
            DiagnosticLogger.warn(this, "frontingIp.fallback.original", "ips=${frontingIps.size}", error)
            val startedRuntime = connectWithOriginal(
                snapshot = snapshot,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                preferences = preferences,
                availableProfiles = availableProfiles,
                excludedProfileFingerprint = exclusion.profileFingerprint,
            )
            FrontingFailureMemory.frontingFailedOriginalWorked(frontingNetworkKey, frontingIps)
            startedRuntime to FRONTING_FALLBACK_NOTICE
        }
    }

    private suspend fun connectWithOriginal(
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String = "",
        quickSpeedRequested: Boolean = false,
    ): StartedMihomoRuntime {
        return startMihomoRuntimeAttempt(
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            selectedCountryCode = selectedCountryCode,
            topEndpoint = null,
            validateConnectivity = false,
            preferences = preferences,
            availableProfiles = availableProfiles,
            excludedProfileFingerprint = excludedProfileFingerprint,
            quickSpeedRequested = quickSpeedRequested,
        )
    }

    private suspend fun connectWithConnectionChain(
        settings: ConnectionChainSettings,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        preferences: SessionPlanPreferences,
        excludedProfileFingerprint: String = "",
        requestedExitCountryCode: String? = null,
    ): StartedMihomoRuntime {
        val subscriptions = subscriptionStore.readUserSubscriptions()
        val sourceIds = buildList {
            addAll(SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS)
            addAll(subscriptions.map(UserSubscription::id))
        }.distinct()
        val cachedSources = sourceIds.mapNotNull { subscriptionId ->
            val snapshot = configRepository.readCachedMihomoConfigOrNull(subscriptionId)
                ?: return@mapNotNull null
            ConnectionChainSource(
                subscriptionId = subscriptionId,
                subscriptionName = subscriptions.firstOrNull { it.id == subscriptionId }?.name
                    ?: getString(
                        if (subscriptionId == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID) {
                            R.string.subscription_public_name
                        } else {
                            R.string.subscription_private_name
                        },
                    ),
                snapshot = snapshot,
            )
        }
        val allProfiles = cachedSources.flatMap { it.snapshot.catalog.profiles }
        val allowIpv6Literals = networkMonitor.hasUsableIpv6DefaultNetwork() ||
            (allProfiles.isNotEmpty() && allProfiles.all { it.isIpv6Literal })
        val sources = cachedSources.mapNotNull { source ->
            val runtimeProfiles = source.snapshot.catalog.profiles.filter {
                allowIpv6Literals || !it.isIpv6Literal
            }
            source.takeIf { runtimeProfiles.isNotEmpty() }?.copy(
                snapshot = source.snapshot.copy(
                    catalog = source.snapshot.catalog.copy(profiles = runtimeProfiles),
                ),
            )
        }
        val delayRecords = sources.flatMap { source ->
            subscriptionStore.readConnectionDelayRecords(
                subscriptionId = source.subscriptionId,
                profiles = source.snapshot.catalog.profiles,
            )
        }
        val plannedRuntimes = withContext(Dispatchers.Default) {
            ConnectionChainPlanner.plans(
                settings,
                sources,
                delayRecords,
                allowIpv6Literals = allowIpv6Literals,
                requestedExitCountryCode = requestedExitCountryCode,
            )
        }
        val plans = plannedRuntimes.filterNot { plan ->
            shouldExcludeRecoveryChainPlan(
                excludedFingerprint = excludedProfileFingerprint,
                finalFingerprint = plan.finalHop.candidate.profile.fingerprint,
                finalHopMode = settings.hop(plan.finalHop.slot).mode,
            )
        }
        if (plans.isEmpty() && plannedRuntimes.isNotEmpty()) {
            throw IOException("No alternative automatic connection chain is available")
        }
        if (plans.size != plannedRuntimes.size) {
            DiagnosticLogger.info(
                this,
                "mihomo.chain.recovery.excluded",
                "excluded=${plannedRuntimes.size - plans.size} remaining=${plans.size}",
            )
        }
        var lastFailure: Throwable? = null
        plans.forEachIndexed { index, plan ->
            try {
                val runtimeSnapshot = MihomoConfigParser.parse(plan.rawYaml)
                // `selectedCountryCode = null` on purpose: the chain plan has ALREADY decided
                // which config exits, and the runtime filter would then search the patched YAML
                // for a profile whose own tag names that country. Those tags are
                // «CatClient Chain 1.1» — the filter would find nothing, reset to automatic and
                // fight the plan. The request is honoured where it can be honoured: in the
                // planner, and in what this method reports below.
                val runtime = startMihomoRuntimeAttempt(
                    snapshot = runtimeSnapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = null,
                    topEndpoint = null,
                    validateConnectivity = true,
                    forcedProxyName = plan.finalRuntimeName,
                    preferences = preferences,
                    availableProfiles = runtimeSnapshot.catalog.profiles,
                )
                val finalCandidate = plan.finalHop.candidate
                // The pinned address of a hop is what the leg is dialled at, so it — not the
                // address inside the subscription — is what the app must report: otherwise
                // the connection details claim one server while the socket goes to another.
                val finalPin = plan.finalHop.serverOverride?.let(::connectionChainEndpointOrNull)
                DiagnosticLogger.info(
                    this,
                    "mihomo.chain.healthy",
                    "attempt=${index + 1}/${plans.size} hops=${plan.hops.size} " +
                        "chain=${plan.summary} pinned=${plan.hops.count { it.serverOverride != null }}",
                )
                DiagnosticLogger.info(
                    this,
                    "mihomo.chain.exit",
                    "requested=${plan.requestedExitCountryCode ?: "auto"} " +
                        "actual=${plan.exitCountryCode ?: "unknown"} matched=${plan.exitCountryMatched}",
                )
                if (!plan.exitCountryMatched) {
                    // Never fatal — a country wish must not cost the user their connection — but
                    // never silent either: the flag then comes from the live egress probe below
                    // instead of from the request, so the app shows where traffic really lands.
                    DiagnosticLogger.warn(
                        this,
                        "mihomo.chain.exit.unavailable",
                        "requested=${plan.requestedExitCountryCode.orEmpty()} " +
                            "actual=${plan.exitCountryCode ?: "unknown"}",
                    )
                }
                return runtime.copy(
                    profile = MihomoConnectionOptionsPolicy.applyTo(
                        finalCandidate.profile,
                        preferences.connectionOptions,
                    ),
                    endpoint = CleanIpResult(
                        ip = finalPin?.ip ?: finalCandidate.profile.server,
                        port = finalPin?.port ?: finalCandidate.profile.port,
                        latencyMs = runtime.delayMs,
                        lossRate = 0.0,
                        checkedAt = System.currentTimeMillis(),
                    ),
                    availableProfiles = emptyList(),
                    nativeAutomaticStart = false,
                    subscriptionId = finalCandidate.subscriptionId,
                    chainHopCount = plan.hops.size,
                    // Kept only when the plan really honoured the request: otherwise staying null
                    // lets refreshEgressCountryInBackground() measure the country that IS
                    // serving this session, which is the honest answer.
                    selectedCountryCode = requestedExitCountryCode.takeIf { plan.exitCountryMatched },
                )
            } catch (error: Throwable) {
                if (
                    error is CancellationException ||
                    error is MihomoCoreBusyException ||
                    error is MihomoCoreSetupTimeoutException
                ) {
                    throw error
                }
                lastFailure = error
                DiagnosticLogger.warn(
                    this,
                    "mihomo.chain.rejected",
                    "attempt=${index + 1}/${plans.size} hops=${plan.hops.size} chain=${plan.summary}",
                    error,
                )
            }
        }
        throw IOException("No configured connection chain passed the runtime health check", lastFailure)
    }

    private suspend fun connectWithTopIpCandidates(
        eventPrefix: String,
        snapshot: MihomoSubscriptionSnapshot,
        profiles: List<ConnectionProfile>,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        bypassConnectionCache: Boolean,
        excludedEndpoint: CleanIpResult?,
        excludedProfileFingerprint: String = "",
        frontingIps: List<String> = frontingIpPreferenceStore.readFrontingIps(),
        useCachedFirst: Boolean = true,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
    ): StartedMihomoRuntime {
        if (frontingIps.isEmpty() && useCachedFirst) {
            cachedTopIpCandidatePlan(profiles, excludedEndpoint)?.let { cachedPlan ->
                try {
                    return connectStartupTopIpCandidates(
                        eventPrefix = eventPrefix,
                        candidatePlan = cachedPlan,
                        snapshot = snapshot,
                        splitTunnelPlan = splitTunnelPlan,
                        selectedCountryCode = selectedCountryCode,
                        preferences = preferences,
                        availableProfiles = availableProfiles,
                        excludedProfileFingerprint = excludedProfileFingerprint,
                    )
                } catch (error: Throwable) {
                    if (
                        error is CancellationException ||
                        error is MihomoCoreBusyException ||
                        error is MihomoCoreSetupTimeoutException
                    ) {
                        throw error
                    }
                    DiagnosticLogger.warn(this, "startup.cachedTopIp.failed", "source=$eventPrefix", error)
                }
            }
        }
        val candidatePlan = findStartupTopIpCandidates(
            profiles = profiles,
            bypassConnectionCache = if (frontingIps.isEmpty()) true else bypassConnectionCache,
            excludedEndpoint = excludedEndpoint,
            frontingIps = frontingIps,
        )
        return connectStartupTopIpCandidates(
            eventPrefix = eventPrefix,
            candidatePlan = candidatePlan,
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            selectedCountryCode = selectedCountryCode,
            preferences = preferences,
            availableProfiles = availableProfiles,
            excludedProfileFingerprint = excludedProfileFingerprint,
        )
    }

    private fun cachedTopIpCandidatePlan(
        profiles: List<ConnectionProfile>,
        excludedEndpoint: CleanIpResult?,
    ): StartupTopIpCandidatePlan? {
        val candidates = StartupScanPolicy.automaticCandidates(
            subscriptionPorts = profiles.map { it.port },
            lastEndpoint = scanStateStore.readLastEndpoint(),
            cachedResults = cleanIpCache.readResults(),
            detectedCarrier = CarrierDetector.detect(this, refresh = true),
            nowMs = System.currentTimeMillis(),
            excludedEndpoint = excludedEndpoint,
        )
        if (candidates.isEmpty()) return null
        DiagnosticLogger.info(
            this,
            "startup.cachedTopIp.ready",
            "candidates=${candidates.size} latencyMs=${candidates.first().latencyMs}",
        )
        return StartupTopIpCandidatePlan(
            primaryPhase = "cached",
            primaryCandidates = candidates,
            exhaustiveCandidates = emptyList(),
        )
    }

    private suspend fun connectStartupTopIpCandidates(
        eventPrefix: String,
        candidatePlan: StartupTopIpCandidatePlan,
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String,
    ): StartedMihomoRuntime {
        return connectStartupTopIpCandidates(
            eventPrefix = eventPrefix,
            phase = candidatePlan.primaryPhase,
            candidates = candidatePlan.primaryCandidates + candidatePlan.exhaustiveCandidates,
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            selectedCountryCode = selectedCountryCode,
            preferences = preferences,
            availableProfiles = availableProfiles,
            excludedProfileFingerprint = excludedProfileFingerprint,
        )
    }

    private suspend fun connectStartupTopIpCandidates(
        eventPrefix: String,
        phase: String,
        candidates: List<CleanIpResult>,
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String,
    ): StartedMihomoRuntime {
        if (candidates.isEmpty()) {
            throw IOException("No $phase top-IP candidates are available")
        }
        val quarantineScope = ProfileFingerprint.sha256(snapshot.rawConfig)
        val availableCandidates = if (preferences.tlsIntegrityEnabled) {
            candidates.filterNot { scanStateStore.isTlsEndpointQuarantined(quarantineScope, it) }
        } else {
            candidates
        }.take(CleanIpDefaults.STARTUP_RUNTIME_ATTEMPTS.coerceAtLeast(1))
        if (availableCandidates.isEmpty()) {
            throw IOException("All $phase top-IP candidates are quarantined by the TLS integrity check")
        }

        publishAutomaticConnectionProgress(Actions.AUTO_PHASE_RUNTIME_TEST)
        var lastFailure: Throwable? = null
        for ((index, endpoint) in availableCandidates.withIndex()) {
            ensureStartupActive(eventPrefix)
            val attempt = index + 1
            try {
                DiagnosticLogger.info(
                    this,
                    "startup.topIp.try",
                    "phase=$phase attempt=$attempt/${availableCandidates.size} latencyMs=${endpoint.latencyMs} lossRate=${endpoint.lossRate}",
                )
                return startMihomoRuntimeAttempt(
                    snapshot = snapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    topEndpoint = endpoint,
                    preferences = preferences,
                    availableProfiles = availableProfiles,
                    excludedProfileFingerprint = excludedProfileFingerprint,
                ).also { startedRuntime ->
                    DiagnosticLogger.info(
                        this,
                        "startup.topIp.connected",
                        "phase=$phase attempt=$attempt/${availableCandidates.size}",
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (error is TlsIntegrityException) {
                    scanStateStore.quarantineTlsEndpoint(quarantineScope, endpoint)
                    DiagnosticLogger.warn(
                        this,
                        "tlsIntegrity.quarantined",
                        "phase=$phase",
                        error,
                    )
                }
                DiagnosticLogger.warn(
                    this,
                    "startup.topIp.rejected",
                    "phase=$phase attempt=$attempt/${availableCandidates.size}",
                    error,
                )
                if (!stopCoreService()) {
                    throw MihomoCoreBusyException(error)
                }
                if (error is MihomoCoreSetupTimeoutException) {
                    throw error
                }
            }
        }
        throw IOException("No $phase top-IP candidate passed Cat core delay and runtime health check", lastFailure)
    }

    private suspend fun startMihomoRuntimeAttempt(
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        topEndpoint: CleanIpResult?,
        validateConnectivity: Boolean = true,
        forcedProxyName: String? = null,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String = "",
        quickSpeedRequested: Boolean = false,
    ): StartedMihomoRuntime {
        // beta220: the core ignores Xray's `finalMask=tlshello…`; honour it with our fragmenting proxy for exactly
        // those proxies, even when the user left the global DPI bypass off.
        val finalMaskOnly = forcedProxyName == null && !preferences.dpiBypassEnabled &&
            MihomoDpiBypassPatcher.hasTlsHelloFinalMask(snapshot.rawConfig)
        if (finalMaskOnly) DiagnosticLogger.info(this, "dpiBypass.finalMask", "tlshello proxies fragmented by the local proxy")
        val dpiBypassEnabled = forcedProxyName == null && (preferences.dpiBypassEnabled || finalMaskOnly)
        val request = SessionPlanRequest(
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            availableProfiles = availableProfiles,
            selectedCountryCode = selectedCountryCode,
            topEndpoint = topEndpoint,
            validateConnectivity = validateConnectivity,
            dpiBypassEnabled = false,
            dpiBypassPort = null,
            forcedProxyName = forcedProxyName,
            excludedProfileFingerprint = excludedProfileFingerprint,
            quickSpeedRequested = quickSpeedRequested,
        )

        fun plan(
            dpiEnabled: Boolean,
            dpiPort: Int?,
            allowAutomaticBridge: Boolean,
        ): SessionPlan =
            SessionPlanner.resolve(
                request = request.copy(
                    dpiBypassEnabled = dpiEnabled,
                    dpiBypassPort = dpiPort,
                    dpiBypassFinalMaskOnly = dpiEnabled && finalMaskOnly,
                    allowAutomaticBridge = allowAutomaticBridge,
                ),
                preferences = preferences,
            )

        var automaticBridgeAvailable = true
        suspend fun startWithAutomaticBridgeFallback(dpiEnabled: Boolean): StartedMihomoRuntime {
            val dpiPort = if (dpiEnabled) DpiBypassPort.allocate() else null
            if (!automaticBridgeAvailable) {
                return startMihomoRuntimeAttemptOnce(
                    plan(dpiEnabled, dpiPort, allowAutomaticBridge = false),
                )
            }
            return try {
                startMihomoRuntimeAttemptOnce(
                    plan(dpiEnabled, dpiPort, allowAutomaticBridge = true),
                )
            } catch (error: AutomaticRoutingBridgeStartupException) {
                DiagnosticLogger.warn(
                    this,
                    "mihomo.automatic.bridge.startupFallback",
                    "retry=originalRuntime",
                    error,
                )
                automaticBridgeAvailable = false
                stopActiveCoreForReplacement()
                startMihomoRuntimeAttemptOnce(
                    plan(dpiEnabled, dpiPort, allowAutomaticBridge = false),
                )
            }
        }

        return try {
            startWithAutomaticBridgeFallback(dpiBypassEnabled)
        } catch (error: DpiBypassStartupException) {
            DiagnosticLogger.warn(
                this,
                "dpiBypass.fallback.direct",
                "fronting=${topEndpoint != null}",
                error,
            )
            stopActiveCoreForReplacement()
            startWithAutomaticBridgeFallback(false)
        }
    }

    private suspend fun startMihomoRuntimeAttemptOnce(plan: SessionPlan): StartedMihomoRuntime {
        val snapshot = plan.snapshot
        val splitTunnelPlan = plan.splitTunnelPlan
        val availableProfiles = plan.availableProfiles
        val selectedCountryCode = plan.selectedCountryCode
        val topEndpoint = plan.topEndpoint
        val validateConnectivity = plan.validateConnectivity
        val dpiBypassEnabled = plan.dpiBypassEnabled
        val dpiBypassPort = plan.dpiBypassPort
        val forcedProxyName = plan.forcedProxyName
        val excludedProfileFingerprint = plan.excludedProfileFingerprint
        val connectionOptions = plan.connectionOptions
        val selectedSubscriptionId = plan.selectedSubscriptionId
        val explicitProfile = plan.explicitProfile
        val selectedAutomaticTypes = plan.selectedAutomaticTypes
        val automaticSelections = plan.automaticSelections
        val automaticSelectionEligible = plan.automaticSelectionEligible
        val quickSpeedEligible = plan.quickSpeedEligible
        val bridgeEligible = plan.bridgeEligible
        val bridgeResult = plan.bridgeResult
        val selectedMap = plan.selectedMap
        if (activeRuntimePaths != null || !coreLifecycle.isIdle()) {
            stopActiveCoreForReplacement()
        }
        if (plan.quickSpeedRequested && !quickSpeedEligible) {
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.skipped",
                "reason=modeIneligible country=${selectedCountryCode != null} " +
                    "explicit=${explicitProfile != null} filtered=${selectedAutomaticTypes.isNotEmpty()} " +
                    "fronting=${topEndpoint != null} forced=${forcedProxyName != null}",
            )
        } else if (quickSpeedEligible) {
            DiagnosticLogger.info(this, "mihomo.quickSpeed.eligible")
        }
        val paths = try {
            MihomoRuntimeConfigBuilder(this).write(plan)
        } catch (error: Throwable) {
            if (
                shouldRetryOriginalAfterAutomaticBridgeFailure(
                    bridgeApplied = bridgeResult.applied,
                    phase = AutomaticBridgeFailurePhase.ConfigCoreOrController,
                    error = error,
                )
            ) {
                throw AutomaticRoutingBridgeStartupException(error)
            }
            throw error
        }
        DiagnosticLogger.info(
            this,
            "mihomo.automatic.bridge",
            "eligible=$bridgeEligible applied=${bridgeResult.applied} reason=${bridgeResult.reason} " +
                "root=${bridgeResult.rootName.orEmpty()} target=${bridgeResult.targetName.orEmpty()}",
        )
        DiagnosticLogger.info(
            this,
            "mihomo.runtime.files",
            "config=${paths.runtimeConfigYaml.absolutePath} service=${paths.serviceJson.absolutePath} patch=${paths.patchFinalJson.absolutePath} controller=${MihomoRuntimeDefaults.CONTROLLER_HOST}:${paths.controlPort} serverOverride=${plan.serverOverrideIp != null} portOverride=${plan.serverOverridePort != null}",
        )
        DiagnosticLogger.info(
            this,
            "mihomo.startup.flow",
            "frontingScanner=${plan.serverOverrideIp != null} nativeEligible=$automaticSelectionEligible bridge=${bridgeResult.applied} preselected=${selectedMap.size} dpiBypass=$dpiBypassEnabled dpiBypassPort=${dpiBypassPort ?: 0} routing=${plan.routingMode.wireName} dnsPrivacy=${plan.dns.mode.wireName} tlsIntegrity=${plan.tlsIntegrityEnabled} amneziaNoise=${connectionOptions.amneziaNoiseEnabled} randomController=true nodeBucket=${nodeCountBucket(snapshot.summary.proxies.size)} groups=${snapshot.summary.groups.size}",
        )

        val controller = MihomoControllerClient(paths.secret, port = paths.controlPort)
        try {
            val setupStartedAt = SystemClock.elapsedRealtime()
            setupCoreRuntime(paths, dpiBypassPort)
            activeRuntimePaths = paths
            DiagnosticLogger.info(
                this,
                "mihomo.core.setup.duration",
                "elapsedMs=${SystemClock.elapsedRealtime() - setupStartedAt} nodeBucket=${nodeCountBucket(snapshot.summary.proxies.size)}",
            )
            val controllerStartedAt = SystemClock.elapsedRealtime()
            waitForController(controller, paths)
            DiagnosticLogger.info(
                this,
                "mihomo.controller.ready.duration",
                "elapsedMs=${SystemClock.elapsedRealtime() - controllerStartedAt}",
            )
        } catch (error: Throwable) {
            if (
                error !is DpiBypassStartupException &&
                shouldRetryOriginalAfterAutomaticBridgeFailure(
                    bridgeApplied = bridgeResult.applied,
                    phase = AutomaticBridgeFailurePhase.ConfigCoreOrController,
                    error = error,
                )
            ) {
                throw AutomaticRoutingBridgeStartupException(error)
            }
            throw error
        }
        handleDefaultNetworkChanged(networkMonitor.currentDefaultNetwork(), force = true)
        val selectedName = bridgeResult.rootName
            ?: MihomoSelectionPolicy.trafficProbeGroup(snapshot.summary)?.name
            ?: MihomoSelectionPolicy.mainSelectorGroup(snapshot.summary)?.name
            ?: throw IOException("Cat core traffic selector is unavailable")
        val preferredSelectorRoots = listOf(selectedName)
        suspend fun getStartupProxies(): JSONObject = withContext(Dispatchers.IO) {
            controller.getProxies(RUNTIME_HEALTH_TIMEOUT_MS.toInt())
        }
        var liveProxies = try {
            getStartupProxies()
        } catch (error: Throwable) {
            if (
                shouldRetryOriginalAfterAutomaticBridgeFailure(
                    bridgeApplied = bridgeResult.applied,
                    phase = AutomaticBridgeFailurePhase.ConfigCoreOrController,
                    error = error,
                )
            ) {
                throw AutomaticRoutingBridgeStartupException(error)
            }
            throw error
        }
        var selections: List<MihomoGroupSelection> = emptyList()
        var nativeAutomaticStart = false
        var fallbackAttemptCount = 0
        var selectedLeaf: ConnectionProfile? = null
        var quickSpeedPin: QuickSpeedPin? = null
        var quickSpeedHandled = false

        suspend fun applyAndVerify(path: List<MihomoGroupSelection>): JSONObject {
            path.forEach { selection ->
                withContext(Dispatchers.IO) {
                    controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                }
                DiagnosticLogger.info(
                    this,
                    "mihomo.selection.applied",
                    "selector=${selection.selectorGroup} selected=${selection.selectedGroup}",
                )
            }
            val updated = getStartupProxies()
            val verified = MihomoControllerProxies.currentSelections(updated, path)
                .associate { it.selectorGroup to it.selectedGroup }
            if (path.any { verified[it.selectorGroup] != it.selectedGroup }) {
                throw IOException("Cat core selector update was not applied")
            }
            return updated
        }

        if (forcedProxyName != null || explicitProfile != null) {
            val targetName = forcedProxyName ?: explicitProfile!!.tag
            val path = MihomoControllerProxies.selectorPath(
                response = liveProxies,
                targetName = targetName,
                preferredRoots = preferredSelectorRoots,
            )
            if (path.isEmpty()) {
                throw IOException(
                    if (forcedProxyName != null) {
                        "Chained proxy is not selectable"
                    } else {
                        "Explicit connection is not selectable"
                    },
                )
            }
            liveProxies = applyAndVerify(path)
            if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, targetName)) {
                throw IOException("Cat core traffic selector did not resolve through the requested connection")
            }
            val explicitDelayMs = explicitProfile
                ?.takeIf { forcedProxyName == null }
                ?.let { profile ->
                    realConnectionDelayMs(
                        controller = controller,
                        profile = profile,
                        timeoutMs = FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS,
                    )
                }
            if (explicitDelayMs == null) {
                verifyRuntimeHealth(
                    timeoutMs = RUNTIME_HEALTH_TIMEOUT_MS,
                    event = "mihomo.proxy.health.explicit",
                )
            }
            liveProxies = getStartupProxies()
            if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, targetName)) {
                throw IOException("Cat core traffic route changed before startup completed")
            }
            selections = path
        } else {
            val preferredGroups = buildList {
                bridgeResult.targetName?.let(::add)
                addAll(automaticSelections.map(MihomoGroupSelection::selectedGroup))
            }.distinct()
            val rejectedAdaptiveGroups = linkedSetOf<String>()
            val rejectedAdaptiveTypes = linkedSetOf<String>()
            var adaptiveAttempt = 0
            if (automaticSelectionEligible) {
                while (true) {
                    val adaptivePlan = MihomoControllerProxies.rootScopedAdaptivePlan(
                        response = liveProxies,
                        rootName = selectedName,
                        preferredGroupNames = preferredGroups,
                        excludedGroupNames = rejectedAdaptiveGroups,
                        excludedGroupTypes = rejectedAdaptiveTypes,
                    ) ?: break
                    adaptiveAttempt += 1
                    DiagnosticLogger.info(
                        this,
                        "mihomo.selection.adaptive.plan",
                        "root=$selectedName group=${adaptivePlan.groupName} type=${adaptivePlan.groupType} hops=${adaptivePlan.selections.size} attempt=$adaptiveAttempt",
                    )
                    var selectorVerified = false
                    try {
                        liveProxies = applyAndVerify(adaptivePlan.selections)
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, adaptivePlan.groupName)) {
                            throw IOException("Adaptive group is not active on the traffic path")
                        }
                        selectorVerified = true
                        verifyRuntimeHealth(
                            timeoutMs = if (adaptiveAttempt == 1) {
                                RUNTIME_HEALTH_TIMEOUT_MS
                            } else {
                                FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS
                            },
                            event = "mihomo.proxy.health.adaptive",
                        )
                        liveProxies = getStartupProxies()
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, adaptivePlan.groupName)) {
                            throw IOException("Adaptive traffic route changed before startup completed")
                        }
                        if (
                            quickSpeedEligible &&
                            adaptivePlan.groupType.lowercase().filter(Char::isLetterOrDigit) == "urltest"
                        ) {
                            quickSpeedHandled = true
                            val quickResult = selectQuickFastestReconnect(
                                controller = controller,
                                paths = paths,
                                initialResponse = liveProxies,
                                rootName = selectedName,
                                groupName = adaptivePlan.groupName,
                                availableProfiles = availableProfiles,
                            )
                            liveProxies = quickResult.response
                            quickSpeedPin = quickResult.pin
                        } else if (quickSpeedEligible) {
                            quickSpeedHandled = true
                            DiagnosticLogger.info(
                                this,
                                "mihomo.quickSpeed.skipped",
                                "reason=adaptiveGroupNotUrlTest",
                            )
                        }
                        selections = adaptivePlan.selections
                        nativeAutomaticStart = true
                        DiagnosticLogger.info(
                            this,
                            "mihomo.selection.adaptive.healthy",
                            "root=$selectedName group=${adaptivePlan.groupName} type=${adaptivePlan.groupType} hops=${adaptivePlan.selections.size}",
                        )
                        break
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        if (
                            !selectorVerified &&
                            adaptivePlan.groupName == bridgeResult.targetName &&
                            shouldRetryOriginalAfterAutomaticBridgeFailure(
                                bridgeApplied = bridgeResult.applied,
                                phase = AutomaticBridgeFailurePhase.Selector,
                                error = error,
                            )
                        ) {
                            throw AutomaticRoutingBridgeStartupException(error)
                        }
                        rejectedAdaptiveGroups += adaptivePlan.groupName
                        rejectedAdaptiveTypes += adaptivePlan.groupType
                        DiagnosticLogger.warn(
                            this,
                            "mihomo.selection.adaptive.rejected",
                            "root=$selectedName group=${adaptivePlan.groupName} type=${adaptivePlan.groupType} attempt=$adaptiveAttempt",
                            error,
                        )
                        liveProxies = getStartupProxies()
                        if (selectedCountryCode != null) break
                    }
                }
            }
            if (automaticSelectionEligible && adaptiveAttempt == 0) {
                if (bridgeResult.applied) {
                    throw AutomaticRoutingBridgeStartupException(
                        IOException("Bridged automatic selector target is unavailable"),
                    )
                }
                DiagnosticLogger.info(
                    this,
                    "mihomo.selection.adaptive.unavailable",
                    "root=$selectedName",
                )
            }

            if (!nativeAutomaticStart) {
                val candidates = ConnectionLocationPolicy.filterProfiles(
                    profiles = ConnectionTypeSelectionPolicy.filterProfiles(
                        availableProfiles,
                        selectedAutomaticTypes,
                    ),
                    selectedCountryCode = selectedCountryCode,
                ).profiles
                if (candidates.isEmpty()) {
                    throw IOException("Selected connection types do not contain an eligible connection")
                }
                val records = withContext(Dispatchers.IO) {
                    subscriptionStore.readConnectionDelayRecords(
                        subscriptionId = selectedSubscriptionId,
                        profiles = candidates,
                    )
                }
                val selectableNames = MihomoControllerProxies.selectableTargetNames(
                    response = liveProxies,
                    targetNames = candidates.map(ConnectionProfile::tag),
                    preferredRoots = preferredSelectorRoots,
                )
                val orderedCandidates = AutomaticConnectionCandidatePolicy.order(
                    profiles = candidates.filter { it.tag in selectableNames },
                    records = records,
                    lastSelectedProfile = scanStateStore.readLastSelectedProfile(candidates),
                    excludedFingerprint = excludedProfileFingerprint,
                    limit = CleanIpDefaults.STARTUP_RUNTIME_ATTEMPTS.coerceAtLeast(1),
                    sticky = getSharedPreferences("cat_client_theme", MODE_PRIVATE).getBoolean("sticky_location", true),
                    networkGood = routeMemoryGood(),
                    networkBad = routeMemoryBad(),
                )
                var lastFailure: Throwable? = null
                for ((index, candidate) in orderedCandidates.withIndex()) {
                    fallbackAttemptCount += 1
                    val path = MihomoControllerProxies.selectorPath(
                        response = liveProxies,
                        targetName = candidate.tag,
                        preferredRoots = preferredSelectorRoots,
                    )
                    if (path.isEmpty()) continue
                    try {
                        liveProxies = applyAndVerify(path)
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, candidate.tag)) {
                            throw IOException("Fallback connection is not active on the traffic path")
                        }
                        verifyRuntimeHealth(
                            timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                            event = "mihomo.proxy.health.fallback",
                        )
                        liveProxies = getStartupProxies()
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, candidate.tag)) {
                            throw IOException("Fallback traffic route changed before startup completed")
                        }
                        selections = path
                        selectedLeaf = candidate
                        DiagnosticLogger.info(
                            this,
                            "mihomo.selection.automatic.healthy",
                            "attempt=${index + 1}/${orderedCandidates.size}",
                        )
                        break
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        lastFailure = error
                        DiagnosticLogger.warn(
                            this,
                            "mihomo.selection.automatic.rejected",
                            "attempt=${index + 1}/${orderedCandidates.size}",
                            error,
                        )
                        liveProxies = getStartupProxies()
                    }
                }
                if (selectedLeaf == null) {
                    throw IOException(
                        "No selectable connection passed the runtime health check",
                        lastFailure,
                    )
                }
            }
            if (quickSpeedEligible && !quickSpeedHandled) {
                DiagnosticLogger.info(
                    this,
                    "mihomo.quickSpeed.skipped",
                    "reason=noHealthyUrlTestPath",
                )
            }
        }

        val selection = selections.firstOrNull()
        var activeProxyName = MihomoControllerProxies.activeProxyName(liveProxies, selectedName)
        DiagnosticLogger.info(
            this,
            "mihomo.active.proxy",
            "selected=$selectedName active=${activeProxyName.orEmpty()} nativeAutomatic=$nativeAutomaticStart fallbackAttempts=$fallbackAttemptCount",
        )
        var delayProbeName = activeProxyName?.takeIf(String::isNotBlank) ?: selectedName
        if (delayProbeName.isBlank()) {
            throw IOException("No Cat core proxy selected for delay test")
        }
        var lastDelayFailure: Throwable? = null
        var delayMs = quickSpeedPin?.delayMs?.toLong() ?: if (validateConnectivity) {
            MihomoRuntimeDefaults.HEALTH_URLS.firstNotNullOfOrNull { url ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        MihomoDelayPolicy.acceptedDelayMs(
                            controller.delay(
                                delayProbeName,
                                timeoutMs = FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS,
                                url = url,
                            ),
                        )
                    }
                }.onFailure { lastDelayFailure = it }.getOrNull()
            } ?: -1L
        } else {
            -1L
        }
        if (validateConnectivity) {
            if (delayMs > 0) {
                DiagnosticLogger.info(
                    this,
                    "mihomo.delay.ok",
                    "name=$delayProbeName delayMs=$delayMs",
                )
            } else {
                DiagnosticLogger.warn(
                    this,
                    "mihomo.delay.unavailable",
                    "name=$delayProbeName continuingWithHttpHealth=true",
                    lastDelayFailure,
                )
            }
        } else {
            DiagnosticLogger.info(this, "mihomo.delay.deferred", "name=$delayProbeName")
        }
        suspend fun restoreQuickSpeedPinIfInvalidated(stage: String) {
            val pin = quickSpeedPin ?: return
            if (
                quickSpeedNetworkUnchanged(
                    pin.networkFingerprint,
                    networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint(),
                ) &&
                activeQuickSpeedPin == pin &&
                invalidatedQuickSpeedPin != pin
            ) {
                return
            }
            liveProxies = controllerSelectionMutex.withLock {
                restoreQuickSpeedSelection(controller, pin.groupName, pin.originalFixed)
            }
            if (activeQuickSpeedPin == pin) activeQuickSpeedPin = null
            if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
            quickSpeedPin = null
            verifyRuntimeHealth(
                timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                event = "mihomo.quickSpeed.network.health",
            )
            liveProxies = runInterruptible(Dispatchers.IO) { controller.getProxies() }
            if (!MihomoControllerProxies.isActiveThrough(liveProxies, pin.rootName, pin.groupName)) {
                throw IOException("Automatic route changed after quick speed pin restoration")
            }
            activeProxyName = MihomoControllerProxies.activeProxyName(liveProxies, selectedName)
            delayProbeName = activeProxyName?.takeIf(String::isNotBlank) ?: selectedName
            delayMs = -1L
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.network.invalidated",
                "stage=$stage",
            )
        }

        verifyTlsIntegrity(plan.tlsIntegrityEnabled)
        restoreQuickSpeedPinIfInvalidated("beforeTun")
        if (plan.effectiveDeviceAccess == EffectiveDeviceAccess.TunnelAccess) {
            startCoreTun(paths, splitTunnelPlan)
        } else {
            DiagnosticLogger.info(
                this,
                "mihomo.core.started",
                "config=${paths.runtimeConfigYaml.absolutePath} mode=proxy tun=false endpoint=127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}",
            )
        }
        restoreQuickSpeedPinIfInvalidated("beforeStarted")
        DiagnosticLogger.info(
            this,
            "startup.topIp.validated",
            "fronting=${topEndpoint != null} selected=$selectedName active=${activeProxyName.orEmpty()} delayProbe=$delayProbeName delayMs=$delayMs validated=$validateConnectivity",
        )

        val profile = MihomoConnectionOptionsPolicy.applyTo(
            activeProfile(snapshot, selectedCountryCode, selection, activeProxyName),
            connectionOptions,
        )
        val endpoint = if (topEndpoint == null) {
            originalEndpointForSelection(snapshot, selectedCountryCode, activeProxyName, delayMs)
        } else {
            runtimeEndpointForSelection(
                topEndpoint = topEndpoint,
                snapshot = snapshot,
                selectedCountryCode = selectedCountryCode,
                selection = selection,
                activeProxyName = activeProxyName,
                serverOverridePort = plan.serverOverridePort,
                delayMs = delayMs,
            )
        }
        return StartedMihomoRuntime(
            profile = profile,
            endpoint = endpoint,
            delayMs = delayMs,
            paths = paths,
            delayProbeName = delayProbeName,
            cacheEndpoint = topEndpoint != null,
            selectedCountryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode),
            availableProfiles = availableProfiles,
            nativeAutomaticStart = nativeAutomaticStart,
            subscriptionId = selectedSubscriptionId,
            selectorRootName = selectedName,
            selectorRoots = preferredSelectorRoots,
            quickSpeedPin = quickSpeedPin,
        )
    }

    private suspend fun applyStartedRuntime(
        startedRuntimeCandidate: StartedMihomoRuntime,
        eventPrefix: String,
        notice: String? = null,
        showServer: Boolean,
    ) = controllerSelectionMutex.withLock {
        var startedRuntime = prepareQuickSpeedRuntimeForStarted(startedRuntimeCandidate)
        while (true) {
            val committed = synchronized(quickSpeedStartedLock) {
                val pendingPin = startedRuntime.quickSpeedPin
                val invalidated = pendingPin != null &&
                    (
                        invalidatedQuickSpeedPin == pendingPin ||
                            !quickSpeedNetworkUnchanged(
                                pendingPin.networkFingerprint,
                                networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint(),
                            )
                        )
                if (invalidated) {
                    false
                } else {
                    commitStartedRuntime(startedRuntime, eventPrefix, notice, showServer)
                    true
                }
            }
            if (committed) return@withLock
            startedRuntime = prepareQuickSpeedRuntimeForStarted(startedRuntime)
        }
    }

    private fun commitStartedRuntime(
        startedRuntime: StartedMihomoRuntime,
        eventPrefix: String,
        notice: String?,
        showServer: Boolean,
    ) {
        activeProfile = startedRuntime.profile
        activeProfileShowsServer = showServer
        activeEndpoint = startedRuntime.endpoint
        activeDelayMs = startedRuntime.delayMs
        activeRuntimePaths = startedRuntime.paths
        activeNativeAutomaticStart = startedRuntime.nativeAutomaticStart
        activeConnectionChained = startedRuntime.chainHopCount > 1
        activeChainHopCount = startedRuntime.chainHopCount
        activeSubscriptionId = startedRuntime.subscriptionId
        activeSelectorRootName = startedRuntime.selectorRootName
        activeSelectorRoots = startedRuntime.selectorRoots
        activeAvailableProfiles = startedRuntime.availableProfiles
        activeRuntimeUsesEndpointOverride = startedRuntime.cacheEndpoint
        activeQuickSpeedPin = startedRuntime.quickSpeedPin?.takeIf { activeQuickSpeedPin == it }
        activeSelectableConnectionFingerprints = emptySet()
        activeSelectorReady = false
        val initialLeaf = startedRuntime.availableProfiles.firstOrNull { candidate ->
            candidate.fingerprint == startedRuntime.profile.fingerprint
        }
        activeConnectionTag = initialLeaf?.tag
            ?: startedRuntime.profile.tag.takeIf { activeConnectionChained }.orEmpty()
        activeConnectionFingerprint = initialLeaf?.fingerprint
            ?: startedRuntime.profile.fingerprint.takeIf { activeConnectionChained }.orEmpty()
        activeConnectionCountryFlag = startedRuntime.selectedCountryCode
            ?.let(ConnectionLocationPolicy::countryFromCode)
            ?.flag
            ?: startedRuntime.profile.let(ConnectionLocationPolicy::countryForProfile)?.flag.orEmpty()
        val configuredFrontingIps = frontingIpPreferenceStore.readFrontingIps()
        activeFrontingIp = FrontingIpPolicy.matchingValue(
            configuredFrontingIps,
            startedRuntime.endpoint.ip,
            startedRuntime.endpoint.port,
        ).orEmpty()
        if (!activeConnectionChained && !startedRuntime.profile.type.equals("mihomo-group", ignoreCase = true)) {
            scanStateStore.saveLastSelectedProfile(
                SelectedConnectionProfile(
                    profile = startedRuntime.profile,
                    delayMs = startedRuntime.delayMs.takeIf { it > 0 }?.toInt() ?: Int.MAX_VALUE,
                    selectedAt = System.currentTimeMillis(),
                ),
            )
        }
        if (
            startedRuntime.cacheEndpoint &&
            FrontingIpPolicy.matchingValue(
                configuredFrontingIps,
                startedRuntime.endpoint.ip,
                startedRuntime.endpoint.port,
            ) == null
        ) {
            cleanIpCache.saveResult(startedRuntime.endpoint)
            scanStateStore.saveLastEndpoint(startedRuntime.endpoint)
        }
        sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        publishState(VpnState.Started, notice)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, serviceNotification(connectedNotificationText()))
        DiagnosticLogger.info(
            this,
            "$eventPrefix.started",
            "nativeAutomatic=${startedRuntime.nativeAutomaticStart} chained=${startedRuntime.chainHopCount > 1} delayMs=${startedRuntime.delayMs}",
        )
        startBackgroundSubscriptionRefresh()
        startPostConnectHealthWatchdog()
        refreshDelayInBackground(startedRuntime)
        if (startedRuntime.selectedCountryCode == null) {
            refreshEgressCountryInBackground(startedRuntime)
        }
    }

    private fun refreshDelayInBackground(startedRuntime: StartedMihomoRuntime) {
        scope.launch(Dispatchers.IO) {
            val controller = MihomoControllerClient(
                startedRuntime.paths.secret,
                port = startedRuntime.paths.controlPort,
            )
            val resolvedProfile = if (startedRuntime.chainHopCount > 1) null else runCatching {
                controllerSelectionMutex.withLock {
                    val response = controller.getProxies()
                    applyLiveSelectorSnapshot(startedRuntime.paths, response)
                }
            }.onFailure { error ->
                DiagnosticLogger.warn(
                    this@CatClientVpnService,
                    "mihomo.selector.graph.failed",
                    error = error,
                )
            }.getOrNull()
            val name = if (startedRuntime.chainHopCount > 1) {
                startedRuntime.delayProbeName
            } else {
                activeConnectionTag.takeIf(String::isNotBlank)
                    ?: startedRuntime.delayProbeName.takeIf(String::isNotBlank)
                    ?: return@launch
            }
            val measuredDelayMs = runCatching {
                controller.delay(name, timeoutMs = BACKGROUND_MIHOMO_DELAY_TIMEOUT_MS)
                    ?.toLong()
            }.onFailure { error ->
                DiagnosticLogger.warn(this@CatClientVpnService, "mihomo.delay.background.failed", "name=$name", error)
            }.getOrNull()?.takeIf { it > 0 }
            if (resolvedProfile == null && measuredDelayMs == null) return@launch

            if (state != VpnState.Started || activeRuntimePaths != startedRuntime.paths) return@launch
            if (!activeConnectionChained && activeConnectionTag.isNotBlank() && activeConnectionTag != name) {
                return@launch
            }
            val profile = resolvedProfile ?: startedRuntime.profile
            val checkedAt = System.currentTimeMillis()
            val delayMs = measuredDelayMs ?: startedRuntime.delayMs
            val endpoint = if (resolvedProfile != null && !startedRuntime.cacheEndpoint) {
                CleanIpResult(
                    ip = resolvedProfile.server,
                    port = resolvedProfile.port,
                    latencyMs = delayMs,
                    lossRate = 0.0,
                    checkedAt = checkedAt,
                )
            } else {
                startedRuntime.endpoint.copy(latencyMs = delayMs, checkedAt = checkedAt)
            }
            if (delayMs > 0) {
                activeDelayMs = delayMs
            }
            activeEndpoint = endpoint
            if (!activeConnectionChained && !profile.type.equals("mihomo-group", ignoreCase = true)) {
                scanStateStore.saveLastSelectedProfile(
                    SelectedConnectionProfile(
                        profile,
                        delayMs.takeIf { it > 0 }?.toInt() ?: Int.MAX_VALUE,
                        checkedAt,
                    ),
                )
            }
            if (
                measuredDelayMs != null &&
                startedRuntime.cacheEndpoint &&
                FrontingIpPolicy.matchingValue(
                    frontingIpPreferenceStore.readFrontingIps(),
                    endpoint.ip,
                    endpoint.port,
                ) == null
            ) {
                cleanIpCache.saveResult(endpoint)
                scanStateStore.saveLastEndpoint(endpoint)
            }
            DiagnosticLogger.info(
                this@CatClientVpnService,
                if (measuredDelayMs != null) {
                    "mihomo.delay.background.ok"
                } else {
                    "mihomo.active.proxy.background.ok"
                },
                "resolved=${resolvedProfile != null} delayMs=${measuredDelayMs ?: -1L} fronting=${startedRuntime.cacheEndpoint}",
            )
        }
    }

    internal suspend fun refreshLiveSelectorState() {
        if (activeConnectionChained) return
        if (connectionDelayTestJob?.isActive == true || connectionSpeedTestJobs.isNotEmpty()) return
        val paths = activeRuntimePaths ?: return
        controllerSelectionMutex.withLock {
            if (state != VpnState.Started || activeRuntimePaths != paths) return@withLock
            val controller = MihomoControllerClient(paths.secret, port = paths.controlPort)
            applyLiveSelectorSnapshot(paths, controller.getProxies())
        }
    }

    internal fun applyLiveSelectorSnapshot(
        paths: MihomoRuntimePaths,
        response: JSONObject,
        publishIfChanged: Boolean = true,
    ): ConnectionProfile? {
        if (activeConnectionChained) return null
        if (state != VpnState.Started || activeRuntimePaths != paths) return null
        val selectableNames = MihomoControllerProxies.selectableTargetNames(
            response = response,
            targetNames = activeAvailableProfiles.map(ConnectionProfile::tag),
            preferredRoots = activeSelectorRootName
                .takeIf(String::isNotBlank)
                ?.let(::listOf)
                ?: activeSelectorRoots,
        )
        val selectableFingerprints = activeAvailableProfiles.asSequence()
            .filter { it.tag in selectableNames }
            .map(ConnectionProfile::fingerprint)
            .toSet()
        val activeName = MihomoControllerProxies.activeProxyName(response, activeSelectorRootName)
            .orEmpty()
        val resolvedProfile = activeAvailableProfiles.firstOrNull { it.tag == activeName }
        val previousReady = activeSelectorReady
        val previousSelectable = activeSelectableConnectionFingerprints
        val previousTag = activeConnectionTag
        val previousFingerprint = activeConnectionFingerprint

        activeSelectorReady = true
        activeSelectableConnectionFingerprints = selectableFingerprints
        activeConnectionTag = activeName
        activeConnectionFingerprint = resolvedProfile?.fingerprint.orEmpty()
        if (resolvedProfile != null) {
            activeProfile = MihomoConnectionOptionsPolicy.applyTo(
                resolvedProfile,
                connectionOptionsPreferenceStore.read(),
            )
            if (!activeRuntimeUsesEndpointOverride) {
                activeEndpoint = CleanIpResult(
                    ip = resolvedProfile.server,
                    port = resolvedProfile.port,
                    latencyMs = activeDelayMs,
                    lossRate = 0.0,
                    checkedAt = System.currentTimeMillis(),
                )
            }
        }
        val changed = !previousReady ||
            previousSelectable != selectableFingerprints ||
            previousTag != activeName ||
            previousFingerprint != activeConnectionFingerprint
        if (changed && publishIfChanged) publishState(VpnState.Started)
        if (changed) {
            DiagnosticLogger.info(
                this,
                "mihomo.selector.graph.updated",
                "root=$activeSelectorRootName active=$activeName selectable=${selectableFingerprints.size}",
            )
        }
        return resolvedProfile
    }

    private fun refreshEgressCountryInBackground(startedRuntime: StartedMihomoRuntime) {
        scope.launch(Dispatchers.IO) {
            val country = runCatching {
                MihomoRuntimeHealth.egressCountryCodeThroughMixedProxy()
                    ?.let(ConnectionLocationPolicy::countryFromCode)
            }.onFailure { error ->
                DiagnosticLogger.warn(this@CatClientVpnService, "mihomo.egress.country.failed", error = error)
            }.getOrNull() ?: return@launch

            if (state != VpnState.Started || activeRuntimePaths != startedRuntime.paths) return@launch
            val previousFlag = activeConnectionCountryFlag
            activeConnectionCountryFlag = country.flag
            if (previousFlag != country.flag) {
                publishState(VpnState.Started)
            }
            DiagnosticLogger.info(
                this@CatClientVpnService,
                "mihomo.egress.country.ok",
                "code=${country.code} flag=${country.flag} previousFlag=$previousFlag",
            )
        }
    }

    private fun eligibleProfilesForRuntime(catalog: SubscriptionCatalog): List<ConnectionProfile> {
        val profiles = catalog.profiles
        DiagnosticLogger.info(
            this,
            "config.profiles",
            "count=${profiles.size} types=${profiles.groupingBy { it.type }.eachCount()}",
        )
        if (profiles.isEmpty()) {
            throw IOException("Subscription has no supported Cat core proxies")
        }

        val defaultNetworkHasIpv6 = networkMonitor.hasUsableIpv6DefaultNetwork()
        val runtimeProfiles = profiles
            .filterNot { it.isIpv6Literal && !defaultNetworkHasIpv6 }
            .ifEmpty { profiles }
        val skippedProfiles = profiles.size - runtimeProfiles.size
        if (skippedProfiles > 0) {
            DiagnosticLogger.info(
                this,
                "config.profiles.filtered",
                "skippedIpv6Literal=$skippedProfiles runtimeProfiles=${runtimeProfiles.size} defaultNetworkHasIpv6=$defaultNetworkHasIpv6",
            )
        }
        return runtimeProfiles
    }

    private fun nodeCountBucket(count: Int): String = when {
        count < 50 -> "0-49"
        count < 200 -> "50-199"
        count < 500 -> "200-499"
        count < 1_000 -> "500-999"
        else -> "1000+"
    }

    private fun profilesForSelectedLocation(
        profiles: List<ConnectionProfile>,
        selectedCountryCode: String?,
    ): List<ConnectionProfile> {
        val result = ConnectionLocationPolicy.filterProfiles(profiles, selectedCountryCode)
        if (result.resetToAuto) {
            locationPreferenceStore.clearSelectedCountry()
            DiagnosticLogger.warn(
                this,
                "location.selection.reset",
                "missingCountry=${selectedCountryCode.orEmpty()} fallback=Auto profiles=${profiles.size}",
            )
        }
        DiagnosticLogger.info(
            this,
            "location.selection",
            "code=${result.selectedCountryCode ?: "auto"} label=${result.selectedLabel} profiles=${result.profiles.size}/${profiles.size}",
        )
        return result.profiles
    }

    private suspend fun findStartupTopIpCandidates(
        profiles: List<ConnectionProfile>,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
        frontingIps: List<String> = frontingIpPreferenceStore.readFrontingIps(),
    ): StartupTopIpCandidatePlan {
        val subscriptionPorts = profiles
            .map { it.port }
            .filter { it > 0 }
        if (subscriptionPorts.isEmpty()) throw IOException("Subscription has no usable proxy ports")

        val tcpProbePorts = StartupScanPolicy.tcpProbePorts(profiles)
        val priorityPorts = StartupScanPolicy.priorityPorts(tcpProbePorts)
        val fallbackPorts = StartupScanPolicy.fallbackPorts(tcpProbePorts)
        val connectionPorts = StartupScanPolicy.orderedConnectionPorts(subscriptionPorts)
        logScanInfo(
            "scanner.startup.ports",
            "priority=$priorityPorts fallback=$fallbackPorts connection=$connectionPorts subscription=${subscriptionPorts.distinct().sorted()} tcpProbe=$tcpProbePorts",
        )

        frontingIps.takeIf { it.isNotEmpty() }?.let { frontingIps ->
            val candidates = StartupScanPolicy.frontingCandidates(
                frontingIps = frontingIps,
                subscriptionPorts = subscriptionPorts,
                checkedAt = System.currentTimeMillis(),
                excludedEndpoint = null,
            )
            if (candidates.isEmpty()) {
                throw IOException("No alternate fronting IP is available")
            }
            DiagnosticLogger.info(
                this,
                "frontingIp.override",
                "enabled=true ips=${frontingIps.size} ports=$connectionPorts candidates=${candidates.size}",
            )
            return StartupTopIpCandidatePlan(
                primaryPhase = "fronting",
                primaryCandidates = candidates,
                exhaustiveCandidates = emptyList(),
            )
        }

        if (bypassConnectionCache) {
            throw IOException("No fresh fronting IP is available")
        }

        publishAutomaticConnectionProgress(Actions.AUTO_PHASE_CHECKING_CACHE)
        val cachedCandidates = (listOfNotNull(scanStateStore.readLastEndpoint()) + cleanIpCache.readResults())
            .filter { it.port in fallbackPorts }
            .distinctBy { "${it.ip}:${it.port}" }
            .sortedForConnection()
        val cached = cleanIpScanner(
            ports = fallbackPorts,
            candidateIps = emptyList(),
        ).findFirstCachedWorking(cachedCandidates)
        if (cached != null) {
            logScanInfo(
                "scanner.fronting.cached",
                "latencyMs=${cached.latencyMs} lossRate=${cached.lossRate} speedBps=${cached.downloadBytesPerSecond} cacheAfterRuntimeValidation=true",
            )
            return StartupTopIpCandidatePlan(
                primaryPhase = "cached",
                primaryCandidates = listOf(cached),
                exhaustiveCandidates = emptyList(),
            )
        }

        // No reusable endpoint survived a fresh probe. Scan a small, bundled
        // Cloudflare anycast catalogue from the phone itself instead of failing
        // or pretending an old cache entry is suitable for the current network.
        publishAutomaticConnectionProgress(Actions.AUTO_PHASE_QUICK_SCAN)
        logScanInfo(
            "scanner.fronting.fresh.start",
            "ips=${IpScanner.IRAN_LIBRARY.size} ports=$fallbackPorts quickMs=${CleanIpDefaults.STARTUP_QUICK_SCAN_MS}",
        )
        val freshScanner = cleanIpScanner(
            ports = fallbackPorts,
            candidateIps = IpScanner.IRAN_LIBRARY,
        )
        val quick = freshScanner.findQuickCandidates(
            maxDurationMs = CleanIpDefaults.STARTUP_QUICK_SCAN_MS,
            targetResults = CleanIpDefaults.STARTUP_QUICK_TARGET_RESULTS,
        )
        if (quick.isNotEmpty()) {
            logScanInfo(
                "scanner.fronting.fresh.ready",
                "phase=quick candidates=${quick.size} bestLatencyMs=${quick.first().latencyMs}",
            )
            return StartupTopIpCandidatePlan(
                primaryPhase = "fresh-quick",
                primaryCandidates = quick,
                exhaustiveCandidates = emptyList(),
            )
        }

        publishAutomaticConnectionProgress(Actions.AUTO_PHASE_FALLBACK_SCAN)
        val exhaustive = freshScanner.findBestByQuality(
            maxScanDurationMs = CleanIpDefaults.STARTUP_FALLBACK_SCAN_MS,
        )
        if (exhaustive != null) {
            logScanInfo(
                "scanner.fronting.fresh.ready",
                "phase=fallback candidates=1 bestLatencyMs=${exhaustive.latencyMs}",
            )
            return StartupTopIpCandidatePlan(
                primaryPhase = "fresh-fallback",
                primaryCandidates = listOf(exhaustive),
                exhaustiveCandidates = emptyList(),
            )
        }

        throw IOException("No endpoint passed a device-side scan on the current network")
    }

    private fun cleanIpScanner(
        ports: List<Int>,
        candidateIps: List<String>,
    ): CleanIpScanner {
        val selectedPorts = ports.distinct().sorted()
        val distinctIps = candidateIps.distinct()
        logScanInfo(
            "scanner.create",
            "mode=tcping speedTestHost=${CleanIpDefaults.SPEED_TEST_HOST} ports=$selectedPorts candidateIps=${candidateIps.size}",
        )
        return CleanIpScanner(
            socketProtector = SocketProtector { socket -> protect(socket) },
            speedTestHost = CleanIpDefaults.SPEED_TEST_HOST,
            candidateProvider = {
                distinctIps.flatMap { ip ->
                    selectedPorts.map { port -> CleanIpCandidate(ip, port) }
                }.distinctBy { "${it.ip}:${it.port}" }
            },
            logger = { event, message -> logScanInfo(event, message) },
        )
    }

    private fun runtimeEndpointForSelection(
        topEndpoint: CleanIpResult,
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        selection: MihomoGroupSelection?,
        activeProxyName: String?,
        serverOverridePort: Int?,
        delayMs: Long,
    ): CleanIpResult {
        val port = serverOverridePort
            ?: runtimePortForSelection(snapshot, selectedCountryCode, selection, activeProxyName)
            ?: topEndpoint.port
        return topEndpoint.copy(
            port = port,
            latencyMs = delayMs.takeIf { it > 0 } ?: topEndpoint.latencyMs,
            checkedAt = System.currentTimeMillis(),
        )
    }

    private fun originalEndpointForSelection(
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        activeProxyName: String?,
        delayMs: Long,
    ): CleanIpResult {
        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProxy = countryCode?.let { code ->
            snapshot.summary.proxies.firstOrNull { proxy ->
                ConnectionLocationPolicy.countryFromText(proxy.name, proxy.server)?.code == code
            }
        }
        val proxy = countryProxy ?: activeProxyName
            ?.let { name -> snapshot.summary.proxies.firstOrNull { proxy -> proxy.name == name } }
            ?: snapshot.summary.proxies.firstOrNull()
        return CleanIpResult(
            ip = proxy?.server ?: MihomoRuntimeDefaults.CONTROLLER_HOST,
            port = proxy?.port?.takeIf { it > 0 } ?: MihomoRuntimeDefaults.MIXED_PORT,
            latencyMs = delayMs,
            lossRate = 0.0,
            checkedAt = System.currentTimeMillis(),
        )
    }

    private fun runtimePortForSelection(
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        selection: MihomoGroupSelection?,
        activeProxyName: String?,
    ): Int? {
        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProfile = countryCode?.let { code ->
            snapshot.catalog.profiles.firstOrNull { profile ->
                ConnectionLocationPolicy.countryForProfile(profile)?.code == code
            }
        }
        if (countryProfile?.port?.takeIf { it > 0 } != null) return countryProfile.port
        val activeProxy = activeProxyName?.let { name ->
            snapshot.summary.proxies.firstOrNull { proxy -> proxy.name == name }
        }
        if (activeProxy?.port?.takeIf { it > 0 } != null) return activeProxy.port
        val selectedProxy = selection?.selectedGroup?.let { selected ->
            snapshot.summary.proxies.firstOrNull { proxy -> proxy.name == selected }
        }
        return selectedProxy?.port?.takeIf { it > 0 }
    }

    private fun logScanInfo(event: String, message: String = "") {
        if (SCAN_DIAGNOSTICS_ENABLED) {
            DiagnosticLogger.info(this, event, message)
        }
    }

    private fun logScanWarn(event: String, message: String = "", error: Throwable? = null) {
        if (SCAN_DIAGNOSTICS_ENABLED) {
            DiagnosticLogger.warn(this, event, message, error)
        }
    }

    internal fun ensureStartupActive(eventPrefix: String) {
        if (state == VpnState.Starting || state == VpnState.Started) return
        throw CancellationException("$eventPrefix canceled while state=${state.wireName}")
    }

    internal fun ensureUnderlyingNetworkAvailable() {
        if (networkMonitor.hasUsableDefaultNetwork()) return
        throw IOException("No internet connection. Connect to Wi-Fi or cellular and try again.")
    }

    internal suspend fun resolveSplitTunnelRuntimePlan(): SplitTunnelRuntimePlan {
        val settings = splitTunnelPreferenceStore.readSettings()
        val apps = withContext(Dispatchers.IO) {
            installedAppRepository.loadLaunchableApps().map { it.packageName }
        }
        return SplitTunnelPolicy.runtimePlan(
            settings = settings,
            launchablePackages = apps,
            selfPackageName = packageName,
        ).also { plan ->
            DiagnosticLogger.info(
                this,
                "splitTunnel.runtime",
                "mode=${plan.mode.wireName} allowed=${plan.allowedPackages.size} disallowed=${plan.disallowedPackages.size} skipped=${plan.skippedPackages.size}",
            )
        }
    }

    internal suspend fun setupCoreRuntime(
        paths: MihomoRuntimePaths,
        dpiBypassPort: Int? = null,
    ) {
        setupCore(paths)
        if (dpiBypassPort != null) {
            try {
                startDpiBypassProxy(dpiBypassPort)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw DpiBypassStartupException(error)
            }
        }
    }

    internal suspend fun startCoreTun(
        paths: MihomoRuntimePaths,
        splitTunnelPlan: SplitTunnelRuntimePlan,
    ) {
        val tunFd = withContext(Dispatchers.Main) {
            establishTun(splitTunnelPlan)
        }
        withContext(Dispatchers.IO) {
            Core.startTun(
                fd = tunFd,
                protect = this@CatClientVpnService::protect,
                resolverProcess = this@CatClientVpnService::resolveProcess,
                stack = MIHOMO_TUN_STACK,
                address = VpnTunnelNetwork.coreAddresses,
                dns = VpnTunnelNetwork.coreDnsServers,
            )
        }
        DiagnosticLogger.info(this, "mihomo.core.started", "config=${paths.runtimeConfigYaml.absolutePath}")
    }

    @Volatile
    internal var childProcessEngineActive = false

    private fun ensureEmbeddedLocalBridgesForRuntimeFile(runtimeYamlFile: File) {
        if (!runtimeYamlFile.isFile) return
        runCatching {
            var yaml = runtimeYamlFile.readText()
            var modified = false

            // 1. WhiteDNS / DNSTT / Slipstream / SlipNet / DoH local SOCKS5 bridges
            val wdnsRegex = Regex("""whitedns-uri\s*:\s*['"]?([^'"\r\n]+)['"]?""")
            SlipNetCliEngine.attach(applicationContext)
            childProcessEngineActive = false
            wdnsRegex.findAll(yaml).toList().forEach { match ->
                val uri = match.groupValues[1].trim()
                val parsed = WhiteDnsSmartScanner.parseTunnelUri(uri)
                if (parsed != null) {
                    val activePort = WhiteDnsSmartScanner.ensureLocalTunnelBridgePort(parsed, startDaemon = true)
                    val result = WhiteDnsSmartScanner.lastEngineResult(parsed)
                    if (result is SlipNetCliEngine.StartResult.Ready) {
                        childProcessEngineActive = true
                        val updated = yaml.replace(
                            Regex("""(port\s*:\s*)\d+(\s*\r?\n(?:.*\r?\n){0,3}\s*whitedns-uri\s*:\s*['"]?""" + Regex.escape(uri)),
                            "$1$activePort$2",
                        )
                        if (updated != yaml) {
                            yaml = updated
                            modified = true
                        }
                    } else {
                        DiagnosticLogger.warn(
                            this,
                            "whitedns.engine.unavailable",
                            "engine=${parsed.engine.name} result=${result?.javaClass?.simpleName} " +
                                ((result as? SlipNetCliEngine.StartResult.Unsupported)?.reasonEn
                                    ?: (result as? SlipNetCliEngine.StartResult.Failed)?.reasonEn).orEmpty(),
                        )
                        // Tell the user WHY this DNS-tunnel config cannot carry traffic instead of
                        // silently leaving a dead proxy in the profile.
                        val farsi = runCatching { AppLanguagePreferenceStore(applicationContext).read() == AppLanguage.Persian }.getOrDefault(true)
                        val reason = when (result) {
                            is SlipNetCliEngine.StartResult.Unsupported -> if (farsi) result.reasonFa else result.reasonEn
                            is SlipNetCliEngine.StartResult.Failed -> if (farsi) result.reasonFa else result.reasonEn
                            else -> if (farsi) "موتور تانل DNS آماده نشد" else "DNS tunnel engine did not start"
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                android.widget.Toast.makeText(applicationContext, "🐢 ${parsed.scheme}: $reason", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }

            // 1b. awg://auto / wireguard://auto placeholders → real Cloudflare WARP account
            // beta210: also every other WireGuard config that points at Cloudflare WARP (feed / shared keys are usually
            // dead or over quota) runs with our own registered key — automatic, no button to press. Can be turned off
            // in «🔑 کلیدهای WARP ما».
            val adoptWarp = WarpRegistration.adoptsWarpConfigs(applicationContext)
            if (yaml.contains(WarpRegistration.PLACEHOLDER_PRIVATE_KEY) ||
                (adoptWarp && yaml.contains(WarpRegistration.WARP_PEER_PUBLIC_KEY))
            ) {
                val outcome = WarpRegistration.ensureAccountAutomatic(applicationContext)
                val account = outcome.account
                if (account != null) {
                    if (adoptWarp) yaml = WarpRegistration.adoptWarpPeers(yaml, account)
                    yaml = WarpRegistration.patchYaml(yaml, account)
                    modified = true
                    DiagnosticLogger.info(this, "warp.key.applied", "source=${outcome.source} adopt=$adoptWarp")
                } else {
                    DiagnosticLogger.warn(this, "warp.register.failed", outcome.log.joinToString(" | ").take(400))
                }
            }

            // 2. Firefox Browser VPN (FENIX) local SOCKS5 bridge
            val fenixRegex = Regex("""fenix-uri\s*:\s*['"]?([^'"\r\n]+)['"]?""")
            val fenixMatches = fenixRegex.findAll(yaml).toList()
            if (fenixMatches.isNotEmpty()) {
                // Use the country written in the config itself (fenix://…?country=DE); it used to be ignored.
                val fenixCountry = fenixMatches.first().groupValues[1].substringAfter('?', "")
                    .split('&').firstOrNull { it.startsWith("country=", ignoreCase = true) }
                    ?.substringAfter('=')?.trim()?.uppercase(java.util.Locale.US)
                if (FirefoxVpnEngine.selectBestActiveAccount(FirefoxVpnEngine.loadAccounts(applicationContext)) == null) {
                    // beta206: without a signed-in Firefox Account Mozilla gives no ProxyPass, so every request
                    // through this profile fails. Say so instead of a bare «خطای اتصال».
                    DiagnosticLogger.warn(this, "fenix.account.missing", "fenix profiles=${fenixMatches.size}")
                    val farsi = runCatching { AppLanguagePreferenceStore(applicationContext).read() == AppLanguage.Persian }.getOrDefault(true)
                    val msg = if (farsi) {
                        "🦊 Cat Fox VPN حساب Firefox وارد‌شده ندارد؛ از «اتصال اضطراری ← Cat Fox VPN» وارد حساب شوید، بعد وصل کنید."
                    } else {
                        "🦊 Cat Fox VPN has no signed-in Firefox account. Sign in under “Emergency → Cat Fox VPN”, then connect."
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching { android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show() }
                    }
                }
                val activePort = FirefoxVpnEngine.startLocalBridge(applicationContext, fenixCountry)
                if (activePort in 1..65535) {
                    val updated = yaml.replace(
                        Regex("""(server\s*:\s*['"]?127\.0\.0\.1['"]?\s*\r?\n\s*port\s*:\s*)\d+(\s*\r?\n(?:.*\r?\n){0,3}\s*fenix-uri\s*:)"""),
                        "$1$activePort$2",
                    )
                    if (updated != yaml) {
                        yaml = updated
                        modified = true
                    }
                }
            }

            // 3. Paqet / paqetNG (patth / patth ng) local SOCKS5 bridge
            val paqetRegex = Regex("""paqet-uri\s*:\s*['"]?([^'"\r\n]+)['"]?""")
            val paqetMatches = paqetRegex.findAll(yaml).toList()
            if (paqetMatches.isNotEmpty()) {
                val firstUri = paqetMatches.first().groupValues[1].trim()
                val activePort = PaqetEngine.ensureLocalBridgeRunning(applicationContext, firstUri)
                if (activePort in 1..65535) {
                    val updated = yaml.replace(
                        Regex("""(server\s*:\s*['"]?127\.0\.0\.1['"]?\s*\r?\n\s*port\s*:\s*)\d+(\s*\r?\n(?:.*\r?\n){0,3}\s*paqet-uri\s*:)"""),
                        "$1$activePort$2",
                    )
                    if (updated != yaml) {
                        yaml = updated
                        modified = true
                    }
                }
            }

            // 4. Local Anti-DPI SNI Record Splitter forwarder (for "🪄 تعمیرشده خودکار" configs)
            val sniFwdRegex = Regex("""sni-fwd-target\s*:\s*['"]?([^'"\r\n]+)['"]?""")
            sniFwdRegex.findAll(yaml).toList().forEach { match ->
                val rawTarget = match.groupValues[1].trim()
                val colon = rawTarget.lastIndexOf(':')
                if (colon > 0) {
                    val targetHost = rawTarget.substring(0, colon).removePrefix("[").removeSuffix("]")
                    val targetPort = rawTarget.substring(colon + 1).toIntOrNull() ?: 443
                    val activePort = LocalSniSpoofProxy.startDirectTcpSpoofForwarder(
                        remoteHost = targetHost,
                        remotePort = targetPort,
                    )
                    if (activePort in 1..65535) {
                        val escapedTarget = Regex.escape(rawTarget)
                        val blockPattern = Regex(
                            """(server\s*:\s*['"]?127\.0\.0\.1['"]?\s*\r?\n\s*port\s*:\s*)\d+((?:\s*\r?\n.*){0,25}?\s*\r?\n\s*sni-fwd-target\s*:\s*['"]?$escapedTarget['"]?)""",
                        )
                        val updated = yaml.replace(blockPattern, "$1$activePort$2")
                        if (updated != yaml) {
                            yaml = updated
                            modified = true
                        }
                    }
                }
            }

            // 5. Auto-heal any cached Shadowsocks 2022 ('2022-blake3-*') keys where URLDecoder previously turned '+' into ' '
            if (yaml.contains("2022-blake3-")) {
                val ss2022KeyFixRegex = Regex(
                    """(password\s*:\s*['"]?)([A-Za-z0-9+/=_:\- ]{20,120})(['"]?\s*\r?\n\s*cipher\s*:\s*['"]?2022-blake3-[a-z0-9\-]+['"]?)""",
                )
                val healedYaml = ss2022KeyFixRegex.replace(yaml) { m ->
                    val prefix = m.groupValues[1]
                    val rawPass = m.groupValues[2]
                    val suffix = m.groupValues[3]
                    val fixedPass = rawPass.split(':').joinToString(":") { part ->
                        val std = part.trim().replace(' ', '+').replace('-', '+').replace('_', '/')
                        val noPad = std.trimEnd('=')
                        noPad + "=".repeat((4 - noPad.length % 4) % 4)
                    }
                    "$prefix$fixedPass$suffix"
                }
                if (healedYaml != yaml) {
                    yaml = healedYaml
                    modified = true
                }
            }

            if (modified) {
                runtimeYamlFile.writeText(yaml)
            }
        }
    }

    internal suspend fun setupCore(paths: MihomoRuntimePaths) {
        val initParams = MihomoRuntimeConfigBuilder.initParamsJson(
            baseDir = paths.baseDir.absolutePath,
            sdkInt = Build.VERSION.SDK_INT,
        ).toString()
        val setupParams = withContext(Dispatchers.IO) {
            ensureEmbeddedLocalBridgesForRuntimeFile(paths.runtimeConfigYaml)
            val installed = MihomoGeoDataInstaller.install(paths.baseDir) { assetPath ->
                assets.open(assetPath)
            }
            if (installed.isNotEmpty()) {
                DiagnosticLogger.info(
                    this@CatClientVpnService,
                    "mihomo.geodata.installed",
                    "files=${installed.joinToString(",")}",
                )
            }
            paths.setupParamsJson.readText()
        }
        val setupMessage = quickSetupCore(initParams, setupParams)
        if (setupMessage.isNotBlank() && !setupMessage.endsWith("is empty")) {
            throw IOException(setupMessage)
        }
    }

    private suspend fun quickSetupCore(
        initParams: String,
        setupParams: String,
    ): String {
        if (!coreLifecycle.beginSetup()) {
            throw MihomoCoreBusyException()
        }
        val deferred = CompletableDeferred<String?>()
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                Core.quickSetup(initParams, setupParams) { result ->
                    val completion = coreLifecycle.finishSetup()
                    if (!deferred.isCompleted) {
                        deferred.complete(result)
                    }
                    if (completion == MihomoCoreSetupCompletion.CleanupClaimed) {
                        DiagnosticLogger.info(
                            this@CatClientVpnService,
                            "mihomo.cleanup.deferred.start",
                            "reason=setupCompletedAfterStopRequest",
                        )
                        coreCleanupScope.launch {
                            cleanupClaimedCore("setupCompletedAfterStopRequest")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                DiagnosticLogger.warn(this, "mihomo.core.setup.dispatch.failed", error = error)
            }
            throw error
        }

        val initialResult = withTimeoutOrNull(CORE_SETUP_SLOW_WARNING_MS) {
            deferred.await()
        }
        if (initialResult != null || deferred.isCompleted) {
            if (!coreLifecycle.isActive()) throw MihomoCoreBusyException()
            return initialResult.orEmpty()
        }

        DiagnosticLogger.warn(
            this,
            "mihomo.core.setup.slow",
            "elapsedMs=$CORE_SETUP_SLOW_WARNING_MS continuing=true",
        )
        val result = withTimeoutOrNull(CORE_SETUP_HARD_TIMEOUT_MS - CORE_SETUP_SLOW_WARNING_MS) {
            deferred.await()
        }
        if (result == null && !deferred.isCompleted) {
            throw MihomoCoreSetupTimeoutException()
        }
        if (!coreLifecycle.isActive()) throw MihomoCoreBusyException()
        return result.orEmpty()
    }

    internal fun establishSstpTun(
        lease: SstpLease,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        serverName: String,
    ): android.os.ParcelFileDescriptor {
        val dns = lease.dns.ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val builder = Builder()
            .setMtu(lease.mtu.coerceIn(1280, SSTP_TUN_MTU))
            .setSession("${getString(R.string.app_name)} · Cat SSTP · $serverName")
            .addAddress(lease.address, 32)
            .addRoute("0.0.0.0", 0)
            // SSTP here carries IPv4 only; IPv6 is routed into the TUN and dropped so it cannot leak around the VPN.
            .addAddress(SSTP_IPV6_BLACKHOLE, 128)
            .addRoute("::", 0)
        dns.forEach { builder.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.setBlocking(true)
        }
        applySplitTunnel(builder, splitTunnelPlan)
        return builder.establish() ?: throw IOException("Android rejected the Cat SSTP VPN tunnel")
    }

    internal fun establishAetherTun(
        ipv4: String,
        ipv6: String,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        mtu: Int = AETHER_TUN_MTU,
    ): Int {
        AetherTunPolicy.validatedAddresses(ipv4, ipv6)
        val safeMtu = mtu.coerceIn(AetherTunPolicy.MASQUE_IN_MASQUE_MTU, AETHER_TUN_MTU)
        val builder = Builder()
            .setMtu(safeMtu)
            .setSession("${getString(R.string.app_name)} · Cat Warp")
            .addAddress(ipv4, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
        // beta219: IPv6 needs ≥1280 and Android refuses (throws) an IPv6 interface below that, so the two-hop
        // transports (1200/1162) run IPv4-only — exactly like the engine's own app does.
        if (AetherTunPolicy.carriesIpv6(safeMtu)) {
            builder.addAddress(ipv6, 128)
                .addRoute("::", 0)
                .addDnsServer("2606:4700:4700::1111")
        }
        DiagnosticLogger.info(this, "aether.tun.mtu", "mtu=$safeMtu ipv6=${AetherTunPolicy.carriesIpv6(safeMtu)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            builder.setBlocking(false)
        }
        applySplitTunnel(builder, splitTunnelPlan)
        return builder.establish()?.detachFd() ?: throw IOException("Android rejected the Cat Warp VPN tunnel")
    }

    internal suspend fun establishTun(splitTunnelPlan: SplitTunnelRuntimePlan): Int {
        for (attempt in 1..TUN_ESTABLISH_ATTEMPTS) {
            val builder = Builder()
                .setMtu(MIHOMO_TUN_MTU)
                .setSession(getString(R.string.app_name))
                .setBlocking(false)
            VpnTunnelNetwork.addresses.forEach { (address, prefixLength) ->
                builder.addAddress(address, prefixLength)
            }
            VpnTunnelNetwork.defaultRoutes.forEach { route -> builder.addRoute(route, 0) }
            VpnTunnelNetwork.dnsServers.forEach(builder::addDnsServer)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            applySplitTunnel(builder, splitTunnelPlan)
            builder.establish()?.detachFd()?.let { return it }
            if (attempt < TUN_ESTABLISH_ATTEMPTS) {
                DiagnosticLogger.warn(this, "mihomo.tun.establish.retry", "attempt=$attempt/$TUN_ESTABLISH_ATTEMPTS")
                delay(TUN_ESTABLISH_RETRY_DELAY_MS)
            }
        }
        throw IOException("Android rejected the VPN tunnel")
    }

    private fun applySplitTunnel(
        builder: Builder,
        splitTunnelPlan: SplitTunnelRuntimePlan,
    ) {
        // A real DNS-tunnel engine runs as a child process with our UID. Its resolver traffic
        // must not re-enter the tunnel (it would loop back into itself), so the app's own UID
        // bypasses the VPN while that engine is active.
        val excludeSelf = childProcessEngineActive
        when (splitTunnelPlan.mode) {
            SplitTunnelMode.Off -> if (excludeSelf) addDisallowedApplication(builder, packageName)
            SplitTunnelMode.VpnOnlySelected -> {
                val allowed = if (excludeSelf) splitTunnelPlan.allowedPackages else splitTunnelPlan.allowedPackages + packageName
                allowed.distinct().filterNot { excludeSelf && it == packageName }.forEach { packageName ->
                    addAllowedApplication(builder, packageName)
                }
            }
            SplitTunnelMode.BypassSelected -> {
                splitTunnelPlan.disallowedPackages
                    .filterNot { it == packageName }
                    .forEach { packageName -> addDisallowedApplication(builder, packageName) }
                if (excludeSelf) addDisallowedApplication(builder, packageName)
            }
        }
    }

    private fun addAllowedApplication(builder: Builder, packageName: String) {
        runCatching { builder.addAllowedApplication(packageName) }
            .onFailure { error ->
                if (error !is PackageManager.NameNotFoundException) {
                    throw error
                }
                DiagnosticLogger.warn(this, "splitTunnel.allowed.missing", "package=$packageName", error)
            }
    }

    private fun addDisallowedApplication(builder: Builder, packageName: String) {
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { error ->
                if (error !is PackageManager.NameNotFoundException) {
                    throw error
                }
                DiagnosticLogger.warn(this, "splitTunnel.disallowed.missing", "package=$packageName", error)
            }
    }

    private fun resolveProcess(
        protocol: Int,
        source: InetSocketAddress,
        target: InetSocketAddress,
        uid: Int,
    ): String {
        val resolvedUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(protocol, source, target)
            }.getOrDefault(-1)
        } else {
            uid
        }
        if (resolvedUid == -1) return ""
        return uidPackageNameCache.getOrPut(resolvedUid) {
            packageManager.getPackagesForUid(resolvedUid)?.firstOrNull().orEmpty()
        }
    }

    internal suspend fun waitForController(
        controller: MihomoControllerClient,
        paths: MihomoRuntimePaths,
        timeoutMs: Long = CONTROLLER_READY_TIMEOUT_MS,
    ) {
        DiagnosticLogger.info(
            this,
            "mihomo.controller.wait.start",
            "endpoint=${controller.endpoint} timeoutMs=$timeoutMs runtimeConfigBytes=${paths.runtimeConfigYaml.length()} profileBytes=${paths.profileYaml.length()} serviceBytes=${paths.serviceJson.length()} patchBytes=${paths.patchFinalJson.length()}",
        )
        val ready = withTimeoutOrNull(timeoutMs) {
            while (isActive) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        controller.getProxies()
                    }.isSuccess
                }
                if (ok) return@withTimeoutOrNull true
                delay(CONTROLLER_POLL_INTERVAL_MS)
            }
            false
        } == true
        if (!ready) {
            logControllerStartupFailure(controller, paths)
            throw IOException("Cat core actions did not become ready")
        }
        DiagnosticLogger.info(this, "mihomo.controller.wait.ok", "endpoint=${controller.endpoint}")
    }

    private suspend fun logControllerStartupFailure(
        controller: MihomoControllerClient,
        paths: MihomoRuntimePaths,
    ) = withContext(Dispatchers.IO) {
        DiagnosticLogger.warn(
            this@CatClientVpnService,
            "mihomo.controller.wait.timeout",
            "endpoint=${controller.endpoint} portBindable=${MihomoControllerPort.canBind(paths.controlPort)} runtimeConfigBytes=${paths.runtimeConfigYaml.length()} profileBytes=${paths.profileYaml.length()} serviceBytes=${paths.serviceJson.length()} patchBytes=${paths.patchFinalJson.length()} coreLogBytes=${paths.logFile.length()} stderrBytes=${paths.errorFile.length()}",
        )
        logFileTail("mihomo.core.log.tail", paths.logFile, paths.secret)
        logFileTail("mihomo.stderr.tail", paths.errorFile, paths.secret)
    }

    private fun logFileTail(
        event: String,
        file: File,
        secret: String,
    ) {
        if (!file.exists()) {
            DiagnosticLogger.warn(this, event, "path=${file.absolutePath} status=missing")
            return
        }
        if (file.length() == 0L) {
            DiagnosticLogger.warn(this, event, "path=${file.absolutePath} status=empty")
            return
        }
        val tail = runCatching {
            file.readText()
                .takeLast(CONTROLLER_LOG_TAIL_CHARS)
                .redactRuntimeSecret(secret)
                .replace("\n", "\\n")
        }.getOrElse { error ->
            DiagnosticLogger.warn(this, event, "path=${file.absolutePath} status=readFailed", error)
            return
        }
        DiagnosticLogger.warn(this, event, "path=${file.absolutePath} bytes=${file.length()} tail=$tail")
    }

    private fun String.redactRuntimeSecret(secret: String): String {
        return if (secret.isBlank()) this else replace(secret, "<mihomo-secret>")
    }

    private fun runtimeHealthStatus(deadlineMs: Long): Int {
        val urls = MihomoRuntimeDefaults.HEALTH_URLS
        for ((index, url) in urls.withIndex()) {
            val timeoutMs = MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(
                deadlineMs = deadlineMs,
                nowMs = SystemClock.elapsedRealtime(),
                remainingUrlCount = urls.size - index,
            ) ?: break
            val code = runCatching {
                MihomoRuntimeHealth.httpStatusThroughMixedProxy(url, timeoutMs)
            }.getOrNull()
            if (code != null && (code == 204 || code in 200..399)) return code
        }
        return -1
    }

    internal suspend fun verifyRuntimeHealth(
        timeoutMs: Long = RUNTIME_HEALTH_TIMEOUT_MS,
        event: String = "mihomo.proxy.health",
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val deadlineMs = MihomoRuntimeHealthDeadlinePolicy.deadlineMs(startedAt, timeoutMs)
        val proxyCode = waitForHealthyStatus(event, deadlineMs) {
            runtimeHealthStatus(deadlineMs)
        } ?: run {
            DiagnosticLogger.warn(
                this,
                "$event.timeout",
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} timeoutMs=$timeoutMs",
            )
            throw IOException(
                "Local Cat core proxy did not pass health check at 127.0.0.1:2080 " +
                    "within ${timeoutMs}ms",
            )
        }
        DiagnosticLogger.info(
            this,
            "$event.ok",
            "code=$proxyCode elapsedMs=${SystemClock.elapsedRealtime() - startedAt} timeoutMs=$timeoutMs",
        )
    }

    private suspend fun verifyTlsIntegrity(enabled: Boolean) {
        if (!enabled) return
        val completed = withTimeoutOrNull(TlsIntegrityPolicy.TOTAL_TIMEOUT_MS) {
            var lastFailure: Throwable? = null
            for (url in TlsIntegrityPolicy.TEST_URLS) {
                try {
                    val code = withContext(Dispatchers.IO) {
                        MihomoRuntimeHealth.httpStatusThroughMixedProxy(
                            url = url,
                            timeoutMs = TlsIntegrityPolicy.PROBE_TIMEOUT_MS,
                        )
                    }
                    DiagnosticLogger.info(
                        this@CatClientVpnService,
                        "tlsIntegrity.ok",
                        "url=$url code=$code",
                    )
                    return@withTimeoutOrNull
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (TlsIntegrityPolicy.isCertificateFailure(error)) {
                        DiagnosticLogger.warn(
                            this@CatClientVpnService,
                            "tlsIntegrity.failed",
                            "url=$url",
                            error,
                        )
                        throw TlsIntegrityException(error)
                    }
                    lastFailure = error
                    DiagnosticLogger.warn(
                        this@CatClientVpnService,
                        "tlsIntegrity.probe.unreachable",
                        "url=$url",
                        error,
                    )
                }
            }
            DiagnosticLogger.warn(
                this@CatClientVpnService,
                "tlsIntegrity.inconclusive",
                "all probes unreachable; allowing connection",
                lastFailure,
            )
        }
        if (completed == null) {
            DiagnosticLogger.warn(
                this,
                "tlsIntegrity.inconclusive",
                "probe deadline reached; allowing connection",
            )
        }
    }

    private suspend fun waitForHealthyStatus(
        event: String,
        deadlineMs: Long,
        check: () -> Int,
    ): Int? {
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val code = withContext(Dispatchers.IO) {
                runCatching { check() }.getOrElse {
                    DiagnosticLogger.warn(this@CatClientVpnService, "$event.failed", error = it)
                    -1
                }
            }
            if (code == 204 || code in 200..399) return code
            val pollDelayMs = MihomoRuntimeHealthDeadlinePolicy.pollDelayMs(
                deadlineMs = deadlineMs,
                nowMs = SystemClock.elapsedRealtime(),
                requestedMs = RUNTIME_HEALTH_POLL_INTERVAL_MS,
            )
            if (pollDelayMs <= 0L) break
            delay(pollDelayMs)
        }
        return null
    }

    internal fun activeProfile(
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        selection: MihomoGroupSelection?,
        activeProxyName: String?,
    ): ConnectionProfile {
        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProfile = countryCode?.let { code ->
            snapshot.catalog.profiles.firstOrNull { profile ->
                ConnectionLocationPolicy.countryForProfile(profile)?.code == code
            }
        }
        if (countryProfile != null) return countryProfile

        activeProxyName?.let { name ->
            snapshot.catalog.profiles.firstOrNull { profile -> profile.tag == name }?.let { return it }
        }

        val tag = selection?.selectedGroup
            ?: MihomoSelectionPolicy.autoGroup(snapshot.summary)?.name
            ?: snapshot.summary.proxies.firstOrNull()?.name
            ?: "Cat core"
        return ConnectionProfile(
            tag = tag,
            type = "mihomo-group",
            server = MihomoRuntimeDefaults.CONTROLLER_HOST,
            port = MihomoRuntimeDefaults.MIXED_PORT,
            transport = "",
            validationHost = MihomoRuntimeDefaults.CONTROLLER_HOST,
        )
    }

    private fun stopVpn(force: Boolean = false) {
        if (!force && alwaysOnActive) {
            DiagnosticLogger.info(this, "disconnect.ignored", "reason=alwaysOn lockdown=$lockdownActive")
            publishState(state)
            return
        }
        if (state == VpnState.Stopped) {
            if (connectionDelayTestJob != null || connectionSpeedTestJobs.isNotEmpty()) {
                cancelConnectionDelayTest()
                connectionSpeedTestJobs.values.toList().forEach { it.cancel() }
                return
            }
            publishState(VpnState.Stopped)
            stopSelf()
            return
        }
        if (state == VpnState.Stopping) return
        DiagnosticLogger.info(this, "disconnect.start", "state=${state.wireName}")
        awaitingPreservedRuntimeHealth = false
        startupJob?.cancel(CancellationException("Disconnect requested"))
        subscriptionRefreshJob?.cancel()
        cancelPostConnectHealthWatchdog()
        publishState(VpnState.Stopping)
        connectionSwitchJob?.cancel(CancellationException("Disconnect requested"))
        cancelConnectionDelayTest()
        connectionSpeedTestJobs.values.toList().forEach { it.cancel() }
        stopJob?.cancel()
        stopJob = scope.launch {
            cancelConnectionTestsAndWait()
            aetherSession?.close()
            aetherSession = null
            aetherPsiphonChain?.close()
            aetherPsiphonChain = null
            aetherTorCarrier?.close()
            aetherTorCarrier = null
            closeSstp("disconnect")
            // beta211: pooled Cat Fox VPN HTTP/2 connections belong to this session's network; start clean next time.
            runCatching { FirefoxVpnEngine.resetUpstreamSessions("vpn stopped") }
            val terminalState = disconnectTerminalState(
                stopCoreService(),
                getString(R.string.state_connection_error),
            )
            if (terminalState == VpnState.Stopped) {
                finishStoppedState("disconnect.stopped")
            } else {
                DiagnosticLogger.warn(
                    this@CatClientVpnService,
                    "disconnect.failed",
                    "reason=coreShutdownTimeout state=${coreLifecycle.currentState()}",
                )
                publishState(terminalState)
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun stopAfterFailure(error: Throwable) {
        awaitingPreservedRuntimeHealth = false
        subscriptionRefreshJob?.cancel()
        cancelPostConnectHealthWatchdog()
        runCatching { aetherSession?.close() }
        aetherSession = null
        runCatching { aetherPsiphonChain?.close() }
        aetherPsiphonChain = null
        runCatching { aetherTorCarrier?.close() }
        aetherTorCarrier = null
        closeSstp("failure")
        stopCoreService()
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeProfileShowsServer = false
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeNativeAutomaticStart = false
        activeConnectionChained = false
        activeChainHopCount = 0
        clearActiveSelectorState()
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        activeFrontingIp = ""
        pendingDefaultNetworkDnsReplay = false
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        if (!shouldPublishStartupError(currentCoroutineContext().isActive, state)) {
            DiagnosticLogger.info(
                this,
                "startup.failure.ignored",
                "reason=shutdownWon state=${state.wireName}",
            )
            return
        }
        publishState(VpnState.Error(ConnectionFaultExplainer.describeChain(error).ifBlank { "Unable to start VPN" }))
        stopSelf()
    }

    private suspend fun keepActiveRuntimeAfterStartupFailure(eventPrefix: String, error: Throwable) {
        val hasUsableDefaultNetwork = networkMonitor.hasUsableDefaultNetwork()
        val healthStatus = if (hasUsableDefaultNetwork) {
            val healthStartedAt = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) {
                runtimeHealthStatus(
                    MihomoRuntimeHealthDeadlinePolicy.deadlineMs(
                        healthStartedAt,
                        FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                    ),
                )
            }
        } else {
            -1
        }
        val preservedState = PostConnectHealthPolicy.preservedRuntimeState(
            hasUsableDefaultNetwork,
            healthStatus,
        )
        if (preservedState == null) {
            DiagnosticLogger.warn(
                this,
                "$eventPrefix.failed.oldRuntimeUnhealthy",
                "code=$healthStatus",
                error,
            )
            stopAfterFailure(error)
            return
        }
        awaitingPreservedRuntimeHealth = preservedState == VpnState.Starting
        DiagnosticLogger.warn(
            this,
            "$eventPrefix.failed.keptActive",
            "state=${preservedState.wireName}",
            error,
        )
        publishState(preservedState)
        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIFICATION_ID,
                serviceNotification(
                    getString(
                        if (preservedState == VpnState.Started) {
                            connectedNotificationTextRes(activeSubscriptionId)
                        } else {
                            R.string.notification_starting
                        },
                    ),
                ),
            )
        if (preservedState == VpnState.Started) startBackgroundSubscriptionRefresh()
        startPostConnectHealthWatchdog()
    }

    private suspend fun stopActiveCoreForReplacement() {
        if (!stopCoreService()) {
            throw MihomoCoreBusyException()
        }
        activeRuntimePaths = null
        activeNativeAutomaticStart = false
        clearActiveSelectorState()
    }

    internal suspend fun stopCoreService(): Boolean {
        val coreStopped = when (coreLifecycle.requestCleanup()) {
            MihomoCoreCleanupRequest.Claimed -> {
                coreCleanupScope.launch {
                    cleanupClaimedCore("serviceRequest")
                }
                awaitCoreIdle(CORE_SHUTDOWN_WAIT_TIMEOUT_MS)
            }
            MihomoCoreCleanupRequest.PendingSetup -> {
                DiagnosticLogger.warn(this, "mihomo.cleanup.deferred", "reason=setupRace")
                awaitCoreIdle(CORE_SETUP_CLEANUP_GRACE_MS)
            }
            MihomoCoreCleanupRequest.AlreadyStopping -> awaitCoreIdle(CORE_SHUTDOWN_WAIT_TIMEOUT_MS)
            MihomoCoreCleanupRequest.AlreadyIdle -> true
        }
        stopDpiBypassProxy()
        // beta218: stopping child engines sleeps briefly per process and deletes a file — not on the screen's thread.
        withContext(Dispatchers.IO) { runCatching { SlipNetCliEngine.stopAll() } }
        childProcessEngineActive = false
        return coreStopped
    }

    private suspend fun awaitCoreIdle(timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!coreLifecycle.isIdle()) {
                delay(CORE_LIFECYCLE_POLL_INTERVAL_MS)
            }
            true
        } == true
    }

    private suspend fun cleanupClaimedCore(reason: String): Boolean {
        DiagnosticLogger.info(this, "mihomo.cleanup.start", "reason=$reason")
        withContext(Dispatchers.IO) {
            runCatching { Core.stopTun() }
                .onFailure { DiagnosticLogger.warn(this@CatClientVpnService, "mihomo.tun.stop.failed", error = it) }
        }
        val completion = withContext(Dispatchers.IO) {
            beginCoreShutdown(reason)
        } ?: return false

        val completedQuickly = withTimeoutOrNull(CORE_SHUTDOWN_SLOW_WARNING_MS) {
            completion.await()
            true
        } == true
        if (completedQuickly) return true

        DiagnosticLogger.warn(
            this,
            "mihomo.shutdown.slow",
            "reason=$reason elapsedMs=$CORE_SHUTDOWN_SLOW_WARNING_MS continuing=true",
        )
        val completed = withTimeoutOrNull(CORE_SHUTDOWN_HARD_TIMEOUT_MS - CORE_SHUTDOWN_SLOW_WARNING_MS) {
            completion.await()
            true
        } == true
        if (!completed) {
            DiagnosticLogger.warn(
                this,
                "mihomo.shutdown.deferred",
                "reason=$reason elapsedMs=$CORE_SHUTDOWN_HARD_TIMEOUT_MS state=${coreLifecycle.currentState()}",
            )
        }
        return completed
    }

    private fun beginCoreShutdown(reason: String): CompletableDeferred<Unit>? {
        val completion = CompletableDeferred<Unit>()
        val action = JSONObject()
            .put("id", "shutdown-${SystemClock.elapsedRealtimeNanos()}")
            .put("method", "shutdown")
        return runCatching {
            Core.invokeAction(action.toString()) {
                val transitioned = coreLifecycle.finishCleanup()
                completion.complete(Unit)
                if (transitioned) {
                    DiagnosticLogger.info(
                        this@CatClientVpnService,
                        "mihomo.cleanup.completed",
                        "reason=$reason",
                    )
                }
            }
            completion
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "mihomo.shutdown.failed", "reason=$reason", error)
        }.getOrNull()
    }

    private fun stopCoreImmediately() {
        when (coreLifecycle.requestCleanup()) {
            MihomoCoreCleanupRequest.Claimed -> {
                coreCleanupScope.launch {
                    cleanupClaimedCore("serviceDestroyed")
                }
            }

            MihomoCoreCleanupRequest.PendingSetup -> DiagnosticLogger.warn(
                this,
                "mihomo.cleanup.skipped",
                "reason=setupInFlight serviceDestroyed=true deferred=true",
            )

            MihomoCoreCleanupRequest.AlreadyStopping,
            MihomoCoreCleanupRequest.AlreadyIdle -> Unit
        }
        runCatching { ByeDpiProxy.stop() }
        runCatching { SlipNetCliEngine.stopAll() }
        childProcessEngineActive = false
        dpiBypassJob?.cancel(CancellationException("Service destroyed"))
        dpiBypassJob = null
        activeDpiBypassPort = null
    }

    private fun finishStoppedState(event: String) {
        awaitingPreservedRuntimeHealth = false
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeProfileShowsServer = false
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeNativeAutomaticStart = false
        activeConnectionChained = false
        activeChainHopCount = 0
        clearActiveSelectorState()
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        activeFrontingIp = ""
        lastPostConnectRecoveryElapsedMs = 0L
        lastDefaultNetworkKey = null
        lastDefaultDns = ""
        pendingDefaultNetworkDnsReplay = false
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        publishState(VpnState.Stopped)
        DiagnosticLogger.info(this, event)
        stopSelf()
    }

    private fun clearActiveSelectorState() {
        activeSubscriptionId = ""
        activeConnectionTag = ""
        activeConnectionFingerprint = ""
        activeSelectorReady = false
        activeSelectableConnectionFingerprints = emptySet()
        activeSelectorRootName = ""
        activeSelectorRoots = emptyList()
        activeAvailableProfiles = emptyList()
        activeRuntimeUsesEndpointOverride = false
        activeQuickSpeedPin = null
        invalidatedQuickSpeedPin = null
    }

    internal fun DefaultNetworkCandidate?.defaultNetworkKey(): String {
        if (this == null) return "none"
        return listOf(name, index.toString(), isWifi.toString(), isCellular.toString(), isEthernet.toString())
            .joinToString("|")
    }

    internal fun DefaultNetworkCandidate?.quickSpeedNetworkFingerprint(): String =
        "${defaultNetworkKey()}#${this?.dnsServers.orEmpty().distinct().joinToString(",")}"

    private fun cancelPostConnectHealthWatchdog() {
        val job = postConnectHealthJob
        postConnectHealthJob = null
        job?.cancel()
    }

    internal fun startPostConnectHealthWatchdog() {
        cancelPostConnectHealthWatchdog()
        val job = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            var nextCheckDelayMs = PostConnectHealthPolicy.INITIAL_CHECK_DELAY_MS
            while (isActive && shouldRunPostConnectHealthWatchdog(state, awaitingPreservedRuntimeHealth)) {
                delay(nextCheckDelayMs)
                if (!isActive || !shouldRunPostConnectHealthWatchdog(state, awaitingPreservedRuntimeHealth)) break
                retryPendingDefaultNetworkDnsReplay()
                if (!networkMonitor.hasUsableDefaultNetwork()) {
                    if (state == VpnState.Started) {
                        awaitingPreservedRuntimeHealth = true
                        publishState(VpnState.Starting)
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            serviceNotification(getString(R.string.notification_starting)),
                        )
                    }
                    consecutiveFailures = 0
                    nextCheckDelayMs = PostConnectHealthPolicy.CHECK_INTERVAL_MS
                    continue
                }

                runCatching { refreshLiveSelectorState() }
                    .onFailure { error ->
                        DiagnosticLogger.warn(
                            this@CatClientVpnService,
                            "mihomo.selector.graph.refresh.failed",
                            error = error,
                        )
                    }

                val healthStartedAt = SystemClock.elapsedRealtime()
                val code = runtimeHealthStatus(
                    MihomoRuntimeHealthDeadlinePolicy.deadlineMs(
                        healthStartedAt,
                        FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                    ),
                )
                if (PostConnectHealthPolicy.isHealthyStatus(code)) {
                    if (state == VpnState.Starting) {
                        awaitingPreservedRuntimeHealth = false
                        publishState(VpnState.Started)
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            serviceNotification(connectedNotificationText()),
                        )
                        startBackgroundSubscriptionRefresh()
                    }
                    if (consecutiveFailures > 0) {
                        DiagnosticLogger.info(
                            this@CatClientVpnService,
                            "mihomo.postConnect.health.recovered",
                            "code=$code",
                        )
                    }
                    consecutiveFailures = 0
                    nextCheckDelayMs = PostConnectHealthPolicy.CHECK_INTERVAL_MS
                    continue
                }

                consecutiveFailures += 1
                nextCheckDelayMs = PostConnectHealthPolicy.FAILURE_RECHECK_DELAY_MS
                DiagnosticLogger.warn(
                    this@CatClientVpnService,
                    "mihomo.postConnect.health.failed",
                    "failures=$consecutiveFailures code=$code",
                )
                val nowElapsedMs = SystemClock.elapsedRealtime()
                if (
                    !PostConnectHealthPolicy.shouldRecover(
                        consecutiveFailures,
                        nowElapsedMs,
                        lastPostConnectRecoveryElapsedMs,
                    )
                ) {
                    if (consecutiveFailures >= PostConnectHealthPolicy.FAILURES_BEFORE_RECOVERY) {
                        DiagnosticLogger.info(
                            this@CatClientVpnService,
                            "mihomo.postConnect.recovery.skipped",
                            "reason=cooldown",
                        )
                        consecutiveFailures = 0
                        nextCheckDelayMs = PostConnectHealthPolicy.CHECK_INTERVAL_MS
                    }
                    continue
                }

                lastPostConnectRecoveryElapsedMs = nowElapsedMs
                DiagnosticLogger.warn(
                    this@CatClientVpnService,
                    "mihomo.postConnect.recovery.start",
                    "reason=consecutiveHealthFailures failures=$consecutiveFailures",
                )
                withContext(Dispatchers.Main) {
                    if (canStartVpnRefresh(state, automatic = true, awaitingPreservedRuntimeHealth)) {
                        refreshVpn(eventPrefix = "selfHeal", automatic = true)
                    }
                }
                break
            }
        }
        postConnectHealthJob = job
        job.invokeOnCompletion {
            if (postConnectHealthJob === job) {
                postConnectHealthJob = null
            }
        }
    }

    private fun startBackgroundSubscriptionRefresh() {
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = scope.launch(Dispatchers.IO) {
            while (isActive && state == VpnState.Started) {
                delay(CatClientConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS)
                if (!isActive || state != VpnState.Started) break
                configRepository.refreshAllSubscriptions()
            }
        }
    }

    private fun publishAutomaticConnectionProgress(phase: String) {
        sendBroadcast(
            Intent(Actions.AUTOMATIC_CONNECTION_PROGRESS)
                .setPackage(packageName)
                .putExtra(Actions.EXTRA_AUTOMATIC_CONNECTION_PHASE, phase),
        )
    }

    internal fun publishState(newState: VpnState, notice: String? = null) {
        val previousState = state
        state = newState
        currentVpnServiceState = newState
        runCatching { onRealInternetCheckState(previousState, newState) }
            .onFailure { DiagnosticLogger.warn(this, "realCheck.state.failed", error = it) }
        if (newState == VpnState.Started) {
            startNotificationTrafficUpdates()
            LocalSniSpoofProxy.loadPersistedPowerSettings(this)
            if (LocalSniSpoofProxy.autoHealWatchdogEnabled || LocalSniSpoofProxy.gamingLowLatencyBoosterEnabled) {
                LocalSniSpoofProxy.ensureAutoHealWatchdogLoop()
            }
        } else {
            stopNotificationTrafficUpdates()
        }
        val countryFlag = if (newState == VpnState.Started) {
            activeConnectionCountryFlag
        } else {
            ""
        }
        val debugFrontingIp = if (newState == VpnState.Started) {
            activeFrontingIp
        } else {
            ""
        }
        val connectionDetails = if (newState == VpnState.Started) {
            activeProfile?.let {
                ConnectionDetailsPresenter.forProfile(
                    it,
                    showServer = activeProfileShowsServer,
                    latencyMs = activeDelayMs,
                    frontingIp = activeFrontingIp,
                    stringFor = { id -> getString(id) },
                )
            }.orEmpty()
        } else {
            ""
        }
        val activeSubscription = activeSubscriptionId.takeIf { newState == VpnState.Started }.orEmpty()
        val activeTag = activeConnectionTag.takeIf { newState == VpnState.Started }.orEmpty()
        val activeFingerprint = activeConnectionFingerprint.takeIf { newState == VpnState.Started }.orEmpty()
        val selectorReady = newState == VpnState.Started && activeSelectorReady
        val selectableFingerprints = if (newState == VpnState.Started) {
            activeSelectableConnectionFingerprints
        } else {
            emptySet()
        }
        VpnRuntimeStateStore.save(
            context = this,
            state = newState,
            sessionStartedAtElapsedMs = sessionStartedAtElapsedMs,
            connectionCountryFlag = countryFlag,
            debugFrontingIp = debugFrontingIp,
            connectionDetails = connectionDetails,
            activeSubscriptionId = activeSubscription,
            activeConnectionTag = activeTag,
            activeConnectionFingerprint = activeFingerprint,
            chainHopCount = if (newState == VpnState.Started) activeChainHopCount else 0,
            liveSelectorReady = selectorReady,
            selectableConnectionFingerprints = selectableFingerprints,
            alwaysOn = alwaysOnActive,
            lockdown = lockdownActive,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { CatClientTileService.requestTileRefresh(this) }
                .onFailure { DiagnosticLogger.warn(this, "tile.refresh.failed", error = it) }
        }
        DiagnosticLogger.info(this, "state.publish", "state=${newState.wireName} debugFrontingIp=${debugFrontingIp}")
        VpnWidgetProvider.refresh(this)
        val intent = Intent(Actions.STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(Actions.EXTRA_STATE, newState.wireName)
            .putExtra(Actions.EXTRA_SESSION_STARTED_AT_ELAPSED_MS, sessionStartedAtElapsedMs)
            .putExtra(Actions.EXTRA_CONNECTION_COUNTRY_FLAG, countryFlag)
            .putExtra(Actions.EXTRA_DEBUG_FRONTING_IP, debugFrontingIp)
            .putExtra(Actions.EXTRA_CONNECTION_DETAILS, connectionDetails)
            .putExtra(Actions.EXTRA_ACTIVE_SUBSCRIPTION_ID, activeSubscription)
            .putExtra(Actions.EXTRA_ACTIVE_CONNECTION_TAG, activeTag)
            .putExtra(Actions.EXTRA_ACTIVE_CONNECTION_FINGERPRINT, activeFingerprint)
            .putExtra(
                Actions.EXTRA_CHAIN_HOP_COUNT,
                if (newState == VpnState.Started) activeChainHopCount else 0,
            )
            .putExtra(Actions.EXTRA_LIVE_SELECTOR_READY, selectorReady)
            .putStringArrayListExtra(
                Actions.EXTRA_SELECTABLE_CONNECTION_FINGERPRINTS,
                ArrayList(selectableFingerprints),
            )
            .putExtra(Actions.EXTRA_ALWAYS_ON, alwaysOnActive)
            .putExtra(Actions.EXTRA_LOCKDOWN, lockdownActive)
        if (newState is VpnState.Error) {
            intent.putExtra(Actions.EXTRA_ERROR, newState.message)
        }
        if (!notice.isNullOrBlank()) {
            intent.putExtra(Actions.EXTRA_NOTICE, notice)
        }
        sendBroadcast(intent)
    }

    internal companion object {
        val coreLifecycle = MihomoCoreLifecycle()
        val coreCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val CHANNEL_ID = "cat_client_vpn"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_TRAFFIC_INTERVAL_MS = 1_000L
        const val CORE_SETUP_SLOW_WARNING_MS = 15_000L
        const val CORE_SETUP_HARD_TIMEOUT_MS = 60_000L
        const val CORE_SETUP_CLEANUP_GRACE_MS = 5_000L
        const val CORE_LIFECYCLE_POLL_INTERVAL_MS = 50L
        const val CORE_SHUTDOWN_SLOW_WARNING_MS = 3_000L
        const val CORE_SHUTDOWN_HARD_TIMEOUT_MS = 30_000L
        const val CORE_SHUTDOWN_WAIT_TIMEOUT_MS = 30_000L
        const val DPI_BYPASS_START_TIMEOUT_MS = 3_000L
        const val DPI_BYPASS_STOP_TIMEOUT_MS = 2_000L
        const val DPI_BYPASS_PORT_POLL_INTERVAL_MS = 50L
        const val CONTROLLER_READY_TIMEOUT_MS = 20_000L
        const val CONTROLLER_POLL_INTERVAL_MS = 300L
        const val CONTROLLER_LOG_TAIL_CHARS = 4_000
        const val RUNTIME_HEALTH_TIMEOUT_MS = 20_000L
        const val FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS = 5_000L
        internal const val REAL_CHECK_PASSIVE_GAP_MS = 5 * 60_000L
        const val RUNTIME_HEALTH_POLL_INTERVAL_MS = 500L
        const val QUICK_SPEED_SHORTLIST_TIMEOUT_MS = 2_000L
        const val QUICK_SPEED_DOWNLOAD_TIMEOUT_MS = 10_000
        const val QUICK_SPEED_PIN_RESTORE_ATTEMPTS = 2
        const val FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS = 1_500
        const val BACKGROUND_MIHOMO_DELAY_TIMEOUT_MS = 3_000
        const val CONNECTION_DELAY_PAUSE_POLL_INTERVAL_MS = 100L
        const val CONNECTION_DELAY_RECORD_BATCH_SIZE = 25
        const val TUN_ESTABLISH_ATTEMPTS = 3
        const val TUN_ESTABLISH_RETRY_DELAY_MS = 250L
        const val FRONTING_FALLBACK_NOTICE = "None of the Fronting IPs were reachable. Used original connection."
        const val FRONTING_SKIPPED_NOTICE =
            "Fronting IPs failed on this network a moment ago, so the original connection was used first (faster)."
        const val SCAN_DIAGNOSTICS_ENABLED = true
        const val MIHOMO_TUN_STACK = "gvisor"
        const val MIHOMO_TUN_MTU = 9000
        const val AETHER_TUN_MTU = 1280
        const val SSTP_TUN_MTU = 1400
        const val SSTP_MAX_CANDIDATES = 6
        const val SSTP_CONNECT_TIMEOUT_MS = 8_000
        const val SSTP_HANDSHAKE_TIMEOUT_MS = 15_000L
        const val SSTP_IPV6_BLACKHOLE = "fd00:5357:5450::2"
    }
}
