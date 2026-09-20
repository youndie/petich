# petich

[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![maven central](https://img.shields.io/maven-central/v/io.github.youndie.petich/petich-core?label=maven%20central&color=40c14a)](https://central.sonatype.com/namespace/io.github.youndie.petich)
[![snapshots](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/petich/petich-core?name=snapshots&color=blue&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/petich/petich-core)

**a distributed saga engine for Kotlin** — a multi-step operation is declared as a definition: the
members it runs, in the order they run. The engine walks it and, when any member fails, undoes the
ones it recorded as done

> 🔁 one member → one step forward and one step back

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
  with the members of one phase running in the order the definition declares them, rather than in
  whatever order a dependency container assembled them in — a member carries no `priority` and no
  phase of its own, the definition places it;
- **compensation** — a failure at step N calls `compensate()` on steps N … 1, in reverse, step N
  included: the engine never learned whether that one's effect landed, so it undoes it too — see
  **What it asks of a member**;
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
| `petich-core` | the engine: definitions, members, phases, compensation, suspend/resume, TTL | jvm, linuxX64 | — |
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

<!-- readme-probe: skip -->
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

**This page describes `main`, and `0.2.0` is the last release.** Everything below the module table —
`petichDefinition`, the three member types, `step_records`, `ctx.idempotencyKey` — arrived after it
and is on snapshots only. Copying the coordinates above and the examples below together will not
compile: take a snapshot to follow the page, or read the README at the tag you are pinning to.

**On Kotlin/Native** the coordinates are the same; take `petich-sqlx4k-postgres` instead of
`petich-postgres`, and bring your own sqlx4k driver:

<!-- readme-probe: skip -->
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
| `step_records` | 0.4.0 | `ALTER TABLE petiches ADD COLUMN IF NOT EXISTS step_records TEXT NOT NULL DEFAULT '{}';` |

`current_interceptor_index` still says *interceptor*, and the model it was named for is gone. The
column keeps the name on purpose: renaming it is a migration every consumer has to run, and a table
rewrite on the busiest table in the system, to buy a word. It is the saga's position in the chain its
definition declares.

**These statements are `petichPostgresSchema()`'s spelling, and the two stores differ on one point.**
The native store spells every JSON-shaped column `TEXT`; `PetichTable` declares the same ones with
Exposed's `json()`, which Postgres creates as `json`. A consumer on `petich-postgres` should write
`JSON NOT NULL DEFAULT '{}'::json` where the `step_records` row above says `TEXT NOT NULL DEFAULT
'{}'`, so that the column matches what its own `PetichTable` declares and its schema tooling proposes
no further migration. `payload` and `enriched_payload` are the same difference, from 0.1.0.

**Either spelling works, and that is measured rather than assumed.** `NativeSchemaCompatibilityTest`
runs the Exposed store's entire conformance corpus — step records included — against a database built
by `petichPostgresSchema()`, and it is green: Exposed writes and reads a `json()` column against a
`text` one without complaint. The difference costs nothing at runtime; what it costs is a schema that
disagrees with its own table declaration, which a consumer's tooling will keep offering to fix — which
is how it was found. The two are not being unified: changing a shipped column's type rewrites the
busiest table in a consumer's system to buy tidiness.

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

A saga is a **definition**: the members it runs, in the order they run.

```kotlin
val order = petichDefinition<OrderPayload>("order") {
    validate("in-stock", InStock(stock))
    authorize("within-limits", WithinLimits(limits))
    step("hold-funds", HoldFunds(payments))
    step("reserve", ReserveStock(stock))
    announce("confirmed", AnnounceOrder(events))
}
```

**Three kinds of member, and the verb is the type.** `validate` and `authorize` take checks, which
decide and have nothing to undo. `step` takes a member that acts and can be undone. `announce` takes
a member that says what happened and **cannot take it back** — no `compensate`, no way to refuse or
fail the saga, and an exception it throws is counted rather than rolled back. That is the whole
meaning of a phase here, and it is the type rather than a convention: a reader can stop at the first
`step` and know that everything above it only decided, and read the last line knowing it cannot undo
the rest. `hold-funds` takes money, so it is a step — in payments
*authorization* is the hold itself, but in these phases AUTHORIZATION asks whether it is allowed.
Putting it in `step` also gives the rollback the order it should have: the reservation is released
before the hold is.

A member that acts is a `PetichStep`: what to do, and how to undo it.

```kotlin
class ReserveStock(private val stock: StockRepository) : PetichStep<OrderPayload> {
    override suspend fun execute(ctx: PetichStepContext, payload: OrderPayload) {
        stock.reserve(payload.sku, payload.quantity)
    }

    override suspend fun compensate(ctx: PetichStepContext, payload: OrderPayload) {
        stock.release(payload.sku, payload.quantity)
    }
}
```

A member that only decides is a `PetichCheck`, and **has no `compensate` at all** — which is the
point of the split. Of the 27 compensations written against the older model, half did nothing; an
empty `compensate` said either "this member has nothing to undo" or "somebody has not finished it
yet", and no reader could tell which.

```kotlin
class InStock(private val stock: StockRepository) : PetichCheck<OrderPayload> {
    override suspend fun check(ctx: PetichCheckContext, payload: OrderPayload) {
        if (!stock.has(payload.sku, payload.quantity)) ctx.reject("out of stock")
    }
}
```

A member that announces is a `PetichAnnouncement`. By the time it runs the stock is reserved and the
money is captured, so it is given no way to undo any of that:

```kotlin
class AnnounceOrder(private val events: OrderEvents) : PetichAnnouncement<OrderPayload> {
    override suspend fun announce(ctx: PetichAnnouncementContext, payload: OrderPayload) {
        ctx.emit(events.confirmed(ctx.petich.id, payload))
    }
}
```

There is no `compensate` to write and no `ctx.fail` to call. If the body throws — a relay that is
down, an encoder that chokes — the saga still completes and the failure is counted through
`PetichEngineMetrics.onAnnouncementFailed`; rolling a completed order back because a notification did
not go is the answer this type exists to refuse. What the member asked to have committed before it
threw still rides with the saga.

A member that needs confirmation suspends — the saga stops and waits for a separate `resume` call:

<!-- readme-probe: statements -->
```kotlin
ctx.suspendFor("CONFIRM", ttl = 5.minutes)
```

`ttl` is this particular member's deadline. If it passes, the sweeper rolls the saga back exactly as
a refusal would: typing a one-time code and approving a long-running request live on different time
scales, and the member knows that, not the engine.

`ctx.resuspendFor(...)` is the other shape: it waits for another answer **at this member** rather
than for the one that moves past it — a cascade offering a ride to one driver after another, where
the member *is* the cascade.

To test a member on its own — ask it the question the engine is about to ask, with no saga, no engine
and no database — hand it a `PetichMemberProbe` and read back what it asked for:

<!-- readme-probe: statements -->
```kotlin
val probe = PetichMemberProbe(saga, stepKey = "capture")
CaptureStep(payments).compensate(probe, payload)

assertEquals(emptyList(), payments.refunded)   // nothing was recorded, so nothing is given back
```

It is the context the engine itself runs members through, not a double of it, so a test asserting
against it is asserting against what production does.

### ⚠️ What it asks of a member

Five rules. They are the engine's side of the bargain stated from the other end, and a member that
breaks them fails in ways that look like storage faults.

**A member's body must be idempotent — a step's `execute`, a check's `check`, an announcement's
`announce`.** The engine calls it, and only then writes the new position, so a call that already
happened can happen again. This is not the rare case of a process dying in between: an optimistic-lock
conflict on that write makes the engine re-read the row and run the same member a second time, on a
healthy instance, under nothing worse than two requests touching one saga.

**Including the announcement**, which is the one that surprises people, because a member with no
`compensate` reads as a member that runs once. It is not one: the position advances *after* its body
returns, exactly as a step's does, and a process that dies inside `announce` leaves the row pointing
at it for `SuspendedPetichSweeper` to re-drive. An announcement that only calls `ctx.emit` is safe by
the outbox key; **an announcement that sends a mail, or calls anything outside the process, will send
it twice** unless it says otherwise.

A member whose effect is a remote call wants its own idempotency key, and the money-shaped ones want
the remote side to honour it — which is the rule below.

**`compensate()` must be idempotent too**, for the same reason mirrored: the rollback commits how
far it has got *after* calling the step, so an interrupted rollback re-compensates the step it was
on.

**`compensate()` may be called for a member that did not happen.** When `execute()` throws or times
out, the engine cannot tell an effect that reached the far side from a call that never landed — it
only ever learns that the step did not report success — so it rolls that step back as well.
`release` therefore has to tolerate arriving without its `reserve`, and a compensation that instead
assumes its own step committed will undo something that was never done.

**What that guard cannot be is the step's own record.** "Undo what the record says happened, return
quietly when there is none" is the advice this page used to give, and it is blind in exactly the case
the rule above describes. The step calls the far side, the far side commits, the answer is lost, the
timeout fires — and the member never reached `ctx.record(...)`, because the identifier it would have
written comes back *in* the answer that was lost. The record is absent, the rollback does nothing,
and the reservation is held for ever. No write ordering fixes it: at the moment of the timeout there
is nothing to write.

`ctx.record` is for what a rollback **needs** — the reservation id, the hold id — and it is exactly
right for that. It cannot be evidence that the effect happened, because that is a fact only the far
side has.

Two outcomes are NOT this case, and both keep the old starting point: a member that calls `ctx.fail`
reported its outcome and is not undone by the engine, and an expired suspension rolls back from the
member that suspended, which committed.

**A member whose effect is remote must name that effect before making the call — a step or an
announcement alike.** `ctx.idempotencyKey` is that name — `"<saga id>:<member key>"`, the same string on the forward pass, on a re-run after a
version conflict, and inside the compensation:

<!-- readme-probe: members -->
```kotlin
override suspend fun execute(ctx: PetichStepContext, payload: OrderPayload) {
    stock.reserve(ctx.idempotencyKey, payload.sku, payload.quantity)
}

override suspend fun compensate(ctx: PetichStepContext, payload: OrderPayload) {
    stock.releaseByKey(ctx.idempotencyKey)   // a no-op when there is nothing under that name
}
```

It answers both of the first two rules at once — a re-run reserves under a name the far side has
already seen, and a rollback cancels by a name it chose itself rather than by evidence it may never
have received. The engine hands it over rather than leaving you to build it, because the two sides
have to spell it **identically**, and a string spelled twice is a string spelled differently once. It
is derived from values the row already carries, so it costs no storage and the write budget below is
unchanged.

**The key is issued per member, not per call**, and a member that makes more than one call has to say
which. `ctx.resuspendFor(...)` describes exactly such a member — a cascade asking one candidate after
another, where the member *is* the cascade — and handed the plain key on every attempt, the second
call arrives under the first call's name. A far side that deduplicates answers with the first call's
result, and the refusal is silent. `ctx.idempotencyKey(discriminator)` is the discriminated form:

<!-- readme-probe: statements -->
```kotlin
board.offer(ctx.idempotencyKey(driverId), payload.rideId, driverId)
```

The discriminator has to be **derivable again inside the compensation** — the candidate's id, the
attempt number the member already keeps in its record or enriched payload — because a member that
issued several sub-keys owes cancelling **all** of them, not the last. petich cannot do that part:
which discriminators were used is the member's own knowledge, and keeping them is the price of acting
more than once.

**A name is not always an address, and the rule above quietly assumed it was.** "Cancel whatever is
under this key" needs a far side that can be *addressed* by the caller's name. Two kinds exist and
they want different code:

- **it cancels by your name.** `releaseByKey(key)` as written above — the compensation says the name
  and is done, and a no-op when nothing is under it is exactly right.
- **it only deduplicates.** The common shape: the key is a token with a lifetime, replaying the same
  request inside the window returns the original response, and nothing can be cancelled by it. Then
  the compensation **replays** `execute`'s request under the same key, reads the id out of the answer
  it gets back, and cancels by that id:

  <!-- readme-probe: members -->
  ```kotlin
  override suspend fun compensate(ctx: PetichStepContext, payload: OrderPayload) {
      // The replay is the point: if the first call landed, this returns ITS answer and no second
      // hold is taken; if it never landed, this creates one and the line below removes it. The net
      // is zero either way, which is what the record could not tell us.
      val hold = payments.hold(ctx.idempotencyKey, payload.paymentMethodId, payload.amount)
      payments.release(hold.id)
  }
  ```

  It costs one extra call on the rollback path, and it is the only form that works on a far side
  which will not take a name for an answer.

That second form has a precondition that is arithmetic rather than principle, and belongs beside the
`stuckAfter` formula below: **the far side must retain the key for longer than a rollback can take.**
The ambiguous case only arises on the pass that ran `execute`, and a rollback of that pass stretches
over at most

```
keyRetention > maxCompensationAttempts × stuckAfter
```

— because each attempt waits a full sweep before the next. Past that window the replay is not a
replay: it is a second effect, taken and then released, and the "net zero" argument stops holding.

Where a far side does neither — no name and no replay — an effect that cannot be named cannot be
reliably undone. petich does not pretend otherwise: it will still call `compensate`, and what that
member can do is bounded by the integration rather than by this engine. Not to be confused with
`petich-idempotency`, which is about an inbound request key arriving twice with different parameters.

**A saga remembers which steps it has run, and refuses to resume against a different chain.** Its
position is an index into the chain its definition declares — reassembled on every pass — so a deploy
that adds, removes or reorders a member in the same or an earlier phase would otherwise re-point
every suspended saga at a different member, and the rollback with it. Each write records a
fingerprint of the members already run; a resume that cannot reproduce it stops with a message naming
both, and runs nothing.

The fingerprint covers that prefix and not the whole chain, so appending a step is an ordinary
release. The column is nullable and a null is never refused, so the upgrade that introduces the guard
does not stop the sagas it cannot yet protect — those keep the old behaviour, including its silence.
`PetichEngine.describeChain(payload, type)` prints the resolved order for a saga type: log it at
startup, or snapshot it in a test, and a chain that moved shows up in a diff instead of in a saga.
The **key** each member is declared under is what that order and that fingerprint are made of, and a
member reads its own through `ctx.stepKey` — which is what to name a span or a log line after, since
it is the same string going forward and inside the member's own rollback.

**A refused saga is left exactly as it was, and that is the recovery rather than an omission.** The
engine writes nothing on a refusal — the row keeps its status, its position and its deadline — so
the saga resumes and finishes the moment a process with the matching chain reaches it again. The
condition is a disagreement between two deployed versions, not damage to the saga.

It has a cost while it lasts: the saga keeps whatever it held, and the row reads what a healthy one
reads. `PetichEngineMetrics.onChainRefused` is the signal — it fires on **every** pass over such a
saga, deliberately, because the mismatch is not an event that happened once and a counter that went
quiet after the first sweep would read as "resolved". Read the rate: non-zero means sagas are being
refused right now.

The remedy is a deploy, not a repair:

1. **Roll the deploy back.** The old chain reproduces the recorded fingerprint and every refused saga
   resumes where it stopped.
2. **Let the sagas in flight finish**, or expire — watch `onChainRefused` fall to zero. That is what
   says it is safe to go again, and it is why a *terminal* status was refused here: marking these
   sagas dead would be unremovable, and the situation is recoverable.
3. **Roll forward.** Members added at the end of a phase never cause this; it is insertion, removal
   and reordering *before* a saga's position that does.

A release that cannot wait can avoid the whole case: append the new member rather than inserting it,
or run the old chain beside the new one until the sagas started under it are done.

**A `compensate()` that throws stops the rollback below it.** The members under the one that threw are
not undone, and the saga stays `COMPENSATING`. That is retried — the whole rollback, not just the
step — up to `maxCompensationAttempts` separate passes, counted on the saga itself so a restart does
not reset them; at the bound the saga becomes `COMPENSATION_FAILED`, which is terminal and means
"undone in part, and nothing will try again". `CompensationFailureHandler.exhausted` is asked for
events to commit in that same transaction, so the announcement cannot be lost separately from the
status, and `PetichEngineConfig(requireCompensationHandler = true)` refuses at construction to build
an engine whose compensation failures go nowhere. This is the one state the engine cannot leave on
its own, and `SuspendedPetichSweeper` re-drives a saga abandoned mid-rollback (see the Cost
section).

**Both ways of refusing a saga undo what ran.** They differ in the name the saga ends under, not in
whether the work comes back:

| the member calls | who may call it | what the engine does | the saga ends as |
| --- | --- | --- | --- |
| `ctx.reject(reason)` | a check or a step | members N−1 … 1 are compensated in reverse | `REJECTED` — a business refusal |
| `ctx.fail(reason)` | a step only | the same | `FAILED` — a fault |

`reject` is on the context a check and a step share, because refusing is what a check is for and a
step may refuse too. `fail` is the step's alone: a fault in something that has nothing to undo is an
exception, and the engine already turns one of those into a rollback. An announcement has neither.

Pick by what the client should be told, which is the question a member can answer about itself. A
refusal used to compensate nothing, which was right for a validation refusing before anything had
happened and silent theft after a member had touched the outside world — and telling those apart
needs to know whether an *earlier* member had an effect, which is knowledge about somebody else's
members. The engine has it; the member does not.

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
and one that completes it. Events a member hands over ride along inside those writes rather than
adding any — three members emitting one event each make three rows in the outbox and no extra write
to the saga.

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
column in the row is not rewritten — and re-TOASTed — on all eight writes for a value that never
changes.

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

It is also the second half of the retention inequality in **What it asks of a member**: a far side
that only deduplicates has to keep an idempotency key for longer than
`maxCompensationAttempts × stuckAfter`, so raising `stuckAfter` lengthens what you are asking of
somebody else's system as well as of this one.

**Two sweepers do not need anything built around them.** Each claims a saga with one write before
touching it, and the row's own optimistic lock decides: on the stranded queue the claim re-stamps the
row and it stops matching the query that found it, and on the expiry queue the claim is the
`PENDING_SIGNATURE → COMPENSATING` transition. **A sweeper that loses the claim skips that saga** — it
does not retry, which is the difference between an arbiter and a race — and reports it through
`onContended`. No lease table, no second query, and nothing for an application to implement.

The stores stamp each row on every write to answer that query, and that stamp is deliberately in no
index: it changes on all eight writes, so indexing it would make every one of them a non-HOT update
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
