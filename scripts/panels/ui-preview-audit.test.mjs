/**
 * Click audit for the UI-redesign PREVIEW document (docs/ui-redesign/preview.html).
 *
 * It exists so the staged redesign never ships a dead button or a console error:
 * every button in the preview must be wired (data-demo / data-view / data-tab),
 * the bottom nav must switch all five sections, and the page must load clean.
 *
 * Run standalone:  node scripts/panels/ui-preview-audit.test.mjs
 */
import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { JSDOM, VirtualConsole } from 'jsdom';

const html = readFileSync(new URL('../../docs/ui-redesign/preview.html', import.meta.url), 'utf8');

const noise = [];
const vc = new VirtualConsole();
vc.on('jsdomError', (e) => noise.push('jsdomError: ' + e.message));
vc.on('error', (...a) => noise.push('console.error: ' + a.join(' ')));
vc.on('warn', (...a) => noise.push('console.warn: ' + a.join(' ')));

const dom = new JSDOM(html, { runScripts: 'dangerously', pretendToBeVisual: true, virtualConsole: vc, url: 'http://localhost/preview.html' });
const { window } = dom;
const doc = window.document;
const settle = (ms) => new Promise((r) => setTimeout(r, ms));
await settle(150);

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('  ✓ ' + name); } else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? ' → ' + extra : '')); } };

console.log('\nUI preview — click audit (jsdom)\n');

// 1) no external resources at all (system fonts, inline SVG, no library)
ok('no external stylesheet, font or script', !/<link\b|@import|fonts\.googleapis|src="https?:/i.test(html));
// ONE theme only: the same tokens, re-declared for the dark scheme — no theme picker.
ok('one theme, auto light/dark from the OS', /@media\s*\(prefers-color-scheme:\s*dark\)/.test(html) && !/data-theme\s*=|setAttribute\('data-theme'/.test(html));
ok('bottom nav has exactly 5 sections', doc.querySelectorAll('.nav button[data-view]').length === 5, doc.querySelectorAll('.nav button[data-view]').length);
ok('five views exist', doc.querySelectorAll('.view').length === 5, doc.querySelectorAll('.view').length);

// 2) every button is wired — the "no dead buttons" rule
const btns = [...doc.querySelectorAll('button')];
const unwired = btns.filter((b) => !b.hasAttribute('data-demo') && !b.hasAttribute('data-view') && !b.hasAttribute('data-tab'));
ok('no unwired button (' + btns.length + ' buttons)', unwired.length === 0, unwired.map((b) => b.textContent.trim().slice(0, 20)).join(' | '));

// 3) clicking every button produces no console noise and no exception
let thrown = 0;
for (const b of btns) { try { b.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); } catch (e) { thrown++; } }
await settle(1000);
ok('clicking every button throws nothing', thrown === 0, thrown);
ok('console stays clean', noise.length === 0, noise.join(' | '));
ok('buttons give feedback (toast shown)', doc.querySelectorAll('.toast').length > 0, doc.querySelectorAll('.toast').length);

// 4) nav actually switches sections
const navBtns = [...doc.querySelectorAll('.nav button[data-view]')];
let switched = 0;
for (const b of navBtns) {
  b.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const on = doc.querySelectorAll('.view.on');
  if (on.length === 1 && on[0].id === b.getAttribute('data-view')) switched++;
}
ok('all 5 nav items switch to their own section', switched === 5, switched);
ok('active nav item is marked for assistive tech', doc.querySelectorAll('.nav button[aria-current="page"]').length === 1);

// 5) accessibility basics
const unlabelled = [...doc.querySelectorAll('button')].filter((b) => !b.textContent.trim() && !b.getAttribute('aria-label'));
ok('every button has a name (text or aria-label)', unlabelled.length === 0, unlabelled.length);
ok('page is RTL Persian', doc.documentElement.getAttribute('dir') === 'rtl' && doc.documentElement.getAttribute('lang') === 'fa');
ok('code blocks scroll in their own container', doc.querySelectorAll('.scroll code').length > 0 && /overflow-x:\s*auto/.test(html));

// 6) touch targets are declared at >=44px
ok('primary buttons are >=44px tall', /\.btn\{[^}]*min-height:44px/.test(html.replace(/\s+/g, '')) || /min-height:44px/.test(html));
ok('nav items are >=44px tall', /\.navbutton\{[^}]*min-height:var\(--nav-h\)/.test(html.replace(/\s+/g, '')));

console.log('\n' + (fail === 0 ? `ui preview: ALL ${pass} PASSED` : `ui preview: ${pass} passed, ${fail} FAILED`) + '\n');
assert.equal(fail, 0, fail + ' preview checks failed');
