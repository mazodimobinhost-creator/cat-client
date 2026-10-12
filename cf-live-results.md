# Cloudflare live test — catpanel-test

run=38263007130 attempt=1 trigger=push actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client head=b0c04c026834a933915747061b791377dc6bf895

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
created KV namespace: 4752bc9361cd4c2f9e41f8e7f7baf919
KV_ID=4752bc9361cd4c2f9e41f8e7f7baf919
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
::add-mask::84x3coqYH9_Rqss0
::add-mask::2c448b2e-7ddd-4e0c-8a90-23e835e7f03b
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
Uploaded catpanel-test (0.98 sec)
Deployed catpanel-test triggers (0.50 sec)
https://catpanel-test.zojfup.workers.dev
```

exit code 0 after 3s

<details><summary><code>deploy</code> — raw output (exit code 0)</summary>

```
# wrangler deploy --config wrangler.cf-test.jsonc --no-bundle | main = dist-panel/catpanel.min.js

 ⛅️ wrangler 4.149.0
────────────────────
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Worker Startup Time: 2 ms
Your Worker has access to the following bindings:
Binding                                                Resource                  
env.CAT_KV (<redacted>)          KV Namespace              
env.OPEN_PANEL ("true")                                Environment Variable      
env.OPEN_SUB ("true")                                  Environment Variable      

Uploaded catpanel-test (0.98 sec)
Deployed catpanel-test triggers (0.50 sec)
  https://catpanel-test.zojfup.workers.dev
Current Version ID: 7647e89b-c726-49e9-b807-303b832b0d42
deploy exit: 0
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Uploaded catpanel-test (0.98 sec)
Deployed catpanel-test triggers (0.50 sec)
  https://catpanel-test.zojfup.workers.dev
::notice title=part 1/1::%0A## Deploy — wrangler deploy --no-bundle%0A%0A```%0A$ npx wrangler deploy --config wrangler.cf-test.jsonc --no-bundle%0ATotal Upload: 482.45 KiB / gzip: 135.49 KiB%0AUploaded catpanel-test (0.98 sec)%0ADeployed catpanel-test triggers (0.50 sec)%0Ahttps://catpanel-test.zojfup.workers.dev%0A```%0A%0Aexit code 0 after 3s%0A

```

</details>

## Stage A-min — mode open — 5/17 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✗ | 404 <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <! |
| /health carries the owner payload while the panel is OPEN (by design) | ✗ | <!DOCTYPE html>
<!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> |
| /api/version reports 6.67.0 (the file loaded and ran) | ✗ | 404 <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="n |
| /api/diag is reachable without a key while the panel is OPEN | ✗ | 404 <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if I |
| KV really reads AND writes from this isolate (diag kv = read+write ok) | ✗ | (no kv field) |
| /api/selfcheck says the handler is alive | ✗ | 404 <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if I |
| an exception becomes a JSON panel_crash report, not an edge error page | ✗ | 404 <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang |
| …and the report carries a stack + the crashed path | ✗ |  |
| a panel password can be set AND persisted to KV | ✗ | 404 persisted=undefined |
| the panel closes for strangers everywhere once the password has propagated | ✗ | still open after 154s |
| an anonymous /api/diag stays a real panel answer, open or closed | ✓ | 200 (open mode: 200 until the propagated lock closes the panel) |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✗ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Hand |
| /api/backup masks secrets by default | ✗ | 200 secrets=masked hash= |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=39025 vless=27 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    404  /health  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"
    404  /api/version  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"
    404  /api/diag  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"
    404  /api/selfcheck  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"
    404  /api/selfcheck?throw=1  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"
    404  /api/settings  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"
    404  /api/diag  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:30:37.709Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they
    200  /sub/<redacted>?raw=1  vless://<redacted>@[2606:4700:d0::a29f:c001]:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zoj
    302  /  

**wrangler tail** (148736 bytes captured)

    {
        "wallTime": 656,
        "cpuTime": 10,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "7647e89b-c726-49e9-b807-303b832b0d42"

<details><summary><code>stage</code> — raw output (exit code 0)</summary>

```
…(truncated; showing the last 9000 characters)
n  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"%0A    404  /api/diag  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"%0A    404  /api/selfcheck  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"%0A    404  /api/selfcheck?throw=1  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"%0A    404  /api/settings  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]-
::notice title=stage-A-min part 2/3::-> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"%0A    404  /api/diag  <!DOCTYPE html> <!--[if lt IE 7]> <html class="no-js ie6 oldie" lang="en-US"> <![endif]--> <!--[if IE 7]> <html class="no-js ie7 oldie" lang="en-US"> <![endif]--> <!--[if IE 8]> <html class="no-js ie8 oldie" lang="en-US"%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM
::notice title=stage-A-min part 3/3::"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":
::notice::stage A-min — 5/17 checks passed, 1101=false

