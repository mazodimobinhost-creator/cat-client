# Android failure report (2923d1b)


## files with compile errors
      8 app/src/main/java/com/cat/client/MainActivity.kt
      3 app/src/test/java/com/whitedns/vpn/PanelUpdatePolicyTest.kt
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
> Task :app:mergeDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:parseDebugLocalResources

> Task :app:processDebugMainManifest
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-165:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-165:19 Warning:
	application@android:dataExtractionRules was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present

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
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:15652:12 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.
w: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/SmartRouteScout.kt:61:35 'val allNetworks: Array<(out) Network!>' is deprecated. Deprecated in Java.

BUILD SUCCESSFUL in 59s
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
C/C++: /home/runner/work/cat-client/cat-client/app/src/main/cpp/byedpi/packets.c:48:35: warning: unknown attribute 'nonstring' ignored [-Wunknown-attributes]
C/C++:    48 | char http_data[43] __attribute__((nonstring)) = {
C/C++:       |                                   ^~~~~~~~~
C/C++: 1 warning generated.
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

BUILD SUCCESSFUL in 28s
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
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PanelUpdatePolicyTest.kt:158:17 No parameter with name 'previewReviewed' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PanelUpdatePolicyTest.kt:159:17 No parameter with name 'confirmed' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PanelUpdatePolicyTest.kt:159:17 No value passed for parameter 'previewAccepted'.

> Task :app:compileDebugUnitTestKotlin FAILED

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

BUILD FAILED in 3s
22 actionable tasks: 4 executed, 18 up-to-date
