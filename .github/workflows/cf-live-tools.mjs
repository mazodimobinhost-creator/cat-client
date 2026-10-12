#!/usr/bin/env node
/**
 * TEMPORARY tooling for the one-off live Cloudflare test (cf-live-test.yml).
 *
 * It is deleted from the tree again when the test is finished — it is not part of
 * the product. Nothing here prints a token, and the two values that could be
 * abused (the test panel password and the app-style UUID) are masked in the log
 * the moment they are generated.
 *
 * Subcommands:
 *   preflight                       verify the token, print account + subdomain
 *   kv-create                       create the throwaway KV namespace (KV_ID=…)
 *   config                          build wrangler.cf-test.jsonc from wrangler.jsonc
 *   gen-credentials                 random panel password + app-style UUID (masked)
 *   uuid --host=HOST                the UUID a passwordless panel derives
 *   deploy --main=PATH [--bundle]   wrangler deploy of one artifact
 *   upload-app                      the app's own upload path (multipart PUT)
 *   stage --label=L --mode=open|locked --sub-key=K
 *                                   wrangler tail + live checks of one stage
 *   summary                         comparison table; fails the job on a hard failure
 *   cleanup                         delete the worker + KV namespace, then verify
 */
import { readFileSync, writeFileSync, appendFileSync, mkdirSync, openSync, closeSync, existsSync, readdirSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { createHash, randomUUID, randomBytes } from 'node:crypto';

const API = 'https://api.cloudflare.com/client/v4';
const TOKEN = process.env.CLOUDFLARE_API_TOKEN || '';
const ACCT = process.env.CLOUDFLARE_ACCOUNT_ID || '';
const WORKER = process.env.WORKER || 'catpanel-test';
const KV_TITLE = process.env.KV_TITLE || 'catpanel-test-kv';
const OUT = process.env.OUT || '/tmp/cf-live';
const MASK = '***MASKED-BY-CAT-PANEL***';
const WRANGLER = './node_modules/.bin/wrangler';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const envFile = (kv) => appendFileSync(process.env.GITHUB_ENV, kv + '\n');

const [, , cmd, ...rest] = process.argv;
const arg = (name, dflt = '') => {
  const hit = rest.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) return dflt;
  const i = hit.indexOf('=');
  return i === -1 ? rest[rest.indexOf(hit) + 1] : hit.slice(i + 1);
};
const flag = (name) => rest.includes(`--${name}`);

async function cf(path, init = {}) {
  const res = await fetch(API + path, Object.assign({}, init, {
    headers: Object.assign({ Authorization: 'Bearer ' + TOKEN, 'content-type': 'application/json' }, init.headers || {}),
  }));
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* keep raw */ }
  return { status: res.status, json, text };
}
const errText = (r) => (r.json ? JSON.stringify(r.json.errors || r.json) : r.text).slice(0, 300);

/* ────────────────────────── subcommands ────────────────────────── */

async function preflight() {
  console.log('worker:', WORKER, '| kv title:', KV_TITLE, '| account id set:', !!ACCT, '| token set:', !!TOKEN);
  if (!TOKEN || !ACCT) { console.error('::error::CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID are not available to this run'); process.exit(1); }
  const v = await cf('/user/tokens/verify');
  console.log('token verify :', v.status, v.json ? v.json.result?.status : v.text.slice(0, 120));
  if (v.status !== 200) process.exit(1);
  const sub = await cf(`/accounts/${ACCT}/workers/subdomain`);
  const subdomain = sub.json?.result?.subdomain || '';
  console.log('subdomain    :', subdomain || `(missing — ${errText(sub)})`);
  if (subdomain) {
    envFile('SUBDOMAIN=' + subdomain);
    envFile('BASE=' + `https://${WORKER}.${subdomain}.workers.dev`);
    console.log('base url     :', `https://${WORKER}.${subdomain}.workers.dev`);
  }
  // Read-only: what else lives on this account (so the report can show that
  // nothing but catpanel-test was created/removed).
  const list = await cf(`/accounts/${ACCT}/workers/scripts`);
  console.log('scripts before:', (list.json?.result || []).map((s) => s.id).join(', ') || `(${errText(list)})`);
}

