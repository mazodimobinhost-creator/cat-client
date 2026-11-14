/**
 * EMERGENCY BRIDGE / dnstt (panel 6.67.6) — the layer that is left when everything else is
 * blocked. A DNS tunnel rides inside ordinary lookups through a DoH/DoT resolver, so a line
 * that still resolves names can still carry a tunnel; it is the method that kept working
 * through the February 2026 blackout (sources in docs/iran-2026-notes.md). It is slow and
 * unstable, which is why it belongs in an «emergency» card and not in the default path.
 *
 * What is pinned here, and why each check is worth a test:
 *
 *   1. the three official Tor bridge lines are the ones in the card, byte for byte — a typo
 *      in a pubkey is a bridge that never connects, and the card is copy-paste-only, so the
 *      text in the page IS the deliverable;
 *   2. the build script really is a script: dnstt comes from the official module path with
 *      `go install` (public-domain source — NOT an unknown binary from a link, the same rule
 *      the relay card follows), the tunnel terminates at the wireguard of the tunnel it
 *      serves (127.0.0.1:9001), and the two values only the server knows (its public key and
 *      the Tor bridge fingerprint) are read from the server by the script itself instead of
 *      being invented in the panel;
 *   3. the resolver argument is translated to the exact key the dnstt client reads
 *      (`doh=` / `dot=` / `udp=`) — verified against dnstt-client's own source, which reads
 *      those three names out of the SOCKS args and never touches the SOCKS destination, so the
 *      first token of the bridge line is line shape, not a dial target;
 *   4. garbage input is refused with a message instead of producing a script that fails
 *      halfway through on the server.
 *
 * The page half runs the REAL generated HTML in jsdom with a fetch mock (the same harness the
 * VIP suite uses), so what is tested is the wiring the owner actually clicks.
 *
 * Usage: node scripts/panels/emergency-bridge.test.mjs
 */
import { strict as assert } from 'node:assert';
import { JSDOM } from 'jsdom';

const workerPath = process.env.CAT_PANEL_WORKER ||
  new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).pathname;
const T = (await import(workerPath))._testing;

const HOST = 'p.workers.dev';
const UUID = '11111111-2222-4333-8444-555555555555';
const OWNER = 'owner-bridge';
const TOR_URL = 'https://forum.torproject.org/t/connecting-to-tor-from-iran-using-dnstt-with-orbot/21485';

let pass = 0;
let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const memKv = () => {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
    list: async () => ({ keys: [], list_complete: true }),
  };
};

async function panel(patch = {}) {
  const kv = memKv();
  const env = { CAT_KV: kv, UUID };
  const settings = Object.assign({}, await T.readSettings(env), {
    passwordHash: await T.sha256Hex(OWNER),
  }, patch);
  await kv.put(T.KV_KEYS.settings, JSON.stringify(settings));
  return { kv, env };
}

console.log('\nEmergency bridge / dnstt (panel 6.67.6)\n');

/* ---------- the page, loaded for real ---------- */
const { env } = await panel();
T.kvCacheClear();
const settings = await T.readSettings(env);
const html = await T.panelPage(env, settings, HOST, UUID);

const errors = [];
const dom = new JSDOM(html, {
  url: 'https://' + HOST + '/panel', runScripts: 'dangerously', pretendToBeVisual: true,
  beforeParse(window) {
    window.addEventListener('error', (e) => errors.push(String((e.error && e.error.stack) || e.message)));
    window.fetch = async (path, init = {}) => {
      const url = new URL(path, 'https://' + HOST);
      const method = (init.method || 'GET').toUpperCase();
      let body = init.body;
      if (body && typeof body === 'object' && !(body instanceof ArrayBuffer)) body = JSON.stringify(body);
      const req = new Request(url, {
        method,
        headers: Object.assign({ 'content-type': 'application/json', 'x-cat-key': OWNER }, init.headers || {}),
        body: method === 'GET' || method === 'HEAD' ? undefined : body,
      });
      return T.handleRequest(req, env, { waitUntil() { } });
    };
  },
});
const { window } = dom;
const { document } = window;
await sleep(350);

