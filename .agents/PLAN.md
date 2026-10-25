# Shared Agent Execution Plan

## Active Goal
Maintain and evolve Cat Client (`v1.10.0-beta146`) and Cat Panel (`v6.69.27`) using all **19 installed specialist skills** (`01` through `19`):
1. Run full test suites and audits (`npm test`, `panel-click-audit.mjs`, `builder-link-repro.mjs`, `ui-preview-audit.test.mjs`, `android-guard.test.mjs`).
2. Fix Android service/UI crashes (`CatClientApplication`, `PsiphonCarrierService`, `PsiphonCarrierClient`, `PsiphonCarrierConfig`, `CatClientVpnService`, `MainActivity`) and declutter App & Panel UI.
3. Integrate WhiteDNS Resolver & DNS Tunnel methods (`DoQ`, `DoH3`, `DoT`, `DoH`, `UDP/53`, `TCP/53`, `Auto`, `HTTPS/SVCB`, `NULL 1232B`, `CottenMax`, `StormDNS`, `MasterDNS`, `Soar`, Iran Auto-Tune presets, DNS catalog) in both Android App (`WhiteDnsSmartScanner.kt`, `MainActivity.kt`) and Cat Panel (`#wdnsCard` in `#v-scan`).
4. Deliver dedicated **SOCKS5 Live Hub** (`ProxyScannerPage.kt`, `MainActivity.kt`, `#v-socks` in `catclient.worker.js`) with 7 live feeds + 4-mirror GitHub API fallback + 62 built-in seeds + auto-refresh on open + 1-tap direct & pool failover connect, plus **Regional Firewall Fingerprinter**, **Auto-MTU & Carrier Finder**, and **Hybrid Iran IP + Sanction-Bypass DNS**.
5. Install and activate all **19 specialist skills** (`01`–`19`), including Project Manager & Reporter (`15`), Problem Analyst (`16`), DevOps & Cloudflare Guardian (`17`), Critics Panel (`18`), and Overseer & Advisor (`19`).

## Status
- [x] Install all 19 specialist skills (`01`–`19`) in `.agents/skills/` and maintain `.agents/STATUS.md`
- [x] Fix Android service/UI crashes & Psiphon chained tunnel config (`Aether v2.4.0` parity)
- [x] Implement WhiteDNS Resolver & DNS Tunnel methods in App & Panel (`v6.69.26` / `v1.10.0-beta145`)
- [x] Implement Dedicated SOCKS5 Live Hub + Auto-MTU & Carrier Finder + Regional Firewall Fingerprinter + Hybrid Iran IP & Sanction DNS (`v6.69.27` / `v1.10.0-beta146`)
- [x] Pass all Node tests, 221-element `panel-click-audit.mjs` (0 dead elements), and Android CI release `v1.10.0-beta146`
