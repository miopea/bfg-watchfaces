#!/usr/bin/env bash
# Re-skin a built watch face APK WITHOUT recompiling resources.
#
# Why this works: resources.arsc stores resource PATHS, not bytes. The dial PNG
# and res/raw/watchface.xml are ordinary zip entries -- raw XML is not even
# compiled to binary. Swap them, re-sign, done.
#
# This is the shell proof of the on-device pipeline. On Android the same idea
# runs via google/pack (which can also vary the package name, unlike this
# script) plus apksig, with no build server anywhere.
#
#   ./reskin.sh <template.apk> <new_dial_bg.png> <new_watchface.xml> <out.apk>
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME}"
BT="$ANDROID_HOME/build-tools/34.0.0"
TPL="$1"; NEWPNG="$2"; NEWXML="$3"; OUT="$4"
HERE="$(cd "$(dirname "$0")" && pwd)"

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
TPL_ABS="$(cd "$(dirname "$TPL")" && pwd)/$(basename "$TPL")"
( cd "$WORK" && unzip -qo "$TPL_ABS" && rm -rf META-INF )

# aapt2 appends -v4 to the density qualifier; find the real path, don't assume.
DIAL=$(cd "$WORK" && ls res/drawable-nodpi*/dial_bg.png)
cp "$NEWPNG" "$WORK/$DIAL"
cp "$NEWXML" "$WORK/res/raw/watchface.xml"

# Validate BEFORE signing. Push rejects invalid faces; catch it here.
if [ -d "$HERE/tools/wff-schema" ] && [ -f "$HERE/tools/V.class" ]; then
  java -cp "$HERE/tools:$(ls "$HERE"/tools/libs/*.jar | tr '\n' ':')" V \
       "$HERE/tools/wff-schema/watchface.xsd" "$WORK/res/raw/watchface.xml"
fi

# resources.arsc must be STORED and 4-byte aligned; add it uncompressed first.
( cd "$WORK" && zip -q -X -0 out.zip resources.arsc && zip -q -X -r out.zip AndroidManifest.xml res )
"$BT/zipalign" -f -p 4 "$WORK/out.zip" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$HERE/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias bfgwatchfaces --out "$OUT" "$WORK/aligned.apk"

echo "reskinned: $OUT"
"$BT/apksigner" verify "$OUT" && echo "signature ok"
BAD=$(unzip -l "$OUT" | awk 'NR>3 && NF>=4 {print $4}' | grep -v '^$' | grep -v '/$' \
      | grep -vE '^(AndroidManifest\.xml|resources\.arsc|res/|META-INF/)' || true)
[ -z "$BAD" ] && echo "contents ok (Push allowlist)" || { echo "DISALLOWED: $BAD"; exit 1; }
