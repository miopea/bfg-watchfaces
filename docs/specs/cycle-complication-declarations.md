# Cycle day: what it costs to ask Google, before it costs anything to build

Scoping only. Nothing here authorises the feature — see swarm `01a0babe` and the
amendment on it. The build is cheap and the permission is not, so the permission
is what gets studied first.

## Where the app starts, verified rather than assumed

`com.bfg.watchfaces` requests exactly four permissions across both modules:

```text
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
com.google.wear.permission.PUSH_WATCH_FACES
com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE
```

There is no Health Connect code anywhere in `:mobile`, `:wear`, `:appcore` or
`:generator` — no `androidx.health`, no `health.READ`, nothing. So the Play
declaration that this app does not handle health data is not merely on file, it
is TRUE, and everything below is about making a different true statement.

## The part that is not a formality

Google's health apps policy does not simply ask you to declare the permission.
It requires a **permission declaration form with a detailed justification, and
approval before publishing**. The permitted use cases are written as letting
people *"directly journal, report, monitor, and/or analyze"* their health
information, or store it and share it with other on-device apps that satisfy
those uses.

**Read that against what this feature actually does, because the gap is the
risk.** This app would not journal, report or analyse anything. It would read
one record type and draw a number on a dial. The honest argument is that a
person glancing at their own wrist IS monitoring their own data, and that the
app is the on-device surface they chose to show it on. That argument may well
be accepted. It is not self-evidently within the wording, and it is decided by
a reviewer rather than by us.

So the cost is not "a review cycle". It is **a form, a justification, an
approval that can be refused, and only then a review cycle** — on an app that
has already spent five, three of them on Wear branded launch alone.

## What would change, concretely

- **Health apps declaration**: from "does not handle health data" to a declared
  health app, with the justification above.
- **Data Safety**: a new collected data type in the Health and fitness
  category, with its retention, sharing and security answers. It must say the
  data is not shared with third parties and not used for ads, which is true and
  costs nothing to assert.
- **Manifest**: `android.permission.health.READ_MENSTRUATION` on `:mobile`
  only. The watch never reads Health Connect; it receives a DATE and computes
  the day number itself. (This said "a number" until 2026-09-19; the build spec
  settled on sending the start date so the value cannot go stale. The watch
  still touches no health data either way.)

## What is already true and worth keeping true

The policy's prohibitions are the easy half here. No transfer or sale to third
parties, no advertising, no use of the data for anything but the stated purpose
— the app already has no advertising, sells nothing, and the community catalog
is parametric, so no health value could reach it even by accident. The standing
promise survives this feature unchanged, PROVIDED the derived number is treated
the way photos already are: `DialParams.isLocalOnly` exists precisely so a face
carrying private content cannot be shared, and a cycle slot must be covered by
the same rule or an equivalent one. That is a build constraint, recorded here so
it is not discovered late.

Minimum-necessary also applies and is easy to satisfy: one record type, reduced
on the phone to a single integer. The raw records never leave Health Connect.

## Settled on 2026-09-19

**The operator authorised it**, having been offered and having declined a
version with no Health Connect at all (she types a start date; no permission, no
declaration, no approval to wait on). He took the harder route knowingly,
because the manual version drifts as soon as she stops maintaining it.

The disclosure SHAPE was already settled — decision `01a0ba48`, a plain
unlabelled number like any other complication. Everything else the build needs
is in [`cycle-complication.md`](cycle-complication.md).

The cost stated above has not changed and is now a cost being deliberately
spent: a form, a justification, an approval that can be refused, and only then a
review cycle. **The form goes first.** Nothing past the point of no return gets
built until Google has answered.

## The justification, drafted

Written 2026-09-20, once the operator confirmed on his wife's own devices that
Google Health shows cycle day on neither the home screen nor the watch face, and
once the data path was verified end to end. **Not submitted.** Filing it is an
operator action: it is a statement to Google that cannot be withdrawn, and its
approval can be refused.

Every sentence below is checked against `cycle-complication.md`. If the build
changes, this changes with it — a justification that describes something the app
does not do is worse than none.

