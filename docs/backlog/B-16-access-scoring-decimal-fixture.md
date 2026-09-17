---
id: B-16
title: "AccessScoring stays JVM-only because its fixture does decimal finance"
status: dropped
priority: P2
size: M
stage: stage-1-portable
---

# B-16 — The one suite whose JVM types are load-bearing

[B-04](B-04-scenario-suites-on-both-targets.md) moved two of the three end-to-end saga suites into
`commonTest`; `AccessScoringPetichEngineTest` did not move, and the reason is not the one written
into B-04. Its `java.math.BigDecimal` is not a money fixture with round numbers — it is an
amortisation calculation:

```kotlin
val interestRate = baseRate.multiply(employmentMultiplier).setScale(2, RoundingMode.HALF_UP)
val monthlyRate = interestRate.divide(BigDecimal("1200"), 10, RoundingMode.HALF_UP)
val monthlyCost = … .divide(powN - BigDecimal.ONE, 2, RoundingMode.HALF_UP)
val dtiRatio = monthlyCost.divide(payload.monthlyScore, 4, RoundingMode.HALF_UP)
```

Division at scale 10 with HALF_UP, and a power over a decimal. Hundredths in a `Long` — what the
other suite got — cannot hold that, so the values, and the assertions on them, would change.

- **Why this is a question rather than an open item.** Three answers are defensible and they are not
  a matter of taste. *(a)* Leave it on the JVM: the engine paths it exercises — enrichment,
  validation, rejection, cross-phase compensation, suspend and resume — are already exercised on
  both targets by the two suites that moved, so what the native target loses is coverage of the
  **fixture's arithmetic**, not of petich. *(b)* Rewrite the fixture in integer basis points and
  recompute every expectation — cheap to do and easy to do dishonestly: the expectations would be
  whatever the new arithmetic prints, which is the trap of writing a test that checks its author's
  answer. *(c)* Bring a multiplatform decimal library into `commonTest` for one suite.
- **What is NOT a reason to choose (b) or (c):** symmetry. A suite list that differs between targets
  reads as an oversight, and a line in the build file plus this item is what stops it from being
  read that way.
- **Does not cover:** the two suites that moved, or `linuxX64Test` coverage in general — 72 of the
  84 cases in `petich-core` now run on both targets.

- AC: the owner picks (a), (b) or (c). Under (a) this item closes as `dropped` with the reason
  recorded in `docs/research/research-native-port.md`; under (b) or (c) it becomes an `open` item
  with the approach named.
- Anchors: `petich-core/src/jvmTest/kotlin/io/github/youndie/petich/AccessScoringPetichEngineTest.kt`,
  `petich-core/src/commonTest/kotlin/io/github/youndie/petich/StockMovePetichEngineTest.kt`

## Dropped 2026-09-17 — the owner picked (a): it stays on the JVM

`AccessScoringPetichEngineTest` keeps its `java.math.BigDecimal` and keeps running on `jvmTest`
only. Nothing is to be done, which is why this closes as `dropped` rather than `done`.

**What that costs, stated so nobody has to rediscover it.** `petich-core` runs 72 of its 84 cases on
`linuxX64`; the twelve that stay are this suite's. What the native target therefore does not
exercise is **the fixture's decimal arithmetic** — an amortisation with `divide(…, 10, HALF_UP)` —
not any part of petich: the engine paths that suite walks (enrichment, validation, rejection,
cross-phase compensation, suspend and resume) are exercised on both targets by
`StockMovePetichEngineTest` and `BadgeIssuancePetichEngineTest`, which moved in
[B-04](B-04-scenario-suites-on-both-targets.md).

**Why (b) and (c) were the wrong trades.** Rewriting the fixture in integer basis points means
recomputing every expectation, and the expectations would be whatever the new arithmetic printed —
a test that checks its author's answer. Bringing a multiplatform decimal library into `commonTest`
adds a dependency to the library's test surface for one suite. Neither buys coverage of petich; both
buy symmetry, and symmetry is not a reason.

**If it ever becomes one**, the trigger is a consumer whose sagas carry decimal money on
Kotlin/Native — then the question is not this suite but whether the engine's own contract needs a
decimal type, which is a different item.
