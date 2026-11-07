// beta244 «🌐 دور زدن تحریم» settings row + dialog + the real per-service test (logic in AntiSanctionRouting.kt).
package com.cat.client

import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * beta244 — one row in «مسیریابی» (next to ad block / sniffing / FakeDNS) that opens the anti-sanction dialog.
 * The dialog says in plain words what pressing each thing does, runs a REAL test of every service through the
 * chosen DNS on the user's own line, and ticks only what passed. Saving reconnects a running tunnel like the
 * other connection options do.
 */
internal fun MainActivity.addAntiSanctionRow(panel: LinearLayout) {
    val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    fun label() {
        val s = routingModePreferenceStore.antiSanction()
        val fa = appLanguagePreferenceStore.read() == AppLanguage.Persian
        button.text = if (s.active) {
            if (fa) "🌐 دور زدن تحریم: روشن (${s.services.size} سرویس)" else "🌐 Sanction bypass: on (${s.services.size} services)"
        } else {
            if (fa) "🌐 دور زدن تحریم با DNS ضدتحریم: خاموش" else "🌐 Sanction bypass via anti-sanction DNS: off"
        }
    }
    label()
    button.minHeight = dp(48)
    button.setOnClickListener { showAntiSanctionDialog { label() } }
    panel.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
}

