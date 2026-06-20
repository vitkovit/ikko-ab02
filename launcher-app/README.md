# launcher-app (`com.mw.launcher`)

The dot-matrix watch-face **home launcher** for the IKKO AB02.

## Features
- Big Ndot-style **clock + date**, dynamic clock width (grows when no alerts, shrinks to make room for badges).
- **Stats** rows: earbud L/R + case battery, Wi-Fi (dotted signal staircase), cellular, Bluetooth — long names
  marquee-scroll (and pause when off-screen for battery).
- **Right-edge control rail** — Volume / Media / Brightness; swipe to switch pages, smooth ease-to-finger
  slider with edge-snap to 0/max. The **physical capacitive edge bar** (x ≈ 255–341, full height) maps a
  *deliberate* vertical drag to the current page.
- **Missed-call / unread-SMS badges** under/next to the clock (dotted phone + envelope, tap-to-clear),
  event-driven via `ContentObserver`.
- App **dock** with custom dotted glyph overrides (e.g. YouTube Music ♪ / YouTube ▶); all-apps drawer.
- **Battery:** all periodic work (8→15 s stats poll, signal listener, marquee) stops when the screen is off.

## Build & install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package set-home-activity com.mw.launcher/.MainActivity
```

## Permissions / services
Grant (API 27 dangerous perms): `READ_PHONE_STATE READ_CALL_LOG WRITE_CALL_LOG READ_SMS ACCESS_FINE_LOCATION
BLUETOOTH BLUETOOTH_ADMIN` (see [SETUP](../SETUP.md) §2). Enable by hand:
- **Notification access** → `com.mw.launcher/.MediaNotifListener` (media widget).
- **Accessibility** → `com.mw.launcher/.AncAccessibilityService` (earbud-battery scrape / ANC).

## Fonts
Expects `app/src/main/assets/{ndot57,clock,body}.otf` + `offbit.ttf` — **not shipped**; drop in an OFL dot font
([FONTS.md](../FONTS.md)). Falls back to the system font if missing.

## Custom dock icons
Dotted glyphs are rasterized from a Material vector onto a dot grid, e.g.:
```bash
magick -size 240x240 xc:black -fill white -draw "scale 10,10 path '<svg-path>'" sil.png
# downsample to a grid, then draw a white circle per "on" cell -> 144x144 PNG in res/drawable-nodpi/
```
See `tools/make_icon.py`.

---
Part of **ikko-ab02** · https://github.com/vitkovit/ikko-ab02
