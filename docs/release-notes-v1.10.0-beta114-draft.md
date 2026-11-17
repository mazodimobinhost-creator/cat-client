# Cat Client 1.10.0-beta114 — measured egress and route status

> TEST CANDIDATE: physical-device verification is pending.

- Measures the active Mihomo route's real Cloudflare trace through the mixed proxy and records IP, country, HTTP latency, and timestamp only on a stable validated network.
- Never infers egress country from server/config geography; unavailable values remain UNKNOWN.
- Shows a compact status row for every measured route.
- Keeps Worker named-proxy HTTP tests, dnstt evidence states, 30-second probe interval, per-network history, and the 20-sample bound.
- Does not add failover, protocols, or selection-policy changes.
