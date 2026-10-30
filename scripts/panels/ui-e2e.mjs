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
import { writeFileSync, readFileSync } from 'node:fs';

const BASE = (process.env.BASE || 'http://127.0.0.1:8799').replace(/\/$/, '');
const UUID = process.env.PANEL_UUID || '11111111-2222-3333-4444-555555555555';
const PANEL_USER = process.env.PANEL_USER || 'admin';
const PANEL_PASSWORD = process.env.PANEL_PASSWORD || 'admin';
const OUT = process.env.OUT || 'ui-e2e-transcript.txt';
const SHOT = process.env.SHOT || 'ui-e2e-dashboard.png';

// The panel version is not a constant of this test — it lives in the artifact
// workerd is serving (scripts/panels/workerd-local.capnp embeds
// dist-panel/catpanel.obf.js). Hardcoding it here meant every panel patch that
// bumped the version turned this check red and blocked the release build, while
// claiming «the version is not visible in the page». Read it from the same file
// the browser is about to receive; an empty answer is a hard failure, not a pass.
const PANEL_ARTIFACT = process.env.PANEL_ARTIFACT || 'dist-panel/catpanel.obf.js';
let EXPECTED_PANEL_VERSION = '';
try {
  EXPECTED_PANEL_VERSION = (readFileSync(PANEL_ARTIFACT, 'utf8').match(/CAT_PANEL_VERSION = '([0-9]+\.[0-9]+\.[0-9]+)'/) || [])[1] || '';
} catch { /* reported by the check below, with the file name */ }

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
// Two expected 404s on a local run, both noted rather than hidden:
//   /favicon.ico    — the browser always asks; the panel ships no icon.
//   /cdn-cgi/trace  — a Cloudflare-EDGE endpoint. The panel asks the edge for client
//                     info (ip/colo) and falls back when the answer is missing; on
//                     workerd it is simply not there. On a real deployment it is 200.
expectedHttp.add('404 /favicon.ico');
expectedHttp.add('404 /cdn-cgi/trace');
// The local workerd fixture has no GitHub release/network source for the optional
// updater lookup. Its fail-closed 502 is expected here; a deployed panel can
// reach that source and is covered separately by update-contract tests.
expectedHttp.add('502 /api/update-check');
const anon = await api('/api/settings', null, 401);
check('API برای ناشناس بسته است', anon.status === 401, `HTTP ${anon.status}`);
const anonDiag = await api('/api/diag', null, 401);
check('/api/diag برای ناشناس بسته است', anonDiag.status === 401, `HTTP ${anonDiag.status}`);

