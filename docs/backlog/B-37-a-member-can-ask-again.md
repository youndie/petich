---
id: B-37
title: "A member had no way to wait for another answer at itself"
status: done
priority: P1
size: S
stage: stage-9-definition
blocked_by: []
---

# B-37 — the cascade is the member, and the model could only move past it

`PetichMemberContext.suspendFor` stores the position **one past** the member, so a resume runs
whatever comes next. That is what money depends on: konekt holds a subscriber's funds and waits, and
the hold is taken exactly once because the member is not re-entered.

`InterceptorResult.Resuspend` has existed for the other case — the member is re-entered, the answer
comes back to it — and the definition model had no verb for it. Research open question 1 guessed a
wizard in konekt would settle it. shashki's order saga settled it instead.

- **The case.** `OfferStep` offers a ride to the nearest driver and waits. A decline releases that
  driver, offers the ride to the next candidate, and waits again — and the next answer has to land in
  the same member, because **the member is the cascade**. Expressed with `suspendFor`, the second
  answer belongs to whatever comes after, and the saga assigns a ride nobody accepted.
- **Two verbs, not a flag.** `resuspendFor` beside `suspendFor`. What differs is not a detail of the
  waiting: it is whether this member runs again, which is the difference between a hold taken once
  and a hold taken per answer. A boolean argument hides that at the call site.

## Findings — 2026-09-20

**Closed as part of shashki's migration (B-32), which is what found it.** The engine's `Resuspend`
branch was already there and correct; what was missing was a way for a member to ask for it.

**A test and its control, and the control is the point.** *a member that re-asks keeps the next
answer for itself* runs a cascade of two answers and asserts both landed in the member that asked.
On its own that proves little — so *a member that suspends does not get the next answer* runs the
same shape through `suspendFor` and asserts the resume runs the member **after** it. The pair is what
says the two verbs differ, and in which direction.

**B-35's other half, noted where it will be read.** `Resuspend` has no field for outbox events
either, so a member that announces and then re-asks still loses the announcement. It is now counted
through `onAnnouncementDiscarded` rather than dropped in silence, the same as a refusal — and giving
`Resuspend` the field is the same one-line change `Suspend` got, whenever somebody needs it.