async function kvCreate() {
  const list = await cf(`/accounts/${ACCT}/storage/kv/namespaces?per_page=100`);
  const found = (list.json?.result || []).find((n) => n.title === KV_TITLE);
  let id = found?.id;
  if (id) console.log(`note: a namespace titled «${KV_TITLE}» already existed — reusing it`);
  else {
    const made = await cf(`/accounts/${ACCT}/storage/kv/namespaces`, { method: 'POST', body: JSON.stringify({ title: KV_TITLE }) });
    if (made.status !== 200 || !made.json?.result?.id) {
      console.error('::error::KV namespace create failed:', made.status, errText(made));
      process.exit(1);
    }
    id = made.json.result.id;
    console.log('created KV namespace:', id);
  }
  console.log('KV_ID=' + id);
  envFile('KV_ID=' + id);
  console.log('all namespaces on the account:', (list.json?.result || []).map((n) => `${n.title}:${n.id}`).join(', ') || '(none listed)');
}

function stripJsonc(raw) {
  let out = '', inStr = false, esc = false, line = false, block = false;
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i], n = raw[i + 1];
    if (line) { if (c === '\n') { line = false; out += c; } continue; }
    if (block) { if (c === '*' && n === '/') { block = false; i++; } continue; }
    if (inStr) { out += c; if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false; continue; }
    if (c === '/' && n === '/') { line = true; i++; continue; }
    if (c === '/' && n === '*') { block = true; i++; continue; }
    if (c === '"') inStr = true;
    out += c;
  }
  return out;
}

function config() {
  const repo = JSON.parse(stripJsonc(readFileSync('wrangler.jsonc', 'utf8')));
  const before = {
    name: repo.name, main: repo.main, compatibility_date: repo.compatibility_date,
    compatibility_flags: repo.compatibility_flags || null, kv: repo.kv_namespaces, vars: repo.vars,
    triggers: repo.triggers || null, observability: repo.observability || null,
  };
  const main = arg('main', 'dist-panel/catpanel.min.js');
  repo.name = WORKER;
  repo.main = main;
  repo.kv_namespaces = [{ binding: 'CAT_KV', id: process.env.KV_ID }];
  delete repo.triggers; // a throwaway worker must not run the 12h cron
  writeFileSync('wrangler.cf-test.jsonc', JSON.stringify(repo, null, 2) + '\n');
  console.log('wrangler.cf-test.jsonc built from wrangler.jsonc:');
  console.log('  name              :', before.name, '→', repo.name);
  console.log('  main              :', before.main, '→', repo.main);
  console.log('  kv cat_kv         : (auto-provisioned / no id)', '→', process.env.KV_ID ? 'id ' + process.env.KV_ID : '(MISSING ID)');
  console.log('  triggers.crons    :', before.triggers ? JSON.stringify(before.triggers) : 'none', '→ removed (throwaway worker)');
  console.log('  compatibility_date:', before.compatibility_date, '(unchanged)');
  console.log('  compatibility_flags:', before.compatibility_flags, '(unchanged)');
  console.log('  vars              :', JSON.stringify(before.vars), '(unchanged)');
  console.log('  observability     :', JSON.stringify(before.observability), '(unchanged)');
}

function uuidFor(host) {
  const dig = createHash('sha256').update('cat-panel:uuid:' + host).digest();
  const b = Buffer.from(dig);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const hex = b.toString('hex');
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20, 32);
}

function genCredentials() {
  const pw = randomBytes(12).toString('base64url');
  const uuid = randomUUID();
  console.log('::add-mask::' + pw);
  console.log('::add-mask::' + uuid);
  envFile('PANEL_TEST_PW=' + pw);
  envFile('APP_UUID=' + uuid);
  console.log('generated a panel password (24-char class) and an app-style UUID — both masked in this log; only lengths are printed:');
  console.log('  password length:', pw.length, '| uuid length:', uuid.length);
}

