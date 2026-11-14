import { JSDOM } from 'jsdom';
/**
 * beta245 🌍 several exit countries per user — driven in the REAL page script (jsdom), not by source greps:
 *   tap DE + FR → both selected, 2 ProxyIP + 1 SOCKS each (fastest first); tap FR again → only DE with 4 + 2;
 *   «Panel default» clears; «Find» with two ticked countries runs them one after another and selects the ones that
 *   got healthy exits; the form POSTs exitCcs + exitCc + ≤12 exits.
 * Usage: node scripts/panels/loc-multi-dom.test.mjs
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;
const KV = { m: new Map(), async get(k){return this.m.get(k)??null}, async put(k,v){this.m.set(k,v)}, async list(){return {keys:[]}} };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HOST = 'https://p.workers.dev';
const html = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
const px = (cc, n, ms) => ({ addr: n, cc, kind: 'px', ok: true, ms });
const sx = (cc, n, ms) => ({ addr: n, cc, kind: 'socks', ok: true, ms });
const LOC = {
  ok: true,
  countries: [{ cc: 'DE', flag: '🇩🇪', fa: 'آلمان', en: 'Germany', ok: 7, best: 50 }, { cc: 'FR', flag: '🇫🇷', fa: 'فرانسه', en: 'France', ok: 3, best: 37 }],
  choices: [{ cc: 'DE', flag: '🇩🇪', fa: 'آلمان', en: 'Germany' }, { cc: 'FR', flag: '🇫🇷', fa: 'فرانسه', en: 'France' }, { cc: 'NL', flag: '🇳🇱', fa: 'هلند', en: 'Netherlands' }],
  pool: [px('DE', '10.0.0.5:2053', 90), px('DE', '10.0.0.1:443', 10), px('DE', '10.0.0.2:8443', 20), px('DE', '10.0.0.3:2096', 30), px('DE', '10.0.0.4:443', 40),
    sx('DE', 'socks5://10.1.0.1:1080', 15), sx('DE', 'socks5://10.1.0.2:4145', 25),
    px('FR', '10.2.0.1:443', 37), px('FR', '10.2.0.2:2053', 50), sx('FR', 'socks5://10.3.0.1:10808', 60)],
};
const fetchCalls = []; let userBody = null;
const dom = new JSDOM(html, { url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true,
  beforeParse(window) {
    window.fetch = async (p, init = {}) => {
      const url = new URL(p, HOST);
      const m = init.method || 'GET';
      if (url.pathname === '/api/locations' && m === 'GET') return Response.json(LOC);
      if (url.pathname === '/api/locations/fetch') {
        const b = JSON.parse(init.body); fetchCalls.push({ cc: b.cc, t: Date.now() });
        await new Promise((r) => setTimeout(r, 30));
        return Response.json(b.cc === 'NL' ? { ok: true, added: [{ addr: '10.4.0.1:443', cc: 'NL' }], testedPx: 3, listedPx: 5 } : { ok: true, added: [], testedPx: 2, listedPx: 2 });
      }
      if (url.pathname === '/api/users' && m === 'POST') userBody = JSON.parse(init.body); // captured, then saved for real
      if (url.hostname !== new URL(HOST).hostname) return new Response('');
      return worker.fetch(new Request(url, { method: m, headers: init.headers || {}, body: init.body }), env, {});
    };
    window.confirm = () => true;
  } });
const w = dom.window;
const errs = []; w.addEventListener('error', (e) => errs.push(String(e.message)));
await new Promise((r) => setTimeout(r, 400));
w.document.querySelector('#btnAdd').click(); // the real user drawer → the exit picker loads the library itself
await new Promise((r) => setTimeout(r, 300));
const $ = (q) => w.document.querySelector(q);
const tap = (cc) => $('#uLocGrid [data-uloc="' + cc + '"]').click();
const on = () => [].filter.call(w.document.querySelectorAll('#uLocGrid [data-uloc]'), (b) => b.classList.contains('on')).map((b) => b.getAttribute('data-uloc'));
const nowTxt = () => $('#uLocNow').textContent;
async function save() {
  userBody = null;
  const f = $('#fUser'); f.elements.uname.value = 'multi'; f.elements.pv.checked = true;
  f.dispatchEvent(new w.Event('submit', { cancelable: true }));
  await new Promise((r) => setTimeout(r, 200));
  const b = userBody; $('#btnAdd').click(); await new Promise((r) => setTimeout(r, 100));
  return b;
}
check('the drawer shows the library countries', !!$('#uLocGrid [data-uloc="DE"]') && !!$('#uLocGrid [data-uloc="FR"]'));
tap('DE'); tap('FR');
check('tap DE then FR → both selected (class + aria-pressed)', JSON.stringify(on()) === '["DE","FR"]' && ['DE', 'FR'].every((c) => $('#uLocGrid [data-uloc="' + c + '"]').getAttribute('aria-pressed') === 'true'), JSON.stringify(on()));
check('the summary names both countries: 4 ProxyIP · 2 SOCKS', /آلمان|Germany/.test(nowTxt()) && /فرانسه|France/.test(nowTxt()) && /4 ProxyIP · 2 SOCKS/.test(nowTxt()), nowTxt());
let b1 = await save();
check('two countries → POST carries exitCcs + exitCc + fastest 2 ProxyIP + 1 SOCKS from each (exact ports)', b1 && JSON.stringify(b1.exitCcs) === '["DE","FR"]' && b1.exitCc === 'DE' && JSON.stringify(b1.exits) === JSON.stringify(['10.0.0.1:443', '10.0.0.2:8443', 'socks5://10.1.0.1:1080', '10.2.0.1:443', '10.2.0.2:2053', 'socks5://10.3.0.1:10808']), JSON.stringify(b1 && [b1.exitCcs, b1.exitCc, b1.exits]));
check('a freshly opened drawer starts on «Panel default» (no countries carried over)', JSON.stringify(on()) === '[""]', JSON.stringify(on()));
tap('DE'); tap('FR'); tap('FR');
check('tap FR again → removed, DE stays', JSON.stringify(on()) === '["DE"]', JSON.stringify(on()));
let b2 = await save();
check('one country → 4 ProxyIP + 2 SOCKS', b2 && JSON.stringify(b2.exitCcs) === '["DE"]' && b2.exits.length === 6 && b2.exits.filter((a) => a.startsWith('socks')).length === 2, JSON.stringify(b2 && b2.exits));
tap('DE'); tap('FR'); tap('');
check('«Panel default» clears every country', JSON.stringify(on()) === '[""]', JSON.stringify(on()));
let b3 = await save();
check('…and saves no exits', b3 && b3.exitCcs.length === 0 && b3.exits.length === 0 && b3.exitCc === '', JSON.stringify(b3 && [b3.exitCcs, b3.exits]));
// finder with two ticked countries
const sel = $('#uLocCc');
check('the finder list is a multi-select', sel.multiple === true);
[].forEach.call(sel.options, (o) => { o.selected = o.value === 'FR' || o.value === 'NL'; });
tap('DE');
$('#uLocFetch').click();
await new Promise((r) => setTimeout(r, 600));
check('«Find» ran FR then NL, one after another', fetchCalls.map((c) => c.cc).join() === 'FR,NL' && fetchCalls[1].t - fetchCalls[0].t >= 25, JSON.stringify(fetchCalls));
check('the message has one line per country', ($('#uLocMsg').textContent.match(/\(\d\/2\)/g) || []).length === 2, $('#uLocMsg').textContent);
let b4 = await save();
check('a country that got healthy exits joins the selection (DE + NL), FR without exits is not forced in', b4 && JSON.stringify(b4.exitCcs) === '["DE","NL"]', JSON.stringify(b4 && b4.exitCcs));
await new Promise((r) => setTimeout(r, 300));
const row = [].find.call(w.document.querySelectorAll('#rows tr'), (tr) => /🇩🇪🇫🇷/.test(tr.textContent));
check('the users table (real worker save + reload) shows a flag for every exit country (🇩🇪🇫🇷)', !!row, $('#rows').textContent.slice(0, 300));
check('no page errors', errs.length === 0, errs.join(' | '));
if (failures) { console.error('\n' + failures + ' check(s) failed'); process.exit(1); }
console.log('\nloc-multi-dom: all passed');
