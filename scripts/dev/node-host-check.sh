#!/usr/bin/env bash
# Local check for the Node-host feature when no Android SDK is available (dev only; CI does the real build).
#   1) type-checks NodeHost.kt + NodeHostController.kt against tiny Android/androidx signature stubs
#      (only errors outside the stubs matter — stub JVM-signature clashes are expected and ignored);
#   2) runs NodeHostTest.kt (JUnit stand-in) and probes a REAL launcher (plain-http /api/version, /sub, WebSocket 101).
# Toolchain (cached in ~/.cache/kt): jdk4py from PyPI + kotlin-compiler from npm.
set -u
R=$(cd "$(dirname "$0")/../.." && pwd); K=$HOME/.cache/kt; W=$K/work; T=$K/tc
mkdir -p "$K" && cd "$K"
[ -x jdk/jdk4py/java-runtime/bin/java ] || { python3 -m pip download -q --no-deps -d . jdk4py && mkdir -p jdk && (cd jdk && unzip -q ../jdk4py-*.whl); }
[ -f ktc/package/lib/kotlin-compiler.jar ] || { mkdir -p ktc && (cd ktc && npm pack -q kotlin-compiler@2.4.21 >/dev/null && tar xzf kotlin-compiler-*.tgz); }
export JAVA_HOME=$K/jdk/jdk4py/java-runtime; export PATH=$JAVA_HOME/bin:$PATH; KC=$K/ktc/package/bin/kotlinc
rm -rf "$T" && mkdir -p "$T/s"
cat > "$T/s/A.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.content
import android.content.res.Resources
import android.content.res.AssetManager
open class Context {
    open val resources: Resources get() = TODO()
    open val assets: AssetManager get() = TODO()
    open val applicationContext: Context get() = TODO()
    open val filesDir: java.io.File get() = TODO()
    open val contentResolver: ContentResolver get() = TODO()
    open val packageName: String get() = TODO()
    open fun getSystemService(name: String): Any? = TODO()
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = TODO()
    open fun getString(id: Int): String = TODO()
    open fun getString(id: Int, vararg args: Any): String = TODO()
    open fun startActivity(i: Intent) {}
    open fun checkSelfPermission(p: String): Int = 0
    companion object { const val CLIPBOARD_SERVICE = "c"; const val NOTIFICATION_SERVICE = "n"; const val MODE_PRIVATE = 0 }
}
interface SharedPreferences { fun getString(k: String, d: String?): String?; fun edit(): Editor
    interface Editor { fun putString(k: String, v: String?): Editor; fun apply() } }
class ContentResolver { fun insert(u: android.net.Uri, v: ContentValues): android.net.Uri? = TODO(); fun openOutputStream(u: android.net.Uri): java.io.OutputStream? = TODO() }
class ContentValues { fun put(k: String, v: String) {} }
class ClipData { fun getItemAt(i: Int): Item = TODO(); class Item { fun coerceToText(c: Context): CharSequence = TODO() }
    companion object { @JvmStatic fun newPlainText(l: CharSequence, t: CharSequence): ClipData = TODO() } }
class ClipboardManager { fun setPrimaryClip(c: ClipData) {}; val primaryClip: ClipData? get() = TODO() }
class Intent(action: String? = null, uri: android.net.Uri? = null) {
    var type: String? = null
    fun putExtra(k: String, v: android.net.Uri): Intent = this
    fun addFlags(f: Int): Intent = this
    companion object { const val ACTION_VIEW = "v"; const val ACTION_SEND = "s"; const val EXTRA_STREAM = "e"; const val FLAG_GRANT_READ_URI_PERMISSION = 1
        @JvmStatic fun createChooser(i: Intent, t: CharSequence): Intent = i } }
interface DialogInterface {
    fun interface OnClickListener { fun onClick(d: DialogInterface, which: Int) }
    fun interface OnShowListener { fun onShow(d: DialogInterface) } }
