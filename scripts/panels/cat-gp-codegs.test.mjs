/**
 * 🌐 Cat GP Code.gs (app/src/main/assets/catgp/Code.gs) run against mocked Apps Script services:
 *   - wrong / missing key → {e:"unauthorized"}, nothing fetched;
 *   - request → UrlFetchApp with the right method, headers (proxy / host dropped), body bytes, no auto-redirects;
 *   - answer → {s, h, b(base64)} exactly as the Cat app's codec reads it;
 *   - HEAD-less methods refused clearly; a refused header is retried with the plain ones;
 *   - with CAT_WORKER set, the request goes to the panel's /gp hop with k and its answer is passed back.
 * Usage: node scripts/panels/cat-gp-codegs.test.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const tpl = fs.readFileSync(path.join(here, '../../app/src/main/assets/catgp/Code.gs'), 'utf8');
let failures = 0;
function check(name, cond, extra) { if (cond) console.log('✓ ' + name); else { failures++; console.error('✗ ' + name + (extra ? ' — ' + extra : '')); } }

check('template has exactly the two placeholders', (tpl.match(/__CAT_KEY__/g) || []).length === 1 && (tpl.match(/__CAT_WORKER__/g) || []).length === 1);
check('script forwards k to the worker hop', /k:\s*CAT_KEY/.test(tpl) && /CAT_WORKER/.test(tpl));
check('script does not echo the worker body on a bad hop answer', /worker HTTP/.test(tpl) && !/t\.slice/.test(tpl));
check('no block comments (GAS reads }/** as a regex → Unexpected token *)', !/\/\*/.test(tpl));
{
  try {
    const s = {
      UrlFetchApp: {},
      Utilities: {},
      ContentService: { MimeType: { JSON: 'json' }, createTextOutput(t) { return { text: t, setMimeType() { return this; } }; } },
      JSON,
    };
    vm.createContext(s);
    vm.runInContext('function myFunction(){\n}\n' + tpl.replace('__CAT_KEY__', '"k"').replace('__CAT_WORKER__', '""'), s);
    check('parses when pasted after the default myFunction', typeof s.doPost === 'function' && typeof s.doGet === 'function');
  } catch (err) {
    check('parses when pasted after the default myFunction', false, String(err));
  }
}

function load(key, worker, fetchImpl) {
  const fetches = [];
  const sandbox = {
    UrlFetchApp: { fetch: (url, opt) => { fetches.push({ url, opt }); return fetchImpl(url, opt, fetches.length); } },
    Utilities: {
      base64Decode: (s) => Array.from(Buffer.from(s, 'base64')).map((b) => (b > 127 ? b - 256 : b)),
      base64Encode: (bytes) => Buffer.from(bytes.map((b) => (b < 0 ? b + 256 : b))).toString('base64'),
    },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: (t) => ({ text: t, setMimeType() { return this; } }) },
    JSON,
  };
  vm.createContext(sandbox);
  vm.runInContext(tpl.replace('__CAT_KEY__', JSON.stringify(key)).replace('__CAT_WORKER__', JSON.stringify(worker)), sandbox);
  const post = (obj) => JSON.parse(sandbox.doPost({ postData: { contents: JSON.stringify(obj) } }).text);
  return { sandbox, fetches, post };
}
const resp = (code, headers, bytes) => ({ getResponseCode: () => code, getAllHeaders: () => headers, getContent: () => bytes, getContentText: () => Buffer.from(bytes.map((b) => (b < 0 ? b + 256 : b))).toString() });

