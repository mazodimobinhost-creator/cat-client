# Cat Client

**Development is happening here:**

### → https://github.com/mazodimobinhost-creator/ArasClient

This repository is the original placeholder.  
The real Cat Client codebase (rebranded from ArasClient + WhiteVPN ideas) lives in the ArasClient fork.

---

## What was done

- Application ID changed to `com.cat.client`
- APK output renamed to `CatClient_*.apk`
- Project name → CatClient
- Visible app name → **Cat Client**
- README updated

## Cat Panel (Cloudflare Worker)

A deployable starter is included in [`workers/cat-panel`](workers/cat-panel). It provides a protected configuration page, health check, and VLESS subscription generator using Cloudflare Secrets. See its README for deployment. It deliberately does not claim to be a VPN relay or provide unauthorized IP scanning.

The Android application source is not present in this repository (this checkout is a placeholder), so an APK cannot honestly be released from this repository yet. The workflow validates the Worker; Android builds must be added after importing the ArasClient/WhiteVPN source and auditing its licenses and package names.

## Next steps for you

1. Clone the ArasClient fork:
   ```bash
   git clone https://github.com/mazodimobinhost-creator/ArasClient.git
   cd ArasClient
   ```

2. Open in **Android Studio**

3. (Important) Refactor the package:
   - Right click on `com.aras.client` package
   - Refactor → Rename → `com.cat.client`

4. Fix any remaining "ArasClient" strings if needed (search project)

5. Build the APK

---

After the package rename works and the app builds, we can continue with:
- Purple color theme
- Icon (black/white cat + purple lightning)
- Extra features from WhiteVPN

