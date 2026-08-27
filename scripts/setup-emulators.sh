#!/usr/bin/env bash
# Create and pair a phone + Wear OS emulator pair.
#
# If you are coming from Capacitor, this is the unfamiliar part: the Wear
# emulator is a SEPARATE device that must be PAIRED with the phone before the
# Data Layer works at all. Without pairing, CapabilityClient finds nothing and
# every phone->watch call silently no-ops with no error. You will burn an
# afternoon debugging your own correct code.
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME}"
SDK="$ANDROID_HOME"
PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"

PHONE_IMG="system-images;android-34;google_apis_playstore;x86_64"

# API 36 is Wear OS 6, and Watch Face Push is Wear OS 6+ ONLY. An android-34
# image is Wear OS 5: a WFF face still sideloads and appears in the carousel, so
# it is fine for step one, but WatchFacePushManager does not exist there and the
# feature this whole project is built on cannot be exercised at all. This used
# to pin android-34, which quietly capped the emulator below the product.
#
# "-signed" is the variant that carries Google Play services. Override to test
# the sideload path on an older platform:
#   WEAR_IMG="system-images;android-34;android-wear;x86_64" scripts/setup-emulators.sh
WEAR_IMG="${WEAR_IMG:-system-images;android-36;android-wear-signed;x86_64}"

echo "==> installing system images (slow the first time)"
sdkmanager --install "$PHONE_IMG" "$WEAR_IMG" "platform-tools" "emulator"

echo "==> creating AVDs"
echo no | avdmanager create avd -n ss_phone -k "$PHONE_IMG" -d pixel_7 --force
echo no | avdmanager create avd -n ss_watch -k "$WEAR_IMG"  -d wearos_large_round --force

cat <<'EOF'

AVDs created: ss_phone, ss_watch

Start them in two terminals:

    emulator -avd ss_phone
    emulator -avd ss_watch

Then PAIR them. This step is manual and is not optional:

  1. Android Studio > Device Manager > ss_watch > overflow > "Pair Wearable"
  2. Or from the command line, forward the watch's pairing port to the phone:

       adb -s <phone-serial> forward tcp:5601 tcp:5601

     then open the Wear OS companion app on the phone emulator and pair to
     "Emulator" over localhost.

Verify pairing BEFORE writing any Data Layer code:

    adb -s <watch-serial> shell dumpsys activity service WearableService | grep -i peer

If no peer is listed, pairing did not take. Fix that first.

CAVEAT: the emulator cannot exercise Watch Face Push end to end -- it depends
on the Watch Face Push system app being present. Use the emulator for UI and
Data Layer work; use a real Pixel Watch for install and activation.
EOF
