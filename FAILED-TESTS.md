# Android failure report (5a527b1)


## files with compile errors
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

> Task :app:processDebugMainManifest FAILED
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:30:9-54 Error:
	Attribute application@fullBackupContent value=(@xml/backup_rules) from AndroidManifest.xml:30:9-54
	is also present at [ca.psiphon:psiphontunnel:2.0.41] AndroidManifest.xml:6:18-88 value=(@xml/ca_psiphon_psiphontunnel_backup_rules).
	Suggestion: add 'tools:replace="android:fullBackupContent"' to <application> element at AndroidManifest.xml:26:5-164:19 to override.

See https://developer.android.com/r/studio-ui/build/manifest-merger for more information about the manifest merger.


FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:processDebugMainManifest'.
> Manifest merger failed : Attribute application@fullBackupContent value=(@xml/backup_rules) from AndroidManifest.xml:30:9-54
  	is also present at [ca.psiphon:psiphontunnel:2.0.41] AndroidManifest.xml:6:18-88 value=(@xml/ca_psiphon_psiphontunnel_backup_rules).
  	Suggestion: add 'tools:replace="android:fullBackupContent"' to <application> element at AndroidManifest.xml:26:5-164:19 to override.

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 1m 20s
11 actionable tasks: 11 executed
