# Cloudflare live test — catpanel-test

run=38258095029 attempt=1 trigger=push actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client head=5be5d4337410f1bd9346364e08596e445e54a3dd

Everything in this file was produced by a real Cloudflare account. Only two
names are ever written: the Worker `catpanel-test` and the KV namespace `catpanel-test-kv`.

## Stage A-min — mode open — 11/17 checks passed — ⚠ 1101 IN A RESPONSE BODY

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true,"version":"6.67.0","kv":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✗ | {"ok":true,"version":"6.67.0","kv":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsU |
| /api/diag is reachable without a key while the panel is OPEN | ✓ | 200 {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo": |
| KV really reads AND writes from this isolate (diag kv = read+write ok) | ✓ | read+write ok |
| /api/selfcheck says the handler is alive | ✓ | 200 {"ok":true,"version":"6.67.0","handler":"alive — /api/selfcheck?throw=1 throws on purpose to prove t |
| an exception becomes a JSON panel_crash report, not an edge error page | ✓ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET" |
| …and the report carries a stack + the crashed path | ✓ | /api/selfcheck |
| a panel password can be set AND persisted to KV | ✓ | 200 persisted=true |
| /api/diag refuses an anonymous caller (401) | ✗ | 200 {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime |
| the old UUID is no longer a key once a password exists | ✗ | 200 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✗ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Hand |
| /api/backup masks secrets by default | ✗ | 200 secrets=masked hash= |
| the subscription serves real configs from the live edge | ✗ | 404 bytes=9 vless=0 |

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
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T13:47:52.514Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they
    404  /sub/6ada0fce-55e5-4cc7-8b25-aa50d8c16b5e?raw=1  not found
    302  /  

**wrangler tail** (45482 bytes captured)

    {
        "wallTime": 226,
        "cpuTime": 6,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "58be0a95-3ae3-42dc-9ca6-3f42f87fb687"

## Stage A2-bundled — mode locked — 5/11 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true,"version":"6.67.0","kv":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✗ | {"ok":true,"version":"6.67.0","kv":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsU |
| /api/diag refuses an anonymous caller (401) | ✗ | 200 {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime |
| the old UUID is no longer a key once a password exists | ✗ | 200 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✗ | 500 {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Hand |
| /api/backup masks secrets by default | ✗ | 200 secrets=masked hash= |
| the subscription serves real configs from the live edge | ✗ | 404 bytes=9 vless=0 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true,"version":"6.67.0","kv":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","repo":"https://github.com/mazodimobinhost-creator/cat-client","needsUser":false,"kv":true,"open":true}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":false,"panelPath":"panel","users
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T13:48:26.358Z","secrets":"masked","masked":["uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secrets stay as they
    404  /sub/6ada0fce-55e5-4cc7-8b25-aa50d8c16b5e?raw=1  not found
    302  /  

**wrangler tail** (22642 bytes captured)

    {
        "wallTime": 19,
        "cpuTime": 6,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "92cbb632-cc11-41b3-9ce6-4b44a11669a0"

## Stage B-obf — mode locked — 10/11 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |
| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| the old UUID is no longer a key once a password exists | ✓ | 401 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |
| the subscription serves real configs from the live edge | ✗ | 404 bytes=9 vless=0 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","OPEN_PANEL","OPEN_SUB"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T13:48:59.982Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    404  /sub/6ada0fce-55e5-4cc7-8b25-aa50d8c16b5e?raw=1  not found
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

**wrangler tail** (27149 bytes captured)

    {
        "wallTime": 235,
        "cpuTime": 6,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "eba9194a-3667-48f9-8b4f-2c32c1297e3d"

## Stage C-app-api — mode locked — 11/11 checks passed

trigger=push repo=mazodimobinhost-creator/cat-client actor=mazodimobinhost-creator ref=arena/c5b36768-cat-client url=https://catpanel-test.zojfup.workers.dev

| check | result | detail |
| --- | --- | --- |
| /health answers ok:true (200 JSON) | ✓ | 200 {"ok":true} |
| /health is bare for an anonymous caller (no version, no kv field) | ✓ | {"ok":true} |
| /api/version reports 6.67.0 (the file loaded and ran) | ✓ | 200 {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false} |
| /api/diag refuses an anonymous caller (401) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| the old UUID is no longer a key once a password exists | ✓ | 401 |
| /api/diag with the owner key returns the report | ✓ | 200 |
| KV read+write is proven on the live account | ✓ | read+write ok |
| owner sees the full crash report on the live edge | ✓ | 500 |
| a stranger is refused BEFORE the throw (401, no report) | ✓ | 401 {"ok":false,"error":"unauthorized"} |
| /api/backup masks secrets by default | ✓ | 200 secrets=masked hash=***MASKED-BY-CAT-PANEL** |
| the subscription serves real configs from the live edge | ✓ | 200 bytes=49166 vless=30 |

**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)

    200  /health  {"ok":true}
    200  /api/version  {"ok":true,"panel":"cat-panel","version":"6.67.0","needsUser":false,"kv":true,"open":false}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    401  /api/diag  {"ok":false,"error":"unauthorized"}
    200  /api/diag  {"ok":true,"version":"6.67.0","host":"catpanel-test.zojfup.workers.dev","runtime":{"cf":true,"colo":"EWR"},"bindings":["CAT_KV","PANEL_PASSWORD","UUID"],"kv":"read+write ok","passwordSet":true,"panelPath":"panel","users"
    500  /api/selfcheck?throw=1  {"ok":false,"success":false,"error":"panel_crash","title":"Worker Exception Handled","version":"6.67.0","at":"/api/selfcheck","method":"GET","message":"PANEL SELF-TEST: deliberate throw from /api/selfcheck?throw=1 — if y
    401  /api/selfcheck?throw=1  {"ok":false,"error":"unauthorized"}
    200  /api/backup  {"ok":true,"version":"6.67.0","exportedAt":"2026-10-12T13:49:25.267Z","secrets":"masked","masked":["passwordHash","uuid"],"restoreNote":"Fields equal to ***MASKED-BY-CAT-PANEL*** are skipped by restore, so the live secre
    200  /sub/3c6a28dc-dea8-40ed-9bfb-e3f7e6eed7f7?raw=1  vless://3c6a28dc-dea8-40ed-9bfb-e3f7e6eed7f7@162.159.138.110:443?encryption=none&security=tls&type=ws&host=catpanel-test.zojfup.workers.dev&path=%2Fvl%2FUelB9Wb5HCA4NDmkB5PEbwL%3Fed%3D2560&sni=catpanel-test.zojfup.worker
    200  /  <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"> <meta name="generator" content="Hugo 0.121.2"><meta name="robots" content="noindex"> <title>S

**wrangler tail** (40822 bytes captured)

    {
        "wallTime": 28,
        "cpuTime": 15,
        "truncated": false,
        "executionModel": "stateless",
        "outcome": "ok",
        "scriptVersion": {
            "id": "f8e9eec4-0903-42c1-ac16-1c6406dae01c"

## Cleanup — verified through the Cloudflare API

- DELETE /accounts/<account>/workers/scripts/catpanel-test → 200
- DELETE /accounts/<account>/storage/kv/namespaces/8b63f59ff4ae40f6bc46291147e09896 → 200
- workers left on the account: (none) — `catpanel-test` gone: **true**
- KV namespaces left: (none) — `catpanel-test-kv` gone: **true**

## Expected-vs-actual summary

| stage | version | KV state | sub | checks | 1101? |
| --- | --- | --- | --- | --- | --- |
| A-min | 6.67.0 | read+write ok | NO | 11/17 | YES |
| A2-bundled | 6.67.0 | read+write ok | NO | 5/11 | no |
| B-obf | 6.67.0 | read+write ok | NO | 10/11 | no |
| C-app-api | 6.67.0 | read+write ok | ok | 11/11 | no |
