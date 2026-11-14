/**
 * Panel on Vercel — storage policy of assets/vercel/panel-adapter.mjs (Vercel Blob as the panel KV).
 * Hobby Blob allows ~2,000 writes/month: settings/users must persist at once, traffic/seen/audit are
 * throttled, caches and rate-limit counters never leave memory, and a failed read is retried.
 * The blobKv() block is evaluated straight from the adapter source with a fake clock and store.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = fs.readFileSync(path.join(here, '../../app/src/main/assets/vercel/panel-adapter.mjs'), 'utf8');
const start = src.indexOf('const reqScope = new AsyncLocalStorage();');
const end = src.indexOf('async function vercelBlobClient()');
if (start < 0 || end < 0) throw new Error('blobKv block not found in the adapter');
const code = "import { AsyncLocalStorage } from 'node:async_hooks';\n" + src.slice(start, end) + '\nexport { reqScope };\n';
const mod = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'));

let failures = 0;
const check = (name, cond, extra) => { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } };

let now = 1_000_000;
const clock = () => now;
const blobs = new Map();
const ops = { put: 0, get: 0, del: 0, list: 0 };
let failNextGet = 0;
const client = {
  async get(p) { ops.get++; if (failNextGet > 0) { failNextGet--; throw new Error('blob 503'); } return blobs.has(p) ? blobs.get(p) : null; },
  async put(p, v) { ops.put++; blobs.set(p, v); },
  async del(p) { ops.del++; blobs.delete(p); },
  async list(prefix) { ops.list++; return [...blobs.keys()].filter((k) => k.startsWith(prefix)); },
};
const kv = mod.blobKv(client, clock);

await kv.put('cat:v6:users', '[{"id":"a"}]');
check('users are written to Blob at once', ops.put === 1 && [...blobs.keys()].every((k) => /^catkv\/[0-9a-f]+$/.test(k)), JSON.stringify([...blobs.keys()]));
await kv.put('cat_repo_cache_v1', '{"ips":[]}');
await kv.put('lf:1.2.3.4', '{"fails":2}', { expirationTtl: 3600 });
await kv.put('cat_events_v1', '[]');
check('caches / rate-limit / events never reach Blob', ops.put === 1, String(ops.put));
check('memory-only values are still readable in this instance', (await kv.get('lf:1.2.3.4', 'json')).fails === 2);
now += 3601 * 1000;
check('expirationTtl is honoured in memory', (await kv.get('lf:1.2.3.4')) === null);

await kv.put('traffic:a', '{"up":1}');
check('first traffic write goes out', ops.put === 2);
for (let i = 2; i < 50; i++) await kv.put('traffic:a', '{"up":' + i + '}');
check('traffic is throttled (49 updates → 1 write)', ops.put === 2, String(ops.put));
check('throttled value is served fresh from memory', JSON.parse(await kv.get('traffic:a')).up === 49);
now += 3 * 3600e3 + 1;
await kv.get('cat:v6:settings');
check('dirty traffic is flushed on a later request after the interval', ops.put === 3 && JSON.parse(blobs.get('catkv/' + Buffer.from('traffic:a').toString('hex'))).up === 49, String(ops.put));

// A second instance (fresh memory) sees what the first one persisted.
const kv2 = mod.blobKv(client, clock);
check('another instance reads the users list', (await kv2.get('cat:v6:users', 'json'))[0].id === 'a');
const getsBefore = ops.get;
await mod.reqScope.run({ tunnel: true }, () => kv2.get('cat:v6:users'));
now += 60e3;
await mod.reqScope.run({ tunnel: true }, () => kv2.get('cat:v6:users'));
check('tunnel requests reuse the cached read for 15 min', ops.get === getsBefore, String(ops.get - getsBefore));
now += 16e3;
await mod.reqScope.run({ tunnel: false }, () => kv2.get('cat:v6:users'));
check('panel/subscription requests re-read after 15 s', ops.get === getsBefore + 1);

failNextGet = 1;
now += 20e3;
const retried = await kv2.get('cat:v6:users', 'json');
check('a failed Blob read is retried, not mistaken for "no users"', Array.isArray(retried) && retried[0].id === 'a');
failNextGet = 2;
now += 20e3;
let threw = false;
try { await kv2.get('cat:v6:users'); } catch (_) { threw = true; }
check('a persistent Blob failure surfaces as an error', threw);

const listed = await kv2.list({ prefix: 'cat:v6:' });
check('list() decodes hex paths back to keys', listed.keys.some((k) => k.name === 'cat:v6:users'), JSON.stringify(listed.keys));
await kv2.delete('cat:v6:users');
check('delete removes the blob', ![...blobs.keys()].includes('catkv/' + Buffer.from('cat:v6:users').toString('hex')));

if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all vercel-storage checks passed');
