# Android failure report (d00b7fd)


## files with compile errors
## log tail
### step18.log
✓ ?addr=ip#DE: clash server is the bare ip (no # in the YAML)
✓ ?addr=ip#DE: sing-box server is the bare ip
✓ ?addr=ip#DE + strict country DE: the pinned address survives
✓ ?addr=ip:99999: no «[1.2.3.4:99999]» garbage
✓ ?addr=ip:99999: falls back to the bare host on the panel ports
✓ ?addr=ip:2053: every config keeps the verified port 2053
✓ ?addr=[v6]:8443: the pin survives, bracketed once
✓ ?addr=bare v6: survives untouched (not mistaken for host:port)
✓ ?addr=mix of junk + one ip: only the real ip is served
✓ ?addr=all junk: the panel address list takes over (never an empty sub)
ADDR/PORT QUERY TESTS PASSED
(node:3618) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=aq-unit is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ syntax check passed
✓ md-daily parses validated rows
✓ md-daily drops risk > 5 (the CAPTCHA tail)
✓ md-daily drops junk cells
✓ md-daily on garbage → empty, no throw
✓ txt «ip port» lines keep their verified port
✓ txt space lines are port-pinned like ip:port
✓ txt space form == colon form
✓ refresh merges md-daily + space-txt feeds (dedup incl. duplicate table rows)
✓ daily IPs reach the pool port-pinned 443
✓ high-risk IP never reaches the pool
✓ space-txt IPs keep verified ports + repo country tag
✓ country import writes the selected ProxyIPs into the active list
✓ reselecting a country is actionable but reports zero real additions
(node:3633) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js?panel=pxfeeds is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ country import persists addresses and country metadata
✓ DEFAULT_PROXY_REPOS carries the daily source
✓ default repo list still fits the cap (≤10)
PROXYIP FEED TESTS PASSED
✓ survival apply → ok + changed list
✓ survival returns the survive link
(node:3652) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
✓ preset: daily rotation
✓ preset: safe SNI = Host (spoofing/rotation off)
✓ preset: fragment on
✓ preset: plain ports on
✓ preset: both protocols
✓ preset: defaults + host + health ordering
✓ preset is idempotent (nothing left to change)
✓ survival without apply → 400
✓ settings dialed back (test setup)
✓ seed: 3 ips with provenance latency
✓ baseline sub: plain configs OFF (as configured)
✓ baseline sub: vless OFF, trojan only (as configured)
✓ baseline sub: original panel order
✓ survive: plain ports forced back on
✓ survive: both protocols forced back on
✓ survive: SNI pinned to worker Host (not spoofed)
✓ survive: scanner-proven fast address stays first
✓ survive applies to Base64 format
✓ survive applies to Clash/Mihomo format
✓ survive applies to sing-box format
✓ survive applies to Xray format
✓ survive render did NOT persist anything
✓ health ordering: 40ms leads, 120ms next, unknown last
✓ health ordering expires stale scans after 7 days
✓ health ordering carries domain Ping through DNS-to-IP mapping
✓ telemetry: users carry lastOnline (panel shows «never connected»)

SNI Lab — live SNI testing (panel 6.67.4)

(node:3664) [MODULE_TYPELESS_PACKAGE_JSON] Warning: Module type of file:///home/runner/work/cat-client/cat-client/app/src/main/assets/panels/catclient.worker.js is not specified and it doesn't parse as CommonJS.
Reparsing as ES module because module syntax was detected. This incurs a performance overhead.
To eliminate this warning, add "type": "module" to /home/runner/work/cat-client/cat-client/package.json.
(Use `node --trace-warnings ...` to show where the warning was created)
  ✓ an anonymous caller gets 401 (the table describes the owner's network)
  ✓ …and cannot trigger the subrequests either
  ✓ an empty candidate list is rejected, not answered with a fake table
  ✓ a fresh panel reports an empty run, not an invented one
  ✓ …and the defaults it ships are listed for the page to offer
  ✓ …and the effective pool it will save into
  ✓ the run reports one row per unique, well-formed candidate
  ✓ a Cloudflare-fronted hostname is marked cf
  ✓ …the trace body alone is enough when the server header is stripped
  ✓ a plain origin is NOT marked cf, and its status is reported
  ✓ garbage and the worker's own hostname never reach the network
  ✓ every probe was a /cdn-cgi/trace fetch, timed
  ✓ the run is persisted under its own KV key (the panel can show it after a reload)
  ✓ …and read back unchanged
  ✓ a long list is capped at 12 probes per run
  ✓ the settings page carries the lab card (test / edge / save)
  ✓ …with both button labels and the hint in the panel language
  ✓ …and the same keys exist in English (no untranslated row)
  ✓ the browser probe is cross-origin and no-cors (a readable cross-origin GET is impossible)
  ✓ …and it reads its candidates from the pool field plus the defaults
  ✓ the saved-list button only offers rows that passed BOTH verdicts
  ✗ the lab never writes settings by itself — saving goes through the settings form  → save path changed
  ✓ a run changes the lab record but NEVER the settings (the pool is only written by the form)
  ✓ the settings payload advertises the defaults so the page can offer them

sni lab: 23 passed, 1 FAILED
node:internal/modules/run_main:123
    triggerUncaughtException(
    ^

AssertionError [ERR_ASSERTION]: 1 checks failed

1 !== 0

    at file:///home/runner/work/cat-client/cat-client/scripts/panels/sni-lab.test.mjs:229:8 {
  generatedMessage: false,
  code: 'ERR_ASSERTION',
  actual: 1,
  expected: 0,
  operator: 'strictEqual',
  diff: 'simple'
}

Node.js v22.23.3
