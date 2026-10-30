// 6.79.3 — a panel on a Node host (SmarterASP / Railway / Lucity / Shiprr…), owner's report:
//   «the site's own IP was not in the configs — it must be put there automatically»,
//   «the exit location found 6 German exits but says ProxyIP/SOCKS do not apply on this host»,
//   «the Clean IP tab is empty».
// Checks: the host's resolved IPv4s ride next to the name (🏠 Server IP, same SNI/Host, never a Cloudflare IP);
// a user's own 🎯/🧦 exits reach the sub on a node; dialTarget sends a pinned ProxyIP the Cloudflare-hosted NAMES
// (by DNS) and every other site direct; the panel page has the node «server IPs» view and no longer hides Locations.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const WORKER = process.env.CAT_PANEL_WORKER || new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).pathname;
const { _testing: T, default: worker } = await import(pathToFileURL(WORKER).href);

const DNS = { 'site1.example.test': ['203.0.113.7', '104.16.5.5'], 'cfsite.example.test': ['104.16.1.1'], 'plain.example.test': ['198.51.100.99'] };
globalThis.__catResolve4 = async (n) => { if (DNS[n]) return DNS[n].slice(); throw new Error('ENOTFOUND ' + n); };
const ENV = { CAT_PLATFORM: 'node', CAT_NODE_EDGE: 'tls', CAT_NODE_PORT: '443', DATA_PLANE_ENABLED: 'true' };
const HOST = 'site1.example.test';
const UUID = '5d2b8f3a-1c4e-4f6a-9b7d-0e1f2a3b4c5d';
const settings = () => T.normalizeSettings(T.defaultSettings());
// 6.79.4: a typed IP rides the sub only after the panel proved it reaches this host (WS 101 with SNI = the name).
const PROBES = [];
const PROBE_ANSWER = { '198.51.100.44': 101, '203.0.113.9': 101, '198.51.100.66': 404 };
T.__setSockets({
  connect({ hostname, port }, opts) {
    PROBES.push({ hostname, port, opts: opts || {} });
    const code = PROBE_ANSWER[hostname];
    let sent = '';
    return {
      opened: code ? Promise.resolve() : Promise.reject(new Error('connect refused')),
      writable: new WritableStream({ write(c) { sent += new TextDecoder().decode(c); } }),
      readable: new ReadableStream({ start(ctl) { if (code) ctl.enqueue(new TextEncoder().encode('HTTP/1.1 ' + code + ' X\r\n\r\n')); ctl.close(); } }),
      get sent() { return sent; },
      close() {},
    };
  },
});

test('the host\'s own IPv4s are found automatically — a Cloudflare IP never (it cannot reach the host)', async () => {
  const ips = await T.nodeHostIps(HOST, true);
  assert.deepEqual(ips, ['203.0.113.7']);
  assert.deepEqual(T.nodeHostIpsCached(HOST), ['203.0.113.7']);
  assert.deepEqual(await T.nodeHostIps('203.0.113.50'), [], 'an IP host needs no lookup');
});

