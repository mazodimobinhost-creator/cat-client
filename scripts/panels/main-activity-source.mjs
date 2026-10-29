// MainActivity is split over MainActivity.kt + MainActivity<Area>.kt (beta220; see docs/CODEMAP.md).
// Source-contract tests read the Android screen code through here so a function can live in any of those files.
// Moved functions are written `internal fun MainActivity.name(`; this view shows them as `private fun name(` and
// the members those files use (widened private -> internal) as `private`, so older contracts keep matching.
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'app', 'src', 'main', 'java', 'com', 'cat', 'client');

export function mainActivityFiles() {
  const parts = readdirSync(DIR).filter((f) => /^MainActivity.+\.kt$/.test(f)).sort();
  return ['MainActivity.kt', ...parts].map((f) => join(DIR, f));
}

export function readMainActivity() {
  return mainActivityFiles().map((f) => readFileSync(f, 'utf8')).join('\n')
    .replace(/^internal (suspend )?fun MainActivity\./gm, (_, s) => `private ${s || ''}fun `)
    .replace(/^( {4}(?:@\w+ )*)internal (?!companion)/gm, '$1private ');
}
