# Cat Panel skins — «Cat Glass» (panel v6.65+)

The panel UI is a **skin system**: one set of CSS variables, six looks. Nothing
about links, configs or subscriptions depends on it — a skin is pure cosmetics.

## Why it looks like this (and what was deliberately *not* copied)

The look was inspired by public Cloudflare/Node panels — the glass surfaces, a
full-bleed wallpaper behind translucent cards, a fixed sidebar, gradient accents
and a rounded, pill-shaped control language.

**No code was copied from them.** Both public repositories that were used as
visual references (`BlueKnightNet/Blue-Knight-Panel` and
`BlueKnightNet/Blue-Knight-Panel-V3`) declare **no licence**, which under
copyright law means *all rights reserved*; Cat Client itself is **GPL-3.0**.
Copying their CSS/HTML into this project would be both a licence violation and
impossible to relicense. So every rule in
`app/src/main/assets/panels/catclient.worker.js` (`BASE_CSS` + the inline
`<style>` of `panelPage`) is written from scratch for this project.

Two consequences worth knowing:

1. **No image assets.** Those panels ship ~5.5 MB of JPG wallpapers. Cat Panel
   is a *single self-contained file* and must stay offline-safe, so every
   wallpaper here is a stack of CSS `radial-gradient` layers — no CDN, no extra
   request, nothing that can be blocked.
2. **No web fonts.** The stack stays on system fonts (`SF Pro / Segoe UI /
   Roboto / Vazirmatn`), again so the page loads with zero network dependency.

## How the system works

| Layer | Where | What it holds |
|---|---|---|
| Tokens | `BASE_CSS` → `:root` | surfaces, text, accent, blur radius, radius, shadow, `--wall` |
| Skin | `BASE_CSS` → `[data-theme=…]` | overrides ≈10 variables: accent trio, `--glow`, `--wall`, `--wall2` |
| Wallpaper | `html[data-theme] body::before/::after` | the gradient stack (crisp) + a blurred, slowly drifting aurora |
| Components | rest of the CSS | every surface reads the tokens, so a new skin needs **no** component rules |
| State | `<html data-theme>` | rendered server-side from `settings.uiTheme`, plus a `catTheme` cookie for the first paint |
| Picker | `#themePick` (inline JS) | six swatches → applies instantly, writes the cookie, `PUT /api/settings {uiTheme}` |

Because the skin lives on `<html data-theme>`, the choice is **server-rendered**:
a panel with KV paints the right colours on the very first frame. The cookie
covers panels *without* KV (settings cannot persist there).

The wallpaper is opt-in per page: only pages that carry `data-theme` get it
(panel + login). The camouflage landing page, the 404 and the public user-info
page stay plain and neutral — the stealth page must not look like a panel.

## The six skins

| id | fa | look |
|---|---|---|
| `aurora` | شفق | teal → violet (default, keeps the Cat identity) |
| `nebula` | سحابی | violet → magenta |
| `ocean` | اقیانوس | cyan → indigo |
| `ember` | اخگر | amber → coral |
| `forest` | جنگل | emerald → teal |
| `steel` | فولاد | neutral graphite (lowest-noise option) |

`aurora` lives in `:root`; the other five are `[data-theme=…]` override blocks.

## Adding a skin (3 steps, no component CSS)

1. Add the id to `PANEL_THEMES` (top of the worker, next to `CAT_PANEL_VERSION`).
2. Add a `[data-theme=<id>]{…}` block in `BASE_CSS` overriding the accent trio
   (`--violet`, `--violet2`, `--fuchsia`), `--glow`, `--nav-bg`, `--wall` and
   `--wall2`.
3. Add `th_<id>` to **both** i18n tables (`fa` and `en`) plus a `THEME_META`
   entry with the swatch preview gradient.

`scripts/panels/panel-integrity.test.mjs` fails if a `data-i` key is missing from
either language, and `scripts/panels/panel-theme.test.mjs` fails if a swatch, its
preview gradient or the persistence round trip is broken.

## Accessibility & safety rails

- `@supports not (backdrop-filter…)` swaps translucent surfaces for opaque ones,
  so text never lands on an unreadable blur-less wash.
- `prefers-reduced-motion: reduce` stops the aurora drift, the marquee and every
  spring transition.
- Accent gradients are used for **numerals and rails**, never for body text, so
  contrast stays high in every skin.
- An unknown `uiTheme` (hand-edited backup JSON, older panel) falls back to
  `aurora` — a bad value can never blank the page.

## Tests

```
node scripts/panels/panel-theme.test.mjs   # DOM round trip: apply → cookie → PUT → re-render
npm test                                   # includes it, plus panel-integrity (i18n parity, inline-script parse)
```

## Data-display pass (6.66.0)

The skins above set the *palette*; 6.66.0 also reworks the components that show
data. The reference was a public UI-pattern library (page header + toolbar, stat
card with a trend line, data table with a toolbar, empty state) — the *patterns*
were taken as guidance, the code here is written from scratch against this
panel's own CSS variables, so nothing is copied and nothing is loaded at
runtime.

| Piece | Change | Why it reads better |
| --- | --- | --- |
| `.vhead` | hairline divider under the header | the title + description read as one page instead of floating text |
| `.st .s` | the sub-line became a pill | matches the "trend chip" pattern (`↑ 23% from baseline`) |
| `.st .n` | `font-variant-numeric: tabular-nums` | live counters stop jittering as digits change width |
| `.st[data-c=…]` | per-colour rail and numeral gradient | nine stat cards no longer look like nine copies of one card |
| `.tbl` | rounded row cards, zebra banding, hover rail, quieter headers | rows are scannable; the eye can follow one row across |
| `.empty` | glass icon bubble + readable caption in a dashed frame | the old rule gave *both* lines the 40px icon size |
| `.chip` variants | leading dot in the chip's own colour | status is readable without reading the word |

Every change is CSS only: no images, no web fonts, no extra request, and the
`prefers-reduced-motion` and no-`backdrop-filter` fallbacks from the skin work
still apply. Persian and English both keep their own line-length, since the
caption is capped in `ch` units rather than pixels.
