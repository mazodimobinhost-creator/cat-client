# Android failure report (ae09e67)


## files with compile errors
     24 app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt
      1 app/src/main/java/com/cat/client/WhiteDnsSmartScanner.kt
      1 app/src/main/java/com/cat/client/MainActivity.kt
## log tail
### step18.log
✓ the app scanner line pasted RAW into the box («#SOCKS5 ip» remark): … which is asked to CONNECT to the real target
✓ the app scanner line pasted RAW into the box («#SOCKS5 ip» remark): … and the client payload reaches the far side through it
✓ a t.me/socks share: the sub has a 🧦 config for it
✓ a t.me/socks share: the 🧦 name carries no relay host:port
✓ a t.me/socks share: the worker exits through that SOCKS5 server (first dial)
✓ a t.me/socks share: … which is asked to CONNECT to the real target
✓ a t.me/socks share: … and the client payload reaches the far side through it

PROXYIP DATA-PLANE TESTS PASSED
✓ syntax check passed
✓ subQuery keeps only in-range ports (70000/0/-5/abc/99999 dropped)
✓ subQuery accepts 65535, rejects 65536
✓ ?port=70000: sub is NOT empty (falls back to the panel port walk)
✓ ?port=70000: every emitted port is dialable
✓ ?port=99999: sing-box carries no out-of-range port
✓ ?port=99999: clash carries no out-of-range port
✓ ?port=99999: xray carries no out-of-range port
✓ ?addr=ip#DE: sub is not empty
✓ ?addr=ip#DE: every server is the BARE ip
✓ ?addr=ip#DE: params survive the «#» (path+security present)
✓ ?addr=ip#DE: the tag shows up as the entry country (flag in the name)
✓ ?addr=ip#DE: clash server is the bare ip (no # in the YAML)
✓ ?addr=ip#DE: sing-box server is the bare ip
✓ ?addr=ip#DE + strict country DE: the pinned address survives
✓ ?addr=ip:99999: no «[1.2.3.4:99999]» garbage
✓ ?addr=ip:99999: falls back to the bare host on the panel ports
✓ ?addr=ip:2053: every config keeps the verified port 2053
✓ ?addr=[v6]:8443: the pin survives, bracketed once
✓ ?addr=bare v6: survives untouched (not mistaken for host:port)
✓ ?addr=mix of junk + one ip: only the real ip is served
✓ ?addr=all junk: the panel address list takes over (never an empty sub)
ADDR/PORT QUERY TESTS PASSED
(node:3314) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=aq-unit is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ syntax check passed
✓ md-daily parses validated rows
✓ md-daily drops risk > 5 (the CAPTCHA tail)
✓ md-daily drops junk cells
✓ md-daily on garbage → empty, no throw
✓ txt «ip port» lines keep their verified port
✓ txt space lines are port-pinned like ip:port
✓ txt space form == colon form
✓ refresh merges md-daily + space-txt feeds (dedup incl. duplicate table rows)
✓ daily IPs reach the pool port-pinned 443
✓ high-risk IP never reaches the pool
✓ space-txt IPs keep verified ports + repo country tag
✓ country import writes the selected ProxyIPs into the active list
✓ reselecting a country is actionable but reports zero real additions
(node:3330) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=pxfeeds is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ country import persists addresses and country metadata
✓ DEFAULT_PROXY_REPOS carries the daily source
✓ default repo list still fits the cap (≤10)
PROXYIP FEED TESTS PASSED
✓ survival apply → ok + changed list
✓ survival returns the survive link
(node:3349) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ preset: daily rotation
✓ preset: safe SNI = Host (spoofing/rotation off)
✓ preset: fragment on
✓ preset: plain ports on
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

SNI Lab — live SNI testing (panel 6.67.4)

(node:3361) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-172:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-172:19 Warning:
	application@android:dataExtractionRules was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present

> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:javaPreCompileDebug
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3122:25 Unresolved reference 'extractRealConfigTemplates'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3125:9 Unresolved reference 'currentStrategy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3126:52 Unresolved reference 'buildServerlessV52FinalMaskJson'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3128:28 Function 'component1()' is ambiguous for this expression: 
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3128:28 Function 'component2()' is ambiguous for this expression: 
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3128:46 Method 'iterator()' is ambiguous for this expression. Applicable candidates:
fun <T> Enumeration<T>.iterator(): Iterator<T>
fun <T> Iterator<T>.iterator(): Iterator<T>
fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>>
fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>>
fun CharSequence.iterator(): CharIterator
fun BufferedInputStream.iterator(): ByteIterator
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3129:29 Unresolved reference 'ensureDedicatedForwarderPort'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3130:34 Unresolved reference 'remoteHost'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3131:34 Unresolved reference 'remotePort'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3135:31 Unresolved reference 'originalSni'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3135:43 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3135:57 Unresolved reference 'hostHeader'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3135:68 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3135:82 Unresolved reference 'remoteHost'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3136:32 Unresolved reference 'hostHeader'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3136:43 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3137:58 Unresolved reference 'path'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3139:67 Unresolved reference 'remark'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3142:31 Unresolved reference 'scheme'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3143:33 Unresolved reference 'uuidOrPass'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3143:90 Unresolved reference 'network'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3145:32 Unresolved reference 'uuidOrPass'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3145:105 Unresolved reference 'network'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/LocalSniSpoofProxy.kt:3210:51 Cannot access 'val SELECT_GROUP: String': it is private in 'com/cat/client/MihomoLinkConfigBuilder'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:17279:52 Cannot access 'currentDecoySni': it is private in 'com/cat/client/LocalSniSpoofProxy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/WhiteDnsSmartScanner.kt:1358:77 Unresolved reference 'DNSTT_COMPAT'.

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

BUILD FAILED in 1m 32s
16 actionable tasks: 16 executed