**Amended 2026-09-20, and this is exactly the failure the sentence above was
written to catch.** The justification described reading "one field" and deriving
"a single number", which was true of the build it was written against and became
false within a day. The feature grew: the carousel tile has a whole screen
rather than a 40px slot, so it now also shows how long the last period ran, the
average interval between recent starts, and which day of the period it is while
one is ongoing. All of it comes from records already read under the same single
permission — that was the criterion for adding it — so nothing about the
PERMISSION changed. But three sentences of the justification stopped being true,
including the one asserting no analysis is performed, and an average is the
closest thing in the feature to an analysis.

**Nothing was submitted in the meantime**, so no inaccurate statement reached
Google. The lesson is the ordering: this file has to be re-read at the moment of
filing, not at the moment of drafting.

## REVISION DRAFTED 2026-10-12 — the app would predict the next period

**NOT FILED. This is a draft and the paragraphs below have not been submitted.**
The approved declaration still stands as written, and the app still matches it,
because no countdown code exists. Filing needs its own operator go at the moment
of filing, with this file re-read then — which is the 2026-09-20 lesson directly
above, applied to itself.

### What changed and why

The operator's wife asked, on 2026-10-12, for the cycle tile to show how many
days until her next period. That is a reasonable request and her own health app
does it. It is also the exact thing the approved justification denies twice:

> It does not predict future periods ... no future date is shown or computed
> anywhere in the app.

So the filing has to change before the build does, not after.

### The declared category

Operator decision, 2026-10-12: **declare it accurately, whatever that costs.**
If the form offers a period or menstrual tracking category, that is the one,
rather than keeping the "Other" that submission 17 was approved under.

The operator reasonably asked whether the category needs to change at all,
"since we are NOT storing data, just doing a calculation". Two corrections, both
of which belong in the filing rather than in a conversation:

1. **Storage is not what the category turns on.** Google's permitted uses are
   framed as journalling, reporting, monitoring and ANALYSING health data. A
   projection is analysis whether or not anything is persisted, and the Data
   Safety form asks about collection and sharing separately from retention.
2. **The app does persist derived values, and always has.** `cycle-start.txt`
   and `cycle-facts.txt` sit in the watch's own app storage, and `CycleState`
   keeps the same on the phone. The raw `MenstruationPeriodRecord` never leaves
   Health Connect — that part is true and stays true — but "we store nothing"
   would be an inaccurate sentence to file, and inaccurate sentences in this
   document are the whole reason this section exists.

### Where it may appear

**The tile only.** Operator decision, 2026-10-12. The dial complication stays a
bare unlabelled number by decision `01a0ba48`, and a "4" read at a glance meaning
"period due in 4 days" is indistinguishable from the cycle-day "4" already
there. `cycle-complication.md` already rejected a ring design for inviting
exactly that misreading; a bare future-date number on the same surface is worse.
The tile has a whole screen and can say what the number means.

### How much history before anything is shown

**Three gaps, which is four logged periods.** Operator decision, 2026-10-12,
with the condition that the tile **says clearly when there is not enough data
yet** rather than going quiet.

One gap more than the average requires, because a countdown is a stronger claim
than an average: it names a date rather than describing the past. The average
bar was itself raised to two gaps on 2026-10-03 after a watch showed "Average
cycle 27 days" to someone who had just started logging, and the wearer's own
reading was that it "doesn't make any sense".

Already true and needing no change: `CycleSource` reads a year of records, so a
year of her own logged history is already in scope. Four periods at a typical
interval is about four months, comfortably inside it.

### The replacement paragraph

This replaces the "no prediction of any kind" paragraph. Everything else in the
justification stands.

> From the same records the app also projects the start of the next period, as a
> number of days, and shows it on the watch tile. The projection is the mean
> interval between the user's own recent logged period starts, added to her most
> recent logged start. It is arithmetic on dates she entered herself and nothing
> else: no model, no population data, no inference about her body.
>
> It is shown only when the user has logged at least four periods, giving three
> intervals to average. Below that the tile states that there is not yet enough
> logged history to estimate, and shows no number. The estimate appears on the
> app's tile only, never on a watch face complication, so it is never displayed
> as an unlabelled figure.
>
> The app still does not identify fertile windows or ovulation, does not state a
> cycle phase, and offers no health guidance, diagnosis or medical
> interpretation of any kind.

### What is NOT settled

