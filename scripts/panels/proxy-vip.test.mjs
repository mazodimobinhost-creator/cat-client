/**
 * OUTBOUND PROXIES / «VIP PROXY» (panel 6.67.5) — the four things a rival panel's card had and
 * this project did not, pinned against the real worker module:
 *
 *   1. a preferred relay (★) that is tried first on every fresh connection;
 *   2. an explicit «may the worker swap a dead relay» switch — OFF must mean ONLY the starred
 *      relay is ever dialled (a silent fallback would defeat the point of pinning);
 *   3. a per-relay live latency table («تست پروکسی») that reports failures per entry instead of
 *      one aggregate error, and never writes settings;
 *   4. the card itself: rows, star click, auto-swap click and the relay build command — loaded
 *      in a real DOM (jsdom) so the wiring is exercised, not just the server side.
 *
 * Server checks run against the real module with a fake KV and an injected socket layer (no
 * network); the page half runs the real generated HTML with a fetch mock.
 *
 * Usage: node scripts/panels/proxy-vip.test.mjs
 */
import { strict as assert } from 'node:assert';
import { JSDOM } from 'jsdom';

const workerPath = process.env.CAT_PANEL_WORKER ||
  new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).pathname;
const T = (await import(workerPath))._testing;

const HOST = 'p.workers.dev';
const UUID = '11111111-2222-4333-8444-555555555555';
const OWNER = 'owner-vip';
const DE = 'socks5://u1:p1@10.0.0.1:1080';
const NL = 'socks5://u2:p2@10.0.0.2:1080';
const THIRD = 'socks5://10.0.0.3:1080';

let pass = 0;
let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};

const memKv = () => {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
    list: async () => ({ keys: [], list_complete: true }),
  };
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Wait for a condition instead of a fixed sleep: CI runs test files two at a time on a 2-core
// runner, and a fixed 120-200 ms window let a late «saved ✓» overwrite the summary line.
const until = async (cond, ms = 5000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { try { if (cond()) return true; } catch (e) { /* not yet */ } await sleep(20); }
  return false;
};

async function panel(patch = {}) {
  const kv = memKv();
  const env = { CAT_KV: kv, UUID };
  const settings = Object.assign({}, await T.readSettings(env), {
    passwordHash: await T.sha256Hex(OWNER),
    proxyIps: [DE, NL, THIRD],
  }, patch);
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  return { kv, env };
}
// readSettings() caches per KV key, so every request in this file clears it first.
const call = (env, path, init) => {
  T.kvCacheClear();
  return T.handleRequest(new Request('https://' + HOST + path, init), env, { waitUntil() { } });
};
const asOwner = (init = {}) => Object.assign({}, init, { headers: Object.assign({ 'x-cat-key': OWNER }, init.headers || {}) });
const putSettings = (env, patch) => call(env, '/api/settings', asOwner({ method: 'PUT', body: JSON.stringify(patch) }));
const cur = async (env) => { T.kvCacheClear(); return T.readSettings(env); };   // cache-safe read

console.log('\nOutbound proxies / VIP (panel 6.67.5)\n');

/* ---------- 1) the star ---------- */
{
  const { env } = await panel();

  let s = await cur(env);
  ok('by default there is no preferred relay and auto-swap is ON (historical behaviour)',
    s.proxyVip === '' && s.proxyAutoSwap === true, `${JSON.stringify(s.proxyVip)} / ${s.proxyAutoSwap}`);

  await putSettings(env, { proxyVip: NL });
  s = await cur(env);
  ok('a relay that IS in the list can be starred', s.proxyVip === NL, s.proxyVip);
  ok('…and the starred relay leads the dial order',
    T.socksRelayList(env, s)[0].url === NL, T.socksRelayList(env, s).map((r) => r.url).join(' | '));
  ok('…without dropping the others (auto-swap on)',
    T.socksRelayList(env, s).length === 3, T.socksRelayList(env, s).length);

  // A star pointing at something the list no longer has must not survive: the worker would
  // otherwise «prefer» a relay it cannot dial and quietly fall through on every connection.
  await putSettings(env, { proxyVip: 'socks5://9.9.9.9:1080' });
  s = await cur(env);
  ok('a star that is not in the list is refused (no stale preference)', s.proxyVip === '', s.proxyVip);
}

