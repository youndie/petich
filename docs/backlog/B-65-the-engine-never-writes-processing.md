---
id: B-65
title: "The engine never writes PROCESSING, so a saga that dies mid-pass is found by neither queue"
status: done
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

## Findings

**Reproduced through the real path before the fix**, three ways, in `StrandedMidPassTest`: a saga
created `DRAFT` killed after its first step (row left `DRAFT`, index 1), the same killed inside its
first step (row left `DRAFT`, index 0), and a saga suspended, resumed, moved on and killed (row left
`PENDING_SIGNATURE`, index 2, no deadline). The sweeper, running both queues after `stuckAfter`,
picked up none of them.

**Decided: the engine writes `PROCESSING`, on writes it already makes** — the rejected alternative
(widening the stuck query to `DRAFT` and deadline-less `PENDING_SIGNATURE`) would have needed a new
predicate in both stores and the corpus, and would have left `PENDING_SIGNATURE` meaning two things.

- A saga handed in as `DRAFT` is **inserted** as `PROCESSING`. The insert happens anyway; a row that
  already exists is returned as it is, so a replay under the same id changes nothing.
- Every committed `Proceed` writes `PROCESSING`, whatever the row said. That is the write that clears
  the deadline, so the two now move together.
- `WriteCountTest` is not edited and green: no write was added.

**What a consumer sees change.** A saga read mid-pass now says `PROCESSING` where it said `DRAFT`, or
`PENDING_SIGNATURE` after a resume. shashki maps `DRAFT` and `PROCESSING` to the same thing already
(`PetichRideRepository.kt`). konekt refuses a resume unless the row is `PENDING_SIGNATURE`
(`TariffUseCases.kt`), so a second resume arriving while the first is carrying the saga on is now
refused rather than accepted into a race — the behaviour its guard was written for.

**Checked by two mutations, one per write**: the insert reverted → "dies inside its first step"
fails alone; the `Proceed` status removed → "resumed … dies after moving on" fails alone. Each
reverted, tree read back clean.

**Not closed, and filed:** a resume whose FIRST member dies before its commit leaves the row exactly
as the suspension wrote it — `PENDING_SIGNATURE`, deadline intact. Without a TTL the client's retried
resume re-runs that member, which is the at-least-once the member already owes. With one, the expiry
rolls back from the suspended member down and, by reading, leaves out the member that died in the
resume. [B-66](B-66-an-expiry-forgets-the-member-a-resume-died-in.md) is to reproduce that and decide.

**Verification.** `./gradlew build` on the Linux box green, conformance against a real Postgres
included; `StrandedMidPassTest` 3/3, `WriteCountTest` 2/2 and `StuckSweepTest` 4/4 on both targets,
result files read.
