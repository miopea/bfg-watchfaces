"""One supervised moderation run. Credentials are resolved only into memory."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import urllib.request

root = Path(__file__).resolve().parents[1]
os.chdir(root)
environment = dict(os.environ)
if subprocess.run(["op", "whoami"], env=environment, capture_output=True).returncode:
    helper = subprocess.run([str(Path.home() / ".local/bin/op-login")], capture_output=True, text=True)
    if helper.returncode:
        sys.exit("Unable to initialize the personal-project credential context.")
    credential = helper.stdout.strip()
    if credential.startswith("export "):
        credential = next((part.split("=", 1)[1] for part in shlex.split(credential) if part.startswith("OP_SERVICE_ACCOUNT_TOKEN=")), "")
    environment["OP_SERVICE_ACCOUNT_TOKEN"] = credential
secret = subprocess.run(["op", "read", "op://BFG/bfg-catalog-moderator-token/credential"], env=environment, capture_output=True, text=True)
if secret.returncode:
    sys.exit("Unable to resolve the moderation credential.")
token = secret.stdout.strip()
base = environment.get("BFG_CATALOG_URL", "https://bfg-catalog.bfg-solutions.workers.dev").rstrip("/")
environment.update(BFG_CATALOG_URL=base, BFG_MODERATOR_TOKEN=token)
success = False
try:
    result = subprocess.run(["./gradlew", ":workbench:moderate", "--no-daemon", "--console=plain"], env=environment, timeout=220)
    success = result.returncode == 0
except subprocess.TimeoutExpired:
    print("Moderation run exceeded its time limit.", file=sys.stderr)
request = urllib.request.Request(base + "/admin/processing/heartbeat",
    data=json.dumps({"success": success}).encode(),
    headers={"Authorization": "Bearer " + token, "Content-Type": "application/json", "User-Agent": "BFG-Moderation-Runner/1.0"})
try:
    with urllib.request.urlopen(request, timeout=15) as response:
        if response.status != 200:
            success = False
except Exception:
    print("Could not report the moderation runner heartbeat.", file=sys.stderr)
    success = False
sys.exit(0 if success else 1)
