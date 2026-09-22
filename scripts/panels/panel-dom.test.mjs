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
const ENV = { CF_IPS: '104.16.6.62,172.67.181.32', SNI_LIST: 'cdn.example.ir' };

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
check('five tabs render', document.querySelectorAll('nav.tabs button').length === 5);
check('home tab active by default', document.querySelector('[data-tab-panel="home"]').classList.contains('active'));
check('config table filled', document.querySelectorAll('#cfgTable tr').length >= 5);
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
check('scanner custom IPs prefilled', (document.querySelector('#scanCustom')?.value || '').includes('104.16.6.62'));

click(document.querySelector('#subFormats .chip[data-format="clash"]'));
check('format chip switches url', (document.querySelector('#subUrlText')?.textContent || '').includes('/clash'));

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
click(document.querySelector('#buildFromIps'));
await wait(50);
check('build-from-ips produced configs', (document.querySelector('#dnsCustomText')?.textContent || '').includes('vless://'));

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
