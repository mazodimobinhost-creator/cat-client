/**
 * HOW-TO-USE: a brand-new owner must see the real setup order, not a dummy
 * copy-paste card: scan IPs → pick a country if they want one → get ProxyIP
 * and SOCKS. Each Home button opens the real page (scanner / loc finder).
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

console.log('\nHow-to-use — scan, then country, then ProxyIP/SOCKS\n');

{
  const env = {};
  const login = await get(env, '/panel');
  const html = await login.text();
  ok('login is a password form', login.status === 200 && html.includes('type="password"') && html.includes('id="f"'), login.status);
  ok('login has no dead tab bar', !html.includes('class="tabbar"') && !html.includes('data-sec="home"'));
  ok('login names the real order (scan → country → ProxyIP/SOCKS)',
    /اسکن آی‌پی|scan IPs/i.test(html) && /ProxyIP/.test(html) && /SOCKS/.test(html));
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
  ok('how-to card is first on the dashboard (before marquee / PWA / stats)',
    html.includes('id="howUse"') && html.includes('id="howScan"') && html.includes('id="howCc"') && html.includes('id="howPx"')
    && html.indexOf('id="v-dash"') < html.indexOf('id="howUse"')
    && html.indexOf('id="howUse"') < html.indexOf('class="marq"')
    && html.indexOf('id="howUse"') < html.indexOf('id="pwaBanner"')
    && html.indexOf('id="howUse"') < html.indexOf('id="hmKv"'));
  ok('three numbered steps are scan → country → ProxyIP/SOCKS',
    html.includes("how_1:'اسکن آی‌پی تمیز'") && html.includes("how_2:'کشور خروجی را انتخاب کن'")
    && html.includes("how_3:'ProxyIP و SOCKS بگیر'")
    && html.includes("how_1:'Scan clean IPs'") && html.includes("how_2:'Pick an exit country'")
    && html.includes("how_3:'Get ProxyIP and SOCKS'"));
  ok('step 1 says tick healthy IPs and send them to the panel list',
    html.includes('به آی‌پی‌های پنل') && html.includes('To the panel IPs'));
  ok('how-to card shows live IP / country / ProxyIP / SOCKS counts',
    html.includes('id="howNow"') && html.includes('function renderHowNow(){')
    && html.includes("how_now:'الان: %1 آی‌پی در لیست") && html.includes("how_now:'Now: %1 IPs in the list"));
  ok('menu label for the IP list is لیست آی‌پی, not Nodes',
    html.includes("n_nodes:'لیست آی‌پی'") && html.includes("n_nodes:'IP list'")
    && !html.includes("n_nodes:'نودها'") && !html.includes("n_nodes:'Nodes'"));
  ok('step buttons open the real scanner and loc finder (not a fake page)',
    /function howGo\(view,sec,atab\)\{/.test(html)
    && /howGo\('scan','addr','ip'\)/.test(html)
    && /howGo\('loc','loc'\)/.test(html)
    && /locKind[\s\S]{0,40}both/.test(html));
  ok('copy-sub is after the three setup steps, not instead of them',
    html.includes('id="howCopy"') && html.indexOf('id="howScan"') < html.indexOf('id="howCopy"')
    && html.includes("how_copy:'کپی لینک اشتراک (بعد از سه قدم بالا)'"));
  ok('settings points back to the same Home order',
    html.includes('id="howSet"') && /اسکن آی‌پی/.test(html) && /ProxyIP و SOCKS/.test(html));
  ok('home tab still has status / errors / usage under the how-to',
    html.includes('id="v-home"') && html.includes('data-i="hm_status"') && html.includes('id="errRows"'));
  ok('IP list is in the sidebar and the hamburger menu (opens the panel IPs tab)',
    /id="sideNav"[\s\S]*?data-view="nodes"/.test(html)
    && /id="menu"[\s\S]*?data-view="nodes"/.test(html)
    && !html.includes('data-view="spoof"'));
  ok('MITM / Patterniha / public SOCKS / Serverless preset are off the owner path',
    !html.includes('MITM-DomainFronting') && !html.includes('id="btnExtPresetFree"')
    && !html.includes('id="btnExtPreset"') && !html.includes('id="skLiveFetch"')
    && !html.includes('id="ehGo"') && !html.includes('loc#ehGo') && !html.includes('patterniha/')
    && !html.includes('vakhov/fresh-proxy-list')
    && !html.includes("t('hero_warn')"));
  ok('SNI spoofing is behind a closed details, switch still in the page',
    html.includes('id="swSniRot"') && html.includes('id="sniSpoofAdv"') && html.includes('id="sniSpoofExtra"')
    && html.indexOf('id="sniSpoofAdv"') < html.indexOf('id="swSniRot"')
    && html.indexOf('id="sniSpoofExtra"') < html.indexOf('name="extraSnis"'));
  ok('user names in the list are prefixed with 🐈‍⬛',
    html.includes('<div class="b">🐈‍⬛ \'+esc(u.name)') && html.includes('<span class="nm">🐈‍⬛ \'+esc(u.name)'));
}

console.log('\n' + (fail === 0 ? `how-to-use: ALL ${pass} PASSED` : `how-to-use: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' checks failed');
