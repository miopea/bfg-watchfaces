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
