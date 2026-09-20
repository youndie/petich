---
id: B-35
title: "A member that emits and then suspends loses the announcement, silently"
status: done
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

## Findings — 2026-09-20

**Suspend carries the announcement now**, in the same write that moves the row to
`PENDING_SIGNATURE`. `InterceptorResult.Suspend` gained `outboxEvents` beside the `sideEffects` it
already had — and that `sideEffects` existed at all was the tell: somebody hit this gap once, gave
the durable timer a field, and left the announcement without one.

**`Reject` and `Compensate` still carry nothing, now as a decision with its reason written down**
(research, D8). Both begin a rollback, the outbox is at-least-once, and an event committed with the
write that starts an undo describes something about to stop being true. A rollback's word belongs to
the compensations, which may announce freely.

**The hole that leaves is closed from both ends.** The refusing member's own compensation does not
run, so what it emitted has no owner:

- **A check cannot announce at all.** `emit` and `attach` moved from `PetichMemberContext` to
  `PetichStepContext`. A check has no compensation to inherit its word, so the model stopped
  accepting what it cannot honour. konekt's purchase validation writes its refusal through its own
  ledger port — that it had to is the evidence the hole was real — and every consumer `emit` is in a
  step, so nothing broke.
- **A step that announces and then refuses is counted.** `onAnnouncementDiscarded(type, stepKey,
  count)` names the member. Deliberately not folded into `onDroppedEvents`, whose own documentation
  calls that one a mistake "reached by accident rather than by decision"; this one is the decision,
  and one counter meaning both would answer neither question.

**Four cases, four mutations, each failing only its own.** `AnnouncementPerOutcomeTest`:

| what was broken | what failed |
| --- | --- |
| `Proceed` stops carrying events | the Proceed case |
| `outcome()` stops folding events into `Suspend` | the Suspend case |
| the engine stops passing them at the suspending write | the Suspend case |
| the discard stops being counted | both refusal cases |

The two Suspend mutations are separate sites — the folding and the write — and each is guarded on its
own. The whole suite was green before any of this with three outcomes out of four losing
announcements, which is what "the existing suite is not the guard" meant.

**Two costs paid on the way, both self-inflicted and both already written down:**

- `git checkout --` to undo a mutation destroyed the uncommitted implementation in the same file.
  The skill says to mutate *after* committing, and this is why. Redone from scratch.
- A comma in a backticked test name is legal on JVM and illegal on Kotlin/Native. The JVM run was
  green; `compileTestKotlinLinuxX64` caught it.
