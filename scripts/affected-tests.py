#!/usr/bin/env python3
"""Run the tests that guard the files you changed — "I fixed X, did I break Y?".

    python3 scripts/affected-tests.py                 # files changed vs HEAD (staged, unstaged, untracked)
    python3 scripts/affected-tests.py --base origin/main
    python3 scripts/affected-tests.py app/src/main/java/com/cat/client/MihomoRuntime.kt ...
    python3 scripts/affected-tests.py --list          # only print, do not run

Panel guards (scripts/panels/*.test.mjs) run here with node. JVM guards are listed with the Gradle command that
runs exactly them (CI runs every JVM test anyway). The mapping is the same one docs/CODEMAP.md shows.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codemap  # noqa: E402

ROOT = codemap.ROOT


def changed_files(base: str | None) -> list[str]:
    def git(*a: str) -> list[str]:
        r = subprocess.run(['git', *a], cwd=ROOT, capture_output=True, text=True)
        return [l for l in r.stdout.splitlines() if l.strip()]
    if base:
        return git('diff', '--name-only', f'{base}...HEAD')
    return sorted(set(git('diff', '--name-only', 'HEAD') + git('ls-files', '--others', '--exclude-standard')))


def main(argv: list[str]) -> int:
    list_only = '--list' in argv
    base = None
    if '--base' in argv:
        base = argv[argv.index('--base') + 1]
    paths = [a for a in argv if not a.startswith('--') and a != base]
    files = paths or changed_files(base)
    if not files:
        print('no changed files'); return 0
    g = codemap.guards()
    jvm: set[str] = set()
    panel: set[str] = set()
    worker_changed = False
    for f in files:
        name = Path(f).name
        if f.startswith('app/src/test/') and name.endswith('.kt'):
            jvm.add(Path(f).stem)
        elif name.endswith('.test.mjs'):
            panel.add(name)
        elif name in g:
            j, p = g[name]
            jvm.update(j); panel.update(p)
            print(f'{f}: {len(j)} JVM + {len(p)} panel guard(s)')
        elif 'assets/panels/' in f or f.startswith('dist-panel/') or f.startswith('scripts/panels/'):
            worker_changed = True
    jvm.discard('MainActivitySources')
    if any(f.startswith("app/src/main/java/") and f.endswith(".kt") for f in files):
        jvm.add('CodeMapTest')
    print()
    if jvm:
        print('JVM guards (CI runs them; locally: ./gradlew :app:testDebugUnitTest ' +
              ' '.join(f'--tests com.cat.client.{t}' for t in sorted(jvm)) + '):')
        for t in sorted(jvm):
            print('  -', t)
    if worker_changed:
        print('panel/worker files changed: run the full `npm test`')
    if not panel:
        print('no panel guards to run'); return 0
    print('panel guards:', ', '.join(sorted(panel)))
    if list_only:
        return 0
    failed = []
    for t in sorted(panel):
        r = subprocess.run(['node', f'scripts/panels/{t}'], cwd=ROOT, capture_output=True, text=True, timeout=600)
        ok = r.returncode == 0
        print(('  ✓ ' if ok else '  ✗ ') + t)
        if not ok:
            failed.append(t)
            print('\n'.join('      ' + l for l in (r.stdout + r.stderr).splitlines()[-25:]))
    print(f'{len(panel) - len(failed)}/{len(panel)} panel guards passed')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