function run(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, Object.assign({ stdio: ['ignore', 'pipe', 'pipe'] }, opts));
    let out = '';
    p.stdout.on('data', (d) => { out += d; process.stdout.write(d); });
    p.stderr.on('data', (d) => { out += d; process.stderr.write(d); });
    p.on('close', (code) => resolve({ code, out }));
  });
}

async function deploy() {
  const main = arg('main');
  const bundle = flag('bundle');
  const args = ['deploy', '--config', 'wrangler.cf-test.jsonc'];
  if (!bundle) args.push('--no-bundle');
  console.log('# wrangler', args.join(' '), '| main =', main);
  const { code, out } = await run(WRANGLER, args);
  const size = out.split('\n').filter((l) => /Total Upload|Uploaded|gzip|Deployed|workers\.dev/.test(l));
  console.log('deploy exit:', code);
  console.log(size.join('\n'));
  if (code !== 0) { console.error('::error::deploy failed'); }
  return code;
}

async function uploadApp() {
  const uuid = process.env.APP_UUID, pw = process.env.PANEL_TEST_PW, kvId = process.env.KV_ID;
  const script = readFileSync('dist-panel/catpanel.obf.js', 'utf8');
  const bindings = [{ type: 'plain_text', name: 'UUID', text: uuid }];
  if (pw) bindings.push({ type: 'secret_text', name: 'PANEL_PASSWORD', text: pw });
  bindings.push({ type: 'kv_namespace', name: 'CAT_KV', namespace_id: kvId });
  const metadata = { main_module: 'worker.js', bindings, compatibility_date: '2025-03-04', compatibility_flags: ['nodejs_compat'] };
  const boundary = '----catclient' + Date.now();
  const head = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n` +
    JSON.stringify(metadata) + '\r\n' +
    `--${boundary}\r\nContent-Disposition: form-data; name="worker.js"; filename="worker.js"\r\n` +
    'Content-Type: application/javascript+module\r\n\r\n');
  const tail = Buffer.from(`\r\n--${boundary}--\r\n`);
  const body = Buffer.concat([head, Buffer.from(script, 'utf8'), tail]);
  const redacted = JSON.parse(JSON.stringify(metadata));
  for (const b of redacted.bindings) if (b.type !== 'kv_namespace') b.text = `<${b.type}: ${String(b.text).length} chars, not printed>`;
  console.log('app-style upload (byte-for-byte the same builder as CloudflareWorker.cfUploadWorker):');
  console.log('  url        :', `PUT ${API}/accounts/<account>/workers/scripts/${WORKER}`);
  console.log('  metadata   :', JSON.stringify(redacted));
  console.log('  body bytes :', body.length, '(script', Buffer.byteLength(script, 'utf8'), '+ multipart overhead', body.length - Buffer.byteLength(script, 'utf8'), ')');
  const res = await fetch(`${API}/accounts/${ACCT}/workers/scripts/${WORKER}`, {
    method: 'PUT',
    headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Content-Length': String(body.length) },
    body,
  });
  const text = await res.text();
  console.log('  HTTP', res.status, text.replace(/\s+/g, ' ').slice(0, 400));
  const route = await cf(`/accounts/${ACCT}/workers/scripts/${WORKER}/subdomain`, {
    method: 'POST', body: JSON.stringify({ enabled: true, previews_enabled: false }),
  });
  console.log('  workers.dev route enabled:', route.status, route.json ? JSON.stringify(route.json.result || route.json.errors) : route.text.slice(0, 160));
  if (res.status !== 200) process.exit(1);
}

/* ─────────────────────── the live checks ─────────────────────── */

async function runChecks(base, label, mode, subKey, pw) {
  const rows = [];
  const seen = [];
  const add = (n, pass, detail = '') => { rows.push({ n, pass, detail }); console.log(`  ${pass ? '✓' : '✗'} ${n}${detail ? '  → ' + detail : ''}`); };
  const req = async (path, init = {}) => {
    let res, body = '';
    try {
      res = await fetch(base + path, Object.assign({ redirect: 'manual' }, init));
      body = await res.text();
    } catch (e) {
      seen.push({ path, status: 0, head: 'NETWORK: ' + (e.message || e) });
      return { status: 0, ct: '', loc: '', body: '', netError: String(e.message || e) };
    }
    const r = { status: res.status, ct: res.headers.get('content-type') || '', loc: res.headers.get('location') || '', body };
    if (/1101|Worker threw exception/i.test(r.body.slice(0, 6000))) r.cf1101 = true;
    seen.push({ path, status: r.status, ct: r.ct, head: r.body.replace(/\s+/g, ' ').slice(0, 220) });
    return r;
  };
  const j = (t) => { try { return JSON.parse(t); } catch { return null; } };
  const auth = (k) => ({ headers: { 'x-cat-key': k } });

  const health = await req('/health');
  add('/health answers ok:true (200 JSON)', health.status === 200 && j(health.body)?.ok === true, `${health.status} ${health.body.replace(/\s+/g, ' ').slice(0, 80)}`);
  add('/health is bare for an anonymous caller (no version, no kv field)',
    !/version|"kv"/.test(health.body || ''), health.body.slice(0, 80));

  const version = await req('/api/version');
  const vj = j(version.body);
  const expect = process.env.EXPECT_VERSION || '6.67.0';
  add(`/api/version reports ${expect} (the file loaded and ran)`,
    version.status === 200 && vj?.version === expect, `${version.status} ${version.body.replace(/\s+/g, ' ').slice(0, 120)}`);

  let diag = null;
  if (mode === 'open') {
    diag = await req('/api/diag');
    const dj = j(diag.body);
    add('/api/diag is reachable without a key while the panel is OPEN',
      diag.status === 200 && dj?.ok === true, `${diag.status} ${diag.body.replace(/\s+/g, ' ').slice(0, 100)}`);
    add('KV really reads AND writes from this isolate (diag kv = read+write ok)',
      /read\+write ok/.test(String(dj?.kv || '')), String(dj?.kv || '(no kv field)'));
    console.log('  · bindings visible to the isolate:', JSON.stringify(dj?.bindings));
    console.log('  · passwordSource/uuidSource     :', dj?.passwordSource, '/', dj?.uuidSource);

    const alive = await req('/api/selfcheck');
    add('/api/selfcheck says the handler is alive', alive.status === 200 && j(alive.body)?.ok === true, `${alive.status} ${alive.body.replace(/\s+/g, ' ').slice(0, 100)}`);

    const thrown = await req('/api/selfcheck?throw=1');
    const tj = j(thrown.body);
    add('an exception becomes a JSON panel_crash report, not an edge error page',
      thrown.status === 500 && tj?.error === 'panel_crash' && String(tj?.stack || '').length > 50,
      `${thrown.status} ${thrown.body.replace(/\s+/g, ' ').slice(0, 140)}`);
    add('…and the report carries a stack + the crashed path', /stack/.test(thrown.body) && /selfcheck/.test(String(tj?.at || '')), String(tj?.at || ''));

    const setpw = await req('/api/settings', { method: 'POST', headers: { 'x-cat-key': subKey, 'content-type': 'application/json' }, body: JSON.stringify({ password: pw }) });
    add('a panel password can be set (and lands in KV)', setpw.status === 200 && j(setpw.body)?.ok === true, `${setpw.status} ${setpw.body.replace(/\s+/g, ' ').slice(0, 100)}`);
  }

  // locked-mode contract (also re-checked after the password above)
  const noauth = await req('/api/diag');
  add('/api/diag refuses an anonymous caller (401)', noauth.status === 401 && !/uuid|passwordSource/.test(noauth.body), `${noauth.status} ${noauth.body.replace(/\s+/g, ' ').slice(0, 80)}`);
  const uuidAsKey = await req('/api/diag', auth(subKey));
  add('the old UUID is no longer a key once a password exists', uuidAsKey.status === 401, String(uuidAsKey.status));

  diag = await req('/api/diag', auth(pw));
  const dj2 = j(diag.body);
  add('/api/diag with the owner key returns the report', diag.status === 200 && dj2?.ok === true, `${diag.status}`);
  add('KV read+write is proven on the live account', /read\+write ok/.test(String(dj2?.kv || '')), String(dj2?.kv || ''));
  console.log('  · bindings visible to the isolate:', JSON.stringify(dj2?.bindings));
  console.log('  · passwordSource / uuidSource    :', dj2?.passwordSource, '/', dj2?.uuidSource);
  console.log('  · usage / subToken / app         :', JSON.stringify(dj2?.usage), '/', dj2?.subToken, '/', JSON.stringify(dj2?.app));

  const tAuth = await req('/api/selfcheck?throw=1', auth(pw));
  const ta = j(tAuth.body);
  add('owner sees the full crash report on the live edge', tAuth.status === 500 && ta?.error === 'panel_crash' && String(ta?.stack || '').length > 50, `${tAuth.status}`);
  const tNo = await req('/api/selfcheck?throw=1');
  add('a stranger is refused BEFORE the throw (401, no report)', tNo.status === 401 && !/panel_crash/.test(tNo.body), `${tNo.status} ${tNo.body.replace(/\s+/g, ' ').slice(0, 80)}`);

  const bak = await req('/api/backup', auth(pw));
  const bj = j(bak.body);
  add('/api/backup masks secrets by default', bak.status === 200 && bj?.settings?.passwordHash === MASK && bj?.secrets === 'masked',
    `${bak.status} secrets=${bj?.secrets} hash=${String(bj?.settings?.passwordHash).slice(0, 24)}`);

  const sub = await req('/sub/' + encodeURIComponent(subKey) + '?raw=1');
  add('the subscription serves real configs from the live edge', sub.status === 200 && /vless:\/\//.test(sub.body),
    `${sub.status} bytes=${sub.body.length} vless=${(sub.body.match(/vless:\/\//g) || []).length}`);

  const root = await req('/');
  console.log(`  · GET / → ${root.status}${root.loc ? ' location=' + root.loc : ''} (${root.body.replace(/\s+/g, ' ').slice(0, 70)})`);

  const any1101 = seen.some((s) => /1101/.test(s.head));
  console.log('\n  raw responses:');
  for (const s of seen) console.log(`    ${String(s.status).padStart(3)}  ${s.path}  ${s.head}`);
  return { label, mode, rows, seen, any1101, version: vj?.version || '', kv: String(dj2?.kv || ''), bindings: dj2?.bindings || [], hardFail: rows.filter((r) => !r.pass).map((r) => r.n) };
}

async function stage() {
  const label = arg('label', 'stage');
  const mode = arg('mode', 'open');
  const subKey = arg('sub-key');
  const pw = process.env.PANEL_TEST_PW;
  const base = arg('base', process.env.BASE);
  console.log(`\n=== stage ${label} (mode ${mode}) against ${base} ===`);
  await sleep(Number(process.env.PROPAGATION_WAIT_MS || 8000));
  const logPath = `${OUT}/tail-${label}.log`;
  const fd = openSync(logPath, 'w');
  const tail = spawn(WRANGLER, ['tail', '--config', 'wrangler.cf-test.jsonc', '--format', 'json'], { stdio: ['ignore', fd, fd] });
  await sleep(12000);
  let result;
  try {
    result = await runChecks(base, label, mode, subKey, pw);
  } finally {
    tail.kill('SIGTERM');
    await sleep(1500);
    try { closeSync(fd); } catch { /* already closed */ }
  }
  const tailText = existsSync(logPath) ? readFileSync(logPath, 'utf8') : '';
  result.tailBytes = tailText.length;
  console.log(`\n--- wrangler tail (${label}) — ${tailText.length} bytes captured ---`);
  console.log(tailText.split('\n').filter((l) => l.trim()).slice(0, 12).join('\n').slice(0, 4000) || '(no live log lines: the requests were served without an exception, or tail could not attach — see the file)');
  writeFileSync(`${OUT}/check-${label}.json`, JSON.stringify(result, null, 1));
  console.log(`\n# ${label}: ${result.rows.filter((r) => r.pass).length}/${result.rows.length} checks passed, 1101 seen: ${result.any1101}`);
}

function summary() {
  const files = existsSync(OUT) ? readdirSync(OUT).filter((f) => f.startsWith('check-') && f.endsWith('.json')).sort() : [];
  const all = files.map((f) => JSON.parse(readFileSync(`${OUT}/${f}`, 'utf8')));
  const lines = [];
  lines.push('| stage | version | KV state | sub | checks | 1101? |');
  lines.push('| --- | --- | --- | --- | --- | --- |');
  for (const s of all) {
    const subOk = s.rows.find((r) => /subscription serves real configs/.test(r.n));
    lines.push(`| ${s.label} | ${s.version || '—'} | ${s.kv || '—'} | ${subOk && subOk.pass ? 'ok' : 'NO'} | ${s.rows.filter((r) => r.pass).length}/${s.rows.length} | ${s.any1101 ? 'YES' : 'no'} |`);
  }
  const table = lines.join('\n');
  console.log(table);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, '\n### Cloudflare live test — catpanel-test\n\n' + table + '\n');
  const hard = all.flatMap((s) => s.hardFail.map((n) => `${s.label}: ${n}`));
  if (hard.length) {
    console.log('\nHARD FAILURES:'); hard.forEach((h) => console.log('  ✗ ' + h));
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, '\n**failed checks:**\n' + hard.map((h) => '- ' + h).join('\n') + '\n');
    process.exit(1);
  }
  console.log('\nall hard expectations held on the live account');
}

async function cleanup() {
  console.log('deleting the throwaway worker + KV namespace…');
  let del = await cf(`/accounts/${ACCT}/workers/scripts/${WORKER}`, { method: 'DELETE' });
  if (del.status !== 200) {
    console.log('  first delete attempt:', del.status, errText(del), '— retrying with ?force=true');
    del = await cf(`/accounts/${ACCT}/workers/scripts/${WORKER}?force=true`, { method: 'DELETE' });
  }
  console.log('  DELETE worker  :', del.status, del.json ? JSON.stringify(del.json.result || del.json.errors) : del.text.slice(0, 160));
  if (process.env.KV_ID) {
    const dk = await cf(`/accounts/${ACCT}/storage/kv/namespaces/${process.env.KV_ID}`, { method: 'DELETE' });
    console.log('  DELETE KV      :', dk.status, dk.json ? JSON.stringify(dk.json.result || dk.json.errors) : dk.text.slice(0, 160));
  }
  const list = await cf(`/accounts/${ACCT}/workers/scripts`);
  const names = (list.json?.result || []).map((s) => s.id);
  console.log('  workers left   :', names.join(', ') || '(none)');
  console.log('  catpanel-test gone:', !names.includes(WORKER));
  const kv = await cf(`/accounts/${ACCT}/storage/kv/namespaces?per_page=100`);
  const titles = (kv.json?.result || []).map((n) => n.title);
  console.log('  kv namespaces  :', titles.join(', ') || '(none)');
  console.log('  test KV gone   :', !titles.includes(KV_TITLE));
}

mkdirSync(OUT, { recursive: true });
const table = { preflight, 'kv-create': kvCreate, config, 'gen-credentials': genCredentials, uuid: () => console.log(uuidFor(arg('host'))), deploy, 'upload-app': uploadApp, stage, summary, cleanup };
if (!table[cmd]) { console.error('unknown subcommand:', cmd, '— one of', Object.keys(table).join(', ')); process.exit(2); }
await table[cmd]();