/* ---------- 2) auto-swap off means «pin it, no silent fallback» ---------- */
{
  const { env } = await panel();
  await putSettings(env, { proxyVip: DE });
  await putSettings(env, { proxyAutoSwap: false });
  const s = await cur(env);
  const list = T.socksRelayList(env, s);
  ok('auto-swap off + a starred relay → EXACTLY that relay is dialled',
    list.length === 1 && list[0].url === DE, list.map((r) => r.url).join(' | '));

  await putSettings(env, { proxyVip: '' });
  const s2 = await cur(env);
  ok('auto-swap off WITHOUT a star keeps the whole list (nothing was pinned)',
    T.socksRelayList(env, s2).length === 3, T.socksRelayList(env, s2).length);

  await putSettings(env, { proxyVip: THIRD, proxyAutoSwap: true });
  const s3 = await cur(env);
  ok('turning auto-swap back on restores the fallback list, star first',
    T.socksRelayList(env, s3).length === 3 && T.socksRelayList(env, s3)[0].url === THIRD,
    T.socksRelayList(env, s3).map((r) => r.url).join(' | '));
}

/* ---------- 3) the latency table (server side, stub socket layer) ---------- */
{
  const emptyPanel = await panel({ proxyIps: [] });
  const anon = await call(emptyPanel.env, '/api/proxy-test', { method: 'POST', body: '{}' });
  ok('an anonymous caller cannot make the worker open sockets', anon.status === 401, anon.status);
  const none = await (await call(emptyPanel.env, '/api/proxy-test', asOwner({ method: 'POST', body: '{}' }))).json();
  ok('with no relays the answer is an empty table, not an error',
    none.ok === true && Array.isArray(none.results) && none.results.length === 0, JSON.stringify(none));

  const { env } = await panel({ proxyCountries: { [DE]: 'DE', [NL]: 'NL' } });
  ok('the panel really has the three relays before the run',
    T.socksRelayList(env, await cur(env)).length === 3);

  // Relay 1 answers instantly, relay 2 refuses, relay 3 is slow. One failure must not hide the
  // healthy rows, and the ms has to be a real measurement.
  T.__setSockets({
    connect(opts) {
      const host = String(opts.hostname);
      if (host === '10.0.0.2') throw new Error('connection refused');
      const slow = host === '10.0.0.3';
      const opened = slow ? new Promise((r) => setTimeout(() => r(undefined), 120)) : Promise.resolve(undefined);
      return {
        opened,
        readable: new ReadableStream({
          start(c) {
            // SOCKS5 greeting reply: version 5, method 0 (no auth) — then the request reply.
            c.enqueue(new Uint8Array([5, 0]));
            setTimeout(() => { try { c.enqueue(new Uint8Array([5, 0, 0, 1, 0, 0, 0, 0, 0, 0])); c.close(); } catch (e) { /* closed */ } }, 5);
          },
        }),
        writable: new WritableStream({ write() { }, close() { } }),
        close() { },
      };
    },
  });
  let table;
  try {
    table = await (await call(env, '/api/proxy-test', asOwner({ method: 'POST', body: '{}' }))).json();
  } finally {
    T.__setSockets(null);
  }

  ok('the table has one row per relay', table.ok === true && table.results.length === 3, JSON.stringify(table).slice(0, 160));
  const byHost = Object.fromEntries((table.results || []).map((r) => [r.host, r]));
  ok('a reachable relay is reported ok with a latency',
    byHost['10.0.0.1'].ok === true && typeof byHost['10.0.0.1'].ms === 'number', JSON.stringify(byHost['10.0.0.1']));
  ok('a refused relay is reported as a FAILURE while the healthy rows still come back',
    byHost['10.0.0.2'].ok === false && /refused/.test(byHost['10.0.0.2'].err || '') && byHost['10.0.0.3'].ok === true,
    JSON.stringify(byHost['10.0.0.2']));
  ok('the country tag already cached by /api/proxy-geo rides along (no extra lookup)',
    byHost['10.0.0.1'].cc === 'DE' && byHost['10.0.0.2'].cc === 'NL', JSON.stringify(table.results.map((r) => r.cc)));
  ok('the reply states the policy it measured under',
    table.autoSwap === true && table.vip === '', `${table.autoSwap} / ${JSON.stringify(table.vip)}`);
  ok('the slow relay measures slower than the instant one',
    byHost['10.0.0.3'].ms >= 100 && byHost['10.0.0.1'].ms < 100,
    `${byHost['10.0.0.1'].ms} vs ${byHost['10.0.0.3'].ms}`);

  const after = await cur(env);
  ok('a test run never touches settings (it is a measurement, not a change)',
    JSON.stringify(after.proxyIps) === JSON.stringify([DE, NL, THIRD]) && after.proxyVip === '',
    JSON.stringify(after.proxyIps));
}

