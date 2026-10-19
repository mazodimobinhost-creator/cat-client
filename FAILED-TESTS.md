# Android failure report (4beec06)

    - java.lang.IllegalArgumentException: Failed requirement." type="java.lang.IllegalArgumentException">java.lang.IllegalArgumentException: Failed requirement.
    - java.lang.IllegalArgumentException: Failed requirement." type="java.lang.IllegalArgumentException">java.lang.IllegalArgumentException: Failed requirement.

## files with compile errors
      8 app/src/main/java/com/cat/client/MainActivity.kt
      1 app/src/main/java/com/cat/client/SmartRouteScout.kt
      1 app/src/main/java/com/cat/client/IpGeolocation.kt
      1 app/src/main/java/com/cat/client/DefaultNetworkMonitor.kt
      1 app/src/main/java/com/cat/client/AppAccent.kt
## log tail
### kotlin-precheck.log

Welcome to Gradle 8.9!

Here are the highlights of this release:
 - Enhanced Error and Warning Messages
 - IDE Integration Improvements
 - Daemon JVM Information

For more details see https://docs.gradle.org/8.9/release-notes.html

Starting a Gradle Daemon (subsequent builds will be faster)
Daemon JVM discovery is an incubating feature.
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
> Task :app:parseDebugLocalResources

> Task :app:processDebugMainManifest
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-165:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-165:19 Warning:
	application@android:dataExtractionRules was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present

> Task :app:mergeDebugResources
> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/AppAccent.kt:12:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
- To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
- To keep applying to the value parameter only, use the '@param:' annotation target.

See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/DefaultNetworkMonitor.kt:145:26 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/IpGeolocation.kt:156:51 'constructor(p0: String!, p1: String!): Locale' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:81:8 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:584:13 'fun setSelectedTabIndicatorHeight(p0: Int): Unit' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:1954:9 'constructor(p0: Activity!): IntentIntegrator' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:1956:46 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:2587:58 'static field TRIM_MEMORY_RUNNING_LOW: Int' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:2596:26 'class IntentIntegrator : Any' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:2596:43 'static fun parseActivityResult(p0: Int, p1: Int, p2: Intent!): IntentResult!' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:15649:12 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/SmartRouteScout.kt:61:35 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.

