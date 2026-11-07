package com.cat.client

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.text.DateFormat
import com.cat.client.MainActivity.Companion.SCREEN_HOME

// Static IP / NAT64, speed & ping pages, ProxyIP and SOCKS5 pages, SNI override push, add-server sheet.
// Moved out of MainActivity.kt unchanged (beta221 code split phase 2); see docs/CODEMAP.md.

/** True when any VPN (ours or another app's) owns the default route — Zed's deviceVpnActive(). */
internal fun MainActivity.deviceVpnActive(): Boolean = runCatching {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
}.getOrDefault(false)

internal fun MainActivity.staticIpEnabled(): Boolean = IpHealthStore(this).pinned && frontingIps.size == 1

internal fun MainActivity.applyStaticIp(ip: String): Boolean {
    val normalized = runCatching { FrontingIpPolicy.normalizeIps(ip) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
    val previousValue = frontingIpPreferenceStore.readFrontingIp()
    frontingIps = normalized.take(1)
    IpHealthStore(this).pinned = true
    NetworkGenomeStore(this).autoFailover = false
    startIpHealthLoop() // no-op while pinned; stops a running loop
    renderFrontingIpChips()
    return saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)
}

/** NAT64 address from an IPv4: <96-bit prefix>:<hex(ipv4)> — BPB's NAT64 trick. */
internal fun MainActivity.nat64Address(prefix: String, ipv4: String): String? {
    val parts = ipv4.trim().split('.')
    if (parts.size != 4) return null
    val bytes = parts.map { it.toIntOrNull() ?: return null }
    if (bytes.any { it < 0 || it > 255 }) return null
    val hex1 = "%02x%02x".format(bytes[0], bytes[1]).dropLeadingZeros()
    val hex2 = "%02x%02x".format(bytes[2], bytes[3]).dropLeadingZeros()
    val base = prefix.trim().trim('[', ']', ':')
    if (!Regex("^[0-9a-fA-F:]{6,45}$").matches(base)) return null
    return "[" + base.trimEnd(':') + "::" + hex1 + ":" + hex2 + "]"
}

internal fun MainActivity.showNat64Dialog() {
    val currentIp = frontingIps.firstOrNull()?.removeSurrounding("[", "]")?.substringBeforeLast(':').orEmpty()
    val prefixes = arrayOf(
        "2a02:898:146:64:: (NL)",
        "2602:fc59:b0:64:: (US)",
        "2602:fc59:11:64:: (US)",
    )
    val chosen = arrayOf(prefixes[0])
    val ipInput = EditText(this).apply {
        hint = getString(R.string.nat64_ip_hint)
        setText(currentIp)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setTextColor(TEXT_PRIMARY); setHintTextColor(palette.textTertiary)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    val preview = TextView(this).apply {
        textSize = 12f; typeface = CatClientDataTypeface; setTextColor(TEXT_SECONDARY)
        setPadding(dp(20), dp(10), dp(20), dp(4))
    }
    fun renderPreview() {
        val built = nat64Address(chosen[0].substringBefore(" ("), ipInput.text?.toString().orEmpty())
        preview.text = built ?: getString(R.string.nat64_invalid)
    }
    ipInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = renderPreview()
    })
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.nat64_title))
        .setMessage(getString(R.string.nat64_explain))
        .setSingleChoiceItems(prefixes, 0) { _, which -> chosen[0] = prefixes[which]; renderPreview() }
        .setView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ipInput, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(12), dp(8), 0) })
                addView(preview)
            },
        )
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton(R.string.nat64_copy) { _, _ ->
            nat64Address(chosen[0].substringBefore(" ("), ipInput.text?.toString().orEmpty())?.let {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("nat64", it))
                Toast.makeText(this, R.string.nat64_copied, Toast.LENGTH_SHORT).show()
            }
        }
        .setPositiveButton(R.string.nat64_apply) { _, _ ->
            val built = nat64Address(chosen[0].substringBefore(" ("), ipInput.text?.toString().orEmpty())
            if (built == null || !applyStaticIp(built)) {
                Toast.makeText(this, R.string.nat64_invalid, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.nat64_applied, built), Toast.LENGTH_LONG).show()
                renderFrontingIpChips()
            }
        }
        .show()
}