# A-min: 5/17 checks passed, 1101 seen: false

```

</details>

## Deploy — wrangler deploy (bundled by wrangler)

```
$ npx wrangler deploy --config wrangler.cf-test.jsonc
Total Upload: 634.94 KiB / gzip: 148.13 KiB
Uploaded catpanel-test (2.56 sec)
Deployed catpanel-test triggers (0.66 sec)
https://catpanel-test.zojfup.workers.dev
```

exit code 0 after 5s

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

Uploaded catpanel-test (2.56 sec)
Deployed catpanel-test triggers (0.66 sec)
  https://catpanel-test.zojfup.workers.dev
Current Version ID: 027724a8-4293-4330-a9e3-7736b0ae415d
deploy exit: 0
Total Upload: 634.94 KiB / gzip: 148.13 KiB
Uploaded catpanel-test (2.56 sec)
Deployed catpanel-test triggers (0.66 sec)
  https://catpanel-test.zojfup.workers.dev
::notice title=part 1/1::%0A## Deploy — wrangler deploy (bundled by wrangler)%0A%0A```%0A$ npx wrangler deploy --config wrangler.cf-test.jsonc%0ATotal Upload: 634.94 KiB / gzip: 148.13 KiB%0AUploaded catpanel-test (2.56 sec)%0ADeployed catpanel-test triggers (0.66 sec)%0Ahttps://catpanel-test.zojfup.workers.dev%0A```%0A%0Aexit code 0 after 5s%0A

```

</details>

## Stage A2-bundled — mode locked — 6/12 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true,"version":"6.67.0","kv":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✗ | {"ok":true,"version":"6.67.0","kv":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsU |
| the lock is observable on the edge answering these requests | ✗ | still open after 155s |
| /api/diag refuses an anonymous caller (401) | ✗ | 200 {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime |
| the old UUID is no longer a key once a password exists | ✗ | 200 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✗ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Hand |
| /api/backup masks secrets by default | ✗ | 200 secrets=masked hash= |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=49102 vless=30 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true,"version":"6.67.0","kv":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsUser":false,"kv":true,"open":true}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"STL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"STL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"STL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"BNA"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:33:41.477Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they
    200  /sub/<redacted>?raw=1  vless://<redacted>@104.17.118.103:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.workers
    302  /  

**wrangler tail** (162429 bytes captured)

    {
        "wallTime": 1,
        "cpuTime": 0,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "027724a8-4293-4330-a9e3-7736b0ae415d"

<details><summary><code>stage</code> — raw output (exit code 0)</summary>

```
…(truncated; showing the last 9000 characters)
rsion":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zo
::notice title=stage-A2-bundled part 2/3::jfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"STL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"STL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"STL"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"BNA"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag
::notice title=stage-A2-bundled part 3/3::  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:33:41.477Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they%0A    200  /sub/<redacted>?raw=1  vless://<redacted>@104.17.118.103:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%252Fvl%252FPUmCDtiu7GPhfINANeQcuDt%253Fed%253D2560&sni=catpanel-test.zojfup.workers%0A    302  /  %0A%0A**wrangler tail** (162429 bytes captured)%0A%0A    {%0A        "wallTime": 1,%0A        "cpuTime": 0,%0A        "truncated": false,%0A        "executionModel": "stateless",%0A        "outcome": "ok",%0A        "scriptVersion": {%0A            "id": "027724a8-4293-4330-a9e3-7736b0ae415d"%0A
::notice::stage A2-bundled — 6/12 checks passed, 1101=false

