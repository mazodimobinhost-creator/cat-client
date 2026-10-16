/**
 * Contrast of the REAL Android palette (DashboardViews.kt Light/Dark + every AppAccent
 * preset after themed() tinting). Parses the Kotlin source, so it cannot drift from it.
 * res/values/colors.xml entries such as text_muted / ping_* are NOT referenced anywhere in
 * the app source (verified by grep), so they are not used as the evidence here.
 * Static colour maths only: it says nothing about rendered screens or TalkBack.
 * Run: node scripts/panels/android-contrast.test.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const dir = path.join(repo, 'app/src/main/java/com/cat/client/');
const dash = readFileSync(dir + 'DashboardViews.kt', 'utf8');
const accent = readFileSync(dir + 'AppAccent.kt', 'utf8');
let failures = 0;
const check = (n, c, x) => { if (c) console.log('✓ ' + n); else { failures++; console.error('✗ ' + n + (x ? ' — ' + x : '')); } };

const hex = (s) => parseInt(s, 16) >>> 0;
const rgb = (c) => [(c >> 16) & 255, (c >> 8) & 255, c & 255];
const blend = (a, b, r) => { const A = rgb(a), B = rgb(b); const o = A.map((v, i) => Math.trunc(v * (1 - r) + B[i] * r)); return (o[0] << 16) | (o[1] << 8) | o[2]; };
const lum = (c) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; const [r, g, b] = rgb(c); return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
const ratio = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

function block(name) {
  const m = new RegExp('private val ' + name + ' = CatClientPalette\\(([\\s\\S]*?)\\n    \\)').exec(dash);
  const o = {}; for (const mm of m[1].matchAll(/(\w+) = 0xFF([0-9A-Fa-f]{6})\.toInt\(\)/g)) o[mm[1]] = hex(mm[2]); return o;
}
const presets = [...accent.matchAll(/^\s{4}(\w+)\("(\w+)", R\.string\.\w+, ((?:0xFF[0-9A-Fa-f]{6}\.toInt\(\), ){8})([0-9.]+)f\)/gm)].map((m) => {
  const c = [...m[3].matchAll(/0xFF([0-9A-Fa-f]{6})/g)].map((x) => hex(x[1]));
  return { name: m[1], dark: c[0], onDark: c[1], light: c[2], onLight: c[3], secDark: c[4], secLight: c[5], tintDark: c[6], tintLight: c[7], k: Number(m[4]) };
});
check('parsed Light + Dark palettes and 8 presets', presets.length === 8 && block('Light').textTertiary && block('Dark').textTertiary, 'presets=' + presets.length);

function themed(base, p, night) {
  const tint = night ? p.tintDark : p.tintLight; const k = p.k; const mix = (c, a = k) => blend(c, tint, a);
  return { ...base, background: mix(base.background), surface: mix(base.surface), surfaceElevated1: mix(base.surfaceElevated1), surfaceElevated2: mix(base.surfaceElevated2), surfaceVariant: mix(base.surfaceVariant), textSecondary: mix(base.textSecondary, k * 0.5), textTertiary: mix(base.textTertiary, k * 0.5), teal: night ? p.dark : p.light };
}
const TEXT = ['textPrimary', 'textSecondary', 'textTertiary'];
const SURF = ['background', 'surface', 'surfaceElevated1', 'surfaceElevated2', 'surfaceVariant'];
const cases = [['Light', false], ['Dark', true]];
for (const [nm, night] of cases) {
  const base = block(nm);
  const variants = [['base', base], ...presets.map((p) => [p.name, themed(base, p, night)])];
  for (const [vn, pal] of variants) {
    for (const t of TEXT) {
      const worst = Math.min(...SURF.map((s) => ratio(pal[t], pal[s])));
      check(`${nm}/${vn}: ${t} >= 4.5:1 on every surface`, worst >= 4.5, worst.toFixed(2));
    }
  }
}
console.log(failures === 0 ? '\nANDROID CONTRAST PASSED' : '\n' + failures + ' CONTRAST CHECK(S) FAILED');
process.exit(failures === 0 ? 0 : 1);
