---
id: research-petich-tracer
title: petich — tracing one saga, and what to draw it on
type: research
status: active
date: 2026-09-24
---

# Research: what one saga did, seen from outside

petich persists the **current position** of a saga and nothing about how it got there. Three review
rounds found their defects by reading code and by test doubles that count calls: six sends of one
receipt, a rollback started by a hung announcement, a business rejection that comes back as `FAILED`
after a crash. None of those was visible in a running system, and nothing in the library would have
made it visible. The first idea was a visual tracer on Compose for Desktop. This document asks what
such a tracer would read, whether the reading is the whole problem, and which surface earns its
cost — with the answers to each question declared before the work starts.

It records **verified facts**, **research questions** with their red and green outcomes, **kill
criteria**, **non-goals**, and the **hypotheses** the work will settle. Anything not verified says
so.

The short version: the surface is the cheapest and least important part. A per-saga event hook is
the work, it has value with no UI at all, and the choice of UI is deferred until the hook has been
read for two weeks.

**Two dates.** The brief was drafted against `56886cc`. Seven items (B-51…B-57) landed after it, and
every fact below was **re-read at `f47828e`** (2026-09-24) before any item was filed. Twelve of the
fourteen claims needed a correction and three were wrong outright; §0 lists what moved, so the
draft's numbers are not quoted from memory later. The draft's item numbers B-51…B-55 were already
taken by that review round — the items here are **B-58…B-63**.

---

## 0. What the re-read at `f47828e` changed

| Draft said | At `f47828e` | Consequence |
|---|---|---|
| `PetichEngineMetrics` has 13 methods | **14** (`onHandlerFailed` from B-52, `onTerminalWriteRefused` from B-54); `onAnnouncementDiscarded` also takes a member key | §1.2 |
| 14 `metrics.` sites in `Petich.kt` | **15** there, plus 3 in `Guarded.kt`; the sweeper has no metrics at all | Risk 1's helper covers 15 sites, not 14 |
| suspension and rollback start never reach metrics | both do — `onSuspend`, `onCompensation` — only without an id | §1.2 |
| `GET /{id}` returns the row | returns `PetichResponse(id, status, …)`, a DTO | §1.3 |
| `petich-core` has three dependencies | **two** main (`kotlinx-serialization-json`, `kotlinx-coroutines-core`) and one test | H2 is "stays at two" |
| the tracer needs a new guard | the guard **exists**: B-52's `guarding` and the `Guarded*` decorators, applied once at construction | RQ1 reuses it |
| events carry a replica label | **nothing in the codebase has one** — "replica" occurs only in comments | the sink stamps it, not the engine (§2, RQ1) |
| the three fixtures are open defects | (a) runs **on purpose** in a test; (b) and (c) are fixed, each with a residual route that still reproduces | fixtures become **pairs** (§1.4), and (b)'s residue is filed as its own defect, [B-59](../backlog/B-59-an-announcement-s-own-timeout-still-rolls-back.md) |

---

## 1. Verified facts

### 1.1 The row is a position, not a history

| Fact | Where verified |
|---|---|
| `Petich` carries 15 fields — `id`, `type`, `currentPhase`, `currentInterceptorIndex`, `status`, `payload`, `enrichedPayload`, `version`, `compensatingFromIndex`, `compensatingTowards`, `resumePayload`, `suspendedUntilEpochMs`, `compensationAttempts`, `chainFingerprint`, `stepRecords`. Each is the latest value; `stepRecords` is a map with one record per member key, not a list | `petich-core/src/commonMain/kotlin/Petich.kt:91-140` |
| The store's `updated_at` is the only **last-written** stamp. It is not a field of `Petich`, is set on insert and on every update, and is read only by `findStuck` (`suspendedUntilEpochMs` is also a time, but a deadline) | `SuspendedPetichSweeper.kt:25-49`; `petich-postgres/src/main/kotlin/PetichTable.kt`, `ExposedPetichRepository.kt`; `petich-sqlx4k-postgres/.../PostgresPetichStore.kt` |
| `WriteCountTest` pins a straight-through six-member saga at 1 insert + 7 updates = **8 saga-table writes** and 3 events; a suspending one at 4 writes up to the suspension and 8 in total | `petich-core/src/commonTest/kotlin/WriteCountTest.kt` |

