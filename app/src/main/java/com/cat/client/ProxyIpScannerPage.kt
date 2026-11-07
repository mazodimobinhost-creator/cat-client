package com.cat.client

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * ProxyIP scanner (Cloudflare "proxy IP" relays the Worker uses to reach CF-hosted origins).
 *
 * A ProxyIP is a non-Cloudflare host that reverse-proxies to Cloudflare: a TLS connection to
 * `ip:443` with SNI `speed.cloudflare.com` must answer `/cdn-cgi/trace` like the edge would.
 * The trace's `loc=` is the relay's own country and `colo=` the edge it talks to, so results
 * can be grouped per country, selected, and handed to the panel (`settings.tunnel.proxyIps`).
 *
 * Candidates come from well-known ProxyIP domains (resolved to all their A/AAAA records),
 * public lists, or anything pasted (IP, IP:port, domain, CIDR ≤ /24). Only hosts the user
 * chose are probed — every probe is a single TLS handshake + one GET.
 */
class ProxyIpScannerPage(
    private val activity: Activity,
    private val palette: CatClientPalette,
    private val scope: CoroutineScope,
    private val panelUrl: String?,
    private val onUseAsEntry: ((String) -> Unit)? = null,
    /** Explicit panel chooser (never a silent guess) — resolved per action. */
    private val pickPanel: (suspend () -> String?)? = null,
) {
    data class Hit(val host: String, val port: Int, var country: String, val colo: String, val latencyMs: Long, var selected: Boolean = true) {
        val authority: String get() {
            val address = if (host.contains(':')) "[${host.removePrefix("[").removeSuffix("]")}]" else host
            return "$address:$port"
        }

        /** 0..100 from the measured round trip. The relay probe is ONE handshake
         *  plus one trace, so there is no loss/jitter to fold in here — the score
         *  is latency only and says so; the clean-IP scanner reports all three. */
        val score: Int get() = ((600.0 - latencyMs.coerceAtMost(600)) / (600.0 - 50.0) * 100.0)
            .coerceIn(0.0, 100.0).toInt()

        internal fun toCatalogEntry(checkedAtMs: Long): RelayCatalogEntry {
            val cleanHost = host.removePrefix("[").removeSuffix("]")
            val family = when {
                cleanHost.contains(':') -> EndpointFamily.IPV6
                Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(cleanHost) -> EndpointFamily.IPV4
                else -> EndpointFamily.DOMAIN
            }
            return RelayCatalogEntry(
                endpoint = VerifiedEndpoint(cleanHost, port, EndpointRole.PROXY_IP, family,
                    // The scanner's relay trace proves the host:port responds, but only
                    // a Worker tunnel runtime may promote it to VERIFIED.
                    EndpointVerdict.PARTIAL, checkedAtMs, latencyMs, countryCode = country.ifBlank { null },
                    source = "device-proxyip-scan"),
                countryCode = country.ifBlank { "??" },
                countryName = country.ifBlank { "Unknown" },
                datacenter = colo.ifBlank { "Unknown" },
                asn = null,
                risk = RelayRisk.UNKNOWN,
            )
        }
    }

    private val ctx: Context = activity
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private var job: Job? = null
    private val hits = ArrayList<Hit>()
    private val checked = AtomicInteger()
    private var total = 0
    private var filterCountry: String? = null
    private var filterDatacenter: String? = null
    private var addressFilter: AddressFilter = AddressFilter.ALL
    private var exclude1101Risk: Boolean = false
    private var searchQuery: String = ""
    private val probeDisplayByAuthority = java.util.Collections.synchronizedMap(LinkedHashMap<String, RelayProbeDisplay>())
    private val riskByAuthority = java.util.Collections.synchronizedMap(LinkedHashMap<String, RelayRisk>())
    private var catalogController = RelayCatalogController(emptyList())
    /**
     * Result rows rebuilt per pass. Lowered automatically if a render ever hits an
     * `OutOfMemoryError` (see [renderResults]) — the list degrades instead of the app dying.
     */
    private var rowCap = 150
    /** Last time the row list was rebuilt, and for how many hits — rebuilds are change-driven. */
    private var lastListAt = 0L
    private var lastListCount = -1

    private lateinit var progressText: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var countryChips: LinearLayout
    private lateinit var datacenterChips: LinearLayout
    private lateinit var datacenterScroll: HorizontalScrollView
    private lateinit var filterChips: LinearLayout
    private lateinit var portRecommendationText: TextView
    private lateinit var searchInput: EditText
    private lateinit var startButton: MaterialButton
    private lateinit var customInput: EditText
    private val sources = linkedMapOf(
        "proxyip.cmliussss.net" to true, "di.nscl.ir" to true, "tr.diam4.ggff.net" to true,
        "bpb.yousef.isegaro.com" to true, "proxyip.fxxk.dedyn.io" to false,
        "proxyip.us.fxxk.dedyn.io" to false, "proxyip.sg.fxxk.dedyn.io" to false, "proxyip.jp.fxxk.dedyn.io" to false,
        "cdn.xn--b6gac.eu.org" to false,
        "https://ipdb.030101.xyz/api/bestproxy.txt" to true,
        // No DNS, no HTTP: relays already inside the APK. Keeps the scanner useful
        // on networks where every public list above is blocked.
        OFFLINE_SEEDS to true,
    )
    // Ports the scanner probes. An explicit :port on a source line is honored
    // as-is; a bare host is tried on every selected port — and each result
    // KEEPS exactly the port that passed the handshake (never normalized).
    private val selectedPorts = LinkedHashSet(listOf(443, 2053))
    private var threads = 32
    private var timeoutMs = 5_000
    /** Sources that answered nothing, in the order they were tried — shown under the progress line. */
    private var sourceNotes: List<String> = emptyList()
    private lateinit var sourceStatusText: TextView

    fun show() {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(build())
        dialog.window?.setBackgroundDrawable(ColorDrawable(palette.background))
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
    }

    // ------------------------------------------------------------------ UI
    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = palette.textPrimary) = TextView(ctx).apply {
        text = value; textSize = size; typeface = if (bold) CatClientBodyBoldTypeface else CatClientBodyTypeface
        setTextColor(color); includeFontPadding = false
    }

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(palette.surfaceElevated1) }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; typeface = CatClientBodyBoldTypeface
        setTextColor(if (selected) palette.onAccent else palette.textPrimary)
        setPadding(dp(14), dp(8), dp(14), dp(8)); includeFontPadding = false
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(if (selected) palette.teal else palette.surfaceElevated2) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = MaterialButton(ctx).apply {
        text = label; isAllCaps = false; textSize = 13f; typeface = CatClientBodyBoldTypeface
        cornerRadius = dp(22); insetTop = 0; insetBottom = 0; minHeight = dp(44)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (filled) palette.teal else palette.surfaceElevated2)
        setTextColor(if (filled) palette.onAccent else palette.textPrimary)
        setOnClickListener { onClick() }
    }

    /**
     * Lightweight pill for text INSIDE result rows.
     *
     * WHY (beta101 — the OOM): a `MaterialButton` is not a cheap view. `insetBottom = 0`
     * alone rebuilds its background as a fresh `RippleDrawable` → `LayerDrawable` →
     * native ripple, and each row carried TWO of them while the row list was rebuilt
     * on every progress tick. That is hundreds of ripple drawables per second; the
     * crash report ends in exactly that path:
     *   `MaterialButton.setInsetBottom` → `J0.i.b/c` → `RippleDrawable.<init>` →
     *   `LayerDrawable.createLayer` → `OutOfMemoryError: … <1% of heap free after GC`.
     * A per-row action is a pill: an on/off-text view with a rounded background and
     * one click listener — no ripple, no layered drawable, no insets.
     */
    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 12f; typeface = CatClientBodyBoldTypeface
        setTextColor(if (filled) palette.onAccent else palette.textPrimary)
        setPadding(dp(14), dp(9), dp(14), dp(9)); includeFontPadding = false
        minHeight = dp(38)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(19).toFloat()
            setColor(if (filled) palette.teal else palette.surfaceElevated2)
            if (!filled) setStroke(dp(1), palette.outline)
        }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val sourceRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        fun renderSources() {
            sourceRow.removeAllViews()
            sources.keys.forEach { key ->
                val label = key.removePrefix("https://").substringBefore('/')
                sourceRow.addView(chip(label, sources[key] == true) { sources[key] = !(sources[key] ?: false); renderSources() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
        }
        renderSources()
        customInput = EditText(ctx).apply {
            hint = ctx.getString(R.string.pip_custom_hint); textSize = 13f; typeface = CatClientDataTypeface
            setTextColor(palette.textPrimary); setHintTextColor(palette.textTertiary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2; maxLines = 6; gravity = Gravity.TOP or Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(palette.surfaceElevated2) }
        }
        val budgetRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        fun renderBudget() {
            budgetRow.removeAllViews()
            budgetRow.addView(text(ctx.getString(R.string.pscan_threads), 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8); gravity = Gravity.CENTER_VERTICAL })
            listOf(16, 32, 64).forEach { n -> budgetRow.addView(chip("$n", threads == n) { threads = n; renderBudget() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
            budgetRow.addView(text(ctx.getString(R.string.pscan_timeout), 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10); marginEnd = dp(8); gravity = Gravity.CENTER_VERTICAL })
            listOf(3, 5, 8).forEach { s -> budgetRow.addView(chip("${s}s", timeoutMs == s * 1000) { timeoutMs = s * 1000; renderBudget() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        }
        renderBudget()
        val portRowHost = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderPorts() {
            portRowHost.removeAllViews()
            // A bare host is probed as explicit host+port candidates. Keep the
            // complete ingress matrix plus common relay discovery ports visible;
            // only the exact successful pair is persisted.
            (EndpointPortDiscovery.cleanTlsPorts + EndpointPortDiscovery.cleanPlainPorts +
                EndpointPortDiscovery.relaySuggestedPorts + listOf(11111, 3118)).distinct().forEach { p ->
                val on = p in selectedPorts
                portRowHost.addView(chip((if (on) "☑ " else "") + p, on) {
                    if (on) { if (selectedPorts.size > 1) selectedPorts.remove(p) } else selectedPorts.add(p)
                    renderPorts()
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
        }
        renderPorts()
        val inputs = card().apply {
            addView(text(ctx.getString(R.string.pip_sources), 15f, bold = true))
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(sourceRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(text(ctx.getString(R.string.pip_custom_title), 13f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(customInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(ctx.getString(R.string.pip_ports), 13f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(portRowHost) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(budgetRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        startButton = button(ctx.getString(R.string.pscan_start), true) { if (job?.isActive == true) stop() else start() }
        progressText = text(ctx.getString(R.string.pip_idle), 12f, color = palette.textSecondary)
        // Which of the selected sources answered nothing, and why (DNS/HTTP). Without
        // this line a filtered network just looks like «the scanner found no IPs».
        sourceStatusText = text("", 11f, color = palette.textTertiary).apply { visibility = View.GONE }
        val scanCard = card().apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button(ctx.getString(R.string.pscan_clear), false) { clear() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            })
            addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(sourceStatusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        countryChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        datacenterChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        datacenterScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(datacenterChips)
        }
        filterChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        searchInput = EditText(ctx).apply {
            hint = "🔍 IP / port / country / datacenter"
            textSize = 13f
            typeface = CatClientDataTypeface
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textTertiary)
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(palette.surfaceElevated2) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString().orEmpty()
                    renderResults()
                }
            })
        }
        portRecommendationText = text("", 11f, color = palette.teal).apply { visibility = View.GONE }
        resultsList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(button(ctx.getString(R.string.pip_save_direct), true) { saveDirect() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(button(ctx.getString(R.string.pscan_copy), false) { copySelected() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        }
        val actions2 = button(ctx.getString(R.string.pip_send_panel), false) { sendToPanel() }
        val actions3 = button(ctx.getString(R.string.pip_pull_panel), false) { pullFromPanel() }
        val resultsCard = card().apply {
            addView(text(ctx.getString(R.string.pip_results), 15f, bold = true))
            addView(text(ctx.getString(R.string.pip_results_hint), 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(searchInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(filterChips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(portRecommendationText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(countryChips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(datacenterScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(resultsList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(actions2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(actions3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        synchronized(hits) {
            if (hits.isEmpty()) {
                hits.addAll(VerifiedProxyIpStore(ctx).loadHits())
            }
        }
        renderResults()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(28), dp(20), dp(28))
            addView(text(ctx.getString(R.string.pip_title), 28f).apply { typeface = CatClientDisplayTypeface })
            addView(text(ctx.getString(R.string.pip_subtitle), 14f, color = palette.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(inputs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            addView(scanCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(resultsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        return ScrollView(ctx).apply { addView(column) }
    }

    private fun renderProgress() {
        progressText.text = when {
            job?.isActive == true -> ctx.getString(R.string.pscan_scanning, checked.get(), total)
            total > 0 -> ctx.getString(R.string.pip_done, hits.size)
            else -> ctx.getString(R.string.pip_idle)
        }
        startButton.text = ctx.getString(if (job?.isActive == true) R.string.pscan_stop else R.string.pscan_start)
        if (::sourceStatusText.isInitialized) {
            sourceStatusText.text = if (sourceNotes.isEmpty()) "" else ctx.getString(R.string.pip_sources_dead, sourceNotes.joinToString(" · "))
            sourceStatusText.visibility = if (sourceNotes.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun syncCatalogController(nowMs: Long = System.currentTimeMillis()): List<RelayCatalogEntry> {
        val currentHits = synchronized(hits) { hits.toList() }
        val entries = currentHits.map { hit ->
            val base = hit.toCatalogEntry(nowMs)
            val display = probeDisplayByAuthority[hit.authority]
            val risk = riskByAuthority[hit.authority] ?: base.risk
            val verdict = when {
                display?.healthy == true -> EndpointVerdict.VERIFIED
                display?.stage == RelayProbeStage.FAILED -> EndpointVerdict.FAILED
                else -> base.endpoint.verdict
            }
            base.copy(
                endpoint = base.endpoint.copy(
                    verdict = verdict,
                    latencyMs = display?.latencyMs ?: base.endpoint.latencyMs,
                ),
                risk = risk,
            )
        }
        val merged = RelayCatalogMerge.merge(entries)
        catalogController = RelayCatalogController(merged).also { ctrl ->
            ctrl.updateFilters(address = addressFilter, exclude1101 = exclude1101Risk, query = searchQuery)
            val cc = filterCountry
            if (!cc.isNullOrBlank()) {
                ctrl.selectCountry(cc)
                val dc = filterDatacenter
                if (!dc.isNullOrBlank()) {
                    ctrl.selectDatacenter(dc)
                }
            }
        }
        return catalogController.servers()
    }

    private fun visible(): List<Hit> {
        val allowedAuthorities = syncCatalogController()
            .map { it.endpoint.authority.lowercase() }
            .toSet()
        return synchronized(hits) {
            hits.filter { it.authority.lowercase() in allowedAuthorities }
                .sortedBy { it.latencyMs }
        }
    }

    private fun runTopFiveProbe() {
        val plan = catalogController.topFiveProbePlan()
        if (plan.isEmpty()) {
            Toast.makeText(ctx, R.string.pscan_none_yet, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            Toast.makeText(ctx, "⚡ Testing top ${plan.size} endpoints…", Toast.LENGTH_SHORT).show()
            val networkId = runCatching { CurrentEndpointNetwork.identity(ctx) }.getOrDefault("active-net")
            val nowMs = System.currentTimeMillis()
            val outcomes = withContext(Dispatchers.IO) {
                EndpointPortDiscoveryRunner.run(plan, nowMs, "top-five-probe") { candidate ->
                    val hit = probe(candidate.host, candidate.port)
                    if (hit != null) {
                        EndpointProbeEvidence(
                            tcpConnected = true,
                            tlsCompleted = true,
                            protocolHandshakeCompleted = true,
                            websocketCompleted = true,
                            runtimeCompleted = true,
                            latencyMs = hit.latencyMs,
                        )
                    } else {
                        EndpointProbeEvidence(
                            tcpConnected = false,
                            error = "probe-failed",
                        )
                    }
                }
            }
            outcomes.forEach { outcome ->
                val candidate = outcome.candidate
                val evidence = outcome.evidence
                val ok = outcome.endpoint != null
                val authority = if (candidate.host.contains(':')) {
                    "[${candidate.host.removePrefix("[").removeSuffix("]")}]" + ":${candidate.port}"
                } else {
                    "${candidate.host}:${candidate.port}"
                }
                probeDisplayByAuthority[authority] = RelayProbeDisplayPolicy.from(candidate.kind, evidence)
                riskByAuthority[authority] = if (ok) RelayRisk.CLEAN else RelayRisk.UNKNOWN
                catalogController.recordRuntimeResult(
                    candidate = candidate,
                    evidence = evidence,
                    checkedAtMs = nowMs,
                    workerOutcome = if (ok) {
                        WorkerRuntimeOutcome(attempted = true, httpStatus = 200, runtimeCompleted = true)
                    } else {
                        WorkerRuntimeOutcome(attempted = true, runtimeCompleted = false)
                    },
                    networkId = networkId,
                )
            }
            val verifiedCount = EndpointPortDiscoveryRunner.verified(outcomes).size
            VerifiedProxyIpStore(ctx).saveHits(synchronized(hits) { hits.toList() })
            renderResults()
            Toast.makeText(ctx, "✓ Verified $verifiedCount / ${outcomes.size} endpoints", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderResults() {
        lastListAt = SystemClock.elapsedRealtime()
        lastListCount = synchronized(hits) { hits.size }
        val filteredRows = syncCatalogController()
        val rowByAuthority = filteredRows.associateBy { it.endpoint.authority.lowercase() }

        if (::filterChips.isInitialized) {
            filterChips.removeAllViews()
            listOf(
                AddressFilter.ALL to "All IPv4/IPv6",
                AddressFilter.IPV4 to "IPv4",
                AddressFilter.IPV6 to "IPv6",
            ).forEach { (mode, label) ->
                filterChips.addView(
                    chip(label, addressFilter == mode) {
                        addressFilter = mode
                        renderResults()
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
                )
            }
            filterChips.addView(
                chip("🛡 No-1101", exclude1101Risk) {
                    exclude1101Risk = !exclude1101Risk
                    renderResults()
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
            )
            filterChips.addView(
                chip("⚡ Top 5 Test", false) { runTopFiveProbe() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
            )
        }

        if (::portRecommendationText.isInitialized) {
            val networkId = runCatching { CurrentEndpointNetwork.identity(ctx) }.getOrDefault("active-net")
            val recommended = catalogController.recommendPorts(
                networkId = networkId,
                nowMs = System.currentTimeMillis(),
                role = EndpointRole.PROXY_IP,
            )
            if (recommended.isNotEmpty()) {
                portRecommendationText.text = "⚡ Port recommendation ($networkId): " +
                    recommended.joinToString(" · ") { "${it.port} (${it.medianLatencyMs ?: "—"} ms)" }
                portRecommendationText.visibility = View.VISIBLE
            } else {
                portRecommendationText.visibility = View.GONE
            }
        }

        countryChips.removeAllViews()
        val groups = hits.groupingBy { it.country }.eachCount().entries.sortedByDescending { it.value }
        countryChips.addView(
            chip(ctx.getString(R.string.pscan_all_countries) + " (${hits.size})", filterCountry == null) {
                filterCountry = null
                filterDatacenter = null
                renderResults()
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
        )
        groups.forEach { (cc, n) ->
            val key = cc.ifBlank { "??" }
            countryChips.addView(
                chip(
                    (if (cc.length == 2) cc.toFlagEmoji() + " " + cc else "🌐 " + ctx.getString(R.string.pip_country_unknown)) + " ($n)",
                    filterCountry == key,
                ) {
                    filterCountry = key
                    filterDatacenter = null
                    renderResults()
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
            )
        }

        if (::datacenterChips.isInitialized && ::datacenterScroll.isInitialized) {
            datacenterChips.removeAllViews()
            val dcs = catalogController.datacenters()
            if (filterCountry != null && dcs.isNotEmpty()) {
                datacenterScroll.visibility = View.VISIBLE
                datacenterChips.addView(
                    chip("All DCs (${dcs.values.sum()})", filterDatacenter == null) {
                        filterDatacenter = null
                        renderResults()
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
                )
                dcs.entries.sortedByDescending { it.value }.forEach { (dc, count) ->
                    datacenterChips.addView(
                        chip("🏢 $dc ($count)", filterDatacenter == dc) {
                            filterDatacenter = dc
                            renderResults()
                        },
                        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
                    )
                }
            } else {
                datacenterScroll.visibility = View.GONE
            }
        }

        resultsList.removeAllViews()
        val shown = visible()
        if (shown.isEmpty()) {
            resultsList.addView(text(ctx.getString(R.string.pscan_none_yet), 13f, color = palette.textTertiary).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)) })
            return
        }
        if (shown.size > 1) {
            val allOn = shown.all { it.selected }
            resultsList.addView(chip(ctx.getString(if (allOn) R.string.pip_deselect_all else R.string.pip_select_all), false) { shown.forEach { it.selected = !allOn }; renderResults() }, LinearLayout.LayoutParams(-2, -2))
        }
        // Guarded: an OutOfMemoryError inside the row loop used to reach the main thread
        // and kill the app (the beta98 report ends in RippleDrawable/LayerDrawable while
        // building a row button). Now: halve the cap, log the numbers, rebuild smaller —
        // the list stays usable and the log says it happened.
        val built = runCatching {
        shown.take(rowCap).forEach { hit ->
            val catalogRow = rowByAuthority[hit.authority.lowercase()] ?: hit.toCatalogEntry(System.currentTimeMillis())
            val probeDisplay = probeDisplayByAuthority[hit.authority]
            val stageBadge = probeDisplay?.stage?.name ?: catalogRow.endpoint.verdict.name
            val riskBadge = when (catalogRow.risk) {
                RelayRisk.CLEAN -> "1101:CLEAN"
                RelayRisk.RISK_1101 -> "1101:RISK"
                RelayRisk.UNKNOWN -> "1101:UNKNOWN"
            }
            // Two lines per row: the address owns the first one outright. The action
            // buttons used to sit beside it on the same line, and on a narrow phone
            // (or a large Display-size setting) their combined width squeezed the
            // address to a few dp — the row looked like «no IP at all» while the
            // flag and the buttons were still there.
            resultsList.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(palette.surfaceElevated2)
                    if (hit.selected) setStroke(dp(1), palette.teal)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { hit.selected = !hit.selected; renderResults() }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
                    addView(text(if (hit.selected) "☑" else "☐", 18f, color = if (hit.selected) palette.teal else palette.textTertiary), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                    addView(text(if (hit.country.length == 2) hit.country.toFlagEmoji() else "🌐", 18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(hit.authority, 14f, bold = true).apply {
                            typeface = CatClientDataTypeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                        })
                        addView(text("${hit.country.ifBlank { ctx.getString(R.string.pip_country_unknown) }} · edge ${hit.colo.ifBlank { "?" }} · ${hit.latencyMs} ms", 12f, color = palette.textSecondary))
                        addView(text("${ctx.getString(R.string.pip_score, hit.score)} · $stageBadge · $riskBadge", 11f, color = palette.textTertiary))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }, LinearLayout.LayoutParams(-1, -2))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    addView(pill(ctx.getString(R.string.pip_choose_route), false) {
                        // «This route» = keep this relay, drop every other selection.
                        hits.forEach { it.selected = (it === hit) }
                        renderResults()
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    if (onUseAsEntry != null) {
                        addView(pill("🎯 ProxyIP", false) {
                            // Never drop even the conventional 443: the scanner result
                            // is an indivisible host+port observation.
                            val verifiedRow = catalogRow.copy(
                                endpoint = catalogRow.endpoint.copy(verdict = EndpointVerdict.VERIFIED),
                            )
                            val action = RelayCatalogAction.useAsProxyIp(verifiedRow)
                            onUseAsEntry.invoke(action?.value ?: hit.authority)
                        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    }
                    addView(pill("⚙️ Config", false) {
                        val verifiedRow = catalogRow.copy(
                            endpoint = catalogRow.endpoint.copy(verdict = EndpointVerdict.VERIFIED),
                        )
                        showRowConfigDialog(verifiedRow)
                    }, LinearLayout.LayoutParams(-2, -2))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        }
        built.exceptionOrNull()?.let { error ->
            if (error is OutOfMemoryError) {
                rowCap = (rowCap / 2).coerceAtLeast(12)
                resultsList.removeAllViews()
                DiagnosticLogger.warn(ctx, "pip.rows.oom", "rowCap=$rowCap hits=${synchronized(hits) { hits.size }}", error)
                renderResults()
            } else {
                DiagnosticLogger.warn(ctx, "pip.rows.failed", error = error)
            }
        }
    }

    private fun showRowConfigDialog(proxyIpRow: RelayCatalogEntry) {
        val base = panelUrl?.trimEnd('/').orEmpty()
        // beta205: no placeholder worker / UUID — a config built from them can never connect. Without a panel of
        // the user the row has nothing real to build on, so say what to do instead of showing a dead link.
        val workerDomain = runCatching { java.net.URI(base).host }.getOrNull().orEmpty()
        val uuid = if (base.isNotBlank()) runCatching { PanelDeploymentStore(activity).uuidFor(base) }.getOrNull().orEmpty() else ""
        if (workerDomain.isBlank() || uuid.isBlank()) {
            Toast.makeText(ctx, R.string.pscan_need_panel, Toast.LENGTH_LONG).show()
            return
        }
        val ingressEndpoint = VerifiedEndpoint(
            host = workerDomain,
            port = 443,
            role = EndpointRole.CLEAN_IP,
            family = EndpointFamily.DOMAIN,
            verdict = EndpointVerdict.VERIFIED,
            checkedAtMs = System.currentTimeMillis(),
            source = "worker-host",
        )
        val ingressRow = RelayCatalogEntry(
            endpoint = ingressEndpoint,
            countryCode = proxyIpRow.countryCode,
            countryName = proxyIpRow.countryName,
            datacenter = proxyIpRow.datacenter,
            asn = proxyIpRow.asn,
            risk = proxyIpRow.risk,
        )
        val config = RelayCatalogConfigFactory.buildForProxyIpRow(
            proxyIpRow = proxyIpRow,
            ingressRow = ingressRow,
            protocol = TunnelProtocol.VLESS,
            credential = uuid,
            workerDomain = workerDomain,
            path = "/?ed=2560",
            fingerprint = "chrome",
            serverName = workerDomain,
        ) ?: return
        val uri = config.encode()
        val clashMap = VerifiedSubscriptionProjector.clash(config)
        val singBoxMap = VerifiedSubscriptionProjector.singBox(config)
        val xrayMap = VerifiedSubscriptionProjector.xray(config)
        val clashJson = VerifiedSubscriptionProjector.toClashJson(listOf(config))
        val singBoxJson = VerifiedSubscriptionProjector.toSingBoxJson(listOf(config))
        val xrayJson = VerifiedSubscriptionProjector.toXrayJson(listOf(config))
        val preview = buildString {
            append("URI:\n").append(uri).append("\n\n")
            append("Clash/Mihomo server: ").append(clashMap["server"]).append(":").append(clashMap["port"]).append("\n")
            append("Sing-box tag: ").append(singBoxMap["tag"]).append("\n")
            append("Xray protocol: ").append(xrayMap["protocol"])
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle("⚙️ ${proxyIpRow.endpoint.authority}")
            .setMessage(preview)
            .setPositiveButton(android.R.string.copy) { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("proxyip-config", uri))
                Toast.makeText(ctx, R.string.pscan_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("JSON") { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val bundle = org.json.JSONObject()
                    .put("clash", org.json.JSONObject(clashJson))
                    .put("singBox", org.json.JSONObject(singBoxJson))
                    .put("xray", org.json.JSONArray(xrayJson))
                cm.setPrimaryClip(ClipData.newPlainText("proxyip-config-json", bundle.toString()))
                Toast.makeText(ctx, R.string.pscan_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Port is NEVER dropped — the panel must dial the relay on exactly the port
    // that passed the handshake (user rule: the port must never change).
    private fun selectedHits(): List<Hit> = visible().filter { it.selected }

    /** «host:port» exactly as it is stored in the panel (IPv6 bracketed). */
    private fun panelKey(hit: Hit): String = hit.authority

    private fun selectedLines(): List<String> = selectedHits().map { panelKey(it) }.distinct()

    /** host:port → CC, so the panel can show the flag the scanner actually measured.
     *  Without this the relays arrive country-less and every 🎯 config loses its flag. */
    private fun selectedCountries(): Map<String, String> = selectedHits()
        .mapNotNull { hit ->
            val cc = hit.country.trim().uppercase()
            if (cc.length == 2) panelKey(hit) to cc else null
        }
        .toMap()

    /** What the panel did with what we sent — never leave the owner guessing.
     *  `ignored` = junk the panel refused; `capped` = valid relays dropped because
     *  the 32-entry list was full. The panel reports both; we show both. */
    internal data class PushOutcome(
        val stored: Int,
        val ignored: List<String>,
        val capped: List<String>,
        val persisted: Boolean = true,
    ) {
        companion object {
            fun from(body: String, requested: List<String>): PushOutcome {
                val snap = PanelSettingsReadBack.parse(body, 0L)
                if (snap == null) {
                    return PushOutcome(0, requested, emptyList(), persisted = false)
                }
                val storedAuthorities = snap.endpoints
                    .filter { it.role != EndpointRole.CLEAN_IP }
                    .map { it.authority.lowercase() }
                    .toSet()
                val normalizedRequested = requested.map { it.substringBefore('#').trim() }.filter { it.isNotBlank() }
                val matched = normalizedRequested.count { it.lowercase() in storedAuthorities }
                val missing = normalizedRequested.filter {
                    it.lowercase() !in storedAuthorities && it !in snap.ignored && it !in snap.capped
                }
                return PushOutcome(
                    stored = matched,
                    ignored = (snap.ignored + missing).distinct(),
                    capped = snap.capped,
                    persisted = snap.kvAvailable,
                )
            }
        }
    }

    private fun copySelected() {
        val lines = selectedLines()
        if (lines.isEmpty()) { Toast.makeText(ctx, R.string.pip_none_selected, Toast.LENGTH_SHORT).show(); return }
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("proxyips", lines.joinToString("\n")))
        Toast.makeText(ctx, R.string.pscan_copied, Toast.LENGTH_SHORT).show()
    }

    /** Hand the selection to Cat Panel: `/?proxyips=a,b` prefills Settings → ProxyIPs (and the list is on the clipboard too). */
    private fun sendToPanel() {
        val lines = selectedLines().take(32)
        if (lines.isEmpty()) { Toast.makeText(ctx, R.string.pip_none_selected, Toast.LENGTH_SHORT).show(); return }
        scope.launch {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("proxyips", lines.joinToString("\n")))
        val base = (pickPanel?.invoke() ?: panelUrl)?.trim()?.trimEnd('/')
        val opened = !base.isNullOrBlank() && runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/?proxyips=" + Uri.encode(lines.joinToString(",")))))
        }.isSuccess
        Toast.makeText(ctx, ctx.getString(if (opened) R.string.pip_sent_panel else R.string.pip_copied_panel, lines.size), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Save straight into the panel: POST /api/login (password → session cookie), GET /api/settings
     * to merge with the existing list, PUT /api/settings {proxyIps}. Nothing is stored on the phone.
     */
    private fun saveDirect() {
        val lines = selectedLines().take(32)
        if (lines.isEmpty()) { Toast.makeText(ctx, R.string.pip_none_selected, Toast.LENGTH_SHORT).show(); return }
        scope.launch {
        val base = (pickPanel?.invoke() ?: panelUrl)?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) { Toast.makeText(ctx, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return@launch }
        val storedPass = runCatching { PanelCredentials.defaultPassword(activity, base) }.getOrNull().orEmpty()
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.pip_password_hint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(palette.textPrimary); setHintTextColor(palette.textTertiary); setPadding(dp(16), dp(12), dp(16), dp(12))
            if (storedPass.isNotBlank()) setText(storedPass)
        }
        val replaceToggle = android.widget.CheckBox(ctx).apply {
            text = ctx.getString(R.string.pip_replace_mode); textSize = 13f
            setTextColor(palette.textPrimary)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0)
            addView(replaceToggle, LinearLayout.LayoutParams(-1, -2))
            addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(ctx.getString(R.string.pip_save_direct))
            .setMessage(base.removePrefix("https://"))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pip_save_direct) { _, _ ->
                val password = input.text.toString()
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { pushToPanel(base, password, lines, replaceToggle.isChecked, selectedCountries()) }
                    }
                    result.onSuccess { out ->
                        val rejected = out.ignored + out.capped
                        val msg = if (rejected.isEmpty()) {
                            ctx.getString(R.string.pip_saved_direct, out.stored)
                        } else {
                            val reason = when {
                                out.ignored.isNotEmpty() && out.capped.isNotEmpty() ->
                                    ctx.getString(R.string.pip_rejected_both, out.ignored.size, out.capped.size)
                                out.capped.isNotEmpty() -> ctx.getString(R.string.pip_rejected_capped, out.capped.size)
                                else -> ctx.getString(R.string.pip_rejected_ignored, out.ignored.size)
                            }
                            ctx.getString(R.string.pip_saved_partial, out.stored, reason, rejected.take(3).joinToString(" · "))
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(ctx, ctx.getString(R.string.pip_save_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
        }
    }

    private fun pushToPanel(
        base: String,
        password: String,
        lines: List<String>,
        replace: Boolean = false,
        countries: Map<String, String> = emptyMap(),
    ): PushOutcome {
        fun call(path: String, method: String, body: String?, cookie: String?): Pair<HttpURLConnection, String> {
            val conn = URL("$base$path").openConnection() as HttpURLConnection
            conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
            val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            return conn to stream.bufferedReader().readText()
        }
        val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(activity, base, password).toString(), null)
        if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(ctx.getString(R.string.pip_wrong_password))
        val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
        login.disconnect()
        if (replace) {
            // Replace mode: the panel ProxyIP list becomes EXACTLY this selection.
            val body = org.json.JSONObject()
                .put("proxyIps", org.json.JSONArray(lines))
                .put("proxyCountries", org.json.JSONObject().apply { countries.forEach { (k, v) -> put(k, v) } })
            val (put, putBody) = call("/api/settings", "PUT", body.toString(), cookie)
            val ok = put.responseCode == 200 && org.json.JSONObject(putBody).optBoolean("ok")
            put.disconnect()
            if (!ok) throw IllegalStateException("HTTP ${put.responseCode}")
            val outcome = PushOutcome.from(putBody, lines)
            if (!outcome.persisted) throw IllegalStateException("NO_KV")
            return outcome
        }
        val (get, getBody) = call("/api/settings", "GET", null, cookie)
        val existing = ArrayList<String>()
        val existingCountries = LinkedHashMap<String, String>()
        if (get.responseCode == 200) {
            val settingsObj = org.json.JSONObject(getBody).optJSONObject("settings")
            val arr = settingsObj?.optJSONArray("proxyIps")
            if (arr != null) for (i in 0 until arr.length()) existing += arr.optString(i)
            // Keep the countries already stored — merging proxyIps alone would wipe them.
            val cobj = settingsObj?.optJSONObject("proxyCountries")
            if (cobj != null) for (k in cobj.keys()) existingCountries[k] = cobj.optString(k)
            // Local-only recovery snapshot: lets the app rebuild the panel elsewhere
            // if Cloudflare suspends this worker after an abuse report (Error 1101).
            if (settingsObj != null) PanelBackup.save(ctx, base, settingsObj.toString(), settingsObj.optString("panelPath"))
        }
        get.disconnect()
        val merged = (lines + existing.filter { it !in lines }).filter { it.isNotBlank() }.distinct().take(32)
        val mergedCountries = LinkedHashMap<String, String>().apply {
            putAll(existingCountries)
            putAll(countries.filterKeys { it in merged })
        }
        val body = org.json.JSONObject()
            .put("proxyIps", org.json.JSONArray(merged))
            .put("proxyCountries", org.json.JSONObject().apply { mergedCountries.forEach { (k, v) -> put(k, v) } })
        val (put, putBody) = call("/api/settings", "PUT", body.toString(), cookie)
        val ok = put.responseCode == 200 && org.json.JSONObject(putBody).optBoolean("ok")
        put.disconnect()
        if (!ok) throw IllegalStateException("HTTP ${put.responseCode}")
        val outcome = PushOutcome.from(putBody, merged)
        if (!outcome.persisted) throw IllegalStateException("NO_KV")
        return outcome
    }

    /**
     * Pull the panel's own ProxyIP pool (GET /api/prepos — owner-only) into the
     * manual box. Nothing is scanned yet: the owner sees exactly what arrived and
     * can delete lines before spending a single probe on them.
     */
    private fun pullFromPanel() {
        // pickPanel is suspend (it may read the deployment store) — resolve it off
        // the main thread, then build the dialog on Main. Never runBlocking here.
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { pickPanel?.invoke() }.getOrNull() ?: panelUrl
            }?.trim()?.trimEnd('/')
            withContext(Dispatchers.Main) {
                if (resolved.isNullOrBlank()) { Toast.makeText(ctx, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return@withContext }
                showPullDialog(resolved)
            }
        }
    }

    private fun showPullDialog(resolved: String) {
        val storedPass = runCatching { PanelCredentials.defaultPassword(activity, resolved) }.getOrNull().orEmpty()
        val input = EditText(ctx).apply {
            hint = ctx.getString(R.string.pip_password_hint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(palette.textPrimary); setHintTextColor(palette.textTertiary); setPadding(dp(16), dp(12), dp(16), dp(12))
            if (storedPass.isNotBlank()) setText(storedPass)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(ctx.getString(R.string.pip_pull_panel))
            .setMessage(resolved.removePrefix("https://"))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pip_pull_panel) { _, _ ->
                val password = input.text.toString()
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) { runCatching { fetchPanelPool(resolved, password) } }
                    outcome.onSuccess { lines ->
                        if (lines.isEmpty()) {
                            Toast.makeText(ctx, ctx.getString(R.string.pip_pull_empty), Toast.LENGTH_LONG).show()
                        } else {
                            val existing = customInput.text.toString().trim()
                            customInput.setText(if (existing.isEmpty()) lines.joinToString("\n") else existing + "\n" + lines.joinToString("\n"))
                            Toast.makeText(ctx, ctx.getString(R.string.pip_pull_ok, lines.size), Toast.LENGTH_LONG).show()
                        }
                    }.onFailure { e ->
                        Toast.makeText(ctx, ctx.getString(R.string.pip_pull_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun fetchPanelPool(base: String, password: String): List<String> {
        fun call(path: String, method: String, body: String?, cookie: String?): Pair<HttpURLConnection, String> {
            val conn = URL("$base$path").openConnection() as HttpURLConnection
            conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
            val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            return conn to stream.bufferedReader().readText()
        }
        val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(activity, base, password).toString(), null)
        if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(ctx.getString(R.string.pip_wrong_password))
        val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
        login.disconnect()
        val (get, getBody) = call("/api/prepos", "GET", null, cookie)
        val code = get.responseCode
        get.disconnect()
        if (code != 200) throw IllegalStateException("HTTP $code")
        val arr = org.json.JSONObject(getBody).optJSONArray("ips") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) { val v = arr.optString(i).trim(); if (v.isNotEmpty()) out += v }
        return out.distinct().take(400)
    }

    private fun clear() {
        job?.cancel(); job = null
        synchronized(hits) { hits.clear() }
        VerifiedProxyIpStore(ctx).clear()
        checked.set(0); total = 0; filterCountry = null; filterDatacenter = null
        probeDisplayByAuthority.clear()
        riskByAuthority.clear()
        lastListCount = -1; lastListAt = 0L
        renderProgress(); renderResults()
    }

    /** Heap numbers, so a future «the app closed» report carries something to read. */
    private fun logMemory(event: String) {
        val runtime = Runtime.getRuntime()
        DiagnosticLogger.info(
            ctx,
            event,
            "maxMb=${runtime.maxMemory() / 1_048_576} totalMb=${runtime.totalMemory() / 1_048_576} " +
                "freeMb=${runtime.freeMemory() / 1_048_576} nativeMb=${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576}",
        )
    }

    private fun stop() {
        job?.cancel(); job = null
        VerifiedProxyIpStore(ctx).saveHits(synchronized(hits) { hits.toList() })
        renderProgress()
        // The hits stay on screen after ⏹, so the country pass must still run for them
        // (it used to happen only after a full scan finished — that is the path that
        // left the list on «همه کشورها 🌐 نامشخص»).
        kickCountryPass()
    }

    /** Resolves countries for whatever the probe could not name, off the UI thread. */
    private fun kickCountryPass() {
        val pending = synchronized(hits) { hits.filter { it.country.length != 2 } }
        if (pending.isEmpty()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { geolocate() }
                VerifiedProxyIpStore(ctx).saveHits(synchronized(hits) { hits.toList() })
                renderResults()
            }.onFailure { error -> DiagnosticLogger.warn(ctx, "proxyip.country.failed", error = error) }
        }
    }

    // ------------------------------------------------------------------ engine
    private fun start() {
        clear()
        logMemory("pip.mem.start")
        val custom = customInput.text.toString()
        val chosen = sources.filterValues { it }.keys.toList()
        job = scope.launch {
            progressText.text = ctx.getString(R.string.pscan_collecting)
            val candidates = withContext(Dispatchers.IO) { collect(chosen, custom) }
            total = candidates.size
            renderProgress()
            if (candidates.isEmpty()) { Toast.makeText(ctx, R.string.pscan_no_candidates, Toast.LENGTH_SHORT).show(); job = null; renderProgress(); return@launch }
            val gate = Semaphore(threads)
            var lastUi = 0L
            withContext(Dispatchers.IO) {
                candidates.map { (host, port) ->
                    async {
                        gate.withPermit {
                            ensureActive()
                            val hit = probe(host, port)
                            checked.incrementAndGet()
                            if (hit != null) synchronized(hits) { hits += hit }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi > 300) { lastUi = now; withContext(Dispatchers.Main) { renderProgress() } }
                            // The row list is the expensive part (one view set per hit):
                            // rebuilding it on every tick was the allocation storm behind
                            // the OOM. Rebuild only when the hit count actually changed.
                            val hitCount = synchronized(hits) { hits.size }
                            if (now - lastListAt > 1_500L && hitCount != lastListCount) {
                                withContext(Dispatchers.Main) { renderResults() }
                            }
                        }
                    }
                }.awaitAll()
                geolocate()
            }
            VerifiedProxyIpStore(ctx).saveHits(synchronized(hits) { hits.toList() })
            job = null
            renderProgress(); renderResults()
            logMemory("pip.mem.end")
        }
    }

    private fun collect(chosen: List<String>, custom: String): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        val notes = ArrayList<String>()
        fun addHost(raw: String) {
            var line = raw.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('#').substringBefore(' ')
            if (line.isEmpty()) return
            var port = 443
            var explicit = false
            Regex("^(.*):(\\d+)$").find(line)?.takeIf { line.count { c -> c == ':' } == 1 || line.startsWith("[") }?.let { m -> line = m.groupValues[1].trim('[', ']'); port = m.groupValues[2].toInt(); explicit = true }
            // An explicit :port survives untouched; a bare host is probed on every
            // selected port — the result KEEPS exactly the port that answered.
            fun put(h: String) { if (explicit) { out += h to port } else selectedPorts.ifEmpty { linkedSetOf(443) }.forEach { out += h to it } }
            val cidr = Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)$").find(line)
            if (cidr != null) {
                val bits = cidr.groupValues[2].toInt().coerceIn(24, 32)
                val base = ipToLong(cidr.groupValues[1]) and (0xFFFFFFFFL shl (32 - bits))
                for (i in 0 until (1L shl (32 - bits))) put(longToIp(base + i))
                return
            }
            if (Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(line) || line.contains(':')) { put(line); return }
            if (line.contains('.')) {
                // Domain → every A/AAAA record (ProxyIP domains rotate many relays behind one name).
                // A name that will not resolve is REPORTED: on a filtered network this is
                // the difference between «nothing found» and «this list is unreachable».
                val records = runCatching { InetAddress.getAllByName(line) }.getOrNull()
                if (records.isNullOrEmpty()) notes += "$line ✗" else records.forEach { put(it.hostAddress.orEmpty()) }
            }
        }
        custom.lines().forEach(::addHost)
        // Offline seeds first: the run already has candidates even if every network
        // source below hangs, and it makes the first results appear immediately.
        val ordered = chosen.sortedBy { if (it == OFFLINE_SEEDS) 0 else 1 }
        ordered.forEachIndexed { index, source ->
            val label = if (source == OFFLINE_SEEDS) null else source.substringAfter("//").substringBefore('/')
            activity.runOnUiThread {
                runCatching { progressText.text = ctx.getString(R.string.pip_collecting_source, index + 1, ordered.size) }
            }
            val before = out.size
            // Each source runs on a throwaway thread with a deadline: a poisoned
            // resolver used to leave the page on «در حال جمعآوری کاندیدها…» forever.
            val finished = runBounded(SOURCE_TIMEOUT_MS) {
                if (source == OFFLINE_SEEDS) {
                    CommunityIpLibrary.proxyIpSeeds.forEach(::addHost)
                } else if (source.startsWith("http")) {
                    val conn = URL(source).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8_000; conn.readTimeout = 10_000
                    val code = conn.responseCode
                    if (code >= 400) throw IllegalStateException("HTTP $code")
                    conn.inputStream.bufferedReader().useLines { lines -> lines.take(300).forEach(::addHost) }
                    conn.disconnect()
                } else addHost(source)
            }
            if (label == null) return@forEachIndexed
            when {
                !finished -> notes += "$label ⏱"
                out.size == before -> notes += "$label ✗"
            }
        }
        sourceNotes = notes.distinct().take(6)
        return out.filter { (h, _) -> h.isNotBlank() && !IpScanner.isCloudflareAddress(h) }.take(2000)
    }

    /**
     * Runs [block] on a throwaway daemon thread and gives up after [timeoutMs].
     * `InetAddress.getAllByName` cannot be interrupted, so the only way to keep a
     * poisoned resolver from freezing the whole run is to stop waiting for it.
     */
    private fun runBounded(timeoutMs: Long, block: () -> Unit): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            runCatching { block() }.onSuccess { ok.set(true) }
            done.countDown()
        }.apply { isDaemon = true; name = "pip-collect" }.start()
        return if (done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) ok.get() else false
    }

    /** TLS to the relay with a Cloudflare SNI and ask the edge for its trace through it. */
    private fun probe(host: String, port: Int): Hit? = runCatching {
        val t0 = SystemClock.elapsedRealtime()
        Socket().use { raw ->
            protectOutboundSocket(raw) // ProxyIP health is about THIS network, never the running tunnel
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = (SSLContext.getDefault().socketFactory.createSocket(raw, TRACE_HOST, port, true) as SSLSocket)
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(TRACE_HOST)) }
            ssl.startHandshake()
            ssl.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_HOST\r\nUser-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\nConnection: close\r\n\r\n".toByteArray())
            ssl.outputStream.flush()
            val body = ssl.inputStream.bufferedReader().readText()
            val latency = SystemClock.elapsedRealtime() - t0
            if (!body.contains("h=$TRACE_HOST") || !body.contains("colo=")) return null
            val loc = Regex("(?:^|\\n)loc=([A-Z]{2})").find(body)?.groupValues?.get(1).orEmpty()
            val colo = Regex("(?:^|\\n)colo=([A-Z]{3})").find(body)?.groupValues?.get(1).orEmpty()
            // Country at probe time: `loc=` first, then the offline colo→country table
            // that the rest of the app already uses for edge metadata. Both are local,
            // so a hit is grouped correctly DURING the scan instead of waiting for the
            // ip-api batch at the end (which a CF-only network path refuses).
            Hit(host, port, loc.ifEmpty { EdgeLocationCatalog.countryForColo(colo).orEmpty() }, colo, latency)
        }
    }.getOrNull()

    /**
     * Country for hits the trace could not name. The offline colo table runs first
     * (instant, no traffic), then ONE ip-api batch for whatever is still unknown —
     * a failed batch leaves those rows on «نامشخص» and is logged, never guessed.
     */
    private fun geolocate() {
        val pending = synchronized(hits) { hits.filter { it.country.length != 2 } }
        if (pending.isEmpty()) return
        pending.forEach { hit -> EdgeLocationCatalog.countryForColo(hit.colo)?.let { hit.country = it } }
        val rest = pending.filter { it.country.length != 2 }
        if (rest.isEmpty()) return
        // The worker relay first (works on a filtered line), then the public
        // endpoints — see RelayCountryLookup's order-of-resolvers note.
        val worker = RelayCountryLookup.deployedWorkerHost(ctx)
        val found = RelayCountryLookup.resolve(ctx, rest.map { it.host }, worker)
        rest.forEach { hit -> found[hit.host]?.let { hit.country = it } }
    }

    private fun ipToLong(ip: String): Long = ip.split('.').fold(0L) { acc, s -> (acc shl 8) or (s.toLong() and 0xFF) }
    private fun longToIp(v: Long): String = "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    private companion object {
        const val TRACE_HOST = "speed.cloudflare.com"
        /** Per-source deadline: slow enough for a real fetch, short enough to stay usable. */
        const val SOURCE_TIMEOUT_MS = 6_000L
        /** Sentinel source key: relays carried inside the APK, no DNS and no HTTP involved. */
        const val OFFLINE_SEEDS = "📦 کتابخانهٔ آفلاین (بدون DNS)"
    }
}

/** Persists ProxyIP hits scanned on the device with their exact `host:port` so
 * route/ProxyIP failover and subscription optimization can use them without
 * losing non-standard ports or re-scanning after closing the dialog. */
class VerifiedProxyIpStore(context: Context) {
    private val prefs = context.getSharedPreferences("cat_client_verified_proxyips", Context.MODE_PRIVATE)

    fun saveHits(hits: List<ProxyIpScannerPage.Hit>) {
        val arr = org.json.JSONArray()
        hits.distinctBy { it.authority.lowercase() }.take(100).forEach { hit ->
            arr.put(
                org.json.JSONObject()
                    .put("host", hit.host.removePrefix("[").removeSuffix("]"))
                    .put("port", hit.port)
                    .put("country", hit.country)
                    .put("colo", hit.colo)
                    .put("latencyMs", hit.latencyMs)
                    .put("selected", hit.selected),
            )
        }
        prefs.edit().putString("hits", arr.toString()).putLong("savedAtMs", System.currentTimeMillis()).apply()
    }

    fun loadHits(): List<ProxyIpScannerPage.Hit> {
        val raw = prefs.getString("hits", null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val host = o.optString("host").trim()
                    val port = o.optInt("port", 0)
                    if (host.isBlank() || port !in 1..65535) continue
                    add(
                        ProxyIpScannerPage.Hit(
                            host = host,
                            port = port,
                            country = o.optString("country"),
                            colo = o.optString("colo"),
                            latencyMs = o.optLong("latencyMs", 100L),
                            selected = o.optBoolean("selected", true),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    internal fun verifiedEndpoints(nowMs: Long = System.currentTimeMillis()): List<VerifiedEndpoint> {
        val savedAt = prefs.getLong("savedAtMs", nowMs).takeIf { it > 0L } ?: nowMs
        return loadHits().fold(emptyList()) { acc, hit ->
            val cleanHost = hit.host.removePrefix("[").removeSuffix("]")
            val family = when {
                cleanHost.contains(':') -> EndpointFamily.IPV6
                Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$").matches(cleanHost) -> EndpointFamily.IPV4
                else -> EndpointFamily.DOMAIN
            }
            val candidate = VerifiedEndpoint(
                host = cleanHost,
                port = hit.port,
                role = EndpointRole.PROXY_IP,
                family = family,
                verdict = EndpointVerdict.VERIFIED,
                checkedAtMs = savedAt,
                latencyMs = hit.latencyMs,
                countryCode = hit.country.ifBlank { null },
                source = "device-proxyip-scan",
            )
            VerifiedEndpointRegistry.save(acc, candidate).first
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

