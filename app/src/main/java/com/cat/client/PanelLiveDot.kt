package com.cat.client

/**
 * beta245 🟢 live dot in front of every panel in the app's panel lists (Cloudflare, Vercel, Node hosts).
 *
 * The colour comes ONLY from a real request to the panel's own `/health` route
 * ([CloudflareWorker.checkPanelHealth], the same probe the status card uses) made from this phone
 * when the list opens — never from a saved flag. Emoji circles are used on purpose: they keep their
 * colour inside plain `setItems` list dialogs, where a coloured View cannot be placed.
 */
internal object PanelLiveDot {
    const val CHECKING = "⚪"
    const val UP = "🟢"
    const val STARTING = "🟡"
    const val DOWN = "🔴"

    /** [CloudflareWorker.PanelHealth.state] → dot. Blank = not answered yet. */
    fun of(state: String?): String = when (state.orEmpty()) {
        "" -> CHECKING
        "HEALTHY" -> UP
        // the workers.dev route is not live yet right after an upload — a wait, not a fault
        "PENDING" -> STARTING
        else -> DOWN
    }

    /**
     * Node-host panels are often plain `http://IP:port` (KataBump), which [CloudflareWorker.checkPanelHealth]
     * cannot reach — cleartext is disabled app-wide — so those go through [NodeHostApi.panelGet] (raw socket for
     * http, HTTPS otherwise) and their answer is classified here with the same rule: 2xx + JSON `"ok"` = up.
     */
    fun stateOf(code: Int, body: String): String = if (code in 200..299 && body.contains("\"ok\"")) "HEALTHY" else "DOWN"

    fun label(state: String?, text: String): String = of(state) + "  " + text

    /** A short word for screen readers and the single-panel status line. */
    fun word(state: String?, fa: Boolean): String = when (of(state)) {
        UP -> if (fa) "در دسترس است" else "reachable"
        STARTING -> if (fa) "در حال بالا آمدن" else "starting"
        DOWN -> if (fa) "جواب نمی‌دهد" else "not answering"
        else -> if (fa) "در حال بررسی…" else "checking…"
    }

    /** `/health` base for a saved panel address: adds https:// to a bare host, drops a trailing slash. */
    fun healthBase(address: String): String? {
        val a = address.trim().trimEnd('/')
        if (a.isBlank()) return null
        return if (a.startsWith("http://", true) || a.startsWith("https://", true)) a else "https://$a"
    }
}
