#!/usr/bin/env bash
# Build the reference watch face and install it on every connected device.
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME}"
cd "$(dirname "$0")/.."

./gradlew :generator:test
( cd watchface-template && ./build.sh )

# The APK is named after the design. Take whichever one build.sh just made.
APK="$(ls -t watchface-template/build/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo "no APK in watchface-template/build -- run ./gradlew :workbench:bake first"; exit 1; }
ADB="$ANDROID_HOME/platform-tools/adb"
DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
[ -z "$DEVICES" ] && { echo "no devices. adb pair / adb connect first."; exit 1; }

for d in $DEVICES; do
  echo "==> $d"
  "$ADB" -s "$d" install -r "$APK"
done

cat <<'EOF'

Installed. On the watch: long-press the current face and scroll to the name you
gave this design.

If it does NOT appear but install reported Success, the WFF XML is almost
certainly schema-invalid. That failure is silent by design. Run:

    ./gradlew :generator:test --tests '*WffSchema*'
EOF
