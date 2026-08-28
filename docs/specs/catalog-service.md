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

## Still open

- Whether authors can withdraw a face they submitted. With no account there is
  no way to prove they wrote it, so this may simply not be possible — worth
  deciding rather than discovering.
- Whether the Cosmos DB free tier is still available on the subscription, which
  only the account holder can see.
