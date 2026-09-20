---
id: B-52
title: "A hung announcement rolls the saga back, and a handler that throws decides its fate"
status: wip
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
