# Play store screenshots

Captured from `:mobile` running on `bfg_phone_local` (SDK 36 phone emulator) on
2026-08-29, at 1080x1920.

Two things that are not obvious and cost a retake each:

- **The emulator is 720x1600, which Play rejects.** That is a 2.22:1 ratio and
  Play's limit is 2:1. `adb shell wm size 1080x1920` plus `wm density 420`
  overrides the display, so the captures are native 16:9 rather than a
  letterboxed 720-wide image scaled up. Reset both afterwards.
- **The status bar is SystemUI demo mode**, not the emulator's own. Without it
  every shot carries the emulator's clock, a broken-wifi glyph and whatever
  notification icons happen to be there. The clock is set to 10:10 to match the
  time on the dial preview.

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1010
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
# ... capture ...
adb shell am broadcast -a com.android.systemui.demo -e command exit
adb shell wm size reset && adb shell wm density reset
```

The order is the order Play shows them: the studio first, colour second,
because those are the two a person sees before deciding.
