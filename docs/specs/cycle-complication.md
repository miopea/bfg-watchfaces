# Cycle day on the dial

**Authorised by the operator on 2026-09-19**, in an interview that settled every
question below. The scoping that preceded it — what Google's health policy costs
to ask for, and what it forbids afterwards — is
[`cycle-complication-declarations.md`](cycle-complication-declarations.md) and is
still the governing document for anything touching Play. This one is the build.

The operator was offered a version with no Health Connect at all: she types a
start date, nothing is read from anywhere, no permission and no declaration. He
chose the Health Connect route knowingly, against that recommendation, because
the manual version drifts the moment she stops maintaining it. That is recorded
here so nobody re-opens it as if it were an oversight.

## What it shows

`Day 14`. A count of days since the most recent recorded period start.

Nothing else. **No phase, no fertile window, no prediction of any kind**, and
this is a policy boundary rather than a product preference. A day count is
arithmetic on a date she entered herself; "fertile" is an inference about a body
drawn from that date, which reads as prediction to a reviewer. Phase was offered
and declined for v1. If it is ever wanted, it goes through its own review cycle
after the count has survived one intact.

The wording follows decision `01a0ba48`: a plain unlabelled number, like every
other complication on the dial.

**Amended 2026-09-20 on the icon half.** The decision also said "no icon that
announces what it is to anyone glancing over her shoulder", and that half no
longer holds. The number itself shipped as "Day 18" rather than "18" — the rule
lived here and nothing executed it — and the person whose wrist it is raised it.
Fixing the wording, the operator also chose to add a mark, on the dial and on
the carousel card, having been shown that a mark gives back the recognisability
that dropping the word removes.

The mark is an open ring: a thin circle with a gap at the bottom. It was chosen
over a crescent (which reads as night or sleep to everyone else on a watch), a
ring with a dot marking position (unmistakably a cycle mark, and a nearly-closed
ring invites reading as "period due", which is prediction) and a shaded disc
(invisible at complication size). To anyone who does not already know, it is a
ring.

`CycleDayTest` now sweeps 400 days and fails on any label carrying a letter, so
the bare-number half is executed rather than written down.

## The data path is verified, and it decides whether this works at all

Confirmed on 2026-09-19, and it was the assumption the whole feature rested on.
Health Connect is a STORE, not a source: our complication reads
`MenstruationPeriodRecord`, and something has to have written it.

**The operator's wife logs her cycle in the Google Health app, and Google Health
writes Periods to Health Connect.** Google's own documentation lists, under
Cycle health, exactly three writable types: **Periods, Flow, Intermenstrual
bleeding**. `Periods` is the interval record whose `startTime` this feature
needs. The path is real:

```text
Google Health (she logs a period)
  -> Health Connect        (Cycle health: Periods)
  -> :mobile READ_MENSTRUATION
  -> latest MenstruationPeriodRecord.startTime
  -> the watch computes "Day 14"
```

Three things to know about it, none of them obvious:

- **Fitbit and Google Health can only WRITE to Health Connect, never read.** The
  path is one-way by design. That is fine here — we are the reader — but it
  means Google Health can never show anything WE write, so there is no round
  trip to design.
- **She has to allow it.** Google Health writes only the data types she has
  toggled on for Health Connect. A blank complication on a phone that clearly
  has the data is most likely this, and the phone's explanation should say so
  rather than blaming the permission we asked for.
- **Cervical mucus, ovulation test and sexual activity are READ-ONLY** in Google
  Health and cannot be written to Health Connect. Irrelevant to a day count, and
  worth knowing before anyone designs a second cycle slot on the assumption that
  everything in the app is reachable.

### It does not generalise to other cycle apps

The feature works for HER because of the app she uses. It is not universal, and
the spec should not pretend otherwise:

- **Clue has no Health Connect integration at all** — its support material says
  it cannot export or sync to other apps on Android. A Clue user would see this
  complication permanently blank, with nothing in our app able to explain why.
- **Flo pairs with Health Connect on Android**, but its help article does not
  state the direction and a direct read did not resolve whether it writes
  menstruation. Unverified.

So the empty state is not an edge case for a minority. For anyone whose tracker
does not write to Health Connect it is the ONLY state, forever. That raises the
bar on what the phone says when there is no data: "no records found" is wrong
and blaming our own permission is worse. It has to be able to say that the app
she tracks in may not be sharing this, and point at the Health Connect setting.

## Where the work happens

Three moving parts, and the split is deliberate.

| Part | Holds | Does |
| --- | --- | --- |
| `:mobile` | `android.permission.health.READ_MENSTRUATION` | Reads Health Connect, finds the latest period start, sends the **date** |
| `:wear` | nothing sensitive | Stores that date, computes the day number, serves it as a complication |
| `:generator` | nothing | Unchanged — the face just points a slot at the provider |

**The phone sends a DATE, not a number.** This was settled against the
alternative of a daily `WorkManager` push, and it is the better shape: a date is
correct forever without anything waking up, so the number cannot go stale when
she stops opening the app — which, for a watch face, is most days. It also means
no background job and no reliance on the phone being reachable at midnight.

> The declarations doc previously said "the watch receives a number". It
> receives a date. Everything else in that document is unaffected: the watch
> still never touches Health Connect, and the raw records still never leave the
> phone.

