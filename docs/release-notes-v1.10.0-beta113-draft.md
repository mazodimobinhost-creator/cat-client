# Cat Client 1.10.0-beta113 — real route measurement

> **DRAFT — NOT RELEASED.** Physical-device sign-off is pending.

## CI-only until device results are returned
- Worker profile measurements use Mihomo's named-proxy HTTP delay endpoint; a TCP port or panel health cannot produce WORKS.
- Timeout, missing HTTP response, and non-successful HTTP probes are failures on a stable validated network.
- Captive, unvalidated, or changed networks are INCONCLUSIVE and are not written to history.
- dnstt has separate NOT_CONFIGURED, ORBOT_OFF, UNKNOWN, WORKS, and FAILED reporting. SOCKS success without explicit transport evidence remains UNKNOWN.
- Smart Route history remains isolated by network and route and bounded to 20 samples.
- Live card shows network type, unambiguous operator or UNKNOWN, active route, measured egress fields, latency, and measurement time. Missing values stay UNKNOWN.

## Physical-device verification
- **Pending:** Wi-Fi Direct/Worker/profile tests.
- **Pending:** mobile-data Direct/Worker/profile tests.
- **Pending:** captive portal and network-change behavior.
- **Pending:** Orbot/dnstt state matrix.
- **Pending:** broken Worker must be FAILED.
- **Pending:** no background probe while the page/app is closed.

The release must not be created until the blocking physical scenarios pass and `BETA113_DEVICE_SIGNOFF` is explicitly enabled.
