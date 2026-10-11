/**
 * Click audit of the panel UI.
 *
 * Loads the real panel page, walks EVERY tab, and clicks EVERY interactive
 * element (button / switch / link / select / checkbox), recording:
 *   • the first console error (with stack) and where it happened;
 *   • every element that throws on click;
 *   • every element that provably does nothing (no DOM mutation, no network
 *     call, no class/value change) — a dead button.
 *
 * NOTE ON FIDELITY: this runs in jsdom, NOT a real browser. It exercises the
 * panel's real inline JavaScript and the real worker endpoints, but it cannot
 * validate layout, CSS, canvas/QR painting or real clipboard access. Anything
 * that depends on those must be checked in a browser (see docs/PANEL-CLICK-AUDIT.md).
 *
 * Usage: node scripts/panels/panel-click-audit.mjs
 */
import { JSDOM, VirtualConsole } from 'jsdom';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;

const KV = { m: new Map(), async get(k) { return this.m.get(k) ?? null; }, async put(k, v) { this.m.set(k, v); }, async list() { return { keys: [] }; } };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HOST = 'https://p.workers.dev';

const htmlRes = await worker.fetch(new Request(HOST + '/panel'), env, {});
const html = await htmlRes.text();

const errors = [];
const netLog = [];
const prompts = [];
const clipboardWrites = [];
let fetchCount = 0;

const vc = new VirtualConsole();
vc.on('jsdomError', (e) => {
  if (/Not implemented/.test(e.message)) return; // jsdom has no prompt/alert — stubbed below
  errors.push({ phase: 'load', detail: String(e.stack || e.message) });
});

const dom = new JSDOM(html, {
  url: HOST + '/panel',
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  virtualConsole: vc,
  beforeParse(window) {
    window.fetch = async (p, init = {}) => {
      fetchCount++;
      const url = new URL(p, HOST);
      netLog.push((init.method || 'GET') + ' ' + url.pathname + url.search);
      const r = await worker.fetch(new Request(url, { method: init.method || 'GET', headers: init.headers || {}, body: init.body }), env, {});
      return r;
    };
    window.confirm = () => true;
    window.alert = (m) => prompts.push('alert: ' + m);
    window.prompt = (m, d) => { prompts.push('prompt: ' + m); return d === undefined ? 'test' : d; };
    if (!window.URL.createObjectURL) window.URL.createObjectURL = () => 'blob:mock';
    if (!window.URL.revokeObjectURL) window.URL.revokeObjectURL = () => { };
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText: async (t) => { clipboardWrites.push(String(t).slice(0, 60)); } }, configurable: true,
    });
    window.addEventListener('error', (e) => {
      errors.push({ phase: 'window', detail: String((e.error && e.error.stack) || e.message) });
    });
    window.addEventListener('unhandledrejection', (e) => {
      errors.push({ phase: 'promise', detail: String((e.reason && e.reason.stack) || e.reason) });
    });
    window.console.error = (...a) => errors.push({ phase: 'console', detail: a.join(' ') });
  },
});

const { window } = dom;
const { document } = window;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await sleep(400);

const VIEWS = ['dash', 'build', 'nodes', 'scan', 'clients', 'inbounds', 'spoof', 'backup', 'settings', 'about'];

function labelOf(el) {
  const id = el.id ? '#' + el.id : '';
  const txt = (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 28);
  const ph = el.getAttribute && el.getAttribute('data-ph');
  const di = el.getAttribute && el.getAttribute('data-i');
  return el.tagName.toLowerCase() + id + (di ? '[' + di + ']' : '') + (txt ? ' “' + txt + '”' : '') + (ph ? ' (ph:' + ph + ')' : '');
}

const results = [];   // { view, el, kind, threw, reacted, note }
let mutationCount = 0;
const observer = new window.MutationObserver((recs) => { mutationCount += recs.length; });
observer.observe(document.body, { childList: true, subtree: true, attributes: true, characterData: true });

function showView(v) {
  const nav = document.querySelector('[data-view="' + v + '"]');
  if (nav) nav.click();
  else {
    const fn = window.show || (window.__show);
    if (typeof fn === 'function') fn(v);
  }
}