**The watch computes the day itself**, in Kotlin, from its own local date. Local
and not UTC — a UTC rollover ticks the day over at the wrong hour and looks
broken to the only person who would notice.

## It is a complication provider, and that has a consequence

Doing date arithmetic on the watch means a provider service, the same shape as
the existing `PhoneNoteService`. There is no alternative worth having: Watch
Face Format has no usable date arithmetic, so a face computing this itself would
need the start date baked into its XML, and every new cycle would mean rebuilding
and re-sending the whole face.

**A registered provider is selectable by any face on the device.** That is
already true of the note today. The operator chose to accept it rather than
advertise the capability, so:

- Our own faces point at it by default; it is not promoted as a feature other
  faces can use.
- The Data Safety declaration must nonetheless state that the value is available
  to other apps on the device. It is, and saying otherwise would be false.
- An option to register it so that only our package may bind it was raised and
  **not** promised, because nobody has verified that Android permits it. If it
  turns out to be possible it is an improvement, not a commitment.

## The empty state is the first thing she will see

Before the permission is granted, and after it is granted but before there is a
record, the slot shows **an em dash** — the same thing every other slot shows
with no data.

The explanation lives on the phone: one line in Studio saying why it is blank,
with the button that asks for the permission. This is the same split
`ActivationConsent` already uses, and for the same reason — a round watch screen
is a poor place to read anything careful.

Rejected: showing "Set up" in the slot (app chrome on a watch face, and it
cannot be tapped through to anything useful), and hiding the slot until it has
data (a slot she deliberately chose silently disappearing is the failure this
repo keeps paying for).

## The permission is asked for at the point of use

When she adds the cycle complication to a slot — not at first launch, and not on
some settings screen. An app that asks for menstrual data before being told what
for has no answer to "why".

## A cycle face must not be shareable

`DialParams.isLocalOnly` exists so a face carrying private content cannot reach
the community catalog, and it was built for imported photos. **A face with a
cycle slot must be covered by that rule or an exact equivalent.**

The leak here is subtler than a photo and worth stating plainly: a shared face
carries `providers[pos]`, the provider's component name. A face naming a cycle
provider tells everyone who downloads it that its author tracks a cycle, even
though no health VALUE is in the JSON at all. Parametric sharing does not
protect against this, because the component name is a parameter.

## What is not verified, and has to be

None of this has run. In particular:

- **Whether a complication provider can schedule its own midnight refresh**
  reliably on Wear OS 6, or whether the day number lags until something else
  pokes it. `PhoneNoteService.notifyChanged` is the existing mechanism for
  pushing an update; whether a time-based one works the same way is unknown.
- **Whether menstruation records are readable at all** through Health Connect on
  the operator's phone with only `READ_MENSTRUATION`, and what the record type
  actually returns for "period start".
- **Whether Google approves the permission declaration.** It is a form with a
  justification and an approval that can be refused — see the declarations doc.
  Nothing should be built past the point of no return until that is answered.

## Built on 2026-09-20

What exists, and where:

| Piece | Where | What it does |
| --- | --- | --- |
| `CycleDay` | `:appcore` | The arithmetic. Day 1 is the first day of the period. Stores and parses the date. Pure, 9 tests. |
| `CycleSource` | `:mobile` | The ONLY code that touches Health Connect. Reads `MenstruationPeriodRecord`, returns one `LocalDate`. |
| `CycleSender` | `:mobile` | Sends that date over `MessageClient`. Sends an EMPTY payload to clear. |
| `CycleSetup` | `:mobile` | The explanation and the permission, shown only once a slot points at the cycle source. |
| `CycleDayService` | `:wear` | The complication provider. Derives the number from the stored date on every request. |
| `WatchLink.CYCLE_START_PATH` | `:appcore` | The one wire path. |

Three things worth knowing that the plan above did not anticipate:

**Day 1 is the first day of the period, not the day after.** That is what the
phrase means to everyone who uses it, and an off-by-one here is invisible to us
and obvious to her. Pinned by a test.

**A large day count is shown rather than hidden.** No staleness ceiling. If the
last logged period was ninety days ago it says `Day 90`, which is strange and
TRUE, and tells her the tracking or the sync has lapsed. A slot she deliberately
chose going quietly blank is the failure this repo keeps paying for.

**A future start date shows nothing.** The phone and the watch can be in
different timezones and an instant near midnight lands on different dates on
each. `Day -3` on a wrist is worse than a blank.

### Sharing is blocked through `isLocalOnly`

A face naming the cycle provider is now `isLocalOnly`, alongside a face carrying
an imported photo. `CatalogService` already refuses anything local-only, so
extending that one property covers every path that could publish — rather than a
second rule at the share button which one path would eventually not have.

`PhoneNoteService` is deliberately NOT on that list: the note is whatever she
typed, and the provider's name discloses nothing she did not choose to write.

## Order of work

1. The Play permission declaration form, because it can be refused and
   everything else is wasted if it is.
2. Phone: read Health Connect, find the latest period start, send the date.
3. Watch: store it, compute the day, serve it as a provider.
4. The local-only rule, before the feature is reachable by anyone who could
   share a face made with it.