{
  const { fetches, post } = load('sekret', '', () => resp(200, { 'Content-Type': 'text/plain', 'Set-Cookie': ['a=1', 'b=2'] }, [104, 105, -1]));
  check('wrong key refused', post({ k: 'nope', u: 'https://x.example/', m: 'GET' }).e === 'unauthorized' && fetches.length === 0);
  check('missing key refused', post({ u: 'https://x.example/' }).e === 'unauthorized' && fetches.length === 0);
  check('non-http URL refused', post({ k: 'sekret', u: 'file:///x' }).e === 'bad url' && fetches.length === 0);
  check('non-string URL refused', post({ k: 'sekret', u: { href: 'https://x.example/' } }).e === 'bad url' && fetches.length === 0);
  check('method UrlFetchApp lacks → clear error', /not supported/.test(post({ k: 'sekret', u: 'https://x.example/', m: 'OPTIONS' }).e) && fetches.length === 0);
  const r = post({ k: 'sekret', u: 'https://x.example/p?q=1', m: 'POST', h: { Host: 'evil', 'Proxy-Authorization': 'x', 'Proxy-Foo': 'y', Accept: 'a/b', Cookie: 'c=1' }, b: Buffer.from([0, 200, 65]).toString('base64'), ct: 'application/octet-stream', r: false });
  const f = fetches[0];
  check('fetches the URL once with the method', fetches.length === 1 && f.url === 'https://x.example/p?q=1' && f.opt.method === 'post');
  check('host / proxy headers dropped, others kept', !('Host' in f.opt.headers) && !('Proxy-Authorization' in f.opt.headers) && !('Proxy-Foo' in f.opt.headers) && f.opt.headers.Accept === 'a/b' && f.opt.headers.Cookie === 'c=1', JSON.stringify(f.opt.headers));
  check('body bytes + content type passed, redirects left to the browser', Buffer.from(f.opt.payload.map((b) => (b < 0 ? b + 256 : b))).equals(Buffer.from([0, 200, 65])) && f.opt.contentType === 'application/octet-stream' && f.opt.followRedirects === false && f.opt.muteHttpExceptions === true);
  check('answer is {s,h,b} the app reads', r.s === 200 && Array.isArray(r.h['Set-Cookie']) && Buffer.from(r.b, 'base64').equals(Buffer.from([104, 105, 255])), JSON.stringify(r));
}
{
  let n = 0;
  const { fetches, post } = load('k', '', (url, opt) => { n++; if (n === 1) throw new Error('Attribute provided with invalid value: Header:User-Agent'); return resp(204, {}, []); });
  const r = post({ k: 'k', u: 'https://x.example/', m: 'GET', h: { 'User-Agent': 'Chrome', Accept: 'text/html', 'X-Odd': '1' } });
  check('refused header → one retry with plain headers', r.s === 204 && fetches.length === 2 && fetches[1].opt.headers.Accept === 'text/html' && !('User-Agent' in fetches[1].opt.headers) && !('X-Odd' in fetches[1].opt.headers), JSON.stringify(fetches.map((x) => x.opt.headers)));
  const { post: post2 } = load('k', '', () => { throw new Error('Address unavailable'); });
  check('other fetch errors become {e}', /Address unavailable/.test(post2({ k: 'k', u: 'https://x.example/' }).e));
}
{
  // Worker hop: the script hands the request to the REAL worker's /gp/<uuid>/<key> (with k) and returns its answer unchanged.
  const UUID = '41111111-2222-4333-8444-555555555555';
  const KEY = 'codegsHopSecretToken12';
  const HOP = 'https://cat-gp.example.workers.dev/gp/' + UUID + '/' + KEY;
  globalThis.fetch = async (u, init = {}) => new Response('site:' + (init.method || 'GET'), { status: 200, headers: { 'content-type': 'text/plain' } });
  const workerPath = process.env.CAT_PANEL_WORKER || path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');
  const worker = (await import(workerPath)).default;
  class FakeKV { constructor(){this.m=new Map();} async get(k,o){const v=this.m.has(k)?this.m.get(k):null;return v!==null&&o&&(o==='json'||o.type==='json')?JSON.parse(v):v;} async put(k,v){this.m.set(k,v);} async delete(k){this.m.delete(k);} async list(){return {keys:[],list_complete:true};} }
  const env = { CAT_KV: new FakeKV(), UUID };
  // UrlFetchApp is synchronous; run the worker first and replay its answer.
  let hopBody = null;
  const { fetches, post } = load(KEY, HOP, (url, opt) => { hopBody = opt.payload; return { getResponseCode: () => 200, getContentText: () => pending } });
  let pending = '';
  // First pass captures the payload the script sends to the hop.
  post({ k: KEY, u: 'https://site.example/a', m: 'GET', h: { Accept: 'x/y' } });
  check('script posts to the hop address', fetches.length === 1 && fetches[0].url === HOP && fetches[0].opt.method === 'post');
  const sent = JSON.parse(hopBody);
  check('hop payload carries u / m / h and the script key', sent.u === 'https://site.example/a' && sent.m === 'GET' && sent.h.Accept === 'x/y' && sent.k === KEY, JSON.stringify(sent));
  const wr = await worker.fetch(new Request(HOP, { method: 'POST', body: hopBody, headers: { 'content-type': 'application/json' } }), env, { waitUntil() {} });
  pending = await wr.text();
  const r = post({ k: KEY, u: 'https://site.example/a', m: 'GET', h: { Accept: 'x/y' } });
  check('worker answer passes back as {s,h,b}', r.s === 200 && Buffer.from(r.b, 'base64').toString() === 'site:GET', JSON.stringify(r).slice(0, 160));
}
if (failures) { console.error(failures + ' failure(s)'); process.exit(1); }
console.log('all Cat GP Code.gs checks passed');
