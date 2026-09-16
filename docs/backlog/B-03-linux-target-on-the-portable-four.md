---
id: B-03
title: "linuxX64 on the four modules that already compile anywhere"
status: done
priority: P0
size: S
stage: stage-1-portable
blocked_by: [B-02]
---

# B-03 — The four modules that need one line each

`petich-core`, `petich-outbox-core`, `petich-idempotency` and `petich-scheduler` are multiplatform
projects with one target. They depend on coroutines, kotlinx-datetime and kotlinx-serialization and
on nothing else; no `commonMain` mentions `java.*`; the clock is a parameter
([research §1.2](../research/research-native-port.md)). Every one of the reasons a saga engine is
usually stuck on the JVM is already absent here, and the library has never said so.

- **The change is `linuxX64()` per module, and that is the argument.** Portability that costs four
  lines to declare is cheaper to declare than to discuss. What it buys is the end of a resolution
  failure: a native consumer today does not get a library with a missing feature, it gets a build
  that stops before its own first line.
- **Its own change, not folded into anything.** `allWarningsAsErrors` and `explicitApi()` are on
  (research §1.7), so the second compiler emits its findings as build failures, all at once. The
  same two lines in chronik found a missing `kotlin.jvm.JvmInline` import and a comma inside a
  backticked test name — both in code, neither visible from a JVM-only build.
- **Rejected: adding Apple and mingw targets while the file is open.** Test tasks nobody runs look
  like coverage; a target is one line whenever a consumer asks (research D2).
- **Does not cover:** `petich-ktor` ([B-06](B-06-ktor-module-and-the-jvm-pinned-catalogue.md)),
  `petich-chronik` ([B-11](B-11-chronik-bridge-blocked.md)), `petich-postgres` (research D3).

- AC: `./gradlew linuxX64Test` runs the existing `commonTest` suites of all four modules and is
  green; the published metadata of each gains `linuxX64ApiElements-published`; whatever the second
  compiler found is written into the item, not only into the diff.
- Anchors: `petich-core/build.gradle.kts`, `petich-outbox-core/build.gradle.kts`,
  `petich-idempotency/build.gradle.kts`, `petich-scheduler/build.gradle.kts`

## Closed 2026-09-16

Four lines, and the compiler behind them found one thing — the one the research named as the risk.

**Three test names carried a comma.** `Name contains illegal characters: ","` in
`SchedulerTest`, `EngineConfigTest` and `SuspendedTtlTest`: a phrase in backticks is a legal
identifier on the JVM and not on Kotlin/Native. Renamed, meaning kept (`skipped, not replayed` →
`skipped rather than replayed`). No guard is needed against a recurrence: `./gradlew build` now
contains `linuxX64Test`, so the next comma fails this repository's own gate at compilation instead
of somebody else's build.

**Nothing else.** No `kotlin.jvm` import to add — the second finding chronik got from the identical
change — and no source file outside those three names was touched.

**What ran, and where.** On the Linux box, because linking and running a `linuxX64` binary is the
subject:

| Command | Result |
|---|---|
| `./gradlew :petich-core:linuxX64Test :petich-outbox-core:linuxX64Test :petich-idempotency:linuxX64Test :petich-scheduler:linuxX64Test` | 80 tests, 0 failures (52 / 5 / 7 / 16), result XML written at 23:05 |
| `./gradlew build` — what CI runs | BUILD SUCCESSFUL |
| `tools/native-consumer-probe.py b03-probe --expect resolve` | RESOLVED, `.kexe` linked — and the same probe, same code, reported REFUSED before this change ([B-02](B-02-native-consumer-probe.md)) |
| the same probe with all four modules declared | RESOLVED |

**The metadata says it too.** Each of the four modules now publishes
`linuxX64ApiElements-published` and `linuxX64SourcesElements-published`, and a
`<module>-linuxx64` directory appears beside `<module>-jvm`. `petich-ktor`, `petich-chronik` and
`petich-postgres` are unchanged, which is what
[B-06](B-06-ktor-module-and-the-jvm-pinned-catalogue.md), [B-11](B-11-chronik-bridge-blocked.md) and
research D3 say they should be.

**Two numbers for [B-12](B-12-guards-meet-the-native-variants.md), read from this run.**
`artifact-name-audit.py` went from 7 to **17 modules** and now checks 34 files, klibs included —
it picked up the new variants without being told. `jvm-floor-audit.py` reports **7 jars and 14 jvm
variants**, exactly as before: it skips native variants by design, so half of what is published is
now outside every audit in this repository. That is the item, and it is no longer a prediction.
