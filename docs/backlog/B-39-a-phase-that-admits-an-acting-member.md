---
id: B-39
title: "AUTHORIZATION admits an acting member, so the phase stops meaning anything"
status: done
priority: P1
size: M
stage: stage-10-review
blocked_by: []
---

# B-39 — the example compiles, and that is the finding

`authorize` has two overloads: one taking a `PetichCheck`, one taking a `PetichStep` (D3). So the
README's own example — `authorize("hold-funds", HoldFunds(payments))`, where the member takes money —
**compiles**. That was the question a reviewer asked to settle it, and the answer settles it against
the design.

- **One premise of the review needs correcting, and it makes the case narrower rather than weaker.**
  A `Reject` from a later member does *not* leave the hold standing: B-20 made a refusal roll back
  what ran, and `RejectRollsBackTest` holds it. So the failure mode is not money left held.
- **What is actually lost is the reader's ability to use a phase at all.** If AUTHORIZATION may hold
  an acting member, "before effects" is a convention the author remembers, not a property the type
  carries — which is the exact shape of defect this stage removed from `compensate`.
- **The terms collide, and that is probably the cause.** In payments, *authorization* IS the hold. In
  petich's phases, AUTHORIZATION means "is this allowed, and has the human agreed". `HoldFunds` is the
  first sense and belongs in the second's neighbour: `step("hold-funds", …)` before `reserve`, which
  also gives the right rollback order — stock released first, then the hold.
- **D3's only witness was konekt**, whose authorisation holds money and then waits. If that member
  belongs in EXECUTION, the decision has no case left and `authorize` should take a check only.

## Acceptance

- A member that can act cannot be declared in a phase that means "before effects" — checked by the
  type, so the mistake does not compile.
- konekt's `HoldFunds` moves with it, and what its saga's `currentPhase` reads while waiting for a
  confirmation is stated: today it is AUTHORIZATION, and nothing depends on that.
- D3 is rewritten or withdrawn, with the reason. If `authorize` keeps both overloads, the argument
  cannot be konekt's.
- The README example compiles only in the shape the design intends.

## Findings

**The overload is gone.** `authorize` takes a `PetichCheck` only, and the README's example was
rewritten into the shape the design intends: `step("hold-funds", …)` above `reserve`, so the rollback
releases the stock before it unholds the money. The KDoc on `authorize` now says why the word is not
the payments one, because the collision is what caused the decision.

**D3 is rewritten, not withdrawn.** Its axis — the verb places, the type names the role — survives;
what was wrong was admitting both types into one verb on konekt's evidence. That evidence was
misread: konekt was not saying "our authorisation acts", it was saying its hold is not an
authorisation in this sense at all. Both consumers' holds now suspend from `step`, so the waiting
that D3 thought it was protecting was never a property of the phase.

**The review's premise needed one correction, and it narrowed the case rather than weakening it.** A
later `Reject` does not leave the hold standing — B-20 rolls back what ran, `RejectRollsBackTest`
holds it. What was lost was never money; it was a reader's ability to use a phase.

**What reads `currentPhase`, answered by grep across both consumers rather than from memory:**
konekt reads it nowhere. shashki reads it in exactly one line —
`PetichRideRepository.rideStatus()`, whose DRAFT/PROCESSING arm asks whether the phase is EXECUTION.
So the move is observable in one place, and the reading it changes is:

> a ride whose saga died **holding the money but not yet offering the ride** now reads MATCHING
> where it read REQUESTED.

That is the better reading — the money is held and the ride is committed to — and it is now pinned by
`RideStatusFromPhaseTest`, which did not exist: the function had no test at all. The live path never
reaches that arm, because a saga waiting for a driver is PENDING_SIGNATURE and maps to MATCHING
without consulting the phase.

**The finding worth more than the item: a phase is not a checkpoint.** Two of shashki's resume
fixtures parked a dead process at the start of EXECUTION and looked like proof that a committed phase
boundary stops a non-idempotent hold from running twice. The engine writes nothing when a phase ends
and commits `currentInterceptorIndex = index + 1` after every member that proceeds, so the row a
death leaves names a **member**. The fixtures were one member short of where a death actually leaves
them. Written into the research beside D3, because it says what a phase is for: a promise to the
reader of the code, never a promise about the database.

## Consumers

- youndie/konekt#50 — three sagas, `0.4.0.84`, nothing there reads a phase.
- youndie/shashki#17 — two sagas, both resume fixtures, and the one line that reads a phase.
