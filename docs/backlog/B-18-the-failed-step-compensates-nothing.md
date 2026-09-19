---
id: B-18
title: "The step that failed is never compensated, and the ambiguous failure is the common case"
status: done
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

## Decided 2026-09-19

**The owner took the first option: the failed step is compensated.** `compensate()` may from now
on be called for a step that did not happen, and the two consumers on 0.2.0 are reviewed against
that rather than edited from here.

## Closed 2026-09-19

`triggerCompensation` takes a `stepOutcomeUnknown` flag, set only where the engine entered a step
and never learned what it did — the `TimeoutCancellationException` and `Exception` catches around
`tryIntercept`. The rollback then starts at that step instead of below it. **No extra write:**
`compensatingFromIndex` keeps its one meaning, "one past the next step to compensate", for every
writer and for the resumed rollback that reads it back, so a resume still cannot double-compensate
its way down the chain.

**Two call sites deliberately keep the old starting point**, and one test each holds them there:
`InterceptorResult.Compensate` is a reported outcome — the step is alive and said what it wants —
and an expired suspension starts from a `currentInterceptorIndex` that already points past the step
that suspended, so adding to it would compensate a step nobody entered. The flag is one character
away from being unconditional, which is why the negative control exists.

**An acceptance criterion written above was wrong, and is corrected here rather than quietly met.**
It asked that a resumed rollback compensate each step *exactly once*. No implementation can promise
that: the rollback commits its position after calling the step, so an interruption in that window
resumes on the same step — which is precisely why `compensate()` is documented as idempotent. What
is guaranteed, and what the test asserts, is that the resumed rollback repeats at most the step it
was interrupted on and **skips nothing below it**.

**The suite found the first consumer-shaped instance of the new burden, and it was in this
repository.** `StockMovePetichEngineTest`'s deposit step compensated by calling
`withdraw(toWarehouse, amount, reservationId)` with the reservation id belonging to *another* step;
that removed the reservation, and the reservation step's own compensation then found nothing to
cancel and left the stock short by the full amount. Harmless while a failed step was skipped by the
rollback — reachable the moment it was not. The fixture now undoes only its own deposit, and only
when the deposit is recorded, which is the shape every compensation needs from here.

**A consequence worth carrying to [B-19](B-19-nobody-picks-up-a-saga-that-died-mid-pass.md):** a
compensation that is not safe for a step that did not happen does not merely misfire, it **throws,
and the rollback of everything below it stops there** — the saga stays in `COMPENSATING` with no
automatic way out and, by default, in silence. This change widens the set of compensations that can
throw, which makes B-19's half about a failing compensation more urgent than when it was written,
not less.

**The version head moved to 0.3.0.** A breaking contract change published as `0.2.0.<build>` would
be a snapshot that looks like a patch. One line in `gradle.properties`, reversible.

**The two consumers were read, not edited** (the owner's instruction). What has to be reviewed
there before they take this version:

| where | what it does on compensation | verdict |
|---|---|---|
| konekt `TopUpInterceptors.kt:82` | `balances.debit(...)`, under a comment stating it is "ONLY REACHED WHEN THE CREDIT ACTUALLY HAPPENED" | **breaks, and it is money.** A decline still returns `Compensate` and is still not undone, but a `payments.settle()` that *throws* now reaches this and debits a balance that was never credited. The comment is false from this version on |
| konekt `PurchaseInterceptors.kt:133` | `balances.release(...)` then `entitlements.cancel(...)` | **check.** Reached when `hold()` or `createPending()` throws; safe only if both are no-ops for an id they never saw |
| shashki `SettlementSteps.kt:200` | refunds `enriched(CHARGE_ID) ?: payload.holdId` | **check.** A charge step that throws before recording its id now falls back to refunding the fare hold |
| shashki `OrderSteps.kt:169` | `enriched(HOLD_ID)?.let { payments.release(it) }` | safe — guarded by the record the step leaves, which is the pattern to copy |
| shashki `SettlementSteps.kt:225` | `payouts.remove(rideId, kind)` | safe — removing a row that is not there is a no-op |

**Where it ran:** `./gradlew build` on the Linux box, 273 tests, 0 failures; the four new cases pass
on `jvm` and on `linuxX64`. Both directions mutated after the change was committed: with the flag
forced off, three of the four fail; with it forced on, the negative control fails and nothing else.
