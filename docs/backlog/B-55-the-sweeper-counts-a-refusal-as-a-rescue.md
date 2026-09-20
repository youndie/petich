---
id: B-55
title: "The sweeper counts a refusal as a rescue, and one failure blocks both queues"
status: wip
priority: P2
size: S
stage: stage-11-review
blocked_by: []
---

# B-55 — two things the sweeper reports that are not what happened

**A refusal is counted as a revival.** `sweepStuck` ignores what `engine.process` returns, so a saga
refused for a changed chain — `PetichResult.SystemFailure` — increments `revived` and calls
`onRevived`. The number an operator watches to see recovery working counts sagas that were not
recovered, and the counter B-44 added for exactly that case fires beside it saying the opposite.

**One failure blocks both queues.** `sweep()` and `sweepStuck()` sit in one `try`. While `findExpired`
is failing — a database that cannot serve that query, an index being rebuilt — the stranded queue is
not processed at all, and the two have nothing to do with each other.

**And a throw from `onWorkerFailure` ends the worker.** It is called from the `catch`, so an
application whose reporting throws stops the sweeper for the life of the process, silently.

## Acceptance

- `revived` counts sagas the engine actually moved. A refusal is reported as one, through whatever
  channel says so, and not as the opposite.
- The two queues fail independently: one throwing does not stop the other in the same pass.
- The failure reporter cannot end the worker, for the same reason every other application callback
  cannot (B-52).
