### npm test failure excerpt
```
  duration_ms: 0.895536
  type: 'test'
  ...
# Subtest: panel repair resolves its Worker from the prerelease list too
not ok 9 - panel repair resolves its Worker from the prerelease list too
  ---
  duration_ms: 0.780399
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/app-prerelease-update.test.mjs:10:1'
  failureType: 'testCodeFailure'
  error: |-
    The input did not match the regular expression /readText\(RELEASES_URL/. Input:
    
    'suspend fun releaseAsset(name: String, maxBytes: Int): String {\n' +
      '        if (!name.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {\n' +
      '            throw IOException("GitHub asset name is invalid")\n' +
      '        }\n' +
      "        // GitHub's /releases/latest/download excludes pre-releases, exactly like\n" +
      '        // /releases/latest. Resolve the asset from the newest non-draft release\n' +
      '        // first, otherwise the repair/update buttons silently reinstall an old\n' +
      '        // Worker even though the APK itself is current. The list URL itself is\n' +
```
PASS cat-panel
PASS ccq
PASS ech
PASS panel-dom
PASS wizard
PASS multipart-upload
PASS chain-protocols
PASS android-guard
PASS link-shape
PASS panel-integrity
PASS kv-persistence
PASS panel-update-contract
PASS panel-selfupdate
PASS sni-host
PASS proxyip-sub
PASS proxyip-dataplane
PASS addr-port-query
PASS proxyip-feeds
PASS browser-probe
PASS ws-probe
PASS survival
PASS hostile-env
PASS content-type
PASS paste-artifact
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
PASS devcop-parity
PASS bestping-custom
PASS cat-panel
PASS ccq
PASS ech
FAIL panel-dom
```
node:internal/modules/package_json_reader:314
  throw new ERR_MODULE_NOT_FOUND(packageName, fileURLToPath(base), null);
        ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'jsdom' imported from /home/runner/work/cat-client/cat-client/scripts/panels/panel-dom.test.mjs
    at Object.getPackageJSONURL (node:internal/modules/package_json_reader:314:9)
    at packageResolve (node:internal/modules/esm/resolve:772:81)
    at moduleResolve (node:internal/modules/esm/resolve:859:18)
    at defaultResolve (node:internal/modules/esm/resolve:989:11)
    at #cachedDefaultResolve (node:internal/modules/esm/loader:747:20)
    at ModuleLoader.resolve (node:internal/modules/esm/loader:724:38)
    at ModuleLoader.getModuleJobForImport (node:internal/modules/esm/loader:320:38)
    at ModuleJob._link (node:internal/modules/esm/module_job:182:49) {
  code: 'ERR_MODULE_NOT_FOUND'
}

Node.js v22.23.4
```
PASS wizard
PASS multipart-upload
PASS chain-protocols
PASS android-guard
PASS link-shape
PASS panel-integrity
PASS kv-persistence
FAIL panel-update-contract
```
✗ could not build the minified artifact — run: npm i --no-save terser
node:internal/modules/package_json_reader:314
  throw new ERR_MODULE_NOT_FOUND(packageName, fileURLToPath(base), null);
        ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'acorn' imported from /home/runner/work/cat-client/cat-client/scripts/panels/minify.mjs
    at Object.getPackageJSONURL (node:internal/modules/package_json_reader:314:9)
    at packageResolve (node:internal/modules/esm/
```
PASS panel-selfupdate
PASS sni-host
PASS proxyip-sub
PASS proxyip-dataplane
PASS addr-port-query
PASS proxyip-feeds
FAIL browser-probe
```
node:internal/modules/package_json_reader:314
  throw new ERR_MODULE_NOT_FOUND(packageName, fileURLToPath(base), null);
        ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'jsdom' imported from /home/runner/work/cat-client/cat-client/scripts/panels/browser-probe.test.mjs
    at Object.getPackageJSONURL (node:internal/modules/package_json_reader:314:9)
    at packageResolve (node:internal/modules/esm/resolve:772:81)
    at moduleResolve (node:internal/modules/esm/resolve:859:18)
    at defaultResolve (node:internal/modules/esm/resolve:989:11)
    at #cachedDefaultResolve (node:internal/modules/esm/loader:747:20)
    at ModuleLoader.resolve (node:internal/modules/esm/loader:724:38)
    at ModuleLoader.getModuleJobForImport (node:internal/modules/esm/loader:320:38)
    at ModuleJob._link (node:internal/modules/esm/module_job:182:49) {
  code: 'ERR_MODULE_NOT_FOUND'
}

Node.js v22.23.4
```
FAIL ws-probe
```
node:internal/modules/package_json_reader:314
  throw new ERR_MODULE_NOT_FOUND(packageName, fileURLToPath(base), null);
        ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'jsdom' imported from /home/runner/work/cat-client/cat-client/scripts/panels/ws-probe.test.mjs
    at Object.getPackageJSONURL (node:internal/modules/package_json_reader:314:9)
    at packageResolve (node:internal/modules/esm/resolve:772:81)
    at moduleResolve (node:internal/modules/esm/resolve:859:18)
    at defaultResolve (node:internal/modules/esm/resolve:989:11)
    at #cachedDefaultResolve (node:internal/modules/esm/loader:747:20)
    at ModuleLoader.resolve (node:internal/modules/esm/loader:724:38)
    at ModuleLoader.getModuleJobForImport (node:internal/modules/esm/loader:320:38)
    at ModuleJob._link (node:internal/modules/esm/module_job:182:49) {
  code: 'ERR_MODULE_NOT_FOUND'
}

Node.js v22.23.4
```
PASS survival
FAIL hostile-env
```
  ✓ no bindings at all: no route throws out of fetch()
  ✓ env argument omitted entirely: no route throws out of fetch()
  ✓ …and the panel route still answers its setup screen
  ✓ …the health probe still answers ok
  ✓ …an owner-only API route answers 401, not 500
  ✓ …an unknown route is a neutral 404
  ✓ CAT_KV that throws on every call: no route throws out of fetch()
  ✓ …the panel still renders (a broken KV is not a crash)
  ✓ CAT_KV returning junk: no route throws out of fetch()
  ✓ …the panel still renders
  ✓ null/undefined/empty env values: no route throws out of fetch()
  ✓ …the panel still renders
  ✓ fresh install + anonymous crash → generic body, no stack (safer default)
  ✓ a request-time crash answers 500, not an escaped throw
  ✓ …and the owner gets the JSON report
  ✓ …carrying the message
  ✓ …and a real stack (a string, like err.stack, plus a split copy)
  ✓ …and it names the version so a stale deploy is visible
  ✓ …with the whole documented shape
  ✓ …as application/json and never cached
  ✓ configured panel + stranger + /api crash → plain generic JSON, no stack
  ✓ configured panel + owner → the report
  ✓ stranger + page crash → the neutral page, nothing about the panel
  ✓ PANEL_DIAG=1 forces the report for everyone (the escape hatch)
  ✓ PANEL_DIAG=0 suppresses it even for the owner
  ✓ …and the neutral API fallback leaks no error text
  ✓ the report never echoes the query string (the one-tap password lives there)
  ✓ /api/diag is owner-only (anonymous gets 401)
  ✓ the owner can sign in
  ✓ /api/diag answers the owner
  ✓ …it proves KV really reads and writes right now
  ✓ …it lists the bindings this isolate can see
  ✓ …it reports the running version
  ✓ …and it never returns the master UUID
  ✓ a KV that refuses every call is REPORTED as FAILED, not swallowed
  ✓ a missing KV binding is named, not hidden
  ✓ /api/selfcheck is owner-only (a stranger cannot even reach the throw)
  ✓ the owner gets a plain health answer without the flag
  ✓ ?throw=1 turns into the JSON crash report (this is the 1101 test)
  ✓ …and the report names the route it happened on
  ✓ a stranger cannot force the throw
node:internal/modules/package_json_reader:314
  throw new ERR_MODULE_NOT_FOUND(packageName, fileURLToPath(base), null);
        ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'acorn' imported from /home/runner/work/cat-client/cat-client/scripts/panels/hostile-env.test.mjs
    at Object.getPackageJSONURL (node:internal/modules/package_json_reader:314:9)
    at packageResolve (node:internal/modules/esm/resolve:772:81)
    at moduleResolve (node:internal/modules/esm/resolve:859:18)
    at defaultResolve (node:internal/modules/esm/resolve:989:11)
    at #cachedDefaultResolve (node:internal/modules/esm/loader:747:20)
    at ModuleLoader.resolve (node:internal/modules/esm/loader:724:38)
    at ModuleLoader.getModuleJobForImport (node:internal/modules/esm/loader:320:38)
    at onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:680:36)
    at TracingChannel.tracePromise (node:diagnostics_channel:350:14)
    at ModuleLoader.import (node:internal/modules/esm/loader:679:21) {
  code: 'ERR_MODULE_NOT_FOUND'
}

Node.js v22.23.4
```
PASS content-type
PASS paste-artifact
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
FAIL devcop-parity
```
(node:2566) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
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
node:internal/modules/package_json_reader:314
  throw new ERR_MODULE_NOT_FOUND(packageName, fileURLToPath(base), null);
        ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'jsdom' imported from /home/runner/work/cat-client/cat-client/scripts/panels/devcop-parity.test.mjs
    at Object.getPackageJSONURL (node:internal/modules/package_json_reader:314:9)
    at packageResolve (node:internal/modules/esm/resolve:772:81)
    at moduleResolve (node:internal/modules/esm/resolve:859:18)
    at defaultResolve (node:internal/modules/esm/resolve:989:11)
    at #cachedDefaultResolve (node:internal/modules/esm/loader:747:20)
    at ModuleLoader.resolve (node:internal/modules/esm/loader:724:38)
    at ModuleLoader.getModuleJobForImport (node:internal/modules/esm/loader:320:38)
    at onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:680:36)
    at TracingChannel.tracePromise (node:diagnostics_channel:350:14)
    at ModuleLoader.import (node:internal/modules/esm/loader:679:21) {
  code: 'ERR_MODULE_NOT_FOUND'
}

Node.js v22.23.4
```
FAIL bestping-custom
```
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
node:internal/modules/package_json_reader:314
  throw new ERR_MODULE_NOT_FOUND(packageName, fileURLToPath(base), null);
        ^

Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'jsdom' imported from /home/runner/work/cat-client/cat-client/scripts/panels/bestping-custom.test.mjs
    at Object.getPackageJSONURL (node:internal/modules/package_json_reader:314:9)
    at packageResolve (node:internal/modules/esm/resolve:772:81)
    at moduleResolve (node:internal/modules/esm/resolve:859:18)
    at defaultResolve (node:internal/modules/esm/resolve:989:11)
    at #cachedDefaultResolve (node:internal/modules/esm/loader:747:20)
    at ModuleLoader.resolve (node:internal/modules/esm/loader:724:38)
    at ModuleLoader.getModuleJobForImport (node:internal/modules/esm/loader:320:38)
    at onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:680:36)
    at TracingChannel.tracePromise (node:diagnostics_channel:350:14)
    at ModuleLoader.import (node:internal/modules/esm/loader:679:21) {
  code: 'ERR_MODULE_NOT_FOUND'
}

Node.js v22.23.4
```
