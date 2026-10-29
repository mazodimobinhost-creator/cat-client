# Android failure report (1ddbdd0)


## files with compile errors
      6 app/src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt
## log tail
### step18.log
✓ another instance reads the users list
✓ tunnel requests reuse the cached read for 15 min
✓ panel/subscription requests re-read after 15 s
✓ a failed Blob read is retried, not mistaken for "no users"
✓ a persistent Blob failure surfaces as an error
✓ list() decodes hex paths back to keys
✓ delete removes the blob
all vercel-storage checks passed
✓ panel bundle has exactly one cloudflare:sockets import to shim
✓ vercel sub answers with links
✓ every Vercel line is WebSocket (the only transport the edge forwards)
✓ Vercel lines: :443, TLS, host == project, SNI in the vercel.app family, alpn http/1.1
✓ Vercel sub serves all three SNIs
✓ Vercel names are unique
✓ both VLESS and Trojan are served
✓ Clash/mihomo: no httpupgrade proxies on Vercel
✓ Clash/mihomo carries the bare vercel.app SNI too
✓ sing-box carries the bare vercel.app SNI too
✓ sing-box: Vercel outbounds use ws only
✓ VLESS over WebSocket reaches the target
✓ Trojan over WebSocket reaches the target
✓ VLESS over WebSocket on the advertised ?ed= path reaches the target
✓ WebSocket with a wrong UUID is refused
✓ httpupgrade request is refused quickly (no silent hang)
all vercel-transport checks passed
✓ test rewrite touched exactly the origin URL
✓ front answers its own health check with the origin
✓ panel /api/version through the front
✓ panel login page through the front
✓ subscription through the front
✓ VLESS over WebSocket through the front reaches the target
✓ VLESS with early data (Sec-WebSocket-Protocol) through the front
✓ front echoes the early-data subprotocol (strict clients need it)
✓ Trojan over WebSocket through the front reaches the target
✓ 3 MB download through the front arrives byte for byte
✓ a wrong UUID still gets nothing through the front
✓ without an origin the front refuses (no open proxy)
✓ an invalid origin is refused
✓ the front refuses to loop to itself
✓ redirects to the origin are rewritten to the front
✓ panel serves the shipped front script with the origin filled in
✓ panel refuses to fill in a non-hostname origin
all vercel-cf-front checks passed
✓ parseSums reads sha256sum lines (text and *binary), ignores junk
✓ patchPanel swaps the one cloudflare:sockets import
✓ patchPanel refuses 0 or 2 imports
✓ pickRelease skips drafts and releases without the adapter
✓ pickRelease takes the newest by date even if the list is out of order
✓ pickRelease honours a pinned tag
[cat] settings from cat-config.json: UUID, CAT_NO_DISK
✓ cat-config.json: only allowed keys, env wins, no objects
[cat] ⚠ cat-config.json is not valid JSON — ignored
✓ cat-config.json: broken JSON is ignored, not fatal
✓ app asset node-panel/index.js is identical to node-panel/index.js
✓ app asset node-panel/package.json is identical to node-panel/package.json
✓ app asset node-panel/README.md is identical to node-panel/README.md
✓ portOf: SERVER_PORT (KataBump) > PORT > 8080
✓ a file that does not match the SHA-256 list is refused
✓ launcher starts on SERVER_PORT and reports the source + storage
✓ /api/version answers through the launcher
✓ plain host: exactly one VLESS + one Trojan line
✓ plain host: address = the host, port = its own port, no TLS, ws, Host = host
✓ plain host: ?addr= / ?port= cannot turn it into a Cloudflare line
✓ HTTPS edge: one VLESS + one Trojan on host:443 TLS, SNI = Host = host
✓ behind Cloudflare: the full Cloudflare set (clean IPs + several CF ports)
✓ VLESS over WebSocket reaches the target through the launcher
✓ Trojan over WebSocket reaches the target through the launcher
✓ owner can sign in through the launcher
✓ plain http: the session cookie drops Secure (else browsers never keep it on http://IP:port)
✓ HTTPS edge: the session cookie keeps Secure
✓ signed-in panel explains the direct Node mode with the real address:port
✓ HTTPS edge: panel says TLS on 443 with the host name
✓ settings save on the Node host
✓ storage file written to cat-data (mode 0600)
✓ restart reuses the prepared panel
✓ identity read from cat-config.json (names logged, values never)
✓ the sub for the UUID from cat-config.json works after the restart
✓ settings survive a restart (file storage)
✓ direct mode: forged X-Forwarded-For does not dodge the login lockout
all node-host checks passed
(node:3713) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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

(node:3720) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
> Task :app:mergeDebugResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:parseDebugLocalResources

> Task :app:processDebugMainManifest
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-244:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-244:19 Warning:
	application@android:dataExtractionRules was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present

> Task :app:processDebugManifest
> Task :app:javaPreCompileDebug
> Task :app:processDebugManifestForPackage
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt:241:16 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt:241:26 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
fun CatClientVpnService.publishConnectionDelayTest(session: ConnectionDelayTestSession, finishedFingerprints: List<String> = ...): Unit
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt:330:24 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt:330:34 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
fun CatClientVpnService.publishConnectionSpeedTest(session: ConnectionSpeedTestSession): Unit
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt:347:30 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
fun CatClientVpnService.publishConnectionSpeedTest(session: ConnectionSpeedTestSession): Unit
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnServiceConnectionTests.kt:365:30 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
fun CatClientVpnService.publishConnectionSpeedTest(session: ConnectionSpeedTestSession): Unit

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

BUILD FAILED in 2m 14s
16 actionable tasks: 16 executed
