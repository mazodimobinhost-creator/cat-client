# Cloudflare live test — catpanel-test

run=38264339482 attempt=1 trigger=push actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client head=10f7a0e7076a7d5305376d70a8bf02b583aa85d8

Everything in this file was produced by a real Cloudflare account. Only two
names are ever written: the Worker `catpanel-test` and the KV namespace `catpanel-test-kv`.

<details><summary><code>preflight</code> — raw output (exit code 0)</summary>

```
worker: catpanel-test | kv title: catpanel-test-kv | account id set: true | token set: true
::warning::trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client
provided by  : secrets.CLOUDFLARE_API_TOKEN=true secrets.CLOUDFLARE_ACCOUNT_ID=true | vars.CLOUDFLARE_API_TOKEN=false vars.CLOUDFLARE_ACCOUNT_ID=false  (repository secrets and repository variables are two separate storages)
::warning::CONTROL — GitHub-injected names visible: GITHUB_TOKEN
CONTROL — GitHub-injected names visible: GITHUB_TOKEN
::warning::repository secrets (token names): CLOUDFLARE_API_TOKEN, cloudflare_api_token, Cloudflare_Api_Token, GITHUB_TOKEN
repository secrets (token names): CLOUDFLARE_API_TOKEN, cloudflare_api_token, Cloudflare_Api_Token, GITHUB_TOKEN
::warning::repository secrets (account names): CLOUDFLARE_ACCOUNT_ID, cloudflare_account_id, GITHUB_TOKEN
repository secrets (account names): CLOUDFLARE_ACCOUNT_ID, cloudflare_account_id, GITHUB_TOKEN
::warning::repository variables (token names): none of the 12 candidate names exist
repository variables (token names): none of the 12 candidate names exist
::warning::repository variables (account names): none of the 9 candidate names exist
repository variables (account names): none of the 9 candidate names exist
::add-mask::<redacted>
::add-mask::<redacted>
token verify : 200 active
subdomain    : zojfup
base url     : https://catpanel-test.zojfup.workers.dev
scripts before: ([])

```

</details>

<details><summary><code>kv-create</code> — raw output (exit code 0)</summary>

```
created KV namespace: d5911327c47d4b76aebdf12ea1295acd
KV_ID=d5911327c47d4b76aebdf12ea1295acd
all namespaces on the account: (none listed)

```

</details>

<details><summary><code>config</code> — raw output (exit code 0)</summary>

```
wrangler.cf-test.jsonc built from wrangler.jsonc:
  name              : empty-wave-5d02 → catpanel-test
  main              : dist-panel/catpanel.stealth.js → dist-panel/catpanel.min.js
  kv cat_kv         : (auto-provisioned / no id) → id <redacted>
  triggers.crons    : {"crons":["0 */12 * * *"]} → removed (throwaway worker)
  compatibility_date: 2026-09-01 (unchanged)
  compatibility_flags: null (unchanged)
  vars              : {"OPEN_PANEL":"true","OPEN_SUB":"true"} (unchanged)
  observability     : {"enabled":true} (unchanged)

```

</details>

<details><summary><code>gen-credentials</code> — raw output (exit code 0)</summary>

```
::add-mask::ntwB8xdO7tRC-fUk
::add-mask::789693c4-1d4b-482e-9b93-571621172e15
generated a panel password (24-char class) and an app-style UUID — both masked in this log; only lengths are printed:
  password length: 16 | uuid length: 36

```

</details>

<details><summary><code>uuid</code> — raw output (exit code 0)</summary>

```
74f749f3-e7ec-4081-980a-3829a3b0b750

```

</details>

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
::notice title=part 1/1::%0A## Deploy — wrangler deploy --no-bundle%0A%0A```%0A$ npx wrangler deploy --config wrangler.cf-test.jsonc --no-bundle%0ATotal Upload: 482.45 KiB / gzip: 135.49 KiB%0AUploaded catpanel-test (0.91 sec)%0ADeployed catpanel-test triggers (0.41 sec)%0Ahttps://catpanel-test.zojfup.workers.dev%0A```%0A%0Aexit code 0 after 3s%0A
  route serves the Worker: true | 13s of polling after deploy
