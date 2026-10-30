package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which key the app is allowed to try on `/sub/<key>` — the phone half of the bug the user
 * reported as «when I create a link it gives this error».
 *
 * The panel hands out (and itself fetches) subscription links built from the master key. Since
 * panel 6.67.0 that key is the subscription TOKEN once the owner rotated one, and the rotation
 * exists precisely to kill every UUID link: a worker that answers `/sub/<uuid>` with 404 is
 * healthy, not broken. A smoke test that only ever tried the UUID therefore declared a working
 * panel dead and kept showing a stale `…/sub/<uuid>` address.
 *
 * The fix tries the UUID first (every install that never rotated still works exactly as
 * before) and then the stored credential — but ONLY when it really is a token (32 hex
 * characters, which is what the panel rotates to). An owner password must never be sent as a
 * subscription key: a wrong guess would look like a broken worker and leak the password into a
 * URL. That rule is a pure function, so it is pinned here rather than trusted.
 */
class PanelSubKeyTest {

    private val uuid = "11111111-2222-4333-8444-555555555555"
    private val token = "a894574bc0cd4c2cc5475bd00619e3b1"

    @Test
    fun anInstallThatNeverRotatedOnlyHasTheUuid() {
        assertEquals(listOf(uuid), CloudflareWorker.subscriptionKeyCandidates(uuid, ""))
    }

    @Test
    fun theRotatedTokenIsTriedAfterTheUuid() {
        assertEquals(
            listOf(uuid, token),
            CloudflareWorker.subscriptionKeyCandidates(uuid, token),
        )
    }

    @Test
    fun anOwnerPasswordIsNeverUsedAsASubscriptionKey() {
        for (password in listOf("owner-secret", "correct horse battery staple", "12345678", "ghp_SECRETPAT")) {
            assertEquals(
                "a password must not reach /sub/<key>: $password",
                listOf(uuid),
                CloudflareWorker.subscriptionKeyCandidates(uuid, password),
            )
        }
    }

    @Test
    fun aPastedTokenSurvivesCasingAndWhitespace() {
        // The panel lower-cases the key in the path, so an upper-case copy still works — and a
        // credential pasted with a trailing newline must not be silently dropped.
        assertEquals(
            listOf(uuid, token),
            CloudflareWorker.subscriptionKeyCandidates(uuid, "  ${token.uppercase()}\n"),
        )
    }

    @Test
    fun aMissingUuidStillLetsTheTokenBeTried() {
        // A re-deploy over a worker the app never stored a UUID for (or a freshly rotated one).
        assertEquals(listOf(token), CloudflareWorker.subscriptionKeyCandidates("", token))
        assertEquals(listOf(token), CloudflareWorker.subscriptionKeyCandidates("   ", token))
    }

    @Test
    fun nothingIsTriedWhenNeitherKeyExists() {
        assertTrue(CloudflareWorker.subscriptionKeyCandidates("", "").isEmpty())
        assertTrue(CloudflareWorker.subscriptionKeyCandidates("  ", "").isEmpty())
    }

    @Test
    fun theSameKeyIsNeverTriedTwice() {
        // An install whose stored UUID already IS the token: one request, not two.
        val keys = CloudflareWorker.subscriptionKeyCandidates(token, token)
        assertEquals(listOf(token), keys)
        assertFalse(keys[0].isBlank())
    }
}
