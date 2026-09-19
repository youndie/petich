---
id: B-19
title: "Nothing picks up a saga left in PROCESSING or COMPENSATING, and a failing compensation has no terminal state"
status: wip
priority: P0
size: L
stage: stage-6-recovery
blocked_by: [B-18]
---

# B-19 — the recovery is paid for at every step and wired to nothing

The engine resumes both interrupted states correctly: `doProcess` continues a rollback from
`compensatingFromIndex` when it finds the saga in `COMPENSATING` (`Petich.kt:684`), and a forward
pass resumes from `currentInterceptorIndex`. Nobody calls it. `SuspendedPetichSweeper` polls
`findExpired`, which is `PENDING_SIGNATURE` only — `expireSuspended` answers `NotSuspended` for
anything else (`Petich.kt:559`), and the conformance corpus makes the exclusion a rule
(`PetichStoreConformance.kt:168`). No store has a query for "rows in `PROCESSING` or `COMPENSATING`,
untouched since". The README sells 17 writes per saga as the price of never being in an unknown
position; the position is indeed known, and today it is known to nobody.

The second half is worse than the first. When `compensate()` throws, the handler is called, the loop
breaks, and the saga stays in `COMPENSATING` for good (`Petich.kt:506`) — no retry, no attempt
count, no dead letter, and the default handler is `NoOpCompensationFailureHandler`. It is the only
state in this engine with no automatic exit, and by default it is also silent.

- **The two halves ship together, in this order.** A sweeper over `COMPENSATING` added before there
  is an attempt counter and a terminal status turns a deterministically failing `compensate()` into
  a hot loop that re-runs the same external calls every poll. So: attempt counter and
  `COMPENSATION_FAILED` (with an outbox event, since that is how this library tells an application
  something happened) first, the query and the worker second.
- **The staleness criterion has to be documented as a formula, not a number.** There is no lease
  and no owner column — `Petich` has no field for either — so "untouched for N" racing a live but
  slow instance is guarded by nothing except the version, which protects the row and not the
  effects. The only honest rule is `N > max(phaseTimeoutsMs ∪ compensationTimeoutsMs)`, and it
  belongs in the KDoc of the parameter, because both tables are per-application and a constant
  chosen "about a minute" is wrong in someone's configuration.
- **The default handler gets fixed the way this repository already fixes this.** petich owns no
  logger by design, and `SuspendedPetichSweeper` says so where it takes `onWorkerFailure`. The
  in-grain answer is the one used for dropped events twice already: a counter
  (`PetichEngineMetrics` has `onCompensation` and no `onCompensationFailure`) plus a
  construction-time refusal in the shape of `requireOutbox` / `requireSideEffects`. Logging by
  default would contradict a stated position for less.
- **Rejected: a lease column now.** It is the right answer for several instances sweeping one
  table, and it is a schema change in two stores plus a rule in the corpus plus a heartbeat nobody
  has asked for yet. The timeout formula is honest about what it does not cover, and the item that
  needs a lease can cite this one.
- **Does not cover:** exactly-once re-drive. Two instances re-driving one crashed saga both re-run
  `intercept()`; one loses on version. That is the same idempotency contract as `B-18`, which is why
  this is blocked by it.

- AC: a compensation that keeps failing reaches `COMPENSATION_FAILED` after a bounded number of
  attempts and emits an outbox event saying so; a saga left in `PROCESSING` or `COMPENSATING` by a
  killed process is picked up and finished without anyone calling `process()` by hand; the
  staleness parameter's documentation states the formula; both stores implement the new query and
  the corpus has a rule for it; the README's Cost section stops promising recovery that is not
  wired up.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt`,
  `petich-core/src/commonMain/kotlin/PetichEngineMetrics.kt`,
  `petich-conformance/src/commonMain/kotlin/io/github/youndie/petich/conformance/PetichStoreConformance.kt`,
  `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt`,
  `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/PostgresPetichStore.kt`
