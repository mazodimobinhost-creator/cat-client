# Android failure report (caa29c0)


## files with compile errors
## log tail
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
FAIL paste-artifact
```

Paste artifact — the file the clipboard is supposed to carry

  ✓ the APK asset exists
  ✓ …and is byte-identical to dist-panel/catpanel.min.js (CI regenerates both)
  ✓ …and the two copies are not accidentally the same file
  ✓ 3 lines (marker · paste instruction · the whole code)
  ✓ line 1 is the version marker
  ✓ the code ends with a closing statement, not mid-token
  ✓ the asset declares the same version as the worker source
  ✓ raw size is in the paste range (300–600 KB)
  ✗ gzip stays under the 155 KB warning line  → 155074
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy uses the normal minified Worker asset
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …and the deploy asset replaces the APK worker after minification

paste artifact: 20 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:133:8
    at ModuleJob.run (node:internal/modules/esm/module_job:343:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
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
FAIL paste-artifact
```

Paste artifact — the file the clipboard is supposed to carry

  ✓ the APK asset exists
  ✓ …and is byte-identical to dist-panel/catpanel.min.js (CI regenerates both)
  ✓ …and the two copies are not accidentally the same file
  ✓ 3 lines (marker · paste instruction · the whole code)
  ✓ line 1 is the version marker
  ✓ the code ends with a closing statement, not mid-token
  ✓ the asset declares the same version as the worker source
  ✓ raw size is in the paste range (300–600 KB)
  ✗ gzip stays under the 155 KB warning line  → 155074
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy uses the normal minified Worker asset
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …and the deploy asset replaces the APK worker after minification

paste artifact: 20 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:133:8
    at ModuleJob.run (node:internal/modules/esm/module_job:343:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
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
FAIL paste-artifact
```

Paste artifact — the file the clipboard is supposed to carry

  ✓ the APK asset exists
  ✓ …and is byte-identical to dist-panel/catpanel.min.js (CI regenerates both)
  ✓ …and the two copies are not accidentally the same file
  ✓ 3 lines (marker · paste instruction · the whole code)
  ✓ line 1 is the version marker
  ✓ the code ends with a closing statement, not mid-token
  ✓ the asset declares the same version as the worker source
  ✓ raw size is in the paste range (300–600 KB)
  ✗ gzip stays under the 155 KB warning line  → 155074
  ✓ UTF-8 round-trips without losing a character
  ✓ CloudflareWorker points the paste asset at panels/catpanel.min.js
  ✓ …and reads it with a size+marker gate before anything is copied
  ✓ the copy button uses the paste artifact
  ✓ …with the big bundle only as a fallback
  ✓ …and the fallback is announced, not silent
  ✓ …and that function was actually found in the source
  ✓ the in-app API deploy uses the normal minified Worker asset
  ✓ the Android build copies the freshly built min into the APK assets
  ✓ …and commits it with the other snapshots
  ✓ the packaged APK is checked: the asset must be there and whole
  ✓ …and the deploy asset replaces the APK worker after minification

paste artifact: 20 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/paste-artifact.test.mjs:133:8
    at ModuleJob.run (node:internal/modules/esm/module_job:343:25)
    at async onImport.tracePromise.__proto__ (node:internal/modules/esm/loader:681:26)
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:117:5) {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
```
PASS secrets-tokens
PASS quicklink-version
PASS chain-tunnel
PASS sni-lab
PASS builder-link-repro
