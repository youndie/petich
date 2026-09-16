---
id: B-03
title: "linuxX64 on the four modules that already compile anywhere"
status: wip
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

