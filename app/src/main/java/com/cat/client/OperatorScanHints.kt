package com.cat.client

/**
 * beta245 📶 per-operator scanner suggestions (pure logic, no Android — unit-tested on the JVM).
 *
 * Two sources, in this order:
 *  1. **learned** — `IP:port` pairs that really passed TLS + HTTP in a scan on THIS phone while it was on that
 *     operator's mobile data (Wi-Fi runs are kept apart under [WIFI]: on Wi-Fi Android still reports the SIM's
 *     operator, so labelling them by the SIM would be wrong). Newest first, 30 days, 60 per operator;
 *  2. **starting ranges** — where to look first on each operator. Not a promise: every address is still
 *     scanned live from the user's own line, and only what answers is shown or kept.
 */
internal object OperatorScanHints {
    const val WIFI = "wifi"
    const val MAX_PER_OP = 60
    const val TTL_MS = 30L * 24 * 3600 * 1000

    data class Hit(val ip: String, val port: Int, val ms: Int, val at: Long) {
        val authority: String get() = (if (ip.contains(':')) "[$ip]" else ip) + ":" + port
    }

    /** The same starting ranges the panel's browser scanner offers (keep both in sync). */
    fun startRanges(op: String): List<String> = when (op) {
        MobileCarrier.MCI.id -> listOf("104.16.0.0/16", "104.17.0.0/16", "172.64.0.0/16", "162.159.0.0/16", "2a06:98c1:3101::6812:202f")
        MobileCarrier.IRANCELL.id -> listOf("188.114.96.0/22", "162.159.192.0/24", "162.159.195.0/24", "104.18.0.0/16")
        MobileCarrier.RIGHTEL.id -> listOf("104.21.0.0/16", "172.67.0.0/16", "188.114.96.0/22")
        MobileCarrier.TCI.id -> listOf("104.16.0.0/14", "172.64.0.0/14", "162.159.0.0/16", "141.101.64.0/18")
        else -> listOf("104.16.0.0/13", "172.64.0.0/13", "162.159.0.0/16", "188.114.96.0/20")
    }

    /**
     * Bucket for a scan: the carrier only when the scan really ran on cellular data; null = the transport could not
     * be told (e.g. only a VPN network is visible) → "" = do not record at all rather than guess.
     */
    fun bucket(carrier: MobileCarrier, onCellular: Boolean?): String = when {
        onCellular == null -> ""
        !onCellular -> WIFI
        carrier == MobileCarrier.UNKNOWN -> ""
        else -> carrier.id
    }

    fun displayName(bucket: String, fa: Boolean): String = when (bucket) {
        MobileCarrier.MCI.id -> if (fa) "همراه اول" else "MCI"
        MobileCarrier.IRANCELL.id -> if (fa) "ایرانسل" else "Irancell"
        MobileCarrier.RIGHTEL.id -> if (fa) "رایتل" else "Rightel"
        MobileCarrier.TCI.id -> if (fa) "مخابرات" else "TCI"
        WIFI -> if (fa) "وای‌فای / اینترنت خانگی" else "Wi-Fi / home internet"
        "" -> if (fa) "نامشخص" else "unknown"
        else -> if (fa) "اپراتور دیگر" else "other operator"
    }

    fun encode(hits: List<Hit>): String = hits.joinToString("\n") { "${it.ip}|${it.port}|${it.ms}|${it.at}" }

    fun decode(raw: String?): List<Hit> = raw.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('|')
        if (p.size != 4) return@mapNotNull null
        val port = p[1].toIntOrNull() ?: return@mapNotNull null
        val ip = p[0].trim()
        if (ip.isEmpty() || port !in 1..65535 || ip.any { it == ' ' || it == '/' }) return@mapNotNull null
        Hit(ip, port, p[2].toIntOrNull() ?: 0, p[3].toLongOrNull() ?: 0L)
    }.toList()

    /** New hits first (a re-test replaces the old row of the same IP:port), expired rows dropped, capped. */
    fun merge(old: List<Hit>, fresh: List<Hit>, now: Long): List<Hit> {
        val keys = fresh.map { it.authority }.toSet()
        return (fresh.distinctBy { it.authority } + old.filter { it.authority !in keys && now - it.at < TTL_MS })
            .take(MAX_PER_OP)
    }

    /**
     * Text for the scanner's range box: learned IPs (fastest first, those learned on [port] before the rest) then
     * the starting ranges. Bare IPs on purpose — the scanner probes each on the chosen ports and keeps every
     * success with exactly the port it answered on.
     */
    fun suggestionText(op: String, learned: List<Hit>, port: Int, now: Long): String {
        val live = learned.filter { now - it.at < TTL_MS }
            .sortedWith(compareBy<Hit>({ if (it.port == port) 0 else 1 }, { it.ms }))
            .map { it.ip }
            .distinct()
        return (live + startRanges(op)).distinct().joinToString("\n")
    }
}
