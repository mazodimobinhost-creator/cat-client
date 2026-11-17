#!/usr/bin/env python3
"""ONE-TIME refactor, phase 2 (beta221): move the remaining feature code out of MainActivity.kt.

Same rules as split-main-activity.py (phase 1): each moved function becomes `internal fun MainActivity.name(...)`
with byte-identical code except `this@MainActivity` -> `this@name`, `::member` -> `this::member`, Java statics that
an extension cannot inherit (`MODE_PRIVATE`, `*_SERVICE`, `RESULT_OK`) get qualified, and private members used by
moved code become `internal`. Functions using `::x.isInitialized` (only legal where the property is declared),
overrides and member extensions stay in MainActivity. Groups are given as header-line ranges of the beta220 file.
Rerunning is a no-op once applied (the marker function is gone from MainActivity).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kotlin_scan import code_mask, function_span, match_brace  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'app/src/main/java/com/cat/client/MainActivity.kt'

# file -> (description for new files, [(firstHeaderLine, lastHeaderLine), ...]) — beta220 MainActivity.kt line numbers.
GROUPS: dict[str, tuple[str, list[tuple[int, int]]]] = {
    'MainActivitySubscriptions.kt': ('', [(1155, 2135), (10257, 10280)]),
    'MainActivityDashboard.kt': ('', [(2429, 2550)]),
    'MainActivityUiKit.kt': ('Shared card/tile/segment widgets (zed* look) used by many screens.', [(2551, 2963)]),
    'MainActivityScanner.kt': ('', [(2964, 4550)]),
    'MainActivityCloud.kt': ('', [(4560, 4851), (7688, 8610), (8827, 8847), (9265, 9308), (12000, 12088)]),
    'MainActivityCatWarp.kt': ('', [(4852, 5137), (7555, 7632)]),
    'MainActivityAntiFilterTools.kt': ('Anti-filter tool dialogs (fronting, relays, NetMelli, DNS tunnels, fusion, labs).',
                                       [(5138, 5958), (6527, 6846), (6973, 7554)]),
    'MainActivityGuides.kt': ('', [(5959, 6001), (7633, 7687), (9626, 9708)]),
    'MainActivityAmneziaSstp.kt': ('Amnezia / WireGuard config dialogs and Cat SSTP (VPN Gate) dialogs.', [(6002, 6526)]),
    'MainActivityFox.kt': ('', [(6847, 6972)]),
    'MainActivityNetworkTools.kt': ('Static IP / NAT64, speed & ping pages, ProxyIP and SOCKS5 pages, add-server sheet.',
                                    [(8611, 8826), (8848, 9264)]),
    'MainActivityAdvanced.kt': ('', [(9309, 9625), (10281, 11147)]),
    'MainActivityDiagnostics.kt': ('Diagnostics: copy/share logs and crash, update check, external links.',
                                   [(11975, 11999), (12089, 12161)]),
}
MARKER = 'fetchFreeConfigs'

FUN_RE = re.compile(r'(?m)^    (?:@\S+\s+)*((?:(?:private|override|internal|protected|public|suspend|inline|operator|tailrec)\s+)*)fun\s+(?:<[^>]*>\s*)?((?:[\w.<>?, ]+\.)?)(\w+)\s*\(')
DECL_RE = re.compile(r'(?m)^(?:    |        )((?:@\w+(?:\([^)\n]*\))?\s+)*)private\s+((?:(?:inline|suspend|lateinit|const|data|enum|inner|abstract|open|tailrec|operator|infix|sealed|value)\s+)*)(fun|val|var|class|object|interface)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)')
STATICS = [(re.compile(r'(?<![\w.])(MODE_PRIVATE|MODE_APPEND|[A-Z][A-Z_]*_SERVICE)\b'), r'Context.\1'),
           (re.compile(r'(?<![\w.])(RESULT_OK|RESULT_CANCELED)\b'), r'Activity.\1')]


def main() -> None:
    src = MAIN.read_text()
    if not re.search(r'(?m)^    (?:private |internal )fun %s\(' % MARKER, src):
        print('already applied'); return
    mask = code_mask(src)
    cls = src.index('class MainActivity : Activity()')
    body_open = src.index('{', cls)
    body_close = match_brace(src, mask, body_open)

    funs: dict[str, list[tuple[int, int, int, str]]] = {}  # name -> (start, end, headerLine, modifiers)
    for m in FUN_RE.finditer(src, body_open, body_close):
        if not mask[m.start() + 4]:
            continue
        if m.group(2):  # member extension (String.x / AlertDialog.x): stays
            continue
        s, e = function_span(src, mask, m.start())
        line = src.count('\n', 0, m.start()) + 1
        while True:
            prev_end = s - 1
            prev_start = src.rfind('\n', 0, prev_end) + 1
            t = src[prev_start:prev_end].strip()
            if src[prev_start:prev_end].startswith('    ') and (t.startswith(('//', '/*', '*', '@'))):
                s = prev_start
            else:
                break
        funs.setdefault(m.group(3), []).append((s, e, line, m.group(1)))

    plan: dict[str, list[str]] = {}
    taken: set[str] = set()
    skipped: list[str] = []
    for fname, (_, ranges) in GROUPS.items():
        for name, spans in funs.items():
            if name in taken:
                continue
            if not any(a <= spans[0][2] <= b for a, b in ranges for _ in [0]):
                continue
            text = ''.join(src[s:e] for s, e, _, _ in spans)
            if any('override' in mods for *_, mods in spans) or '.isInitialized' in text:
                skipped.append(name); taken.add(name); continue
            plan.setdefault(fname, []).append(name); taken.add(name)

    moved_names = {n for names in plan.values() for n in names}
    priv_names = {m.group(4) for m in DECL_RE.finditer(src, body_open, body_close) if mask[m.start() + 4]}
    comp_idx = src.index('    internal companion object {', body_open)
    comp_close = match_brace(src, mask, src.index('{', comp_idx))
    companion_names = set(re.findall(r'(?m)^        (?:private\s+)?(?:const\s+)?(?:val|var|fun)\s+(\w+)', src[comp_idx:comp_close]))
    nested_types = set(re.findall(r'(?m)^    (?:private\s+|internal\s+)?(?:data\s+|enum\s+|inner\s+)?(?:class|object|interface)\s+(\w+)', src[body_open:body_close]))
    all_members = set(funs) | priv_names | set(re.findall(r'(?m)^    (?:\S+\s+)*?(?:val|var)\s+(\w+)', src[body_open:body_close]))

    spans, files = [], {}
    for fname, names in plan.items():
        parts = []
        for name in sorted(names, key=lambda n: funs[n][0][0]):
            for s, e, _, _ in funs[name]:
                spans.append((s, e))
                parts.append(transform(src[s:e], name, all_members))
        files[fname] = parts
    tokens = set(re.findall(r'\b\w+\b', '\n'.join(p for ps in files.values() for p in ps)))

    out = src
    for s, e in sorted(spans, reverse=True):
        if out[e:e + 1] == '\n' and out[s - 2:s] == '\n\n':
            e += 1
        out = out[:s] + out[e:]

    widened = {n for n in priv_names if n in tokens and n not in moved_names}

    def rep(m: re.Match) -> str:
        if m.group(4) not in widened:
            return m.group(0)
        g = m.group(0)
        k = g.index('private')
        return g[:k] + 'internal' + g[k + len('private'):]
    out = DECL_RE.sub(rep, out)
    for name in moved_names:
        out = re.sub(r'(?<![\w.)\]>?])::%s\b' % name, 'this::%s' % name, out)
    used_companion = sorted(n for n in companion_names if n in tokens)
    used_nested = sorted(n for n in nested_types if n in tokens)

    imports = re.findall(r'(?m)^import .*$', src)
    for fname, parts in files.items():
        text = '\n'.join(parts)
        local = set(re.findall(r'\b\w+\b', text))
        extra = ['import com.cat.client.MainActivity.Companion.%s' % n for n in used_companion if n in local]
        extra += ['import com.cat.client.MainActivity.%s' % n for n in used_nested if n in local]
        need = imports + extra + ['import android.content.Context', 'import android.app.Activity']
        path = MAIN.parent / fname
        if path.exists():
            cur = path.read_text()
            have = set(re.findall(r'(?m)^import .*$', cur))
            add = [i for i in dict.fromkeys(need) if i not in have]
            if add:
                last = list(re.finditer(r'(?m)^import .*$', cur))[-1]
                cur = cur[:last.end()] + '\n' + '\n'.join(add) + cur[last.end():]
            cur = cur.rstrip('\n') + '\n\n// ---- moved from MainActivity.kt in beta221 (code split phase 2) ----\n\n' + text.rstrip('\n') + '\n'
            path.write_text(cur)
        else:
            header = ('package com.cat.client\n\n' + '\n'.join(dict.fromkeys(need)) + '\n\n'
                      '// %s\n// Moved out of MainActivity.kt unchanged (beta221 code split phase 2); see docs/CODEMAP.md.\n\n'
                      % GROUPS[fname][0])
            path.write_text(header + text.rstrip('\n') + '\n')
    MAIN.write_text(out)
    print('moved %d functions into %d files; widened %d; skipped (stay in core): %s' % (
        len(moved_names), len(files), len(widened), ', '.join(sorted(skipped))))
    print('MainActivity lines: %d -> %d' % (src.count('\n'), out.count('\n')))


def transform(text: str, name: str, members: set[str]) -> str:
    sig = re.compile(r'(?m)^(    (?:@\S+\s+)*)(?:private|internal)\s+((?:suspend\s+|inline\s+)*)fun\s+(<[^>]*>\s*)?%s\s*\(' % name)
    m = sig.search(text)
    if not m:
        raise SystemExit('signature not private/internal for %s' % name)
    gen = (m.group(3) or '').strip()
    new_sig = '%sinternal %sfun %sMainActivity.%s(' % (m.group(1), m.group(2), (gen + ' ') if gen else '', name)
    text = text[:m.start()] + new_sig + text[m.end():]
    text = text.replace('this@MainActivity', 'this@%s' % name)
    text = re.sub(r'(?<![\w.)\]>?])::(\w+)\b', lambda mm: ('this::' + mm.group(1)) if mm.group(1) in members else mm.group(0), text)
    for rx, sub in STATICS:
        text = rx.sub(sub, text)
    if '"""' not in text:
        text = '\n'.join(l[4:] if l.startswith('    ') else l for l in text.split('\n'))
    return text


if __name__ == '__main__':
    main()
