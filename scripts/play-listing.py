#!/usr/bin/env python3
"""Read and update the Play store listing — text and graphics — without a browser.

WHY THIS EXISTS
---------------
Same reason as play-release.py: the console's file picker runs on whichever
machine the browser does, and this project builds on a headless Linux box driven
from a Windows laptop over a tunnel. The store icon is generated here by
`./gradlew :workbench:brand`, so it should upload from here too.

    eval "$(op-login)"
    scripts/play-listing.py --show
    scripts/play-listing.py --icon docs/brand/play-icon-512.png --dry-run
    scripts/play-listing.py --icon docs/brand/play-icon-512.png

WHAT IT REFUSES TO DO
---------------------
It never touches a track and never commits a release. Graphics and words only.
--dry-run stages everything and then deletes the edit, which is the honest way
to find out whether Play accepts an asset before anyone can see it.
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

# Play's own names for the slots, and how many each holds. `icon` and
# `featureGraphic` replace; the screenshot slots accumulate, so anything going
# into one is cleared first or the old shots stay alongside the new.
SINGLE = {"icon", "featureGraphic", "promoGraphic", "tvBanner"}


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


def png_size(path: str):
    """Width and height from the IHDR, so a wrong-sized asset fails here and not
    after a round trip to Google."""
    with open(path, "rb") as fh:
        head = fh.read(26)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


EXPECTED = {"icon": (512, 512), "featureGraphic": (1024, 500)}


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--package", default="com.bfg.watchfaces")
    p.add_argument("--language", default="en-US")
    p.add_argument("--show", action="store_true",
                   help="print the current listing and image slots, change nothing")
    p.add_argument("--icon", help="512x512 store icon")
    p.add_argument("--feature", help="1024x500 feature graphic")
    p.add_argument("--phone", action="append", default=[],
                   help="phone screenshot; repeat. Replaces the whole slot.")
    p.add_argument("--wear", action="append", default=[],
                   help="Wear OS screenshot; repeat. Replaces the whole slot.")
    p.add_argument("--short", help="short description, 80 characters")
    p.add_argument("--full", help="full description, or @path to read a file")
    p.add_argument("--dry-run", action="store_true",
                   help="stage everything, then DELETE the edit instead of committing")
    a = p.parse_args()

    uploads = []
    if a.icon:
        uploads.append(("icon", a.icon))
    if a.feature:
        uploads.append(("featureGraphic", a.feature))
    uploads += [("phoneScreenshots", s) for s in a.phone]
    uploads += [("wearScreenshots", s) for s in a.wear]

    for slot, path in uploads:
        if not os.path.isfile(path):
            sys.exit("no such file: %s" % path)
        size = png_size(path)
        if slot in EXPECTED and size and size != EXPECTED[slot]:
            sys.exit("%s must be %dx%d, but %s is %dx%d"
                     % ((slot,) + EXPECTED[slot] + (path,) + size))

    full = a.full
    if full and full.startswith("@"):
        with open(full[1:]) as fh:
            full = fh.read().strip()
    if a.short and len(a.short) > 80:
        sys.exit("short description is %d characters; Play allows 80" % len(a.short))

    token = access_token(service_account())
    edit_id = call(token, "POST", "%s/applications/%s/edits" % (API, a.package))["id"]
    base = "%s/applications/%s/edits/%s/listings/%s" % (API, a.package, edit_id, a.language)

    if a.show or not (uploads or a.short or full):
        listing = call(token, "GET", base)
        print("listing (%s)" % a.language)
        print("  title            : %s" % listing.get("title", ""))
        print("  short description: %s" % listing.get("shortDescription", ""))
        body = listing.get("fullDescription", "")
        print("  full description : %s" % ((body[:70] + "...") if len(body) > 70 else body))
        for slot in ("icon", "featureGraphic", "phoneScreenshots", "wearScreenshots"):
            got = call(token, "GET", "%s/%s" % (base, slot)).get("images", [])
            print("  %-17s: %d image(s)" % (slot, len(got)))
        call(token, "DELETE", "%s/applications/%s/edits/%s" % (API, a.package, edit_id))
        return

    if a.short or full:
        listing = call(token, "GET", base)
        patch = {"language": a.language,
                 "title": listing.get("title", ""),
                 "shortDescription": a.short or listing.get("shortDescription", ""),
                 "fullDescription": full or listing.get("fullDescription", "")}
        call(token, "PUT", base, body=patch)
        print("words updated")

    cleared = set()
    for slot, path in uploads:
        if slot not in cleared:
            # Screenshot slots ACCUMULATE -- uploading two more to a slot that
            # already holds three leaves five. Clearing before the first upload
            # to a slot makes `--phone a.png --phone b.png` mean exactly those.
            call(token, "DELETE", "%s/%s" % (base, slot))
            cleared.add(slot)
        with open(path, "rb") as fh:
            res = call(token, "POST", "%s/applications/%s/edits/%s/listings/%s/%s?uploadType=media"
                       % (UPLOAD, a.package, edit_id, a.language, slot),
                       body=fh.read(), content_type="image/png")
        print("uploaded %-16s <- %s (id %s)" % (slot, path, res.get("image", {}).get("id", "?")))

    if a.dry_run:
        call(token, "DELETE", "%s/applications/%s/edits/%s" % (API, a.package, edit_id))
        print("dry run: edit deleted, the live listing is untouched")
        return

    call(token, "POST", "%s/applications/%s/edits/%s:commit" % (API, a.package, edit_id))
    print("committed to the %s listing" % a.language)


if __name__ == "__main__":
    main()
