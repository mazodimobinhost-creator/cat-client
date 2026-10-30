/**
 * Flow audit of the panel UI (jsdom, NOT a real browser).
 *
 * The click audit proves nothing throws; this one proves the FLOWS actually
 * work end to end: what you type reaches the worker, gets validated, and comes
 * back into the UI. Every flow prints the real observed value.
 *
 * Outbound internet from the worker is stubbed to fail fast (the sandbox has
 * none), which also exercises the error paths: a network failure must surface
 * as a readable message, never as silence.
 *
 * Usage: node scripts/panels/panel-flow-audit.mjs
 */
import { JSDOM, VirtualConsole } from 'jsdom';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;

const realFetch = globalThis.fetch;
globalThis.fetch = async (u, init) => {
  const url = String((u && u.url) || u);
  if (/^https?:\/\//i.test(url) && !url.includes('p.workers.dev')) throw new Error('network blocked (sandbox)');
  return realFetch(u, init);
};

const KV = { m: new Map(), async get(k) { return this.m.get(k) ?? null; }, async put(k, v) { this.m.set(k, v); }, async list() { return { keys: [] }; } };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HOST = 'https://p.workers.dev';

const html = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();
const errors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', (e) => { if (!/Not implemented/.test(e.message)) errors.push(String(e.message)); });

const dom = new JSDOM(html, {
  url: HOST + '/panel', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: vc,
  beforeParse(window) {
    window.fetch = async (p, init = {}) => {
      const url = new URL(p, HOST);
      return worker.fetch(new Request(url, { method: init.method || 'GET', headers: init.headers || {}, body: init.body }), env, {});
    };
    window.confirm = () => true;
    window.alert = (m) => { window.__lastAlert = String(m); };
    window.prompt = (m, d) => (d === undefined ? 'test' : d);
    if (!window.URL.createObjectURL) window.URL.createObjectURL = () => 'blob:mock';
    if (!window.URL.revokeObjectURL) window.URL.revokeObjectURL = () => { };
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText: async () => { } }, configurable: true });
    window.addEventListener('error', (e) => errors.push(String((e.error && e.error.stack) || e.message)));
    window.addEventListener('unhandledrejection', (e) => errors.push('promise: ' + String((e.reason && e.reason.stack) || e.reason)));
    window.console.error = (...a) => errors.push('console: ' + a.join(' '));
  },
});
const { window } = dom; const { document } = window;
const $ = (s) => document.querySelector(s);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await sleep(400);

let pass = 0, fail = 0;
const check = (n, ok, detail) => { if (ok) { pass++; console.log('✓ ' + n + (detail ? ' — ' + detail : '')); } else { fail++; console.error('✗ ' + n + (detail ? ' — ' + detail : '')); } };

async function settings() {
  const j = await (await worker.fetch(new Request(HOST + '/api/settings', { headers: { cookie: await cookie() } }), env, {})).json();
  return j.settings || j;
}
async function cookie() {
  const r = await worker.fetch(new Request(HOST + '/api/login', { method: 'POST', body: JSON.stringify({ password: env.UUID }), headers: { 'content-type': 'application/json' } }), env, {});
  return (r.headers.get('set-cookie') || '').split(';')[0];
}
const go = async (v) => { const n = document.querySelector('[data-view="' + v + '"]'); if (n) n.click(); await sleep(80); };

console.log('# flow audit — outbound network is blocked on purpose (sandbox)\n');

// ── 1. settings save ─────────────────────────────────────────────────────────
await go('settings');
const f = $('#fSettings');
f.elements.ptitle.value = 'Cat Audit Title';
f.querySelector('button[type="submit"]').click();
await sleep(250);
const st1 = await settings();
check('settings: title saved through the form', st1.title === 'Cat Audit Title', 'title=' + JSON.stringify(st1.title) + ' saveState=' + JSON.stringify(($('#saveState') || {}).textContent));

