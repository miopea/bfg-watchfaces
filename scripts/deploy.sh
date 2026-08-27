#!/usr/bin/env bash
# Build the reference watch face and install it on every connected device.
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME}"
cd "$(dirname "$0")/.."

./gradlew :generator:test
( cd watchface-template && ./build.sh )

APK="watchface-template/build/silver-sand.apk"
ADB="$ANDROID_HOME/platform-tools/adb"
DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
[ -z "$DEVICES" ] && { echo "no devices. adb pair / adb connect first."; exit 1; }

for d in $DEVICES; do
  echo "==> $d"
  "$ADB" -s "$d" install -r "$APK"
done

cat <<'EOF'

Installed. On the watch: long-press the current face, scroll to "Silver Sand"
(the default face; the app is BFG Watch Faces).

If it does NOT appear but install reported Success, the WFF XML is almost
certainly schema-invalid. That failure is silent by design. Run:

    ./gradlew :generator:test --tests '*WffSchema*'
EOF