::notice title=part 1/1::- the workers.dev route answered with the Worker after **13s** of polling

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
::notice title=stage-A-min part 1/2::%0A## Stage A-min — mode open — 17/17 checks passed%0A%0Atrigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev%0A%0A| check | result | detail |%0A| --- | --- | --- |%0A| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true,"version":"6.67.0","kv":true} |%0A| /health carries the owner payload while the panel is OPEN (by design) | ✓ | {"ok":true,"version":"6.67.0","kv":true} |%0A| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsU |%0A| /api/diag is reachable without a key while the panel is OPEN | ✓ | 200 {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo": |%0A| KV really reads AND writes from this isolate (diag kv = read+write ok) | ✓ | read+write ok |%0A| /api/selfcheck says the handler is alive | ✓ | 200 {"ok":true,"version":"6.67.0","handler":"alive — /api/selfcheck?throw=1 throws on purpose to prove t |%0A| an exception becomes a JSON panel_crash report, not an edge error page | ✓ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET" |%0A| …and the report carries a stack + the crashed path | ✓ | /api/selfcheck |%0A| a panel password can be set AND persisted to KV | ✓ | 200 persisted=true |%0A| the panel closes for strangers everywhere once the password has propagated | ✓ | ~66s until 3 consecutive 401s (KV eventual consistency) |%0A| an anonymous /api/diag stays a real panel answer, open or closed | ✓ | 401 (open mode: 200 until the propagated lock closes the panel) |%0A| /api/diag with the owner key returns the report | ✓ | 200 |%0A| KV read+write is proven on the live account | ✓ | read+write ok |%0A| owner sees the full crash report on the live edge | ✓ | 500 |%0A| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |%0A| the subscription serves real configs from the live edge | ✓ | 200 bytes=38901 vless=27 |%0A%0A**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)%0A%0A    200  /health  {"ok":true,"version":"6.67.0","kv":true}%0A    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsUser":false,"kv":true,"open":true}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/selfcheck  {"ok":true,"version":"6.67.0","handler":"alive — /api/selfcheck?throw=1 throws on purpose to prove the crash report replaces «Error 1101»","diag":"unset"}%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    200  /api/settings  {"ok":true,"persisted":true,"ignored":[],"capped":[],"settings":{"title":"","lang":"fa","uiTheme":"aurora","ips":[],"repoAuto":true,"repoAutoDefaultMigrated":true,"useDefaults":true,"tlsPorts":["443","2053","2083"],"plai%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup
::notice title=stage-A-min part 2/2::.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}%0A    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:40:13.415Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre%0A    200  /sub/<redacted>?raw=1  vless://<redacted>@172.64.145.93:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%252Fvl%252FPUmCDtiu7GPhfINANeQcuDt%253Fed%253D2560&sni=catpanel-test.zojfup.workers.%0A    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S%0A%0A**wrangler tail** (112914 bytes captured)%0A%0A    {%0A        "wallTime": 71,%0A        "cpuTime": 5,%0A        "truncated": false,%0A        "executionModel": "stateless",%0A        "outcome": "ok",%0A        "scriptVersion": {%0A            "id": "34a4b81b-aa20-4d19-b42a-4af481a91eff"%0A
::notice::stage A-min — 17/17 checks passed, 1101=false

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
::notice title=part 1/1::%0A## Deploy — wrangler deploy (bundled by wrangler)%0A%0A```%0A$ npx wrangler deploy --config wrangler.cf-test.jsonc%0ATotal Upload: 634.94 KiB / gzip: 148.13 KiB%0AUploaded catpanel-test (2.43 sec)%0ADeployed catpanel-test triggers (0.79 sec)%0Ahttps://catpanel-test.zojfup.workers.dev%0A```%0A%0Aexit code 0 after 5s%0A
  route serves the Worker: true | 0s of polling after deploy
