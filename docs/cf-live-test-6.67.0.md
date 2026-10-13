# تستِ زندهٔ کلودفلر برای Cat Panel 6.67.0 — خروجیِ خام

این فایل رکوردِ رانِ سبزِ `38264339482` روی یک **حساب واقعیِ** کلودفلر است
(برنچ `arena/c5b36768-cat-client`، کامیت `10f7a0e`). تنها نام‌هایی که آن ران
نوشت و در پایان پاک کرد: ورکرِ `catpanel-test` و KVِ `catpanel-test-kv`
(تأییدِ پاک‌سازی در همین فایل، بخشِ Cleanup). هیچ ورکر یا KV دیگری لمس نشد و
هیچ توکنی چاپ یا ذخیره نشد.

کلیدهای واقعی (رمزِ پنل، UUID، توکنِ ساب، شناسهٔ KV) در این متن با
`<redacted N chars>` جایگزین شده‌اند؛ بقیهٔ بایت‌ها همان چیزی است که لبهٔ
کلودفلر برگرداند. نسخهٔ خط‌به‌خط و بدونِ ویرایشِ همین رکورد در
`cf-live-results.md` در ریشهٔ مخزن است.

---

## Deploy — wrangler deploy --no-bundle

```
$ npx wrangler deploy --config wrangler.cf-test.jsonc --no-bundle
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Uploaded catpanel-test (0.91 sec)
Deployed catpanel-test triggers (0.41 sec)
https://catpanel-test.zojfup.workers.dev
```

exit code 0 after 3s
- the workers.dev route answered with the Worker after **13s** of polling
<details><summary><code>deploy</code> — raw output (exit code 0)</summary>

```
# wrangler deploy --config wrangler.cf-test.jsonc --no-bundle | main = dist-panel/catpanel.min.js

 ⛅️ wrangler 4.149.0
────────────────────
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Worker Startup Time: 3 ms
Your Worker has access to the following bindings:
Binding                                                Resource                  
env.CAT_KV (<redacted>)          KV Namespace              
env.OPEN_PANEL ("true")                                Environment Variable      
env.OPEN_SUB ("true")                                  Environment Variable      

Uploaded catpanel-test (0.91 sec)
Deployed catpanel-test triggers (0.41 sec)
  https://catpanel-test.zojfup.workers.dev
Current Version ID: 34a4b81b-aa20-4d19-b42a-4af481a91eff
deploy exit: 0
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Uploaded catpanel-test (0.91 sec)
Deployed catpanel-test triggers (0.41 sec)
  https://catpanel-test.zojfup.workers.dev
  route serves the Worker: true | 13s of polling after deploy

```

</details>

## Deploy — wrangler deploy (bundled by wrangler)

```
$ npx wrangler deploy --config wrangler.cf-test.jsonc
Total Upload: 634.94 KiB / gzip: 148.13 KiB
Uploaded catpanel-test (2.43 sec)
Deployed catpanel-test triggers (0.79 sec)
https://catpanel-test.zojfup.workers.dev
```

exit code 0 after 5s
- the workers.dev route answered with the Worker after **0s** of polling
<details><summary><code>deploy</code> — raw output (exit code 0)</summary>