BUILD SUCCESSFUL in 1m 16s
15 actionable tasks: 15 executed
### build-log.txt
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++:    65 |         __android_log_print(s, "proxy", str, ##__VA_ARGS__)
C/C++:       |                                              ^
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/error.h:65:46: warning: token pasting of ',' and __VA_ARGS__ is a GNU extension [-Wgnu-zero-variadic-macro-arguments]
C/C++: 12 warnings generated.
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

BUILD SUCCESSFUL in 38s
46 actionable tasks: 31 executed, 15 up-to-date
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
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes

> Task :app:testDebugUnitTest FAILED

VerifiedConfigFailoverTest > routeChangePreservesEveryNonIngressField FAILED
    java.lang.IllegalArgumentException at VerifiedConfigFailoverTest.kt:15

VerifiedConfigFailoverTest > noReplacementReturnsByteEquivalentUri FAILED
    java.lang.IllegalArgumentException at VerifiedConfigFailoverTest.kt:32

239 tests completed, 2 failed

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///home/runner/work/cat-client/cat-client/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 7s
24 actionable tasks: 6 executed, 18 up-to-date
FAIL cat-panel
```
✓ latency: per-country P50/P95 sorted, dead IPs excluded
✓ panel: latency card container + title + neighbor badge + svc note
✓ panel: best-ms summary wired
✓ panel: inline script still parses
✓ neighbor: import ok with neighborIps
✓ neighbor: flagged IP gets src=neighbor
✓ neighbor: regular scanner IP keeps src=scanner
✓ neighbor: crown badge wired in UI
✓ stealth: one-time latch defaults panelPath=panel
✓ stealth: explicit path respected
✓ stealth: opting out stays off
✓ panel: manual-add card wired
✓ panel: inline script still parses (manual add)
✓ panel path: settings PUT ok
✓ panel path: / redirects transparently
✓ panel path: /panel serves the real login
✓ panel path: unknown path is ordinary 404
✓ stealth: robots.txt quiet
✓ stealth: reset to legacy open
✓ manual: import ok
✓ manual: src=manual stored
✓ beta58: stealth on for routing tests
✓ beta58: /?ips= hops to the real panel path
✓ beta58: /?p= quick-login hops to /panel
✓ beta58: plain / redirects transparently
✓ beta58: panel entry nudges the repo pools
✓ beta58: sub hit nudges the repo pools
✓ beta58: stealth reset
✓ beta58: ?ips= never auto-imports
✓ beta59: free preset button wired
✓ beta59: ECH field note on the toggle
✓ beta59: inline script still parses
✓ dns cache: roundtrip
✓ dns cache: miss returns null
✓ cooldown: attempts listed before
✓ cooldown: relayAttempts still returns all (filtering happens at dial)
✓ cooldown: relayCool flags the failed one
✓ cooldown: direct dial never cools
✓ cooldown: socks relays cool too
✓ t.me socks: plain link parses
✓ t.me socks: auth link parses
✓ sub query tolerates &amp; links (Telegram copy)
✓ ext sub fetch sanitizes &amp;
✓ generated TLS configs put the worker host into the sni param (Cloudflare answers 403 on a mismatch)
✓ 30 addresses still yield plain-port entries (entry limit is shared between waves)
✓ wave rotation keeps address diversity (>= 12 distinct addresses)
✓ entry limit still respected with many addresses
✓ owner IPs lead a sub that also carries free library IPs
✓ free library IPs still appear, on :443, behind the owner
✓ ?norepo=1 still drops the library IPs

8 FAILED
- empty address list falls back to built-in configs + worker host — vless://11111111-2222-4333-8444-555555555555@www.speedtest.net:443?encryption=none&security=tls&type=ws&host=catpanel-demo.workers.dev&path=%2Fvl%2F73atqjr5mmk1yojdcOYueuH%3Fed%3D2
- proxyIps keep ip:port exactly (never stripped) — ["104.17.1.1:2053","83.147.217.103:1080"]
- proxyIpList puts preferred-country proxy first — ["proxyip.cmliussss.net","di.nscl.ir","tr.diam4.ggff.net"]
- user own ProxyIP still present
- per-ProxyIP configs: numbered + flag + Persian country — 🎯 1. proxyip.cmliussss.net
- per-ProxyIP configs keep the SNI remark suffix while spoofing is on — 🎯 1. proxyip.cmliussss.net · icook.tw
- PX config carries a parseable proxyip relay path — vless://11111111-2222-4333-8444-555555555555@catpanel-demo.workers.dev:443?encryption=none&security=tls&type=ws&host=catpanel-demo.workers.dev&path=%2Fvl%2F73atqjr5mmk1yojdcOYueuH%3Fed%3D2560%26proxyip%3Dproxyip.cmliussss.net&sni=catpanel-demo.workers.dev&fp=chrome&alpn=http%2F1.1&cs=TLS_AES_256_GCM_SHA384%3ATLS_CHACHA20_POLY1305_SHA256%3ATLS_AES_128_GCM_SHA256%3ATLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384%3ATLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256%3ATLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256&fm=%7B%22tcp%22%3A%5B%7B%22type%22%3A%22fragment%22%2C%22settings%22%3A%7B%22packets%22%3A%22tlshello%22%2C%22lengths%22%3A%5B%220-6%22%2C%22100-140%22%2C%221-3%22%5D%2C%22delays%22%3A%5B%221-3%22%5D%2C%22maxSplit%22%3A%228%22%7D%7D%2C%7B%22type%22%3A%22fragment%22%2C%22settings%22%3A%7B%22packets%22%3A%221-3%22%2C%22lengths%22%3A%5B%2280-160%22%2C%221-3%22%5D%2C%22delays%22%3A%5B%221-3%22%5D%2C%22maxSplit%22%3A%228%22%7D%7D%5D%7D#%F0%9F%8E%AF%201.%20proxyip.cmliussss.net
- en locale → English country label — 🎯 1. proxyip.cmliussss.net
```
PASS ccq
PASS ech
PASS panel-dom
PASS wizard
PASS multipart-upload
PASS chain-protocols
PASS android-guard
PASS link-shape
PASS panel-integrity
PASS kv-persistence
PASS panel-update-contract
PASS panel-selfupdate
PASS sni-host
FAIL proxyip-sub
```
✗ plain-only ports: 🎯 lines exist and are plain (security=none, no SNI, port 80) — {"tls":false,"sni":"","port":80}
✓ plain-only ports: the clean lines are plain too
✗ clash builder link: 🎯 proxies present, total ≤ 24 — 18 / 2
✗ sing-box builder link: 🎯 outbounds present, total ≤ 24 — 18 / 2
✗ xray builder link: 🎯 configs present, total ≤ 24 — 18 / 2
✗ panel-user sub with a limit: 🎯 lines present, total ≤ 24 — 24 / 2
✓ 🧬 extra-SNI lines: still in subs without a limit
✓ 🧬 extra-SNI lines: not in limited links (SNI ≠ Host is rejected by Cloudflare)
✓ 🧦 own SOCKS relay: 2 lines (VLESS+Trojan) in a limited link, relay referenced (not inlined)
✓ 🧦 the link never contains the relay username/password
✗ 🧦 relay does not displace the ProxyIP lines
✓ 20 ProxyIPs, limit=24: total is exactly 24
✗ 20 ProxyIPs, limit=24: the 🎭 part is capped at half (12), clean IPs keep the rest — 0 🎯 / 24 clean
✓ 20 ProxyIPs, limit=24: clean-IP lines still come first
✗ limit=40 → 40 lines, 20 of them 🎯 — 40 / 0
✗ small list + limit=60: 4 clean + all 6 🎯, nothing cut — 6 / 2
✓ no ProxyIP configured: a limited link carries no 🎯 line (defaults are not injected)
✓ no ProxyIP configured: a sub without a limit keeps the built-in defaults (unchanged)
✗ links: 6 🎯 entries (3 ProxyIPs × 2 protocols) — 2
✓ links: every 🎯 entry pins EXACTLY the ProxyIP in its name
✗ links: the pins differ per entry (not 6 copies of one config) — 203.0.113.11:8443
✓ links: the 🧦 SOCKS entries carry an opaque relay reference, never the credentials
✓ links: no 🧦 entry leaks a socks5 URL
✓ links: ordinary entries carry no pin
✗ clash: 6 🎯 entries (3 ProxyIPs × 2 protocols) — 2
✓ clash: every 🎯 entry pins EXACTLY the ProxyIP in its name
✗ clash: the pins differ per entry (not 6 copies of one config) — 203.0.113.11:8443
✓ clash: the 🧦 SOCKS entries carry an opaque relay reference, never the credentials
✓ clash: no 🧦 entry leaks a socks5 URL
✓ clash: ordinary entries carry no pin
✗ sing-box: 6 🎯 entries (3 ProxyIPs × 2 protocols) — 2
✓ sing-box: every 🎯 entry pins EXACTLY the ProxyIP in its name
✗ sing-box: the pins differ per entry (not 6 copies of one config) — 203.0.113.11:8443
✓ sing-box: the 🧦 SOCKS entries carry an opaque relay reference, never the credentials
✓ sing-box: no 🧦 entry leaks a socks5 URL
✓ sing-box: ordinary entries carry no pin
✗ xray: 6 🎯 entries (3 ProxyIPs × 2 protocols) — 2
✓ xray: every 🎯 entry pins EXACTLY the ProxyIP in its name
✗ xray: the pins differ per entry (not 6 copies of one config) — 203.0.113.11:8443
✓ xray: the 🧦 SOCKS entries carry an opaque relay reference, never the credentials
✓ xray: no 🧦 entry leaks a socks5 URL
✓ xray: ordinary entries carry no pin
✓ app-scanner line (raw text): stored as a clean socks5:// entry, nothing else
✓ app-scanner line: nothing reported as ignored
✓ app-scanner line: the sub gets its 🧦 SOCKS configs (VLESS+Trojan), named without the relay address
✓ app-scanner line: … and NO stray 🎯 relay for the same IP
✓ data stored by the old form is repaired (remark dropped, stray portless IP removed)
✓ the whole line as one element is cleaned too
✓ a t.me/socks share becomes a socks5:// relay (the box promises it)
✓ … and it is used as a SOCKS relay, not as a Cloudflare ProxyIP
✗ valid relays kept: ip, ip:port, [v6]:port, bare v6, dotted name; remarks stripped — ["203.0.113.11:8443","[2001:db8::1]:8443"]
✗ everything else is REPORTED, not silently kept (http://, junk words, port out of range) — ["http://203.0.113.20:8080","not","a","host!!","1.2.3.4:99999","203.0.113.10","2001:db8::2","proxyip.example.com"]
✓ a comment-only line is not an «ignored entry»
✓ saving the cleaned list again changes nothing and ignores nothing
✓ a save that does not touch the list reports nothing ignored
✓ parseChain accepts a #remark (also for the fixed-exit chain setting)
✓ 🎭 form posts the Proxy IP box as raw text (server splits lines and strips remarks)
✓ 🎭 form tells the owner what was ignored

PROXYIP SUB TESTS FAILED (25)
```
FAIL proxyip-dataplane
```
✓ syntax check passed
(node:3317) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?dp=base is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✗ baseline: a plain config to a Cloudflare-hosted target dials the FIRST relay of the panel list — 203.0.113.11:8443
✓ the 🎯 link for 203.0.113.11:8443 carries a real pin in its client path
✓ VLESS 🎯 203.0.113.11:8443: the worker dials THAT relay first (host AND port from the pin)
file:///home/runner/work/cat-client/cat-client/scripts/panels/proxyip-dataplane.test.mjs:107
const clientPath = (link) => dec((/[?&]path=([^&#]*)/.exec(link.split('#')[0]) || [])[1]);
                                                                ^

TypeError: Cannot read properties of undefined (reading 'split')
    at clientPath (file:///home/runner/work/cat-client/cat-client/scripts/panels/proxyip-dataplane.test.mjs:107:65)
    at file:///home/runner/work/cat-client/cat-client/scripts/panels/proxyip-dataplane.test.mjs:142:26

Node.js v22.23.3
```
PASS addr-port-query
PASS proxyip-feeds
PASS browser-probe
PASS ws-probe
PASS survival
PASS hostile-env
PASS content-type
PASS paste-artifact
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
