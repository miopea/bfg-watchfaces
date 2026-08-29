#!/usr/bin/env bash
# Build libpack_java.so for Android, from the same pinned and PATCHED pack that
# scripts/build-pack.sh uses for the desktop CLI.
#
# WHY NOT ANDROIDIFY'S PREBUILT .so
#
# Google's Androidify sample ships one for all four ABIs and it is tempting.
# Two reasons not to, and the second is the one that bites:
#
#   1. build-pack.sh already rejected scavenging them -- "unversioned binaries
#      nobody here can audit". Pointing at a device does not change that.
#   2. Theirs is built from UNPATCHED pack. This repo carries
#      scripts/pack-qualifiers.patch; without it res/drawable-nodpi is recorded
#      as density 0, which means mdpi rather than "unspecified", and the watch
#      scales a dial image that explicitly says do not scale me. That bug was
#      found and fixed here on 2026-08-28 (commit 0c56aa1). Using their binary
#      would silently reintroduce it on the device path only, which is the
#      hardest place to notice it.
#
# Building it ourselves is also what puts the JNI symbol in OUR package: the
# symbol name encodes the Java package, so a prebuilt .so forces the binding to
# live in com.android.developers.androidify.
#
# PREREQUISITES -- none of these are installed by this script
#
#   rustup + cargo          https://rustup.rs
#   cargo-ndk               cargo install cargo-ndk
#   Android NDK             sdkmanager "ndk;27.2.12479018"
#   protoc                  pack-aab generates code from .proto
#
# Produces: mobile/src/main/jniLibs/<abi>/libpack_java.so  (gitignored)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
WORK="${PACK_SRC_DIR:-$ROOT/build/pack-src}"
OUT="$ROOT/mobile/src/main/jniLibs"

# The four ABIs Wear OS and its emulators actually run. armeabi-v7a is still
# here because some shipping watches are 32-bit.
ABIS="arm64-v8a armeabi-v7a x86_64 x86"

missing=""
command -v cargo >/dev/null || missing="$missing cargo"
command -v protoc >/dev/null || [ -n "${PROTOC:-}" ] || missing="$missing protoc"
cargo ndk --version >/dev/null 2>&1 || missing="$missing cargo-ndk"
if [ -n "$missing" ]; then
  echo "ERROR: missing toolchain:$missing" >&2
  echo "  rustup:    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh" >&2
  echo "  cargo-ndk: cargo install cargo-ndk" >&2
  echo "  protoc:    apt-get install protobuf-compiler" >&2
  exit 1
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ] || {
  echo "ERROR: no Android NDK. Install one:" >&2
  echo "  \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'ndk;27.2.12479018'" >&2
  exit 1
}
export ANDROID_NDK_HOME
echo "NDK: $ANDROID_NDK_HOME"

# The desktop script owns the checkout and the patch. Reuse it rather than
# cloning a second copy that could drift to a different commit -- two pack
# checkouts at two revisions is exactly the failure the pin exists to prevent.
if [ ! -d "$WORK/pack-api" ]; then
  echo "no patched pack checkout at $WORK; running build-pack.sh first"
  "$ROOT/scripts/build-pack.sh"
fi
grep -q "fn resource_type" "$WORK/pack-api/src/res_dir.rs" 2>/dev/null || \
  grep -rq "resource_type" "$WORK/pack-api/src" 2>/dev/null || {
    echo "WARNING: the qualifier patch does not look applied in $WORK." >&2
    echo "  Delete it and re-run scripts/build-pack.sh." >&2
    exit 1
  }

for abi in $ABIS; do
  echo "building $abi..."
  ( cd "$ROOT/scripts/pack-java" && cargo ndk -t "$abi" -o "$OUT" build --release )
done

echo
for abi in $ABIS; do
  f="$OUT/$abi/libpack_java.so"
  [ -f "$f" ] && printf "  %-12s %s bytes\n" "$abi" "$(stat -c%s "$f")" || {
    echo "  $abi MISSING" >&2; exit 1; }
done
echo
echo "Built from pack $(cd "$WORK" && git rev-parse --short HEAD) + pack-qualifiers.patch."
echo "These are gitignored: they are build output, not source."
