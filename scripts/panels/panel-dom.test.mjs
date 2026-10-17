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
let failUserCreate = false;
let failUserNonJson = false;
let failUserList = false;
let failSubscription = false;
let emptySubscription = false;
const dom = new JSDOM(html, { url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true,
  beforeParse(window) {
    window.fetch = async (path, init={}) => {
      const url = new URL(path, HOST);
      if (failUserList && url.pathname === '/api/users' && (init.method || 'GET') === 'GET') {
        return new Response('user-list unavailable', { status: 503, headers: { 'content-type': 'text/plain' } });
      }
      if (failUserCreate && url.pathname === '/api/users' && (init.method || 'GET') === 'POST') {
        return new Response(JSON.stringify({ ok: false, error: 'internal', message: 'diagnostic detail https://example.test/private/11111111-2222-4333-8444-555555555555' }), {
          status: 500, headers: { 'content-type': 'application/json', 'cf-ray': 'diagnostic-user-create-ray' },
        });
      }
      if (failUserNonJson && url.pathname === '/api/users' && (init.method || 'GET') === 'POST') {
        return new Response('PRIVATE_INTERNAL_HTML https://example.test/hidden/11111111-2222-4333-8444-555555555555', {
          status: 500, headers: { 'content-type': 'text/html; charset=utf-8', 'cf-ray': 'diagnostic-user-non-json-ray' },
        });
      }
      if (failSubscription && url.pathname.startsWith('/sub/')) {
        return new Response('worker-private-body', { status: 502, headers: { 'content-type': 'text/plain', 'cf-ray': 'diagnostic-subscription-ray' } });
      }
      if (emptySubscription && url.pathname.startsWith('/sub/')) {
        return new Response('\n', { status: 200, headers: { 'content-type': 'text/plain' } });
      }
      const r = await worker.fetch(new Request(url, { method: init.method||'GET', headers: init.headers||{}, body: init.body }), env, {});
      return r;
    };
    window.confirm = () => true;
    if (!window.URL.createObjectURL) window.URL.createObjectURL = () => 'blob:mock';
    if (!window.URL.revokeObjectURL) window.URL.revokeObjectURL = () => {};
    window.addEventListener('error', (e) => errors.push(e.error ? String(e.error.stack||e.error) : e.message));
    window.console.error = (...a) => errors.push(a.join(' '));
  } });
const { window } = dom; const { document } = window;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
await sleep(300);
let failures=0; const check=(n,c,x)=>{ if(c) console.log('✓ '+n); else { failures++; console.error('✗ '+n+(x?' — '+x:'')); } };
check('no JS errors on load', errors.length===0, errors.join('\n'));
check('stats rendered', document.querySelector('#stUsers').textContent==='0');
// Aether builder: preserve the classic URI, expose PattNG's newer WG-over-MASQUE mode,
// and keep the H2 fingerprint/fragment query names exactly as its parser expects.
{
  const parseAether = (link) => new URLSearchParams(link.split('?')[1].split('#')[0]);
  const classic = parseAether(document.querySelector('#aeLink').value);
  check('Aether default keeps legacy Gool as WireGuard-in-WireGuard', classic.get('protocol') === 'gool' && classic.get('scan') === 'balanced' && !classic.has('transport'));
  document.querySelector('#aePick [data-v="wg-over-masque"]').click();
  const wgMasqueLink = document.querySelector('#aeLink').value;
  const wgMasque = parseAether(wgMasqueLink);
  check('Aether exposes new WireGuard-over-MASQUE Gool over HTTP/2', wgMasque.get('protocol') === 'wg-over-masque' && wgMasque.get('transport') === 'h2' && wgMasque.get('ip') === 'both');
  check('new Gool uses PattNG fingerprint and native Aether fragmentation keys', wgMasque.get('fingerprint') === 'semi-python' && wgMasque.get('fragment') === '1' && wgMasque.get('fragment_size') === '8-16' && wgMasque.get('fragment_delay') === '5-10' && !wgMasque.has('fingerPrint') && !wgMasque.has('fm'));
  document.querySelector('#aePick [data-v="masque"]').click();
  const masque = parseAether(document.querySelector('#aeLink').value);
  check('MASQUE/H2 link uses the same parser-supported TLS options', masque.get('protocol') === 'masque' && masque.get('transport') === 'h2' && masque.get('fingerprint') === 'semi-python' && masque.get('fragment') === '1' && !masque.has('fm'));
  document.querySelector('#aePick [data-v="warp"]').click();
  check('single-hop WARP link uses PattNG protocol identifier wg', parseAether(document.querySelector('#aeLink').value).get('protocol') === 'wg');
  document.querySelector('#aePick [data-v="gool-classic"]').click();
}

