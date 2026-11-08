package com.cat.client

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
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

// beta247 🎁 free networks (UI): 🌀 Psiphon · 🆓 Free Proxy · 🌏 SSTP · 🔑 Outline · 🎫 50 GB · 🏮 Fanoos · 🟢 Proton · 🌐 Cat GP · 🔐 Amnezia · 🌬️ Cat Wind · 🚀 Cat Warp. Fetching: FreeNetworkEngines.kt / CatWindEngine.kt / CatWarpFree.kt.

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

/** beta254: Fanoos config-new on the SIM/Wi-Fi, so the service sees Iran — not the VPN exit. */
private inline fun <T> MainActivity.withPhysicalNet(block: () -> T): T {
    val net = activePhysicalNetwork()
    val prev = FreeNetHttp.openOnPhysical
    if (net != null) FreeNetHttp.openOnPhysical = { url -> net.openConnection(url) }
    try {
        return block()
    } finally {
        FreeNetHttp.openOnPhysical = prev
    }
}

/** Hub: free networks, each explained in one line («اینو بزنی این میشه»). */
internal fun MainActivity.showFreeNetworksHub() {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val box = freeBox()
    var dialog: AlertDialog? = null
    fun entry(title: String, detail: String, accent: Boolean, pencil: (() -> Unit)? = null, action: () -> Unit) {
        if (pencil == null) {
            box.addView(freeButton(title, accent) { dialog?.dismiss(); action() }, freeLp(10))
        } else {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val main = freeButton(title, accent) { dialog?.dismiss(); action() }
            main.setOnLongClickListener { dialog?.dismiss(); pencil(); true }
            row.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                freeButton("✏️", accent = false) { dialog?.dismiss(); pencil() }.apply {
                    contentDescription = t("ویرایش IP و SNI", "Edit IP and SNI")
                },
                LinearLayout.LayoutParams(dp(56), -2).apply { marginStart = dp(6) },
            )
            box.addView(row, freeLp(10))
        }
        box.addView(freeText(detail, 12f), freeLp(3))
    }
    entry(
        t("🌀 سایفون رایگان (بدون حساب — در ایران بیشتر جواب می‌دهد)", "🌀 Free Psiphon (no account — what usually works in Iran)"),
        t("اینو بزنی: بدون ساختن حساب وصل می‌شود. پراستفاده‌ترین ابزار رایگان در ایران است. روی Cat Warp سوار می‌شود. از کانفیگ معمولی کندتر است.",
            "Tap: connects with no account. The free tool Iranians use most. It rides on Cat Warp. Slower than a normal config."),
        accent = true,
    ) { showFreePsiphonDialog() }
    entry(
        t("🦊 Cat Fox VPN — کشورها با پینگ روی خط شما", "🦊 Cat Fox VPN — countries with ping on your line"),
        t("اینو بزنی: پینگ واقعی هر کشور موزیلا روی همین اینترنت نشان داده می‌شود، بعد انتخاب می‌کنی. برای اتصال حساب رایگان ۵۰ گیگ لازم است؛ خودِ پینگ حساب نمی‌خواهد.",
            "Tap: live ping of each Mozilla country on this network, then you pick. Connecting needs the free 50 GB account; the ping itself does not."),
        accent = true,
    ) { showFirefoxCountryPickerDialog() }
    entry(
        t("🌬️ Cat Wind — کشورها با پینگ روی خط شما", "🌬️ Cat Wind — countries with ping on your line"),
        t("اینو بزنی: وارد شو یا حساب رایگان بساز (تیک ربات با توست). بعدش Cat خودش همهٔ کانفیگ‌های مجاز را می‌گیرد، پینگ می‌کند و اضافه می‌کند.",
            "Tap: sign in or create the free account (the human check stays yours). Cat then fetches every allowed config, pings it, and adds it."),
        accent = true,
    ) { showCatWindDialog() }
    entry(
        t("🚀 Cat Warp — تونل کلودفلر با پینگ زنده", "🚀 Cat Warp — Cloudflare tunnel with live ping"),
        t("اینو بزنی: پروتکل را انتخاب می‌کنی (وایرگارد برای ایرانسل، MASQUE-H2 برای همراه اول)، پینگ واقعی همان IP:port روی خط تو، بعد وصل می‌شود. خروجی آی‌پی کلودفلر است — تعویض کشور نیست. SSTP جدا در همین فهرست است.",
            "Tap: pick a protocol (WireGuard for Irancell, MASQUE-H2 for Hamrah-e Aval), live ping of that IP:port on your line, then connect. Exit is a Cloudflare IP — not a country-change VPN. SSTP is a separate item in this list."),
        accent = true,
    ) { showCatWarpFreeDialog() }
    entry(
        t("🆓 Cat Free Proxy (۷ کشور، بدون حساب)", "🆓 Cat Free Proxy (7 countries, no account)"),
        t("اینو بزنی: برنامه از یک شبکهٔ رایگان عمومی سرور تازه با رمز می‌گیرد و وصل می‌شود. فقط وب و اپ‌ها (TCP)؛ تماس صوتی و بازی‌هایی که UDP می‌خواهند کار نمی‌کنند. رمزها هر ۶ ساعت خودکار نو می‌شوند.",
            "Tap: the app gets fresh servers with passwords from a public free network and connects. Web and apps only (TCP); voice calls / games that need UDP do not work. Passwords renew by themselves every 6 h."),
        accent = true,
    ) { showFreeProxyDialog() }
    entry(
        t("🌏 SSTP رایگان (VPN Gate — ژاپن/کره، داخل HTTPS)", "🌏 Free SSTP (VPN Gate — Japan/Korea, inside HTTPS)"),
        t("اینو بزنی: فهرست سرورهای داوطلب گرفته می‌شود (کاربر vpn / رمز vpn) و وصل می‌شود. سایت خود VPN Gate در ایران فیلتر است؛ فهرست از راه دیگر می‌آید. بعضی خطوط SSTP را می‌بندند.",
            "Tap: volunteer servers are fetched (user vpn / password vpn) and it connects. The VPN Gate site is filtered in Iran; the list comes another way. Some lines block SSTP."),
        accent = false,
    ) { showCatSstpDialog() }
    entry(
        t("🔑 Outline رایگان (Shadowsocks، بدون حساب)", "🔑 Free Outline (Shadowsocks, no account)"),
        t("اینو بزنی: کلیدهای رایگان از چند فهرست گرفته می‌شوند، روی خط شما تست می‌شوند و سالم‌ها وصل می‌شوند. اگر هیچ‌کدام باز نشد، دقیق می‌گوید.",
            "Tap: free keys are taken from several lists, tested on your line, and the working ones connect. If none open it says so."),
        accent = false,
    ) { showOutlineFreeHubDialog() }
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
    entry(
        t("🟢 Cat Proton (حساب رایگان، چند کشور)", "🟢 Cat Proton (free account, several countries)"),
        t("اینو بزنی: وارد شو یا حساب رایگان بساز (تیک ربات با توست). بعدش Cat خودش همهٔ کانفیگ‌های مجاز را می‌گیرد و اضافه می‌کند — پلن پولی جعل نمی‌شود.",
            "Tap: sign in or create the free account (the human check stays yours). Cat then fetches every allowed config and adds it — Plus is not faked."),
        accent = true,
    ) { showProtonDialog() }
    entry(
        t("🌐 Cat GP — وقتی فقط گوگل باز است", "🌐 Cat GP — when only Google is open"),
        t("اینو بزنی: مرورگر از اسکریپت خودتان روی گوگل رد می‌شود. مداد یا نگه‌داشتن = IP و SNI لبه. فقط مرورگر؛ تلگرام نه.",
            "Tap: the browser goes through YOUR script on Google. Pencil or hold = edge IP and SNI. Browsers only; not Telegram."),
        accent = false,
        pencil = { showCatGpEdgeDialog() },
    ) { showCatGpDialog() }
    entry(
        t("🔐 Cat Amnezia (کلید خودت)", "🔐 Cat Amnezia (your own key)"),
        t("آمنزیا سرور رایگان نمی‌دهد. اینو بزنی: می‌گوید کلید را از کجا بیاوری (سرور خودت، پروتون، یا فروشنده) و وصل می‌کند.",
            "Amnezia does not give free servers. Tap: it tells you where to get a key (your server, Proton, or a seller) and connects."),
        accent = false,
    ) { showAmneziaSectionDialog() }
    val built = MaterialAlertDialogBuilder(this)
        .setTitle(t("🎁 شبکه‌های رایگان", "🎁 Free networks"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialog = built
    built.showCatClientDialog()
}

/** Times every share link on THIS network. Reachable first by RTT; closed rows stay, never a made-up delay. */
private suspend fun MainActivity.rankLinksByLivePing(
    links: List<String>,
    step: (String) -> Unit,
    fa: Boolean,
): Pair<List<String>, List<String>> {
    if (links.isEmpty()) return links to emptyList()
    step(if (fa) "پینگ واقعی هر سرور روی همین اینترنت…" else "Live ping of each server on this network…")
    val ranked = withContext(Dispatchers.IO) { LiveNetPing.rankShares(links) }
    val lines = ranked.map { hit ->
        val addr = if (hit.host.isNullOrBlank() || hit.port <= 0) "?" else "${hit.host}:${hit.port}"
        (if (fa) LiveNetPing.labelFa(hit.rttMs) else LiveNetPing.labelEn(hit.rttMs)) + " · $addr"
    }
    return ranked.map { it.link } to lines
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
        val (ordered, pingLines) = rankLinksByLivePing(result.links, step, fa)
        step(t("۲) ذخیره به‌عنوان اشتراک «$name»…", "2) Saving as subscription “$name”…"))
        val store = FreeNetworkStore(this@buildFreeProxy)
        val subId = try {
            saveFreeSubscription(store.freeProxySubId, name, FreeProxyPlanner.subscriptionText(ordered))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ ذخیره نشد", "❌ Not saved"), result.report + pingLines + (e.message ?: e.javaClass.simpleName), null, "")
            return@freeProgress
        }
        store.freeProxySubId = subId
        renderSubscriptions()
        freeResult(
            t("✅ ${ordered.size} سرور رایگان آماده است", "✅ ${ordered.size} free servers ready"), result.report + pingLines, subId,
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
            withContext(Dispatchers.IO) { withPhysicalNet { FreeNetworkBuilder.lantern(this@fetchFanoos, preferred) } }
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
        val (_, pingLines) = rankLinksByLivePing(result.links, step, fa)
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
        report += pingLines
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
        var seenIn = ""
        var viaTunnel = false
        var viaPhysical = false
        var seenTz = ""
        var seenIp = ""
        var failuresInARow = 0
        var i = -1
        while (i < queue.size) {
            val loc = if (i < 0) null else queue[i]
            i++
            val label = loc?.label() ?: t("🎲 خودکار", "🎲 Automatic")
            step("${i + 1}/${queue.size + 1}) $label…")
            var refused = false
            val got = try {
                withContext(Dispatchers.IO) { withPhysicalNet { FreeNetworkBuilder.lantern(this@fetchFanoosAll, loc) } }
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
            if (seenIn.isBlank()) seenIn = tr.clientCountry
            if (result.report.any { it.startsWith("config ✓") && it.contains("(tunnel)") }) viaTunnel = true
            if (result.report.any { it.contains("(physical)") }) viaPhysical = true
            if (seenTz.isBlank()) seenTz = result.report.firstOrNull { it.startsWith("tz ") }?.removePrefix("tz ").orEmpty()
            if (seenIp.isBlank()) seenIp = result.report.firstOrNull { it.startsWith("client-ip ") }?.removePrefix("client-ip ").orEmpty()
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
        // beta254: official client sends timezone + the phone's public IP (looked up off the VPN).
        val tz = seenTz.ifBlank { LanternPlanner.timeZone() }
        report += t("ساعت گوشی: ", "Phone timezone: ") + tz
        if (seenIp.isNotBlank()) report += t("آی‌پی فرستاده‌شده (شبکهٔ خود گوشی): ", "Public IP sent (phone network): ") + seenIp
        if (seenIn.isNotBlank()) {
            val how = when {
                viaPhysical -> t(" (از سیم‌کارت/وای‌فای، نه از داخل VPN)", " (over SIM/Wi-Fi, not through the VPN)")
                viaTunnel -> t(" (درخواست از داخل VPN روشن رفت)", " (the request went through the running VPN)")
                else -> ""
            }
            report += t("سرویس شما را در این کشور دید: ", "The service saw you in: ") + LanternPlanner.flagOf(seenIn) + " " + seenIn + how
            if (!seenIn.equals("IR", ignoreCase = true)) {
                report += when {
                    !LanternPlanner.looksLikeIranTimeZone(tz) -> t(
                        "ℹ️ ساعت گوشی تهران نیست. اپ رسمی فانوس از ساعت برای تشخیص ایران استفاده می‌کند — اگر در ایران هستید ساعت را روی تهران بگذارید و دوباره «🌍» را بزنید.",
                        "ℹ️ The phone timezone is not Tehran. The official Fanoos app uses the timezone to detect Iran — if you are in Iran set the clock to Tehran and tap “🌍” again.")
                    viaPhysical -> t(
                        "ℹ️ درخواست از خود گوشی رفت و ساعت تهران است، ولی سرویس باز هم ایران ندید. پس محدودیت حساب رایگان همان سرویس است، نه تشخیص کشور.",
                        "ℹ️ The request left from the phone itself and the clock is Tehran, but the service still did not see Iran. That is the free-account limit of the service, not country detection.")
                    else -> t(
                        "ℹ️ سرویس شما را ایران ندید. اگر VPN روشن است، Cat سعی می‌کند از سیم‌کارت بفرستد؛ اگر باز هم نشد VPN را قطع کنید و دوباره بزنید.",
                        "ℹ️ The service did not see you in Iran. If a VPN is on, Cat tries to send over SIM/Wi-Fi; if it still fails, disconnect the VPN and try again.")
                }
            }
        }
        val fresh = LanternPlanner.limitPerCountry(collected, LanternPlanner.perCountryFor(LanternPlanner.countriesOf(collected).size))
        if (fresh.isEmpty()) {
            freeResult(t("⚠️ سرور قابل‌استفاده‌ای نیامد", "⚠️ No usable server"), report, null,
                t("سرویس فانوس از این شبکه سروری نداد که Cat واقعاً اجرا کند. با یک کانفیگ سالم وصل شوید و دوباره بزنید.",
                    "Fanoos gave no server Cat can really run from this network. Connect with a working config and try again."))
            return@freeProgress
        }
        val merged = LanternPlanner.merge(store.lanternLinks, fresh, cap = LanternPlanner.ALL_COUNTRIES_CAP)
        val (_, pingLines) = rankLinksByLivePing(merged.map { it.first }, step, fa)
        report += pingLines
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
            t("همه در همین یک اشتراک‌اند (هر ردیف با نام کشورش). پینگ بالا روی همین اینترنت است. «⚡ اتصال» را بزنید.",
                "All are in this one subscription (each row named by its country). Pings above are on this network. Tap “⚡ Connect”."),
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

// ───────────── 🟢 Cat Proton ─────────────

internal fun MainActivity.showProtonDialog() {
    showProtonWebView(emptySet())
}

private fun MainActivity.showProtonPaste(want: Set<String>) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val input = EditText(this).apply {
        hint = "[Interface]\nPrivateKey = …\n\n[Peer]\nPublicKey = …\nEndpoint = …"
        minLines = 8
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        textSize = 12.5f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        textDirection = View.TEXT_DIRECTION_LTR
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(t("📋 فایل WireGuard پروتون", "📋 Proton WireGuard file"))
        .setView(ScrollView(this).apply { addView(input, LinearLayout.LayoutParams(-1, dp(280))) })
        .setPositiveButton(t("➕ افزودن", "➕ Add")) { _, _ ->
            val text = input.text?.toString().orEmpty()
            addProtonConfs(listOf("paste.conf" to text), want)
        }
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
        .showCatClientDialog()
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun MainActivity.showProtonWebView(want: Set<String>) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    var dialogRef: AlertDialog? = null
    var poll: Job? = null
    var pulling = false
    val collected = ArrayList<Pair<String, String>>()

    val box = freeBox()
    box.addView(freeText(t(
        "وارد شو یا حساب رایگان بساز. تیک ربات با توست. بعد از ورود Cat خودش همهٔ کانفیگ‌ها را می‌گیرد و اضافه می‌کند.",
        "Sign in or create a free account. The human check stays yours. After that Cat fetches every allowed config and adds it.",
    ), 12f))
    val status = freeText(t("⏳ حساب را بساز یا وارد شو…", "⏳ Sign in or create the account…"), 12.5f, TEAL, bold = true)

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

    fun note(msg: String) { status.text = msg }

    fun finishWith(files: List<Pair<String, String>>) {
        if (files.isEmpty()) {
            note(t("حساب آمد ولی کانفیگ مجاز نبود.", "Signed in, but no allowed config."))
            pulling = false
            return
        }
        poll?.cancel()
        dialogRef?.dismiss()
        addProtonConfs(files, want)
    }

    fun takeConf(name: String, text: String) {
        if (!ProtonFreePlanner.looksLikeConf(text)) return
        if (collected.any { it.second == text }) return
        collected += name to text
        if (!pulling && collected.isNotEmpty()) finishWith(collected.toList())
    }

    fun protonCookies(): String = listOf(
        "https://account.proton.me",
        "https://account.protonvpn.com",
        "https://proton.me",
    ).mapNotNull { runCatching { android.webkit.CookieManager.getInstance().getCookie(it) }.getOrNull() }
        .filter { it.isNotBlank() }
        .joinToString("; ")

    fun pullFromSession() {
        if (pulling) return
        val session = ProtonFreePlanner.sessionFromCookies(protonCookies()) ?: return
        pulling = true
        note(t("✅ وارد شدی؛ دارم همهٔ کانفیگ‌ها را می‌گیرم…", "✅ Signed in; fetching every config…"))
        activityScope.launch(Dispatchers.IO) {
            val result = runCatching { ProtonFreePlanner.pullWireGuardFiles(session, want) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { finishWith(it) },
                    onFailure = { error ->
                        pulling = false
                        note(
                            t(
                                "خودکار نشد (${error.message ?: "error"}). اگر فایل .conf کپی کردی، همان را می‌خوانم.",
                                "Auto-fetch failed (${error.message ?: "error"}). If you copied a .conf, I will read it.",
                            ),
                        )
                    },
                )
            }
        }
    }

    fun downloadUrl(url: String, filename: String) {
        activityScope.launch(Dispatchers.IO) {
            val text = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("User-Agent", webView.settings.userAgentString ?: "CatClient")
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                body
            }.getOrDefault("")
            runOnUiThread { takeConf(filename.ifBlank { url.substringAfterLast('/') }, text) }
        }
    }

    webView.setDownloadListener { url, _, contentDisposition, _, _ ->
        val name = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE)
            .find(contentDisposition.orEmpty())?.groupValues?.get(1)?.trim()
            ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "proton.conf" }
        if (url.startsWith("http", true)) downloadUrl(url, name)
    }
    webView.webViewClient = object : android.webkit.WebViewClient() {
        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
            val url = request?.url?.toString().orEmpty()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) return false
            return true
        }

        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
            val page = url.orEmpty().lowercase(java.util.Locale.US)
            if ("pricing" in page || "plan" in page) {
                note(t("پلن رایگان را انتخاب کن (پولی را نزن)؛ بعدش خودکار می‌آید.", "Pick the free plan (not paid); then it continues on its own."))
            }
            pullFromSession()
        }
    }

    box.addView(freeButton(t("🔑 ورود", "🔑 Sign in"), accent = true) { webView.loadUrl(ProtonFreePlanner.LOGIN) }, freeLp(6))
    box.addView(freeButton(t("🆕 ساخت حساب رایگان", "🆕 Create a free account"), accent = false) { webView.loadUrl(ProtonFreePlanner.SIGNUP) }, freeLp(4))
    box.addView(webView, LinearLayout.LayoutParams(-1, dp(380)).apply { topMargin = dp(6) })
    box.addView(status, freeLp(6))

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(t("🟢 حساب رایگان پروتون", "🟢 Free Proton account"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialogRef = dialog
    dialog.setOnDismissListener {
        poll?.cancel()
        runCatching { webView.stopLoading(); webView.destroy() }
    }
    dialog.showCatClientDialog()
    webView.loadUrl(ProtonFreePlanner.LOGIN)
    poll = activityScope.launch {
        while (true) {
            delay(1_200)
            pullFromSession()
            val clip = runCatching { readClipboardConfigText().orEmpty() }.getOrDefault("")
            if (ProtonFreePlanner.looksLikeConf(clip)) takeConf("clipboard.conf", clip)
        }
    }
}

private fun MainActivity.addProtonConfs(files: List<Pair<String, String>>, want: Set<String>) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val name = "🟢 Cat Proton"
    freeProgress(t("⏳ ساخت کانفیگ‌های پروتون…", "⏳ Building Proton configs…")) { step ->
        val links = ArrayList<String>()
        val report = ArrayList<String>()
        val codes = ArrayList<String>()
        for ((filename, text) in files) {
            if (!ProtonFreePlanner.looksLikeConf(text)) {
                report += "⚠️ $filename: " + t("فایل WireGuard کامل نیست", "not a complete WireGuard file")
                continue
            }
            val code = ProtonFreePlanner.countryOfConf(text, filename)
            if (want.isNotEmpty() && code.isNotBlank() && code !in want) {
                report += "↩️ $filename: $code " + t("انتخاب نشده بود", "was not selected")
                continue
            }
            val label = ProtonFreePlanner.rowLabel(text, filename, fa)
            val rows = WireGuardConfText.toUris(text).map { link ->
                val base = link.substringBefore('#')
                val tag = java.net.URLEncoder.encode("🟢 Cat Proton · $label", "UTF-8").replace("+", "%20")
                "$base#$tag"
            }
            if (rows.isEmpty()) {
                report += "⚠️ $filename: " + t("لینک ساخته نشد", "no link built")
                continue
            }
            if (rows.any { it.startsWith("awg://") }) {
                report += "⚠️ $filename: " + t("این فایل Amnezia است نه پروتون؛ از بخش آمنزیا اضافه کنید", "this file is Amnezia, not Proton; add it in the Amnezia section")
                continue
            }
            links += rows
            if (code.isNotBlank()) codes += code
            report += "✅ $filename → ${ProtonFreePlanner.flagOf(code)} $code ×${rows.size}"
        }
        if (links.isEmpty()) {
            freeResult(t("⚠️ کانفیگی ساخته نشد", "⚠️ No config built"), report, null,
                t("فایل باید PrivateKey و PublicKey و Endpoint داشته باشد. از صفحهٔ دانلود پروتون، WireGuard را بگیرید.",
                    "The file needs PrivateKey, PublicKey and Endpoint. From Proton's downloads page, get WireGuard."))
            return@freeProgress
        }
        val (ordered, pingLines) = rankLinksByLivePing(links, step, fa)
        report += pingLines
        step(t("ذخیره به‌عنوان «$name»…", "Saving as “$name”…"))
        val text = ProtonFreePlanner.subscriptionText(ordered)
        val store = FreeNetworkStore(this@addProtonConfs)
        val subId = try {
            saveFreeSubscription(store.protonSubId, name, text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ ذخیره نشد", "❌ Not saved"), report + (e.message ?: e.javaClass.simpleName), null, "")
            return@freeProgress
        }
        store.protonSubId = subId
        renderSubscriptions()
        val countries = codes.distinct()
        report += t("کشورها: ", "Countries: ") + countries.joinToString(" ") { ProtonFreePlanner.flagOf(it) + it }
        freeResult(
            t("✅ ${links.size} سرور پروتون از ${countries.size.coerceAtLeast(1)} کشور", "✅ ${links.size} Proton servers from ${countries.size.coerceAtLeast(1)} countries"),
            report,
            subId,
            t("وایرگارد خام روی بعضی اپراتورهای ایران بسته است. اگر وصل نشد، «🦊 Cat Fox VPN» یا «🆓 Cat Free Proxy» را امتحان کنید. این کانفیگ‌ها مال حساب خودتان است، نه کلید مشترک.",
                "Plain WireGuard is blocked on some Iranian operators. If it does not connect, try “🦊 Cat Fox VPN” or “🆓 Cat Free Proxy”. These configs belong to your account, not a shared key."),
        )
    }
}

/**
 * One-tap Warp → Psiphon. Psiphon is the free circumvention tool Iranians actually use
 * (no account). The chain stays on wireguard / masque-h2 / existing Warp modes.
 */
internal fun MainActivity.showFreePsiphonDialog() {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val box = freeBox()
    var dialog: AlertDialog? = null
    box.addView(
        freeText(
            t(
                "سایفون بدون ساختن حساب کار می‌کند و پراستفاده‌ترین ابزار رایگان در ایران است. روی Cat Warp سوار می‌شود. کشور را خالی بگذارید تا خودش سریع‌ترین را بگیرد. از کانفیگ معمولی کندتر است.",
                "Psiphon needs no account and is the free tool Iranians use most. It rides on Cat Warp. Leave the country empty to pick the fastest. Slower than a normal config.",
            ),
            13f,
        ),
        freeLp(8),
    )
    box.addView(
        freeButton(t("🌀 وصل شو — کشور خودکار", "🌀 Connect — automatic country"), true) {
            dialog?.dismiss()
            connectFreePsiphon("")
        },
        freeLp(10),
    )
    box.addView(
        freeButton(t("کشور خروجی را خودم انتخاب کنم", "Pick the exit country myself"), false) {
            dialog?.dismiss()
            pickFreePsiphonCountry()
        },
        freeLp(8),
    )
    val built = MaterialAlertDialogBuilder(this)
        .setTitle(t("🌀 سایفون رایگان", "🌀 Free Psiphon"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialog = built
    built.showCatClientDialog()
}

internal fun MainActivity.pickFreePsiphonCountry() {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val regions = listOf("") + PsiphonCarrierConfig.availableRegions(this)
    val labels = regions.map { code ->
        PsiphonCarrierConfig.regionDisplayLabel(
            code,
            fa,
            t("⚡ خودکار (سریع‌ترین)", "⚡ Automatic (fastest)"),
        )
    }.toTypedArray()
    MaterialAlertDialogBuilder(this)
        .setTitle(t("کشور خروجی سایفون", "Psiphon exit country"))
        .setItems(labels) { _, idx -> connectFreePsiphon(regions[idx]) }
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
        .showCatClientDialog()
}

internal fun MainActivity.connectFreePsiphon(region: String) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val cur = aetherPreferenceStore.read()
    val simple = AetherSelection.simplifyModeForChain(cur.mode, "psiphon")
    val peer = cur.endpoint?.takeIf { it.isNotBlank() } ?: when {
        cur.ipScan == "v6" && simple == AetherMode.WireGuard -> "[2606:4700:d0::a29f:c001]:2408"
        cur.ipScan == "v6" -> "[2606:4700:d0::a29f:c001]:443"
        simple == AetherMode.WireGuard -> "162.159.192.1:2408"
        else -> "162.159.198.1:443"
    }
    val cleanRegion = region.uppercase(java.util.Locale.US).takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
    val tag = cleanRegion.ifBlank { "Auto" }
    val ready = cur.copy(
        enabled = true,
        mode = simple,
        chainTarget = "psiphon",
        exitLocation = "foreign",
        psiphonRegion = cleanRegion,
        endpoint = peer,
        endpointScannedAt = cur.endpointScannedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
        profileName = "Cat Warp → Psiphon $tag",
    )
    Toast.makeText(this, t("🌀 در حال ساخت سایفون و اتصال…", "🌀 Building Psiphon and connecting…"), Toast.LENGTH_SHORT).show()
    activityScope.launch {
        val tomlFile = java.io.File(filesDir, "aether.toml")
        if (!tomlFile.exists() || tomlFile.length() == 0L) {
            withContext(Dispatchers.IO) {
                runCatching { AetherOperations.provision(simple, tomlFile, null, ready.ipScan) }
            }
        }
        saveAetherConfigIntoMainSubscriptionList(ready, activateAetherEngine = true, replaceAll = true) {
            connectCatWarpAfterSave()
        }
    }
}

// ───────────── 🚀 Cat Warp (Cloudflare tunnel, live ping) ─────────────

internal fun MainActivity.showCatWarpFreeDialog() {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val pingBy = java.util.concurrent.ConcurrentHashMap<String, Long?>()
    val display = ArrayList(CatWarpFreePlanner.PROTOS)
    fun line(p: CatWarpFreePlanner.Proto): String {
        val ms = if (pingBy.containsKey(p.id)) {
            if (fa) LiveNetPing.labelFa(pingBy[p.id]) else LiveNetPing.labelEn(pingBy[p.id])
        } else {
            if (fa) LiveNetPing.waitingFa() else LiveNetPing.waitingEn()
        }
        return "${p.label(fa)} · ${p.hint(fa)} · $ms · ${p.host}:${p.port}"
    }
    val labels = ArrayList(display.map { line(it) })
    val adapter = ArrayAdapter(this, android.R.layout.select_dialog_item, ArrayList(labels))
    fun push() {
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
    }
    var pingJob: Job? = null
    val shown = MaterialAlertDialogBuilder(this)
        .setTitle(t("🚀 Cat Warp — پینگ زنده روی خط شما", "🚀 Cat Warp — live ping on your line"))
        .setAdapter(adapter) { dlg, idx ->
            dlg.dismiss()
            val p = display.getOrNull(idx) ?: return@setAdapter
            connectCatWarpFree(p)
        }
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    shown.setOnDismissListener { pingJob?.cancel() }
    shown.showCatClientDialog()
    pingJob = activityScope.launch(Dispatchers.IO) {
        CatWarpFreePlanner.PROTOS.forEach { p ->
            pingBy[p.id] = LiveNetPing.tcpMs(p.host, p.port)
            withContext(Dispatchers.Main) {
                if (!shown.isShowing) return@withContext
                val i = display.indexOfFirst { it.id == p.id }
                if (i >= 0) {
                    labels[i] = line(display[i])
                    push()
                }
            }
        }
        val sorted = display.sortedWith(
            compareBy(
                { pingBy[it.id] == null },
                { pingBy[it.id] ?: 9_999L },
            ),
        )
        withContext(Dispatchers.Main) {
            if (!shown.isShowing) return@withContext
            display.clear()
            display.addAll(sorted)
            labels.clear()
            display.forEach { labels += line(it) }
            push()
        }
    }
}

internal fun MainActivity.connectCatWarpFree(proto: CatWarpFreePlanner.Proto) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val cur = aetherPreferenceStore.read()
    val ready = cur.copy(
        enabled = true,
        mode = proto.mode,
        chainTarget = "none",
        exitLocation = if (proto.mode == AetherMode.MasqueInMasque) "foreign" else cur.exitLocation.ifBlank { "auto" },
        psiphonRegion = "",
        endpoint = proto.endpoint,
        ipScan = proto.ipScan,
        endpointScannedAt = System.currentTimeMillis(),
        profileName = "Cat Warp · ${proto.label(fa)}",
    )
    Toast.makeText(
        this,
        t("🚀 در حال ساخت Cat Warp (${proto.label(fa)})…", "🚀 Building Cat Warp (${proto.labelEn})…"),
        Toast.LENGTH_SHORT,
    ).show()
    activityScope.launch {
        val tomlFile = java.io.File(filesDir, "aether.toml")
        if (!tomlFile.exists() || tomlFile.length() == 0L) {
            withContext(Dispatchers.IO) {
                runCatching { AetherOperations.provision(proto.mode, tomlFile, null, proto.ipScan) }
            }
        }
        saveAetherConfigIntoMainSubscriptionList(ready, activateAetherEngine = true, replaceAll = true) {
            connectCatWarpAfterSave()
        }
    }
}

// ───────────── 🌬️ Cat Wind ─────────────

internal fun MainActivity.showCatWindDialog() {
    val store = FreeNetworkStore(this)
    if (CatWindClient(store).cachedSession() != null) {
        connectCatWind()
        return
    }
    showCatWindLogin { connectCatWind() }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun MainActivity.showCatWindLogin(after: () -> Unit) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    var dialogRef: AlertDialog? = null
    var poll: Job? = null
    var pulling = false

    val box = freeBox()
    box.addView(freeText(t(
        "وارد شو یا حساب رایگان بساز. تیک ربات با توست. بعد از ورود Cat خودش همهٔ کانفیگ‌ها را می‌گیرد و اضافه می‌کند.",
        "Sign in or create a free account. The human check stays yours. After that Cat fetches every allowed config and adds it.",
    ), 12f))
    val status = freeText(t("⏳ حساب را بساز یا وارد شو…", "⏳ Sign in or create the account…"), 12.5f, TEAL, bold = true)

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
    if (android.os.Build.VERSION.SDK_INT >= 21) {
        runCatching { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) }
    }

    fun note(msg: String) { status.text = msg }
    var lastUrl = CatWindPlanner.LOGIN_PAGE

    fun windCookies(): String {
        runCatching { android.webkit.CookieManager.getInstance().flush() }
        return listOf(
            "https://windscribe.com",
            "https://www.windscribe.com",
            "https://api.windscribe.com",
            "https://res.windscribe.com",
        ).mapNotNull { runCatching { android.webkit.CookieManager.getInstance().getCookie(it) }.getOrNull() }
            .filter { it.isNotBlank() }
            .joinToString("; ")
    }

    fun pullFromSession(hash: String?, pageUrl: String? = lastUrl) {
        val cookies = windCookies()
        val auth = CatWindPlanner.sessionHashFromText(hash)
            ?: CatWindPlanner.sessionHashFromCookies(cookies)
            ?: CatWindPlanner.sessionHashFromUrl(pageUrl)
        if (auth == null && !CatWindPlanner.looksLoggedIn(pageUrl)) return
        if (pulling) return
        pulling = true
        note(t("✅ وارد شدی؛ دارم همهٔ کانفیگ‌ها را می‌گیرم…", "✅ Signed in; fetching every config…"))
        activityScope.launch(Dispatchers.IO) {
            val result = runCatching {
                withPhysicalNet {
                    val client = CatWindClient(FreeNetworkStore(this@showCatWindLogin))
                    if (auth != null) client.adoptAuth(auth) else client.adoptFromWeb(cookies)
                }
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        poll?.cancel()
                        dialogRef?.dismiss()
                        after()
                    },
                    onFailure = { error ->
                        pulling = false
                        note(
                            t(
                                "خودکار نشد (${error.message ?: "error"}). دوباره وارد شو.",
                                "Auto-fetch failed (${error.message ?: "error"}). Sign in again.",
                            ),
                        )
                    },
                )
            }
        }
    }

    webView.webViewClient = object : android.webkit.WebViewClient() {
        override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
            val url = request?.url?.toString().orEmpty()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) return false
            return true
        }

        override fun shouldInterceptRequest(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
            val url = request?.url?.toString().orEmpty()
            val q = request?.url?.getQueryParameter("session_auth_hash")
                ?: request?.url?.getQueryParameter("auth_hash")
            if (!q.isNullOrBlank() || CatWindPlanner.looksLoggedIn(url)) {
                view?.post { pullFromSession(q, url) }
            }
            return null
        }

        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
            lastUrl = url.orEmpty().ifBlank { lastUrl }
            pullFromSession(null, lastUrl)
            runCatching {
                webView.evaluateJavascript(CatWindPlanner.SESSION_SCAN_JS) { raw ->
                    pullFromSession(jsString(raw), lastUrl)
                }
            }
        }
    }

    box.addView(freeButton(t("🔑 ورود", "🔑 Sign in"), accent = true) { webView.loadUrl(CatWindPlanner.LOGIN_PAGE) }, freeLp(6))
    box.addView(freeButton(t("🆕 ساخت حساب رایگان", "🆕 Create a free account"), accent = false) { webView.loadUrl(CatWindPlanner.SIGNUP) }, freeLp(4))
    box.addView(webView, LinearLayout.LayoutParams(-1, dp(380)).apply { topMargin = dp(6) })
    box.addView(status, freeLp(6))

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(t("🌬️ ورود Cat Wind", "🌬️ Cat Wind sign-in"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialogRef = dialog
    dialog.setOnDismissListener {
        poll?.cancel()
        runCatching { webView.stopLoading(); webView.destroy() }
    }
    dialog.showCatClientDialog()
    webView.loadUrl(CatWindPlanner.LOGIN_PAGE)
    poll = activityScope.launch {
        while (true) {
            delay(1_200)
            pullFromSession(null, lastUrl)
            runCatching {
                webView.evaluateJavascript(CatWindPlanner.SESSION_SCAN_JS) { raw ->
                    pullFromSession(jsString(raw), lastUrl)
                }
            }
        }
    }
}

private fun MainActivity.connectCatWind(codes: Set<String> = emptySet()) {
    val fa = freeFa()
    fun t(f: String, e: String) = if (fa) f else e
    val store = FreeNetworkStore(this)
    val client = CatWindClient(store)
    if (client.cachedSession() == null) {
        showCatWindLogin { connectCatWind(codes) }
        return
    }
    val name = CatWindPlanner.NAME
    freeProgress(t("⏳ ساخت کانفیگ‌های Cat Wind…", "⏳ Building Cat Wind configs…")) { step ->
        step(t("۱) فهرست شهرها…", "1) City list…"))
        val session = client.cachedSession() ?: throw CancellationException()
        val locs = try {
            withContext(Dispatchers.IO) { withPhysicalNet { client.locations(session.premium) } }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ فهرست نیامد", "❌ List failed"), listOf(e.message ?: e.javaClass.simpleName), null,
                t("اگر این سایت از اینجا بسته است، با یک کانفیگ سالم وصل شو و دوباره بزن.", "If this site is blocked here, connect with a working config and try again."))
            return@freeProgress
        }
        val chosen = CatWindPlanner.pick(locs, codes, session.premium)
        if (chosen.isEmpty()) {
            freeResult(t("⚠️ شهری در پلن رایگان نبود", "⚠️ No city on the free plan"), client.log, null,
                t("پلن رایگان فقط چند شهر دارد؛ پلن پولی جعل نمی‌شود.", "The free plan only has a handful of cities; a paid plan is not faked."))
            return@freeProgress
        }
        step(t("۲) گرفتن همهٔ کانفیگ‌ها…", "2) Fetching every config…"))
        val links = ArrayList<String>()
        val errors = ArrayList<String>()
        try {
            val creds = withContext(Dispatchers.IO) { withPhysicalNet { client.credentials(session) } }
            links += chosen.map { CatWindPlanner.httpsLink(creds.first, creds.second, it, fa) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            errors += e.message ?: e.javaClass.simpleName
        }
        val wgPool = chosen.filter { it.canWireGuard() }
        if (wgPool.isNotEmpty()) {
            val ranked = withContext(Dispatchers.IO) {
                wgPool.map { it to client.ping(it, proto = CatWindPlanner.Proto.WireGuard) }
                    .sortedWith(compareBy({ !it.second.reachable }, { it.second.rttMs ?: 9_999L }))
                    .map { it.first }
                    .take(CatWindPlanner.WG_MAX)
            }
            for (loc in ranked) {
                try {
                    links += withContext(Dispatchers.IO) { withPhysicalNet { client.wgLink(session, loc, fa) } }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    errors += "${loc.hostname}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        if (links.isEmpty()) {
            if (errors.any { it.contains("expired", true) || it.contains("session", true) }) client.forget()
            freeResult(
                t("❌ کانفیگ ساخته نشد", "❌ No config was built"),
                client.log + errors,
                null,
                t("دوباره وارد شو. پلن پولی جعل نمی‌شود.", "Sign in again. A paid plan is not faked."),
            )
            return@freeProgress
        }
        val (ordered, pingLines) = rankLinksByLivePing(links, step, fa)
        step(t("۳) ذخیره به‌عنوان «$name»…", "3) Saving as “$name”…"))
        val subId = try {
            saveFreeSubscription(store.windSubId, name, CatWindPlanner.subscriptionText(ordered))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            freeResult(t("❌ ذخیره نشد", "❌ Not saved"), client.log + pingLines + errors + (e.message ?: e.javaClass.simpleName), null, "")
            return@freeProgress
        }
        store.windSubId = subId
        renderSubscriptions()
        val report = ArrayList<String>()
        report += client.log
        report += t("شهرها: ${chosen.size}", "Cities: ${chosen.size}")
        report += pingLines
        report += errors
        freeResult(
            t("✅ ${ordered.size} سرور Cat Wind آماده است", "✅ ${ordered.size} Cat Wind servers ready"),
            report,
            subId,
            t("«⚡ اتصال» را بزن. Stealth روی TCP ۴۴۳ و WireGuard روی UDP ۴۴۳ است. اگر یکی وصل نشد بعدی را از فهرست بزن.",
                "Tap “⚡ Connect”. Stealth is TCP 443 and WireGuard is UDP 443. If one fails, pick the next in the list."),
        )
    }
}

/** evaluateJavascript returns a JSON string literal; decode it. */
private fun jsString(raw: String?): String = runCatching { org.json.JSONArray("[" + (raw ?: "\"\"") + "]").optString(0) }.getOrDefault("")
