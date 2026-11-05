// beta244 «دور زدن تحریم»: chosen sanctioned services go DIRECT, resolved by an Iranian anti-sanction DNS.
package com.cat.client

/**
 * beta244 — anti-sanction routing (idea from other clients' «sanction rules»; own code, own list).
 *
 * Services that refuse Iranian users (ChatGPT, Gemini, Docker Hub…) are NOT filtered by Iran; they are blocked
 * by the service itself. An Iranian anti-sanction DNS answers their names with its own relay, so they open
 * DIRECTLY on the user's line: faster than the tunnel, no server volume used, and no «VPN detected» walls.
 *
 * Off by default. When on, the mihomo runtime gets, for the services the user ticked:
 *  - one `DOMAIN-SUFFIX,<d>,DIRECT` rule per domain, merged into the ONE existing `rules:` list (never a second one),
 *    placed after the app's measurement rules so the ping / exit-IP probes still ride the tunnel;
 *  - `IP-CIDR,<dns>/32,DIRECT,no-resolve` so the queries to that DNS never ride the tunnel (`respect-rules`
 *    would otherwise send them abroad, and the DNS would answer a foreign IP with the real, sanctioned address);
 *  - `dns.nameserver-policy` `'+.<d>': '<dns>'`.
 *
 * Which DNS works for which service changes often and per line, so nothing is claimed: [verdict] reads a real
 * answer (DNS on the user's line → direct HTTPS with a verified certificate) and the dialog ticks only what passed.
 */
object AntiSanctionRouting {
    data class Dns(val ip: String, val fa: String, val en: String)
    data class Service(
        val id: String,
        val label: String,
        val domains: List<String>,
        val testHost: String,
        val testPath: String,
    )

    /** Settings as stored; [enabled] false = nothing is added to the engine config. */
    data class Settings(val enabled: Boolean = false, val dns: String = DNS_OPTIONS[0].ip, val services: Set<String> = emptySet()) {
        val active: Boolean get() = enabled && services.isNotEmpty() && DNS_OPTIONS.any { it.ip == dns }
        fun chosen(): List<Service> = SERVICES.filter { it.id in services }
    }

    val DNS_OPTIONS = listOf(
        Dns("178.22.122.100", "شکن", "Shecan"),
        Dns("185.51.200.2", "شکن ۲", "Shecan 2"),
        Dns("10.202.10.202", "۴۰۳", "403"),
        Dns("78.157.42.100", "الکترو", "Electro"),
        Dns("10.202.10.10", "رادار", "Radar"),
    )

    val SERVICES = listOf(
        Service("openai", "ChatGPT / OpenAI", listOf("openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com"), "api.openai.com", "/v1/models"),
        Service(
            "gemini", "Gemini / Google AI Studio",
            listOf("gemini.google.com", "aistudio.google.com", "generativelanguage.googleapis.com", "makersuite.google.com", "alkalimakersuite-pa.clients6.google.com"),
            "generativelanguage.googleapis.com", "/v1beta/models",
        ),
        Service("claude", "Claude", listOf("claude.ai", "anthropic.com"), "api.anthropic.com", "/v1/models"),
        Service("copilot", "GitHub Copilot", listOf("githubcopilot.com", "copilot-proxy.githubusercontent.com"), "api.githubcopilot.com", "/"),
        Service("docker", "Docker Hub", listOf("docker.io", "docker.com"), "registry-1.docker.io", "/v2/"),
        Service("android", "Android Developers / Firebase", listOf("developer.android.com", "firebase.google.com", "firebaseinstallations.googleapis.com"), "developer.android.com", "/"),
        Service("nvidia", "NVIDIA", listOf("nvidia.com", "nvidia.cn"), "www.nvidia.com", "/en-us/"),
        Service("amd", "AMD", listOf("amd.com"), "www.amd.com", "/en.html"),
        Service("intel", "Intel", listOf("intel.com"), "www.intel.com", "/content/www/us/en/homepage.html"),
        Service("oracle", "Oracle / Java", listOf("oracle.com", "java.com"), "www.oracle.com", "/"),
        Service("adobe", "Adobe", listOf("adobe.com", "adobe.io"), "www.adobe.com", "/"),
        Service("epic", "Epic Games", listOf("epicgames.com", "unrealengine.com"), "store.epicgames.com", "/"),
    )

    private const val P = "  - "

    private fun q(v: String) = "'" + v.replace("'", "''") + "'"

