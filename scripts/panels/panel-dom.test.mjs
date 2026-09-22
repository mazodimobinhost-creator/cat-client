/**
 * Cat Panel DOM test — loads the panel HTML in jsdom, runs the embedded client
 * script and asserts the UI actually renders and reacts.
 *
 * Optional dev tool (needs `npm i jsdom`); the dependency-free suite is
 * scripts/panels/cat-panel.test.mjs. If jsdom is missing this script skips.
 *
 * Usage: node scripts/panels/panel-dom.test.mjs
 */
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const workerPath = path.join(here, '../../app/src/main/assets/panels/catclient.worker.js');

let JSDOM;
let VirtualConsole;
try {
  ({ JSDOM, VirtualConsole } = await import('jsdom'));
} catch (e) {
  console.log('SKIP: jsdom is not installed (npm i jsdom).');
  process.exit(0);
}

const mod = await import(workerPath);
const worker = mod.default;
const HOST = 'catpanel-demo.workers.dev';
const ENV = {
  OPEN_PANEL: 'true', // the panel is locked by default in v5; DOM tests look at the unlocked shell
 CF_IPS: '104.16.6.62,172.67.181.32', SNI_LIST: 'cdn.example.ir' };

const html = await (await worker.fetch(new Request('https://' + HOST + '/', { headers: { Host: HOST } }), ENV)).text();

const errors = [];
const virtualConsole = new VirtualConsole();
virtualConsole.on('jsdomError', (e) => errors.push('jsdomError: ' + e.message));
virtualConsole.on('error', (...args) => errors.push('console.error: ' + args.join(' ')));

const pings = [];
const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  url: 'https://' + HOST + '/',
  pretendToBeVisual: true,
  virtualConsole,
  beforeParse(window) {
    window.localStorage.clear();
    window.confirm = () => false;
    window.open = () => null;
    window.fetch = async (url) => {
      const target = String(url);
      pings.push(target);
      await new Promise((r) => setTimeout(r, 1));
      return new Response('fl=1\ncolo=FRA\n', { status: 200 });
    };
  },
});

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
await wait(200);
const { document } = dom.window;
const win = dom.window;

let failures = 0;
const check = (name, cond, extra) => {
  const ok = !!cond;
  if (!ok) failures++;
  console.log((ok ? '✓ ' : '✗ ') + name + (!ok && extra ? ' — ' + extra : ''));
};
const click = (el) => el.dispatchEvent(new win.MouseEvent('click', { bubbles: true }));

const realErrors = errors.filter((e) => !e.includes('scrollTo'));
check('no script errors', realErrors.length === 0, realErrors.join(' | '));
check('state injected', win.CAT_STATE?.host === HOST);
check('seven tabs render', document.querySelectorAll('nav.tabs button').length === 7, String(document.querySelectorAll('nav.tabs button').length));
check('users tab exists', !!document.querySelector('[data-tab-panel="users"]'));
check('tools tab exists', !!document.querySelector('[data-tab-panel="tools"]'));
check('home tab active by default', document.querySelector('[data-tab-panel="home"]').classList.contains('active'));
check('config table filled', document.querySelectorAll('#cfgTable tr').length >= 5);
check('IR clean-IP library listed', (document.querySelector('#irIpsOut')?.textContent || '').includes('104.16.0.1'));
check('tools expose KV state', !!document.querySelector('#tSave') && !!document.querySelector('#selfTable'));
const allLinks = document.querySelector('#cfgAllText')?.textContent || '';
check('links include vless/trojan/warp', allLinks.includes('vless://') && allLinks.includes('trojan://') && allLinks.includes('warp://'));
check('clean-IP variants built', allLinks.includes('104.16.6.62'));
const dnsRows = document.querySelectorAll('#dnsTable tr').length;
check('dns table filled', dnsRows >= 6, 'rows=' + dnsRows);
check('dns presets include new resolvers', (document.querySelector('#dnsTable')?.textContent || '').includes('dns.mullvad.net'));
check('custom DoH/DoT inputs render', !!document.querySelector('#dohCustom') && !!document.querySelector('#dotCustom'));
check('DoT presets render', document.querySelectorAll('#dotTable tr').length >= 6 || document.body.innerHTML.includes('one.one.one.one'));
check('theme picker renders options', document.querySelectorAll('[data-theme-pick]').length === 5);
check('single-config builder renders', !!document.querySelector('#singleBuild') && !!document.querySelector('#singleAddr'));
check('single config builds a vless link', (() => {
  document.querySelector('#singleAddr').value = '104.16.6.62';
  document.querySelector('#singleName').value = 'Cat Single';
  click(document.querySelector('#singleBuild'));
  const out = document.querySelector('#singleOut').textContent;
  return out.startsWith('vless://') && out.includes('104.16.6.62') && out.includes(HOST);
})(), (document.querySelector('#singleOut') || {}).textContent);
check('single config add link points at the app', (document.querySelector('#singleAdd')?.getAttribute('href') || '').startsWith('catclient://add-sub?url='));
check('theme switch toggles the body attribute', (() => {
  click(document.querySelector('[data-theme-pick="mono"]'));
  return win.document.documentElement.getAttribute('data-theme') === 'mono';
})());
check('sub + doh urls shown', (document.querySelector('#subUrlText')?.textContent || '').includes('/sub') && (document.querySelector('#dohUrlText')?.textContent || '').includes('/dns-query'));
check('deep link present', document.body.innerHTML.includes('catclient://add-sub?url='));
check('scan targets embedded', (win.CAT_STATE?.scanTargets || []).length > 20);
check('scanner sni prefilled with panel host', document.querySelector('#scanSni')?.value === HOST);
check('config builder lists CF_IPS as addresses', (document.querySelector('#cfgAddresses')?.value || '').includes('104.16.6.62'));
check('sub url carries the uuid', (document.querySelector('#subUrlText')?.textContent || '').includes('/sub/' + win.CAT_STATE.uuid));
check('config table has address × port rows', document.querySelectorAll('#cfgTable tr').length >= 6);

