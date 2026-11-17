# Cat Client 1.10.0-beta321

Panel 6.92.0.

- Amnezia: ping is UDP handshake reply, not ICMP to Cloudflare (that was ~120 ms on every 8.6.112.x while WireGuard still failed 0/65).
- Amnezia: no 65-row delay test on WARP clones. If UDP is closed, the app says so instead of connecting dead rows.
