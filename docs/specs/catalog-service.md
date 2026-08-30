# The catalog service

The community catalog moves off GitHub. Anyone can share a face and anyone can
report one, neither needing an account anywhere.

Settled by operator decision `01a049a3-0a0c-7521-a6f3-f40510b81cf7`:

> Submission path: "Move the catalog off GitHub entirely"
>
> Reporting: "We need another solution"

This is the contract that replacement has to satisfy. It is written before the
code, the same way `docs/SPEC.md` insists the permission flow was — the thing
being replaced is a Play-required complaint path, and getting it wrong is not a
refactor.

## Why GitHub cannot do this

GitHub has no anonymous write path. Commits, pull requests, issues, discussions,
comments and gists all require an authenticated account; reading is anonymous,
writing never is, and no repository setting changes that.

Everything else follows from that one fact. It is not a limitation of how the
catalog was built.

## What is being given up

Recorded because it was argued against and chosen anyway, and the next person
deserves the reasoning rather than a mystery.

- **Removals stop being a public commit.** Today deleting `faces/<slug>.json` is
  an auditable, reversible act in a history anyone can read. A service makes
  moderation a database write that only the maintainer can see.
- **Nobody can take a copy any more.** A git repo of JSON is portable by
  construction: anyone can clone the whole community catalog and it keeps
  working. That property is lost unless the service deliberately exports.
- **Free hosting stops being automatic.** jsDelivr over a public repo is free
  because it is somebody else's CDN over somebody else's git. A service is free
  only while a free tier says so.

Mitigations are in the requirements below: an export endpoint, a public
moderation log, and a hard rule that everything can be exported as files.

## What has NOT changed, and must not

- **Parameters only, never rasters.** A face is ~5KB of JSON. This keeps the
  catalog small and is the IP shield — you cannot encode a logo as "knotwork,
  scale 26, pewter". `Engine.TEXTURE` faces are rejected, not accepted and dealt
  with later.
- **Submissions validate without a human.** A face must parse, render, and emit
  WFF that passes Google's XSD. This matters more than usual: a schema-invalid
  face installs cleanly and then never appears in the carousel, so there is
  nothing for a reviewer to notice.
- **No account, no ads, no cost to the user.** The About screen makes that
  promise and it is the only promotion in the app.

## Requirements

### R1 — Anyone can submit, with no account

The app POSTs a face. No sign-in, no email, no identity of any kind. An author
name is optional and is a display string, not a login.

### R2 — Anyone can report, with no account

The current report path opens a GitHub issue form and says so in the app: "You
will need a GitHub account to send it." `MODERATION.md` already flags that as
"a real limitation and it is stated rather than hidden". It stops being
acceptable the moment submission is anonymous, because then anyone can publish
and only developers can complain.

Google Play requires a working in-app complaint path for any app showing user
content. Without a reachable one the app cannot ship at all, which makes this
the highest-risk item here.

### R3 — Nothing is public until it is approved

The abuse control, and the reason R1 is safe. Anonymous submission removes the
only handle moderation normally has: there is no identity to ban. A public
endpoint that writes to a public catalog will be found.

Pre-moderation contains that — spam never becomes visible, so flooding the queue
costs the attacker effort and gains them nothing. Rate limiting alone does not,
because it only slows a flood that still lands.

A submission is therefore in exactly one state:

| State | Meaning | Visible to |
| --- | --- | --- |
| `pending` | Passed automated validation, awaiting a human | Nobody |
| `published` | Approved | Everyone |
| `rejected` | Declined, with a reason | Nobody |
| `removed` | Was published, taken down after a report | Nobody |

### R4 — The moderation promises in `MODERATION.md` still hold

They were published, so they are commitments, not aspirations: IP claims acted
on within 72 hours, slurs and harassment removed on sight, impersonation within
seven days, appeals answered. The service has to make those achievable by one
maintainer — which means a queue that can be worked through, not an inbox.

The existing caveat stays true and stays stated: **a removed face is not
retracted from a watch it is already installed on.** Nothing in this system can
reach onto someone's wrist and it would be wrong to imply otherwise.

### R5 — Reads stay cheap and cacheable

