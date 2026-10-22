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

/** Aether WARP (simple WireGuard or MASQUE-HTTP/2) as a loopback-only SOCKS carrier for Psiphon or Tor. */
internal class AetherProxyCarrier(
    private val scope: CoroutineScope,
    private val identityFile: File,
    private val protectSocket: (Int) -> Boolean,
    private val endpoint: String? = null,
    val port: Int = 1819,
    private val carrierMode: AetherMode = AetherMode.MasqueHttp2,
    private val ipScan: String = "both",
) : Closeable {
    private val active = AtomicBoolean(false)
    private var job: Job? = null
    private var ready = CompletableDeferred<Unit>()

    suspend fun start(timeoutMs: Long = 300_000) {
        check(active.compareAndSet(false, true)) { "Aether proxy carrier is already active" }
        check(NativeAetherBridge.isLoaded) { "Aether native core is unavailable" }
        ready = CompletableDeferred()
        val simpleMode = when (carrierMode) {
            AetherMode.WireGuard -> AetherMode.WireGuard
            else -> AetherMode.MasqueHttp2
        }
        val config = AetherRuntimeConfig(
            mode = simpleMode,
            identityFile = identityFile,
            endpoint = endpoint,
            runtimeEndpoint = AetherRuntimeEndpoint.Proxy,
            listenPort = port,
            ipScan = ipScan,
        )
        val json = config.toNativeJson()
        val validation = NativeAetherBridge.validate(json)
        check(validation.ok) { validation.error ?: "Invalid Aether proxy configuration" }
        NativeAetherBridge.setSocketProtector(NativeSocketProtector(protectSocket))
        val prepared = NativeAetherBridge.prepare(json).getOrThrow()
        job = scope.launch(Dispatchers.IO) {
            val result = NativeAetherBridge.run(json, prepared.peer, -1, NativeEngineListener {
                ready.complete(Unit)
            })
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
