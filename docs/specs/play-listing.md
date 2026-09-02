# Play listing and Data Safety

Drafted 2026-09-02 for the top launch gate in `launch-scope.md` §3.2 and §3.4.
Every claim below is read out of the code, with the file named, because the
saved declaration is currently **wrong** and a declaration that does not match
observed behaviour is the single most likely reason to fail review.

## 1. What the app actually does with data

Read from the source, not from memory.

| Call | Where | Carries | Identifies anyone? |
| --- | --- | --- | --- |
| `GET /index.json` | browse the gallery | nothing | no |
| `GET /faces/<slug>` | open one | nothing | no |
| `GET /config` | client id, limits | nothing | no |
| `POST /faces/<slug>/installed` | install counter | **empty body** | no |
| `POST /reports` | report a face | slug, reason, free text | no |
| `POST /faces` | **publish** | face parameters + Google ID token | **yes** |
| `GET /submissions/<id>` | check status | nothing | no |
| `POST /submissions/<id>/withdraw` | take a face back | Google ID token | **yes** |

`CatalogService.kt` lines 131–311.

**Only publishing and withdrawing identify anybody.** Browsing, installing and
reporting are anonymous, and that asymmetry is deliberate — it is what moved the
catalog off GitHub.

### What the service keeps

- **`authorKey` = `SHA-256(salt + Google sub)`** — `auth.ts:162`. The `sub` is
  Google's per-application subject id. The address and the name in the token are
  never written anywhere; only the hash is stored, and only so somebody can
  withdraw their own face.
- **Rate-limit buckets = `SHA-256(salt + route + window + IP)`** —
  `ratelimit.ts:47`. Salted, bucketed by time window, and expiring. **The raw
  address is never stored.**
- **Face parameters** — engine, colours, layout, complication choices. A
  description of a design, not of a person.

### What never leaves the phone

**Photos.** A face using one is `isLocalOnly` (`DialParams.kt:710`) and
`CatalogService.submit` refuses it before touching the network — with a test
asserting the transport is never called. The picker itself is
`PickVisualMedia`, which needs no permission and can see only the chosen image.

## 2. Proposed Data Safety answers

### Collected

| Type | Collected | Shared | Required? | Purpose |
| --- | --- | --- | --- | --- |
| Personal info → **User IDs** | Yes | No | Optional | App functionality |
| **Other user-generated content** (face designs) | Yes | **Yes** — published to a public gallery | Optional | App functionality |

Both are **optional**: everything except publishing works without them.
Encrypted in transit (HTTPS), and there is a deletion route — withdrawing a face
removes it and its author key.

### Not collected

Photos and videos · Location · Contacts · Calendar · Messages · Financial ·
Health and fitness · Audio · Files · Web browsing · Installed apps

Complication values — steps, heart rate, weather — are read **by the watch face
on the watch**, from the system's own providers. They never reach this app's
code and never leave the device.

### The judgement call, flagged rather than buried

`POST /faces` sends a Google **ID token**, which contains the account's email
address and name as claims. The service verifies it, derives the hash, and
discards the rest — nothing else is written.

Play's ephemeral-processing exemption covers data that is processed in memory
and not stored, which appears to fit. **But the transmission does happen**, and
whether that needs declaring as "Email address — collected" is a policy reading,
not a code fact.

**This one is worth ten minutes with Play's own Data Safety guidance before
submitting.** Declaring the email as collected is the conservative answer and
costs nothing but a line on the listing; guessing wrong in the other direction
is a rejected release. It is the only item here that is a judgement rather than
a measurement.

## 3. Store listing

### Short description (80 characters)

> Design your own watch faces. Free, open source, nothing to buy.

### Full description

> **BFG Watch Faces**
>
> Design a watch face on your phone and send it to your Wear OS watch.
>
> Everything is free. No subscription, no adverts, nothing to unlock, no account
> needed to look around or to install a face somebody else made.
>
> **Design it yourself**
> Engraved guilloche patterns — knotwork, botanical, clous de Paris, rosette,
> barleycorn — or brushed metal, carbon and linen. Choose numbers or hands, in
> four styles. Pick your colours. Or use one of your own photos.
>
> **Complications that fit**
> Steps, heart rate, battery, weather, your next event, sunrise and sunset. The
> layout sizes them so the values fit rather than getting cut off.
>
> **A community gallery**
> Browse faces other people made and install them with a tap. Publish your own
> if you want to — that is the only thing that needs a sign-in, and only so you
> can take it back later.
>
> **Your photos stay on your phone**
> A face made from your own picture is never uploaded and cannot be shared.
>
> Open source: github.com/miopea/bfg-watchfaces

### Why this wording

The category's reviews are about billing — surprise subscriptions, refused
refunds — so **"nothing to buy" leads**, and the sign-in is explained where the
suspicion would otherwise land. The photo sentence exists because it is the
first question anyone asks about a photo feature, and because it is a code
guarantee rather than a promise.

No brand names, no "500,000 faces", no superlatives. The library is not the
argument.

### Screenshots

From `DialRenderer`, at real dial size, showing real faces — a guilloche dial, an
analog face with hands, a photo dial, the Studio screen and the community
gallery. `./gradlew :workbench:bake` renders them without needing a device.

## 4. Content rating

No user-to-user messaging, no purchases, no ads, no location, no personal
information in shared content. Expected **Everyone / PEGI 3**.

Faces are pre-moderated by a person before they appear publicly (`MODERATION.md`),
and any face can be reported anonymously from inside the app — worth stating on
the rating questionnaire, because "user-generated content" otherwise reads worse
than it is here: a face is a set of numbers describing a pattern.

## 5. Still to do by hand

- Submit the Data Safety form, after settling §2's judgement call.
- Write the privacy policy URL. `bfgsolutions.net` was agreed as the domain.
- Render and upload screenshots.
- Complete the content rating questionnaire.
- **The clean install test** — `launch-scope.md` §3.1, and the one that has never
  been run.