# A2-bundled: 6/12 checks passed, 1101 seen: false

```

</details>

## Deploy — wrangler deploy --no-bundle

```
$ npx wrangler deploy --config wrangler.cf-test.jsonc --no-bundle
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Uploaded catpanel-test (2.33 sec)
Deployed catpanel-test triggers (0.42 sec)
https://catpanel-test.zojfup.workers.dev
```

exit code 0 after 4s

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

Uploaded catpanel-test (2.33 sec)
Deployed catpanel-test triggers (0.42 sec)
  https://catpanel-test.zojfup.workers.dev
Current Version ID: 9f44ba09-2638-46ce-9d43-120d97cc818f
deploy exit: 0
Total Upload: 482.45 KiB / gzip: 135.49 KiB
Uploaded catpanel-test (2.33 sec)
Deployed catpanel-test triggers (0.42 sec)
  https://catpanel-test.zojfup.workers.dev
::notice title=part 1/1::%0A## Deploy — wrangler deploy --no-bundle%0A%0A```%0A$ npx wrangler deploy --config wrangler.cf-test.jsonc --no-bundle%0ATotal Upload: 482.45 KiB / gzip: 135.49 KiB%0AUploaded catpanel-test (2.33 sec)%0ADeployed catpanel-test triggers (0.42 sec)%0Ahttps://catpanel-test.zojfup.workers.dev%0A```%0A%0Aexit code 0 after 4s%0A

```

</details>

## Stage B-obf — mode locked — 6/12 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true,"version":"6.67.0","kv":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✗ | {"ok":true,"version":"6.67.0","kv":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsU |
| the lock is observable on the edge answering these requests | ✗ | still open after 154s |
| /api/diag refuses an anonymous caller (401) | ✗ | 200 {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime |
| the old UUID is no longer a key once a password exists | ✗ | 200 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✗ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Hand |
| /api/backup masks secrets by default | ✗ | 200 secrets=masked hash= |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=49134 vless=30 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true,"version":"6.67.0","kv":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsUser":false,"kv":true,"open":true}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:36:44.249Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they
    200  /sub/<redacted>?raw=1  vless://<redacted>@104.18.37.247:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPUmCDtiu7GPhfINANeQcuDt%3Fed%3D2560&sni=catpanel-test.zojfup.workers.
    302  /  

**wrangler tail** (166806 bytes captured)

    {
        "wallTime": 196,
        "cpuTime": 9,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "9f44ba09-2638-46ce-9d43-120d97cc818f"

<details><summary><code>stage</code> — raw output (exit code 0)</summary>

```
…(truncated; showing the last 9000 characters)
ag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.
::notice title=stage-B-obf part 2/3::workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"o
::notice title=stage-B-obf part 3/3::k":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:36:44.249Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they%0A    200  /sub/<redacted>?raw=1  vless://<redacted>@104.18.37.247:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%252Fvl%252FPUmCDtiu7GPhfINANeQcuDt%253Fed%253D2560&sni=catpanel-test.zojfup.workers.%0A    302  /  %0A%0A**wrangler tail** (166806 bytes captured)%0A%0A    {%0A        "wallTime": 196,%0A        "cpuTime": 9,%0A        "truncated": false,%0A        "executionModel": "stateless",%0A        "outcome": "ok",%0A        "scriptVersion": {%0A            "id": "9f44ba09-2638-46ce-9d43-120d97cc818f"%0A
::notice::stage B-obf — 6/12 checks passed, 1101=false

