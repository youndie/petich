---
id: B-54
title: "A resumed rollback forgets it was a refusal, and can drag a terminal saga back"
status: wip
priority: P1
size: S
stage: stage-11-review
blocked_by: []
---

# B-54 — two defects, one missing fact: what this rollback is going to end as

**A resumed rollback ends `FAILED` even when it was a refusal.** `doProcess` resumes an interrupted
rollback with `triggerCompensation(currentPetich, "Resuming compensation")` — the default
`terminalStatus` is `FAILED`. The target status is written nowhere, so a saga refused on business
grounds whose process died mid-rollback is finished by the sweeper as `FAILED`. The client asking
again is told the server broke rather than that it was refused. **B-20 exists to keep those two
apart**, and this is the one path that loses the distinction.

**And a terminal saga can be dragged back into `COMPENSATING`.** `forceUpdateStateWithRetry` re-reads
`latest` on a conflict and writes its own status over it without looking at `latest.status`. A replica
paused longer than `stuckAfter` — a GC pause, a frozen VM — wakes after another replica's sweeper has
finished the rollback as `FAILED`, overwrites it, and compensates a second time. The `stuckAfter`
formula makes this rare; it does not make it impossible.

Both are the same missing fact written down in two places.

## Acceptance

- The status a rollback will end under is persisted with the mark that starts it, and the resume path
  reads it instead of defaulting.
- A saga refused with `ctx.reject`, its process killed mid-rollback, finished by a second pass, ends
  `REJECTED`.
- `forceUpdateStateWithRetry` does not overwrite a row that is already terminal; it stops. The cheap
  invariant is enough and the expensive one is not needed.
- A test for the second: a terminal row, a stale writer, and the row still terminal afterwards.