- The exact wording the tile uses below the minimum, and whether it names the
  number of periods still needed. **ACCEPTED 2026-10-17 as drafted** — operator
  decision `01a147d8-e53c-7241-b73b-ee48fe6b6fb8`, "Accept both strings as
  drafted". Drafted 2026-10-16:

  > Next period
  > Log 2 more periods to see an estimate

  with the number counting down as she logs, and the singular form "Log 1 more
  period to see an estimate" at the last step. It names the number because the
  operator's condition was that the tile is "clear when we don't have enough
  data yet to calculate", and a bare "not enough yet" is not clear — it does not
  say how much more, so it reads as broken rather than as waiting. It avoids
  "data", "history", "logged entries" and "calculate" for the reason recorded
  app-wide: these are strings a person reads on a wrist, not developer language.
  This has NOT been checked against the tile's actual line width.
- ~~Whether the Console form's category list contains a period option.~~
  **ANSWERED by driving the Console on 2026-10-12. It does.** See below.
- Whether re-filing disturbs the live production release. CLAUDE.md records that
  App content declarations are a reviewed surface and that changing one while a
  review runs cancels and restarts it; no review is running now, but this should
  be checked again at the moment of filing.

### What the form actually looks like, read 2026-10-12

Driven in the Console, nothing changed and nothing submitted. Play Console →
Monitor and improve → Policy and programs → App content → Actioned → Health
apps → Manage. The declaration's last edit is recorded as Sep 23, 2026.

It is **three steps**, not one: `1 Health features in your app`, `2 Health data
permissions`, `3 Regional requirements`. Step 1 confirms the manifest permission
it detected, shown verbatim as `android.permission.health.READ_MENSTRUATION`.

**"Period tracking" EXISTS as a checkbox**, under the Health and fitness group:

```text
Health and fitness
  [ ] Activity and fitness
  [ ] Nutrition and weight management
  [ ] Period tracking          <- exists, currently UNCHECKED
  [ ] Sleep management
  [ ] Stress management, relaxation, mental acuity
Medical
  [ ] Diseases and conditions management ...
```

So the 2026-09-20 note that this form is "a feature checklist" is still right
about its shape, and the thing that has changed is that the app now holds the
permission, so step 1 lists it and steps 2 and 3 exist.

**The "Other" free-text box is 250 characters, and currently holds 226 of them.**
Not the 10,000-character justification CLAUDE.md describes — that belongs to
step 2, `Health data permissions`, which I did not open because reaching it
means clicking Next and I was not willing to risk persisting step 1 on a
published app. So the long replacement paragraph drafted above targets step 2
and has NOT been confirmed to fit anywhere yet.

### The question this raises, which was not asked before

Is **Period tracking** the accurate box for the app AS IT IS TODAY, countdown or
no countdown?

The app already reads `MenstruationPeriodRecord` and displays the day of the
cycle, the day of the period while one is running, the last period's length and
the average interval. That is a reasonable description of period tracking. It
was declared under "Other" instead, and Google approved that on 2026-09-23.

If "Other" was simply the wrong box from the start, then ticking "Period
tracking" is a CORRECTION to the app as it exists, not a declaration of a
feature that does not exist — and the objection recorded on 2026-09-20 against
filing first ("declaring a period-tracking feature the app did not have") would
not apply to it, because the app does have one.

That is a genuinely different question from the countdown and it is the
operator's to answer. It is also the only part of this that could honestly be
filed before any code is written.

### FILED 2026-10-12 — the category, and only the category

The operator submitted it himself. I ticked **Period tracking** in step 1 and
was then blocked from advancing the form by this session's permission layer
("Unrequested Commit in a Connected App"), so he clicked through the remaining
steps and submitted.

Verified afterwards on a fresh page load, not from intent:

```text
Health and fitness
  [x] Period tracking
Other
  [x] Other — 226/250, text UNCHANGED
```

And on the publishing overview:

```text
Changes in review
  App content / Health apps — "Complete Health apps declaration"
  Running quick checks for commonly found issues, up to 14 minutes remaining
Managed publishing: off.  Last published: September 23, 2026.
```

**The description was deliberately left alone**, and it still reads:

> Displays the day of the user's menstrual cycle on a watch face complication
> and a watch tile, read from period dates they already keep in Health Connect.
> Read-only and display-only: no logging, no prediction, no health advice.

That is still TRUE, because the countdown does not exist. Ticking "Period
tracking" while saying "no prediction" is coherent rather than contradictory:
the app does track periods, it does not predict them. So this filing is a
correction of the CATEGORY to match the app that already shipped, and it
declares nothing the app does not do.

