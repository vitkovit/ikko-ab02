# Third-party notices

This repository contains only original, MIT-licensed code (see `LICENSE`). The full setup *uses* several
third-party components that are **not** included here — you download/build them yourself per `SETUP.md`. They
remain under their own licenses:

## Build / runtime dependencies
- **Mozilla GeckoView** (`org.mozilla.geckoview`) — used by `claude-app` as the embedded browser engine.
  Mozilla Public License 2.0. Pulled from Maven at build time; not vendored here.
- **Android Gradle Plugin / AndroidX** — Google, Apache-2.0. Pulled from Maven.

## Apps you install on the device (instructions only, not redistributed)
- **microG** (GmsCore) — Apache-2.0 — https://microg.org/  — Google-Play-Services replacement.
- **ReVanced** (patched YouTube / YouTube Music) — GPLv3 tooling — https://revanced.app/  — you patch the
  official APKs yourself; nothing pre-patched is shipped here.
- **Aurora Store** — GPLv3 — anonymous installs of Play apps.
- **Organic Maps** — Apache-2.0 — GMS-free offline maps.
- **Gboard** — Google, proprietary — installed from your own source.
- **F-Droid** — GPLv3 — open-source app store.

## Fonts (NOT included — see FONTS.md)
- **Ndot 57**, **NType 82** — Colophon Foundry for **Nothing Technology** — proprietary, redistribution
  forbidden. Excluded.
- **OffBit** — Power Type (Teguh Arief) — see https://power-type.com/license — excluded to be safe.

## Native library (NOT included)
- **`libserial_port.so`** — the **stock IKKO** native serial-port library used for ANC / earbud control
  (`/dev/ttyS1`). It is IKKO's proprietary binary and is **not** distributed here. Our `SerialPort.java`
  (kept in package `com.ikkoaudio.core.serialport.jni` only so the stock `.so`'s JNI symbols still link) is our
  own thin wrapper. The launcher runs fine without the `.so` — `AncController` swallows the load failure and
  simply disables ANC control. To enable it, extract `libserial_port.so` from your device's stock firmware and
  drop it at `launcher-app/app/src/main/jniLibs/armeabi-v7a/libserial_port.so` (gitignored).

## Reference material that is intentionally absent
Decompiled IKKO firmware apps (stock launcher/dialer/settings) were used locally for reverse-engineering and
are **not** part of this repo — they are IKKO's copyright.

---
Part of **ikko-ab02** — https://github.com/vitkovit/ikko-ab02
