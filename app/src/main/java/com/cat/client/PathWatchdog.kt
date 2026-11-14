package com.cat.client

import android.content.Context

/**
 * The multi-path watchdog: the tiny state machine that decides *when* the app stops insisting
 * on the normal path and takes the Tor one instead — and when it comes back.
 *
 * WHY A DECISION ENGINE AND NOT «IF FAILED THEN SWITCH»: the naive version flaps. A path that
 * fails once (a dead clean IP, a carrier hiccup) would push the owner onto a slow Tor path, and
 * the first success would yank them back — a connection that changes shape every minute is
 * worse than a slow stable one. So the rules are:
 *
 *   * the primary path has to fail [FAILURES_BEFORE_SWITCH] times IN A ROW before the fallback
 *     is used (one-off failures are noise, not a verdict);
 *   * a single success resets the failure run (so a path that recovers is not punished for the
 *     past);
 *   * the fallback is not permanent: after [PRIMARY_RETRY_INTERVAL_MS] the primary gets another
 *     chance, and if it fails again the run rebuilds and the fallback returns. That is what
 *     makes «always connected» honest instead of «stuck on Tor forever»;
 *   * a fallback that is no longer available (Orbot closed) sends the app straight back to the
 *     primary — the alternative would be routing into a dead local port.
 *
 * Everything here is pure functions over an immutable state: no clock, no Android, no I/O, so
 * the whole policy is testable on the JVM (see [PathWatchdogTest]).
 */
internal object PathWatchdogPolicy {
    /** Consecutive primary failures before the fallback is taken. */
    const val FAILURES_BEFORE_SWITCH = 2

    /** How long the primary is left alone before it is tried again. */
    const val PRIMARY_RETRY_INTERVAL_MS = 10 * 60 * 1000L
}

internal data class PathWatchdogState(
    val consecutivePrimaryFailures: Int = 0,
    val usingFallback: Boolean = false,
    val switchedAtMs: Long? = null,
)

internal sealed interface PathWatchdogDecision {
    val reason: String

    /** Stay wherever we are. */
    data class Stay(override val reason: String) : PathWatchdogDecision

    /** Leave the normal path and use the fallback (Orbot). */
    data class UseFallback(override val reason: String) : PathWatchdogDecision

    /** Come back to the normal path (retry window elapsed, or the fallback is gone). */
    data class ReturnToPrimary(override val reason: String) : PathWatchdogDecision
}

internal object PathWatchdog {
    /** Feed one outcome of an attempt on the *primary* path. */
    fun observePrimary(state: PathWatchdogState, succeeded: Boolean): PathWatchdogState {
        return if (succeeded) {
            state.copy(consecutivePrimaryFailures = 0)
        } else {
            state.copy(consecutivePrimaryFailures = state.consecutivePrimaryFailures + 1)
        }
    }

    fun decide(
        armed: Boolean,
        fallbackAvailable: Boolean,
        state: PathWatchdogState,
        nowMs: Long,
        failuresBeforeSwitch: Int = PathWatchdogPolicy.FAILURES_BEFORE_SWITCH,
        retryIntervalMs: Long = PathWatchdogPolicy.PRIMARY_RETRY_INTERVAL_MS,
    ): PathWatchdogDecision {
        if (!armed) return PathWatchdogDecision.Stay("disarmed")
        if (state.usingFallback) {
            if (!fallbackAvailable) return PathWatchdogDecision.ReturnToPrimary("fallback-unavailable")
            val since = state.switchedAtMs
            if (since != null && nowMs - since >= retryIntervalMs) {
                return PathWatchdogDecision.ReturnToPrimary("primary-retry-window")
            }
            return PathWatchdogDecision.Stay("fallback-in-use")
        }
        if (!fallbackAvailable) return PathWatchdogDecision.Stay("no-fallback")
        if (state.consecutivePrimaryFailures >= failuresBeforeSwitch) {
            return PathWatchdogDecision.UseFallback(
                "primary-failed-${state.consecutivePrimaryFailures}",
            )
        }
        return PathWatchdogDecision.Stay("primary-healthy-run")
    }

    /**
     * Apply a decision to the state. Switching clears the failure run: after a return, the
     * primary starts from zero again, which is what stops a single old failure from bouncing the
     * app back to the fallback the moment it is retried.
     */
    fun afterDecision(
        state: PathWatchdogState,
        decision: PathWatchdogDecision,
        nowMs: Long,
    ): PathWatchdogState = when (decision) {
        is PathWatchdogDecision.UseFallback -> state.copy(
            usingFallback = true,
            switchedAtMs = nowMs,
            consecutivePrimaryFailures = 0,
        )
        is PathWatchdogDecision.ReturnToPrimary -> state.copy(
            usingFallback = false,
            switchedAtMs = null,
            consecutivePrimaryFailures = 0,
        )
        is PathWatchdogDecision.Stay -> state
    }

    /** The human-readable state of the run, for the settings row. */
    fun describe(state: PathWatchdogState): String {
        return if (state.usingFallback) {
            "fallback failures=${state.consecutivePrimaryFailures}"
        } else {
            "primary failures=${state.consecutivePrimaryFailures}/${PathWatchdogPolicy.FAILURES_BEFORE_SWITCH}"
        }
    }
}

/**
 * Persistence for the watchdog: the switch, the failure run and the fact that the fallback is
 * currently in use. Kept next to the Orbot engine because the fallback *is* Orbot today, and a
 * watchdog whose state did not survive a process restart would forget it was on the fallback and
 * crawl back to a path that is known to be dead.
 */
internal class PathWatchdogStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_watchdog", Context.MODE_PRIVATE)

    fun isArmed(): Boolean = prefs.getBoolean(KEY_ARMED, false)

    fun setArmed(armed: Boolean) {
        prefs.edit().putBoolean(KEY_ARMED, armed).apply()
    }

    fun readState(): PathWatchdogState = PathWatchdogState(
        consecutivePrimaryFailures = prefs.getInt(KEY_FAILURES, 0),
        usingFallback = prefs.getBoolean(KEY_USING_FALLBACK, false),
        switchedAtMs = prefs.getLong(KEY_SWITCHED_AT, 0L).takeIf { it > 0L },
    )

    fun saveState(state: PathWatchdogState) {
        prefs.edit()
            .putInt(KEY_FAILURES, state.consecutivePrimaryFailures)
            .putBoolean(KEY_USING_FALLBACK, state.usingFallback)
            .putLong(KEY_SWITCHED_AT, state.switchedAtMs ?: 0L)
            .apply()
    }

    /** One failed attempt on the normal path. Returns the new state. */
    fun recordPrimaryFailure(): PathWatchdogState {
        val next = PathWatchdog.observePrimary(readState(), succeeded = false)
        saveState(next)
        return next
    }

    /** One successful attempt on the normal path (resets the failure run). */
    fun recordPrimarySuccess(): PathWatchdogState {
        val next = PathWatchdog.observePrimary(readState(), succeeded = true)
        saveState(next)
        return next
    }

    fun applyDecision(decision: PathWatchdogDecision, nowMs: Long = System.currentTimeMillis()): PathWatchdogState {
        val next = PathWatchdog.afterDecision(readState(), decision, nowMs)
        saveState(next)
        return next
    }

    private companion object {
        const val KEY_ARMED = "armed"
        const val KEY_FAILURES = "failures"
        const val KEY_USING_FALLBACK = "using_fallback"
        const val KEY_SWITCHED_AT = "switched_at"
    }
}