**What is therefore still owed before a countdown can ship.** This filing does
NOT cover it. All of the following remain:

- The 226-character step 1 description still denies prediction and would have to
  change.
- The long replacement paragraph drafted above targets step 2, `Health data
  permissions`, which nobody has yet opened. Whether a 10,000-character
  justification box exists there is still unconfirmed by observation.
- Step 3, `Regional requirements`, is unexamined.
- And the code does not exist, which is why none of the above was touched.

So the ordering recorded on 2026-09-20 — build it, then declare what is actually
there — is intact. This filing moved only the part that was already true.

### READ 2026-10-16 — the filing cleared, and the complete category list

Read in the Console, nothing changed and nothing submitted.

**The 2026-10-12 filing is through.** The Health apps row sits under
**Actioned**, last edited Oct 12. **Need attention** is empty — "You're all
caught up". The app dashboard says "You have no unpublished changes". Policy
status reads "No issues found". The "Changes in review / running quick checks"
banner recorded above, on the day of filing, is gone.

**The App content page shows no approval state — but Submission activity
does, and that is where to look.** Corrected the same day, 2026-10-16, after
the paragraph below was written from the wrong page.

Expanding the Health apps **Actioned** row gives exactly one sentence, "You
told us about the health features in your app", with no date, status or verdict
beside it. That is true, and it is what made "no approval state anywhere" look
right. It is the wrong page.

`Publishing overview → Submission activity` carries a dated verdict per
submission, declaration-only submissions included:

```text
Submission 18
  Source             Play Console
  Change             App content / Health apps
                     "Complete Health apps declaration"
  Changes submitted  Oct 12, 2026, 3:01 PM
  Status             Published — On Oct 16, 2026, 4:57 AM
```

So the 2026-10-12 filing is not merely "actioned": it was **approved and
published at 04:57 on 2026-10-16**, four days later, and the publishing
overview's "Last published" moved from September 23 to October 16 to match.

**What this corrects, and what it does not.** It corrects "the Console exposes
no approval state", which was written from the App content page alone. It does
NOT revive the health-gate measurement of 2026-10-12: that measurement was an
`edits:commit` succeeding while submission 18 was pending, and submission 18
being approved four days LATER confirms it was genuinely pending at the time
rather than quietly already through. The three-condition rule in `CLAUDE.md`
still stands, and is still not restated from one measurement.

The API remains without a review-state field (enumerated 2026-09-23), so this
is a Console-only reading.

**And it makes the countdown HARDER to file, not easier.** Submission 18
contained the Health apps declaration and nothing else, and the only thing that
changed inside it was the category tick. The 226-character step 1 description
was deliberately left alone on 2026-10-12 and still reads:

> Displays the day of the user's menstrual cycle on a watch face complication
> and a watch tile, read from period dates they already keep in Health Connect.
> Read-only and display-only: no logging, no prediction, no health advice.

So "no prediction" is now the Google-APPROVED text, as of 2026-10-16, rather
than merely the filed one. A countdown contradicts it in as many words, and
shipping one without re-filing would make an approved declaration false. The
approval does not open the door to the countdown; it is the door.

**The full checkbox list**, which was recorded truncated above and matters for
the countdown question, because it is where a prediction feature would have to
be declared:

```text
Health and fitness
  [ ] Activity and fitness
  [ ] Nutrition and weight management
  [x] Period tracking                     <- ticked 2026-10-12
  [ ] Sleep management
  [ ] Stress management, relaxation, mental acuity
Medical
  [ ] Diseases and conditions management
  [ ] Clinical decision support
  [ ] Disease prevention and public health
  [ ] Emergency and first aid
  [ ] Healthcare services and management
  [ ] Medical device apps
  [ ] Mental and behavioral health
  [ ] Medical reference and education
  [ ] Medication and treatment management
  [ ] Physical therapy and rehabilitation
  [ ] Reproductive and sexual health      <- the one to think about
Human subjects research
  [ ] Research studies, clinical trials, and patient communities
Other
  [x] Other — 226/250, text unchanged
```

**`Reproductive and sexual health` is the box nobody had considered.** It sits
under Medical, not under Health and fitness, and a countdown to a future period
looked closer to it than a day count is. Settled below, 2026-10-16: it is not
the right box.

