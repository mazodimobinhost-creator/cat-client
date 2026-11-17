package com.cat.client

import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// beta247 🧪 find the TLS-fragmenting method that works on this internet (UI). Engine: DpiPresetTester.kt.

private fun MainActivity.dpiFa(): Boolean = appLanguagePreferenceStore.read() == AppLanguage.Persian

/** The user's own TLS servers: the selected subscription first, then the others. */
private fun MainActivity.dpiTestTargets(): List<DpiTarget> {
    val selected = userSubscriptionManager.selectedId()
    val ids = listOf(selected) + userSubscriptionManager.list().map { it.id }.filter { it != selected }
    val raws = ids.distinct().flatMap { id -> userSubscriptionManager.cachedSnapshot(id)?.summary?.proxies.orEmpty().map { it.rawYaml } }
    return DpiPresetPlan.targets(raws)
}

/** [onApplied] gets the preset id the user switched on (so the chips row can follow). */
internal fun MainActivity.showDpiPresetTesterDialog(onApplied: (String) -> Unit = {}) {
    val fa = dpiFa()
    fun t(f: String, e: String) = if (fa) f else e
    activityScope.launch {
        val targets = withContext(Dispatchers.IO) { runCatching { dpiTestTargets() }.getOrDefault(emptyList()) }
        if (targets.isEmpty()) {
            MaterialAlertDialogBuilder(this@showDpiPresetTesterDialog)
                .setTitle(t("🧪 پیدا کردن روش تکه‌تکه‌کردن", "🧪 Find the fragmenting method"))
                .setMessage(t(
                    "این آزمون روی سرورهای خودتان انجام می‌شود (کانفیگ‌های VLESS / Trojan / VMess با TLS). هنوز چنین کانفیگی اضافه نکرده‌اید؛ اول کانفیگ یا پنل خود را اضافه کنید.",
                    "This test runs against your own servers (VLESS / Trojan / VMess configs with TLS). You have not added one yet; add your config or panel first.",
                ))
                .setPositiveButton(android.R.string.ok, null)
                .create().showCatClientDialog()
            return@launch
        }
        val names = targets.joinToString("\n") { "• " + it.sni }
        MaterialAlertDialogBuilder(this@showDpiPresetTesterDialog)
            .setTitle(t("🧪 کدام روش تکه‌تکه‌کردن روی اینترنت من کار می‌کند؟", "🧪 Which fragmenting method works on my internet?"))
            .setMessage(t(
                "اینو بزنی: برنامه اتصال به سرورهای خودتان را یک بار مستقیم و بعد با هر ۷ روش تکه‌تکه‌کردن TLS واقعاً امتحان می‌کند (حدود یک دقیقه) و بهترین را پیشنهاد می‌دهد. برای آزمون، اتصال فعلی چند لحظه قطع می‌شود.\n\nسرورها:\n$names",
                "Tap: the app really tries reaching your own servers once directly and then with each of the 7 TLS fragmenting methods (about a minute) and suggests the best. The current connection stops briefly for the test.\n\nServers:\n$names",
            ))
            .setPositiveButton(t("🔎 شروع آزمون", "🔎 Start the test")) { _, _ -> runDpiPresetTest(targets, onApplied) }
            .setNegativeButton(android.R.string.cancel, null)
            .create().showCatClientDialog()
    }
}

