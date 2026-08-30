# The catalog service

Anonymous submit, anonymous report, and a moderation queue for the BFG Watch
Faces community catalog. Cloudflare Workers, D1 and Turnstile.

**Not deployed.** Nothing has been created on any Cloudflare account. The
database id in `wrangler.toml` is a placeholder.

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
GET  /config                   public values   -> the Turnstile site key

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
then pass while the rule was gone. Turnstile uses Cloudflare's documented
always-passes test keys, so nothing here touches the network.

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

Not done yet, and it needs an interactive login this repo's tooling cannot
perform:

```bash
wrangler login
wrangler d1 create bfg-catalog          # put the printed id in wrangler.toml
npm run db:remote                       # apply schema.sql
wrangler secret put TURNSTILE_SECRET
wrangler secret put MODERATOR_TOKEN
wrangler secret put IP_SALT
npm run deploy
```

Then, and only then, the app can be pointed at it — behind one seam, per the
spec's sequencing. **The GitHub report route stays live until the replacement is
deployed and verified.** Removing it first would leave the app with no complaint
path at all, which Play's UGC rules require before it can ship.
