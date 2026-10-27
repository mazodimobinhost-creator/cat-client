# Android failure report (eb8cf18)


## files with compile errors
     51 app/src/main/java/com/cat/client/VercelRelayController.kt
      1 app/src/main/java/com/cat/client/VercelRelay.kt
      1 app/src/main/java/com/cat/client/VercelPanel.kt
## log tail
### step18.log
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
(node:3326) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=aq-unit is not specified and it doesn't parse as CommonJS.
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
(node:3341) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=pxfeeds is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ country import persists addresses and country metadata
✓ DEFAULT_PROXY_REPOS carries the daily source
✓ default repo list still fits the cap (≤10)
PROXYIP FEED TESTS PASSED
✓ survival apply → ok + changed list
✓ survival returns the survive link
(node:3360) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
✓ vercel sub answers 200 with links
✓ every vercel line is :443
✓ every vercel line is TLS
✓ every vercel address is a Vercel edge address or the project host
✓ SNI == Host == project domain on every vercel line
✓ no ECH / cipher-suite pinning on vercel lines
✓ vercel sub carries the project host itself
✓ vercel sub carries both VLESS and Trojan
✓ ?port= cannot re-add non-443 ports on vercel
✓ cloudflare behaviour unchanged: non-Vercel addresses still present
✓ cloudflare behaviour unchanged: alternate ports still present
all vercel-platform checks passed
(node:3372) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)

SNI Lab — live SNI testing (panel 6.67.4)

(node:3387) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:821:74 Function 'component1()' is ambiguous for this expression: 
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:821:74 Function 'component2()' is ambiguous for this expression: 
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:826:45 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:826:45 Not enough information to infer type argument for 'V'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:827:28 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:827:28 Not enough information to infer type argument for 'B'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:827:31 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:828:27 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:828:27 Not enough information to infer type argument for 'B'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:828:30 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:829:29 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:829:29 Not enough information to infer type argument for 'B'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:871:27 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:872:45 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:872:95 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:873:36 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:873:74 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:873:74 Not enough information to infer type argument for 'R'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:873:78 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:874:21 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:874:21 Not enough information to infer type argument for 'B'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:874:24 Unresolved reference 'VercelPanelProbe'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:876:17 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:876:17 Not enough information to infer type argument for 'T'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:876:27 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:876:27 Function 'component1()' is ambiguous for this expression: 
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:876:27 Function 'component2()' is ambiguous for this expression: 
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
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:878:24 Unresolved reference 'ok'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:878:73 Unresolved reference 'millis'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:878:120 Unresolved reference 'detail'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:883:25 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:884:37 Unresolved reference 'size'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:884:87 Unresolved reference 'size'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:887:25 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:887:94 Unresolved reference 'it'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:888:34 Unresolved reference 'size'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:888:126 Unresolved reference 'size'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:891:21 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:891:21 Not enough information to infer type argument for 'T'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:891:26 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:891:31 Unresolved reference 'second'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:897:104 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:907:24 Unresolved reference 'VercelPanelPlanner'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/VercelRelayController.kt:999:100 Unresolved reference 'VercelPanelPlanner'.

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

BUILD FAILED in 1m 37s
16 actionable tasks: 16 executed
