/**
 * The per-link overrides (?addr= / ?port=) must never emit an undialable config.
 *
 * The report shape: pin links the config builder / users share (`/sub/<uuid>?addr=…&port=…`)
 * bypassed ALL of the validation /api/ips applies, with three silent failures proven on 6.55.1:
 *   1. «?addr=1.2.3.4#DE» — the country tag became part of the server name; once pasted into any
 *      client the «#» starts the remark, so port/path/security params are swallowed → dead config;
 *   2. «?addr=1.2.3.4:99999» — pinnedPortOf refused the out-of-range port, then the WHOLE string
 *      was bracketed as one IPv6 hostname → garbage «[1.2.3.4:99999]:443»;
 *   3. «?port=70000» — the filter was `p > 0`, so subscriptions were full of ports no TCP stack
 *      can dial (the settings PUT validated 1..65535; the query did not).
 * Pins (the feature itself must survive):
 *   - ports are clamped to 1..65535; an all-invalid ?port= falls back to the panel's port walk;
 *   - «ip#CC» tags are stripped from the server and become the entry's country (chips/strict work);
 *   - an out-of-range pin falls back to the bare host on the normal port walk (never bracket-garbage);
 *   - a VALID pin keeps its verified port («ip:2053», «[v6]:8443»);
 *   - junk tokens are dropped, and if NOTHING survives the panel's address list takes over
 *     (a pin link must never serve an empty sub);
 *   - every output format (raw links, Clash, sing-box, Xray) carries the cleaned server.
 *
 * Usage: node scripts/panels/addr-port-query.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
// Module-level settings cache → every panel gets its OWN module instance (cache-busting query).
const loadModule = async (tag) => import(pathToFileURL(workerPath).href + '?panel=' + tag);

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
  const mod = await loadModule(tag);
  const worker = mod.default;
  const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true' };
  const call = (p, method = 'GET', body) => worker.fetch(
    new Request('https://' + HOST + p, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body && JSON.stringify(body) }),
    env, { waitUntil() {} });
  await call('/api/ips', 'POST', { ips: ['104.16.88.20'], source: 'manual' });
  return { env, call, T: mod._testing };
}
const dec = (v) => { try { return decodeURIComponent(v || ''); } catch { return String(v || ''); } };
const maybeB64 = (t) => (/^(vless|trojan):\/\//.test(t.trim()) ? t : Buffer.from(t.trim(), 'base64').toString('utf8'));
const fromLinks = (text) => maybeB64(text).split(/\r?\n/).filter((l) => /^(vless|trojan):\/\//.test(l)).map((l) => {
  const noFrag = l.split('#')[0];
  return {
    link: l,
    name: dec(l.split('#').slice(1).join('#')),
    server: (/@(\[[^\]]+\]|[^:/?#]+)/.exec(noFrag) || [])[1],
    port: Number((/@(?:\[[^\]]+\]|[^:/?#]+):(\d+)\?/.exec(noFrag) || [])[1]),
    hasPath: /[?&]path=/.test(noFrag),
    hasSecurity: /[?&]security=/.test(noFrag),
  };
});
const portOk = (p) => Number.isInteger(p) && p > 0 && p < 65536;

/* ── unit: subQuery port bounds ─────────────────────────────────────────────── */
{
  const P = await panel('aq-unit');
  const q1 = P.T.subQuery(new URL('https://' + HOST + '/sub/x?port=70000,443,0,-5,abc,99999'));
  check('subQuery keeps only in-range ports (70000/0/-5/abc/99999 dropped)', JSON.stringify(q1.port) === '[443]', JSON.stringify(q1.port));
  const q2 = P.T.subQuery(new URL('https://' + HOST + '/sub/x?ports=80%2C65535%2C65536'));
  check('subQuery accepts 65535, rejects 65536', JSON.stringify(q2.port) === '[80,65535]', JSON.stringify(q2.port));
}

/* ── ?port= out of range → no undialable config anywhere ───────────────────── */
{
  const P = await panel('aq-port');
  const ls = fromLinks(await (await P.call('/sub/' + UUID + '?port=70000&limit=8')).text());
  check('?port=70000: sub is NOT empty (falls back to the panel port walk)', ls.length > 0);
  check('?port=70000: every emitted port is dialable', ls.every((e) => portOk(e.port)), ls.map((e) => e.port).join(','));
  const sb = await (await P.call('/singbox/' + UUID + '?port=99999&limit=8')).text();
  check('?port=99999: sing-box carries no out-of-range port', !sb.includes('"99999"') && !sb.includes(':99999'));
  const cl = await (await P.call('/clash/' + UUID + '?port=99999&limit=8')).text();
  check('?port=99999: clash carries no out-of-range port', !cl.includes('port: 99999'));
  const xr = await (await P.call('/xray/' + UUID + '?port=99999&limit=8')).text();
  check('?port=99999: xray carries no out-of-range port', !xr.includes(':99999'));
}