Steps 2 (`Health data permissions`) and 3 (`Regional requirements`) are still
unopened, for the same reason as before: reaching them means clicking Next on a
published app's live declaration.

### SETTLED 2026-10-16 — the category needs no change, and the real constraint is 250 characters

Read from Google's own Help Center page for this form — the one linked beside
the checkbox list, Play Console Help answer `14738291`, "Provide information
for the Health apps declaration form" — rather than reasoned from the category
names.

**Period tracking already covers prediction, in Google's own words:**

> Tools that empower users to monitor and manage their menstrual health. These
> apps provide features such as menstrual cycle tracking, ovulation tracking
> and prediction, and fertility awareness.

**Reproductive and sexual health is a different kind of app:**

> Tools for managing reproductive health issues, fertility treatments or sexual
> health disease management, and providing resources for sexual health
> education.

So the answer is **no second box.** Google names *ovulation prediction* inside
Period tracking, and projecting the next period's start is a weaker claim than
that — it is the same arithmetic on the same records, one step earlier in the
cycle. Reproductive and sexual health describes conditions, treatments, disease
management and education, and this app does none of the four.

The form is all-that-apply, not pick-one — "Declare all the health features that
your app offers to its users" — so the test is whether the app offers a feature
in that category, and for Reproductive and sexual health it plainly does not.
Ticking it anyway would not be cautious. It would be inaccurate, which is the
one thing operator decision 2026-10-12 ruled out.

**Which means the category half of the re-file is ALREADY DONE.** Submission 18
ticked Period tracking on 2026-10-12 and Google approved it on 2026-10-16, and
the category Google approved is one whose published definition includes
prediction. Nothing about the countdown requires a category change.

**What is left is one sentence, and it has 24 characters of headroom.** The step
1 free-text box is 250 characters and currently holds 226. That is the whole
remaining obstacle in step 1, and it had not been noticed as a constraint:

> Displays the day of the user's menstrual cycle on a watch face complication
> and a watch tile, read from period dates they already keep in Health Connect.
> Read-only and display-only: no logging, no prediction, no health advice.

Drafted replacement, **243 characters**, measured not estimated. **ACCEPTED
2026-10-17 as drafted**, decision `01a147d8-e53c-7241-b73b-ee48fe6b6fb8` —
which accepts the wording and files nothing:

> Shows the day of the user's menstrual cycle on a watch face and tile, from
> period dates they keep in Health Connect. The tile also estimates the next
> period's start by averaging their own logged cycles. Read-only: no logging,
> no health advice.

What each cut bought, since the budget is tight enough that the reasoning
matters: `Displays` → `Shows`, `a watch face complication and a watch tile` →
`a watch face and tile`, `read from period dates they already keep` → `from
period dates they keep`, and `Read-only and display-only` → `Read-only`. What
is deliberately KEPT: `by averaging their own logged cycles`, because that is
the sentence that distinguishes arithmetic on her own entries from a model, and
it is the single most load-bearing clause in the whole declaration; and `no
health advice`, because that is the line the app must never cross. What is
deliberately DROPPED: `no prediction`, which is the point.

**Still unconfirmed, and it is the same gap as before:** the long replacement
paragraph drafted earlier in this file targets step 2, `Health data
permissions`, and nobody has opened step 2. Whether a 10,000-character
justification box exists there is still a reading of CLAUDE.md rather than an
observation. Step 1 can now be filed within budget regardless, which was not
true an hour ago.

### BEFORE FILING, 2026-10-17 — the privacy policy denies prediction too

**Read this before filing anything. Filing the accepted sentence on its own
would put two contradictory documents in front of the same reviewer.**

The privacy policy declared to Play is not in this repo. `docs/privacy.html`
only redirects; the policy itself is
`bfg-solutions/src/app/products/watchfaces/privacy/page.tsx`, served at
`bfgsolutions.net/products/watchfaces/privacy`, "Last updated: September 2026".
Two of its sentences stop being true the moment the countdown exists:

1. Lines 90–91: "The app makes no predictions: it does not estimate a future
   period, a fertile window or ovulation". The accepted step 1 sentence says
   the tile estimates the next period's start. A reviewer reading both reads a
   contradiction. Submission 16 was rejected over the privacy policy, and the
   redirect comment in `docs/privacy.html` records how: the document a reviewer
   read described a different app.
