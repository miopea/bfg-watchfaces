/**
 * Shotcraft — Play Store screenshots for BFG Watch Faces.
 *
 * ## Render only, on purpose
 *
 * Shotcraft's capture phase drives a running WEB app through Playwright. This
 * is an ANDROID app, and the screens worth showing — a dial redrawing as a
 * slider moves, a face on a wrist — only exist there. So the raws come from the
 * device instead:
 *
 *   adb exec-out screencap -p            -> build/captures/
 *   ./gradlew :workbench:storeshots      -> build/store/    (1080x1920)
 *   copy into screenshots/raw/           -> {name}-play-store-phone-dark.png
 *   node <shotcraft>/packages/core/dist/cli/index.js render play-store-phone
 *
 * `storeshots` already pads to Play's 9:16 — a Pixel capture is 9:20 and gets
 * rejected outright — which is exactly the 1080x1920 the play-store-phone
 * template expects, so the raws drop straight in.
 *
 * ## Both themes, and the dark/light choice is still open
 *
 * This said "dark only ... this app has one look", which was wrong: the app
 * follows the phone's theme, and light captures exist. Both are rendered so the
 * two sets can be compared side by side before one is chosen for the listing.
 *
 * PICK ONE. A listing that mixes them reads as five screenshots of two apps.
 *
 * The light raws currently come from the bfg-solutions site captures, which are
 * 760x1593 and padded up to 1080x1920 — there is no light capture of My faces,
 * so `04-myfaces` renders dark only. A missing raw renders nothing and says
 * nothing, which is why that is written down here rather than discovered.
 */
import { defineConfig } from "shotcraft";

export default defineConfig({
  // Never fetched: the render phase reads raws from disk. Present because the
  // config type wants a target, and pointing it at the workbench is at least
  // honest about where a web capture WOULD come from.
  target: "http://localhost:7777",

  templates: [{ pkg: "@shotcraft/template-play-store-phone", themes: ["dark", "light"] }],

  /**
   * Five screens, in the order somebody meets the app.
   *
   * Captions say what the app DOES rather than naming the screen — "Designs"
   * on a picture of designs is a label, not a reason to install.
   */
  screens: [
    {
      route: "/",
      name: "01-styles",
      caption: "Twelve engine-turned dials, ready to wear",
    },
    {
      route: "/",
      name: "02-variety",
      caption: "Guilloché, brushed metal, linen — or your own photo",
    },
    {
      route: "/",
      name: "03-studio",
      caption: "Every dial redraws as you move a slider",
    },
    {
      route: "/",
      name: "04-myfaces",
      caption: "Name a face and send it straight to your watch",
    },
    {
      route: "/",
      name: "05-free",
      caption: "Free. No ads, no account, no subscription.",
    },
  ],
});
