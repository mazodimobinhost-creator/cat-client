---
name: ui-panel-design
description: Use for designing or building any UI, admin panel, dashboard, landing page, or app screen, especially Persian/RTL and mobile-first. Covers layout, typography, color, states, and quality checks.
---

# UI and panel design

## Before you design
1. Ask or infer: who uses it, on what device (assume phone first), what the 3 most frequent actions are.
2. Pick one clear visual direction (minimal, dense data panel, playful, etc.) and keep it across every screen. Do not mix styles.
3. Define tokens first: colors, spacing scale (4/8/12/16/24/32), radius, shadows, font sizes. Reuse them everywhere. Never hardcode random values.

## Persian / RTL rules
- Set `dir="rtl"` and `lang="fa"` on the root. Use logical properties (`margin-inline-start`, `padding-inline-end`, `text-align: start`) instead of left/right.
- Font: Vazirmatn (or IRANSans if licensed). Use a fallback stack: `Vazirmatn, Tahoma, system-ui, sans-serif`. Body 15-16px, line-height 1.7 for Persian paragraphs.
- Mirror directional icons (back arrows, chevrons, progress). Do NOT mirror logos, media controls, phone numbers, or code.
- Numbers: use Persian digits for display text when the product is Persian, but keep Latin digits inside inputs for phone, card, and amounts unless the backend normalizes. Always normalize Persian/Arabic digits to Latin before sending to an API.
- Use ZWNJ (نیم‌فاصله) correctly in Persian text. Never replace it with a space.
- Dates: show Jalali (Shamsi) for users, store UTC/ISO in the database. Week starts Saturday.
- Mixed text (Persian + English/URLs/IPs/configs): wrap LTR fragments in `<bdi>` or `dir="ltr"` so they do not scramble. Config strings, tokens, and code blocks are always `dir="ltr"`.
- Currency: say Toman or Rial explicitly; group digits by 3.

## Layout and components
- Mobile-first: design at 360px width, then scale up. Touch targets at least 44x44px. Bottom navigation or bottom sheets for primary phone actions.
- Panels: sidebar collapses to a drawer on mobile. Tables become stacked cards under ~640px, or scroll horizontally inside their own container.
- Every screen needs all states designed: loading (skeleton), empty (explain + a call to action), error (what happened + retry), success, offline.
- Forms: label above field, inline validation after blur, clear error text, disabled button while submitting, keep user input on error.
- Destructive actions: confirm dialog naming the thing being deleted. Offer undo when possible.
- Show feedback within 100ms of any tap (pressed state, spinner).

## Visual quality
- Max 2 font weights per screen in practice (regular + semibold). Clear type hierarchy: title, section, body, caption.
- Contrast: body text at least 4.5:1 against its background. Check in dark mode too.
- Support light and dark via CSS variables and `prefers-color-scheme`. Do not use pure black on pure white.
- Avoid template look: no generic purple gradients, no random emoji as icons, no clutter. Use one icon set consistently (e.g. Lucide).
- Whitespace is a feature. Group related items; separate groups with space, not heavy borders.
- Animations: 150-250ms, ease-out, only to explain change. Respect `prefers-reduced-motion`.

## Performance (matters on slow Iranian mobile connections)
- Keep first load small: self-host fonts (subset Persian + Latin), compress images (WebP/AVIF), lazy-load below the fold.
- Do not depend on third-party CDNs that may be blocked in Iran (Google Fonts, some JS CDNs). Bundle assets locally or serve through your own domain/Cloudflare.
- Make the UI usable on a flaky connection: retry with backoff, cache last known data, show stale data with a "last updated" label.

## Before saying done
- Open it at 360px, 768px, and desktop width. Check RTL, dark mode, empty/error states.
- Tab through with a keyboard; check focus rings and labels.
- Take a screenshot if tools allow and look at it critically. List anything that looks off and fix it.
