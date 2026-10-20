# Android failure report (a873cec)


## files with compile errors
      4 app/src/main/java/com/cat/client/MihomoShareLink.kt
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

> Task :app:compileDebugKotlin FAILED
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MihomoShareLink.kt:240:94 Argument type mismatch: actual type is 'String', but 'Long' was expected.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MihomoShareLink.kt:255:68 No value passed for parameter 'nowMs'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MihomoShareLink.kt:255:68 No value passed for parameter 'maxAgeMs'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MihomoShareLink.kt:255:81 Unresolved reference 'encode'.

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

BUILD FAILED in 41s
15 actionable tasks: 15 executed
