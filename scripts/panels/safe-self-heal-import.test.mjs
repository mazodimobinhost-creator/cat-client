import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url), 'utf8');
const wrangler = await readFile(new URL('../../wrangler.jsonc', import.meta.url), 'utf8');

test('tunnel timeout is activity-reset instead of a fixed connection lifetime', () => {
  const start = source.indexOf('async function pumpTunnel(');
  const end = source.indexOf('/* Trojan hash', start);
  const body = source.slice(start, end);
  assert.match(body, /const touch = \(\) =>/);
  assert.match(body, /clearTimeout\(idleTimer\)/);
  assert.match(body, /TransformStream/);
  assert.ok((body.match(/touch\(\)/g) || []).length >= 3, 'timer must arm and reset in both directions');
  assert.match(body, /Promise\.race\(\[Promise\.all\(\[upstream, downstream\]\), idleFailure\]\)/);
  assert.match(body, /safeCloseWs\(ws, 1001, 'tunnel finished'\)/);
});

test('default deployment does not expose subscriptions without a key', () => {
  assert.match(wrangler, /"OPEN_SUB"\s*:\s*"false"/);
});

test('unsafe uploaded patch shapes are not imported', () => {
  assert.doesNotMatch(source, /passThroughOnException\s*\(/);
  assert.doesNotMatch(source, /\/api\/self-heal\/clear-cache/);
  assert.doesNotMatch(source, /SELF_HEAL_SOURCES/);
  assert.doesNotMatch(source, /CF_API_TOKEN/);
});