2. Lines 100–102: "Your phone sends three values". `CycleFacts` carries exactly
   three — the last start, its end, the average gap — and none of them is a
   count of periods. The accepted below-minimum wording, "Log 2 more periods",
   needs that count on the watch, so a fourth value has to cross, or the phone
   has to send the shortfall itself. Either way the sentence becomes false.
   Which of the two is a build-time choice; that the policy changes is not.

The fertile-window and ovulation half of sentence 1 stays true and should stay
in the policy: the app still estimates neither.

**`CycleFacts` itself says the same thing in its KDoc** — "All of this is
description, none of it is prediction", and "no change to the Play declaration".
That is a code comment, not a public statement, so it changes when the code
does, not before.

### The order, and why it is this order

Operator decision `01a1313f-675c-7fc0-a288-9a3e486f2307`, "Yes, re-file the
declaration first", governs. The objection recorded on 2026-09-20 against filing
first was about declaring health features for an app that had NONE, and putting
"collects health data" on a public listing while it collected none. That is not
this case: the app already reads period data, already declares Period tracking,
and Google's definition of that category already includes prediction. Filing
first here puts one clause of one description ahead of the code, in the
direction of declaring more rather than less. An earlier line in this file
saying the 2026-09-20 ordering is "intact" predates that reading and is
superseded by it.

1. **Submission 19 must leave review first.** Phone 104 and watch 1049 went to
   production review on 2026-10-17. App content is a reviewed surface, and
   changing it while a review runs cancels and restarts that review. Readable
   only in the Console, under Publishing overview → Submission activity.
2. **The policy and the declaration change together.** The policy is a public
   page on the company site, in another repo; it should say the same thing the
   declaration says on the day the reviewer reads both.
3. **Then the code, then a release.** The live app is behind both documents
   for that window. Both overstate rather than deny, which is the safer
   direction, and both agree with each other, which is what a reviewer checks.

