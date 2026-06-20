# dialer-app (`com.mw.dialer`)

A minimal, watch-scaled **dialer / caller** in the dot-matrix theme. Dial-out only — incoming calls stay with
the device's native dialer/InCallService.

## Features
- Bottom nav **Recent · Contacts · Dial** (lands on Recent), scaled for the watch (92dp bar, 40dp icons,
  19sp labels).
- **Contacts** with an alphabet scrubber (`AlphaSlider`), search, and **vCard import** (Contacts tab → Import).
- **Recents** call log with direction glyphs; tap to call.
- **Dial** pad with large keys.
- **Missed-call dot** on the Recent tab when there are unacknowledged missed calls; opening Recent marks them
  seen (clears the dot here and the launcher badge).

## Build & install
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Permissions
Grant: `READ_CONTACTS CALL_PHONE READ_CALL_LOG WRITE_CALL_LOG` (it also requests these at first launch).

## Fonts
Expects `app/src/main/res/font/ndot57.otf` — **not shipped** ([FONTS.md](../FONTS.md)); falls back to system.

## Contacts
Import a `.vcf` (see [SETUP](../SETUP.md) §4). The vCard is **private** — never commit it (gitignored).

---
Part of **ikko-ab02** · https://github.com/vitkovit/ikko-ab02
