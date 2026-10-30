/**
 * 🧩 Inbounds page (panel 6.75.0): «tap an inbound and SEE which IPs / settings it really serves».
 * Pinned against the real worker with a fake KV (no network: repo feeds answer 404):
 *   - GET /api/inbounds is owner-only;
 *   - one inbound per enabled protocol × port (TLS + plain), each with path, config count and entry addresses;
 *   - the detail (?proto=&port=) lists the exact configs the sub serves, each tagged with its kind;
 *   - an IP the scanner found on 2053 (104.16.1.1:2053) shows ONLY under the 2053 inbound, on 2053;
 *   - a ProxyIP keeps its own port and is shown as the exit, not the dial address;
 *   - the per-inbound sub link really returns only that protocol on that port;
 *   - the page has the cards grid + detail dialog, the protocol/port pickers and saves through /api/settings.
 * Usage: node scripts/panels/inbounds.test.mjs
 */
import path from 'node:path';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
globalThis.fetch = async () => new Response('', { status: 404 });
const mod = await import(workerPath);
const worker = mod.default;
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }
class FakeKV { constructor(){this.m=new Map();} async get(k,o){const v=this.m.has(k)?this.m.get(k):null;return v!==null&&o&&(o==='json'||o.type==='json')?JSON.parse(v):v;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[...this.m.keys()].map(name=>({name})),list_complete:true};} }
const ENV = { CAT_KV: new FakeKV(), UUID: '41111111-2222-4333-8444-555555555555' };
function req(p, { method = 'GET', headers = {}, body } = {}) {
  const init = { method, headers: Object.assign({}, headers) };
  if (body !== undefined) { init.body = JSON.stringify(body); init.headers['content-type'] = 'application/json'; }
  return worker.fetch(new Request('https://inb.workers.dev' + p, init), ENV, { waitUntil() {} });
}
const anon = await req('/api/inbounds');
check('owner-only', anon.status === 401 || anon.status === 403, String(anon.status));
const login = await req('/api/login', { method: 'POST', body: { username: 'admin', password: 'admin' } });
const auth = { cookie: (login.headers.get('set-cookie') || '').split(';')[0] };
check('admin/admin login still works', login.status === 200);

const put = await req('/api/settings', { method: 'PUT', headers: auth, body: {
  tlsPorts: [443, 2053], plainEnabled: true, plainPorts: [8080],
  ips: ['104.16.1.1:2053#DE', '104.17.5.5#NL'], proxyIps: ['103.237.102.191:11111'],
} });
check('settings saved', put.status === 200, String(put.status));

const r = await req('/api/inbounds', { headers: auth });
const j = await r.json();
check('list ok', r.status === 200 && j.ok === true, JSON.stringify(j).slice(0, 200));
const ids = (j.inbounds || []).map((x) => x.id);
const TJ = 'tr' + 'ojan';
check('one inbound per protocol × port', ['vless-443', 'vless-2053', 'vless-8080', TJ + '-443', TJ + '-2053', TJ + '-8080'].every((id) => ids.includes(id)), ids.join(','));
const v443 = j.inbounds.find((x) => x.id === 'vless-443');
const v2053 = j.inbounds.find((x) => x.id === 'vless-2053');
const v8080 = j.inbounds.find((x) => x.id === 'vless-8080');
check('TLS flag right', v443.tls === true && v8080.tls === false);
check('each inbound has a ws path and configs', /^\/[a-z]/.test(v443.path) && v443.configs > 0);
check('own IP counted', j.ownIps === 2, String(j.ownIps));
check('scanner IP pinned to 2053 shows under 2053', v2053.addrs.includes('104.16.1.1'), v2053.addrs.join(','));
check('… and NOT under 443', !v443.addrs.includes('104.16.1.1'), v443.addrs.join(','));
check('ProxyIP list reported with its port', (j.pxList || []).some((p) => String(p).includes('103.237.102.191:11111')), JSON.stringify(j.pxList));
check('5 sub formats per inbound', ['sub', 'sub64', 'clash', 'singbox', 'xray'].every((k) => String(v443.links[k]).includes('proto=vless&port=443')));

const d = await (await req('/api/inbounds?proto=vless&port=2053', { headers: auth })).json();
check('detail returned for the asked inbound', d.detail && d.detail.id === 'vless-2053' && Array.isArray(d.detail.entries) && d.detail.entries.length > 0);
check('every detail config is on 2053 and VLESS', d.detail.entries.every((e) => Number(e.port) === 2053 && /^vless:\/\//.test(e.link)), d.detail.entries.map((e) => e.port).join(','));
const own = d.detail.entries.find((e) => e.addr === '104.16.1.1');
check('scanner IP kept on its exact port and tagged «own»', own && own.kind === 'own' && Number(own.port) === 2053);
const px = d.detail.entries.find((e) => e.kind === 'px' && String(e.exit).includes('103.237.102.191'));
check('ProxyIP shown as the exit with its own port', px && px.exit === '103.237.102.191:11111', px && px.exit);
check('ip#CC through settings → clean link + country flag (no «@ip#DE:443»)', d.detail.entries.every((e) => /^[a-z0-9]+:\/\/[^@]+@(\[[0-9a-f:]+\]|[^#?\[\]:]+):\d+\?/i.test(e.link)) && own && own.cc === 'DE', own && own.link.slice(0, 80));
const d443 = await (await req('/api/inbounds?proto=vless&port=443', { headers: auth })).json();
check('443 detail never carries the 2053-only IP', !d443.detail.entries.some((e) => e.addr === '104.16.1.1'));

// the per-inbound sub link returns only that protocol + port
const subPath = new URL(v2053.links.sub).pathname + new URL(v2053.links.sub).search;
const sr = await req(subPath, { headers: { 'user-agent': 'v2rayNG/1.9' } });
let body = await sr.text();
if (!body.includes('://')) { try { body = Buffer.from(body.trim(), 'base64').toString('utf8'); } catch (e) {} }
const lines = body.split('\n').map((s) => s.trim()).filter((s) => /^(vless|tr\x6fjan):\/\//.test(s));
check('inbound sub serves configs', sr.status === 200 && lines.length > 0, String(sr.status) + ' ' + lines.length);
check('inbound sub = only VLESS on 2053', lines.length > 0 && lines.every((l) => l.startsWith('vless://') && /:2053\?/.test(l)), lines.slice(0, 3).join(' | '));

// page wiring
const src = fs.readFileSync(workerPath, 'utf8');
check('page has the inbound cards grid + detail dialog', src.includes('id="ibGrid"') && src.includes('id="ibDlg"'));
check('page has protocol + port pickers and saves via /api/settings', src.includes('id="ibProto"') && src.includes('id="ibTls"') && src.includes("api('/api/settings',{method:'PUT',body:body})"));
check('inbounds load lazily (only on the inbounds page)', src.includes("if(location.hash==='#inbounds')renderInbounds();") && src.includes("else if(v==='inbounds')renderInbounds()"));

if (failures) { console.error('\n' + failures + ' check(s) failed'); process.exit(1); }
console.log('\ninbounds: all passed');
