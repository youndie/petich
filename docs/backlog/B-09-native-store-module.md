---
id: B-09
title: "The native store: the four contracts implemented over sqlx4k, driver supplied by the application"
status: done
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

- **The corpus cannot see one caller racing another** ([B-07](B-07-storage-conformance-corpus.md)
  closes with this gap named): it runs one caller at a time, so a store that passes every rule can
  still hand one row to two workers. The atomicity test with several workers belongs next to the
  store, in this item.

- AC: the corpus is green against this store on both targets, an atomicity case with four workers
  hands out each row once, and the [probe](B-02-native-consumer-probe.md) — a `linuxX64` project with
  its own driver — runs a saga end to end: create, suspend, resume, compensate, and the outbox rows
  that go with each.
- Anchors: `petich-core/src/commonMain/kotlin/Petich.kt`,
  `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt` (the behaviour being reproduced),
  `gradle/libs.versions.toml`, `settings.gradle.kts`

## Closed 2026-09-17

`petich-sqlx4k-postgres`: four stores, `jvm` and `linuxX64`, accepted by the corpus on **both**
targets — `PostgresConformanceTest` (4 cases) and `ConcurrentWritersTest` (2) run twice, **12 tests,
0 failures**, against a real Postgres Gradle starts for them.

**The corpus earned its keep on the first run**, which is the whole argument of
[B-07](B-07-storage-conformance-corpus.md) arriving on time: it reported five rules broken and
named the cause — *the implementation threw: SQLError: [NamedParameterNotFound] :: Parameter 'type'
not found*. One binder served the insert and the update, and a saga's `type` is written once and
never updated; sqlx4k refuses a parameter the statement does not mention. Five rules, one defect,
found before any of it was published.

**What the corpus could not see, and now has its own test.** Four writers race to advance one saga:
exactly one wins, and the version predicate is what makes that true. Four callers `saveOrGet` one
new id: one row, one answer to all four. Both on `Dispatchers.Default`, both asserting counts rather
than durations. That was this item's debt from B-07, and it is paid here rather than left to a
consumer.

**Verified through the real path — a native binary, its own driver, a real database.**

```
pass 1: result=ActionRequired stored=PENDING_SIGNATURE version=1
pass 2: result=Success stored=COMPLETED version=3
outbox: [(shipped-order-1, order.shipped)]
the waiting step ran 1 time(s), the notifying step 1
SAGA OK: created, suspended, resumed, completed, and its event is in the outbox
```

The probe links `petich-sqlx4k-postgres` from the published klib **and its own
`sqlx4k-postgres`** — that it links at all is the evidence for the store carrying no driver, not the
comment saying so.

**Two findings from that run, both in the probe rather than in the store:**

*The engine does not re-run the step that suspended.* The first version emitted the outbox event
from the suspending interceptor on the resume pass; it never ran, and the outbox stayed empty. The
resume continues **after** that step, deliberately — so the event moved to the next one, which is
also a better test: it proves the outbox row and the state change commit together.

*A consumer that stores sagas needs the serialization plugin.* `@Serializable` without
`kotlin("plugin.serialization")` is an annotation nothing reads, and the failure arrives at the
first write as *Serializer for class 'OrderPayload' is not found*. Written into the module's
document, where a consumer meets it before the failure does.

**One thing left unexplained rather than papered over.** Run against a Postgres started by hand on
another port, the Rust driver panicked with `Io :: Unexpected error occurred` while `psql` on the
same URL worked. Against the container Gradle starts for the tests — same image, same options — it
connects every time. The runs above are the latter; the discrepancy is recorded because a reader hitting
it should know it has been seen, not to claim it is understood.

**Half of [B-10](B-10-the-clock-the-second-store-cannot-read.md) arrived here by construction:** this
store takes a `PetichClock` and reads no platform clock. What remains is the Exposed store's two
reads and what to do about youndie/petich#20.
