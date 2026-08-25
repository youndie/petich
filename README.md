# petich

[![kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![petich-core](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/petich-core?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/petich-core)
[![petich-ktor](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/petich-ktor?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/petich-ktor)
[![petich-postgres](https://reposilite.kotlin.website/api/badge/latest/snapshots/io/github/youndie/petich-postgres?name=snapshots&color=40c14a&prefix=v)](https://reposilite.kotlin.website/#/snapshots/io/github/youndie/petich-postgres)

**a distributed saga engine for Kotlin** — a multi-step operation is described as a chain of
interceptors; the engine walks it through phases and, when any step fails, undoes exactly what had
already happened

> 🔁 one interceptor → one step forward and one step back

Built around a single question: what is left in the system if you die halfway.

### 🤔 What it solves

An operation that spans several services is not one database write. Reserve capacity, claim a quota,
apply the change, hand it to a downstream system, notify. Any step can refuse, and some are already
irreversible by then. An ordinary `try/catch` does not help — what needs undoing is not a database
transaction but actions already performed, in reverse order, and only those that really happened.

The engine takes on exactly that:

- **order and phases** — `ENRICHMENT → VALIDATION → AUTHORIZATION → EXECUTION → POST_PROCESSING`,
  with steps inside a phase ordered by priority;
- **compensation** — a failure at step N calls `compensate()` on steps N−1 … 1, in reverse;
- **waiting for a human** — a saga can pause for a confirmation and continue on a later HTTP
  request, holding neither a thread nor a database connection;
- **a deadline on that wait** — a suspended saga nobody came back to is rolled back by a background
  sweeper instead of living forever while holding resources it already claimed;
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

| module | what for | depends on |
| --- | --- | --- |
| `petich-core` | the engine: sagas, interceptors, phases, compensation, suspend/resume, TTL | — |
| `petich-ktor` | REST endpoints for creating and resuming a saga | `petich-core` |
| `petich-postgres` | storage on Exposed | core, outbox, idempotency, scheduler |
| `petich-outbox-core` | at-least-once event delivery with backoff and dead lettering | — |
| `petich-idempotency` | protection against a key reused with a DIFFERENT request | — |
| `petich-scheduler` | a saga on a schedule, starting with no HTTP initiator | — |

Three modules deliberately do not depend on the core. `petich-outbox-core` knows only about a row —
"id/type/payload, deliver at least once"; `petich-scheduler` only about "it is time" and "here is the
payload"; `petich-idempotency` only about "this key already arrived with a different fingerprint".
Each is usable on its own, and that is not an accident but the condition under which they do not turn
into part of somebody's feature.

### 🔌 Installation

```kotlin
repositories {
    maven("https://reposilite.kotlin.website/snapshots")
}

dependencies {
    implementation("io.github.youndie:petich-core:0.1.0.2")
    implementation("io.github.youndie:petich-ktor:0.1.0.2")
    implementation("io.github.youndie:petich-postgres:0.1.0.2")
}
```

`petich-postgres` deliberately ships no driver and no connection pool: it works with an Exposed
`Database` handed to it and does not know which DBMS sits underneath. Choosing a driver is the
application's decision.

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

One saga of six interceptors is about 17 database writes, 11 of them into the saga table itself:
`1 INSERT + one UPDATE per interceptor + 1 final`, plus the suspend/resume machinery. A saga of four
interceptors comes to 9 writes. The numbers were taken through `pg_stat_user_tables` and do not
depend on the hardware.

This is the price of recoverability: state is written at every step boundary precisely so that a
process dying between steps never leaves a saga in an unknown position.

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

Java 25 for every module at once — not tidiness but a Gradle requirement: it tags variants with the
`org.gradle.jvm.version` attribute and refuses to build a module on 21 against a dependency on 25.

### 📄 License

MIT.
