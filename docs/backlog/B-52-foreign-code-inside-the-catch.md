---
id: B-52
title: "A hung announcement rolls the saga back, and a handler that throws decides its fate"
status: done
priority: P1
size: M
stage: stage-11-review
blocked_by: []
---

# B-52 — one class of defect: somebody else's code running where an exception changes the outcome

**A hung announcement is rolled back.** `withTimeout(config.timeoutMs(phase))` wraps every member,
including an announcement, and `announce()` rethrows `CancellationException` —
`TimeoutCancellationException` is a subclass. So the TCE escapes to the phase loop and calls
`triggerCompensation`. B-41 made an announcement that **throws** harmless and left the one that
**hangs** able to undo a completed saga. Catching TCE inside the helper does not fix it: the outer
`withTimeout` completes exceptionally anyway. The announcement needs its own bound —
`withTimeoutOrNull` **inside** `announce` — so the failure is handled in a live coroutine and the
handler can still `emit`.

**And every foreign callback runs unguarded**, which is the same shape:

| call | what a throw does |
| --- | --- |
| `AnnouncementFailureHandler.failed` | escapes to the outer `catch` and rolls the saga back |
| `compensationFailureHandler.handle` | escapes `triggerCompensation`; `recordGivingUp` never runs, the attempt is not counted, so `maxCompensationAttempts` stops bounding anything and the sweeper re-drives for ever. On the direct path it reaches the outer catch and writes `FAILED` over a half-undone saga |
| `compensationFailureHandler.exhausted` | called outside the `try`, same effect |
| `metrics.*` inside the loop | an application's implementation, same effect |

## Acceptance

- One wrapper for every call into an application's code: it rethrows `CancellationException` and
  counts-and-swallows the rest. Named, and used at every site above rather than at the ones somebody
  remembered.
- An announcement that hangs past its timeout ends `COMPLETED` with no compensation, and the failure
  handler is still called — which is only possible if the timeout is caught while the coroutine is
  alive.
- Tests: an announcement that hangs; an announcement whose handler throws, ending `COMPLETED`;
  `handle` throwing `maxCompensationAttempts` times in a row, ending `COMPENSATION_FAILED` — which is
  the assertion that the bound still bounds.

## Findings

**An imposed deadline cannot be caught, so the member has to own it.** `withTimeout` cancels the
coroutine, and everything downstream is obliged to let a `CancellationException` through — which is
why catching the timeout inside `announce()` was never going to work. `PetichMemberRun` gained
`boundsItsOwnTime`; an announcement is the only member that says yes, and it uses
`withTimeoutOrNull`, which **ends the body and returns**, so the counter and the failure handler
still run. That last part is the acceptance's real criterion: "the handler is still called" is only
possible in a coroutine that is still alive.

**The guards are decorators, not a `try` at each call**, and that is the difference between a rule
and a property. `GuardedMetrics`, `GuardedCompensationFailureHandler` and
`GuardedAnnouncementFailureHandler` wrap what the engine is handed, at construction, so no site
inside the engine has to remember — including sites written later. The constructor parameters keep
their names, because consumers pass them by name, and the properties that shadow them are the wrapped
ones.

**The worst of the three was `handle`**, whose throw escaped before the attempt was counted: with
`recordGivingUp` skipped, `maxCompensationAttempts` bounded nothing and the sweeper re-drove the saga
for ever. The test for it asserts `COMPENSATION_FAILED` is reached with a reporter that throws every
time — the bound still bounding is the whole assertion.

**And the decorator's own weakness found me while I was writing it.** `GuardedMetrics` must name
every method of `PetichEngineMetrics`; I added `onHandlerFailed` to the interface **after** writing
the decorator, so it fell through to the interface's no-op default and the counter was silently not
forwarded. A test caught it only because it asserted on that counter. It is the hand-written-list
shape again, and it is written into the file rather than left for the next person: what stands in for
a guard here is that every counter this engine relies on is asserted somewhere.

**Checked by two mutations, each isolating one half.** Letting the phase loop bound the announcement
again fails the hang case alone; unwrapping the announcement handler fails the throwing-handler case
alone. Restored, tree clean.

**Verification.** Full `build --rerun-tasks` on the Linux box, exit code read rather than piped: 473
tests across `jvmTest`, `linuxX64Test` and `test`.
