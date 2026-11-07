### npm test failure excerpt
```
#   diff: 'simple'
# }
# Node.js v22.23.4
# Subtest: scripts/panels/quicklink-version.test.mjs
not ok 60 - scripts/panels/quicklink-version.test.mjs
  ---
  duration_ms: 403.865765
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/quicklink-version.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# Relay reference — fail closed, never fall back to a direct connection
#   ✓ a 🧦 entry exists to test with
#   ✓ its path carries a relay reference, not the credentials
#   ✓ the id is parseable
# (node:3256) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?rfc=build is not specified and it doesn't parse as CommonJS.
# Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
--
# ✓ en: my-internet check also opens the front
# ✓ en: no script errors
# 6 failure(s)
# Subtest: scripts/panels/vercel-doctor-ui.test.mjs
not ok 78 - scripts/panels/vercel-doctor-ui.test.mjs
  ---
  duration_ms: 3238.256228
  type: 'test'
  location: '/home/runner/work/cat-client/cat-client/scripts/panels/vercel-doctor-ui.test.mjs:1:1'
  failureType: 'testCodeFailure'
  exitCode: 1
  signal: ~
  error: 'test failed'
  code: 'ERR_TEST_FAILURE'
  ...
# ✓ vercel sub answers 200 with links
# ✓ every vercel line is :443
# ✓ every vercel line is TLS
# ✓ every vercel address is a Vercel edge address or the project host
# ✓ Host == project domain on every vercel line
# ✓ every vercel SNI is a *.vercel.app name
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
FAIL quicklink-version
```
(node:4001) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)

Quick links (?p=) and the app/panel version handshake

  ✓ a root link hops to the panel path without the secret
  ✓ a FAILED ?p= still redirects (302)
  ✓ …to a URL with no secret in it
  ✓ …carrying the stale flag instead
  ✓ …and no session cookie is set
  ✓ a correct ?p= sets the session cookie
  ✓ …and redirects to a clean URL too
  ✓ …keeping the params that are not secrets
  ✓ the login page answers the stale flag
  ✓ …without echoing the UUID into the page
  ✓ on a fresh install admin bootstrap quick link still signs the owner in
  ✓ …and the UUID does not stay in the URL
  ✓ with no app report there is no version banner
  ✓ a matching report shows no banner
  ✓ …but it is remembered (av/pv in KV)
  ✗ a skew renders the banner
  ✓ …naming both versions
  ✗ …and saying which side is older
  ✓ …with dismiss text that is translated in both languages
  ✗ …and the warning survives an open with no params at all (read back from KV)
  ✓ a stranger never sees the banner
  ✗ a newer app is reported as «panel is older»
  ✗ junk version params are neutralised  → 200
  ✓ …and cannot overwrite the last real report
  ✓ …while still staying short
  ✓ diag exposes the handshake
  ✓ …and names the app version it saw
  ✓ …with an explicit mismatch verdict
  ✓ the UUID can be rotated while a password exists
  ✓ the pre-rotation quick link is refused (and stripped)

quick links & version handshake: 25 passed, 5 FAILED

node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 5 check(s) failed

5 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/quicklink-version.test.mjs:190:8 {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 5,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.4
```
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
PASS devcop-parity
PASS bestping-custom
