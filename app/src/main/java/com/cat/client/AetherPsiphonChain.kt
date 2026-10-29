package com.cat.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of [AetherPsiphonChain.start].
 * [via] is `psiphon` (requested country), `psiphon-auto` (Psiphon picked the country) or
 * `warp-2hop` (Psiphon could not connect; two-hop WARP that was verified to exit outside Iran).
 * [exit] is the real exit seen by Cloudflare through the final SOCKS port (null = could not be measured).
 */
internal data class AetherChainOutcome(
    val socksPort: Int,
    val via: String,
    val requestedRegion: String,
    val exit: AetherExitTrace?,
)

/**
 * Starts and owns the two carrier legs; the caller attaches [PsiphonTunPlan] last.
 *
 * Earlier builds gave Psiphon only 18 s (+12 s) and then silently used the single-hop WARP
 * carrier as the "Psiphon" SOCKS port. Single-hop WARP from Iran exits with an Iran-geolocated
 * IP, so users who picked a foreign country (e.g. Germany) mostly got an IR IP without any hint.
 * Now Psiphon gets a realistic budget, the fallback is a two-hop (foreign) WARP carrier, and the
 * real exit country is measured; an IR exit while a foreign exit was requested is a hard error.
 */
internal class AetherPsiphonChain(
    context: Context,
    private val scope: CoroutineScope,
    private val identityFile: File,
    private val protectSocket: (Int) -> Boolean,
    private val endpoint: String? = null,
    private val aetherPort: Int = 1819,
    private val carrierMode: AetherMode = AetherMode.MasqueHttp2,
    private val ipScan: String = "both",
    private val masqueTransport: String = "h2",
    private val innerEndpoint: String? = null,
    private val linkOptions: AetherSelection? = null,
) : Closeable {
    private val active = AtomicBoolean(false)
    private var aether = newCarrier(exitLocation = "auto")
    private val psiphon = PsiphonCarrierClient(context)

    private fun newCarrier(exitLocation: String) = AetherProxyCarrier(
        scope = scope,
        identityFile = identityFile,
        protectSocket = protectSocket,
        endpoint = endpoint,
        port = aetherPort,
        carrierMode = carrierMode,
        ipScan = ipScan,
        masqueTransport = masqueTransport,
        innerEndpoint = innerEndpoint,
        exitLocation = exitLocation,
        linkOptions = linkOptions,
    )

    suspend fun start(region: String = "", onStep: (String) -> Unit = {}): AetherChainOutcome {
        check(active.compareAndSet(false, true)) { "Cat-Psiphon chain is already active" }
        return try {
            onStep("WARP")
            aether.start()
            val regionLabel = region.ifEmpty { "Auto" }
            onStep("Psiphon $regionLabel")
            val first = runCatching {
                psiphon.start(aetherPort, region, timeoutMs = if (region.isNotEmpty()) REGION_TIMEOUT_MS else AUTO_TIMEOUT_MS)
            }
            first.getOrNull()?.let { state ->
                return AetherChainOutcome(state.socksPort, "psiphon", region, measure(state.socksPort))
            }
            runCatching { psiphon.close() }
            // A library start error (bad config) fails the same way for every country — retrying only costs 45 s.
            val libraryRefused = first.exceptionOrNull()?.message.orEmpty().contains("failed to start Psiphon library", ignoreCase = true)
            if (region.isNotEmpty() && !libraryRefused) {
                // The requested country may have no reachable server right now: let Psiphon pick one.
                delay(1_500L)
                onStep("Psiphon Auto")
                val second = runCatching { psiphon.start(aetherPort, "", timeoutMs = AUTO_RETRY_TIMEOUT_MS) }
                second.getOrNull()?.let { state ->
                    return AetherChainOutcome(state.socksPort, "psiphon-auto", region, measure(state.socksPort))
                }
                runCatching { psiphon.close() }
            }
            // Last resort: two-hop WARP (wiw / wom / mim) which exits outside Iran, never single-hop.
            onStep("WARP 2-hop")
            runCatching { aether.close() }
            aether = newCarrier(exitLocation = "foreign")
            aether.start()
            val trace = measure(aetherPort)
            if (trace?.isIranIp == true) {
                throw IOException(
                    "سایفون وصل نشد و WARP دولایه هم آی‌پی ایران (${trace.ip}) داد؛ چون کشور خارج خواسته بودید با آی‌پی ایران " +
                        "وصل نمی‌کنیم. چند دقیقه بعد دوباره یا کشور دیگری را امتحان کنید. — " +
                        "Psiphon did not connect (${first.exceptionOrNull()?.message ?: "timeout"}) and the two-hop WARP fallback " +
                        "still exits in Iran (${trace.ip}). Refusing to connect with an Iranian IP while a foreign exit was requested.",
                )
            }
            AetherChainOutcome(aetherPort, "warp-2hop", region, trace)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private suspend fun measure(port: Int): AetherExitTrace? =
        withContext(Dispatchers.IO) { AetherOperations.probeExitTraceViaSocks(port) }

    override fun close() {
        psiphon.close()
        aether.close()
        active.set(false)
    }

    companion object {
        const val REGION_TIMEOUT_MS = 75_000L
        const val AUTO_TIMEOUT_MS = 90_000L
        const val AUTO_RETRY_TIMEOUT_MS = 45_000L
    }
}
