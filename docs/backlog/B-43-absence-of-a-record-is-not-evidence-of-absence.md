---
id: B-43
title: "The guard the README recommends is blind in the case it exists for"
status: done
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

## Findings

**The answer is a fifth rule, not a better guard.** `ctx.idempotencyKey` is a deterministic
`"<saga id>:<member key>"` — identical on the forward pass, on a re-run after a version conflict, and
inside the compensation — so a rollback cancels by a name it chose itself rather than by evidence it
may never receive. It is written into **What it asks of a member** beside the other four, because
those four are written that way and this one decides whether money comes back.

**The engine hands the string over rather than leaving a member to build it**, which is the same
argument B-36 made for `stepKey`: the two sides have to spell it identically, and a string spelled
twice is a string spelled differently once.

**The eight-write budget is unchanged, and that is asserted rather than claimed.** Both halves of the
key are already on the row, so nothing is stored; `WriteCountTest` still asserts
`assertEquals(8, repository.sagaTableWrites)` and passed in the full run.

**The hard criterion is met by a test that puts the two guards on either side of one failure.**
`LostAnswerCompensationTest` has a warehouse that commits and *then* loses the answer:

- the member that undoes **by its record** leaves the reservation standing — this is the defect, kept
  as a live positive control rather than described;
- the member that undoes **by its key** releases it;
- undoing by key is still a no-op when the call never landed, so the older rule is not weakened;
- the key is the same string on both passes, and two members of one saga do not share it.

A test where the step simply never ran does not exercise any of this: there the record is absent AND
the effect is absent, so a rollback that does nothing is right by accident. The two only come apart
here.

**Checked by mutation after the implementation was committed:** making the key vary with the row's
version — so the forward pass and the compensation disagree — fails exactly the two cases that depend
on determinism. Restored, tree clean.

**Corroboration from a consumer, not invention.** konekt's `HoldFunds` already passes `ctx.petich.id`
into `balances.hold(...)` — the shape arrived at by hand, keyed by the saga rather than the member
because that saga holds money in one place.

**And a consumer defect of exactly this class, filed rather than folded in.** shashki's
`HoldPaymentStep` releases by the hold id it enriched, and that id comes back in the answer a timeout
loses — so a gateway that commits and goes quiet leaves the rider's fare held for ever.
`CaptureStep` has the same shape. It is `youndie/shashki` B-91, not a change here: its
`PaymentGateway` port takes no caller-chosen name, so fixing it is a port change in that repository.

**Recorded as D13**, with the rejected alternatives: writing the intent before the effect (works,
costs a write per acting member, breaks the published budget) and leaving the string to the member.
