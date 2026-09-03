# Play Store screenshots

Rendered by [Shotcraft](https://github.com/miopea/shotcraft) from real device
captures. The config is checked in; the images are not.

## Why render-only

Shotcraft's capture phase drives a running **web** app through Playwright. This
is an Android app, and the things worth showing — a dial redrawing under a
slider, a mascot face, the gallery — only exist on the device. So the raws come
from a phone and only the compositing half of Shotcraft is used.

## Regenerating

```bash
# 1. Capture from a connected phone, one PNG per screen.
ADB_SERVER_SOCKET=tcp:localhost:5038 \
  $ANDROID_HOME/platform-tools/adb -s <device> exec-out screencap -p \
  > build/captures/1-designs.png          # repeat per screen

# 2. Pad to Play's aspect ratio. A Pixel capture is 9:20 and Play's limit is
#    9:16, so a raw screenshot is rejected outright.
./gradlew :workbench:storeshots           # build/captures -> build/store (1080x1920)

# 3. Name them the way the render phase expects, and composite.
cp build/store/1-designs.png \
   tools/screenshots/screenshots/raw/01-styles-play-store-phone-dark.png
cd tools/screenshots && pnpm install && pnpm render
```

Output: `tools/screenshots/screenshots/play-store-phone/*-dark.png`, 1080×1920,
ready to upload.

## Capturing a light set (2026-09-03)

The app follows the phone's theme, so a light set is a real option and the
dials read harder against white than against the dark ground — on dark, Carbon
Black and Knotwork Graphite half-disappear into the background.

Three things that are not obvious:

**Turn the status bar into a prop, or crop it.** In dark mode the status bar is
invisible and nobody noticed it. In light mode it shows the tester's own
notification icons. `adb shell cmd uimode night no` then SystemUI demo mode
(`settings put global sysui_demo_allowed 1`, broadcast `com.android.systemui.demo`)
sets a 10:10 clock and a full battery — which matches the 10:10 on every dial —
but `notifications -e visible false` did NOT take on this Pixel, so the icons
survived. Cropping the top 130px is what actually worked. The status bar is the
one thing safe to crop: it is the phone's, not the app's.

**Fill My faces before you shoot it.** With two saved faces the screen is 60%
empty and sells nothing. Five, named the way a person names things — Trail Day,
Board Room, Night Shift — shows the app being used. Save them through the real
flow (Designs -> tap a style -> Save to My faces), because a face saved any
other way will not have a preview thumbnail.

**Frame the gallery on a row boundary.** A mid-scroll capture clips a row of
dials top AND bottom and looks like a mistake. Scroll so a row starts just under
the tab bar.

## Two things that will bite

**The raw filename is the contract.** It must be
`{name}-{template-id}-{theme}.png`, and `{name}` must match a `screens[].name`
in `shotcraft.config.ts`. A mismatch renders nothing and reports no error worth
reading.

**Shotcraft pins its own Playwright browser build.** If `render` fails with
"Executable doesn't exist at .../chromium_headless_shell-NNNN", install that
exact build using Shotcraft's OWN Playwright, not whatever is on PATH:

```bash
cd ~/projects/personal/shotcraft
node node_modules/.pnpm/playwright@X.Y.Z/node_modules/playwright/cli.js \
  install chromium-headless-shell
```
