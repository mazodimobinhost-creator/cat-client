package com.cat.client

/**
 * beta237: when Cat Fox VPN renews its Mozilla ProxyPass, and what to do with each Guardian answer — the same rules
 * Firefox's own IP Protection uses (toolkit/components/ipprotection, MPL-2.0; rules re-implemented, no code copied).
 *
 * Firefox renews the pass on a timer 2 minutes before it ends (`ProxyPass.ROTATION_TIME`), in the background, while
 * the old pass still works. Cat used to renew only *inside* a tunnel, in the pass's last 60 seconds: every tunnel
 * opened in that minute started its own renewal at once, all of them waited for vpn.mozilla.org, and if the renewal
 * had to fall back to a request through the VPN itself it went back into the Fox bridge, which needed a pass…
 * «it works for a while, then nothing opens until I reconnect».
 */
internal object FenixPassPolicy {
    /** Renew this long before the pass ends (Firefox: 2 min; Cat: 5 min, so a slow line still finishes in time). */
    const val ROTATE_BEFORE_SEC = 300L

    /** A pass in its last 30 s is not sent any more: it can expire on Mozilla's side during the handshake. */
    const val HARD_MARGIN_SEC = 30L

    /** How often the background renewer looks at the pass while the bridge runs. */
    const val CHECK_INTERVAL_MS = 30_000L

    /** How long a tunnel waits for a renewal another thread is already doing (then it fails instead of piling up). */
    const val WAIT_FOR_RENEWAL_MS = 8_000L

    enum class State { NONE, FRESH, ROTATE_SOON, EXPIRED }

    fun state(pass: String, expiresAtSec: Long?, nowSec: Long): State = when {
        pass.isBlank() -> State.NONE
        expiresAtSec == null || expiresAtSec <= 0L -> State.FRESH
        expiresAtSec <= nowSec + HARD_MARGIN_SEC -> State.EXPIRED
        expiresAtSec <= nowSec + ROTATE_BEFORE_SEC -> State.ROTATE_SOON
        else -> State.FRESH
    }

    /** The pass can still be sent right now (renewal may be due, but it has not run out). */
    fun usable(state: State): Boolean = state == State.FRESH || state == State.ROTATE_SOON

    enum class Next { USE, RENEW_LOGIN, ENROLL, STOP }

    /**
     * Guardian `GET /api/v1/fpn/token` status → next step (Firefox `GuardianClient.toError`): 200 a pass; 401 the
     * sign-in token was rejected → get a fresh one and ask again (Firefox drops its cached OAuth token); 403 signed in
     * but not enrolled → enroll (`POST /api/v1/fpn/activate`) and ask again; 429 quota, 451 region, 5xx → stop.
     * Each extra step is tried once.
     */
    fun afterTokenStatus(status: Int, renewedLogin: Boolean, enrolled: Boolean): Next = when {
        status in 200..299 -> Next.USE
        status == 401 && !renewedLogin -> Next.RENEW_LOGIN
        status == 403 && !enrolled -> Next.ENROLL
        else -> Next.STOP
    }

    /**
     * A ClientHello split that fails at once (not a timeout) is our socket wrapper, not the network: on Android 8–10
     * the platform TLS socket writes to the wrapped socket's file descriptor directly and never sees the split, so
     * the handshake breaks. Then the node is dialled again without the split. A timeout means the line or the filter,
     * where a second, unsplit try would only add another wait.
     */
    fun retryWithoutSplit(fragmented: Boolean, error: Throwable): Boolean =
        fragmented && error !is java.net.SocketTimeoutException && error !is java.io.InterruptedIOException
}

/** beta237: after a real network switch (Wi-Fi ⇄ mobile, another Wi-Fi) Cat Fox VPN drops its HTTP/2 connections. */
internal class FoxNetworkReset {
    /** The last real network seen («none» in between — e.g. Wi-Fi lost, mobile not up yet — is skipped). */
    @Volatile var lastRealKey: String? = null
        private set

    /** True when [newKey] is a different real network than the last one, i.e. the old connections are dead. */
    fun onNetwork(newKey: String, bridgeRunning: Boolean): Boolean {
        if (newKey.isBlank() || newKey == "none") return false
        val previous = lastRealKey
        lastRealKey = newKey
        return bridgeRunning && previous != null && previous != newKey
    }
}
