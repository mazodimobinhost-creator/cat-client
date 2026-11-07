# Handoff log — Arena agent · Grok · ملاد

Read `docs/COLLABORATION.md` first. Add the newest entry at the top. One entry per finished piece of work.

```
## YYYY-MM-DD — <who> — <short title>
- Version / commit: v1.10.0-betaNNN / <sha>
- Done: …
- Evidence: CI run / phone test (operator, device) / log keys
- Open / next owner: …
```

## beta255 / Panel 6.83.0 — 2026-11-07
- Audit: Aether 2.4.0 (WhiteAestherMobile v1.15.0) is current — v1.15.1 is their Worker/proxy UI, Cat already has those. Psiphon Android lib 2.0.42 is current. Mihomo stays v1.19.30 (FlClash patches; upstream v1.19.33 not rebased).
- **Xray-core v26.10.31** (`a4a3dff`) security: MPTCP off. App pin + node Reality download. SOCKS/Reality/SS inbounds `tcpMptcp: false`, listen 127.0.0.1.
- **Psiphon restart:** `startCarrier` stops the previous tunnel; client `start()` unbinds first so a country retry actually runs.

## beta254 / Panel 6.83.0 — 2026-11-07
- Owner asked for Fanoos “free Pro for Iran”, Proton like Fox, Amnezia and other free VPNs. **Not done:** fake Pro tokens, identity rotation, Play-Integrity guest login.
- **Fanoos:** official Radiance `X-Lantern-Config-Client-IP` (public IP looked up off the VPN) + IANA timezone; config-new bound to SIM/Wi-Fi (`FreeNetHttp.openOnPhysical`). Timezone `Asia/Tehran` is stronger evidence of Iran than the connecting IP. If the service still only gives DE+SG after that, it is the free-account cap.
- **🟢 Cat Proton:** official signup/sign-in WebView (human check stays the user's). `.conf` download/paste → `wireguard://` by country. Free plan countries NL/JP/US/CA/RO/PL/NO/SG. Plus is not faked.
- **🔐 Cat Amnezia:** no free cloud account — hub entry to the existing vpn:// / `.conf` import.

## beta252 / Panel 6.83.0 — 2026-11-07
- Fanoos free accounts get a fixed country set (owner phone: 🇩🇪 + 🇸🇬, requested country ignored). `FixedCheck` stops «🌍» after 3 ignored requests and the result is remembered 24 h (`lanternFixedRecent`). Do not add identity rotation to get other countries — that dodges the service's paid tier.

## beta251 / Panel 6.83.0 — 2026-11-07
- **Panel region:** `PLACEMENT_REGIONS`/`normalizePlacement`/`placementMeta`/`placementFromCf` next to `selfUpdatePanel`; `selfUpdatePanelWorker` puts `meta.placement` and saves KV `cat:v6:placement`; route `/api/placement` (owner, cf-only POST with `confirm`); UI card `#plcCard` (backup tab) + client `plc*`. Smart needs traffic from several places and up to 15 min. Placement only moves fetch handlers — direct 🌐 configs; ProxyIP/SOCKS configs keep their exit.
- **Fanoos:** `LanternPlanner.CATALOG`, `allTargets`, `extendQueue`, `answeredButRefused`; `fetchFanoosAll` loop in `MainActivityFreeNetworks.kt`. Whether the service honours catalog locations for free accounts is only known on a phone — the report says which were not given.
- **Phone checks:** panel region apply + «الان واقعاً در دیتاسنتر …» line; Fanoos «🌍» country count; Fox one sub (beta250+).

## beta249 / Panel 6.82.2 — 2026-11-06
- Fanoos: `LanternPlanner.translate` reads `outbound_locations` (name «🏮 Cat Fanoos <flag> <country> <city> #n») + `servers` (offered) + `country`;
  `configBody(..., Location?)` sends `preferred_location` {country, city, country_code}; `merge` keeps other countries (cap 40); store `ln_offered`, `ln_links`.
- 🌐 Cat GP: `CatGpRelay.kt` (pure: `CatGpJson`, `CatGpCodec`, `CatGpDer`, `CatGpCa`, `CatGpFronter`, `CatGpProxy`, `CatGpScripts`), `CatGpEngine.kt` (prefs `cat_gp`,
  CA in filesDir/catgp, port 10871 user `catgp`), `MainActivityCatGp.kt` (dialog), template `assets/catgp/Code.gs`. Service hook "2c" starts it when the yaml
  points at 127.0.0.1:10871; `stopVpn` stops it; `deadLocalTags` exempts the port. Worker `catGpHop` at `/gp/<uuid>`.
- Unknown until phone test: Conscrypt server TLS + Chrome trusting the user CA on a real phone; which Google front IPs work per operator; real GAS latency.

## beta248 / Panel 6.82.1 — 2026-11-06
- Fanoos: `LanternPlanner.CONFIG_URLS` = df.getiantem.org first (radiance `kindling/client.go`: Google edge 403s Iran on df.iantem.io), `isEdgeBlock` → next host; body adds `backend`.
- Worker `locFetch`: skip `isCloudflareIp` candidates (`skippedCf`), 24 tagged + own `proxyIps` (non-socks) within 24 s, 5 parallel, retry `www.cloudflare.com` on non-connect errors, `errors` histogram → UI `locWhy()`. `vercelApiRelay(request,url,master,env)` accepts enabled user ids.
- App: `VercelRoutePlanner.panelsFromSubscriptions` + `routeReport`; `VercelRelayController.cloudflarePanels()` = built + from subscriptions.
- Free networks in `ALL_VPNS_2026_CATALOG` (ids free_proxy/fanoos/free_50gb; size 12) and tools menu items 2–4 (older items shifted by 3 in the `when`).
- Unknown until phone test: whether df.getiantem.org answers config-new from Iran; why the user's feed ProxyIPs failed (the new «علت» line will say).


## 2026-11-06 — Arena agent — beta247 / Panel 6.82.0 (Vercel token deploy, free networks, fragmenting test)
- Version / commit: v1.10.0-beta247 / c6f1056 + test fix 272a0ae — push run 42215115829 green, prerelease 20 assets
- Done: Vercel API route chain direct → Cat tunnel → CF panel `/vrelay/` (needs the CF panel on 6.82.0); 🎁 free networks
  (Cat Free Proxy 7 countries, 50 GB account in an in-app WebView where the USER solves the captcha, Cat Fanoos experimental);
  connect-time credential refresh in the service (8 s budget); 🧪 real per-network test of the 7 fragmenting presets.
- Evidence: npm test green (budgets: raw 930 133/950 000, gzip 281 217/284 000); kotlinc harness 23/23 (Vercel 7, free 14,
  DPI 2); real TLS handshake + SOCKS5 IP hand-off checked against github.com in the sandbox. Free-network APIs are NOT reachable
  from the sandbox → untested live; Android UI compiles only in CI. NOT phone-tested.
- Open / next owner: ملاد — phone: 🎁 hub (each of the 3), 🧪 test on MCI vs Irancell; owner — update the CF panel to 6.82.0,
  then retry the Vercel token deploy and send «📋 کپی گزارش» if it still fails.

## 2026-11-05 — Arena agent — beta245 / Panel 6.81.4 (owner's 5 items)
- Version / commit: v1.10.0-beta245 / d7fa69a — all CI green first time, prerelease 20 assets
- Done: several exit countries per user (≤6, ≤12 exits, `exitCc` kept = first); ➕ library → panel ProxyIP/SOCKS list (healthy
  only, exact ports); 📶 per-operator scanner memory (panel: `cf.asn` → operator, `/api/scan-ops`; app: «Suggested IPs for my
  operator», Wi-Fi kept separate via `NetworkCapabilities`); one-tap Vercel Blob storage on «Update» (`ensureVercelStorage`);
  🟢 live `/health` dots in every app panel list (Node http panels via the raw-socket path — cleartext is off app-wide).
- Evidence: npm test 160/160; `loc-multi-dom.test.mjs` 15 (real page + real worker save); locations 58; vercel-self-update 38;
  kotlinc harness `Beta245OperatorScanAndLiveDotTest` 6/6. Android UI files compile only in CI. NOT phone-tested.
- Open / next owner: ملاد — phone check of the dots (KataBump http panel must turn 🟢) and of «📶» on MCI vs Irancell vs Wi-Fi;
  owner — press «آپدیت» on the Vercel panel with a token so storage attaches (live panel is 6.77.3, users not persisted).

## 2026-11-05 — Arena agent — beta243 / Panel 6.81.2 (review round 2: Grok panel + Claude app)
- Version / commit: v1.10.0-beta243 / f840249 — all CI runs green (10 success, deploy-worker skipped), prerelease 20 assets
- Done (every item checked against code; table in docs/REVIEW-BRIEF.md «پاسخ به دور دوم»):
  Grok: `pages/wrangler.jsonc` OPEN_PANEL/OPEN_SUB were "true" → "false" + test; chain «all» non-Strict fallback was
  silent → kept (Strict switch = owner's choice) but recorded: `/api/settings` `exitDown`, red toast on panel load;
  COLLABORATION §3 now separates `portal/` (account portal, D1) from `pages/` (panel on Pages).
  Claude: wizard remembers only reachable+confirmed peers; one `AetherFallbackStages` for AetherSession + carrier
  (no repeated discovery, full search budget after a failed hint, one alternate table); chain carriers get the plan
  budget; WiW alternate WireGuard→WoM (WireGuard+foreign = wiw again — found by the new behaviour test); learned-mode
  window keeps first savedAt; failover retry session gets its own watchdog (learns only after its own prepare).
- Evidence: `Beta241AetherNetworkPlanTest` 11/11 + `Beta243AetherReviewRound2Test` 7/7 locally (kotlinc harness with
  real AetherConfig.kt + org.json stub) and in CI; `npm test` green; mesh / pages-target / deploy-bot pass. NOT phone-tested.
- Drive folder 1uS0bj7aGXc-TzHTuEdChAQ6E6kAQdxwY: docs A (app diff), B (complete panel diff beta239→243), C 01–12
  (full worker, ~95 KB parts), D (full Aether files). Links in brief §0.
- Open / next owner: ملاد — CF panel → 6.81.2; chain «all» with Strict off + chain server down → panel shows the red
  toast; Cat Warp WiW on a UDP-blocking line should fall to WoM (log `aether.plan*`). Reviewers — round 3 on beta243.

## 2026-11-05 — Arena agent — beta242 / Panel 6.81.1 (review round 1)
- Version / commit: v1.10.0-beta242 / 376a1fc — all CI runs green, prerelease 20 assets
- Done: a reviewer (no repo access) sent 5 hypotheses from the brief; each checked against the code:
  (1) IPv6 check already excluded ULA/link-local; real gap = arbitrary non-VPN network while our tunnel is up →
  shared `pickUnderlyingNetwork` (active non-VPN, else validated, Wi-Fi/Ethernet before cellular).
  (2) Wi-Fi key = hash(gateway+DNS), no SSID; real gap = learned WireGuard for WARP-in-WARP «auto» became single-hop
  `wg` (Iran exit) → `AetherNetworkPlanner.alternateMode`/`exitForAlternate` shared by planner, AetherSession, carrier.
  (3) wizard showed a hit that failed the connection check as «✅» → `confirmed` flag, 🟡 text, ranked lower.
  (4) chain bug 4 confirmed and fixed in the panel: link exit «all» fails closed (`link exit down (all sites)`),
  `/api/links` → `exitDown`; «cf» keeps fallback. (5) ProxyIPs are seeds only — unchanged.
- Evidence: `Beta241AetherNetworkPlanTest` 11/11 run locally via kotlinc + reflection runner from `app/`; `npm test`
  159/159; `mesh.test.mjs` (3 new checks), `katabump-cf-link`, `worker-realtraffic` pass; CI green. NOT phone-tested.
- Code for reviewers without GitHub: Drive folder «Cat Client — بازبینی beta242» (diff + full files), brief §0.
- Open / next owner: ملاد — CF panel → 6.81.1; with a 🔗 link in «all», stop the peer and check that sites fail
  (not direct) and the link page shows the error; Cat Warp across lines (log `aether.plan*`). Reviewers — round 2.

## 2026-11-05 — Arena agent — beta241 (Cat Warp on other people's lines)
- Version / commit: v1.10.0-beta241 / 2fd70e9 (code) + 88748b0 (test fix, brief) — all 8 CI runs green, prerelease 20 assets
- Done: «Cat Warp configs the app builds only work on my own internet». Cause: wizard configs carry the builder line's
  endpoint, IP family (IPv6) and mode (WireGuard/UDP); on another line the service dialled exactly that, each fallback
  stage with 180 s, and the link endpoint was saved into the new network's memory BEFORE trying it. Fix:
  `AetherNetworkPlanner` (pure) + `runAetherStartup`: IPv6→IPv4 when the underlying network has no IPv6 (IPv6 endpoints
  dropped), endpoint verified on this network first, link endpoint as a 60 s hint, else core search; learned working mode
  per network (7 days); endpoint/mode remembered only after `Started` from the peer the core really used
  (`AetherSession.lastPeer`); old memories not trusted (`netpool2_`/`netmem2_`); duplicate discovery stage skipped.
  Also: `panel-680` `/api/ech-pool` test made deterministic; `docs/REVIEW-BRIEF.md` for Claude/Grok.
- Evidence: `Beta241AetherNetworkPlanTest`; planner run in a local kotlinc harness (6 cases); `npm test` 159/159; CI green.
  NOT phone-tested. Log key on the phone: `aether.plan` (net=… mode/ip/endpoint/budget + reasons), `aether.plan.learned`.
- Open / next owner: ملاد — build a Cat Warp config on MCI, connect it on Irancell and on a Wi-Fi without IPv6 (and on
  a friend's phone); send the «کپی گزارش عیب‌یابی» lines `aether.plan*`. Claude/Grok — full review per `docs/REVIEW-BRIEF.md`.

## 2026-11-05 — Arena agent — beta240 (Panel 6.81.0)
- Version / commit: v1.10.0-beta240 / 8f97a76 — all CI runs green (push + PR), prerelease with 20 assets
- Done: Worker «boots but nothing connects» — 5 root causes fixed: DATA_PLANE_ENABLED missing in every CF deploy path
  (now set everywhere + in-panel 🔌 fix `/api/data-plane`), Blob WebSocket frames on compat ≥2026-03-17 (1002 on every
  handshake), no ProxyIP/SOCKS retry when a CF-hosted site closes a direct dial with 0 bytes (host:port cache 30 min),
  `/api/ip-test` marked good CF clean IPs dead (now `skip:'edge'` → tested in the owner's browser), VPN-viewer warning.
  Settings: sniffing (route-only), FakeDNS switch, system HTTP proxy (Android 10+), ping address (Google/Cloudflare).
- Evidence: `npm test` 159/159; `worker-realtraffic.test.mjs` 22/22 (real VLESS through miniflare; the beta239 worker
  fails it with 1002); Kotlin via CI (`Beta240CoreOptionsTest`).
- Open / next owner: ملاد — on an existing CF panel: update to 6.81.0, press 🔌 if the red banner shows, then connect a
  config on MCI + Irancell; toggle Sniffing / FakeDNS off / HTTP proxy and report any app that breaks.
  Grok — review with `scripts/review-worktree.sh 8f97a76 --base 938cf99`. Still open: chain bug 4 (silent link→direct
  fallback in `all` mode); curated Iran-tested CF IP source (own implementation).

## 2026-11-05 — Arena agent — beta239 released
- Version / commit: v1.10.0-beta239 / a7439a5 — all 6 CI runs green, prerelease with 20 assets
- Done: Fox 406 fix (challenge solved on the host that raised it, same User-Agent as the retried call, per-host cookies,
  Firefox 159 + MozillaVPN identity), Telegram MTProto finder (real FakeTLS handshake test), Hysteria v1 + hy2 pin/port
  hopping, subscription provider headers (title / announcement / support / page / refresh interval).
- Evidence: CI (JUnit incl. an in-process FakeTLS proxy test); Fox NOT phone-tested yet (sandbox cannot reach Mozilla).
- Open / next owner: ملاد — phone test Fox sign-in + connect on MCI and Irancell (report the exact Persian error if any);
  test «✈️ پروکسی تلگرام» on both operators. Grok — review with `scripts/review-worktree.sh a7439a5 --base e1dd27b`.

## 2026-11-05 — Arena agent — beta238 released
- Version / commit: v1.10.0-beta238 / 9fe0fc2 — all CI runs green, release with 20 assets
- Done: Fox login over the official Mozilla WebChannel (no 406, verified sessions only), Zerops/Seenode/Hostim host profiles,
  encrypted DNS (DoH/DoT) scan with poisoning check, `scripts/review-worktree.sh`, this collaboration file.
- Open / next owner: ملاد — phone tests #4, #5, #6, #8 on the task board; Grok — review beta238 with `scripts/review-worktree.sh`.
