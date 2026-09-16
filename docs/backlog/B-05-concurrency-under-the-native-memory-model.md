---
id: B-05
title: "The per-saga lock has never run under the Kotlin/Native memory model"
status: open
priority: P1
size: S/M
stage: stage-1-portable
blocked_by: [B-03]
---

# B-05 — One concurrency case that runs on both targets

The engine serialises work on a saga with a map of per-saga `Mutex`es guarded by a second `Mutex`
(`petich-core/src/commonMain/kotlin/Petich.kt:388`), and optimistic locking by version behind it.
Every suite that touches it runs under `runBlocking` on one thread; the single exception switches to
`Dispatchers.Default` for one case (`EngineConfigTest.kt:227`). On the JVM that is a thread pool
with the JVM memory model under it. On Kotlin/Native it is a different memory model and, in any real
service, several worker threads. Nothing here has ever been observed under that combination.

- **One case, deliberately small, and it must be able to fail for the right reason.** Two coroutines
  on a multi-threaded dispatcher advance the same saga; the assertion is the version count and the
  number of executed steps, not a duration. A timing assertion on a shared runner measures the
  runner.
- **Prove the case can fail before trusting it.** Remove the per-saga lock and it must go red on
  both targets; a concurrency test that passes against a broken engine is the usual outcome, and
  the check costs one run.
- **Rejected: a stress run with many coroutines and a pass/fail on throughput.** It is slower, it is
  flaky on a shared runner, and it answers a question nobody asked.
- **Does not cover:** storage-level races. Two processes competing for the same row is the store's
  contract, and it belongs to the corpus ([B-07](B-07-storage-conformance-corpus.md)).

- AC: the case is green on `jvmTest` and `linuxX64Test`, and red on both when the per-saga lock is
  removed; the removal run is quoted in the item.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-core/src/commonTest/kotlin/EngineConfigTest.kt`,
  `petich-core/src/commonTest/kotlin/VersionConflictTest.kt`