::notice title=part 1/1::- the workers.dev route answered with the Worker after **0s** of polling

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
::notice title=stage-A2-bundled part 1/1::%0A## Stage A2-bundled — mode locked — 12/12 checks passed%0A%0Atrigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev%0A%0A| check | result | detail |%0A| --- | --- | --- |%0A| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |%0A| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |%0A| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |%0A| the lock is observable on the edge answering these requests | ✓ | ~0s for the write to reach this colo |%0A| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| the old UUID is no longer a key once a password exists | ✓ | 401 |%0A| /api/diag with the owner key returns the report | ✓ | 200 |%0A| KV read+write is proven on the live account | ✓ | read+write ok |%0A| owner sees the full crash report on the live edge | ✓ | 500 |%0A| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |%0A| the subscription serves real configs from the live edge | ✓ | 200 bytes=49002 vless=30 |%0A%0A**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)%0A%0A    200  /health  {"ok":true}%0A    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}%0A    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:40:42.763Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre%0A    200  /sub/<redacted>?raw=1  vless://<redacted>@199.232.211.112:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%252Fvl%252FPUmCDtiu7GPhfINANeQcuDt%253Fed%253D2560&sni=catpanel-test.zojfup.worker%0A    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S%0A%0A**wrangler tail** (40795 bytes captured)%0A%0A    {%0A        "wallTime": 1,%0A        "cpuTime": 0,%0A        "truncated": false,%0A        "executionModel": "stateless",%0A        "outcome": "ok",%0A        "scriptVersion": {%0A            "id": "04c64c09-707f-4690-9c84-9a2104df237f"%0A
::notice::stage A2-bundled — 12/12 checks passed, 1101=false

# A2-bundled: 12/12 checks passed, 1101 seen: false

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
::notice title=part 1/1::%0A## Deploy — wrangler deploy --no-bundle%0A%0A```%0A$ npx wrangler deploy --config wrangler.cf-test.jsonc --no-bundle%0ATotal Upload: 482.45 KiB / gzip: 135.49 KiB%0AUploaded catpanel-test (1.99 sec)%0ADeployed catpanel-test triggers (0.38 sec)%0Ahttps://catpanel-test.zojfup.workers.dev%0A```%0A%0Aexit code 0 after 4s%0A
  route serves the Worker: true | 1s of polling after deploy