click(document.querySelector('#subFormats .chip[data-fmt="/clash"]'));
check('format chip switches url', (document.querySelector('#subUrlText')?.textContent || '').includes('/clash'));

// --- BPB-style builder: pick ports + protocol, apply, url + table update ---
check('BPB default ports preselected (80,443,2053,8443,8080)', ['80', '443', '2053', '8443', '8080'].every((p) => document.querySelector('#cfgPorts .chip[data-port="' + p + '"]')?.classList.contains('active')));
click(document.querySelector('#cfgPorts .chip[data-port="8443"]')); // off
click(document.querySelector('#cfgPorts .chip[data-port="8080"]')); // off
click(document.querySelector('#cfgPorts .chip[data-port="2083"]')); // on
click(document.querySelector('#cfgProtos .chip[data-proto="trojan"]')); // turn trojan off
document.querySelector('#cfgSni').value = 'cdn.example.ir';
click(document.querySelector('#cfgApply'));
await wait(20);
const cfgUrl = document.querySelector('#cfgSubUrl')?.textContent || '';
check('apply embeds ports in the sub url', cfgUrl.includes('ports=443,2053,2083,80'), cfgUrl);
check('apply embeds protocol filter', cfgUrl.includes('proto=vless'), cfgUrl);
check('apply embeds custom sni', cfgUrl.includes('sni=cdn.example.ir'), cfgUrl);
const allText = document.querySelector('#cfgAllText')?.textContent || '';
check('table drops trojan after toggle', !allText.includes('trojan://'));
check('table has plain-http port-80 config', allText.includes(':80?encryption=none&security=none'));
check('table has tls port-2053 config', allText.includes(':2053?encryption=none&security=tls'));
const embeddedSub = await (await worker.fetch(new Request(cfgUrl.replace('/clash', ''), { headers: { Host: HOST } }), ENV)).text();
const decodedSub = Buffer.from(embeddedSub, 'base64').toString('utf8');
check('worker honours the embedded options', decodedSub.includes('@104.16.6.62:2053') && decodedSub.includes('sni=cdn.example.ir') && !decodedSub.includes('trojan://'));

click(document.querySelector('#langBtn'));
check('language toggles to EN', document.body.getAttribute('data-lang') === 'en');
check('nav labels translated', document.querySelector('[data-nav-label="home"]').textContent === 'Home');
click(document.querySelector('#langBtn'));
check('language toggles back to FA', document.body.getAttribute('data-lang') === 'fa');

click(document.querySelector('nav.tabs button[data-tab="scanner"]'));
check('scanner tab activates', document.querySelector('[data-tab-panel="scanner"]').classList.contains('active'));

click(document.querySelector('[data-qr-target="subUrlText"]'));
check('QR modal opens with svg url', (document.querySelector('#qrImg')?.getAttribute('src') || '').includes('/qr.svg?d='));

// --- run a scan against the stubbed fetch and use the results ---
document.querySelector('#scanLimit').value = '8';
click(document.querySelector('#scanStart'));
await wait(600);
const scanRows = document.querySelectorAll('#scanTable tr').length;
check('scan produced result rows', scanRows >= 1, 'rows=' + scanRows);
check('scan probed the CDN trace endpoint', pings.some((u) => u.includes('/cdn-cgi/trace')), pings.slice(0, 2).join(','));
check('scan status reports completion', (document.querySelector('#scanStatus')?.textContent || '').length > 0);
document.querySelectorAll('#scanTable [data-ip-check]').forEach((cb) => { cb.checked = true; cb.dispatchEvent(new win.Event('change', { bubbles: true })); });
click(document.querySelector('#useIpsInConfigs'));
await wait(50);
check('scan → configs moves ips into the builder', document.querySelector('[data-tab-panel="configs"]').classList.contains('active') && (document.querySelector('#cfgAddresses')?.value || '').split('\n').length >= 2);
check('scan → configs rebuilt the sub url', (document.querySelector('#cfgSubUrl')?.textContent || '').includes('ips='));

// --- QR endpoint: the served SVG must contain exactly the encoder's dark modules ---
const payload = 'https://' + HOST + '/sub';
const svg = await (await worker.fetch(new Request('https://' + HOST + '/qr.svg?d=' + encodeURIComponent(payload)), ENV)).text();
const countSvg = ((svg.match(/<path d="([^"]*)"/) || [])[1]?.match(/M/g) || []).length;
const encoded = mod._testing.qrEncode(payload, 'M');
const countLocal = encoded.modules.reduce((n, row) => n + row.filter(Boolean).length, 0);
check('qr svg served', svg.startsWith('<svg') && countSvg > 0);
check('qr svg module count matches the encoder', countLocal === countSvg, `${countLocal} encoder vs ${countSvg} svg`);
try {
  const { default: QRCode } = await import('qrcode');
  const ref = QRCode.create([{ data: payload, mode: 'byte' }], { errorCorrectionLevel: 'M' });
  const countRef = ref.modules.data.reduce((n, v) => n + (v ? 1 : 0), 0);
  check('qr svg module count matches the reference library', countRef === countSvg, `${countRef} ref vs ${countSvg} svg`);
} catch (e) {
  console.log('· skipped cross-library QR comparison (npm i qrcode)');
}

console.log(failures === 0 ? '\nPANEL DOM TESTS PASSED' : `\n${failures} DOM TEST(S) FAILED`);
process.exit(failures ? 1 : 0);
