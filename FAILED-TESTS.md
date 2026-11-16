# Android failure report (527649f)


## files with compile errors
      2 app/src/main/java/com/cat/client/MainActivityMethodFinder.kt
      2 app/src/main/java/com/cat/client/MainActivityEngines.kt
## log tail
### panel-tests.log
  ✓ the JVM tests for the policy ship in the unit-test source set
  ✓ all 5 watchdog strings exist in English and Persian
  ✓ …with matching placeholders in both languages
  ✓ the policy tests cover the anti-flap rules (one failure is noise, one success resets)

orbot engine: 53 passed, 0 FAILED
(node:3146) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ admin/admin login still works (default kept)
✓ user created with firstUseDays=30 and no expiry yet
✓ pending user sub works (not expired)
✓ pending user sub has no expire= yet
✓ first connection is admitted
✓ first connection starts the 30-day clock (stored)
✓ later connections do not write KV again
✓ listed user now shows a real expiry
✓ per-user IPs kept with their exact ports
✓ user sub uses the user IP with its exact port 11111
✓ user sub never re-ports a pinned IP to 443/80
✓ per-user IP list capped at 20
✓ empty list clears per-user IPs (back to panel IPs)
✓ fw preset stored
✓ Irancell F&F user link uses 188.114.98.0 + fp=unsafe
✓ the same user's Clash link still works (preset skipped)
✓ unknown preset id is rejected to automatic
✓ manifest served as application/manifest+json
✓ manifest standalone with an icon
✓ icon is an SVG
✓ panel page links the manifest
✓ panel has ⚡ quick user, install button, first-use switch, preset and IP fields
✓ fa + en strings for the new fields
Not implemented: Window's scrollTo() method
✓ UI loads without JS errors
✓ ⚡ quick user: 30 days from first connection, both protocols
✓ ⚡ quick user opens its links drawer
✓ pending user shows «from first connection» in the list
✓ first-use switch is on for a pending user
✓ preset select lists automatic + the 5 operator profiles
✓ saving sends firstUseDays=45, days=0, preset and the exact IP:port list
✓ «5 fastest» fills the user field fastest-first, ports kept, dead skipped
✓ still no JS errors

