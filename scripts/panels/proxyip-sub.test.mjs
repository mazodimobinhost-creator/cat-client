/**
 * ProxyIPs added in the panel must reach the subscription — including the links the config builder makes.
 *
 * The report: «how do I add ProxyIPs to the sub?». The panel's «SNI و ProxyIP» tab and the app's ProxyIP
 * scanner both save the list (PUT /api/settings {proxyIps}) and the worker builds a 🎯 config per ProxyIP
 * (`…?proxyip=<ip>` on the WS path) — but only for subs WITHOUT `?limit=`. The panel's config builder (the
 * «کانفیگ‌ساز» tab, whose links look like `ports=80%2C443%2C8080&limit=60`) ALWAYS sets `limit`, and any limit
 * dropped the whole 🎭 section. So a ProxyIP added in the panel never reached a single builder link.
 *
 * Pins:
 *   - builder-style links (limit > 2) carry a 🎯 line per VLESS/Trojan per configured ProxyIP, and the limit still
 *     counts EVERY line (the 🎭 part gets at most half of it, the clean-IP tail makes room);
 *   - only what the owner configured appears in limited links — not the built-in default ProxyIPs, not 🧬 spoof
 *     SNIs (Cloudflare rejects SNI ≠ Host); subs without a limit are unchanged (defaults and 🧬 still there);
 *   - real single-exit pins (?addr=, ?limit=1|2, a strict country) stay free of the 🎭 section;
 *   - 🎯/🧦 follow the ports the link asked for (plain-only selection ⇒ plain lines, no SNI);
 *   - every output format (links, Clash, sing-box, Xray, panel-user subs) shows them.
 *
 * Usage: node scripts/panels/proxyip-sub.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
// The worker keeps settings in a module-level cache (KV_READ_TTL_MS), so every panel below gets its OWN module
// instance (cache-busting query) — otherwise a «fresh» panel would see the previous panel's settings.
const instance = async (tag) => (await import(pathToFileURL(workerPath).href + '?panel=' + tag)).default;

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 260) : '')); }
};

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
  async delete(k) { this.m.delete(k); }
  async list() { return { keys: [] }; }
}
const UUID = '78d0b256-8174-444d-9553-84ec9d8f84ee';
const HOST = 'edge-pedre.catclient-0ltgml5i.workers.dev';
async function panel(tag) {
  const worker = await instance(tag);
  const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true' };
  const call = (p, method = 'GET', body) => worker.fetch(
    new Request('https://' + HOST + p, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body && JSON.stringify(body) }),
    env, { waitUntil() {} });
  return { env, call };
}
const jsonOf = async (r) => { try { return await r.json(); } catch { return null; } };
const dec = (v) => { try { return decodeURIComponent(v || ''); } catch { return String(v || ''); } };
const maybeB64 = (t) => (/^(vless|trojan):\/\//.test(t.trim()) ? t : Buffer.from(t.trim(), 'base64').toString('utf8'));

// ── readers ───────────────────────────────────────────────────────────────────
const fromLinks = (text) => maybeB64(text).split(/\r?\n/).filter((l) => /^(vless|trojan):\/\//.test(l)).map((l) => {
  const noFrag = l.split('#')[0];
  return {
    link: l,
    name: dec(l.split('#').slice(1).join('#')),
    tls: /[?&]security=tls/.test(noFrag),
    sni: dec((/[?&]sni=([^&#]*)/.exec(noFrag) || [])[1]),
    port: Number((/@(?:\[[^\]]+\]|[^:/?#]+):(\d+)\?/.exec(noFrag) || [])[1]),
    wsPath: dec((/[?&]path=([^&#]*)/.exec(noFrag) || [])[1]),
  };
});
const isPx = (e) => e.name.includes('🎯');
const isSocks = (e) => e.name.includes('🧦');
const isSpoofSni = (e) => e.name.includes('🧬');
const pxValue = (e) => { const q = e.wsPath.split('?')[1] || ''; const m = /(?:^|&)proxyip=([^&]*)/.exec(q); return m ? dec(m[1]) : null; };
const fromClash = (yaml) => String(yaml).split(/\n\s*- name:/).slice(1).filter((b) => /\btype:\s*(vless|trojan)\b/.test(b)).map((b) => ({ name: (/^\s*"?([^"\n]*)/.exec(b) || [])[1] || '' }));
const fromSingbox = (t) => (JSON.parse(t).outbounds || []).filter((o) => o.type === 'vless' || o.type === 'trojan').map((o) => ({ name: o.tag || '' }));
const fromXray = (t) => { const doc = JSON.parse(maybeB64(t).trim().startsWith('[') ? maybeB64(t) : t); return (Array.isArray(doc) ? doc : [doc]).map((o) => ({ name: o.remarks || '' })); };

const MY_PX = ['203.0.113.10', '203.0.113.11:8443', '198.51.100.7'];
const DEFAULT_PX = ['proxyip.cmliussss.net', 'di.nscl.ir', 'tr.diam4.ggff.net'];

// ═══ A) a panel whose owner added three ProxyIPs ═════════════════════════════
const A = await panel('A');
await A.call('/api/ips', 'POST', { ips: ['104.16.88.20', '104.17.12.4', '172.64.145.9', '162.159.140.8', '188.114.97.3'], source: 'manual' });
const put = await jsonOf(await A.call('/api/settings', 'PUT', { proxyIps: MY_PX }));
check('ProxyIPs persist (the call the 🎭 form and the app scanner make)', put && put.ok && put.persisted === true && JSON.stringify(put.settings.proxyIps) === JSON.stringify(MY_PX), JSON.stringify(put));
const made = await jsonOf(await A.call('/api/users', 'POST', { name: 'ali' }));
const token = made && made.user && made.user.id;
const sub = async (q) => fromLinks(await (await A.call('/sub/' + UUID + q)).text());

{ // the user's own builder-style link, verbatim
  const e = await sub('?ports=80%2C443%2C8080&amp;limit=60');
  const px = e.filter(isPx);
  check('builder link: limit=60 still gives exactly 60 lines', e.length === 60, e.length);
  check('builder link: a 🎯 line per VLESS/Trojan per ProxyIP (3 × 2)', px.length === 6, px.length);
  check('builder link: every configured ProxyIP is pinned in a path', MY_PX.every((p) => px.some((e2) => pxValue(e2) === p)), px.map(pxValue).join(','));
  check('builder link: the path is a real query — one «?», ed AND proxyip', px.every((e2) => (e2.wsPath.match(/\?/g) || []).length === 1 && /(?:^|[?&])ed=\d+/.test(e2.wsPath) && pxValue(e2)), px[0] && px[0].wsPath);
  check('builder link: 🎯 lines are TLS with SNI == Host (Cloudflare 403s anything else)', px.every((e2) => e2.tls && e2.sni === HOST), JSON.stringify(px[0] && { tls: px[0].tls, sni: px[0].sni }));
  check('builder link: clean-IP lines still lead (TLS-first order kept)', !isPx(e[0]) && e[0].tls, e[0] && e[0].name);
  check('builder link: no built-in default ProxyIP sneaks in', !px.some((e2) => DEFAULT_PX.includes(pxValue(e2))), px.map(pxValue).join(','));
}
{ const e = await sub('?limit=24'); // the builder's default count
  check('builder default (limit=24): total ≤ 24 and the ProxyIP lines are there', e.length <= 24 && e.filter(isPx).length === 6, e.length + ' / ' + e.filter(isPx).length); }
{ const e = await sub('');
  check('no limit: ProxyIP lines are there as before (own list only — defaults only fill an EMPTY list)', e.filter(isPx).length === 6 && !e.some((x) => isPx(x) && DEFAULT_PX.includes(pxValue(x))), e.filter(isPx).length); }
{ const e = await sub('?ports=443'); check('ports=443 only: ProxyIP lines present, on 443', e.filter(isPx).length === 6 && e.filter(isPx).every((x) => x.port === 443)); }

// pins stay pins
for (const [label, q] of [['?limit=1', '?limit=1'], ['?limit=2', '?limit=2'], ['?addr= pin', '?addr=104.16.88.20'], ['strict country', '?country=DE&strict=1']]) {
  const e = await sub(q);
  check('pinned link ' + label + ' carries no 🎭 lines', e.length > 0 && !e.some((x) => isPx(x) || isSocks(x) || isSpoofSni(x)), e.length + ' lines, ' + e.filter(isPx).length + ' 🎯');
}

// plain-only selection ⇒ plain 🎯 lines, no SNI
{ const e = await sub('?ports=80%2C8080&limit=24'); const px = e.filter(isPx);
  check('plain-only ports: 🎯 lines exist and are plain (security=none, no SNI, port 80)', px.length === 6 && px.every((x) => !x.tls && !x.sni && x.port === 80), JSON.stringify(px[0] && { tls: px[0].tls, sni: px[0].sni, port: px[0].port }));
  check('plain-only ports: the clean lines are plain too', e.filter((x) => !isPx(x)).every((x) => !x.tls)); }

// ── other formats ─────────────────────────────────────────────────────────────
{ const c = fromClash(await (await A.call('/clash/' + UUID + '?limit=24')).text());
  check('clash builder link: 🎯 proxies present, total ≤ 24', c.filter((x) => x.name.includes('🎯')).length === 6 && c.length <= 24, c.length + ' / ' + c.filter((x) => x.name.includes('🎯')).length);
  const s = fromSingbox(await (await A.call('/singbox/' + UUID + '?limit=24')).text());
  check('sing-box builder link: 🎯 outbounds present, total ≤ 24', s.filter((x) => x.name.includes('🎯')).length === 6 && s.length <= 24, s.length + ' / ' + s.filter((x) => x.name.includes('🎯')).length);
  const x = fromXray(await (await A.call('/xray/' + UUID + '?limit=24')).text());
  check('xray builder link: 🎯 configs present, total ≤ 24', x.filter((y) => y.name.includes('🎯')).length === 6 && x.length <= 24, x.length + ' / ' + x.filter((y) => y.name.includes('🎯')).length);
  if (token) {
    const u = fromLinks(await (await A.call('/u/' + token + '?limit=24')).text());
    check('panel-user sub with a limit: 🎯 lines present, total ≤ 24', u.filter(isPx).length === 6 && u.length <= 24, u.length + ' / ' + u.filter(isPx).length);
  } }

// 🧬 spoof SNIs are a no-limit extra; 🧦 own SOCKS relays follow the limit rules
{ await A.call('/api/settings', 'PUT', { extraSnis: ['time.is'] });
  const nolim = await sub(''); const lim = await sub('?limit=24');
  check('🧬 extra-SNI lines: still in subs without a limit', nolim.some(isSpoofSni));
  check('🧬 extra-SNI lines: not in limited links (SNI ≠ Host is rejected by Cloudflare)', !lim.some(isSpoofSni));
  await A.call('/api/settings', 'PUT', { extraSnis: [], proxyIps: MY_PX.concat(['socks5://u:p@192.0.2.9:1080']) });
  const withSocks = await sub('?limit=24'); const sk = withSocks.filter(isSocks);
  check('🧦 own SOCKS relay: 2 lines (VLESS+Trojan) in a limited link, relay carried in the path', sk.length === 2 && sk.every((x) => /socks5:\/\/u:p@192\.0\.2\.9:1080/.test(pxValue(x) || '')), sk.length + ' ' + (sk[0] && pxValue(sk[0])));
  check('🧦 relay does not displace the ProxyIP lines', withSocks.filter(isPx).length === 6 && withSocks.length <= 24); }

// ═══ B) many ProxyIPs: the limit stays a hard total, the 🎭 part takes at most half ═══
{ const B = await panel('B');
  await B.call('/api/ips', 'POST', { ips: ['104.16.88.20', '104.17.12.4', '172.64.145.9'], source: 'manual' });
  const many = Array.from({ length: 20 }, (_, i) => '203.0.113.' + (100 + i));
  await B.call('/api/settings', 'PUT', { proxyIps: many });
  const get = async (q) => fromLinks(await (await B.call('/sub/' + UUID + q)).text());
  const e24 = await get('?limit=24');
  check('20 ProxyIPs, limit=24: total is exactly 24', e24.length === 24, e24.length);
  check('20 ProxyIPs, limit=24: the 🎭 part is capped at half (12), clean IPs keep the rest', e24.filter(isPx).length === 12 && e24.filter((x) => !isPx(x)).length === 12, e24.filter(isPx).length + ' 🎯 / ' + e24.filter((x) => !isPx(x)).length + ' clean');
  check('20 ProxyIPs, limit=24: clean-IP lines still come first', !isPx(e24[0]) && e24.slice(0, 12).every((x) => !isPx(x)));
  const e40 = await get('?limit=40');
  check('limit=40 → 40 lines, 20 of them 🎯', e40.length === 40 && e40.filter(isPx).length === 20, e40.length + ' / ' + e40.filter(isPx).length); }

// ═══ C) small clean list: nothing is truncated ═══════════════════════════════
{ const C = await panel('C');
  await C.call('/api/settings', 'PUT', { useDefaults: false, includeHost: false, proxyIps: MY_PX });
  await C.call('/api/ips', 'POST', { ips: ['104.16.88.20', '104.17.12.4'], source: 'manual' });
  const e = fromLinks(await (await C.call('/sub/' + UUID + '?ports=443&limit=60')).text());
  check('small list + limit=60: 4 clean + all 6 🎯, nothing cut', e.length === 10 && e.filter(isPx).length === 6, e.length + ' / ' + e.filter(isPx).length); }

// ═══ D) an owner who added NO ProxyIP: limited links stay clean, plain subs keep the defaults ═══
{ const D = await panel('D');
  await D.call('/api/ips', 'POST', { ips: ['104.16.88.20', '104.17.12.4'], source: 'manual' });
  const lim = fromLinks(await (await D.call('/sub/' + UUID + '?limit=24')).text());
  check('no ProxyIP configured: a limited link carries no 🎯 line (defaults are not injected)', !lim.some(isPx), lim.filter(isPx).length);
  const nolim = fromLinks(await (await D.call('/sub/' + UUID)).text());
  check('no ProxyIP configured: a sub without a limit keeps the built-in defaults (unchanged)', nolim.filter(isPx).length === 6 && nolim.filter(isPx).every((x) => DEFAULT_PX.includes(pxValue(x))), nolim.filter(isPx).length); }

console.log(failures ? '\nPROXYIP SUB TESTS FAILED (' + failures + ')' : '\nPROXYIP SUB TESTS PASSED');
process.exit(failures ? 1 : 0);
