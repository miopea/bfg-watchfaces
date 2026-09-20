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
other complication on the dial. No icon that announces what it is to anyone
glancing over her shoulder.

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

## Order of work

1. The Play permission declaration form, because it can be refused and
   everything else is wasted if it is.
2. Phone: read Health Connect, find the latest period start, send the date.
3. Watch: store it, compute the day, serve it as a provider.
4. The local-only rule, before the feature is reachable by anyone who could
   share a face made with it.
