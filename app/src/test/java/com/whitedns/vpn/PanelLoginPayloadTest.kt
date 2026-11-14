package com.whitedns.vpn

import com.cat.client.PanelCredentials
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's `/api/login` requires `username` whenever the worker was deployed
 * with a panel username (`PANEL_USER`, see `checkLogin()` in
 * catclient.worker.js: `if (wantUser && username.trim() !== wantUser) return false`).
 *
 * The app used to send the password alone, so on any username-protected panel
 * every «send to panel» action failed with «wrong password» and clean IPs could
 * never be added — the user-visible half of «the buttons don't work».
 *
 * These tests pin the payload builder (the Context overload only feeds it the
 * stored values).
 */
class PanelLoginPayloadTest {

    @Test
    fun payloadAlwaysCarriesUsernameAndTypedPassword() {
        val payload = PanelCredentials.loginPayload("admin", "typed-pass", "stored-pass")
        assertTrue("username must always be present", payload.has("username"))
        assertEquals("admin", payload.getString("username"))
        assertEquals("what the user typed wins", "typed-pass", payload.getString("password"))
    }

    @Test
    fun storedPasswordIsTheFallbackWhenThePromptIsEmpty() {
        val payload = PanelCredentials.loginPayload("", "", "uuid-as-default-password")
        assertEquals("", payload.getString("username"))
        assertEquals("uuid-as-default-password", payload.getString("password"))
    }

    @Test
    fun usernameIsTrimmedButNeverInvented() {
        assertEquals("admin", PanelCredentials.loginPayload("  admin  ", "x", "").getString("username"))
        assertEquals("", PanelCredentials.loginPayload("", "x", "").getString("username"))
    }

    @Test
    fun payloadKeySetIsExactlyWhatTheWorkerParses() {
        // The worker reads body.username / body.password; anything else is noise.
        val payload = PanelCredentials.loginPayload("boss", "pw", "")
        val keys = payload.keys().asSequence().toSortedSet().joinToString(",")
        assertEquals("password,username", keys)
        // Round-trip through JSON exactly as the HTTP body does.
        val parsed = JSONObject(payload.toString())
        assertEquals("boss", parsed.optString("username"))
        assertEquals("pw", parsed.optString("password"))
    }
}
