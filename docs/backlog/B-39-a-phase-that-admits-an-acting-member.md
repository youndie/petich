---
id: B-39
title: "AUTHORIZATION admits an acting member, so the phase stops meaning anything"
status: open
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