The gallery is the reason to open the app twice, so the index has to be fast and
must not cost per view. An index of name, author, engine and colours for a
10,000-face catalog is a few MB and changes rarely; it should be served from
cache at the edge, not computed per request.

### R6 — Everything can be exported as files

A single endpoint that exports every published face as the same
`faces/<slug>.json` plus `index.json` the git catalog uses today. This is what
buys back the portability being given up: if the service dies, is priced, or is
abandoned, the catalog survives as files. It also means the app's existing
on-disk catalog format stays the interchange format rather than becoming
legacy.

### R7 — Abuse control without identity

No account means no ban. What is left:

- Pre-moderation (R3), which is the substantive one.
- A proof-of-humanity check on submit and report. It must not require an account
  and must not track the user.
- Per-IP rate limits, understood as a speed bump rather than a control.

## The interface the app needs

Three operations. Everything else is the maintainer's side.

```text
POST /faces      submit a face      -> { id, state: "pending" }
POST /reports    report a face      -> { id }
GET  /index.json published faces    -> the same shape as the git catalog's index
GET  /faces/<slug>                  -> one face's parameters
GET  /export                        -> R6, everything as files
```

Payloads are the formats that already exist. `CatalogStore.Entry` is the face
record and `index.json` is already generated; neither should be redesigned
because the transport changed.

## Recommended implementation: Azure Static Web Apps

Operator asked for research: "find out what is there that is free. Ideally
something we already use? Cloudflare or AZ."

**They already use Azure, decisively.** Across the sibling repos there are 325
files mentioning Azure against 16 mentioning Cloudflare, and the Azure ones are
real deployments — `azure/webapps-deploy`, `azure/login` with OIDC federated
credentials, App Service, staging slots. There is no `wrangler.toml` anywhere on
the machine. Cloudflare would be a new account, a new auth model and a new thing
to keep alive; Azure is muscle memory and the GitHub OIDC trust is already set
up.

The numbers below were read from Microsoft's and Cloudflare's own documentation
on 2026-08-28, not from memory. Re-check them before committing: free tiers are
someone else's pricing page and the About screen's promise depends on this.

### The shape

| Piece | What runs it | Free? |
| --- | --- | --- |
| Published catalog (`index.json`, `faces/*.json`, `/export`) | Static files on the Static Web App's global CDN | Yes |
| Submit, report | Managed Azure Functions, included in the Free plan | Yes |
| Moderation queue | Cosmos DB free tier | Yes, with one caveat below |

The important realisation is that **the published catalog barely needs a
database.** It changes only when a maintainer approves something, so it is
static content — served from a CDN with no per-request cost and no query.
Only the pending queue takes writes, and a queue of things nobody has approved
yet is tiny.

### Azure Static Web Apps, Free plan

From `learn.microsoft.com/azure/static-web-apps/plans` and `/quotas`:

- Web hosting, GitHub integration, globally distributed static content, and
  free automatically renewing SSL — all on Free
- **APIs via Azure Functions: Managed**, included rather than billed separately
- **Included bandwidth: 100 GB/month**
- Total storage per app: **500 MB**
- 10 apps per subscription, 2 custom domains, 3 preview environments
- **Service Level Agreement: None**

Two of those deserve comment.

**Overage bandwidth on the Free plan is listed as "Unavailable"**, where the
Standard plan bills $0.20/GB. That is not a limitation here, it is the single
best property on offer: the Free plan physically cannot generate a bill. For an
app whose only promotion is "Every part of this app is free. No ads. No account.
No subscription", a plan that stops serving rather than quietly charging is
worth more than a larger allowance that can.

**500 MB of app storage** sounds small and is not. A face is ~5KB of JSON, so
that is roughly 100,000 faces — an order of magnitude past the 10,000-face
figure `docs/SPEC.md` sizes the design around.

100 GB/month is likewise generous against a few MB of index per gallery load.

No SLA is acceptable for a free community gallery. `MODERATION.md`'s promises
are about response times, not uptime.

### The queue: Cosmos DB free tier

From `learn.microsoft.com/azure/cosmos-db/free-tier`: the first **1000 RU/s and
25 GB are free**, and "free tier lasts indefinitely for the lifetime of the
account". Not a 12-month trial.

