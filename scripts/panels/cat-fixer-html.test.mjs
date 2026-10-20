import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const html = await readFile(new URL('../../tools/cat-fixer.html', import.meta.url), 'utf8');

test('HTML inspector never requests credentials or mutates Cloudflare', () => {
  assert.doesNotMatch(html, /id=['"](?:apiToken|accountId)['"]|authorization\s*:|api\.cloudflare\.com|method\s*:\s*['"](?:PUT|POST|DELETE)/i);
  assert.match(html, /چیزی deploy یا حذف نمی‌کند/);
});

test('HTML inspector requires three fresh exact-version probes', () => {
  assert.match(html, /for\(let i=1;i<=3;i\+\+\)/);
  assert.match(html, /api\/version\?doctor=/);
  assert.match(html, /cache:'no-store'/);
  assert.match(html, /j\.version===expected/);
  assert.match(html, /passed===3/);
});

test('HTML inspector redacts common secrets, blocks 6.69.12, and accepts HTTPS only', () => {
  assert.match(html, /REDACTED_UUID/);
  assert.match(html, /REDACTED/);
  assert.match(html, /\^https:/);
  assert.match(html, /value="6\.69\.14"/);
  assert.match(html, /BLOCKED_1101_VERSION/);
});
