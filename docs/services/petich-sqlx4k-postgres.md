---
id: petich-sqlx4k-postgres
title: petich-sqlx4k-postgres — the store a Kotlin/Native service can take
type: service
tech_stack: [Kotlin Multiplatform, jvm, linuxX64, sqlx4k, PostgreSQL]
depends_on: [petich-core, petich-outbox-core, petich-idempotency, petich-scheduler]
---

# petich-sqlx4k-postgres

The four storage contracts over sqlx4k, speaking Postgres. It is the second implementation of what
`petich-postgres` implements with Exposed — and the first one that exists where the JVM does not.

## Why it exists

JDBC is a JVM interface rather than a protocol, so `petich-postgres` does not travel. Until this
module a Kotlin/Native service could take the engine, its HTTP surface and the three independent
modules, and had nowhere to put a saga
([research §1.5](../research/research-native-port.md), D8).

Postgres rather than SQLite is the owner's decision ([B-08](../backlog/B-08-which-database-for-the-native-store.md)):
the corpus then compares like with like, and a service moving from the JVM can point both stores at
one database — the column names here match `petich-postgres` exactly, so a saga written by either is
read by the other.

## What it owns

| Class | Contract |
|---|---|
| `PostgresPetichStore` | `PetichRepository`, `OutboxAwarePetichRepository`, `ExpiringPetichRepository` |
| `PostgresOutboxStore` | `OutboxRepository` |
| `PostgresIdempotencyStore` | `IdempotencyRepository` |
| `PostgresScheduleStore` | `ScheduleRepository` |

Three things it deliberately does **not** own:

- **the driver.** It depends on the database-agnostic half of sqlx4k and is handed a `Driver` the
  application opened. A native binary that links two sqlx4k drivers does not link at all — they
  define the same symbols — and a store that carries none cannot cause that, whichever the
  application brings;
- **the schema.** `petichPostgresSchema(...)` returns the statements as text; the application runs
  them from its own migrations, with its own version numbering. This module never executes them;
- **the clock.** It takes a `PetichClock`, like the engine. There is no platform to read here, and
  the Exposed store's two `System.currentTimeMillis()` calls are a known defect under replica skew
  (youndie/petich#20, [B-10](../backlog/B-10-the-clock-the-second-store-cannot-read.md)).

## Wiring it

```kotlin
val db = PostgreSQL(url = …, username = …, password = …, options = …)   // yours
petichPostgresSchema().forEach { db.execute(it).getOrThrow() }          // or your migration tool

val store = PostgresPetichStore(db, json, clock)
val engine = PetichEngine(interceptors, store)
```

**The `Json` must have the payload types registered**, and the compiler will not say so: the saga's
payload is stored polymorphically, so an unregistered subclass fails at the first write with
*Serializer for class 'X' is not found*. A consumer also needs `kotlin("plugin.serialization")` —
`@Serializable` without the plugin is an annotation nothing reads, which the probe found by failing
exactly that way.

## Code anchors

| What | Code |
|---|---|
| the four stores | `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/` |
| the schema the application runs | `petich-sqlx4k-postgres/src/commonMain/kotlin/io/github/youndie/petich/sqlx4k/postgres/Schema.kt` |
| the corpus run against it, both targets | `petich-sqlx4k-postgres/src/commonTest/kotlin/io/github/youndie/petich/sqlx4k/postgres/PostgresConformanceTest.kt` |
| what the corpus cannot see: four writers racing | `petich-sqlx4k-postgres/src/commonTest/kotlin/io/github/youndie/petich/sqlx4k/postgres/ConcurrentWritersTest.kt` |
| the JVM store this one mirrors | `petich-postgres/src/main/kotlin/ExposedPetichRepository.kt` |
| a native consumer running a whole saga | `tools/native-consumer-probe/src/linuxX64Main/kotlin/Main.kt` |

## How the tests get a database

Gradle starts one container and points both test tasks at it through the environment
(`PETICH_TEST_POSTGRES_URL`), because `linuxX64Test` cannot use Testcontainers — that is a JVM
library. The two targets write to **different tables** (`petiches_jvm`, `petiches_linuxx64`): they
share a server, and a run of one must not be able to turn the other red.

Nothing is skipped when the database is missing. A store suite that quietly passes without one is
how a store ships untested; the harness fails with the command to run instead.

## Quirks

- **`markFailed` counts in SQL** — `retry_count = retry_count + 1` — where the Exposed store reads
  the value and writes it back. Two relays failing to deliver one event both write the same number
  there and an attempt disappears. The corpus cannot see it (it runs one caller at a time), so it is
  written here.
- **The insert and the update bind different parameters.** The saga's `type` is written once and
  never updated, and sqlx4k refuses a parameter the statement does not mention — one shared binder
  for both statements is how this module failed its first corpus run.
- **`saveOrGet` is `ON CONFLICT DO NOTHING` plus a read, in one transaction**, not a read followed by
  an insert: two callers arriving with one new saga both see "no such row" in the second shape.
