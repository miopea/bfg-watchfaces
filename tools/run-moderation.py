"""Run the prebuilt moderator using provisioned environment credentials only."""
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def run():
    environment = dict(os.environ)
    token = environment.get("BFG_MODERATOR_TOKEN", "")
    if not token.strip():
        print("Provision BFG_MODERATOR_TOKEN in the moderation service environment.", file=sys.stderr)
        return 1
    classpath_file = ROOT / "workbench/build/moderation-classpath.txt"
    if not classpath_file.is_file():
        print("Build the runner with :workbench:prepareModerationRunner before starting it.", file=sys.stderr)
        return 1
    classpath = classpath_file.read_text().strip()
    if not classpath or any(not Path(part).exists() for part in classpath.split(os.pathsep)):
        print("The prebuilt moderation classpath is incomplete; rebuild the runner.", file=sys.stderr)
        return 1
    base = environment.get("BFG_CATALOG_URL", "https://bfg-catalog.bfg-solutions.workers.dev").rstrip("/")
    environment["BFG_CATALOG_URL"] = base
    java = str(Path(environment["JAVA_HOME"]) / "bin/java") if environment.get("JAVA_HOME") else "java"
    success = False
    try:
        result = subprocess.run([java, "-cp", classpath, "com.bfg.watchfaces.workbench.Moderate"],
            cwd=ROOT, env=environment, timeout=220)
        success = result.returncode == 0
    except (subprocess.TimeoutExpired, OSError):
        print("Moderation could not finish; check the runner installation and processing status.", file=sys.stderr)
    request = urllib.request.Request(base + "/admin/processing/heartbeat",
        data=json.dumps({"success": success}).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json", "User-Agent": "BFG-Moderation-Runner/1.0"})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=15) as response:
            success = success and response.status == 200
    except Exception:
        print("Could not report the moderation runner heartbeat.", file=sys.stderr)
        success = False
    return 0 if success else 1

if __name__ == "__main__":
    sys.exit(run())
