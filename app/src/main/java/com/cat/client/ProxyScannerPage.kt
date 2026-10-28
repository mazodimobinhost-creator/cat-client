package com.cat.client

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Public proxy scanner (like the "Proxy Panel Scanner" web tool): collects candidates from public
 * lists (monosans / proxifly via jsDelivr), a pasted list, or CIDR ranges × a port set; probes
 * each with a real protocol handshake (SOCKS5 greeting + CONNECT, SOCKS4 CONNECT, HTTP CONNECT)
 * under a thread/timeout budget; geolocates the survivors as they arrive (a Cloudflare
 * trace through the proxy itself, then the database chain); lets you filter by
 * protocol/country, copy/export TXT/JSON, or import a SOCKS5 hit straight into Cat Client.
 */
class ProxyScannerPage(
    private val activity: Activity,
    private val palette: CatClientPalette,
    private val scope: CoroutineScope,
    private val panelBaseUrl: String? = null,
    private val pickPanel: (suspend () -> String?)? = null,
    private val importLink: (String) -> Unit,
    private val connectDirect: ((Hit) -> Unit)? = null,
    private val connectPool: ((List<Hit>) -> Unit)? = null,
    private val startWithSocks5Only: Boolean = false,
) {
    enum class Proto(val label: String) { SOCKS5("SOCKS5"), SOCKS4("SOCKS4"), HTTP("HTTP") }
    data class Candidate(val host: String, val port: Int, val proto: Proto?, val countryHint: String = "")
    data class Hit(val host: String, val port: Int, val proto: Proto, val latencyMs: Long, var country: String = "") {
        val authority: String
            get() = if (host.contains(':')) "[${host.removePrefix("[").removeSuffix("]")}]:$port" else "$host:$port"

        internal fun toCatalogEntry(checkedAtMs: Long, datacenter: String = "Unknown"): RelayCatalogEntry {
            val endpoint = VerifiedEndpointAdapters.fromProxyScan(this, checkedAtMs)
            return RelayCatalogEntry(
                endpoint = endpoint,
                countryCode = country.ifBlank { "??" },
                countryName = country.ifBlank { "Unknown" },
                datacenter = datacenter.ifBlank { "Unknown" },
                asn = null,
                risk = RelayRisk.UNKNOWN,
            )
        }
    }

    private val ctx: Context = activity
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private var job: Job? = null
    private val hits = ArrayList<Hit>()
    private val checked = AtomicInteger(); private val open = AtomicInteger(); private val openNotProxy = AtomicInteger()
    private var total = 0
    private var filterProto: Proto? = null
    private var filterCountry: String? = null
    private var addressFilter: AddressFilter = AddressFilter.ALL
    private var catalogController = RelayCatalogController(emptyList())
    private val probeDisplayByAuthority = java.util.concurrent.ConcurrentHashMap<String, RelayProbeDisplay>()

    private lateinit var countChecked: TextView; private lateinit var countOpen: TextView
    private lateinit var countHealthy: TextView; private lateinit var countOpenNot: TextView
    private lateinit var progressText: TextView
    private lateinit var sourceStatusText: TextView
    /** Where the countries came from («through the proxy» / database / unknown). */
    private lateinit var countryStatusText: TextView
    /** Public lists that answered nothing this run (DNS/HTTP), shown under the progress line. */
    private var sourceNotes: List<String> = emptyList()
    /**
     * Country resolution is incremental, not «once at the end»: hosts already asked
     * about are remembered (never asked twice), one pass runs at a time, and the note line
     * says how many countries were MEASURED through the proxy versus taken from a database
     * — so the next «🌐 نامشخص» report names the culprit instead of guessing.
     */
    private val geoTried = java.util.Collections.synchronizedSet(HashSet<String>())
    private val geoBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Result rows rebuilt per pass; halved automatically if a render ever hits an OOM. */
    private var rowCap = 200
    private var lastListAt = 0L
    private var lastListCount = -1

    @Volatile
    private var lastGeoKick = 0L
    private val geoViaProxy = AtomicInteger()
    private val geoViaDatabase = AtomicInteger()
    private lateinit var resultsList: LinearLayout
    private lateinit var filterChips: LinearLayout
    private lateinit var protoChips: LinearLayout
    private lateinit var countryChips: LinearLayout
    private lateinit var startButton: MaterialButton
    private val sourceToggles = LinkedHashMap<String, Pair<String, Proto?>>() // label -> (url, proto)
    private val sourceSelected = HashSet<String>()
    private lateinit var customInput: EditText
    private lateinit var portsInput: EditText
    private var threads = 100
    private var timeoutMs = 5_000
    private var activeDialog: Dialog? = null

    fun show() {
        if (startWithSocks5Only) filterProto = Proto.SOCKS5
        val persisted = VerifiedRelayStore(ctx).loadHits()
        if (persisted.isNotEmpty() && hits.isEmpty()) {
            synchronized(hits) { hits.addAll(persisted) }
        }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        activeDialog = dialog
        dialog.setContentView(build())
        dialog.window?.setBackgroundDrawable(ColorDrawable(palette.background))
        dialog.setOnDismissListener { job?.cancel(); activeDialog = null }
        dialog.show()
        val autoRefresh = ctx.getSharedPreferences("cat_verified_relays", Context.MODE_PRIVATE)
            .getBoolean("auto_refresh_on_open", true)
        if ((startWithSocks5Only || hits.isEmpty()) && autoRefresh) {
            start()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun text(value: String, size: Float, bold: Boolean = false, color: Int = palette.textPrimary) = TextView(ctx).apply {
        text = value; textSize = size; typeface = if (bold) CatClientBodyBoldTypeface else CatClientBodyTypeface
        setTextColor(color); includeFontPadding = false
    }

    private fun card() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(22).toFloat(); setColor(palette.surfaceElevated1) }
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; typeface = CatClientBodyBoldTypeface
        setTextColor(if (selected) palette.onAccent else palette.textPrimary)
        setPadding(dp(14), dp(8), dp(14), dp(8)); includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(20).toFloat()
            setColor(if (selected) palette.teal else palette.surfaceElevated2)
        }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun chipRow(vararg chips: View) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            chips.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
        })
    }

    private fun input(hint: String, lines: Int) = EditText(ctx).apply {
        this.hint = hint; textSize = 13f; typeface = CatClientDataTypeface
        setTextColor(palette.textPrimary); setHintTextColor(palette.textTertiary)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = lines; maxLines = lines + 4; gravity = Gravity.TOP or Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat(); setColor(palette.surfaceElevated2) }
    }

    private fun counter(label: String): Pair<LinearLayout, TextView> {
        val v = text("0", 20f, bold = true)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(16).toFloat(); setColor(palette.surfaceElevated2) }
            addView(v); addView(text(label, 11f, color = palette.textSecondary).apply { gravity = Gravity.CENTER })
        }
        return box to v
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = MaterialButton(ctx).apply {
        text = label; isAllCaps = false; textSize = 13f; typeface = CatClientBodyBoldTypeface
        cornerRadius = dp(22); insetTop = 0; insetBottom = 0; minHeight = dp(44)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (filled) palette.teal else palette.surfaceElevated2)
        setTextColor(if (filled) palette.onAccent else palette.textPrimary)
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val isFarsi = ctx.resources.configuration.locales[0]?.language == "fa"
        sourceToggles["vakhov · Fresh SOCKS5"] = "https://cdn.jsdelivr.net/gh/vakhov/fresh-proxy-list@master/socks5.txt" to Proto.SOCKS5
        sourceToggles["roosterkid · SOCKS5"] = "https://cdn.jsdelivr.net/gh/roosterkid/openproxylist@main/SOCKS5_RAW.txt" to Proto.SOCKS5
        sourceToggles["monosans · SOCKS5"] = "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks5.txt" to Proto.SOCKS5
        sourceToggles["hideip.me · SOCKS5 (Geo)"] = "https://cdn.jsdelivr.net/gh/zloi-user/hideip.me@main/socks5.txt" to Proto.SOCKS5
        sourceToggles["TheSpeedX · SOCKS5"] = "https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/socks5.txt" to Proto.SOCKS5
        sourceToggles["proxifly · SOCKS5"] = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt" to Proto.SOCKS5
        sourceToggles["⚡ Built-in SOCKS5 Seeds"] = "builtin://socks5-seeds" to Proto.SOCKS5
        sourceToggles["monosans · SOCKS4"] = "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks4.txt" to Proto.SOCKS4
        sourceToggles["monosans · HTTP"] = "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/http.txt" to Proto.HTTP
        sourceToggles["proxifly · all"] = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/all/data.txt" to null
        sourceSelected += listOf(
            "vakhov · Fresh SOCKS5",
            "roosterkid · SOCKS5",
            "monosans · SOCKS5",
            "hideip.me · SOCKS5 (Geo)",
            "⚡ Built-in SOCKS5 Seeds",
        )

        val sourcesRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        fun renderSources() {
            sourcesRow.removeAllViews()
            sourceToggles.keys.forEach { key ->
                sourcesRow.addView(chip(key, key in sourceSelected) {
                    if (!sourceSelected.remove(key)) sourceSelected += key
                    renderSources()
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            }
        }
        renderSources()
        customInput = input(ctx.getString(R.string.pscan_custom_hint), 3)
        portsInput = input(ctx.getString(R.string.pscan_ports_hint), 1).apply { setText("1080, 1081, 1082, 1083, 8080, 3128, 14111") }

        val threadsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        val timeoutRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        fun renderBudget() {
            threadsRow.removeAllViews(); timeoutRow.removeAllViews()
            threadsRow.addView(text(ctx.getString(R.string.pscan_threads), 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10); gravity = Gravity.CENTER_VERTICAL })
            listOf(50, 100, 200).forEach { n -> threadsRow.addView(chip("$n", threads == n) { threads = n; renderBudget() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
            timeoutRow.addView(text(ctx.getString(R.string.pscan_timeout), 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10); gravity = Gravity.CENTER_VERTICAL })
            listOf(3, 5, 8).forEach { s -> timeoutRow.addView(chip("${s}s", timeoutMs == s * 1000) { timeoutMs = s * 1000; renderBudget() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        }
        renderBudget()

        val prefs = ctx.getSharedPreferences("cat_verified_relays", Context.MODE_PRIVATE)
        var autoRefreshOnOpen = prefs.getBoolean("auto_refresh_on_open", true)
        val autoRefreshHolder = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        fun renderAutoRefreshChip() {
            autoRefreshHolder.removeAllViews()
            val lbl = if (isFarsi) {
                if (autoRefreshOnOpen) "🔄 آپدیت خودکار لیست ساکس۵ در بدو ورود: روشن ✓" else "🔄 آپدیت خودکار لیست ساکس۵ در بدو ورود: خاموش"
            } else {
                if (autoRefreshOnOpen) "🔄 Auto-update SOCKS5 on open: ON ✓" else "🔄 Auto-update SOCKS5 on open: OFF"
            }
            autoRefreshHolder.addView(chip(lbl, autoRefreshOnOpen) {
                autoRefreshOnOpen = !autoRefreshOnOpen
                prefs.edit().putBoolean("auto_refresh_on_open", autoRefreshOnOpen).apply()
                renderAutoRefreshChip()
            })
        }
        renderAutoRefreshChip()

        val inputsCard = card().apply {
            addView(text(ctx.getString(R.string.pscan_sources), 15f, bold = true))
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(sourcesRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(autoRefreshHolder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(text(ctx.getString(R.string.pscan_custom_title), 13f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(customInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(ctx.getString(R.string.pscan_ports_title), 13f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(portsInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(threadsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(timeoutRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        startButton = button(ctx.getString(R.string.pscan_start), filled = true) { if (job?.isActive == true) stop() else start() }
        val poolConnectButton = button(
            if (isFarsi) "🚀 اتصال هوشمند VPN به همه ساکس۵های سالم (Auto-Failover)"
            else "🚀 Connect VPN to Healthy SOCKS5 Pool (Auto-Failover)",
            filled = true,
        ) {
            val socks5Hits = synchronized(hits) { hits.filter { it.proto == Proto.SOCKS5 }.sortedBy { it.latencyMs } }
            if (socks5Hits.isEmpty()) {
                Toast.makeText(
                    ctx,
                    if (isFarsi) "ابتدا اسکن/آپدیت ساکس۵ را بزنید تا ساکس‌های سالم پیدا شوند" else "Run SOCKS5 scan/update first to find healthy servers",
                    Toast.LENGTH_SHORT,
                ).show()
            } else if (connectPool != null) {
                activeDialog?.dismiss()
                connectPool.invoke(socks5Hits)
            } else {
                ProxyScanImport.uri(socks5Hits.first(), System.currentTimeMillis())?.let { uri ->
                    activeDialog?.dismiss()
                    importLink("$uri#${socks5Hits.first().country.ifBlank { "SOCKS5" }} ${socks5Hits.first().host}")
                }
            }
        }
        progressText = text(ctx.getString(R.string.pscan_idle), 12f, color = palette.textSecondary)
        // Names the public lists that answered nothing: on a filtered network the
        // default jsdelivr feeds simply cannot be fetched, and that is the whole
        // reason a run finds no proxies at all.
        sourceStatusText = text("", 11f, color = palette.textTertiary).apply { visibility = View.GONE }
        // «کشور: …» — always visible on the scan card: which pass answered, and how
        // many rows are still unknown (the number the owner has been reading as a filter).
        countryStatusText = text("", 11f, color = palette.textTertiary).apply { visibility = View.GONE }
        val (c1, v1) = counter(ctx.getString(R.string.pscan_checked)); countChecked = v1
        val (c2, v2) = counter(ctx.getString(R.string.pscan_open)); countOpen = v2
        val (c3, v3) = counter(ctx.getString(R.string.pscan_healthy)); countHealthy = v3
        val (c4, v4) = counter(ctx.getString(R.string.pscan_open_not_proxy)); countOpenNot = v4
        val countersRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            listOf(c1, c2, c3, c4).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }) }
        }
        val scanCard = card().apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE; gravity = Gravity.CENTER_VERTICAL
                addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button(ctx.getString(R.string.pscan_clear), filled = false) { clear() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            })
            addView(poolConnectButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(sourceStatusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(countryStatusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(countersRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        filterChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        protoChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        countryChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        resultsList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        val exportRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(button(ctx.getString(R.string.pscan_copy), false) { copyAll() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(button("TXT ⬇", false) { share(false) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(button("JSON ⬇", false) { share(true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(button("☁ Panel", true) { pushRelaysToPanel(visibleHits()) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        }
        val resultsCard = card().apply {
            addView(text(ctx.getString(R.string.pscan_results), 15f, bold = true))
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(filterChips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(protoChips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(countryChips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(resultsList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(exportRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        renderResults()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(28), dp(20), dp(28))
            addView(text(ctx.getString(R.string.pscan_title), 28f).apply { typeface = CatClientDisplayTypeface })
            addView(text(ctx.getString(R.string.pscan_subtitle), 14f, color = palette.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(inputsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            addView(scanCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(resultsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        return ScrollView(ctx).apply { addView(column) }
    }

    private fun renderCounters() {
        if (::sourceStatusText.isInitialized) {
            sourceStatusText.text = if (sourceNotes.isEmpty()) "" else ctx.getString(R.string.pip_sources_dead, sourceNotes.joinToString(" · "))
            sourceStatusText.visibility = if (sourceNotes.isEmpty()) View.GONE else View.VISIBLE
        }
        countChecked.text = checked.get().toString(); countOpen.text = open.get().toString()
        countHealthy.text = hits.size.toString(); countOpenNot.text = openNotProxy.get().toString()
        progressText.text = if (job?.isActive == true) ctx.getString(R.string.pscan_scanning, checked.get(), total) else if (total > 0) ctx.getString(R.string.pscan_done, hits.size) else ctx.getString(R.string.pscan_idle)
        startButton.text = ctx.getString(if (job?.isActive == true) R.string.pscan_stop else R.string.pscan_start)
    }

    private fun syncCatalogController(): List<RelayCatalogEntry> {
        val nowMs = System.currentTimeMillis()
        val snapshot = synchronized(hits) { hits.toList() }
        val entries = snapshot.map { h -> h.toCatalogEntry(nowMs) }
        catalogController = RelayCatalogController(entries)
        catalogController.updateFilters(
            address = addressFilter,
            exclude1101 = false,
            query = "",
        )
        val cc = filterCountry
        if (cc != null) {
            catalogController.selectCountry(cc.ifBlank { "??" })
        }
        return catalogController.servers()
    }

    private fun visibleHits(): List<Hit> {
        val allowed = syncCatalogController().map { it.endpoint.authority.lowercase() }.toSet()
        return synchronized(hits) { hits.toList() }.filter { h ->
            (filterProto == null || h.proto == filterProto) &&
                (filterCountry == null || h.country == filterCountry) &&
                h.authority.lowercase() in allowed
        }.sortedBy { it.latencyMs }
    }

    private fun runTopFiveProbe() {
        val plan = catalogController.topFiveProbePlan()
        if (plan.isEmpty()) {
            Toast.makeText(ctx, R.string.pscan_none_yet, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, "⚡ Testing top ${plan.size} relays…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val nowMs = System.currentTimeMillis()
            val networkId = runCatching { NetworkContext.key(ctx) }.getOrDefault("active-net")
            withContext(Dispatchers.IO) {
                plan.forEach { candidate ->
                    val proto = when (candidate.kind) {
                        PortProbeKind.SOCKS5 -> Proto.SOCKS5
                        PortProbeKind.SOCKS4 -> Proto.SOCKS4
                        else -> Proto.HTTP
                    }
                    val hit = probe(Candidate(candidate.host, candidate.port, proto))
                    val evidence = if (hit != null) {
                        EndpointProbeEvidence(
                            tcpConnected = true,
                            protocolHandshakeCompleted = true,
                            runtimeCompleted = true,
                            latencyMs = hit.latencyMs,
                        )
                    } else {
                        EndpointProbeEvidence(tcpConnected = false, error = "probe-failed")
                    }
                    val authority = if (candidate.host.contains(':')) {
                        "[${candidate.host.removePrefix("[").removeSuffix("]")}]" + ":${candidate.port}"
                    } else {
                        "${candidate.host}:${candidate.port}"
                    }
                    probeDisplayByAuthority[authority] = RelayProbeDisplayPolicy.from(candidate.kind, evidence)
                    catalogController.recordRuntimeResult(
                        candidate = candidate,
                        evidence = evidence,
                        checkedAtMs = nowMs,
                        workerOutcome = if (hit != null) {
                            WorkerRuntimeOutcome(attempted = true, httpStatus = 200, runtimeCompleted = true)
                        } else {
                            WorkerRuntimeOutcome(attempted = true, runtimeCompleted = false)
                        },
                        networkId = networkId,
                    )
                }
            }
            VerifiedRelayStore(ctx).saveHits(synchronized(hits) { hits.toList() })
            renderResults()
        }
    }

    private fun renderResults() {
        lastListAt = SystemClock.elapsedRealtime()
        lastListCount = synchronized(hits) { hits.size }
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
                chip("⚡ Top 5 Test", false) { runTopFiveProbe() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) },
            )
        }
        protoChips.removeAllViews()
        protoChips.addView(chip(ctx.getString(R.string.pscan_all) + " (${hits.size})", filterProto == null) { filterProto = null; renderResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        Proto.entries.forEach { p ->
            val n = hits.count { it.proto == p }
            protoChips.addView(chip("${p.label} ($n)", filterProto == p) { filterProto = p; renderResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        countryChips.removeAllViews()
        val byCountry = hits.groupingBy { it.country }.eachCount().entries.sortedByDescending { it.value }
        countryChips.addView(chip(ctx.getString(R.string.pscan_all_countries), filterCountry == null) { filterCountry = null; renderResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        byCountry.forEach { (cc, n) ->
            val label = (if (cc.length == 2) cc.toFlagEmoji() + " " + cc else "🌐 " + ctx.getString(R.string.pip_country_unknown)) + " ($n)"
            countryChips.addView(chip(label, filterCountry == cc) { filterCountry = cc; renderResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        resultsList.removeAllViews()
        val shown = visibleHits()
        if (shown.isEmpty()) {
            resultsList.addView(text(ctx.getString(if (hits.isEmpty()) R.string.pscan_none_yet else R.string.pscan_filter_empty), 13f, color = palette.textTertiary).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)) })
            return
        }
        // Guarded: an OutOfMemoryError while building rows must not reach the main
        // thread and close the app (beta98's report ends exactly there — RippleDrawable/
        // LayerDrawable allocation while a row was being built). Halve the cap, log, retry.
        val built = runCatching {
        val isFarsiRow = ctx.resources.configuration.locales[0]?.language == "fa"
        shown.take(rowCap).forEach { hit ->
            val probeBadge = probeDisplayByAuthority[hit.authority]?.let {
                " · ${it.stage.name}" + if (it.healthy) " ✓" else ""
            }.orEmpty()
            resultsList.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat(); setColor(palette.surfaceElevated2) }
                addView(text(if (hit.country.length == 2) hit.country.toFlagEmoji() else "🌐", 18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(hit.authority, 14f, bold = true).apply { typeface = CatClientDataTypeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(text("${hit.proto.label} · ${hit.latencyMs} ms$probeBadge", 12f, color = palette.textSecondary))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (hit.proto == Proto.SOCKS5 && connectDirect != null) {
                    addView(chip(if (isFarsiRow) "⚡ اتصال" else "⚡ Connect", true) {
                        activeDialog?.dismiss()
                        connectDirect.invoke(hit)
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                }
                addView(chip(ctx.getString(if (hit.proto == Proto.SOCKS5) R.string.pscan_add else R.string.pscan_copy), hit.proto == Proto.SOCKS5 && connectDirect == null) {
                    if (hit.proto == Proto.SOCKS5) {
                        ProxyScanImport.uri(hit, System.currentTimeMillis())?.let { uri ->
                            importLink("$uri#${hit.country.ifBlank { "SOCKS5" }} ${hit.host}")
                        }
                    } else {
                        val verifiedRow = hit.toCatalogEntry(System.currentTimeMillis()).let {
                            it.copy(endpoint = it.endpoint.copy(verdict = EndpointVerdict.VERIFIED))
                        }
                        val relay = RelayCatalogAction.copyRelay(verifiedRow)?.value
                            ?: ProxyScanImport.uri(hit, System.currentTimeMillis())
                            ?: hit.authority
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("proxy", relay))
                        Toast.makeText(ctx, R.string.pscan_copied, Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        }
        built.exceptionOrNull()?.let { error ->
            if (error is OutOfMemoryError) {
                rowCap = (rowCap / 2).coerceAtLeast(12)
                resultsList.removeAllViews()
                DiagnosticLogger.warn(ctx, "pscan.rows.oom", "rowCap=$rowCap hits=${synchronized(hits) { hits.size }}", error)
                renderResults()
            } else {
                DiagnosticLogger.warn(ctx, "pscan.rows.failed", error = error)
            }
        }
    }

    private fun copyAll() {
        val list = visibleHits()
        if (list.isEmpty()) return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("proxies", list.joinToString("\n") { "${it.proto.label.lowercase()}://${it.authority}" }))
        Toast.makeText(ctx, R.string.pscan_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share(json: Boolean) {
        val list = visibleHits()
        if (list.isEmpty()) return
        val body = if (json) JSONArray().apply {
            list.forEach { put(JSONObject().put("host", it.host).put("port", it.port).put("protocol", it.proto.label.lowercase()).put("latency_ms", it.latencyMs).put("country", it.country)) }
        }.toString(2) else list.joinToString("\n") { it.authority }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }, null))
    }

    private fun pushRelaysToPanel(targets: List<Hit>) {
        if (targets.isEmpty()) {
            Toast.makeText(ctx, R.string.pscan_none_yet, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val picked = pickPanel?.invoke() ?: panelBaseUrl
            if (picked.isNullOrBlank()) {
                Toast.makeText(ctx, R.string.pip_no_panel, Toast.LENGTH_LONG).show()
                return@launch
            }
            val base = picked.trimEnd('/')
            val nowMs = System.currentTimeMillis()
            val verifiedEndpoints = targets.take(32).map { h ->
                VerifiedEndpointAdapters.fromProxyScan(h, nowMs).copy(verdict = EndpointVerdict.VERIFIED)
            }
            val batchResult = catalogController.buildVisiblePanelBatch()
                .takeIf { it.accepted > 0 && targets.size == visibleHits().size }
                ?: PanelEndpointBatch.build(verifiedEndpoints.map { it to null })
            if (batchResult.accepted == 0) {
                Toast.makeText(ctx, R.string.pscan_none_yet, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val password = runCatching { PanelCredentials.defaultPassword(activity, base) }.getOrNull().orEmpty().ifBlank { "admin" }
                    val loginConn = (URL("$base/api/login").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 8_000; doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    loginConn.outputStream.use {
                        it.write(PanelCredentials.loginPayload(activity, base, password).toString().toByteArray())
                    }
                    val cookie = loginConn.headerFields["Set-Cookie"]?.firstOrNull()?.substringBefore(';').orEmpty()
                    loginConn.disconnect()

                    val getConn = (URL("$base/api/settings").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000; readTimeout = 8_000
                        if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                    }
                    val beforeText = if (getConn.responseCode in 200..299) {
                        getConn.inputStream.bufferedReader().readText()
                    } else null
                    getConn.disconnect()

                    val existingSnap = beforeText?.let { PanelSettingsReadBack.parse(it, nowMs) }
                    val putBody = PanelSettingsReadBack.toSettingsPutBody(batchResult.payload, existingSnap)
                    val putConn = (URL("$base/api/settings").openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"; connectTimeout = 8_000; readTimeout = 8_000; doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                    }
                    putConn.outputStream.use { it.write(putBody.toByteArray()) }
                    val code = putConn.responseCode
                    val respText = (if (code in 200..299) putConn.inputStream else putConn.errorStream)?.bufferedReader()?.readText().orEmpty()
                    putConn.disconnect()
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    if (verifiedEndpoints.size == 1) {
                        val singleSync = PanelSettingsReadBack.verifySingle(verifiedEndpoints.first(), beforeText, respText, nowMs)
                        if (singleSync.status == PanelEndpointSyncStatus.REJECTED ||
                            singleSync.status == PanelEndpointSyncStatus.NO_KV ||
                            singleSync.status == PanelEndpointSyncStatus.READ_BACK_MISMATCH
                        ) {
                            throw IllegalStateException("panel-readback-failed (1)")
                        }
                    }
                    val afterSnap = PanelSettingsReadBack.parse(respText, nowMs)
                    val syncResult = if (targets.size == visibleHits().size) {
                        catalogController.verifyVisiblePanelSync(
                            before = existingSnap?.endpoints.orEmpty(),
                            after = afterSnap?.endpoints,
                            kvAvailable = afterSnap?.kvAvailable == true,
                        ).takeIf { it.results.isNotEmpty() }
                            ?: PanelSettingsReadBack.verifyBatch(verifiedEndpoints, beforeText, respText, nowMs)
                    } else {
                        PanelSettingsReadBack.verifyBatch(verifiedEndpoints, beforeText, respText, nowMs)
                    }
                    if (syncResult.failed > 0) throw IllegalStateException("panel-readback-failed (${syncResult.failed})")
                    syncResult.saved + syncResult.duplicates
                }
            }
            result.onSuccess { n ->
                Toast.makeText(ctx, ctx.getString(R.string.pip_saved_direct, n), Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(ctx, ctx.getString(R.string.pip_save_failed, e.message ?: "error"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clear() {
        job?.cancel(); job = null
        hits.clear(); checked.set(0); open.set(0); openNotProxy.set(0); total = 0
        filterProto = null; filterCountry = null; addressFilter = AddressFilter.ALL
        probeDisplayByAuthority.clear()
        VerifiedRelayStore(ctx).clear()
        // A new run asks about its own hosts; the counters behind the note line reset
        // with it (they describe THIS run's countries, not the session's).
        geoTried.clear(); geoViaProxy.set(0); geoViaDatabase.set(0); lastGeoKick = 0L
        lastListCount = -1; lastListAt = 0L
        updateCountryNote(null)
        renderCounters(); renderResults()
    }

    private fun stop() {
        job?.cancel(); job = null
        VerifiedRelayStore(ctx).saveHits(synchronized(hits) { hits.toList() })
        renderCounters()
        // The hits stay on screen after ⏹ — so their countries must still arrive.
        kickCountryPass(force = true)
    }

    // ---------------------------------------------------------------- engine

    private fun start() {
        clear()
        logMemory("pscan.mem.start")
        val ports = portsInput.text.toString().split(',', ' ', ';', '\n').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }.distinct().ifEmpty { listOf(1080) }
        val custom = customInput.text.toString()
        val urls = sourceToggles.filterKeys { it in sourceSelected }.values.toList()
        job = scope.launch {
            progressText.text = ctx.getString(R.string.pscan_collecting)
            val candidates = withContext(Dispatchers.IO) { collect(urls, custom, ports) }
            total = candidates.size
            renderCounters()
            if (candidates.isEmpty()) { Toast.makeText(ctx, R.string.pscan_no_candidates, Toast.LENGTH_SHORT).show(); return@launch }
            val gate = Semaphore(threads)
            var lastUi = 0L
            withContext(Dispatchers.IO) {
                candidates.map { c ->
                    async {
                        gate.withPermit {
                            ensureActive()
                            val result = probe(c)
                            checked.incrementAndGet()
                            if (result != null) {
                                synchronized(hits) { hits += result }
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUi > 250L) {
                                lastUi = now
                                withContext(Dispatchers.Main) { renderCounters() }
                                // Countries appear WHILE the scan runs, not after it:
                                // the owner reads the list mid-run (and stops it) and
                                // «🌐 نامشخص» on every row was this exact gap.
                                kickCountryPass()
                            }
                            // Rebuilding 200 rows four times a second was pure allocation
                            // churn (that is the OOM the owner hit). Only when it changed.
                            val hitCount = synchronized(hits) { hits.size }
                            if (now - lastListAt > 1_200L && hitCount != lastListCount) {
                                withContext(Dispatchers.Main) { renderResults() }
                            }
                        }
                    }
                }.awaitAll()
            }
            // Last pass: whatever the incremental ones did not answer, plus the rows
            // collected in the final seconds of the run.
            withContext(Dispatchers.IO) { resolveCountries(synchronized(hits) { hits.toList() }) }
            VerifiedRelayStore(ctx).saveHits(synchronized(hits) { hits.toList() })
            renderCounters(); renderResults()
            if (::countryStatusText.isInitialized) {
                val note = countryNoteLine()
                DiagnosticLogger.info(ctx, "proxy.country.done", "hits=${synchronized(hits) { hits.size }} $note")
                updateCountryNote(note)
            }
            job = null
            renderCounters()
            logMemory("pscan.mem.end")
        }
    }

    private fun collect(urls: List<Pair<String, Proto?>>, custom: String, ports: List<Int>): List<Candidate> {
        val out = LinkedHashSet<Candidate>()
        fun addLine(raw: String, defaultProto: Proto?) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return
            var proto = defaultProto
            Regex("^(socks5|socks4|socks|http|https)://", RegexOption.IGNORE_CASE).find(line)?.let { m ->
                proto = when (m.groupValues[1].lowercase()) { "socks5", "socks" -> Proto.SOCKS5; "socks4" -> Proto.SOCKS4; else -> Proto.HTTP }
                line = line.substring(m.value.length)
            }
            var countryHint = ""
            if ('#' in line) {
                countryHint = countryCodeFromNameOrIso(line.substringAfter('#').trim())
                line = line.substringBefore('#')
            }
            line = line.substringBefore(' ').substringAfter('@').trim()
            val cidr = Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)$").find(line)
            if (cidr != null) {
                val bits = cidr.groupValues[2].toInt().coerceIn(16, 32)
                val base = ipToLong(cidr.groupValues[1]) and (0xFFFFFFFFL shl (32 - bits))
                val count = 1L shl (32 - bits)
                val step = if (count > 4096) count / 4096 else 1 // cap enormous ranges
                var i = 0L
                while (i < count && out.size < 60_000) { ports.forEach { p -> out += Candidate(longToIp(base + i), p, null) }; i += step }
                return
            }
            val bracketV6Port = Regex("^\\[([^]]+)]:(\\d{1,5})(?::(.+))?$").find(line)
            if (bracketV6Port != null) {
                val p = bracketV6Port.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 }
                val cc = countryHint.ifBlank { countryCodeFromNameOrIso(bracketV6Port.groupValues.getOrElse(3) { "" }) }
                if (p != null) out += Candidate(bracketV6Port.groupValues[1], p, proto, cc)
                return
            }
            val hpCountry = Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+|[A-Za-z0-9.-]+):(\\d{1,5})(?::(.+))?$").find(line)
            if (hpCountry != null) {
                val p = hpCountry.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 }
                val cc = countryHint.ifBlank { countryCodeFromNameOrIso(hpCountry.groupValues.getOrElse(3) { "" }) }
                if (p != null) out += Candidate(hpCountry.groupValues[1], p, proto, cc)
            } else {
                val bareHost = line.removePrefix("[").removeSuffix("]")
                if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(bareHost) || bareHost.contains(':') || bareHost.contains('.')) {
                    ports.forEach { p -> out += Candidate(bareHost, p, proto, countryHint) }
                }
            }
        }
        custom.lines().forEach { addLine(it, null) }
        val notes = ArrayList<String>()
        urls.forEach { (url, proto) ->
            if (url == "builtin://socks5-seeds") {
                BUILTIN_SOCKS5_SEEDS.forEach { addLine(it, Proto.SOCKS5) }
                return@forEach
            }
            val label = url.substringAfter("//").substringBefore('/')
            val fetchedLines = fetchFeedLinesWithMirrors(url)
            if (fetchedLines != null && fetchedLines.isNotEmpty()) {
                fetchedLines.forEach { if (out.size < 60_000) addLine(it, proto) }
            } else {
                notes += "$label ✗"
            }
        }
        sourceNotes = notes.distinct().take(6)
        return out.toList()
    }

    private fun fetchFeedLinesWithMirrors(primaryUrl: String): List<String>? {
        val candidates = ArrayList<Pair<String, Boolean>>()
        candidates += primaryUrl to false
        if (primaryUrl.contains("cdn.jsdelivr.net/gh/")) {
            val ghPath = primaryUrl.substringAfter("cdn.jsdelivr.net/gh/")
            candidates += "https://fastly.jsdelivr.net/gh/$ghPath" to false
            val ownerRepoBranch = ghPath.substringBefore('/') + "/" + ghPath.substringAfter('/').substringBefore('/')
            val repoPart = ownerRepoBranch.substringBefore('@')
            val branchPart = ownerRepoBranch.substringAfter('@', "main")
            val filePath = ghPath.substringAfter('/').substringAfter('/')
            candidates += "https://raw.githubusercontent.com/$repoPart/$branchPart/$filePath" to false
            candidates += "https://api.github.com/repos/$repoPart/contents/$filePath?ref=$branchPart" to true
        }
        for ((u, isGhApi) in candidates) {
            val lines = runCatching {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 12_000
                if (isGhApi) conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IllegalStateException("HTTP $code")
                }
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (isGhApi) {
                    val b64 = JSONObject(text).optString("content").replace("\n", "").replace("\r", "")
                    if (b64.isEmpty()) emptyList()
                    else String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)).lines()
                } else {
                    text.lines()
                }
            }.getOrNull()
            if (!lines.isNullOrEmpty()) return lines
        }
        return null
    }

    private fun probe(c: Candidate): Hit? {
        val protos = if (c.proto != null) listOf(c.proto) else listOf(Proto.SOCKS5, Proto.HTTP, Proto.SOCKS4)
        var anyOpen = false
        for (p in protos) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = runCatching {
                Socket().use { s ->
                    protectOutboundSocket(s) // beta205: probe THIS network, not the running tunnel
                    s.connect(InetSocketAddress(c.host, c.port), timeoutMs)
                    anyOpen = true
                    s.soTimeout = timeoutMs
                    handshake(p, s.getInputStream(), s.getOutputStream())
                }
            }.getOrDefault(false)
            if (ok) {
                open.incrementAndGet()
                return Hit(c.host, c.port, p, SystemClock.elapsedRealtime() - t0, c.countryHint)
            }
            if (!anyOpen) return null // port closed: no point trying other protocols
        }
        open.incrementAndGet(); openNotProxy.incrementAndGet()
        return null
    }

    private fun handshake(p: Proto, ins: InputStream, out: OutputStream): Boolean = when (p) {
        Proto.SOCKS5 -> {
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val a = ins.read(); val b = ins.read()
            if (a != 0x05 || b != 0x00) false else {
                out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 1, 1, 1, 0x00, 0x50)); out.flush()
                val v = ins.read(); val rep = ins.read()
                v == 0x05 && rep == 0x00
            }
        }
        Proto.SOCKS4 -> {
            out.write(byteArrayOf(0x04, 0x01, 0x00, 0x50, 1, 1, 1, 1, 0x00)); out.flush()
            val buf = ByteArray(8); var n = 0
            while (n < 8) { val r = ins.read(buf, n, 8 - n); if (r < 0) break; n += r }
            n >= 2 && buf[1] == 0x5A.toByte()
        }
        Proto.HTTP -> {
            out.write("CONNECT 1.1.1.1:80 HTTP/1.1\r\nHost: 1.1.1.1:80\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray()); out.flush()
            val line = StringBuilder()
            while (line.length < 200) { val ch = ins.read(); if (ch < 0 || ch == '\n'.code) break; line.append(ch.toChar()) }
            Regex("^HTTP/1\\.[01] 200").containsMatchIn(line.toString())
        }
    }

    /**
     * Country per hit, in TWO passes and never only at the end of a run.
     *
     * «Skip at the end» was the whole failure: a 1303-candidate scan takes minutes, the
     * owner screenshots the list while it runs (or presses ⏹), and `geolocate()` — the
     * only place countries were ever fetched — had not run yet, so every chip said
     * «🌐 نامشخص» even though the lookup worked.
     *
     * Pass 1 MEASURES: a Cloudflare trace opened THROUGH each verified proxy returns
     * `loc=`, i.e. the country that proxy exits from — no third-party host, which is
     * the part that cannot work on a filtered line (see [ProxyEgressCountry]).
     * Pass 2 asks the database chain (owner's worker → HTTPS APIs → ip-api batch) for
     * whatever pass 1 could not answer.
     */
    private suspend fun resolveCountries(targets: List<Hit>): Int {
        val pending = targets.filter { it.country.length != 2 && geoTried.add(it.host) }
        if (pending.isEmpty()) return 0
        val resolvedBefore = targets.count { it.country.length == 2 }
        withContext(Dispatchers.Main) { updateCountryNote(ctx.getString(R.string.pscan_geo_running, pending.size)) }
        val measured = runCatching {
            ProxyEgressCountry.resolve(
                pending.map { ProxyEgressCountry.Endpoint(it.host, it.port, it.proto.label) },
            )
        }.getOrDefault(emptyMap())
        if (measured.isNotEmpty()) {
            pending.forEach { hit -> measured[hit.host]?.let { hit.country = it } }
            geoViaProxy.addAndGet(measured.size)
            withContext(Dispatchers.Main) { renderResults() }
        }
        val rest = pending.filter { it.country.length != 2 }
        var fromDatabase = 0
        if (rest.isNotEmpty()) {
            val worker = RelayCountryLookup.deployedWorkerHost(ctx)
            val database = runCatching { RelayCountryLookup.resolve(ctx, rest.map { it.host }, worker) }
                .getOrDefault(emptyMap())
            rest.forEach { hit -> database[hit.host]?.let { hit.country = it } }
            fromDatabase = rest.count { it.country.length == 2 }
            geoViaDatabase.addAndGet(fromDatabase)
        }
        val unknown = pending.count { it.country.length != 2 }
        DiagnosticLogger.info(
            ctx,
            "proxy.country",
            "asked=${pending.size} measured=${measured.size} database=$fromDatabase unknown=$unknown " +
                "resolvedBefore=$resolvedBefore",
        )
        return pending.size - unknown
    }

    /**
     * One country pass at a time, at most every few seconds, for the hits collected SO
     * FAR. [force] bypasses the interval (used by ⏹, where the scan is over but the
     * hits are still on screen and must not stay «نامشخص»).
     */
    private fun kickCountryPass(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastGeoKick < 4_000L) return
        lastGeoKick = now
        if (!geoBusy.compareAndSet(false, true)) return
        val snapshot = synchronized(hits) { hits.toList() }
        if (snapshot.isEmpty()) { geoBusy.set(false); return }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { resolveCountries(snapshot) }
                VerifiedRelayStore(ctx).saveHits(synchronized(hits) { hits.toList() })
                renderResults()
                updateCountryNote(countryNoteLine())
            } catch (error: Throwable) {
                DiagnosticLogger.warn(ctx, "proxy.country.failed", error = error)
            } finally {
                geoBusy.set(false)
            }
        }
    }

    /** «کشور: ۱۴ سنجیده از خودِ پروکسی · ۶ از دیتابیس · ۲ نامشخص» */
    private fun countryNoteLine(): String {
        val total = synchronized(hits) { hits.size }
        if (total == 0) return ""
        val unknown = synchronized(hits) { hits.count { it.country.length != 2 } }
        return ctx.getString(R.string.pscan_geo_note, geoViaProxy.get(), geoViaDatabase.get(), unknown)
    }

    private fun updateCountryNote(value: String?) {
        if (!::countryStatusText.isInitialized) return
        countryStatusText.text = value.orEmpty()
        countryStatusText.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
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

    private fun ipToLong(ip: String): Long = ip.split('.').fold(0L) { acc, s -> (acc shl 8) or (s.toLong() and 0xFF) }
    private fun longToIp(v: Long): String = "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    companion object {
        internal fun countryCodeFromNameOrIso(raw: String): String {
            val clean = raw.trim()
            if (clean.isEmpty()) return ""
            if (clean.length == 2 && clean.all { it.isLetter() }) return clean.uppercase()
            val token = clean.split(' ', ',', '-', '_').firstOrNull { it.length == 2 && it.all(Char::isLetter) }
            val lower = clean.lowercase()
            return when {
                lower.contains("united states") || lower == "usa" -> "US"
                lower.contains("germany") || lower.contains("deutschland") -> "DE"
                lower.contains("netherlands") || lower.contains("holland") -> "NL"
                lower.contains("united kingdom") || lower.contains("great britain") || lower == "uk" -> "GB"
                lower.contains("france") -> "FR"
                lower.contains("russia") || lower.contains("russian") -> "RU"
                lower.contains("singapore") -> "SG"
                lower.contains("japan") -> "JP"
                lower.contains("canada") -> "CA"
                lower.contains("finland") -> "FI"
                lower.contains("sweden") -> "SE"
                lower.contains("switzerland") -> "CH"
                lower.contains("poland") -> "PL"
                lower.contains("turkey") || lower.contains("türkiye") -> "TR"
                lower.contains("emirates") || lower == "uae" -> "AE"
                lower.contains("hong kong") -> "HK"
                lower.contains("korea") -> "KR"
                lower.contains("india") -> "IN"
                lower.contains("italy") -> "IT"
                lower.contains("spain") -> "ES"
                lower.contains("austria") -> "AT"
                lower.contains("romania") -> "RO"
                lower.contains("bulgaria") -> "BG"
                lower.contains("ukraine") -> "UA"
                lower.contains("brazil") -> "BR"
                lower.contains("australia") -> "AU"
                lower.contains("taiwan") -> "TW"
                lower.contains("vietnam") -> "VN"
                lower.contains("indonesia") -> "ID"
                lower.contains("thailand") -> "TH"
                lower.contains("kazakhstan") -> "KZ"
                token != null -> token.uppercase()
                else -> ""
            }
        }

        /**
         * Fresh snapshot of explicit `IP:port` SOCKS5 candidates from open public feeds
         * (`vakhov/fresh-proxy-list`, `roosterkid/openproxylist`, `monosans/proxy-list`, `zloi-user/hideip.me`)
         * so the scanner has candidates to probe even when external CDNs are blocked on a restricted SIM.
         * Every entry keeps its exact source port and is runtime-probed before any connection/import.
         */
        val BUILTIN_SOCKS5_SEEDS: List<String> = listOf(
            "104.219.236.127:1080",
            "107.219.228.250:7777",
            "110.235.247.248:1080",
            "110.235.248.81:1080",
            "110.235.255.252:1080",
            "121.169.46.116:1090",
            "124.248.191.117:1080",
            "174.138.61.184:1080",
            "185.194.217.97:1080",
            "194.163.182.6:1080",
            "202.62.49.65:1080",
            "202.62.50.222:1080",
            "202.62.55.95:1080",
            "203.25.208.163:1100",
            "203.25.208.163:1145",
            "34.124.190.108:8080",
            "47.236.20.209:1145",
            "5.255.113.177:1080",
            "5.255.117.127:1080",
            "5.255.117.250:1080",
            "59.46.216.131:30001",
            "31.211.142.115:8192",
            "85.143.254.38:1080",
            "212.33.248.45:1080",
            "69.55.49.177:38172",
            "113.176.118.150:1080",
            "102.36.127.249:1080",
            "94.102.89.133:1080",
            "77.50.61.182:1080",
            "195.98.82.63:1080",
            "213.139.210.13:1088",
            "194.28.162.12:1080",
            "90.154.61.187:1080",
            "31.58.144.15:1080",
            "178.252.74.29:1081",
            "213.27.29.153:51000",
            "94.233.120.194:1080",
            "35.198.71.112:40001",
            "78.29.56.178:1080",
            "77.239.96.194:1081",
            "95.83.152.62:1080",
            "217.71.230.103:1080",
            "145.220.227.145:8080",
            "31.129.147.102:1080",
            "79.134.4.225:1080",
            "46.146.220.247:1080",
            "45.74.31.46:5628",
            "85.174.84.22:1080",
            "46.146.220.180:1080",
            "66.151.41.133:1080",
            "45.74.31.46:4941",
            "45.74.31.46:5183",
            "208.102.51.6:58208:United States",
            "69.61.200.104:36181:United States",
            "66.42.224.229:41679:United States",
            "192.111.137.37:18762:United States",
            "192.111.129.145:16894:United States",
            "192.252.214.20:15864:United States",
            "98.178.72.21:10919:United States",
            "184.178.172.25:15291:United States",
            "174.75.211.193:4145:United States",
            "142.54.232.6:4145:United States",
        )
    }
}

/**
 * Persists verified SOCKS4 / SOCKS5 / HTTP CONNECT relay hits (`host:port`) across app
 * restarts so the 3-tier catalogue and panel sync can reuse device-tested relays.
 */
internal class VerifiedRelayStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveHits(hits: List<ProxyScannerPage.Hit>) {
        val nowMs = System.currentTimeMillis()
        val arr = JSONArray()
        hits.distinctBy { Triple(it.proto, it.host.lowercase(), it.port) }.take(200).forEach { h ->
            arr.put(
                JSONObject()
                    .put("host", h.host.removePrefix("[").removeSuffix("]"))
                    .put("port", h.port)
                    .put("proto", h.proto.name)
                    .put("latencyMs", h.latencyMs)
                    .put("country", h.country)
                    .put("checkedAtMs", nowMs),
            )
        }
        prefs.edit().putString(KEY_HITS, arr.toString()).apply()
    }

    fun loadHits(): List<ProxyScannerPage.Hit> {
        val raw = prefs.getString(KEY_HITS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<ProxyScannerPage.Hit>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val host = o.optString("host").trim().removePrefix("[").removeSuffix("]")
                val port = o.optInt("port", -1)
                if (host.isEmpty() || port !in 1..65535) continue
                val proto = runCatching { ProxyScannerPage.Proto.valueOf(o.optString("proto", "SOCKS5")) }
                    .getOrDefault(ProxyScannerPage.Proto.SOCKS5)
                out += ProxyScannerPage.Hit(
                    host = host,
                    port = port,
                    proto = proto,
                    latencyMs = o.optLong("latencyMs", 0L).coerceAtLeast(1L),
                    country = o.optString("country", ""),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun verifiedEndpoints(defaultCheckedAtMs: Long = System.currentTimeMillis()): List<VerifiedEndpoint> {
        val raw = prefs.getString(KEY_HITS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            var out = emptyList<VerifiedEndpoint>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val host = o.optString("host").trim().removePrefix("[").removeSuffix("]")
                val port = o.optInt("port", -1)
                if (host.isEmpty() || port !in 1..65535) continue
                val proto = runCatching { ProxyScannerPage.Proto.valueOf(o.optString("proto", "SOCKS5")) }
                    .getOrDefault(ProxyScannerPage.Proto.SOCKS5)
                val hit = ProxyScannerPage.Hit(
                    host = host,
                    port = port,
                    proto = proto,
                    latencyMs = o.optLong("latencyMs", 0L).coerceAtLeast(1L),
                    country = o.optString("country", ""),
                )
                val checkedAtMs = o.optLong("checkedAtMs", defaultCheckedAtMs).takeIf { it > 0L } ?: defaultCheckedAtMs
                val candidate = VerifiedEndpointAdapters.fromProxyScan(hit, checkedAtMs).copy(verdict = EndpointVerdict.VERIFIED)
                out = VerifiedEndpointRegistry.save(out, candidate).first
            }
            out
        }.getOrDefault(emptyList())
    }

    fun clear() {
        prefs.edit().remove(KEY_HITS).apply()
    }

    companion object {
        private const val PREFS_NAME = "cat_verified_relays"
        private const val KEY_HITS = "hits_json"
    }
}

