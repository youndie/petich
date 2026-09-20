---
id: B-44
title: "A saga whose chain changed is refused for ever and has no status for it"
status: wip
priority: P1
size: S
stage: stage-10-review
blocked_by: []
---

# B-44 — the second state with no automatic way out, and this one has no name

Refusing a resume whose recorded prefix no longer matches is right, and covering the prefix rather
than the whole chain is what makes appending a step an ordinary release (B-21). What is missing is
what happens to the saga *afterwards*.

Verified rather than assumed:

- `chainMismatch` returns `PetichResult.SystemFailure` and **writes nothing** — the row keeps its
  status, its position and its deadline (`Petich.kt`);
- the expiry path turns the same refusal into `ExpireResult.ChainChanged`, and
  `SuspendedPetichSweeper` hands it to `onWorkerFailure` and moves on (`SuspendedPetichSweeper.kt`);
- so the sweeper re-finds the saga on every pass, re-computes the same mismatch and re-reports it,
  for as long as the deploy stands. The TTL cannot save it either: expiring means rolling back, and
  rolling back walks the chain that is refused.

Whatever it held — a hold, a reservation, a driver — is held for the duration. This is the **second**
state in the engine with no automatic way out, and unlike `COMPENSATION_FAILED` it has no status of
its own, so nothing on a dashboard separates it from a saga that is merely slow.

## Acceptance

- A saga refused for a changed chain is distinguishable from one that is running — by a status of its
  own, or at minimum by a counter that a person can alert on, decided knowingly and recorded.
- If a terminal status is chosen, it announces itself the way an exhausted compensation does: a row in
  the outbox, so the fact leaves the database.
- The sweeper stops re-reporting the same saga every pass, or it is stated why re-reporting is the
  wanted behaviour.
- A runbook paragraph either way: roll the deploy back, let the sagas in flight finish, roll forward.
  That is the recovery whether or not a status is added, and it is nowhere today.
