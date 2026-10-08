/**
 * Link-shape regression tests for the subscription links the panel hands out.
 *
 * A user reported «the config doesn't work» for a fresh /u/<token> sub. Two
 * defects were found while chasing it:
 *
 *  1. proxyip/socks-relay variants appended their parameter with a hardcoded
 *     '?' even though the path already carries the early-data query — the link
 *     became `/vl/<seed>?ed=2560?proxyip=host`, and no client (or the worker
 *     itself) can parse that second query string, so the relay was silently
 *     ignored;
 *  2. nothing guaranteed that a generated link is *dialable by our own router*:
 *     every ws path the panel emits must match the shape isTunnelPath accepts.
 *
 * This test generates the real subscription (master /sub and a per-user /u/),
 * then asserts, for every link: single '?', ed present, proxyip parseable when
 * present, and the pathname matching the worker's own acceptance regex.
 *
 * Runs on the readable source or an obfuscated artifact: CAT_PANEL_WORKER=<path>
 * Usage: node scripts/panels/link-shape.test.mjs
 */
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = process.env.CAT_PANEL_WORKER ||
  path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
execFileSync(process.execPath, ['--check', workerPath], { stdio: 'pipe' });
console.log('✓ syntax check passed');

globalThis.fetch = () => Promise.reject(new Error('net-stubbed-in-tests'));
const mod = await import(workerPath);
const worker = mod.default;

let failures = 0;
const check = (name, cond, extra) => {
  if (cond) console.log('✓ ' + name);
  else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); }
};

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.has(k) ? this.m.get(k) : null; }
  async put(k, v) { this.m.set(k, v); }
  async delete(k) { this.m.delete(k); }
  async list() { return { keys: [] }; }
}

const HOST = 'catpanel-demo.workers.dev';
const MASTER = '11111111-2222-4333-8444-555555555555';
const USER = {
  id: 'a7ee8b54-a54c-459c-8410-829a8d7adb07',
  name: 'shape-user',
  enabled: true,
  createdAt: 1,
  expiresAt: 0,
  note: '',
  protocols: { vless: true, trojan: true },
};

function makeEnv() {
  const kv = new FakeKV();
  kv.m.set('cat:v6:users', JSON.stringify([USER]));
  return { CAT_KV: kv, UUID: MASTER };
}
const req = (p, env) => worker.fetch(new Request('https://' + HOST + p), env, { waitUntil() {} });

/** Every query parameter of a share link, decoded. */
function linkParams(line) {
  const query = line.slice(line.indexOf('?') + 1).split('#')[0];
  const out = new Map();
  for (const part of query.split('&')) {
    const at = part.indexOf('=');
    const key = decodeURIComponent(at < 0 ? part : part.slice(0, at));
    const value = at < 0 ? '' : decodeURIComponent(part.slice(at + 1));
    if (key && !out.has(key)) out.set(key, value);
  }
  return out;
}

const TUNNEL_PATHNAME = /^\/(vl|tr)\/[0-9a-z_-]{4,64}$/i;

function auditSub(raw, label) {
  const links = raw.split('\n').map((l) => l.trim()).filter((l) => l.includes('://'));
  check(label + ': subscription carries links', links.length > 0, String(links.length));
  check(label + ': only vless/trojan links', links.every((l) => /^(vless|trojan):\/\//.test(l)));

  const paths = links.map((l) => linkParams(l).get('path') || '');
  check(label + ': every link carries a ws path', paths.every((p) => p.startsWith('/')));
  check(
    label + ': no path has a second "?" (regression: ?ed=2560?proxyip=…)',
    paths.every((p) => (p.match(/\?/g) || []).length <= 1),
    paths.find((p) => (p.match(/\?/g) || []).length > 1),
  );
  check(
    label + ': shared params are &-separated when several are present',
    paths.every((p) => {
      const query = p.split('?')[1] || '';
      return !query.includes('?') && query.split('&').every((kv) => kv.includes('=') || kv === '');
    }),
  );
  check(label + ': every ws path is dialable by our own router shape',
    paths.every((p) => TUNNEL_PATHNAME.test(p.split('?')[0])),
    paths.find((p) => !TUNNEL_PATHNAME.test(p.split('?')[0])));

  const relayLinks = paths.filter((p) => p.includes('proxyip='));
  check(label + ': relay (proxyip) variants exist', relayLinks.length > 0, String(relayLinks.length));
  check(label + ': proxyip is a real, standalone parameter',
    relayLinks.every((p) => {
      const params = new Map(p.split('?')[1].split('&').map((kv) => [kv.split('=')[0], kv.split('=').slice(1).join('=')]));
      const value = params.get('proxyip') || '';
      return params.has('ed') && value.length > 3 && !value.includes('?');
    }),
    relayLinks.find((p) => !p.includes('&proxyip=')));

  check(label + ': early-data (ed) survives on relay variants',
    relayLinks.every((p) => /[?&]ed=\d+(&|$)/.test(p)),
    relayLinks.find((p) => !/[?&]ed=\d+(&|$)/.test(p)));
  return { links, paths, relayLinks };
}

/* ── master sub ─────────────────────────────────────────────────────── */
{
  const env = makeEnv();
  const response = await req('/sub/' + MASTER + '?raw=1', env);
  const raw = await response.text();
  check('master /sub answers 200', response.status === 200, String(response.status));
  auditSub(raw, 'master');
}

/* ── per-user sub (the /u/<token> link from the report) ─────────────── */
{
  const env = makeEnv();
  const response = await req('/u/' + USER.id, env);
  const raw = await response.text();
  check('per-user /u/<token> answers 200', response.status === 200, String(response.status));
  const { links, relayLinks } = auditSub(raw, 'user');

  // Both protocols the user is allowed must be present (trojan links are the
  // half that broke in the app's converter before Host-header support).
  check('user sub includes vless links', links.some((l) => l.startsWith('vless://')));
  check('user sub includes trojan links', links.some((l) => l.startsWith('trojan://')));
  check('user sub host param points at the worker host',
    links.every((l) => (linkParams(l).get('host') || '').length > 0),
    links.find((l) => !(linkParams(l).get('host') || '').length));
  check('relay variants carry the worker host too (Host routing)',
    relayLinks.length === 0 || relayLinks.every((p) => p.includes('proxyip=')));

  const proxy = await req('/u/' + USER.id + '/clash', env);
  check('clash output answers 200', proxy.status === 200, String(proxy.status));
}

console.log(failures === 0 ? 'LINK SHAPE TESTS PASSED' : `LINK SHAPE TESTS FAILED (${failures})`);
process.exit(failures === 0 ? 0 : 1);
