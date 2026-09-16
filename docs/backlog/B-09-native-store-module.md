---
id: B-09
title: "The native store: the four contracts implemented over sqlx4k, driver supplied by the application"
status: open
priority: P1
size: L
stage: stage-3-storage
blocked_by: [B-07, B-08]
---

# B-09 — Somewhere for a native service to put a saga

After the targets land, a Kotlin/Native service can build the engine and has nowhere to store a
saga: the only implementation of the four contracts is Exposed over JDBC, and JDBC is a JVM
interface rather than a protocol ([research §1.5](../research/research-native-port.md)). The
durability the engine promises is, until this module exists, a JVM-only promise.

- **The module depends on the database-agnostic half of sqlx4k and never on a driver.** A native
  binary that links two sqlx4k drivers does not link at all — each carries its own Rust runtime and
  they define the same symbols. A store that carries none cannot cause that collision whichever
  driver the application brings, and the application is already the one that opens the connection:
  `petich-postgres` takes an Exposed `Database` and declares no driver either.
- **Accepted by the corpus, not by a diff against Exposed** ([B-07](B-07-storage-conformance-corpus.md)),
  plus its own cases for what the corpus structurally cannot see: several workers claiming at once,
  and the outbox insert committing with the state change rather than beside it.
- **The transactional promise is the one to prove first.** `update(petich, outboxEvents)` writing
  both in one transaction is what makes "the work happened and the notification never went out"
  impossible; a store that satisfies every other rule and loses that one is worse than no store,
  because the engine's README promises it.
- **Rejected: `expect`/`actual` inside `petich-postgres`.** Research D3 — one coordinate bought, the
  ability to depend on either half alone lost, and no shared code beyond the SQL text.
- **Does not cover:** DDL. Like `petich-postgres`, the module ships no migrations; the schema is the
  consumer's, and the table and index names are part of the contract.

- AC: the corpus is green against this store on both targets, an atomicity case with four workers
  hands out each row once, and the [probe](B-02-native-consumer-probe.md) — a `linuxX64` project with
  its own driver — runs a saga end to end: create, suspend, resume, compensate, and the outbox rows
  that go with each.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt` (the behaviour being reproduced),
  `gradle/libs.versions.toml`, `settings.gradle.kts`

