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
# SETUP — ONE line in the host you already use. No second connection.
#
#     Host bgsdev
#         ... everything you already have ...
#         RemoteForward 5038 localhost:5037
#
# Your existing command is unchanged:
#
#     ssh -N bgsdev -i C:\temp\swarm_key.pem
#
# This is safe to sit alongside ExitOnForwardFailure yes. That option fires when
# a forward cannot be ESTABLISHED, which for RemoteForward means binding 5038
# here. The far end — your adb server — is contacted lazily, per connection, so
# the forward comes up whether or not adb is running on your machine, and you
# can plug a watch in later without reconnecting.
#
# (Reasoned from ssh's semantics, not observed: this box has no key to ssh to
# itself with, so the case could not be run here.)
#
# Two details that are not arbitrary:
#
#   RemoteForward, not LocalForward. Every other line in that config is you
#   reaching a service that runs HERE. This one goes the other way.
#
#   5038, not 5037. Any bare `adb` command on this box starts a daemon on 5037,
#   and a RemoteForward onto an occupied port under ExitOnForwardFailure drops
#   the WHOLE session — every app port with it. Nothing here binds 5038, and
#   this script deliberately never starts a daemon on it either: it checks
#   whether the bridge is up BEFORE invoking adb, precisely so a stray command
#   cannot squat the port and lock you out on the next reconnect.
#
# On the watch machine, nothing to run by hand — plugging in a watch, opening
# Android Studio or starting an emulator all start adb for you. Then here:
#
#     export ADB_SERVER_SOCKET=tcp:localhost:5038
#     scripts/remote-adb.sh
#
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

# Something listening on 5038 only proves SSH bound the port. It says nothing
# about whether the far end answers -- ssh accepts the connection either way and
# only then tries to reach your adb server. If that fails, adb reports
# "protocol fault (couldn't read status)", which reads like a broken adb and is
# actually "the other machine is not running one".
if ! python3 - "$PORT" <<'PROBE'
import socket, sys
port = int(sys.argv[1])
req = b"host:version"
try:
    s = socket.create_connection(("127.0.0.1", port), timeout=6)
    s.sendall(b"%04x%s" % (len(req), req))
    sys.exit(0 if s.recv(64) else 1)
except Exception:
    sys.exit(1)
PROBE
then
  echo
  echo "The tunnel is up, but nothing answered on the other side." >&2
  echo "SSH forwarded the connection and your machine refused it, which means" >&2
  echo "no adb server is running there." >&2
  echo >&2
  echo "On the machine with the watch, run:" >&2
  echo "    adb devices" >&2
  echo >&2
  echo "That starts the server. Plugging in a watch, opening Android Studio or" >&2
  echo "starting an emulator does it too. No need to reconnect the tunnel." >&2
  exit 3
fi
echo "far end           : answering"
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
