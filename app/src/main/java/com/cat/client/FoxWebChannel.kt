// beta238: Cat Fox VPN sign-in through Mozilla's official page using the Firefox WebChannel (session handed over only once verified, token request sent by the page).
package com.cat.client

import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * The official sign-in page (`accounts.firefox.com`, context `fx_desktop_v3`) talks to Firefox through the
 * «WebChannel»: it asks `fxaccounts:fxa_status` / `fxaccounts:can_link_account` and, after the user signed in, sends
 * `fxaccounts:login` with the session token and whether it is verified yet (e-mail code / 2FA still pending → not).
 *
 * Before beta238 Cat polled the page's local storage and took the first session token it found — often one that was
 * not confirmed yet, so the token exchange failed («unconfirmed session») while the user was still typing the code —
 * and then exchanged it from the app, where Mozilla's anti-bot check answers 406. Now Cat answers the WebChannel like
 * Firefox does, waits for a verified session, and lets the page itself send the token request.
 *
 * Idea from the MIT-licensed FoxyVPN 1.0.8 (Vauth/FoxyVPN); re-implemented for Cat, no code copied.
 */
internal object FoxWebChannel {
    const val CHANNEL_ID = "account_updates"
    const val CMD_STATUS = "fxaccounts:fxa_status"
    const val CMD_CAN_LINK = "fxaccounts:can_link_account"
    const val CMD_LOGIN = "fxaccounts:login"
    const val CMD_OAUTH_LOGIN = "fxaccounts:oauth_login"

    /** Firefox's own sign-in context: the page then uses the WebChannel instead of keeping the session to itself. */
    const val START_URL = "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email"

    /** The page only speaks the WebChannel to a browser that says it is Firefox. */
    fun firefoxAndroidUserAgent(androidRelease: String): String {
        val release = androidRelease.trim().ifEmpty { "14" }
        return "Mozilla/5.0 (Android $release; Mobile; rv:152.0) Gecko/152.0 Firefox/152.0"
    }

    private val SESSION_TOKEN = Regex("^[0-9a-f]{64}$")

    fun isSessionToken(value: String?): Boolean = value != null && SESSION_TOKEN.matches(value.trim().lowercase(Locale.US))

