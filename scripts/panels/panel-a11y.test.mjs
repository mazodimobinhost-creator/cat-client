/**
 * Static accessibility checks on the READABLE panel source (catclient.worker.js).
 * No DOM, no layout engine: these are string/CSS checks. They prove the rules are present in
 * the source, not that a rendered page behaves; real rendering/TalkBack still needs a browser/device.
 * WORKER_SRC=<path> lets the same test run against another copy (used to show fail-before).
 * Run: node scripts/panels/panel-a11y.test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const src = readFileSync(process.env.WORKER_SRC || path.join(repo, 'app/src/main/assets/panels/catclient.worker.js'), 'utf8');
let failures = 0;
const check = (n, c, x) => { if (c) console.log('✓ ' + n); else { failures++; console.error('✗ ' + n + (x ? ' — ' + x : '')); } };
const px = (re) => { const m = re.exec(src); return m ? Number(m[1]) : NaN; };

// 1. touch targets (CSS hit area)
check('.ib is at least 44x44', px(/\n\.ib\{width:(\d+)px;height:\d+px;/) >= 44 && px(/\n\.ib\{width:\d+px;height:(\d+)px;/) >= 44);
check('.act .ib (table actions) is at least 44x44', px(/\.act \.ib\{width:(\d+)px;height:\d+px\}/) >= 44 && px(/\.act \.ib\{width:\d+px;height:(\d+)px\}/) >= 44);
check('mobile .ib is at least 44x44', px(/\n \.ib\{width:(\d+)px;height:\d+px\}/) >= 44);
check('mobile .btn min-height is at least 44', px(/\n \.btn\{min-height:(\d+)px\}/) >= 44);
check('.btn.sm min-height is at least 44', px(/\.btn\.sm\{min-height:(\d+)px/) >= 44);
check('.pick button min-height is at least 44', px(/\.pick button\{min-height:(\d+)px/) >= 44);
check('.ipl .chip (clickable) min-height is at least 44', px(/\.ipl \.chip\{min-height:(\d+)px/) >= 44);
check('inline "+" buttons in country chips are at least 44px', (src.match(/min-height:44px;min-width:44px;padding:1px 8px;margin:0">\+<\/button>/g) || []).length === 2);

// 2. accessible names on icon-only controls
const staticIb = [...src.matchAll(/<(?:button|a) class="ib[^"]*"[^>]*>/g)].map((m) => m[0]);
check('found the static icon buttons', staticIb.length >= 15, String(staticIb.length));
const unlabeled = staticIb.filter((t) => !/aria-label="[^"]+"/.test(t) || !/title="[^"]+"/.test(t));
check('every static icon button has aria-label AND title', unlabeled.length === 0, unlabeled.map((t) => t.slice(0, 70)).join(' | '));
for (const k of ['edit', 'renew', 'toggle', 'del', 'cc_link']) check(`dynamic icon button "${k}" has aria-label`, src.includes(`aria-label=\"'+t('${k}')+'\"`));
check('close buttons (menu + drawer) are labelled', /data-mclose data-al="al_close"[^>]*aria-label="Close"/.test(src) && /data-close data-al="al_close"[^>]*aria-label="Close"/.test(src));

// 3. translated labels exist in BOTH dictionaries, and the applier sets them
const keys = [...new Set([...src.matchAll(/data-al="(\w+)"/g)].map((m) => m[1]))];
check('data-al keys found', keys.length >= 10, String(keys.length));
const fa = src.indexOf("diag_title:'تشخیص (فقط مالک)'"); const en = src.indexOf("diag_title:'Diagnostics (owner only)'");
check('both dictionaries located', fa > 0 && en > 0);
const seg = (from) => src.slice(Math.max(0, from - 90000), from + 90000);
for (const k of keys) {
  const inFa = new RegExp('\\b' + k + ":'").test(seg(fa)); const inEn = new RegExp('\\b' + k + ":'").test(seg(en));
  check(`i18n key ${k} exists in fa and en`, inFa && inEn, `fa=${inFa} en=${inEn}`);
}
check('i18n applier sets aria-label + title from data-al', /\[data-al\]'\)\.forEach\(function\(el\)\{var s=t\(el\.getAttribute\('data-al'\)\);el\.setAttribute\('aria-label',s\);el\.setAttribute\('title',s\)\}\)/.test(src));

// 4. operational font sizes
const small = src.split('\n').map((l, i) => [i + 1, l]).filter(([, l]) => /font-size:\s*(9|10|11)px/.test(l) && !/\.marq|\.ar\{|\.brand \.v/.test(l));
check('no 9-11px operational text (marquee, arrow glyph, version badge excepted)', small.length === 0, small.map(([i]) => i).join(','));

// 5. RTL: no uppercase / letter-spacing for Persian
check('RTL override removes text-transform + letter-spacing', /html\[dir="rtl"\] label,[^{]*\{text-transform:none;letter-spacing:normal\}/.test(src));

// 6. drawer dialog semantics + Escape
check('drawer panel has role=dialog + aria-modal + label', /class="pn frm" role="dialog" aria-modal="true" aria-labelledby="dTitle"/.test(src));
check('Escape closes the drawer as well as the menu', /e\.key==='Escape'\)\{menuSet\(false\);var dr=\$\('#drawer'\);if\(dr&&dr\.classList\.contains\('show'\)\)closeDrawer\(\)\}/.test(src));

console.log(failures === 0 ? '\nPANEL A11Y PASSED' : '\n' + failures + ' PANEL A11Y CHECK(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
