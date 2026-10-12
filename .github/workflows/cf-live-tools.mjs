#!/usr/bin/env node
/**
 * TEMPORARY tooling for the one-off live Cloudflare test (cf-live-test.yml).
 *
 * It is deleted from the tree again when the test is finished — it is not part of
 * the product.
 *
 * retrigger: 5 (a comment change is enough to make the workflow run again) Nothing here prints a token, and the two values that could be
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
const WRANGLER = process.env.WRANGLER || './node_modules/.bin/wrangler';
const RESULTS = process.env.RESULTS_FILE || 'cf-live-results.md';

/**
 * Redaction, and the reason it exists: GitHub's secret scanning push protection
 * refused three pushes of this results file with «SECRET DETECTED IN CONTENT —
 * Cloudflare User API Token», because a live panel response contained a random
 * forty-character base64url string, which is exactly what a Cloudflare API token
 * looks like. The push is rejected either way, Contents API included (409), so the
 * record has to be stripped of anything that pattern-matches a credential. Only
 * lookalikes are touched: status codes, byte counts, check tables, UUIDs, md5s and
 * the vless links in the subscription sample all survive unchanged.
 */
function scrub(text) {
  let out = String(text);
  const exact = [
    process.env.PANEL_TEST_PW, process.env.APP_UUID, process.env.SUB_KEY_DERIVED,
    process.env.KV_ID, process.env.TOKEN, process.env.CLOUDFLARE_API_TOKEN,
    process.env.CLOUDFLARE_ACCOUNT_ID,
  ].filter((v) => typeof v === 'string' && v.length >= 12);
  for (const v of exact) out = out.split(v).join('<redacted>');
  // Cloudflare's detector fires on any 40+ character run of URL-safe base64, and
  // its sibling fires on ordinary base64 with padding.
  out = out.replace(/[A-Za-z0-9_-]{40,}/g, (m) => `<redacted ${m.length} chars>`);
  out = out.replace(/[A-Za-z0-9+/]{40,}={0,2}/g, (m) => `<redacted ${m.length} chars>`);
  return out;
}
/**
 * The raw job log is only reachable through a signed blob URL that the owner's
 * tooling cannot fetch, so every result is also appended to a file that the
 * workflow commits onto the branch — the one channel that is known to work end
 * to end.
 */
function appendResults(rawText) {
  const text = scrub(rawText);
  try {
    const head = existsSync(RESULTS) ? readFileSync(RESULTS, 'utf8') : '';
    writeFileSync(RESULTS, head + text);
    return true;
  } catch (e) {
    try { process.stderr.write('appendResults failed: ' + e.message + '\n'); } catch { /* ignore */ }
    return false;
  }
}

/**
 * Workflow-command escaping, so a multi-line section survives as one annotation:
 * the maximum honesty the transport allows is an annotation whose message is the
 * section itself, and annotations ARE readable from the outside.
 */
const esc = (t) => String(t).replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');

/**
 * Append to the branch file AND mirror the same text into annotations — chunked,
 * because one workflow command cannot carry an unbounded message. Both channels
 * are best-effort on purpose: losing the report must never fail the test itself.
 */
