# petich

[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![maven central](https://img.shields.io/maven-central/v/io.github.youndie.petich/petich-core?label=maven%20central&color=40c14a)](https://central.sonatype.com/namespace/io.github.youndie.petich)
[![snapshots](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/petich/petich-core?name=snapshots&color=blue&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/petich/petich-core)

**a distributed saga engine for Kotlin** — a multi-step operation is described as a chain of
interceptors; the engine walks it through phases and, when any step fails, undoes the steps it
recorded as done

> 🔁 one interceptor → one step forward and one step back

Built around a single question: what is left in the system if you die halfway.

Why it is built this way, and what is being worked on: [`docs/`](docs/) and the
[backlog](backlog.md).

### 🤔 What it solves

An operation that spans several services is not one database write. Reserve capacity, claim a quota,
apply the change, hand it to a downstream system, notify. Any step can refuse, and some are already
irreversible by then. An ordinary `try/catch` does not help — what needs undoing is not a database
transaction but actions already performed, in reverse order, and only those that really happened.

The engine takes on exactly that:

- **order and phases** — `ENRICHMENT → VALIDATION → AUTHORIZATION → EXECUTION → POST_PROCESSING`,
  with steps inside a phase ordered by priority, and ties by name rather than by whatever order a
  dependency container assembled them in;
- **compensation** — a failure at step N calls `compensate()` on steps N … 1, in reverse, step N
  included: the engine never learned whether that one's effect landed, so it undoes it too — see
  **What it asks of an interceptor**;
- **waiting for a human** — a saga can pause for a confirmation and continue on a later HTTP
  request, holding neither a thread nor a database connection;
- **a deadline on that wait** — a suspended saga nobody came back to is rolled back by a background
  sweeper instead of living forever while holding resources it already claimed;
- **a saga whose process died** — the same sweeper re-drives what was left in `PROCESSING` or
  `COMPENSATING`, which is what the state written at every step boundary was paid for;
- **resistance to races** — optimistic locking by version plus a per-saga mutex inside the process;
- **reliable notifications** — with an outbox-aware repository (`petich-postgres` is one) the intent
  to emit an event is written in the SAME transaction as the state change, which makes "the work
  happened but the notification never went out" structurally impossible. A repository without that
  support still works; the engine falls back to a plain update and drops the events. That fallback
  is now countable and refusable: `PetichEngineMetrics.onDroppedEvents` fires on every event lost
  this way, and `PetichEngineConfig(requireOutbox = true)` refuses to build an engine whose
  repository cannot store them at all. Both are off by default, so a deliberately outbox-free
  application changes nothing; anything wiring the outbox to a broker wants the second one, because
  the drop is otherwise invisible — the saga completes and its state is correct.

### 📦 Modules

| module | what for | targets | depends on |
| --- | --- | --- | --- |
| `petich-core` | the engine: sagas, interceptors, phases, compensation, suspend/resume, TTL | jvm, linuxX64 | — |
| `petich-ktor` | REST endpoints for creating and resuming a saga | jvm, linuxX64 | `petich-core` |
| `petich-postgres` | storage on Exposed | **jvm only** — Exposed over JDBC, and JDBC is a JVM interface rather than a protocol | core, outbox, idempotency, scheduler |
| `petich-outbox-core` | at-least-once event delivery with backoff and dead lettering | jvm, linuxX64 | — |
| `petich-idempotency` | protection against a key reused with a DIFFERENT request | jvm, linuxX64 | — |
| `petich-scheduler` | a saga on a schedule, starting with no HTTP initiator | jvm, linuxX64 | — |
| `petich-chronik` | a fired [chronik](https://github.com/youndie/chronik) timer resumes a suspended saga | jvm, linuxX64 — needs chronik 0.2.0 or newer | `petich-core` |
| `petich-conformance` | the rules a storage implementation has to satisfy, as cases you can run against yours | jvm, linuxX64 | core, outbox, idempotency, scheduler |
| `petich-sqlx4k-postgres` | the same storage contracts over sqlx4k, for a service with no JVM; brings no driver | jvm, linuxX64 | core, outbox, idempotency, scheduler |

A Kotlin/Native service can take the engine, its HTTP surface, the three independent modules **and a
store**: `petich-sqlx4k-postgres` implements the same four contracts over sqlx4k and is accepted by
the corpus in `petich-conformance` on both targets. `petich-postgres` stays where it is — Exposed
over JDBC — and both write the same columns, so a service can move one process at a time. See
[docs/](docs/).

Three modules deliberately do not depend on the core. `petich-outbox-core` knows only about a row —
"id/type/payload, deliver at least once"; `petich-scheduler` only about "it is time" and "here is the
payload"; `petich-idempotency` only about "this key already arrived with a different fingerprint".
Each is usable on its own, and that is not an accident but the condition under which they do not turn
into part of somebody's feature.

### 🔌 Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.youndie.petich:petich-core:0.2.0")
    implementation("io.github.youndie.petich:petich-ktor:0.2.0")
    implementation("io.github.youndie.petich:petich-postgres:0.2.0")
}
```

Releases are on Maven Central. Snapshots keep going to
`https://reposilite.kotlin.website/snapshots` as `<version>.<build>` — add that repository beside
`mavenCentral()` to take one.

**On Kotlin/Native** the coordinates are the same; take `petich-sqlx4k-postgres` instead of
`petich-postgres`, and bring your own sqlx4k driver:

```kotlin
dependencies {
    implementation("io.github.youndie.petich:petich-core:0.2.0")
    implementation("io.github.youndie.petich:petich-sqlx4k-postgres:0.2.0")
    implementation("io.github.smyrgeorge:sqlx4k-postgres:1.13.1")   // the driver is yours
}
```

`petich-postgres` deliberately ships no driver and no connection pool: it works with an Exposed
`Database` handed to it and does not know which DBMS sits underneath. Choosing a driver is the
application's decision. It ships no DDL either — the tables describe themselves, indexes included,
so `MigrationUtils` and the Exposed Gradle plugin generate a schema that matches what the queries
actually filter on.

#### Upgrading a schema that already holds sagas

A fresh install takes the whole table from the generator (`MigrationUtils`) or from
`petichPostgresSchema()`. An existing one needs the difference, and since petich ships no migrations
that difference is stated here, column by column, so it can be copied into your own migration.

| column | since | what to run on an older schema |
| --- | --- | --- |
| `id`, `type`, `current_phase`, `current_interceptor_index`, `status`, `payload`, `enriched_payload`, `version` | 0.2.0 or earlier | part of the original table |
| `suspended_until` | 0.2.0 or earlier | part of the original table |
| `compensation_attempts` | 0.3.0 | `ALTER TABLE petiches ADD COLUMN IF NOT EXISTS compensation_attempts INT NOT NULL DEFAULT 0;` |
| `updated_at` | 0.3.0 | `ALTER TABLE petiches ADD COLUMN IF NOT EXISTS updated_at BIGINT NOT NULL DEFAULT 0;` |
| `chain_fingerprint` | 0.3.0 | `ALTER TABLE petiches ADD COLUMN IF NOT EXISTS chain_fingerprint VARCHAR(64);` |

Every one of the 0.3.0 columns carries a default or is nullable, so each `ALTER` is a catalogue
change rather than a table rewrite — and none of them stops a saga written by the previous version
from being read by this one. **Until they exist, every saga fails**: the store selects the columns by
name, so the first write reports `column petiches.compensation_attempts does not exist` and nothing
in that message names petich or a version.

**A consumer on 0.1.0 should take the whole table rather than a delta.** 0.1.0 predates this
repository's tags and the move to the `io.github.youndie.petich` coordinates, so there is nothing to
diff against and no statement here can be verified for it.

The `ALTER`s above take a brief `ACCESS EXCLUSIVE` lock, as does
`PetichTable.tuningStatements()` — see below. If your migrations bound how long they wait for a
lock, these belong under the same bound.

One thing a schema generator cannot say: `PetichTable.tuningStatements()` returns an
`ALTER TABLE … SET (fillfactor = 80)` to run once beside the generated DDL. A saga row is updated at
every step boundary, and Postgres can keep those updates off the index chain only while the page has
room for the new version; at the default 100 there is none, and the table and its index bloat for a
workload that was avoidable. `petich-sqlx4k-postgres` says the same thing as `WITH (fillfactor = 80)`
in the SQL it hands you, because it states its schema as SQL — same setting, two shapes.

### ✍️ What it looks like

A saga step is an interceptor: what to do, and how to undo it.

```kotlin
class ReserveStockInterceptor(private val stock: StockRepository) : PetichInterceptor<OrderPayload> {
    override val phase = PetichPhase.EXECUTION
    override val priority = 10

    override fun supports(payload: PetichPayload) = payload is OrderPayload

    override suspend fun intercept(petich: Petich, payload: OrderPayload): InterceptorResult {
        stock.reserve(payload.sku, payload.quantity)
        return InterceptorResult.Proceed()
    }

    override suspend fun compensate(petich: Petich, payload: OrderPayload) {
        stock.release(payload.sku, payload.quantity)
    }
}
```

A step that needs confirmation returns `Suspend` — the saga stops and waits for a separate `resume`
call:

```kotlin
return InterceptorResult.Suspend(requiredAction = "CONFIRM", ttl = 5.minutes)
```

`ttl` is this particular step's deadline. If it passes, the sweeper rolls the saga back exactly as a
refusal would: typing a one-time code and approving a long-running request live on different time
scales, and the step knows that, not the engine.

### ⚠️ What it asks of an interceptor

Four rules. They are the engine's side of the bargain stated from the other end, and an interceptor
that breaks them fails in ways that look like storage faults.

**`intercept()` must be idempotent.** The engine calls it, and only then writes the new position —
so a call that already happened can happen again. This is not the rare case of a process dying in
between: an optimistic-lock conflict on that write makes the engine re-read the row and run the same
step a second time, on a healthy instance, under nothing worse than two requests touching one saga.
A step whose effect is a remote call wants its own idempotency key, and the money-shaped ones want
the remote side to honour it.

**`compensate()` must be idempotent too**, for the same reason mirrored: the rollback commits how
far it has got *after* calling the step, so an interrupted rollback re-compensates the step it was
on.

**`compensate()` may be called for a step that did not happen.** When `intercept()` throws or times
out, the engine cannot tell an effect that reached the far side from a call that never landed — it
only ever learns that the step did not report success — so it rolls that step back as well.
`release` therefore has to tolerate arriving without its `reserve`, and a compensation that instead
assumes its own step committed will undo something that was never done. The guard is usually the
evidence the step leaves: undo what the record says happened, and return quietly when there is no
record.

Two results are NOT this case, and both keep the old starting point: a step that returns
`Compensate` reported its outcome and is not undone by the engine, and an expired suspension rolls
back from the step that suspended, which committed.

**A saga remembers which steps it has run, and refuses to resume against a different chain.** Its
position is an index into a list filtered by `supports()` and sorted by priority — assembled fresh on
every pass — so a deploy that adds, removes or re-prioritises a step in the same or an earlier phase
would otherwise re-point every suspended saga at a different step, and the rollback with it. Each
write records a fingerprint of the steps already run; a resume that cannot reproduce it stops with a
message naming both, and runs nothing.

The fingerprint covers that prefix and not the whole chain, so appending a step is an ordinary
release. The column is nullable and a null is never refused, so the upgrade that introduces the guard
does not stop the sagas it cannot yet protect — those keep the old behaviour, including its silence.
`PetichEngine.describeChain(payload)` prints the resolved order for a payload type: log it at
startup, or snapshot it in a test, and a chain that moved shows up in a diff instead of in a saga.
`PetichInterceptor.stepKey` is what that order and that fingerprint are made of — the class name by
default, overridable to survive a rename.

**A `compensate()` that throws stops the rollback below it.** The steps under the one that threw are
not undone, and the saga stays `COMPENSATING`. That is retried — the whole rollback, not just the
step — up to `maxCompensationAttempts` separate passes, counted on the saga itself so a restart does
not reset them; at the bound the saga becomes `COMPENSATION_FAILED`, which is terminal and means
"undone in part, and nothing will try again". `CompensationFailureHandler.exhausted` is asked for
events to commit in that same transaction, so the announcement cannot be lost separately from the
status, and `PetichEngineConfig(requireCompensationHandler = true)` refuses at construction to build
an engine whose compensation failures go nowhere. This is the one state the engine cannot leave on
its own; nothing yet re-drives an abandoned saga into it (see the Cost section).

**Both ways of refusing a saga undo what ran.** They differ in the name the saga ends under, not in
whether the work comes back:

| result | what the engine does | the saga ends as |
| --- | --- | --- |
| `Reject(reason)` | steps N−1 … 1 are compensated in reverse | `REJECTED` — a business refusal |
| `Compensate(reason)` | the same | `FAILED` — a fault |

Pick by what the client should be told, which is the question an interceptor can answer about
itself. `Reject` used to compensate nothing, which was right for a validation refusing before
anything had happened and silent theft after a step had touched the outside world — and telling
those apart needs to know whether an *earlier* step had an effect, which is knowledge about somebody
else's steps. The engine has it; the interceptor does not.

In both cases the refusing step itself is not undone: unlike a step that threw, it reported its
outcome, and what it reported is that it declined to act.

### 🚫 What it does not do

- **it does not choose a DBMS and does not create tables** — DDL is the application's, and so are
  migrations;
- **it does not deliver events itself** — `petich-outbox-core` provides the mechanism; the transport
  (a queue, a webhook, a push) is implemented by the application;
- **it does not store a result for idempotency** — replaying a terminal saga under the same id is
  short-circuited by the engine itself, while `petich-idempotency` catches a different case: the same
  key with different request parameters;
- **it does not serialise sagas for you** — the per-saga mutex is keyed by saga id and the
  optimistic lock sits on the saga's own row, so two sagas touching the same business entity do not
  contend at the engine level. If they contend, the source is the application's own writes to a
  shared row, and the cure lives in the data model rather than here.

### 💰 Cost

A saga of six steps costs **eight writes to the saga table**: one `INSERT`, one `UPDATE` per step,
and one that completes it. Events an interceptor hands over ride along inside those writes rather
than adding any — three steps emitting one event each make three rows in the outbox and no extra
write to the saga.

**A suspension does not add a write; it moves one.** The step that suspends writes
`PENDING_SIGNATURE` instead of the `Proceed` it would have written, and it is deliberately not
re-executed on resume, so the same six steps cost the same eight writes whether the saga waits for a
human in the middle or not. What the resume does add is a `saveOrGet` at the head of its pass, which
changes no row here and is an `INSERT … ON CONFLICT DO NOTHING` in the sqlx4k store — so a count of
*statements* is one higher than this count of *row changes*.

Both numbers are asserted by `WriteCountTest`, against that exact scenario, so a change that adds a
write fails a test rather than aging a sentence. The figure this paragraph used to carry — "about 17
writes, 11 of them into the saga table" — was taken once by hand for a scenario nobody wrote down,
and could not be reproduced.

This is the price of recoverability: state is written at every step boundary precisely so that a
process dying between steps never leaves a saga in an unknown position. Each of those writes is
narrower than it was: the payload is written once by the insert and never sent again, so the largest
column in the row is not rewritten — and re-TOASTed — eleven times for a value that never changes.

**And something reads it.** A process that dies mid-pass leaves its saga in `PROCESSING`, one that
dies mid-rollback leaves it in `COMPENSATING`, and `SuspendedPetichSweeper` now re-drives both
through the engine, which resumes from the written position. It is off until you choose
`stuckAfter`, and that number is a formula rather than a taste: there is no lease, so nothing
distinguishes a dead process from a slow one, and it must exceed the longest a healthy pass can
take —

```
stuckAfter > max(phaseTimeoutsMs ∪ compensationTimeoutsMs)
```

— because a saga being worked on slowly by a live instance must not look stranded.

**Two sweepers do not need anything built around them.** Each claims a saga with one write before
touching it, and the row's own optimistic lock decides: on the stranded queue the claim re-stamps the
row and it stops matching the query that found it, and on the expiry queue the claim is the
`PENDING_SIGNATURE → COMPENSATING` transition. **A sweeper that loses the claim skips that saga** — it
does not retry, which is the difference between an arbiter and a race — and reports it through
`onContended`. No lease table, no second query, and nothing for an application to implement.

The stores stamp each row on every write to answer that query, and that stamp is deliberately in no
index: it changes on all eleven writes, so indexing it would make every one of them a non-HOT update
on the busiest table here to serve a query that runs once per poll.

### 📊 Observability

`PetichEngineMetrics` provides optional counters: saga passes, version conflicts, state-write
retries, compensations, waits on the client, and outbox events dropped. A no-op by default, costing
nothing.

Most exist for a question that cannot be answered from outside: **why** did throughput drop. From
outside you see only latency, while a slowdown that looks identical has at least three distinct
causes, each cured differently.

`onDroppedEvents` is the exception, and answers a question nobody thinks to ask. When the repository
is not outbox-aware the events are thrown away, the saga completes, and its state is correct — every
assertion anyone naturally writes about that run passes, and only the consumer at the far end of the
event never runs. Nothing else in the system is different, which is why a counter is the only thing
that can say it happened. A flat non-zero line here is a plain `PetichRepository` that reached a
place needing an outbox-aware one; `requireOutbox` refuses that at construction instead.

Read the counters in the right order. Optimistic retries are the contention signal — zero of them
means sagas are not fighting over rows, whatever else is slow. Saga passes per operation is NOT
that signal: a saga that suspends for a confirmation goes through the engine at least twice with no
contention at all, so the figure sits comfortably above one in a workload where nothing collides.

### 🛠️ Building

```bash
./gradlew build
```

One JVM floor for every module at once — not tidiness but a Gradle requirement: a module built
below the floor cannot depend on one advertising it, so it is all of them or none. The number lives
in `gradle.properties` as `sborka.jvmFloor`, and nowhere else: the shared conventions read it there,
`tools/jvm-floor-audit.py` compares the published bytecode against the same line, and no build script
spells it out.

**Java 21 is a consumer's floor too.** Every published variant declares it as
`org.gradle.jvm.version`, so a project on anything older is refused at resolution, by name, before
it compiles rather than at class loading. It was briefly 25 — not because anything here needs 25,
but because that was the JDK the build ran on, which is the accident a named floor exists to
prevent.

### 📄 License

MIT.