check('i18n applied (fa)', document.querySelector('[data-i="users"]').textContent.length>2 && document.documentElement.dir==='rtl');
check('kv chip ok', document.querySelector('#chipKv').classList.contains('ok'));
check('clean-IP feed auto-add is visibly ON by default', document.querySelector('#rpAuto').classList.contains('p'));
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
document.querySelector('#swHealth').click();
fs.dispatchEvent(new window.Event('submit', { cancelable: true })); await sleep(300);
check('settings saved → brand title', document.querySelector('#brandTitle').textContent==='My Cat');
check('health-order control persists scanner ordering', (await (await window.fetch('/api/settings')).json()).settings.healthOrder === true);
// ip import
document.querySelector('#ipPaste').value='1.1.1.1#de, 2.2.2.2:2053#NL\n198.51.100.10\n[2606:4700::1]:8443#us example.com nope';
document.querySelector('#btnIpAppend').click(); await sleep(300);
check('WhiteDNS bare-IP export plus tagged/pinned scanner lines imported', document.querySelector('#ipCount').textContent==='5', document.querySelector('#ipCount').textContent);
const importedScan = (await (await window.fetch('/api/settings')).json()).settings;
check('plain WhiteDNS IPv4 plus tagged scanner ports/countries are preserved', importedScan.ips.includes('198.51.100.10') && importedScan.ips.includes('2.2.2.2:2053') && importedScan.ips.includes('[2606:4700::1]:8443') && importedScan.ipCountries['1.1.1.1']==='DE' && importedScan.ipCountries['2.2.2.2:2053']==='NL' && importedScan.ipCountries['[2606:4700::1]:8443']==='US', JSON.stringify({ips:importedScan.ips, countries:importedScan.ipCountries}));
await window.fetch('/api/ips', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ ips: ['[2606:4700::2]:8443#US'], source: 'scanner', pingMs: { '[2606:4700::2]:8443#US': 342 }, countries: { '[2606:4700::2]:8443': 'US' } }) });
document.querySelector('#ipPaste').value='[2606:4700::2]:8443#US';
document.querySelector('#btnIpAppend').click(); await sleep(300);
check('tagged scanner result displays its source and measured latency', document.querySelector('#ipList').textContent.includes('از اسکنر') && document.querySelector('#ipList').textContent.includes('342ms'));
// delete user — goes through the ask() modal now (no native confirm)
document.querySelector('[data-del]').click(); await sleep(100);
check('ask modal shown', document.querySelector('#ask').classList.contains('show'));
document.querySelector('#askYes').click(); await sleep(300);
check('user deleted', document.querySelectorAll('#rows tr').length===0);
// A failed write must show a useful, redacted API reason instead of the generic «error» toast.
failUserCreate = true;
document.querySelector('#btnAdd').click();
const fFail = document.querySelector('#fUser'); fFail.elements.uname.value='diagnostic-user'; fFail.elements.days.value='30';
fFail.dispatchEvent(new window.Event('submit', { cancelable: true })); await sleep(100);
const saveError = document.querySelector('#toast').textContent;
check('user-save error shows detail, content type + CF-Ray but redacts URLs + UUIDs', saveError.includes('HTTP 500') && saveError.includes('internal') && saveError.includes('diagnostic detail') && saveError.includes('application/json') && saveError.includes('diagnostic-user-create-ray') && !saveError.includes('example.test') && !saveError.includes('11111111-2222-4333-8444-555555555555'), saveError);
failUserCreate = false;
document.querySelector('[data-close]').click();
// A non-JSON 500 includes safe response metadata for triage, never its body.
failUserNonJson = true;
document.querySelector('#btnAdd').click();
const fNonJson = document.querySelector('#fUser'); fNonJson.elements.uname.value='non-json-diagnostic'; fNonJson.elements.days.value='30';
fNonJson.dispatchEvent(new window.Event('submit', { cancelable: true })); await sleep(100);
const nonJsonSaveError = document.querySelector('#toast').textContent;
check('non-JSON save error shows status, content type + CF-Ray but not body', nonJsonSaveError.includes('HTTP 500') && nonJsonSaveError.includes('text/html') && nonJsonSaveError.includes('diagnostic-user-non-json-ray') && nonJsonSaveError.includes('non-JSON response') && !nonJsonSaveError.includes('PRIVATE_INTERNAL_HTML') && !nonJsonSaveError.includes('example.test') && !nonJsonSaveError.includes('11111111-2222-4333-8444-555555555555'), nonJsonSaveError);
failUserNonJson = false;
document.querySelector('[data-close]').click();
// A 201 create followed by a failed GET is not a failed save; don't mislead the owner into retrying.
failUserList = true;
document.querySelector('#btnAdd').click();
const fRefresh = document.querySelector('#fUser'); fRefresh.elements.uname.value='saved-but-refresh-failed'; fRefresh.elements.days.value='30';
fRefresh.dispatchEvent(new window.Event('submit', { cancelable: true })); await sleep(200);
const refreshError = document.querySelector('#toast').textContent;
check('post-save refresh error says the user was saved', refreshError.includes('ذخیره شد') && refreshError.includes('HTTP 503'), refreshError);
failUserList = false;
const usersAfterRefreshError = await (await window.fetch('/api/users')).json();
check('user persists after refresh-only failure', usersAfterRefreshError.users.some(u => u.name === 'saved-but-refresh-failed'));
// settings export: real click → a download anchor appears (BPB-style backup)
document.querySelector('[data-view="settings"]').click();
document.querySelector('#btnSetExport').click(); await sleep(100);
check('settings export → download anchor', !!document.querySelector('a[download="cat-panel-settings.json"]'));

