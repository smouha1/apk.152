# Smouha Call v1.5.0 (Glass V4 Edition)

Companion Android app for **Smouha Papper** Chrome extension: LAN bridge for instant Call / WhatsApp from the dashboard.

- **applicationId:** `com.smouha.call`
- **versionName:** `1.5.0` (`versionCode` 9)
- **minSdk:** 21 · **targetSdk:** 34

## What's New in v1.5.0

1. **⚡ 1-Tap Zero-IP Fast Pairing:** Tap **⚡ 1-Tap Fast Pair with PC (2 min)** on the phone, then click **⚡ Auto-Pair Phone** in the Chrome extension — no IP typing and no QR needed.
2. **Official Brand Identity & Glassy V4 UI:** Matches the Picker Performance Glass V4 theme (Warm Frosted Glass Light Mode + Smoked Obsidian Dark Mode toggle + Official Light-Blue `"S"` icon).
3. **Unified Singleton `PairingManager`:** Fixes first-try pairing race condition between UI and background service.
4. **Instant Direct WhatsApp Launch:** Uses `whatsapp://send?phone=` targeted directly to WhatsApp / WhatsApp Business without opening a browser tab first.
5. **Live UI & Today KPI Counters:** Real-time updates on the phone screen when calls or WhatsApp actions are triggered from the PC.

## Build on GitHub Actions (Recommended)

1. Push or upload this folder to your GitHub repository root.
2. Open **Actions** → **Build APK** → **Run workflow**.
3. Download the built artifact **`SmouhaCall-apk`**.
