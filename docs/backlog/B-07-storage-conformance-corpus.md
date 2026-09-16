---
id: B-07
title: "A conformance corpus for the four storage contracts, written while there is one implementation"
status: open
priority: P0
size: M
stage: stage-3-storage
---

# B-07 — What a store has to do, as cases that can be run against it

petich has four storage contracts — `PetichRepository` with its outbox-aware, side-effect-aware and
expiring extensions, `OutboxRepository`, `IdempotencyRepository`, `ScheduleRepository` — and exactly
one implementation of each, in `petich-postgres`. That module's only test asserts package names and
index names ([research §1.5](../research/research-native-port.md)); no storage behaviour is
exercised anywhere in this repository. So the rules a store must satisfy exist only as the Exposed
code that happens to satisfy them.

- **The corpus comes before the second store, and that ordering is the whole item.** Written
  afterwards, a corpus describes the intersection of two implementations — including whatever both
  get wrong. Written now, it is a statement of what petich promises, and the second store is
  measured against it rather than consulted.
- **Cases named by the rule, findings collected rather than thrown.** "The update applies only when
  the version matches"; "events and the state change become visible together or not at all"; "a key
  claimed twice with a different fingerprint is refused"; "a due job is returned once per tick". A
  run reaches the end and reports everything, because a new backend otherwise gets fixed one finding
  per run. The shape is in the neighbouring repository
  (`chronik/chronik-conformance/src/commonMain/kotlin/ConformanceKit.kt`) and it earned its keep
  there: three violated rules in a store that had passed everything hand-written.
- **A module, in `commonMain`, so it runs on both targets** — and so a consumer writing their own
  store can take it. Consumers do not get it as a runtime dependency of any store; it is a test
  dependency.
- **The corpus is not enough on its own, and the item says where.** It runs one worker at a time, so
  atomicity under competition needs its own case with several workers — the defect chronik's corpus
  could not see.
- **Does not cover:** the Exposed store's Postgres-specific behaviour (JSON columns, the declared
  indexes). That stays in `petich-postgres`.

- AC: the corpus runs green against `ExposedPetichRepository` and its three siblings on a real
  Postgres, and a deliberately broken store — the version predicate removed from `update` — is
  reported by name, not by a timeout.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-outbox-core/src/commonMain/kotlin/OutboxRepository.kt`,
  `petich-idempotency/src/commonMain/kotlin/io/github/youndie/petich/idempotency/IdempotencyRepository.kt`,
  `petich-scheduler/src/commonMain/kotlin/SchedulerWorker.kt`,
  `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt`

