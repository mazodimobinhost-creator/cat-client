# Android failure report (a0ec828)


## files with compile errors
     13 app/src/test/java/com/cat/client/VercelPanelTest.kt
     11 app/src/main/java/com/cat/client/MainActivity.kt
      3 app/src/main/java/com/cat/client/AppDesign.kt
      2 app/src/main/java/com/cat/client/AppAccent.kt
      1 app/src/main/java/com/cat/client/SmartRouteScout.kt
      1 app/src/main/java/com/cat/client/MapOrigin.kt
      1 app/src/main/java/com/cat/client/IpGeolocation.kt
      1 app/src/main/java/com/cat/client/DefaultNetworkMonitor.kt
## log tail
### step18.log
✓ vercel login page explains Vercel Blob + the in-app fix
✓ vercel login page has no Cloudflare KV binding steps
✓ cloudflare login page keeps the KV binding steps
✓ settings: vercelDomain keeps a real domain (scheme/path stripped)
✓ settings: vercelDomain refuses vercel.app names
✓ settings: saved vercelDomain drives the Domain group
✓ vercel-doctor refuses anonymous callers
✓ vercel-doctor reports 101 for vercel.app / random / mixed / project SNIs
✓ vercel-doctor shows the nextjs.org fronting control as 421
✓ vercel-doctor dials with the planned SNIs
✓ vercel-doctor is not offered on Cloudflare
✓ cloudflare behaviour unchanged: non-Vercel addresses still present
✓ cloudflare behaviour unchanged: alternate ports still present
✓ vercel panel shows the SNI + scanner note
✓ cloudflare panel has no Vercel note
✓ vercel panel has the 🔬 Vercel scanner card (server + my-internet test + custom domain)
✓ cloudflare panel has no Vercel scanner card
✓ the scanner card script does not break out of its <script>
✓ vercel panelPage carries the note twice (dashboard + scanner)
all vercel-platform checks passed
Not implemented: Window's scrollTo() method
(node:3649) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ fa: card + config are on the page
✓ fa: server check renders 101 / 421 per group
✓ fa: server check posts to /api/vercel-doctor
✓ fa: my-internet check marks each name
✓ fa: my-internet check skips the mixed-case and nextjs.org rows
✓ fa: recommends only an SNI that passed BOTH checks (the random name here)
✓ fa: saving a vercel.app name is refused with a message
✓ fa: saving without storage explains the permanent way
✓ fa: a saved domain joins the next my-internet check
✓ fa: no script errors
Not implemented: Window's scrollTo() method
✓ en: card + config are on the page
✓ en: server check renders 101 / 421 per group
✓ en: server check posts to /api/vercel-doctor
✓ en: my-internet check marks each name
✓ en: my-internet check skips the mixed-case and nextjs.org rows
✓ en: recommends only an SNI that passed BOTH checks (the random name here)
✓ en: saving a vercel.app name is refused with a message
✓ en: saving without storage explains the permanent way
✓ en: a saved domain joins the next my-internet check
✓ en: no script errors
all vercel-doctor-ui checks passed
✓ users are written to Blob at once
✓ caches / rate-limit / events never reach Blob
✓ memory-only values are still readable in this instance
✓ expirationTtl is honoured in memory
✓ first traffic write goes out
✓ traffic is throttled (49 updates → 1 write)
✓ throttled value is served fresh from memory
✓ dirty traffic is flushed on a later request after the interval
✓ another instance reads the users list
✓ tunnel requests reuse the cached read for 15 min
✓ panel/subscription requests re-read after 15 s
✓ a failed Blob read is retried, not mistaken for "no users"
✓ a persistent Blob failure surfaces as an error
✓ list() decodes hex paths back to keys
✓ delete removes the blob
all vercel-storage checks passed
✓ panel bundle has exactly one cloudflare:sockets import to shim
✓ vercel sub answers with links
✓ every Vercel line is WebSocket (the only transport the edge forwards)
✓ Vercel lines: :443, TLS, host == project, SNI in the vercel.app family, alpn http/1.1
✓ Vercel sub serves all three SNIs
✓ Vercel names are unique
✓ both VLESS and Trojan are served
✓ Clash/mihomo: no httpupgrade proxies on Vercel
✓ Clash/mihomo carries the bare vercel.app SNI too
✓ sing-box carries the bare vercel.app SNI too
✓ sing-box: Vercel outbounds use ws only
✓ VLESS over WebSocket reaches the target
✓ Trojan over WebSocket reaches the target
✓ VLESS over WebSocket on the advertised ?ed= path reaches the target
✓ WebSocket with a wrong UUID is refused
✓ httpupgrade request is refused quickly (no silent hang)
all vercel-transport checks passed
(node:3673) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ gapped download: every byte arrives in order (420002/420002)
✓ slow trickle (3 ms gaps, odd sizes): nothing lost (105842/105842)
✓ burst (no gaps, 6.4 MB): nothing lost, bundled ≤128 KB (6553602/6553602)
✓ slow client: remote reading pauses (0 chunk(s) read while 3 MB was queued)
✓ slow client: resumes and delivers everything after the buffer drains (200/200 chunks)
tunnel grain tests passed