**Consequence 1.** A tracer cannot be a reader of the saga table. Whatever it shows has to come from
somewhere the engine does not write today, and adding that somewhere must not add a write to the
busiest table in the system.

### 1.2 Metrics are keyed by saga type, and by nothing finer

| Fact | Where verified |
|---|---|
| `PetichEngineMetrics` has 14 methods. None takes a saga id or a timestamp; the KDoc says so on purpose ("Deliberately no saga id"). Three take a member key (`onAnnouncementFailed`, `onAnnouncementDiscarded`) or a phase (`onChainRefused`) | `petich-core/src/commonMain/kotlin/PetichEngineMetrics.kt` |
| The engine calls metrics from 15 sites in `Petich.kt` and 3 in `Guarded.kt`; the saga is in scope at every one | `grep -n "metrics\." Petich.kt Guarded.kt` |
| Rollback start (`onCompensation`) and suspension (`onSuspend`) reach metrics, per type. **Member entry and exit, resume, and each undone step reach nobody** — an undone step only writes the `COMPENSATING` row | `Petich.kt`, `triggerCompensation` and the undo loop |
| The sweeper has no metrics parameter. Claim won/lost and revival go to its callbacks: `onExpired`, `onRevived`, `onContended`, `onNotExpired`, `onUnknownType`, `onWorkerFailure`. Since B-55 `onRevived` means "claimed and handed to the engine" and a changed chain is reported through `onWorkerFailure` before any claim | `petich-core/src/commonMain/kotlin/SuspendedPetichSweeper.kt` |

**Consequence 2.** The 18 call sites are the skeleton of a per-saga event stream; the id is already
on the stack at each of them. What is missing is an interface that accepts it, the events metrics
never had a reason to carry (member entered/left, resume, step undone), and a path from the sweeper,
which has no reporting channel of its own.

### 1.3 The static picture exists; the dynamic one does not

| Fact | Where verified |
|---|---|
| `describeChain(payload, type)` prints one line per phase in enum order, `PHASE: a -> b (check) -> c (announcement)`, globals first — the same `chainFor` the run uses, so it is run order | `Petich.kt`, `describeChain`; `GlobalMemberTest.kt` |
| Nothing prints the path one saga took through it | — |
| `petich-ktor` mounts `POST ""` (create), `POST /{id}/resume` and `GET /{id}`, which returns a DTO of id and status — not the row. No listing, no history | `petich-ktor/src/commonMain/kotlin/PetichRouting.kt`, `CreatePetichRequest.kt` |

**Consequence 3.** "Definition with the actual path drawn over it" is the one picture a general
tracing backend cannot draw, because the definition is petich's and the backend has never seen it.
Everything else a tracer would show — a waterfall of members with durations, retries, and a
backwards walk — is what tracing backends draw for a living.

### 1.4 The fixtures, as they stand at `f47828e`

The draft's fixtures were three defects. Two are fixed, so a fixture that needs the defect to be
present would be a fixture of nothing. Each is therefore a **pair**: two runs the trace must tell
apart, both reproducible today without editing the engine. A tracer that renders both halves of a
pair the same way has failed the fixture, whatever it renders.

