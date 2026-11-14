/**
 * beta90 — the «survival pack»: everything in the sub should survive any
 * line. Four parts, all covered here:
 *   1. POST /api/survival — one click applies broad fallbacks
 *      (daily rotation, safe SNI=Host, fragment-capable formats, plain ports,
 *      both protocols, defaults+host, health ordering) and returns the survive link
 *   2. ?survive=1 on a sub forces those same flags for THAT render only,
 *      even when the panel settings say otherwise
 *   3. ?health=1 / settings.healthOrder — addresses proven fastest on real
 *      scanners (ipSources ms) lead the sub; unknowns keep their place
 *   4. telemetry surface: /api/users decorates lastOnline (the panel shows
 *      «connected in 24h / never connected» — exercised by panel-dom render)
 *
 * Usage: node scripts/panels/survival.test.mjs
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
const mod = await import(workerPath);
const worker = mod.default;
const T = mod._testing;
const KV = { m: new Map(), async get(k){return this.m.get(k)??null}, async put(k,v){this.m.set(k,v)}, async list(){return {keys:[]}} };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HOST = 'https://p.workers.dev';
const SUB = HOST + '/sub/' + env.UUID;
let failures = 0;
const check = (n, c, x) => { if (c) console.log('✓ ' + n); else { failures++; console.error('✗ ' + n + (x ? ' — ' + x : '')); } };
const call = (p, method, body) => worker.fetch(new Request(HOST + p, {
  method: method || 'GET',
  headers: body ? { 'content-type': 'application/json' } : {},
  body: body ? JSON.stringify(body) : undefined,
}), env, {});

// ── 1: the survival preset endpoint ────────────────────────────────────────
{
  const r = await call('/api/survival', 'POST', { apply: true });
  const j = await r.json();
  check('survival apply → ok + changed list', j.ok === true && Array.isArray(j.changed) && j.changed.length > 0, JSON.stringify(j).slice(0, 200));
  check('survival returns the survive link', typeof j.survive === 'string' && j.survive.indexOf('/sub/') > -1 && j.survive.endsWith('?survive=1'), String(j.survive));
  const s = (await (await call('/api/settings')).json()).settings;
  check('preset: daily rotation', s.subRotate === 'daily', s.subRotate);
  check('preset: safe SNI = Host (spoofing/rotation off)', s.sniFront === false && s.sniRotate === false, JSON.stringify([s.sniFront, s.sniRotate]));
  check('preset: fragment on', !!(s.fragment && s.fragment.enabled), JSON.stringify(s.fragment));
  check('preset: plain ports on', s.plainEnabled === true && (s.plainPorts || []).length > 0, JSON.stringify(s.plainPorts));
  check('preset: both protocols', !!(s.protocols && s.protocols.vless && s.protocols.trojan), JSON.stringify(s.protocols));
  check('preset: defaults + host + health ordering', s.useDefaults !== false && s.includeHost !== false && s.healthOrder === true, JSON.stringify([s.useDefaults, s.includeHost, s.healthOrder]));
  const r2 = await call('/api/survival', 'POST', { apply: true });
  const j2 = await r2.json();
  check('preset is idempotent (nothing left to change)', j2.ok === true && j2.changed.length === 0, JSON.stringify(j2.changed));
  const r3 = await call('/api/survival', 'POST', {});
  check('survival without apply → 400', r3.status === 400);
}

// ── 2+3: dial the resilience flags back down, seed proven-latency IPs ──────
{
  const r = await call('/api/settings', 'PUT', { plainEnabled: false, subRotate: 'off', healthOrder: false, protocols: { vless: false, trojan: true }, sniFront: true, sniRotate: true });
  check('settings dialed back (test setup)', r.ok || r.status === 200, 'status ' + r.status);
  const ips = await call('/api/ips', 'POST', { ips: ['10.0.0.1', '10.0.0.2#DE', '10.0.0.3'], replace: true, source: 'manual', pingMs: { '10.0.0.2#DE': 120, '10.0.0.3': 40 } });
  const j = await ips.json();
  check('seed: 3 ips with provenance latency', j.ok === true && j.count === 3, JSON.stringify(j).slice(0, 200));
}

const subText = async (q) => (await (await call('/sub/' + env.UUID + q))).text();
const pos = (txt, ip) => txt.indexOf(ip);

{
  const t0 = await subText('?rotate=off&dom=1');
  check('baseline sub: plain configs OFF (as configured)', t0.indexOf('security=none') === -1);
  check('baseline sub: vless OFF, trojan only (as configured)', t0.indexOf('vless://') === -1 && t0.indexOf('trojan://') > -1);
  check('baseline sub: original panel order', pos(t0, '10.0.0.1') > -1 && pos(t0, '10.0.0.1') < pos(t0, '10.0.0.2') && pos(t0, '10.0.0.2') < pos(t0, '10.0.0.3'), [pos(t0, '10.0.0.1'), pos(t0, '10.0.0.2'), pos(t0, '10.0.0.3')].join(','));

  // ── ?survive=1 forces the resilience flags for this render only ─────────
  const ts = await subText('?rotate=off&survive=1&dom=1');
  check('survive: plain ports forced back on', ts.indexOf('security=none') > -1);
  check('survive: both protocols forced back on', ts.indexOf('vless://') > -1 && ts.indexOf('trojan://') > -1);
  check('survive: SNI pinned to worker Host (not spoofed)', ts.includes('sni=p.workers.dev') && !ts.includes('sni=skk.moe') && !ts.includes('sni=icook.tw'));
  check('survive: scanner-proven fast address stays first', pos(ts, '10.0.0.3') > -1 && pos(ts, '10.0.0.3') < pos(ts, '10.0.0.2') && pos(ts, '10.0.0.2') < pos(ts, '10.0.0.1'), [pos(ts, '10.0.0.3'), pos(ts, '10.0.0.2'), pos(ts, '10.0.0.1')].join(','));
  const b64 = await (await call('/sub64/' + env.UUID + '?survive=1&dom=1')).text();
  const clash = await (await call('/clash/' + env.UUID + '?survive=1&dom=1')).text();
  const sing = await (await call('/singbox/' + env.UUID + '?survive=1&dom=1')).text();
  const xray = await (await call('/xray/' + env.UUID + '?survive=1&dom=1&raw=1')).text();
  check('survive applies to Base64 format', T.b64decode(b64).includes('vless://') && T.b64decode(b64).includes('trojan://') && T.b64decode(b64).includes('sni=p.workers.dev'));
  check('survive applies to Clash/Mihomo format', clash.includes('type: vless') && clash.includes('type: trojan') && clash.includes('p.workers.dev'));
  check('survive applies to sing-box format', sing.includes('"type": "vless"') && sing.includes('"type": "trojan"') && sing.includes('p.workers.dev'));
  check('survive applies to Xray format', xray.includes('"protocol": "vless"') && xray.includes('"protocol": "trojan"') && xray.includes('p.workers.dev'));
  const after = (await (await call('/api/settings')).json()).settings;
  check('survive render did NOT persist anything', after.plainEnabled === false && after.subRotate === 'off' && after.protocols.vless === false && after.sniFront === true && after.sniRotate === true, JSON.stringify([after.plainEnabled, after.subRotate, after.protocols, after.sniFront, after.sniRotate]));

  // ── ?health=1: proven-fast addresses lead ────────────────────────────────
  const th = await subText('?rotate=off&health=1&dom=1');
  const h = [pos(th, '10.0.0.3'), pos(th, '10.0.0.2'), pos(th, '10.0.0.1')];
  check('health ordering: 40ms leads, 120ms next, unknown last', h[0] > -1 && h[0] < h[1] && h[1] < h[2], h.join(','));
  await call('/api/settings', 'PUT', { ipSources: { '10.0.0.1': { src: 'scanner', ms: 1, at: Date.now() - 10 * 86400000 }, '10.0.0.3': { src: 'scanner', ms: 900, at: Date.now() - 10 * 86400000 } } });
  const stale = await subText('?rotate=off&health=1&dom=1');
  check('health ordering expires stale scans after 7 days', pos(stale, '10.0.0.1') < pos(stale, '10.0.0.2') && pos(stale, '10.0.0.2') < pos(stale, '10.0.0.3'), [pos(stale, '10.0.0.1'), pos(stale, '10.0.0.2'), pos(stale, '10.0.0.3')].join(','));
  const now = Date.now();
  const mapped = T.normalizeSettings({ ips: ['slow.example', 'fast.example'], useDefaults: false, includeHost: false, tlsPorts: [443], plainEnabled: false, plainPorts: [80], protocols: { vless: true, trojan: false }, healthOrder: true, entryLimit: 4, ipSources: { 'slow.example': { src: 'scanner', ms: 140, at: now }, 'fast.example': { src: 'scanner', ms: 30, at: now } }, domMap: { 'slow.example': '10.0.0.2', 'fast.example': '10.0.0.3' } });
  const mappedEntries = T.buildConfigEntries('p.workers.dev', env, mapped, env.UUID, null, { rotate: 'off' }).entries;
  check('health ordering carries domain Ping through DNS-to-IP mapping', mappedEntries[0] && mappedEntries[0].addr === '10.0.0.3' && mappedEntries[1] && mappedEntries[1].addr === '10.0.0.2', mappedEntries.slice(0, 2).map(e => e.addr).join(','));

  // ── telemetry: lastOnline decoration is still served ─────────────────────
  const users = (await (await call('/api/users')).json()).users;
  check('telemetry: users carry lastOnline (panel shows «never connected»)', Array.isArray(users) && users.every(u => typeof u.lastOnline === 'number'), JSON.stringify(users).slice(0, 120));
}

process.exit(failures ? 1 : 0);