private fun MainActivity.runDpiPresetTest(targets: List<DpiTarget>, onApplied: (String) -> Unit) {
    val fa = dpiFa()
    fun t(f: String, e: String) = if (fa) f else e
    val cancelled = AtomicBoolean(false)
    val log = TextView(this).apply { textSize = 12.5f; setTextColor(TEXT_PRIMARY) }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(6)); addView(log) }
    val progress = MaterialAlertDialogBuilder(this)
        .setTitle(t("⏳ آزمون روش‌های تکه‌تکه‌کردن…", "⏳ Testing fragmenting methods…"))
        .setView(ScrollView(this).apply { addView(box) })
        .setNegativeButton(t("توقف", "Stop")) { _, _ -> cancelled.set(true) }
        .setCancelable(false)
        .create()
    progress.showCatClientDialog()
    val lines = mutableListOf<String>()
    activityScope.launch {
        try {
            if (buttonModel.state != VpnState.Stopped) {
                lines += t("قطع اتصال فعلی برای آزمون…", "Stopping the current connection for the test…"); log.text = lines.joinToString("\n")
                startVpnService(Actions.DISCONNECT)
                val until = SystemClock.elapsedRealtime() + 15_000L
                while (buttonModel.state != VpnState.Stopped && SystemClock.elapsedRealtime() < until) delay(300)
                if (buttonModel.state != VpnState.Stopped) {
                    progress.dismiss()
                    showDpiResult(t("❌ اتصال قطع نشد", "❌ The connection did not stop"), t("چند ثانیه بعد دوباره امتحان کنید.", "Try again in a few seconds."), null, onApplied)
                    return@launch
                }
                delay(800)
            }
            val outcome = withContext(Dispatchers.IO) {
                DpiPresetTester.run(targets, fa, { s -> runOnUiThread { lines += s; log.text = lines.joinToString("\n") } }, { cancelled.get() })
            }
            progress.dismiss()
            val ranked = DpiPresetPlan.rank(outcome.trials)
            val report = buildString {
                fun row(label: String, tr: DpiTrial) = appendLine(
                    (if (tr.ok > 0) "✅ " else "❌ ") + "$label: ${tr.ok}/${tr.total}" + (tr.bestMs?.let { " · ${it}ms" } ?: ""),
                )
                row(t("مستقیم (بدون تکه‌تکه‌کردن)", "Direct (no fragmenting)"), outcome.direct)
                outcome.trials.forEach { row(it.presetId, it) }
                outcome.notes.forEach { appendLine("ℹ️ $it") }
            }.trim()
            val best = ranked.firstOrNull()
            val direct = outcome.direct
            when {
                direct.total > 0 && direct.ok == direct.total && (best == null || best.ok <= direct.ok) ->
                    showDpiResult(t("✅ روی این اینترنت لازم نیست", "✅ Not needed on this internet"), report + "\n\n" +
                        t("سرورهای شما بدون تکه‌تکه‌کردن هم وصل می‌شوند؛ آن را خاموش نگه دارید (سریع‌تر است).", "Your servers connect without fragmenting; keep it off (faster)."), null, onApplied)
                best != null && best.ok > direct.ok ->
                    showDpiResult(t("✅ بهترین روش: ${best.presetId}", "✅ Best method: ${best.presetId}"), report + "\n\n" +
                        t("با «روشن کردن» تکه‌تکه‌کردن با همین روش روشن می‌شود و دوباره وصل می‌شوید.", "“Switch on” turns fragmenting on with this method and reconnects."), best.presetId, onApplied)
                else ->
                    showDpiResult(t("⚠️ هیچ روشی کمک نکرد", "⚠️ No method helped"), report + "\n\n" +
                        t("تکه‌تکه‌کردن فقط وقتی کمک می‌کند که نام (SNI) فیلتر شده باشد. اینجا احتمالاً خودِ IP سرور بسته است: «اسکنر IP» یا روش‌های دیگر تب ضدسانسور را امتحان کنید.",
                            "Fragmenting only helps when the name (SNI) is filtered. Here the server IP itself is probably blocked: try the IP scanner or the other anti-censorship methods."), null, onApplied)
            }
        } catch (e: Exception) {
            runCatching { progress.dismiss() }
            if (e is CancellationException) throw e
            showDpiResult(t("❌ آزمون انجام نشد", "❌ The test failed"), e.message ?: e.javaClass.simpleName, null, onApplied)
        }
    }
}

private fun MainActivity.showDpiResult(title: String, message: String, presetId: String?, onApplied: (String) -> Unit) {
    val fa = dpiFa()
    val builder = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton(if (fa) "بستن" else "Close", null)
    if (presetId != null) {
        builder.setPositiveButton(if (fa) "✅ روشن کردن" else "✅ Switch on") { _, _ ->
            dpiBypassPreferenceStore.savePreset(presetId)
            onApplied(presetId)
            if (dpiBypassPreferenceStore.isEnabled()) {
                reconnectForConnectionOptionChange()
            } else {
                tlsFragmentCheckbox.isChecked = true // built with the same settings screen that holds this button
                saveTlsFragmentEnabled(true)
            }
            renderFragmentCarrierLine()
        }
    }
    builder.create().showCatClientDialog()
}