```
# wrangler deploy --config wrangler.cf-test.jsonc | main = dist-panel/catpanel.min.js

 ⛅️ wrangler 4.149.0
────────────────────
[33m▲ [43;33m[[43;30mWARNING[43;33m][0m [1mDuplicate key "isCloudflareIp" in object literal[0m [duplicate-object-key]

    dist-panel/catpanel.min.js:3:491745:
[37m      3 │ ...a224Hex:ne,trojanHash:ae,[32misCloudflareIp[37m:oe,CF_CIDR_RANGES:se,__s...
        ╵                             [32m~~~~~~~~~~~~~~[0m

  The original key "isCloudflareIp" is here:

    dist-panel/catpanel.min.js:3:490702:
[37m      3 │ ...yFirewallBypassPreset:ma,[32misCloudflareIp[37m:oe,DEFAULT_CLEAN_ADDRESS...
        ╵                             [32m~~~~~~~~~~~~~~[0m


[33m▲ [43;33m[[43;30mWARNING[43;33m][0m [1mDuplicate key "DEFAULT_CLEAN_ADDRESSES" in object literal[0m [duplicate-object-key]

    dist-panel/catpanel.min.js:3:493290:
[37m      3 │ ...TS:st,PLAIN_PORTS:ot,[32mDEFAULT_CLEAN_ADDRESSES[37m:lt,SCAN_RANGES:$a,h...
        ╵                         [32m~~~~~~~~~~~~~~~~~~~~~~~[0m

  The original key "DEFAULT_CLEAN_ADDRESSES" is here:

    dist-panel/catpanel.min.js:3:490720:
[37m      3 │ ...ma,isCloudflareIp:oe,[32mDEFAULT_CLEAN_ADDRESSES[37m:lt,countryLatency:f...
        ╵                         [32m~~~~~~~~~~~~~~~~~~~~~~~[0m


Total Upload: 634.94 KiB / gzip: 148.13 KiB
Worker Startup Time: 2 ms
Your Worker has access to the following bindings:
Binding                                                Resource                  
env.CAT_KV (<redacted>)          KV Namespace              
env.OPEN_PANEL ("true")                                Environment Variable      
env.OPEN_SUB ("true")                                  Environment Variable      

Uploaded catpanel-test (2.43 sec)
Deployed catpanel-test triggers (0.79 sec)
  https://catpanel-test.zojfup.workers.dev
Current Version ID: 04c64c09-707f-4690-9c84-9a2104df237f
deploy exit: 0
Total Upload: 634.94 KiB / gzip: 148.13 KiB
Uploaded catpanel-test (2.43 sec)
Deployed catpanel-test triggers (0.79 sec)
  https://catpanel-test.zojfup.workers.dev
  route serves the Worker: true | 0s of polling after deploy

```

</details>

## Deploy — wrangler deploy --no-bundle

```
$ npx wrangler deploy --config wrangler.cf-test.jsonc --no-bundle
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Uploaded catpanel-test (1.99 sec)
Deployed catpanel-test triggers (0.38 sec)
https://catpanel-test.zojfup.workers.dev
```

exit code 0 after 4s
- the workers.dev route answered with the Worker after **1s** of polling
<details><summary><code>deploy</code> — raw output (exit code 0)</summary>

```
# wrangler deploy --config wrangler.cf-test.jsonc --no-bundle | main = dist-panel/catpanel.obf.js

 ⛅️ wrangler 4.149.0
────────────────────
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Worker Startup Time: 2 ms
Your Worker has access to the following bindings:
Binding                                                Resource                  
env.CAT_KV (<redacted>)          KV Namespace              
env.OPEN_PANEL ("true")                                Environment Variable      
env.OPEN_SUB ("true")                                  Environment Variable      

Uploaded catpanel-test (1.99 sec)
Deployed catpanel-test triggers (0.38 sec)
  https://catpanel-test.zojfup.workers.dev
Current Version ID: 3176bb28-458d-4785-875a-581826eb38bd
deploy exit: 0
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Uploaded catpanel-test (1.99 sec)
Deployed catpanel-test triggers (0.38 sec)
  https://catpanel-test.zojfup.workers.dev
  route serves the Worker: true | 1s of polling after deploy

```

</details>

