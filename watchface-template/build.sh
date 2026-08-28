#!/usr/bin/env bash
# Build + sign the watch face.
#
# No Gradle deliberately. A WFF face has no code, so aapt2 is enough -- and AGP
# injects kotlin/ and DebugProbesKt.bin into the APK, which Google Play accepts
# but Watch Face Push REJECTS. Building with aapt2 directly produces exactly the
# four paths Push permits.
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
cd "$(dirname "$0")"

# Validate the WFF XML BEFORE building. This is not optional decoration: a
# schema-invalid face still compiles, links, signs, and installs, and then
# silently never appears in the carousel. There is no runtime error.
if [ -d tools/wff-schema ] && [ -f tools/V.class ] && command -v java >/dev/null; then
  java -cp "tools:$(ls tools/libs/*.jar | tr '\n' ':')" V \
       tools/wff-schema/watchface.xsd res/raw/watchface.xml
else
  echo "WARNING: validator not available. Run scripts/bootstrap.sh." >&2
fi

# The artwork is generated, not source, and is gitignored. Fail loudly and with
# a command that actually works if it is missing.
#
# preview.png matters as much as dial_bg.png: res/xml/watch_face_info.xml
# requires a <Preview>, and without the file aapt2 link fails on an unresolved
# @drawable/preview rather than on anything that names the real problem.
MISSING=""
for f in dial_bg preview; do
  [ -f "res/drawable-nodpi/$f.png" ] || MISSING="$MISSING res/drawable-nodpi/$f.png"
done
if [ -n "$MISSING" ]; then
  echo "ERROR: missing generated artwork:$MISSING" >&2
  echo >&2
  echo "Bake it from the parameters:" >&2
  echo "    ./gradlew :workbench:bake" >&2
  echo "    ./gradlew :workbench:bake --args=\"--preset=Rosette Noir\"" >&2
  echo >&2
  echo "Or design it interactively:" >&2
  echo "    ./gradlew :workbench:workbench   # then http://localhost:7777" >&2
  exit 1
fi

# ---- optional: build with google/pack instead of the Android SDK -------------
#
# PROVEN TO WORK, and deliberately NOT the default. See DECISIONS.md 2026-08-28.
#
# pack compiles and signs the APK with no SDK, no aapt2 and no Java, and it is
# the same library that will build faces on the device. Running it here is how
# that path gets exercised before any of it ships.
#
# It is opt-in because of one real difference: pack has no resource-qualifier
# support, so `res/drawable-nodpi/` has to become `res/drawable/`. That drops
# the instruction not to density-scale the dial, and a 456x456 dial scaled 2x on
# a high-density watch is 3.3MB a frame instead of 831KB. Nobody here can
# measure that without a watch, so aapt2 stays in charge of what ships.
#
#   scripts/build-pack.sh && USE_PACK=1 ./build.sh
if [ "${USE_PACK:-}" = "1" ]; then
  PACK="${PACK_BIN:-../build/pack-cli}"
  [ -x "$PACK" ] || { echo "ERROR: $PACK not found. Run scripts/build-pack.sh first." >&2; exit 1; }

  FACE_SLUG="${FACE_SLUG:-watchface}"
  rm -rf build/pack && mkdir -p build/pack/res
  cp AndroidManifest.xml build/pack/
  # Flatten the density qualifier, which is exactly the caveat above.
  for d in res/*/; do
    name=$(basename "$d")
    cp -r "$d" "build/pack/res/${name%%-*}"
  done

  "$PACK" build/pack "build/$FACE_SLUG"
  rm -f "build/$FACE_SLUG.aab"        # Push takes an APK; the bundle is for Play
  echo "built with pack: build/$FACE_SLUG.apk"

  BAD=$(unzip -l "build/$FACE_SLUG.apk" | awk 'NR>3 && NF>=4 {print $4}' | grep -v '^$' | grep -v '/$' \
        | grep -vE '^(AndroidManifest\.xml|resources\.arsc|res/|META-INF/)' || true)
  [ -z "$BAD" ] && echo "contents ok (Push allowlist)" || { echo "DISALLOWED: $BAD"; exit 1; }
  exit 0
fi

rm -rf build && mkdir -p build/compiled
"$BT/aapt2" compile --dir res -o build/compiled/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 33 --target-sdk-version 34 \
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}" \
  build/compiled/res.zip

# Throwaway dev key. Keep it: same key means `adb install -r` upgrades in place,
# a different key means uninstall-first on every iteration.
[ -f debug.keystore ] || keytool -genkeypair -v -keystore debug.keystore \
  -storepass android -keypass android -alias bfgwatchfaces -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=BFG Watch Faces, O=BFG, C=US"

"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
# The APK is named after the design being built. FACE_SLUG is set by the
# workbench; the default keeps a bare ./build.sh working on its own.
FACE_SLUG="${FACE_SLUG:-watchface}"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias bfgwatchfaces --out "build/$FACE_SLUG.apk" build/aligned.apk
rm -f build/unsigned.apk build/aligned.apk

echo "built: build/$FACE_SLUG.apk"

# Confirm nothing outside the Watch Face Push allowlist crept in.
BAD=$(unzip -l "build/$FACE_SLUG.apk" | awk 'NR>3 && NF>=4 {print $4}' | grep -v '^$' | grep -v '/$' \
      | grep -vE '^(AndroidManifest\.xml|resources\.arsc|res/|META-INF/)' || true)
[ -z "$BAD" ] && echo "contents ok (Push allowlist)" || { echo "DISALLOWED: $BAD"; exit 1; }
