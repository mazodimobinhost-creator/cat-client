import { JSDOM } from 'jsdom';
/**
 * beta88 — the «Test from my network» (browser) scan must probe RAW IPs and
 * ip:port pins from the panel browser itself, not only domains, and must be
 * able to prune the dead ones from the user's own IP list.
 *
 * Contract under test (see catclient.worker.js, #btnBrowserTest):
 *   - domains  → https://<host>/cdn-cgi/trace no-cors timing probe
 *   - raw IPs  → https://<ip>[:port]/ no-cors TLS reachability probe:
 *       settle fast (resolve OR reject) = alive on the user's line,
 *       timeout (AbortController @ 4s)  = dead on the user's line
 *   - plain ports (80/8080/…) → not probeable in a browser (mixed content),
 *       marked ⊘ / skip and never pruned
 *   - results land in window.__browserScan keyed by the raw list entry
 *   - «🧹 prune» replaces settings.ips keeping only entries that are not
 *       proven dead on the user's line (POST /api/ips replace:true)
 *
 * Usage: node scripts/panels/browser-probe.test.mjs
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

// ── seed the user's IP list before the page boots ─────────────────────────
const IPS = [
  '203.0.113.10:8443',   // pinned-port raw IP  → alive on the user's line
  '203.0.113.20',        // raw IP              → dead (hangs until timeout)
  'alive.dom',           // domain              → alive
  'dead.dom',            // domain              → dead
  '203.0.113.30:8080',   // plain (non-TLS)     → cannot be probed, must survive prune
];
{
  const r = await worker.fetch(new Request(HOST + '/api/ips', {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ips: IPS, replace: true, source: 'manual' }),
  }), env, {});
  const j = await r.json();
  check('seed: 5 ips stored', j.ok && j.count === 5, JSON.stringify(j).slice(0, 200));
}

// ── boot the panel with a fetch stub that plays "the user's line" ─────────
const DEAD = ['203.0.113.20', 'dead.dom'];
let probeUrls = [];
const html = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();
const errors = [];
const dom = new JSDOM(html, { url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true,
  beforeParse(window) {
    window.fetch = async (url, init = {}) => {
      const s = String(url);
      if (s.startsWith('https://')) {            // a scan probe — simulate the line
        probeUrls.push(s);
        const dead = DEAD.some(d => s.indexOf(d) > -1);
        if (dead) return new Promise((res, rej) => {   // black-hole: only the panel's own abort ends it
          if (init.signal) init.signal.addEventListener('abort', () => rej(new window.DOMException('aborted', 'AbortError')));
        });
        return {};                                // fast settle = edge reachable on this line
      }
      return worker.fetch(new Request(HOST + s, { method: init.method || 'GET', headers: init.headers || {}, body: init.body }), env, {});
    };
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
check('prune button exists and is hidden before any scan', !!document.querySelector('#btnBrowserPrune') && document.querySelector('#btnBrowserPrune').style.display === 'none');
check('i18n: browser test label is network-scan (fa)', document.querySelector('#btnBrowserTest').textContent.includes('شبکه'));
check('i18n: prune label translated', document.querySelector('#btnBrowserPrune').textContent.trim().length > 2);

// ── run the scan from "the user's line" ────────────────────────────────────
const errsBefore = errors.length;
document.querySelector('#btnBrowserTest').click();
check('click did not throw', errors.length === errsBefore, errors.slice(errsBefore).join('\n').slice(0, 400));

// wait for the 4s-dead probes to time out (poll up to 15s)
let settled = false;
for (let w = 0; w < 60 && !settled; w++) {
  await sleep(250);
  const bs = window.__browserScan || {};
  settled = IPS.every(ip => bs[ip] && (bs[ip].ok !== undefined || bs[ip].skip));
}
const BS = window.__browserScan || {};
check('scan settled within 15s', settled, JSON.stringify(BS).slice(0, 400));
check('raw pinned IP probed over TLS (port kept)', probeUrls.some(u => u === 'https://203.0.113.10:8443/'), probeUrls.filter(u => u.indexOf('203.0.113.10') > -1).join(','));
check('raw IP alive on the line → ok', BS['203.0.113.10:8443'] && BS['203.0.113.10:8443'].ok === true && typeof BS['203.0.113.10:8443'].ms === 'number', JSON.stringify(BS['203.0.113.10:8443']));
check('raw IP black-holed → dead (ok:false)', BS['203.0.113.20'] && BS['203.0.113.20'].ok === false, JSON.stringify(BS['203.0.113.20']));
check('domain alive via cdn-cgi probe', BS['alive.dom'] && BS['alive.dom'].ok === true && probeUrls.some(u => u.indexOf('alive.dom/cdn-cgi/trace') > -1), JSON.stringify(BS['alive.dom']));
check('domain black-holed → dead', BS['dead.dom'] && BS['dead.dom'].ok === false, JSON.stringify(BS['dead.dom']));
check('plain port skipped, never probed', BS['203.0.113.30:8080'] && BS['203.0.113.30:8080'].skip === true && !probeUrls.some(u => u.indexOf('203.0.113.30') > -1), JSON.stringify(BS['203.0.113.30:8080']));
check('no http:// probe (mixed content safe)', probeUrls.every(u => u.startsWith('https://')));
const scanSum = document.querySelectorAll('#scanRes div')[1];
check('summary rendered with dead count', !!scanSum && /\d+\/\d+/.test(scanSum.textContent) && scanSum.textContent.includes('1'), scanSum ? scanSum.textContent.slice(0, 120) : 'no summary');
check('prune button revealed after dead found', document.querySelector('#btnBrowserPrune').style.display !== 'none');

// ── prune: dead-on-my-line IPs leave the list, the rest stay ───────────────
document.querySelector('#btnBrowserPrune').click();
await sleep(150);
check('ask modal shown', document.querySelector('#ask').classList.contains('show'));
document.querySelector('#askYes').click();
await sleep(400);
{
  const r = await worker.fetch(new Request(HOST + '/api/settings'), env, {});
  const j = await r.json();
  const ips = j.settings.ips;
  check('prune kept alive raw IP', ips.includes('203.0.113.10:8443'), JSON.stringify(ips));
  check('prune kept alive domain', ips.includes('alive.dom'), JSON.stringify(ips));
  check('prune kept untestable plain-port IP', ips.includes('203.0.113.30:8080'), JSON.stringify(ips));
  check('prune removed dead raw IP', !ips.includes('203.0.113.20'), JSON.stringify(ips));
  check('prune removed dead domain', !ips.includes('dead.dom'), JSON.stringify(ips));
  check('list has exactly the 3 surviving entries', ips.length === 3, JSON.stringify(ips));
}

process.exit(failures ? 1 : 0);
