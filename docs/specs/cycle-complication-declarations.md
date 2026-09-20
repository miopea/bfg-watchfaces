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

## What to check before acting on any of this

Google's health policy pages change, and this was read on 2026-09-19. Re-read
the permission declaration requirements and the permitted use-case wording
before filling anything in; the wording is the whole argument.