/* ---------- 4) the card itself, in a real DOM ---------- */
{
  const { env } = await panel({ proxyCountries: { [DE]: 'DE', [NL]: 'NL' }, proxyVip: DE });
  T.kvCacheClear();                       // readSettings caches per KV key
  const settings = await cur(env);
  const html = await T.panelPage(env, settings, HOST, UUID);

  const writes = [];             // every PUT body the page sends
  const errors = [];
  const dom = new JSDOM(html, {
    url: 'https://' + HOST + '/panel', runScripts: 'dangerously', pretendToBeVisual: true,
    beforeParse(window) {
      window.addEventListener('error', (e) => errors.push(String((e.error && e.error.stack) || e.message)));
      window.fetch = async (path, init = {}) => {
        const url = new URL(path, 'https://' + HOST);
        const method = (init.method || 'GET').toUpperCase();
        if (url.pathname === '/api/proxy-test') {
          return new Response(JSON.stringify({
            ok: true, autoSwap: true, vip: DE,
            results: [
              { url: DE, host: '10.0.0.1', port: 1080, cc: 'DE', ms: 42, ok: true, vip: true, err: '' },
              { url: NL, host: '10.0.0.2', port: 1080, cc: 'NL', ms: 7, ok: false, vip: false, err: 'connection refused' },
              { url: THIRD, host: '10.0.0.3', port: 1080, cc: '', ms: 90, ok: true, vip: false, err: '' },
            ],
          }), { status: 200, headers: { 'content-type': 'application/json' } });
        }
        let body = init.body;
        if (typeof body === 'string' && method === 'PUT') { try { writes.push(JSON.parse(body)); } catch (e) { writes.push(body); } }
        if (body && typeof body === 'object' && !(body instanceof ArrayBuffer)) body = JSON.stringify(body);
        const req = new Request(url, { method, headers: Object.assign({ 'content-type': 'application/json', 'x-cat-key': OWNER }, init.headers || {}), body: method === 'GET' || method === 'HEAD' ? undefined : body });
        return T.handleRequest(req, env, { waitUntil() { } });
      };
    },
  });
  const { window } = dom;
  const { document } = window;
  await until(() => document.querySelectorAll('#vipList > div').length === 3);
  await sleep(50);

  ok('the page loads with no JS error', errors.length === 0, errors.join(' | ').slice(0, 200));
  ok('the card renders one row per relay from the panel payload',
    document.querySelectorAll('#vipList [data-vip]').length === 2 && document.querySelectorAll('#vipList > div').length === 3,
    'rows: ' + document.querySelectorAll('#vipList > div').length);
  ok('each row carries the country flag and the host:port',
    /🇩🇪 DE/.test(document.querySelector('#vipList').textContent) && document.querySelector('#vipList').textContent.includes('10.0.0.1:1080'),
    document.querySelector('#vipList').textContent.slice(0, 120));
  {
    const rows = document.querySelectorAll('#vipList > div');
    const starred = Array.from(rows).filter((r) => /★/.test(r.textContent));
    ok('exactly one relay is marked ★, and it is the starred one from settings',
      starred.length === 1 && starred[0].textContent.includes('10.0.0.1') && !starred[0].querySelector('[data-vip]'),
      starred.map((r) => r.textContent).join(' | '));
  }

  // star → PUT proxyVip with the relay that was clicked
  document.querySelector('#vipList [data-vip="' + NL + '"]').click();
  await until(() => writes.some((w) => w && typeof w === 'object' && 'proxyVip' in w));
  // let the save round-trip finish (its «saved ✓» lands in #vipOut) before the next step
  await until(() => /✓/.test(document.querySelector('#vipOut').textContent), 2000);
  await sleep(50);
  const starWrite = writes.find((w) => w && typeof w === 'object' && 'proxyVip' in w);
  ok('clicking ☆ saves that relay as the preference',
    !!starWrite && starWrite.proxyVip === NL, JSON.stringify(writes));

  // test-all → the ms from the server lands in the rows (before any pinning, so all 3 are listed)
  document.querySelector('#vipTest').click();
  await until(() => /2\s*از\s*3|2 of 3/.test(document.querySelector('#vipOut').textContent)
    && document.querySelector('#vipList').textContent.includes('90ms'));
  let list = document.querySelector('#vipList').textContent;
  ok('the latency table prints ms for the healthy relays', list.includes('42ms') && list.includes('90ms'), list.slice(0, 160));
  ok('…and marks the dead relay instead of printing a fake number',
    /✗/.test(list) && !list.includes('7ms'), list.slice(0, 160));
  ok('the summary line counts how many answered',
    /2\s*از\s*3|2 of 3/.test(document.querySelector('#vipOut').textContent), document.querySelector('#vipOut').textContent);

  // auto-swap switch → PUT proxyAutoSwap false, and the worker must then dial ONLY the star
  const before = writes.length;
  document.querySelector('#swAutoSwap').click();
  await until(() => writes.slice(before).some((w) => w && typeof w === 'object' && 'proxyAutoSwap' in w));
  await sleep(50);
  const swapWrite = writes.slice(before).find((w) => w && typeof w === 'object' && 'proxyAutoSwap' in w);
  ok('clicking the auto-swap switch saves the new value (false = pinned)',
    !!swapWrite && swapWrite.proxyAutoSwap === false, JSON.stringify(writes.slice(before)));
  const pinned = await cur(env);
  ok('…and the worker really narrows to the single starred relay once pinned',
    T.socksRelayList(env, pinned).length === 1 && T.socksRelayList(env, pinned)[0].url === NL,
    T.socksRelayList(env, pinned).map((r) => r.url).join(' | '));
  ok('…and the card states the policy instead of leaving the owner guessing',
    document.querySelector('#vipList').textContent.includes('10.0.0.2') &&
    /تعویض خودکار|Auto-swap/.test(document.body.textContent), 'switch label missing');

  // the build command: real click, real textarea
  document.querySelector('#vipCmdBtn').click();
  await sleep(60);
  const cmd = document.querySelector('#vipCmdText').value;
  ok('the build command opens with your own port/user/password',
    /microsocks -i 0\.0\.0\.0 -p [23]\d{4} -u cat[a-z0-9]{4} -P [a-z0-9]{10}/.test(cmd), cmd.split('\n')[2]);
  ok('…and ends with the exact URL to paste back into the add form',
    /socks5:\/\/cat[a-z0-9]{4}:[a-z0-9]{10}@<IP-سرور>:<PORT>/.test(cmd), cmd.split('\n').slice(-4).join(' / '));
  ok('…and warns to keep the port closed to everything but Cloudflare',
    cmd.includes('173.245.48.0/20'), 'ufw line missing');
  ok('…and it uses a normal distro package (nothing downloaded from an unknown URL)',
    cmd.includes('apt install -y microsocks') && !/curl .*\| *sh/.test(cmd));

  dom.window.close();
}

console.log('\nproxy vip: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