    /**
     * True when [domain] belongs to a service the user ticked. The runtime then leaves it out of its «AI domains
     * always ride the tunnel» list (those rules come first and would shadow the DIRECT rule); unticked services
     * keep that protection.
     */
    fun covers(s: Settings, domain: String): Boolean =
        s.active && s.chosen().any { svc -> svc.domains.any { domain == it || domain.endsWith(".$it") } }

    /** Rules for the ONE `rules:` list (already indented); empty when off. */
    fun rulesYaml(s: Settings): String {
        if (!s.active) return ""
        return buildString {
            append(P).append(q("IP-CIDR,${s.dns}/32,DIRECT,no-resolve")).append('\n')
            for (svc in s.chosen()) for (d in svc.domains) append(P).append(q("DOMAIN-SUFFIX,$d,DIRECT")).append('\n')
        }
    }

    /** `nameserver-policy` block inside `dns:` (2-space key, 4-space entries); empty when off. */
    fun nameserverPolicyYaml(s: Settings): String {
        if (!s.active) return ""
        return buildString {
            append("  nameserver-policy:\n")
            for (svc in s.chosen()) for (d in svc.domains) append("    ").append(q("+.$d")).append(": ").append(q(s.dns)).append('\n')
        }
    }

    fun encode(s: Settings): String = (if (s.enabled) "1" else "0") + "|" + s.dns + "|" + s.services.sorted().joinToString(",")

    fun decode(raw: String?): Settings {
        val p = raw.orEmpty().split('|')
        if (p.size < 3) return Settings()
        val dns = p[1].takeIf { d -> DNS_OPTIONS.any { it.ip == d } } ?: DNS_OPTIONS[0].ip
        val known = SERVICES.map { it.id }.toSet()
        return Settings(p[0] == "1", dns, p[2].split(',').filter { it in known }.toSet())
    }

    // ---- the real test: a DNS A query on the user's line, then direct HTTPS ----

    fun dnsQuery(host: String, id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8 and 0xff); out.write(id and 0xff)
        out.write(0x01); out.write(0x00) // recursion desired
        out.write(0); out.write(1); repeat(6) { out.write(0) }
        for (label in host.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.size in 1..63) { "bad label" }
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(0); out.write(1) // A
        out.write(0); out.write(1) // IN
        return out.toByteArray()
    }

    /** First IPv4 answer of a reply to [id]; null for no answer, an error code or a malformed packet. */
    fun firstA(reply: ByteArray, len: Int, id: Int): String? = runCatching {
        fun u8(i: Int) = reply[i].toInt() and 0xff
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (len < 12 || u16(0) != id || (u8(3) and 0x0f) != 0) return null
        var p = 12
        fun skipName() {
            while (true) {
                val l = u8(p)
                if (l == 0) { p += 1; return }
                if (l and 0xc0 == 0xc0) { p += 2; return }
                p += 1 + l
            }
        }
        repeat(u16(4)) { skipName(); p += 4 }
        repeat(u16(6)) {
            skipName()
            val type = u16(p); val rdLen = u16(p + 8)
            p += 10
            if (p + rdLen > len) return null
            if (type == 1 && rdLen == 4) return "${u8(p)}.${u8(p + 1)}.${u8(p + 2)}.${u8(p + 3)}"
            p += rdLen
        }
        null
    }.getOrNull()

    /** Iran's filter answers with its own private block page addresses; that is «filtered», not «works». */
    fun isFilterAddress(ip: String): Boolean = ip.startsWith("10.10.34.") || ip == "0.0.0.0" || ip.startsWith("127.")

    enum class Verdict { Works, StillSanctioned, Filtered, NoDns, NoConnect, BadCert, ServerError }

    private val SANCTION = Regex(
        "(?i)unsupported_country|unsupported[ _]region|country, region, or territory|location is not supported|user location|" +
            "not available in your (country|region)|not supported in your (country|region)|us company|export (control|restriction)|" +
            "sanction|embargo|restricted (country|region)|request not allowed|you do not have access|access denied",
    )

    /** HTTP status + the start of the body → what the user gets on that path. */
    fun verdict(status: Int, body: String): Verdict = when {
        status == 451 -> Verdict.StillSanctioned
        status in setOf(400, 401, 403, 404) && SANCTION.containsMatchIn(body) -> Verdict.StillSanctioned
        status in 200..499 -> Verdict.Works
        else -> Verdict.ServerError
    }

    fun statusOf(head: String): Int = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