**The one thing to check before building.** There can be **one free-tier Cosmos
account per Azure subscription**, and if another account in the subscription has
already claimed it the option does not even appear. Given how much else is
already on Azure here, it may well be taken. If it is, Azure Table Storage costs
cents per month at this size — but cents is not zero, and the About screen makes
a promise about zero. Worth confirming before the first resource is created.

### Why not Cloudflare

Genuinely close on the free tier, and it was the earlier recommendation. From
Cloudflare's own limits pages: Workers Free gives 100,000 requests/day, and D1
Free gives 1 database at a **500 MB maximum size** with 50 queries per Worker
invocation. That is ample too.

It loses on the thing the operator actually asked about: it is not what they
already use. A second cloud account, a second deploy story, a second set of
credentials and a second thing to remember exists — for a free app maintained by
one person, that cost is paid forever and the capability gain is nil.

## Sequencing — do not break the working path first

The GitHub report route works today and is what makes the app shippable under
Play's UGC rules. It stays live and in place until the replacement is deployed
and verified.

Order:

1. Build and deploy the service. Verify submit, report and read against it.
2. Point the app at it, behind one seam so the switch is configuration.
3. Only then remove the GitHub issue route and rewrite `MODERATION.md`.
4. Decide what happens to `miopea/bfg-watchfaces-catalog`. Retiring it is the
   decision's plain reading, but archiving it read-only preserves the audit
   trail of everything published under the old model.

Removing step 3's route before step 1 exists would leave the app with no
complaint path at all, which is worse than a complaint path that needs an
account.

## Settled since this was written

Operator decision `01a049b0-053d-7fb3-a2e6-c855678204c8`:

- **Hosting**: research it, "ideally something we already use? Cloudflare or
  AZ." Answered above — Azure, because that is what is already in use.
- **Public moderation log**: "Let's figure this out based on the service.
  Hopefully no." Deferred, leaning against. Nothing below depends on it, and the
  export endpoint (R6) already covers the portability half of what a public log
  would have bought.
- **The old catalog repo**: "Retire it entirely."

### Retiring the repo is LAST, not first

`miopea/bfg-watchfaces-catalog` currently hosts the app's only working complaint
path — the report form is a GitHub issue template in that repository. Archiving
it disables issues; deleting it destroys the form outright. Either one, done
before the replacement exists, leaves the app with no complaint path,
which Play's UGC rules require before it can ship at all.

So the instruction is authorised and queued, not ignored. It happens at step 4
of the sequencing above, after the service is deployed and the app points at it.

## Settled by interview, 2026-08-30

### A published slug carries an id, because a slug is a package name

`watchfacepush.<slug>` is the Watch Face Push package. Two community faces both
called "Midnight" produce the SAME package, and installing the second silently
replaces the first on the watch — the kind of invisible difference that has cost
the most this week.

A published face's slug is therefore `name_<short id>`:

```text
watchfacepush.midnight_7f3a
watchfacepush.midnight_c214
```

Collisions become impossible by construction rather than by policy, and nobody
has to be told their name is taken. The package name is slightly uglier and is
visible in Settings on the watch; that is the price.

Locally saved faces keep their plain slug. This applies when a face is
PUBLISHED, which is the only moment two strangers' names can meet.

### Bot check: Cloudflare Turnstile

The operator already has a Cloudflare account, so the "second cloud" objection
that shaped the hosting decision does not apply. Free, needs no account from the
person submitting, and tracks nobody. A script tag and one secret is a far
smaller commitment than a second runtime, which is what the hosting argument was
actually about.

### The queue grows; oldest first

Submissions are always accepted and worked through in order. Nothing is lost and
nobody is turned away because strangers arrived first. "Awaiting review" can
therefore mean weeks, and that is said in the app rather than implied — the
response promises in `MODERATION.md` are about REPORTS, not submissions, and
that distinction now needs to be explicit there too.

A cap was rejected: it turns real submissions away and hands an attacker a way
to hold the queue full. Auto-publishing after a timeout was rejected outright —
it defeats R3, since an attacker need only wait.

### Withdrawal: a random per-install id

