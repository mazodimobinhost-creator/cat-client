/**
 * TLS SNI must equal the HTTP Host — the reason the generated configs «don't work».
 *
 * Cloudflare checks that the SNI of a TLS handshake equals the Host header of the request inside it and
 * answers an early, unlogged 403 otherwise («domain fronting» is blocked). The panel used to put a rotating
 * DIFFERENT domain (icook.tw, speedtest.net, visa.com …) into the SNI of every TLS config while Host stayed
 * the worker — so all 44 TLS lines of the user's subscription were rejected by Cloudflare, no matter how
 * clean the IPs were. Only the non-TLS port lines could connect.
 *
 * Pins, for EVERY output format (links, Clash, sing-box, Xray, panel-user subs):
 *   - by default each TLS entry carries SNI == Host; plain (non-TLS) entries carry no SNI at all;
 *   - the user's exact link (`ports=80,443,8080&amp;limit=60`) keeps working: limit/ports honoured;
 *   - ECH (?ech=1) keeps the inner SNI == Host;
 *   - SNI spoofing stays available but is strictly opt-in (settings.sniFront) and togglable both ways;
 *   - the scanner endpoint keeps its own meaning (skk.moe).
 *
 * Usage: node scripts/panels/sni-host.test.mjs   (CAT_PANEL_WORKER=<file> to test an artifact)
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
const env = { CAT_KV: new FakeKV(), UUID, OPEN_PANEL: 'true' };
const call = (p, method = 'GET', body) => worker.fetch(
  new Request('https://' + HOST + p, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body && JSON.stringify(body) }),
  env, { waitUntil() {} });
const jsonOf = async (r) => { try { return await r.json(); } catch { return null; } };

const ips = ['104.16.88.20', '104.17.12.4', '172.64.145.9', '162.159.140.8', '188.114.97.3'];
const added = await jsonOf(await call('/api/ips', 'POST', { ips, source: 'manual' }));
check('clean IPs persist (the earlier complaint stays fixed)', added && added.ok && added.persisted === true && added.count === ips.length, JSON.stringify(added));
const made = await jsonOf(await call('/api/users', 'POST', { name: 'ali' }));
const token = made && made.user && made.user.id;
check('a panel user can be created', !!token, JSON.stringify(made).slice(0, 120));

// ── format readers: every one returns [{ tls, sni, host }] ───────────────────
const dec = (v) => { try { return decodeURIComponent(v || ''); } catch { return String(v || ''); } };
const fromLinks = (text) => String(text).split(/\r?\n/).filter((l) => /^(vless|trojan):\/\//.test(l)).map((l) => ({
  tls: /[?&]security=tls/.test(l),
  sni: dec((/[?&]sni=([^&#]*)/.exec(l) || [])[1]),
  host: dec((/[?&]host=([^&#]*)/.exec(l) || [])[1]),
  hasEch: /[?&]ech=/.test(l),
}));
const maybeB64 = (t) => (/^(vless|trojan):\/\//.test(t.trim()) ? t : Buffer.from(t.trim(), 'base64').toString('utf8'));
const fromClash = (yaml) => {
  const out = [];
  for (const block of String(yaml).split(/\n\s*- name:/).slice(1)) {
    if (!/\btype:\s*(vless|trojan)\b/.test(block)) continue;
    const tls = /\btls:\s*true\b/.test(block);
    out.push({ tls, sni: ((/servername:\s*"?([^"\n]+)"?/.exec(block) || [])[1] || '').trim(), host: ((/Host:\s*"?([^"\n]+)"?/.exec(block) || [])[1] || '').trim() });
  }
  return out;
};
const fromSingbox = (text) => (JSON.parse(text).outbounds || []).filter((o) => o.type === 'vless' || o.type === 'trojan').map((o) => ({
  tls: !!(o.tls && o.tls.enabled), sni: (o.tls && o.tls.server_name) || '', host: (o.transport && o.transport.headers && o.transport.headers.Host) || '',
}));
const fromXray = (text) => {
  const doc = JSON.parse(maybeB64(text).trim().startsWith('[') || maybeB64(text).trim().startsWith('{') ? maybeB64(text) : text);
  const out = [];
  const walk = (o) => {
    if (!o || typeof o !== 'object') return;
    if (o.streamSettings) {
      const ss = o.streamSettings;
      out.push({ tls: ss.security === 'tls', sni: (ss.tlsSettings && ss.tlsSettings.serverName) || '', host: (ss.wsSettings && ss.wsSettings.headers && ss.wsSettings.headers.Host) || '' });
    }
    for (const v of Object.values(o)) walk(v);
  };
  walk(doc);
  return out;
};

const read = async (p) => { const r = await call(p); return { status: r.status, text: await r.text() }; };
const MASTER_URL = '/sub/' + UUID + '?ports=80%2C443%2C8080&amp;limit=60'; // verbatim from the user's report

function expectHostSni(label, entries) {
  const tls = entries.filter((e) => e.tls);
  const plain = entries.filter((e) => !e.tls);
  check(label + ': has TLS entries', tls.length > 0, entries.length + ' entries');
  const bad = tls.filter((e) => !e.sni || e.sni.toLowerCase() !== HOST || (e.host && e.host.toLowerCase() !== HOST));
  check(label + ': every TLS entry has SNI == Host (Cloudflare 403s anything else)', bad.length === 0, bad.length + ' of ' + tls.length + ' differ, e.g. ' + JSON.stringify(bad[0]));
  check(label + ': non-TLS entries carry no SNI', plain.every((e) => !e.sni), JSON.stringify(plain.find((e) => e.sni)));
}

// ── 1) the user's exact link ──────────────────────────────────────────────────
{
  const r = await read(MASTER_URL);
  check('the user link answers 200', r.status === 200, r.status);
  const e = fromLinks(maybeB64(r.text));
  check('&amp; in the link is tolerated: limit=60 honoured', e.length === 60, e.length);
  check('ports from the link are honoured (TLS 443 and plain 80)', e.some((x) => x.tls) && e.some((x) => !x.tls));
  expectHostSni('user link', e);
}
// ── 2) every other format and the panel-user subscription ────────────────────
{
  const r = await read('/sub/' + UUID + '?ports=443&limit=24'); expectHostSni('links', fromLinks(maybeB64(r.text)));
  const c = await read('/clash/' + UUID + '?limit=24'); expectHostSni('clash', fromClash(c.text));
  const s = await read('/singbox/' + UUID + '?limit=24'); expectHostSni('sing-box', fromSingbox(s.text));
  const x = await read('/xray/' + UUID + '?limit=24'); expectHostSni('xray', fromXray(x.text));
  if (token) {
    const u = await read('/u/' + token + '?limit=24'); expectHostSni('panel user /u/<token>', fromLinks(maybeB64(u.text)));
    const uc = await read('/u/' + token + '/clash?limit=24'); expectHostSni('panel user clash', fromClash(uc.text));
  }
}
// ── 3) ECH keeps the inner SNI == Host; ?sni= pins one SNI ────────────────────
{
  const r = await read('/sub/' + UUID + '?ports=443&limit=12&ech=1');
  const e = fromLinks(maybeB64(r.text)).filter((x) => x.tls);
  check('?ech=1: TLS entries carry an ech= param', e.length > 0 && e.every((x) => x.hasEch), e.length);
  check('?ech=1: inner SNI is still the Host', e.every((x) => x.sni.toLowerCase() === HOST), JSON.stringify(e[0]));
  const p = await read('/sub/' + UUID + '?ports=443&limit=12&sni=time.is');
  check('?sni= still pins one explicit SNI (per-link opt-in)', fromLinks(maybeB64(p.text)).filter((x) => x.tls).every((x) => x.sni === 'time.is'));
}
// ── 4) spoofing: strictly opt-in, fully functional, togglable both ways ──────
{
  const st0 = await jsonOf(await call('/api/settings'));
  check('settings expose sniFront, default false (the app reads it)', st0 && st0.settings && st0.settings.sniFront === false, JSON.stringify(st0 && st0.settings && st0.settings.sniFront));
  const on = await jsonOf(await call('/api/settings', 'PUT', { sniFront: true, sniRotate: true }));
  check('PUT sniFront:true persists', on && on.ok && on.persisted === true && on.settings.sniFront === true, JSON.stringify(on && on.persisted));
  const r = await read('/sub/' + UUID + '?ports=443&limit=24');
  const e = fromLinks(maybeB64(r.text)).filter((x) => x.tls);
  const distinct = new Set(e.map((x) => x.sni));
  check('spoofing on: the rotating pool is back (>1 distinct SNI)', distinct.size > 1, [...distinct].join(','));
  check('spoofing on: the worker host is never in the pool', !distinct.has(HOST), [...distinct].join(','));
  const off = await jsonOf(await call('/api/settings', 'PUT', { sniFront: false }));
  check('PUT sniFront:false persists', off && off.ok && off.settings.sniFront === false);
  expectHostSni('after switching spoofing off again', fromLinks(maybeB64((await read('/sub/' + UUID + '?ports=443&limit=24')).text)));
}
// ── 5) the scanner keeps its own meaning; the page wires the switch ──────────
{
  const t = await jsonOf(await call('/api/scan-targets.json'));
  check('/api/scan-targets.json sni stays the scan SNI (skk.moe)', t && t.sni === 'skk.moe', t && t.sni);
  const T = mod._testing;
  const html = T.panelPage({ CAT_PANEL_KV: new Map() }, T.defaultSettings(), HOST, 'u123');
  check('settings form saves the master switch as sniFront', html.includes("sniFront:$('#swSniRot').classList.contains('on')"));
  check('the switch reflects settings.sniFront', html.includes("$('#swSniRot').classList.toggle('on',s.sniFront===true)"));
  check('hero chip shows «worker host ✓» when spoofing is off', html.includes('hero_sni_host') && html.includes('hero_sni_pool'));
}

// ── 6) the Android app talks to this setting: keep both sides in step ───────────
{
  const { readFileSync } = await import('node:fs');
  const kt = readFileSync(path.join(here, '../../app/src/main/java/com/cat/client/MainActivity.kt'), 'utf-8');
  check('app: «Make panel main SNI» opts in to spoofing (else a 6.54+ panel silently ignores it)', /put\("sni", sni\)\.put\("sniFront", true\)/.test(kt));
  check('app: the status dialog reads sniFront (host ✓ / spoofed ⚠️ / old panel)', /st\.has\("sniFront"\) && !st\.optBoolean\("sniFront"\)/.test(kt) && kt.includes('R.string.panel_st_sni_spoof'));
  check('app: the main-SNI dialog warns that Cloudflare rejects SNI != host', kt.includes('R.string.sni_main_warn'));
}

console.log(failures ? '\nSNI == HOST TESTS FAILED (' + failures + ')' : '\nSNI == HOST TESTS PASSED');
process.exit(failures ? 1 : 0);
