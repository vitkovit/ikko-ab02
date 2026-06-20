# Fonts

The apps were designed around the **Nothing OS dot-matrix look**. Those fonts are **proprietary** and are
**deliberately NOT included** in this repo (their license forbids redistribution). The apps still build and run
without them — they fall back to the system font — but to get the intended dotted aesthetic you must supply a
dot-matrix font yourself.

## What the apps expect

| App | Path to drop the font | Original (proprietary) |
|-----|-----------------------|------------------------|
| launcher | `launcher-app/app/src/main/assets/ndot57.otf` | Ndot 57 (Nothing/Colophon) — clock + UI |
| launcher | `launcher-app/app/src/main/assets/clock.otf`  | Ndot 57 (Nothing/Colophon) — clock |
| launcher | `launcher-app/app/src/main/assets/body.otf`   | NType 82 (Nothing/Colophon) — body text |
| launcher | `launcher-app/app/src/main/assets/offbit.ttf` | OffBit (Power Type) — accents |
| dialer   | `dialer-app/app/src/main/res/font/ndot57.otf` | Ndot 57 (Nothing/Colophon) |

The loaders all catch a missing file and return `Typeface.DEFAULT`, so nothing crashes if a font is absent.

## Recommended open-source replacement (SIL OFL)

Any dot-matrix / pixel font works. Good freely-licensed options:

- **DotGothic16** — SIL Open Font License — https://fonts.google.com/specimen/DotGothic16
- **Pixelify Sans** — SIL OFL — https://fonts.google.com/specimen/Pixelify+Sans
- **Departure Mono** — MIT — https://departuremono.com/

Download one, then copy it to each path above using the original filenames (e.g. save DotGothic16 as
`ndot57.otf` / `clock.otf` / `body.otf`). `*.otf` and `*.ttf` are gitignored so your fonts will never be
committed.

> If you own a legitimate license to the Nothing fonts you may use those locally — just don't commit them.
