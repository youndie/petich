---
id: B-35
title: "A member that emits and then suspends loses the announcement, silently"
status: wip
priority: P1
size: M
stage: stage-9-definition
blocked_by: []
---

# B-35 — the new model accepts what the old one could not express

`RecordingContext.outcome()` folds what a member asked to have committed into its decision. On
`Proceed` both the events and the side effects ride along. On `Suspend` only the **side effects** do —
`InterceptorResult.Suspend` has no field for outbox events — and on `Reject` and `Compensate` neither
does.

- **The old model refused to express this; the new one accepts it and drops it.** An interceptor
  returning `Suspend` had nowhere to put an event, so nobody wrote one. `ctx.emit(...)` before
  `ctx.suspendFor(...)` compiles, runs, and the event is gone: `forceUpdateStateWithRetry` is called
  with `sideEffects = ...` and no `outboxEvents`, and nothing reports a loss. A silent drop is worse
  than a missing capability, which is the whole argument of this stage.
- **Half of the gap was already closed once.** `Suspend.sideEffects` exists because someone hit
  exactly this — its own comment says a suspending step "could hand the engine nothing at all until
  now — not even an outbox event". The durable timer got a field; the announcement did not.
- **`Reject` and `Compensate` dropping events is defensible and should be written down, not fixed by
  accident.** `outcome()`'s KDoc argues that a rollback's writes belong to the compensations, which do
  run for both (B-20 made `Reject` roll back). But a `PetichCheck` that rejects has nothing to undo
  and so no compensation to carry its word — konekt's purchase validation writes its refusal through
  its own port for exactly that reason. Whether that is a decision or a second hole is the question
  this item has to answer, not assume.
- **Found by B-32**, reading the member the purchase saga suspends on. It is not in that item's way —
  konekt's `HoldFunds` does not announce before suspending — so it is filed rather than folded in.

## Acceptance

- A member that emits and then suspends either has its event committed with the suspending write, or
  is refused at the point of emitting with a message naming why. Not dropped.
- The same question is answered for `Reject` and `Compensate`, in the research document, with the
  reason — including the `PetichCheck` case, which has no compensation to inherit the word.
- A test per outcome that fails when the folding stops carrying what that outcome is supposed to
  carry. The existing suite passes today with events dropped on three outcomes out of four, so it is
  not the guard.