EOF
cat > "$T/s/B.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.content.res
class Resources { val displayMetrics: android.util.DisplayMetrics get() = TODO() }
class AssetManager { fun open(n: String): java.io.InputStream = TODO() }
class ColorStateList { companion object { @JvmStatic fun valueOf(c: Int): ColorStateList = TODO() } }
EOF
cat > "$T/s/C.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.util
class DisplayMetrics { @JvmField var density: Float = 1f }
EOF
cat > "$T/s/D.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.app
open class Activity : android.content.Context() { fun runOnUiThread(r: Runnable) {} }
class NotificationChannel(id: String, name: CharSequence, imp: Int)
class NotificationManager { fun createNotificationChannel(c: NotificationChannel) {}; companion object { const val IMPORTANCE_HIGH = 4 } }
class PendingIntent { companion object { const val FLAG_UPDATE_CURRENT = 1; const val FLAG_IMMUTABLE = 2
    @JvmStatic fun getActivity(c: android.content.Context, r: Int, i: android.content.Intent, f: Int): PendingIntent = TODO() } }
class Notification
EOF
cat > "$T/s/E.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.net
class Uri { companion object { @JvmStatic fun parse(s: String): Uri = TODO() } }
EOF
cat > "$T/s/F.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.os
object Build { object VERSION { @JvmField var SDK_INT: Int = 0 }; object VERSION_CODES { const val O = 26; const val Q = 29; const val TIRAMISU = 33 } }
object Environment { @JvmField val DIRECTORY_DOWNLOADS: String = "Download" }
EOF
cat > "$T/s/G.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.provider
object MediaStore { object MediaColumns { const val DISPLAY_NAME = "a"; const val MIME_TYPE = "b"; const val RELATIVE_PATH = "c" }
    object Downloads { @JvmField val EXTERNAL_CONTENT_URI: android.net.Uri = TODO() } }
EOF
cat > "$T/s/H.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.text
object InputType { const val TYPE_CLASS_TEXT = 1; const val TYPE_TEXT_VARIATION_PASSWORD = 2; const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 4 }
EOF
cat > "$T/s/I.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.view
open class View(c: android.content.Context? = null) {
    var layoutDirection: Int = 0; var textDirection: Int = 0; var isEnabled: Boolean = true
    open fun setPadding(a: Int, b: Int, c: Int, d: Int) {}
    open fun setBackgroundColor(c: Int) {}
    fun setOnClickListener(l: OnClickListener?) {}
    fun interface OnClickListener { fun onClick(v: View) }
    companion object { const val LAYOUT_DIRECTION_LOCALE = 3; const val TEXT_DIRECTION_LOCALE = 5; const val TEXT_DIRECTION_LTR = 3 } }
open class ViewGroup(c: android.content.Context? = null) : View(c) {
    fun addView(v: View) {}
    fun addView(v: View, p: LayoutParams) {}
    fun removeAllViews() {}
    open class LayoutParams(w: Int, h: Int)
    open class MarginLayoutParams(w: Int, h: Int) : LayoutParams(w, h) { @JvmField var topMargin: Int = 0; var marginEnd: Int = 0; var marginStart: Int = 0 } }
EOF
cat > "$T/s/J.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package android.widget
import android.view.View
import android.view.ViewGroup
open class TextView(c: android.content.Context) : View(c) {
    open var text: CharSequence = ""
    var textSize: Float = 0f; var hint: CharSequence = ""; var inputType: Int = 0
    fun setText(id: Int) {}
    fun setTextColor(c: Int) {}; fun setHintTextColor(c: Int) {}; fun setTextIsSelectable(b: Boolean) {} }
open class EditText(c: android.content.Context) : TextView(c) { fun setText(s: String) {} }
open class Button(c: android.content.Context) : TextView(c) { var isAllCaps: Boolean = false; var minHeight: Int = 0 }
open class CompoundButton(c: android.content.Context) : Button(c) {
    fun setOnCheckedChangeListener(l: OnCheckedChangeListener?) {}
    fun interface OnCheckedChangeListener { fun onCheckedChanged(b: CompoundButton, checked: Boolean) } }
class CheckBox(c: android.content.Context) : CompoundButton(c)
class LinearLayout(c: android.content.Context) : ViewGroup(c) {
    var orientation: Int = 0
    class LayoutParams(w: Int, h: Int, weight: Float = 0f) : ViewGroup.MarginLayoutParams(w, h)
    companion object { const val VERTICAL = 1; const val HORIZONTAL = 0 } }