| Fixture | The pair | Where it runs today |
|---|---|---|
| **(a) six sends** | an unnamed announcement re-sent on every retried pass, **against** a named one sent once | `AnnouncementRunsAgainTest.kt` — "an unnamed announcement sends the receipt twice…", kept failing on purpose (B-50 is a rule, not a fix), and "a named announcement sends it once" |
| **(b) the hung announcement** | an announcement that hangs past its own deadline and ends `COMPLETED` with the handler called, **against** one whose body's own `withTimeout` escapes and still rolls the saga back | `ForeignCodeCannotDecideTest.kt` "an announcement that hangs past its deadline…" for the first half; the second half is **reasoned, not tested** — `withTimeoutOrNull` rethrows a timeout that is not its own, `announce` rethrows `CancellationException`, and the phase loop's `TimeoutCancellationException` branch calls `triggerCompensation`. Filed as [B-59](../backlog/B-59-an-announcement-s-own-timeout-still-rolls-back.md) |
| **(c) the lost `REJECTED`** | an interrupted refusal resumed from a row with `compensatingTowards = REJECTED`, ending `REJECTED`, **against** the same row with the field `null` — a row written before the column, or by an older build in a rolling deploy — ending `FAILED` through the `?: FAILED` fallback | `RollbackKnowsItsEndingTest.kt` "a refusal whose rollback was interrupted is finished as a refusal"; the `null` half is one `copy` away |

**Consequence 4.** These three pairs are the acceptance of the hook. A tracing form that does not make
each pair distinguishable to a person who has not read the code is not solving the problem the
tracer was proposed for. Pair (b)'s second half disappears when B-59 lands; from then on it is
produced by the same mutation that item's test is checked by, which is how every review item since
B-50 was checked anyway.

### 1.5 The name "chronik" is taken, and no tracing library is a dependency

| Fact | Where verified |
|---|---|
| `petich-chronik` bridges timers into saga resumes (`SagaTimerSink.deliver` → `engine.process(… TimerFired …)`); it is not a chronicle of saga events | `petich-chronik/src/commonMain/kotlin/SagaTimerSink.kt` |
| No OpenTelemetry, otel or tracy coordinate anywhere in the build | `gradle/libs.versions.toml`, every `build.gradle.kts` |
| Nothing named tracer, listener, observer, span or hook exists; the reporting channels are metrics, the two failure handlers, and the sweeper's and the timer sink's callbacks | `grep -ri "tracer\|listener\|onEvent\|PetichEvent"` |

**Consequence 5.** The hook gets a name that does not collide (`PetichTracer`), and whatever exports
it to a tracing backend lives in its own module (`petich-trace-otel`) so that `petich-core` stays at
two dependencies.

---

## 2. Research questions, with outcomes declared before the work

### RQ1. Can the engine report a per-saga event stream at zero cost to the row?

The hook: `PetichTracer` with a `NoOp` default, a sealed `PetichTraceEvent` carrying saga id, type,
member key, phase, attempt and a `PetichClock` timestamp, delivered best-effort and outside any
transaction — the discipline `PetichEngineMetrics` already follows. It is a **constructor
parameter**, because B-57's rule puts "anything the engine calls into or wraps in a guard" there,
and it is wrapped once by a `GuardedTracer` next to `GuardedMetrics`.

**No replica label in the event.** Nothing in the engine knows which replica it is, and adding that
knowledge to trace one field is backwards: a tracer instance is per process, so the sink stamps its
own label. The sweeper takes the same tracer, since claims happen there and not in the engine.

- **Green.** Every event below is emitted with no change to `PetichRepository`, the conformance
  corpus, or the counts in `WriteCountTest`: pass started / retried; member entered / proceeded /
  rejected / failed / timed out / suspended / re-suspended; announcement failed; claim won / lost on
  either queue; rollback started (from index, terminal status); step undone; rollback gave up /
  exhausted; chain refused; chain unavailable. A tracer that throws changes nothing about the saga.
  Each fixture pair of §1.4 produces two traces that differ in the events that name the difference.
- **Red.** An event turns out to need a write, or a store query, to be correct — then the tracer is a
  store-side feature, this brief stops, and the item is refiled against the storage contract.

### RQ2. Do plain spans show the three fixtures without a custom surface?

A `petich-trace-otel` module maps events to OpenTelemetry spans: saga as trace, member as span,
retry as a repeated span, rollback as a span whose children are the undone steps, claim outcomes as
events on the sweeper span. Rendered in a stock Jaeger/Tempo UI from a captured export.

