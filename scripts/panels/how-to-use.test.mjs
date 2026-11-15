/**
 * HOW-TO-USE: a brand-new owner must see, without hunting, how to use the panel.
 *
 * Login is only a password form (no dead tab bar). After sign-in the Home tab
 * opens with three numbered steps and one copy button that uses the same
 * subLinks() the user list already uses. Settings says “change the password,
 * leave the rest”.
 *
 * Run standalone:  node scripts/panels/how-to-use.test.mjs
 */
import { strict as assert } from 'node:assert';
import { pathToFileURL } from 'node:url';

const workerPath = process.env.CAT_PANEL_WORKER
  ? new URL(process.env.CAT_PANEL_WORKER, pathToFileURL(process.cwd() + '/')).href
  : new URL('../../app/src/main/assets/panels/catclient.worker.js', import.meta.url).href;

const T = (await import(workerPath))._testing;
const HOST = 'howto.workers.dev';
const memKv = () => {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); },
    list: async () => ({ keys: [] }),
  };
};
const get = async (env, path, init = {}) => T.handleRequest(new Request('https://' + HOST + path, init), env, { waitUntil() { } });

let pass = 0; let fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); }
};

console.log('\nHow-to-use — a new owner is not lost\n');

{
  const env = {};
  const login = await get(env, '/panel');
  const html = await login.text();
  ok('login is a password form', login.status === 200 && html.includes('type="password"') && html.includes('id="f"'), login.status);
  ok('login has no dead tab bar', !html.includes('class="tabbar"') && !html.includes('data-sec="home"'));
  ok('login says the three steps after sign-in',
    /کپی لینک اشتراک|copy the subscription/i.test(html) && /Cat Client/.test(html));
  ok('first-run still shows admin / admin', html.includes('<code>admin</code>'));
}

{
  const env = { CAT_KV: memKv() };
  const tap = await get(env, '/api/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin' }),
  });
  const cookie = (tap.headers.get('set-cookie') || '').split(';')[0];
  ok('admin / admin still signs in', tap.status === 200 && /cat_session=/.test(cookie), tap.status);
  const page = await get(env, '/panel', { headers: { cookie } });
  const html = await page.text();
  ok('home how-to card is first on the home tab',
    html.includes('id="howUse"') && html.includes('id="howCopy"') && html.includes('id="howLink"')
    && html.indexOf('id="howUse"') < html.indexOf('id="hmKv"'), html.includes('id="howUse"'));
  ok('three numbered steps, no extra knobs',
    html.includes('data-i="how_1"') && html.includes('data-i="how_2"') && html.includes('data-i="how_3"')
    && html.includes('class="howuse"'));
  ok('Persian and English how-to strings ship',
    html.includes("how_title:'چطور استفاده کنم؟'") && html.includes("how_title:'How do I use this?'")
    && html.includes("how_copy:'کپی لینک اشتراک'") && html.includes("how_copy:'Copy subscription link'"));
  ok('copy button uses the same subLinks() as the user list',
    /function howSubUrl\(\)\{/.test(html) && /subLinks\(location\.origin/.test(html)
    && /\$\('#howCopy'\)/.test(html));
  ok('settings tells a new owner to change the password and leave the rest',
    html.includes('id="howSet"') && html.includes("how_set_title:'اول رمز را عوض کن")
    && html.includes("how_set_title:'Change the password first"));
  ok('home tab still has status / errors / usage under the how-to',
    html.includes('id="v-home"') && html.includes('data-i="hm_status"') && html.includes('id="errRows"'));
}

console.log('\n' + (fail === 0 ? `how-to-use: ALL ${pass} PASSED` : `how-to-use: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' checks failed');