A random value made on first run and stored on the device. Sent ONLY when
submitting or reporting, never on reads, so browsing stays anonymous. It lets
someone withdraw their own face and lets the app show what they have submitted.

It is deliberately weak: reinstalling makes a new one, and the old face can then
only be withdrawn by reporting it. That is stated at submit rather than
discovered.

**It is NOT used for moderation.** Blocking by install id would make it a real
identity with consequences, while still being defeated by a reinstall — the
worst of both. It exists to give an author their own face back.

**R1's wording changes.** "No identity of any kind" was true and is no longer.
It becomes: no account, and nothing that identifies a person. About says so in
the promise itself rather than in a privacy page nobody reads.

### Reports queue for a human and never auto-hide

A report is a message, not an action. Mass-reporting achieves nothing but a
longer queue. Auto-hiding on N reports was rejected because with no accounts "N
people" is one person and a loop — it hands anyone a takedown button.

The cost is accepted and already written into `MODERATION.md`: a harmful face
stays up until a person sees it, which is what the 72-hour promise means.

### Pending faces live in My faces

Marked "Waiting to be reviewed", with a withdraw option, where the person's
faces already are. No new screen, and it sets the expectation that review takes
time. A confirmation-and-nothing-else was rejected: it cannot distinguish "still
waiting" from "quietly rejected", which is the state people actually ask about.

### Byte-identical submissions are rejected; near-duplicates are not

An exact parameter match adds nothing. Anything else is somebody's judgement
about colour and scale, and refereeing that needs a threshold nobody can defend
and would land in the appeals path.

### The gallery is ordered by popularity, and that costs something

Chosen over newest-first with the cost named: ranking by installs means the app
REPORTS installs, and the app has promised that browsing sends nothing.

The reporting is made as small as it can be:

```text
POST /faces/<slug>/installed
(empty body)

stored:  midnight_7f3a -> 1,412
```

No install id, no device details, nothing correlatable, one number per face. A
per-person history is exactly what this must not become, which is why the
install id is not attached even though it exists.

The count is inflatable by anyone posting in a loop, so the ranking is a hint
rather than a truth. That is acceptable for ordering a gallery and would not be
for anything else.

**About states it in the promise**, not in a footnote:

```text
No account. No ads. No cost.

When you install a community face, the app adds one to that face's counter so
the gallery can show popular designs. Nothing about you is sent — not a name,
not a number, not your other faces.
```

### Offline: the cached index, marked as possibly stale

The index is a few MB and changes rarely, so caching it is nearly free, and a
face is parameters — anything cached still previews and still sends to a watch.
Someone can therefore browse and install a face that has since been removed,
which `MODERATION.md` already admits it cannot prevent on a wrist.

## Checked 2026-08-30: the Azure recommendation does not survive

Both halves of the "check before building" turned out badly, and the second one
is the important one.

### The Cosmos free tier is already taken

```text
az cosmosdb list
  rcgcrm   freeTier=True   rg=crm
```

One free-tier account per subscription, and `rcgcrm` has it. The option will not
even appear. Table Storage would be cents a month — and cents is not zero, which
is exactly what the About screen promises.

### The Azure that is available is the EMPLOYER'S

```text
az account list
  Information Technology (EA)   tenant=rcg.org   Enabled     <- the only one
```

This is the decision that matters, and the spec got it wrong by reasoning from
the wrong evidence. "They already use Azure, decisively — 325 files across the
sibling repos" is true, and every one of those repos is work. The Azure in
question belongs to The Restored Church of God, under an Enterprise Agreement.

BFG Watch Faces is a personal open-source project. Putting its community service
into an employer's EA subscription is not a hosting choice, it is a question
about whose resources, whose bill, whose policy and whose ownership — and it is
not a question this document can answer.

### Cloudflare, checked and working

The operator has their own account, and everything the service needs is
available on it:

```text
account: BFG Solutions
  D1 databases       OK (0 of the free tier's 1 used)
  Workers scripts    OK (1 already deployed: budgetbug-email-inbound)
  Turnstile widgets  OK
  Pages projects     OK
```

Workers is already in production use on that account, so the "second deploy
story, second set of credentials, second thing to remember exists" argument does
not apply either — it is the same account the bot check was already going to use
after the interview.