class ScrollView(c: android.content.Context) : ViewGroup(c)
class Toast { fun show() {}; companion object { const val LENGTH_LONG = 1; @JvmStatic fun makeText(c: android.content.Context, t: CharSequence, d: Int): Toast = TODO() } }
EOF
cat > "$T/s/K.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package androidx.appcompat.app
open class AlertDialog : android.content.DialogInterface {
    fun getButton(w: Int): android.widget.Button = TODO()
    fun setOnShowListener(l: android.content.DialogInterface.OnShowListener?) {}
    fun dismiss() {}
    companion object { const val BUTTON_POSITIVE = -1 } }
EOF
cat > "$T/s/L.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package com.google.android.material.dialog
import android.content.DialogInterface
class MaterialAlertDialogBuilder(c: android.content.Context) {
    fun setTitle(t: CharSequence?) = this; fun setMessage(t: CharSequence?) = this; fun setView(v: android.view.View?) = this
    fun setPositiveButton(t: CharSequence?, l: DialogInterface.OnClickListener?) = this
    fun setPositiveButton(t: Int, l: DialogInterface.OnClickListener?) = this
    fun setNegativeButton(t: CharSequence?, l: DialogInterface.OnClickListener?) = this
    fun setNegativeButton(t: Int, l: DialogInterface.OnClickListener?) = this
    fun setItems(items: Array<out CharSequence>?, l: DialogInterface.OnClickListener?) = this
    fun setCancelable(b: Boolean) = this
    fun create(): androidx.appcompat.app.AlertDialog = TODO() }
EOF
cat > "$T/s/M.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package com.google.android.material.button
class MaterialButton(c: android.content.Context) : android.widget.Button(c) {
    var cornerRadius: Int = 0; var strokeWidth: Int = 0; var strokeColor: android.content.res.ColorStateList? = null }
EOF
cat > "$T/s/N.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package androidx.core.content
object FileProvider { @JvmStatic fun getUriForFile(c: android.content.Context, a: String, f: java.io.File): android.net.Uri = TODO() }
EOF
cat > "$T/s/O.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package androidx.core.app
class NotificationCompat { class Builder(c: android.content.Context, ch: String) {
    fun setSmallIcon(i: Int) = this; fun setContentTitle(t: CharSequence?) = this; fun setContentText(t: CharSequence?) = this
    fun setStyle(s: BigTextStyle) = this; fun setContentIntent(p: android.app.PendingIntent) = this; fun setAutoCancel(b: Boolean) = this
    fun build(): android.app.Notification = TODO() }
    class BigTextStyle { fun bigText(t: CharSequence?) = this } }
class NotificationManagerCompat { fun notify(id: Int, n: android.app.Notification) {}
    companion object { @JvmStatic fun from(c: android.content.Context): NotificationManagerCompat = TODO() } }
EOF
cat > "$T/s/P.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package androidx.work
class Data { fun getString(k: String): String? = null; class Builder { fun putString(k: String, v: String?) = this; fun build(): Data = TODO() } }
enum class ExistingWorkPolicy { REPLACE }
open class WorkerParameters
class OneTimeWorkRequest
class OneTimeWorkRequestBuilderImpl { fun setInitialDelay(d: Long, u: java.util.concurrent.TimeUnit) = this; fun setInputData(d: Data) = this; fun build(): OneTimeWorkRequest = TODO() }
@Suppress("FunctionName") inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequestBuilderImpl = TODO()
abstract class ListenableWorker(c: android.content.Context, p: WorkerParameters) {
    val applicationContext: android.content.Context get() = TODO()
    val inputData: Data get() = TODO()
    abstract class Result { companion object { @JvmStatic fun success(): Result = TODO() } } }
abstract class CoroutineWorker(c: android.content.Context, p: WorkerParameters) : ListenableWorker(c, p) { abstract suspend fun doWork(): Result }
class WorkManager { fun enqueueUniqueWork(n: String, p: ExistingWorkPolicy, r: OneTimeWorkRequest) {}; fun cancelUniqueWork(n: String) {}
    companion object { @JvmStatic fun getInstance(c: android.content.Context): WorkManager = TODO() } }
EOF
cat > "$T/s/Q.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package org.json
class JSONObject(s: String = "{}") {
    fun optJSONArray(k: String): JSONArray? = null; fun optJSONObject(k: String): JSONObject? = null
    fun optString(k: String): String = ""; fun optString(k: String, d: String): String = d
    fun optLong(k: String): Long = 0; fun optInt(k: String): Int = 0; fun optInt(k: String, d: Int): Int = d; fun optBoolean(k: String): Boolean = false; fun optBoolean(k: String, d: Boolean): Boolean = d
    fun put(k: String, v: Any?): JSONObject = this
    fun keys(): Iterator<String> = TODO() }
