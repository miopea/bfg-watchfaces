# Recurring watch review

BGS Dev runs `bfg-moderation.timer` every two minutes after the previous run
finishes. The runner claims due submissions, validates and renders trusted
previews, requests AI advice when enabled, and records completion or retry state.
One failed review does not stop later submissions. Five failed attempts require
operator attention. GitHub's moderation workflow is a manual recovery path.

The deployed checkout is
`/home/bschleifer/projects/personal/bfg-moderation-env-runner`.
The service uses Java 21 and prebuilt classes. During installation or an update,
populate the schema and validator dependencies using `scripts/bootstrap.sh`,
then build with `./gradlew :workbench:prepareModerationRunner`. Verify with
`python3 tools/test-moderation-environment.py` and
`python3 tools/test-moderation-runner.py`; set `JAVA_HOME` to Java 21 for both
the build and runner test. These tests use isolated HTTP fixtures.

Provision `BFG_MODERATOR_TOKEN` and `BFG_CATALOG_URL` in
`~/.config/bfg-admin/moderation.env` with mode 0600 in a 0700 directory. Install
the tracked service and timer in `~/.config/systemd/user`, reload systemd, and
enable the timer. The service does not read 1Password or build code at startup.
Do not print credentials or include their values in source or commands.

The operator explicitly approved recurring Anthropic review on September 5,
2026: trusted previews, public face/author details, policy, author-history counts
and recent published previews may be sent and recommendations saved. Activation
verified the existing recommendations-only mode. Automatic publication remains
a separate explicit setting; no submission is automatically rejected or removed.

Check service results with `systemctl --user show bfg-moderation.service` using
the Result, ExecMainStatus and execution timestamp properties. Check the timer's
ActiveState and next trigger. The catalog's authenticated `/api/ops/health`
reports a healthy moderation runner only after a successful recent heartbeat.
A successful empty queue establishes scheduling and reporting, not a new AI
provider call. Never publish or manufacture real submissions solely for testing.
