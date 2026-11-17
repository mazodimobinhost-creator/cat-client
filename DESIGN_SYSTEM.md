# Cat Client — Dashdark X inspired design system

The Android UI uses a navy / sky-blue palette inspired by the supplied dashboard reference. The app is a native Android application, not a web dashboard, so its screens retain native navigation and interaction.

## Colors

| Token | Dark | Light |
|---|---|---|
| Background | `#080F24` | `#F0F7FF` |
| Surface | `#0D1932` | `#FFFFFF` |
| Elevated card | `#112443` | `#F3F9FF` |
| Primary | `#38BDF8` | `#0284C7` |
| Accent | `#0EA5E9` | `#0EA5E9` |
| Outline | `#284261` | `#D4E5F5` |
| Text | `#F1F8FF` | `#0B2342` |

The colors are implemented in both Android resource variants and `CatClientDesignTokens` for programmatic UI views. Theme switching remains supported. The panel `:root` / `clean` / `ocean` / `daylight` skins use the same navy / sky tokens.

## Typography

The bundled Persian font is Vazirmatn. Estedad is **not** bundled in the provided repository, so it has not been substituted with a mislabeled font. To adopt Estedad, add properly licensed font files under `app/src/main/res/font/` and update the font references in the styles.

## Scope and limitations

This pass migrates the shared native Android design tokens and resource colors, and updates the globe accent. It does not claim a pixel-perfect recreation of every screen, a redesigned Windows client, or a successful APK build. VPN and networking logic are left unchanged.