### Both are in use, so: which is better and cheaper

| | Cloudflare (BFG Solutions) | Azure (Information Technology EA) |
| --- | --- | --- |
| Whose account | The operator's | The employer's |
| Published catalog | Pages / Workers, cached at the edge, free | Static Web Apps Free, cached at the edge, free |
| Submit, report, count | Workers Free, 100k requests/day | Managed Functions, included in SWA Free |
| Moderation queue | D1 Free — 1 database, 500 MB, indefinite | Cosmos free tier TAKEN → Table Storage, pence/month |
| Bot check | Turnstile, same account, same request | Turnstile, cross-account call |
| Domain for it | `bfgsolutions.net` already on the account | DNS points from Cloudflare anyway |
| Can it generate a bill | No — Free stops serving | Storage bills pence to the employer |
| Already running there | Yes: `budgetbug-email-inbound`, subdomain `bfg-solutions` | Yes, but all of it work |

Two differences are worth more than the rest.

**`POST /faces/<slug>/installed` is on the app's critical path.** Every install
of a community face makes that call, and Workers run as V8 isolates with
effectively no cold start, where Functions on the Static Web Apps Free plan cold
start in seconds. Submit and report are rare enough not to care; the counter is
not.

**Turnstile is Cloudflare's.** Validating a token from a Worker is one
same-account call. From Azure it is a cross-cloud hop with a second credential
to store and rotate — which is the "second cloud" cost the original decision was
trying to avoid, arriving anyway, just pointing the other way.

Azure would win if this needed to sit inside existing operational tooling —
monitoring, alerting, an on-call rota. A personal free gallery has none of that,
and the sibling repos' Azure is not the operator's to borrow for it.

### Settled 2026-08-30: Cloudflare

The operator chose Cloudflare in the terminal — "Cloudflare, go ahead" — after
instructing that account ownership was not a factor and the choice was to be
made on technical merit alone. **The ownership argument above is therefore
retired and must not be raised again.** It is left in the record because it was
made, not because it still counts.

The merit case, which is what decided it:

- **Workers have no cold start, and the install counter is on the critical
  path.** Every install of a community face calls
  `POST /faces/<slug>/installed`. Workers are V8 isolates; Functions on the
  Static Web Apps Free plan cold start in seconds. Submit and report are rare
  enough not to care; the counter is not.
- **D1 is real SQL, so the dedup rule is enforced by the engine.**
  "Byte-identical submissions are rejected" is one partial unique index. Table
  Storage cannot express it, so on Azure it becomes a read-then-write in
  application code with a race two simultaneous submissions can drive through.
  Cosmos would fix that and its free tier is taken.
- **Turnstile is a same-platform call** rather than a second service with a
  second credential to store and rotate.

Azure's genuine advantages were named and judged not to apply yet: App Insights,
point-in-time restore, staging slots, and a read path with zero compute per
gallery view. All of it is operational maturity for a project with no on-call,
no alerting and no restore drill.

The numbers still hold: Workers Free is 100,000 requests/day and D1 Free is one
database at 500 MB, against a catalog sized for 10,000 faces of ~5KB each.

**One ceiling is worth writing down**, because it is the place this design would
first hurt: a response served from `caches.default` still costs a Worker
invocation, so the 100k/day free limit applies to total requests rather than
cache misses. Azure's shape avoids that by serving the index as a genuinely
static file. Cloudflare can match it with Pages or a zone Cache Rule, and that
is the move if the request count ever approaches the limit — not a migration.

## Validation is split, and the split is the interesting part

R3 says "submissions validate without a human". That was written assuming a JVM
and it cannot be satisfied as written: a Worker is JavaScript, the emitter is
Kotlin and the schema validator is Xerces.

Porting either into JavaScript was rejected outright. It would be a second
implementation of the file format, which is the thing `SlotGeometry`,
`ControlInventory`, `EngravedStroke` and `FaceCodec` all exist to prevent, and
each of those was created *after* a duplicated implementation had already caused
a real bug.

So the check happens in two places:

