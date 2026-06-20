# CLAUDE.md — build & deploy this on an IKKO AB02

You are Claude Code running on a laptop with an **IKKO Activebuds AB02** attached over USB. Your job: reproduce
the full setup in this repo. Read this end-to-end first, then work top-down. Verify on the device after each
step — don't assume.

## Device facts (ground truth)
- IKKO AB02 · Android **8.1 / API 27** · **armeabi-v7a** (32-bit) · **no GMS** · locked bootloader · not rooted.
- Screen **368×448 @ 160 dpi → density 1.0**, so `dp == sp == px`. Design at **smartwatch scale**: big text,
  finger-sized targets, NOT phone-MD3 sizing. If something looks small, scale up decisively (+40–50%).
- Off-screen **capacitive edge bar** reports as touchscreen events at **x ≈ 255–341, full height**.
- Apps (all `com.mw.*`): `launcher`, `dialer`, `focus` (the timer), `touch` (digitizer test), `claude`.

## Environment
- Need **JDK 17**, Android **platform-tools** (`adb`), SDK with `platforms;android-34` + `build-tools`.
- Tell Gradle where the SDK is: `export ANDROID_HOME=/path/to/sdk` (or write `sdk.dir=` into each app's
  `local.properties` — gitignored, never commit).
- One device assumed; otherwise `adb devices` → use `-s <serial>` everywhere. **Do not hardcode a serial in
  committed files.**
- Each app is its own Gradle project. Build+install loop:
  ```bash
  cd <app> && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  Prepend `JAVA_HOME=/path/to/jdk17` if Gradle picks the wrong Java.

## Order of operations
1. **Fonts** — optional; drop an OFL dot font per `FONTS.md` (apps fall back to system font if absent).
2. **Build + install** all five apps (loop above, or just the ones you want).
3. **Permissions + services** — run the `pm grant` block and enable the notification-listener + accessibility
   services (see `SETUP.md` §2). These gate the launcher's media widget, earbud-battery scrape, and
   missed-call/SMS badges.
4. **Default launcher** — `adb shell cmd package set-home-activity com.mw.launcher/.MainActivity`.
5. **Contacts** — import the user's `.vcf` (`SETUP.md` §4). Treat the vCard as private: never copy it into the
   repo, delete from `/sdcard` after import.
6. **microG + ReVanced YouTube / YouTube Music**, **Organic Maps**, **Gboard** — `SETUP.md` §5–7. You patch
   official APKs; you do not ship patched binaries.
7. **Stock cleanup** — optional (`SETUP.md` §8). Keep the native dialer/MMS (they own incoming calls/SMS).

## Verifying on-device (do this, don't guess)
- **Screenshots:** `adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png` then look at it. A ~1–2 KB
  PNG means the screen was asleep/black — `adb shell input keyevent KEYCODE_WAKEUP`, swipe up to dismiss the
  keyguard (`input swipe 184 380 184 120 200`), and for capture sessions `adb shell svc power stayon true`
  (restore `false` when done).
- **Volume/slider:** `adb shell media volume --stream 3 --get`.
- **Input:** `adb shell input tap X Y` / `input swipe`. The launcher's edge slider needs a *deliberate* vertical
  drag (≥ ~26px) starting at x ≥ 255; taps there do nothing by design.
- **Logs / state:** `adb logcat -d`, `adb shell dumpsys audio|power|activity activities`.

## Known gotchas (learned the hard way)
- **Google Maps is a dead end** (forced-update gate crashes; bundles have no v7a libs) → use Organic Maps.
- Use **armeabi-v7a single-arch** APKs, never Play app-bundles (split APKs, 0 native libs → crash).
- `adb shell` (uid shell) **cannot read/write `call_log` or `sms`** here — you can't seed fake calls/texts;
  verify those features with real calls/texts or by temporarily forcing values in code (then revert).
- The keyguard re-appears a lot; screencaps often catch the lockscreen clock — dismiss it before capturing.
- Battery: the launcher is the always-on home, so it gates all periodic work on **screen on/off**; keep that
  pattern. The Focus timer **intentionally keeps the screen on while running** — do not "optimize" that away.

## Guardrails
- This is authorized customization of the user's own device. Fine to sideload, set defaults, grant perms,
  uninstall stock apps for `--user 0`.
- **Never** commit private data (contacts/vCard, screenshots showing SSID/carrier/names/numbers), signing keys
  (`*.keystore`/`*.jks`), `local.properties`, or the proprietary fonts. They're gitignored — keep it that way.

Details for any step live in `SETUP.md` and the per-app `README.md`s.

---
Part of **ikko-ab02** · https://github.com/vitkovit/ikko-ab02