// settings import: real File → FileReader → merged PUT → UI re-renders
{
  const file = new window.File([JSON.stringify({ _cat: 'cat-panel-settings', v: 1, settings: { title: 'ImportedTitle' } })], 's.json', { type: 'application/json' });
  const inp = document.querySelector('#setImportFile');
  Object.defineProperty(inp, 'files', { value: [file] });
  inp.dispatchEvent(new window.Event('change', { bubbles: true }));
  await sleep(400);
  check('settings import → merged + rendered', document.querySelector('#brandTitle').textContent === 'ImportedTitle', document.querySelector('#brandTitle').textContent);
  check('import did not touch chain field', inp && document.querySelector('#fSettings').elements.chain !== undefined);
}

// about view: supported clients card (BPB parity)
document.querySelector('[data-view="about"]').click();
check('supported clients card', document.querySelectorAll('.clients a').length === 6 && document.querySelector('.clients').textContent.includes('v2rayNG'));
const rec1101 = document.querySelector('[data-i="rec_hint"]').textContent + ' ' + document.querySelector('[data-i="rec_steps"]').textContent;
check('1101 guidance points to runtime logs without calling it a ban or suggesting account changes', rec1101.includes('1101') && rec1101.includes('Logs') && rec1101.includes('stack trace') && !rec1101.includes('اکانت تازه') && !rec1101.includes('ساب‌دامینه را'));

// hamburger menu: burger opens the drawer, items are labeled, view switch closes it
document.querySelector('#btnBurger').click(); await sleep(80);
check('burger opens menu drawer', document.querySelector('#menu').classList.contains('show'));
check('menu has 10 labeled items', document.querySelectorAll('#menu .mlist > button').length === 10 && document.querySelector('#menu .mtx b').textContent.length > 2);
document.querySelector('#menu [data-view="dash"]').click(); await sleep(80);
check('menu item switches view + closes', document.querySelector('#v-dash').classList.contains('on') && !document.querySelector('#menu').classList.contains('show'));
check('no bottom nav in DOM', !document.querySelector('.nav'));

