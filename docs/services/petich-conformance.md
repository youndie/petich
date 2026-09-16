---
id: petich-conformance
title: petich-conformance — the rules a store has to satisfy
type: service
tech_stack: [Kotlin Multiplatform, jvm, linuxX64]
depends_on: [petich-core, petich-outbox-core, petich-idempotency, petich-scheduler]
---

# petich-conformance

Four corpora of cases — one per storage contract — that can be run against any implementation of
them. It is a test library, not part of the engine: nothing in petich depends on it at runtime.

## Why it exists before the second store

petich promises things about storage that live in comments and in one implementation: an update
applies only when the version follows, a refused update writes no events, `tryClaim` is atomic at
the storage level. Until this module there was no way to *ask* an implementation whether it does
those things, and `petich-postgres` had no storage test at all.

A corpus written after a second store describes the intersection of the two — whatever both happen
to do becomes the rule, including whatever both get wrong. Written while there is one, it is a
statement about the contract. The order matters enough to be a decision: see
[research D4](../research/research-native-port.md).

## What is in it

| Corpus | Subject | Contracts covered |
|---|---|---|
| `PetichStoreConformance` | `PetichStoreSubject` | `PetichRepository`, plus `OutboxAwarePetichRepository` and `ExpiringPetichRepository` when the store implements them — those cases skip themselves when it does not |
| `OutboxStoreConformance` | `OutboxStoreSubject` | `OutboxRepository` |
| `IdempotencyStoreConformance` | `IdempotencyStoreSubject` | `IdempotencyRepository` |
| `ScheduleStoreConformance` | `ScheduleStoreSubject` | `ScheduleRepository` |

`run(subject)` returns a list of `Finding(rule, detail)`. Empty means every rule held. Findings are
collected rather than thrown, because a new store otherwise gets fixed one finding per run — and the
second finding is often what explains the first.

## Wiring a subject

Two things catch everybody once:

- **`reset()` runs before every case**, not once per run. A row left behind decides the next case's
  answer, and the corpus then reports a defect that belongs to the case before it.
- **The payload must be registered.** The corpus writes `ConformancePayload`, and polymorphic
  serialisation is registration on every platform — a store handed a `Json` that has not been told
  about it fails every case with a serialisation error instead of a rule. `ConformanceTest` in
  `petich-postgres` shows the four lines.

## What the corpus deliberately does not say

- **The order of `fetchPending`.** The engine promises at-least-once delivery, not order, and the
  one implementation orders by a stamp several replicas write (youndie/petich#20). A rule here would
  invent a guarantee and the next store would be "fixed" to meet it.
- **Anything about DDL.** petich ships no migrations; a store may lay its tables out as it likes.
- **Competition between two callers over the same rows** — except for `tryClaim`, whose atomicity is
  part of its written contract and has a case with eight concurrent callers on a real dispatcher.
  Everything else runs one caller at a time, so a store that passes can still hand one row to two
  workers. That needs its own test next to the store; it is named as a gap here rather than left to
  be discovered.

## Code anchors

| What | Code |
|---|---|
| the corpora | `petich-conformance/src/commonMain/kotlin/io/github/youndie/petich/conformance/` |
| the run loop and `Finding` | `petich-conformance/src/commonMain/kotlin/io/github/youndie/petich/conformance/Corpus.kt` |
| the payload a subject must register | `petich-conformance/src/commonMain/kotlin/io/github/youndie/petich/conformance/ConformancePayload.kt` |
| the only implementation run against it today | `petich-postgres/src/test/kotlin/io/github/youndie/petich/postgres/ConformanceTest.kt` |

## Quirks

- **Three of the tests next to it break the store on purpose.** `ConformanceTest` runs the corpus
  against a version-blind store, an outbox-dropping store and a read-then-write `tryClaim`, and
  asserts which rules come back. Without them a green corpus and a corpus that cannot report
  anything look identical.
- **The version-blind store trips two rules, not one.** With no version predicate the stale update
  applies, so its events are written and the outbox rule fails too — a defect in one place named in
  two, which is worth knowing before reading the second report as a second bug.
