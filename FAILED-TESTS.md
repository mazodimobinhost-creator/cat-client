# Unit test failure report (bbb2fde)

    - java.lang.AssertionError: expected: java.lang.Integer&lt;100&gt; but was: java.lang.Long&lt;100&gt;" type="java.lang.AssertionError">java.lang.AssertionError: expected: java.lang.Integer&lt;100&gt; but was: java.lang.Long&lt;100&gt;
    - java.lang.AssertionError: expected: java.lang.Integer&lt;20&gt; but was: java.lang.Long&lt;20&gt;" type="java.lang.AssertionError">java.lang.AssertionError: expected: java.lang.Integer&lt;20&gt; but was: java.lang.Long&lt;20&gt;

## files with compile errors
## log tail
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

> Task :app:testDebugUnitTest

SmartRouteHistoryTest > aggregatesSuccessLatencyAndFailureStreak FAILED
    java.lang.AssertionError at SmartRouteHistoryTest.kt:20

SmartRouteHistoryTest > boundsHistoryToRecentSamples FAILED
    java.lang.AssertionError at SmartRouteHistoryTest.kt:30

132 tests completed, 2 failed

> Task :app:testDebugUnitTest FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///home/runner/work/cat-client/cat-client/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

BUILD FAILED in 6s
24 actionable tasks: 6 executed, 18 up-to-date
