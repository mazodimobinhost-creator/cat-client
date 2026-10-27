/**
 * Panel on Vercel (6.69.38+): with env.CAT_PLATFORM = 'vercel' (set by assets/vercel/panel-adapter.mjs)
 * every subscription line must be dialable on Vercel — TLS :443 only, Vercel edge addresses or the
 * project domain, SNI == Host == the project domain, no Cloudflare clean IPs / alt ports / plain :80.
 * The same script without CAT_PLATFORM must keep its Cloudflare behaviour.
 *
 * Usage: node scripts/panels/vercel-platform.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const worker = (await import(workerPath)).default;

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + String(extra).slice(0, 300) : '')); }
};

const UUID = '719f3599-09b0-4c6f-9f8d-bd78f0d36c57';
const HOST = 'cat-panel-v7k2m.vercel.app';
const VERCEL = new Set(['vercel.com', '76.76.21.21', '64.29.17.65', '216.198.79.65', HOST]);

async function subLines(env, query = '') {
  const res = await worker.fetch(new Request('https://' + HOST + '/sub/' + UUID + query), env, { waitUntil() {} });
  const body = await res.text();
  let plain = body;
  try { const d = Buffer.from(body, 'base64').toString('utf8'); if (/:\/\//.test(d)) plain = d; } catch (_) { }
  return { status: res.status, lines: plain.split(/\r?\n/).filter((l) => /^(vless|trojan):\/\//.test(l)) };
}
function parse(line) {
  const u = new URL(line.replace(/^(vless|trojan):/, 'http:'));
  return { host: u.hostname.replace(/^\[|\]$/g, ''), port: Number(u.port || 80), q: u.searchParams };
}

const vercelEnv = { UUID, TROJAN_PASS: 'p-vercel-test', CAT_PLATFORM: 'vercel' };
const v = await subLines(vercelEnv);
check('vercel sub answers 200 with links', v.status === 200 && v.lines.length >= 4, v.status + ' / ' + v.lines.length);
const parsed = v.lines.map(parse);
check('every vercel line is :443', parsed.every((p) => p.port === 443), parsed.map((p) => p.port).join(','));
check('every vercel line is TLS', parsed.every((p) => p.q.get('security') === 'tls'));
check('every vercel address is a Vercel edge address or the project host', parsed.every((p) => VERCEL.has(p.host)), parsed.map((p) => p.host).join(','));
check('SNI == Host == project domain on every vercel line', parsed.every((p) => p.q.get('sni') === HOST && p.q.get('host') === HOST));
check('no ECH / cipher-suite pinning on vercel lines', parsed.every((p) => !p.q.get('ech') && !p.q.get('cs')));
check('vercel sub carries the project host itself', parsed.some((p) => p.host === HOST));
check('vercel sub carries both VLESS and Trojan', v.lines.some((l) => l.startsWith('vless://')) && v.lines.some((l) => l.startsWith('trojan://')));
const ported = (await subLines(vercelEnv, '?port=80,8443,443')).lines.map(parse);
check('?port= cannot re-add non-443 ports on vercel', ported.length > 0 && ported.every((p) => p.port === 443), ported.map((p) => p.port).join(','));

// 6.69.39: no 🎯 ProxyIP / 🧦 / 🧬 duplicates on Vercel (it dials every destination directly).
check('vercel sub has no ProxyIP/SOCKS/SNI-spoof entries', !v.lines.some((l) => /proxyip|pyip|%F0%9F%8E%AF|%F0%9F%A7%A6|%F0%9F%A7%AC/i.test(l)), v.lines.find((l) => /proxyip|%F0%9F%8E%AF/i.test(l)));
// A Cloudflare clean IP can never reach a Vercel project: only Vercel's own networks survive.
const mixed = (await subLines(Object.assign({}, vercelEnv, { CF_IPS: '104.16.1.1,188.114.98.0,76.76.21.99,216.150.16.7' }))).lines.map(parse);
check('vercel keeps scanned Vercel IPs from the list', mixed.some((p) => p.host === '76.76.21.99') && mixed.some((p) => p.host === '216.150.16.7'), mixed.map((p) => p.host).join(','));
check('vercel drops Cloudflare IPs from the list', !mixed.some((p) => p.host === '104.16.1.1' || p.host === '188.114.98.0'));
// Without storage a new user would only live in one instance's memory (the «sub shows 0 configs» bug).
const login = await worker.fetch(new Request('https://' + HOST + '/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin' }) }), vercelEnv, { waitUntil() {} });
const cookie = String(login.headers.get('set-cookie') || '').split(';')[0];
check('vercel panel login admin/admin works', login.status === 200 && cookie.includes('='), login.status);
const addUser = await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json', cookie, origin: 'https://' + HOST }, body: JSON.stringify({ name: 'ali' }) }), vercelEnv, { waitUntil() {} });
const addBody = await addUser.text();
check('vercel without storage refuses to create a user (409, explains storage)', addUser.status === 409 && /storage/i.test(addBody), addUser.status + ' ' + addBody);
// …and with storage attached the same request creates the user.
const memKv = (() => { const m = new Map(); return { async get(k) { return m.has(k) ? m.get(k) : null; }, async put(k, v) { m.set(k, String(v)); }, async delete(k) { m.delete(k); }, async list() { return { keys: [...m.keys()].map((name) => ({ name })), list_complete: true }; } }; })();
const storedEnv = Object.assign({}, vercelEnv, { CAT_KV: memKv });
const login2 = await worker.fetch(new Request('https://' + HOST + '/api/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: 'admin' }) }), storedEnv, { waitUntil() {} });
const cookie2 = String(login2.headers.get('set-cookie') || '').split(';')[0];
const add2 = await worker.fetch(new Request('https://' + HOST + '/api/users', { method: 'POST', headers: { 'content-type': 'application/json', cookie: cookie2, origin: 'https://' + HOST }, body: JSON.stringify({ name: 'ali' }) }), storedEnv, { waitUntil() {} });
check('vercel with storage creates the user', add2.status === 201, add2.status + ' ' + (await add2.text()).slice(0, 120));

const cf = (await subLines({ UUID, TROJAN_PASS: 'p-cf-test' })).lines.map(parse);
check('cloudflare behaviour unchanged: non-Vercel addresses still present', cf.some((p) => !VERCEL.has(p.host)));
check('cloudflare behaviour unchanged: alternate ports still present', cf.some((p) => p.port !== 443));

if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all vercel-platform checks passed');
