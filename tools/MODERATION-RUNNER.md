# Independent moderation runner

<!-- cspell:words journalctl -->

The user systemd timer runs the JVM renderer every two minutes after the previous
run finishes. D1 leases prevent overlapping GitHub and server runs from reviewing
the same revision concurrently. Failures retry with backoff up to five attempts;
the admin Retry processing action resets that budget. Failed technical validation
requires human attention. The default mode is recommendations; publication waits
for the operator. In explicitly enabled automatic mode, the service can publish
fresh high-confidence recommendations within the saved author and library limits.
Every such publication has an atomic audit record. Rejection and removal always
require the operator.

Install Java 21 and clone this repository to
`~/projects/personal/bfg-moderation-runner`. Run `scripts/bootstrap.sh` there.
The existing personal-project `~/.local/bin/op-login` helper and `op` resolve the
moderator credential in memory. Never put the token in the unit file.

Copy `tools/bfg-moderation.{service,timer}` to `~/.config/systemd/user/`, then run
`systemctl --user daemon-reload` and
`systemctl --user enable --now bfg-moderation.timer`. Enable user lingering to
keep the timer running after logout. Inspect `systemctl --user status
bfg-moderation.timer` and `journalctl --user -u bfg-moderation.service`.

Apply D1 migration `0004-moderation-processing.sql` before deploying the matching
Worker, then `0005-automatic-approval.sql` for the optional approval policy and
audit history. Its default preserves recommendation mode. Update this isolated checkout to the tested release when deploying runner
changes. A failed run or heartbeat older than five minutes degrades catalog health.
The heartbeat reports runner availability; per-submission failures remain in the
inbox even if the following run is healthy.

`python3 tools/test-moderation-runner.py` exercises the actual Kotlin runner against
an isolated HTTP service and proves that one AI failure does not stop later work.