internal fun MainActivity.clearStaticIp() {
    IpHealthStore(this).pinned = false
    NetworkGenomeStore(this).autoFailover = true
    startIpHealthLoop()
}

internal fun MainActivity.buildStaticIpCard(): View {
    val card = advancedSettingsPanel()
    card.addView(advancedSectionLabel(getString(R.string.static_ip_title)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })
    card.addView(advancedSectionDetail(getString(R.string.static_ip_desc)), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    val input = scannerInput(getString(R.string.static_ip_hint), if (staticIpEnabled()) frontingIps.first() else "")
    card.addView(scannerFieldLayout(getString(R.string.static_ip_label), input), LinearLayout.LayoutParams(-1, -2))
    val status = TextView(this).apply {
        textSize = 12f
        typeface = CatClientDataTypeface
        setTextColor(TEXT_SECONDARY)
        setPadding(dp(8), dp(8), dp(8), 0)
    }
    fun renderStatus() {
        status.text = if (staticIpEnabled()) getString(R.string.static_ip_on, frontingIps.first()) else getString(R.string.static_ip_off)
    }
    renderStatus()
    card.addView(status, LinearLayout.LayoutParams(-1, -2))
    val toggle = MaterialSwitch(this).apply {
        isChecked = staticIpEnabled()
        setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val value = input.text?.toString()?.trim().orEmpty().ifBlank { frontingIps.firstOrNull().orEmpty() }
                if (value.isBlank() || !applyStaticIp(value)) {
                    isChecked = false
                    Toast.makeText(this@buildStaticIpCard, R.string.static_ip_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    input.setText(frontingIps.first())
                    Toast.makeText(this@buildStaticIpCard, getString(R.string.static_ip_on, frontingIps.first()), Toast.LENGTH_SHORT).show()
                }
            } else {
                clearStaticIp()
            }
            renderStatus()
            renderScannerIpHealth()
        }
    }
    card.addView(advancedToggleRow(getString(R.string.static_ip_switch), getString(R.string.static_ip_switch_detail), toggle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LOCALE; setPadding(dp(8), dp(4), dp(8), 0) }
    fun smallButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; setAllCaps(false); textSize = 11.5f; minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
        insetTop = 0; insetBottom = 0; cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(withAlpha(TEAL, 34)); strokeWidth = dp(1); strokeColor = ColorStateList.valueOf(withAlpha(TEAL, 130)); setTextColor(TEAL)
        setOnClickListener { onClick() }
    }
    pickRow.addView(smallButton(getString(R.string.static_ip_pick_pool)) {
        val pool = IpHealthStore(this).entries().sortedBy { it.pingMs.takeIf { p -> p > 0 } ?: Long.MAX_VALUE }
        if (pool.isEmpty()) { Toast.makeText(this, R.string.static_ip_pool_empty, Toast.LENGTH_SHORT).show(); return@smallButton }
        val labels = pool.map { e -> (e.countryCode?.toFlagEmoji() ?: "🌐") + " " + e.authority + (e.colo?.let { "  $it" } ?: "") + (if (e.pingMs > 0) "  ${e.pingMs} ms" else "") }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.static_ip_pick_pool)
            .setItems(labels) { _, which -> input.setText(pool[which].authority); if (toggle.isChecked) { applyStaticIp(pool[which].authority); renderStatus(); renderScannerIpHealth() } else toggle.isChecked = true }
            .show()
    }, LinearLayout.LayoutParams(-2, -2))
    pickRow.addView(smallButton(getString(R.string.static_ip_use_current)) {
        val current = frontingIps.firstOrNull()
        if (current == null) Toast.makeText(this, R.string.static_ip_no_current, Toast.LENGTH_SHORT).show()
        else { input.setText(current); if (!toggle.isChecked) toggle.isChecked = true }
    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    pickRow.addView(smallButton(getString(R.string.nat64_btn)) { showNat64Dialog() })
    card.addView(pickRow, LinearLayout.LayoutParams(-1, -2))
    // Sticky location: reconnects keep the last working server instead of hopping to the fastest one.
    val stickySwitch = MaterialSwitch(this).apply {
        isChecked = getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE).getBoolean("sticky_location", true)
        setOnCheckedChangeListener { _, checked -> getSharedPreferences("cat_client_theme", Context.MODE_PRIVATE).edit().putBoolean("sticky_location", checked).apply() }
    }
    card.addView(advancedToggleRow(getString(R.string.sticky_location_title), getString(R.string.sticky_location_detail), stickySwitch), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    return card
}

/** Multi-location: make a whole country the active fronting set (best-ranked first) and reconnect. */
internal fun MainActivity.useCountryIps(countryCode: String) {
    val store = IpHealthStore(this)
    val entries = store.entries().filter { it.countryCode.equals(countryCode, true) && it.fails == 0 }
    if (entries.isEmpty()) { Toast.makeText(this, R.string.multi_location_empty, Toast.LENGTH_SHORT).show(); return }
    val genomes = NetworkGenomeStore(this)
    val ranked = CognitiveEngine.rank(entries, genomes.genomes(entries.map { it.ip }), NetworkContext.key(this))
    store.preferredCountry = countryCode
    store.pinned = false
    val previousValue = frontingIpPreferenceStore.readFrontingIp()
    frontingIps = FrontingIpPolicy.mergeNewestFirst(ranked.map { it.authority }, emptyList())
    renderFrontingIpChips()
    if (saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) {
        Toast.makeText(this, getString(R.string.multi_location_applied, countryCode.toFlagEmoji(), ranked.size), Toast.LENGTH_SHORT).show()
    }
    renderScannerIpHealth()
}

internal fun MainActivity.showSpeedTestPage() {
    SpeedTestPage(this, palette, activityScope, connected = currentVpnStateIsStarted()).show()
}

/**
 * ⏱ Precise ping & jitter page. Prefills the ticked (or best verified) scanner row with
 * its exact port, and the worker the scanner measures through — never a made-up value:
 * with no scan yet the target is the worker domain itself on 443.
 */
internal fun MainActivity.showPingJitterPage() {
    val farsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    activityScope.launch {
        val worker = withContext(Dispatchers.IO) { runCatching { scannerWorkerHost(null) }.getOrNull() }
        val picked = scannerResults.firstOrNull { scannerSelected.contains(scannerRowKey(it)) }
            ?: scannerResults.filter { it.tlsOk }.minByOrNull { it.workerMs ?: it.lineMs ?: it.pingMs }
        val target = picked?.authority ?: worker?.let { "$it:443" }
        PingJitterPage(this@showPingJitterPage, palette, activityScope, farsi, target, worker, vpnOn = currentVpnStateIsStarted()).show()
    }
}

internal fun MainActivity.showProxyIpScannerPage() {
    ProxyIpScannerPage(this, palette, activityScope, panelBaseUrl(), onUseAsEntry = { applyProxyIpAsEntry(it) }, pickPanel = { pickPanelBaseInteractive() }).show()
}

/**
 * Apply a verified ProxyIP (`host:port` or `[ipv6]:port`) from the ProxyIP scanner
 * as an outbound ProxyIP (`?proxyip=host:port`) on the currently selected subscription
 * profile — never into the Clean IP `frontingIps` pool.
 */
internal fun MainActivity.applyProxyIpAsEntry(ip: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("proxyip", ip))
    val chain = connectionChainPreferenceStore.read()
    val entrySlot = chain.firstConfiguredSlot()
    if (chain.isActive && entrySlot != null) {
        val pinned = connectionChainEndpointOrNull(ip)?.encoded() ?: ip
        updateConnectionChainSettings(
            chain.withHop(entrySlot, chain.hop(entrySlot).copy(serverOverride = pinned)),
        )
        Toast.makeText(
            this,
            getString(
                R.string.scanner_applied_chain,
                pinned,
                getString(connectionChainSlotLabelRes(entrySlot)),
            ),
            Toast.LENGTH_LONG,
        ).show()
        return
    }
    val nowMs = System.currentTimeMillis()
    val proxyEndpoint = VerifiedEndpointParser.parseAuthority(
        value = ip,
        role = EndpointRole.PROXY_IP,
        verdict = EndpointVerdict.VERIFIED,
        checkedAtMs = nowMs,
        source = "device-proxyip-scan",
    )
    val selectedSubId = SubscriptionStore(this).readSelectedSubscriptionId()
    val targetProfile = connectionSelectionPreferenceStore.readSelectedProfile(selectedSubId, connectionProfiles)
        ?.takeIf { !it.shareLink.isNullOrBlank() }
        ?: connectionProfiles.firstOrNull { !it.shareLink.isNullOrBlank() }
    if (proxyEndpoint != null && targetProfile?.shareLink != null) {
        val rebuiltUri = VerifiedConfigFailover.changeProxyIpUri(
            rawUri = targetProfile.shareLink,
            candidates = listOf(proxyEndpoint),
            nowMs = nowMs,
            maxAgeMs = 86_400_000L,
        )
        if (rebuiltUri != null) {
            val updated = userSubscriptionManager.replaceProfileShareLink(
                subscriptionId = selectedSubId,
                targetTag = targetProfile.tag,
                updatedShareLink = rebuiltUri,
                nowMs = nowMs,
            )
            if (updated != null) {
                connectionProfiles = connectionProfiles.map { if (it.tag == targetProfile.tag) updated else it }
                val selectedTypes = connectionSelectionPreferenceStore
                    .readAutomaticTypes(selectedSubId, connectionProfiles)
                    .ifEmpty { ConnectionTypeSelectionPolicy.availableTypes(connectionProfiles).toSet() }
                handleConnectionSelected(updated, selectedTypes)
                Toast.makeText(
                    this,
                    "🎯 ProxyIP applied: ${proxyEndpoint.authority} (${updated.displayTag})",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        }
    }
    if (proxyEndpoint != null) {
        Toast.makeText(this, "🎯 ProxyIP copied: ${proxyEndpoint.authority}", Toast.LENGTH_LONG).show()
        return
    }
    val previousValue = frontingIpPreferenceStore.readFrontingIp()
    frontingIps = FrontingIpPolicy.mergeNewestFirst(listOf(ip), frontingIps)
    renderFrontingIpChips()
    if (!saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)) return
    Toast.makeText(this, getString(R.string.scanner_applied, ip, 0L), Toast.LENGTH_LONG).show()
}

/** Put an SNI into the live configs (servername of every TLS proxy) and reconnect; null clears it. */
internal fun MainActivity.applySniOverride(sni: String?) {
    val previous = frontingIpPreferenceStore.readSniOverride()
    frontingIpPreferenceStore.saveSniOverride(sni)
    val next = frontingIpPreferenceStore.readSniOverride()
    if (next != previous && buttonModel.state == VpnState.Started) {
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        startVpnService(Actions.RECONNECT)
    }
    Toast.makeText(this, if (next.isBlank()) getString(R.string.sni_override_cleared) else getString(R.string.sni_override_applied, next), Toast.LENGTH_LONG).show()
}

/** "Add to panel" from the SNI scanner: merges the host into settings.extraSnis (🧬 SNI configs). */
internal fun MainActivity.pushSniToPanel(sni: String) = activityScope.launch { pushSniToPanelSuspend(sni) }

/** "Make panel main SNI": PUT /api/settings {sni} — every TLS config the panel emits re-fronts through this host (kills the skk.moe default in one tap). */
internal fun MainActivity.pushSniMainToPanel(sni: String) = activityScope.launch { pushSniMainToPanelSuspend(sni) }

internal suspend fun MainActivity.pushSniMainToPanelSuspend(sni: String) {
    val base = kotlinx.coroutines.withContext(Dispatchers.Main) { pickPanelBaseInteractive() }?.trim()?.trimEnd('/')
    if (base.isNullOrBlank()) { Toast.makeText(this, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return }
    val storedPass = runCatching { PanelCredentials.defaultPassword(this, base) }.getOrNull().orEmpty()
    kotlinx.coroutines.withContext(Dispatchers.Main) {
        val input = TextInputEditText(this@pushSniMainToPanelSuspend).apply {
            hint = getString(R.string.pip_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (storedPass.isNotBlank()) setText(storedPass)
        }
        val box = LinearLayout(this@pushSniMainToPanelSuspend).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        MaterialAlertDialogBuilder(this@pushSniMainToPanelSuspend)
            .setTitle(getString(R.string.sni_main_title, sni))
            .setMessage(base.removePrefix("https://") + "\n\n" + getString(R.string.sni_main_warn, sni))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sni_action_main) { d, _ ->
                val password = input.text.toString()
                d.dismiss()
                activityScope.launch {
                    val result = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { pushSniMainToPanelApi(base, password, sni) } }
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        Toast.makeText(this@pushSniMainToPanelSuspend, result.fold({ getString(R.string.sni_main_ok, sni) }, { getString(R.string.sni_main_fail, it.message ?: "?") }), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }
}

internal fun MainActivity.pushSniMainToPanelApi(base: String, password: String, sni: String) {
    fun call(path: String, method: String, body: String?, cookie: String?): Pair<java.net.HttpURLConnection, String> {
        val conn = java.net.URL("$base$path").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
        val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return conn to stream.bufferedReader().readText()
    }
    val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(applicationContext, base, password).toString(), null)
    if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(getString(R.string.pip_wrong_password))
    val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
    login.disconnect()
    // beta204: sniRotate=false — otherwise the panel kept rotating its built-in SNI pool (names never tested on this
    // network) and the SNI the user just verified appeared on only a few configs.
    val (put, putBody) = call("/api/settings", "PUT", org.json.JSONObject().put("sni", sni).put("sniFront", true).put("sniRotate", false).toString(), cookie)
    val ok = put.responseCode == 200 && org.json.JSONObject(putBody).optBoolean("ok")
    put.disconnect()
    if (!ok) throw IllegalStateException("HTTP ${put.responseCode}")
}

internal suspend fun MainActivity.pushSniToPanelSuspend(sni: String) {
    val base = kotlinx.coroutines.withContext(Dispatchers.Main) { pickPanelBaseInteractive() }?.trim()?.trimEnd('/')
    if (base.isNullOrBlank()) { Toast.makeText(this, R.string.pip_no_panel, Toast.LENGTH_LONG).show(); return }
    val storedPass = runCatching { PanelCredentials.defaultPassword(this, base) }.getOrNull().orEmpty()
    val input = EditText(this).apply {
        if (storedPass.isNotBlank()) setText(storedPass)
        hint = getString(R.string.pip_password_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(getString(R.string.sni_push_title))
        .setMessage(base.removePrefix("https://"))
        .setView(input)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.sni_action_panel) { _, _ ->
            val password = input.text.toString()
            activityScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { pushSniToPanelApi(base, password, sni) } }
                result.onSuccess { added ->
                    Toast.makeText(this@pushSniToPanelSuspend, getString(if (added) R.string.sni_push_ok else R.string.sni_push_exists, sni), Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    Toast.makeText(this@pushSniToPanelSuspend, getString(R.string.sni_push_fail, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }
        .show()
}

internal fun MainActivity.pushSniToPanelApi(base: String, password: String, sni: String): Boolean {
    fun call(path: String, method: String, body: String?, cookie: String?): Pair<java.net.HttpURLConnection, String> {
        val conn = java.net.URL("$base$path").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 10_000; conn.readTimeout = 15_000; conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        if (cookie != null) conn.setRequestProperty("Cookie", cookie)
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
        val stream = if (conn.responseCode < 400) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return conn to stream.bufferedReader().readText()
    }
    val (login, loginBody) = call("/api/login", "POST", PanelCredentials.loginPayload(applicationContext, base, password).toString(), null)
    if (login.responseCode != 200 || !org.json.JSONObject(loginBody).optBoolean("ok")) throw IllegalStateException(getString(R.string.pip_wrong_password))
    val cookie = login.headerFields.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value }.joinToString("; ") { it.substringBefore(';') }
    login.disconnect()
    val (get, getBody) = call("/api/settings", "GET", null, cookie)
    val existing = ArrayList<String>()
    if (get.responseCode == 200) {
        val arr = org.json.JSONObject(getBody).optJSONObject("settings")?.optJSONArray("extraSnis")
        if (arr != null) for (i in 0 until arr.length()) existing += arr.optString(i)
    }
    get.disconnect()
    if (existing.any { it.equals(sni, ignoreCase = true) }) return false
    val merged = (existing + sni).distinct().take(8)
    val (put, putBody) = call("/api/settings", "PUT", org.json.JSONObject().put("extraSnis", org.json.JSONArray(merged)).toString(), cookie)
    val ok = put.responseCode == 200 && org.json.JSONObject(putBody).optBoolean("ok")
    put.disconnect()
    if (!ok) throw IllegalStateException("HTTP ${put.responseCode}")
    return true
}

internal fun MainActivity.showProxyScannerPage() {
    ProxyScannerPage(
        activity = this,
        palette = palette,
        scope = activityScope,
        panelBaseUrl = panelBaseUrl(),
        pickPanel = { pickPanelBaseInteractive() },
        importLink = { link -> showAddSubscriptionDialog(link, "") },
        connectDirect = { hit -> connectSocks5HitDirectly(hit) },
        connectPool = { hits -> connectSocks5PoolDirectly(hits) },
        startWithSocks5Only = false,
    ).show()
}

internal fun MainActivity.showSocks5HubPage() {
    ProxyScannerPage(
        activity = this,
        palette = palette,
        scope = activityScope,
        panelBaseUrl = panelBaseUrl(),
        pickPanel = { pickPanelBaseInteractive() },
        importLink = { link -> showAddSubscriptionDialog(link, "") },
        connectDirect = { hit -> connectSocks5HitDirectly(hit) },
        connectPool = { hits -> connectSocks5PoolDirectly(hits) },
        startWithSocks5Only = true,
    ).show()
}

internal fun MainActivity.connectSocks5HitDirectly(hit: ProxyScannerPage.Hit) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val uri = ProxyScanImport.uri(hit, System.currentTimeMillis()) ?: return
    val countryTag = hit.country.ifBlank { "SOCKS5" }
    val shareLink = "$uri#$countryTag ${hit.authority} (${hit.latencyMs}ms)"
    val subName = "🧦 SOCKS5 Direct ($countryTag ${hit.host}:${hit.port})"
    activityScope.launch {
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("🧦 SOCKS5 Direct") }
                if (existing != null) {
                    userSubscriptionManager.update(existing.id, subName, shareLink)
                } else {
                    userSubscriptionManager.add(subName, shareLink)
                }
            }
        }.getOrNull()
        if (saved == null) {
            Toast.makeText(
                this@connectSocks5HitDirectly,
                if (isFarsi) "⚠️ ساخت پروفایل اتصال SOCKS5 ناموفق بود" else "⚠️ Failed to create SOCKS5 profile",
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        val curAether = aetherPreferenceStore.read()
        if (curAether.enabled) {
            aetherPreferenceStore.save(curAether.copy(enabled = false))
        }
        orbotEnginePreferenceStore.setEnabled(false)
        userSubscriptionManager.select(saved.id)
        onSubscriptionSelected()
        Toast.makeText(
            this@connectSocks5HitDirectly,
            if (isFarsi) "⚡ در حال اتصال مستقیم به پراکسی ساکس۵ (${hit.authority} · ${hit.latencyMs}ms)…"
            else "⚡ Connecting directly to SOCKS5 (${hit.authority} · ${hit.latencyMs}ms)…",
            Toast.LENGTH_LONG,
        ).show()
        openScreen(SCREEN_HOME)
        if (currentVpnServiceState == VpnState.Started) {
            reconnectForConnectionOptionChange()
        } else {
            beginConnectFlow(Actions.CONNECT)
        }
    }
}

internal fun MainActivity.connectSocks5PoolDirectly(hits: List<ProxyScannerPage.Hit>) {
    val isFarsi = appLanguagePreferenceStore.read() == AppLanguage.Persian
    val nowMs = System.currentTimeMillis()
    val links = hits
        .filter { it.proto == ProxyScannerPage.Proto.SOCKS5 }
        .sortedBy { it.latencyMs }
        .distinctBy { "${it.host.lowercase(Locale.US)}:${it.port}" }
        .take(40)
        .mapIndexedNotNull { idx, h ->
            val baseUri = ProxyScanImport.uri(h, nowMs) ?: return@mapIndexedNotNull null
            val cc = h.country.ifBlank { "GL" }
            "$baseUri#$cc-${idx + 1} ${h.host}:${h.port} (${h.latencyMs}ms)"
        }
    if (links.isEmpty()) {
        Toast.makeText(
            this,
            if (isFarsi) "⚠️ ابتدا دکمه شروع اسکن را بزنید تا ساکس۵های سالم پیدا شوند"
            else "⚠️ Start the scan first to find working SOCKS5 proxies",
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val subName = "🧦 SOCKS5 Auto-Pool (${links.size})"
    activityScope.launch {
        val saved = runCatching {
            withContext(Dispatchers.IO) {
                val existing = userSubscriptionManager.list().firstOrNull { it.name.startsWith("🧦 SOCKS5 Auto-Pool") }
                if (existing != null) {
                    userSubscriptionManager.update(existing.id, subName, links.joinToString("\n"))
                } else {
                    userSubscriptionManager.add(subName, links.joinToString("\n"))
                }
            }
        }.getOrNull()
        if (saved == null) {
            Toast.makeText(
                this@connectSocks5PoolDirectly,
                if (isFarsi) "⚠️ ساخت استخر خودکار SOCKS5 ناموفق بود" else "⚠️ Failed to build SOCKS5 pool",
                Toast.LENGTH_LONG,
            ).show()
            return@launch
        }
        val curAether = aetherPreferenceStore.read()
        if (curAether.enabled) {
            aetherPreferenceStore.save(curAether.copy(enabled = false))
        }
        orbotEnginePreferenceStore.setEnabled(false)
        userSubscriptionManager.select(saved.id)
        onSubscriptionSelected()
        Toast.makeText(
            this@connectSocks5PoolDirectly,
            if (isFarsi) "🚀 استخر هوشمند ساکس۵ با ${links.size} پراکسی سالم ساخته شد — در حال اتصال خودکار…"
            else "🚀 Smart SOCKS5 pool built with ${links.size} live proxies — connecting…",
            Toast.LENGTH_LONG,
        ).show()
        openScreen(SCREEN_HOME)
        if (currentVpnServiceState == VpnState.Started) {
            reconnectForConnectionOptionChange()
        } else {
            beginConnectFlow(Actions.CONNECT)
        }
    }
}

internal fun MainActivity.showAddServerSheet() {
    val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
    fun item(glyph: String, labelRes: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(14), dp(8), dp(14))
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(withAlpha(TEAL, 40)), null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() })
        setOnClickListener { sheet.dismiss(); onClick() }
        addView(TextView(this@showAddServerSheet).apply {
            text = glyph
            textSize = 18f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(TEAL)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(TextView(this@showAddServerSheet).apply {
            setText(labelRes)
            textSize = 16f
            typeface = CatClientBodyTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        setPadding(dp(20), dp(16), dp(20), dp(28))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            setColor(palette.surfaceElevated1)
        }
        addView(TextView(this@showAddServerSheet).apply {
            setText(R.string.servers_add_title)
            textSize = 26f
            typeface = CatClientDisplayTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        })
        addView(advancedSectionLabel(getString(R.string.servers_add_import)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        addView(item("📋", R.string.servers_add_clipboard) { addSubscriptionFromClipboard() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            addView(item("▣", R.string.servers_add_qr) { startSubscriptionQrScan() }, LinearLayout.LayoutParams(-1, -2))
        }
        addView(item("✎", R.string.servers_add_manual) { showAddSubscriptionDialog() }, LinearLayout.LayoutParams(-1, -2))
        addView(item("📂", R.string.servers_add_file) { appTools.openImportFilePicker() }, LinearLayout.LayoutParams(-1, -2))
        addView(item("🧰", R.string.servers_add_tools) { appTools.showToolsHub() }, LinearLayout.LayoutParams(-1, -2))
        addView(item("🐱", R.string.servers_add_vercel_panel) { vercelRelay.showPanel() }, LinearLayout.LayoutParams(-1, -2))
        addView(item("🔺", R.string.servers_add_vercel) { vercelRelay.show() }, LinearLayout.LayoutParams(-1, -2))
        addView(wizardText(getString(R.string.servers_add_file_hint), secondary = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    sheet.setContentView(body)
    (body.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
    sheet.show()
}