## Stage A-min — mode open — 17/17 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true,"version":"6.67.0","kv":true} |
| /health carries the owner payload while the panel is OPEN (by design) | ✓ | {"ok":true,"version":"6.67.0","kv":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsU |
| /api/diag is reachable without a key while the panel is OPEN | ✓ | 200 {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo": |
| KV really reads AND writes from this isolate (diag kv = read+write ok) | ✓ | read+write ok |
| /api/selfcheck says the handler is alive | ✓ | 200 {"ok":true,"version":"6.67.0","handler":"alive — /api/selfcheck?throw=1 throws on purpose to prove t |
| an exception becomes a JSON panel_crash report, not an edge error page | ✓ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET" |
| …and the report carries a stack + the crashed path | ✓ | /api/selfcheck |
| a panel password can be set AND persisted to KV | ✓ | 200 persisted=true |
| the panel closes for strangers everywhere once the password has propagated | ✓ | ~66s until 3 consecutive 401s (KV eventual consistency) |
| an anonymous /api/diag stays a real panel answer, open or closed | ✓ | 401 (open mode: 200 until the propagated lock closes the panel) |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=38901 vless=27 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true,"version":"6.67.0","kv":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsUser":false,"kv":true,"open":true}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/selfcheck  {"ok":true,"version":"6.67.0","handler":"alive — /api/selfcheck?throw=1 throws on purpose to prove the crash report replaces «Error 1101»","diag":"unset"}
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    200  /api/settings  {"ok":true,"persisted":true,"ignored":[],"capped":[],"settings":{"title":"","lang":"fa","uiTheme":"aurora","ips":[],"repoAuto":true,"repoAutoDefaultMigrated":true,"useDefaults":true,"tlsPorts":["443","2053","2083"],"plai
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:40:13.415Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@172.64.145.93:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.workers.
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

**wrangler tail** (112914 bytes captured)

    {
        "wallTime": 71,
        "cpuTime": 5,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "34a4b81b-aa20-4d19-b42a-4af481a91eff"

<details><summary><code>stage</code> — raw output (exit code 0)</summary>

```
…(truncated; showing the last 9000 characters)
i/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:40:13.415Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@172.64.145.93:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.workers.
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

--- wrangler tail (A-min) — 112914 bytes captured ---
{
    "wallTime": 71,
    "cpuTime": 5,
    "truncated": false,
    "executionModel": "stateless",
    "outcome": "ok",
    "scriptVersion": {
        "id": "34a4b81b-aa20-4d19-b42a-4af481a91eff"
    },
    "scriptName": "catpanel-test",
    "diagnosticsChannelEvents": [],
    "exceptions": [],

# A-min: 17/17 checks passed, 1101 seen: false

```

</details>

## Deploy — wrangler deploy (bundled by wrangler)

```
$ npx wrangler deploy --config wrangler.cf-test.jsonc
Total Upload: 634.94 KiB / gzip: 148.13 KiB
Uploaded catpanel-test (2.43 sec)
Deployed catpanel-test triggers (0.79 sec)
https://catpanel-test.zojfup.workers.dev
```

exit code 0 after 5s
- the workers.dev route answered with the Worker after **0s** of polling
<details><summary><code>deploy</code> — raw output (exit code 0)</summary>

```
# wrangler deploy --config wrangler.cf-test.jsonc | main = dist-panel/catpanel.min.js

 ⛅️ wrangler 4.149.0
────────────────────
[33m▲ [43;33m[[43;30mWARNING[43;33m][0m [1mDuplicate key "isCloudflareIp" in object literal[0m [duplicate-object-key]

    dist-panel/catpanel.min.js:3:491745:
[37m      3 │ ...a224Hex:ne,trojanHash:ae,[32misCloudflareIp[37m:oe,CF_CIDR_RANGES:se,__s...
        ╵                             [32m~~~~~~~~~~~~~~[0m

  The original key "isCloudflareIp" is here:

    dist-panel/catpanel.min.js:3:490702:
[37m      3 │ ...yFirewallBypassPreset:ma,[32misCloudflareIp[37m:oe,DEFAULT_CLEAN_ADDRESS...
        ╵                             [32m~~~~~~~~~~~~~~[0m


[33m▲ [43;33m[[43;30mWARNING[43;33m][0m [1mDuplicate key "DEFAULT_CLEAN_ADDRESSES" in object literal[0m [duplicate-object-key]

    dist-panel/catpanel.min.js:3:493290:
[37m      3 │ ...TS:st,PLAIN_PORTS:ot,[32mDEFAULT_CLEAN_ADDRESSES[37m:lt,SCAN_RANGES:$a,h...
        ╵                         [32m~~~~~~~~~~~~~~~~~~~~~~~[0m

  The original key "DEFAULT_CLEAN_ADDRESSES" is here:

    dist-panel/catpanel.min.js:3:490720:
[37m      3 │ ...ma,isCloudflareIp:oe,[32mDEFAULT_CLEAN_ADDRESSES[37m:lt,countryLatency:f...
        ╵                         [32m~~~~~~~~~~~~~~~~~~~~~~~[0m


Total Upload: 634.94 KiB / gzip: 148.13 KiB
Worker Startup Time: 2 ms
Your Worker has access to the following bindings:
Binding                                                Resource                  
env.CAT_KV (<redacted>)          KV Namespace              
env.OPEN_PANEL ("true")                                Environment Variable      
env.OPEN_SUB ("true")                                  Environment Variable      

Uploaded catpanel-test (2.43 sec)
Deployed catpanel-test triggers (0.79 sec)
  https://catpanel-test.zojfup.workers.dev
Current Version ID: 04c64c09-707f-4690-9c84-9a2104df237f
deploy exit: 0
Total Upload: 634.94 KiB / gzip: 148.13 KiB
Uploaded catpanel-test (2.43 sec)
Deployed catpanel-test triggers (0.79 sec)
  https://catpanel-test.zojfup.workers.dev
  route serves the Worker: true | 0s of polling after deploy

```

</details>

## Stage A2-bundled — mode locked — 12/12 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |
| the lock is observable on the edge answering these requests | ✓ | ~0s for the write to reach this colo |
| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| the old UUID is no longer a key once a password exists | ✓ | 401 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=49002 vless=30 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:40:42.763Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@199.232.211.112:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.worker
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

**wrangler tail** (40795 bytes captured)

    {
        "wallTime": 1,
        "cpuTime": 0,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "04c64c09-707f-4690-9c84-9a2104df237f"

<details><summary><code>stage</code> — raw output (exit code 0)</summary>

```

=== stage A2-bundled (mode locked) against https://catpanel-test.zojfup.workers.dev ===
  ✓ /health answers ok:true (200 JSON)  → 200 {"ok":true}
  ✓ /health is bare for an anonymous caller (no version, no kv field)  → {"ok":true}
  ✓ /api/version reports 6.67.0 (the file loaded and ran)  → 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
  ✓ the lock is observable on the edge answering these requests  → ~0s for the write to reach this colo
  ✓ /api/diag refuses an anonymous caller (401)  → 401 {"ok":false,"error":"unauthorized"}
  ✓ the old UUID is no longer a key once a password exists  → 401
  ✓ /api/diag with the owner key returns the report  → 200
  ✓ KV read+write is proven on the live account  → read+write ok
  · bindings visible to the isolate: ["CAT_KV","OPEN_PANEL","OPEN_SUB"]
  · passwordSource / uuidSource    : panel / derived from the hostname
  · usage / subToken / app         : {"day":"2026-10-12","reqs":13,"writes":2,"errors":[{"t":1791816013267,"k":"unhandled","d":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if you can read this JSON, the 1101 handler works","n":2}],"pending":0,"noKv":false} / not set (links use the master UUID) / {"appVersion":"the app has not reported one yet","panelBundled":"the app has not reported one yet","seenAt":null,"match":"unknown"}
  ✓ owner sees the full crash report on the live edge  → 500
  ✓ a stranger is refused BEFORE the throw (401, no report)  → 401 {"ok":false,"error":"unauthorized"}
  ✓ /api/backup masks secrets by default  → 200 secrets=masked hash=***MASKED-BY-CAT-PANEL**
  ✓ the subscription serves real configs from the live edge  → 200 bytes=49002 vless=30
  · GET / → 200 (<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name=)

  raw responses:
    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:40:42.763Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@199.232.211.112:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.worker
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

--- wrangler tail (A2-bundled) — 40795 bytes captured ---
{
    "wallTime": 1,
    "cpuTime": 0,
    "truncated": false,
    "executionModel": "stateless",
    "outcome": "ok",
    "scriptVersion": {
        "id": "04c64c09-707f-4690-9c84-9a2104df237f"
    },
    "scriptName": "catpanel-test",
    "diagnosticsChannelEvents": [],
    "exceptions": [],

# A2-bundled: 12/12 checks passed, 1101 seen: false

```

</details>

## Stage B-obf — mode locked — 12/12 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |
| the lock is observable on the edge answering these requests | ✓ | ~0s for the write to reach this colo |
| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| the old UUID is no longer a key once a password exists | ✓ | 401 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=49026 vless=30 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"ATL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:41:11.165Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@198.41.200.128:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.workers
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

**wrangler tail** (40799 bytes captured)

    {
        "wallTime": 132,
        "cpuTime": 7,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "3176bb28-458d-4785-875a-581826eb38bd"

<details><summary><code>stage</code> — raw output (exit code 0)</summary>

```

=== stage B-obf (mode locked) against https://catpanel-test.zojfup.workers.dev ===
  ✓ /health answers ok:true (200 JSON)  → 200 {"ok":true}
  ✓ /health is bare for an anonymous caller (no version, no kv field)  → {"ok":true}
  ✓ /api/version reports 6.67.0 (the file loaded and ran)  → 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
  ✓ the lock is observable on the edge answering these requests  → ~0s for the write to reach this colo
  ✓ /api/diag refuses an anonymous caller (401)  → 401 {"ok":false,"error":"unauthorized"}
  ✓ the old UUID is no longer a key once a password exists  → 401
  ✓ /api/diag with the owner key returns the report  → 200
  ✓ KV read+write is proven on the live account  → read+write ok
  · bindings visible to the isolate: ["CAT_KV","OPEN_PANEL","OPEN_SUB"]
  · passwordSource / uuidSource    : panel / derived from the hostname
  · usage / subToken / app         : {"day":"2026-10-12","reqs":18,"writes":3,"errors":[{"t":1791816042624,"k":"unhandled","d":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if you can read this JSON, the 1101 handler works","n":3}],"pending":0,"noKv":false} / not set (links use the master UUID) / {"appVersion":"the app has not reported one yet","panelBundled":"the app has not reported one yet","seenAt":null,"match":"unknown"}
  ✓ owner sees the full crash report on the live edge  → 500
  ✓ a stranger is refused BEFORE the throw (401, no report)  → 401 {"ok":false,"error":"unauthorized"}
  ✓ /api/backup masks secrets by default  → 200 secrets=masked hash=***MASKED-BY-CAT-PANEL**
  ✓ the subscription serves real configs from the live edge  → 200 bytes=49026 vless=30
  · GET / → 200 (<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name=)

  raw responses:
    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"ATL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:41:11.165Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@198.41.200.128:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.workers
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

--- wrangler tail (B-obf) — 40799 bytes captured ---
{
    "wallTime": 132,
    "cpuTime": 7,
    "truncated": false,
    "executionModel": "stateless",
    "outcome": "ok",
    "scriptVersion": {
        "id": "3176bb28-458d-4785-875a-581826eb38bd"
    },
    "scriptName": "catpanel-test",
    "diagnosticsChannelEvents": [],
    "exceptions": [],

# B-obf: 12/12 checks passed, 1101 seen: false

```

</details>

## App-style upload (the same builder as CloudflareWorker.cfUploadWorker)

- method/url: `PUT /accounts/<account>/workers/scripts/catpanel-test`
- headers: `Content-Type: multipart/form-data; boundary=…`, `Content-Length: 1253197`
- metadata: `{"main_module":"worker.js","bindings":[{"type":"plain_text","name":"UUID","text":"<plain_text: 36 chars, not printed>"},{"type":"secret_text","name":"PANEL_PASSWORD","text":"<secret_text: 16 chars, not printed>"},{"type":"kv_namespace","name":"CAT_KV","namespace_id":"<redacted>"}],"compatibility_date":"2025-03-04","compatibility_flags":["nodejs_compat"]}`
- body bytes: 1253197 (script 1252540 + multipart overhead 657)
- HTTP 200: `{ "result": { "created_on": "2026-10-12T14:38:29.622854Z", "modified_on": "2026-10-12T14:41:13.909432Z", "id": "catpanel-test", "tag": "b3b1af53d29948b59932c64f33f51b34", "entry_point": "worker.js", "tags": [], "deployment_id": "9cc75fd75394421da52795aa0b2cf21e", "tail_consumers": null, "logpush": f`
- workers.dev route: HTTP 200

<details><summary><code>upload-app</code> — raw output (exit code 0)</summary>

```
app-style upload (byte-for-byte the same builder as CloudflareWorker.cfUploadWorker):
  url        : PUT https://api.cloudflare.com/client/v4/accounts/<account>/workers/scripts/catpanel-test
  metadata   : {"main_module":"worker.js","bindings":[{"type":"plain_text","name":"UUID","text":"<plain_text: 36 chars, not printed>"},{"type":"secret_text","name":"PANEL_PASSWORD","text":"<secret_text: 16 chars, not printed>"},{"type":"kv_namespace","name":"CAT_KV","namespace_id":"<redacted>"}],"compatibility_date":"2025-03-04","compatibility_flags":["nodejs_compat"]}
  body bytes : 1253197 (script 1252540 + multipart overhead 657 )
  HTTP 200 { "result": { "created_on": "2026-10-12T14:38:29.622854Z", "modified_on": "2026-10-12T14:41:13.909432Z", "id": "catpanel-test", "tag": "b3b1af53d29948b59932c64f33f51b34", "entry_point": "worker.js", "tags": [], "deployment_id": "9cc75fd75394421da52795aa0b2cf21e", "tail_consumers": null, "logpush": false, "has_assets": false, "has_modules": true, "etag": "<redacted 43 chars>
  workers.dev route enabled: 200 {"enabled":true,"previews_enabled":false}

```

</details>

## Stage C-app-api — mode locked — 12/12 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |
| the lock is observable on the edge answering these requests | ✓ | ~0s for the write to reach this colo |
| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| the old UUID is no longer a key once a password exists | ✓ | 401 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=48980 vless=30 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"ATL"},"bindings":["CAT_KV","PANEL_PASSWORD","UUID"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:41:45.535Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@151.101.100.167:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FuATVSrh0WCfNPvzZe7rRYtO%3Fed%3D2560&sni=catpanel-test.zojfup.worker
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

**wrangler tail** (40800 bytes captured)

    {
        "wallTime": 31,
        "cpuTime": 23,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "9cc75fd7-5394-421d-a527-95aa0b2cf21e"

<details><summary><code>stage</code> — raw output (exit code 0)</summary>

```

=== stage C-app-api (mode locked) against https://catpanel-test.zojfup.workers.dev ===
  ✓ /health answers ok:true (200 JSON)  → 200 {"ok":true}
  ✓ /health is bare for an anonymous caller (no version, no kv field)  → {"ok":true}
  ✓ /api/version reports 6.67.0 (the file loaded and ran)  → 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
  ✓ the lock is observable on the edge answering these requests  → ~0s for the write to reach this colo
  ✓ /api/diag refuses an anonymous caller (401)  → 401 {"ok":false,"error":"unauthorized"}
  ✓ the old UUID is no longer a key once a password exists  → 401
  ✓ /api/diag with the owner key returns the report  → 200
  ✓ KV read+write is proven on the live account  → read+write ok
  · bindings visible to the isolate: ["CAT_KV","PANEL_PASSWORD","UUID"]
  · passwordSource / uuidSource    : panel / the UUID variable
  · usage / subToken / app         : {"day":"2026-10-12","reqs":23,"writes":4,"errors":[{"t":1791816071039,"k":"unhandled","d":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if you can read this JSON, the 1101 handler works","n":4}],"pending":0,"noKv":false} / not set (links use the master UUID) / {"appVersion":"the app has not reported one yet","panelBundled":"the app has not reported one yet","seenAt":null,"match":"unknown"}
  ✓ owner sees the full crash report on the live edge  → 500
  ✓ a stranger is refused BEFORE the throw (401, no report)  → 401 {"ok":false,"error":"unauthorized"}
  ✓ /api/backup masks secrets by default  → 200 secrets=masked hash=***MASKED-BY-CAT-PANEL**
  ✓ the subscription serves real configs from the live edge  → 200 bytes=48980 vless=30
  · GET / → 200 (<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name=)

  raw responses:
    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"ATL"},"bindings":["CAT_KV","PANEL_PASSWORD","UUID"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:41:45.535Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/<redacted>?raw=1  vless://<redacted>@151.101.100.167:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FuATVSrh0WCfNPvzZe7rRYtO%3Fed%3D2560&sni=catpanel-test.zojfup.worker
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

--- wrangler tail (C-app-api) — 40800 bytes captured ---
{
    "wallTime": 31,
    "cpuTime": 23,
    "truncated": false,
    "executionModel": "stateless",
    "outcome": "ok",
    "scriptVersion": {
        "id": "9cc75fd7-5394-421d-a527-95aa0b2cf21e"
    },
    "scriptName": "catpanel-test",
    "diagnosticsChannelEvents": [],
    "exceptions": [],

# C-app-api: 12/12 checks passed, 1101 seen: false

```

</details>

## Cleanup — verified through the Cloudflare API

- DELETE /accounts/<account>/workers/scripts/catpanel-test → 200
- DELETE /accounts/<account>/storage/kv/namespaces/<redacted> → 200
- workers left on the account: (none) — `catpanel-test` gone: **true**
- KV namespaces left: (none) — `catpanel-test-kv` gone: **true**

<details><summary><code>cleanup</code> — raw output (exit code 0)</summary>

```
deleting the throwaway worker + KV namespace…
  DELETE worker  : 200 {"id":"b3b1af53d29948b59932c64f33f51b34"}
  DELETE KV      : 200 []
  workers left   : (none)
  catpanel-test gone: true
  kv namespaces  : (none)
  test KV gone   : true

```

</details>

## Expected-vs-actual summary

| stage | version | KV state | sub | checks | 1101? |
| --- | --- | --- | --- | --- | --- |
| A-min | 6.67.0 | read+write ok | ok | 17/17 | no |
| A2-bundled | 6.67.0 | read+write ok | ok | 12/12 | no |
| B-obf | 6.67.0 | read+write ok | ok | 12/12 | no |
| C-app-api | 6.67.0 | read+write ok | ok | 12/12 | no |

<details><summary><code>summary</code> — raw output (exit code 0)</summary>

```
| stage | version | KV state | sub | checks | 1101? |
| --- | --- | --- | --- | --- | --- |
| A-min | 6.67.0 | read+write ok | ok | 17/17 | no |
| A2-bundled | 6.67.0 | read+write ok | ok | 12/12 | no |
| B-obf | 6.67.0 | read+write ok | ok | 12/12 | no |
| C-app-api | 6.67.0 | read+write ok | ok | 12/12 | no |

all hard expectations held on the live account

```

</details>

---

# پیوست — بازبینیِ فقط-خواندنی، ۲۰۲۶-۱۰-۱۳ (چه چیزی اندازه‌گیری شد و چه چیزی نشد)

همهٔ خطوطِ زیر خروجیِ خامِ یک رانِ واقعی است (ورک‌فلوی موقتِ «CF read-only diagnosis»)،
فقط `GET` و یک نشستِ موقتِ `wrangler tail`. **هیچ چیزی روی حساب نوشته، دیپلوی یا حذف نشد.**

## الف) وضعیتِ اعتبارنامهٔ Actions در همین لحظه

```
GET /user/tokens/verify -> HTTP 200
{"result":{"id":"b17a…6c7","status":"active"},"success":true,"errors":[],"messages":[{"code":10000,"message":"This API Token is valid and active"…}]}

GET /accounts/<id> -> HTTP 403
{"success":false,"errors":[{"code":9109,"message":"User was deactivated"}],"result":null}

GET /workers/subdomain -> HTTP 403
{"success":false,"errors":[{"code":10000,"message":"Authentication error",…}],"result":null}

GET /workers/scripts -> HTTP 403
{"success":false,"errors":[{"code":10000,"message":"Authentication error",…}],"result":null}

GET /workers/scripts/edge-9ccq0/settings -> HTTP 429
{"success":false,"errors":[{"code":10429,"message":"Rate limited. Please wait and consider throttling your request speed"}],"result":null}
```

یعنی: خودِ توکن «active» است، ولی هیچ خواندنِ حساب‌سطحی با آن ممکن نیست؛ و از تماسِ
چهارم به بعد، API با `429 / 10429` throttle می‌کند (همین هم `wrangler tail` را بست).

## ب) دو ورکرِ مورد سؤال، در همین لحظه

```
DNS: edge-9ccq0.zojfup.workers.dev → 2606:4700:3033::ac43:b861 / 2606:4700:3030::6815:1309
     edge-vgiab.zojfup.workers.dev → همان نشانی‌ها
/health       -> HTTP 404, 17 bytes, type=text/plain; charset=UTF-8
/health body  -> error code: 1042
/api/version  -> HTTP 404, type=text/plain; charset=UTF-8
wrangler tail -> refused: 10429 rate limited (twice, once per worker)
```

**۱۴۰۲/… یعنی امروز، زیرِ آن دو نام هیچ ورکری سرو نمی‌شود** (`1042` پاسخِ workers.dev
برای نامِ بدونِ اسکریپت است) — پس ۱۱۰۱ آن دو ورکر دیگر قابلِ بازتولید نیست و علتش هم
قابلِ خواندن نیست: بایت‌های اسکریپت با این توکن خوانده نمی‌شوند (۴۰۳ در بخشِ الف).

## ج) همان لحظه: مسیرِ آپلودِ خودِ اپ روی workerd محلی

همان فایل و همان تنظیماتِ `cfUploadWorker` (main_module، compat `2025-03-04`، `nodejs_compat`،
بایندینگ‌های OPEN_PANEL/OPEN_SUB) روی موتورِ خودِ کلودفلر (workerd «2026-10-13») بالا آمد:

```
/health                 200 application/json  {"ok":true,"version":"6.67.0","kv":false}
/api/version            200 application/json  {"ok":true,"panel":"cat-panel","version":"6.67.0",…,"open":true}
/api/selfcheck?throw=1  500 application/json  {"error":"panel_crash","title":"Worker Exception Handled",…}
/api/diag               200 application/json  {"…","kv":"NOT BOUND — every write is memory-only, attach CAT_KV",…}
```

و هر سه آرتیفکتِ به‌روز (`catpanel.obf.js` = 1,243,155 B، `catpanel.min.js` = 494,032 B،
`catclient.worker.js` = 655,438 B، همه ‎6.67.0‎) با `node --check` پارس می‌شوند.
