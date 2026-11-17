#!/usr/bin/env python3
"""Fail the build when a panel login is sent without a username.

The Cat Panel worker rejects `/api/login` when it was deployed with a panel
username and the request carries only a password (see `checkLogin()` in
app/src/main/assets/panels/catclient.worker.js). Six copies of that call existed
in the app and every one of them dropped the username, so on such a panel
nothing could be pushed to the panel — clean IPs, SNI and chain updates all
failed with «wrong password».

Every `/api/login` payload must therefore be built by PanelCredentials
(which always includes the username field) or mention a username explicitly.

Usage: python3 scripts/panel-login-guard.py   (exit 1 on violations)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"

LOGIN_LINE = re.compile(r"""["']/api/login["']""")
ALLOWED = re.compile(r"PanelCredentials\.loginPayload|PASSWORD_ONLY_OK")


def main() -> int:
    violations = []
    checked = 0
    for path in sorted(SRC.rglob("*.kt")):
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if not LOGIN_LINE.search(line):
                continue
            checked += 1
            if ALLOWED.search(line):
                continue
            # A payload built nearby is fine too: the code may fetch the URL,
            # configure the connection and only then write the JSON body
            # (CloudflareWorker.verifyPanelLogin does exactly that).
            window = "\n".join(lines[max(0, index - 4): index + 12])
            if ALLOWED.search(window):
                continue
            if re.search(r"""put\(\s*"username"|username\s*=|username\s*,""", window):
                continue
            rel = path.relative_to(ROOT)
            violations.append(f"{rel}:{index + 1}: {line.strip()[:120]}")

    if violations:
        print("panel-login guard: /api/login payload(s) without a username:")
        for item in violations:
            print("  ✗", item)
        print("  → build the body with PanelCredentials.loginPayload(context, base, password)")
        return 1

    print(f"panel-login guard OK — {checked} /api/login call site(s) carry a username")
    return 0


if __name__ == "__main__":
    sys.exit(main())