class JSONArray(s: String = "[]") { fun length(): Int = 0; fun optJSONObject(i: Int): JSONObject? = null; fun put(v: Any?): JSONArray = this; fun optString(i: Int): String = "" }
EOF
cat > "$T/s/R.kt" <<'EOF'
@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")
package kotlinx.coroutines
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
interface CoroutineScope
interface Job { fun cancel() }
object Dispatchers { val IO: CoroutineContext = EmptyCoroutineContext }
fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job = TODO()
suspend fun <T> withContext(c: CoroutineContext, block: suspend CoroutineScope.() -> T): T = TODO()
suspend fun delay(ms: Long) {}
typealias CancellationException = java.util.concurrent.CancellationException
EOF
cat > "$T/s/S.kt" <<'EOF'
@file:Suppress("unused", "PackageDirectoryMismatch", "ClassName")
package com.cat.client
object BuildConfig { const val VERSION_NAME = "1" }
object R { object drawable { const val ic_cloud_tab = 1 }; object string { const val node_renew_channel = 1; const val node_renew_title = 2; const val node_renew_text = 3 } }
class CatClientDesignTokens(val textPrimary: Int, val textSecondary: Int, val teal: Int) { companion object { fun forContext(c: android.content.Context) = CatClientDesignTokens(0, 0, 0) } }
EOF
cat > "$T/s/U.kt" <<'EOF'
@file:Suppress("unused", "PackageDirectoryMismatch", "ClassName")
package android
object R { object string { const val ok = 1; const val cancel = 2 } }
object Manifest { object permission { const val POST_NOTIFICATIONS = "p" } }
EOF
cat > "$T/s/V.kt" <<'EOF'
@file:Suppress("unused", "PackageDirectoryMismatch")
package android.content.pm
object PackageManager { const val PERMISSION_GRANTED = 0 }
EOF
cat > "$T/s/X.kt" <<'EOF'
@file:Suppress("unused", "PackageDirectoryMismatch")
package android.graphics
object Color { const val TRANSPARENT = 0 }
EOF
SRC=$R/app/src/main/java/com/cat/client
echo "== type-check (stubs) =="
"$KC" "$T"/s/*.kt "$SRC/NodeHost.kt" "$SRC/NodeHostController.kt" "$SRC/ApiHostDeploy.kt" -d "$T/out" > "$T/log" 2>&1
grep -E 'error:' "$T/log" | grep -v "$T/s/" > "$T/errs" || true
if [ -s "$T/errs" ]; then cat "$T/errs"; echo "TYPE-CHECK FAILED"; exit 1; fi
echo "type-check OK (only errors outside the stubs count)"

echo "== NodeHostTest + real launcher =="
rm -rf "$W" && mkdir -p "$W/src" "$W/out"
python3 - "$SRC/NodeHost.kt" "$W/src/NodeHostPanel.kt" <<'PY'
import sys
s=open(sys.argv[1],encoding='utf-8').read()
def block(m):
    start=s.index(m); i=s.index('{',start); d=0
    while True:
        c=s[i]
        if c=='{': d+=1
        elif c=='}':
            d-=1
            if d==0: return s[start:i+1]
        i+=1
imports=[l for l in s.splitlines() if l.startswith('import java')]
open(sys.argv[2],'w').write('package com.cat.client\n\n'+'\n'.join(imports)+'\n\n'+block('object NodeHostPanel {')+
 '\n\ninternal class NodeHostApiException(message: String, val code: Int) : Exception(message)\n'+
 'internal object ProbeHarness {\n    private fun request(url: String, method: String, headers: Map<String, String>, body: ByteArray? = null, contentType: String = "", timeoutMs: Int = 0): String = throw NodeHostApiException("https not in harness", 0)\n'+
 block('    fun panelGet(')+'\n'+block('    fun websocketStatus(')+'\n}\n')
PY
cp "$R/app/src/test/java/com/cat/client/NodeHostTest.kt" "$W/src/"
cat > "$W/src/Junit.kt" <<'KT'
package org.junit
annotation class Test
object Assert {
    @JvmStatic fun assertEquals(e: Any?, a: Any?) { if (e != a) throw AssertionError("expected <$e> but was <$a>") }
    @JvmStatic fun assertEquals(e: Double, a: Double, d: Double) { if (Math.abs(e - a) > d) throw AssertionError("expected <$e> but was <$a>") }
    @JvmStatic fun assertTrue(c: Boolean) { if (!c) throw AssertionError("expected true") }
    @JvmStatic fun assertFalse(c: Boolean) { if (c) throw AssertionError("expected false") }
    @JvmStatic fun assertNull(a: Any?) { if (a != null) throw AssertionError("expected null but was <$a>") }
    @JvmStatic fun assertArrayEquals(e: ByteArray, a: ByteArray) { if (!e.contentEquals(a)) throw AssertionError("arrays differ") }
    @JvmStatic fun assertArrayEquals(m: String, e: ByteArray, a: ByteArray) { if (!e.contentEquals(a)) throw AssertionError(m) }
}
KT
cat > "$W/src/Run.kt" <<'KT'
import com.cat.client.NodeHostTest
import com.cat.client.NodeHostPanel
import com.cat.client.ProbeHarness
fun main(args: Array<String>) {
    val t = NodeHostTest(); var fail = 0; var n = 0
    for (m in NodeHostTest::class.java.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) }.sortedBy { it.name }) {
        n++
        try { m.invoke(t); println("PASS " + m.name) } catch (e: java.lang.reflect.InvocationTargetException) { fail++; println("FAIL " + m.name + ": " + e.targetException) }
    }
    if (args.size >= 2) {
        val base = NodeHostPanel.normalizeAddress("127.0.0.1:" + args[0])!!
        val ver = ProbeHarness.panelGet(base, "/api/version")
        n++; if (ver.first == 200 && ver.second.contains("cat-panel")) println("PASS plain-http /api/version") else { fail++; println("FAIL probe $ver") }
        val links = NodeHostPanel.decodeSub(ProbeHarness.panelGet(base, "/sub/" + args[1]).second)
        n++; if (links.size == 2) println("PASS /sub: 2 links") else { fail++; println("FAIL sub " + links.size) }
        val path = links.firstOrNull()?.let { NodeHostPanel.linkParam(it, "path") } ?: "/"
        val host = links.firstOrNull()?.let { NodeHostPanel.linkParam(it, "host") } ?: "127.0.0.1"
        n++; val c = ProbeHarness.websocketStatus(base, path, host); if (c == 101) println("PASS WebSocket 101") else { fail++; println("FAIL ws $c") }
        n++; val b = ProbeHarness.websocketStatus(base, "/nope", host); if (b != 101) println("PASS wrong path -> $b") else { fail++; println("FAIL wrong path 101") }
    }
    println("$n tests, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
KT
"$KC" "$W"/src/*.kt -d "$W/out" -nowarn 2>&1 | grep -v '^warning' || true
APP=$W/app; S2=$W/srcdir; mkdir -p "$APP" "$S2"
cp "$R/app/src/main/assets/panels/catclient.worker.js" "$S2/"; cp "$R/app/src/main/assets/vercel/panel-adapter.mjs" "$S2/cat-panel-adapter.mjs"
cp "$R/node-panel/index.js" "$R/node-panel/package.json" "$APP/"; ln -s "$R/node_modules" "$APP/node_modules"
PORT=$((20000 + RANDOM % 20000)); UUID=6b1f0c2e-3d4a-4b5c-8d6e-7f8091a2b3c4
echo "{\"UUID\":\"$UUID\",\"TROJAN_PASS\":\"cat-test\"}" > "$APP/cat-config.json"
(cd "$APP" && SERVER_PORT=$PORT CAT_PANEL_SOURCE_DIR=$S2 setsid nohup node index.js > "$W/launcher.log" 2>&1 < /dev/null &)
for i in $(seq 1 40); do grep -q listening "$W/launcher.log" 2>/dev/null && break; sleep 0.5; done
cd "$R/app"; timeout 90 java -cp "$W/out:$K/ktc/package/lib/kotlin-stdlib.jar" RunKt $PORT $UUID; rc=$?
pkill -f "node index[.]js" 2>/dev/null || true
exit $rc