function publish(rawText, title) {
  const text = scrub(rawText);
  const ok = appendResults(text);
  if (process.env.NO_ANNOTATIONS === '1') return ok;
  try {
    const chunks = [];
    for (let i = 0; i < text.length && chunks.length < 3; i += 3800) chunks.push(text.slice(i, i + 3800));
    chunks.forEach((c, i) => {
      console.log(`::notice title=${title ? title + ' ' : ''}part ${i + 1}/${chunks.length}::` + esc(c));
    });
  } catch { /* ignore */ }
  return ok;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const envFile = (kv) => appendFileSync(process.env.GITHUB_ENV, kv + '\n');
function markdownTable(rows) {
  return ['| check | result | detail |', '| --- | --- | --- |']
    .concat(rows.map((r) => `| ${r.n} | ${r.pass ? '✓' : '✗'} | ${String(r.detail || '').replace(/\|/g, '\\|').slice(0, 160)} |`))
    .join('\n');
}

/**
 * SELF-RECORDING. The raw job log sits behind a signed blob URL that the owner's
 * tooling cannot fetch, and `continue-on-error` steps still report success in the
 * API — so a step that dies silently stays invisible. Every line a subcommand
 * prints is therefore kept in a ring buffer and appended to the results file on
 * exit together with the exit code: whatever happens, the evidence travels.
 */
const __log = [];
for (const stream of ['stdout', 'stderr']) {
  const orig = process[stream].write.bind(process[stream]);
  process[stream].write = (chunk, enc, cb) => {
    try {
      __log.push(String(chunk));
      if (__log.length > 500) __log.splice(0, 100);
    } catch { /* never interfere with writing */ }
    return orig(chunk, enc, cb);
  };
}
process.on('exit', (code) => {
  try {
    const tail = __log.join('');
    if (!tail.trim() || process.env.NO_SELF_RECORD === '1') return;
    const name = process.argv[2] || '?';
    const overflow = tail.length > 9000 ? '…(truncated; showing the last 9000 characters)\n' : '';
    const section = `\n<details><summary><code>${name}</code> — raw output (exit code ${code})</summary>\n\n` + '```\n' + overflow + tail.slice(-9000) + '\n```\n\n</details>\n';
    if (code === 0) {
      // healthy run: the file is enough, the annotation budget stays for the
      // publish diagnostics and the cleanup verification
      appendResults(section);
    } else {
      // a step that died must still leave its bytes somewhere readable
      publish(section, name + '-raw-' + code);
    }
  } catch { /* an exit handler must never throw */ }
});

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
  console.log('::warning::trigger=' + (process.env.GITHUB_EVENT_NAME || '?') + ' repo=' + (process.env.GITHUB_REPOSITORY || '?') +
    ' actor=' + (process.env.GITHUB_ACTOR || '?') + ' ref=' + (process.env.GITHUB_REF_NAME || '?'));
  console.log('provided by  : secrets.CLOUDFLARE_API_TOKEN=' + process.env.HAS_SECRET_TOKEN +
    ' secrets.CLOUDFLARE_ACCOUNT_ID=' + process.env.HAS_SECRET_ACCOUNT +
    ' | vars.CLOUDFLARE_API_TOKEN=' + process.env.HAS_VAR_TOKEN +
    ' vars.CLOUDFLARE_ACCOUNT_ID=' + process.env.HAS_VAR_ACCOUNT +
    '  (repository secrets and repository variables are two separate storages)');
  // Name-level diagnostic: which candidate names resolve in either storage, plus
  // the controls GitHub injects into every run (if those are empty, the `secrets`
  // context itself is unreadable here and no name will ever resolve).
  const parseObj = (raw) => { try { return JSON.parse(raw || '{}'); } catch { return {}; } };
  const controls = parseObj(process.env.CONTROLS);
  const controlHits = Object.keys(controls).filter((k) => controls[k] === true);
  console.log('::warning::CONTROL — GitHub-injected names visible: ' + (controlHits.join(', ') || 'NONE (the secrets context itself reads empty)'));
  console.log('CONTROL — GitHub-injected names visible:', controlHits.join(', ') || 'NONE (the secrets context itself reads empty)');
  for (const [label, raw] of [['repository secrets (token names)', process.env.CANDIDATE_SECRETS_TOKEN],
                              ['repository secrets (account names)', process.env.CANDIDATE_SECRETS_ACCOUNT],
                              ['repository variables (token names)', process.env.CANDIDATE_VARS_TOKEN],
                              ['repository variables (account names)', process.env.CANDIDATE_VARS_ACCOUNT]]) {
    const obj = parseObj(raw);
    const hits = Object.keys(obj).filter((k) => obj[k] === true);
    const line = `${label}: ${hits.length ? hits.join(', ') : 'none of the ' + Object.keys(obj).length + ' candidate names exist'}`;
    console.log('::warning::' + line);
    console.log(line);
  }
  if (!TOKEN || !ACCT) {
    // Both the warning and the error carry the four booleans, so the answer shows
    // up as an ANNOTATION (readable with `gh run view`) even when the raw job log
    // is out of reach.
    const flags = 'secrets.CLOUDFLARE_API_TOKEN=' + process.env.HAS_SECRET_TOKEN +
      ' secrets.CLOUDFLARE_ACCOUNT_ID=' + process.env.HAS_SECRET_ACCOUNT +
      ' vars.CLOUDFLARE_API_TOKEN=' + process.env.HAS_VAR_TOKEN +
      ' vars.CLOUDFLARE_ACCOUNT_ID=' + process.env.HAS_VAR_ACCOUNT;
    console.log('::warning::credential sources in this run — ' + flags);
    console.error('::error::neither secrets.* nor vars.* carry CLOUDFLARE_API_TOKEN + CLOUDFLARE_ACCOUNT_ID — ' + flags +
      '. This repository has no Actions environment, so environment secrets are not it either. ' +
      'Add them in Settings → Secrets and variables → Actions under the «Secrets» tab of the ACTIONS section ' +
      '(Codespaces and Dependabot secrets live in the same screen but are NOT given to workflows), ' +
      'in the repository mazodimobinhost-creator/cat-client.');
    process.exit(1);
  }
  // Fresh file per run: the workflow commits it, and a reader must be able to
  // trust that everything below belongs to THIS run.
  writeFileSync(RESULTS, `# Cloudflare live test — catpanel-test\n\n` +
    `run=${process.env.GITHUB_RUN_ID || '?'} attempt=${process.env.GITHUB_RUN_ATTEMPT || '?'} ` +
    `trigger=${process.env.GITHUB_EVENT_NAME || '?'} actor=${process.env.GITHUB_ACTOR || '?'} ` +
    `ref=${process.env.GITHUB_REF_NAME || '?'} head=${process.env.GITHUB_SHA || '?'}\n\n` +
    `Everything in this file was produced by a real Cloudflare account. Only two\n` +
    `names are ever written: the Worker \`${WORKER}\` and the KV namespace \`${KV_TITLE}\`.\n`);
  // A value stored as a repository VARIABLE is not masked by GitHub (only secrets
  // are). Register both forms now, before anything else can echo them, so a
  // variable-held token cannot end up in a log either.
  if (TOKEN) console.log('::add-mask::' + TOKEN);
  if (ACCT) console.log('::add-mask::' + ACCT);
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
  const startedAt = Date.now();
  const bundle = flag('bundle');
  const args = ['deploy', '--config', 'wrangler.cf-test.jsonc'];
  if (!bundle) args.push('--no-bundle');
  console.log('# wrangler', args.join(' '), '| main =', main);
  const { code, out } = await run(WRANGLER, args);
  const size = out.split('\n').filter((l) => /Total Upload|Uploaded|gzip|Deployed|workers\.dev/.test(l));
  console.log('deploy exit:', code);
  console.log(size.join('\n'));
  publish(`\n## Deploy — ${bundle ? 'wrangler deploy (bundled by wrangler)' : 'wrangler deploy --no-bundle'}\n\n` +
    '```\n' + ['$ npx wrangler ' + args.join(' ')].concat(out.split('\n').filter((l) => /Total Upload|Uploaded|Deployed|workers\.dev|gzip/i.test(l)).map((l) => l.trim())).join('\n') + '\n```\n\n' +
    `exit code ${code} after ${Math.round((Date.now() - startedAt) / 1000)}s\n`);
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
  // The subdomain route is enabled BEFORE the section is written: reading `route`
  // earlier than this line threw «Cannot access 'route' before initialization»,
  // the step died with exit code 1 while `continue-on-error` still reported it as
  // success, and stage C left no trace at all in the results file.
  const route = await cf(`/accounts/${ACCT}/workers/scripts/${WORKER}/subdomain`, {
    method: 'POST', body: JSON.stringify({ enabled: true, previews_enabled: false }),
  });
  console.log('  workers.dev route enabled:', route.status, route.json ? JSON.stringify(route.json.result || route.json.errors) : route.text.slice(0, 160));
  publish(`\n## App-style upload (the same builder as CloudflareWorker.cfUploadWorker)\n\n` +
    '- method/url: `PUT /accounts/<account>/workers/scripts/' + WORKER + '`\n' +
    '- headers: `Content-Type: multipart/form-data; boundary=…`, `Content-Length: ' + body.length + '`\n' +
    '- metadata: `' + JSON.stringify(redacted).replace(/\|/g, '\\|') + '`\n' +
    '- body bytes: ' + body.length + ' (script ' + Buffer.byteLength(script, 'utf8') + ' + multipart overhead ' + (body.length - Buffer.byteLength(script, 'utf8')) + ')\n' +
    '- HTTP ' + res.status + ': `' + text.replace(/\s+/g, ' ').slice(0, 300) + '`\n' +
    '- workers.dev route: HTTP ' + route.status + '\n',
    'app-upload');
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
    const r = { status: res.status, ct: res.headers.get('content-type') || '', loc: res.headers.get('location') || '', body, cf1101: false };
    // The edge's error page is HTML. Our own JSON mentions «1101» on purpose
    // (the selfcheck hint, the diag note), so a body that parses as JSON is never
    // counted — the first live run flagged itself with this false positive.
    const looksHtml = /text\/html/i.test(r.ct) || /^\s*<(!doctype|html)/i.test(r.body);
    if (looksHtml && /Error 1101|Worker threw exception|cf-error-details/i.test(r.body.slice(0, 8000))) {
      r.cf1101 = true;
      console.log('  ⚠ CLOUDFLARE ERROR PAGE on ' + path + ': ' + r.body.replace(/\s+/g, ' ').slice(0, 300));
    }
    seen.push({ path, status: r.status, ct: r.ct, cf1101: r.cf1101, head: r.body.replace(/\s+/g, ' ').slice(0, 220) });
    return r;
  };
  const j = (t) => { try { return JSON.parse(t); } catch { return null; } };
  const auth = (k) => ({ headers: { 'x-cat-key': k } });

  const health = await req('/health');
  add('/health answers ok:true (200 JSON)', health.status === 200 && j(health.body)?.ok === true, `${health.status} ${health.body.replace(/\s+/g, ' ').slice(0, 80)}`);
  if (mode === 'open') {
    // OPEN_PANEL=true makes every caller an owner by design, so /health carries
    // the owner payload here; the bare form is asserted on the locked stages.
    add('/health carries the owner payload while the panel is OPEN (by design)',
      /"version"/.test(health.body || ''), health.body.slice(0, 90));
  } else {
    add('/health is bare for an anonymous caller (no version, no kv field)',
      !/version|"kv"/.test(health.body || ''), health.body.slice(0, 80));
  }

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
    // `persisted` is the difference between «the write landed in KV» and «it only
    // lived in this isolate's memory» — the rehearsal showed the old check passing
    // on a KV-less worker, which would have hidden exactly the failure that makes
    // every save button look broken.
    const spw = j(setpw.body);
    add('a panel password can be set AND persisted to KV', setpw.status === 200 && spw?.ok === true && spw?.persisted === true,
      `${setpw.status} persisted=${spw?.persisted}`);
  }

  // KV is eventually consistent (Cloudflare documents up to 60s for a write to be
  // visible elsewhere), so the password just written needs a real wait before the
  // locked-mode contract can be asserted. The measured delay is recorded — it is
  // the honest explanation for the one case a user can hit: «I saved it, then
  // another screen still showed the old state for a moment».
  let waitSeconds = 0;
  if (mode === 'open') {
    // A KV write propagates across Cloudflare's POPs asynchronously (documented:
    // up to 60 s), and each isolate caches settings for up to 60 s of its own, so
    // one isolate can still answer as «open» long after another has closed. The
    // honest expectation is therefore a *stable* close, not an instant one — the
    // wait below measures how long it really takes for three consecutive requests
    // to be refused, and that number is reported instead of being hidden.
    const t0 = Date.now();
    const deadline = t0 + 150000;
    let streak = 0;
    while (Date.now() < deadline) {
      const probe = await req('/api/diag');
      streak = probe.status === 401 ? streak + 1 : 0;
      if (streak >= 3) break;
      await sleep(5000);
      waitSeconds = Math.round((Date.now() - t0) / 1000);
    }
    add('the panel closes for strangers everywhere once the password has propagated', streak >= 3,
      streak >= 3 ? `~${waitSeconds}s until 3 consecutive 401s (KV eventual consistency)` : `still open after ${waitSeconds}s`);
    console.log('  · lock propagation observed:', waitSeconds + 's');
  }

  // Locked-mode contract — only meaningful once the panel is really closed. In
  // open mode an unauthenticated /api/diag answering 200 is the documented
  // behaviour (asserted further up), so expecting 401 there would be the suite
  // contradicting itself: A-min failed its Summary on exactly that for two runs.
  const noauth = await req('/api/diag');
  if (mode === 'locked') {
    add('/api/diag refuses an anonymous caller (401)', noauth.status === 401 && !/uuid|passwordSource/.test(noauth.body), `${noauth.status} ${noauth.body.replace(/\s+/g, ' ').slice(0, 80)}`);
    const uuidAsKey = await req('/api/diag', auth(subKey));
    add('the old UUID is no longer a key once a password exists', uuidAsKey.status === 401, String(uuidAsKey.status));
  } else {
    add('anonymous /api/diag stays allowed in open mode (401 only after locking)', noauth.status === 200, `${noauth.status}`);
  }

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

  // Only a real edge error page counts (see req()): our own JSON mentions «1101»
  // on purpose in the selfcheck hint and the diag note.
  const any1101 = seen.some((s) => s.cf1101 === true);
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

  // The publishable section (this is what the branch commit carries) — written
  // here, not inside runChecks, because the tail log only exists in this scope.
  const passed = result.rows.filter((r) => r.pass).length;
  const tailLines = tailText.split('\n').filter((l) => l.trim()).slice(0, 8).map((l) => '    ' + l.slice(0, 240)).join('\n') || '    (no live log lines captured)';
  publish(`\n## Stage ${label} — mode ${result.mode} — ${passed}/${result.rows.length} checks passed${result.any1101 ? ' — ⚠ 1101 IN A RESPONSE BODY' : ''}\n\n` +
    `trigger=${process.env.GITHUB_EVENT_NAME || '?'} repo=${process.env.GITHUB_REPOSITORY || '?'} actor=${process.env.GITHUB_ACTOR || '?'} ref=${process.env.GITHUB_REF_NAME || '?'} url=${base}\n\n` +
    `${markdownTable(result.rows)}\n\n` +
    `**raw responses** (HTTP status, path, first 220 chars — the bytes the edge really returned)\n\n` +
    result.seen.map((x) => `    ${String(x.status).padStart(3)}  ${x.path}  ${x.head}`).join('\n') + '\n\n' +
    `**wrangler tail** (${tailText.length} bytes captured)\n\n${tailLines}\n`,
    'stage-' + label);
  console.log('::notice::stage ' + label + ' — ' + passed + '/' + result.rows.length + ' checks passed, 1101=' + result.any1101);
  console.log(`\n# ${label}: ${passed}/${result.rows.length} checks passed, 1101 seen: ${result.any1101}`);
}

