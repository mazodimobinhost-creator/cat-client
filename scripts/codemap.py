#!/usr/bin/env python3
"""Generate docs/CODEMAP.md — "what is where" for the Android app, and which tests guard each file.

    python3 scripts/codemap.py          # rewrite docs/CODEMAP.md
    python3 scripts/codemap.py --check  # CI: fail when a source file or screen function is missing from the map

The map answers three questions before you touch something:
  1. Which file owns this feature?            -> "Areas" table (one line per file, with its purpose)
  2. Where exactly is this screen/dialog?     -> "MainActivity screens" index (function -> file:line)
  3. What can break if I edit this file?      -> "Guards" column (JVM + panel tests that read or call it)
Run `python3 scripts/affected-tests.py` after an edit to run the guards of the files you changed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/cat/client'
TEST = ROOT / 'app/src/test/java/com/cat/client'
PANEL_TESTS = ROOT / 'scripts/panels'
OUT = ROOT / 'docs/CODEMAP.md'

AREAS: list[tuple[str, str]] = [
    ('Screens — MainActivity and its area files', r'^MainActivity'),
    ('VPN service, lifecycle & state', r'^(CatClientVpnService|VpnState|VpnRuntime|ConnectionLifecycle|SessionPlan|CatClientTile|QuickSettings|VpnWidget|VpnNotification|PathWatchdog|DefaultNetworkMonitor|Mihomo|RealInternetCheck|RouteMemory|ConnectionTest|ConnectionDelay|UiStall|CrashWatch|EarlyCrash)'),
    ('Engines (Cat Warp, Cat Fox VPN, Psiphon, SSTP, Tor, DNS, paqet, relays)', r'^(Aether|Warp|Fenix|Firefox|Psiphon|Sstp|Orbot|Paqet|SlipNet|WhiteDns|Amnezia|LocalSniSpoof|DpiBypass|Mlm|VercelRelay|ConnectionChain|VerifiedTunnelChain)'),
    ('Subscriptions & config formats', r'^(Subscription|SubConv|UserSubscriptions|ConfigFileImport|FreeConfigs|QrCodes|CatWarpList|VerifiedConfig|VerifiedSubscription|CatClientConfig|ConnectionLabel|ConnectionDisplay|CommunitySource|DeltaKronecker)'),
    ('Scanners, probes & automatic route choice', r'(Scan|^Ip|^CleanIp|^Endpoint|^Smart|Probe|^Ping|^Speed|^Relay|^Fronting|^ProfileDelay|^AutoConnection|^NetworkGenome|^MobileCarrier|^Operator|^ProxyEgress|^CurrentEndpoint|^VerifiedEndpoint|^VerifiedRelay|^EdgeLocation|^IspProtocol|^ForeignService|^CommunityIp|^StartupScan|^ConnectionFault|^LanSharing)'),
    ('Cloud panel, deployment & reports', r'^(Panel|Worker|Cloudflare|Deploy|Vercel|NodeHost|ApiHost|TechNamooz|AppReport)'),
    ('Other UI: pages, views, theme, design', r'(Page$|View$|Views$|^Zed|^App(Design|Theme|Accent|Language)|Drawable$|^FlagBadge|^WorldMap|^MapOrigin|^Dashboard|^TvUi|^ConnectButton|^Debounced)'),
    ('App infrastructure (settings, updates, logging, privacy)', r'.'),
]


def kt_files(d: Path) -> list[Path]:
    return sorted(d.glob('*.kt'))


def area_of(stem: str) -> str:
    for name, rx in AREAS:
        if re.search(rx, stem):
            return name
    return AREAS[-1][0]


LICENSE_WORDS = ('License', 'Copyright', 'SPDX', 'mozilla.org/MPL', 'GNU ', 'Hallmark')

# Files whose header does not say what they own.
OVERRIDES = {
    'MainActivity.kt': 'App shell: lifecycle, shared screen state, dock/tabs, connect button, small UI helpers. '
                       'Big screens/dialogs live in MainActivity<Area>.kt.',
    'CatClientVpnService.kt': 'The VPN service: starts/stops every engine, owns the TUN, health checks, '
                              'notification, real-internet check after connect.',
    'VpnRuntimeStateStore.kt': 'Process-wide VPN state (Started/Stopped/…) + broadcast to the UI and widgets.',
    'MihomoRuntime.kt': 'Cat core (mihomo) runtime: builds the YAML for the selected profile, DNS/TUN/rules.',
    'MihomoConfig.kt': 'Parses mihomo YAML catalogs into profiles.',
    'SessionPlan.kt': 'Per-connection plan: DNS mode, QUIC handling, TUN options chosen before start.',
    'ConnectionLifecycle.kt': 'Connect/disconnect state machine shared by UI, tile and widget.',
    'DefaultNetworkMonitor.kt': 'Watches the phone\'s default network (Wi-Fi/mobile changes).',
}


def purpose_of(text: str, name: str = '') -> str:
    # area files carry a one-line purpose comment written by the split
    m = re.search(r'(?m)^// (.+)\n// Moved out of MainActivity\.kt', text)
    if m:
        return m.group(1).strip()
    if name in OVERRIDES:
        return OVERRIDES[name]
    stem = name[:-3]
    decl = re.search(r'(?m)^(?:@\S+\s+)*(?:(?:internal|private|public|data|enum|sealed|abstract|open|value)\s+)*(?:class|object|interface|fun)\s+', text)
    head = text[:decl.start()] if decl else text[:6000]
    own = re.search(r'/\*\*((?:(?!\*/).)*?)\*/\s*(?:@\S+\s+)*(?:(?:internal|private|public|data|enum|sealed|abstract|open|value)\s+)*(?:class|object|interface)\s+%s\b' % re.escape(stem), text, re.S)
    candidates = [own.group(1)] if own else []
    candidates += [b.group(1) for b in re.finditer(r'/\*\*?(.*?)\*/', head, re.S)]
    candidates += [' '.join(re.findall(r'(?m)^// (.*)$', head))]
    for c in candidates:
        body = ' '.join(l.strip().lstrip('*').strip() for l in c.split('\n')).strip()
        if len(body) < 12 or any(w in body for w in LICENSE_WORDS):
            continue
        return first_sentence(body)
    return '—'


def first_sentence(s: str) -> str:
    s = re.sub(r'\s+', ' ', s).replace('|', '/').strip()
    m = re.match(r'(.+?[.!?])(\s|$)', s)
    out = m.group(1) if m else s
    return out if len(out) <= 150 else out[:147].rstrip() + '…'


def top_types(text: str) -> list[str]:
    return re.findall(r'(?m)^(?:(?:internal|private|public|data|enum|sealed|abstract|open|annotation|value|fun)\s+)*(?:class|object|interface)\s+(\w+)', text)


FUN_RX = re.compile(r'(?m)^( {0,4})(?:@\S+\s+)*(?:(?:private|internal|override|protected|public|suspend|inline|operator)\s+)*fun\s+(?:<[^>]*>\s*)?(?:MainActivity\.)?(\w+)\s*\(')


def screen_functions(path: Path) -> list[tuple[str, int]]:
    text = path.read_text()
    out = []
    for m in FUN_RX.finditer(text):
        indent = len(m.group(1))
        if path.name == 'MainActivity.kt' and indent != 4:
            continue
        if path.name != 'MainActivity.kt' and indent != 0:
            continue
        out.append((m.group(2), text.count('\n', 0, m.start()) + 1))
    return out


def guards() -> dict[str, tuple[list[str], list[str]]]:
    """file name -> (JVM test classes, panel test files) that read or call it."""
    srcs = kt_files(SRC)
    types = {p.name: set(top_types(p.read_text())) for p in srcs}
    jvm = {p.stem: p.read_text() for p in kt_files(TEST)}
    panel = {p.name: p.read_text() for p in sorted(PANEL_TESTS.glob('*.test.mjs'))}
    out: dict[str, tuple[list[str], list[str]]] = {}
    for p in srcs:
        name, stem = p.name, p.stem
        is_screen = stem.startswith('MainActivity')
        names = {t for t in types[name] if len(t) > 3} - {'MainActivity'} | ({stem} if stem != 'MainActivity' else set())
        rx = re.compile(r'\b(%s)\b' % '|'.join(sorted(map(re.escape, names)))) if names else None
        j, n = [], []
        for t, body in jvm.items():
            if t == 'CodeMapTest':
                continue
            if f'"{name}"' in body or f'/{name}' in body or (rx and rx.search(body)) or (
                    is_screen and ('MainActivitySources' in body or '"MainActivity.kt"' in body)):
                j.append(t)
        for t, body in panel.items():
            if name in body or (is_screen and 'readMainActivity' in body):
                n.append(t)
        out[name] = (sorted(j), sorted(n))
    return out


def render() -> str:
    g = guards()
    by_area: dict[str, list[Path]] = {}
    for p in kt_files(SRC):
        by_area.setdefault(area_of(p.stem), []).append(p)
    lines = [
        '# Code map — what is where',
        '',
        '> Generated by `python3 scripts/codemap.py` — do not edit by hand; rerun it after adding, moving or renaming',
        '> code (CI fails with `--check` when a file or screen function is missing).',
        '',
        '## How to change something without breaking something else',
        '',
        '1. Find the owning file below (or the screen function in the MainActivity index).',
        '2. Look at its **Guards**: those tests read or call that file. Run them after your edit:',
        '   `python3 scripts/affected-tests.py` (uses `git diff`; or pass file paths) — runs the panel guards',
        '   locally and lists the JVM guards CI will run.',
        '3. Screens: MainActivity.kt keeps lifecycle, shared state and small helpers; each big screen/dialog lives in',
        '   its own `MainActivity<Area>.kt` as `internal fun MainActivity.name(...)`. Shared state used by those files',
        '   is `internal` on MainActivity. Source-contract tests read all of them through',
        '   `MainActivitySources` (JVM) / `scripts/panels/main-activity-source.mjs` (panel).',
        '4. Vocabulary (Session Readiness, Route Health, …) is in `CONTEXT.md`; lifecycle ownership in',
        '   `docs/adr/0001-separate-lifecycle-ownership-and-health.md`.',
        '',
        '## Areas',
        '',
    ]
    total = 0
    for area, _ in AREAS:
        files = by_area.get(area)
        if not files:
            continue
        lines += [f'### {area}', '', '| File | Lines | Purpose | Guards (JVM · panel) |', '|---|---:|---|---|']
        for p in files:
            text = p.read_text()
            n = text.count('\n')
            total += n
            j, pn = g[p.name]
            guard = (', '.join(j) or '—') + ' · ' + (', '.join(x.replace('.test.mjs', '') for x in pn) or '—')
            if len(guard) > 260:
                guard = f'{len(j)} JVM · {len(pn)} panel (see `affected-tests.py`)'
            lines.append(f'| `{p.name}` | {n} | {purpose_of(text, p.name)} | {guard} |')
        lines.append('')
    lines += [f'_{len(kt_files(SRC))} Kotlin files, {total} lines._', '', '## MainActivity screens — function index', '',
              'Every member of MainActivity.kt and every extension in the area files, with its line. Search this list',
              'for the dialog/button you want to change.', '']
    for p in sorted(SRC.glob('MainActivity*.kt'), key=lambda x: (x.name != 'MainActivity.kt', x.name)):
        funs = screen_functions(p)
        lines += [f'### `{p.name}` ({len(funs)} functions)', '']
        lines.append(', '.join(f'`{f}`:{ln}' for f, ln in funs) or '—')
        lines.append('')
    lines += ['## Where is type X declared?', '', '| Type | File |', '|---|---|']
    idx = []
    for p in kt_files(SRC):
        for t in top_types(p.read_text()):
            idx.append((t, p.name))
    for t, f in sorted(set(idx), key=lambda x: x[0].lower()):
        lines.append(f'| `{t}` | `{f}` |')
    lines += ['', '## Panel (Cloudflare/Vercel worker)', '',
              '* Source: `app/src/main/assets/panels/catclient.worker.js` (edit only this; `dist-panel/*` is built from it).',
              '* Tests: `scripts/panels/*.test.mjs` (`npm test`); bundle budgets in `paste-artifact.test.mjs`.',
              '']
    return '\n'.join(lines)


def check() -> int:
    if not OUT.is_file():
        print('docs/CODEMAP.md missing — run python3 scripts/codemap.py'); return 1
    doc = OUT.read_text()
    missing = [p.name for p in kt_files(SRC) if f'`{p.name}`' not in doc]
    for p in SRC.glob('MainActivity*.kt'):
        for f, _ in screen_functions(p):
            if f'`{f}`:' not in doc:
                missing.append(f'{p.name}:{f}')
    if missing:
        print('docs/CODEMAP.md is out of date — run python3 scripts/codemap.py. Missing:', ', '.join(missing[:40]))
        return 1
    print('code map OK — every source file and screen function is listed')
    return 0


if __name__ == '__main__':
    if '--check' in sys.argv:
        sys.exit(check())
    OUT.write_text(render())
    print(f'wrote {OUT.relative_to(ROOT)}')
