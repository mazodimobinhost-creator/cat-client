// Server country of each free host: the countries it offers and who sets it (the app, you on the host's site, or nobody).
package com.cat.client

/**
 * beta227 — «ask the user the country». The country of the server is the IP that sites see, so every host install
 * now starts with «📍 Server country». What the app can really do differs per host (Oct 2026 sources):
 *  • Railway: 4 regions, the app sets it through the API (docs.railway.com/deployments/regions);
 *  • Shiprr: eu / us / asia / oc, picked by YOU in the «New app» form — a preference, may fall back (shiprr.app/docs/limits);
 *  • SmarterASP: Europe (Amsterdam) or USA, picked by YOU at sign-up, fixed afterwards;
 *  • KataBump: France only (OVH Gravelines, legal notice); Lucity: EU only;
 *  • Bot-Hosting: the host decides («You cannot choose where your deployment runs», legal §2) — the app shows the node.
 * Pure Kotlin (no Android) so the JVM tests can pin it.
 */
internal object HostRegions {
    enum class Mode { APP_SETS, YOU_PICK, FIXED, HOST_DECIDES }

    /** [site] = the exact words to choose on the host's own form (YOU_PICK only). */
    data class Region(val id: String, val fa: String, val en: String, val site: String = "")

    fun mode(kind: String): Mode = when (kind) {
        "railway" -> Mode.APP_SETS
        "shiprr", "smarterasp" -> Mode.YOU_PICK
        "bothosting" -> Mode.HOST_DECIDES
        else -> Mode.FIXED
    }

    fun choices(kind: String): List<Region> = when (kind) {
        "railway" -> ApiHostPlan.RAILWAY_REGIONS.map { Region(it.first, it.second.first, it.second.second) }
        "shiprr" -> listOf(
            Region("eu", "🇪🇺 اروپا — پیشنهادی، نزدیک‌تر به ایران", "🇪🇺 Europe — recommended, closer to Iran", "eu"),
            Region("us", "🇺🇸 آمریکا", "🇺🇸 USA", "us"),
            Region("asia", "🌏 آسیا", "🌏 Asia", "asia"),
            Region("oc", "🇦🇺 استرالیا / اقیانوسیه", "🇦🇺 Australia / Oceania", "oc"),
        )
        "smarterasp" -> listOf(
            Region("europe", "🇳🇱 هلند (آمستردام) — پیشنهادی، نزدیک‌تر به ایران", "🇳🇱 Netherlands (Amsterdam) — recommended, closer to Iran", "Europe (Amsterdam)"),
            Region("usa", "🇺🇸 آمریکا", "🇺🇸 USA", "USA"),
        )
        "katabump" -> listOf(Region("fr", "🇫🇷 فرانسه (OVH، Gravelines)", "🇫🇷 France (OVH, Gravelines)"))
        "lucity" -> listOf(Region("eu", "🇪🇺 اتحادیهٔ اروپا", "🇪🇺 European Union"))
        "bothosting" -> listOf(Region("auto", "🎲 خودکار: معمولاً 🇫🇮 فنلاند یا 🇩🇪 آلمان، گاهی 🇺🇸 آمریکا", "🎲 Automatic: usually 🇫🇮 Finland or 🇩🇪 Germany, sometimes 🇺🇸 USA"))
        else -> emptyList()
    }

    /** The user can change it (in the app or on the host's form). */
    fun selectable(kind: String): Boolean = mode(kind) == Mode.APP_SETS || mode(kind) == Mode.YOU_PICK

    fun defaultRegion(kind: String): String = choices(kind).firstOrNull()?.id.orEmpty()

    /** [id] when this host offers it, else the host's default. */
    fun valid(kind: String, id: String?): String =
        id?.takeIf { r -> choices(kind).any { it.id == r } } ?: defaultRegion(kind)

    fun region(kind: String, id: String?): Region? = choices(kind).firstOrNull { it.id == valid(kind, id) }

    /** «🇳🇱» for a panel name; empty where the host decides (the node tells it later). */
    fun flag(kind: String, id: String?): String =
        if (mode(kind) == Mode.HOST_DECIDES) "" else region(kind, id)?.en?.substringBefore(' ').orEmpty()

    /** Panel name + flag, without doubling a flag that is already there. */
    fun withFlag(name: String, flag: String): String =
        if (flag.isBlank() || name.endsWith(flag)) name else "$name $flag"
}
