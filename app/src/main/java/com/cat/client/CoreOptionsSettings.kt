// beta240 settings rows for core options other clients carry and Cat lacked: sniffing, FakeDNS, system HTTP proxy.
package com.cat.client

import android.os.Build
import android.widget.LinearLayout
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * beta240 — three engine options that other clients show in their settings screen and Cat did not have.
 * Each one maps to a real option of the running core (nothing cosmetic):
 *
 * - «Sniffing»          → mihomo `sniffer` (route-only: `override-destination: false`), [MihomoRuntimeConfigBuilder.SNIFFER_BLOCK].
 * - «FakeDNS»           → `dns.enhanced-mode` fake-ip (default, unchanged) or redir-host (real IPs).
 * - «System HTTP proxy» → `VpnService.Builder.setHttpProxy(127.0.0.1:mixed-port)`, Android 10+ only.
 *
 * A change reconnects a running tunnel at once (same path as the other connection options), so the owner sees
 * the effect without a manual reconnect. «Mux» is deliberately absent: the core cannot speak the multiplexing
 * most VLESS/Trojan servers use, and a switch that breaks working configs would be worse than none.
 */
internal fun MainActivity.addCoreOptionRows(panel: LinearLayout) {
    val store = routingModePreferenceStore
    fun row(title: Int, detail: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val toggle = MaterialSwitch(this).apply {
            isChecked = checked
            contentDescription = getString(title)
            setOnClickListener { onChange(isChecked) }
        }
        panel.addView(
            advancedToggleRow(title = getString(title), detail = getString(detail), toggle = toggle),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
        )
    }
    row(R.string.core_sniffing_title, R.string.core_sniffing_description, store.isSniffingEnabled()) { on ->
        saveCoreOption("sniffing", store.isSniffingEnabled(), on) { store.saveSniffingEnabled(it) }
    }
    row(R.string.core_fake_dns_title, R.string.core_fake_dns_description, store.isFakeDnsEnabled()) { on ->
        saveCoreOption("fakeDns", store.isFakeDnsEnabled(), on) { store.saveFakeDnsEnabled(it) }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        row(R.string.core_http_proxy_title, R.string.core_http_proxy_description, store.isSystemHttpProxyEnabled()) { on ->
            saveCoreOption("systemHttpProxy", store.isSystemHttpProxyEnabled(), on) { store.saveSystemHttpProxyEnabled(it) }
        }
    }
}

private fun MainActivity.saveCoreOption(name: String, previous: Boolean, next: Boolean, save: (Boolean) -> Unit) {
    if (previous == next) return
    save(next)
    DiagnosticLogger.info(this, "activity.coreOption.saved", "$name=$next")
    reconnectForConnectionOptionChange()
}

/**
 * beta240 «ping address»: the URL every config's real delay test requests through the tunnel. Google stays the
 * default; Cloudflare helps on networks where Google's test host is slow or throttled, which would otherwise make
 * every config look bad. Both answer HTTPS 204, so a middlebox cannot fake a good ping.
 */
internal fun MainActivity.addDelayTestUrlRow(panel: LinearLayout, current: String, onPick: (String) -> Unit) {
    val urls = ConnectionTestSettings.DELAY_TEST_URLS
    val labels = listOf(getString(R.string.config_test_url_google) to 0, getString(R.string.config_test_url_cloudflare) to 0)
    panel.addView(
        zedTitledCard(
            R.string.config_test_url_title,
            R.string.config_test_url_detail,
            zedSegmented(labels, urls.indexOf(current).coerceAtLeast(0)) { picked ->
                onPick(urls[picked])
                DiagnosticLogger.info(this, "activity.delayTestUrl.saved", urls[picked])
            },
        ),
        LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12); marginEnd = dp(12); bottomMargin = dp(12) },
    )
}