# B-obf: 6/12 checks passed, 1101 seen: false

```

</details>

## App-style upload (the same builder as CloudflareWorker.cfUploadWorker)

- method/url: `PUT /accounts/<account>/workers/scripts/catpanel-test`
- headers: `Content-Type: multipart/form-data; boundary=…`, `Content-Length: 1245074`
- metadata: `{"main_module":"worker.js","bindings":[{"type":"plain_text","name":"UUID","text":"<plain_text: 36 chars, not printed>"},{"type":"secret_text","name":"PANEL_PASSWORD","text":"<secret_text: 16 chars, not printed>"},{"type":"kv_namespace","name":"CAT_KV","namespace_id":"<redacted>"}],"compatibility_date":"2025-03-04","compatibility_flags":["nodejs_compat"]}`
- body bytes: 1245074 (script 1244417 + multipart overhead 657)
- HTTP 200: `{ "result": { "created_on": "2026-10-12T14:27:41.56951Z", "modified_on": "2026-10-12T14:36:47.078971Z", "id": "catpanel-test", "tag": "26f7f45fdf6f4316b4357218bc975786", "entry_point": "worker.js", "tags": [], "deployment_id": "82c73da194bd434998c00224b545c787", "tail_consumers": null, "logpush": fa`
- workers.dev route: HTTP 200

<details><summary><code>upload-app</code> — raw output (exit code 0)</summary>

```
app-style upload (byte-for-byte the same builder as CloudflareWorker.cfUploadWorker):
  url        : PUT https://api.cloudflare.com/client/v4/accounts/<account>/workers/scripts/catpanel-test
  metadata   : {"main_module":"worker.js","bindings":[{"type":"plain_text","name":"UUID","text":"<plain_text: 36 chars, not printed>"},{"type":"secret_text","name":"PANEL_PASSWORD","text":"<secret_text: 16 chars, not printed>"},{"type":"kv_namespace","name":"CAT_KV","namespace_id":"<redacted>"}],"compatibility_date":"2025-03-04","compatibility_flags":["nodejs_compat"]}
  body bytes : 1245074 (script 1244417 + multipart overhead 657 )
  HTTP 200 { "result": { "created_on": "2026-10-12T14:27:41.56951Z", "modified_on": "2026-10-12T14:36:47.078971Z", "id": "catpanel-test", "tag": "26f7f45fdf6f4316b4357218bc975786", "entry_point": "worker.js", "tags": [], "deployment_id": "82c73da194bd434998c00224b545c787", "tail_consumers": null, "logpush": false, "has_assets": false, "has_modules": true, "etag": "<redacted 44 chars>
  workers.dev route enabled: 200 {"enabled":true,"previews_enabled":false}
::notice title=app-upload part 1/1::%0A## App-style upload (the same builder as CloudflareWorker.cfUploadWorker)%0A%0A- method/url: `PUT /accounts/<account>/workers/scripts/catpanel-test`%0A- headers: `Content-Type: multipart/form-data; boundary=…`, `Content-Length: 1245074`%0A- metadata: `{"main_module":"worker.js","bindings":[{"type":"plain_text","name":"UUID","text":"<plain_text: 36 chars, not printed>"},{"type":"secret_text","name":"PANEL_PASSWORD","text":"<secret_text: 16 chars, not printed>"},{"type":"kv_namespace","name":"CAT_KV","namespace_id":"<redacted>"}],"compatibility_date":"2025-03-04","compatibility_flags":["nodejs_compat"]}`%0A- body bytes: 1245074 (script 1244417 + multipart overhead 657)%0A- HTTP 200: `{ "result": { "created_on": "2026-10-12T14:27:41.56951Z", "modified_on": "2026-10-12T14:36:47.078971Z", "id": "catpanel-test", "tag": "26f7f45fdf6f4316b4357218bc975786", "entry_point": "worker.js", "tags": [], "deployment_id": "82c73da194bd434998c00224b545c787", "tail_consumers": null, "logpush": fa`%0A- workers.dev route: HTTP 200%0A

```

</details>

## Stage C-app-api — mode locked — 11/12 checks passed

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
| /api/backup masks secrets by default | ✗ | 200 secrets=masked hash= |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=49040 vless=30 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","PANEL_PASSWORD","UUID"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:37:09.443Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they
    200  /sub/<redacted>?raw=1  vless://<redacted>@162.159.138.110:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPZAh9Aq93lZPWaKMAKcGrj3%3Fed%3D2560&sni=catpanel-test.zojfup.worker
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

**wrangler tail** (40829 bytes captured)

    {
        "wallTime": 17,
        "cpuTime": 13,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "82c73da1-94bd-4349-98c0-0224b545c787"

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
  · passwordSource / uuidSource    : env / the UUID variable
  · usage / subToken / app         : {"day":"2026-10-12","reqs":74,"writes":3,"errors":[{"t":1791815804058,"k":"unhandled","d":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if you can read this JSON, the 1101 handler works","n":4}],"pending":0,"noKv":false} / not set (links use the master UUID) / {"appVersion":"the app has not reported one yet","panelBundled":"the app has not reported one yet","seenAt":null,"match":"unknown"}
  ✓ owner sees the full crash report on the live edge  → 500
  ✓ a stranger is refused BEFORE the throw (401, no report)  → 401 {"ok":false,"error":"unauthorized"}
  ✗ /api/backup masks secrets by default  → 200 secrets=masked hash=
  ✓ the subscription serves real configs from the live edge  → 200 bytes=49040 vless=30
  · GET / → 200 (<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name=)

  raw responses:
    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","PANEL_PASSWORD","UUID"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:37:09.443Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they
    200  /sub/<redacted>?raw=1  vless://<redacted>@162.159.138.110:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FPZAh9Aq93lZPWaKMAKcGrj3%3Fed%3D2560&sni=catpanel-test.zojfup.worker
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

--- wrangler tail (C-app-api) — 40829 bytes captured ---
{
    "wallTime": 17,
    "cpuTime": 13,
    "truncated": false,
    "executionModel": "stateless",
    "outcome": "ok",
    "scriptVersion": {
        "id": "82c73da1-94bd-4349-98c0-0224b545c787"
    },
    "scriptName": "catpanel-test",
    "diagnosticsChannelEvents": [],
    "exceptions": [],
::notice title=stage-C-app-api part 1/1::%0A## Stage C-app-api — mode locked — 11/12 checks passed%0A%0Atrigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev%0A%0A| check | result | detail |%0A| --- | --- | --- |%0A| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |%0A| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |%0A| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |%0A| the lock is observable on the edge answering these requests | ✓ | ~0s for the write to reach this colo |%0A| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| the old UUID is no longer a key once a password exists | ✓ | 401 |%0A| /api/diag with the owner key returns the report | ✓ | 200 |%0A| KV read+write is proven on the live account | ✓ | read+write ok |%0A| owner sees the full crash report on the live edge | ✓ | 500 |%0A| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |%0A| /api/backup masks secrets by default | ✗ | 200 secrets=masked hash= |%0A| the subscription serves real configs from the live edge | ✓ | 200 bytes=49040 vless=30 |%0A%0A**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)%0A%0A    200  /health  {"ok":true}%0A    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    401  /api/diag  {"ok":false,"error":"unauthorized"}%0A    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"MEM"},"bindings":["CAT_KV","PANEL_PASSWORD","UUID"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users%0A    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y%0A    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}%0A    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T14:37:09.443Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they%0A    200  /sub/<redacted>?raw=1  vless://<redacted>@162.159.138.110:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%252Fvl%252FPZAh9Aq93lZPWaKMAKcGrj3%253Fed%253D2560&sni=catpanel-test.zojfup.worker%0A    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S%0A%0A**wrangler tail** (40829 bytes captured)%0A%0A    {%0A        "wallTime": 17,%0A        "cpuTime": 13,%0A        "truncated": false,%0A        "executionModel": "stateless",%0A        "outcome": "ok",%0A        "scriptVersion": {%0A            "id": "82c73da1-94bd-4349-98c0-0224b545c787"%0A
::notice::stage C-app-api — 11/12 checks passed, 1101=false

# C-app-api: 11/12 checks passed, 1101 seen: false

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
  DELETE worker  : 200 {"id":"26f7f45fdf6f4316b4357218bc975786"}
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
| A-min | — | read+write ok | ok | 5/17 | no |
| A2-bundled | 6.67.0 | read+write ok | ok | 6/12 | no |
| B-obf | 6.67.0 | read+write ok | ok | 6/12 | no |
| C-app-api | 6.67.0 | read+write ok | ok | 11/12 | no |

<details><summary><code>summary</code> — raw output (exit code 1)</summary>

```
| stage | version | KV state | sub | checks | 1101? |
| --- | --- | --- | --- | --- | --- |
| A-min | — | read+write ok | ok | 5/17 | no |
| A2-bundled | 6.67.0 | read+write ok | ok | 6/12 | no |
| B-obf | 6.67.0 | read+write ok | ok | 6/12 | no |
| C-app-api | 6.67.0 | read+write ok | ok | 11/12 | no |
::notice title=summary part 1/1::%0A## Expected-vs-actual summary%0A%0A| stage | version | KV state | sub | checks | 1101? |%0A| --- | --- | --- | --- | --- | --- |%0A| A-min | — | read+write ok | ok | 5/17 | no |%0A| A2-bundled | 6.67.0 | read+write ok | ok | 6/12 | no |%0A| B-obf | 6.67.0 | read+write ok | ok | 6/12 | no |%0A| C-app-api | 6.67.0 | read+write ok | ok | 11/12 | no |%0A

HARD FAILURES:
  ✗ A-min: /health answers ok:true (200 JSON)
  ✗ A-min: /health carries the owner payload while the panel is OPEN (by design)
  ✗ A-min: /api/version reports 6.67.0 (the file loaded and ran)
  ✗ A-min: /api/diag is reachable without a key while the panel is OPEN
  ✗ A-min: KV really reads AND writes from this isolate (diag kv = read+write ok)
  ✗ A-min: /api/selfcheck says the handler is alive
  ✗ A-min: an exception becomes a JSON panel_crash report, not an edge error page
  ✗ A-min: …and the report carries a stack + the crashed path
  ✗ A-min: a panel password can be set AND persisted to KV
  ✗ A-min: the panel closes for strangers everywhere once the password has propagated
  ✗ A-min: a stranger is refused BEFORE the throw (401, no report)
  ✗ A-min: /api/backup masks secrets by default
  ✗ A2-bundled: /health is bare for an anonymous caller (no version, no kv field)
  ✗ A2-bundled: the lock is observable on the edge answering these requests
  ✗ A2-bundled: /api/diag refuses an anonymous caller (401)
  ✗ A2-bundled: the old UUID is no longer a key once a password exists
  ✗ A2-bundled: a stranger is refused BEFORE the throw (401, no report)
  ✗ A2-bundled: /api/backup masks secrets by default
  ✗ B-obf: /health is bare for an anonymous caller (no version, no kv field)
  ✗ B-obf: the lock is observable on the edge answering these requests
  ✗ B-obf: /api/diag refuses an anonymous caller (401)
  ✗ B-obf: the old UUID is no longer a key once a password exists
  ✗ B-obf: a stranger is refused BEFORE the throw (401, no report)
  ✗ B-obf: /api/backup masks secrets by default
  ✗ C-app-api: /api/backup masks secrets by default

```

</details>
