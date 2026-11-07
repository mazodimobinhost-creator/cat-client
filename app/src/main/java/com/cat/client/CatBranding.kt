package com.cat.client

/**
 * beta207: only Cat's own names on screen. Subscriptions saved by older builds (or named by a feed) can still
 * carry another app's name — «⚡ Aether / PattNG Configs», «🦊 Firefox VPN (DE)», «⚡ MLM FLUX (V4)» … — so names
 * are rewritten when they are read. This file is the one place that is allowed to spell the old names.
 */
object CatBranding {
    private val phrases = listOf(
        "Aether / PattNG" to "Cat Warp",
        "Aether WARP" to "Cat Warp",
        "MLM FLUX" to "Cat FLUX",
        "MLM Scout" to "Cat Scout",
        "MLM Pool" to "Cat Pool",
        "Firefox Browser VPN" to "Cat Fox VPN",
        "Firefox VPN" to "Cat Fox VPN",
        "فایرفاکس VPN" to "Cat Fox VPN",
    )
    private val words = listOf(
        "MLM VPN" to "Cat",
        "FENIX" to "Cat Fox",
        "PattNG" to "Cat",
        "Patterniha" to "Cat",
        "Aether" to "Cat Warp",
        "WhiteDNS" to "Cat DNS",
        "SlipNet" to "Cat DNS",
        "Delta-Kronecker" to "Cat Feed",
        "MahsaNG" to "Cat",
        "MahsaNet" to "Cat Feed",
        "MLM" to "Cat",
    ).map { (w, r) -> Regex("""(?<![A-Za-z0-9_])""" + Regex.escape(w) + """(?![A-Za-z0-9_])""") to r }

    fun rebrandName(name: String): String {
        var out = name
        phrases.forEach { (a, b) -> out = out.replace(a, b) }
        words.forEach { (re, r) -> out = re.replace(out, r) }
        return out
            .replace("Cat Warp Cat Warp", "Cat Warp")
            .replace("Cat Fox VPN (Cat Fox", "Cat Fox VPN (")
            .replace("Cat Cat", "Cat")
    }
}
