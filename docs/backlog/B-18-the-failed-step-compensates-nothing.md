---
id: B-18
title: "The step that failed is never compensated, and the ambiguous failure is the common case"
status: question
priority: P0
size: M
stage: stage-6-recovery
---

# B-18 — rollback starts at N−1, so the effect of step N leaks

`triggerCompensation` takes `compensateFromIdx` from the saga's own `currentInterceptorIndex`
(`Petich.kt:446`) and starts the rollback at `compensateFromIdx - 1` (`Petich.kt:473`). The index
advances only in the `Proceed` branch, after a successful write (`Petich.kt:854`), so when step N
throws or times out the index still points at N and the rollback covers N−1 … 0. Step N compensates
nothing.

That is correct only if a failed `intercept()` means nothing happened, and for a remote call it does
not. `stock.reserve()` reaching the far side and the answer being lost is the ordinary failure of a
distributed system, not an exotic one, and the engine sees exactly what it sees when the call never
landed. A second path reaches the same state without any network ambiguity: an optimistic conflict
on the `Proceed` write re-runs the step (`processWithRetry` → `doProcess`), and if the second call
refuses or throws, the first call's effect is already orphaned.

- **The decision, and it is the owner's, because it breaks a published contract.** Compensating N
  as well is the only option that can be honest: the alternative — writing into the contract that
  `intercept()` is atomic, so an exception means no effect — is unimplementable for a remote call,
  and a contract nobody can satisfy is worse than a named gap. The cost is that `compensate()` must
  then tolerate "the step did not happen" (`release` without `reserve`), which every existing
  implementation in konekt and shashki was written without. In semver terms this is a `!`.
- **The engine needs no extra write for it.** Temporal-style engines record the compensation before
  performing the action; here `compensatingFromIndex = N` already means "we were attempting N". The
  information is present and the rollback declines to use it — the change is in the walk, not in the
  cost model, and the 17 writes in the README stay 17.
- **Not one line, though.** `compensatingFromIndex` is persisted with the meaning "the next one to
  compensate" (`rollbackIndex + 1`), and a resumed rollback reads it back; if the starting index
  starts including the failed step, both writers and the reader have to mean the same thing, or a
  resumed rollback compensates step N twice. Plus the `coerceAtMost(size - 1)` at a phase boundary.
- **Rejected: deciding it per interceptor**, through a flag like `compensateOnFailure`. It puts the
  choice where the knowledge is not: the author of step N knows whether their own call is
  ambiguous, which is the easy half, but the default would then have to be the unsafe one to keep
  the published behaviour, and a safety flag that is off by default protects the code that already
  sets it.
- **Does not cover:** who re-drives a saga that died in `PROCESSING` — that is `B-19`, and the two
  interact, because more recovery means more second calls of the kind described above. This one is
  first for that reason.

- AC: a step that throws after performing its effect gets its `compensate()` called; a resumed
  rollback compensates each step exactly once, proved by a test that interrupts the rollback between
  the compensation and its write; the README's contract section and the KDoc on `compensate()` say
  that it may be called for a step that did not happen; the two consumers are reviewed against the
  new contract before the version that carries it is published.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonTest/kotlin/io/github/youndie/petich/StockMovePetichEngineTest.kt`
