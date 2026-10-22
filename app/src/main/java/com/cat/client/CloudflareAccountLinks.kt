package com.cat.client

/**
 * Cloudflare account/token entry links, and the invariant that makes the
 * in-app «get a token» flow work for brand-new users.
 *
 * Why this file exists — the «Invalid redirect_uri» dead end:
 * the token template link (`profile/api-tokens?permissionGroupKeys=…&accountId=*&zoneId=all&name=…`)
 * is a deep link with query parameters. When it is opened while the user is
 * NOT signed in to Cloudflare, the login page treats the whole URL as its
 * `redirect_uri`, and its validator rejects that URL — the user sees
 * «Invalid redirect_uri - does not match configured values» and cannot get any
 * further, precisely at the moment they are trying to create an account.
 *
 * The flow that always works:
 *   1. SIGNUP / LOGIN — plain URLs with nothing to validate, so Cloudflare can
 *      never reject them; the user creates or opens their account;
 *   2. TOKENS (also parameter-free, a safe fallback);
 *   3. only then the parameter-rich token template (CloudflareWorker
 *      .CF_TOKEN_TEMPLATE_URL), which now opens straight on the dashboard
 *      because a session exists — no redirect, no validation, no error.
 *
 * The unit test `CloudflareAccountLinksTest` pins the invariant: everything in
 * [REDIRECT_SAFE] must stay query-parameter free, and the template URL must
 * stay parameter-rich (that is why it may never be the first link a
 * signed-out user taps).
 */
object CloudflareAccountLinks {
    const val SIGNUP = "https://dash.cloudflare.com/sign-up"
    const val LOGIN = "https://dash.cloudflare.com/login"
    const val TOKENS = "https://dash.cloudflare.com/profile/api-tokens"

    /** URLs that are safe while signed out: no query → no redirect_uri check. */
    val REDIRECT_SAFE = listOf(SIGNUP, LOGIN, TOKENS)

    /**
     * True when the URL carries query parameters and therefore must not be the
     * first link a signed-out user opens (Cloudflare validates it as a
     * redirect_uri and rejects it → «Invalid redirect_uri»).
     */
    fun carriesQueryParameters(url: String): Boolean = url.substringAfter('?', "").isNotEmpty()
}
