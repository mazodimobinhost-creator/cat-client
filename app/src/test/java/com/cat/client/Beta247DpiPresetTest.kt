package com.cat.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** beta247 🧪 fragmenting-method test: which servers are tested and how presets are ranked (pure). */
class Beta247DpiPresetTest {
    @Test fun onlyTcpTlsServersWithARealName() {
        val raws = listOf(
            "  - {name: a, type: vless, server: 104.16.1.1, port: 443, tls: true, servername: w.example.workers.dev, network: ws}",
            "  - name: b\n    type: trojan\n    server: t.example.com\n    port: 8443\n    sni: 't.example.com'",
            "  - {name: c, type: vless, server: 1.2.3.4, port: 443, tls: true, servername: 1.2.3.4}",       // IP name → skipped
            "  - {name: d, type: vless, server: 5.6.7.8, port: 80, tls: false, servername: x.example.com}", // no TLS
            "  - {name: e, type: hysteria2, server: h.example.com, port: 443, sni: h.example.com}",        // UDP
            "  - {name: f, type: vless, server: 127.0.0.1, port: 10808, tls: true, servername: loop.example.com}",
            "  - {name: g, type: vless, server: 104.16.1.2, port: 2053, tls: true, servername: W.example.workers.dev}", // same name
            "  - {name: h, type: vless, server: r.example.com, port: 443, servername: www.speedtest.net, reality-opts: {public-key: k}}",
        )
        val targets = DpiPresetPlan.targets(raws)
        assertEquals(
            listOf(
                DpiTarget("104.16.1.1", 443, "w.example.workers.dev"),
                DpiTarget("t.example.com", 8443, "t.example.com"),
                DpiTarget("r.example.com", 443, "www.speedtest.net"),
            ),
            targets,
        )
        assertEquals(2, DpiPresetPlan.targets(raws, max = 2).size)
        assertTrue(DpiPresetPlan.targets(emptyList()).isEmpty())
    }

    @Test fun rankingPrefersMorePassesThenSpeed() {
        val ranked = DpiPresetPlan.rank(listOf(
            DpiTrial("default", 1, 3, 90), DpiTrial("mci", 3, 3, 400), DpiTrial("tci", 3, 3, 200), DpiTrial("gaming", 0, 3, null),
        ))
        assertEquals(listOf("tci", "mci", "default"), ranked.map { it.presetId })
    }
}
