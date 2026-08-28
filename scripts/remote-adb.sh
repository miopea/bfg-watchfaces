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
# SETUP — a SEPARATE ssh host, used only while a watch is plugged in.
#
# Everything else in a normal config is LocalForward: you reaching services that
# run HERE. This is the one that goes the other way, and it is only live while
# there is a watch on your machine. Keeping it apart means a dead adb forward
# cannot take your app ports down with it.
#
#     Host bgsdev-watch
#         HostName 10.2.0.4
#         User bschleifer
#         RemoteForward 5038 localhost:5037
#         # deliberately NO ExitOnForwardFailure: if adb is not running on your
#         # side, the session should still come up rather than refusing to
#         # connect at all.
#
# Then, on the machine with the watch:
#
#     adb devices            # starts the local adb server; do this FIRST
#     ssh -N bgsdev-watch -i C:\temp\swarm_key.pem
#
# And here:
#
#     export ADB_SERVER_SOCKET=tcp:localhost:5038
#     scripts/remote-adb.sh
#
# Two details that are not arbitrary:
#
#   RemoteForward, not LocalForward. LocalForward listens on YOUR machine and
#   reaches this one — the wrong way round. RemoteForward listens HERE.
#
#   5038, not 5037. Any bare `adb` command on this box grabs 5037. If that
#   collides while ExitOnForwardFailure is set, ssh drops the WHOLE session
#   rather than just the one forward — which is why this lives on its own host
#   and its own port.
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
