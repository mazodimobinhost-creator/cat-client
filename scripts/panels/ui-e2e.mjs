// ui-e2e.mjs — «همه‌چی اتومات»: یک مرورگرِ واقعی (Chromium) پنل را باز می‌کند،
// لاگین می‌کند، وریفای می‌کند، و بعد کدِ پنل را از داخل همان مرورگر صدا می‌زند.
//
// چه چیزی این تست هست و چه چیزی نیست (برچسبِ صادقانه):
//   * رانتایم:  workerd محلی — همان موتورِ Cloudflare، روی همین ماشین، بدونِ حساب.
//   * مرورگر:   Chromium واقعی (Playwright) — نه jsdom، نه شبیه‌سازِ دستی.
//   * برچسب:    «workerd محلی» — نه «کلودفلر واقعی». برای آن، تستِ لبه لازم است.
//
// Usage: BASE=http://127.0.0.1:8799 PANEL_UUID=… node scripts/panels/ui-e2e.mjs
import { chromium } from 'playwright';
import { writeFileSync } from 'node:fs';

const BASE = (process.env.BASE || 'http://127.0.0.1:8799').replace(/\/$/, '');
const UUID = process.env.PANEL_UUID || '11111111-2222-3333-4444-555555555555';
const OUT = process.env.OUT || 'ui-e2e-transcript.txt';
const SHOT = process.env.SHOT || 'ui-e2e-dashboard.png';

