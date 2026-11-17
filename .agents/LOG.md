# Shared Agent Log

- **2026-11-07 (Fox protocols + all countries — `v1.10.0-beta259`):** Cat Fox VPN: HTTP/1.1 as a real choice, HTTP/2 default, HTTP/3 listed (no QUIC stack → HTTP/2). Every Mozilla country is offered (no 🔒 fallback). Live list keeps extra Fastly nodes per country.
- **2026-11-07 (Iran free methods in 🎁 — `v1.10.0-beta258`):** Hub adds one-tap Warp→Psiphon (no account), SSTP/VPN Gate, Outline/SS. No fake paid VPN. Engines already existed; they were not discoverable in 🎁.
- **2026-11-07 (Cat GP/Amnezia/finder UX — `v1.10.0-beta257`):** Cat GP dialog in plain language; Amnezia says where the key comes from (no free cloud); broken WG keys dropped so the method finder does not die on `proxy 37: decode peer public key`.
- **2026-11-07 (Cat GP hop shared-key — `v1.10.0-beta256` / `6.84.0`):** `/gp/<uuid>/<key>` + POST `q.k`; first key stored in KV so a leaked UUID cannot fetch; GAS forwards `k`; gzip max 8 layers; Cat GP in 🎁 free networks. Independent of Aras-GP `panel/`.
- **2026-10-25 (Skill Setup Phase 1 & 2):** Installed all 19 specialist skills (`01` through `19`) from Google Drive folder `1Ib_oKMGA8a9M2Z5rg8c6-O6HyE8ml5rQ` into `.agents/skills/` and created `.agents/STATUS.md`.
- **2026-10-25 (Crash Hunter & Android Service Fixes — `v1.10.0-beta145` / `6.69.26`):**
  - `CatClientApplication.kt`, `PsiphonCarrierService.kt`, `PsiphonCarrierClient.kt`, `PsiphonCarrierConfig.kt`, `CatClientVpnService.kt`, `MainActivity.kt`: Fixed `:psiphon` secondary process isolation, Messenger IPC start, `DisableTactics: true` + 11 TCP protocols for chained Psiphon, and `BadTokenException` dialog guards.
- **2026-10-25 (Dedicated SOCKS5 Hub & 3 Smart Tools — `v1.10.0-beta146` / `6.69.27`):**
  - `ProxyScannerPage.kt`, `WhiteDnsSmartScanner.kt`, `MainActivity.kt`, `catclient.worker.js`: Added Dedicated SOCKS5 Live Hub (7 live feeds + 4-mirror GitHub API fallback + 62 built-in seeds + 1-tap direct & pool connect), Regional Firewall Fingerprinter, Auto-MTU & Carrier Record Finder, and Hybrid Iran IP + Auto Sanction-Bypass DNS.
- **2026-10-25 (Complete 6 Remaining Items — `v1.10.0-beta147` / `6.69.28`):**
  - Native in-app parsing & local SOCKS5 bridge execution for `cottendns://`, `stormdns://`, `masterdns://`, `soar://`, and `dnstt://` (`WhiteDnsSmartScanner.kt`, `SubConvConverter.kt`, `MihomoShareLink.kt`, `MainActivity.kt`).
  - Irancell `F&F` (`finalMask=tlshello-0-len`, `cipherSuites=semi-python`) preservation in `SubConvConverter.kt`, `UserSubscriptions.kt`, `MihomoShareLink.kt` and TLS ClientHello record splitter (`splitTlsClientHelloRecord`).
  - Server-side `RFC 1928` SOCKS5 handshake testing (`kind: 'socks'`) in `/api/ip-test`, `data-sk-test`, and `#skLiveFetch` in `catclient.worker.js`.
  - Aether Smart Conditional Chain (`auto-psiphon`) and Top-10 Per-Carrier Clean Endpoint Pool with automatic failover (`AetherPreferenceStore.kt`, `CatClientVpnService.kt`, `MainActivity.kt`).
  - Fallback Worker Domain (`fallbackHost` in `catclient.worker.js` and `SubscriptionSourceLoader.candidateHttpsUrls` in `SubscriptionSnapshot.kt`).
