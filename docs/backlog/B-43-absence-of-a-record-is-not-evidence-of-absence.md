---
id: B-43
title: "The guard the README recommends is blind in the case it exists for"
status: wip
priority: P1
size: M
stage: stage-10-review
blocked_by: []
---

# B-43 — "no record, so return quietly" reads "it never landed" out of no evidence at all

`compensate()` may be called for a member that did not happen — the engine cannot tell an effect that
reached the far side from a call that never landed (B-18). The README states that, and then, in the
same paragraph, recommends the guard: *"undo what the record says happened, and return quietly when
there is no record."*

Those two sentences disagree. The absent record is exactly what the ambiguous case looks like:

- `reserve()` reaches the far side and commits there;
- the response is lost; `withTimeout` fires;
- the member was suspended inside that call, so it never reached `ctx.record(...)` — and could not
  have, because the `reservationId` it would record comes back **in the response that was lost**;
- `compensate` reads no record and returns quietly;
- the reservation is held for ever.

**The reviewer's stated mechanism was different and the truth is worse.** The objection was raised as
a persistence-ordering problem — that `step_records` ride in the same `UPDATE` as the position and so
are written after `execute()` returns. They are not: `DefinitionRun.run` snaps the record in a
`finally`, and both `catch` arms fold it in with `withRecordOf` before starting the rollback, which is
the whole of the channel B-29 added. A member that records and *then* dies does carry its record into
its own undo. So the hole cannot be closed by persisting earlier: at the moment of the timeout there
is nothing to persist.

- **`step_records` are the right channel for what a rollback needs** — the `reservationId`, the hold
  id — and the wrong one for *whether the effect happened*. The first is data the member already has;
  the second is a fact only the far side knows.
- **Two shapes work, and they cost differently.** A deterministic idempotency key (`sagaId + stepKey`)
  lets `compensate` say "cancel whatever is under this key" and be a no-op when there is nothing —
  no record needed at all. Writing the intent *before* the effect also works and costs an extra write
  per acting member, which breaks the eight-write budget the Cost section publishes.
- The consumers already lean on this without saying so: shashki's `HoldPaymentStep` enriches the hold
  id rather than recording it, and its compensation reads it back the same way anybody else does.

## Acceptance

- The README stops recommending a guard that is blind in the case the rule above it describes, and
  says what a member should use instead.
- Whichever shape is chosen is demonstrated by a test in which the effect **did** land and the record
  is absent, and the rollback still undoes it. A test where the step simply never ran does not
  exercise this.
- If the answer is "petich cannot fix this, the member must carry an idempotency key", that is stated
  as a requirement on a member rather than left as advice — the other three rules in
  **What it asks of a member** are written that way.
- The eight-write figure in the Cost section is re-derived, or said to be unchanged, under whichever
  shape is chosen.
