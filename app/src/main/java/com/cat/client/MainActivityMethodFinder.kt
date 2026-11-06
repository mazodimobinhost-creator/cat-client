package com.cat.client

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cat.client.MainActivity.Companion.SCREEN_HOME
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// beta246 🧭 «Which method works on my internet?» + 🆓 free WireGuard/Hysteria2 servers (UI).
// Pure choices: MethodFinderPlan.kt · fetching: MethodFinderSources.kt · real test: the service's delay test
// (CatClientVpnServiceConnectionTests.kt). See docs/CODEMAP.md.

private fun MainActivity.finderFa(): Boolean = appLanguagePreferenceStore.read() == AppLanguage.Persian

/** One line: «✅ 🚀 Hysteria2 140ms · ✅ 🛡️ WireGuard 320ms · ❌ 🐱 Cat Warp». */
internal fun MainActivity.methodFinderSummaryLine(results: List<FinderFamilyResult>): String {
    val fa = finderFa()
    return MethodFinderPlan.ranked(results).filter { it.tested > 0 }.joinToString(" · ") { r ->
        val name = r.family.emoji + " " + (if (fa) r.family.fa else r.family.en).substringBefore(" (")
        if (r.working > 0) "✅ $name ${r.bestMs ?: "?"}ms" else "❌ $name"
    }
}

