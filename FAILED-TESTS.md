# Android failure report (e2724f8)


## files with compile errors
     65 app/src/main/java/com/cat/client/MainActivity.kt
      4 app/src/main/java/com/cat/client/CatClientVpnService.kt
      1 app/src/main/java/com/cat/client/AetherOperations.kt
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
(node:3544) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=aq-unit is not specified and it doesn't parse as CommonJS.
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
(node:3559) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=pxfeeds is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ country import persists addresses and country metadata
✓ DEFAULT_PROXY_REPOS carries the daily source
✓ default repo list still fits the cap (≤10)
PROXYIP FEED TESTS PASSED
✓ survival apply → ok + changed list
✓ survival returns the survive link
(node:3578) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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

(node:3590) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
> Task :app:javaPreCompileDebug
> Task :app:processDebugManifestForPackage
> Task :app:processDebugResources

> Task :app:compileDebugKotlin
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/AetherOperations.kt:507:61 Argument type mismatch: actual type is 'Int?', but 'Int' was expected.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnService.kt:1370:37 Unresolved reference 'probeEndpointLatencyMs'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnService.kt:1374:38 Unresolved reference 'probeBestProxyRttMs'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnService.kt:1379:45 Unresolved reference 'protocol'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/CatClientVpnService.kt:1381:39 Unresolved reference 'resolverHost'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12691:42 Unresolved reference 'VALID_TOR_BRIDGES'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12710:44 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12710:44 Not enough information to infer type argument for 'V'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12883:42 Unresolved reference 'exitLoc'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12883:50 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12884:88 Unresolved reference 'exitIp'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12884:95 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12888:42 Unresolved reference 'exitLoc'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12888:50 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12889:83 Unresolved reference 'exitIp'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12889:90 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12893:50 Unresolved reference 'outerEndpoint'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12894:54 Unresolved reference 'innerEndpoint'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12894:68 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12895:55 Unresolved reference 'exitIp'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12895:62 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12896:49 Unresolved reference 'exitLoc'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12896:57 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12897:62 Unresolved reference 'exitColo'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12897:71 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12900:48 Unresolved reference 'outerEndpoint'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12901:52 Unresolved reference 'innerEndpoint'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12901:66 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12902:54 Unresolved reference 'exitIp'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12902:61 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12903:51 Unresolved reference 'exitLoc'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12903:59 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12904:54 Unresolved reference 'exitColo'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12904:63 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12979:50 Unresolved reference 'fingerprint'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12979:62 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12980:47 Unresolved reference 'fragmentEnabled'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12981:51 Unresolved reference 'fragmentSize'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12981:64 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12982:52 Unresolved reference 'fragmentDelay'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12982:66 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12983:48 Unresolved reference 'masqueSni'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12983:58 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12984:42 Unresolved reference 'echEnabled'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12985:45 Unresolved reference 'echDns'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12985:52 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12986:48 Unresolved reference 'echDomain'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12986:58 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12988:52 Unresolved reference 'psiphonCdnIps'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12989:52 Unresolved reference 'psiphonCdnSni'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12990:53 Unresolved reference 'psiphonBundled'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12991:53 Unresolved reference 'torBridgesMode'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12991:68 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12992:55 Unresolved reference 'torCustomBridges'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12993:52 Unresolved reference 'torRelaysMode'.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:12993:66 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13022:21 No parameter with name 'fingerprint' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13023:21 No parameter with name 'fragmentEnabled' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13024:21 No parameter with name 'fragmentSize' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13025:21 No parameter with name 'fragmentDelay' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13026:21 No parameter with name 'masqueSni' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13027:21 No parameter with name 'echEnabled' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13028:21 No parameter with name 'echDns' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13029:21 No parameter with name 'echDomain' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13031:21 No parameter with name 'psiphonCdnIps' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13032:21 No parameter with name 'psiphonCdnSni' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13033:21 No parameter with name 'psiphonBundled' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13034:21 No parameter with name 'torBridgesMode' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13035:21 No parameter with name 'torCustomBridges' found.
e: file:///home/runner/work/cat-client/cat-client/app/src/main/java/com/cat/client/MainActivity.kt:13036:21 No parameter with name 'torRelaysMode' found.

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

BUILD FAILED in 1m 19s
16 actionable tasks: 16 executed
