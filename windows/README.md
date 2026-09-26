# Cat Client for Windows

Desktop version of Cat Client — the same purple/black/white identity, made for
Windows with no install and no dependencies (Python standard library only).

## Run (fastest)

Double-click `run.bat` (or `python main.py` from this folder). Works with any
Python 3.10+ from python.org or the Microsoft Store.

## Download (built exe)

Every GitHub Release of the main Cat Client repo with a `CatClient-Windows-*.zip`
asset contains a ready `CatClient.exe` built by GitHub Actions
(`.github/workflows/windows.yml`). Unzip and run — no Python needed.

## What it does

- **Subscriptions** — fetch any v2rayNG/V2Box subscription URL or paste links;
  copy raw links, subscription text or Clash skeleton; share the same list with
  every user who needs a config.
- **Clean-IP scanner** — two-stage probe (TCP, then TLS + `/cdn-cgi/trace`)
  across Cloudflare ranges plus the built-in clean-IP library, with real country
  flags per edge location; copy the best IPs for the Cat Panel.
- **Free configs** — public community sources with their own credits.
- **Test** — measure TCP/TLS latency of any config link before sharing.
- **Cat configs** — build `🐱 Cat · country · protocol · port · flag` labelled
  VLESS/Trojan links for the Cat Panel (same labels as the Android app).

Cat Panel deployments still go through `wrangler deploy` as documented in the
main repository README; this Windows app works alongside the panel.
