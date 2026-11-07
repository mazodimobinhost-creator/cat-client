# Android failure report (248f635)

    - java.lang.AssertionError" type="java.lang.AssertionError">java.lang.AssertionError

## files with compile errors
      6 app/src/main/java/com/cat/client/MainActivity.kt
      4 app/src/main/java/com/cat/client/MainActivityFox.kt
      3 app/src/main/java/com/cat/client/MainActivitySubscriptions.kt
      3 app/src/main/java/com/cat/client/AppDesign.kt
      2 app/src/main/java/com/cat/client/MainActivityRealInternet.kt
      2 app/src/main/java/com/cat/client/AppAccent.kt
      1 app/src/main/java/com/cat/client/SmartRouteScout.kt
      1 app/src/main/java/com/cat/client/MapOrigin.kt
      1 app/src/main/java/com/cat/client/MainActivityUiKit.kt
      1 app/src/main/java/com/cat/client/MainActivityScanner.kt
      1 app/src/main/java/com/cat/client/MainActivityNetworkTools.kt
      1 app/src/main/java/com/cat/client/MainActivityGuides.kt
      1 app/src/main/java/com/cat/client/MainActivityEngines.kt
      1 app/src/main/java/com/cat/client/MainActivityDiagnostics.kt
      1 app/src/main/java/com/cat/client/MainActivityDashboard.kt
      1 app/src/main/java/com/cat/client/MainActivityConnectionTesting.kt
      1 app/src/main/java/com/cat/client/MainActivityCloud.kt
      1 app/src/main/java/com/cat/client/MainActivityCatWarp.kt
      1 app/src/main/java/com/cat/client/MainActivityAntiFilterTools.kt
      1 app/src/main/java/com/cat/client/MainActivityAmneziaSstp.kt
## log tail
### step18.log
  type: 'test'
  ...
# Subtest: the free clean-IP library / ProxyIP feeds never reach a node sub (CI with network: 32 lines)
ok 6 - the free clean-IP library / ProxyIP feeds never reach a node sub (CI with network: 32 lines)
  ---
  duration_ms: 1.987493
  type: 'test'
  ...
# Subtest: a user pinned to Germany: their 🎯 ProxyIP and 🧦 SOCKS exits are in their sub on a node
ok 7 - a user pinned to Germany: their 🎯 ProxyIP and 🧦 SOCKS exits are in their sub on a node
  ---
  duration_ms: 1.367198
  type: 'test'
  ...
# Subtest: no exits typed → no built-in default ProxyIP lines on a node
ok 8 - no exits typed → no built-in default ProxyIP lines on a node
  ---
  duration_ms: 0.528169
  type: 'test'
  ...
# Subtest: data plane on a node: a pinned 🎯 ProxyIP carries the Cloudflare-hosted names, other sites go direct
ok 9 - data plane on a node: a pinned 🎯 ProxyIP carries the Cloudflare-hosted names, other sites go direct
  ---
  duration_ms: 1.833716
  type: 'test'
  ...
# Subtest: panel page on a node: «server IPs» view present, body marked node (Clean IP no longer blank)
ok 10 - panel page on a node: «server IPs» view present, body marked node (Clean IP no longer blank)
  ---
  duration_ms: 4.740886
  type: 'test'
  ...
# Subtest: rendered page: node CSS shows the server-IP view and Locations (Vercel still hides Locations)
ok 11 - rendered page: node CSS shows the server-IP view and Locations (Vercel still hides Locations)
  ---
  duration_ms: 8.758226
  type: 'test'
  ...
# Subtest: /api/node/ips: each typed IP comes back ✅/❌ with its test result; the page has the per-host capability list + save & test
ok 12 - /api/node/ips: each typed IP comes back ✅/❌ with its test result; the page has the per-host capability list + save & test
  ---
  duration_ms: 44.920415
  type: 'test'
  ...