/* ---------- 1) the card is there, with Tor's own lines ---------- */
{
  const card = document.querySelector('#bridgeCard');
  ok('the emergency bridge card is part of the panel', !!card);
  ok('the page loads with no JS error', errors.length === 0, errors.join(' | ').slice(0, 200));

  const tor = document.querySelector('#brTorLines');
  const lines = String((tor && tor.value) || '').split('\n').filter(Boolean);
  ok('three bridge lines are offered', lines.length === 3, lines.length);
  ok('every line is a dnstt line and carries the five things Orbot needs',
    lines.every((l) => /^dnstt \S+ [0-9A-F]{40} doh=\S+ pubkey=[0-9a-f]{64} domain=\S+$/.test(l)),
    lines[0]);
  // Byte-for-byte: a single flipped hex digit in a pubkey is a bridge that never connects.
  ok('…the exact official pubkey of the first line (typo = a dead bridge)',
    lines[0].includes('pubkey=241169008830694749fe96bb070c4855c5bb5b9c47b3833ed7d88521ba30a43f'));
  ok('…the three tunnel domains from the official thread',
    lines.map((l) => l.split('domain=')[1]).join(',') === 't.ruhnama.net,t2.bypasscensorship.org,t.arkadag.org',
    lines.map((l) => l.split('domain=')[1]).join(','));

  ok('the card says where the lines come from (a moving target needs a source link)',
    document.querySelector('#brSrc').getAttribute('href') === TOR_URL);
  const text = card.textContent;
  ok('the Orbot steps name the real menu path', /Set Transport/.test(text) && /Custom Bridges/.test(text));
  ok('…and the dns.google caveat is stated, with the two alternatives',
    /dns\.google/.test(text) && /dot=1\.1\.1\.1:853/.test(text) && /udp=8\.8\.8\.8:53/.test(text));
  ok('the card admits the cost instead of selling it (slow + unstable)',
    /۱۰۰\s?KB\/s/.test(text) && /کند/.test(text) && /بی.?ثبات/.test(text), text.slice(0, 160));
}

/* ---------- 2) the self-hosted script ---------- */
const gen = async (domain, ip, resolver) => {
  document.querySelector('#brDomain').value = domain;
  document.querySelector('#brIp').value = ip;
  document.querySelector('#brDoh').value = resolver;
  document.querySelector('#brGen').click();
  await sleep(60);
  return {
    out: document.querySelector('#brOut').textContent,
    box: document.querySelector('#brCmdBox').style.display,
    cmd: document.querySelector('#brCmdText').value,
  };
};

{
  const r = await gen('example.com', '203.0.113.2', 'https://dns.google/dns-query');
  ok('valid input produces the script (and an empty error line)', r.box === 'block' && r.out === '', r.out || r.box);
  const cmd = r.cmd;
  ok('the script is a bash script', cmd.startsWith('#!/usr/bin/env bash'));

  ok('dnstt is built from the official public-domain source, not downloaded as a binary',
    cmd.includes('go install www.bamsoftware.com/git/dnstt.git/dnstt-server@latest') &&
    !/curl .*\| *sh/.test(cmd) && !/wget .*dnstt/.test(cmd));
  ok('…and installed system-wide with an explicit mode (not run from the user home)',
    cmd.includes('sudo install -m 0755 "$(go env GOPATH)/bin/dnstt-server" /usr/local/bin/dnstt-server'));
  ok('the tunnel key is generated on the server and kept 0400',
    cmd.includes('-gen-key -privkey-file /etc/dnstt/server.key -pubkey-file /etc/dnstt/server.pub') &&
    cmd.includes('chmod 400 /etc/dnstt/server.key'));

  ok('a private Tor bridge is created (bridges must not be published by default)',
    cmd.includes('BridgeRelay 1') && cmd.includes('ORPort 9001') &&
    cmd.includes('PublishServerDescriptor 0') && cmd.includes('ExitRelay 0'));
  ok('…and the tunnel terminates at that local ORPort, not at a remote service',
    /ExecStart=\/usr\/local\/bin\/dnstt-server -udp :53 -privkey-file \/etc\/dnstt\/server\.key \$TUNNEL 127\.0\.0\.1:9001/.test(cmd));
  ok('UDP/53 is opened — the port recursive resolvers actually query',
    cmd.includes('ufw allow 53/udp') && cmd.includes('-udp :53'));

  ok('the DNS records the owner must add are printed with their own domain',
    cmd.includes('A     $NODE   -> $SERVER_IP') && cmd.includes('NS    $TUNNEL -> $NODE') &&
    cmd.includes('DOMAIN="example.com"') && cmd.includes('SERVER_IP="203.0.113.2"'));
  ok('the tunnel zone keeps the short `t.` label the README asks for, with `tns.` for the A record',
    cmd.includes('TUNNEL="t.$DOMAIN"') && cmd.includes('NODE="tns.$DOMAIN"'));

  ok('the bridge line printed at the end carries the DNS port and the resolver key',
    cmd.includes('echo "dnstt $SERVER_IP:53') && cmd.includes('$RESOLVER_ARGS pubkey=$PUB domain=$TUNNEL"'));
  ok('…with the resolver translated to the key the dnstt client reads', cmd.includes('RESOLVER_ARGS="doh=https://dns.google/dns-query"'));
  // Both values live on the server: inventing them in the panel would hand out a line that
  // looks right and cannot authenticate.
  ok('the public key and the bridge fingerprint are read FROM the server',
    cmd.includes('PUB=$(sudo cat /etc/dnstt/server.pub)') &&
    cmd.includes('grep -rhoE "[0-9A-F]{40}" /var/lib/tor/fingerprint'));
  ok('…and a missing fingerprint stops the script instead of printing a broken line',
    cmd.includes('${FP:?'));
  ok('the script ends with a real check, not just «done»',
    cmd.includes('systemctl status dnstt') && cmd.includes('dig +short NS $TUNNEL @1.1.1.1'));
}

