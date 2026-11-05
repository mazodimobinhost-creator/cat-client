package com.cat.client

import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeEngineListener
import com.whitedns.whiteaesther.core.NativeResult
import com.whitedns.whiteaesther.core.NativeSocketProtector
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one native Aether session. All blocking methods must be called on Dispatchers.IO. */
internal class AetherSession(
    private val protectSocket: (Int) -> Boolean,
    /** Establishes a TUN and detaches its descriptor; native code becomes its owner. */
    private val establishTun: (ipv4: String, ipv6: String, mtu: Int) -> Int,
    private val onReady: () -> Unit,
) : Closeable {
    private val active = AtomicBoolean(false)

    /** beta241: what really connected (after the fallbacks), so the service can remember it for this network. */
    @Volatile var lastPeer: String? = null
        private set
    @Volatile var lastMode: AetherMode? = null
        private set
    @Volatile var lastIpScan: String? = null
        private set

    fun run(config: AetherRuntimeConfig): NativeResult {
        if (!active.compareAndSet(false, true)) return NativeResult(false, "Cat Warp session is already active")
        var handedOff = false
        var tunFd = -1
        return try {
            check(NativeAetherBridge.isLoaded) { "Cat Warp native core is unavailable" }
            val gen = AetherEngineSlot.newGeneration()
            // beta215: a previous engine (reconnect, failover, a chain carrier) may still hold the core's only run slot.
            if (!AetherEngineSlot.stopAndAwaitIdle({ NativeAetherBridge.stop() })) throw IOException(AetherEngineSlot.BUSY_MESSAGE)
            val forceMimH2 = config.resolvedNativeTransport() == "mim" && config.usesMasqueHttp2
            NativeAetherBridge.setSocketProtector(NativeSocketProtector { fd ->
                if (forceMimH2) AetherOperations.enforceMasqueHttp2EnvNow(true)
                protectSocket(fd)
            })
            val primaryJson = config.toNativeJson()
            val validation = NativeAetherBridge.validate(primaryJson)
            if (!validation.ok) throw IOException(validation.error ?: "Cat Warp configuration is invalid")
            var effectiveConfig = config
            var effectiveJson = primaryJson
            val prepared = AetherOperations.withMasqueHttp2ForMim(config) {
                AetherEngineSlot.prepareWhenFree { NativeAetherBridge.prepare(primaryJson) }
            }.recoverCatching { firstError ->
                // Automatic fallback 1: if a pinned endpoint or IPv6-only scan failed (e.g. SIM has no IPv6 route),
                // retry with dual-stack automatic endpoint discovery.
                AetherEngineSlot.ensureCurrent(gen)
                val autoDiscoveryCfg = config.copy(endpoint = null, ipScan = "both")
                val autoDiscovery = autoDiscoveryCfg.toNativeJson()
                // beta241: the first attempt already WAS automatic discovery on both families — repeating it only
                // doubled the wait before the other mode was tried.
                val sameAsFirst = config.endpoint == null && config.ipScan == "both"
                (if (sameAsFirst) Result.failure(firstError) else AetherOperations.withMasqueHttp2ForMim(autoDiscoveryCfg) {
                    NativeAetherBridge.prepare(autoDiscovery)
                }).onSuccess {
                    effectiveConfig = autoDiscoveryCfg
                    effectiveJson = autoDiscovery
                }.recoverCatching {
                    // Automatic fallback 2: switch between MASQUE and WireGuard while preserving Iran/Foreign exit policy.
                    val altMode = when (config.mode) {
                        AetherMode.MasqueInMasque -> AetherMode.WireGuardOverMasque
                        AetherMode.WarpInWarp -> AetherMode.WireGuard
                        AetherMode.WireGuard -> AetherMode.MasqueHttp2
                        else -> AetherMode.WireGuard
                    }
                    AetherEngineSlot.ensureCurrent(gen)
                    val altCfg = config.copy(mode = altMode, endpoint = null, ipScan = "both")
                    val altJson = altCfg.toNativeJson()
                    NativeAetherBridge.prepare(altJson).onSuccess {
                        effectiveConfig = altCfg
                        effectiveJson = altJson
                    }.getOrElse { throw firstError }
                }.getOrThrow()
            }.getOrThrow()
            AetherEngineSlot.ensureCurrent(gen)
            lastPeer = prepared.peer.takeIf { it.isNotBlank() }
            lastMode = effectiveConfig.mode
            lastIpScan = effectiveConfig.ipScan
            val (ipv4, ipv6) = AetherTunPolicy.validatedAddresses(prepared.ipv4, prepared.ipv6)
            // beta219: size the interface for the transport that really runs (a fallback may have changed it).
            tunFd = establishTun(ipv4, ipv6, AetherTunPolicy.mtuForTransport(AetherTunPolicy.transportOfJson(effectiveJson)))
            check(tunFd >= 0) { "Could not establish Cat Warp TUN" }
            handedOff = true
            AetherOperations.withMasqueHttp2ForMim(effectiveConfig) {
                AetherEngineSlot.track { NativeAetherBridge.run(effectiveJson, prepared.peer, tunFd, NativeEngineListener(onReady)) }
            }
        } catch (error: Throwable) {
            NativeResult(false, error.message ?: "Cat Warp session failed")
        } finally {
            // nativeRun owns and closes a handed-off descriptor. Before handoff,
            // the establish callback remains responsible for closing failures.
            if (!handedOff && tunFd >= 0) runCatching {
                android.os.ParcelFileDescriptor.adoptFd(tunFd).close()
            }
            runCatching { NativeAetherBridge.setSocketProtector(null) }
            active.set(false)
        }
    }

    fun cancelPreparation(): Boolean = NativeAetherBridge.cancelPrepare()

    override fun close() {
        NativeAetherBridge.cancelPrepare()
        NativeAetherBridge.cancelScan()
        NativeAetherBridge.stop()
        runCatching { NativeAetherBridge.setSocketProtector(null) }
    }
}
