# Android failure report (85c61f1)

    - org.junit.ComparisonFailure: expected:&lt;[ZZ]&gt; but was:&lt;[Unknown Region]&gt;" type="org.junit.ComparisonFailure">org.junit.ComparisonFailure: expected:&lt;[ZZ]&gt; but was:&lt;[Unknown Region]&gt;

## files with compile errors
     11 app/src/main/java/com/cat/client/MainActivity.kt
      3 app/src/main/java/com/cat/client/AppDesign.kt
      2 app/src/main/java/com/cat/client/AppAccent.kt
      1 app/src/main/java/com/cat/client/SmartRouteScout.kt
      1 app/src/main/java/com/cat/client/MapOrigin.kt
      1 app/src/main/java/com/cat/client/IpGeolocation.kt
      1 app/src/main/java/com/cat/client/DefaultNetworkMonitor.kt
## log tail
### step18.log
✓ preset: both protocols
✓ preset: defaults + host + health ordering
✓ preset is idempotent (nothing left to change)
✓ survival without apply → 400
✓ settings dialed back (test setup)
✓ seed: 3 ips with provenance latency
✓ baseline sub: plain configs OFF (as configured)
✓ baseline sub: vless OFF, trojan only (as configured)
✓ baseline sub: original panel order
✓ survive: plain ports forced back on
✓ survive: both protocols forced back on
✓ survive: SNI pinned to worker Host (not spoofed)
✓ survive: scanner-proven fast address stays first
✓ survive applies to Base64 format
✓ survive applies to Clash/Mihomo format
✓ survive applies to sing-box format
✓ survive applies to Xray format
✓ survive render did NOT persist anything
✓ health ordering: 40ms leads, 120ms next, unknown last
✓ health ordering expires stale scans after 7 days
✓ health ordering carries domain Ping through DNS-to-IP mapping
✓ telemetry: users carry lastOnline (panel shows «never connected»)
✓ vercel sub answers 200 with links
✓ every vercel line is :443
✓ every vercel line is TLS
✓ every vercel address is a Vercel edge address or the project host
✓ Host == project domain on every vercel line
✓ every vercel SNI is a vercel.app name (project, bare vercel.app or mixed-case project)
✓ vercel sub carries the three SNI variants in equal numbers
✓ bare vercel.app SNI lines come first
✓ SNI-variant lines are named apart
✓ no vercel line uses a non-vercel.app SNI (vercel.com → 421)
✓ ?sni= pins one SNI (no variants)
✓ ?limit= still caps the vercel sub
✓ no ECH / cipher-suite pinning on vercel lines
✓ vercel sub carries the project host itself
✓ vercel sub carries both VLESS and Trojan
✓ ?port= cannot re-add non-443 ports on vercel
✓ vercel sub has no ProxyIP/SOCKS/SNI-spoof entries
✓ vercel keeps scanned Vercel IPs from the list
✓ vercel drops Cloudflare IPs from the list
(node:3611) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ vercel panel login admin/admin works
✓ vercel without storage refuses to create a user (409, explains storage)
✓ vercel with storage creates the user
✓ vercel login page explains Vercel Blob + the in-app fix
✓ vercel login page has no Cloudflare KV binding steps
✓ cloudflare login page keeps the KV binding steps
✓ cloudflare behaviour unchanged: non-Vercel addresses still present
✓ cloudflare behaviour unchanged: alternate ports still present
✓ vercel panel shows the SNI + scanner note
✓ cloudflare panel has no Vercel note
✓ vercel panelPage carries the note twice (dashboard + scanner)
all vercel-platform checks passed
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

SNI Lab — live SNI testing (panel 6.67.4)

(node:3645) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MapOrigin.kt:30:23 'constructor(p0: String!, p1: String!): Locale' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/SmartRouteScout.kt:61:35 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.

> Task :app:compileDebugJavaWithJavac
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin

BUILD SUCCESSFUL in 2m 41s
19 actionable tasks: 19 executed
### build-log.txt
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
C/C++: In file included from /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/extend.c:20:
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/extend.c:512:54: warning: format specifies type 'ssize_t' (aka 'long') but the argument has type 'int' [-Wformat]
C/C++:   512 |             LOG(LOG_E, "invalid record size: %zd\n", val->tls_rec_size);
C/C++:       |                                              ~~~     ^~~~~~~~~~~~~~~~~
C/C++:       |                                              %d
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:48: note: expanded from macro 'LOG'
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                         ~~~    ^~~~~~~~~~~
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                            ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:44: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
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

BUILD SUCCESSFUL in 1m 5s
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

MapOriginTest > countryNameIsLocalisedWithCodeFallback FAILED
    org.junit.ComparisonFailure at MapOriginTest.kt:29

456 tests completed, 1 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///home/runner/work/cat-client/cat-client/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 5s
24 actionable tasks: 4 executed, 20 up-to-date