test('configs: the name AND the server IP, both with SNI = Host = the name, port 443, «🏠 Server IP»', async () => {
  await T.nodeHostIps(HOST, true);
  const s = settings();
  assert.deepEqual(T.addressList(HOST, ENV, s), [HOST, '203.0.113.7']);
  const { entries } = T.buildConfigEntries(HOST, ENV, s, UUID, null, {});
  const viaIp = entries.filter((e) => e.addr === '203.0.113.7');
  assert.ok(entries.some((e) => e.addr === HOST));
  assert.equal(viaIp.length, 2, 'VLESS + Trojan on the server IP');
  for (const e of entries) {
    const u = new URL(e.link.replace(/^(vless|trojan):\/\//, 'http://'));
    assert.equal(u.searchParams.get('sni'), HOST);
    assert.equal(u.searchParams.get('host'), HOST);
    assert.equal(Number(u.port), 443);
  }
  assert.ok(viaIp.every((e) => /🏠/.test(e.name) && /Server IP/.test(e.name)), viaIp.map((e) => e.name).join(' | '));
  assert.ok(!entries.some((e) => e.addr === '104.16.5.5'));
});

test('typed IPs (owner / this user) are used on a node too — Cloudflare IPs dropped', async () => {
  await T.nodeHostIps(HOST, true);
  const s = Object.assign(settings(), { ips: ['198.51.100.44', '104.17.2.2'] });
  assert.deepEqual(T.addressList(HOST, ENV, s), [HOST, '203.0.113.7'], 'not tested yet → not in the sub');
  await T.nodeVerifyTyped(ENV, s, HOST);
  assert.deepEqual(T.addressList(HOST, ENV, s), [HOST, '203.0.113.7', '198.51.100.44']);
});

test('«10 configs → 10 work»: an IP that does not reach THIS host (404 / refused) stays out; the check uses SNI = Host = the name', async () => {
  await T.nodeHostIps(HOST, true);
  const s = Object.assign(settings(), { ips: ['198.51.100.66', '198.51.100.77', '198.51.100.44'] });
  PROBES.length = 0;
  await T.nodeVerifyTyped(ENV, s, HOST, true);
  assert.deepEqual(PROBES.map((p) => p.hostname).sort(), ['198.51.100.44', '198.51.100.66', '198.51.100.77']);
  assert.ok(PROBES.every((p) => p.port === 443 && p.opts.secureTransport === 'on' && p.opts.servername === HOST), JSON.stringify(PROBES.map((p) => p.opts)));
  assert.equal(T.nodeIpCheck(HOST, '198.51.100.66').status, 404);
  assert.equal(T.nodeIpCheck(HOST, '198.51.100.77').ok, false);
  assert.deepEqual(T.addressList(HOST, ENV, s), [HOST, '203.0.113.7', '198.51.100.44']);
  const { entries } = T.buildConfigEntries(HOST, ENV, s, UUID, null, {});
  assert.ok(!entries.some((e) => /198\.51\.100\.(66|77)/.test(e.addr)));
  PROBES.length = 0;
  await T.nodeVerifyTyped(ENV, s, HOST);
  assert.equal(PROBES.length, 0, 'fresh results are cached — no re-dial on every sub fetch');
  const plain = Object.assign({}, ENV, { CAT_NODE_EDGE: 'plain', CAT_NODE_PORT: '25565' });
  await T.nodeVerifyTyped(plain, Object.assign(settings(), { ips: ['198.51.100.44'] }), 'other.example.test');
  assert.equal(PROBES[0].port, 25565);
  assert.ok(!PROBES[0].opts.secureTransport, 'a plain edge is checked without TLS on its one port');
});

test('client regexes survive the page template (manual IPs / SOCKS lists split on spaces, not on the letter s)', async () => {
  const page = String(T.panelPage(ENV, settings(), HOST, UUID));
  assert.ok(!page.includes('split(/[s,]+/)'));
  assert.ok(page.includes("split(/[\\s,]+/)"));
  assert.ok(page.includes('match(/:(\\d{1,5})$/)'));
});

test('the free clean-IP library / ProxyIP feeds never reach a node sub (CI with network: 32 lines)', async () => {
  await T.nodeHostIps(HOST, true);
  const s = Object.assign(settings(), {
    ips: ['104.19.150.96:443', '198.51.100.44:2053', '203.0.113.9', '45.85.118.5:443'],
    repoIps: ['104.19.150.96:443', '45.85.118.5:443'],
    proxyIps: ['198.51.100.20:443', 'socks5://198.51.100.30:1080'], proxyRepoAuto: true,
  });
  await T.nodeVerifyTyped(ENV, s, HOST);
  assert.deepEqual(T.addressList(HOST, ENV, s), [HOST, '203.0.113.7', '198.51.100.44', '203.0.113.9']);
  const { entries } = T.buildConfigEntries(HOST, ENV, s, UUID, null, {});
  assert.deepEqual([...new Set(entries.map((e) => e.addr))].sort(), [HOST, '198.51.100.44', '203.0.113.7', '203.0.113.9'].sort(), entries.map((e) => e.name + ' ' + e.addr + ':' + e.port).join(' | '));
  assert.ok(entries.every((e) => e.port === 443), 'a node answers on its one port — a typed :2053 is not advertised');
  assert.ok(!entries.some((e) => /^🎯|^🧦/.test(e.name)), 'panel-wide / feed exits are not listed on a node — only a pinned user\'s');
});

test('a user pinned to Germany: their 🎯 ProxyIP and 🧦 SOCKS exits are in their sub on a node', async () => {
  await T.nodeHostIps(HOST, true);
  const user = { id: 'u1', exits: ['198.51.100.20:443', 'socks5://u:p@198.51.100.30:1080'], exitCc: 'DE' };
  const s = T.withUserExits(settings(), user);
  const { entries } = T.buildConfigEntries(HOST, ENV, s, UUID, null, {});
  const px = entries.filter((e) => /^🎯/.test(e.name));
  const sx = entries.filter((e) => /^🧦/.test(e.name));
  assert.ok(px.length >= 2 && px.every((e) => decodeURIComponent(e.wsPath).includes('proxyip=198.51.100.20:443') && e.addr === HOST && e.port === 443), JSON.stringify(px.map((e) => [e.name, e.wsPath])));
  assert.ok(sx.length >= 2, entries.map((e) => e.name).join(' | '));
  assert.ok(!entries.some((e) => /^🧬/.test(e.name)), 'no SNI-spoof lines on a node');
});

test('no exits typed → no built-in default ProxyIP lines on a node', async () => {
  const { entries } = T.buildConfigEntries(HOST, ENV, settings(), UUID, null, {});
  assert.ok(!entries.some((e) => /^🎯|^🧦/.test(e.name)), entries.map((e) => e.name).join(' | '));
});

function fakeSockets(log) {
  return {
    connect({ hostname, port }) {
      log.push(hostname + ':' + port);
      return { opened: Promise.resolve(), readable: new ReadableStream(), writable: new WritableStream(), close() {} };
    },
  };
}

test('data plane on a node: a pinned 🎯 ProxyIP carries the Cloudflare-hosted names, other sites go direct', async () => {
  const log = [];
  const r1 = await T.dialTarget('cfsite.example.test', 443, ENV, settings(), null, '198.51.100.20:443', fakeSockets(log));
  assert.equal(r1.via, 'proxy:198.51.100.20:443');
  assert.equal(log[0], '198.51.100.20:443');
  const r2 = await T.dialTarget('plain.example.test', 443, ENV, settings(), null, '198.51.100.20:443', fakeSockets(log));
  assert.equal(r2.via, 'direct');
  assert.equal(log[1], 'plain.example.test:443');
});

test('panel page on a node: «server IPs» view present, body marked node (Clean IP no longer blank)', async () => {
  const page = String(T.panelPage(ENV, settings(), HOST, UUID));
  assert.ok(page.includes('id="v-nodeips"'));
  assert.ok(page.includes('data-plat="node"'));
  assert.ok(page.includes('function nipLoad('));
});

test('rendered page: node CSS shows the server-IP view and Locations (Vercel still hides Locations)', async () => {
  const page = String(T.panelPage(ENV, settings(), HOST, UUID)).replace(/\s+/g, ' ');
  assert.ok(page.includes('[data-plat=vercel] :is(#v-loc,#v-loc-cards,#v-socks)'));
  assert.ok(!page.includes('[data-plat] :is(#v-loc'));
  assert.ok(page.includes('body:not([data-plat=node]) #v-nodeips{display:none!important}'));
  assert.ok(page.includes("'/api/node/ips'"));
});

test('/api/node/ips: each typed IP comes back ✅/❌ with its test result; the page has the per-host capability list + save & test', async () => {
  class KV { constructor() { this.m = new Map(); } async get(k) { return this.m.has(k) ? this.m.get(k) : null; } async put(k, v) { this.m.set(k, v); } async delete(k) { this.m.delete(k); } async list() { return { keys: [] }; } }
  const env = Object.assign({}, ENV, { CAT_KV: new KV(), UUID, CAT_HOST: 'railway' });
  const call = (path, init) => worker.fetch(new Request('https://' + HOST + path, Object.assign({ headers: { authorization: 'Bearer admin', 'content-type': 'application/json' } }, init)), env, { waitUntil() {} });
  const put = await call('/api/settings', { method: 'PUT', body: JSON.stringify({ ips: ['198.51.100.44', '198.51.100.66'] }) });
  assert.equal(put.status, 200);
  const j = await (await call('/api/node/ips?refresh=1')).json();
  assert.equal(j.platform, 'railway');
  const by = Object.fromEntries(j.typed.map((x) => [x.ip, x]));
  assert.equal(by['198.51.100.44'].ok, true);
  assert.equal(by['198.51.100.66'].ok, false);
  assert.equal(by['198.51.100.66'].checked, true);
  const page = String(T.panelPage(env, settings(), HOST, UUID));
  for (const id of ['id="nipCaps"', 'id="nipTyped"', 'id="nipSave"', 'function nipCaps(']) assert.ok(page.includes(id), id);
});