function summary() {
  const files = existsSync(OUT) ? readdirSync(OUT).filter((f) => f.startsWith('check-') && f.endsWith('.json')).sort() : [];
  if (!files.length) {
    console.error('::error::no stage produced a result — the run did nothing (missing credentials, or every deploy failed). ' +
      'This is a FAILURE, not a quiet success.');
    process.exit(1);
  }
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
  publish(`\n## Expected-vs-actual summary\n\n${table}\n`, 'summary');
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
  let dkStatus = 'skipped (no KV_ID)';
  if (process.env.KV_ID) {
    const dk = await cf(`/accounts/${ACCT}/storage/kv/namespaces/${process.env.KV_ID}`, { method: 'DELETE' });
    dkStatus = dk.status;
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
  publish(`\n## Cleanup — verified through the Cloudflare API\n\n` +
    `- DELETE /accounts/<account>/workers/scripts/${WORKER} → ${del.status}\n` +
    `- DELETE /accounts/<account>/storage/kv/namespaces/${process.env.KV_ID || '?'} → ${dkStatus}\n` +
    `- workers left on the account: ${names.join(', ') || '(none)'} — \`${WORKER}\` gone: **${!names.includes(WORKER)}**\n` +
    `- KV namespaces left: ${titles.join(', ') || '(none)'} — \`${KV_TITLE}\` gone: **${!titles.includes(KV_TITLE)}**\n`);
}

mkdirSync(OUT, { recursive: true });
/**
 * `emit --file=<path> --title=<t>` — publish a file from the runner through
 * annotations. The raw job log and every artifact are served from
 * *.blob.core.windows.net, which the owner's tooling cannot reach, so the
 * annotations API is the only transport that carries bytes out of a run.
 */
function emit() {
  const file = arg('file');
  const title = arg('title', 'emit');
  let text;
  try {
    text = readFileSync(file, 'utf8');
  } catch (e) {
    console.log(`::warning::${title}: cannot read ${file} (${e.message})`);
    return;
  }
  const chunks = [];
  for (let i = 0; i < text.length && chunks.length < 8; i += 5500) chunks.push(text.slice(i, i + 5500));
  console.log(`::notice title=${title}-size::${esc(text.length + ' bytes in ' + file)}`);
  chunks.forEach((c, i) => console.log(`::notice title=${title} ${i + 1}/${chunks.length}::` + esc(c)));
  if (chunks.length === 8 && text.length > 8 * 5500) console.log(`::warning::${title}: truncated at 44 kB by the annotation budget`);
}

const table = { preflight, 'kv-create': kvCreate, config, 'gen-credentials': genCredentials, uuid: () => console.log(uuidFor(arg('host'))), deploy, 'upload-app': uploadApp, stage, summary, cleanup, emit };
if (!table[cmd]) { console.error('unknown subcommand:', cmd, '— one of', Object.keys(table).join(', ')); process.exit(2); }
await table[cmd]();
