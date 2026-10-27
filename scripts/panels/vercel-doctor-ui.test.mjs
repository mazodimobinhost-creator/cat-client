/**
 * Panel 6.69.43 — the 🔬 Vercel scanner card in a real DOM (jsdom): the card's own script must run on its
 * own (the panel's main script keeps api()/toast() private), the server check must render Vercel's answer
 * per SNI group, the my-internet check must mark what the user's line blocks, and the recommendation must
 * only pick an SNI that passed. Usage: node scripts/panels/vercel-doctor-ui.test.mjs (CAT_PANEL_WORKER=<file>)
 */
import { JSDOM } from 'jsdom';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const T = (await import(workerPath))._testing;
let failures = 0;
const check = (name, cond, extra) => { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 300) : '')); } };
const until = async (f, ms = 4000) => { const t = Date.now(); while (Date.now() - t < ms) { if (f()) return true; await new Promise((r) => setTimeout(r, 25)); } return !!f(); };

const HOST = 'cat-panel-v7k2m.vercel.app', UUID = '719f3599-09b0-4c6f-9f8d-bd78f0d36c57';
for (const lang of ['fa', 'en']) {
  const html = T.panelPage({ CAT_PLATFORM: 'vercel' }, { lang }, HOST, UUID);
  const errors = [], calls = [];
  const dom = new JSDOM(html, { runScripts: 'dangerously', url: 'https://' + HOST + '/panel/', beforeParse(w) {
    w.addEventListener('error', (e) => errors.push(e.message));
    w.Response = globalThis.Response;
    w.fetch = (u, o) => {
      u = String(u); calls.push([u, o && o.method]);
      if (u.includes('/api/vercel-doctor')) return Promise.resolve(new Response(JSON.stringify({ ok: true, results: [
        { id: 'bare', sni: 'vercel.app', status: 101, ok: true, ms: 40 }, { id: 'rnd', sni: 'wq8x2k1m0zz9.vercel.app', status: 101, ok: true, ms: 41 },
        { id: 'project', sni: HOST, status: 101, ok: true, ms: 39 }, { id: 'front', sni: 'nextjs.org', status: 421, ok: false, ms: 30 }] })));
      if (u.includes('/api/settings') && o && o.method === 'PUT') { const b = JSON.parse(o.body); return Promise.resolve(new Response(JSON.stringify({ ok: true, persisted: false, settings: { vercelDomain: /vercel/.test(b.vercelDomain) ? '' : b.vercelDomain } }))); }
      // the user's line: vercel.app and the project name are blocked, the random name passes
      if (/^https:\/\/(vercel\.app|cat-panel)/.test(u)) return Promise.reject(new TypeError('Failed to fetch'));
      if (/^https:\/\/w[a-z0-9]+\.vercel\.app\//.test(u)) return Promise.resolve(new Response(''));
      return Promise.resolve(new Response('{}'));
    };
  } });
  const w = dom.window, d = w.document;
  await new Promise((r) => setTimeout(r, 200));
  check(lang + ': card + config are on the page', !!d.getElementById('vDoctor') && !!w.__VD && w.__VD.plan.map((p) => p.id).join(',') === 'bare,rnd,mixed,project,front', w.__VD && JSON.stringify(w.__VD.plan));
  d.getElementById('vdSrv').click();
  check(lang + ': server check renders 101 / 421 per group', await until(() => /101/.test(d.getElementById('vdOut').textContent) && /421/.test(d.getElementById('vdOut').textContent)), d.getElementById('vdOut').textContent.slice(0, 200));
  check(lang + ': server check posts to /api/vercel-doctor', calls.some(([u, m]) => u.endsWith('/api/vercel-doctor') && m === 'POST'));
  d.getElementById('vdNet').click();
  check(lang + ': my-internet check marks each name', await until(() => (d.getElementById('vdOut').textContent.match(/📶/g) || []).length >= 3), d.getElementById('vdOut').textContent.slice(0, 300));
  const netUrls = calls.map(([u]) => u).filter((u) => /^https:\/\/[^/]+\/\?cat-scan=/.test(u));
  check(lang + ': my-internet check skips the mixed-case and nextjs.org rows', netUrls.length === 3 && !netUrls.some((u) => /nextjs\.org|[A-Z]/.test(u.split('?')[0])), netUrls.join(' '));
  const rec = d.getElementById('vdRec').textContent;
  check(lang + ': recommends only an SNI that passed BOTH checks (the random name here)', /wnm2u6310h33\.vercel\.app|w[a-z0-9]{11}\.vercel\.app/.test(rec) && rec.includes('/sub/' + UUID + '?sni=w') && !/sni=vercel\.app/.test(rec), rec.slice(0, 240));
  d.getElementById('vdDom').value = 'x.vercel.app';
  d.getElementById('vdSave').click();
  check(lang + ': saving a vercel.app name is refused with a message', await until(() => d.getElementById('vdMsg').textContent.length > 5 && /vercel/.test(d.getElementById('vdMsg').textContent)));
  d.getElementById('vdDom').value = 'vpn.example.ir';
  d.getElementById('vdSave').click();
  check(lang + ': saving without storage explains the permanent way', await until(() => /CAT_VERCEL_DOMAIN/.test(d.getElementById('vdMsg').textContent)), d.getElementById('vdMsg').textContent);
  check(lang + ': a saved domain joins the next my-internet check', w.__VD.plan[0].id === 'custom' && w.__VD.plan[0].sni === 'vpn.example.ir');
  check(lang + ': no script errors', errors.length === 0, errors.join(' | '));
  w.close();
}
if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all vercel-doctor-ui checks passed');
process.exit(0);
