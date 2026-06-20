# Full setup — IKKO AB02

End-to-end manual setup. Prefer to automate it? Hand [`CLAUDE.md`](CLAUDE.md) to Claude Code instead.

> All `adb` commands assume **one** device is attached. If you have several, find the serial with
> `adb devices` and add `-s <serial>` to each command.

---

## 0. Prerequisites (laptop)

- **JDK 17** (e.g. Temurin / `openjdk@17`).
- **Android platform-tools** (`adb`).
- **Android SDK** with `platforms;android-34` and matching `build-tools` (apps: compileSdk 34, targetSdk 27,
  minSdk 23 — 21 for touch/claude).
- Point Gradle at the SDK: either export `ANDROID_HOME=/path/to/sdk` or create `local.properties` in each app
  (`sdk.dir=/path/to/sdk`). `local.properties` is gitignored — never commit it.
- On the AB02: **Developer options → USB debugging** on. Accept the RSA prompt on first connect.

Build artifacts are JDK-version sensitive; if Gradle complains, prepend `JAVA_HOME=/path/to/jdk17`.

## 1. Build & install the apps

Each app is a standalone Gradle project:

```bash
for app in launcher-app dialer-app pomodoro-app touch-app claude-app; do
  ( cd "$app" && ./gradlew assembleDebug \
    && adb install -r app/build/outputs/apk/debug/app-debug.apk )
done
```

(Fonts are optional — see [`FONTS.md`](FONTS.md). `claude-app` pulls GeckoView from Maven, so its first build
is large/slow.)

## 2. Grant runtime permissions

Some launcher/dialer permissions are "dangerous" (API 27) — grant them up front so nothing is blocked:

```bash
# Launcher: stats, missed-call / unread-SMS badges
for p in READ_PHONE_STATE READ_CALL_LOG WRITE_CALL_LOG READ_SMS \
         ACCESS_FINE_LOCATION BLUETOOTH BLUETOOTH_ADMIN; do
  adb shell pm grant com.mw.launcher android.permission.$p
done
# Dialer
for p in READ_CONTACTS CALL_PHONE READ_CALL_LOG WRITE_CALL_LOG; do
  adb shell pm grant com.mw.dialer android.permission.$p
done
```

Two services must be enabled **by hand** in Settings (or via the commands below):
- **Notification access** for the launcher's media widget (`com.mw.launcher/.MediaNotifListener`).
- **Accessibility** for the launcher's earbud-battery scraper / ANC control (`.AncAccessibilityService`).

```bash
adb shell settings put secure enabled_notification_listeners com.mw.launcher/com.mw.launcher.MediaNotifListener
adb shell settings put secure enabled_accessibility_services com.mw.launcher/com.mw.launcher.AncAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## 3. Make the launcher the default home

```bash
adb shell cmd package set-home-activity com.mw.launcher/.MainActivity
```
(Or press Home once and pick "Home" / set as default.)

## 4. Contacts via vCard (no Google sync)

Put your contacts in a `.vcf` (export from your phone / Google Contacts). **Never commit it** (gitignored).

```bash
adb push contacts.vcf /sdcard/Download/contacts.vcf
adb shell am start -t "text/x-vcard" -a android.intent.action.VIEW \
  -d file:///sdcard/Download/contacts.vcf
```
…then confirm the import in the contacts UI. The dialer also has an **Import** button (Contacts tab) that opens
a file picker. After import you can delete the file: `adb shell rm /sdcard/Download/contacts.vcf`.

## 5. Google-free Google apps — microG + ReVanced

The AB02 has **no Google Play Services**. To run real YouTube / YouTube Music with login + background play:

1. **microG (GmsCore)** — install from https://microg.org/ (armeabi-v7a build), open it, run the **self-check**.
2. **ReVanced** — patch the *official* APKs yourself (don't ship patched binaries):
   - Get **ReVanced Manager** (https://revanced.app/) or the CLI.
   - Supply the official **YouTube** and **YouTube Music** armeabi-v7a APKs (e.g. from APKMirror; match an
     architecture-specific, non-bundle build).
   - Apply the default patch set **with microG support**, install the result, sign into your Google account.

> 32-bit / no-GMS gotchas: pick **armeabi-v7a single-arch** APKs, not Play "app bundles" (split APKs with no
> native libs crash). See the writeup for the dead-ends we hit.

## 6. Maps

Google Maps is a **dead end** here (its forced-update server gate crashes the only complete v7a build).
Use **Organic Maps** instead — GMS-free, offline, works:
- Install the **Organic Maps** APK from F-Droid (https://f-droid.org/packages/app.organicmaps/), then download
  your region's offline map in-app.

## 7. Keyboard

Install **Gboard** (your own source), then make it the default IME:
```bash
adb shell ime list -s                       # find the Gboard id
adb shell ime enable com.google.android.inputmethod.latin/com.google.android.apps.inputmethod.libs.lm.LatinIME
adb shell ime set    com.google.android.inputmethod.latin/com.google.android.apps.inputmethod.libs.lm.LatinIME
```
(Then pick a theme in Gboard settings.)

## 8. Remove the stock clutter (optional)

You can disable IKKO's native apps for the current user without root:
```bash
adb shell pm uninstall --user 0 com.ikkoaudio.pomodoro_clock   # native timer (replaced by com.mw.focus)
```
Leave the native **dialer/MMS/InCallService** in place — they own incoming calls & SMS, and our launcher's
badges read their call-log / SMS state. (Our `com.mw.dialer` is dial-out only.)

## 9. Custom dot icons (optional)

`tools/make_icon.py` and the ImageMagick recipe in each app's README generate the dotted glyphs (music ♪,
video ▶, phone, envelope, hourglass) by rasterizing a vector silhouette onto a dot grid. Re-tint / re-shape as
you like.

---

### Hardware notes
- **Physical edge bar:** the off-screen capacitive strip reports as touchscreen events at screen **x ≈ 255–341,
  full height**. The launcher maps a deliberate vertical drag there to the Volume/Media/Brightness rail.
- **Screen:** 368×448 @ density 1.0 — design at **watch scale**, not phone-MD3 (big text, finger targets).

---
Part of **ikko-ab02** · https://github.com/vitkovit/ikko-ab02