/* ── ?addr=ip#CC — the tag must not leak into the server ───────────────────── */
{
  const P = await panel('aq-tag');
  const ls = fromLinks(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('1.2.3.4#DE') + '&limit=8')).text());
  check('?addr=ip#DE: sub is not empty', ls.length > 0);
  check('?addr=ip#DE: every server is the BARE ip', ls.every((e) => e.server === '1.2.3.4'), ls.map((e) => e.server).join(','));
  check('?addr=ip#DE: params survive the «#» (path+security present)', ls.every((e) => e.hasPath && e.hasSecurity));
  check('?addr=ip#DE: the tag shows up as the entry country (flag in the name)', ls.some((e) => e.name.includes('🇩🇪')), ls[0] && ls[0].name);
  const cl = await (await P.call('/clash/' + UUID + '?addr=' + encodeURIComponent('1.2.3.4#DE') + '&limit=8')).text();
  check('?addr=ip#DE: clash server is the bare ip (no # in the YAML)', cl.includes('server: "1.2.3.4"') && !cl.includes('1.2.3.4#DE'));
  const sb = await (await P.call('/singbox/' + UUID + '?addr=' + encodeURIComponent('1.2.3.4#DE') + '&limit=8')).text();
  check('?addr=ip#DE: sing-box server is the bare ip', /"server":\s*"1\.2\.3\.4"/.test(sb) && !sb.includes('1.2.3.4#DE'));
  // the tag also feeds strict country filtering on the pin link itself
  const strictLs = fromLinks(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('1.2.3.4#DE') + '&country=DE&strict=1&limit=8')).text());
  check('?addr=ip#DE + strict country DE: the pinned address survives', strictLs.length > 0 && strictLs.every((e) => e.server === '1.2.3.4'));
}

/* ── ?addr= with an out-of-range pin → bare host, never bracket-garbage ────── */
{
  const P = await panel('aq-pinbad');
  const raw = maybeB64(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('1.2.3.4:99999') + '&limit=8')).text());
  check('?addr=ip:99999: no «[1.2.3.4:99999]» garbage', !raw.includes('[1.2.3.4:99999]'));
  const ls = fromLinks(raw);
  check('?addr=ip:99999: falls back to the bare host on the panel ports', ls.length > 0 && ls.every((e) => e.server === '1.2.3.4' && portOk(e.port)), ls.map((e) => e.server + ':' + e.port).join(','));
}

/* ── VALID pins keep their verified port ───────────────────────────────────── */
{
  const P = await panel('aq-pingood');
  const ls = fromLinks(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('104.16.88.20:2053') + '&limit=8')).text());
  check('?addr=ip:2053: every config keeps the verified port 2053', ls.length > 0 && ls.every((e) => e.port === 2053), ls.map((e) => e.port).join(','));
  const v6 = fromLinks(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('[2001:db8::5]:8443') + '&limit=8')).text());
  check('?addr=[v6]:8443: the pin survives, bracketed once', v6.length > 0 && v6.every((e) => e.server === '[2001:db8::5]' && e.port === 8443), v6.map((e) => e.server + ':' + e.port).join(','));
  const bareV6 = fromLinks(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('2001:db8::5') + '&limit=8')).text());
  check('?addr=bare v6: survives untouched (not mistaken for host:port)', bareV6.length > 0 && bareV6.every((e) => e.server === '[2001:db8::5]'), bareV6.map((e) => e.server).join(','));
}

/* ── junk tokens are dropped; an all-junk pin falls back to the panel list ─── */
{
  const P = await panel('aq-junk');
  const some = fromLinks(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('exa,a..b,104.16.88.20') + '&limit=8')).text());
  check('?addr=mix of junk + one ip: only the real ip is served', some.length > 0 && some.every((e) => e.server === '104.16.88.20'), some.map((e) => e.server).join(','));
  const none = fromLinks(await (await P.call('/sub/' + UUID + '?addr=' + encodeURIComponent('not a host!!') + '&limit=8')).text());
  check('?addr=all junk: the panel address list takes over (never an empty sub)', none.length > 0);
}

if (failures) { console.error(failures + ' FAILURE(S)'); process.exit(1); }
console.log('ADDR/PORT QUERY TESTS PASSED');
