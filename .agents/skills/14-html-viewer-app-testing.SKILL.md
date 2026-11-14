---
name: html-viewer-app-testing
description: Use to build polished, working HTML/CSS/JS pages and panels, preview them as a real viewer would (screenshots, clicks, console checks), and to test Android phone apps (emulator or real device via adb). Use whenever the result must look premium and actually work, not just compile.
---

# HTML viewer, premium design, and phone app testing

## Honest limits
- A preview needs a headless browser (Playwright/Chromium or Puppeteer). If the environment has none or no network to install it, say so, give the user the exact commands, and still validate HTML/CSS/JS statically.
- Testing a phone app needs an emulator or a real device connected through `adb`. A cloud sandbox usually has neither. Ask for the APK and/or a device, or give the user the commands and a script to run and paste back. Never claim "tested on a phone" without logs or screenshots.

## Part 1: Build HTML that works
- One self-contained file when possible (inline CSS/JS). Self-host fonts and icons (inline SVG). No dependence on Google Fonts, CDNs, or analytics that may be blocked in Iran.
- Semantic HTML: header, nav, main, section, button (not div), label for every input. `lang="fa" dir="rtl"` for Persian, logical CSS properties.
- Mobile-first, responsive with CSS grid/flex, `meta viewport`, safe-area insets, no horizontal scroll at 360px.
- Every button and control must do something real or be clearly disabled. Wire up events, handle empty/loading/error states, and avoid dead links (`href="#"`).
- JS: no globals leaking, handle exceptions, validate inputs, debounce expensive handlers, store state in memory or `localStorage` inside try/catch.

## Part 2: Premium look (design tokens first)
```css
:root{
  --bg:#0b0f17; --surface:#121826; --surface-2:#182033; --text:#e8ecf4; --muted:#93a0b8;
  --primary:#5b8cff; --primary-2:#7c5cff; --ok:#2ecc8f; --warn:#f5b84a; --danger:#ff5d6c;
  --radius:14px; --shadow:0 10px 30px rgba(0,0,0,.35); --ring:0 0 0 3px rgba(91,140,255,.35);
  --font:Vazirmatn,Tahoma,system-ui,sans-serif;
}
@media (prefers-color-scheme:light){:root{--bg:#f5f7fb;--surface:#fff;--surface-2:#eef2f9;--text:#101828;--muted:#5b6784;--shadow:0 10px 30px rgba(16,24,40,.10)}}
body{margin:0;background:var(--bg);color:var(--text);font:16px/1.7 var(--font)}
.card{background:var(--surface);border-radius:var(--radius);box-shadow:var(--shadow);padding:20px;border:1px solid rgba(255,255,255,.04)}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;min-height:46px;padding:0 20px;border:0;border-radius:12px;
  font:600 15px var(--font);color:#fff;cursor:pointer;background:linear-gradient(135deg,var(--primary),var(--primary-2));
  box-shadow:0 6px 18px rgba(91,140,255,.35);transition:transform .15s,box-shadow .15s,filter .15s}
.btn:hover{filter:brightness(1.08);box-shadow:0 8px 24px rgba(91,140,255,.45)}
.btn:active{transform:translateY(1px) scale(.98)}
.btn:focus-visible{outline:none;box-shadow:var(--ring)}
.btn[disabled]{opacity:.5;cursor:not-allowed;box-shadow:none}
.btn.ghost{background:transparent;color:var(--text);border:1px solid rgba(255,255,255,.14);box-shadow:none}
.btn.danger{background:var(--danger);box-shadow:0 6px 18px rgba(255,93,108,.3)}
@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
```
Design rules:
- One accent, one gradient at most; generous spacing (8px grid); radius consistent; soft shadows; subtle borders. Hierarchy: big clear title, muted secondary text, one primary button per view.
- Components to include in a premium panel: sticky top bar, sidebar/drawer, KPI cards, charts (inline SVG or canvas), tables with hover and sticky header, tabs, modals, toasts, skeleton loaders, empty states, toggle switches, segmented controls, badges, copy-to-clipboard buttons with feedback.
- Micro-interactions: 150-250ms transitions, pressed/hover/focus states, success toast after actions, loading spinner inside the button while waiting.
- Icons: inline SVG, one set, 20-24px, `currentColor`.
- Avoid: random gradients on everything, emoji as icons, tiny text, low contrast, crowded layouts, animations that block use.

## Part 3: Preview and check (viewer routine)
1. Serve or open the file (`python -m http.server`) and render with Playwright:
   - Screenshots at 360x800, 390x844, 768x1024, 1280x800, 1920x1080; light and dark; RTL.
   - Save to `screens/` and look at each one critically. List what looks off and fix it.
2. Capture console errors, failed requests (404, blocked CDN), and uncaught exceptions. Zero tolerated.
3. Click through every button, link, tab, modal, form (valid and invalid input). Confirm the expected change happened (DOM state or screenshot).
4. Keyboard test: Tab order, visible focus, Esc closes modals, Enter submits.
5. Accessibility: run axe-core; check contrast, labels, alt text, landmark roles.
6. Performance: Lighthouse or manual: file size, image sizes, layout shift, no render-blocking external files. Test with network throttled (Slow 3G) and offline behavior.
7. Cross-check in Chromium, Firefox, and WebKit if available.
Minimal Playwright example:
```js
import { chromium } from 'playwright';
const b = await chromium.launch(); const p = await b.newPage({ viewport:{width:390,height:844} });
p.on('console', m => m.type()==='error' && console.log('CONSOLE', m.text()));
p.on('pageerror', e => console.log('PAGEERROR', e.message));
await p.goto('file:///path/index.html'); await p.screenshot({ path:'screens/mobile.png', fullPage:true });
await b.close();
```

## Part 4: Test phone apps (Android)
Setup: Android SDK platform-tools, an emulator (AVD) or a real device with USB debugging, `adb devices` shows it.
1. Install and launch: `adb install -r app.apk`, `adb shell monkey -p <package> 1`.
2. Look: `adb exec-out screencap -p > screen.png`; screen recording: `adb shell screenrecord /sdcard/r.mp4`.
3. Read logs: `adb logcat -v time *:E` (filter by package PID); capture crashes (FATAL EXCEPTION), ANRs, StrictMode warnings.
4. Drive the UI: `adb shell input tap X Y`, `input text`, `input keyevent 4` (back); `adb shell uiautomator dump` to read the view tree and find element positions. Better: Maestro flows (YAML) or Espresso/Compose UI tests, Appium for cross-device.
5. Scenarios: first launch, permissions denied/granted, rotation, background/foreground, kill and restore (`adb shell am kill`), airplane mode, slow/blocked network, low storage, dark mode, RTL (`adb shell settings put global ...` or developer option), large font size, small screens, Android 8 through latest.
6. VPN/proxy apps: start/stop, switching Wi-Fi/mobile, DNS leak check, kill-switch behavior, battery optimization effects.
7. Release check: test the signed, minified release APK, not only debug. Check app size, startup time, memory (`adb shell dumpsys meminfo <package>`), and jank (`dumpsys gfxinfo`).
8. If no device is available: give the user a short script that runs steps 1-3 and collects `screens/` and `logcat.txt` to send back.

## Final report
Done / Verified (list screenshots, viewports, devices, commands, results) / Not verified (and why) / Issues found with fixes / Remaining rough edges. Attach or reference the screenshot files.
