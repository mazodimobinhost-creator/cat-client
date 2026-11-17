---
name: updater-everything-current
description: Use to check and update everything a project or server depends on: OS packages, language dependencies, Docker images, Android/Gradle, GitHub Actions, Cloudflare Wrangler, and proxy cores/panels (Xray, sing-box, 3x-ui, Marzban). Verifies current versions from real sources, updates safely, tests, and reports.
---

# Updater: keep everything current

## Rules
1. Never state "latest version" from memory. Look it up: package registry, `--outdated` commands, GitHub Releases/tags, official docs. Report the source and date you checked.
2. Update in a controlled way: backup or commit first, update in small groups, test after each group, keep a rollback path.
3. Security updates first. Then bug fixes. Then minor versions. Major versions only with a changelog review and a plan.
4. Read release notes/changelogs for breaking changes before updating. Summarize them.
5. Be careful with very new releases: for non-security updates prefer versions that have been out at least a week or two, to avoid freshly published bad or compromised releases. Verify checksums/signatures of downloaded binaries.
6. If the environment has no network, say so, and give the exact commands for the user to run instead of pretending.

## Step 1: Inventory
Find and list: `package.json`/lockfiles, `requirements*.txt`/`pyproject.toml`, `build.gradle(.kts)` and version catalog, `Dockerfile`/`docker-compose.yml`, `.github/workflows/*`, `wrangler.toml`, `.nvmrc`/`.tool-versions`, server services and binaries (Xray, sing-box, nginx, panel), OS version.

## Step 2: Check what is outdated
| Area | Commands |
|---|---|
| Node | `npm outdated`, `npm audit`, `npx npm-check-updates` (preview only) |
| Python | `pip list --outdated`, `pip-audit` |
| Android/Gradle | `./gradlew dependencyUpdates` (ben-manes plugin) or version-catalog check; also AGP, Kotlin, Gradle wrapper, compileSdk/targetSdk |
| Docker | `docker images`, compare tags with registry; rebuild with `--pull`; `docker scout`/`trivy` for vulnerabilities |
| OS (Debian/Ubuntu) | `apt update && apt list --upgradable`; `unattended-upgrades` status; reboot-required check |
| GitHub Actions | review each `uses:` version; Dependabot config for `github-actions` ecosystem |
| Cloudflare | `npm view wrangler version`, `wrangler --version`; check `compatibility_date` in `wrangler.toml` |
| Proxy cores/panels | `xray version`, `sing-box version`, panel version in UI/CLI; compare with GitHub Releases |
| Runtimes | Node LTS, Python, JDK, Kotlin versions vs. supported/LTS status |

## Step 3: Plan
Produce a table: component | current | available | type (security/minor/major) | risk | action. Group into batches: (1) security patches, (2) safe minor/patch, (3) major/risky. Ask for approval before major or risky batches.

## Step 4: Update and test
1. Commit or snapshot (git commit, server snapshot, config backup).
2. Apply one batch. Update lockfiles. For servers, update during low traffic and keep a second access path (console or second SSH session).
3. Build, lint, run tests, run the app. For servers: check service status, logs, ports, and a real client connection.
4. If tests fail, find which package caused it (bisect by reverting one at a time), fix or pin it, document why.
5. Repeat for the next batch.

## Step 5: Proxy and server specifics
- Xray/sing-box: download from the official GitHub release, verify the checksum, keep the old binary, restart the service, test a real connection, and confirm configs still validate (`xray run -test -config ...`, `sing-box check -c ...`). Config syntax changes between major versions, so read the notes.
- Panels (3x-ui, Marzban): back up the database and config first, update via the official method, check that inbound settings and subscriptions survive, confirm admin credentials/path are still hardened.
- Update GeoIP/GeoSite data files (geoip.dat, geosite.dat), including Iran-specific rule lists, on a schedule.
- Android cores (libXray/libbox): pin the version, rebuild all ABIs, test VPN start/stop and network switching.
- In Iran, downloads from GitHub or Docker Hub may fail. Use a working proxy or a domestic mirror and verify hashes.

## Step 6: Automate
Set up Dependabot or Renovate (grouped updates, weekly), a scheduled GitHub Action that runs tests and opens PRs, `unattended-upgrades` for security patches on servers, and a monthly reminder to review majors, runtimes, and cores. Add a simple health check after auto-updates and an alert on failure.

## Report
1. Checked on (date) and from which sources.
2. Table of components: before -> after, or "up to date".
3. Tests run and results.
4. What was skipped or blocked and why (breaking changes, no network, needs approval).
5. Rollback instructions and next scheduled check.
