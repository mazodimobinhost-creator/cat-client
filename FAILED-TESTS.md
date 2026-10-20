# Android failure report (239bc56)


## files with compile errors
      8 app/src/main/java/com/cat/client/MainActivity.kt
      2 app/src/main/java/com/cat/client/ProxyIpScannerPage.kt
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
> Task :app:mergeDebugResources
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11196:47 Unresolved reference 'parseHostPort'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11197:39 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11197:45 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11197:45 Function 'component1()' is ambiguous for this expression: 
fun <T> Array<out T>.component1(): T
fun ByteArray.component1(): Byte
fun ShortArray.component1(): Short
fun IntArray.component1(): Int
fun LongArray.component1(): Long
fun FloatArray.component1(): Float
fun DoubleArray.component1(): Double
fun BooleanArray.component1(): Boolean
fun CharArray.component1(): Char
fun <T> List<T>.component1(): T
fun <K, V> Map.Entry<K, V>.component1(): K
fun UIntArray.component1(): UInt
fun ULongArray.component1(): ULong
fun UByteArray.component1(): UByte
fun UShortArray.component1(): UShort.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11197:45 Function 'component2()' is ambiguous for this expression: 
fun <T> Array<out T>.component2(): T
fun ByteArray.component2(): Byte
fun ShortArray.component2(): Short
fun IntArray.component2(): Int
fun LongArray.component2(): Long
fun FloatArray.component2(): Float
fun DoubleArray.component2(): Double
fun BooleanArray.component2(): Boolean
fun CharArray.component2(): Char
fun <T> List<T>.component2(): T
fun <K, V> Map.Entry<K, V>.component2(): V
fun UIntArray.component2(): UInt
fun ULongArray.component2(): ULong
fun UByteArray.component2(): UByte
fun UShortArray.component2(): UShort.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11223:30 Argument type mismatch: actual type is 'List<Any>', but 'List<VerifiedEndpoint>' was expected.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11242:62 Unresolved reference 'authority'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:11250:70 Unresolved reference 'authority'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/ProxyIpScannerPage.kt:452:79 Unresolved reference 'OK'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/ProxyIpScannerPage.kt:505:73 Unresolved reference 'bestLatencyMs'.

> Task :app:compileDebugKotlin FAILED

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

BUILD FAILED in 55s
15 actionable tasks: 15 executed