**Who presses the buttons.** On 2026-10-12 this session's permission layer
refused to advance the Health apps form ("Unrequested Commit in a Connected
App") and the operator clicked through it himself. Plan on that again. Step 2,
`Health data permissions`, has still never been opened; whoever files will see
it for the first time then, and the long paragraph drafted above is ready for it
if it has a box.

### Permission requested

`android.permission.health.READ_MENSTRUATION`, on the handheld module only. No
`WRITE_` permission of any kind is requested, and none is needed: the app never
writes to Health Connect.

### Why the app needs it

> BFG Watch Faces lets a person design a watch face for their own Wear OS watch.
> One of the things a face can show is the current day of the user's menstrual
> cycle, alongside the other readings a watch face carries such as steps, heart
> rate and battery.
>
> The app reads one record type, `MenstruationPeriodRecord`, over the past 365
> days, and uses its start and end dates. From those it derives, and displays to
> the user on their own devices: the current day of the cycle, the day of the
> period while one is ongoing, the length of the most recent period, and the
> average interval between recent period start dates. No other record type is
> read, and the app never writes to Health Connect.
>
> The app performs no prediction or interpretation of any kind. It does not
> predict future periods, does not identify fertile windows or ovulation, does
> not state a cycle phase, and offers no health guidance. The average interval is
> descriptive arithmetic over dates the user entered herself, presented as a
> past-tense fact about her own logging; it is never used to project a future
> date, and no future date is shown or computed anywhere in the app.
>
> This falls within the permitted use of allowing a user to monitor their own
> health information. A person glancing at their own wrist is monitoring their
> own data, and the watch face is the on-device surface they chose to display it
> on. The app is the tool they use to put it there.

### Where the data goes, stated plainly

> The raw records never leave Health Connect. The handheld app reads them and
> sends to the user's own paired watch, over the Wear OS Data Layer — a direct
> device-to-device channel — only: the start date of the most recent period, its
> end date, and the average interval in days. The watch derives every displayed
> number from those locally. No record, and no field other than those three,
> ever leaves the handheld device.
>
> No menstrual data, and no value derived from it, is transmitted to any server,
> to the developer, or to any third party. The app has no advertising, sells
> nothing, and operates no analytics on this data. The app's community feature,
> where users share watch face designs, transmits only design parameters such as
> colours and pattern settings; a design that uses a cycle complication is
> excluded from sharing entirely.

### The disclosure that must not be omitted

> On the watch the values are shown in two places, both belonging to this app:
> a tile in the watch's own tile carousel, which the user adds deliberately, and
> a complication on a watch face. Tapping the tile opens the cycle section of
> the user's existing health app on the paired phone; it passes no data, it
> launches an app.
>
> On the watch, the derived day count is published as a standard Wear OS
> complication data source. This is the platform mechanism by which a watch face
> displays a value, and it means the number is technically readable by other
> watch faces the user has installed on their own watch. No raw menstrual data is
> exposed this way — only the single derived day count, and only on the user's
> own device.

That paragraph is the one it would be easiest to leave out and the one most
likely to matter. It is the consequence of building this as a provider service,
which `cycle-complication.md` explains is the only workable shape. Omitting it
would make the rest of the declaration false.

### Data Safety answers

**The "Shared" row is the one to get right, and it is not obvious.** Read the
form's own definitions before answering it: Data Safety requires disclosing data
*"transferred from your server to a third party, or transferred to another third
party app on the same device"*. Publishing the day count as a Wear OS
complication data source makes it readable by other watch faces on the wearer's
own watch, which is literally that.

There is a countervailing exemption — sharing a person **initiates themselves**
is exempt, and pointing a watch face at a complication source is a deliberate,
specific act by the wearer. So an argument exists for "No".

**Answer "Yes" anyway, and describe it.** Under-declaring is the failure that
costs; over-declaring costs a sentence. The disclosure paragraph in the health
justification above already says it plainly, and an answer here that contradicts
that paragraph is worse than either answer alone.

| Question | Answer |
| --- | --- |
| Data type | Health and fitness → Health info |
| Collected | No — the data is never sent to a server |
| Shared | Yes — readable by other watch faces on the wearer's own device, via the standard complication mechanism, after she points one at it. No third-party servers, no developer access |
| Processed ephemerally | No — the derived dates and the average interval are stored on the watch |
| Required or optional | Optional; the app is fully usable without it |
| Purpose | App functionality |
| Used for advertising | No |
| Encrypted in transit | Yes — Wear OS Data Layer |
| User can request deletion | Yes — removing the complication or revoking the permission |

### Before submitting

- Re-read the permitted use-case wording on the live policy page. It was read on
  2026-09-19; the pages change and the wording is the whole argument.
- Confirm the build matches the description, **at the moment of filing**. It
  drifted once already, between the drafting and now, and the drift was in the
  direction of the app doing MORE than the form said. Re-read `CycleSource`,
  `CycleFacts` and `CycleTileService` against the three quoted blocks above;
  those are the three files that decide whether this document is true.
- Confirm nothing in the app computes or displays a FUTURE date. That is the
  single line that separates this from a prediction feature, and it is the one a
  well-meaning addition would cross without noticing.

### Where this actually gets filed, corrected 2026-09-20

The console was driven and the earlier assumption here was wrong.

**Play Console → App content → Health apps is a FEATURE CHECKLIST**, plus a
"Regional requirements" step which currently says none apply to this app. The
box is **"Period tracking"** under Health and fitness
(`POLICY_RESPONSE_CHOICE_ID_HEALTH_CYCLE_TRACKING`). It is mutually exclusive
with "My app does not have any health features" — while that one is ticked, all
eighteen feature boxes are `disabled`, which looks exactly like a broken page.
Untick it first.

**There is no justification field in that form.** So the wording drafted above
does not go there, and the "approval that can be refused" this document warned
about is not what that form produces. Where the `READ_MENSTRUATION` justification
is actually demanded — at bundle review, or on a separate Health Connect form —
is **not yet established**, and the wording remains worth having whenever it is
asked for.

**Saving stages; it does not submit.** A save lands in Publishing overview as
"changes not yet submitted for review", and a separate button sends them. That
is the same save-versus-commit distinction `CLAUDE.md` records for the API.

**The original "file before building" order was abandoned**, deliberately, on
2026-09-20. It rested on the premise that filing would buy an early approve or
refuse; it does not. Declaring a period-tracking feature the app did not have —
and, worse, putting "collects health data" on a public store listing while the
app collected none — would have been a false statement to find out nothing. The
feature was built first instead, so every answer above describes something real.

## What to check before acting on any of this

Google's health policy pages change, and this was read on 2026-09-19. Re-read
the permission declaration requirements and the permitted use-case wording
before filling anything in; the wording is the whole argument.