// 6.47.0 grouping: settings form split into 10 labeled topic cards (chain no
// longer sits next to the deploy block); health endpoint de-fingerprinted
document.querySelector('[data-view="settings"]').click();
check('settings = 10 topic cards', document.querySelectorAll('#fSettings > .card.sec').length === 10, document.querySelectorAll('#fSettings > .card.sec').length);
check('card titles rendered (fa)', document.querySelector('[data-i="g_chain"]').textContent.length > 3);
{
  const cards = Array.from(document.querySelectorAll('#fSettings > .card.sec'));
  const chainCard = cards.find(c => c.querySelector('[data-i="g_chain"]'));
  const ghCard = cards.find(c => c.querySelector('[data-i="g_gh"]'));
  check('chain card separate from deploy card', !!chainCard && !!ghCard && chainCard !== ghCard);
  const order = cards.findIndex(c => c.querySelector('input[name="chain"]'));
  const tg = cards.findIndex(c => c.querySelector('input[name="tgToken"]'));
  check('chain card comes before telegram card', order >= 0 && tg >= 0 && order < tg);
}

// 6.48.0: service-proof card (BPB parity) in the nodes view
document.querySelector('[data-view="nodes"]').click();
check('service-proof card present', !!document.querySelector('#btnAiTest') && document.querySelector('[data-i="ai_title"]').textContent.length > 2);

// 6.46.0 motion/iOS design: marquee strip, spring drawer CSS, press-scale CSS
check('dashboard marquee strip', !!document.querySelector('.marq .mi2') && document.querySelectorAll('.marq span').length === 2);
const css = document.querySelector('style') ? Array.from(document.querySelectorAll('style')).map(x => x.textContent).join('') : '';
check('iOS motion CSS present', css.includes('--spring') && css.includes('@keyframes marq') && css.includes('prefers-reduced-motion'));

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
// beta90 survival card: the real button reaches the owner-only API, applies
// the safe SNI=Host fallback set, and renders the emergency subscription URL.
{
  check('survival pack card + button are present', !!document.querySelector('#survCard #btnSurvival') && document.querySelector('#survCard [data-i="surv_title"]').textContent.length > 3);
  document.querySelector('#btnSurvival').click();
  await sleep(700);
  const st = (await (await window.fetch('/api/settings')).json()).settings;
  check('survival button applies fallback settings', st.subRotate === 'daily' && st.sniFront === false && st.sniRotate === false && st.plainEnabled && st.healthOrder === true && st.protocols.vless && st.protocols.trojan, JSON.stringify([st.subRotate, st.sniFront, st.sniRotate, st.plainEnabled, st.healthOrder, st.protocols]));
  const a = document.querySelector('#survOut a');
  check('survival button renders shareable ?survive=1 link', !!a && a.href.endsWith('?survive=1'), a && a.href);
  const surviveFormats = Array.from(document.querySelectorAll('#survOut [data-copy]')).map(b => b.getAttribute('data-copy'));
  check('survival has raw, base64, Clash, sing-box and Xray format links', surviveFormats.length === 5 && surviveFormats.some(u => u.includes('/clash/') && u.endsWith('?survive=1')) && surviveFormats.some(u => u.includes('/singbox/') && u.endsWith('?survive=1')) && surviveFormats.some(u => u.includes('/xray/') && u.endsWith('?survive=1')), surviveFormats.join(' | '));
}

