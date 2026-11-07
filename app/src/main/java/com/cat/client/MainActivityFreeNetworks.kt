package com.cat.client

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.cat.client.MainActivity.Companion.SCREEN_HOME
import com.cat.client.MainActivity.Companion.SCREEN_SERVERS
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// beta247 🎁 free networks (UI): 🆓 Cat Free Proxy · 🎫 50 GB free account · 🏮 Cat Fanoos (experimental).
// Fetching / translating: FreeNetworkEngines.kt (pure parts unit-tested). See docs/CODEMAP.md.

private fun MainActivity.freeFa(): Boolean = appLanguagePreferenceStore.read() == AppLanguage.Persian

private fun MainActivity.freeButton(label: String, accent: Boolean, onClick: () -> Unit) = MaterialButton(this).apply {
    text = label
    isAllCaps = false
    textSize = 13.5f
    typeface = CatClientBodyBoldTypeface
    minHeight = dp(46)
    insetTop = 0
    insetBottom = 0
    cornerRadius = dp(12)
    if (accent) {
        backgroundTintList = ColorStateList.valueOf(TEAL)
        setTextColor(palette.onAccent)
    } else {
        backgroundTintList = ColorStateList.valueOf(withAlpha(palette.surfaceElevated2, 220))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 140))
        setTextColor(TEXT_PRIMARY)
    }
    setOnClickListener { onClick() }
}

private fun MainActivity.freeText(value: String, size: Float = 12.5f, color: Int = TEXT_SECONDARY, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = CatClientBodyBoldTypeface
    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    setTextIsSelectable(true)
}

private fun MainActivity.freeBox() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(8), dp(18), dp(6))
}

