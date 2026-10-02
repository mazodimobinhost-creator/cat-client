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
) {
    data class Hit(val host: String, val port: Int, val country: String, val colo: String, val latencyMs: Long, var selected: Boolean = true)

    private val ctx: Context = activity
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private var job: Job? = null
    private val hits = ArrayList<Hit>()
    private val checked = AtomicInteger()
    private var total = 0
    private var filterCountry: String? = null

    private lateinit var progressText: TextView
    private lateinit var resultsList: LinearLayout
    private lateinit var countryChips: LinearLayout
    private lateinit var startButton: MaterialButton
    private lateinit var customInput: EditText
    private val sources = linkedMapOf(
        "proxyip.cmliussss.net" to true, "di.nscl.ir" to true, "tr.diam4.ggff.net" to true,
        "bpb.yousef.isegaro.com" to true, "proxyip.fxxk.dedyn.io" to false,
        "proxyip.us.fxxk.dedyn.io" to false, "proxyip.sg.fxxk.dedyn.io" to false, "proxyip.jp.fxxk.dedyn.io" to false,
        "cdn.xn--b6gac.eu.org" to false,
        "https://ipdb.030101.xyz/api/bestproxy.txt" to true,
    )
    private var ports = listOf(443)
    private var threads = 32
    private var timeoutMs = 5_000

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
        val inputs = card().apply {
            addView(text(ctx.getString(R.string.pip_sources), 15f, bold = true))
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(sourceRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(text(ctx.getString(R.string.pip_custom_title), 13f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(customInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(budgetRow) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        startButton = button(ctx.getString(R.string.pscan_start), true) { if (job?.isActive == true) stop() else start() }
        progressText = text(ctx.getString(R.string.pip_idle), 12f, color = palette.textSecondary)
        val scanCard = card().apply {
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
                addView(button(ctx.getString(R.string.pscan_clear), false) { clear() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            })
            addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        countryChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        resultsList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(button(ctx.getString(R.string.pip_send_panel), true) { sendToPanel() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            addView(button(ctx.getString(R.string.pscan_copy), false) { copySelected() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        }
        val resultsCard = card().apply {
            addView(text(ctx.getString(R.string.pip_results), 15f, bold = true))
            addView(text(ctx.getString(R.string.pip_results_hint), 12f, color = palette.textSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(countryChips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(resultsList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
    }

    private fun visible() = hits.filter { filterCountry == null || it.country == filterCountry }.sortedBy { it.latencyMs }

    private fun renderResults() {
        countryChips.removeAllViews()
        val groups = hits.groupingBy { it.country }.eachCount().entries.sortedByDescending { it.value }
        countryChips.addView(chip(ctx.getString(R.string.pscan_all_countries) + " (${hits.size})", filterCountry == null) { filterCountry = null; renderResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        groups.forEach { (cc, n) ->
            countryChips.addView(chip((if (cc.length == 2) cc.toFlagEmoji() + " " + cc else "🌐 ??") + " ($n)", filterCountry == cc) { filterCountry = cc; renderResults() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
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
        shown.take(150).forEach { hit ->
            resultsList.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(palette.surfaceElevated2)
                    if (hit.selected) setStroke(dp(1), palette.teal)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { hit.selected = !hit.selected; renderResults() }
                addView(text(if (hit.selected) "☑" else "☐", 18f, color = if (hit.selected) palette.teal else palette.textTertiary), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                addView(text(if (hit.country.length == 2) hit.country.toFlagEmoji() else "🌐", 18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(if (hit.port == 443) hit.host else "${hit.host}:${hit.port}", 14f, bold = true).apply { typeface = CatClientDataTypeface; maxLines = 1 })
                    addView(text("${hit.country.ifBlank { "??" }} · edge ${hit.colo.ifBlank { "?" }} · ${hit.latencyMs} ms", 12f, color = palette.textSecondary))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun selectedLines(): List<String> = visible().filter { it.selected }.map { if (it.port == 443) it.host else "${it.host}:${it.port}" }

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
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("proxyips", lines.joinToString("\n")))
        val base = panelUrl?.trim()?.trimEnd('/')
        val opened = !base.isNullOrBlank() && runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/?proxyips=" + Uri.encode(lines.joinToString(",")))))
        }.isSuccess
        Toast.makeText(ctx, ctx.getString(if (opened) R.string.pip_sent_panel else R.string.pip_copied_panel, lines.size), Toast.LENGTH_LONG).show()
    }

    private fun clear() {
        job?.cancel(); job = null; hits.clear(); checked.set(0); total = 0; filterCountry = null
        renderProgress(); renderResults()
    }

    private fun stop() { job?.cancel(); job = null; renderProgress() }

    // ------------------------------------------------------------------ engine
    private fun start() {
        clear()
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
                            if (now - lastUi > 300) { lastUi = now; withContext(Dispatchers.Main) { renderProgress(); renderResults() } }
                        }
                    }
                }.awaitAll()
            }
            job = null
            renderProgress(); renderResults()
        }
    }

    private fun collect(chosen: List<String>, custom: String): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        fun addHost(raw: String) {
            var line = raw.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('#').substringBefore(' ')
            if (line.isEmpty()) return
            var port = 443
            Regex("^(.*):(\\d+)$").find(line)?.takeIf { !line.contains("::") || line.startsWith("[") }?.let { m -> line = m.groupValues[1].trim('[', ']'); port = m.groupValues[2].toInt() }
            val cidr = Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+)/(\\d+)$").find(line)
            if (cidr != null) {
                val bits = cidr.groupValues[2].toInt().coerceIn(24, 32)
                val base = ipToLong(cidr.groupValues[1]) and (0xFFFFFFFFL shl (32 - bits))
                for (i in 0 until (1L shl (32 - bits))) out += longToIp(base + i) to port
                return
            }
            if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(line) || line.contains(':')) { out += line to port; return }
            if (line.contains('.')) {
                // Domain → every A/AAAA record (ProxyIP domains rotate many relays behind one name).
                runCatching { InetAddress.getAllByName(line) }.getOrDefault(emptyArray()).forEach { out += it.hostAddress.orEmpty() to port }
            }
        }
        custom.lines().forEach(::addHost)
        chosen.forEach { source ->
            if (source.startsWith("http")) {
                runCatching {
                    val conn = URL(source).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                    conn.inputStream.bufferedReader().useLines { lines -> lines.take(300).forEach(::addHost) }
                    conn.disconnect()
                }
            } else addHost(source)
        }
        return out.filter { (h, _) -> h.isNotBlank() && !IpScanner.isCloudflareAddress(h) }.take(2000)
    }

    /** TLS to the relay with a Cloudflare SNI and ask the edge for its trace through it. */
    private fun probe(host: String, port: Int): Hit? = runCatching {
        val t0 = SystemClock.elapsedRealtime()
        Socket().use { raw ->
            raw.connect(InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = (SSLContext.getDefault().socketFactory.createSocket(raw, TRACE_HOST, port, true) as SSLSocket)
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(TRACE_HOST)) }
            ssl.startHandshake()
            ssl.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $TRACE_HOST\r\nUser-Agent: CatClient\r\nConnection: close\r\n\r\n".toByteArray())
            ssl.outputStream.flush()
            val body = ssl.inputStream.bufferedReader().readText()
            val latency = SystemClock.elapsedRealtime() - t0
            if (!body.contains("h=$TRACE_HOST") || !body.contains("colo=")) return null
            val loc = Regex("(?m)^loc=([A-Z]{2})").find(body)?.groupValues?.get(1).orEmpty()
            val colo = Regex("(?m)^colo=([A-Z]{3})").find(body)?.groupValues?.get(1).orEmpty()
            Hit(host, port, loc, colo, latency)
        }
    }.getOrNull()

    private fun ipToLong(ip: String): Long = ip.split('.').fold(0L) { acc, s -> (acc shl 8) or (s.toLong() and 0xFF) }
    private fun longToIp(v: Long): String = "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    private companion object {
        const val TRACE_HOST = "speed.cloudflare.com"
    }
}
