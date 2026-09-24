---
id: B-59
title: "An announcement whose own withTimeout fires still rolls a finished saga back"
status: done
priority: P1
size: S
stage: stage-12-tracer
blocked_by: []
---

# B-59 — the route B-52 left: a timeout that is not the engine's

[B-52](B-52-foreign-code-inside-the-catch.md) stopped a **hung** announcement from rolling the saga
back: an announcement gets no outer deadline (`boundsItsOwnTime`) and bounds itself with
`withTimeoutOrNull` inside `announce`, so the handler still runs and the saga ends `COMPLETED`.
Found while re-reading for [research-petich-tracer](../research/research-petich-tracer.md) §1.4 —
**reasoned, not yet reproduced**:

1. the announcement's body uses its own `withTimeout` (an HTTP client's deadline, say) and lets the
   `TimeoutCancellationException` escape;
2. `withTimeoutOrNull` returns `null` only for **its own** timeout and rethrows anyone else's;
3. `announce` catches `CancellationException` first and rethrows it — the comment there is right
   about cancellation and wrong about this subclass;
4. the phase loop's `catch (e: TimeoutCancellationException)` records the member and calls
   `triggerCompensation(..., stepOutcomeUnknown = true)`.

So a saga whose every effect already happened is undone because a receipt's HTTP call timed out —
exactly B-52's defect, through a door B-52 did not test. For a **step** the same path is right (an
unknown outcome is compensated); for an announcement it is not.

- **Reproduce first.** A test whose announcement does `withTimeout(1) { delay(…) }`; if it ends
  `COMPLETED` the reasoning is wrong somewhere, and the item closes with where.
- **The decision:** inside `announce`, a `CancellationException` while the **caller** is still active
  (`currentCoroutineContext().isActive`) is the body's own failure, not the caller going away, and is
  handled like any other announcement failure. Rejected: catching `TimeoutCancellationException`
  specifically — it would still let any other foreign `CancellationException` (a cancelled child
  scope inside the body) decide the saga.
- Not covered: steps and checks, where a timeout of unknown origin is correctly an unknown outcome.

## Acceptance

- The reproduction test exists and was seen failing before the fix.
- An announcement whose body's own `withTimeout` fires ends `COMPLETED`, the failure handler is
  called with a reason, and `onAnnouncementFailed` is counted.
- Cancelling the process during an announcement still propagates (the existing test stays green).
- Checked by mutation: the new branch removed → the reproduction fails.

- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonTest/kotlin/ForeignCodeCannotDecideTest.kt`

## Findings

**Reproduced by B-58**, before this item was taken: `TracerTest` "pair b" runs an announcement whose
body is `withTimeout(10) { delay(10_000) }` and the saga ends `FAILED` with `reserve` undone. That
assertion is the one this item flips; the test that item left is the reproduction.

**Fixed as the item decided**, in `announce`: a `CancellationException` caught while the engine's
own coroutine is still active came from inside the body and is handled as the body failing — counted,
handed to `AnnouncementFailureHandler`, the saga carried on. One caught while the caller is cancelled
is rethrown as before. The check is the caller's state rather than the exception's type, so any
foreign cancellation from inside the body — a cancelled child scope, not only a deadline — lands in
the same place.

**Reproduced before the fix through the real path**: `AnnouncementOwnTimeoutTest` "an announcement
whose own deadline fires does not roll the saga back" failed with `SystemFailure(Timed out waiting
for 10 ms)`; the cancellation control beside it was green before and after. No such control existed
— the item said "the existing test stays green", and there was none — so it was written here, first.

**`TracerTest` pair b flipped, as B-58 said it would.** Its second half now ends `COMPLETED` and the
trace tells the two runs apart by whose deadline it names (`timed out after 30ms` is the engine's,
`Timed out waiting for 10 ms` the body's). The rollback it used to show is what the mutation below
brings back.

**Checked by mutation**: the new branch reduced to `throw e` → the reproduction and pair b fail
(2 of 11); restored, tree read back clean.

**Verification.** On the Linux box: `:petich-core:jvmTest` and `:petich-core:linuxX64Test` green,
result files read — `AnnouncementOwnTimeoutTest` 2/2, `TracerTest` 9/9, `ForeignCodeCannotDecideTest`
3/3 on each target. The README's announcement paragraph now names the body's own deadline.
