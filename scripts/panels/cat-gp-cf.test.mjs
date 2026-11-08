/**
 * Cat GP dedicated Cloudflare Worker (app/src/main/assets/catgp/worker.js):
 *   - GET HTML says Google Script and Cloudflare are connected;
 *   - GET JSON { relay: "cat-gp-cf", linked: true };
 *   - POST same wire as Code.gs; wrong key → unauthorized; not the Cat panel.
 * Usage: node scripts/panels/cat-gp-cf.test.mjs
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const tpl = fs.readFileSync(path.join(here, '../../app/src/main/assets/catgp/worker.js'), 'utf8');
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }

check('one key placeholder', (tpl.match(/__CAT_KEY__/g) || []).length === 1);
check('page says the pair is connected', /گوگل اسکریپت و کلادفلر به هم متصل شدند/.test(tpl) && /Google Script and Cloudflare are connected/.test(tpl));
check('not the Cat panel', /not the Cat panel/.test(tpl) && !/\/panel\//.test(tpl));
check('no block comments', !/\/\*/.test(tpl));

const KEY = 'GpCfDedicatedKey12ab';
let calls = [];
globalThis.fetch = async (u, init = {}) => {
  const url = String(u);
  const headers = new Headers(init.headers || {});
  const body = init.body ? Buffer.from(init.body) : null;
  calls.push({ url, method: (init.method || 'GET').toUpperCase(), headers, body, redirect: init.redirect });
  if (url.startsWith('https://site.example/')) {
    return new Response('site-ok', { status: 200, headers: { 'content-type': 'text/plain' } });
  }
  return new Response('', { status: 404 });
};
const filled = tpl.replace('__CAT_KEY__', JSON.stringify(KEY));
const tmp = path.join(os.tmpdir(), 'cat-gp-cf-worker-' + Date.now() + '.mjs');
fs.writeFileSync(tmp, filled);
const worker = (await import(pathToFileURL(tmp).href)).default;
const HOST = 'https://cat-gp.example.workers.dev';
const call = (init = {}) => worker.fetch(new Request(HOST + '/', init));

{
  const html = await call({ headers: { accept: 'text/html' } });
  const t = await html.text();
  check('GET HTML is the connected page', html.status === 200 && t.includes('گوگل اسکریپت و کلادفلر به هم متصل شدند') && t.includes('not the Cat panel'));
  const j = await (await call({ headers: { accept: 'application/json' } })).json();
  check('GET JSON linked', j.ok === true && j.relay === 'cat-gp-cf' && j.linked === true, JSON.stringify(j));
}
{
  calls = [];
  const bad = await (await call({ method: 'POST', body: JSON.stringify({ k: 'nope', u: 'https://site.example/' }), headers: { 'content-type': 'application/json' } })).json();
  check('wrong key refused', bad.e === 'unauthorized' && calls.length === 0, JSON.stringify(bad));
  const ok = await (await call({ method: 'POST', body: JSON.stringify({ k: KEY, u: 'https://site.example/x', m: 'GET', h: { Accept: 'text/plain' } }), headers: { 'content-type': 'application/json' } })).json();
  check('POST relays and marks linked', ok.s === 200 && ok.linked === true && Buffer.from(ok.b, 'base64').toString() === 'site-ok', JSON.stringify(ok).slice(0, 160));
  check('exactly one site fetch', calls.length === 1 && calls[0].url === 'https://site.example/x');
}
if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all Cat GP dedicated Worker checks passed');
