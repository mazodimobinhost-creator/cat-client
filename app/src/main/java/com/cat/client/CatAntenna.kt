package com.cat.client

/**
 * Cat's own signal antenna: how many bars a real delay lights up.
 * Null / failed / not measured → 0. Never invents a ping.
 */
internal object CatAntenna {
    const val MAX = 4

    fun bars(delayMs: Int?, failed: Boolean = false): Int {
        if (failed || delayMs == null || delayMs <= 0) return 0
        return when {
            delayMs <= 80 -> 4
            delayMs <= 200 -> 3
            delayMs <= 400 -> 2
            else -> 1
        }
    }

    fun spoken(delayMs: Int?, failed: Boolean = false, fa: Boolean): String {
        val n = bars(delayMs, failed)
        return when {
            failed -> if (fa) "آنتن قطع" else "Antenna off"
            n <= 0 -> if (fa) "آنتن ندارد" else "No antenna"
            else -> if (fa) "آنتن $n از $MAX" else "Antenna $n of $MAX"
        }
    }
}
