---
id: B-06
title: "petich-ktor: the catalogue names -jvm coordinates, which cannot resolve for a native target"
status: done
priority: P1
size: S
stage: stage-2-ktor
blocked_by: [B-03]
---

# B-06 — The HTTP surface and five coordinates that end in `-jvm`

All five Ktor entries in the version catalogue name the JVM artefact by coordinate —
`io.ktor:ktor-server-core-jvm` and four siblings (`gradle/libs.versions.toml`). In a multiplatform
source set that is not a missed optimisation: it is the line that makes the native compilation
unresolvable, whatever targets the module declares. The platform-agnostic coordinates publish
`linuxx64` klibs at the pinned 3.5.2 — server-core, content-negotiation, status-pages, test-host and
the JSON bridge, all verified present
([research §1.4](../research/research-native-port.md)).

- **Drop the suffix and add the target.** `petich-ktor` touches only plugin, routing, request and
  response APIs, all of them common; a Ktor server library in the portfolio already runs
  `testApplication` from `commonTest` on native (`kore/kore-ktor/build.gradle.kts`), so the routing
  test can move with it — its only JVM-ism is a `ConcurrentHashMap` in a fake repository.
- **Rejected: keeping the `-jvm` coordinates and adding native-only aliases.** Two names for one
  dependency, and the one a source set picks then depends on which source set a reader is looking
  at. The suffix exists for consumers who genuinely have only a JVM target; this module does not.
- **Does not cover:** an engine. `petich-ktor` depends on `ktor-server-core` and takes whatever
  `EmbeddedServer` the application builds — that stays true on native, where the engine will be CIO.

- AC: `linuxX64Test` compiles and runs `PetichRoutingTest` against `testApplication`; a native
  consumer's build resolves `io.github.youndie.petich:petich-ktor` and links.
- Anchors: `gradle/libs.versions.toml`, `petich-ktor/build.gradle.kts`,
  `petich-ktor/src/jvmTest/kotlin/io/github/youndie/petich/ktor/PetichRoutingTest.kt`

## Closed 2026-09-16

Five coordinates lost their `-jvm` suffix, `petich-ktor` gained `linuxX64()`, and
`PetichRoutingTest` moved to `commonTest`.

**Ktor's test host runs on Kotlin/Native.** `testApplication` drives the routes on both targets:
**7 tests on `jvmTest`, the same 7 on `linuxX64Test`**, 0 failures. That was the part of this item
carrying the most risk — the rest is a build file — and it is answered by a run rather than by the
klib's existence in a registry listing.

**The suffix was the whole blocker, and it is worth saying why.** A `-jvm` coordinate names one
platform's artefact. In a multiplatform source set it does not merely fail to help: it is what makes
the native compilation unresolvable no matter which targets the module declares. The
platform-agnostic coordinate resolves to the jvm artefact for a jvm consumer and to the klib for a
native one, which is the mechanism KMP publication exists for — and the jvm suites staying green
through this change is the evidence that nothing was taken away from the existing consumers.

**One more `java.util.Map` method**, in the same shape as [B-04](B-04-scenario-suites-on-both-targets.md):
the routing test's fake repository used `putIfAbsent`. Replaced with `getOrPut`.

**Verified through the real path.** Local publication `b06-probe`, then the probe from
[B-02](B-02-native-consumer-probe.md) declaring six coordinates at once —
`petich-core`, `petich-ktor`, `petich-outbox-core`, `petich-idempotency`, `petich-scheduler`,
`petich-conformance` — RESOLVED and linked. Six `<module>-linuxx64` directories now sit in the local
repository beside the jvm ones. What the probe proves for the five it does not call into is
resolution and linking; what `linuxX64Test` proves for `petich-ktor` is stronger, and it is the run
above.

**Still jvm-only, by decision rather than omission:** `petich-postgres` (research D3) and
`petich-chronik` ([B-11](B-11-chronik-bridge-blocked.md), waiting on a release in another
repository).
