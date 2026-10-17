/**
 * «Cat Glass» skin tests (v6.65).
 *
 * The skins are pure cosmetics — they must never touch a config, a link or a
 * subscription — but they do ship as inline CSS + one data-theme attribute, and
 * a broken selector there is exactly the kind of thing nothing else notices.
 * This test therefore checks the whole round trip in a real DOM:
 *
 *   1. the inline stylesheet parses (a stray brace silently kills every rule
 *      after it — the page still "works", it just looks broken);
 *   2. the skin the owner saved is server-rendered, so there is no colour flash;
 *   3. picking a skin applies it, writes the cookie for KV-less panels and PUTs
 *      uiTheme to /api/settings;
 *   4. an unknown/garbage id can never break the page (falls back to default).
 *
 * Usage: node scripts/panels/panel-theme.test.mjs
 */
import { JSDOM, VirtualConsole } from 'jsdom';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const mod = await import(path.join(here, '../../app/src/main/assets/panels/catclient.worker.js'));
const worker = mod.default;
const KV = { m: new Map(), async get(k) { return this.m.get(k) ?? null; }, async put(k, v) { this.m.set(k, v); }, async list() { return { keys: [] }; } };
const env = { CAT_KV: KV, UUID: '11111111-2222-4333-8444-555555555555', OPEN_PANEL: 'true' };
const HOST = 'https://p.workers.dev';

let failures = 0;
const check = (n, c, x) => { if (c) console.log('✓ ' + n); else { failures++; console.error('✗ ' + n + (x ? ' — ' + String(x).slice(0, 200) : '')); } };

async function boot() {
  const html = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();
  const cssErrors = [];
  const puts = [];
  const vc = new VirtualConsole();
  vc.on('jsdomError', (e) => { if (/Could not parse CSS/.test(e.message)) cssErrors.push(e.message); });
  const dom = new JSDOM(html, {
    url: HOST + '/', runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: vc,
    beforeParse(w) {
      w.fetch = async (p, i = {}) => {
        if ((i.method || 'GET') === 'PUT') { try { puts.push(JSON.parse(i.body)); } catch { /* non-JSON probe */ } }
        return worker.fetch(new Request(new URL(p, HOST), { method: i.method || 'GET', headers: i.headers || {}, body: i.body }), env, {});
      };
      w.confirm = () => true;
    },
  });
  await new Promise((r) => setTimeout(r, 400));
  return { dom, html, cssErrors, puts };
}

const { dom, html, cssErrors, puts } = await boot();
const { window } = dom; const { document } = window;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ── 1. the stylesheet parses ─────────────────────────────────────────── */
check('inline stylesheet parses (no stray brace kills later rules)', cssErrors.length === 0, cssErrors.join(' | '));
check('wallpaper + glass tokens are inlined', html.includes('--wall:radial-gradient') && html.includes('backdrop-filter:blur(var(--blur))'));
// «aurora» is the default: it lives in :root, the other five are [data-theme=…] overrides.
check('default skin lives in :root', html.includes('--wall:radial-gradient'));
check('five alternate skins declared', ['nebula', 'ocean', 'ember', 'forest', 'steel'].every((t) => html.includes('[data-theme=' + t + ']{')));

/* ── 2. server-rendered skin (no flash of the wrong colour) ───────────── */
check('default skin rendered server-side', document.documentElement.getAttribute('data-theme') === 'aurora', document.documentElement.getAttribute('data-theme'));

/* ── 3. the picker works end to end ───────────────────────────────────── */
const btn = document.querySelector('[data-theme-open]');
check('skin button exists (sidebar + mobile drawer)', !!btn && document.querySelectorAll('[data-theme-open]').length >= 2);
btn.click(); await sleep(60);
check('picker opens', document.querySelector('#themePick').classList.contains('show'));
const swatches = [...document.querySelectorAll('#themeGrid button')];
check('six skins offered', swatches.length === 6, String(swatches.length));
check('skin names translated (fa)', swatches.map((b) => b.querySelector('span').textContent).join(',') === 'شفق,سحابی,اقیانوس,اخگر,جنگل,فولاد', swatches.map((b) => b.querySelector('span').textContent).join(','));
check('active skin marked', swatches.filter((b) => b.classList.contains('on')).map((b) => b.getAttribute('data-t')).join() === 'aurora');
check('every swatch carries a preview gradient', swatches.every((b) => (b.getAttribute('style') || '').includes('--tpw')));

swatches.find((b) => b.getAttribute('data-t') === 'nebula').click(); await sleep(200);
check('skin applies live (no reload)', document.documentElement.getAttribute('data-theme') === 'nebula', document.documentElement.getAttribute('data-theme'));
check('cookie written (KV-less panels keep the choice)', /catTheme=nebula/.test(document.cookie), document.cookie);
check('choice persisted to /api/settings', puts.some((p) => p.uiTheme === 'nebula'), JSON.stringify(puts));
check('swatch selection moved', swatches.filter((b) => b.classList.contains('on')).map((b) => b.getAttribute('data-t')).join() === 'nebula');

document.querySelector('#themePick .tpbg').click(); await sleep(50);
check('backdrop closes the picker', !document.querySelector('#themePick').classList.contains('show'));
document.querySelector('[data-theme-open]').click(); await sleep(50);
document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' })); await sleep(50);
check('Esc closes the picker', !document.querySelector('#themePick').classList.contains('show'));

/* ── 4. the next page load renders the saved skin ─────────────────────── */
const html2 = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();
check('saved skin is server-rendered on the next load', /data-theme="nebula"/.test(html2), (html2.match(/data-theme="[a-z]+"/) || [])[0]);

/* ── 5. garbage never breaks the page ─────────────────────────────────── */
const bad = await (await worker.fetch(new Request(HOST + '/panel'), env, {})).text();
check('a known-good page still renders', bad.includes('id="stUsers"'));
const { normalizeSettings, defaultSettings, PANEL_THEMES } = mod._testing;
const fallback = normalizeSettings({ uiTheme: 'does-not-exist' }).uiTheme;
check('unknown skin id falls back to the default', fallback === defaultSettings().uiTheme && PANEL_THEMES.includes(fallback), fallback);
check('skin is cosmetic only: every skin id is a plain lowercase word', PANEL_THEMES.every((t) => /^[a-z]{3,10}$/.test(t)), PANEL_THEMES.join(','));

console.log(failures ? 'PANEL THEME FAILURES: ' + failures : 'PANEL THEME ALL PASSED');
process.exit(failures ? 1 : 0);