- **Green.** A person shown only the waterfall can answer, for each pair: *how many times did the
  receipt member run and why* (a), *what started the rollback, if anything* (b), *what status did the
  pass intend and what did the saga end in* (c). Three of three.
- **Red.** Any one of the three needs the definition to be understood — which member should have run
  next, which phase the index pointed into — and the waterfall cannot say it. That is the argument
  for a surface that knows the definition, and only for that.

### RQ3. Is the definition-with-path overlay worth a page, and no more than a page?

Only asked if RQ2 is red on at least one fixture. The candidate: a route in `petich-ktor` that
renders `describeChain` as lanes and draws one saga's events over it as inline SVG — served by the
service itself, opened in a browser, no install.

- **Green.** The overlay shows the fixture(s) RQ2 could not, from `describeChain` plus the RQ1
  events, with no state beyond the page's own query and no JavaScript beyond what a static SVG needs.
  Recorded chain versus current chain (B-44) is drawn as a diff on the same picture.
- **Red.** The page needs live updates, interaction, or its own storage to be useful — then it is an
  application, and the surface question is reopened as RQ4 rather than grown in place.

**Unverified and to be settled there:** the page needs the events of one saga after the fact, and
RQ1's hook is best-effort and in memory by contract. Where the page reads them from — a bounded
in-process ring the sink keeps, or nothing, with the page drawing only a saga run while it is open —
is part of RQ3's "no state" test, not a detail of it.

### RQ4. Where would Compose for Desktop pay for itself?

Only asked if RQ3 is red. The one mode with a plausible answer is **in-process**: a test runs a
definition under fault injection with a controllable `PetichClock`, and a window shows the engine
react — the test's own assertions are the data, and the JVM-only limit is irrelevant because tests
are JVM. (`petich-core` ships no test clock; the suites write `PetichClock { now }` lambdas, and
`petich-conformance` has `MovableClock`.)

- **Green.** A fault-injection scenario (kill inside a member, contended claim, TTL expiry on a
  cascade) is readable live in under a minute by someone who has not seen the engine, and the
  scenario is also an ordinary test that passes headless.
- **Red.** The visualizer needs a data path the tests do not already have, or it only shows what the
  headless assertion already prints — then it is a demo, and a demo is not a module.

---

## 3. Kill criteria

Any one of these ends the line of work at the stage it is reached, and the brief is amended to say
so rather than the item left open.

1. **RQ1 red.** The hook costs a write or a store change. Stop; refile.
2. **Two weeks of silence.** The hook ships with a logging sink in konekt and shashki and, after two
   weeks of ordinary use, no defect or question was answered by reading a trace that a counting test
   double had not already answered. Then the tracer is the wrong tool for this library's problems,
   and RQ2–RQ4 are not asked.
3. **RQ2 green on three of three.** The waterfall is enough; the overlay and every surface after it
   are dropped, and the OTel module is the deliverable.
4. **The overlay needs state.** RQ3 red ends the `petich-ktor` page; it does not automatically open
   RQ4 — RQ4 is asked only if the in-process mode has a scenario nobody can debug headless.
5. **A desktop artefact.** Anything that has to be installed, updated, or distributed separately from
   the service or the test suite is out. A Compose window that a test opens is in; a Compose app is
   not.

---

## 4. Non-goals

- **A durable saga history.** An append-only event table would be an audit log and an event-sourcing
  decision, with its own retention, schema, and write cost. The hook here is best-effort and
  in-memory by contract; if a durable history is ever wanted it is a separate brief with a separate
  cost section.
- **Replacing `PetichEngineMetrics`.** Counters stay counters. The tracer is per saga; metrics are
  per type; both exist and neither is derived from the other.
- **A dashboard.** Nothing here lists sagas, aggregates them, or alerts. `GET /{id}` and the
  sweeper's counters already answer "which" and "how many".
- **Replay or time travel.** The engine's resume model is not a deterministic replay and this does
  not make it one.
- **Native support for any surface.** The hook is `commonMain` and runs on `linuxX64`; a page or a
  window does not have to.
