---
id: B-04
title: "The three end-to-end saga suites run on the JVM only"
status: done
priority: P1
size: M
stage: stage-1-portable
blocked_by: [B-03]
---

# B-04 — Move the realistic suites into `commonTest`

`petich-core` has thirteen suites in `commonTest` and three in `jvmTest`, and the three are the ones
that exercise a whole saga: `StockMovePetichEngineTest`, `BadgeIssuancePetichEngineTest`,
`AccessScoringPetichEngineTest`. They are in `jvmTest` for three imports —
`java.util.UUID`, `java.util.concurrent.ConcurrentHashMap` and `java.math.BigDecimal` — none of
which is load-bearing: the UUIDs are test identifiers, the maps are fake repositories, the decimals
are money in a fixture. After [B-03](B-03-linux-target-on-the-portable-four.md), `linuxX64Test` runs
everything *except* the three suites that look most like a real application.

- **Move them rather than duplicate them.** A second copy compiled for the other target is two
  suites drifting apart; what is wanted is the same assertions on both.
  `kotlin.uuid.Uuid` replaces the identifiers, a `Mutex`-guarded `MutableMap` replaces the
  concurrent one (the fakes are already only touched from coroutines), and the money fixture becomes
  minor units in a `Long` — which is what the saga stores anyway.
- **Rejected: leaving them where they are and calling the native target covered.** Thirteen suites of
  unit-level behaviour passing on native says the compiler works; it does not say a saga compensates
  correctly there.
- **Does not cover:** the concurrency question — a fake map that is correct under a single-threaded
  `runBlocking` says nothing about a multi-threaded dispatcher. That is
  [B-05](B-05-concurrency-under-the-native-memory-model.md).

- AC: `linuxX64Test` and `jvmTest` run the same suite list for `petich-core`, and the three saga
  scenarios are in it on both; `petich-ktor`'s routing test moves with the same treatment in
  [B-06](B-06-ktor-module-and-the-jvm-pinned-catalogue.md).
- Anchors: `petich-core/src/jvmTest/kotlin/io/github/youndie/petich/StockMovePetichEngineTest.kt`,
  `petich-core/src/jvmTest/kotlin/io/github/youndie/petich/BadgeIssuancePetichEngineTest.kt`,
  `petich-core/src/jvmTest/kotlin/io/github/youndie/petich/AccessScoringPetichEngineTest.kt`,
  `petich-core/src/jvmTest/kotlin/io/github/youndie/petich/ConfirmResumePayload.kt`

## Closed 2026-09-16 — two suites of three, and the third has an item of its own

`BadgeIssuancePetichEngineTest` and `StockMovePetichEngineTest` are in `commonTest` and run on both
targets. `petich-core` on `linuxX64` went from **52 tests to 72**; `jvmTest` still reports **84,
0 failures**, the same number as before the move, so nothing was dropped on the way. The difference
is exactly the 12 cases of `AccessScoringPetichEngineTest`.

**The premise of this item was half wrong, and the compiler said so twice.**

*First:* the list of what kept these suites on the JVM was drawn from their imports, and the imports
do not show everything. `getOrDefault`, `putIfAbsent` and `computeIfPresent` are `java.util.Map`
methods available on a plain Kotlin `Map` on the JVM, invisible in an import list and unresolved on
Kotlin/Native. That is a better answer to "how do I find the JVM-only surface" than grepping
imports: declare the target and read the errors.

*Second:* `AccessScoring`'s `BigDecimal` is not "money in a fixture". It is an amortisation with
`divide(…, 10, HALF_UP)` and a power over a decimal, and hundredths in a `Long` cannot hold it.
Moving it means changing the numbers the assertions check — which is a decision about honesty, not
a refactor, so it is [B-16](B-16-access-scoring-decimal-fixture.md), a question for the owner.

**What did NOT move: the assertions.** One test compares a notification string carrying a
human-readable amount (`"WITHDRAW: from1 -50500.00"`). With hundredths in a `Long` the log said
`-5050000`, and the cheap fix would have been to update the expectation. The fixture formats the
amount instead, so the test still checks the string it always checked.
