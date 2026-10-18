# NOVA X and referenced-video review

Reviewed 2026-10-18. Architecture/backlog review only; no third-party code is copied into Cat.

## Sources

- `NOVA-X-PANEL/NOVA-X-PANEL`, main at `e0931b3` when reviewed.
- YouTube `rW_B_Aiw7bs` (2026-10-17): NOVA X installation, Iran/foreign tunnel, Telegram shop and reseller walkthrough.
- YouTube `aNnTCzKej-Q` (2026-10-17): a separate MFPanel/Katabump deployment walkthrough.

## License and trust

NOVA X carries GPL-3.0 and is a large 3x-ui descendant, not a drop-in Cat Railway component. A direct derivative requires GPL-compatible distribution, preserved notices and corresponding source. Cat will independently implement selected ideas instead of importing or rebranding it.

The MFPanel video asks viewers to upload unspecified Telegram-distributed files to a free third-party host. It does not establish provenance, license, reproducible builds, update signatures or credential custody. Cat must not automate or recommend that supply chain. The public password shown in the video must not be reused. Video performance and operator claims are demonstrations, not Cat evidence.

## Useful NOVA X capabilities

- Xray multi-protocol inbounds, REALITY/XTLS, AmneziaWG, TUIC v5 and MTProto.
- Per-client quota, expiry, renewal and accounting.
- Multi-node management and traffic reconciliation.
- SQLite for small installations and PostgreSQL for larger/multi-location installations.
- Automated certificate lifecycle with persistent ACME state.
- Telegram shop plus administrator and reseller roles.
- Automated Iran/foreign FRP and GRE tunnel management.
- Backup/restore and migration handling.
- Explicit Go memory budgets and container health controls.
- Fail2ban with explicit NET_ADMIN/NET_RAW requirements.
- Userspace AmneziaWG without a host kernel module.
- Offline security image, race tests and master/node end-to-end tests referenced in repository history.

## Risks and Cat constraints

- Railway cannot be assumed to provide GRE, host networking, iptables capabilities or stable inbound UDP. Capability checks must precede those controls.
- A public Railway deployment is not equivalent to the two-VPS setup shown in the NOVA video.
- Telegram commerce adds payment, authorization, replay, audit and personal-data obligations and must remain separate from tunnel control.
- Example database credentials are development placeholders and must never reach public deployment.
- Root installers need pinned versions, checksums, dry-run and rollback.
- Node country metadata does not prove egress country; Cat retains fresh egress probes.

## Independent Cat backlog

1. Railway/container privilege report before GRE, FRP, firewall or UDP controls.
2. Optional PostgreSQL while retaining SQLite for a single node.
3. Least-privilege reseller scopes and immutable audit events.
4. Signed, versioned node enrollment and quota/expiry reconciliation.
5. Certificate renewal status, timestamp and alerts without resetting certificates.
6. Memory-budget and backup-integrity health checks.
7. Userspace AmneziaWG feasibility as a separately licensed sidecar.
8. A separate threat model before any commerce/payment feature.

## Explicit non-adoptions

- No Telegram-distributed MFPanel binary/archive.
- No copied or rebranded NOVA/3x-ui source.
- No claim that free hosting or Iran tunnels work permanently.
- No automatic GRE/iptables activation without proven host capability.
