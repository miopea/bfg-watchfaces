#!/usr/bin/env python3
"""Publish app bundles to a Google Play track, without a browser.

WHY THIS EXISTS
---------------
Uploading through the Play Console means a file picker on whichever machine the
browser runs on. This project builds on a headless Linux box and the console is
driven from a Windows laptop over a tunnel, so "just drag the AAB in" means
copying a 19MB artefact across a bridge and clicking through twenty controls,
every release. The Publisher API does the same thing in one command, from the
machine that built the bundle, and can run in CI later.

CREDENTIALS
-----------
The service account key is read from 1Password at run time and never written to
disk, never printed, and never committed:

    eval "$(op-login)"
    scripts/play-release.py --track internal \
        --bundle mobile/build/outputs/bundle/release/mobile-release.aab \
        --bundle wear/build/outputs/bundle/release/wear-release.aab

The account is play-publisher@budgetbug-495002.iam.gserviceaccount.com, which
holds "Release apps to testing tracks" on this app. It is deliberately NOT the
billing service account: those are separate identities on purpose.

WHAT IT DOES NOT DO
-------------------
It does not roll out to production, and there is no flag to. Production needs
the App content declarations, a store listing and a privacy policy, none of
which belong behind a script argument.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt  # PyJWT

API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
ITEM = "bfg-watchfaces-play-publisher"
VAULT = "BFG"


def service_account() -> dict:
    """Read the key from 1Password. Never cached, never written down."""
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


def call(token: str, method: str, url: str, body=None, content_type=None):
    headers = {"Authorization": "Bearer " + token}
    data = None
    if content_type:
        headers["Content-Type"] = content_type
        data = body
    elif body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:900]
        sys.exit("\n%s %s\nHTTP %s\n%s" % (method, url.split("?")[0], e.code, detail))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--package", default="com.bfg.watchfaces")
    # Play exposes a track per form factor. A Wear bundle CANNOT go on the phone
    # track: the commit fails with "requires the Wear OS system feature
    # android.hardware.type.watch. To publish this release on the current track,
    # remove this artifact." So phone and watch are two releases, not one.
    p.add_argument("--track", default="internal",
                   choices=["internal", "alpha", "beta",
                            "wear:internal", "wear:beta"],
                   help="production and wear:production are deliberately absent")
    p.add_argument("--bundle", action="append", required=True,
                   help="path to an .aab; repeat for phone + wear")
    p.add_argument("--notes", default=None, help="release notes for en-US")
    p.add_argument("--dry-run", action="store_true",
                   help="upload and stage, then DELETE the edit instead of committing")
    a = p.parse_args()

    for b in a.bundle:
        if not os.path.isfile(b):
            sys.exit("no such bundle: %s" % b)

    token = access_token(service_account())
    print("authenticated")

    edit = call(token, "POST", "%s/applications/%s/edits" % (API, a.package))
    edit_id = edit["id"]
    print("edit %s" % edit_id)

    version_codes = []
    for path in a.bundle:
        size = os.path.getsize(path)
        print("uploading %s (%.1f MB)..." % (os.path.basename(path), size / 1048576))
        with open(path, "rb") as fh:
            res = call(token, "POST",
                       "%s/applications/%s/edits/%s/bundles?uploadType=media"
                       % (UPLOAD, a.package, edit_id),
                       body=fh.read(), content_type="application/octet-stream")
        version_codes.append(str(res["versionCode"]))
        print("  versionCode %s  sha256=%s" % (res["versionCode"], res.get("sha256", "?")[:16]))

    release = {"versionCodes": version_codes, "status": "completed"}
    if a.notes:
        release["releaseNotes"] = [{"language": "en-US", "text": a.notes}]
    call(token, "PUT",
         "%s/applications/%s/edits/%s/tracks/%s" % (API, a.package, edit_id, a.track),
         body={"track": a.track, "releases": [release]})
    print("staged %s on track '%s'" % (", ".join(version_codes), a.track))

    if a.dry_run:
        call(token, "DELETE", "%s/applications/%s/edits/%s" % (API, a.package, edit_id))
        print("dry run: edit deleted, nothing published")
        return

    call(token, "POST", "%s/applications/%s/edits/%s:commit" % (API, a.package, edit_id))
    print("committed. live on '%s' for your testers." % a.track)


if __name__ == "__main__":
    main()