::notice title=part 1/1::- the workers.dev route answered with the Worker after **1s** of polling

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
::notice title=stage-B-obf part 1/1::%0A## Stage B-obf — mode locked — 12/12 checks passed%0A%0Atrigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev%0A%0A| check | result | detail |%0A| --- | --- | --- |%0A| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |%0A| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |%0A| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |%0A| the lock is observable on the edge answering these requests | ✓ | ~0s for the write to reach this colo |%0A| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| the old UUID is no longer a key once a password exists | ✓ | 401 |%0A| /api/diag with the owner key returns the report | ✓ | 200 |%0A| KV read+write is proven on the live account | ✓ | read+write ok |%0A| owner sees the full crash report on the live edge | ✓ | 500 |%0A| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |%0A| the subscription serves real configs from the live edge | ✓ | 200 bytes=49026 vless=30 |%0A%0A**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)%0A%0A    200  /health  {"ok":true}%0A    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"ATL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}%0A    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:41:11.165Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre%0A    200  /sub/<redacted>?raw=1  vless://<redacted>@198.41.200.128:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%252Fvl%252FPUmCDtiu7GPhfINANeQcuDt%253Fed%253D2560&sni=catpanel-test.zojfup.workers%0A    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S%0A%0A**wrangler tail** (40799 bytes captured)%0A%0A    {%0A        "wallTime": 132,%0A        "cpuTime": 7,%0A        "truncated": false,%0A        "executionModel": "stateless",%0A        "outcome": "ok",%0A        "scriptVersion": {%0A            "id": "3176bb28-458d-4785-875a-581826eb38bd"%0A
::notice::stage B-obf — 12/12 checks passed, 1101=false

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
::notice title=app-upload part 1/1::%0A## App-style upload (the same builder as CloudflareWorker.cfUploadWorker)%0A%0A- method/url: `PUT /accounts/<account>/workers/scripts/catpanel-test`%0A- headers: `Content-Type: multipart/form-data; boundary=…`, `Content-Length: 1253197`%0A- metadata: `{"main_module":"worker.js","bindings":[{"type":"plain_text","name":"UUID","text":"<plain_text: 36 chars, not printed>"},{"type":"secret_text","name":"PANEL_PASSWORD","text":"<secret_text: 16 chars, not printed>"},{"type":"kv_namespace","name":"CAT_KV","namespace_id":"<redacted>"}],"compatibility_date":"2025-03-04","compatibility_flags":["nodejs_compat"]}`%0A- body bytes: 1253197 (script 1252540 + multipart overhead 657)%0A- HTTP 200: `{ "result": { "created_on": "2026-10-12T14:38:29.622854Z", "modified_on": "2026-10-12T14:41:13.909432Z", "id": "catpanel-test", "tag": "b3b1af53d29948b59932c64f33f51b34", "entry_point": "worker.js", "tags": [], "deployment_id": "9cc75fd75394421da52795aa0b2cf21e", "tail_consumers": null, "logpush": f`%0A- workers.dev route: HTTP 200%0A

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
::notice title=stage-C-app-api part 1/1::%0A## Stage C-app-api — mode locked — 12/12 checks passed%0A%0Atrigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev%0A%0A| check | result | detail |%0A| --- | --- | --- |%0A| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |%0A| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |%0A| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |%0A| the lock is observable on the edge answering these requests | ✓ | ~0s for the write to reach this colo |%0A| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| the old UUID is no longer a key once a password exists | ✓ | 401 |%0A| /api/diag with the owner key returns the report | ✓ | 200 |%0A| KV read+write is proven on the live account | ✓ | read+write ok |%0A| owner sees the full crash report on the live edge | ✓ | 500 |%0A| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |%0A| the subscription serves real configs from the live edge | ✓ | 200 bytes=48980 vless=30 |%0A%0A**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)%0A%0A    200  /health  {"ok":true}%0A    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"ATL"},"bindings":["CAT_KV","PANEL_PASSWORD","UUID"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}%0A    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:41:45.535Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre%0A    200  /sub/<redacted>?raw=1  vless://<redacted>@151.101.100.167:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%252Fvl%252FuATVSrh0WCfNPvzZe7rRYtO%253Fed%253D2560&sni=catpanel-test.zojfup.worker%0A    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S%0A%0A**wrangler tail** (40800 bytes captured)%0A%0A    {%0A        "wallTime": 31,%0A        "cpuTime": 23,%0A        "truncated": false,%0A        "executionModel": "stateless",%0A        "outcome": "ok",%0A        "scriptVersion": {%0A            "id": "9cc75fd7-5394-421d-a527-95aa0b2cf21e"%0A
::notice::stage C-app-api — 12/12 checks passed, 1101=false

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
::notice title=part 1/1::%0A## Cleanup — verified through the Cloudflare API%0A%0A- DELETE /accounts/<account>/workers/scripts/catpanel-test → 200%0A- DELETE /accounts/<account>/storage/kv/namespaces/<redacted> → 200%0A- workers left on the account: (none) — `catpanel-test` gone: **true**%0A- KV namespaces left: (none) — `catpanel-test-kv` gone: **true**%0A

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
::notice title=summary part 1/1::%0A## Expected-vs-actual summary%0A%0A| stage | version | KV state | sub | checks | 1101? |%0A| --- | --- | --- | --- | --- | --- |%0A| A-min | 6.67.0 | read+write ok | ok | 17/17 | no |%0A| A2-bundled | 6.67.0 | read+write ok | ok | 12/12 | no |%0A| B-obf | 6.67.0 | read+write ok | ok | 12/12 | no |%0A| C-app-api | 6.67.0 | read+write ok | ok | 12/12 | no |%0A

all hard expectations held on the live account

```

</details>