// ---- 2) لاگینِ واقعی از داخلِ فرم (کلیک، نه فراخوانیِ مستقیمِ API) ----
// Fresh deployments use the documented admin/admin bootstrap gate. UUID remains
// a subscription identity and must never be treated as a management password.
check('فیلد نام کاربری ورود دیده می‌شود', await page.locator('#u').isVisible().catch(() => false));
await page.fill('#u', PANEL_USER);
await page.fill('#p', PANEL_PASSWORD);
await Promise.all([
  page.waitForNavigation({ waitUntil: 'load', timeout: 45_000 }),
  page.click('button[type=submit]'),
]);
const afterLogin = await page.content();
check('لاگین از فرم رد شد (فرمِ ورود رفته)', !afterLogin.includes('id="p"'));
check('پنلِ واقعی رندر شد (ناوبری + بخش‌ها)', afterLogin.includes('id="sideNav"') && (afterLogin.match(/data-sec="/g) || []).length >= 8,
  `${(afterLogin.match(/data-sec="/g) || []).length} بخش`);
check('نسخهٔ پنل در صفحه دیده می‌شود', !!EXPECTED_PANEL_VERSION && afterLogin.includes(EXPECTED_PANEL_VERSION),
  `صفحه: ${(afterLogin.match(/6\.67\.\d+/g) || [])[0] || 'پیدا نشد'} · ${PANEL_ARTIFACT}: ${EXPECTED_PANEL_VERSION || 'خوانده نشد'}`);

// ---- 3) وریفای از داخلِ همان نشستِ مرورگر ----
const settings = await api('/api/settings');
check('/api/settings با نشست: 200 و ok:true', settings.status === 200 && /"ok":true/.test(settings.body), `HTTP ${settings.status}`);
let reportedHost = '';
try { reportedHost = JSON.parse(settings.body).host || ''; } catch { /* leave empty */ }
const browserHost = new URL(BASE).host;
// The panel reports the hostname without a port, which is correct for links and SNI
// (a workers.dev deployment has none); a local run listens on :8799, so compare the
// hostnames and say the full pair either way.
check('هاستِ گزارش‌شده همان هاستِ مرورگر است (بدونِ پورت)',
  reportedHost === browserHost.split(':')[0],
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

// ---- 3٫۵) کلیک روی ناوبریِ واقعی: هر بخش باید فعال شود، بی‌خطا ----
// ساختارِ واقعی: دکمه‌ها `data-view="x"` و بخش‌ها `#v-x` با کلاسِ `view`;
// فعال‌شدن = «view on» روی همان بخش + «on» روی دکمه (تابعِ show در پنل).
// روی صفحهٔ موبایل منو بسته است، پس اول باید باز شود — دقیقاً کاری که کاربر می‌کند.
// 6.75.0: «nodes» و «spoof» دیگر صفحهٔ جدا نیستند — تبِ «آیپی تمیز» و «لوکیشن» شدند (فقط به‌عنوان میان‌بر data-view می‌مانند).
const ALL_VIEWS = ['dash', 'clients', 'inbounds', 'link', 'scan', 'build', 'loc', 'tools', 'settings', 'backup', 'about'];
const ALIAS_VIEWS = ['nodes', 'spoof'];
const navNames = await page.$$eval('[data-view]', (els) => [...new Set(els.map((e) => e.getAttribute('data-view')))]);
say(`# دکمه‌های ناوبری در DOM: ${navNames.join(', ')}`);
const missing = navNames.filter((n) => !ALL_VIEWS.includes(n) && !ALIAS_VIEWS.includes(n));
// پنل موبایل‌محور است و ناوبری پشتِ همبرگر (#btnBurger → #menu.show) پنهان است.
const openedMenu = await page.evaluate(() => {
  const m = document.getElementById('menu');
  if (m && m.classList.contains('show')) return 'already-open';
  const btn = document.getElementById('btnBurger');
  if (btn) { btn.click(); return 'clicked #btnBurger'; }
  return 'no burger button';
});
say(`# منو: ${openedMenu}`);
const switched = [], stuck = [];
for (const v of ALL_VIEWS) {
  const loc = page.locator(`[data-view="${v}"]:visible`).first();
  let clicked = false;
  if (await loc.count()) {
    clicked = await loc.click({ timeout: 6000 }).then(() => true).catch(() => false);
  }
  if (!clicked) {
    // منو ممکن است با هر کلیک بسته شود؛ بازش کن و دوباره امتحان کن
    await page.evaluate(() => { const b = document.getElementById('btnBurger'); if (b && !document.getElementById('menu').classList.contains('show')) b.click(); });
    await page.waitForTimeout(120);
    clicked = await page.locator(`[data-view="${v}"]:visible`).first().click({ timeout: 6000 }).then(() => true).catch(() => false);
  }
  await page.waitForTimeout(140);
  const state = await page.evaluate((vv) => {
    const el = document.getElementById('v-' + vv);
    return { on: !!el && el.classList.contains('on'), cls: el ? el.className : 'no-section' };
  }, v);
  (clicked && state.on ? switched : stuck).push(clicked ? `${v}(کلاس=${state.cls})` : `${v}(کلیک نشد)`);
}
check('هر بخشِ ناوبری با کلیک واقعی فعال می‌شود', stuck.length === 0,
  `${switched.length}/${ALL_VIEWS.length}${stuck.length ? ' | گیرکرده: ' + stuck.join(' , ') : ''}`);
check('همهٔ دکمه‌های ناوبری از فهرستِ انتظار می‌آیند (بخشِ ناشناخته‌ای اضافه نشده)', missing.length === 0, missing.join(',') || 'هیچ');

// همان لحظه: سازندهٔ آیکون‌ها (مسیرِ محاسبه‌ای) نباید خطا داده باشد
check('گشت‌وگذار در همهٔ بخش‌ها خطای کنسولی جدیدی تولید نکرد', consoleErrors.length === 0, `${consoleErrors.length}`);

// ---- 3٫۶) سازندهٔ ساب: لینکی که می‌سازد باید همان کلیدِ زندهٔ پنل باشد ----
// باگِ گزارش‌شده («وقتی لینک ساخته می‌شه این ارور رو می‌ده») از همین‌جا می‌آمد: پنل لینک را
// از UUID می‌ساخت و بعد خودِ بگن (bGen) همان لینک را fetch می‌کرد؛ به‌محضِ چرخشِ توکنِ ساب
// (فیچرِ 6.67.0) مسیرِ UUID عمداً 404 می‌شود و کاربر «ساب خالی / پاسخِ ساب کانفیگ نیست:
// HTTP 404» می‌دید. اینجا همان کلیکِ واقعی انجام می‌شود و کلیدِ لینکِ ساخته‌شده با کلیدِ
// اعلام‌شدهٔ پنل مقایسه می‌شود.
// صادقانه: خودِ حالتِ «چرخش‌شده» اینجا قابلِ بازتولید نیست — این رانتایم عمداً KV ندارد،
// پس توکنی نمی‌تواند ذخیره شود. آن حالت روی خودِ ماژولِ ورکر پین شده است:
// scripts/panels/secrets-tokens.test.mjs و scripts/panels/builder-link-repro.mjs.
const openView = async (v) => {
  if (!(await page.locator(`[data-view="${v}"]:visible`).count())) {
    await page.evaluate(() => { const b = document.getElementById('btnBurger'); if (b && !document.getElementById('menu').classList.contains('show')) b.click(); });
    await page.waitForTimeout(150);
  }
  return page.locator(`[data-view="${v}"]:visible`).first().click({ timeout: 6000 }).then(() => true).catch(() => false);
};
let liveMasterPath = '';
try { liveMasterPath = new URL(JSON.parse(settings.body).links.sub).pathname; } catch { /* reported below */ }
check('پنل کلیدِ زندهٔ ساب را اعلام می‌کند (api/settings → links.sub)', /^\/sub\/[^/]+$/.test(liveMasterPath), liveMasterPath || '(ندارد)');
await openView('build');
await page.waitForTimeout(150);
await page.click('#bGen').catch(() => {});
await page.waitForTimeout(3000);
const built = await page.evaluate(() => ({
  link: document.getElementById('bLink').value,
  count: Number(document.getElementById('bCount').textContent || '0'),
  noteVisible: getComputedStyle(document.getElementById('bEmpty')).display !== 'none',
  note: (document.getElementById('bEmpty').textContent || '').slice(0, 170),
  preview: (document.getElementById('bPrev').value || '').slice(0, 40),
}));
check('لینکِ سازنده همان کلیدِ زندهٔ پنل را دارد (نه UUIDِ کهنه)',
  !!liveMasterPath && built.link.includes(liveMasterPath),
  `لینک: ${built.link.replace(BASE, '') || '(خالی)'} | کلیدِ پنل: ${liveMasterPath}`);
check('…و همان لینک واقعاً کانفیگ برمی‌گرداند (نُتِ خطا ندارد)',
  !built.noteVisible && built.count > 0 && /^vless:\/\//.test(built.preview),
  `count=${built.count} | نُت: ${built.noteVisible ? built.note : 'پنهان'} | پیش‌نمایش: ${built.preview}`);

// کوئری و مسیرهای عجیب: باید محترمانه جواب بدهند، نه ۵۰۰
for (const path of ['/panel?stale=1', '/nope-does-not-exist']) {
  if (path === '/nope-does-not-exist') expectedHttp.add(`404 ${path}`);
  const r = await api(path, null, path === '/nope-does-not-exist' ? 404 : undefined);
  check(`${path} پاسخِ محترمانه می‌دهد`, r.status < 500, `HTTP ${r.status}`);
}

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
