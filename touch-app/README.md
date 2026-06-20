# touch-app (`com.mw.touch`)

A tiny **digitizer / edge-bar test surface** — invaluable for mapping the AB02's screen and its off-screen
capacitive edge bar.

## What it does
- Starts all-white; every touched pixel turns green and stays green, so a slide (including the physical edge
  strip) leaves a visible trail of exactly which pixels the panel reports.
- Live `x / y / pointer-count` HUD top-left.
- Overlays the launcher's two **slider hit-zones** (blue = edge strip / slide-only; orange = on-screen rail /
  tap+slide) so you can see where input is interactive.
- **Volume DOWN** clears the trail; **Volume UP** toggles the zone overlay.

## Build & install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mw.touch/.MainActivity
```

How the AB02's edge bar was found: `adb shell getevent -lt` while sliding → events land at screen
**x ≈ 255–341, full height**.

---
Part of **ikko-ab02** · https://github.com/vitkovit/ikko-ab02