/** Card at the top of the «🛡️ اتصال اضطراری و ضدسانسور» tab. */
internal fun MainActivity.buildMethodFinderCard(): View {
    val fa = finderFa()
    fun t(f: String, e: String) = if (fa) f else e
    val panel = advancedSettingsPanel()
    panel.addView(
        TextView(this).apply {
            text = t("🧭 کدام روش روی اینترنت من کار می‌کند؟", "🧭 Which method works on my internet?")
            textSize = 15f
            typeface = CatClientBodyBoldTypeface
            setTextColor(TEAL)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    panel.addView(
        advancedSectionDetail(
            t(
                "«بررسی» را بزنید: برنامه همهٔ روش‌هایش (Cat Warp، سرورهای رایگان Hysteria2 و وایرگارد، کانفیگ‌های خودتان، Cat Fox VPN و SSTP) را واقعاً روی همین اینترنت امتحان می‌کند و بهترین را بالا می‌آورد. لازم نیست تا آخر صبر کنید؛ هر وقت چند روش سبز شد «⏹ توقف» بزنید، نتیجه‌ها می‌مانند. بعد «اتصال» کنار روش سبز را بزنید.",
                "Tap Check: the app really tries every method it has (Cat Warp, free Hysteria2 and WireGuard servers, your own configs, Cat Fox VPN and SSTP) on this internet and puts the best on top. No need to wait for the end: tap ⏹ Stop once a few are green; results stay. Then tap Connect next to a green method.",
            ),
        ),
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
    )
    val (last, at) = MethodFinderStore.read(this, NetworkContext.key(this))
    if (last.isNotEmpty() && at > 0L) {
        panel.addView(
            TextView(this).apply {
                val ago = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0L)
                val agoText = when {
                    ago < 1 -> t("همین الان", "just now")
                    ago < 60 -> t("$ago دقیقه پیش", "$ago min ago")
                    ago < 48 * 60 -> t("${ago / 60} ساعت پیش", "${ago / 60} h ago")
                    else -> t("${ago / 1440} روز پیش", "${ago / 1440} days ago")
                }
                text = t("آخرین بررسی روی همین شبکه ($agoText): ", "Last check on this network ($agoText): ") + methodFinderSummaryLine(last)
                textSize = 12.5f
                setTextColor(TEXT_SECONDARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
    }
    panel.addView(
        finderButton(t("🔎 بررسی همهٔ روش‌ها روی اینترنت من", "🔎 Check every method on my internet"), accent = true) {
            showMethodFinderDialog(FinderFamily.entries.toSet(), freeOnly = false)
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
    )
    // beta247: free networks that need no config of your own (MainActivityFreeNetworks.kt).
    panel.addView(
        finderButton(t("🎁 شبکه‌های رایگان (کانفیگ لازم نیست)", "🎁 Free networks (no config needed)"), accent = false) { showFreeNetworksHub() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
    )
    return panel
}

private fun MainActivity.finderButton(label: String, accent: Boolean, onClick: () -> Unit) = MaterialButton(this).apply {
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

/**
 * The finder dialog. [freeOnly] = the «🆓 free WireGuard/Hysteria2 servers» entry of the Amnezia/WireGuard section:
 * same real test, only the free-server methods, its own ready-to-use subscription.
 */
internal fun MainActivity.showMethodFinderDialog(families: Set<FinderFamily>, freeOnly: Boolean) {
    val fa = finderFa()
    fun t(f: String, e: String) = if (fa) f else e
    val subName = if (freeOnly) MethodFinderPlan.FREE_SUBSCRIPTION_NAME else MethodFinderPlan.SUBSCRIPTION_NAME
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }
    box.addView(
        TextView(this).apply {
            text = if (freeOnly) {
                t(
                    "برنامه فهرست‌های عمومی سرور رایگان Hysteria2 و وایرگارد را (که صاحبانشان مرتب تازه می‌کنند) می‌گیرد، سرورهای مشکوک را کنار می‌گذارد (مثلاً Hysteria2ای که فقط با خاموش‌کردن بررسی گواهی کار می‌کند) و بقیه را واقعاً از داخل خودشان امتحان می‌کند. سرورهای سالم در سابسکریپشن «$subName» می‌مانند؛ «اتصال» سریع‌ترین را وصل می‌کند. سرورهای رایگان عمومی‌اند: برای کارهای حساس از آن‌ها استفاده نکنید.",
                    "The app fetches the public free Hysteria2 and WireGuard lists (refreshed often by their owners), drops doubtful servers (e.g. Hysteria2 that only works with certificate checks off) and really tests the rest through themselves. Working servers stay in the “$subName” subscription; Connect uses the fastest. Free servers are public: do not use them for sensitive things.",
                )
            } else {
                t(
                    "هر روش با یک درخواست واقعی از داخل خودش امتحان می‌شود، نه فقط پینگ؛ پس «✅» یعنی واقعاً اینترنت می‌دهد. اول از هر روش یکی امتحان می‌شود تا زود جواب بگیرید. اگر الان وصل هستید، برای آزمون درست روی اینترنت خودتان اتصال چند لحظه قطع می‌شود (اول می‌پرسد).",
                    "Each method is tried with a real request through it, not just a ping, so ✅ means it really gives internet. One of each method is tried first so you get answers quickly. If you are connected now, the connection is paused for an honest test on your own internet (it asks first).",
                )
            }
            textSize = 13f
            setTextColor(TEXT_SECONDARY)
            setLineSpacing(0f, 1.15f)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        },
        LinearLayout.LayoutParams(-1, -2),
    )
    val statusView = TextView(this).apply {
        textSize = 14f
        typeface = CatClientBodyBoldTypeface
        setTextColor(TEXT_PRIMARY)
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
    }
    box.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    var onMain: () -> Unit = {}
    val mainButton = finderButton(t("🔎 بررسی", "🔎 Check"), accent = true) { onMain() }
    box.addView(mainButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    if (!freeOnly) {
        box.addView(
            TextView(this).apply {
                text = t(
                    "سایفون و تور اینجا امتحان نمی‌شوند: سایفون در Cat پشت Cat Warp سوار می‌شود و آزمون جدا فقط بخش وارپ را می‌سنجد؛ تور هم چند دقیقه طول می‌کشد تا راه بیفتد. برای آن‌ها از کارت «موتورهای ضدسانسور» همین صفحه مستقیم وصل شوید.",
                    "Psiphon and Tor are not tested here: in Cat, Psiphon rides behind Cat Warp and a separate test would only measure the WARP part; Tor needs minutes to start. Connect to them directly from the anti-censorship engines card on this page.",
                )
                textSize = 12f
                setTextColor(palette.textTertiary)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) },
        )
    }

    var candidates: List<FinderCandidate> = emptyList()
    var skipped: Map<FinderFamily, String> = emptyMap()
    var tested: Set<Int> = emptySet()
    var delays: Map<Int, Int> = emptyMap()
    var subId: String? = null
    var running = false
    var finished = false
    var testId: String? = null
    var job: Job? = null
    var dialogRef: androidx.appcompat.app.AlertDialog? = null

    fun stateLine(r: FinderFamilyResult): Pair<String, Int> = when {
        r.working > 0 -> t(
            "✅ کار می‌کند · بهترین ${r.bestMs}ms · ${r.working} از ${r.tested} سالم",
            "✅ Works · best ${r.bestMs} ms · ${r.working} of ${r.tested} working",
        ) to TEAL
        r.tested >= r.total -> t(
            "❌ روی این اینترنت کار نکرد (${r.total} مورد امتحان شد)",
            "❌ Did not work on this internet (${r.total} tried)",
        ) to palette.red
        r.tested == 0 && finished -> t("⏸ نوبتش نرسید (توقف زدید)", "⏸ Not reached (stopped)") to palette.textTertiary
        r.tested == 0 -> t("⏳ در صف · ${r.total} مورد", "⏳ Queued · ${r.total}") to TEXT_SECONDARY
        finished -> t("⏸ ${r.tested} از ${r.total} امتحان شد، هیچ‌کدام کار نکرد", "⏸ ${r.tested} of ${r.total} tried, none worked") to palette.amber
        else -> t("⏳ ${r.tested} از ${r.total} امتحان شد…", "⏳ ${r.tested} of ${r.total} tried…") to palette.amber
    }

    fun renderRows() {
        rows.removeAllViews()
        val results = MethodFinderPlan.summarize(candidates, tested, delays)
        val shown = if (finished) MethodFinderPlan.ranked(results) else results
        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(withAlpha(palette.surfaceElevated2, 200))
            }
        }
        val overall = results.filter { it.working > 0 && it.bestIndex != null }.minByOrNull { it.bestMs ?: Int.MAX_VALUE }
        val sidTop = subId
        if (finished && overall != null && sidTop != null) {
            val label = (if (fa) overall.family.fa else overall.family.en).substringBefore(" (")
            rows.addView(
                finderButton(t("⭐ اتصال به بهترین روش ($label · ${overall.bestMs}ms)", "⭐ Connect with the best method ($label · ${overall.bestMs} ms)"), accent = true) {
                    dialogRef?.dismiss()
                    connectFinderCandidate(sidTop, overall.bestIndex!!)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
            )
        }
        shown.forEach { r ->
            val (line, color) = stateLine(r)
            val c = card()
            c.addView(TextView(this).apply {
                text = r.family.title(fa)
                textSize = 14f
                typeface = CatClientBodyBoldTypeface
                setTextColor(TEXT_PRIMARY)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            })
            c.addView(TextView(this).apply {
                text = line
                textSize = 12.5f
                setTextColor(color)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            })
            val best = r.bestIndex
            val sid = subId
            if (finished && r.working > 0 && best != null && sid != null) {
                c.addView(
                    finderButton(t("🚀 اتصال با بهترینِ این روش", "🚀 Connect with the best of this method"), accent = true) {
                        dialogRef?.dismiss()
                        connectFinderCandidate(sid, best)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
                )
            }
            rows.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        skipped.forEach { (f, why) ->
            val c = card()
            c.addView(TextView(this).apply {
                text = f.title(fa)
                textSize = 14f
                typeface = CatClientBodyBoldTypeface
                setTextColor(palette.textTertiary)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            })
            c.addView(TextView(this).apply {
                text = "— $why"
                textSize = 12f
                setTextColor(palette.textTertiary)
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            })
            rows.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    fun cancelServiceTest() {
        val id = testId ?: return
        runCatching {
            startService(
                Intent(this, CatClientVpnService::class.java)
                    .setAction(Actions.CANCEL_CONNECTION_DELAY_TEST)
                    .putExtra(Actions.EXTRA_APP_INITIATED, true)
                    .putExtra(Actions.EXTRA_DELAY_TEST_ID, id),
            )
        }
    }

    fun idle(message: String) {
        running = false
        finished = true
        testId = null
        statusView.text = message
        mainButton.text = t("🔄 دوباره بررسی", "🔄 Check again")
        renderRows()
    }

    suspend fun runFinder() {
        running = true
        finished = false
        candidates = emptyList()
        skipped = emptyMap()
        tested = emptySet()
        delays = emptyMap()
        mainButton.text = t("⏹ توقف", "⏹ Stop")
        statusView.text = if (families.any { it in FinderFamily.FREE_SERVERS }) {
            t("⏳ جمع‌کردن روش‌ها و گرفتن فهرست سرورهای رایگان…", "⏳ Collecting methods and fetching the free-server lists…")
        } else {
            t("⏳ جمع‌کردن روش‌ها…", "⏳ Collecting methods…")
        }
        renderRows()
        // Lists are fetched BEFORE pausing a live connection: if the user is connected, the lists come through it.
        val gathered = withContext(Dispatchers.IO) { MethodFinderSources.gather(this@showMethodFinderDialog, families, fa) }
        skipped = gathered.skipped
        val ordered = MethodFinderPlan.numbered(MethodFinderPlan.interleave(gathered.candidates))
        if (ordered.isEmpty()) {
            idle(t("چیزی برای امتحان پیدا نشد (دلیلش زیر هر روش نوشته شده).", "Nothing to test was found (the reason is under each method)."))
            return
        }
        candidates = ordered
        renderRows()

        if (buttonModel.state != VpnState.Stopped) {
            if (!confirmFinderDisconnect(fa)) {
                candidates = emptyList()
                idle(t("لغو شد: آزمون درست روی اینترنت خودتان فقط با قطع موقت اتصال فعلی ممکن است.", "Cancelled: an honest test on your own internet needs the current connection paused."))
                return
            }
            statusView.text = t("⏳ قطع موقت اتصال فعلی…", "⏳ Pausing the current connection…")
            startVpnService(Actions.DISCONNECT)
            val until = SystemClock.elapsedRealtime() + 20_000L
            while (buttonModel.state != VpnState.Stopped && SystemClock.elapsedRealtime() < until) delay(300)
            if (buttonModel.state != VpnState.Stopped) {
                idle(t("اتصال قطع نشد؛ چند ثانیه بعد دوباره «بررسی» را بزنید.", "The connection did not stop; tap Check again in a few seconds."))
                return
            }
            delay(800) // let the phone's own network settle before measuring it
        }

        statusView.text = t("⏳ آماده‌کردن ${candidates.size} مورد برای آزمون…", "⏳ Preparing ${candidates.size} items for the test…")
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val text = candidates.joinToString("\n") { it.link }
                val existing = userSubscriptionManager.list().firstOrNull { it.name == subName }
                if (existing != null) userSubscriptionManager.update(existing.id, subName, text) else userSubscriptionManager.add(subName, text)
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            idle(t("فهرست آزمون ساخته نشد: ", "Could not build the test list: ") + (e.message ?: e.javaClass.simpleName))
            return
        }
        subId = saved.id
        renderSubscriptions()
        val profiles = withContext(Dispatchers.IO) { userSubscriptionManager.cachedSnapshot(saved.id)?.catalog?.profiles.orEmpty() }
        val indexByFp = profiles.mapNotNull { p ->
            MethodFinderPlan.indexOf(p.tag)?.takeIf { it < candidates.size }?.let { p.fingerprint to it }
        }.toMap()
        // A row the converter refused (broken link) counts as tested-and-failed, so nothing waits forever.
        val refused = candidates.indices.toSet() - indexByFp.values.toSet()
        tested = refused
        if (indexByFp.isEmpty()) {
            idle(t("هیچ‌کدام از موردها قابل خواندن نبود.", "None of the items could be read."))
            return
        }
        val id = SystemClock.elapsedRealtimeNanos().toString()
        testId = id
        val fps = profiles.map { it.fingerprint }.filter { it in indexByFp }
        ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = id,
                subscriptionId = saved.id,
                connectionTypes = emptySet(),
                targetFingerprints = fps,
                status = Actions.DELAY_TEST_PREPARING,
                total = fps.size,
            ),
        )
        startForegroundService(
            Intent(this, CatClientVpnService::class.java)
                .setAction(Actions.TEST_CONNECTION_DELAYS)
                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                .putExtra(Actions.EXTRA_DELAY_TEST_ID, id)
                .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, saved.id)
                .putStringArrayListExtra(Actions.EXTRA_CONNECTION_TYPES, ArrayList<String>())
                .putStringArrayListExtra(Actions.EXTRA_CONNECTION_FINGERPRINTS, ArrayList(fps)),
        )
        val networkKey = NetworkContext.key(this)
        var endStatus = Actions.DELAY_TEST_FAILED
        var endError = ""
        // Hard ceiling so a stuck run can never hold the dialog: rows × timeout is far below this.
        val deadline = SystemClock.elapsedRealtime() + 15 * 60_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(600)
            val snap = ConnectionDelayTestState.snapshot(saved.id) ?: continue
            if (snap.testId != id) {
                endError = t("آزمون دیگری شروع شد.", "Another test started.")
                break
            }
            tested = refused + snap.finishedFingerprints.mapNotNull { indexByFp[it] }
            delays = snap.delays.mapNotNull { (fp, ms) -> indexByFp[fp]?.let { it to ms } }.toMap()
            val workingMethods = MethodFinderPlan.summarize(candidates, tested, delays).count { it.working > 0 }
            statusView.text = t(
                "⏳ ${tested.size} از ${candidates.size} · $workingMethods روش کار کرد",
                "⏳ ${tested.size} of ${candidates.size} · $workingMethods method(s) work",
            )
            renderRows()
            MethodFinderStore.save(this, networkKey, MethodFinderPlan.summarize(candidates, tested, delays))
            if (!snap.isRunning) {
                endStatus = snap.status
                endError = snap.error
                break
            }
        }
        if (SystemClock.elapsedRealtime() >= deadline) cancelServiceTest()
        val summary = MethodFinderPlan.summarize(candidates, tested, delays)
        MethodFinderStore.save(this, networkKey, summary)
        // The saved list keeps only what worked here, fastest first: it is ready to use from the subscriptions list.
        if (delays.isNotEmpty()) {
            val keep = delays.entries.sortedBy { it.value }.joinToString("\n") { candidates[it.key].link }
            withContext(Dispatchers.IO) { runCatching { userSubscriptionManager.update(saved.id, subName, keep) } }
            renderSubscriptions()
        }
        val n = summary.count { it.working > 0 }
        idle(
            when (endStatus) {
                Actions.DELAY_TEST_COMPLETED -> if (n > 0) {
                    t("✅ بررسی تمام شد · $n روش کار کرد. سالم‌ها در «$subName» هستند.", "✅ Done · $n method(s) work. Working ones are in “$subName”.")
                } else {
                    t("❌ بررسی تمام شد؛ هیچ روشی روی این اینترنت کار نکرد. شبکه را عوض کنید (وای‌فای/سیم دیگر) و دوباره امتحان کنید.", "❌ Done; no method worked on this internet. Try another network (Wi-Fi / other SIM) and check again.")
                }
                Actions.DELAY_TEST_CANCELED -> t("⏹ متوقف شد · نتیجه‌های تا اینجا نگه داشته شد ($n روش کار کرد).", "⏹ Stopped · results so far are kept ($n method(s) work).")
                else -> t("⚠️ آزمون کامل نشد: ", "⚠️ The test did not finish: ") + endError.ifBlank { t("نامشخص", "unknown") }
            },
        )
    }

    onMain = {
        if (running) {
            if (testId != null) {
                statusView.text = t("⏳ در حال توقف… (موردی که در حال آزمون است تمام می‌شود)", "⏳ Stopping… (the item being tested finishes)")
                cancelServiceTest()
            } else {
                job?.cancel()
                idle(t("⏹ متوقف شد.", "⏹ Stopped."))
            }
        } else {
            job = activityScope.launch {
                try {
                    runFinder()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    idle(t("⚠️ خطا: ", "⚠️ Error: ") + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    val scroll = ScrollView(this).apply { addView(box) }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(if (freeOnly) t("🆓 سرورهای رایگان WireGuard و Hysteria2", "🆓 Free WireGuard & Hysteria2 servers") else t("🧭 کدام روش روی اینترنت من کار می‌کند؟", "🧭 Which method works on my internet?"))
        .setView(scroll)
        .setNegativeButton(t("بستن", "Close"), null)
        .create()
    dialog.setOnDismissListener {
        // Closing mid-run stops the test cleanly; what finished is already remembered for this network.
        if (running) {
            cancelServiceTest()
            if (testId == null) job?.cancel()
        }
    }
    dialogRef = dialog
    statusView.text = t("آماده · «🔎 بررسی» را بزنید.", "Ready · tap 🔎 Check.")
    dialog.showCatClientDialog()
    // Start right away: the user opened this to get an answer.
    onMain()
}

private suspend fun MainActivity.confirmFinderDisconnect(fa: Boolean): Boolean = suspendCancellableCoroutine { cont ->
    fun t(f: String, e: String) = if (fa) f else e
    val d = MaterialAlertDialogBuilder(this)
        .setTitle(t("قطع موقت اتصال؟", "Pause the connection?"))
        .setMessage(
            t(
                "برای اینکه نتیجه مال اینترنت خود شما باشد (نه اتصال فعلی)، اتصال چند لحظه قطع می‌شود. بعد از بررسی با «اتصال» کنار روش سبز دوباره وصل شوید.",
                "So the result reflects your own internet (not the current connection), the connection is paused. After the check, reconnect with Connect next to a green method.",
            ),
        )
        .setPositiveButton(t("قطع کن و بررسی کن", "Pause and check")) { _, _ -> if (cont.isActive) cont.resume(true) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
        .create()
    d.setOnDismissListener { if (cont.isActive) cont.resume(false) }
    d.showCatClientDialog()
}

/** Connects to candidate «#[index]» of the finder subscription (Cat Warp rows switch Cat Warp on, others off). */
internal fun MainActivity.connectFinderCandidate(subscriptionId: String, index: Int) {
    val fa = finderFa()
    activityScope.launch {
        val profile = withContext(Dispatchers.IO) {
            userSubscriptionManager.cachedSnapshot(subscriptionId)?.catalog?.profiles.orEmpty()
                .firstOrNull { MethodFinderPlan.indexOf(it.tag) == index }
        }
        if (profile == null) {
            Toast.makeText(this@connectFinderCandidate, if (fa) "این مورد دیگر در فهرست نیست؛ دوباره بررسی کنید." else "This item is no longer in the list; check again.", Toast.LENGTH_LONG).show()
            return@launch
        }
        if (buttonModel.state != VpnState.Stopped) {
            startVpnService(Actions.DISCONNECT)
            val until = SystemClock.elapsedRealtime() + 15_000L
            while (buttonModel.state != VpnState.Stopped && SystemClock.elapsedRealtime() < until) delay(300)
        }
        userSubscriptionManager.select(subscriptionId)
        orbotEnginePreferenceStore.setEnabled(false)
        onSubscriptionSelected()
        handleConnectionSelected(profile, emptySet())
        openScreen(SCREEN_HOME)
        if (buttonModel.state == VpnState.Stopped) beginConnectFlow(Actions.CONNECT)
    }
}
