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
PWD_ROOT="$PWD"
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

# Pinned. The patch below is against this exact commit, and an unpinned clone
# would silently start failing to apply -- or worse, apply to something that has
# moved underneath it.
PACK_COMMIT="${PACK_COMMIT:-7b60931e4058}"

mkdir -p "$OUT"
if [ ! -d "$WORK/.git" ]; then
  echo "cloning google/pack..."
  git clone https://github.com/google/pack.git "$WORK"
else
  echo "using existing checkout: $WORK"
fi
( cd "$WORK" && git checkout -q "$PACK_COMMIT" 2>/dev/null || {
    git fetch -q origin && git checkout -q "$PACK_COMMIT"; } )

# Resource qualifiers. pack treats a res subdirectory name as the resource TYPE,
# so `res/drawable-nodpi/` becomes a type called `drawable-nodpi` and
# `@drawable/preview` cannot be resolved. Flattening the directory instead would
# drop `nodpi`, which is the instruction NOT to density-scale the dial -- a
# 456x456 dial scaled 2x is several times the per-frame memory.
#
# This teaches it that `<type>-<qualifiers>` is a type plus a configuration, and
# writes the density into ResTable_config. Verified: aapt2 reads (nodpi) back
# out of the result, and the resource table matches aapt2's own byte for byte in
# type ids and entry ids.
#
# Not upstreamed. If it ever is, this file loses the patch and keeps the pin.
if ! ( cd "$WORK" && git diff --quiet ); then
  echo "checkout already patched; resetting"
  ( cd "$WORK" && git checkout -q -- . )
fi
echo "applying scripts/pack-qualifiers.patch..."
( cd "$WORK" && git apply "$PWD_ROOT/scripts/pack-qualifiers.patch" ) || {
  echo "ERROR: the qualifier patch did not apply to pack@$PACK_COMMIT." >&2
  echo "  pack has probably moved. Re-derive it, or unset the pin deliberately." >&2
  exit 1
}

echo "building pack-cli (release)..."
( cd "$WORK" && cargo build --release -p pack-cli )

cp "$WORK/target/release/pack-cli" "$OUT/pack-cli"
echo "built: build/pack-cli"
"$OUT/pack-cli" 2>&1 | head -1 || true