const lines = [];
let failed = 0;
const say = (s = '') => { lines.push(s); console.log(s); };
const check = (name, ok, detail = '') => {
  if (!ok) failed++;
  say(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? '  — ' + detail : ''}`);
  return ok;
};

const browser = await chromium.launch({ args: ['--no-sandbox'] });
const ctx = await browser.newContext();
const page = await ctx.newPage();

const consoleErrors = [];
const sameOriginFailures = [];
const externalFailures = [];
const httpFailures = [];      // same-origin non-2xx the page hit, with the status
const expectedHttp = new Set(); // …the ones this test asked for on purpose
page.on('console', (m) => {
  if (m.type() !== 'error') return;
  const t = m.text();
  // A failed fetch logs "Failed to load resource" — that is the same event the
  // response listener records with its status code, so counting it twice would
  // double-penalise the deliberate 401 probes. Real JS/SVG/parse errors stay.
  if (/^Failed to load resource/.test(t)) return;
  consoleErrors.push(t.slice(0, 300));
});
page.on('requestfailed', (r) => {
  const url = r.url();
  const rec = `${r.method()} ${url} — ${r.failure()?.errorText || 'failed'}`;
  (url.startsWith(BASE) ? sameOriginFailures : externalFailures).push(rec);
});
page.on('response', (r) => {
  if (!r.url().startsWith(BASE)) return;
  if (r.status() < 400) return;
  const path = r.url().slice(BASE.length) || '/';
  httpFailures.push(`${r.status()} ${path}`);
});

// Full bodies, not a 400-byte window: the host name and the sub link live further
// down the payload, and a truncated body made two checks lie in the first run.
const api = (path, init, expectStatus) => {
  if (expectStatus) expectedHttp.add(`${expectStatus} ${path}`);
  return page.evaluate(
    async ([p, i]) => {
      const r = await fetch(p, i || undefined);
      return { status: r.status, type: r.headers.get('content-type') || '', body: (await r.text()).slice(0, 20_000) };
    }, [path, init || null]);
};

say(`# UI end-to-end — ${new Date().toISOString()}`);
say(`# base: ${BASE}`);
say(`# browser: ${browser.version()}`);
say('');

// ---- 1) تازه‌وارد: / باید به صفحهٔ ورود برود و API باید بسته باشد ----
await page.goto(BASE + '/', { waitUntil: 'load', timeout: 45_000 });
check('ورودِ تازه‌وارد به / به صفحهٔ ورود می‌رسد', /\/panel\b/.test(page.url()), page.url().replace(BASE, ''));
check('فرمِ ورود دیده می‌شود (input#p)', await page.locator('#p').isVisible().catch(() => false));
// The browser always asks for /favicon.ico; the panel ships no icon, so a 404 here
// is expected behaviour rather than a product failure — noted, not hidden.
expectedHttp.add('404 /favicon.ico');
const anon = await api('/api/settings', null, 401);
check('API برای ناشناس بسته است', anon.status === 401, `HTTP ${anon.status}`);
const anonDiag = await api('/api/diag', null, 401);
check('/api/diag برای ناشناس بسته است', anonDiag.status === 401, `HTTP ${anonDiag.status}`);

// ---- 2) لاگینِ واقعی از داخلِ فرم (کلیک، نه فراخوانیِ مستقیمِ API) ----
await page.fill('#p', UUID);
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load', timeout: 45_000 }),
  page.click('button[type=submit]'),
]);
const afterLogin = await page.content();
check('لاگین از فرم رد شد (فرمِ ورود رفته)', !afterLogin.includes('id="p"'));
check('پنلِ واقعی رندر شد (ناوبری + بخش‌ها)', afterLogin.includes('id="sideNav"') && (afterLogin.match(/data-sec="/g) || []).length >= 8,
  `${(afterLogin.match(/data-sec="/g) || []).length} بخش`);
check('نسخهٔ پنل در صفحه دیده می‌شود', /6\.67\.0/.test(afterLogin), (afterLogin.match(/6\.67\.\d+/g) || [])[0] || 'پیدا نشد');

// ---- 3) وریفای از داخلِ همان نشستِ مرورگر ----
const settings = await api('/api/settings');
check('/api/settings با نشست: 200 و ok:true', settings.status === 200 && /"ok":true/.test(settings.body), `HTTP ${settings.status}`);
let reportedHost = '';
try { reportedHost = JSON.parse(settings.body).host || ''; } catch { /* leave empty */ }
const browserHost = new URL(BASE).host;
check('هاستِ گزارش‌شده همان هاستِ مرورگر است', reportedHost === browserHost,
  `گزارش‌شده: ${reportedHost || '(خالی)'} / مرورگر: ${browserHost}`);
const diag = await api('/api/diag');
check('/api/diag با نشست: 200 و رانتایم را می‌گوید', diag.status === 200 && /"cf":true/.test(diag.body), `HTTP ${diag.status}`);
check('/api/diag هشدارِ KVِ نبسته را می‌دهد (این دمو KV ندارد)', /NOT BOUND/i.test(diag.body));
const backup = await api('/api/backup');
check('/api/backup: 200 و رازها ماسک‌شده', backup.status === 200 && /"secrets":"masked"/.test(backup.body));
const sub = await api('/sub/' + UUID);
const browserName = new URL(BASE).hostname;
const subHosts = [...sub.body.matchAll(/[?&]host=([^&\s]+)/g)].map((m) => decodeURIComponent(m[1]));
check('لینکِ ساب ساخته می‌شود و از همین هاست بیرون می‌آید',
  sub.status === 200 && sub.body.startsWith('vless://') && subHosts.some((h) => h.split(':')[0] === browserName),
  `${sub.body.slice(0, 32)}… hosts=${[...new Set(subHosts)].join(',') || 'none'}`);

// ---- 4) یک «دستگاهِ دیگر»: نشستِ تازه نباید چیزی ببیند ----
const ctx2 = await browser.newContext();
const page2 = await ctx2.newPage();
await page2.goto(BASE + '/panel', { waitUntil: 'load', timeout: 45_000 });
const other = await page2.evaluate(async () => { const r = await fetch('/api/settings'); return r.status; });
expectedHttp.add('401 /api/settings');
check('نشستِ تازه (دستگاهِ دیگر) هنوز بسته است', other === 401, `HTTP ${other}`);
await ctx2.close();

// ---- 5) شواهد ----
await page.screenshot({ path: SHOT, fullPage: false });
say('');
say(`# کنسول: ${consoleErrors.length} خطای واقعی (JS/SVG)، بدونِ «Failed to load resource»`);
for (const e of consoleErrors.slice(0, 8)) say(`    ${e}`);
const unexpectedHttp = httpFailures.filter((f) => !expectedHttp.has(f));
say(`# پاسخ‌های 4xx/5xx هم‌مبدأ: ${httpFailures.length} (انتظاری: ${httpFailures.filter((f) => expectedHttp.has(f)).length})`);
for (const f of httpFailures) say(`    ${expectedHttp.has(f) ? '✓ عمدی' : '✗ ناخواسته'}  ${f}`);
for (const f of unexpectedHttp) say(`# ناخواسته: ${f}`);
say(`# درخواست‌های شکست‌خوردهٔ same-origin: ${sameOriginFailures.length}`);
for (const e of sameOriginFailures.slice(0, 8)) say(`    ${e}`);
say(`# درخواست‌های شکست‌خوردهٔ بیرونی (طبیعی روی رانر؛ فقط گزارش): ${externalFailures.length}`);
for (const e of externalFailures.slice(0, 5)) say(`    ${e}`);
check('هیچ خطای کنسولی (JS/SVG) در مسیرِ لاگین/وریفای نبود', consoleErrors.length === 0, `${consoleErrors.length}`);
check('هیچ پاسخِ 4xx/5xxِ ناخواسته‌ای نبود', unexpectedHttp.length === 0, unexpectedHttp.join(' | ') || 'هیچ');
check('هیچ درخواستِ شکست‌خوردهٔ same-origin نبود', sameOriginFailures.length === 0, `${sameOriginFailures.length}`);
say(`# screenshot: ${SHOT}`);

await browser.close();
say('');
say(failed === 0 ? '# ALL CHECKS PASSED' : `# ${failed} CHECK(S) FAILED`);
writeFileSync(OUT, lines.join('\n') + '\n');
process.exit(failed === 0 ? 0 : 1);