SNI Lab — live SNI testing (panel 6.67.4)

(node:3681) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
  ✓ an anonymous caller gets 401 (the table describes the owner's network)
  ✓ …and cannot trigger the subrequests either
  ✓ an empty candidate list is rejected, not answered with a fake table
  ✓ a fresh panel reports an empty run, not an invented one
  ✓ …and the defaults it ships are listed for the page to offer
  ✓ …and the effective pool it will save into
  ✓ the run reports one row per unique, well-formed candidate
  ✓ a Cloudflare-fronted hostname is marked cf
  ✓ …the trace body alone is enough when the server header is stripped
  ✓ a plain origin is NOT marked cf, and its status is reported
  ✓ garbage and the worker's own hostname never reach the network
  ✓ every probe was a /cdn-cgi/trace fetch, timed
  ✓ the run is persisted under its own KV key (the panel can show it after a reload)
  ✓ …and read back unchanged
  ✓ a long list is capped at 12 probes per run
  ✓ the settings page carries the lab card (test / edge / save)
  ✓ …with both button labels and the hint in the panel language
  ✓ …and the same keys exist in English (no untranslated row)
  · source-shape checks skipped (artifact is minified/obfuscated — identifiers renamed)
  ✓ a run changes the lab record but NEVER the settings (the pool is only written by the form)
  ✓ the settings payload advertises the defaults so the page can offer them

sni lab: 20 passed, 0 FAILED
### kotlin-precheck.log

Welcome to Gradle 8.9!

Here are the highlights of this release:
 - Enhanced Error and Warning Messages
 - IDE Integration Improvements
 - Daemon JVM Information

For more details see https://docs.gradle.org/8.9/release-notes.html

Starting a Gradle Daemon (subsequent builds will be faster)
Daemon JVM discovery is an incubating feature.

> Configure project :app
w: file:///home/runner/work/cat-client/cat-client/app/build.gradle.kts:113:41: Unnecessary non-null assertion (!!) on a non-null receiver of type String
w: file:///home/runner/work/cat-client/cat-client/app/build.gradle.kts:113:84: Unnecessary non-null assertion (!!) on a non-null receiver of type String
w: file:///home/runner/work/cat-client/cat-client/app/build.gradle.kts:142:9: 'jvmTarget: String' is deprecated. Please migrate to the compilerOptions DSL. More details are here: https://kotl.in/u1r8ln

> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig
> Task :app:generateDebugResValues
> Task :app:checkDebugAarMetadata
> Task :app:mapDebugSourceSetPaths
> Task :app:generateDebugResources
> Task :app:packageDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:mergeDebugResources
> Task :app:parseDebugLocalResources

> Task :app:processDebugMainManifest
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-243:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-243:19 Warning:
	application@android:dataExtractionRules was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present

> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:javaPreCompileDebug
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/AppAccent.kt:13:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
- To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
- To keep applying to the value parameter only, use the '@param:' annotation target.

See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/AppAccent.kt:82:50 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
- To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
- To keep applying to the value parameter only, use the '@param:' annotation target.

See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/AppDesign.kt:12:44 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
- To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
- To keep applying to the value parameter only, use the '@param:' annotation target.

See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/AppDesign.kt:24:45 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
- To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
- To keep applying to the value parameter only, use the '@param:' annotation target.

See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/AppDesign.kt:41:48 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
- To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
- To keep applying to the value parameter only, use the '@param:' annotation target.

See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/DefaultNetworkMonitor.kt:145:26 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/IpGeolocation.kt:156:51 'constructor(p0: String!, p1: String!): Locale' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:675:13 'fun setSelectedTabIndicatorHeight(p0: Int): Unit' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:2165:9 'constructor(p0: Activity!): IntentIntegrator' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:2167:46 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:3422:58 'static field TRIM_MEMORY_RUNNING_LOW: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:3432:26 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:3432:43 'static fun parseActivityResult(p0: Int, p1: Int, p2: Intent!): IntentResult!' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:20120:22 'var databaseEnabled: Boolean' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:20205:93 'static field SHOW_FORCED: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:20409:61 'static field SOFT_INPUT_ADJUST_RESIZE: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:28864:12 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MapOrigin.kt:33:30 'constructor(p0: String!, p1: String!): Locale' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/SmartRouteScout.kt:61:35 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.

> Task :app:compileDebugJavaWithJavac
> Task :app:bundleDebugClassesToCompileJar

> Task :app:compileDebugUnitTestKotlin FAILED
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:249:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:250:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:251:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:252:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:253:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:254:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:255:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:256:24 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:257:11 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:257:35 Unresolved reference 'it'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:257:43 Unresolved reference 'Cause'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:257:66 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/cat/client/VercelPanelTest.kt:260:32 Unresolved reference 'Cause'.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugUnitTestKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 3m 10s
19 actionable tasks: 19 executed
