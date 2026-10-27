package com.cat.client

import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeEngineListener
import com.whitedns.whiteaesther.core.NativeSocketProtector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
) : Closeable {
    private val active = AtomicBoolean(false)
    private var job: Job? = null
    private var ready = CompletableDeferred<Unit>()

    suspend fun start(timeoutMs: Long = 300_000) {
        check(active.compareAndSet(false, true)) { "Aether proxy carrier is already active" }
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
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
        )
        val primaryJson = config.toNativeJson()
        val validation = NativeAetherBridge.validate(primaryJson)
        check(validation.ok) { validation.error ?: "Invalid Aether proxy configuration" }
        val forceMimH2 = config.resolvedNativeTransport() == "mim" && config.usesMasqueHttp2
        NativeAetherBridge.setSocketProtector(NativeSocketProtector { fd ->
            if (forceMimH2) AetherOperations.enforceMasqueHttp2EnvNow(true)
            protectSocket(fd)
        })
        var effectiveConfig = config
        var effectiveJson = primaryJson
        val prepared = AetherOperations.withMasqueHttp2ForMim(config) {
            NativeAetherBridge.prepare(primaryJson)
        }.recoverCatching { firstError ->
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
                val altCfg = config.copy(mode = fallbackMode, endpoint = null, ipScan = "both")
                val altJson = altCfg.toNativeJson()
                NativeAetherBridge.prepare(altJson).onSuccess {
                    effectiveConfig = altCfg
                    effectiveJson = altJson
                }.getOrElse { throw firstError }
            }.getOrThrow()
        }.getOrThrow()
        val runCfg = effectiveConfig
        val runJson = effectiveJson
        job = scope.launch(Dispatchers.IO) {
            val result = AetherOperations.withMasqueHttp2ForMim(runCfg) {
                NativeAetherBridge.run(runJson, prepared.peer, -1, NativeEngineListener {
                    ready.complete(Unit)
                })
            }
            if (!result.ok && !ready.isCompleted) {
                ready.completeExceptionally(IOException(result.error ?: "Aether proxy stopped"))
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

    override fun close() {
        NativeAetherBridge.cancelPrepare()
        NativeAetherBridge.stop()
        job?.cancel()
        job = null
        runCatching { NativeAetherBridge.setSocketProtector(null) }
        active.set(false)
    }
}