- **2026-10-25 (6 Dr.Net-Inspired Capabilities — `v1.10.0-beta148` / `6.69.28`):**
  - Mid-Stream Freeze & Throttling Detector (`evaluateMidStreamTransfer`, `probeMidStreamFreezeAndThroughput`, `runMidStreamFreezeDetectorDialog`).
  - Domain Fronting & Unpoisoned Real IP Finder (`isPoisonedIranDnsIp`, `resolveUnpoisonedRealIp`, `showDomainFrontingAndUnpoisonResolverDialog`).
  - Google Apps Script (`GAS`) Relay (`gasrelay://` URI scheme, `buildGoogleAppsScriptRelaySource`, `buildGasRelayUri`, daily 20k quota tracker in `VpnRuntimeStateStore`, and `showGoogleAppsScriptRelayDialog`).
  - Cat Client Lite Worker Generator (`buildLiteWorkerScript` with zero-KV quota, Irancell F&F, MCI IPv6, and explicit-port ProxyIP).
  - Independent Auto-Reconnect on Drop, Auto-Connect on Boot/Update (both default off), and Universal Kill Switch (`VpnRuntimeStateStore`, `showConnectionResilienceAndKillSwitchDialog`).
  - Step-by-Step Live Connection Progress (`VpnRuntimeStateStore.saveLiveConnectStep` in `CatClientVpnService.kt`).
- **2026-10-26 (Real engines + Toolbox + panel limits/2FA — `v1.10.0-beta174` / `6.69.37`):**
  - DNS tunnels: fake probe-then-direct bridge replaced by the real SlipNet CLI engine (`SlipNetCliEngine.kt`, built from source in CI, AGPL-3.0, `NOTICE`); unsupported tunnel types now fail loudly (toast + `whitedns.engine.unavailable`).
  - Paqet / Fenix: silent direct-connection fallbacks removed (honest failure). WARP-AWG `auto`: real WARP registration (`WarpRegistration.kt`, X25519) instead of a fake key; s1/s2 no longer injected into vanilla WG / Proton `.conf`.
  - Deep links for every config scheme, `ACTION_SEND`, "open with" files (`ConfigFileImport.kt`, manifest); SAF file import, backup/restore, app lock (PIN + device credential, `AppLock.kt`), 6-hourly background subscription refresh + expiry/data-cap notifications (`SubscriptionAutoRefresh.kt`).
  - WhiteDNS-style toolkit (`WhiteDnsToolkit.kt` + `AppToolsController.kt`, hub button 18): ASN export (RIPEstat), CIDR→IP, SNI scanner, 3-wave HTTP/SOCKS5 open-proxy scan, speed & loss rank, DNS resolver/tunnel-readiness scan, Config Maker (exact-port rewrite / endpoint extract).
  - Panel 6.69.37: per-user traffic quota with a KV-safe meter (only quota users, ≤1 write/10 min/user), simultaneous-IP limit, real `subscription-userinfo`, salted password hash, per-IP login lockout, sign-in audit log, TOTP 2FA, trojan auth map keyed on user state. New suite `scripts/panels/panel-security.test.mjs`; paste budget raised with justification.
- **2026-10-26 (ZedSecure 3.1.5–3.1.7 review — `v1.10.0-beta175`):**
  - Connection failures now keep their reason on screen: `ConnectionFaultExplainer.kt` classifies the full cause chain (port busy, config, certificate, Reality, root, permission, network, DNS, auth, refused, health check, timeout) into Persian/English title + "what to do"; auto dialog after a failed connect, tap the status text to reopen; "Try again" / "Copy details".
  - AmneziaWG: H1–H4 were parsed as Int and silently dropped when > Int.MAX or a 2.0 range (tunnel "connected" but carried no data) — now `AmneziaHeaders.kt` keeps uint32/ranges; s3/s4/i3–i5 forwarded from `.conf` imports too.
  - `vmess://<base64>#Name` links (name after `#`) imported correctly instead of being dropped.
