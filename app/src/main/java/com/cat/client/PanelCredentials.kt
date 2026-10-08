/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Panel credentials for the Cat Panel HTTP API.
 *
 * Why this exists: the panel can be deployed with an optional «panel username»
 * (the worker binds PANEL_USER and then its /api/login requires BOTH fields —
 * see `checkLogin()` in catclient.worker.js). The app's API clients used to send
 * only the password, so on any username-protected panel every «send to panel»
 * action failed with «wrong password» no matter what the user typed — clean IPs
 * could never be added. The username learned at deploy time (or typed once) is
 * kept here, keyed by worker URL, and every login now carries it.
 */
package com.cat.client

import android.content.Context
import org.json.JSONObject

object PanelCredentials {

    private const val PREFS = "cat_client_panel_deploys"

    private fun key(workerUrl: String, field: String): String =
        "$field:" + workerUrl.trimEnd('/').lowercase(java.util.Locale.US)

    /** Remembers who owns this panel so later API calls can authenticate. */
    fun remember(context: Context, workerUrl: String, username: String, password: String = "") {
        val base = workerUrl.trimEnd('/')
        if (base.isBlank()) return
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val user = username.trim()
        // A blank username is meaningful (public panel): clearing it must stick,
        // otherwise a previous value would keep breaking the login.
        edit.putString(key(base, "user"), user)
        if (password.isNotBlank()) edit.putString(key(base, "pass"), password)
        edit.apply()
    }

    fun username(context: Context, workerUrl: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(workerUrl, "user"), null)
            .orEmpty()
            .trim()

    fun password(context: Context, workerUrl: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(workerUrl, "pass"), null)
            .orEmpty()

    /**
     * Login body for /api/login. `username` is always present (empty string when
     * the panel has none) — the worker compares it only when PANEL_USER is set,
     * and an empty value never matches a set username, so this stays correct for
     * both flavours of panel.
     *
     * The pure overload is what the JVM tests pin; the Context one only supplies
     * the stored values.
     */
    fun loginPayload(storedUsername: String, password: String, storedPassword: String = ""): JSONObject =
        JSONObject()
            .put("username", storedUsername.trim())
            .put("password", if (password.isNotBlank()) password else storedPassword)

    fun loginPayload(context: Context, workerUrl: String, password: String): JSONObject =
        loginPayload(username(context, workerUrl), password, password(context, workerUrl))
}