1..12
# tests 12
# suites 0
# pass 12
# fail 0
# cancelled 0
# skipped 0
# todo 0
# duration_ms 6185.812744
✓ ClientHello SNI parser: a real hello → its SNI
✓ ClientHello SNI parser: half a hello → «wait for more» (null), HTTP → null
✓ X25519 pair in Xray text form (43 base64url chars each) + 8-hex shortId
✓ the public half really belongs to the private half
✓ Xray JSON: one VLESS-Reality inbound per profile on 127.0.0.1, vision flow, target = its SNI:443
✓ node Reality uses Xray-core v26.10.31 (MPTCP off)
✓ Xray JSON: the host's private ranges are blackholed
✓ SS-2022: server key 16 bytes, user key 16 bytes, stable per UUID, different per user
✓ SS-2022: ssEnsureKeys never replaces existing keys
✓ Xray JSON: Reality inbounds keep their places, SS-2022 is the last one (127.0.0.1, base+4, TCP, one key per user)
✓ mux: HTTP methods (incl. WebSocket GET and h2c PRI) → panel; random bytes → SS; a 3-byte start → wait
✓ unzipOne pulls the deflated «xray» out of a release-style zip
✓ Reality: offered on the direct host, OFF by default, no lines in the sub
✓ Reality: turning it on without confirm:true is refused
✓ Reality: Xray started and the path was proven through the public port (real cert for the SNI)
✓ Reality: Xray knows the owner's UUID
✓ SS-2022: the round trip through the PUBLIC port (mux → SS inbound, derived key) passed and ONE ss:// line is in the sub
✓ Reality: one VLESS-Reality line per operator profile in the sub (pbk/sid/sni/flow/port)
✓ one port, TLS → the Xray inbound of ITS SNI
✓ one port, HTTP still reaches the panel while Reality runs
✓ one port, the WebSocket tunnel still upgrades (101) while Reality runs
✓ a restart for a new user list keeps the lines in the sub meanwhile
✓ SS-2022: the metered user gets no ss:// line, the unmetered one gets exactly one
✓ a user with a volume quota gets no Reality lines and is not in Xray; a user without one is
✓ Reality off → status off and the lines (Reality + SS-2022) leave the sub

ALL PASSED
(node:3773) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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

(node:3780) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
> Task :app:mergeDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:parseDebugLocalResources

> Task :app:processDebugMainManifest
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-244:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-244:19 Warning:
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
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnServiceRealInternet.kt:140:24 Condition is always 'true'.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/DefaultNetworkMonitor.kt:145:26 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/IpGeolocation.kt:156:51 'constructor(p0: String!, p1: String!): Locale' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:700:13 'fun setSelectedTabIndicatorHeight(p0: Int): Unit' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:1440:58 'static field TRIM_MEMORY_RUNNING_LOW: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:1450:26 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:1450:43 'static fun parseActivityResult(p0: Int, p1: Int, p2: Intent!): IntentResult!' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:2874:12 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityAdvanced.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityAmneziaSstp.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityAntiFilterTools.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityCatWarp.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityCloud.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityConnectionTesting.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityDashboard.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityDiagnostics.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityEngines.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityFox.kt:82:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityFox.kt:438:22 'var databaseEnabled: Boolean' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityFox.kt:518:93 'static field SHOW_FORCED: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityFox.kt:791:61 'static field SOFT_INPUT_ADJUST_RESIZE: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityGuides.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityNetworkTools.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityRealInternet.kt:132:18 Condition is always 'false'.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityRealInternet.kt:153:19 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityScanner.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivitySubscriptions.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivitySubscriptions.kt:1695:5 'constructor(p0: Activity!): IntentIntegrator' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivitySubscriptions.kt:1697:42 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityUiKit.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MapOrigin.kt:33:30 'constructor(p0: String!, p1: String!): Locale' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/SmartRouteScout.kt:61:35 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.

> Task :app:compileDebugJavaWithJavac
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin

BUILD SUCCESSFUL in 2m 19s
19 actionable tasks: 19 executed
### build-log.txt
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: 12 warnings generated.
C/C++: In file included from /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/main.c:11:
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: 8 warnings generated.
C/C++: In file included from /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/desync.c:30:
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: 17 warnings generated.
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/packets.c:48:35: warning: unknown attribute 'nonstring' ignored [-Wunknown-attributes]
C/C++:    48 | char http_data[43] __attribute__((nonstring)) = {
C/C++:       |                                   ^~~~~~~~~
C/C++: 1 warning generated.
C/C++: In file included from /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/proxy.c:13:
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: 23 warnings generated.

> Task :app:mergeDebugJniLibFolders
> Task :app:validateSigningDebug
> Task :app:writeDebugAppMetadata
> Task :app:writeDebugSigningConfigVersions
> Task :app:mergeDebugNativeLibs
> Task :app:stripDebugDebugSymbols
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug

BUILD SUCCESSFUL in 56s
46 actionable tasks: 29 executed, 17 up-to-date
### unit-test.log
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes
> Task :app:bundleDebugClassesToRuntimeJar

> Task :app:testDebugUnitTest

Beta260LivePingTest > versionIsBeta260 FAILED
    java.lang.AssertionError at Beta260LivePingTest.kt:74

811 tests completed, 1 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///home/runner/work/cat-client/cat-client/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 12s
24 actionable tasks: 4 executed, 20 up-to-date
