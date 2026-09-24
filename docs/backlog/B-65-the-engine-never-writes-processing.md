---
id: B-65
title: "The engine never writes PROCESSING, so a saga that dies mid-pass is found by neither queue"
status: open
priority: P1
size: M
stage: stage-12-tracer
blocked_by: []
---

# B-65 — the stuck queue looks for a status nothing writes

B-19 gave the sweeper a second queue: sagas left in `PROCESSING` or `COMPENSATING` by a process that
died, re-driven after `stuckAfter`. The README promises it — "A process that dies mid-pass leaves its
saga in `PROCESSING`". **The engine never writes `PROCESSING`.** Found while building H1 for
[B-58](B-58-a-per-saga-event-hook.md); every line below is read in the code, not yet run end to end:

- `doProcess` keeps whatever status the row was created with. A `Proceed` copies the saga with a new
  position and the same status; only suspension, rollback and the terminal writes change it.
- konekt's tariff saga and both of shashki's sagas are created `DRAFT`
  (`TariffUseCases.kt`, `RequestRideUseCase.kt`, `SettleRideUseCase.kt`). A process dying mid-pass
  leaves them `DRAFT`, and `findStuck` is never asked for `DRAFT`.
- A saga that suspended and was resumed moves forward as `PENDING_SIGNATURE` with
  `suspendedUntilEpochMs` cleared. Dying then leaves it matching neither `findStuck` (wrong status)
  nor `findExpired` (no deadline).
- Only `petich-ktor`'s create route writes `PROCESSING`, and only on the first pass.
- Every stuck-queue test seeds `PROCESSING` by hand, so the suite is green about a state the engine
  cannot reach.

- **Reproduce first**, through the real path: a saga created `DRAFT`, killed inside a member, swept
  with `stuckAfter`; and one suspended, resumed, killed.
- **The decision to make:** the engine writes `PROCESSING` itself — on the first write of a pass that
  moves the saga forward, which is a write it already makes, so the count in `WriteCountTest` may not
  move — against widening the stuck queue's predicate to `DRAFT` and deadline-less
  `PENDING_SIGNATURE`. The first keeps one meaning per status; the second needs no write but makes
  "not touched since" ambiguous for a draft nobody has processed yet.
- Not covered: consumers' own status handling; they are told through their own trackers.

## Acceptance

- Both reproductions fail before the fix and are swept after it.
- `WriteCountTest`'s counts either stay or the item says why they moved and the README's Cost section
  moves with them.
- The conformance corpus covers whatever predicate the stores end up answering.

- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt`,
  `petich-core/src/commonTest/kotlin/StuckSweepTest.kt`,
  `petich-core/src/commonTest/kotlin/WriteCountTest.kt`
