package com.cat.client

import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeEngineListener
import com.whitedns.whiteaesther.core.NativeSocketProtector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Aether WARP (MASQUE-in-MASQUE, MASQUE-HTTP/2, WireGuard-over-MASQUE, WARP-in-WARP, or WireGuard) as a loopback-only SOCKS carrier for Psiphon or Tor. */
internal class AetherProxyCarrier(
    private val scope: CoroutineScope,
    private val identityFile: File,
    private val protectSocket: (Int) -> Boolean,
    private val endpoint: String? = null,
    val port: Int = 1819,
    private val carrierMode: AetherMode = AetherMode.MasqueHttp2,
    private val ipScan: String = "both",
    private val masqueTransport: String = "h2",
    private val innerEndpoint: String? = null,
    /** `auto` = carrier only (Psiphon/Tor decides the exit); `foreign` = two-hop WARP so the carrier itself exits abroad. */
    private val exitLocation: String = "auto",
    /** beta206: the profile's own TLS / fragment / SNI / scan options, so a chained carrier dials like the link says. */
    private val linkOptions: AetherSelection? = null,
) : Closeable {
    private val active = AtomicBoolean(false)
    private var job: Job? = null
    private var ready = CompletableDeferred<Unit>()

    suspend fun start(timeoutMs: Long = 300_000) {
        check(active.compareAndSet(false, true)) { "Cat Warp proxy carrier is already active" }
        val loaded = withContext(Dispatchers.IO) { NativeAetherBridge.isLoaded }
        if (!loaded) {
            active.set(false)
            throw IllegalStateException("Cat Warp native core is unavailable")
        }
        ready = CompletableDeferred()
        val requestedMode = when (carrierMode) {
            AetherMode.MasquePsiphon -> AetherMode.MasqueHttp2
            else -> carrierMode
        }
        val config = AetherRuntimeConfig(
            mode = requestedMode,
            identityFile = identityFile,
            endpoint = endpoint,
            innerEndpoint = innerEndpoint,
            runtimeEndpoint = AetherRuntimeEndpoint.Proxy,
            listenPort = port,
            ipScan = ipScan,
            masqueTransport = masqueTransport,
            exitLocation = exitLocation,
        ).let { base ->
            val o = linkOptions ?: return@let base
            base.copy(
                scanMode = o.scanMode.takeIf { it in AetherRuntimeConfig.VALID_SCAN_MODES } ?: base.scanMode,
                noizePreset = o.noizePreset.takeIf { it in AetherRuntimeConfig.VALID_NOIZE_PRESETS } ?: base.noizePreset,
                fingerprint = o.fingerprint.ifBlank { "chrome" },
                fragmentEnabled = o.fragmentEnabled,
                fragmentSize = o.fragmentSize,
                fragmentDelay = o.fragmentDelay,
                masqueSni = o.masqueSni,
                echEnabled = o.echEnabled,
                echDns = o.echDns,
                echDomain = o.echDomain,
            )
        }
        val gen = AetherEngineSlot.newGeneration()
        // beta215: the WARP → Psiphon chain closes one carrier and starts the next at once; wait until the old
        // engine's native run has returned, otherwise prepare answers «engine is already running».
        val idle = withContext(Dispatchers.IO) { AetherEngineSlot.stopAndAwaitIdle({ NativeAetherBridge.stop() }) }
        if (!idle) {
            active.set(false)
            throw IOException(AetherEngineSlot.BUSY_MESSAGE)
        }
        // beta218: everything from here to the end of prepare is blocking native work (endpoint scan, WARP
        // provisioning, a busy-engine retry with Thread.sleep) that can take many seconds. The VPN service calls
        // start() from its main-thread scope, which is also the screen's thread, so the whole app froze after
        // «Connect» on the WARP → Psiphon / WARP → Tor chains — even «Disconnect» could not be pressed. It now runs
        // on an IO thread; cancelPrepare() from close() (main thread) can interrupt it.
        val (prepared, effectiveConfig, effectiveJson) = try {
            withContext(Dispatchers.IO) { prepareBlocking(config, requestedMode, gen) }
        } catch (error: Throwable) {
            active.set(false)
            throw error
        }
        if (!AetherEngineSlot.isCurrent(gen)) {
            active.set(false)
            throw IOException("Cat Warp start superseded by a newer connection")
        }
        val runCfg = effectiveConfig
        val runJson = effectiveJson
        job = scope.launch(Dispatchers.IO) {
            val result = AetherOperations.withMasqueHttp2ForMim(runCfg) {
                AetherEngineSlot.track {
                    NativeAetherBridge.run(runJson, prepared.peer, -1, NativeEngineListener {
                        ready.complete(Unit)
                    })
                }
            }
            if (!result.ok && !ready.isCompleted) {
                ready.completeExceptionally(IOException(result.error ?: "Cat Warp proxy stopped"))
            }
            active.set(false)
        }
        try {
            withTimeout(timeoutMs) { ready.await() }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun prepareBlocking(
        config: AetherRuntimeConfig,
        requestedMode: AetherMode,
        gen: Long,
    ): Triple<PreparedHolder, AetherRuntimeConfig, String> {
        val primaryJson = config.toNativeJson()
        val validation = NativeAetherBridge.validate(primaryJson)
        check(validation.ok) { validation.error ?: "Invalid Cat Warp proxy configuration" }
        val forceMimH2 = config.resolvedNativeTransport() == "mim" && config.usesMasqueHttp2
        NativeAetherBridge.setSocketProtector(NativeSocketProtector { fd ->
            if (forceMimH2) AetherOperations.enforceMasqueHttp2EnvNow(true)
            protectSocket(fd)
        })
        var effectiveConfig = config
        var effectiveJson = primaryJson
        val prepared = AetherOperations.withMasqueHttp2ForMim(config) {
            AetherEngineSlot.prepareWhenFree { NativeAetherBridge.prepare(primaryJson) }
        }.recoverCatching { firstError ->
            AetherEngineSlot.ensureCurrent(gen)
            val autoDiscoveryCfg = config.copy(endpoint = null, ipScan = "both")
            val autoDiscovery = autoDiscoveryCfg.toNativeJson()
            AetherOperations.withMasqueHttp2ForMim(autoDiscoveryCfg) {
                NativeAetherBridge.prepare(autoDiscovery)
            }.onSuccess {
                effectiveConfig = autoDiscoveryCfg
                effectiveJson = autoDiscovery
            }.recoverCatching {
                val fallbackMode = when (requestedMode) {
                    AetherMode.MasqueInMasque, AetherMode.WireGuardOverMasque -> AetherMode.MasqueHttp2
                    AetherMode.WarpInWarp -> AetherMode.WireGuard
                    AetherMode.WireGuard -> AetherMode.MasqueHttp2
                    else -> AetherMode.WireGuard
                }
                AetherEngineSlot.ensureCurrent(gen)
                val altCfg = config.copy(mode = fallbackMode, endpoint = null, ipScan = "both")
                val altJson = altCfg.toNativeJson()
                NativeAetherBridge.prepare(altJson).onSuccess {
                    effectiveConfig = altCfg
                    effectiveJson = altJson
                }.getOrElse { throw firstError }
            }.getOrThrow()
        }.getOrThrow()
        return Triple(PreparedHolder(prepared.peer), effectiveConfig, effectiveJson)
    }

    private class PreparedHolder(val peer: String)

    override fun close() {
        NativeAetherBridge.cancelPrepare()
        NativeAetherBridge.stop()
        job?.cancel()
        job = null
        runCatching { NativeAetherBridge.setSocketProtector(null) }
        active.set(false)
    }
}
