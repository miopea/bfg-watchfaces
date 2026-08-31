# The catalog service

Anonymous submit, anonymous report, and a moderation queue for the BFG Watch
Faces community catalog. Cloudflare Workers and D1, with a Google sign-in
required only to publish.

**Deployed** 2026-08-30 to the BFG Solutions account:
<https://bfg-catalog.bfg-solutions.workers.dev>

Verified against the live service, not the upload's own output: reads answer,
`/admin/*` refuses a missing and a wrong token with 401, and the write
endpoints return 401 because `GOOGLE_CLIENT_ID` is not set yet — the
fail-closed behaviour, working. Version `e8b13e72` is serving 100% of traffic.

**Publishing needs a Google sign-in; reporting never does.** Submitting is off
until an OAuth client id is configured — a missing one must never quietly become
an open submission endpoint. Reporting works today, anonymously, because
requiring an account to complain was intolerable the moment submitting did not.

## Why this exists rather than a GitHub repository

GitHub has no anonymous write path of any kind — commits, pull requests, issues,
discussions and gists all need an account, and no repository setting changes
that. The aim is that anyone can share a face and anyone can report one without
signing up anywhere, so the catalog had to move.

`../docs/specs/catalog-service.md` is the contract. Read it before changing
anything here.

## The four things most easily got wrong

1. **Nothing is public until a person approves it.** Anonymous submission
   removes the only handle moderation normally has: there is no identity to ban.
   Pre-moderation is what makes it safe. Rate limiting is a speed bump and is
   named as one in `src/ratelimit.ts`.
2. **A report is a message, not an action.** Nothing here hides a face. With no
   accounts, "N people reported it" is one person and a loop, and auto-hiding on
   a count would hand anyone a takedown button.
3. **The install counter carries nothing about the person.** No install id, no
   device details, one number per face. It is inflatable by anyone posting in a
   loop, which is acceptable for ordering a gallery and would not be for
   anything else.
4. **`/export` is not a nice-to-have.** It is the mitigation for what moving off
   git gave up — nobody can clone the catalog any more. It emits the same
   `faces/<slug>.json` and `index.json` the git catalog held, so the on-disk
   format stays the interchange format.

## This service does not know what a face is

`params-contract.json` is **generated** from the Kotlin that defines the file
format:

```bash
./gradlew :workbench:contract   # writes params-contract.json and test/fixtures/face.json
```

Ranges come from `ControlInventory`, layout bounds from `SlotGeometry`, enums
from `DialParams`, the field list from `FaceCodec.toQuery`, the colour and
ComponentName patterns from `DialParams`' own regexes, and the font weights from
Watch Face Format's XSD. Nothing in `src/` decides what a legal face looks
like — it reads the answer.

Both generated files are committed, because deploying with `wrangler` must not
depend on a JVM having been run. `ContractFileTest` in `:workbench` fails if a
committed copy goes stale, which is the only thing that can notice.

**The test fixture is generated too**, by the same task, from `FaceCodec` and
`CatalogStore`. A hand-typed fixture would prove the service accepts a face the
app never produces — and that is not hypothetical: the generated one immediately
caught `dateSize` being bounded by `SlotGeometry.MAX_DATE_SIZE` (56) when the
stored default is 64, which would have rejected every genuine submission.

## What this validator does NOT check

It does not render the face and it does not check the emitted WFF against
Google's XSD. Neither can run in a Worker, and porting either into JavaScript
would be a second implementation of the file format.

That check still runs before anything is published — on the JVM, in the
moderation pass, with the real emitter and the real schema. So nothing reaches
the public without an automated verdict, but the verdict no longer arrives at
the moment the POST returns. This matters because a schema-invalid face
installs cleanly and then never appears in the carousel: there is no error on
either side.

## Endpoints

```text
POST /faces                    submit          -> { id, slug, state: "pending" }
POST /reports                  report          -> { id, state: "open" }
POST /faces/<slug>/installed   count one       -> 204, empty
GET  /index.json               the gallery     -> published faces, popular first
GET  /faces/<slug>             one face        -> the catalog's on-disk shape
GET  /submissions/<id>         what happened   -> state, and the reason if any
POST /submissions/<id>/withdraw                -> needs the install id
GET  /export                   R6              -> every published face, as files
GET  /config                   public values   -> the OAuth client id

GET  /admin/queue?state=pending                -> oldest first
GET  /admin/reports                            -> open reports, oldest first
POST /admin/faces/<id>/publish|reject|remove   -> reject and remove need a reason
POST /admin/reports/<id>/resolve               -> needs an outcome
```

`/admin/*` takes one bearer token. There is no other credential and no default
value: a default moderator token is a published one.

## Running the tests

```bash
npm install
npm test        # 39 tests, inside workerd, against a real local D1
npm run typecheck
```

The tests apply the real `schema.sql` rather than a copy — the partial unique
index that enforces "byte-identical submissions are rejected" is exactly the
kind of thing a hand-maintained test copy loses silently, and the test would
then pass while the rule was gone. The tests sign their own ID tokens with
their own key and stand in for Google's key server, so nothing touches the
network and the Worker still verifies signatures for real.

## Moderating

The queue is worked from the repo, not from a dashboard:

```bash
export BFG_CATALOG_URL=https://bfg-catalog.<subdomain>.workers.dev
eval "$(op-login)" && export BFG_MODERATOR_TOKEN="$(op read op://…/moderator-token)"

./gradlew :workbench:moderate                        # review, decide nothing
./gradlew :workbench:moderate --args="--auto-reject" # reject what provably fails
./gradlew :workbench:moderate --args="--publish=<id>"
./gradlew :workbench:moderate --args="--reject=<id> --reason=..."
./gradlew :workbench:moderate --args="--reports"
```

**This is where a face meets Google's XSD.** The Worker cannot do it, so if this
does not run before publication, nothing does. It also renders every face to
`build/moderation/*.png`, because deciding whether a dial is somebody's logo
means looking at it.

`--reject` and `--remove` both require a reason. `MODERATION.md` promises
appeals are answered, and an appeal against a decision with no recorded reason
cannot be.

## Deploying

Already done once. `wrangler login --device` is the flow that works from here —
it prints a URL and a code instead of needing a browser on this machine, which
the default localhost-callback flow cannot have.

```bash
npx wrangler login --device     # only if the stored credential has expired
npm run deploy
```

`IP_SALT` and `MODERATOR_TOKEN` are set. The moderator token is in 1Password as
**bfg-catalog-moderator-token** (BFG vault) — it is the only credential in the
system and the only one a human needs to read back.

### What is still switched off

`GOOGLE_CLIENT_ID` is empty, so publishing answers 401. To turn it on:

1. In Google Cloud Console, create an OAuth 2.0 **Web application** client id
   for the project that already holds the Play service account.
2. Register the Android app's SHA-1 fingerprints against it — **debug AND
   release**, or sign-in works for the developer and fails for everybody else.
   That is the usual way this is got wrong.
3. Put the client id in `wrangler.toml` under `GOOGLE_CLIENT_ID` and redeploy.
   It is public by necessity: the app fetches it from `/config` and needs the
   same value to ask Google for a token.

There is nothing to host and no page to serve. That requirement belonged to
Turnstile, which is gone.

Only then can the app be pointed at this service, behind one seam, per the
spec's sequencing. **The GitHub report route stays live until that is done and
verified.** Removing it first would leave the app with no complaint path at all,
which Play's UGC rules require before it can ship.
