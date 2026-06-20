# claude-app (`com.mw.claude`)

A fullscreen **Claude client** for the AB02 — really a thin **GeckoView** (Mozilla's browser engine) wrapper
around `https://claude.ai/login`. You sign in on the website; there is **no API key** anywhere in this app.

## Why GeckoView (not WebView)?
The stock Android 8.1 System WebView on this device is too old for claude.ai. GeckoView ships its own
up-to-date engine and handles the Google-OAuth popup (opened in a dialog session).

## Build & install
```bash
./gradlew assembleDebug      # first build is large/slow — GeckoView is pulled from Maven
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mw.claude/.MainActivity
```

## Notes
- Dependency: `org.mozilla.geckoview` (MPL-2.0), fetched from Maven — not vendored here. See
  [NOTICE](../NOTICE.md).
- No secrets: auth is the normal website login (Google / email). Nothing is stored by this app beyond Gecko's
  own cookie/profile storage on-device.
- Desktop user-agent by default; play-store / app-store / `intent://` links are blocked.

---
Part of **ikko-ab02** · https://github.com/vitkovit/ikko-ab02
