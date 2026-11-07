package com.cat.client

import java.util.concurrent.atomic.AtomicInteger

/**
 * beta215: the Cat Warp native core runs ONE engine per app process (the bridge keeps a global run slot and answers
 * «engine is already running» to prepare/run while it is taken). Stopping an engine only *asks* it to stop: the slot
 * stays taken until its blocking native run call returns. Starting the next engine straight after a stop — the
 * WARP → Psiphon chain falling back to two-hop WARP, a reconnect, a failover — therefore failed with
 * «Connection failed: engine is already running».
 *
 * Every native run goes through [track]; before a new engine prepares, [stopAndAwaitIdle] stops a leftover one and
 * waits until its run call has really returned.
 */
internal object AetherEngineSlot {
    private val inFlight = AtomicInteger(0)
    private val lock = Object()

    private val generation = java.util.concurrent.atomic.AtomicLong(0L)

    fun busy(): Boolean = inFlight.get() > 0

    /** Each engine start takes a new generation; an older start that is still preparing must not go on to run. */
    fun newGeneration(): Long = generation.incrementAndGet()

    fun isCurrent(gen: Long): Boolean = generation.get() == gen

    fun ensureCurrent(gen: Long) {
        if (!isCurrent(gen)) throw java.io.IOException("Cat Warp start superseded by a newer connection")
    }

    fun <T> track(block: () -> T): T {
        inFlight.incrementAndGet()
        try {
            return block()
        } finally {
            if (inFlight.decrementAndGet() <= 0) {
                inFlight.set(0)
                synchronized(lock) { lock.notifyAll() }
            }
        }
    }

    /** Stops a leftover engine (re-asking every [repeatMs]) and waits up to [timeoutMs] for its run call to return. */
    fun stopAndAwaitIdle(stop: () -> Unit, timeoutMs: Long = 12_000L, repeatMs: Long = 500L): Boolean {
        if (!busy()) return true
        runCatching(stop)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (busy()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) break
                lock.wait(minOf(left, repeatMs))
                if (busy()) runCatching(stop)
            }
        }
        return !busy()
    }

    /**
     * Runs a native prepare; when the core still answers «already running» (an engine that was not started through
     * [track], or one that has not unwound yet), asks it to stop and tries again for up to [timeoutMs].
     */
    fun <T> prepareWhenFree(
        stop: () -> Unit = {
            runCatching {
                com.whitedns.whiteaesther.core.NativeAetherBridge.cancelPrepare()
                com.whitedns.whiteaesther.core.NativeAetherBridge.cancelScan()
                com.whitedns.whiteaesther.core.NativeAetherBridge.stop()
            }
        },
        timeoutMs: Long = 10_000L,
        pauseMs: Long = 700L,
        attempt: () -> Result<T>,
    ): Result<T> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var result = attempt()
        while (result.isFailure && isEngineBusyError(result.exceptionOrNull()?.message) &&
            System.currentTimeMillis() < deadline
        ) {
            runCatching(stop)
            stopAndAwaitIdle(stop, timeoutMs = pauseMs * 3)
            Thread.sleep(pauseMs)
            result = attempt()
        }
        return result
    }

    fun isEngineBusyError(message: String?): Boolean {
        val m = message?.lowercase().orEmpty()
        return "engine is already running" in m || "route preparation is already running" in m ||
            "endpoint scan is already running" in m
    }

    const val BUSY_MESSAGE =
        "هستهٔ Cat Warp هنوز اتصال قبلی را می‌بست و آزاد نشد؛ چند ثانیه صبر کنید و دوباره «اتصال» را بزنید. " +
            "— The previous Cat Warp engine did not shut down in time; wait a few seconds and connect again."
}
