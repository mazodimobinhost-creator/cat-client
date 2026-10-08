import { JSDOM } from 'jsdom';
/**
 * DOM smoke test of the v6 panel UI (needs `npm i` for jsdom).
 * Usage: node scripts/panels/panel-dom.test.mjs
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;
const KV = { m: new Map(), async get(k){return this.m.get(k)??null}, async put(k,v){this.m.set(k,v)}, async list(){return {keys:[]}} };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HOST='https://p.workers.dev';
// beta57 stealth default: the UI lives at /panel — / is the camouflage page.
const htmlRes = await worker.fetch(new Request(HOST + '/panel'), env, {});
const html = await htmlRes.text();
const errors = [];
const dom = new JSDOM(html, { url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true,
  beforeParse(window) {
    window.fetch = async (path, init={}) => {
      const r = await worker.fetch(new Request(HOST + path, { method: init.method||'GET', headers: init.headers||{}, body: init.body }), env, {});
      return r;
    };
    window.confirm = () => true;
    window.addEventListener('error', (e) => errors.push(e.error ? String(e.error.stack||e.error) : e.message));
    window.console.error = (...a) => errors.push(a.join(' '));
  } });
const { window } = dom; const { document } = window;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
await sleep(300);
let failures=0; const check=(n,c,x)=>{ if(c) console.log('✓ '+n); else { failures++; console.error('✗ '+n+(x?' — '+x:'')); } };
check('no JS errors on load', errors.length===0, errors.join('\n'));
check('stats rendered', document.querySelector('#stUsers').textContent==='0');
check('i18n applied (fa)', document.querySelector('[data-i="users"]').textContent.length>2 && document.documentElement.dir==='rtl');
check('kv chip ok', document.querySelector('#chipKv').classList.contains('ok'));
// add user via drawer
document.querySelector('#btnAdd').click();
check('drawer opens', document.querySelector('#drawer').classList.contains('show'));
const f = document.querySelector('#fUser'); f.elements.uname.value='sara'; f.elements.days.value='15';
f.dispatchEvent(new window.Event('submit', { cancelable: true }));
await sleep(300);
check('user created + rendered', document.querySelectorAll('#rows tr').length===1 && document.querySelector('#rows').textContent.includes('sara'), document.querySelector('#rows').innerHTML.slice(0,200));
check('stat updated', document.querySelector('#stUsers').textContent==='1');
// toggle
document.querySelector('[data-toggle]').click(); await sleep(300);
check('toggle → disabled chip', document.querySelector('#rows').textContent.includes('غیرفعال'));
// settings save
document.querySelector('[data-view="settings"]').click();
check('settings view shown', document.querySelector('#v-settings').classList.contains('on'));
const fs = document.querySelector('#fSettings'); fs.elements.ptitle.value='My Cat'; fs.elements.entryLimit.value='20';
fs.dispatchEvent(new window.Event('submit', { cancelable: true })); await sleep(300);
check('settings saved → brand title', document.querySelector('#brandTitle').textContent==='My Cat');
// ip import
document.querySelector('#ipPaste').value='1.1.1.1, 2.2.2.2\nexample.com nope';
document.querySelector('#btnIpAppend').click(); await sleep(300);
check('ips imported', document.querySelector('#ipCount').textContent==='3', document.querySelector('#ipCount').textContent);
// delete user — goes through the ask() modal now (no native confirm)
document.querySelector('[data-del]').click(); await sleep(100);
check('ask modal shown', document.querySelector('#ask').classList.contains('show'));
document.querySelector('#askYes').click(); await sleep(300);
check('user deleted', document.querySelectorAll('#rows tr').length===0);
// beta58: ✍️ manual add — real click, real POST, real storage (regression:
// a template-literal escape bug once mangled every entry into «a.com:443»)
{
  const before = ((await (await window.fetch('/api/settings')).json()).settings.ips || []).length;
  document.querySelector('#manualIps').value = '198.51.100.9\n2606:4700:4700::1111\nwww.visa.com';
  document.querySelector('#manualTest').checked = false;
  document.querySelector('#btnManualAdd').click();
  await sleep(400);
  const st = (await (await window.fetch('/api/settings')).json()).settings;
  const ips = st.ips || [];
  check('manual add: 3 entries stored', ips.includes('198.51.100.9:443') && ips.includes('[2606:4700:4700::1111]:443') && ips.includes('www.visa.com:443'), JSON.stringify(ips));
  check('manual add: list grew by 3', ips.length === before + 3, before + ' → ' + ips.length);
  check('manual add: src=manual badge', !!(st.ipSources && Object.values(st.ipSources).some((v) => v && v.src === 'manual')));
}
// lang toggle triggers reload (location.reload not implemented in jsdom → ignore errors from that)
check('no JS errors overall', errors.filter(e=>!/reload/.test(e)).length===0, errors.join('\n'));
// login page + info page parse
const login = await (await worker.fetch(new Request(HOST + '/panel'), { CAT_KV: KV, UUID: env.UUID }, {})).text();
const d2 = new JSDOM(login, { runScripts:'dangerously' }); check('login page has form', !!d2.window.document.querySelector('#f'));
console.log(failures ? failures+' FAILED' : 'DOM PASSED'); process.exit(failures?1:0);