for (const view of VIEWS) {
  showView(view);
  await sleep(60);
  const viewEl = document.getElementById('v-' + view);
  if (!viewEl) { results.push({ view, el: '(view missing)', kind: 'view', threw: false, reacted: false, note: 'VIEW NOT FOUND' }); continue; }
  const on = viewEl.classList.contains('on');
  if (!on) { results.push({ view, el: '(view not activated)', kind: 'view', threw: false, reacted: false, note: 'VIEW DID NOT ACTIVATE' }); }

  const sel = 'button, [role="button"], a[href], input[type="checkbox"], select, .sw, [onclick]';
  const nodes = [...viewEl.querySelectorAll(sel)];
  for (let i = 0; i < nodes.length; i++) {
    const el = nodes[i];
    el.setAttribute('data-audit', view + ':' + i);
  }
  for (let i = 0; i < nodes.length; i++) {
    const fresh = viewEl.querySelector('[data-audit="' + view + ':' + i + '"]');
    if (!fresh) continue;
    if (fresh.disabled) continue;
    // Hidden via CSS (jsdom computes styles from the panel's <style> block) or inline.
    try {
      const cs = window.getComputedStyle(fresh);
      if (cs && (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0')) continue;
    } catch (e) { /* ignore */ }
    const tag = fresh.tagName.toLowerCase();
    // External navigation cannot happen inside jsdom — reported separately, not as dead.
    if (tag === 'a') {
      const href = fresh.getAttribute('href') || '';
      if (/^(https?:|mailto:|catclient:|v2rayng:|clash:|sing-box:|aether:)/i.test(href)) {
        results.push({ view, el: labelOf(fresh) + ' → ' + href.slice(0, 60), kind: 'link', threw: null, reacted: true, note: 'external/deep link (not testable in jsdom)' });
        continue;
      }
    }
    showView(view);
    await sleep(20);
    const label = labelOf(fresh);
    const before = {
      err: errors.length, fetch: fetchCount, mut: mutationCount,
      cls: fresh.className, html: document.body.innerHTML.length, prompts: prompts.length, clip: clipboardWrites.length,
      checked: fresh.checked, value: fresh.value,
    };
    let threw = null;
    try {
      if (fresh.tagName.toLowerCase() === 'select') {
        // A <select> only reacts to a value change + change event, never to a bare click.
        const opts = [...fresh.options].filter((o) => !o.disabled);
        const other = opts.find((o) => o.value !== fresh.value) || opts[1];
        if (other) { fresh.value = other.value; fresh.dispatchEvent(new window.Event('change', { bubbles: true })); }
      } else {
        fresh.click();
      }
    } catch (e) {
      threw = String((e && e.stack) || e);
    }
    await sleep(90);
    const after = { err: errors.length, fetch: fetchCount, mut: mutationCount, prompts: prompts.length, clip: clipboardWrites.length, checked: fresh.checked, value: fresh.value };
    const newErrs = errors.slice(before.err, after.err);
    const reacted = threw || newErrs.length ||
      after.fetch > before.fetch || after.mut > before.mut ||
      after.prompts > before.prompts || after.clip > before.clip ||
      fresh.className !== before.cls || after.checked !== before.checked || after.value !== before.value;
    results.push({
      view, el: label, kind: fresh.tagName.toLowerCase() + (fresh.type ? ':' + fresh.type : ''),
      threw: threw ? threw.split('\n').slice(0, 2).join(' | ') : null,
      reacted: !!reacted,
      note: newErrs.length ? newErrs.map((e) => e.phase + ': ' + String(e.detail).split('\n')[0].slice(0, 120)).join(' ;; ') : '',
    });
    fresh.removeAttribute('data-audit');
  }
}

const dead = results.filter((r) => !r.reacted && r.kind !== 'view' && r.kind !== 'link');
const thrown = results.filter((r) => r.threw);
const errored = results.filter((r) => r.note);

console.log('='.repeat(78));
console.log('CLICK AUDIT — ' + results.filter((r) => r.kind !== 'view').length + ' elements over ' + VIEWS.length + ' views');
console.log('='.repeat(78));
console.log('\n## load-time errors (' + errors.filter((e) => e.phase === 'load').length + ')');
for (const e of errors.filter((x) => x.phase === 'load').slice(0, 5)) console.log('  • ' + String(e.detail).split('\n').slice(0, 3).join('\n    '));
console.log('\n## clicks that THREW (' + thrown.length + ')');
for (const r of thrown) console.log('  ✗ [' + r.view + '] ' + r.el + '\n      ' + r.threw);
console.log('\n## clicks that produced a console/window error (' + errored.length + ')');
for (const r of errored) console.log('  ! [' + r.view + '] ' + r.el + '\n      ' + r.note);
console.log('\n## DEAD elements — no DOM change, no request, no class toggle (' + dead.length + ')');
for (const r of dead) console.log('  ? [' + r.view + '] ' + r.kind + ' ' + r.el);
console.log('\n## view activation');
for (const r of results.filter((x) => x.kind === 'view')) console.log('  [' + r.view + '] ' + (r.note || 'ok'));
observer.disconnect();
window.close();