- **2026-10-29 (beta220–222):** real internet card + auto next route + opt-in panel report (beta220, Panel 6.78.0); F&F check — mihomo silently drops `final-mask`/`cipher-suites`, so `finalMask=tlshello…` proxies are now dialled through the ByeByeDPI fragmenting proxy automatically (beta221, `Beta220FinalMaskTest`); MainActivity split phase 2 (12 364 → ≈3 660 lines, `scripts/dev/split-main-activity-2.py`); CatClientVpnService split per engine/feature (6 468 → ≈4 270 lines, `scripts/dev/split-vpn-service.py`, beta222).

- **2026-10-29 (beta223):** built-in Xray engine — F&F/finalMask proxies swapped to local SOCKS inbounds served by the official Xray-core (built from pinned source in CI), sockets protected over a unix socket (SCM_RIGHTS), runner exits on stdin close, fallback to the beta221 ByeByeDPI path. Verified locally: real Xray v26.10.21 accepts the generated config (`-test`), live run protected the socket and split the ClientHello into 11 TLS records. `Beta223XrayEngineTest`.
- 2026-10-29 beta224 (291): SmarterASP node panel (IIS), Fox port policy + target failures, CrashWatch exit reasons. Local: Beta224HostFoxCrashTest + NodeHostTest pass (kotlinc 2.4.21 harness), node-host.test.mjs all pass (incl. IIS spawn).
- 2026-10-29 beta225 (292, Panel 6.79.0): free-host audit — CAT_HOST per host, KataBump direct-port header trust fix, 🟧 Cloudflare front for HTTPS Node hosts, per-platform hiding of CF-only panel sections, per-host renew reminders + SmarterASP trial reminder, IIS keep-warm. Local: npm test 134/134, node-host 69 checks, artifact suites green, NodeHostPanel helpers compiled+run with kotlinc 2.4.21.
- 2026-10-29 beta226: chain fixes (ws key, VLESS addons, no response wait), katabump-cf-link real-socket test, Railway region picker (multiRegionConfig), Bot-Hosting node country, host country texts. Panel 6.79.1.
- 2026-10-30 beta227: HostRegions + askCountry before every host install, Railway change-region button, flags in saved names.
- 2026-10-30 beta228: SmarterASP Linux trial guide + smarterasp-linux.test.mjs, USA default, cat-config leak check, wsClientLayer close-frame data fix (Panel 6.79.2).
- 2026-10-30 beta229: SmarterASP guide from real panel screenshots (Server Location list, form fields, FTP, bots rule).
- 2026-10-30 beta230: SmarterASP Linux server.js entry + /health guide from NodeJs page screenshot.
- 2026-10-30 beta231: SmarterASP Linux runner-load fix (autostarted) + cat-bundled first-start panel.
- 2026-10-30 beta232: SmarterASP Linux read-only site files — prebuilt cat-code in zip, temp code/data fallback.
- 2026-10-30 beta233 (Panel 6.79.3): node hosts — auto server IPs in configs, Clean IP page for nodes, exits on nodes (SOCKS all sites, ProxyIP CF names by DNS).  CI fix: feed clean IPs (repoIps «IP:443») leaked into node subs; node lists only pinned user exits.
- 2026-10-30 beta234 (Panel 6.79.4): node typed IPs verified by the panel (WS 101, SNI=host) before entering subs; per-host capability list + save & test on the node Clean IP page; 14 lost regex escapes in the page template fixed. Botkeep not added (its fair-use rules forbid proxies/VPN endpoints).

