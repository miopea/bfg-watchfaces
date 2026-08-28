#!/usr/bin/env bash
# Build google/pack's CLI from source.
#
# pack compiles AND signs watch face APKs with no Android SDK, no aapt2, no
# Java. It is the same library the app will use to build faces ON THE DEVICE
# (docs/SPEC.md), so proving it here on the desktop de-risks that before any
# binary ships.
#
# There are no published releases and no prebuilt artifacts, so building from
# source is the normal path rather than an exotic one. The alternative floating
# around is scavenging .so files out of Google's Androidify sample app, which
# would mean shipping unversioned binaries nobody here can audit.
#
# Produces: build/pack-cli
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$PWD/build"
WORK="${PACK_SRC_DIR:-$OUT/pack-src}"

command -v cargo >/dev/null || {
  echo "ERROR: cargo not found. pack is a Rust project; install a Rust toolchain." >&2
  exit 1
}

# pack-aab generates code from .proto for the App Bundle format, so prost-build
# wants protoc even though we only care about the APK. Not optional: the
# workspace fails to build without it, with an error that names protoc and not
# the reason.
if [ -z "${PROTOC:-}" ] && ! command -v protoc >/dev/null; then
  echo "ERROR: protoc not found." >&2
  echo "  pack-aab needs it to compile the App Bundle protos." >&2
  echo "  Install protobuf-compiler, or download a release and set PROTOC:" >&2
  echo "    https://github.com/protocolbuffers/protobuf/releases" >&2
  exit 1
fi

mkdir -p "$OUT"
if [ ! -d "$WORK/.git" ]; then
  echo "cloning google/pack..."
  git clone --depth 1 https://github.com/google/pack.git "$WORK"
else
  echo "using existing checkout: $WORK"
fi

echo "building pack-cli (release)..."
( cd "$WORK" && cargo build --release -p pack-cli )

cp "$WORK/target/release/pack-cli" "$OUT/pack-cli"
echo "built: build/pack-cli"
"$OUT/pack-cli" 2>&1 | head -1 || true
