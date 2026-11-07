package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Beta215AetherEngineSlotTest {
    @Test
    fun waitsUntilTheOldEngineRunHasReallyReturned() {
        val started = CountDownLatch(1)
        val stopAsked = CountDownLatch(1)
        val worker = thread {
            AetherEngineSlot.track {
                started.countDown()
                // like nativeRun: returns some time after stop() was requested
                stopAsked.await(5, TimeUnit.SECONDS)
                Thread.sleep(300)
            }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(AetherEngineSlot.busy())
        val stops = AtomicInteger(0)
        val idle = AetherEngineSlot.stopAndAwaitIdle({ stops.incrementAndGet(); stopAsked.countDown() }, timeoutMs = 5_000)
        assertTrue(idle)
        assertFalse(AetherEngineSlot.busy())
        assertTrue(stops.get() >= 1)
        worker.join(2_000)
    }

    @Test
    fun idleSlotDoesNotStopAnything() {
        val stops = AtomicInteger(0)
        assertTrue(AetherEngineSlot.stopAndAwaitIdle({ stops.incrementAndGet() }))
        assertEquals(0, stops.get())
    }

    @Test
    fun givesUpHonestlyWhenTheEngineNeverStops() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val worker = thread { AetherEngineSlot.track { started.countDown(); release.await(5, TimeUnit.SECONDS) } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertFalse(AetherEngineSlot.stopAndAwaitIdle({}, timeoutMs = 400, repeatMs = 100))
        release.countDown()
        worker.join(2_000)
        assertFalse(AetherEngineSlot.busy())
    }

    @Test
    fun prepareIsRetriedWhileTheCoreSaysAlreadyRunning() {
        val calls = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val result = AetherEngineSlot.prepareWhenFree(stop = { stops.incrementAndGet() }, timeoutMs = 5_000, pauseMs = 50) {
            if (calls.incrementAndGet() < 3) Result.failure(IllegalStateException("engine is already running")) else Result.success("ok")
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(3, calls.get())
        assertTrue(stops.get() >= 2)
        // other errors are not retried
        val other = AtomicInteger(0)
        val failed = AetherEngineSlot.prepareWhenFree(stop = {}, timeoutMs = 2_000, pauseMs = 50) {
            other.incrementAndGet(); Result.failure<String>(IllegalStateException("no endpoint answered"))
        }
        assertTrue(failed.isFailure)
        assertEquals(1, other.get())
    }

    @Test
    fun olderStartIsSuperseded() {
        val first = AetherEngineSlot.newGeneration()
        assertTrue(AetherEngineSlot.isCurrent(first))
        val second = AetherEngineSlot.newGeneration()
        assertFalse(AetherEngineSlot.isCurrent(first))
        assertTrue(AetherEngineSlot.isCurrent(second))
        val error = runCatching { AetherEngineSlot.ensureCurrent(first) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }

    @Test
    fun busyErrorsGetAPlainExplanation() {
        assertTrue(AetherEngineSlot.isEngineBusyError("Connection failed: engine is already running"))
        assertFalse(AetherEngineSlot.isEngineBusyError("timeout"))
        assertEquals(ConnectionFaultExplainer.Cause.EngineBusy, ConnectionFaultExplainer.classify("engine is already running"))
        assertEquals(ConnectionFaultExplainer.Cause.EngineBusy, ConnectionFaultExplainer.classify(AetherEngineSlot.BUSY_MESSAGE))
    }
}

class Beta215AetherEngineWiringTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/cat/client/$name"), File("app/src/main/java/com/cat/client/$name"))
            .first { it.isFile }.readText()

    @Test
    fun everyNativeRunIsTrackedAndEveryStartWaitsForTheSlot() {
        listOf("AetherSession.kt", "AetherProxyCarrier.kt").forEach { name ->
            val s = src(name)
            assertTrue(name, s.contains("AetherEngineSlot.track {"))
            assertTrue(name, s.contains("AetherEngineSlot.stopAndAwaitIdle({ NativeAetherBridge.stop() })"))
            assertTrue(name, s.contains("AetherEngineSlot.prepareWhenFree { NativeAetherBridge.prepare(primaryJson) }"))
            assertTrue(name, s.contains("AetherEngineSlot.newGeneration()"))
            // no untracked native run left
            val runs = Regex("""NativeAetherBridge\.run\(""").findAll(s).count()
            val tracked = Regex("""AetherEngineSlot\.track \{\s*NativeAetherBridge\.run\(""").findAll(s).count()
            assertEquals(name, runs, tracked)
        }
    }
}