all devcop-parity checks passed
(node:3160) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ admin/admin login still works
✓ Best Ping is appended LAST (config #1 keeps its meaning)
✓ Best Ping holds every other config as bp-N exits
✓ leastPing balancer over bp- with fallback
✓ observatory probes through the tunnel (generate_204, interval 30s)
✓ the catch-all routes to the balancer, not to a single proxy
✓ direct/block still present (iran bypass / ads rules keep working)
✓ exact ports kept inside Best Ping (2053 stays 2053, 8443 stays 8443)
✓ ?bp=0 removes Best Ping from one link
✓ one exit (VLESS+Trojan on one IP:port) never gets a Best Ping wrapper
✓ bestPing setting can be switched off, interval is clamped to ≥10
✓ setting off → no Best Ping; ?bp=1 forces it back for one link
✓ sing-box ws outbounds carry max_early_data=2560 + Sec-WebSocket-Protocol
✓ sing-box path is untouched (proxyip pins in the query keep working)
✓ custom config needs the owner login
✓ custom: exact port from the address wins (2053, not 443)
✓ custom: 2053 → TLS automatically, SNI = Host = worker
✓ custom: default path is the deployment path with ed=2560
✓ custom: Xray JSON is a full config with the same exit
✓ custom: sing-box outbound with early data + utls
✓ custom: Clash snippet present with ws-opts + servername
✓ custom: 8080 → no TLS automatically (Cloudflare plain port)
✓ custom: TLS forced on a plain port is warned about
✓ custom: IPv6 is bracketed in the link, trojan uses the trojan password
✓ custom: random BPB-style path /vl/<16-32>, no ed when switched off
✓ custom: random-case SNI still names the worker (case-insensitive)
✓ custom: fingerprint is honoured
✓ custom: the worker data plane accepts the random path (101 or a WebSocket answer)
✓ custom: a path the data plane would reject is refused up front
✓ custom: an accepted own path keeps its query and gains ed=2560
✓ custom: fragment → freedom fragment outbound + dialerProxy in Xray, tls_fragment in sing-box
✓ custom: ECH wins over fragment and the case trick (both switched off, warned)
✓ custom: SNI ≠ Host is warned about (Cloudflare 403)
✓ custom: invalid address / port / fragment are rejected with a reason
✓ custom: built for a panel user → that user’s own id in the link
✓ custom: unknown user → 404
✓ custom: an empty form still builds a working worker-domain config
✓ panel page has the 🧪 custom config card + builder button
✓ panel page has the Best Ping switch
Not implemented: Window's scrollTo() method
✓ UI: panel loads without JS errors
✓ UI: the card says what it is in plain words (title + hint translated)
✓ UI: the form sends exactly what was picked
✓ UI: link + Clash + sing-box + Xray boxes, each with a copy button
✓ UI: the «port from the address» warning is shown in words, not as a key
✓ UI: «custom path» reveals its field
✓ UI: a rejected path shows a plain error and no outputs
✓ UI: still no JS errors
all bestping-custom checks passed
✓ no KV → 503 with an honest reason
✓ non-Cat body is rejected (400)
✓ GET on the public endpoint → 405
✓ a Cat report is kept (one KV write)
✓ second report from the same IP within a minute → 429
✓ rate-limited report costs no KV write
✓ stored item keeps operator/engine/kind/status
✓ unknown fields (server, uuid) are dropped
✓ angle brackets are stripped from free text
✓ oversized report is refused
✓ owner view needs sign-in
(node:3172) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ owner view lists the report and per operator × engine stats
✓ owner can clear reports
✓ daily cap → 429 without a write
✓ panel page has the reports section + loader
✓ section text exists in Persian and English

app report: all passed
### step18.log
  type: 'test'
  ...
# Subtest: the free clean-IP library / ProxyIP feeds never reach a node sub (CI with network: 32 lines)
ok 6 - the free clean-IP library / ProxyIP feeds never reach a node sub (CI with network: 32 lines)
  ---
  duration_ms: 1.957209
  type: 'test'
  ...
# Subtest: a user pinned to Germany: their 🎯 ProxyIP and 🧦 SOCKS exits are in their sub on a node
ok 7 - a user pinned to Germany: their 🎯 ProxyIP and 🧦 SOCKS exits are in their sub on a node
  ---
  duration_ms: 1.390391
  type: 'test'
  ...
# Subtest: no exits typed → no built-in default ProxyIP lines on a node
ok 8 - no exits typed → no built-in default ProxyIP lines on a node
  ---
  duration_ms: 0.533599
  type: 'test'
  ...
# Subtest: data plane on a node: a pinned 🎯 ProxyIP carries the Cloudflare-hosted names, other sites go direct
ok 9 - data plane on a node: a pinned 🎯 ProxyIP carries the Cloudflare-hosted names, other sites go direct
  ---
  duration_ms: 1.870439
  type: 'test'
  ...
# Subtest: panel page on a node: «server IPs» view present, body marked node (Clean IP no longer blank)
ok 10 - panel page on a node: «server IPs» view present, body marked node (Clean IP no longer blank)
  ---
  duration_ms: 6.917202
  type: 'test'
  ...
# Subtest: rendered page: node CSS shows the server-IP view and Locations (Vercel still hides Locations)
ok 11 - rendered page: node CSS shows the server-IP view and Locations (Vercel still hides Locations)
  ---
  duration_ms: 11.307323
  type: 'test'
  ...
# Subtest: /api/node/ips: each typed IP comes back ✅/❌ with its test result; the page has the per-host capability list + save & test
ok 12 - /api/node/ips: each typed IP comes back ✅/❌ with its test result; the page has the per-host capability list + save & test
  ---
  duration_ms: 49.858722
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
# duration_ms 6208.852384
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
(node:3826) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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

(node:3833) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
> Task :app:parseDebugLocalResources
> Task :app:mergeDebugResources

> Task :app:processDebugMainManifest
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-249:19 Warning:
	application@android:allowBackup was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present
/home/runner/work/cat-client/cat-client/app/src/main/AndroidManifest.xml:26:5-249:19 Warning:
	application@android:dataExtractionRules was tagged at AndroidManifest.xml:26 to replace other declarations but no other declaration present

> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:javaPreCompileDebug
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityEngines.kt:3776:36 Unresolved reference 'rttBadgeاسپوف'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityEngines.kt:3777:28 Unresolved reference 'rttBadgeSpoof'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityMethodFinder.kt:222:21 Unresolved reference 'cancelServiceTest'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivityMethodFinder.kt:250:25 Unresolved reference 'cancelServiceTest'.

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

BUILD FAILED in 2m 10s
16 actionable tasks: 16 executed