    /** Only Mozilla's account pages may hand Cat a session. */
    fun isTrustedHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase(Locale.US).orEmpty()
        return h == "accounts.firefox.com" || h.endsWith(".accounts.firefox.com")
    }

    /** `verified` wins when present; otherwise a pending `verificationReason` means «not yet». */
    fun isVerified(verifiedField: Boolean?, verificationReason: String?): Boolean =
        verifiedField ?: verificationReason.isNullOrBlank()

    /** Mozilla's answer to the token request when the e-mail code / 2FA is not done yet: wait, do not fail. */
    fun isUnconfirmedAnswer(status: Int, body: String): Boolean =
        status == 400 && (
            body.contains("\"errno\":104") || body.contains("\"errno\":138") ||
                body.contains("Unconfirmed session", ignoreCase = true) ||
                body.contains("unverified", ignoreCase = true)
            )

    /** Pages the account site shows once the sign-in is complete. */
    fun isSuccessUrl(url: String?): Boolean {
        val u = url?.lowercase(Locale.US) ?: return false
        if (!u.contains("firefox.com")) return false
        return listOf("/connect_another_device", "/pair", "/settings", "/signin_confirmed", "/signup_confirmed", "/verified", "/complete")
            .any { u.contains(it) }
    }

    /**
     * Injected into every account page: forwards `WebChannelMessageToChrome` events to [Bridge.onMessage] and lets
     * Cat answer with `WebChannelMessageToContent` (both object and string forms — the page accepts either).
     */
    const val HOOK_JS = """
(function () {
  if (window.__catChannelHooked) return;
  window.__catChannelHooked = true;
  var fwd = function (detail) {
    try {
      var host = window.location.hostname;
      if (host !== 'accounts.firefox.com') return;
      catFxaChannel.onMessage(typeof detail === 'string' ? detail : JSON.stringify(detail));
    } catch (e) {}
  };
  window.addEventListener('WebChannelMessageToChrome', function (e) { fwd(e.detail); });
  window.__catReply = function (channelId, messageId, command, dataJson) {
    try {
      var env = { id: channelId, message: { messageId: messageId, command: command, data: JSON.parse(dataJson) } };
      window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: env }));
      window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: JSON.stringify(env) }));
    } catch (e) {}
  };
})();
"""

    /** The page sends the signed token request itself (see [FirefoxVpnEngine.webViewOAuthRequest]). */
    fun oauthFetchJs(authorization: String, bodyJson: String): String {
        val q = { s: String -> org.json.JSONObject.quote(s) }
        return """
(function () {
  fetch('https://api.accounts.firefox.com/v1/oauth/token', {
    method: 'POST',
    headers: { 'Authorization': ${q(authorization)}, 'Content-Type': 'application/json' },
    body: ${q(bodyJson)}
  }).then(function (r) { return r.text().then(function (t) { catFxaChannel.onOAuthResult(r.status, t); }); })
    .catch(function (e) { catFxaChannel.onOAuthError(e ? ('' + (e.message || e)) : 'fetch failed'); });
})();
"""
    }

    /**
     * JavaScript bridge (`catFxaChannel`). Callbacks arrive on a WebView thread; the listener methods are posted to
     * the WebView's (UI) thread.
     */
    class Bridge(
        webView: WebView,
        private val listener: Listener,
    ) {
        interface Listener {
            /** A verified session token: start the token request. */
            fun onVerifiedSession(sessionToken: String, email: String)
            /** The page's token request worked. */
            fun onTokens(sessionToken: String, email: String, accessToken: String, refreshToken: String)
            /** Mozilla says the e-mail code / 2FA is not done yet: keep the page open and wait. */
            fun onWaitingForCode()
            /** The page's token request failed (not «unconfirmed»): fall back to the app-side exchange. */
            fun onTokenRequestFailed(sessionToken: String, email: String, reason: String)
        }

        private val viewRef = WeakReference(webView)
        @Volatile private var session: String? = null
        @Volatile private var email: String = ""
        @Volatile private var started = false

        @JavascriptInterface
        fun onMessage(payload: String) {
            val root = runCatching { org.json.JSONObject(payload) }.getOrNull() ?: return
            val msg = root.optJSONObject("message") ?: return
            val command = msg.optString("command")
            val messageId = msg.optString("messageId")
            val data = msg.optJSONObject("data") ?: org.json.JSONObject()
            when (command) {
                CMD_STATUS -> reply(messageId, command, """{"capabilities":{},"signedInUser":null}""")
                CMD_CAN_LINK -> reply(messageId, command, """{"ok":true}""")
                CMD_LOGIN, CMD_OAUTH_LOGIN -> {
                    val token = data.optString("sessionToken").trim().lowercase(Locale.US)
                    if (isSessionToken(token)) {
                        session = token
                        data.optString("email").trim().takeIf { it.isNotEmpty() }?.let { email = it }
                        val verified = isVerified(
                            if (data.has("verified")) data.optBoolean("verified", false) else null,
                            data.optString("verificationReason").takeIf { data.has("verificationReason") },
                        )
                        if (verified) start()
                    }
                    if (messageId.isNotEmpty()) reply(messageId, command, """{"ok":true}""")
                }
                else -> {
                    // e.g. fxaccounts:verified / change_password after the e-mail code: the pending session is now good.
                    if (command.contains("verified", ignoreCase = true)) {
                        data.optString("sessionToken").trim().lowercase(Locale.US).takeIf { isSessionToken(it) }?.let { session = it }
                        start()
                    }
                    if (messageId.isNotEmpty()) reply(messageId, command, """{"ok":true}""")
                }
            }
        }

        /** A confirmed session found in the page's storage (fallback path) goes through the same page-side request. */
        fun adoptSession(sessionToken: String, accountEmail: String) {
            val token = sessionToken.trim().lowercase(Locale.US)
            if (!isSessionToken(token)) return
            if (session == null || !started) {
                session = token
                if (accountEmail.isNotBlank()) email = accountEmail
            }
            start()
        }

        /** The page reached a «you are signed in» screen: a pending session counts as verified now. */
        fun onSuccessPage() = start()

        private fun start() {
            val token = session ?: return
            if (started) return
            started = true
            post { listener.onVerifiedSession(token, email) }
        }

        @JavascriptInterface
        fun onOAuthResult(status: Int, body: String) {
            val token = session ?: return
            if (status in 200..299) {
                val json = runCatching { org.json.JSONObject(body) }.getOrNull()
                val access = json?.optString("access_token").orEmpty()
                if (access.isNotEmpty()) {
                    val refresh = json?.optString("refresh_token").orEmpty()
                    post { listener.onTokens(token, email, access, refresh) }
                    return
                }
            }
            if (isUnconfirmedAnswer(status, body)) {
                started = false // the code is not entered yet: try again when the page says it is verified
                post { listener.onWaitingForCode() }
                return
            }
            post { listener.onTokenRequestFailed(token, email, "HTTP $status") }
        }

        @JavascriptInterface
        fun onOAuthError(reason: String) {
            val token = session ?: return
            post { listener.onTokenRequestFailed(token, email, reason.take(160)) }
        }

        fun reply(messageId: String, command: String, dataJson: String) {
            val q = { s: String -> org.json.JSONObject.quote(s) }
            val js = "window.__catReply && window.__catReply(${q(CHANNEL_ID)}, ${q(messageId)}, ${q(command)}, ${q(dataJson)});"
            post { runCatching { viewRef.get()?.evaluateJavascript(js, null) } }
        }

        private fun post(block: () -> Unit) {
            val v = viewRef.get() ?: return
            v.post { runCatching(block) }
        }
    }
}
