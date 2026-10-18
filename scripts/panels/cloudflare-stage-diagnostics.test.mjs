import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const path = new URL('../../diagnostics/cloudflare-stage-01-panel.js', import.meta.url);
const source = await readFile(path, 'utf8');
const worker = (await import(path.href + '?test=' + Date.now())).default;
const env = { UUID:'11111111-2222-4333-8444-555555555555', PANEL_USER:'admin', PANEL_PASSWORD:'secret', CAT_KV:{get:async()=>null} };
test('diagnostic is three physical lines and contains no proxy or camouflage code', () => {
  assert.equal(source.trimEnd().split('\n').length, 3);
  assert.doesNotMatch(source, /cloudflare:sockets|websocket|vless|trojan|Sora|eval\s*\(|new Function/i);
});
test('stage 02 is a three-line import-only sockets diagnostic', async () => {
  const stage2 = await readFile(new URL('../../diagnostics/cloudflare-stage-02-sockets-import.js', import.meta.url), 'utf8');
  assert.equal(stage2.trimEnd().split('\n').length, 3);
  assert.match(stage2, /from"cloudflare:sockets"/);
  assert.doesNotMatch(stage2, /connect\s*\(|vless|trojan|websocket|Sora|eval\s*\(|new Function/i);
});

test('stage 01 health, selfcheck, panel and 404 respond', async () => {
  assert.equal(await (await worker.fetch(new Request('https://x/health'),env)).text(),'ok');
  const self=await (await worker.fetch(new Request('https://x/api/selfcheck'),env)).json();
  assert.deepEqual(self.bindings,{uuid:true,panelUser:true,panelPassword:true,kv:true});
  const panel=await worker.fetch(new Request('https://x/panel'),env);
  assert.equal(panel.status,200); assert.match(await panel.text(),/آزمون مرحله ۱/);
  assert.equal((await worker.fetch(new Request('https://x/missing'),env)).status,404);
});