- **Fixing defects through the tracer.** A defect a fixture exposes gets its own item with its own
  test — [B-59](../backlog/B-59-an-announcement-s-own-timeout-still-rolls-back.md) is the first — and
  the tracer records it; it is not a substitute for the test.

---

## 5. Hypotheses, to be settled where stated

- **H1. Best-effort delivery is sufficient.** A tracer that misses events when a process dies loses
  exactly the events of the dying pass, and the sweeper's revival is the event that says a pass died.
  Settled in RQ1 by the fixture "kill inside a member": the trace must read as *entered, [nothing],
  claimed and revived by the sweeper* rather than as a member that never ran.
- **H2. `petich-core` needs no new dependency.** The event types are plain Kotlin; the OTel mapping is
  in its own module. Settled by `petich-core/build.gradle.kts` still naming two main dependencies
  after RQ1.
- **H3. The overlay is one SVG.** `describeChain` gives the lanes; the RQ1 events give the path; the
  recorded fingerprint versus the current one gives the diff. Settled in RQ3 by line count and by
  the absence of a script tag.
- **H4. The order of surfaces is waterfall → page → window, and each is only opened by the previous
  one's red.** Settled by the kill criteria above; a surface opened without its predecessor's red is
  a deviation and is written up as one.
- **H5. The consumers produce traffic worth reading.** Kill criterion 2 assumes two weeks of
  *ordinary use* of konekt and shashki. Both are reference services of this portfolio; whether
  anything runs sagas through them in two weeks is not verified. Settled at the end of
  [B-60](../backlog/B-60-a-logging-sink-in-konekt-and-shashki.md) by counting the sagas the sink
  logged — a silence over zero sagas is not the silence the criterion means, and says so rather than
  counting as it.

---

## 6. Risks

**Risk 1. The hook becomes a second metrics API and the two drift.** Mitigation: the metric sites
call the tracer *and* metrics from one helper, so an event added to one cannot be forgotten in the
other; a test enumerates the sealed event type and asserts each variant has at least one emitting
site.

**Risk 2. A slow tracer slows the saga.** Mitigation: the interface is synchronous and the contract
says the implementation must return immediately; the OTel module buffers. A test with a tracer that
blocks asserts the contract is visible — it fails loudly rather than in production.

**Risk 3. Trace events leak what the row does not.** A reason string already reached the outbox once
(B-57). Mitigation: events carry keys, phases, indices and outcomes; free-text reasons are truncated
and flagged, and payloads are never included.

**Risk 4. The two consumers are also the only users.** Kill criterion 2 and H5 exist because of
this.

---

## 7. What happens next

Order of items, each gated on the previous one's outcome:

1. **[B-58](../backlog/B-58-a-per-saga-event-hook.md) — the hook.** `PetichTracer`,
   `PetichTraceEvent`, `NoOp`, the guard, the sweeper's share, the enumeration test. AC: each fixture
   pair of §1.4 produces two traces that name the difference. Settles RQ1, H1, H2.
2. **[B-59](../backlog/B-59-an-announcement-s-own-timeout-still-rolls-back.md) — the residue of
   B-52.** Not a tracer item; found by this re-read and filed so the fixture does not become the only
   place the defect is written down.
3. **[B-60](../backlog/B-60-a-logging-sink-in-konekt-and-shashki.md) — a logging sink in konekt and
   shashki.** Starts the two-week clock of kill criterion 2; settles H5 at its end.
4. **[B-61](../backlog/B-61-petich-trace-otel.md) — `petich-trace-otel`.** Opened only if kill
   criterion 2 did not fire. AC: RQ2's waterfall test against captured exports of the three pairs.
5. **[B-62](../backlog/B-62-the-petich-ktor-trace-page.md) — the `petich-ktor` page.** Opened only by
   an RQ2 red; AC is RQ3's green.
6. **[B-63](../backlog/B-63-the-in-process-window.md) — the in-process window.** Opened only by an
   RQ3 red; AC is RQ4's green.

Items 4–6 are filed as `question` rather than `open` until their gate is passed, so the index shows
what is decided and what is waiting on evidence.
