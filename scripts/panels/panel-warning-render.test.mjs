/**
 * Browser-side proof for the two things a syntax check cannot show:
 *   • the Home error card really renders its refresh/clear buttons (they were
 *     missing once — the handlers existed but the elements did not), and
 *   • the SOCKS5 tab really renders the «re-fetch your subscription / deleting a
 *     relay breaks its configs» warning.
 * The refresh button is then CLICKED and the request it makes is asserted, so
 * this is a wiring test, not an existence test.
 *
 * Run standalone:  node scripts/panels/panel-warning-render.test.mjs
 */
import { strict as assert } from 'node:assert';
import { JSDOM } from 'jsdom';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;

const UUID = '11111111-2222-4333-8444-555555555555';
const HOST = 'https://p.workers.dev';
const mod = await import(workerPath);
const worker = mod.default;
const KV = { m: new Map(), async get(k) { return this.m.get(k) ?? null; }, async put(k, v) { this.m.set(k, v); }, async delete(k) { this.m.delete(k); }, async list() { return { keys: [] }; } };
const env = { CAT_KV: KV, UUID, OPEN_PANEL: 'true' };

const html = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('  ✓ ' + name); } else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); } };

const requests = [];
const dom = new JSDOM(html, {
  url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true,
  beforeParse(window) {
    window.fetch = async (path, init = {}) => {
      const url = new URL(path, HOST);
      requests.push((init.method || 'GET') + ' ' + url.pathname);
      return worker.fetch(new Request(url.toString(), init), env, {});
    };
    window.matchMedia = window.matchMedia || (() => ({ matches: false, addEventListener() { }, removeEventListener() { } }));
    window.HTMLElement.prototype.scrollIntoView = () => { };
  },
});
const { window } = dom;
const doc = window.document;
const visible = (el) => !!el && !el.closest('[style*="display:none"]') && el.getClientRects !== undefined;
await new Promise((r) => setTimeout(r, 400));

console.log('\nRendered panel (jsdom) — error-card buttons + relay warning\n');

/* ---------- the Home error card ---------- */
const card = doc.querySelector('#errRows')?.closest('div');
ok('#errRows exists (the error log card is on Home)', !!doc.querySelector('#errRows'));
for (const id of ['errRefresh', 'errClear']) {
  const el = doc.querySelector('#' + id);
  ok('#' + id + ' exists in the DOM', !!el);
  ok('#' + id + ' has a visible label (not an empty button)', !!el && el.textContent.trim().length > 0, el && JSON.stringify(el.textContent));
  ok('#' + id + ' sits inside the error card, not somewhere detached',
    !!el && !!card && card.contains(el));
}
ok('the two buttons are different labels, not a copy-paste duplicate',
  doc.querySelector('#errRefresh')?.textContent.trim() !== doc.querySelector('#errClear')?.textContent.trim());

/* ---------- clicking refresh must actually reload the log ---------- */
requests.length = 0;
doc.querySelector('#errRefresh').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
await new Promise((r) => setTimeout(r, 400));
ok('#errRefresh click reaches /api/errors (the button is wired, not decorative)',
  requests.some((r) => r.endsWith('/api/errors')), requests.join(', '));

/* ---------- the SOCKS5 tab warning ---------- */
const goSocks = doc.querySelector('[data-atab-go="sk"]');
ok('the addresses section has a SOCKS5 tab button', !!goSocks);
goSocks.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
await new Promise((r) => setTimeout(r, 300));
ok('clicking it switches the view (body[data-atab=sk])', doc.body.getAttribute('data-atab') === 'sk', doc.body.getAttribute('data-atab'));
const warn = doc.querySelector('[data-i="sk_warn"]');
ok('the warning element renders inside the SOCKS5 tab', !!warn);
ok('…and it is filled with text, not an empty placeholder', !!warn && warn.textContent.trim().length > 40, warn && warn.textContent.trim().length);
const wt = (warn?.textContent || '');
ok('…it tells the user to fetch the subscription again', /sub|ساب/i.test(wt), wt.slice(0, 80));
ok('…and that deleting a proxy breaks the configs that used it', /(delet|remov|حذف)/i.test(wt), wt.slice(0, 80));
ok('…the warning is NOT shown in the SOCKS5 tab only after a click on something else (it is in the tab itself)',
  !!warn && !!doc.querySelector('[data-atab="sk"]')?.contains(warn));

console.log('\n' + (fail === 0 ? `panel warning render: ALL ${pass} PASSED` : `panel warning render: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' checks failed');
