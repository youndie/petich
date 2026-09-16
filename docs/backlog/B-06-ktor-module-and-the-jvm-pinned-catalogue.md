---
id: B-06
title: "petich-ktor: the catalogue names -jvm coordinates, which cannot resolve for a native target"
status: wip
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

