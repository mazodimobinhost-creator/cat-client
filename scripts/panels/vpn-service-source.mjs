// beta222: CatClientVpnService is split over CatClientVpnService.kt + CatClientVpnService<Area>.kt (see docs/CODEMAP.md).
// Source-contract tests read the service through here so a function can live in any of those files. Moved functions
// are written `internal fun CatClientVpnService.name(`; this view shows them as `private fun name(` and widened
// members as `private`, so older contracts keep matching.
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'app', 'src', 'main', 'java', 'com', 'cat', 'client');

export function vpnServiceFiles() {
  const parts = readdirSync(DIR).filter((f) => /^CatClientVpnService.+\.kt$/.test(f)).sort();
  return ['CatClientVpnService.kt', ...parts].map((f) => join(DIR, f));
}

export function readVpnService() {
  const raw = vpnServiceFiles().map((f) => readFileSync(f, 'utf8')).join('\n');
  const moved = new Set([...raw.matchAll(/^internal (?:suspend )?fun CatClientVpnService\.(\w+)\(/gm)].map((m) => m[1]));
  return raw
    .replace(/this@(\w+)/g, (all, n) => (moved.has(n) ? 'this@CatClientVpnService' : all))
    .replace(/^internal (suspend )?fun CatClientVpnService\./gm, (_, s) => `private ${s || ''}fun `)
    .replace(/^( {4}(?:@\w+ )*)internal (?!companion)/gm, '$1private ');
}
