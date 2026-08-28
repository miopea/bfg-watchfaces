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

## Recommended implementation

Cloudflare Workers, with D1 for the queue and Cloudflare's own edge cache for
reads. Turnstile for R7.

Chosen because it is the only shape that meets every requirement without a bill:
anonymous writes are trivial, reads are cached at the edge without a second CDN,
a proof-of-humanity check exists that needs no account and no tracking, and
storage for a catalog measured in megabytes is nowhere near any threshold.

**This must be confirmed before it is committed to.** "Free" is a claim about
someone else's pricing page, and the About screen's promise that every part of
the app is free depends on it staying true. Confirm current limits against real
numbers — expected faces, expected gallery views — rather than against the word
"generous".

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

## Open

- Which backend, if not the recommendation above.
- Whether the moderation log is public. It is the cheapest way to buy back the
  auditability that a git history was giving for free.
- Whether authors can withdraw a face they submitted. With no account there is
  no way to prove they wrote it, so this may simply not be possible — worth
  deciding rather than discovering.
