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
