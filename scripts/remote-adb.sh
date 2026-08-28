#!/usr/bin/env bash
# Check the bridge to a watch or emulator running on ANOTHER machine.
#
# This box builds everything and can run nothing: /dev/kvm is unreachable from
# its user namespace (gid 993 is unmapped), x86_64 emulation refuses without it,
# and the arm64 escape is closed too — the emulator rejects a foreign
# architecture outright. See DECISIONS.md 2026-08-28.
#
# So the split is: BUILD HERE, RUN THERE. adb is already a client/server
# protocol over TCP, so one SSH reverse forward is the entire bridge.
#
# IN YOUR ~/.ssh/config, on the machine with the watch:
#
#     RemoteForward 5038 localhost:5037
#
# RemoteForward, not LocalForward. LocalForward would listen on YOUR machine and
# reach this one — the wrong way round. RemoteForward listens HERE and reaches
# your adb server.
#
# And 5038 here rather than 5037, on purpose. Any bare `adb` command run on this
# box starts a daemon on 5037; with ExitOnForwardFailure yes, a RemoteForward
# onto an occupied port does not merely fail that forward, it drops the WHOLE
# session — every app port with it. 5038 is nothing else's default, so the
# bridge cannot be knocked over by an unrelated adb command.
#
# Then here:
#
#     export ADB_SERVER_SOCKET=tcp:localhost:5038
#     scripts/remote-adb.sh
#
# Everything after that — install, shell, screencap — targets whatever is
# plugged into or emulated on your machine.
set -euo pipefail
cd "$(dirname "$0")/.."

SOCKET="${ADB_SERVER_SOCKET:-tcp:localhost:5038}"
PORT="${SOCKET##*:}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
[ -x "$ADB" ] || { echo "ERROR: adb not found at $ADB" >&2; exit 1; }

echo "adb server socket : $SOCKET"

# The footgun this script exists for. If the forward is NOT up, adb quietly
# starts its own empty daemon on that port and reports "no devices" — which
# looks identical to "your watch is not plugged in" and is not. Check whether
# something was already listening BEFORE running adb.
if ss -ltn 2>/dev/null | grep -q ":$PORT\b"; then
  BRIDGE="something is listening on $PORT"
else
  echo
  echo "NOTHING is listening on port $PORT." >&2
  echo "The reverse forward is not up, so adb would start an empty local" >&2
  echo "daemon and report 'no devices' — which is not the same thing." >&2
  echo >&2
  echo "Add to ~/.ssh/config on the machine with the watch, then reconnect:" >&2
  echo "    RemoteForward $PORT localhost:5037" >&2
  exit 1
fi
echo "bridge            : $BRIDGE"
echo

ADB_SERVER_SOCKET="$SOCKET" "$ADB" devices -l | sed 's/^/  /'
echo

COUNT=$(ADB_SERVER_SOCKET="$SOCKET" "$ADB" devices | grep -cE "\sdevice$" || true)
if [ "$COUNT" -eq 0 ]; then
  echo "Bridge is up, but no device is attached on the other side." >&2
  echo "Start a Wear OS 6 emulator there, or plug in a watch with adb debugging on." >&2
  exit 2
fi

echo "$COUNT device(s) reachable. To put a face on one:"
echo
echo "    cd watchface-template && ./build.sh"
echo "    ADB_SERVER_SOCKET=$SOCKET \\"
echo "      \$ANDROID_HOME/platform-tools/adb install -r build/watchface.apk"
echo
echo "Then open the watch face picker and look for it by name. That single"
echo "observation is what docs/SPEC.md calls step one, and nothing in this repo"
echo "has ever been able to make it."
