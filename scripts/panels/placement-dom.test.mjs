import { JSDOM } from 'jsdom';
/**
 * 6.83.0 🌍 panel region (Cloudflare Workers placement) — driven in the REAL page script (jsdom) against the REAL
 * worker routes; only Cloudflare's own API and trace are mocked:
 *   the card appears on a Cloudflare host with the current setting + the live data centre; «Pick a country» shows the
 *   Google Cloud list, switching to AWS / Azure refills it; Apply asks first, then (no token bound) asks once for the
 *   token and the Worker is re-uploaded with placement.region; Smart / Default work the same way; cancelling the
 *   confirm uploads nothing; a non-Cloudflare host (Vercel) never shows the card.
 * Usage: node scripts/panels/placement-dom.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
const worker = (await import(workerPath)).default;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const UUID = '11111111-2222-4333-8444-555555555555';
const metas = [];
let tokenSeen = '';
globalThis.fetch = async (u, init = {}) => {
  const url = String(u);
  if (url.includes('/cdn-cgi/trace')) return new Response('fl=1\ncolo=AMS\nloc=NL\n', { status: 200 });
  if (url.endsWith('/accounts/acc-1/workers/scripts/edge-cat/settings')) {
    tokenSeen = String((init.headers && (init.headers.Authorization || init.headers.authorization)) || (init.headers && init.headers.get && init.headers.get('authorization')) || '');
    return Response.json({ success: true, result: { bindings: [{ type: 'plain_text', name: 'UUID', text: UUID }, { type: 'kv_namespace', name: 'CAT_KV', namespace_id: 'kv-1' }] } });
  }
  if (url.endsWith('/accounts/acc-1/workers/scripts/edge-cat') && init.method === 'PUT') {
    metas.push(JSON.parse(await init.body.get('metadata').text()));
    return Response.json({ success: true, result: { id: 'edge-cat' } });
  }
  if (url.includes('/releases/')) return new Response("/* CAT_PANEL_VERSION = '6.99.9' */\nexport default {};\n", { status: 200 });
  return new Response('', { status: 404 });
};

class FakeKV { constructor() { this.m = new Map(); } async get(k, o) { const v = this.m.has(k) ? this.m.get(k) : null; return v !== null && o && (o === 'json' || o.type === 'json') ? JSON.parse(v) : v; } async put(k, v) { this.m.set(k, v); } async delete(k) { this.m.delete(k); } async list() { return { keys: [...this.m.keys()].map((name) => ({ name })), list_complete: true }; } }

async function openPanel(HOST, env) {
  const html = await (await worker.fetch(new Request(HOST + '/panel'), env, { waitUntil() {} })).text();
  const dom = new JSDOM(html, { url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true,
    beforeParse(window) {
      window.fetch = async (p, init = {}) => {
        const url = new URL(p, HOST);
        if (url.hostname !== new URL(HOST).hostname) return new Response('');
        return worker.fetch(new Request(url, { method: init.method || 'GET', headers: init.headers || {}, body: init.body }), env, { waitUntil() {} });
      };
      window.confirm = () => true;
    } });
  const errs = [];
  dom.window.addEventListener('error', (e) => errs.push(String(e.message)));
  await sleep(500);
  return { w: dom.window, errs };
}

