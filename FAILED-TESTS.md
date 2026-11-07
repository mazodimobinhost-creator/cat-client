# Android failure report (d331b7a)


## files with compile errors
      1 app/src/main/java/com/cat/client/MainActivityFreeNetworks.kt
## log tail
### step18.log
  type: 'test'
  ...
# Subtest: the free clean-IP library / ProxyIP feeds never reach a node sub (CI with network: 32 lines)
ok 6 - the free clean-IP library / ProxyIP feeds never reach a node sub (CI with network: 32 lines)
  ---
  duration_ms: 1.823471
  type: 'test'
  ...
# Subtest: a user pinned to Germany: their 🎯 ProxyIP and 🧦 SOCKS exits are in their sub on a node
ok 7 - a user pinned to Germany: their 🎯 ProxyIP and 🧦 SOCKS exits are in their sub on a node
  ---
  duration_ms: 1.556409
  type: 'test'
  ...
# Subtest: no exits typed → no built-in default ProxyIP lines on a node
ok 8 - no exits typed → no built-in default ProxyIP lines on a node
  ---
  duration_ms: 0.632272
  type: 'test'
  ...
# Subtest: data plane on a node: a pinned 🎯 ProxyIP carries the Cloudflare-hosted names, other sites go direct
ok 9 - data plane on a node: a pinned 🎯 ProxyIP carries the Cloudflare-hosted names, other sites go direct
  ---
  duration_ms: 2.145462
  type: 'test'
  ...
# Subtest: panel page on a node: «server IPs» view present, body marked node (Clean IP no longer blank)
ok 10 - panel page on a node: «server IPs» view present, body marked node (Clean IP no longer blank)
  ---
  duration_ms: 5.996939
  type: 'test'
  ...
# Subtest: rendered page: node CSS shows the server-IP view and Locations (Vercel still hides Locations)
ok 11 - rendered page: node CSS shows the server-IP view and Locations (Vercel still hides Locations)
  ---
  duration_ms: 10.617827
  type: 'test'
  ...
# Subtest: /api/node/ips: each typed IP comes back ✅/❌ with its test result; the page has the per-host capability list + save & test
ok 12 - /api/node/ips: each typed IP comes back ✅/❌ with its test result; the page has the per-host capability list + save & test
  ---
  duration_ms: 56.951647
  type: 'test'
  ...
1..12
# tests 12
# suites 0
# pass 12
# fail 0
# cancelled 0
# skipped 0
# todo 0
# duration_ms 6220.826978
✓ ClientHello SNI parser: a real hello → its SNI
✓ ClientHello SNI parser: half a hello → «wait for more» (null), HTTP → null
✓ X25519 pair in Xray text form (43 base64url chars each) + 8-hex shortId
✓ the public half really belongs to the private half
✓ Xray JSON: one VLESS-Reality inbound per profile on 127.0.0.1, vision flow, target = its SNI:443
✓ node Reality uses Xray-core v26.10.31 (MPTCP off)
✓ Xray JSON: the host's private ranges are blackholed
✓ SS-2022: server key 16 bytes, user key 16 bytes, stable per UUID, different per user
✓ SS-2022: ssEnsureKeys never replaces existing keys
✓ Xray JSON: Reality inbounds keep their places, SS-2022 is the last one (127.0.0.1, base+4, TCP, one key per user)
✓ mux: HTTP methods (incl. WebSocket GET and h2c PRI) → panel; random bytes → SS; a 3-byte start → wait
✓ unzipOne pulls the deflated «xray» out of a release-style zip
✓ Reality: offered on the direct host, OFF by default, no lines in the sub
✓ Reality: turning it on without confirm:true is refused
✓ Reality: Xray started and the path was proven through the public port (real cert for the SNI)
✓ Reality: Xray knows the owner's UUID
✓ SS-2022: the round trip through the PUBLIC port (mux → SS inbound, derived key) passed and ONE ss:// line is in the sub
✓ Reality: one VLESS-Reality line per operator profile in the sub (pbk/sid/sni/flow/port)
✓ one port, TLS → the Xray inbound of ITS SNI
✓ one port, HTTP still reaches the panel while Reality runs
✓ one port, the WebSocket tunnel still upgrades (101) while Reality runs
✓ a restart for a new user list keeps the lines in the sub meanwhile
✓ SS-2022: the metered user gets no ss:// line, the unmetered one gets exactly one
✓ a user with a volume quota gets no Reality lines and is not in Xray; a user without one is
✓ Reality off → status off and the lines (Reality + SS-2022) leave the sub

ALL PASSED
(node:3927) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ gapped download: every byte arrives in order (420002/420002)
✓ slow trickle (3 ms gaps, odd sizes): nothing lost (105842/105842)
✓ burst (no gaps, 6.4 MB): nothing lost, bundled ≤128 KB (6553602/6553602)
✓ slow client: remote reading pauses (0 chunk(s) read while 3 MB was queued)
✓ slow client: resumes and delivers everything after the buffer drains (200/200 chunks)
tunnel grain tests passed

SNI Lab — live SNI testing (panel 6.67.4)

(node:3934) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:mergeDebugResources
> Task :app:parseDebugLocalResources

> Task :app:processDebugMainManifest
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-244:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-244:19 Warning:
	application@android:dataExtractionRules was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present

> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:javaPreCompileDebug
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityFreeNetworks.kt:1066:27 Method 'contains' from ConcurrentHashMap might have unexpected semantics: it calls 'containsValue' instead of 'containsKey'. Use the explicit form of the call to 'containsKey'/'containsValue'/'contains' or cast the value to 'kotlin.collections.Map' instead. See https://youtrack.jetbrains.com/issue/KT-18053 for more details.

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

BUILD FAILED in 2m 21s
16 actionable tasks: 16 executed
