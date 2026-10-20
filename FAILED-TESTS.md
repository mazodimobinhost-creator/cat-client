# Android failure report (ba01eb4)


## files with compile errors
      7 app/src/main/java/com/cat/client/PanelUpdate.kt
      6 app/src/main/java/com/cat/client/VerifiedSubscriptionProjection.kt
      5 app/src/main/java/com/cat/client/WorkerSourceDownloader.kt
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

> Task :app:compileDebugKotlin FAILED
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/PanelUpdate.kt:37:12 Unresolved reference 'WorkerChannelArtifact'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/PanelUpdate.kt:37:59 Unresolved reference 'fromSource'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/PanelUpdate.kt:86:8 Unresolved reference 'WorkerManifestVerdict'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/PanelUpdate.kt:87:54 Unresolved reference 'validateChannel'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/PanelUpdate.kt:93:20 Unresolved reference 'WorkerManifestVerdict'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/PanelUpdate.kt:137:30 Unresolved reference 'ok'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/PanelUpdate.kt:138:102 Unresolved reference 'reason'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VerifiedSubscriptionProjection.kt:24:24 Argument type mismatch: actual type is 'T? (of fun <T> T.takeIf)', but 'K? (of fun <K> ELVIS_CALL)' was expected.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VerifiedSubscriptionProjection.kt:24:31 Unresolved reference 'remark'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VerifiedSubscriptionProjection.kt:24:39 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VerifiedSubscriptionProjection.kt:24:46 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VerifiedSubscriptionProjection.kt:24:69 Argument type mismatch: actual type is 'String', but 'K (of fun <K> ELVIS_CALL)' was expected.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VerifiedSubscriptionProjection.kt:25:50 Unresolved reference 'remark'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/WorkerSourceDownloader.kt:19:8 Unresolved reference 'WorkerChannelArtifact'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/WorkerSourceDownloader.kt:19:55 Unresolved reference 'fromSource'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/WorkerSourceDownloader.kt:25:27 Unresolved reference 'WorkerStaticAnalysisReport'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/WorkerSourceDownloader.kt:25:77 Unresolved reference 'analyze'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/WorkerSourceDownloader.kt:32:61 Unresolved reference 'plan'.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 48s
15 actionable tasks: 15 executed
