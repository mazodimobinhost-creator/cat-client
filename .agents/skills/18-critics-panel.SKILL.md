---
name: critics-panel
description: Use after any significant work (code, design, config, plan, architecture, report). A panel of critics with different viewpoints reviews it and gives blunt, specific feedback, then a fixer applies the improvements and re-checks. Use to catch weak spots before the user does.
---

# Critics panel and fixer

## How it works
1. **Author** presents the work with its goal and what was verified.
2. **Critics** each review from their own viewpoint, independently, without seeing the others' notes first. Each must give specific, evidence-based findings, not general praise or vague complaints.
3. **Fixer** collects all findings, deduplicates, ranks them, fixes what is justified, and explains what was rejected and why.
4. **Re-review**: critics check the fixed version. Repeat up to 3 rounds or until no High/Critical items remain.
5. **Record** findings and decisions in `OPEN_ISSUES.md` and `DECISIONS.md`.

## The critics
- **Skeptic (correctness)**: "Where is this wrong?" Logic errors, wrong assumptions, unverified claims, missing tests, invented facts.
- **Security critic**: secrets, auth, injection, exposed ports, risky permissions, supply chain, logs with personal data.
- **User critic**: confusing flows, unclear text, missing states, too many steps, Persian wording quality, accessibility, a beginner's view.
- **Designer critic**: hierarchy, spacing, consistency, contrast, RTL issues, "does it look premium or template-like?".
- **Ops critic**: what breaks at 3 a.m.? Monitoring, rollback, backups, Cloudflare behavior, rate limits, costs, single points of failure.
- **Network critic (Iran)**: what if Cloudflare/GitHub/Google are blocked or throttled? Fallbacks, mirrors, dependency on foreign services, behavior on bad mobile networks.
- **Maintainer critic**: readability, duplication, naming, complexity, docs, ease of updating, test coverage.
- **Performance critic**: payload sizes, slow queries, blocking calls, battery use, startup time.
- **Devil's advocate**: argues for a completely different approach and asks "do we need this at all?".
Pick the critics that fit the task; for big work use all.

## Rules for critics
- Be direct and specific: location, problem, why it matters, suggested fix, severity.
- Severity: Critical (breaks/harms), High (likely problem), Medium (should fix), Low (polish), Question (needs info).
- Back claims with evidence (line, output, screenshot, doc). Say "unsure" when unsure.
- No flattery. Do mention what is solid, briefly, so it is not accidentally changed.
- Criticize the work, not the author. Stay on the goal the user cares about.

## Rules for the fixer
- Fix Critical and High first, then Medium. Do not refactor beyond the findings.
- For every finding: Accepted (with the change), Rejected (with reasoning), or Needs user decision.
- After fixing, re-run tests/builds/screenshots to verify. Do not claim improvement without checking.
- Resolve critic conflicts with a test or measurable criterion; escalate to the user only for real trade-offs.

## Report format
**Verdict**: ship / ship after fixes / rework.
**Top issues**: ranked list with critic, severity, location, fix status.
**Fixed in this round**: with verification.
**Rejected or deferred**: reason.
**Needs your decision**: options and a recommendation.
**Strengths to keep**: 2-3 bullets.
**Next round**: what will be re-checked.

## Triggers to run the panel
Before any release or deploy, after large features, after design work, after security-relevant changes, after incidents, and on a weekly schedule for the whole project.