## 2026-10-30 — beta235 / Panel 6.80.0 (four owner requests)
- Worker: `/api/ip-repo` (+ page picker with browser test, ≥40 ms rule, IPv6 pre-check), `/api/ech-pool` + `echPool` round-robin (udp:// and IP-cert DoH only), `resetDays` + `trafficOf` + `cronUsageReset`, `/api/cf-quota` + `cronQuotaWarn`, `/api/node/reality` + `realityLines` (raw/b64 subs only, unmetered users only, only when verified).
- Adapter: Reality module (Xray v26.10.21 download + SHA2-256 check, X25519, per-operator inbounds on 127.0.0.1, SNI mux on the public port, verify through the public port, restart with backoff, state in `<CAT_KV_DIR>/reality/reality.json`), active only with `CAT_HOST_DIRECT=1`.
- Tests: `panel-680.test.mjs`, `node-reality.test.mjs` (fake Xray via `CAT_XRAY_BIN`, openssl test CA via `NODE_EXTRA_CA_CERTS`), panel-dom additions. Budgets raised (raw 930 000 / guard 940 000 / gzip 278 000). The cat-panel.html one-liner is now at android.yml line 331.


## 2026-10-30 — beta237 (Cat Fox VPN vs Firefox IP Protection)
- Compared FirefoxVpnEngine with Firefox `toolkit/components/ipprotection` (GuardianClient, GuardianTypes ProxyPass, IPPChannelFilter, server list; MPL-2.0, rules only). New `FenixPassPolicy.kt` (pass states, 5-min rotation, Guardian 401/403 steps, split-retry rule, `FoxNetworkReset`), `FoxHttpProxyRequest` in `FoxPortPolicy.kt`.
- Engine: single-flight `renewPassOnce` + `startPassRenewer`, expired pass renewed in `startLocalBridge`, `openNodeTls` unsplit retry (`unsplitTlsNodes`), API requests unsplit protected retry + host-name check, `experiment=alpha`, UA Firefox 152, HTTP-proxy forwarding. Service: `networkWatch.onNetwork` → `resetUpstreamSessions("network changed")`.
- Local: pure policies checked with a kotlinc 2.4.21 harness; engine parse-checked (only Android/org.json unresolved). `Beta237FoxPassTest` added.
- 2026-11-05 (beta238, `9fe0fc2`, CI green, 20 assets): Fox login via WebChannel `account_updates` (`FoxWebChannel.kt`; verified sessions only; in-page Hawk OAuth exchange avoids Fastly 406; no blind activate). `GitHostProfiles.kt` (Zerops `zerops.yml`, Seenode port 8080, Hostim Dockerfile; noDisk); Slipway skipped (self-hosted). `WhiteDnsToolkit.scanEncryptedResolvers` + Tools item (DoH/DoT, `endpointIdentificationAlgorithm=HTTPS`, poisoned-answer check). `docs/COLLABORATION.md`, `.agents/HANDOFF.md`, `scripts/review-worktree.sh` (detached worktree, shallow-safe). mlmvpn ideas skipped: per-app split + the same engines already exist; GPL-3.
- **2026-11-05 (Fox 406 fix, Telegram finder, Hysteria, provider headers — `v1.10.0-beta239` / 306, Panel 6.80.0):**
  - `FoxFastlyChallenge.kt` (new, pure: challenge detection, `init([…])` parser, trusted origins, `FoxCookieJar`), `FirefoxVpnEngine.kt` (solve on the challenged host with the request UA, per-host `controlCookies`, Firefox 159, `mozillaVpnUserAgent()`, fail-fast retries ≤5, clearer 406 errors), `FoxWebChannel.kt` (UA 159).
  - `TelegramProxyFinder.kt` (new) + Tools entry in `AppToolsController.kt`: public MTProxy list via jsDelivr/raw mirrors or pasted links, real FakeTLS HMAC handshake on protected sockets, open fastest in Telegram.
  - `SubConvConverter.kt` / `MihomoShareLink.kt` / `UserSubscriptions.kt` / `FreeConfigs.kt`: `hysteria://` v1, hy2 `pinSHA256` → `fingerprint`, `mport`/authority port list → `ports`, ALPN; round trip.
  - `SubscriptionProviderInfo.kt` (new) + `SubscriptionSnapshot.kt` / `UserSubscriptions.kt` / `MainActivitySubscriptions.kt` / `SubscriptionAutoRefresh.kt` / strings: `profile-title`, `profile-update-interval`, `announce`, `support-url`, `profile-web-page-url`.
  - Tests: `Beta239FoxFastlyTest`, `Beta239ProtocolsTelegramTest`. CI green at `a7439a5`.
- **2026-11-05 (Worker really works + settings parity — `v1.10.0-beta240` / Panel `6.81.0`):**
  - `catclient.worker.js`: Blob-safe `websocketReadable` + `binaryType='arraybuffer'` before `accept()`; first-reply guard with `NO_DIRECT` host:port cache and carried pending read; `selfUpdatePanelWorker` adds/forces `DATA_PLANE_ENABLED`; `/api/data-plane` + `#dpLine` banner; `/api/ip-test` `skip:'edge'` + `viewer`; `/api/ip-repo` `viewer`; client browser re-test + `vpnNote`/`PLAIN_P`.
  - DATA_PLANE in `wrangler.jsonc`, `WorkerDeploymentPipeline.kt`, `CloudflareWorker.kt`, `catclient.wizard.js`, `deploy-bot/worker.js`.
  - Settings: `CoreOptionsSettings.kt` (new), `MihomoRuntime.kt` (`sniffing`/`fakeDns` params, `SNIFFER_BLOCK`, prefs), `CatClientVpnService.kt` (`setHttpProxy`), `ConnectionTestSettings.kt` (`delayTestUrl`), strings en/fa.
  - Tests: `worker-realtraffic.test.mjs` (new, in `npm test`), `panel-selfupdate` block 11, `Beta240CoreOptionsTest`.
- **2026-11-05 (beta241 / Panel 6.81.0):** Cat Warp network planner (`AetherNetworkPlanner.kt`, `CatClientVpnServiceCatWarp.kt`, `AetherSession.kt`, `AetherPreferenceStore.kt`) — configs built on one line now adapt on other lines; remember-after-success only. Flaky `/api/ech-pool` test fixed. `docs/REVIEW-BRIEF.md` (full-project review for Claude/Grok). CI green at `88748b0`.
- **2026-11-05 (beta242 / Panel 6.81.1):** review round 1 — chain bug 4 fixed (panel link «all» fails closed, `/api/links` exitDown); Cat Warp alternate keeps two-hop exit; shared underlying-network picker; wizard `confirmed`. CI green at `376a1fc`. Drive code bundle for reviewers.
- **2026-11-05 (beta243 / Panel 6.81.2):** review round 2 — Grok: Pages OPEN_PANEL/OPEN_SUB false, chain non-Strict fallback now visible (`exitDown` toast), portal vs pages docs. Claude: wizard remembers only confirmed peers, shared `AetherFallbackStages` (session + carrier, full search budget), WiW alternate = WoM, learned-mode window fixed, failover watchdog, behaviour tests. CI green at `f840249`; Drive bundle A/B/C01–12/D.

## 2026-11-05 — beta244 / Panel 6.81.3
- Reviewed BPB VPN (GPL-3, Flutter), M-F-PANEL (no licence), YouTube NA9lokdUnxc (AWS fake identity — refused), Qa2spLnZpu4 (M-F on KataBump).
- App: `AntiSanctionRouting.kt` (pure) + `AntiSanctionSettings.kt` (row, dialog, `AntiSanctionProbe`); `MihomoRuntime` takes `antiSanction` (rules into the one `rules:` list, `nameserver-policy` in `dns:`).
- Panel: SS-2022 inbound in the Reality Xray, mux non-HTTP → SS, `ssVerify` via a short-lived Xray client through the public port; worker `realityLines` adds `ss://` only when verified.
- Local evidence: npm test 159/159 files; node-reality 24/24 (fake Xray); kotlinc harness Beta244AntiSanctionRoutingTest 6/6. Runtime YAML test + UI compile only in CI (no android.jar here). Real Xray could not run here (release assets blocked; Go deps not reachable) — config fields checked against the v26.10.21 source.
- CI round 1 (`5ae596d`): Android JUnit caught that the runtime's forced-tunnel AI list (`SERVICE_DOMAINS`, injected first) shadowed the DIRECT rules of ticked services → `AntiSanctionRouting.covers` filters only ticked services out. ui-e2e failed outside our code (npm `playwright@latest` 1.65.0 without `playwright-core` 1.65.0) → pinned 1.64.1.
- CI round 2 (`0811854`): all green (Android build + JUnit, Panel tests, UI e2e, Railway, portal). Release `v1.10.0-beta244` prerelease, 20 assets.
- Open: phone test of the anti-sanction dialog (test while disconnected → save → connect → ChatGPT/Gemini direct); KataBump test of SS-2022 with real Xray (sandbox could not run Xray).

## 2026-11-05 — beta245 / Panel 6.81.4
- Owner's 5 items (KataBump «🌍 exit location» screenshot): multi-country exits, Vercel user persistence, per-operator scanner IPs (panel + app), ➕ library → panel, green dots for working panels.
- Panel: `userExitCcs` (≤6 countries, ≤12 exits, `exitCc` = first); `/api/locations/to-panel` (healthy only, exact address, `PXIPS_KEY` cap 32); `/api/scan-ops` (`cf.asn` → operator, ≤60 × 30 days); `ensureVercelStorage` in `selfUpdateVercelPanel` + `/api/vercel-storage`.
- App: `PanelLiveDot.kt`, `OperatorScanHints.kt` (pure), `OperatorScanMemory.kt`; dots in `MainActivityCloud`, `VercelRelayController` (projects + scanner picker), `NodeHostController` (picker + page, raw-socket `/health` for http panels — cleartext is off app-wide); scanner «📶 Suggested IPs for my operator» + `rememberScanForOperator`.
- Local evidence: npm test 160/160 files; vercel-self-update 38 ✓; locations 58 ✓; loc-multi-dom 15 ✓ (real page, real worker save); kotlinc harness Beta245OperatorScanAndLiveDotTest 6/6. Android UI files compile only in CI.
- Open: Vercel live panel is 6.77.3 — owner must press «آپدیت» with a token (or Storage → Blob → Connect → Redeploy) to keep users.
- CI (`d7fa69a`): all green first time (2× Android build + JUnit, Panel tests ×2, UI e2e, Railway, portal, deploy-panel build). Release `v1.10.0-beta245` prerelease, 20 assets; bot snapshot `d2044c4`.

## 2026-11-06 — v1.10.0-beta246 (313) · Panel 6.81.4 unchanged
- Request: Lantern engine; «which method works on my internet» (test all, rank, stop keeps results); complete WireGuard/Amnezia with free auto-tested servers. Ideas only from another client's releases (GPL-3), own code.
- App: `MethodFinderPlan.kt` (pure: families, list parse/pick, `ShareLinkRemark`, quick-first order, «#n» mapping, summary, per-network encode), `MethodFinderSources.kt` (HTTPS lists jsDelivr→raw→Fastly; Cat Warp ×4 on user's settings, Fox if signed in, SSTP ×6, own configs ≤10), `MainActivityMethodFinder.kt` (card, dialog, consent → DISCONNECT, real `TEST_CONNECTION_DELAYS`, ⏹ = `CANCEL_CONNECTION_DELAY_TEST`, ⭐/🚀 connect via `handleConnectionSelected`). `ConnectionDelayTestSession.delays` filled by the service for WORKS rows.
- Entry points: anti-censorship tab card; Amnezia section 🆓 button; hub option 0; «🚀 I just want to connect» → finder (TCP-only `runMlmScoutRecommendations` / `fetchAndScanAmneziaAndProtonConfigs` no longer reachable from UI).
- Lantern deferred: radiance novpn build has no socket protect (loop through Cat's own VPN), needs custom Go main + Android DNS flags; no Go in sandbox → blind CI rounds.
- Local evidence: kotlinc harness Beta246MethodFinderTest 9/9; parser over today's real lists: 4 724 rows, 1 735 unpinned insecure Hy2 refused, picks 16 Hy2 + 6 WG + 4 WARP; codemap --check OK.
- CI: `1c5be36` compiled but `CodeMapTest` failed (an `object` in a `MainActivity<Area>.kt` file) → `MethodFinderStore` moved to `MethodFinderSources.kt` (`9ea2faf`). Push run `42129112050` all green (compile, JVM tests, release APK, guards, smoke); release `v1.10.0-beta246` prerelease, 20 assets. The pull_request twin run failed at the compile pre-check while the push run on the same commit compiled; it cannot be re-run (`gh run rerun` refused) — treat as flaky, recheck on the next push.

## 2026-11-06 — v1.10.0-beta247 (314) · Panel 6.82.0
- Request: Vercel deploy with token fails completely; Lantern like Fox; VeePN free network; consider Shroudly; automate FreeSocks.
- Vercel: `VercelApiRoutes.kt` (pure: token clean, relay base from CF panel records, attempt report), `VercelRelay.VercelApi(token, routeSource)` loops direct → 127.0.0.1:2080 (accept ≤300 ms) → CF `/vrelay/` (≤3, workers.dev first), sticky, no POST replay after a read timeout; worker 6.82.0 `vercelApiRelay()`.
- Free networks: `FreeNetworkEngines.kt` (FreeNetHttp direct→tunnel; FreeProxyPlanner/Api; LanternPlanner + SingBoxOutboundLinks; FreeAccountLinks; FreeNetworkStore; FreeNetworkBuilder incl. `refreshFreeProxyCreds` hooked in `CatClientVpnService.ensureEmbeddedLocalBridgesForRuntimeFile`), UI `MainActivityFreeNetworks.kt`; entry: method-finder card + guide search.
- Found: inline subscription text starting with `https://` is classified as a remote URL (`UserSubscriptionSourceKind.fromWireName`) → free-proxy text starts with a `#` line.
- Shroudly: `DpiPresetTester.kt` + `MainActivityDpiTester.kt` (button under the preset chips). Server-less mode rejected: mihomo `direct` has no dialer-proxy (v1.19.30 source), SOCKS hand-off → poisoned DNS.
- Local evidence: harness 23/23; npm test green; codemap --check OK; JDK note: `HttpsURLConnection.getDefaultHostnameVerifier()` always false on desktop Java → endpoint identification + Android-only verifier.
- CI: `c6f1056` main code compiled; only `Beta247VercelRoutesTest` failed to compile (`com.sun.net.httpserver` is not on the Android unit-test classpath) → plain `ServerSocket` responder (`272a0ae`). Push run `42215115829` all green; PR twin green; release `v1.10.0-beta247` prerelease, 20 assets.


## beta248 (2026-11-06)
- Fanoos 403 → df.getiantem.org first; exit finder CF-IP skip + reasons + retry host + own ProxyIPs (panel 6.82.1); Vercel panel route from subscriptions + user UUID relay + clearer report; free networks next to Fox.
- Local: harness Beta248 4/4, Vercel 7/7, FreeNetworks 14/14; npm test 162/162; codemap --check OK; minify/obfuscate/stealth --check OK.
- CI: `13b91f5` push run 42220442629 ✅ + all PR twins ✅; release v1.10.0-beta248 prerelease 20 assets.

## beta249 (2026-11-06)
- Fanoos countries (names, picker via preferred_location, merge, honest "not given" note); 🌐 Cat GP engine (local MITM proxy → Google-fronted Apps Script relay, own CA, Code.gs asset, optional panel /gp hop 6.82.2).
- Local: harness Beta249CatGp 7/7, Beta249Fanoos 5/5, Beta247FreeNetworks 14/14; TLS1.2 ECDSA check; npm test 164/164; codemap --check OK; minify/obfuscate/stealth --check OK (min.js 933 712).
- CI: `3c372f0` failed compile (test used `Context()`, abstract on Android) → `fe676a0` push run 42269567588 ✅ + all PR twins ✅; release v1.10.0-beta249 prerelease 20 assets.

## beta250 (2026-11-06)
- Fox: one «🦊 Cat Fox VPN» subscription with every country (REC, free, 🔒 locked); per-row routing via SOCKS5 user `fox-CC` (RFC 1929) in the local bridge (`FoxCountryRouting.kt`, `FirefoxVpnEngine.handleFenixClient`/`openUpstreamTunnelStream(rowNode)`); per-row real ping authenticates as its country; auto ping-all after connecting multi-row subs (`connectFreeSubscription`).
- Fanoos: «🌍 همهٔ کشورها» (one request per offered location, ≤4 per country, cap 80) into the same subscription.
- Local: harness Beta250 6/6 + Beta249Fanoos 5/5; codemap --check.
- CI: `6641e8d` push run 42273690163 ✅ + all PR twins ✅; release v1.10.0-beta250 prerelease 20 assets.

## beta251 (2026-11-07)
- Panel 6.83.0: 🌍 region (Cloudflare placement: Smart / GCP·AWS·Azure region / default) via `/api/placement` + self-update metadata `placement`, kept on every update (Cloudflare's reported value wins over the saved one); clean domains in library feeds.
- Fanoos: picker always shown; «🌍» = auto + service list + `LanternPlanner.CATALOG` (20), queue grows with `tr.offered`, HTTP 4xx = «not given» (does not stop the run).
- Fox: no change needed — beta250 already writes every country into one subscription; the hub's single-row Fox card is dead code (no caller).
- Local: npm test 166/166 after budget raise; panel.yml list 31/31; placement-dom + panel-selfupdate on obf/stealth ✅; harness (rebuilt: /tmp was wiped) Beta251 5/5 + Beta250 6/6 + Beta249 5/5 + Beta247 14/14; codemap --check OK; min.js 945 260 / 950 000.
- CI: `df57bec` push run 42346733504 ✅ + all PR twins ✅; release v1.10.0-beta251 prerelease 20 assets (catclient.worker.js 945 260 B).

## beta252 (2026-11-07)
- Owner screenshot: Fanoos «🌍» = 8 servers / 2 countries (DE+SG for all 20 requests) → service assigns a fixed set to free accounts.
- `LanternPlanner.FixedCheck` (stop after 3 ignored), `serverKey` (dedupe by server), `perCountryFor` (1–2 countries → 8 each); store `ln_fixed`/`ln_fixed_at` (24 h); picker offers «🔄 fresh servers» when known; grouped «not given» line.
- No identity rotation to get more countries (that would dodge the service's paid-tier limit).
- Local: harness Beta252 6/6 + Beta251 5/5 + Beta250 6/6 + Beta249 5/5 + Beta247 14/14; codemap --check OK; syntax 405/416 (same 11 known).
- CI: `0ac2f5c` push run 42349168737 ✅ + all PR twins ✅; release v1.10.0-beta252 prerelease 20 assets.

## beta253 (2026-11-07)
- Owner asked why Fanoos lacks countries «free for Iran». Lantern store listing 10.0.5: «free plan genuinely free … Pro unlocks unlimited data, server selection». Added «service saw you in» + VPN note to the «🌍» report (geo through a connected VPN is not IR).
- Local: harness Beta252 6/6 + Beta251 5/5 + Beta249 5/5; syntax 405/416 (same 11); codemap OK.
- CI: `885a29f` push run 42351550439 ✅ + all PR twins ✅; release v1.10.0-beta253 prerelease 20 assets.

## beta254 (2026-11-07)
- Owner: Fanoos is free-Pro for Iran (tested official app); Proton like Fox; Amnezia and other free VPNs.
- Not done: fake Pro tokens, identity rotation, Play-Integrity guest login, Amnezia cloud accounts (none exist).
- Fanoos: official Radiance headers `X-Lantern-Config-Client-IP` + timezone; config-new bound to physical network; report explains Tehran clock vs VPN vs free-account cap.
- Proton: official page WebView + `.conf` intercept/paste → `wireguard://` by country (vanilla WG, not AWG junk).
- Amnezia: hub entry to existing import.
- Local: harness Beta254 + Beta252 + Beta251 + Beta249; syntax; codemap.
- CI: `c48ea7b` push run 42353622380 ✅ + all PR twins ✅; release v1.10.0-beta254 prerelease 20 assets.

## beta255 (2026-11-07)
- Audit: Aether 2.4.0 current (1.15.1 is their UI); Psiphon 2.0.42 current; Xray v26.10.31 security (MPTCP); mihomo 1.19.30 kept (patches).
- Fixes: Psiphon restart (stop previous tunnel; unbind before second start); Xray/node Reality `tcpMptcp: false` on loopback inbounds.
- Local: harness Beta255 + Beta223; node-reality; syntax; codemap.
- CI: `29eb7bf` push run 42358017472 ✅ + all PR twins ✅; release v1.10.0-beta255 prerelease 20 assets.
