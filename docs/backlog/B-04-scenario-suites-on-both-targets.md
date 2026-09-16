---
id: B-04
title: "The three end-to-end saga suites run on the JVM only"
status: open
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

