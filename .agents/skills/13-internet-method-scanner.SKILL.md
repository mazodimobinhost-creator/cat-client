---
name: internet-method-scanner
description: Use to find out what actually works right now for open internet access in Iran. Searches GitHub and community sources for current methods, tools, and configs, vets them, tests them safely, and adds the working ones to a catalog. Run on request and on a schedule.
---

# Internet method scanner

## Goal
Answer: "Which methods work today, on which operator, and how well?" Then keep a catalog the other skills can use. Never claim a method works without a test; never present old reports as current.

## Requirements and limits
- Needs web/GitHub access. If unavailable, say so and give the user the exact search queries and commands to run, or ask them to paste results.
- Real tests must come from inside Iran, on the actual operator. From a foreign sandbox you can only vet code, read reports, and validate config syntax. Say which you did.
- Do not claim you can see YouTube/Instagram UI; test reachability and throughput only.

## Scan procedure
1. **Collect candidates** (sources, newest first):
   - GitHub: releases and issues of Xray-core, sing-box, Hiddify, v2rayNG, NekoBox, Marzban, 3x-ui, Psiphon, Tor/Snowflake, Cloudflare clean-IP scanners, fragment/noise tools, DoH tools, DNS-bypass projects. Search issue trackers for "Iran", "Irancell", "MCI", "Rightel", "Shatel", "Mobinnet", "fragment", "Reality", "blocked", and look at the last 2-4 weeks.
   - GitHub search by topic and recency: `topic:xray`, `topic:v2ray`, `topic:sing-box`, `topic:censorship-circumvention`, sorted by recently updated.
   - Measurement and status: OONI, Cloudflare Radar, IODA, ntc.party, project discussions and release notes.
   - Community channels the user provides.
2. **Vet each candidate before touching it** (see below). Drop anything suspicious.
3. **Classify** by layer it defeats (DNS, SNI/DPI, IP block, throttling, protocol fingerprint, whitelist mode) and by type (client, core, panel, scanner, DNS, bridge).
4. **Test safely** (see below), per operator.
5. **Score and record** in `METHODS.md`; feed the winners to `iran-it-admin-mode` and `iran-internet-circumvention`.
6. **Report** a ranked list with evidence and date.

## Vetting checklist (supply-chain safety)
- Prefer official repos and releases of well-known projects. Check owner identity, stars and age, last commit/release date, open issues, number of maintainers, license.
- Be suspicious of: brand-new repos with huge claims, obfuscated code, binaries without source, unusual install scripts (`curl | bash`), requests for broad permissions, tools asking for your keys, "free VPN" repos with embedded server lists.
- Read the code or at least the install script, network calls, and dependencies. Verify release checksums/signatures.
- Run unknown code only in a sandbox/VM/container without secrets and with no access to personal files. Never run it on the production server or the user's main machine.
- Do not use public "free config" lists as a trusted path: the owners can log or tamper with traffic. If used for testing connectivity, use disposable identities and never log in to real accounts. Prefer generating your own configs for your own server.
- Never add third-party servers to the user's subscription without telling them the risk.
- Respect licenses; do not copy code without attribution.

## Test procedure
For each viable method, on each operator:
1. Record: date/time, operator, city, connection type, client+version, protocol/transport/port/SNI, options (fragment, noise, mux).
2. Layered checks: DNS, TCP, TLS, first HTTP byte.
3. Targets: neutral site, Google, YouTube (page load + bytes transferred), Instagram, Telegram, GitHub, Cloudflare, Docker Hub, the user's own domain.
4. Metrics: latency (median of 10), loss, 20-second download Mbps, stability over 5-10 minutes, reconnects, battery/CPU on phone if relevant.
5. Repeat at a different hour; blocking and throttling vary by time.
6. Provide a small script/commands the user can run locally (curl through the proxy with `--proxy socks5h://127.0.0.1:PORT`, speed test file download, a loop writing CSV) when you cannot test yourself.

## Catalog (`METHODS.md`)
Table columns: method | tool+version | source URL | last checked | operator | result (works / partial / fails) | speed | notes | confidence (tested by us / reported by others / unverified).
Mark entries older than 14 days as "stale" and re-test. Remove entries that fail twice.

## Scoring
Score 0-5 on: works now, speed, stability, simplicity for users, safety/trust of the code, maintenance activity, ease of rotation when blocked. Recommend a primary and two fallbacks, from different families (for example Reality-TCP, CDN-WebSocket with fragment, Hysteria2).

## Schedule (if automation exists)
Run weekly (daily during instability): refresh candidates, re-test catalog, update the updater's version list, alert the user when the primary method fails or a better one appears.

## Report format
1. Date and where tests ran (inside Iran? which operator?).
2. Ranked table: method, evidence, score.
3. Newly found and rejected (with the reason).
4. Not tested and why.
5. Recommended action now.
