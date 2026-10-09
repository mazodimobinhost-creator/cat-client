import { JSDOM } from 'jsdom';
/**
 * beta89 — «Real connection test» (🔌) opens a REAL WebSocket from the panel
 * browser to the tunnel path (/ws), the exact route a config takes. If the
 * handshake opens on the user's line, configs really connect through that
 * domain. The panel's own host is always the baseline row.
 *
 * Contract under test (see catclient.worker.js, #btnWsTest):
 *   - targets = the panel host + domain entries (port pins kept, #tags
 *     stripped); raw IPs are NOT targets (a browser cannot send the Host
 *     header that clean-IP routing needs) — they must never be probed here
 *   - plain (non-TLS) port pins are skipped (a TLS handshake there is fake)
 *   - each probe = new WebSocket('wss://<host>[:port]/ws'); open = alive
 *     route on the user's line; error/close/timeout(8s) = ✗
 *   - results land in window.__wsScan; the test is advisory (no prune —
 *     failure may also mean "not routed to the worker")
 *
 * Usage: node scripts/panels/ws-probe.test.mjs
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;
const KV = { m: new Map(), async get(k){return this.m.get(k)??null}, async put(k,v){this.m.set(k,v)}, async list(){return {keys:[]}} };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HOST = 'https://p.workers.dev';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let failures = 0;
const check = (n, c, x) => { if (c) console.log('✓ ' + n); else { failures++; console.error('✗ ' + n + (x ? ' — ' + x : '')); } };

// ── seed the user's IP list ────────────────────────────────────────────────
const IPS = [
  'mydom.example',         // domain routed to the worker → WS opens
  'baddom.example',        // dead / not routed            → WS fails
  '104.16.1.1',            // raw IP                       → never a WS target
  'pindom.example:2053',   // pinned TLS port            → wss://…:2053/ws
  'plaindom.example:8080', // pinned PLAIN port            → skipped, no probe
];
{
  const r = await worker.fetch(new Request(HOST + '/api/ips', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ips: IPS, replace: true, source: 'manual' }),
  }), env, {});
  const j = await r.json();
  check('seed: 5 entries stored', j.ok && j.count === 5, JSON.stringify(j).slice(0, 200));
}

// ── boot the panel with a fake WebSocket playing "the user's line" ────────
const GOOD = ['p.workers.dev', 'mydom.example', 'pindom.example'];
const wsUrls = [];
class FakeWebSocket {
  constructor(url) {
    this.url = String(url); wsUrls.push(this.url);
    this.readyState = 0;
    setTimeout(() => {
      const good = GOOD.some(h => this.url === 'wss://' + h + '/ws' || this.url.startsWith('wss://' + h + ':'));
      if (good) { this.readyState = 1; if (this.onopen) this.onopen(); }
      else { if (this.onerror) this.onerror(new Error('boom')); if (this.onclose) this.onclose({ code: 1006 }); }
    }, 10);
  }
  close() { this.readyState = 3; if (this.onclose) this.onclose({ code: 1000 }); }
}
const html = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();
const errors = [];
const dom = new JSDOM(html, { url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true,
  beforeParse(window) {
    window.WebSocket = FakeWebSocket;
    window.fetch = async (p, init = {}) => worker.fetch(new Request(HOST + p, { method: init.method || 'GET', headers: init.headers || {}, body: init.body }), env, {});
    window.confirm = () => true;
    if (!window.URL.createObjectURL) window.URL.createObjectURL = () => 'blob:mock';
    if (!window.URL.revokeObjectURL) window.URL.revokeObjectURL = () => {};
    window.addEventListener('error', (e) => errors.push(e.error ? String(e.error.stack || e.error) : e.message));
    window.console.error = (...a) => errors.push(a.join(' '));
  } });
const { window } = dom; const { document } = window;
await sleep(400);
for (let w = 0; w < 50 && !window.CFG; w++) await sleep(100);   // page load finished (settings in)

check('no JS errors on load', errors.length === 0, errors.join('\n').slice(0, 400));
check('ws test button exists', !!document.querySelector('#btnWsTest'));
check('i18n: ws button label translated (fa)', document.querySelector('#btnWsTest').textContent.includes('واقعی'));

// ── run the real-connection test ───────────────────────────────────────────
const errsBefore = errors.length;
document.querySelector('#btnWsTest').click();
await sleep(300);
check('click did not throw', errors.length === errsBefore, errors.slice(errsBefore).join('\n').slice(0, 400));
const WSR = window.__wsScan || {};
check('panel host is the baseline target', WSR['p.workers.dev'] && WSR['p.workers.dev'].base === true, JSON.stringify(WSR['p.workers.dev']));
check('baseline route opens on the line', WSR['p.workers.dev'] && WSR['p.workers.dev'].ok === true && typeof WSR['p.workers.dev'].ms === 'number', JSON.stringify(WSR['p.workers.dev']));
check('routed domain opens → ok with ms', WSR['mydom.example'] && WSR['mydom.example'].ok === true, JSON.stringify(WSR['mydom.example']));
check('dead domain → ok:false', WSR['baddom.example'] && WSR['baddom.example'].ok === false, JSON.stringify(WSR['baddom.example']));
check('pinned TLS port kept in the probe URL', wsUrls.includes('wss://pindom.example:2053/ws') && WSR['pindom.example:2053'] && WSR['pindom.example:2053'].ok === true, wsUrls.join(' '));
check('every probe is a /ws tunnel handshake', wsUrls.length >= 3 && wsUrls.every(u => u.startsWith('wss://') && u.endsWith('/ws')), wsUrls.join(' '));
check('raw IP is never WS-probed', !wsUrls.some(u => u.indexOf('104.16.1.1') > -1) && !('104.16.1.1' in WSR), wsUrls.join(' '));
check('plain-port pin skipped (no fake TLS)', !wsUrls.some(u => u.indexOf('plaindom.example') > -1) && !('plaindom.example:8080' in WSR), wsUrls.join(' '));
const wsSum = document.querySelectorAll('#wsTestOut div')[1];
check('summary shows the verified count', !!wsSum && /3\/\d+/.test(wsSum.textContent), wsSum ? wsSum.textContent.slice(0, 120) : 'no summary');
check('no prune from the ws test (advisory only)', document.querySelector('#btnBrowserPrune').style.display === 'none');

process.exit(failures ? 1 : 0);