| Where | What it catches | When |
| --- | --- | --- |
| The Worker, from the generated contract | Values outside a slider's range, unknown enum members, malformed colours, unknown fields, and strings that would break out of an XML attribute | At the POST |
| The moderation pass, on the JVM | Whether the face renders and emits WFF that passes Google's XSD | Before publication |

**Nothing reaches the public without an automated verdict**, which is what R3
was protecting. What changed is that the verdict no longer arrives while the
submitter is waiting. That is worth stating plainly because a schema-invalid
face installs cleanly and then never appears in the carousel — there is no
error, on either side, so the automated check is the only signal that exists.

### The contract is generated, not written twice

`catalog-service/params-contract.json` comes from `CatalogContract` in
`:generator` via `./gradlew :workbench:contract`. Ranges come from
`ControlInventory`, layout bounds from `SlotGeometry`, enums from `DialParams`,
the field list from `FaceCodec.toQuery`, the colour and ComponentName patterns
from `DialParams`' own regexes, and the font weights from Watch Face Format's
XSD. It is committed because a `wrangler deploy` must not depend on a JVM having
been run, and `ContractFileTest` fails when the committed copy goes stale.

The test fixture is generated by the same task, from `FaceCodec` and
`CatalogStore`. This is not fastidiousness: the generated fixture immediately
caught `dateSize` being bounded by `SlotGeometry.MAX_DATE_SIZE` (56) when the
stored default is 64. Every genuine submission would have been rejected on the
public endpoint, and every test using a hand-written fixture would have passed.

### One security bound that was not previously needed

`WffEmitter` interpolates `fontFamily` and every ComponentName straight into XML
attributes with no escaping. That is harmless while every face is made on the
machine that renders it, and stops being harmless the moment a stranger can
submit one — a quote closes the attribute. The contract publishes patterns for
both and the Worker enforces them.

## The moderation pass

Built. `./gradlew :workbench:moderate`, driven by `BFG_CATALOG_URL` and
`BFG_MODERATOR_TOKEN` from the environment.

It is split in two on purpose. `Moderation` takes a queue row and returns a
verdict, with no network in it; `Moderate` does the talking. That is what makes
the load-bearing half provable while the service is not deployed — and it is the
load-bearing half, because this is the only place in the system where a face
meets Google's XSD.

Three things it does that are not obvious:

- **It renders every face to a PNG**, into `build/moderation/`. "Slurs and
  harassment removed on sight" is not a figure of speech, and a queue of
  parameter blobs is an inbox rather than something one person can work
  through. The automated half cannot tell you a dial is somebody's logo.
- **It refuses to run without the schema installed.** Every verdict would come
  back clean, which is indistinguishable from an empty queue and would publish
  exactly the faces this pass exists to catch.
- **The passing verdict is called `LOOKS_FINE`, not `APPROVE`**, and a test
  asserts no verdict is ever named APPROVE. It means the automated checks found
  nothing, which says nothing about trademark, impersonation or harassment.

### The slug rule differs between the two catalogs, and getting it wrong is silent

In the git catalog a face is `<slug>.json` and the slug is exactly
`slugify(name)`. A face published by the SERVICE carries a short random id,
because the slug is the Watch Face Push package suffix and two strangers can
pick the same name.

Reusing the git rule in the moderation pass would have refused **every**
submission the service ever accepted. `CatalogStore.validateDocument` therefore
takes a `SlugRule` rather than one rule guessing, and `PublishedSlug` in
`:appcore` holds the published form — construct in the Worker, verify here, both
downstream of the same two numbers in the generated contract.

## Deployed 2026-08-30

<https://bfg-catalog.bfg-solutions.workers.dev>, version `e8b13e72`, serving
100% of traffic on the BFG Solutions account.

Verified against the live service rather than the upload's own output: reads
answer, `/admin/*` refuses a missing and a wrong token, and the moderator token
round-trips from 1Password into a working `/admin/queue`. The D1 database is
`bfg-catalog`; `schema.sql` created three tables.

`wrangler login --device` is what made this possible from here. The default flow
redirects to `localhost:8976` on the machine running wrangler, which is not the
machine with the browser; the device grant prints a URL and a code instead.