private fun MainActivity.showAntiSanctionDialog(onSaved: () -> Unit) {
    val fa = appLanguagePreferenceStore.read() == AppLanguage.Persian
    fun t(f: String, e: String) = if (fa) f else e
    val store = routingModePreferenceStore
    val current = store.antiSanction()
    val pad = dp(16)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
    fun text(s: String, sizeSp: Float = 14f) = TextView(this).apply { this.text = s; textSize = sizeSp; setPadding(0, dp(6), 0, dp(6)) }
    box.addView(
        text(
            t(
                "اینو روشن کنی این میشه: سرویس‌هایی که تیک بزنی (مثل ChatGPT، Gemini، Docker) دیگر از VPN رد نمی‌شوند؛ " +
                    "مستقیم از اینترنت خودت باز می‌شوند و DNS ضدتحریمِ ایرانی، تحریمِ خودِ آن سایت را دور می‌زند. " +
                    "سریع‌تر است، حجم سرور مصرف نمی‌کند و پیام «VPN شناسایی شد» نمی‌گیری. بقیهٔ سایت‌ها مثل قبل از VPN می‌روند.\n\n" +
                    "«🧪 تست واقعی» را بزن: هر سرویس با همین DNS روی اینترنت خودت امتحان می‌شود و فقط آن‌هایی که واقعاً کار کردند تیک می‌خورند.",
                "Turn this on and the services you tick (ChatGPT, Gemini, Docker…) stop going through the VPN: they open directly " +
                    "on your own line and the Iranian anti-sanction DNS gets around the site's own sanction block. Faster, no server volume, " +
                    "no «VPN detected» walls. Every other site still uses the VPN.\n\n" +
                    "Press «🧪 Real test»: each service is tried through this DNS on your own line and only the ones that really work are ticked.",
            ),
            13f,
        ),
    )
    val enable = MaterialSwitch(this).apply { this.text = t("روشن", "On"); isChecked = current.enabled; minHeight = dp(48) }
    box.addView(enable)
    box.addView(text(t("DNS ضدتحریم:", "Anti-sanction DNS:")))
    val radios = RadioGroup(this)
    AntiSanctionRouting.DNS_OPTIONS.forEachIndexed { i, d ->
        radios.addView(RadioButton(this).apply { id = View.generateViewId(); this.text = "${if (fa) d.fa else d.en} · ${d.ip}"; tag = d.ip; isChecked = d.ip == current.dns; minHeight = dp(44) })
        if (i == 0 && AntiSanctionRouting.DNS_OPTIONS.none { it.ip == current.dns }) (radios.getChildAt(0) as RadioButton).isChecked = true
    }
    box.addView(radios)
    box.addView(text(t("سرویس‌ها:", "Services:")))
    val checks = AntiSanctionRouting.SERVICES.map { svc ->
        CheckBox(this).apply { this.text = svc.label; tag = svc; isChecked = svc.id in current.services; minHeight = dp(44) }.also { box.addView(it) }
    }
    val status = text("", 13f)
    val testButton = MaterialButton(this).apply { this.text = t("🧪 تست واقعی", "🧪 Real test"); minHeight = dp(48) }
    box.addView(testButton)
    box.addView(status)
    fun chosenDns(): String = (0 until radios.childCount).map { radios.getChildAt(it) as RadioButton }.firstOrNull { it.isChecked }?.tag as? String
        ?: AntiSanctionRouting.DNS_OPTIONS[0].ip
    radios.setOnCheckedChangeListener { _, _ ->
        // results belong to the DNS they were measured with
        checks.forEach { it.text = (it.tag as AntiSanctionRouting.Service).label }
        status.text = ""
    }
    testButton.setOnClickListener {
        if (vpnStateEqualsStarted()) {
            status.text = t(
                "⚠️ الان به VPN وصلی؛ تست باید روی اینترنت خودت (بدون VPN) انجام شود تا جواب واقعی بدهد. اول اتصال را قطع کن، تست کن، ذخیره کن و دوباره وصل شو.",
                "⚠️ You are connected. The test must run on your own line (no VPN) to be real: disconnect, test, save, then connect again.",
            )
            return@setOnClickListener
        }
        val dns = chosenDns()
        testButton.isEnabled = false
        status.text = t("⏳ در حال تست ${checks.size} سرویس با $dns …", "⏳ Testing ${checks.size} services via $dns …")
        checks.forEach { it.text = (it.tag as AntiSanctionRouting.Service).label + "  ⏳" }
        activityScope.launch {
            val gate = Semaphore(4)
            val results = withContext(Dispatchers.IO) {
                checks.map { cb -> async { gate.withPermit { AntiSanctionProbe.test(dns, cb.tag as AntiSanctionRouting.Service) } } }.awaitAll()
            }
            var works = 0
            checks.zip(results).forEach { (cb, r) ->
                val svc = cb.tag as AntiSanctionRouting.Service
                val ok = r.verdict == AntiSanctionRouting.Verdict.Works
                if (ok) works++
                cb.isChecked = ok
                cb.text = svc.label + "  " + verdictLabel(r, fa)
            }
            if (works > 0) enable.isChecked = true
            testButton.isEnabled = true
            status.text = if (works > 0) {
                t("✅ $works از ${checks.size} سرویس با این DNS روی اینترنت تو کار کرد و تیک خورد. «ذخیره» را بزن.", "✅ $works of ${checks.size} work through this DNS on your line and are ticked. Press «Save».")
            } else {
                t("❌ با این DNS هیچ سرویسی روی اینترنت تو کار نکرد. یک DNS دیگر انتخاب کن و دوباره تست کن.", "❌ Nothing works through this DNS on your line. Pick another DNS and test again.")
            }
            DiagnosticLogger.info(this@showAntiSanctionDialog, "activity.antiSanction.test", "dns=$dns works=$works/${checks.size}")
        }
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(t("🌐 دور زدن تحریم با DNS ضدتحریم", "🌐 Sanction bypass via anti-sanction DNS"))
        .setView(ScrollView(this).apply { addView(box) })
        .setPositiveButton(t("ذخیره", "Save")) { _, _ ->
            val next = AntiSanctionRouting.Settings(
                enabled = enable.isChecked,
                dns = chosenDns(),
                services = checks.filter { it.isChecked }.map { (it.tag as AntiSanctionRouting.Service).id }.toSet(),
            )
            if (next.enabled && next.services.isEmpty()) {
                Toast.makeText(this, t("هیچ سرویسی تیک نخورده؛ چیزی تغییر نمی‌کند.", "No service ticked; nothing changes."), Toast.LENGTH_LONG).show()
            }
            val before = store.antiSanction()
            store.saveAntiSanction(next)
            onSaved()
            if (before.active != next.active || (next.active && (before.dns != next.dns || before.services != next.services))) {
                DiagnosticLogger.info(this, "activity.antiSanction.saved", AntiSanctionRouting.encode(next))
                reconnectForConnectionOptionChange()
            }
        }
        .setNegativeButton(t("بستن", "Close"), null)
        .show()
}

private fun verdictLabel(r: AntiSanctionProbe.Result, fa: Boolean): String = when (r.verdict) {
    AntiSanctionRouting.Verdict.Works -> if (fa) "✅ کار می‌کند (${r.detail})" else "✅ works (${r.detail})"
    AntiSanctionRouting.Verdict.StillSanctioned -> if (fa) "⛔ هنوز تحریم است" else "⛔ still sanctioned"
    AntiSanctionRouting.Verdict.Filtered -> if (fa) "🚫 فیلتر است" else "🚫 filtered"
    AntiSanctionRouting.Verdict.NoDns -> if (fa) "❌ DNS جواب نداد" else "❌ no DNS answer"
    AntiSanctionRouting.Verdict.NoConnect -> if (fa) "❌ وصل نشد" else "❌ no connection"
    AntiSanctionRouting.Verdict.BadCert -> if (fa) "⚠️ گواهی نامعتبر — استفاده نمی‌شود" else "⚠️ invalid certificate — not used"
    AntiSanctionRouting.Verdict.ServerError -> if (fa) "❌ خطای سرور (${r.detail})" else "❌ server error (${r.detail})"
}

/** The real path a ticked service will take: the chosen DNS on this line → direct HTTPS with a verified certificate. */
internal object AntiSanctionProbe {
    data class Result(val verdict: AntiSanctionRouting.Verdict, val detail: String = "")

    fun test(dns: String, svc: AntiSanctionRouting.Service): Result {
        val ip = resolve(dns, svc.testHost) ?: return Result(AntiSanctionRouting.Verdict.NoDns)
        if (AntiSanctionRouting.isFilterAddress(ip)) return Result(AntiSanctionRouting.Verdict.Filtered, ip)
        val raw = Socket()
        try {
            try {
                raw.connect(InetSocketAddress(InetAddress.getByName(ip), 443), 6_000)
            } catch (e: Exception) {
                return Result(AntiSanctionRouting.Verdict.NoConnect, ip)
            }
            raw.soTimeout = 8_000
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, svc.testHost, 443, true) as SSLSocket
            try {
                ssl.startHandshake()
            } catch (e: SSLHandshakeException) {
                val certProblem = generateSequence<Throwable>(e) { it.cause }.any { it is java.security.cert.CertificateException }
                return Result(if (certProblem) AntiSanctionRouting.Verdict.BadCert else AntiSanctionRouting.Verdict.NoConnect, ip)
            } catch (e: Exception) {
                return Result(AntiSanctionRouting.Verdict.NoConnect, ip)
            }
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(svc.testHost, ssl.session)) {
                return Result(AntiSanctionRouting.Verdict.BadCert, ip)
            }
            ssl.outputStream.write(
                ("GET ${svc.testPath} HTTP/1.1\r\nHost: ${svc.testHost}\r\nUser-Agent: Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36\r\n" +
                    "Accept: */*\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            ssl.outputStream.flush()
            val buf = ByteArray(8_192)
            var n = 0
            runCatching {
                val input = ssl.inputStream
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
            }
            val reply = String(buf, 0, n, Charsets.ISO_8859_1)
            val code = AntiSanctionRouting.statusOf(reply)
            if (code == 0) return Result(AntiSanctionRouting.Verdict.NoConnect, ip)
            return Result(AntiSanctionRouting.verdict(code, reply.substringAfter("\r\n\r\n", "")), "HTTP $code")
        } catch (e: Exception) {
            return Result(AntiSanctionRouting.Verdict.NoConnect, ip)
        } finally {
            runCatching { raw.close() }
        }
    }

    private fun resolve(dns: String, host: String): String? {
        repeat(2) { attempt ->
            val id = ((System.nanoTime() + attempt) and 0xffff).toInt()
            val q = AntiSanctionRouting.dnsQuery(host, id)
            val ans = runCatching {
                DatagramSocket().use { s ->
                    s.soTimeout = 2_500
                    s.send(DatagramPacket(q, q.size, InetAddress.getByName(dns), 53))
                    val buf = ByteArray(1_500)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    AntiSanctionRouting.firstA(buf, p.length, id)
                }
            }.getOrNull()
            if (ans != null) return ans
        }
        return null
    }
}
