---
id: B-51
title: "A storage error after an effect writes FAILED and undoes nothing"
status: open
priority: P0
size: M
stage: stage-11-review
blocked_by: []
---

# B-51 — the one terminal status nothing will ever pick up, holding money

`doProcess` ends with `catch (e: Exception) { return failTerminally(...) }`, and `failTerminally`
writes `FAILED` and returns. **It compensates nothing.** Everything thrown outside a member's own call
lands there: a transient storage fault on the Proceed write, the Suspend write, a metrics
implementation that throws.

```
hold-funds commits.  reserve runs.  the UPDATE that records reserve's progress throws once.
        → FAILED, the hold standing, the reservation standing.
```

`FAILED` is terminal, so `SuspendedPetichSweeper`'s stranded queue — which looks at `PROCESSING` and
`COMPENSATING` — never touches it. Nothing in the system will ever release that hold. A repeated
request is told the saga already failed, and the README tells a reader that `FAILED` means what ran
was undone.

**The method is older than the sweeper**, and that is the whole explanation. Its reason was "do not
leave a saga in an intermediate status, nobody will pick it up"; since B-26 somebody will.

## Acceptance

- A fault after at least one acting member has committed does **not** end as `FAILED` with no
  compensation. Either `triggerCompensation(stepOutcomeUnknown = true)` runs, or the saga is left in
  `PROCESSING` and the caller gets `SystemFailure` — decided and recorded, not both.
- `FAILED` written without a rollback stays legal **only before the first effect**, and the code says
  which branch it is in rather than leaving a reader to infer it.
- A test where the repository throws a plain `RuntimeException` on the second step's write:
  compensations run, or the status is still `PROCESSING`. Today it is `FAILED` with zero
  compensations, and that is the control.
- The README's account of `FAILED` is corrected if the chosen answer changes it.