**Preview URLs are explicitly disabled.** Every version would otherwise get its
own public address bound to the SAME database, so a submission through a preview
URL would land in the real moderation queue and a second public entry point
would exist that nobody was thinking about.

## The app's client, 2026-08-30

`CatalogService` in `:appcore` is the seam. One base URL, one place to point
somewhere else — which is what made the whole migration reversible and was the
condition the sequencing was built around. A test asserts the URL appears in
exactly one file.

`CatalogTransport` is an interface so tests can answer without a network, with
one real implementation on `HttpURLConnection` because that works unchanged on
the JVM and on Android. `java.net.http` is nicer and is API 34+, which would
have meant two implementations of three methods.

### What is verified, and how

**The read path, on a real Android runtime against the deployed service.** The
Community tab was driven on an SDK 36 phone emulator: it fetched
`index.json`, rendered the empty state, and wrote the response to
`cache/catalog/catalog-index.json` — a document the live Worker stamped
`"generated":"2026-08-30T23:17:54.876Z"`. That timestamp is the evidence; an
empty gallery on its own looks exactly like a placeholder.

The discriminating test was taking the network away. With no cache and no
network the tab says the catalog is not answering, naming the real host in the
underlying failure. With a cache it shows the cached list, marked.

**Five live tests** run the client against the deployed service
(`BFG_CATALOG_URL=... ./gradlew :appcore:test --tests '*CatalogLive*'`), and
skip otherwise so a machine with no network does not fail a build.

### What is NOT verified, and cannot be yet

**Submit and report have never run end to end.** The client methods exist and
are tested against the seam, including that a refusal surfaces the service's own
per-field problems rather than collapsing to "failed". But both need a Turnstile
token, Turnstile is not configured, and the service fail-closes — so the furthest
either has gone against the live service is a 403 that arrives as a readable
message. One live test asserts submissions are switched off, and it will FAIL
when Turnstile lands, which is the intended reminder that this paragraph has
gone stale.

**There is no submit or report UI.** Building a share button that cannot work
would be worse than not having one.

### Two things caught by running it

- **A cached empty list was reported as "Nothing shared yet".** That is a claim
  about the catalog which a phone holding a snapshot of unknown age cannot make.
  Online and offline looked identical. It now says it is showing what it last
  downloaded.
- **The failure message was developer speech** — `Unable to resolve host
  "bfg-catalog.bfg-solutions.workers.dev": No address associated with hostname`,
  on a real screen. The transport's cause is now dropped rather than shown.

### A tile fetches its own face

`index.json` carries name, author, engine and two colours — enough to browse,
deliberately not enough to render, because full parameters for ten thousand
faces is not an index. Drawing a tile from the engine and colours alone would
show a face that is not the one somebody submitted. So each tile fetches its own
parameters and renders them through the one rasterizer; a `LazyVerticalGrid`
only composes what is visible, so that is a handful of requests and they come
from the edge cache.

## Still open

- **Turnstile.** `TURNSTILE_SECRET` is unset, so submit and report answer 403.
  That is the fail-closed path working as designed, and it is also the reason
  the service cannot accept anything yet. It needs a widget created in the
  dashboard.
- **Submit and report UI**, once Turnstile exists.
- **Retiring `miopea/bfg-watchfaces-catalog`**, which is LAST. It still hosts the
  app's only working complaint path.
- **The app's submit and report paths.** The service has no client yet: the
  Community tab still reads a local directory and the report sheet still opens a
  GitHub issue. That is step 3 of the sequencing, and step 4 does not begin
  until it is done.

## Release gates, not tasks

Raised in the interview and answered "this isn't released, there isn't anyone to
notify" — correct today, and both become blocking before the app goes public.

- **A route for rights-holders that is not the app.** `MODERATION.md` promises
  to act on IP claims within 72 hours, and a rights-holder is usually not a
  user. When the GitHub repo retires, its issue form goes with it. A published
  email address is the minimum.
- **A working in-app complaint path.** Play's UGC rules require one before an
  app showing user content can ship. The GitHub route covers this today and must
  not be removed before the replacement is live — which is what the sequencing
  above already says.

Neither is needed while the app is on internal testing with one tester. Both are
needed before the Community tab is visible to anyone else.
