package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-path watchdog, exercised as real code on the JVM.
 *
 * The whole point of this engine is that «the path failed, jump to Tor» is the *wrong* rule: a
 * single failure (a dead clean IP, a carrier hiccup) would throw the owner onto a slow Tor path
 * and the first success would yank them back. These tests pin the anti-flap rules, because the
 * cost of getting them wrong is a connection that changes shape every minute:
 *
 *   * one failure is noise — the run has to reach the threshold;
 *   * one success resets the run, so a recovered path is not punished for its past;
 *   * the fallback is temporary — after the retry window the primary is tried again;
 *   * a fallback that is gone (Orbot closed) sends the app back immediately, because the
 *     alternative is dialling a dead local port;
 *   * a disarmed watchdog never moves, whatever the counters say.
 */
class PathWatchdogTest {

    private val armed = true
    private val available = true
    private val t0 = 1_000_000L

    private fun decide(
        state: PathWatchdogState,
        now: Long = t0,
        armed: Boolean = this.armed,
        fallbackAvailable: Boolean = this.available,
    ) = PathWatchdog.decide(armed, fallbackAvailable, state, now)

    // ---------- the failure run ----------

    @Test
    fun oneFailureIsNoise() {
        val once = PathWatchdog.observePrimary(PathWatchdogState(), succeeded = false)
        assertEquals(1, once.consecutivePrimaryFailures)
        assertTrue("a single failure must not switch the path", decide(once) is PathWatchdogDecision.Stay)
    }

    @Test
    fun theRunHasToReachTheThreshold() {
        val twice = PathWatchdog.observePrimary(
            PathWatchdog.observePrimary(PathWatchdogState(), succeeded = false),
            succeeded = false,
        )
        assertEquals(2, twice.consecutivePrimaryFailures)
        val decision = decide(twice)
        assertTrue(decision is PathWatchdogDecision.UseFallback)
        assertEquals("primary-failed-2", decision.reason)
    }

    @Test
    fun oneSuccessResetsTheRun() {
        val failures = PathWatchdog.observePrimary(
            PathWatchdog.observePrimary(PathWatchdogState(), succeeded = false),
            succeeded = false,
        )
        val recovered = PathWatchdog.observePrimary(failures, succeeded = true)
        assertEquals(0, recovered.consecutivePrimaryFailures)
        assertTrue(decide(recovered) is PathWatchdogDecision.Stay)
    }

    // ---------- taking the fallback ----------

    @Test
    fun takingTheFallbackRecordsWhenItStarted() {
        val switched = PathWatchdog.afterDecision(
            PathWatchdogState(consecutivePrimaryFailures = 2),
            PathWatchdogDecision.UseFallback("primary-failed-2"),
            nowMs = t0,
        )
        assertTrue(switched.usingFallback)
        assertEquals(t0, switched.switchedAtMs)
        // The run is cleared: after a return the primary starts from zero again.
        assertEquals(0, switched.consecutivePrimaryFailures)
    }

    @Test
    fun theFallbackStaysUntilTheRetryWindowElapses() {
        val inFallback = PathWatchdogState(usingFallback = true, switchedAtMs = t0)
        val early = decide(inFallback, now = t0 + PathWatchdogPolicy.PRIMARY_RETRY_INTERVAL_MS - 1)
        assertTrue("must not bounce back before the window", early is PathWatchdogDecision.Stay)
        val late = decide(inFallback, now = t0 + PathWatchdogPolicy.PRIMARY_RETRY_INTERVAL_MS)
        assertTrue(late is PathWatchdogDecision.ReturnToPrimary)
        assertEquals("primary-retry-window", late.reason)
    }

    @Test
    fun aFallbackThatIsGoneSendsTheAppBackImmediately() {
        val inFallback = PathWatchdogState(usingFallback = true, switchedAtMs = t0)
        val decision = decide(inFallback, now = t0 + 5, fallbackAvailable = false)
        assertTrue(decision is PathWatchdogDecision.ReturnToPrimary)
        assertEquals("fallback-unavailable", decision.reason)
    }

    @Test
    fun returningClearsTheFallbackState() {
        val returned = PathWatchdog.afterDecision(
            PathWatchdogState(usingFallback = true, switchedAtMs = t0),
            PathWatchdogDecision.ReturnToPrimary("primary-retry-window"),
            nowMs = t0 + PathWatchdogPolicy.PRIMARY_RETRY_INTERVAL_MS,
        )
        assertFalse(returned.usingFallback)
        assertEquals(null, returned.switchedAtMs)
        assertEquals(0, returned.consecutivePrimaryFailures)
    }

    @Test
    fun afterAReturnTheOldFailuresCannotBounceItBack() {
        // The state a return leaves behind must need TWO fresh failures again, not one.
        val returned = PathWatchdog.afterDecision(
            PathWatchdogState(usingFallback = true, switchedAtMs = t0),
            PathWatchdogDecision.ReturnToPrimary("primary-retry-window"),
            nowMs = t0 + PathWatchdogPolicy.PRIMARY_RETRY_INTERVAL_MS,
        )
        val oneFail = PathWatchdog.observePrimary(returned, succeeded = false)
        assertTrue(decide(oneFail) is PathWatchdogDecision.Stay)
    }

    // ---------- the two hard "no" rules ----------

    @Test
    fun withoutAFallbackTheWatchdogNeverSwitches() {
        val failures = PathWatchdogState(consecutivePrimaryFailures = 9)
        val decision = decide(failures, fallbackAvailable = false)
        assertTrue(decision is PathWatchdogDecision.Stay)
        assertEquals("no-fallback", decision.reason)
    }

    @Test
    fun aDisarmedWatchdogNeverMoves() {
        val failures = PathWatchdogState(consecutivePrimaryFailures = 9)
        val decision = decide(failures, armed = false)
        assertTrue(decision is PathWatchdogDecision.Stay)
        assertEquals("disarmed", decision.reason)
        val inFallback = PathWatchdogState(usingFallback = true, switchedAtMs = t0)
        assertTrue(decide(inFallback, now = t0 + 10 * 60 * 1000L, armed = false) is PathWatchdogDecision.Stay)
    }

    @Test
    fun theStatusLineStatesTheRunItIsActuallyOn() {
        assertEquals("primary failures=1/2", PathWatchdog.describe(PathWatchdogState(consecutivePrimaryFailures = 1)))
        assertTrue(PathWatchdog.describe(PathWatchdogState(usingFallback = true)).startsWith("fallback"))
    }
}
