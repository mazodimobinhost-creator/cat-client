# Unit test failure report (44530b9)


## files with compile errors
     30 app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt
     28 app/src/test/java/com/whitedns/vpn/ConnectButtonModelTest.kt
     21 app/src/test/java/com/whitedns/vpn/ConnectionLocationPolicyTest.kt
     20 app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt
     16 app/src/test/java/com/whitedns/vpn/FrontingIpPolicyTest.kt
     16 app/src/test/java/com/whitedns/vpn/AppLanguageTest.kt
     15 app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt
     12 app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt
     12 app/src/test/java/com/whitedns/vpn/ConnectionTestWakeLockTest.kt
     11 app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt
     10 app/src/test/java/com/whitedns/vpn/DefaultNetworkSelectorTest.kt
      8 app/src/test/java/com/whitedns/vpn/ConnectionModeTest.kt
      6 app/src/test/java/com/whitedns/vpn/ConnectionTypeSelectionPolicyTest.kt
      6 app/src/test/java/com/whitedns/vpn/ConnectionTestingSwipePolicyTest.kt
      4 app/src/test/java/com/whitedns/vpn/TransferSpeedFormatterTest.kt
      4 app/src/test/java/com/whitedns/vpn/PrivacyPolicyAcceptancePolicyTest.kt
      4 app/src/test/java/com/whitedns/vpn/DiagnosticLoggerTest.kt
      4 app/src/test/java/com/whitedns/vpn/ConnectionSpeedCancellationTest.kt
      2 app/src/test/java/com/whitedns/vpn/TvUiPolicyTest.kt
      2 app/src/test/java/com/whitedns/vpn/PanelUpdatePolicyTest.kt