/* ---------- 3) the resolver argument: three transports ---------- */
{
  const dot = await gen('example.com', '203.0.113.2', 'tls://1.1.1.1');
  ok('tls:// becomes dot= with the standard 853 port filled in',
    dot.cmd.includes('RESOLVER_ARGS="dot=1.1.1.1:853"'), dot.cmd.split('\n').find((l) => l.includes('RESOLVER_ARGS')));

  const dotp = await gen('example.com', '203.0.113.2', 'tls://9.9.9.9:8853');
  ok('an explicit DoT port is kept as given', dotp.cmd.includes('RESOLVER_ARGS="dot=9.9.9.9:8853"'));

  const udp = await gen('example.com', '203.0.113.2', '8.8.8.8:53');
  ok('a bare host:port is read as plaintext UDP DNS', udp.cmd.includes('RESOLVER_ARGS="udp=8.8.8.8:53"'));

  const udpp = await gen('example.com', '203.0.113.2', 'udp://8.8.8.8');
  ok('udp:// fills in port 53', udpp.cmd.includes('RESOLVER_ARGS="udp=8.8.8.8:53"'));
}

/* ---------- 4) garbage is refused where it is typed ---------- */
{
  const bad = [
    ['', '203.0.113.2', 'https://dns.google/dns-query', 'br_bad_domain'],
    ['http://example.com', '203.0.113.2', 'https://dns.google/dns-query', 'br_bad_domain'],
    ['example', '203.0.113.2', 'https://dns.google/dns-query', 'br_bad_domain'],
    ['example.com', '', 'https://dns.google/dns-query', 'br_bad_ip'],
    ['example.com', '1.2.3.4:53', 'https://dns.google/dns-query', 'br_bad_ip'],
    ['example.com', '999.1.1.1', 'https://dns.google/dns-query', 'br_bad_ip'],
    ['example.com', '203.0.113.2', 'http://dns.google/dns-query', 'br_bad_doh'],
    ['example.com', '203.0.113.2', 'dns.google', 'br_bad_doh'],
  ];
  const fa = (k) => ({ br_bad_domain: /دامنهٔ معتبر/, br_bad_ip: /IP معتبر/, br_bad_doh: /resolver/ }[k]);
  for (const [domain, ip, resolver, key] of bad) {
    const r = await gen(domain, ip, resolver);
    ok(`refused: ${key} for ${JSON.stringify(domain)} / ${JSON.stringify(ip)} / ${JSON.stringify(resolver)}`,
      fa(key).test(r.out), r.out || '(no message)');
  }
  // IPv6 is a legitimate server address, so it must NOT be refused.
  const v6 = await gen('example.com', '2001:db8::2', 'https://dns.google/dns-query');
  ok('an IPv6 server address is accepted', v6.cmd.includes('SERVER_IP="2001:db8::2"'), v6.out || v6.cmd.slice(0, 60));
  // A wrong answer must never leave the previous script looking current.
  const r = await gen('bad', '203.0.113.2', 'https://dns.google/dns-query');
  ok('…and the box is not the thing that reports success', r.out !== '' && fa('br_bad_domain').test(r.out), r.out);
}

/* ---------- 5) copy buttons hand over exactly what is on screen ---------- */
{
  const seen = [];
  window.navigator.clipboard = { writeText: async (v) => { seen.push(v); } };
  document.querySelector('#brTorCopy').click();
  await sleep(60);
  const t = document.querySelector('#brTorLines').value;
  ok('the Tor copy button copies all three lines into the clipboard',
    seen.some((v) => v === t && v.split('\n').length === 3), seen.length + ' writes');

  await gen('example.com', '203.0.113.2', 'https://dns.google/dns-query');
  document.querySelector('#brCmdCopy').click();
  await sleep(60);
  ok('the script copy button copies the whole script',
    seen.some((v) => v.startsWith('#!/usr/bin/env bash') && v.includes('dnstt-server@latest')), seen.length + ' writes');
}

dom.window.close();

console.log('\nemergency bridge: ' + pass + ' passed, ' + fail + ' FAILED');
assert.equal(fail, 0, fail + ' checks failed');
