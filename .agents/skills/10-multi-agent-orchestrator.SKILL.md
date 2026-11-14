---
name: multi-agent-orchestrator
description: Use for any project with more than one part (app, backend, panel, infra, security). Splits work into specialist roles, runs them in parallel where the platform allows, makes them review each other, and keeps a shared written record so work continues without the user. Use for audits, upgrades, big features, and recurring maintenance.
---

# Multi-agent orchestrator

## Honest limits first
- This skill defines roles, a process, and a shared record. Whether roles run as real parallel agents, and whether work continues while the user is away, depends on what the platform offers (sub-agents, scheduled tasks, background runs, automations). Check which exist. If none, simulate the roles one after another in the same session and say so.
- Without a scheduler or background runner, nothing happens "when the user is not there". Tell the user how to enable it (scheduled task, GitHub Action cron, automation) or set it up yourself if you have access.

## Roles (one focus each; never let one role hold everything)
1. **Lead / Planner**: reads the goal, splits the work, assigns roles, resolves conflicts, decides done.
2. **Programmer(s)**: one per area (Android, backend/Worker, panel/web, scripts). Follow `precise-programmer`.
3. **Reviewer**: reads every diff critically, looks for logic errors, regressions, style problems. Must not review its own code.
4. **Tester / QA**: writes and runs tests, tries to break things, checks edge cases, RTL and mobile.
5. **Bug Hunter**: runs `autonomous-bug-hunter`.
6. **Security reviewer**: runs `security-review` on every change touching auth, secrets, network, CI.
7. **Designer**: runs `app-panel-designer` for UI work.
8. **Network/Iran specialist**: runs `iran-internet-circumvention`, `iran-it-admin-mode`, and `internet-method-scanner`.
9. **Updater**: runs `updater-everything-current`.
10. **Scribe**: keeps the shared record and the final report.
Use fewer roles for small tasks; merge roles, never skip Reviewer and Tester.

## Shared record (the team's memory)
Keep these files in the repo (e.g. `.agents/`) and update them every round:
- `PLAN.md`: goal, tasks, owner role, status (todo / doing / review / done / blocked).
- `LOG.md`: dated entries: who did what, commands run, results.
- `DECISIONS.md`: choices made and why, so nobody redoes the debate.
- `OPEN_ISSUES.md`: bugs and risks with severity, owner, status.
Never store secrets in these files.

## Round loop
1. Lead reads the records and the repo state. Picks the highest-value tasks.
2. Roles work independently on separate files/branches to avoid conflicts. One branch per task; small commits.
3. **Cross-review**: each change is reviewed by a different role. Reviewer writes findings; author fixes or argues with evidence (a test, a doc link, a log).
4. **Debate rule**: if two roles disagree, each states the claim, the evidence, and a way to test it. Run the test; the result decides. Lead breaks ties and records it in `DECISIONS.md`.
5. Tester runs the full suite; Security reviews risky diffs; Designer checks UI.
6. Lead merges only when: build green, tests green, review approved, no new High/Critical findings.
7. Scribe writes the round summary. Repeat until goals are met or a blocker needs the user.

## Autonomous maintenance (when scheduling exists)
Run on a schedule (e.g. daily or weekly): Updater checks versions, Bug Hunter scans and fixes, Tester runs everything, Security scans for secrets and vulnerable dependencies, Scanner re-tests connectivity methods. Rules for unattended work:
- Work on a branch and open a PR; do not push to main.
- Auto-fix only low-risk items (patch updates, lint, clear bugs with tests). Anything risky (major upgrades, auth changes, deletions, infrastructure, credentials) waits for the user, listed in `OPEN_ISSUES.md`.
- Never rotate, delete, or deploy production credentials or infrastructure without approval.
- Stop and report if tests fail twice in a row, or if something unexpected appears (new secrets in repo, failing deploy, strange traffic).
- Send a short summary (Telegram/email/PR comment): what changed, what was verified, what needs a decision.

## Quality gates (all must pass before "done")
Builds from clean checkout; tests pass; lint/type check pass; no secrets in diff; reviewed by another role; security checked for relevant changes; UI checked at 360px and RTL; release build tested for Android; docs and records updated.

## Rules
- No role claims results it did not produce. Every claim links to a command output, test, or file.
- Keep rounds small and reversible. Prefer many small safe changes over one huge one.
- Be concise in the final report to the user: done, verified, not verified, needs decision.
