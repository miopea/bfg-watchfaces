#!/usr/bin/env python3
"""Submit a phone bundle AND a watch bundle to production, in ONE review.

WHY THIS IS A SEPARATE SCRIPT
-----------------------------
`play-release.py` refuses production on purpose. Its docstring says so, and its
`--track` help says "production and wear:production are deliberately absent".
That refusal was written when production still needed App content declarations,
a store listing and a privacy policy that did not exist yet. All three have
existed since 2026-09-24, so the REASON has expired -- but loosening the old
script would quietly turn every future `--track` typo into a public release.

So production lives here instead, in a file whose name says what it does, and
`play-release.py` keeps refusing. Testing tracks go through that script; this
one is the deliberate act.

THE ORDERING THIS EXISTS TO GET RIGHT
-------------------------------------
Adding a change to a review that is already running CANCELS and RESTARTS it.
Submission 1 was thrown away eighteen minutes in for exactly that, because the
second form factor was staged after the first had been submitted.

Both form factors therefore go in ONE edit, as two track updates, followed by a
SINGLE commit. The Console cannot do this -- it invites two separate releases
and two separate submissions -- which is the whole reason to drive it from here.

A Wear bundle cannot go on the phone track and vice versa: Play refuses the
commit naming `android.hardware.type.watch`. Hence two tracks, not one.

WHAT A COMMIT MEANS HERE
------------------------
With managed publishing OFF, committing an edit that touches production IS the
submission. There is no second button. Run with --dry-run first if you want to
prove the uploads parse; a green dry run proves only that, because the policy
gates live in the commit.

    eval "$(op-login)"
    scripts/play-production.py \
        --bundle mobile/build/outputs/bundle/release/mobile-release.aab \
        --wear-bundle wear/build/outputs/bundle/release/wear-release.aab \
        --notes "..."
"""
import argparse
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# Reuse the credential path, the HTTP helper and the commit logic rather than
# writing a second copy that can drift from the one that is exercised weekly.
_spec = importlib.util.spec_from_file_location(
    "play_release", os.path.join(HERE, "play-release.py"))
_pr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_pr)

PHONE_TRACK = "production"
WEAR_TRACK = "wear:production"


def commit_and_say_which(token: str, package: str, edit_id: str) -> str:
    """Commit, and return which of Play's two forms actually went through.

    `play-release.py`'s commit() falls back to changesNotSentForReview=true when
    Play's own error demands it. On a TESTING track that fallback is harmless --
    a testing track is exempt from review, so there is nothing to dispatch. On
    PRODUCTION it is not harmless at all: it means the release was committed and
    NOT sent for review, which is a completely different claim from "submitted".
    Reporting one as the other is how a release gets called shipped while it
    sits in Play waiting for someone to press a button nobody knows about.
    """
    url = "%s/applications/%s/edits/%s:commit" % (_pr.API, package, edit_id)
    failed = _pr.attempt(token, url)
    if failed is None:
        return "sent for review"
    if "changesNotSentForReview" not in failed:
        sys.exit("\ncommit refused:\n" + failed)
    held = _pr.attempt(token, url + "?changesNotSentForReview=true")
    if held is not None:
        sys.exit("\ncommit refused both ways.\nbare:\n%s\nheld back:\n%s"
                 % (failed, held))
    return "committed but NOT sent for review"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--package", default="com.bfg.watchfaces")
    p.add_argument("--bundle", required=True, help="phone .aab")
    p.add_argument("--wear-bundle", required=True, help="watch .aab")
    p.add_argument("--notes", default=None, help="release notes for en-US")
    p.add_argument("--dry-run", action="store_true",
                   help="upload and stage, then DELETE the edit instead of committing")
    a = p.parse_args()

    for b in (a.bundle, a.wear_bundle):
        if not os.path.isfile(b):
            sys.exit("no such bundle: %s" % b)

    token = _pr.access_token(_pr.service_account())
    print("authenticated")

    edit = _pr.call(token, "POST",
                    "%s/applications/%s/edits" % (_pr.API, a.package))
    edit_id = edit["id"]
    print("edit %s" % edit_id)

    staged = {}
    for path, track in ((a.bundle, PHONE_TRACK), (a.wear_bundle, WEAR_TRACK)):
        size = os.path.getsize(path)
        print("uploading %s (%.1f MB)..." % (os.path.basename(path), size / 1048576))
        with open(path, "rb") as fh:
            res = _pr.call(token, "POST",
                           "%s/applications/%s/edits/%s/bundles?uploadType=media"
                           % (_pr.UPLOAD, a.package, edit_id),
                           body=fh.read(), content_type="application/octet-stream")
        code = str(res["versionCode"])
        staged[track] = code
        print("  versionCode %s  sha256=%s" % (code, res.get("sha256", "?")[:16]))

    # Both tracks are updated BEFORE the single commit below. This is the whole
    # point of the script; do not move the commit inside this loop.
    for track, code in staged.items():
        release = {"versionCodes": [code], "status": "completed"}
        if a.notes:
            release["releaseNotes"] = [{"language": "en-US", "text": a.notes}]
        _pr.call(token, "PUT",
                 "%s/applications/%s/edits/%s/tracks/%s"
                 % (_pr.API, a.package, edit_id, track),
                 body={"track": track, "releases": [release]})
        print("staged %s on track '%s'" % (code, track))

    if a.dry_run:
        _pr.call(token, "DELETE",
                 "%s/applications/%s/edits/%s" % (_pr.API, a.package, edit_id))
        print("dry run: edit deleted, nothing submitted")
        return

    outcome = commit_and_say_which(token, a.package, edit_id)
    print("\ncommitted one edit covering both form factors: %s" % outcome)
    for track, code in staged.items():
        print("  %-16s %s" % (track, code))
    if outcome != "sent for review":
        print("\nWARNING: this is NOT a submission. Play held the changes back;\n"
              "someone has to send them for review in the Console.")


if __name__ == "__main__":
    main()
