import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url), 'utf8');
const ownerGate = source.indexOf('/* ---- everything below is owner-only ---- */');
const status = source.indexOf("path === '/api/self-heal/status'");
const clear = source.indexOf("path === '/api/self-heal/clear-cache'");

test('self-heal endpoints remain behind the common owner gate', () => {
  assert.ok(ownerGate >= 0 && status > ownerGate && clear > ownerGate);
});

test('cache clearing is POST, same-origin, confirmed and rate limited', () => {
  const body = source.slice(clear, clear + 2400);
  assert.match(body, /method !== 'POST'/);
  assert.match(body, /requestOrigin !== origin/);
  assert.match(body, /x-cat-action/);
  assert.match(body, /MAINTENANCE_GAP_MS/);
  assert.match(body, /status: 429|, 429\)/);
});

test('safe cache clearing never touches persistence or sessions', () => {
  const endpointEnd = source.indexOf('\n  }\n\n  /**', clear) + 4;
  const body = source.slice(clear, endpointEnd);
  assert.match(body, /kvCache\.clear\(\)/);
  assert.match(body, /GEO_CACHE\.clear\(\)/);
  assert.doesNotMatch(body, /\.delete\(|\.put\(|passwordHash|SESSION_COOKIE|writeSettings|writeUsers/);
});

test('status response exposes counts, not sensitive values', () => {
  const body = source.slice(status, clear);
  assert.match(body, /caches:/);
  assert.doesNotMatch(body, /masterUuid|password|token|proxyIps|bindings:/i);
});
