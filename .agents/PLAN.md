# Shared Agent Execution Plan

## Active Goal
Complete the 5-part user request using all 14 installed specialist skills:
1. Run full test suites and audits (`npm test`, `panel-click-audit.mjs`, `builder-link-repro.mjs`, `ui-preview-audit.test.mjs`, `android-guard.test.mjs`).
2. Fix Android service/UI crashes (`CatClientApplication`, `PsiphonCarrierService`, `PsiphonCarrierClient`, `PsiphonCarrierConfig`, `CatClientVpnService`, `MainActivity`) and declutter App & Panel UI.
3. Integrate WhiteDNS Resolver & DNS Tunnel methods (`DoQ`, `DoH3`, `DoT`, `DoH`, `UDP/53`, `TCP/53`, `Auto`, `HTTPS/SVCB`, `NULL 1232B`, `CottenMax`, `StormDNS`, `MasterDNS`, `Soar`, 9 Iran Auto-Tune presets, 52-server DNS catalog) in both Android App (`WhiteDnsSmartScanner.kt`, `MainActivity.kt`) and Cat Panel (`#wdnsCard` in `#v-scan`).
4. Launch live visual preview (`start_process` on `0.0.0.0:8787` + `present_file` on `docs/ui-redesign/preview.html`).
5. Deliver a comprehensive engineering & field analysis of what should change, what is redundant, what is missing, and what works on Iranian networks right now (MCI, Irancell, TCI/Fiber).

## Status
- [x] Install all 14 specialist skills in `.agents/skills/`
- [x] Fix Android service/UI crashes & Psiphon chained tunnel config (`Aether v2.4.0` parity)
- [x] Implement WhiteDNS Resolver & DNS Tunnel methods in App & Panel (`v6.69.26` / `v1.10.0-beta145`)
- [x] Pass all 116 Node tests (`npm test`), 221-element `panel-click-audit.mjs` (0 dead elements), 27-variant `builder-link-repro.mjs`, and 15-check `ui-preview-audit.test.mjs`
- [x] Start live preview server (`0.0.0.0:8787`) and commit/push to `arena/c5b36768-cat-client`