## log tail
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:22:28 Unresolved reference 'ConnectionLocationPolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:22:71 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:28:23 Unresolved reference 'MihomoConfigParser'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:30:20 Unresolved reference 'MihomoSelectionPolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:31:28 Unresolved reference 'MihomoSelectionPolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:42:23 Unresolved reference 'MihomoConfigParser'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:44:26 Unresolved reference 'MihomoSelectionPolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:47:13 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:47:13 Not enough information to infer type argument for 'T'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:48:17 Unresolved reference 'MihomoGroupSelection'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:49:17 Unresolved reference 'MihomoGroupSelection'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:50:17 Unresolved reference 'MihomoGroupSelection'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoConfigParserTest.kt:54:53 Unresolved reference 'MihomoSelectionPolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:11:25 Unresolved reference 'MihomoCoreLifecycle'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:14:22 Unresolved reference 'MihomoCoreCleanupRequest'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:15:22 Unresolved reference 'MihomoCoreState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:16:22 Unresolved reference 'MihomoCoreSetupCompletion'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:17:22 Unresolved reference 'MihomoCoreState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:23:25 Unresolved reference 'MihomoCoreLifecycle'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:26:22 Unresolved reference 'MihomoCoreSetupCompletion'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:27:22 Unresolved reference 'MihomoCoreCleanupRequest'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:28:22 Unresolved reference 'MihomoCoreCleanupRequest'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:32:22 Unresolved reference 'MihomoCoreState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:38:25 Unresolved reference 'MihomoCoreLifecycle'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:41:22 Unresolved reference 'MihomoCoreSetupCompletion'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:48:25 Unresolved reference 'MihomoCoreLifecycle'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:50:22 Unresolved reference 'MihomoCoreSetupCompletion'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoCoreLifecycleTest.kt:52:22 Unresolved reference 'MihomoCoreState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:13:29 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:18:27 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:19:29 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:20:20 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:21:20 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:26:28 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:27:28 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:28:28 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:29:26 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:33:26 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/MihomoRuntimeHealthDeadlinePolicyTest.kt:36:29 Unresolved reference 'MihomoRuntimeHealthDeadlinePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PanelUpdatePolicyTest.kt:26:21 Argument type mismatch: actual type is 'Boolean', but 'String!' was expected.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PanelUpdatePolicyTest.kt:26:66 Argument type mismatch: actual type is 'String', but 'Boolean' was expected.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PrivacyPolicyAcceptancePolicyTest.kt:10:21 Unresolved reference 'PrivacyPolicyAcceptancePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PrivacyPolicyAcceptancePolicyTest.kt:16:13 Unresolved reference 'PrivacyPolicyAcceptancePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PrivacyPolicyAcceptancePolicyTest.kt:17:17 Unresolved reference 'PrivacyPolicyAcceptancePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/PrivacyPolicyAcceptancePolicyTest.kt:25:13 Unresolved reference 'PrivacyPolicyAcceptancePolicy'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:9:28 Unresolved reference 'QuickSettingsTileStateMapper'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:9:73 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:11:22 Unresolved reference 'QuickSettingsTileVisualState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:12:22 Unresolved reference 'R'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:18:13 Unresolved reference 'QuickSettingsTileVisualState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:19:13 Unresolved reference 'QuickSettingsTileStateMapper'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:19:58 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:22:13 Unresolved reference 'QuickSettingsTileVisualState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:23:13 Unresolved reference 'QuickSettingsTileStateMapper'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:23:58 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:30:13 Unresolved reference 'QuickSettingsTileVisualState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:31:13 Unresolved reference 'QuickSettingsTileStateMapper'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:31:58 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:34:13 Unresolved reference 'QuickSettingsTileVisualState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:35:13 Unresolved reference 'QuickSettingsTileStateMapper'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:35:58 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:41:28 Unresolved reference 'QuickSettingsTileStateMapper'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:41:73 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:43:22 Unresolved reference 'QuickSettingsTileVisualState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/QuickSettingsTileStateMapperTest.kt:44:22 Unresolved reference 'R'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/RuntimeSecurityPolicyTest.kt:10:22 Unresolved reference 'MihomoControllerSecret'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:9:22 Unresolved reference 'ServiceStopAction'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:9:56 Unresolved reference 'ServiceStopDecision'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:9:85 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:14:22 Unresolved reference 'ServiceStopAction'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:14:49 Unresolved reference 'ServiceStopDecision'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:14:78 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:19:22 Unresolved reference 'ServiceStopAction'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:19:48 Unresolved reference 'ServiceStopDecision'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:19:77 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:24:22 Unresolved reference 'ServiceStopAction'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:24:48 Unresolved reference 'ServiceStopDecision'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/ServiceStopDecisionTest.kt:24:77 Unresolved reference 'VpnState'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/TransferSpeedFormatterTest.kt:9:31 Unresolved reference 'formatTransferSpeed'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/TransferSpeedFormatterTest.kt:14:33 Unresolved reference 'formatTransferSpeed'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/TransferSpeedFormatterTest.kt:19:32 Unresolved reference 'formatTransferSpeed'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/TransferSpeedFormatterTest.kt:24:34 Unresolved reference 'formatTransferSpeed'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/TvUiPolicyTest.kt:10:32 Unresolved reference 'televisionSafeInsets'.
e: file:///home/runner/work/cat-client/cat-client/app/src/test/java/com/whitedns/vpn/TvUiPolicyTest.kt:11:30 Unresolved reference 'televisionSafeInsets'.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:compileDebugUnitTestKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 4s
22 actionable tasks: 4 executed, 18 up-to-date
FAIL cat-panel
```
✓ warp on => single wireguard outbound w/ reserved array
✓ warp chain => hub + inner dialerProxy
✓ xray config dials through warp (sockopt dialerProxy)
✓ chain mode: warp-hub present + inner dialerProxy
✓ subQuery parses noext
✓ extSubs normalize: https-only, caps, name default
✓ parseExtUris: b64, scheme filter, cap 100
✓ /ext route gated without key
✓ /ext unknown index => 404
✓ sub still works with extSubs + noext
✓ sub without noext survives dead ext sub (allSettled)
✓ /ext/<n>/<uuid> fetches (or clean-fails offline)
✗ toAddrs with empty pool adds nothing — {"ok":true,"added":64,"count":67,"persisted":true}
✓ toAddrs imports healthy pool with country tags
✓ imported pool IPs stored + tagged
✓ toAddrs dedupes (second run adds 0)
✗ ips list grew by exactly 2
✓ pool IP gets a config in the sub
✓ subRotate defaults to fetch (fresh set every update)
✓ rotate=fetch: sub changes across refreshes
✓ rotate=daily: deterministic within the day
✓ rotate=off: stable order
✓ rotation keeps port walk: first config is TLS :443
✓ repo refresh merges both feeds, uniq + junk-filtered
✓ /api/repos GET status
✓ json-speed sort within feed: fastest first
✓ dead<3 still in pool
✓ dead≥3 dropped from pool
✓ repoAuto on → library IP (pinned 443) in sub
✓ ?norepo=1 excludes library IPs
✓ repoAuto off → no library IPs
✓ repo import by country adds to panel list
✓ sanitizeRepos: https-only, defaults when empty
✓ proxy repo refresh: csv+txt merged, uniq
✓ /api/prepos GET status
✓ csv-proxy speed sort: fastest first
✓ proxy domains kept
✓ proxy dead<3 kept
✓ proxy dead≥3 dropped
✓ proxy import by country
✓ proxyRepoAuto on → appended ProxyIP in sub ?proxyip=
✓ ?norepo=1 excludes repo ProxyIPs
✓ user own ProxyIP still present
✓ proxyRepoAuto off → no repo ProxyIPs
✓ sanitizeProxyRepos: kinds + https-only
✓ proxyRepoAuto defaults ON
✓ repo default country kept (sanitized)
✓ txt feed gets repo default country tag
✓ per-ProxyIP configs: numbered + flag + Persian country
✓ PX config carries ?proxyip= relay path
✓ en locale → English country label
✓ pinnedPortOf parses v4/v6/domain, rejects bare
✓ /api/ips stores ip:port + cc, rejects junk
✓ normalize keeps custom ports
✓ default SNI is NOT the panel host
✓ SNI: settings beat default
✓ SNI: env beats default
✓ generated configs do not put panel host into sni param

2 FAILED
```
PASS panel-dom
PASS wizard
PASS multipart-upload
FAIL cat-panel
```
✓ rotate=daily: deterministic within the day
✓ rotate=off: stable order
✓ rotation keeps port walk: first config is TLS :443
✓ repo refresh merges both feeds, uniq + junk-filtered
✓ /api/repos GET status
✓ json-speed sort within feed: fastest first
✓ dead<3 still in pool
✓ dead≥3 dropped from pool
✓ repoAuto on → library IP (pinned 443) in sub
✓ ?norepo=1 excludes library IPs
✓ repoAuto off → no library IPs
✓ repo import by country adds to panel list
✓ sanitizeRepos: https-only, defaults when empty
✓ proxy repo refresh: csv+txt merged, uniq
✓ /api/prepos GET status
✓ csv-proxy speed sort: fastest first
✓ csv port column preserved (real port wins over 443)
✓ proxy domains kept (port-pinned)
✓ proxy dead<3 kept
✓ proxy dead≥3 dropped
✓ proxy import by country
✓ proxyRepoAuto on → appended port-pinned ProxyIP in sub
✓ pxOverride double-decode → host:port
✓ ?norepo=1 excludes repo ProxyIPs
✓ user own ProxyIP still present
✓ proxyRepoAuto off → no repo ProxyIPs
✓ sanitizeProxyRepos: kinds + https-only
✓ csv PORT column survives: real port pinned
✓ missing csv port → default pin 443
✓ proxyRepoAuto defaults ON
✓ repo default country kept (sanitized)
✓ txt feed gets repo default country tag
✓ per-ProxyIP configs: numbered + flag + Persian country
✓ PX config carries ?proxyip= relay path
✓ en locale → English country label
✓ pinnedPortOf parses v4/v6/domain, rejects bare
✓ /api/ips stores ip:port + cc, rejects junk
✓ normalize keeps custom ports
✓ default SNI is NOT the panel host
✓ SNI: settings beat default
✓ SNI: env beats default
✓ hero «in use» card on dashboard
✓ panel version is 6.27.0
✓ blockQuic in clash yaml
✓ blockQuic in singbox
✓ blockQuic in xray
✓ ip provenance (src+ping) persisted
✓ ips import accepted provenance payload
✓ provenance badge stored (scanner + 341ms)
✓ ip-test 401 without session
✓ ip-test empty list → empty results
✓ worker-side test button on IP list
✓ domain → raw CF IP in sub (DNS-free)
✓ ?dom=1 / domToIp=false keeps domains
✓ poisoned cache entry ignored (must be CF-range)
✓ sub query tolerates &amp; links (Telegram copy)
✓ ext sub fetch sanitizes &amp;
✓ generated configs do not put panel host into sni param

1 FAILED
```
PASS panel-dom
PASS wizard
PASS multipart-upload
