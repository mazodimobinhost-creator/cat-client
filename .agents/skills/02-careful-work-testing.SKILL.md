---
name: careful-work-testing
description: Use for every coding, config, or deployment task. Enforces precise work, real testing, honest reporting, and no invented results.
---

# Careful, verified work

## Core rules
1. Never claim something works unless you ran it and saw the result. If you could not run it, say exactly that: "written but not tested, because X".
2. Never invent output, logs, versions, URLs, IPs, API fields, or config keys. If unsure, look it up in docs or the actual code, or say you are unsure.
3. Read before you edit. Open the real files, understand the current structure, then change the minimum needed. Do not rewrite working code without a reason.
4. If the request is ambiguous in a way that changes the result, ask one short question. Otherwise state your assumption and proceed.

## Working loop
1. **Understand**: restate the goal in one line. List constraints (platform, versions, what must not break).
2. **Plan**: short numbered steps. Identify risky steps (deletes, migrations, deploys, secrets).
3. **Implement** in small steps. After each step, run something that proves it (build, unit test, curl, linter, type check).
4. **Test**: happy path, empty input, wrong input, big input, network failure, timeout, concurrent use, and the exact bug that was reported.
5. **Review your own diff** like a stranger would: leftover debug code, hardcoded secrets, unhandled errors, wrong edge cases.
6. **Report** honestly (format below).

## When something fails
- Read the full error and the first stack frame in your own code. Do not guess-and-patch repeatedly.
- Form one hypothesis, test it with a small experiment, then change code. If two attempts fail, step back and re-read assumptions.
- Never hide a failure by deleting the test, catching and ignoring the exception, or loosening a check just to turn it green.
- Keep a short log of what you tried so you do not loop.

## Testing checklist by area
- **Backend / Worker / API**: status codes, error bodies, auth failures, rate limits, malformed JSON, idempotency, timeouts.
- **Frontend**: loading/empty/error states, RTL layout, small screens, slow network.
- **Android**: rotation, process death, no network, permission denied, low-end device, release (minified) build, not only debug.
- **CI / GitHub Actions**: run on a clean checkout; check secrets are referenced by name only; check caches do not mask failures.
- **Config files**: validate syntax with a real parser (jq, yamllint, JSON schema, the tool's own `check`/`test` command) before delivering.

## Network-dependent testing honesty
- If the environment has no internet or blocked domains, say so. Do not pretend you tested against a real server, YouTube, Instagram, Google, or Cloudflare.
- Test connectivity explicitly (DNS resolve, TCP connect, TLS handshake, HTTP status) and report each layer separately. See the network skill for commands.
- Results from one network (one ISP, one location) do not prove anything about another. Say which network you tested from.

## Safety
- Back up or make a git commit before risky changes. Prefer reversible steps.
- Never print or commit secrets. Mask tokens in logs (show only last 4 chars).
- Ask before destructive or irreversible actions (dropping data, force push, deleting infrastructure, rotating live credentials).

## Final report format
- **Done**: what changed (files/functions).
- **Verified**: exactly what you ran and the observed result.
- **Not verified**: what you could not test and why.
- **Risks / next steps**: anything the user must watch or do manually.
Keep it short and concrete. No vague words like "should work".
