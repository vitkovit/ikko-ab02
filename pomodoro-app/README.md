# pomodoro-app — "Focus" timer (`com.mw.focus`)

A dot-matrix **interval / focus timer**. (Deliberately *not* called Pomodoro — no tomatoes.)

## Features
- Big dotted **MM:SS** hero countdown (timestamp-based, so it stays accurate across sleep/resume).
- **120-dot progress ring** that fills via a connecting line (~2 dots/sec).
- **Drag-to-set** duration: vertical swipe over the timer — left half = minutes, right half = seconds; scroll
  chevrons hint it.
- **Mirror-symmetric layout**: wall clock / media transport / TIMER / controls / phase label.
- Background-music **media transport** controls.
- Focus / short-break / long-break phases with completion tone.

## Build & install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# optional: remove the stock timer it replaces
adb shell pm uninstall --user 0 com.ikkoaudio.pomodoro_clock
```

## Notes
- **Keeps the screen on while a session is running** (intentional — you want to see the countdown). This is the
  main battery cost and is a deliberate choice.
- The redraw ticker only runs while counting and stops in `onPause` / when detached — already battery-friendly.

## Fonts
Uses the launcher-style dot font if present; otherwise the built-in `DotFont` dot renderer / system font. No
font binaries shipped ([FONTS.md](../FONTS.md)).

---
Part of **ikko-ab02** · https://github.com/vitkovit/ikko-ab02
