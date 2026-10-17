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
    private val establishTun: (ipv4: String, ipv6: String) -> Int,
    private val onReady: () -> Unit,
) : Closeable {
    private val active = AtomicBoolean(false)

    fun run(config: AetherRuntimeConfig): NativeResult {
        if (!active.compareAndSet(false, true)) return NativeResult(false, "Aether session is already active")
        var handedOff = false
        var tunFd = -1
        return try {
            check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
            NativeAetherBridge.setSocketProtector(NativeSocketProtector(protectSocket))
            val json = config.toNativeJson()
            val validation = NativeAetherBridge.validate(json)
            if (!validation.ok) throw IOException(validation.error ?: "Aether configuration is invalid")
            val prepared = NativeAetherBridge.prepare(json).getOrThrow()
            val (ipv4, ipv6) = AetherTunPolicy.validatedAddresses(prepared.ipv4, prepared.ipv6)
            tunFd = establishTun(ipv4, ipv6)
            check(tunFd >= 0) { "Could not establish Aether TUN" }
            handedOff = true
            NativeAetherBridge.run(json, prepared.peer, tunFd, NativeEngineListener(onReady))
        } catch (error: Throwable) {
            NativeResult(false, error.message ?: "Aether session failed")
        } finally {
            // nativeRun owns and closes a handed-off descriptor. Before handoff,
            // the establish callback remains responsible for closing failures.
            if (!handedOff && tunFd >= 0) runCatching { android.system.Os.close(tunFd) }
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
