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
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Public proxy scanner (like the "Proxy Panel Scanner" web tool): collects candidates from public
 * lists (monosans / proxifly via jsDelivr), a pasted list, or CIDR ranges × a port set; probes
 * each with a real protocol handshake (SOCKS5 greeting + CONNECT, SOCKS4 CONNECT, HTTP CONNECT)
 * under a thread/timeout budget; geolocates the survivors in one batch; lets you filter by
 * protocol/country, copy/export TXT/JSON, or import a SOCKS5 hit straight into Cat Client.
 */
class ProxyScannerPage(
    private val activity: Activity,
    private val palette: CatClientPalette,
    private val scope: CoroutineScope,
    private val importLink: (String) -> Unit,
) {
    enum class Proto(val label: String) { SOCKS5("SOCKS5"), SOCKS4("SOCKS4"), HTTP("HTTP") }
    data class Candidate(val host: String, val port: Int, val proto: Proto?)
    data class Hit(val host: String, val port: Int, val proto: Proto, val latencyMs: Long, var country: String = "")

    private val ctx: Context = activity
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private var job: Job? = null
    private val hits = ArrayList<Hit>()
    private val checked = AtomicInteger(); private val open = AtomicInteger(); private val openNotProxy = AtomicInteger()
    private var total = 0
    private var filterProto: Proto? = null
    private var filterCountry: String? = null

    private lateinit var countChecked: TextView; private lateinit var countOpen: TextView
    private lateinit var countHealthy: TextView; private lateinit var countOpenNot: TextView
    private lateinit var progressText: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var protoChips: LinearLayout
    private lateinit var countryChips: LinearLayout
    private lateinit var startButton: MaterialButton
    private val sourceToggles = LinkedHashMap<String, Pair<String, Proto?>>() // label -> (url, proto)
    private val sourceSelected = HashSet<String>()
    private lateinit var customInput: EditText
    private lateinit var portsInput: EditText
    private var threads = 100
    private var timeoutMs = 5_000

    fun show() {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(build())
        dialog.window?.setBackgroundDrawable(ColorDrawable(palette.background))
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
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
        sourceToggles["monosans · SOCKS5"] = "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks5.txt" to Proto.SOCKS5
        sourceToggles["monosans · SOCKS4"] = "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/socks4.txt" to Proto.SOCKS4
        sourceToggles["monosans · HTTP"] = "https://cdn.jsdelivr.net/gh/monosans/proxy-list@main/proxies/http.txt" to Proto.HTTP
        sourceToggles["proxifly · all"] = "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/all/data.txt" to null
        sourceSelected += "monosans · SOCKS5"

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

        val inputsCard = card().apply {
            addView(text(ctx.getString(R.string.pscan_sources), 15f, bold = true))
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(sourcesRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(text(ctx.getString(R.string.pscan_custom_title), 13f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(customInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(ctx.getString(R.string.pscan_ports_title), 13f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(portsInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(threadsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(timeoutRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        startButton = button(ctx.getString(R.string.pscan_start), filled = true) { if (job?.isActive == true) stop() else start() }
        progressText = text(ctx.getString(R.string.pscan_idle), 12f, color = palette.textSecondary)
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
            addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(countersRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        protoChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        countryChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        resultsList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        val exportRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(button(ctx.getString(R.string.pscan_copy), false) { copyAll() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(button("TXT ⬇", false) { share(false) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(button("JSON ⬇", false) { share(true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        }
        val resultsCard = card().apply {
            addView(text(ctx.getString(R.string.pscan_results), 15f, bold = true))
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(protoChips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
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
        countChecked.text = checked.get().toString(); countOpen.text = open.get().toString()
        countHealthy.text = hits.size.toString(); countOpenNot.text = openNotProxy.get().toString()
        progressText.text = if (job?.isActive == true) ctx.getString(R.string.pscan_scanning, checked.get(), total) else if (total > 0) ctx.getString(R.string.pscan_done, hits.size) else ctx.getString(R.string.pscan_idle)
        startButton.text = ctx.getString(if (job?.isActive == true) R.string.pscan_stop else R.string.pscan_start)
    }

    private fun visibleHits() = hits.filter { (filterProto == null || it.proto == filterProto) && (filterCountry == null || it.country == filterCountry) }.sortedBy { it.latencyMs }

    private fun renderResults() {
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
            val label = (if (cc.length == 2) cc.toFlagEmoji() + " " + cc else "?? ") + " ($n)"
            countryChips.addView(chip(label, filterCountry == cc) { filterCountry = cc; renderResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        resultsList.removeAllViews()
        val shown = visibleHits()
        if (shown.isEmpty()) {
            resultsList.addView(text(ctx.getString(if (hits.isEmpty()) R.string.pscan_none_yet else R.string.pscan_filter_empty), 13f, color = palette.textTertiary).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)) })
            return
        }
        shown.take(200).forEach { hit ->
            resultsList.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat(); setColor(palette.surfaceElevated2) }
                addView(text(if (hit.country.length == 2) hit.country.toFlagEmoji() else "🌐", 18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("${hit.host}:${hit.port}", 14f, bold = true).apply { typeface = CatClientDataTypeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(text("${hit.proto.label} · ${hit.latencyMs} ms", 12f, color = palette.textSecondary))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(chip(ctx.getString(if (hit.proto == Proto.SOCKS5) R.string.pscan_add else R.string.pscan_copy), hit.proto == Proto.SOCKS5) {
                    if (hit.proto == Proto.SOCKS5) importLink("socks5://${hit.host}:${hit.port}#${hit.country.ifBlank { "SOCKS5" }} ${hit.host}")
                    else {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("proxy", "${hit.host}:${hit.port}"))
                        Toast.makeText(ctx, R.string.pscan_copied, Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun copyAll() {
        val list = visibleHits()
        if (list.isEmpty()) return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("proxies", list.joinToString("\n") { "${it.proto.label.lowercase()}://${it.host}:${it.port}" }))
        Toast.makeText(ctx, R.string.pscan_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share(json: Boolean) {
        val list = visibleHits()
        if (list.isEmpty()) return
        val body = if (json) JSONArray().apply {
            list.forEach { put(JSONObject().put("host", it.host).put("port", it.port).put("protocol", it.proto.label.lowercase()).put("latency_ms", it.latencyMs).put("country", it.country)) }
        }.toString(2) else list.joinToString("\n") { "${it.host}:${it.port}" }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }, null))
    }

    private fun clear() {
        job?.cancel(); job = null
        hits.clear(); checked.set(0); open.set(0); openNotProxy.set(0); total = 0
        filterProto = null; filterCountry = null
        renderCounters(); renderResults()
    }

    private fun stop() {
        job?.cancel(); job = null
        renderCounters()
    }

    // ---------------------------------------------------------------- engine

    private fun start() {
        clear()
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
                            if (now - lastUi > 250L) { lastUi = now; withContext(Dispatchers.Main) { renderCounters() } }
                        }
                    }
                }.awaitAll()
            }
            withContext(Dispatchers.IO) { geolocate() }
            renderCounters(); renderResults()
            job = null
            renderCounters()
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
            line = line.substringBefore('#').substringBefore(' ').substringBefore('@').trim()
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
            val hp = Regex("^([^:]+):(\\d+)$").find(line)
            if (hp != null) out += Candidate(hp.groupValues[1], hp.groupValues[2].toInt(), proto)
            else if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(line) || line.contains('.')) ports.forEach { p -> out += Candidate(line, p, proto) }
        }
        custom.lines().forEach { addLine(it, null) }
        urls.forEach { (url, proto) ->
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 20_000
                conn.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (out.size < 60_000) addLine(it, proto) } }
                conn.disconnect()
            }
        }
        return out.toList()
    }

    private fun probe(c: Candidate): Hit? {
        val protos = if (c.proto != null) listOf(c.proto) else listOf(Proto.SOCKS5, Proto.HTTP, Proto.SOCKS4)
        var anyOpen = false
        for (p in protos) {
            val t0 = SystemClock.elapsedRealtime()
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(c.host, c.port), timeoutMs)
                    anyOpen = true
                    s.soTimeout = timeoutMs
                    handshake(p, s.getInputStream(), s.getOutputStream())
                }
            }.getOrDefault(false)
            if (ok) { open.incrementAndGet(); return Hit(c.host, c.port, p, SystemClock.elapsedRealtime() - t0) }
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

    private fun geolocate() {
        val snapshot = synchronized(hits) { hits.toList() }
        snapshot.chunked(100).forEach { chunk ->
            runCatching {
                val body = JSONArray().apply { chunk.forEach { put(JSONObject().put("query", it.host).put("fields", "countryCode,query")) } }.toString()
                val conn = URL("http://ip-api.com/batch?fields=countryCode,query").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 8_000; conn.readTimeout = 10_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val map = HashMap<String, String>()
                for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); map[o.optString("query")] = o.optString("countryCode").uppercase(Locale.ROOT) }
                chunk.forEach { it.country = map[it.host].orEmpty() }
            }
        }
    }

    private fun ipToLong(ip: String): Long = ip.split('.').fold(0L) { acc, s -> (acc shl 8) or (s.toLong() and 0xFF) }
    private fun longToIp(v: Long): String = "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
}