// ── Cloudflare host, no token bound ─────────────────────────────────────────
{
  const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true', CF_ACCOUNT_ID: 'acc-1', CF_WORKER_NAME: 'edge-cat' };
  const { w, errs } = await openPanel('https://edge-cat.someone.workers.dev', env);
  const $ = (q) => w.document.querySelector(q);
  const ask = async (answer) => { // answer: true = ✓, string = typed, null = cancel
    for (let i = 0; i < 40 && !$('#ask').classList.contains('show'); i++) await sleep(25);
    const shown = $('#ask').classList.contains('show');
    const msg = $('#askMsg').textContent;
    if (typeof answer === 'string') $('#askIn').value = answer;
    $(answer === null ? '#askNo' : '#askYes').click();
    await sleep(250);
    return { shown, msg };
  };
  check('page script runs without errors', errs.length === 0, errs.join(' | '));
  const card = $('#plcCard');
  check('🌍 region card is shown on a Cloudflare Worker', card && card.style.display !== 'none');
  check('hamburger + sidebar have data-view=region', w.document.querySelectorAll('[data-view="region"]').length >= 2);
  check('the card lives on its own #v-region page, not Countries or Backup', $('#v-region') && $('#v-region').contains(card) && !($('#v-loc') && $('#v-loc').contains(card)) && !($('#v-backup') && $('#v-backup').contains(card)));
  check('the card is titled تغییر ریجن', /تغییر ریجن/.test(($('#plcTitle') && $('#plcTitle').textContent) || ''), $('#plcTitle') && $('#plcTitle').textContent);
  check('it shows the current setting and the live data centre', /پیش‌فرض|Default/.test($('#plcNow').textContent) && $('#plcNow').textContent.includes('AMS'), $('#plcNow').textContent);
  check('nothing to apply until something else is picked', $('#plcApply').disabled === true);
  check('Smart / Specific cards are there like Zeus', /Smart/.test($('#plcModes').textContent) && /Specific/.test($('#plcModes').textContent), $('#plcModes').textContent);
  $('#plcModes [data-plc-mode="region"]').click();
  const opts = () => [].map.call($('#plcRegion').options, (o) => o.value);
  const rows = () => [].map.call(($('#plcTable') && $('#plcTable').querySelectorAll('tbody tr')) || [], (tr) => tr.getAttribute('data-plc-id'));
  check('«Pick a country» shows the Google Cloud regions with flags', $('#plcRegion').style.display !== 'none' && opts().length >= 20 && opts().every((v) => v.startsWith('gcp:')) && /🇩🇪|🇳🇱|🇺🇸/.test($('#plcRegion').textContent), opts().slice(0, 3).join(','));
  check('the table lists Frankfurt / Germany / europe-west3', rows().includes('gcp:europe-west3') && /Frankfurt/.test($('#plcTable').textContent) && /Germany/.test($('#plcTable').textContent), ($('#plcTable') && $('#plcTable').textContent || '').slice(0, 180));
  $('#plcProv [data-plc-prov="aws"]').click();
  check('switching to AWS refills the list', opts().length >= 15 && opts().every((v) => v.startsWith('aws:')));
  $('#plcProv [data-plc-prov="gcp"]').click();
  const row = $('#plcTable [data-plc-id="gcp:europe-west3"]');
  if (row) row.click(); else { const sel = $('#plcRegion'); sel.value = 'gcp:europe-west3'; sel.dispatchEvent(new w.Event('change')); }
  check('a region is picked → Apply is enabled', $('#plcApply').disabled === false);
  // cancel the confirm → nothing uploaded
  $('#plcApply').click();
  let a = await ask(null);
  check('Apply asks first; cancelling uploads nothing', a.shown && /Frankfurt/.test(a.msg) && metas.length === 0, a.msg);
  // confirm → no token bound → asked once for it → uploaded with the region
  $('#plcApply').click();
  a = await ask(true);
  const t = await ask('cf-test-token-123');
  for (let i = 0; i < 40 && !/✅|✗/.test($('#plcMsg').textContent); i++) await sleep(25);
  check('no token bound → asked once for the Cloudflare token', t.shown && /token|توکن/i.test(t.msg), t.msg);
  check('…and the Worker is re-uploaded with placement.region (UUID + KV kept)', metas.length === 1 && JSON.stringify(metas[0].placement) === '{"region":"gcp:europe-west3"}' && metas[0].bindings.some((b) => b.name === 'CAT_KV') && metas[0].bindings.some((b) => b.name === 'UUID' && b.text === UUID), JSON.stringify(metas[0] || null).slice(0, 200));
  check('the typed token is what reached Cloudflare', tokenSeen.includes('cf-test-token-123'), tokenSeen);
  check('success message tells the user to wait, refresh and update the sub', /✅/.test($('#plcMsg').textContent), $('#plcMsg').textContent);
  check('after saving, Apply is disabled again (nothing new to apply)', $('#plcApply').disabled === true);
  // Smart: the token was saved, so no second token prompt
  $('#plcModes [data-plc-mode="smart"]').click();
  $('#plcApply').click();
  await ask(true);
  for (let i = 0; i < 40 && metas.length < 2; i++) await sleep(25);
  check('Smart → placement.mode = smart, no second token prompt', metas.length === 2 && JSON.stringify(metas[1].placement) === '{"mode":"smart"}' && !$('#ask').classList.contains('show'), JSON.stringify(metas[1] || null).slice(0, 120));
  $('#plcModes [data-plc-mode="off"]').click();
  $('#plcApply').click();
  await ask(true);
  for (let i = 0; i < 40 && metas.length < 3; i++) await sleep(25);
  check('Default → uploaded without any placement', metas.length === 3 && !('placement' in metas[2]));
  check('no page errors during the whole flow', errs.length === 0, errs.join(' | '));
}

// ── non-Cloudflare host: no card ────────────────────────────────────────────
{
  for (const [plat, host] of [['vercel', 'https://cat-x.vercel.app'], ['node', 'https://cat-y.lucity.app']]) {
    const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true', CAT_PLATFORM: plat };
    const { w } = await openPanel(host, env);
    const card = w.document.querySelector('#plcCard');
    check('a ' + plat + ' panel never shows the region card', !card || card.style.display === 'none');
    const r = await worker.fetch(new Request(host + '/api/placement', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ confirm: true, placement: 'smart' }) }), env, { waitUntil() {} });
    check('…and its API refuses to apply (409 cf-only)', r.status === 409);
  }
}

console.log(failures ? '\nPLACEMENT DOM TESTS FAILED (' + failures + ')' : '\nPLACEMENT DOM TESTS PASSED');
process.exit(failures ? 1 : 0);