private fun MainActivity.freeLp(top: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

/** Hub: three free networks, each explained in one line («اینو بزنی این میشه»). */
internal fun MainActivity.showFreeNetworksHub() {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val box = freeBox()
    var dialog: AlertDialog? = null
    fun entry(title: String, detail: String, accent: Boolean, action: () -> Unit) {
        box.addView(freeButton(title, accent) { dialog?.dismiss(); action() }, freeLp(10))
        box.addView(freeText(detail, 12f), freeLp(3))
    }
    entry(
        t("🆓 Cat Free Proxy (۷ کشور، بدون حساب)", "🆓 Cat Free Proxy (7 countries, no account)"),
        t("اینو بزنی: برنامه از یک شبکهٔ رایگان عمومی سرور تازه با رمز می‌گیرد و وصل می‌شود. فقط وب و اپ‌ها (TCP)؛ تماس صوتی و بازی‌هایی که UDP می‌خواهند کار نمی‌کنند. رمزها هر ۶ ساعت خودکار نو می‌شوند.",
            "Tap: the app gets fresh servers with passwords from a public free network and connects. Web and apps only (TCP); voice calls / games that need UDP do not work. Passwords renew by themselves every 6 h."),
        accent = true,
    ) { showFreeProxyDialog() }
    entry(
        t("🎫 حساب رایگان ۵۰ گیگ در ماه", "🎫 Free 50 GB / month account"),
        t("اینو بزنی: سایت یک مؤسسهٔ غیرانتفاعی داخل برنامه باز می‌شود؛ تیک «من ربات نیستم» را خودتان می‌زنید (برنامه این را دور نمی‌زند)، حساب ساخته می‌شود و Cat لینک اشتراک را خودش برمی‌دارد و وصل می‌کند.",
            "Tap: a non-profit's site opens inside the app; you tick the human check yourself (the app never bypasses it), the account is made and Cat picks up the subscription link and connects."),
        accent = false,
    ) { showFreeAccountDialog() }
    entry(
        t("🏮 Cat Fanoos (آزمایشی)", "🏮 Cat Fanoos (experimental)"),
        t("اینو بزنی: از سرویس رایگان فانوس فهرست سرور گرفته می‌شود و هر سروری که هستهٔ Cat می‌تواند اجرا کند اضافه می‌شود. اگر هیچ‌کدام قابل‌استفاده نبود، دقیق می‌گوید چرا.",
            "Tap: a server list is fetched from the free Fanoos service and every server Cat's core can run is added. If none is usable it says exactly why."),
        accent = false,
    ) { showFanoosDialog() }
    val built = MaterialAlertDialogBuilder(this)
        .setTitle(t("🎁 شبکه‌های رایگان", "🎁 Free networks"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialog = built
    built.showCatClientDialog()
}

/** Progress dialog with a live log; [work] runs on IO and returns the lines of its report. */
private fun MainActivity.freeProgress(title: String, work: suspend (step: (String) -> Unit) -> Unit): Job {
    val fa = freeFa()
    val log = freeText("", 12.5f, TEXT_PRIMARY)
    val box = freeBox().apply { addView(log) }
    var job: Job? = null
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(if (fa) "توقف" else "Stop") { _, _ -> job?.cancel() }
        .setCancelable(false)
        .create()
    dialog.showCatClientDialog()
    val lines = mutableListOf<String>()
    val step: (String) -> Unit = { s -> runOnUiThread { lines += s; log.text = lines.joinToString("\n") } }
    job = activityScope.launch {
        try {
            work(step)
        } finally {
            runCatching { dialog.dismiss() }
        }
    }
    return job
}

/** Saves [text] as the subscription remembered under [storedId] (updated in place), returning its id. */
internal suspend fun MainActivity.saveFreeSubscription(storedId: String, name: String, text: String): String = withContext(Dispatchers.IO) {
    val existing = userSubscriptionManager.list().firstOrNull { it.id == storedId } ?: userSubscriptionManager.list().firstOrNull { it.name == name }
    (if (existing != null) userSubscriptionManager.update(existing.id, name, text) else userSubscriptionManager.add(name, text)).id
}

/**
 * Select the subscription and connect its first profile (or the one [pick] chooses), disconnecting first when needed.
 * beta250: a subscription with several rows (every Fox / Fanoos country) is then pinged row by row through the live
 * connection, so the list shows each country's real ping right away.
 */
internal fun MainActivity.connectFreeSubscription(
    subId: String,
    pick: (List<ConnectionProfile>) -> ConnectionProfile? = { it.firstOrNull() },
) {
    val fa = freeFa()
    activityScope.launch {
        val profiles = withContext(Dispatchers.IO) { userSubscriptionManager.cachedSnapshot(subId)?.catalog?.profiles.orEmpty() }
        val profile = pick(profiles) ?: profiles.firstOrNull()
        if (profile == null) {
            Toast.makeText(this@connectFreeSubscription, if (fa) "کانفیگی برای اتصال نیست." else "No config to connect.", Toast.LENGTH_LONG).show()
            return@launch
        }
        if (buttonModel.state != VpnState.Stopped) {
            startVpnService(Actions.DISCONNECT)
            val until = SystemClock.elapsedRealtime() + 15_000L
            while (buttonModel.state != VpnState.Stopped && SystemClock.elapsedRealtime() < until) delay(300)
        }
        userSubscriptionManager.select(subId)
        orbotEnginePreferenceStore.setEnabled(false)
        onSubscriptionSelected()
        handleConnectionSelected(profile, emptySet())
        openScreen(SCREEN_HOME)
        if (buttonModel.state == VpnState.Stopped) beginConnectFlow(Actions.CONNECT)
        if (profiles.size > 1) {
            val until = SystemClock.elapsedRealtime() + 60_000L
            while (buttonModel.state != VpnState.Started && SystemClock.elapsedRealtime() < until) delay(500)
            if (buttonModel.state == VpnState.Started) {
                delay(1_500) // let the first pages through before the sweep
                runPingAll(autoConnect = false)
            }
        }
    }
}

private fun MainActivity.freeResult(title: String, report: List<String>, subId: String?, note: String) {
    val fa = freeFa()
    val box = freeBox()
    box.addView(freeText(report.joinToString("\n"), 12f))
    if (note.isNotBlank()) box.addView(freeText(note, 12.5f, TEXT_PRIMARY), freeLp(8))
    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(if (fa) "بستن" else "Close", null)
    if (subId != null) {
        builder.setPositiveButton(if (fa) "⚡ اتصال" else "⚡ Connect") { _, _ -> connectFreeSubscription(subId) }
        builder.setNeutralButton(if (fa) "📋 دیدن کانفیگ‌ها" else "📋 See configs") { _, _ -> openScreen(SCREEN_SERVERS) }
    }
    builder.create().showCatClientDialog()
}

// ───────────── 🆓 Cat Free Proxy ─────────────

internal fun MainActivity.showFreeProxyDialog() {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val store = FreeNetworkStore(this)
    val locations = FreeProxyPlanner.LOCATIONS
    val chosen = store.freeProxyRegions.split(',').toSet()
    val checked = BooleanArray(locations.size) { locations[it].region in chosen }
    if (checked.none { it }) checked[0] = true
    val labels = locations.map { it.flag + " " + (if (fa) it.nameFa else it.nameEn) }.toTypedArray()
    MaterialAlertDialogBuilder(this)
        .setTitle(t("🆓 Cat Free Proxy — کدام کشورها؟", "🆓 Cat Free Proxy — which countries?"))
        .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
        .setPositiveButton(t("🚀 گرفتن و ساخت", "🚀 Fetch & build")) { _, _ ->
            val regions = locations.filterIndexed { i, _ -> checked[i] }.map { it.region }.ifEmpty { listOf("nl") }
            buildFreeProxy(regions)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showCatClientDialog()
}

private fun MainActivity.buildFreeProxy(regions: List<String>) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val name = "🆓 Cat Free Proxy"
    freeProgress(t("⏳ گرفتن سرورهای رایگان…", "⏳ Getting free servers…")) { step ->
        step(t("۱) گرفتن کلید ناشناس و فهرست سرور (مستقیم، و اگر بسته بود از داخل اتصال فعلی)…", "1) Anonymous key + server list (direct, else through the current connection)…"))
        val result = try {
            withContext(Dispatchers.IO) { FreeNetworkBuilder.freeProxy(this@buildFreeProxy, regions, fa) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ انجام نشد", "❌ Failed"), listOf(e.message ?: e.javaClass.simpleName),
                null, t("اگر روی اینترنت ملی هستید، اول با هر کانفیگ سالمی وصل شوید و دوباره بزنید.", "If you are on the national internet, connect with any working config first and try again."))
            return@freeProgress
        }
        if (result.links.isEmpty()) {
            freeResult(t("❌ سروری نیامد", "❌ No servers"), result.report, null,
                t("سرویس جواب نداد یا از اینجا بسته است. با یک کانفیگ سالم وصل شوید و دوباره بزنید.", "The service did not answer or is blocked here. Connect with a working config and try again."))
            return@freeProgress
        }
        step(t("۲) ذخیره به‌عنوان اشتراک «$name»…", "2) Saving as subscription “$name”…"))
        val store = FreeNetworkStore(this@buildFreeProxy)
        val subId = try {
            saveFreeSubscription(store.freeProxySubId, name, FreeProxyPlanner.subscriptionText(result.links))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ ذخیره نشد", "❌ Not saved"), result.report + (e.message ?: e.javaClass.simpleName), null, "")
            return@freeProgress
        }
        store.freeProxySubId = subId
        renderSubscriptions()
        freeResult(
            t("✅ ${result.links.size} سرور رایگان آماده است", "✅ ${result.links.size} free servers ready"), result.report, subId,
            t("«⚡ اتصال» را بزنید. فقط TCP است (وب، تلگرام، اینستاگرام…)؛ تماس صوتی/بازی UDP کار نمی‌کند. اگر یکی وصل نشد، از فهرست کانفیگ‌ها بعدی را بزنید. رمزها خودکار نو می‌شوند.",
                "Tap “⚡ Connect”. TCP only (web, Telegram, Instagram…); UDP voice/games do not work. If one fails, pick the next in the config list. Passwords renew by themselves."),
        )
    }
}

// ───────────── 🏮 Cat Fanoos (experimental) ─────────────

internal fun MainActivity.showFanoosDialog() {
    val fa = freeFa()
    // beta251: the picker is always shown. A free account often gets no location list, so the catalog of common
    // locations fills it in; «🌍 every country» asks for each of them and keeps what really comes back.
    val offered = FreeNetworkStore(this).lanternOfferedList().ifEmpty { LanternPlanner.CATALOG }
    // beta252: if the last run showed that this account only gets a fixed set of countries, say so and offer what
    // really works (fresh servers of those countries) first, instead of 20 country rows that all give the same.
    val fixed = FreeNetworkStore(this).lanternFixedRecent()
    if (fixed.isNotEmpty()) {
        val flags = fixed.joinToString(" ") { LanternPlanner.flagOf(it) + " " + it }
        val items = listOf(
            if (fa) "🔄 سرورهای تازهٔ همین کشورها ($flags)" else "🔄 Fresh servers of these countries ($flags)",
            if (fa) "🌍 دوباره امتحان همهٔ کشورها" else "🌍 Try every country again",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(if (fa) "🏮 Cat Fanoos" else "🏮 Cat Fanoos")
            .setMessage(
                if (fa) "دفعهٔ قبل سرویس فانوس به حساب رایگان فقط این کشورها را داد: $flags — درخواست کشورهای دیگر را نادیده گرفت. برای کشورهای بیشتر «🦊 Cat Fox VPN» را امتحان کنید."
                else "Last time the Fanoos service gave a free account only: $flags — it ignored requests for other countries. For more countries try “🦊 Cat Fox VPN”.",
            )
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) fetchFanoosAll(emptyList())
                else fetchFanoosAll(LanternPlanner.allTargets(FreeNetworkStore(this).lanternOfferedList()))
            }
            .setNegativeButton(if (fa) "بستن" else "Close", null)
            .create().showCatClientDialog()
        return
    }
    // beta250: «🌍 every country» fills the ONE «🏮 Cat Fanoos» subscription with every location the service gives,
    // each row named by its country; once connected every row is pinged, so the list shows all countries + pings.
    val labels = listOf(
        if (fa) "🌍 همهٔ کشورها (همه در همین یک اشتراک، با پینگ)" else "🌍 Every country (all in this one subscription, with pings)",
        if (fa) "🎲 خودکار (بهترین را خود سرویس انتخاب کند)" else "🎲 Automatic (the service picks)",
    ) + offered.map { it.label() }
    MaterialAlertDialogBuilder(this)
        .setTitle(if (fa) "🏮 Cat Fanoos — کدام کشور؟" else "🏮 Cat Fanoos — which country?")
        .setItems(labels.toTypedArray()) { _, which ->
            when (which) {
                0 -> fetchFanoosAll(LanternPlanner.allTargets(FreeNetworkStore(this).lanternOfferedList()))
                1 -> fetchFanoos(null)
                else -> fetchFanoos(offered[which - 2])
            }
        }
        .setNegativeButton(if (fa) "بستن" else "Close", null)
        .create().showCatClientDialog()
}

private fun MainActivity.fetchFanoos(preferred: LanternPlanner.Location?) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val name = "🏮 Cat Fanoos"
    val unknown = t("❔ کشور نامشخص", "❔ unknown country")
    freeProgress(t("⏳ گرفتن فهرست فانوس…", "⏳ Getting the Fanoos list…")) { step ->
        step(t("۱) ساخت کاربر ناشناس و گرفتن تنظیمات (فقط پروتکل‌های استاندارد)…", "1) Anonymous user + config (standard protocols only)…"))
        if (preferred != null) step(t("   کشور درخواستی: ", "   Requested country: ") + preferred.label())
        val (result, tr) = try {
            withContext(Dispatchers.IO) { FreeNetworkBuilder.lantern(this@fetchFanoos, preferred) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ انجام نشد", "❌ Failed"), listOf(e.message ?: e.javaClass.simpleName), null,
                t("سرویس فانوس از این شبکه جواب نداد. با یک کانفیگ سالم وصل شوید و دوباره بزنید.", "The Fanoos service did not answer from this network. Connect with a working config and try again."))
            return@freeProgress
        }
        if (result.links.isEmpty()) {
            val skipped = tr.skipped.entries.joinToString(", ") { "${it.key}×${it.value}" }.ifBlank { t("هیچ سروری", "no servers") }
            freeResult(t("⚠️ سرور قابل‌استفاده‌ای نیامد", "⚠️ No usable server"), result.report, null,
                t("فانوس برای این شبکه فقط این‌ها را داد: $skipped — این نوع‌ها فقط با برنامهٔ خود فانوس کار می‌کنند و Cat آن‌ها را جعل نمی‌کند. بعداً دوباره امتحان کنید.",
                    "Fanoos only gave: $skipped — those types only run in its own app, and Cat does not fake them. Try again later."))
            return@freeProgress
        }
        step(t("۲) ذخیره به‌عنوان اشتراک «$name»…", "2) Saving as subscription “$name”…"))
        val store = FreeNetworkStore(this@fetchFanoos)
        val fresh = result.links.mapIndexed { i, l -> l to (tr.linkLocations.getOrNull(i)?.code ?: "") }
        val merged = LanternPlanner.merge(store.lanternLinks, fresh)
        val subId = try {
            saveFreeSubscription(store.lanternSubId, name, merged.joinToString("\n") { it.first })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ ذخیره نشد", "❌ Not saved"), result.report + (e.message ?: e.javaClass.simpleName), null, "")
            return@freeProgress
        }
        store.lanternSubId = subId
        store.lanternLinks = merged
        renderSubscriptions()
        val report = result.report.toMutableList()
        report += t("کشورهای این نوبت: ", "Countries this time: ") + tr.countrySummary(unknown)
        val kept = merged.size - fresh.size
        if (kept > 0) report += t("از قبل نگه داشته شد: $kept سرور از کشورهای دیگر", "Kept from before: $kept servers of other countries")
        if (tr.clientCountry.isNotBlank()) report += t("سرویس شما را در این کشور می‌بیند: ", "The service sees you in: ") + LanternPlanner.flagOf(tr.clientCountry) + " " + tr.clientCountry
        val honoured = LanternPlanner.honoured(preferred, tr.linkLocations)
        val countryNote = when {
            honoured == false -> t("⚠️ سرویس «${preferred?.label()}» را برای حساب رایگان نداد و به‌جایش همین‌ها را فرستاد. سرورهای کشورهای دیگر عوض نشدند.",
                "⚠️ The service did not give “${preferred?.label()}” to a free account and sent these instead. Other countries' servers were kept.")
            honoured == true -> t("✅ کشور درخواستی آمد.", "✅ The requested country came.")
            tr.offered.size > 1 -> t("🌍 سرویس ${tr.offered.size} مکان دارد؛ دوباره «🏮 فانوس» را بزنید و «🌍 همهٔ کشورها» را انتخاب کنید تا همه با پینگ در همین اشتراک بیایند (سرورهای فعلی می‌مانند).",
                "🌍 The service offers ${tr.offered.size} locations; tap “🏮 Fanoos” again and choose “🌍 Every country” to get them all, with pings, in this subscription (current servers stay).")
            else -> t("سرویس فقط همین مکان را برای این حساب داد.", "The service only offered this location to this account.")
        }
        freeResult(t("✅ ${result.links.size} سرور فانوس اضافه شد", "✅ ${result.links.size} Fanoos servers added"), report, subId,
            countryNote + "\n" + t("آزمایشی است: اگر یکی وصل نشد بعدی را امتحان کنید.", "Experimental: if one fails try the next."))
    }
}

/** beta250: one request per offered location, all merged into the one «🏮 Cat Fanoos» subscription. */
private fun MainActivity.fetchFanoosAll(offered: List<LanternPlanner.Location>) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val name = "🏮 Cat Fanoos"
    // beta251: one automatic request first (whatever the service gives by itself), then every location; locations the
    // service reveals on the way join the queue. A location it refuses (HTTP 4xx) is reported, not treated as «down».
    val queue = offered.take(LanternPlanner.ALL_COUNTRIES_MAX_LOCATIONS).toMutableList()
    freeProgress(t("⏳ گرفتن سرورهای همهٔ کشورهای فانوس…", "⏳ Getting Fanoos servers for every country…")) { step ->
        val collected = ArrayList<Pair<String, String>>()
        val report = ArrayList<String>()
        val notGiven = ArrayList<String>()
        val fixed = LanternPlanner.FixedCheck()
        var fixedConfirmed = false
        var failuresInARow = 0
        var i = -1
        while (i < queue.size) {
            val loc = if (i < 0) null else queue[i]
            i++
            val label = loc?.label() ?: t("🎲 خودکار", "🎲 Automatic")
            step("${i + 1}/${queue.size + 1}) $label…")
            var refused = false
            val got = try {
                withContext(Dispatchers.IO) { FreeNetworkBuilder.lantern(this@fetchFanoosAll, loc) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                refused = LanternPlanner.answeredButRefused(e.message)
                null.also { report += (if (refused) "↩️ " else "❌ ") + "$label: " + (e.message ?: e.javaClass.simpleName).take(80) }
            }
            if (got == null) {
                if (loc != null) notGiven += loc.label()
                if (refused) continue
                // Two dead answers in a row = the service (or this network) stopped answering; do not hammer it.
                if (++failuresInARow >= 2) { report += t("⏹ سرویس دیگر جواب نداد؛ بقیه رها شد.", "⏹ The service stopped answering; the rest was skipped."); break }
                continue
            }
            failuresInARow = 0
            val (result, tr) = got
            val more = LanternPlanner.extendQueue(queue, tr.offered)
            if (more > 0) step(t("   سرویس $more مکان دیگر هم معرفی کرد؛ به صف اضافه شد.", "   The service named $more more locations; queued."))
            val rows = result.links.mapIndexed { k, l -> l to (tr.linkLocations.getOrNull(k)?.code ?: "") }
            collected += rows
            val gotSet = LanternPlanner.countriesOf(rows).toSet()
            val codes = gotSet.joinToString(" ") { LanternPlanner.flagOf(it) + it }
            val sameAsAlways = fixed.isSameAsAlways(loc, gotSet)
            val stop = fixed.add(loc, gotSet)
            when {
                rows.isEmpty() -> report += "⚠️ $label: " + t("سرور قابل‌استفاده نداد", "no usable server")
                // Ignored silently (the same countries as always): grouped into one line at the end, not one line each.
                sameAsAlways || fixed.isSameAsAlways(loc, gotSet) -> notGiven += loc!!.label()
                LanternPlanner.honoured(loc, tr.linkLocations) == false -> { report += "↩️ $label: " + t("نداد؛ به‌جایش $codes", "not given; sent $codes instead"); notGiven += loc!!.label() }
                else -> report += "✅ $label: ${rows.size} ($codes)"
            }
            if (stop) { fixedConfirmed = true; break }
        }
        val store = FreeNetworkStore(this@fetchFanoosAll)
        val base = fixed.base.orEmpty()
        if (fixedConfirmed && base.isNotEmpty()) {
            store.lanternFixed = base.joinToString(",")
            store.lanternFixedAt = System.currentTimeMillis()
            val flags = base.joinToString(" ") { LanternPlanner.flagOf(it) + " " + it }
            report += t("🔒 سرویس فانوس به این حساب رایگان فقط این کشورها را می‌دهد: $flags — ${notGiven.size} کشور دیگر را خواستیم و هر بار همین‌ها را فرستاد، پس بقیه را نپرسیدیم (ظاهراً انتخاب کشور فقط برای حساب پولی خودش است).",
                "🔒 The Fanoos service gives this free account only: $flags — we asked for ${notGiven.size} other countries and got the same each time, so the rest was not asked (choosing a country seems to be for its paid accounts only).")
        } else if (fixed.honouredSeen) {
            store.lanternFixed = ""
        }
        val fresh = LanternPlanner.limitPerCountry(collected, LanternPlanner.perCountryFor(LanternPlanner.countriesOf(collected).size))
        if (fresh.isEmpty()) {
            freeResult(t("⚠️ سرور قابل‌استفاده‌ای نیامد", "⚠️ No usable server"), report, null,
                t("سرویس فانوس از این شبکه سروری نداد که Cat واقعاً اجرا کند. با یک کانفیگ سالم وصل شوید و دوباره بزنید.",
                    "Fanoos gave no server Cat can really run from this network. Connect with a working config and try again."))
            return@freeProgress
        }
        val merged = LanternPlanner.merge(store.lanternLinks, fresh, cap = LanternPlanner.ALL_COUNTRIES_CAP)
        val subId = try {
            saveFreeSubscription(store.lanternSubId, name, merged.joinToString("\n") { it.first })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ ذخیره نشد", "❌ Not saved"), report + (e.message ?: e.javaClass.simpleName), null, "")
            return@freeProgress
        }
        store.lanternSubId = subId
        store.lanternLinks = merged
        renderSubscriptions()
        val countries = LanternPlanner.countriesOf(merged)
        report += t("کشورهای رسیده: ", "Countries received: ") + countries.joinToString(" ") { LanternPlanner.flagOf(it) + it }
        if (notGiven.isNotEmpty()) report += t("خواسته شد ولی داده نشد: ", "Asked for but not given: ") + notGiven.map { it.substringBefore(' ') }.distinct().joinToString(" ")
        if (countries.size <= 2) report += t("برای کشورهای بیشتر: «🦊 Cat Fox VPN» (چند کشور با حساب رایگان فایرفاکس) یا «🆓 Cat Free Proxy».", "For more countries: “🦊 Cat Fox VPN” (several countries with a free Firefox account) or “🆓 Cat Free Proxy”.")
        freeResult(
            t("✅ ${merged.size} سرور از ${countries.size} کشور در «$name»", "✅ ${merged.size} servers from ${countries.size} countries in “$name”"),
            report,
            subId,
            t("همه در همین یک اشتراک‌اند (هر ردیف با نام کشورش). «⚡ اتصال» را بزنید؛ بعد از وصل شدن پینگ همهٔ ردیف‌ها گرفته و کنار هر کدام نشان داده می‌شود.",
                "All are in this one subscription (each row named by its country). Tap “⚡ Connect”; once connected every row is pinged and its ping is shown next to it."),
        )
    }
}

// ───────────── 🎫 50 GB free account ─────────────

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun MainActivity.showFreeAccountDialog() {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val store = FreeNetworkStore(this)
    val name = "🎫 Cat Free 50GB"
    var found: String? = null
    var dialogRef: AlertDialog? = null
    var poll: Job? = null

    val box = freeBox()
    box.addView(freeText(t(
        "۱) در صفحهٔ زیر «Get account» / «ساخت حساب» را بزنید و تیک «من ربات نیستم» را خودتان بزنید.\n۲) شمارهٔ ۳۲ رقمی حساب را جایی امن نگه دارید (تنها راه ورود دوباره است؛ Cat هم پایین نشانش می‌دهد).\n۳) وارد شوید و «لینک اشتراک» را بسازید یا «باز کردن در برنامه» را بزنید؛ Cat لینک را خودش برمی‌دارد.\nاگر سایت باز نشد، اول با یک کانفیگ وصل شوید یا آدرس آینه را پایین بنویسید.",
        "1) In the page below tap “Get account” and tick the human check yourself.\n2) Keep the 32-digit account number safe (the only way back in; Cat also shows it below).\n3) Sign in and create the subscription link or tap “open in app”; Cat picks the link up by itself.\nIf the site does not open, connect with a config first or type a mirror address below.",
    ), 12f))
    val siteInput = EditText(this).apply {
        setText(store.freeAccountSite)
        textSize = 12.5f
        setSingleLine(true)
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    box.addView(siteInput, freeLp(6))
    val status = freeText(t("⏳ منتظر لینک اشتراک…", "⏳ Waiting for the subscription link…"), 12.5f, TEAL, bold = true)
    val accountView = freeText(store.freeAccountNumber.takeIf { it.length == 32 }?.let { t("🔢 شمارهٔ حساب: ", "🔢 Account number: ") + FreeAccountLinks.groupAccount(it) } ?: "", 12.5f, TEXT_PRIMARY, bold = true)

    val webView = object : android.webkit.WebView(this) {
        override fun onCheckIsTextEditor(): Boolean = true
    }.apply {
        isFocusable = true
        isFocusableInTouchMode = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_UP) {
                dialogRef?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                if (!v.hasFocus()) v.requestFocus()
            }
            false
        }
    }
    runCatching { android.webkit.CookieManager.getInstance().setAcceptCookie(true) }

    val addButton = freeButton(t("➕ افزودن به Cat و اتصال", "➕ Add to Cat and connect"), accent = true) {}
    addButton.isEnabled = false

    fun onFound(link: String) {
        if (found == link) return
        found = link
        status.text = t("✅ لینک پیدا شد: ", "✅ Link found: ") + link.take(60) + "…"
        addButton.isEnabled = true
    }

    fun scanText(text: String) {
        FreeAccountLinks.accountNumber(text)?.let { acct ->
            if (store.freeAccountNumber != acct) {
                store.freeAccountNumber = acct
                accountView.text = t("🔢 شمارهٔ حساب (نگه دارید): ", "🔢 Account number (keep it): ") + FreeAccountLinks.groupAccount(acct)
            }
        }
        val link = FreeAccountLinks.subscriptionUrls(text).firstOrNull()
            ?: FreeAccountLinks.shareLinks(text).takeIf { it.isNotEmpty() }?.joinToString("\n")
        link?.let(::onFound)
    }

    webView.webViewClient = object : android.webkit.WebViewClient() {
        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
            val url = request?.url?.toString().orEmpty()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) return false
            // «Open in app» deep links (v2rayng / hiddify / clash / sub:// …): keep the link, never leave the page.
            FreeAccountLinks.fromDeepLink(url)?.let(::onFound)
            return true
        }
    }

    fun load() {
        val raw = siteInput.text?.toString().orEmpty().trim().ifBlank { FreeAccountLinks.SITE }
        val url = if (raw.startsWith("http", true)) raw else "https://$raw"
        store.freeAccountSite = url.trimEnd('/')
        webView.loadUrl(url.trimEnd('/') + "/get-account")
    }
    box.addView(freeButton(t("🌐 باز کردن صفحه", "🌐 Open the page"), accent = false) { load() }, freeLp(4))
    box.addView(webView, LinearLayout.LayoutParams(-1, dp(420)).apply { topMargin = dp(6) })
    box.addView(status, freeLp(6))
    box.addView(accountView, freeLp(4))
    box.addView(freeButton(t("📋 لینک را کپی کرده‌ام (از کلیپ‌بورد بخوان)", "📋 I copied the link (read the clipboard)"), accent = false) {
        val clip = readClipboardConfigText().orEmpty()
        val link = FreeAccountLinks.subscriptionUrls(clip).firstOrNull() ?: clip.trim().takeIf { it.startsWith("https://", true) && "/sub" in it && it.none(Char::isWhitespace) }
            ?: FreeAccountLinks.shareLinks(clip).takeIf { it.isNotEmpty() }?.joinToString("\n")
        if (link == null) Toast.makeText(this, t("در کلیپ‌بورد لینکی نبود.", "No link in the clipboard."), Toast.LENGTH_LONG).show() else onFound(link)
    }, freeLp(6))
    box.addView(freeButton(t("🔢 کپی شمارهٔ حساب", "🔢 Copy the account number"), accent = false) {
        val acct = store.freeAccountNumber
        if (acct.length != 32) {
            Toast.makeText(this, t("هنوز شماره‌ای دیده نشده.", "No number seen yet."), Toast.LENGTH_LONG).show()
        } else {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("account", acct))
            Toast.makeText(this, t("کپی شد؛ جایی امن نگه دارید.", "Copied; keep it somewhere safe."), Toast.LENGTH_LONG).show()
        }
    }, freeLp(6))
    box.addView(addButton, freeLp(8))

    addButton.setOnClickListener {
        val link = found ?: return@setOnClickListener
        dialogRef?.dismiss()
        activityScope.launch {
            val subId = try {
                saveFreeSubscription(store.freeAccountSubId, name, link)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The subscription host may be unreachable right now: hand it to the normal add dialog instead.
                Toast.makeText(this@showFreeAccountDialog, t("اشتراک الان دریافت نشد؛ در پنجرهٔ افزودن دوباره امتحان کنید.", "The subscription could not be fetched now; try again in the add dialog."), Toast.LENGTH_LONG).show()
                openScreen(SCREEN_SERVERS)
                showAddSubscriptionDialog(link, name)
                return@launch
            }
            store.freeAccountSubId = subId
            renderSubscriptions()
            connectFreeSubscription(subId)
        }
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(t("🎫 حساب رایگان ۵۰ گیگ", "🎫 Free 50 GB account"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialogRef = dialog
    dialog.setOnDismissListener {
        poll?.cancel()
        runCatching { webView.stopLoading(); webView.destroy() }
    }
    dialog.showCatClientDialog()
    load()
    // Read what the page shows every 2 s (text, inputs, links) — no clicks, no form filling, no captcha work.
    poll = activityScope.launch {
        while (true) {
            delay(2_000)
            runCatching { webView.evaluateJavascript(FreeAccountLinks.SCAN_JS) { raw -> scanText(jsString(raw)) } }
        }
    }
}

/** evaluateJavascript returns a JSON string literal; decode it. */
private fun jsString(raw: String?): String = runCatching { org.json.JSONArray("[" + (raw ?: "\"\"") + "]").optString(0) }.getOrDefault("")
