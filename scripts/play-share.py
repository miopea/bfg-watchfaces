#!/usr/bin/env python3
"""Put a build on a device NOW, without waiting for a review.

Why this exists
---------------
On 2026-09-20 the phone app grew a Health Connect permission, and Play refused
every upload to the internal testing track with:

    403  You must let us know whether your app includes any health features.

That is Google gating a TESTING track behind a declaration review. The operator
could not test the feature that the declaration describes until the declaration
describing it was accepted, which is a loop with nothing in it.

Internal app sharing is the way out. It takes a bundle, skips review entirely,
and hands back a link that installs on any device signed in as an authorised
tester. It is Google's own answer to "I need this on a phone right now".

Why not just sideload
---------------------
Because the obvious alternatives do not work here:

- `adb install -r` of a locally signed release APK fails on signature mismatch.
  Play App Signing means the installed app is signed with the APP signing key,
  and anything built here is signed with the UPLOAD key.
- A debug build with an applicationIdSuffix installs alongside, but then the
  phone and watch packages no longer match, and the Wear Data Layer will not
  deliver between them. That breaks the exact feature being tested.

Usage
-----
    scripts/play-share.py --bundle mobile/build/outputs/bundle/release/mobile-release.aab

Prints a URL. Open it on the phone, signed in as a tester on the account.

The link installs a build that is NOT on any track. It does not touch
production, does not touch internal testing, and cannot start or restart a
review -- which is the whole point.
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
ITEM = "bfg-watchfaces-play-publisher"
VAULT = "BFG"


def service_account() -> dict:
    """The publisher key, from 1Password. Never on disk, never in argv."""
    # Same route play-release.py uses: it is a DOCUMENT, not a field.
    r = subprocess.run(["op", "document", "get", ITEM, "--vault", VAULT],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("could not read %s from 1Password; run: eval \"$(op-login)\"" % ITEM)
    return json.loads(r.stdout)


def access_token(sa: dict) -> str:
    now = int(time.time())
    assertion = jwt.encode(
        {"iss": sa["client_email"], "scope": SCOPE, "aud": sa["token_uri"],
         "iat": now, "exp": now + 3600},
        sa["private_key"], algorithm="RS256",
        headers={"kid": sa["private_key_id"]})
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion}).encode()
    with urllib.request.urlopen(urllib.request.Request(sa["token_uri"], data=body)) as r:
        return json.load(r)["access_token"]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--package", default="com.bfg.watchfaces")
    p.add_argument("--bundle", required=True, help="path to an .aab")
    a = p.parse_args()

    tok = access_token(service_account())
    data = open(a.bundle, "rb").read()
    print("uploading %s (%.1f MB) to internal app sharing..." % (
        a.bundle.split("/")[-1], len(data) / 1e6))

    url = "%s/applications/internalappsharing/%s/artifacts/bundle?uploadType=media" % (
        UPLOAD, a.package)
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Authorization": "Bearer " + tok,
                 "Content-Type": "application/octet-stream"})
    try:
        # Generous: a 17MB bundle over a slow link, plus Play's own processing.
        with urllib.request.urlopen(req, timeout=300) as r:
            out = json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit("\nupload refused\nHTTP %s\n%s" % (e.code, e.read().decode()[:900]))

    print("\n  certificate sha256: %s" % out.get("certificateSha256Hash", "?"))
    print("\nOpen this on the phone, signed in as a tester:\n")
    print("  %s\n" % out.get("downloadUrl"))
    print("It is not on any track. Nothing was submitted and no review started.")


if __name__ == "__main__":
    main()