// ── 2. ProxyIP entry: valid, socks5 and junk (lives in the #fSpoof form) ─────
await go('settings');
const fs = $('#fSpoof');
fs.elements.proxyIps.value = '1.2.3.4:443\nsocks5://user:pass@5.6.7.8:1080\nnot-an-ip\n9.9.9.9';
fs.querySelector('button[type="submit"]').click();
await sleep(250);
const st2 = await settings();
const px = st2.proxyIps || [];
check('proxyIP: ip:port accepted', px.includes('1.2.3.4:443'), JSON.stringify(px));
check('proxyIP: socks5:// accepted', px.some((x) => String(x).startsWith('socks5://')), JSON.stringify(px));
check('proxyIP: junk rejected (not silently stored)', !px.some((x) => /not-an-ip/.test(String(x))), JSON.stringify(px));
const ignoredNote = document.body.textContent.includes('نادیده') || /ignored/i.test(document.body.textContent);
check('proxyIP: junk reported back to the user', ignoredNote || !!window.__lastAlert, 'alert=' + JSON.stringify(window.__lastAlert || null));

// ── 3. manual IP entry (nodes view) ──────────────────────────────────────────
await go('nodes');
$('#manualTest').checked = false;
$('#manualIps').value = '104.16.1.1\n8.8.8.8\nbad..ip';
$('#btnManualAdd').click();
await sleep(300);
const st3 = await settings();
const ips = (st3.ips || []).map(String);
// the manual box pins the port it shows in its own field (default 443)
check('manual IP: valid addresses stored', ips.includes('104.16.1.1:443') && ips.includes('8.8.8.8:443'), JSON.stringify(ips));
check('manual IP: invalid address dropped', !ips.some((x) => /bad/.test(x)), JSON.stringify(ips));
check('manual IP: list renders in the UI', /104\.16\.1\.1/.test($('#ipList').textContent), $('#ipList').textContent.replace(/\s+/g, ' ').slice(0, 90));

// ── 4. paste/import box ──────────────────────────────────────────────────────
$('#ipPaste').value = '188.114.97.7:443#DE\n203.0.113.5';
$('#btnIpAppend').click();
await sleep(300);
const st4 = await settings();
check('paste import: appended (kept previous entries)', (st4.ips || []).includes('203.0.113.5') && (st4.ips || []).includes('104.16.1.1:443'), JSON.stringify(st4.ips));
check('paste import: country tag parsed', ((st4.ipCountries || {})['188.114.97.7:443'] || '') === 'DE', JSON.stringify(st4.ipCountries));

// ── 5. CIDR add (scanner view) ───────────────────────────────────────────────
await go('scan');
$('#scanCidr').value = '104.16.0.0/29';
$('#btnCidrAdd').click();
await sleep(400);
const st5 = await settings();
check('CIDR: range expanded into the IP list', (st5.ips || []).filter((a) => String(a).startsWith('104.16.0.')).length >= 6,
  JSON.stringify((st5.ips || []).filter((a) => String(a).startsWith('104.16.0.'))));

// ── 6. build a subscription ──────────────────────────────────────────────────
await go('build');
$('#bGen').click();
await sleep(700);
const count = Number(($('#bCount') || {}).textContent || 0);
const prev = ($('#bPrev') || {}).value || '';
check('builder: generated a non-empty subscription', count > 0 && /vless:\/\//i.test(prev), 'count=' + count + ' first=' + prev.split('\n')[0].slice(0, 70));

// ── 7. error paths must be visible, never silent ─────────────────────────────
await go('nodes');
const errBefore = errors.length;
$('#btnIpTest').click();
await sleep(900);
const ipOut = ($('#ipTestOut') || {}).textContent || '';
check('ip test (network blocked): shows a message instead of hanging silently', ipOut.trim().length > 0, JSON.stringify(ipOut.replace(/\s+/g, ' ').slice(0, 120)));

await go('scan');
$('#btnBrowserTest').click();
await sleep(900);
const scanOut = ($('#scanRes') || {}).textContent || '';
check('browser scan (network blocked): shows a message instead of hanging silently', scanOut.trim().length > 0, JSON.stringify(scanOut.replace(/\s+/g, ' ').slice(0, 120)));

console.log('\nuncaught JS errors during flows: ' + errors.length);
for (const e of errors.slice(0, 8)) console.log('  • ' + String(e).split('\n').slice(0, 2).join(' | ').slice(0, 200));
console.log('\n' + pass + ' passed, ' + fail + ' failed');
window.close();
process.exit(fail ? 1 : 0);
