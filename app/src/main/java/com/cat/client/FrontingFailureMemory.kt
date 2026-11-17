package com.cat.client

/**
 * beta217 (slow connect): with fronting/clean IPs saved, every connect first tried up to five of them — each try a
 * full core start plus a health check that may wait 20 s — and only then fell back to the config's own address.
 * On a network where those IPs do not work but the original does, that made *every* connect take a minute or more.
 *
 * When the IPs all fail and the original then connects, this remembers it for that network (and that exact IP
 * list) for [TTL_MS]. The next connect on the same network goes to the original first; the saved IPs are still
 * tried if the original fails. Switching network, changing the IP list or a fronting success forgets it.
 */
internal object FrontingFailureMemory {
    const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 16

    private val entries = LinkedHashMap<String, Long>()

    private fun key(networkKey: String, frontingIps: List<String>): String =
        networkKey + "|" + frontingIps.map(String::trim).sorted().joinToString(",")

    @Synchronized
    fun shouldTryOriginalFirst(
        networkKey: String,
        frontingIps: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (frontingIps.isEmpty()) return false
        val k = key(networkKey, frontingIps)
        val until = entries[k] ?: return false
        if (nowMs >= until) {
            entries.remove(k)
            return false
        }
        return true
    }

    @Synchronized
    fun frontingFailedOriginalWorked(
        networkKey: String,
        frontingIps: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (frontingIps.isEmpty()) return
        val k = key(networkKey, frontingIps)
        entries.remove(k)
        entries[k] = nowMs + TTL_MS
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun frontingWorked(networkKey: String, frontingIps: List<String>) {
        entries.remove(key(networkKey, frontingIps))
    }

    @Synchronized
    internal fun clearForTest() = entries.clear()
}
