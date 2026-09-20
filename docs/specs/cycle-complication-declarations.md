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