// The builder must surface HTTP failures rather than mislabeling them «no clean IPs».
{
  document.querySelector('[data-view="build"]').click();
  failSubscription = true;
  document.querySelector('#bGen').click();
  await sleep(100);
  check('builder shows status, content type + CF-Ray when subscription request fails', document.querySelector('#bEmpty').textContent.includes('HTTP 502') && document.querySelector('#bEmpty').textContent.includes('text/plain') && document.querySelector('#bEmpty').textContent.includes('diagnostic-subscription-ray'), document.querySelector('#bEmpty').textContent);
  check('builder does not expose the raw response body', !document.querySelector('#bEmpty').textContent.includes('worker-private-body'));
  failSubscription = false;
  document.querySelector('#bGen').click();
  const generatedDeadline = Date.now() + 2000;
  while (Date.now() < generatedDeadline && !(Number(document.querySelector('#bCount').textContent) > 0 && document.querySelector('#bEmpty').style.display === 'none')) await sleep(50);
  const generatedCount = Number(document.querySelector('#bCount').textContent);
  const generatedPreview = document.querySelector('#bPrev').value;
  check('builder counts successful URI lines', generatedCount > 0 && generatedCount <= 24, String(generatedCount));
  check('builder preview contains generated configs', /^(vless|trojan):\/\//m.test(generatedPreview), generatedPreview.slice(0, 100));
  emptySubscription = true;
  document.querySelector('#bGen').click();
  const emptyDeadline = Date.now() + 2000;
  while (Date.now() < emptyDeadline && !(document.querySelector('#bCount').textContent === '0' && document.querySelector('#bEmpty').style.display !== 'none' && document.querySelector('#bEmpty').textContent.includes('HTTP 200'))) await sleep(50);
  check('builder distinguishes a successful but empty HTTP response', document.querySelector('#bCount').textContent === '0' && document.querySelector('#bEmpty').textContent.includes('HTTP 200'), JSON.stringify({ count: document.querySelector('#bCount').textContent, shown: document.querySelector('#bEmpty').style.display, text: document.querySelector('#bEmpty').textContent }));
  emptySubscription = false;
  const fwIds = ['mci-ech', 'mci-ipv6', 'mci-ipv6-ff', 'irancell-ff'];
  check('builder exposes four firewall presets and an editable entry IP', fwIds.every(id => !!document.querySelector('#bFw [data-v="' + id + '"]')) && !!document.querySelector('#bAddr'));
  const builderSettingsBeforeFw = (await (await window.fetch('/api/settings')).json()).settings;
  document.querySelector('#bFw [data-v="irancell-ff"]').click();
  document.querySelector('#bGen').click();
  await sleep(350);
  let fwLink = new URL(document.querySelector('#bLink').value);
  const fwXrayLink = new URL(document.querySelector('#bXrayLink').value);
  check('Builder links the Irancell profile, its suggested IP and Xray endpoint per-link', fwLink.searchParams.get('fw') === 'irancell-ff' && fwLink.searchParams.get('addr') === '188.114.97.6' && fwXrayLink.pathname.includes('/xray/') && fwXrayLink.searchParams.get('fw') === 'irancell-ff', fwLink.href + ' | ' + fwXrayLink.href);
  check('Builder shows Xray import and hides incompatible Cat Client/Hiddify buttons', document.querySelector('#bXrayWrap').style.display === 'block' && document.querySelector('#bCat').style.display === 'none' && document.querySelector('#bHid').style.display === 'none');
  const builderCustomIp = document.querySelector('#bAddr');
  builderCustomIp.value = '198.51.100.42';
  builderCustomIp.dispatchEvent(new window.Event('input', { bubbles: true }));
  document.querySelector('#bGen').click();
  await sleep(350);
  fwLink = new URL(document.querySelector('#bLink').value);
  check('Builder keeps the editable IP in the profile link', fwLink.searchParams.get('fw') === 'irancell-ff' && fwLink.searchParams.get('addr') === '198.51.100.42');
  document.querySelector('#bFw [data-v=""]').click();
  document.querySelector('#bGen').click();
  await sleep(350);
  const defaultBuilderLink = new URL(document.querySelector('#bLink').value);
  const builderSettingsAfterFw = (await (await window.fetch('/api/settings')).json()).settings;
  check('Builder clears profile-only state when reset to default', !defaultBuilderLink.searchParams.has('fw') && document.querySelector('#bXrayWrap').style.display === 'none' && document.querySelector('#bCat').style.display !== 'none' && document.querySelector('#bHid').style.display !== 'none');
  check('Builder firewall selection never persists into settings', builderSettingsBeforeFw.fingerprint === builderSettingsAfterFw.fingerprint && builderSettingsBeforeFw.echList === builderSettingsAfterFw.echList && builderSettingsBeforeFw.cipherSuites === builderSettingsAfterFw.cipherSuites);
}

// lang toggle triggers reload (location.reload not implemented in jsdom → ignore errors from that)
check('no JS errors overall', errors.filter(e=>!/reload/.test(e)).length===0, errors.join('\n'));
// login page + info page parse
const login = await (await worker.fetch(new Request(HOST + '/panel'), { CAT_KV: KV, UUID: env.UUID }, {})).text();
const d2 = new JSDOM(login, { runScripts:'dangerously' }); check('login page has form', !!d2.window.document.querySelector('#f'));
console.log(failures ? failures+' FAILED' : 'DOM PASSED'); process.exit(failures?1:0);
