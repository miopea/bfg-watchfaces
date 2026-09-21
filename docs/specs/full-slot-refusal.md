# Refuse the send when the watch has no slot to put it in

Decided 2026-09-21, interviewing the two blocked tickets. Covers the mitigation
half of swarm `01a064e7`, whose real fix waits on a platform release.

## The situation

Watch Face Push allocates slots. On the operator's Pixel Watch 5 there is
**one**. When that slot holds a face this install cannot attribute to itself,
`addWatchFace` fails and there is no way out through the API: `removeWatchFace`
needs a slotId the app owns, and owning none is precisely the problem.

`InstallPlan.Route.NoSlotAvailable` already models it, correctly and with tests.
This spec is not about detecting the state. It is about WHEN the person finds
out.

## What happens today

The phone builds the whole APK — measured at 2.7s for 520KB, plus signing and a
validator round trip — sends it over the Data Layer, and the WATCH discovers
there is nowhere to put it. The person watches a build succeed and an install
fail, then reads that they must reinstall the watch app.

That was tolerable when the only user was the operator. The app reached
production on 2026-09-20, so the next person to hit it is his wife, and the one
after that is a stranger.

## The decision

**Refuse the send before doing the work, and say why.**

Rejected: leaving it as-is. The error already names the remedy and the watch app
shows a free-slot count, which is genuinely better than nothing — but it asks
the person to notice a number before it matters and to interpret a failure after
it does. A refusal that arrives before the progress bar is a different
experience from a failure that arrives after it.

Rejected: a guided recovery flow that walks the person through the reinstall and
re-sends everything afterwards. It is the nicer product and it is more moving
parts around an unrecoverable state, on a path that only exists because of a
platform gap that may close. Do the cheap correct thing; revisit if the platform
does not move.

## What this requires, and the part that is not obvious

**Only the watch knows.** `remainingSlotCount` and the attribution check both
live on the watch; the phone is what starts a send. So the phone must ASK before
it builds, which means a request/response across the Data Layer rather than the
fire-and-forget sends most of this app uses.

That pattern already exists and already has a scar: the catalog request added on
2026-09-19 went nowhere because `FaceReceiverService` declared only
`CHANNEL_EVENT` in its intent filter and never received `MESSAGE_RECEIVED`. The
path prefix now covers `/bfg-watchfaces`, so a new path needs no manifest
change — but anyone adding one should read that comment first.

And the reply must be AWAITED. `answerCatalog` originally did not, and a
`WearableListenerService` is torn down the moment its callback returns, so the
watch logged that it had answered and the phone logged that nothing arrived.

## What it must not do

**Never block a send on a watch that simply did not answer.** A timeout, a watch
out of range, an older watch build that has never heard of the new path — all of
those must fall through to attempting the send exactly as today. The refusal is
an optimisation for a known-bad state, not a new precondition for sending at
all. Getting this backwards would turn a rare unrecoverable failure into a
common recoverable one, which is the trade `InstallPlan.mayRemoveAfterFailedUpdate`
exists to document.

## What the person reads

End-user language, no slot IDs and no API names. It should say that the watch
has no room for another face, that this app cannot clear it, and what actually
fixes it. The existing failure copy is the reference for tone: the sentence
stays friendly and the cause goes somewhere findable — see swarm `01a0bae9`.

## DONE WHEN

- A send attempted against a watch in the unattributable-full state is refused
  before the APK is built, with an explanation a person can act on.
- A watch that does not answer, answers late, or predates the feature is sent to
  exactly as before.
- The refusal path is covered in `:appcore` tests, where the branches run in
  milliseconds rather than on a wrist.

## NOT in scope

The underlying fix. That needs `androidx.wear.watchfacepush` above 1.0.0, which
has not moved since 2026-04-08, and `01a064e7` holds that wait on a schedule.
