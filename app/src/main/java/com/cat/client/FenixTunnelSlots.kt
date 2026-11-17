package com.cat.client

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * beta219: slots for Cat Fox VPN tunnels (one per app TCP connection).
 *
 * The bridge allowed 64 tunnels and simply hung up on the 65th. Tunnels never time out (an idle keep-alive, push
 * or long-poll connection is legitimate), so after a few minutes of normal phone use — Instagram, Telegram, Google
 * services each keep many connections open — every slot was held by an idle connection and every *new* connection
 * was refused: «connected, but nothing loads / Instagram freezes».
 *
 * Now the cap is higher and, when it is reached, the tunnel that has been silent the longest (at least
 * [idleEvictMs]) is closed to make room — apps simply reopen an idle connection when they need it again.
 */
internal class FenixTunnelSlots(
    val capacity: Int,
    private val idleEvictMs: Long = 15_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    inner class Lease internal constructor() : Closeable {
        @Volatile var lastActive: Long = clock()
            private set
        private val attached = CopyOnWriteArrayList<Closeable>()
        private val released = AtomicBoolean(false)
        private val evictedFlag = AtomicBoolean(false)

        val evicted: Boolean get() = evictedFlag.get()

        /** Data moved in either direction. */
        fun touch() {
            lastActive = clock()
        }

        /** Sockets closed when this tunnel is evicted (the client socket, later the upstream tunnel). */
        fun attach(closeable: Closeable) {
            attached += closeable
            if (evictedFlag.get()) runCatching { closeable.close() }
        }

        internal fun evict() {
            if (!evictedFlag.compareAndSet(false, true)) return
            attached.forEach { runCatching { it.close() } }
            close()
        }

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            live.remove(this)
            permits.release()
        }
    }

    private val permits = Semaphore(capacity)
    private val live: MutableSet<Lease> = ConcurrentHashMap.newKeySet()

    val inUse: Int get() = live.size

    /** A slot for a new tunnel, evicting the longest-idle one when full; `null` when every tunnel is busy. */
    fun acquire(waitMs: Long = 1_500L): Lease? {
        if (!permits.tryAcquire()) {
            evictOldestIdle()
            if (!permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return null
        }
        return Lease().also { live += it }
    }

    /** Closes the tunnel that has been silent the longest, if it has been silent for at least [idleEvictMs]. */
    fun evictOldestIdle(): Boolean {
        val now = clock()
        val victim = live.filter { now - it.lastActive >= idleEvictMs }.minByOrNull { it.lastActive } ?: return false
        victim.evict()
        return true
    }
}
